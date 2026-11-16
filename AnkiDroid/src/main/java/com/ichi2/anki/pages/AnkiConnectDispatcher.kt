// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · AnkiConnect-compatible local API (port of
// the foosoft/anki-connect desktop add-on), 2026

package com.ichi2.anki.pages

import android.util.Base64
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject

/**
 * AnkiConnect-compatible JSON actions mapped onto libanki. The dispatcher is
 * pure suspend logic so the Robolectric gate can drive it without sockets;
 * [AnkiConnectServer] wraps it in HTTP. Only 127.0.0.1 can reach the server —
 * this never exposes the collection to the LAN.
 *
 * The action set mirrors the desktop (anki-qt) implementation: a practical
 * subset of upstream AnkiConnect, unknown actions answered with the upstream
 * "unsupported action" error.
 */
object AnkiConnectDispatcher {
    const val VERSION = 6

    /** Dispatch one action; the return value is a JSON-ready object/array/primitive. */
    suspend fun dispatch(
        action: String,
        params: JSONObject,
    ): Any =
        when (action) {
            "version" -> VERSION
            "requestPermission" ->
                JSONObject()
                    .put("permission", "granted")
                    .put("requireApiKey", false)
                    .put("version", VERSION)
            "sync" -> throw IllegalArgumentException("sync is not supported by Anki Plus")
            else -> dispatchCollectionAction(action, params)
        }

    /** Actions that need the collection; they open it lazily through withCol. */
    private suspend fun dispatchCollectionAction(
        action: String,
        params: JSONObject,
    ): Any =
        withCol {
            when (action) {
                "deckNames" -> JSONArray(decks.allNamesAndIds().map { it.name })
                "deckNamesAndIds" ->
                    JSONArray().apply {
                        decks.allNamesAndIds().forEach {
                            put(JSONObject().put("name", it.name).put("id", it.id))
                        }
                    }
                // allNamesAndIds() is a Sequence here; JSONArray needs a Collection
                "modelNames" -> JSONArray(notetypes.allNamesAndIds().map { it.name }.toList())
                "modelNamesAndIds" ->
                    JSONArray().apply {
                        notetypes.allNamesAndIds().forEach {
                            put(JSONObject().put("name", it.name).put("id", it.id))
                        }
                    }
                "modelFieldNames", "modelFields" -> {
                    val modelName = params.getString("modelName")
                    val notetype =
                        notetypes.byName(modelName)
                            ?: throw IllegalArgumentException("model was not found: $modelName")
                    JSONArray(notetype.fieldsNames)
                }
                "findCards" -> JSONArray(findCards(params.getString("query")))
                "findNotes" -> JSONArray(findNotes(params.getString("query")))
                "cardsInfo" -> cardsInfo(requireIdList(params, "cards"))
                "notesInfo" -> notesInfo(requireIdList(params, "notes"))
                "addNote" -> addOneNote(params.getJSONObject("note"))
                "addNotes" ->
                    JSONArray().apply {
                        val notes = params.getJSONArray("notes")
                        for (i in 0 until notes.length()) {
                            val noteId = runCatching { addOneNote(notes.getJSONObject(i)) }
                            put(noteId.getOrNull() ?: JSONObject.NULL)
                        }
                    }
                "getTags" -> JSONArray(tags.all())
                "addTags", "removeTags" -> {
                    val noteIds = requireIdList(params, "notes")
                    // upstream takes a space-separated string here
                    val tagsParam =
                        params.optString("tags").ifEmpty {
                            val array = params.optJSONArray("tags") ?: JSONArray()
                            (0 until array.length()).joinToString(" ") { array.getString(it) }
                        }
                    if (action == "addTags") {
                        tags.bulkAdd(noteIds, tagsParam)
                    } else {
                        tags.bulkRemove(noteIds, tagsParam)
                    }
                    true
                }
                "suspend" -> {
                    sched.suspendCards(requireIdList(params, "cards"))
                    true
                }
                "unsuspend" -> {
                    sched.unsuspendCards(requireIdList(params, "cards"))
                    true
                }
                "bury" -> {
                    sched.buryCards(requireIdList(params, "cards"))
                    true
                }
                "getEaseOfCards" ->
                    JSONObject().apply {
                        requireIdList(params, "cards").forEach { put(it.toString(), getCard(it).factor.toLong()) }
                    }
                "cardsToNotes" ->
                    JSONArray().apply { requireIdList(params, "cards").forEach { put(getCard(it).nid) } }
                "storeMediaFile" -> {
                    val filename = params.getString("filename")
                    val data = Base64.decode(params.getString("data"), Base64.DEFAULT)
                    if (filename.isEmpty() || data.isEmpty()) {
                        throw IllegalArgumentException("storeMediaFile needs a filename and base64 data")
                    }
                    // upstream answers with the stored (possibly renamed) filename
                    media.writeData(
                        filename,
                        com.google.protobuf.ByteString
                            .copyFrom(data),
                    )
                }
                else -> throw IllegalArgumentException("unsupported action: $action")
            }
        }

