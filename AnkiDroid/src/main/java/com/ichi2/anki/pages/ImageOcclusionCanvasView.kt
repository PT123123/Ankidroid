// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** A small native canvas for creating and adjusting image-occlusion masks. */
class ImageOcclusionCanvasView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        enum class Tool { SELECT, RECTANGLE, ELLIPSE, POLYGON }

        data class Point(
            var x: Float,
            var y: Float,
        )

        data class Mask(
            val type: Tool,
            var left: Float,
            var top: Float,
            var width: Float,
            var height: Float,
            var ordinal: Int? = null,
            var points: List<Point> = emptyList(),
        )

        var masks: MutableList<Mask> = mutableListOf()
            set(value) {
                field = value
                invalidate()
            }
        var tool: Tool = Tool.RECTANGLE
            set(value) {
                field = value
                selectedIndex = -1
                invalidate()
            }
        var onMasksChanged: (() -> Unit)? = null
        private val undoStates = ArrayDeque<List<Mask>>()
        private val redoStates = ArrayDeque<List<Mask>>()

        private var bitmap: Bitmap? = null
        private val imageRect = RectF()
        private val previousImageRect = RectF()
        private val shapeRect = RectF()
        private val shapePath = android.graphics.Path()
        private val maskPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(170, 35, 120, 230)
                style = Paint.Style.FILL
            }
        private val selectedPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f.dp
            }
        private var startX = 0f
        private var startY = 0f
        private var selectedIndex = -1
        private var moving = false
        private var pendingNormalizedMasks: List<Mask>? = null
        private var gestureStart: List<Mask>? = null

        fun setImage(image: Bitmap?) {
            bitmap = image
            updateImageRect()
            invalidate()
        }

        fun removeSelected() {
            if (selectedIndex in masks.indices) {
                rememberChange()
                masks.removeAt(selectedIndex)
                selectedIndex = -1
                onMasksChanged?.invoke()
                invalidate()
            }
        }

        fun undo() {
            if (undoStates.isEmpty()) return
            redoStates.addLast(masks.map(::copyMask))
            masks = undoStates.removeLast().map(::copyMask).toMutableList()
            selectedIndex = -1
            onMasksChanged?.invoke()
        }

        fun redo() {
            if (redoStates.isEmpty()) return
            undoStates.addLast(masks.map(::copyMask))
            masks = redoStates.removeLast().map(::copyMask).toMutableList()
            selectedIndex = -1
            onMasksChanged?.invoke()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(32, 32, 32))
            val image = bitmap ?: return
            canvas.drawBitmap(image, null, imageRect, null)
            masks.forEachIndexed { index, mask ->
                setViewRect(mask, shapeRect)
                when (mask.type) {
                    Tool.ELLIPSE -> canvas.drawOval(shapeRect, maskPaint)
                    Tool.POLYGON -> canvas.drawPath(pathFor(mask), maskPaint)
                    else -> canvas.drawRect(shapeRect, maskPaint)
                }
                if (index == selectedIndex) {
                    when (mask.type) {
                        Tool.ELLIPSE -> canvas.drawOval(shapeRect, selectedPaint)
                        Tool.POLYGON -> canvas.drawPath(pathFor(mask), selectedPaint)
                        else -> canvas.drawRect(shapeRect, selectedPaint)
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStart = masks.map(::copyMask)
                    startX = x
                    startY = y
                    if (tool == Tool.SELECT) {
                        selectedIndex = masks.indexOfLast { contains(it, x, y) }
                        moving = selectedIndex >= 0
                    } else {
                        if (!imageRect.contains(x, y)) return false
                        val mask = Mask(tool, x, y, 0f, 0f)
                        if (tool == Tool.POLYGON) mask.points = listOf(Point(x, y))
                        masks.add(mask)
                        selectedIndex = masks.lastIndex
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tool == Tool.SELECT && moving && selectedIndex in masks.indices) {
                        val mask = masks[selectedIndex]
                        val dx = x - startX
                        val dy = y - startY
                        mask.left = (mask.left + dx).coerceIn(imageRect.left, imageRect.right - mask.width)
                        mask.top = (mask.top + dy).coerceIn(imageRect.top, imageRect.bottom - mask.height)
                        if (mask.points.isNotEmpty()) mask.points = mask.points.map { Point(it.x + dx, it.y + dy) }
                        startX = x
                        startY = y
                    } else if (selectedIndex in masks.indices && tool != Tool.SELECT) {
                        val mask = masks[selectedIndex]
                        if (tool == Tool.POLYGON) {
                            val last = mask.points.lastOrNull()
                            if (last == null || abs(last.x - x) + abs(last.y - y) > 8f.dp) {
                                mask.points =
                                    mask.points +
                                    Point(x.coerceIn(imageRect.left, imageRect.right), y.coerceIn(imageRect.top, imageRect.bottom))
                                val bounds = boundsFor(mask.points)
                                mask.left = bounds.left
                                mask.top = bounds.top
                                mask.width = bounds.width()
                                mask.height = bounds.height()
                            }
                            invalidate()
                            return true
                        }
                        val left = min(startX, x).coerceAtLeast(imageRect.left)
                        val top = min(startY, y).coerceAtLeast(imageRect.top)
                        val right = maxOf(startX, x).coerceAtMost(imageRect.right)
                        val bottom = maxOf(startY, y).coerceAtMost(imageRect.bottom)
                        mask.left = left
                        mask.top = top
                        mask.width = right - left
                        mask.height = bottom - top
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    moving = false
                    if (selectedIndex in masks.indices &&
                        (masks[selectedIndex].width < 8f.dp || (tool == Tool.POLYGON && masks[selectedIndex].points.size < 3)) &&
                        tool != Tool.SELECT
                    ) {
                        masks.removeAt(selectedIndex)
                        selectedIndex = -1
                    }
                    val before = gestureStart
                    if (before != null && before != masks) rememberChange(before)
                    gestureStart = null
                    onMasksChanged?.invoke()
                    invalidate()
                    return true
                }
            }
            return true
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateImageRect()
        }

        fun normalizedMasks(): List<Mask> {
            if (imageRect.isEmpty) return emptyList()
            return pendingNormalizedMasks ?: masks.map { mask ->
                Mask(
                    mask.type,
                    ((mask.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
                    ((mask.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f),
                    (mask.width / imageRect.width()).coerceIn(0f, 1f),
                    (mask.height / imageRect.height()).coerceIn(0f, 1f),
                    mask.ordinal,
                    mask.points.map {
                        Point(
                            ((it.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
                            (
                                (it.y - imageRect.top) /
                                    imageRect.height()
                            ).coerceIn(0f, 1f),
                        )
                    },
                )
            }
        }

        fun setNormalizedMasks(value: List<Mask>) {
            pendingNormalizedMasks = value
            if (!imageRect.isEmpty) {
                masks = value.map(::toAbsoluteMask).toMutableList()
                pendingNormalizedMasks = null
            }
            invalidate()
        }

        private fun setViewRect(
            mask: Mask,
            target: RectF,
        ) {
            if (mask.left in 0f..1f && mask.top in 0f..1f && mask.width in 0f..1f && mask.height in 0f..1f) {
                target.set(
                    imageRect.left + mask.left * imageRect.width(),
                    imageRect.top + mask.top * imageRect.height(),
                    imageRect.left + (mask.left + mask.width) * imageRect.width(),
                    imageRect.top + (mask.top + mask.height) * imageRect.height(),
                )
            } else {
                target.set(mask.left, mask.top, mask.left + mask.width, mask.top + mask.height)
            }
        }

        private fun toViewRect(mask: Mask) = RectF().also { setViewRect(mask, it) }

        private fun contains(
            mask: Mask,
            x: Float,
            y: Float,
        ): Boolean = toViewRect(mask).contains(x, y)

        private fun pathFor(mask: Mask): android.graphics.Path =
            shapePath.apply {
                reset()
                mask.points.firstOrNull()?.let { first ->
                    moveTo(first.x, first.y)
                    mask.points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
            }

        private fun boundsFor(points: List<Point>): RectF {
            val left = points.minOfOrNull { it.x } ?: 0f
            val top = points.minOfOrNull { it.y } ?: 0f
            val right = points.maxOfOrNull { it.x } ?: left
            val bottom = points.maxOfOrNull { it.y } ?: top
            return RectF(left, top, right, bottom)
        }

        private fun copyMask(mask: Mask) = mask.copy(points = mask.points.map { Point(it.x, it.y) })

        private fun updateImageRect() {
            val image = bitmap ?: return
            if (width <= 0 || height <= 0) return
            previousImageRect.set(imageRect)
            val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
            val drawnWidth = image.width * scale
            val drawnHeight = image.height * scale
            imageRect.set((width - drawnWidth) / 2, (height - drawnHeight) / 2, (width + drawnWidth) / 2, (height + drawnHeight) / 2)
            if (!previousImageRect.isEmpty && pendingNormalizedMasks == null && previousImageRect != imageRect) {
                masks.forEach { mask ->
                    mask.left = imageRect.left + ((mask.left - previousImageRect.left) / previousImageRect.width()) * imageRect.width()
                    mask.top = imageRect.top + ((mask.top - previousImageRect.top) / previousImageRect.height()) * imageRect.height()
                    mask.width = mask.width / previousImageRect.width() * imageRect.width()
                    mask.height = mask.height / previousImageRect.height() * imageRect.height()
                    if (mask.points.isNotEmpty()) {
                        mask.points =
                            mask.points.map { point ->
                                Point(
                                    imageRect.left + ((point.x - previousImageRect.left) / previousImageRect.width()) * imageRect.width(),
                                    imageRect.top + ((point.y - previousImageRect.top) / previousImageRect.height()) * imageRect.height(),
                                )
                            }
                    }
                }
            }
            pendingNormalizedMasks?.let { normalized ->
                masks = normalized.map(::toAbsoluteMask).toMutableList()
                pendingNormalizedMasks = null
            }
        }

        private fun toAbsoluteMask(mask: Mask) =
            Mask(
                mask.type,
                imageRect.left + mask.left * imageRect.width(),
                imageRect.top + mask.top * imageRect.height(),
                mask.width * imageRect.width(),
                mask.height * imageRect.height(),
                mask.ordinal,
                mask.points.map { Point(imageRect.left + it.x * imageRect.width(), imageRect.top + it.y * imageRect.height()) },
            )

        private fun rememberChange(state: List<Mask>? = masks.map(::copyMask)) {
            if (state != null) undoStates.addLast(state.map(::copyMask))
            redoStates.clear()
        }

        private val Float.dp: Float get() = this * resources.displayMetrics.density
    }
