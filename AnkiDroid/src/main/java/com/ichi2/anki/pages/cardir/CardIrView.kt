// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardir

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.PathParser
import com.ichi2.anki.settings.Prefs
import java.io.File
import kotlin.math.abs

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
    irJson: ByteArray? = null,
    side: String = "question",
    variant: String? = null,
    modifier: Modifier = Modifier,
    fonts: CardIrFonts = CardIrFonts.EMPTY,
    mediaDirectory: File? = null,
    revealedCloze: Set<Long> = emptySet(),
    onAction: (IrNode) -> Unit = {},
    onTap: (Offset) -> Unit = {},
    onSwipe: (Float) -> Unit = {},
    onVerticalSwipe: (Float, Int) -> Unit = { _, _ -> },
    verticalScrollOffset: () -> Int = { 0 },
    onMultiTouch: (Int) -> Unit = {},
    onLayoutUnavailable: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        var locallyRevealedCloze by remember(frame, side) { mutableStateOf(revealedCloze) }
        val hasCapturedGeometry = remember(frame) { frame.root.requiresCapturedGeometry() }
        val layoutRects =
            remember(irJson, variant, maxWidth, frame, hasCapturedGeometry) {
                if (irJson == null || variant == null || maxWidth == Dp.Infinity) {
                    null
                } else if (hasCapturedGeometry) {
                    // Taffy cannot place ruby or inline sup/sub nodes. Keep
                    // the browser-measured geometry for the whole frame.
                    emptyMap()
                } else {
                    TaffyCardLayout.layout(irJson, side, variant, maxWidth.value, fonts)
                }
            }
        LaunchedEffect(irJson, variant, layoutRects) {
            if (irJson != null && variant != null && layoutRects == null) {
                onLayoutUnavailable?.invoke()
            }
        }
        val rootRect = layoutRects?.get(frame.root.id)
        val canvasWidth = rootRect?.w?.takeIf { it > 0f } ?: frame.width
        val canvasHeight = rootRect?.h?.takeIf { it > 0f } ?: frame.height
        val aspect = if (canvasWidth > 0f && canvasHeight > 0f) canvasWidth / canvasHeight else 1f
        val painter =
            remember(frame, fonts, mediaDirectory, locallyRevealedCloze, layoutRects) {
                CardIrPainter(fonts, mediaDirectory, locallyRevealedCloze, onAction, layoutRects.orEmpty())
            }

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .pointerInput(frame, layoutRects, canvasWidth) {
                        detectTapGestures { tap ->
                            val scale = if (canvasWidth > 0f) size.width / canvasWidth else 1f
                            val hit = painter.hitTest(tap.x / scale, tap.y / scale)
                            if (hit != null) {
                                if (hit.needsClozeReveal() &&
                                    hit.actions.any { it.kind == "reveal-cloze" && it.on in setOf("reveal", "click", "tap") }
                                ) {
                                    locallyRevealedCloze = locallyRevealedCloze + hit.id
                                    painter.action(hit)
                                    return@detectTapGestures
                                }
                                painter.action(hit)
                                if (hit.type == "audio" || hit.tag == "a") return@detectTapGestures
                            }
                            onTap(tap)
                        }
                    }.pointerInput(frame, layoutRects) {
                        var dragX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onHorizontalDrag = { change, delta ->
                                dragX += delta
                                change.consume()
                            },
                            onDragEnd = { if (dragX != 0f) onSwipe(dragX) },
                            onDragCancel = { dragX = 0f },
                        )
                    }.pointerInput(frame) {
                        awaitEachGesture {
                            var maxPointers = 0
                            var firstDownTime: Long? = null
                            var lastEventTime = 0L
                            var startScrollOffset = 0
                            var dragX = 0f
                            var dragY = 0f
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                maxPointers = maxOf(maxPointers, pressed.size)
                                if (firstDownTime == null) {
                                    firstDownTime = pressed.firstOrNull()?.uptimeMillis
                                    startScrollOffset = verticalScrollOffset()
                                }
                                if (maxPointers == 1) {
                                    for (change in event.changes) {
                                        dragX += change.position.x - change.previousPosition.x
                                        dragY += change.position.y - change.previousPosition.y
                                    }
                                }
                                lastEventTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventTime
                                if (maxPointers > 1) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })

                            val swipeThreshold = 100f / Prefs.swipeSensitivity
                            if (maxPointers == 1 && abs(dragY) > abs(dragX) && abs(dragY) > swipeThreshold) {
                                onVerticalSwipe(dragY, startScrollOffset)
                            }
                            firstDownTime?.let { downTime ->
                                if (maxPointers in 2..4 && lastEventTime - downTime <= 300L) {
                                    onMultiTouch(maxPointers)
                                }
                            }
                        }
                    },
        ) {
            val scale = if (canvasWidth > 0f) size.width / canvasWidth else 1f
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(scale, scale)
                painter.draw(native, frame.root)
                native.restore()
            }
        }
    }
}

