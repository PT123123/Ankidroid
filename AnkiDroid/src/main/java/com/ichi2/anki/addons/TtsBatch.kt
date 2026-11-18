// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · TTS batch generation (B23), 2026

package com.ichi2.anki.addons

import android.speech.tts.TextToSpeech
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * B23 · batch "field text → speech → media file → [sound:...] tag", the core of
 * AwesomeTTS/HyperTTS. The engine is injected so the chunk/tag/write pipeline is
 * unit-testable; the UI passes a real [TextToSpeech].
 */
object TtsBatch {
    /** A synthesizer that writes utterance [text] as audio into [outFile]. */
    fun interface Synthesizer {
        suspend fun synthesize(
            text: String,
            locale: Locale,
            outFile: File,
        ): Boolean
    }

    data class Result(
        val updatedNotes: Int = 0,
        val generatedFiles: Int = 0,
        val failures: List<String> = emptyList(),
    )

    /**
     * For each note, synthesize the first [fieldIndex] field (plain text), write
     * the audio into the collection media folder and append the [sound:] tag to
     * that field. One `updateNote` per note so a failure never corrupts others.
     *
     * The [synthesize] callback owns the engine lifecycle; this function only
     * decides file names, tags and note updates.
     */
    suspend fun generate(
        noteIds: List<Long>,
        fieldIndex: Int,
        locale: Locale,
        mediaDir: File,
        synthesizer: Synthesizer,
        maxChars: Int = 200,
    ): Result =
        withContext(Dispatchers.IO) {
            var updated = 0
            var files = 0
            val failures = mutableListOf<String>()
            for (noteId in noteIds) {
                val fields =
                    try {
                        withCol { getNote(noteId).fields.toList() }
                    } catch (e: Exception) {
                        failures += "note $noteId: ${e.message ?: "read failed"}"
                        continue
                    }
                if (fieldIndex !in fields.indices) {
                    failures += "note $noteId: no field $fieldIndex"
                    continue
                }
                val plain = AddonTools.ttsPlainText(fields[fieldIndex])
                if (plain.isEmpty()) {
                    failures += "note $noteId: empty field"
                    continue
                }
                val name = AddonTools.ttsMediaName(plain, noteId)
                val outFile = File(mediaDir, name)
                val ok =
                    try {
                        synthesizer.synthesize(plain, locale, outFile)
                    } catch (e: Exception) {
                        Timber.w(e, "TTS synthesize failed for note %d", noteId)
                        false
                    }
                if (!ok || !outFile.exists() || outFile.length() == 0L) {
                    failures += "note $noteId: synthesize failed"
                    outFile.delete()
                    continue
                }
                files++
                val tag = AddonTools.ttsSoundTag(name)
                val newFields = fields.toMutableList()
                if (!newFields[fieldIndex].contains(tag)) {
                    newFields[fieldIndex] = newFields[fieldIndex] + tag
                }
                try {
                    withCol {
                        val note = getNote(noteId)
                        note.setField(fieldIndex, newFields[fieldIndex])
                        updateNote(note)
                    }
                    updated++
                } catch (e: Exception) {
                    failures += "note $noteId: update failed: ${e.message ?: ""}"
                }
            }
            Result(updated, files, failures)
        }

    /** Real synthesizer backed by Android's [TextToSpeech.synthesizeToFile]. */
    class AndroidSynthesizer(
        private val tts: TextToSpeech,
    ) : Synthesizer {
        override suspend fun synthesize(
            text: String,
            locale: Locale,
            outFile: File,
        ): Boolean =
            suspendCancellableCoroutine { cont ->
                try {
                    tts.language = locale
                    val params =
                        android.os.Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_batch")
                        }
                    val result = tts.synthesizeToFile(text, params, outFile, "tts_batch")
                    if (result != TextToSpeech.SUCCESS) {
                        if (cont.isActive) cont.resume(false)
                        return@suspendCancellableCoroutine
                    }
                    // synthesizeToFile is async; poll for the file on the caller context.
                    // The UtteranceProgressListener owned by the UI signals completion; a
                    // short bounded poll here covers the common case without a global callback.
                    var waited = 0
                    while (waited < 100 && !outFile.exists()) {
                        Thread.sleep(50)
                        waited++
                    }
                    if (cont.isActive) cont.resume(outFile.exists() && outFile.length() > 0)
                } catch (e: Exception) {
                    Timber.w(e, "synthesizeToFile failed")
                    if (cont.isActive) cont.resume(false)
                }
            }
    }
}
