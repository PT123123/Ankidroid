// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.Keep

@Keep
internal interface TaffyMeasureCallback {
    fun measure(
        nodeId: Long,
        text: String,
        availableWidth: Float,
        availableHeight: Float,
    ): FloatArray
}

/**
 * Android binding for the same Taffy kernel used by the Qt and GPUI clients.
 *
 * A missing or failed native library is reported as `null`, allowing card
 * rendering to fall back to its existing WebView path. Rectangles are keyed
 * by IR node id; each native record is `[id, xBits, yBits, widthBits, heightBits]`.
 */
object TaffyCardLayout {
    private val libraryAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("anki_native_layout") }.isSuccess
    }

    /** Computes the selected frame's layout for [width] CSS pixels. */
    fun layout(
        irJson: ByteArray,
        side: String,
        variant: String,
        width: Float,
        fonts: CardIrFonts = CardIrFonts.EMPTY,
    ): Map<Long, IrRect>? {
        if (!libraryAvailable || width <= 0f) return null
        val card = CardIrParser.parse(irJson.decodeToString()) ?: return null
        val frame = card.sides[side]?.get(variant) ?: return null
        val output =
            runCatching { layoutNative(irJson, side, variant, width, AndroidTaffyMeasurer(frame.root, fonts)) }
                .getOrNull() ?: return null
        if (output.size % VALUES_PER_NODE != 0) return null
        val rects =
            buildMap(output.size / VALUES_PER_NODE) {
                for (offset in output.indices step VALUES_PER_NODE) {
                    put(
                        output[offset],
                        IrRect(
                            x = Float.fromBits(output[offset + 1].toInt()),
                            y = Float.fromBits(output[offset + 2].toInt()),
                            w = Float.fromBits(output[offset + 3].toInt()),
                            h = Float.fromBits(output[offset + 4].toInt()),
                        ),
                    )
                }
            }
        val validRects =
            rects.takeIf { result ->
                result.values.all { rect ->
                    rect.x.isFinite() && rect.y.isFinite() && rect.w.isFinite() && rect.h.isFinite() &&
                        rect.w >= 0f && rect.h >= 0f
                }
            }
        return validRects?.takeIf { it.containsKey(frame.root.id) }
    }

    private external fun layoutNative(
        irJson: ByteArray,
        side: String,
        variant: String,
        width: Float,
        callback: TaffyMeasureCallback,
    ): LongArray?

    @Keep
    private class AndroidTaffyMeasurer(
        root: IrNode,
        private val fonts: CardIrFonts,
    ) : TaffyMeasureCallback {
        private data class Entry(
            val node: IrNode,
            val style: Map<String, String>,
        )

        private val entries = HashMap<Long, Entry>()

        init {
            fun visit(
                node: IrNode,
                inherited: Map<String, String>,
            ) {
                val style = CardIrStyle.inheritedStyle(inherited, node.style)
                entries[node.id] = Entry(node, style)
                val childStyle = CardIrStyle.childStyle(style)
                node.children.forEach { visit(it, childStyle) }
            }
            visit(root, emptyMap())
        }

        override fun measure(
            nodeId: Long,
            text: String,
            availableWidth: Float,
            availableHeight: Float,
        ): FloatArray {
            val entry = entries[nodeId] ?: return floatArrayOf(0f, 0f)
            val node = entry.node
            if (node.type != "text" || text.isEmpty()) return floatArrayOf(node.rect.w, node.rect.h)

            val style = entry.style
            val paint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = CardIrStyle.fontSize(style)
                    typeface = fonts.resolve(style)
                    letterSpacing = if (textSize > 0f) CardIrStyle.length(style, "letter-spacing") / textSize else 0f
                }
            val lineHeight = CardIrStyle.length(style, "line-height")
            if (availableWidth <= 0f) {
                val count = maxOf(1, node.runs.size)
                val width = paint.measureText(text) + CardIrStyle.length(style, "word-spacing") * text.count { it == ' ' }
                val height = if (lineHeight > 0f) lineHeight * count else (paint.fontMetrics.descent - paint.fontMetrics.ascent) * count
                return floatArrayOf(width, height)
            }

            val layout =
                StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, availableWidth.toInt().coerceAtLeast(1))
                    .setAlignment(
                        when (style["text-align"]) {
                            "center" -> Layout.Alignment.ALIGN_CENTER
                            "right" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        },
                    ).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setIncludePad(false)
                    .build()
            val width = (0 until layout.lineCount).maxOfOrNull(layout::getLineWidth) ?: 0f
            val wordSpacing = CardIrStyle.length(style, "word-spacing") * text.count { it == ' ' }
            val measuredHeight = if (lineHeight > 0f) lineHeight * layout.lineCount else layout.height.toFloat()
            return floatArrayOf((width + wordSpacing).coerceAtMost(availableWidth), measuredHeight)
        }
    }

    private const val VALUES_PER_NODE = 5
}