private fun IrNode.requiresCapturedGeometry(): Boolean =
    type == "ruby" || type == "cloze" || attrs["captured-inline"] == "true" || children.any(IrNode::requiresCapturedGeometry)

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
    private val layoutRects: Map<Long, IrRect> = emptyMap(),
) {
    private class FixedLineHeightSpan(
        private val heightPx: Int,
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: Paint.FontMetricsInt,
        ) {
            fm.descent = fm.ascent + heightPx
            fm.bottom = fm.descent
        }
    }

    private data class SvgTransform(
        val scaleX: Float,
        val scaleY: Float,
        val translateX: Float,
        val translateY: Float,
    ) {
        fun apply(rect: IrRect): IrRect =
            rect.copy(
                x = translateX + rect.x * scaleX,
                y = translateY + rect.y * scaleY,
                w = rect.w * scaleX,
                h = rect.h * scaleY,
            )

        companion object {
            fun between(
                source: IrRect,
                target: IrRect,
            ): SvgTransform? {
                if (source.w <= 0f || source.h <= 0f) return null
                val scaleX = target.w / source.w
                val scaleY = target.h / source.h
                if (!scaleX.isFinite() || !scaleY.isFinite()) return null
                return SvgTransform(
                    scaleX = scaleX,
                    scaleY = scaleY,
                    translateX = target.x - source.x * scaleX,
                    translateY = target.y - source.y * scaleY,
                )
            }
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val roundedRectPath = Path()
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
        inheritedSvgTransform: SvgTransform? = null,
        availableTextRight: Float? = null,
    ) {
        val style = CardIrStyle.inheritedStyle(inherited, node.style)
        val isSvgRoot = node.type == "svg" && node.tag == "svg"
        val svgTransform =
            if (isSvgRoot) {
                SvgTransform.between(node.rect, layoutRects[node.id] ?: node.rect)
            } else {
                inheritedSvgTransform
            }
        val baseRect = if (node.type == "svg") node.rect else layoutRects[node.id] ?: node.rect
        val rect = if (node.type == "svg") svgTransform?.apply(baseRect) ?: baseRect else baseRect
        val isVisible = style["visibility"] != "hidden"
        val alpha = ((style["opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f) * 255).toInt()
        val alphaLayer = if (isVisible && alpha < 255) canvas.saveLayerAlpha(null, alpha) else -1
        if (isVisible) {
            val revealedAnswer = node.attrs["data-cloze"].takeIf { node.type == "cloze" && node.id in revealedCloze }
            if (revealedAnswer != null) {
                val answerRun = IrRun(revealedAnswer, rect)
                drawText(
                    canvas,
                    IrNode(id = node.id, type = "text", rect = rect, runs = listOf(answerRun)),
                    style,
                    rect,
                    availableTextRight,
                )
            } else {
                when (node.type) {
                    "text" -> drawText(canvas, node, style, rect, availableTextRight)
                    "image" -> drawImage(canvas, node, style, rect)
                    "svg" -> drawSvgShape(canvas, node, style, rect)
                    "audio" -> drawAudio(canvas, rect)
                    "input" -> drawInput(canvas, node, style, rect)
                    else -> drawBox(canvas, node, style, rect)
                }
            }
            if (node.tag == "li") drawListMarker(canvas, node, style)
        }
        if (isVisible && node.type != "text") {
            if (!rect.isEmpty) {
                hitTargets.add(
                    RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h) to node,
                )
            }
        }
        val childStyle = CardIrStyle.childStyle(style)
        val childTextRight =
            rect.x + rect.w - CardIrStyle.length(style, "padding-right") - CardIrStyle.borderWidth(style, "right")
        val clipsChildren =
            node.tag != "hr" && node.tag != "svg" &&
                style["overflow-x"] in setOf("hidden", "clip") &&
                style["overflow-y"] in setOf("hidden", "clip")
        val clipSave =
            if (clipsChildren) {
                val save = canvas.save()
                val left = rect.x + CardIrStyle.borderWidth(style, "left")
                val top = rect.y + CardIrStyle.borderWidth(style, "top")
                val right = rect.x + rect.w - CardIrStyle.borderWidth(style, "right")
                val bottom = rect.y + rect.h - CardIrStyle.borderWidth(style, "bottom")
                canvas.clipRect(left, top, right, bottom)
                save
            } else {
                -1
            }
        if (node.type != "cloze" || node.id !in revealedCloze) {
            for (child in node.children) {
                drawNode(canvas, child, childStyle, svgTransform, childTextRight)
            }
        }
        if (clipSave >= 0) canvas.restoreToCount(clipSave)
        if (alphaLayer >= 0) canvas.restoreToCount(alphaLayer)
    }

    private fun drawListMarker(
        canvas: Canvas,
        listItem: IrNode,
        style: Map<String, String>,
    ) {
        val marker = listItem.attrs["list-marker"] ?: return
        val (textNode, run) = firstListItemText(listItem) ?: return
        val textRect = layoutRects[textNode.id] ?: textNode.rect
        val runX = run.rect.x + textRect.x - textNode.rect.x
        val runY = run.rect.y + textRect.y - textNode.rect.y
        textPaint.color = CardIrStyle.color(style, "color") ?: FALLBACK_COLOR
        textPaint.textSize = CardIrStyle.fontSize(style)
        textPaint.typeface = fonts.resolve(style)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.letterSpacing =
            if (textPaint.textSize > 0f) CardIrStyle.length(style, "letter-spacing") / textPaint.textSize else 0f
        val metrics = textPaint.fontMetrics
        val baseline = runY + (run.rect.h - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        val gap = maxOf(textPaint.textSize * 0.3f, 3f)
        canvas.drawText(marker, runX - gap, baseline, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun firstListItemText(node: IrNode): Pair<IrNode, IrRun>? {
        if (node.type == "text") return node.runs.firstOrNull()?.let { node to it }
        for (child in node.children) {
            if (child.tag == "ul" || child.tag == "ol") continue
            firstListItemText(child)?.let { return it }
        }
        return null
    }

    /** Background, borders and rounded corners. */
    private fun drawBox(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
        rect: IrRect,
    ) {
        if (rect.isEmpty) return

        val background = CardIrStyle.color(style, "background-color")
        if (background != null) {
            fillPaint.color = background
            val radii = cornerRadii(style, rect)
            if (radii.all { it <= 0f }) {
                canvas.drawRect(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, fillPaint)
            } else {
                roundedRectPath.reset()
                roundedRectPath.addRoundRect(
                    RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h),
                    radii,
                    Path.Direction.CW,
                )
                canvas.drawPath(roundedRectPath, fillPaint)
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
        drawBorderSide(canvas, style, rect, "top", top)
        drawBorderSide(canvas, style, rect, "bottom", bottom)
        drawBorderSide(canvas, style, rect, "left", left)
        drawBorderSide(canvas, style, rect, "right", right)
        strokePaint.pathEffect = null
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawBorderSide(
        canvas: Canvas,
        style: Map<String, String>,
        rect: IrRect,
        side: String,
        width: Float,
    ) {
        if (width <= 0f) return
        strokePaint.color = CardIrStyle.color(style, "border-$side-color") ?: FALLBACK_COLOR
        val borderStyle = style["border-$side-style"]
        if (borderStyle == "double" && width >= 3f) {
            strokePaint.pathEffect = null
            strokePaint.strokeCap = Paint.Cap.BUTT
            strokePaint.strokeWidth = width / 3f
            for (offset in listOf(width / 6f, width * 5f / 6f)) {
                val edge =
                    when (side) {
                        "top" -> rect.y + offset
                        "bottom" -> rect.y + rect.h - offset
                        "left" -> rect.x + offset
                        else -> rect.x + rect.w - offset
                    }
                if (side == "top" || side == "bottom") {
                    canvas.drawLine(rect.x, edge, rect.x + rect.w, edge, strokePaint)
                } else {
                    canvas.drawLine(edge, rect.y, edge, rect.y + rect.h, strokePaint)
                }
            }
            return
        }

        strokePaint.strokeWidth = width
        setBorderPattern(borderStyle, width)
        when (side) {
            "top" -> canvas.drawLine(rect.x, rect.y + width / 2f, rect.x + rect.w, rect.y + width / 2f, strokePaint)
            "bottom" -> canvas.drawLine(rect.x, rect.y + rect.h - width / 2f, rect.x + rect.w, rect.y + rect.h - width / 2f, strokePaint)
            "left" -> canvas.drawLine(rect.x + width / 2f, rect.y, rect.x + width / 2f, rect.y + rect.h, strokePaint)
            "right" -> canvas.drawLine(rect.x + rect.w - width / 2f, rect.y, rect.x + rect.w - width / 2f, rect.y + rect.h, strokePaint)
        }
    }

    private fun setBorderPattern(
        style: String?,
        width: Float,
    ) {
        strokePaint.strokeCap = if (style == "dotted") Paint.Cap.ROUND else Paint.Cap.BUTT
        strokePaint.pathEffect =
            when (style) {
                "dashed" -> DashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
                "dotted" -> DashPathEffect(floatArrayOf(width * 0.01f, width * 2f), 0f)
                else -> null
            }
    }

    /** Draw the supported, static SVG subset from the browser-captured bounds. */
    private fun drawSvgShape(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
        rect: IrRect,
    ) {
        if (rect.isEmpty) return
        val bounds = RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h)
        val tag = node.tag ?: return
        when (tag) {
            "circle" -> if (hasSvgFill(style)) canvas.drawOval(bounds, svgFillPaint(style))
            "ellipse", "rect" -> canvas.drawOvalOrRect(tag, bounds, style)
            "path" -> {
                val path =
                    runCatching { PathParser.createPathFromPathData(node.attrs["d"] ?: return) }
                        .getOrNull() ?: return
                if (style["fill-rule"] == "evenodd") path.fillType = Path.FillType.EVEN_ODD
                val source = RectF()
                path.computeBounds(source, true)
                if (source.width() <= 0f || source.height() <= 0f) return
                val transform = Matrix()
                transform.setRectToRect(source, bounds, Matrix.ScaleToFit.FILL)
                path.transform(transform)
                val fill = svgFillPaint(style)
                if (hasSvgFill(style)) canvas.drawPath(path, fill)
                drawSvgStroke(canvas, path, style)
            }
        }
        if (tag in setOf("circle", "ellipse", "rect")) drawSvgStroke(canvas, tag, bounds, style)
    }

    private fun Canvas.drawOvalOrRect(
        tag: String,
        bounds: RectF,
        style: Map<String, String>,
    ) {
        if (!hasSvgFill(style)) return
        val paint = svgFillPaint(style)
        if (tag == "rect") drawRect(bounds, paint) else drawOval(bounds, paint)
    }

    private fun hasSvgFill(style: Map<String, String>): Boolean = style["fill"] != "none"

    private fun svgFillPaint(style: Map<String, String>): Paint =
        fillPaint.apply {
            this.style = Paint.Style.FILL
            color = CardIrStyle.parseColor(style["fill"]) ?: Color.BLACK
        }

    private fun drawSvgStroke(
        canvas: Canvas,
        path: Path,
        style: Map<String, String>,
    ) {
        val color = CardIrStyle.color(style, "stroke") ?: return
        val width = CardIrStyle.length(style, "stroke-width")
        if (width <= 0f) return
        strokePaint.apply {
            this.style = Paint.Style.STROKE
            this.color = color
            strokeWidth = width
            strokeCap = if (style["stroke-linecap"] == "round") Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = if (style["stroke-linejoin"] == "round") Paint.Join.ROUND else Paint.Join.MITER
        }
        canvas.drawPath(path, strokePaint)
    }

    private fun drawSvgStroke(
        canvas: Canvas,
        tag: String,
        bounds: RectF,
        style: Map<String, String>,
    ) {
        if (tag == "path") return
        val color = CardIrStyle.color(style, "stroke") ?: return
        val width = CardIrStyle.length(style, "stroke-width")
        if (width <= 0f) return
        strokePaint.apply {
            this.style = Paint.Style.STROKE
            this.color = color
            strokeWidth = width
            strokeCap = if (style["stroke-linecap"] == "round") Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = if (style["stroke-linejoin"] == "round") Paint.Join.ROUND else Paint.Join.MITER
        }
        if (tag == "rect") canvas.drawRect(bounds, strokePaint) else canvas.drawOval(bounds, strokePaint)
    }

    /** One `drawText` per measured line run. */
    private fun drawText(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
        rect: IrRect = layoutRects[node.id] ?: node.rect,
        availableTextRight: Float? = null,
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
        textPaint.letterSpacing =
            if (textPaint.textSize > 0f) CardIrStyle.length(style, "letter-spacing") / textPaint.textSize else 0f
        val wordSpacing = CardIrStyle.length(style, "word-spacing")
        val justify = style["text-align"] == "justify"
        val decorations = style["text-decoration-line"].orEmpty().split(Regex("\\s+"))
        textPaint.isUnderlineText = "underline" in decorations
        textPaint.isStrikeThruText = "line-through" in decorations

        val metrics = textPaint.fontMetrics
        val widestCapturedRun = node.runs.maxOfOrNull { it.rect.w } ?: 0f
        val reflowText = node.text ?: node.runs.joinToString(separator = "") { it.text }
        if (reflowText.isNotEmpty() && widestCapturedRun > rect.w + 0.5f && rect.w > 0f) {
            val alignment =
                when (style["text-align"]) {
                    "center" -> Layout.Alignment.ALIGN_CENTER
                    "right" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            val lineHeight = CardIrStyle.length(style, "line-height").toInt()
            val layoutText =
                if (lineHeight > 0) {
                    SpannableString(reflowText).apply {
                        setSpan(FixedLineHeightSpan(lineHeight), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    reflowText
                }
            val builder =
                StaticLayout.Builder
                    .obtain(layoutText, 0, layoutText.length, textPaint, rect.w.toInt().coerceAtLeast(1))
                    .setAlignment(alignment)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setIncludePad(false)
            if (android.os.Build.VERSION.SDK_INT >= 26 && justify) {
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            val layout = builder.build()
            val save = canvas.save()
            canvas.translate(rect.x, rect.y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            return
        }
        for ((index, run) in node.runs.withIndex()) {
            if (run.text.isEmpty()) continue
            val runRect =
                run.rect.copy(
                    x = run.rect.x + rect.x - node.rect.x,
                    y = run.rect.y + rect.y - node.rect.y,
                )
            // The run rect is the line box; the baseline sits inside it.
            val baseline = runRect.y + (runRect.h - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            val isLastLine = index == node.runs.lastIndex
            val spaces = run.text.count { it == ' ' }
            val justifySpacing =
                if (justify && !isLastLine && spaces > 0) {
                    (
                        (
                            (availableTextRight?.minus(runRect.x) ?: runRect.w) -
                                textPaint.measureText(run.text) - wordSpacing * spaces
                        ) / spaces
                    ).coerceAtLeast(0f)
                } else {
                    0f
                }
            drawTextWithWordSpacing(canvas, run.text, runRect.x, baseline, wordSpacing + justifySpacing)
        }
    }

    private fun drawTextWithWordSpacing(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        wordSpacing: Float,
    ) {
        if (wordSpacing == 0f || ' ' !in text) {
            canvas.drawText(text, x, baseline, textPaint)
            return
        }
        var cursor = x
        var segmentStart = 0
        var index = 0
        while (index < text.length) {
            if (text[index] != ' ') {
                index++
                continue
            }
            if (segmentStart < index) {
                val word = text.substring(segmentStart, index)
                canvas.drawText(word, cursor, baseline, textPaint)
                cursor += textPaint.measureText(word)
            }
            val spacesStart = index
            while (index < text.length && text[index] == ' ') index++
            val spaces = text.substring(spacesStart, index)
            canvas.drawText(spaces, cursor, baseline, textPaint)
            cursor += textPaint.measureText(spaces) + wordSpacing * spaces.length
            segmentStart = index
        }
        if (segmentStart < text.length) {
            val word = text.substring(segmentStart)
            canvas.drawText(word, cursor, baseline, textPaint)
        }
    }

    /** An image from the media folder. */
    private fun drawImage(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
        rect: IrRect,
    ) {
        val src = node.src ?: return
        val directory = mediaDirectory ?: return
        if (rect.isEmpty) return

        val bitmap =
            bitmaps.getOrPut(src) {
                val file = CardIrMedia.resolve(directory, src) ?: return@getOrPut null
                if (file.isFile) BitmapFactory.decodeFile(file.path) else null
            } ?: return

        val destination =
            if (style["object-fit"] == "contain" && bitmap.width > 0 && bitmap.height > 0) {
                val scale = minOf(rect.w / bitmap.width, rect.h / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = rect.x + (rect.w - width) / 2f
                val top = rect.y + (rect.h - height) / 2f
                RectF(left, top, left + width, top + height)
            } else {
                RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h)
            }
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
    }

    /** The replay button. */
    private fun drawAudio(
        canvas: Canvas,
        rect: IrRect,
    ) {
        if (rect.isEmpty) return
        val size = minOf(rect.w, rect.h)
        if (size <= 0f) return
        val cx = rect.x + rect.w / 2f
        val cy = rect.y + rect.h / 2f
        val radius = size / 2f

        fillPaint.color = AUDIO_BUTTON_BACKGROUND
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // The triangle of Anki's play icon.
        val path = Path()
        path.moveTo(cx - radius * 0.25f, cy - radius * 0.45f)
        path.lineTo(cx + radius * 0.45f, cy)
        path.lineTo(cx - radius * 0.25f, cy + radius * 0.45f)
        path.close()
        fillPaint.color = Color.BLACK
        canvas.drawPath(path, fillPaint)
    }

    /** A typed answer field. */
    private fun drawInput(
        canvas: Canvas,
        node: IrNode,
        style: Map<String, String>,
        rect: IrRect,
    ) {
        if (rect.isEmpty) return
        strokePaint.color = CardIrStyle.color(style, "border-top-color") ?: FALLBACK_COLOR
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f
        canvas.drawRect(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, strokePaint)
    }

    private fun cornerRadii(
        style: Map<String, String>,
        rect: IrRect,
    ): FloatArray {
        val maximum = minOf(rect.w, rect.h) / 2f

        fun radius(corner: String) = CardIrStyle.cornerRadius(style, corner).coerceIn(0f, maximum)

        return floatArrayOf(
            radius("top-left"),
            radius("top-left"),
            radius("top-right"),
            radius("top-right"),
            radius("bottom-right"),
            radius("bottom-right"),
            radius("bottom-left"),
            radius("bottom-left"),
        )
    }

    companion object {
        private const val FALLBACK_COLOR = 0xFF1F2937.toInt()
        private const val AUDIO_BUTTON_BACKGROUND = 0xFFD3D3D3.toInt()
    }
}
