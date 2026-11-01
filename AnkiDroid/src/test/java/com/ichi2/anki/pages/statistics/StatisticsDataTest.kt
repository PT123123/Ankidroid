// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.statistics

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import java.time.LocalDate

class StatisticsDataTest {
    @Test
    fun `bucketDays covers the range in even bins`() {
        val data = mapOf(-30L to 5L, -15L to 7L, 0L to 2L)
        val bars = bucketDays(data, maxBars = 3, minDay = -30, maxDay = 0)
        // span 31 over 3 bins -> bucket size 11: [-30,-19), [-19,-8), [-8,3)
        assertThat(bars.map { it.value }, contains(5L, 7L, 2L))
        assertThat(bars.first().x0, equalTo(-30L))
        assertThat(bars.last().x1, equalTo(3L))
    }

    @Test
    fun `bucketDays drops out-of-range days`() {
        val data = mapOf(-100L to 9L, -5L to 1L)
        val bars = bucketDays(data, maxBars = 10, minDay = -10, maxDay = 0)
        assertThat(bars.sumOf { it.value }, equalTo(1L))
    }

    @Test
    fun `bucketReviews stacks all five series`() {
        val data =
            mapOf(
                -2L to ReviewsPerDay(learn = 1, relearn = 2, young = 3, mature = 4, filtered = 5),
                -1L to ReviewsPerDay(learn = 1, relearn = 0, young = 0, mature = 0, filtered = 0),
            )
        val bins = bucketReviews(data, maxBars = 4, minDay = -2, maxDay = 0)
        // daily buckets (span 3 fits in 4)
        assertThat(bins.size, equalTo(3))
        assertThat(bins[0][ReviewsPerDay.LEARN].value, equalTo(1L))
        assertThat(bins[0][ReviewsPerDay.MATURE].value, equalTo(4L))
        assertThat(bins[0][ReviewsPerDay.FILTERED].value, equalTo(5L))
        assertThat(bins[1][ReviewsPerDay.LEARN].value, equalTo(1L))
    }

    @Test
    fun `weightedMedian finds the middle card's interval`() {
        val data = mapOf(1L to 10L, 5L to 10L, 30L to 1L)
        // 21 cards; the 10.5th card sits in the interval-5 bucket
        assertThat(weightedMedian(data), equalTo(5L))
        assertThat(weightedMedian(emptyMap()), equalTo(null))
    }

    @Test
    fun `reviewsToDateCounts maps offsets onto absolute dates`() {
        val today = LocalDate.of(2026, 11, 1)
        val counts = mapOf(0L to 3L, -1L to 4L, 1L to 2L)
        val byDate = reviewsToDateCounts(counts, today)
        assertThat(byDate[today], equalTo(3L))
        assertThat(byDate[today.minusDays(1)], equalTo(4L))
        assertThat(byDate[today.plusDays(1)], equalTo(2L))
    }

    @Test
    fun `formatters`() {
        assertThat(formatCompact(56), equalTo("56"))
        assertThat(formatCompact(12_340), equalTo("12.3k"))
        assertThat(formatCompact(234_000), equalTo("234k"))
        assertThat(formatPercent(12.0), equalTo("12"))
        assertThat(formatPercent(12.34), equalTo("12.3"))
        assertThat(formatMillis(4_000), equalTo("4.0s"))
        assertThat(formatMillis(90_000), equalTo("1.5m"))
        assertThat(formatMillis(7_200_000), equalTo("2.0h"))
    }

    @Test
    fun `niceCeiling snaps to 1-2-5 steps`() {
        assertThat(niceCeiling(1), equalTo(1))
        assertThat(niceCeiling(7), equalTo(10))
        assertThat(niceCeiling(31), equalTo(50))
        assertThat(niceCeiling(999), equalTo(1000))
    }

    @Test
    fun `heatmap ramp is dark for busy days`() {
        assertThat(blendRamp(0f), equalTo(HEATMAP_BLUES.first()))
        assertThat(blendRamp(1f), equalTo(HEATMAP_BLUES.last()))
        assertThat(sqrtScale(4, 16), equalTo(0.5f))
    }
}
