// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.statistics

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Gate for the shared heatmap geometry: the renderer draws from [heatmapGeometry]
 * and the tap hit-test resolves through [heatmapDateAt], so a tap on the centre
 * of a drawn cell must return exactly that cell's date.
 */
class HeatmapGeometryTest {
    // 2026-01-01 is a Thursday; with a Sunday start the grid begins 2025-12-28
    private val geometry = heatmapGeometry(1200f, 160f, 2026, DayOfWeek.SUNDAY, labelPx = 10f)

    private fun centreOf(date: LocalDate): Pair<Float, Float> {
        val offset = ChronoUnit.DAYS.between(geometry.firstCell, date).toInt()
        val column = offset / 7
        val row = offset % 7
        return Pair(
            geometry.labelLane + (column + 0.5f) * geometry.cell,
            geometry.monthLane + (row + 0.5f) * geometry.cell,
        )
    }

    private fun dateAt(date: LocalDate): LocalDate? {
        val (x, y) = centreOf(date)
        return heatmapDateAt(x, y, geometry)
    }

    @Test
    fun `grid starts on the configured weekday before january first`() {
        assertThat(geometry.firstCell, equalTo(LocalDate.of(2025, 12, 28)))
        assertThat(geometry.lastDate, equalTo(LocalDate.of(2026, 12, 31)))
        val monday = heatmapGeometry(1200f, 160f, 2026, DayOfWeek.MONDAY, labelPx = 10f)
        assertThat(monday.firstCell, equalTo(LocalDate.of(2025, 12, 29)))
    }

    @Test
    fun `tapping a cell centre returns that date`() {
        assertThat(dateAt(LocalDate.of(2026, 3, 15)), equalTo(LocalDate.of(2026, 3, 15)))
        assertThat(dateAt(LocalDate.of(2026, 7, 4)), equalTo(LocalDate.of(2026, 7, 4)))
        // boundary cells: leading previous-year cell and Dec 31 itself
        assertThat(dateAt(geometry.firstCell), equalTo(LocalDate.of(2025, 12, 28)))
        assertThat(dateAt(geometry.lastDate), equalTo(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `adjacent cells resolve to adjacent dates`() {
        // control: proves the mapping moves with the tap, not a constant answer
        val day = LocalDate.of(2026, 6, 10)
        val (x, y) = centreOf(day)
        assertThat(heatmapDateAt(x + geometry.cell, y, geometry), equalTo(day.plusDays(7)))
        assertThat(heatmapDateAt(x, y + geometry.cell, geometry), equalTo(day.plusDays(1)))
    }

    @Test
    fun `label and month lanes are not tappable`() {
        val (_, y) = centreOf(LocalDate.of(2026, 6, 10))
        val (x, _) = centreOf(LocalDate.of(2026, 6, 10))
        assertThat(heatmapDateAt(geometry.labelLane - 1f, y, geometry), equalTo(null))
        assertThat(heatmapDateAt(x, geometry.monthLane - 1f, geometry), equalTo(null))
    }

    @Test
    fun `taps beyond the grid return null`() {
        val x = geometry.labelLane + geometry.weeks * geometry.cell + 1f
        val y = geometry.monthLane + 7f * geometry.cell + 1f
        assertThat(heatmapDateAt(x, y, geometry), equalTo(null))
    }

    @Test
    fun `trailing rows after december 31 return null`() {
        // Dec 31 2026 is a Thursday (row 4 with Sunday start); rows 5-6 of the
        // last column fall in 2027 and must not resolve
        val (x, y) = centreOf(geometry.lastDate)
        assertThat(heatmapDateAt(x, y + geometry.cell, geometry), equalTo(null))
        assertThat(heatmapDateAt(x, y + 2f * geometry.cell, geometry), equalTo(null))
    }

    @Test
    fun `degenerate size resolves nothing`() {
        val tiny = heatmapGeometry(10f, 10f, 2026, DayOfWeek.SUNDAY, labelPx = 10f)
        assertThat(heatmapDateAt(5f, 5f, tiny), equalTo(null))
    }
}
