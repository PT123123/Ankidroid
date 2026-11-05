// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File

/**
 * Draws a Card IR frame.
 *
 * The IR carries absolute rects and resolved styles, so the drawing code is
 * a straight translation: nothing is measured, nothing is laid out, no
 * browser is involved. The canvas is scaled so that a frame compiled for
 * 800 px is drawn at whatever width the device has.
 *
 * @param frame the frame to draw
 * @param fonts the fonts available on this device
 * @param mediaDirectory where the media files live, for images
 * @param revealedCloze the ids of the cloze nodes that are revealed
 * @param onAction called when the user taps a node that carries a behaviour
 */
@Composable
fun CardIrView(
    frame: IrFrame,
    modifier: Modifier = Modifier,
    fonts: CardIrFonts = CardIrFonts.EMPTY,
    mediaDirectory: File? = null,
    revealedCloze: Set<Long> = emptySet(),
    onAction: (IrNode) -> Unit = {},
) {
    val aspect =
        remember(frame.width, frame.height) {
            if (frame.width > 0f && frame.height > 0f) frame.width / frame.height else 1f
        }
    val painter =
        remember(frame, fonts, mediaDirectory, revealedCloze) {
            CardIrPainter(fonts, mediaDirectory, revealedCloze, onAction)
        }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .pointerInput(frame) {
                    detectTapGestures { tap ->
                        val scale = if (frame.width > 0f) size.width / frame.width else 1f
                        val hit = painter.hitTest(tap.x / scale, tap.y / scale)
                        if (hit != null) {
                            painter.action(hit)
                        }
                    }
                },
    ) {
        val scale = if (frame.width > 0f) size.width / frame.width else 1f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(scale, scale)
            painter.draw(native, frame.root)
            native.restore()
        }
    }
}

/**
 * The drawing side of the renderer.
 *
 * It is deliberately not a composable: the IR is a tree of boxes with known
 * geometry, so the whole card is one canvas and one recursive walk.
 */