    private fun Collection.cardsInfo(cardIds: List<Long>): JSONArray {
        val deckNames = decks.allNamesAndIds().associate { it.id to it.name }
        val modelNames = notetypes.allNamesAndIds().associate { it.id to it.name }
        return JSONArray().apply {
            for (cardId in cardIds) {
                val card = getCard(cardId)
                val note = getNote(card.nid)
                val fieldNames = note.notetype.fieldsNames
                val fields =
                    JSONObject().apply {
                        fieldNames.forEachIndexed { index, name ->
                            put(name, note.fields.getOrElse(index) { "" })
                        }
                    }
                val cardJson =
                    JSONObject().apply {
                        put("id", card.id)
                        put("noteId", card.nid)
                        put("deck", deckNames[card.did] ?: "")
                        put("modelName", modelNames[note.noteTypeId] ?: "")
                        put("ord", card.ord)
                        put("type", card.type.code)
                        put("queue", card.queue.code)
                        put("due", card.due)
                        put("interval", card.ivl)
                        put("factor", card.factor)
                        put("reps", card.reps)
                        put("lapses", card.lapses)
                        put("flags", card.flags)
                        put("fields", fields)
                    }
                put(cardJson)
            }
        }
    }

    private fun Collection.notesInfo(noteIds: List<Long>): JSONArray =
        JSONArray().apply {
            for (noteId in noteIds) {
                val note = getNote(noteId)
                val fieldNames = note.notetype.fieldsNames
                val fields =
                    JSONArray().apply {
                        fieldNames.forEachIndexed { order, name ->
                            put(
                                JSONObject()
                                    .put("name", name)
                                    .put("order", order)
                                    .put("value", note.fields.getOrElse(order) { "" }),
                            )
                        }
                    }
                val noteJson =
                    JSONObject().apply {
                        put("noteId", note.id)
                        put("guid", note.guId ?: "")
                        put("tags", JSONArray(note.tags))
                        put("fields", fields)
                    }
                put(noteJson)
            }
        }

    /** Adds one note per the upstream schema; creates the deck if missing. */
    private fun Collection.addOneNote(note: JSONObject): Long {
        val deckName = note.getString("deckName")
        val modelName = note.getString("modelName")
        val fields = note.getJSONObject("fields")
        val notetype =
            notetypes.byName(modelName)
                ?: throw IllegalArgumentException("model was not found: $modelName")
        val deckId =
            decks.idForName(deckName)
                ?: decks.id(deckName, create = true)
                ?: throw IllegalArgumentException("deck could not be created: $deckName")
        val newNote = newNote(notetype)
        notetype.fieldsNames.forEachIndexed { index, name ->
            if (!fields.has(name)) {
                throw IllegalArgumentException("field was not found in note: $name")
            }
            newNote.setField(index, fields.getString(name))
        }
        val noteTags = note.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until noteTags.length()) {
            newNote.tags.add(noteTags.getString(i))
        }
        addNote(newNote, deckId)
        return newNote.id
    }

    private fun idList(
        params: JSONObject,
        key: String,
    ): List<Long> {
        val value = params.opt(key) ?: return emptyList()
        return when (value) {
            is JSONArray -> (0 until value.length()).map { value.getLong(it) }
            is Number -> listOf(value.toLong())
            else -> emptyList()
        }
    }

    private fun requireIdList(
        params: JSONObject,
        key: String,
    ): List<Long> {
        val ids = idList(params, key)
        if (ids.isEmpty()) {
            throw IllegalArgumentException("$key param must be a non-empty id list")
        }
        return ids
    }
}
