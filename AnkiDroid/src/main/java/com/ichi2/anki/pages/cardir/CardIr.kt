// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Card IR: a card frozen into pure data.
 *
 * The IR is produced by the offline compiler (headless Chromium, compile time)
 * and is the single source of truth for a card. It contains no HTML, no CSS
 * and no JavaScript: the styles are already resolved and the geometry is
 * already measured, so the runtime only has to draw it.
 *
 * The protocol is defined in `anki-qt/docs/native-card-render/schema/card_ir.schema.json`.
 */
@Serializable
data class CardIr(
    val ir_version: String = "",
    val card_id: String = "",
    val notetype: String = "",
    val layout_mode: String? = null,
    val variants: List<String> = emptyList(),
    val sides: Map<String, Map<String, IrFrame>> = emptyMap(),
    val behaviors: List<IrBehavior> = emptyList(),
    val warnings: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
)

@Serializable
data class IrBehavior(
    val kind: String = "",
    val trigger: String? = null,
)

/** One face of a card in one variant (theme + width + scale). */
@Serializable
data class IrFrame(
    val width: Float = 0f,
    val height: Float = 0f,
    val root: IrNode = IrNode(),
    val node_count: Int = 0,
    val text_run_count: Int = 0,
)

/**
 * A node of the card tree.
 *
 * The rects are absolute, relative to the top left of the card content box.
 * A `text` node carries no style of its own; it inherits from the nearest
 * element ancestor, which is what [CardIrStyle.inheritedStyle] does.
 */
@Serializable
data class IrNode(
    val id: Long = 0,
    @SerialName("type")
    val type: String = "",
    val tag: String? = null,
    val attrs: Map<String, String> = emptyMap(),
    val text: String? = null,
    val src: String? = null,
    val style: Map<String, String> = emptyMap(),
    val rect: IrRect = IrRect(),
    val runs: List<IrRun> = emptyList(),
    val actions: List<IrAction> = emptyList(),
    val children: List<IrNode> = emptyList(),
)

@Serializable
data class IrAction(
    val on: String = "",
    val kind: String = "",
)

/** Plain text currently represented by this node's text descendants. */
fun IrNode.displayedText(): String =
    if (type == "text") {
        text ?: runs.joinToString(separator = "") { it.text }
    } else {
        children.joinToString(separator = "") { it.displayedText() }
    }

/** Whether a cloze node currently displays a placeholder instead of its answer. */
fun IrNode.needsClozeReveal(): Boolean = type == "cloze" && attrs["data-cloze"]?.let { it != displayedText() } == true

/** An axis aligned rectangle in CSS pixels. */
@Serializable
data class IrRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
) {
    val isEmpty: Boolean get() = w <= 0f || h <= 0f
}

/** One measured line of text. */
@Serializable
data class IrRun(
    val text: String = "",
    val rect: IrRect = IrRect(),
)

/** The parser used for the IR documents. */
object CardIrParser {
    /** The IR protocol version this renderer understands. */
    const val SUPPORTED_VERSION = "0.1.0"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Parses an IR document.
     *
     * @return the parsed card, or null when the document is not usable IR
     *   (wrong version, malformed json, missing frames). The caller is
     *   expected to fall back to the WebView reviewer in that case.
     */
    fun parse(text: String): CardIr? {
        val card =
            try {
                json.decodeFromString(CardIr.serializer(), text)
            } catch (e: Exception) {
                return null
            }
        if (card.ir_version != SUPPORTED_VERSION) return null
        if (card.sides.isEmpty()) return null
        return card
    }

    /**
     * Picks the frame for a side.
     *
     * The variant key looks like `light@800x1`: theme, width and scale. When
     * the exact variant is missing the closest one is used, so a card still
     * renders after the display width changed.
     */
    fun selectFrame(
        card: CardIr,
        side: String,
        darkTheme: Boolean,
        availableWidth: Int,
    ): Pair<IrFrame, String>? {
        val variants = card.sides[side] ?: card.sides["question"] ?: return null
        if (variants.isEmpty()) return null

        val theme = if (darkTheme) "dark" else "light"
        val exact =
            variants.entries.firstOrNull { (key, _) ->
                key.startsWith("$theme@") && variantWidth(key) == availableWidth
            }
        val picked =
            exact
                ?: variants.entries.firstOrNull { it.key.startsWith("$theme@") }
                ?: variants.entries.firstOrNull { it.key.startsWith("light@") }
                ?: variants.entries.first()
        return picked.value to picked.key
    }

    /** The width encoded in a variant key such as `light@800x1`. */
    fun variantWidth(key: String): Int {
        val at = key.indexOf('@')
        if (at < 0) return 0
        val width = key.substring(at + 1).substringBefore('x')
        return width.toIntOrNull() ?: 0
    }
}