class CardIrPainter(
    private val fonts: CardIrFonts,
    private val mediaDirectory: File?,
    private val revealedCloze: Set<Long>,
    private val onAction: (IrNode) -> Unit,
) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmaps = HashMap<String, Bitmap?>()

    /** The nodes that can be tapped, in draw order (the last one is on top). */
    private val hitTargets = ArrayList<Pair<RectF, IrNode>>()

    /** Draws a frame onto a native canvas. */
    fun draw(
        canvas: Canvas,
        root: IrNode,
    ) {
        hitTargets.clear()
        drawNode(canvas, root, emptyMap())
    }

    /** The node under a point in card coordinates, if any. */
    fun hitTest(
        x: Float,
        y: Float,
    ): IrNode? {
        for (index in hitTargets.indices.reversed()) {
            val (rect, node) = hitTargets[index]
            if (x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom) {
                return node
            }
        }
        return null
    }

    /** Reports a tapped node to the host. */
    fun action(node: IrNode) {
        onAction(node)
    }

    private fun drawNode(
        canvas: Canvas,
        node: IrNode,
        inherited: Map<String, String>,
    ) {
        val style = CardIrStyle.inheritedStyle(inherited, node.style)
        when (node.type) {
            "text" -> drawText(canvas, node, style)
            "image" -> drawImage(canvas, node, style)
            "svg" -> Unit // An SVG has no native equivalent; the audio button draws its own glyph.
            "audio" -> drawAudio(canvas, node, style)
            "input" -> drawInput(canvas, node, style)
            "ruby" -> drawRuby(canvas, node, style)
            else -> drawBox(canvas, node, style)
        }
        if (node.type != "text") {
            val rect = node.rect
            if (!rect.isEmpty) {
                hitTargets.add(
                    RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h) to node,
                )
            }
        }
        val childStyle = CardIrStyle.childStyle(style)
        for (child in node.children) {
            drawNode(canvas, child, childStyle)
        }
    }

    /** Background, borders and rounded corners. */
    private fun drawBox(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
    ) {
        val rect = node.rect
        if (rect.isEmpty) return

        val background = CardIrStyle.color(style, "background-color")
        if (background != null) {
            fillPaint.color = background
            val radius = cornerRadius(style)
            if (radius <= 0f) {
                canvas.drawRect(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, fillPaint)
            } else {
                canvas.drawRoundRect(
                    RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h),
                    radius,
                    radius,
                    fillPaint,
                )
            }
        }

        drawBorders(canvas, style, rect)
    }

    private fun drawBorders(
        canvas: Canvas,
        style: Map<String, String>,
        rect: IrRect,
    ) {
        val left = CardIrStyle.borderWidth(style, "left")
        val right = CardIrStyle.borderWidth(style, "right")
        val top = CardIrStyle.borderWidth(style, "top")
        val bottom = CardIrStyle.borderWidth(style, "bottom")
        if (left <= 0f && right <= 0f && top <= 0f && bottom <= 0f) return

        strokePaint.style = Paint.Style.STROKE
        if (top > 0f) {
            strokePaint.color = CardIrStyle.color(style, "border-top-color") ?: FALLBACK_COLOR
            strokePaint.strokeWidth = top
            val y = rect.y + top / 2f
            canvas.drawLine(rect.x, y, rect.x + rect.w, y, strokePaint)
        }
        if (bottom > 0f) {
            strokePaint.color = CardIrStyle.color(style, "border-bottom-color") ?: FALLBACK_COLOR
            strokePaint.strokeWidth = bottom
            val y = rect.y + rect.h - bottom / 2f
            canvas.drawLine(rect.x, y, rect.x + rect.w, y, strokePaint)
        }
        if (left > 0f) {
            strokePaint.color = CardIrStyle.color(style, "border-left-color") ?: FALLBACK_COLOR
            strokePaint.strokeWidth = left
            val x = rect.x + left / 2f
            canvas.drawLine(x, rect.y, x, rect.y + rect.h, strokePaint)
        }
        if (right > 0f) {
            strokePaint.color = CardIrStyle.color(style, "border-right-color") ?: FALLBACK_COLOR
            strokePaint.strokeWidth = right
            val x = rect.x + rect.w - right / 2f
            canvas.drawLine(x, rect.y, x, rect.y + rect.h, strokePaint)
        }
    }

    /** One `drawText` per measured line run. */
    private fun drawText(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
    ) {
        if (node.runs.isEmpty()) {
            // A text node without runs has nothing to draw: the compiler
            // removes the characters that collapse to zero width.
            return
        }
        textPaint.color = CardIrStyle.color(style, "color") ?: FALLBACK_COLOR
        textPaint.textSize = CardIrStyle.fontSize(style)
        textPaint.typeface = fonts.resolve(style)
        textPaint.textAlign = Paint.Align.LEFT

        val metrics = textPaint.fontMetrics
        for (run in node.runs) {
            if (run.text.isEmpty()) continue
            val rect = run.rect
            // The run rect is the line box; the baseline sits inside it.
            val baseline = rect.y + (rect.h - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            canvas.drawText(run.text, rect.x, baseline, textPaint)
        }
    }

    /** An image from the media folder. */
    private fun drawImage(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
    ) {
        val src = node.src ?: return
        val directory = mediaDirectory ?: return
        val rect = node.rect
        if (rect.isEmpty) return

        val bitmap =
            bitmaps.getOrPut(src) {
                val file = File(directory, src)
                if (file.isFile) BitmapFactory.decodeFile(file.path) else null
            } ?: return

        val destination = RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
    }

    /** The replay button. */
    private fun drawAudio(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
    ) {
        val hasArtwork = node.children.any { it.type == "svg" }
        if (hasArtwork) return // the SVG artwork carries the button
        val rect = node.rect
        if (rect.isEmpty) return
        val size = minOf(rect.w, rect.h)
        if (size <= 0f) return
        val cx = rect.x + rect.w / 2f
        val cy = rect.y + rect.h / 2f
        val radius = size / 2f

        fillPaint.color = CardIrStyle.color(style, "color") ?: FALLBACK_COLOR
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // The triangle of Anki's play icon.
        val path = Path()
        path.moveTo(cx - radius * 0.25f, cy - radius * 0.45f)
        path.lineTo(cx + radius * 0.45f, cy)
        path.lineTo(cx - radius * 0.25f, cy + radius * 0.45f)
        path.close()
        fillPaint.color = Color.WHITE
        canvas.drawPath(path, fillPaint)
    }

    /** A typed answer field. */
    private fun drawInput(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
    ) {
        val rect = node.rect
        if (rect.isEmpty) return
        strokePaint.color = CardIrStyle.color(style, "border-top-color") ?: FALLBACK_COLOR
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f
        canvas.drawRect(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, strokePaint)
    }

    /**
     * Ruby text: the base run plus the reading above it, centred.
     *
     * The IR gives the whole ruby element one rect; the reading has to be
     * positioned by hand, exactly like the Windows renderer does.
     */
    private fun drawRuby(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
    ) {
        val base = node.children.firstOrNull { it.type != "rt" && it.type != "rb" }
        val reading = node.children.firstOrNull { it.type == "rt" }
        if (base != null) {
            drawText(canvas, base, style)
        }
        if (reading == null) return

        textPaint.color = CardIrStyle.color(style, "color") ?: FALLBACK_COLOR
        textPaint.textSize = CardIrStyle.fontSize(style) * 0.5f
        textPaint.typeface = fonts.resolve(style)
        textPaint.textAlign = Paint.Align.LEFT
        val metrics = textPaint.fontMetrics
        val text = reading.runs.firstOrNull()?.text ?: reading.text ?: return
        val width = textPaint.measureText(text)
        val rect = reading.rect
        val x = rect.x + (rect.w - width) / 2f
        val baseline = rect.y + (rect.h - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun cornerRadius(style: Map<String, String>): Float {
        val radii =
            listOf(
                CardIrStyle.cornerRadius(style, "top-left"),
                CardIrStyle.cornerRadius(style, "top-right"),
                CardIrStyle.cornerRadius(style, "bottom-right"),
                CardIrStyle.cornerRadius(style, "bottom-left"),
            )
        return radii.max()
    }

    companion object {
        private const val FALLBACK_COLOR = 0xFF1F2937.toInt()
    }
}
