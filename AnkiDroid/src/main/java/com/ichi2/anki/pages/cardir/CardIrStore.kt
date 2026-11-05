// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

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
    ): CardIr? {
        val file = irFile(collectionDirectory, cardId)
        if (!file.isFile) return null
        val text =
            try {
                file.readText()
            } catch (e: Exception) {
                return null
            }
        return CardIrParser.parse(text)
    }
}
