// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · AnkiConnect-compatible local API gate, 2026

package com.ichi2.anki.pages

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.QueueType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate for the AnkiConnect-compatible dispatcher (pages/AnkiConnectDispatcher.kt):
 * drives every supported action against the real in-memory collection. The HTTP
 * layer (AnkiConnectServer) is a thin wrapper asserted on the desktop side.
 */
@RunWith(AndroidJUnit4::class)
class AnkiConnectDispatcherTest : RobolectricTest() {
    // storeMediaFile writes through col.media.dir, which needs a real folder
    override fun getCollectionStorageMode(): CollectionStorageMode = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    private fun dispatch(
        action: String,
        params: JSONObject = JSONObject(),
    ): Any = runBlocking { AnkiConnectDispatcher.dispatch(action, params) }

    @Test
    fun versionPermissionAndUnknownAction() {
        assertEquals(6, dispatch("version"))

        val permission = dispatch("requestPermission") as JSONObject
        assertEquals("granted", permission.getString("permission"))
        assertFalse(permission.getBoolean("requireApiKey"))
        assertEquals(6, permission.getInt("version"))

        val exception = runCatching { dispatch("noSuchAction") }.exceptionOrNull()
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains("unsupported action"))
    }

    @Test
    fun deckAndModelQueries() {
        addDeck("探针")
        val decks = dispatch("deckNames") as JSONArray
        assertTrue(decks.toString().contains("探针"))

        val deckPairs = dispatch("deckNamesAndIds") as JSONArray
        val probe =
            (0 until deckPairs.length())
                .map { deckPairs.getJSONObject(it) }
                .first { it.getString("name") == "探针" }
        assertTrue(probe.getLong("id") > 0)

        val note = addBasicNote("探针正面", "探针背面")
        val models = dispatch("modelNames") as JSONArray
        assertTrue(models.toString().contains(note.notetype.name))
        val fields =
            dispatch("modelFieldNames", JSONObject().put("modelName", note.notetype.name)) as JSONArray
        assertEquals(note.notetype.fieldsNames.size, fields.length())
    }

    @Test
    fun findAndInfoRoundTrip() {
        val note = addBasicNote("探针正面", "探针背面")
        val cardId = note.firstCard().id
        val fieldNames = note.notetype.fieldsNames

        val cards = dispatch("findCards", JSONObject().put("query", "探针正面")) as JSONArray
        assertEquals(1, cards.length())
        val notes = dispatch("findNotes", JSONObject().put("query", "探针正面")) as JSONArray
        assertEquals(1, notes.length())
        assertEquals(note.id, notes.getLong(0))

        val cardInfo =
            (
                dispatch("cardsInfo", JSONObject().put("cards", JSONArray(listOf(cardId))))
                    as JSONArray
            ).getJSONObject(0)
        assertEquals(cardId, cardInfo.getLong("id"))
        assertEquals(note.id, cardInfo.getLong("noteId"))
        assertEquals("探针正面", cardInfo.getJSONObject("fields").getString(fieldNames[0]))
        assertEquals(0, cardInfo.getInt("reps"))

        val noteInfo =
            (
                dispatch("notesInfo", JSONObject().put("notes", JSONArray(listOf(note.id))))
                    as JSONArray
            ).getJSONObject(0)
        assertEquals(note.id, noteInfo.getLong("noteId"))
        assertEquals("探针正面", noteInfo.getJSONArray("fields").getJSONObject(0).getString("value"))
    }

    @Test
    fun addNoteAndTagsRoundTrip() {
        addDeck("接口牌组")
        val fixture = addBasicNote("模板正面", "模板背面")
        val modelName = fixture.notetype.name
        val fieldNames = fixture.notetype.fieldsNames

        val params =
            JSONObject().put(
                "note",
                JSONObject()
                    .put("deckName", "接口牌组")
                    .put("modelName", modelName)
                    .put(
                        "fields",
                        JSONObject().put(fieldNames[0], "接口正面").put(fieldNames[1], "接口背面"),
                    ).put("tags", JSONArray(listOf("probe-tag"))),
            )
        val newId = dispatch("addNote", params) as Long
        assertTrue(newId > 0)

        val found = dispatch("findNotes", JSONObject().put("query", "接口正面")) as JSONArray
        assertEquals(1, found.length())

        assertTrue(
            dispatch(
                "addTags",
                JSONObject().put("notes", JSONArray(listOf(newId))).put("tags", "probe-tag-1"),
            ) as Boolean,
        )
        assertTrue((dispatch("getTags") as JSONArray).toString().contains("probe-tag-1"))
        assertTrue(
            dispatch(
                "removeTags",
                JSONObject().put("notes", JSONArray(listOf(newId))).put("tags", "probe-tag-1"),
            ) as Boolean,
        )
        // rslib keeps unused tags registered until clearUnusedTags (upstream
        // AnkiConnect behaves the same), so assert removal at the note level
        val tagsAfter =
            (
                dispatch("notesInfo", JSONObject().put("notes", JSONArray(listOf(newId))))
                    as JSONArray
            ).getJSONObject(0).getJSONArray("tags").toString()
        assertFalse(tagsAfter.contains("probe-tag-1"))
        // the note-level tag from addNote is untouched
        assertTrue(tagsAfter.contains("probe-tag"))
    }

    @Test
    fun suspendBuryRoundTrip() {
        val note = addBasicNote("调度探针正面", "调度探针背面")
        val cardId = note.firstCard().id

        assertTrue(dispatch("suspend", JSONObject().put("cards", JSONArray(listOf(cardId)))) as Boolean)
        assertEquals(QueueType.Suspended, col.getCard(cardId).queue)
        assertTrue(dispatch("unsuspend", JSONObject().put("cards", JSONArray(listOf(cardId)))) as Boolean)
        assertEquals(QueueType.New, col.getCard(cardId).queue)
        assertTrue(dispatch("bury", JSONObject().put("cards", JSONArray(listOf(cardId)))) as Boolean)
        assertEquals(QueueType.ManuallyBuried, col.getCard(cardId).queue)
        assertTrue(dispatch("unsuspend", JSONObject().put("cards", JSONArray(listOf(cardId)))) as Boolean)
        assertEquals(QueueType.New, col.getCard(cardId).queue)

        val ease =
            dispatch("getEaseOfCards", JSONObject().put("cards", JSONArray(listOf(cardId))))
                as JSONObject
        assertEquals(0L, ease.getLong(cardId.toString()))
        val notesOfCards =
            dispatch("cardsToNotes", JSONObject().put("cards", JSONArray(listOf(cardId))))
                as JSONArray
        assertEquals(note.id, notesOfCards.getLong(0))
    }

    @Test
    fun addNoteFailuresStayClean() {
        addDeck("正常牌组")
        val note = addBasicNote("失败探针正面", "失败探针背面")

        val badModel =
            JSONObject().put(
                "note",
                JSONObject()
                    .put("deckName", "正常牌组")
                    .put("modelName", "无此模板")
                    .put("fields", JSONObject().put("Front", "x")),
            )
        val modelException = runCatching { dispatch("addNote", badModel) }.exceptionOrNull()
        assertTrue(modelException is IllegalArgumentException)
        assertTrue(modelException!!.message!!.contains("model was not found"))

        val badField =
            JSONObject().put(
                "note",
                JSONObject()
                    .put("deckName", "正常牌组")
                    .put("modelName", note.notetype.name)
                    .put("fields", JSONObject().put("不存在的字段", "x")),
            )
        val fieldException = runCatching { dispatch("addNote", badField) }.exceptionOrNull()
        assertTrue(fieldException!!.message!!.contains("field was not found"))
    }

    @Test
    fun emptyIdListsAreRejectedLikeDesktop() {
        for (action in listOf("cardsInfo", "getEaseOfCards", "cardsToNotes", "suspend", "unsuspend", "bury")) {
            val exception =
                runCatching { dispatch(action, JSONObject().put("cards", JSONArray())) }.exceptionOrNull()
            assertTrue("$action should reject an empty list", exception is IllegalArgumentException)
            assertTrue(exception!!.message!!.contains("cards param must be a non-empty id list"))
        }
        for (action in listOf("notesInfo", "addTags", "removeTags")) {
            val exception =
                runCatching { dispatch(action, JSONObject().put("notes", JSONArray())) }.exceptionOrNull()
            assertTrue("$action should reject an empty list", exception is IllegalArgumentException)
            assertTrue(exception!!.message!!.contains("notes param must be a non-empty id list"))
        }
    }

    @Test
    fun storeMediaFileReturnsTheStoredName() {
        val bytes = "探针媒体".toByteArray(Charsets.UTF_8)
        val params =
            JSONObject()
                .put("filename", "ankiconnect-probe.txt")
                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        val stored = dispatch("storeMediaFile", params) as String
        assertEquals("ankiconnect-probe.txt", stored)
        val file = java.io.File(col.media.dir, stored)
        assertTrue(file.exists())
        assertEquals("探针媒体", file.readText(Charsets.UTF_8))

        val emptyData =
            JSONObject().put("filename", "x.txt").put("data", "")
        val exception = runCatching { dispatch("storeMediaFile", emptyData) }.exceptionOrNull()
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains("filename and base64 data"))
    }
}
