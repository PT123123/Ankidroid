// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads the Card IR sidecar of a collection.
 *
 * The IR never goes into `collection.anki2` and never changes the apkg or
 * the sync protocol: it lives next to the collection in
 * `card_ir/cards/<card id>.json`, with the shared assets in
 * `card_ir/assets/`.
 *
 * The Windows side writes the same layout (`anki-qt/src/card_ir_store.cpp`),
 * so a collection that has been pre-compiled on the desktop can be rendered
 * on Android without a second compilation.
 */
object CardIrStore {
    /** The sidecar folder of a collection. */
    fun root(collectionDirectory: File): File = File(collectionDirectory, "card_ir")

    /** The IR document of a card. */
    fun irFile(
        collectionDirectory: File,
        cardId: Long,
    ): File = File(root(collectionDirectory), "cards/$cardId.json")

    /** A shared asset (an inline SVG, for example). */
    fun assetFile(
        collectionDirectory: File,
        sha256: String,
    ): File = File(root(collectionDirectory), "assets/$sha256")

    /** Whether a card has an IR. */
    fun hasIr(
        collectionDirectory: File,
        cardId: Long,
    ): Boolean = irFile(collectionDirectory, cardId).isFile

    /**
     * Reads the IR of a card.
     *
     * @return the card, or null when there is no IR, when it is damaged or
     *   when it was compiled by an incompatible version. A null result is
     *   the signal to fall back to the WebView reviewer.
     */
    fun read(
        collectionDirectory: File,
        cardId: Long,
    ): CardIr? = readText(collectionDirectory, cardId)?.let(CardIrParser::parse)

    /** Reads the raw JSON so the same document can be passed to the Taffy kernel. */
    fun readText(
        collectionDirectory: File,
        cardId: Long,
    ): String? {
        val file = irFile(collectionDirectory, cardId)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** Atomically adds a face captured from the already-rendered reviewer DOM. */
    fun writeCapturedFace(
        collectionDirectory: File,
        cardId: Long,
        side: String,
        variant: String,
        frameJson: String,
        unsupported: List<String>,
    ): Boolean {
        if (side !in setOf("question", "answer")) return false
        val frame = runCatching { JSONObject(frameJson) }.getOrNull() ?: return false
        val file = irFile(collectionDirectory, cardId)
        val document =
            readText(collectionDirectory, cardId)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
        val sides = document.optJSONObject("sides") ?: JSONObject()
        val sideVariants = sides.optJSONObject(side) ?: JSONObject()
        sideVariants.put(variant, frame)
        sides.put(side, sideVariants)

        val variants = LinkedHashSet<String>()
        document.optJSONArray("variants")?.let { existing ->
            for (index in 0 until existing.length()) existing.optString(index).takeIf(String::isNotBlank)?.let(variants::add)
        }
        variants.add(variant)

        // Keep compiler-authored unsupported markers separate from capture
        // results. A template can change between reviews, so stale capture
        // markers must not permanently disable the native path.
        val captureUnsupported = document.optJSONObject("android_capture_unsupported") ?: JSONObject()
        captureUnsupported.put(side, JSONArray(unsupported.distinct()))
        document.put("android_capture_unsupported", captureUnsupported)

        val compilerUnsupported =
            document.optJSONArray("android_compiler_unsupported") ?: document.optJSONArray("unsupported") ?: JSONArray()
        // Preserve the compiler's global markers. Android DOM capture markers
        // stay face-specific so an unsupported answer does not hide a safe
        // question (and vice versa).
        document.put("android_compiler_unsupported", compilerUnsupported)

        document.put("ir_version", CardIrParser.SUPPORTED_VERSION)
        document.put("card_id", cardId.toString())
        document.put("notetype", document.optString("notetype", "Android capture"))
        document.put("layout_mode", "bounds")
        document.put("variants", JSONArray(variants.toList()))
        document.put("sides", sides)
        document.put("unsupported", compilerUnsupported)

        return runCatching {
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                stream.write(document.toString().toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (error: Exception) {
                atomicFile.failWrite(stream)
                throw error
            }
            true
        }.getOrDefault(false)
    }
}
