// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.statistics

import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The range selectors the stats page offers; [days] is what the backend's `graphs` takes, 0 = all. */
enum class StatsRange(
    val days: Int,
) {
    MONTH(31),
    THREE_MONTHS(90),
    YEAR(365),
    ALL(0),
}

/** One bar of a histogram: [x0] inclusive, [x1] exclusive, in days (or interval days). */
data class Bar(
    val x0: Long,
    val x1: Long,
    val value: Long,
)

/** The colour and label of one stacked series. */
data class SeriesSpec(
    val label: String,
    val color: Long,
)

/** Reviews split per day, in counts (values are reviews) or millis (values are times). */
data class ReviewsPerDay(
    val learn: Long,
    val relearn: Long,
    val young: Long,
    val mature: Long,
    val filtered: Long,
) {
    val total: Long get() = learn + relearn + young + mature + filtered

    companion object {
        const val MATURE = 0
        const val YOUNG = 1
        const val RELEARN = 2
        const val LEARN = 3
        const val FILTERED = 4
    }
}

/** Single-series histograms: bucket [data] day offsets into at most [maxBars] even bins in [minDay]..[maxDay]. */
fun bucketDays(
    data: Map<Long, Long>,
    maxBars: Int,
    minDay: Long,
    maxDay: Long,
): List<Bar> {
    val inRange = data.filterKeys { it in minDay..maxDay }
    if (inRange.isEmpty() || maxDay <= minDay) return emptyList()
    val span = maxDay - minDay + 1
    val bucketSize = maxOf(1, ceil(span.toDouble() / maxBars).toLong())
    val bars = mutableListOf<Bar>()
    var start = minDay
    while (start <= maxDay) {
        val end = start + bucketSize
        val value = inRange.filterKeys { it in start until end }.values.sum()
        bars.add(Bar(start, end, value))
        start = end
    }
    return bars
}

/** Reviews (or review time) bucketed like [bucketDays]; returns one 5-entry stack per bin (see [ReviewsPerDay]). */
fun bucketReviews(
    data: Map<Long, ReviewsPerDay>,
    maxBars: Int,
    minDay: Long,
    maxDay: Long,
): List<List<Bar>> {
    val inRange = data.filterKeys { it in minDay..maxDay }
    if (inRange.isEmpty() || maxDay <= minDay) return emptyList()
    val span = maxDay - minDay + 1
    val bucketSize = maxOf(1, ceil(span.toDouble() / maxBars).toLong())
    val bins = mutableListOf<List<Bar>>()
    var start = minDay
    while (start <= maxDay) {
        val end = start + bucketSize
        val sums = LongArray(5)
        for ((day, review) in inRange) {
            if (day !in start until end) continue
            sums[ReviewsPerDay.MATURE] += review.mature
            sums[ReviewsPerDay.YOUNG] += review.young
            sums[ReviewsPerDay.RELEARN] += review.relearn
            sums[ReviewsPerDay.LEARN] += review.learn
            sums[ReviewsPerDay.FILTERED] += review.filtered
        }
        bins.add(List(5) { idx -> Bar(start, end, sums[idx]) })
        start = end
    }
    return bins
}

/** Weighted median of a histogram map (interval -> cards). */
fun weightedMedian(data: Map<Long, Long>): Long? {
    val total = data.values.sum()
    if (total == 0L) return null
    val half = total / 2.0
    var running = 0L
    for ((key, count) in data.entries.sortedBy { it.key }) {
        running += count
        if (running >= half) return key
    }
    return null
}

/** Review counts keyed by day offset (0 = today, negative = past) mapped to absolute dates. */
fun reviewsToDateCounts(
    counts: Map<Long, Long>,
    today: LocalDate,
): Map<LocalDate, Long> =
    counts.entries
        .groupBy({ today.plusDays(it.key) }, { it.value })
        .mapValues { (_, values) -> values.sum() }

/** 1234 -> "1.2k", 56 -> "56"; used for axis ticks. */
fun formatCompact(value: Long): String {
    if (value < 10_000) return value.toString()
    val thousands = value / 1000.0
    return if (thousands < 100) {
        String.format(Locale.getDefault(), "%.1fk", thousands)
    } else {
        "${thousands.roundToLong()}k"
    }
}

/** "12.3"; one decimal unless integral. */
fun formatPercent(value: Double): String =
    if (value == floor(value)) value.roundToLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

/** Elapsed-time label in the desktop's compact style: 5s, 13.5m, 2.3h, 1.4d, 3mo. */
fun formatMillis(millis: Long): String {
    val seconds = millis / 1000.0
    return when {
        seconds < 60 -> "${secondsValue(seconds)}s"
        seconds < 3600 -> "${secondsValue(seconds / 60)}m"
        seconds < 86_400 -> "${secondsValue(seconds / 3600)}h"
        seconds < 86_400 * 60 -> "${secondsValue(seconds / 86_400)}d"
        else -> "${secondsValue(seconds / (86_400 * 30))}mo"
    }
}

/** A day count as a compact span: 13d, 1.5mo, 2.1y. */
fun formatDays(days: Long): String =
    when {
        days < 60 -> "${days}d"
        days < 365 -> formatPercent(days / 30.4) + "mo"
        else -> formatPercent(days / 365.0) + "y"
    }

/** Epoch milliseconds as a short local date-time: 2026-11-01 14:30. */
fun formatEpochMs(epochMillis: Long): String {
    val dateTime =
        java.time.Instant
            .ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
    return java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(dateTime)
}

/** Epoch milliseconds as a short local date: 2026-11-01. */
fun formatEpochDay(epochMillis: Long): String {
    val date =
        java.time.Instant
            .ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
    return java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd", Locale.getDefault())
        .format(date)
}

private fun secondsValue(value: Double): String =
    if (value >= 100) value.roundToLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

/** sqrt stretch for the heatmap colour ramp, matching the desktop's scaleSequentialSqrt. */
fun sqrtScale(
    value: Long,
    max: Long,
): Float = if (max <= 0) 0f else sqrt(value.toDouble() / max.toDouble()).toFloat()

/** A "blues" ramp, light to dark, for the heatmap. */
val HEATMAP_BLUES =
    intArrayOf(
        0xFFF1F6FC.toInt(),
        0xFFD0E1F2.toInt(),
        0xFF7FB3D8.toInt(),
        0xFF2E75B6.toInt(),
        0xFF08306B.toInt(),
    )

fun blendRamp(
    t: Float,
    ramp: IntArray = HEATMAP_BLUES,
): Int {
    val clamped = t.coerceIn(0f, 1f) * (ramp.size - 1)
    val index = clamped.toInt()
    val fraction = clamped - index
    if (index >= ramp.size - 1) return ramp.last()
    return lerpColor(ramp[index], ramp[index + 1], fraction)
}

fun lerpColor(
    start: Int,
    end: Int,
    fraction: Float,
): Int {
    val a = (start ushr 24) + (((end ushr 24) - (start ushr 24)) * fraction).toInt()
    val r = ((start shr 16) and 0xFF) + ((((end shr 16) and 0xFF) - ((start shr 16) and 0xFF)) * fraction).toInt()
    val g = ((start shr 8) and 0xFF) + ((((end shr 8) and 0xFF) - ((start shr 8) and 0xFF)) * fraction).toInt()
    val b = (start and 0xFF) + (((end and 0xFF) - (start and 0xFF)) * fraction).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
