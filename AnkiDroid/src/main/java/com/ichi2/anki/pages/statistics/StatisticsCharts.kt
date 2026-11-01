// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.statistics

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/** "Nice" ceiling for a y axis: 1/2/5 * 10^n above [value]. */
fun niceCeiling(value: Long): Long {
    if (value <= 0) return 1
    var magnitude = 1L
    while (magnitude * 10 <= value) magnitude *= 10
    for (step in longArrayOf(1, 2, 5, 10)) {
        if (value <= step * magnitude) return step * magnitude
    }
    return 10 * magnitude
}

/** A vertical bar chart (single or stacked series) drawn on a Canvas, desktop-styled. */
@Composable fun HistogramChart(
    bins: List<List<Bar>>,
    seriesPerBin: List<List<SeriesSpec>>,
    modifier: Modifier = Modifier,
    yFormat: (Long) -> String = ::formatCompact,
    xLabel: (Bar) -> String,
    labelEvery: Int = 5,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val yMax = niceCeiling(bins.maxOfOrNull { bin -> bin.sumOf { it.value } } ?: 0L)
    Canvas(modifier = modifier.height(180.dp).fillMaxWidth()) {
        if (bins.isEmpty() || yMax == 0L) return@Canvas
        val leftPad = 2.6f * labelPx
        val bottomPad = 1.6f * labelPx
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - bottomPad
        val paint =
            Paint().apply {
                isAntiAlias = true
                color = labelColor.toArgb()
                textSize = labelPx
            }
        // horizontal grid lines + y labels
        val tickCount = 4
        for (i in 0..tickCount) {
            val value = yMax * i / tickCount
            val y = chartHeight - chartHeight * i / tickCount
            drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
            if (i > 0) {
                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(yFormat(value), leftPad - 4f, y + labelPx / 3, paint)
            }
        }
        // bars
        val slot = chartWidth / bins.size
        val barWidth = (slot * 0.82f).coerceAtLeast(1f)
        for ((binIndex, bin) in bins.withIndex()) {
            var stackTop = chartHeight
            val x0 = leftPad + binIndex * slot + (slot - barWidth) / 2
            val series = seriesPerBin.getOrElse(binIndex) { emptyList() }
            for ((seriesIndex, bar) in bin.withIndex()) {
                if (bar.value <= 0) continue
                val barHeight = chartHeight * bar.value / yMax
                drawRect(
                    color = Color(series.getOrNull(seriesIndex)?.color ?: 0xFF2E75B6),
                    topLeft = Offset(x0, stackTop - barHeight),
                    size = Size(barWidth, barHeight),
                )
                stackTop -= barHeight
            }
        }
        // x labels on a few bars
        paint.textAlign = Paint.Align.CENTER
        val step = maxOf(1, ceil(bins.size.toFloat() / labelEvery).toInt())
        for ((binIndex, bin) in bins.withIndex()) {
            if (binIndex % step != 0) continue
            val x0 = leftPad + binIndex * slot + slot / 2
            drawContext.canvas.nativeCanvas.drawText(xLabel(bin.first()), x0, size.height - 2f, paint)
        }
    }
}

/** One horizontal stacked bar with proportional segments. */
@Composable fun HorizontalStackedBar(
    segments: List<Pair<SeriesSpec, Long>>,
    modifier: Modifier = Modifier,
) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    val cornerRadius = with(LocalDensity.current) { 3.dp.toPx() }
    Canvas(modifier = modifier.height(14.dp).fillMaxWidth()) {
        var x = 0f
        for ((spec, value) in segments) {
            if (value <= 0) continue
            val width = size.width * value / total
            drawRoundRect(
                color = Color(spec.color),
                topLeft = Offset(x, 0f),
                size = Size(width, size.height),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            )
            x += width
        }
    }
}

/** Legend dot + label. */
@Composable fun LegendEntry(
    spec: SeriesSpec,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawRect(Color(spec.color))
        }
        Text(
            text = spec.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * The contribution calendar: one column per week, one row per weekday.
 * [counts] is keyed by absolute date.
 */
@Composable fun CalendarHeatmap(
    year: Int,
    counts: Map<LocalDate, Long>,
    firstDayOfWeek: DayOfWeek,
    modifier: Modifier = Modifier,
    todayRing: Color = Color(0xFF43A047),
) {
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current
    val labelPx = with(density) { 8.dp.toPx() }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val today = LocalDate.now()

    val startDate = LocalDate.of(year, 1, 1)
    val endDate = LocalDate.of(year, 12, 31)
    var firstCell = startDate
    while (firstCell.dayOfWeek != firstDayOfWeek) {
        firstCell = firstCell.minusDays(1)
    }
    val weeks = (ChronoUnit.DAYS.between(firstCell, endDate) / 7).toInt() + 1
    val maxCount = counts.values.maxOrNull() ?: 0L

    Canvas(modifier = modifier.height(130.dp).fillMaxWidth()) {
        val labelLane = 3.2f * labelPx
        val monthLane = 1.8f * labelPx
        val cell = minOf((size.width - labelLane) / weeks, (size.height - monthLane) / 7f)
        val paint =
            Paint().apply {
                isAntiAlias = true
                color = labelColor.toArgb()
                textSize = labelPx
            }
        val corner = cell * 0.22f
        // weekday labels, once
        paint.textAlign = Paint.Align.RIGHT
        for (offset in intArrayOf(1, 3, 5)) {
            val day = firstDayOfWeek.plus(offset.toLong())
            drawContext.canvas.nativeCanvas.drawText(
                day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                labelLane - 2f,
                monthLane + offset * cell + cell * 0.8f,
                paint,
            )
        }
        // month labels + cells
        var date = firstCell
        var column = 0
        while (!date.isAfter(endDate)) {
            val x = labelLane + column * cell
            if (date.dayOfMonth == 1) {
                paint.textAlign = Paint.Align.LEFT
                drawContext.canvas.nativeCanvas.drawText(
                    date.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    x,
                    monthLane - 3f,
                    paint,
                )
            }
            val row = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
            val count = counts[date]
            val color =
                when {
                    count == null || count == 0L -> emptyColor
                    else -> Color(blendRamp(sqrtScale(count, maxCount)))
                }
            drawRoundRect(
                color = color,
                topLeft = Offset(x + 1f, monthLane + row * cell + 1f),
                size = Size(cell - 2f, cell - 2f),
                cornerRadius = CornerRadius(corner, corner),
            )
            if (date == today) {
                drawRoundRect(
                    color = todayRing,
                    topLeft = Offset(x + 1f, monthLane + row * cell + 1f),
                    size = Size(cell - 2f, cell - 2f),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 2f),
                )
            }
            if (date.dayOfWeek == firstDayOfWeek.plus(6)) column++
            date = date.plusDays(1)
        }
    }
}
