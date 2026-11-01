// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.statistics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import anki.stats.GraphPreferences
import anki.stats.GraphsResponse
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.pages.viewmodel.StatisticsUiState
import java.time.DayOfWeek
import java.time.LocalDate

// series colours, matching the desktop's greens/reds/oranges/purples families
private const val COLOR_MATURE = 0xFF2E7D32
private const val COLOR_YOUNG = 0xFF81C784
private const val COLOR_LEARN = 0xFFFB8C00
private const val COLOR_RELEARN = 0xFFE53935
private const val COLOR_FILTERED = 0xFF8E24AA
private const val COLOR_BAR = 0xFF2E75B6
private const val COLOR_NEW = 0xFF1976D2
private const val COLOR_SUSPENDED = 0xFF9E9E9E
private const val COLOR_BURIED = 0xFF616161
private const val COLOR_TODAY_RING = 0xFF43A047

@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    onRangeSelected: (StatsRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RangeChips(state.range, onRangeSelected) }
        val data = state.data
        if (data == null) {
            item { LoadingCard(state.loading) }
        } else {
            item { TodayCard(data, state.studiedTodayMessage) }
            item { CalendarCard(data, state.prefs) }
            item { FutureDueCard(data, state.range, state.prefs?.futureDueShowBacklog ?: false) }
            item { ReviewsCard(data, state.range) }
            item { CardCountsCard(data, state.prefs?.cardCountsSeparateInactive ?: false) }
            item { IntervalsCard(data) }
            if (data.fsrs) {
                item { StabilityCard(data) }
                item { DifficultyCard(data) }
                item { RetrievabilityCard(data) }
            } else {
                item { EasesCard(data) }
            }
            item { AddedCard(data) }
            item { HoursCard(data, state.range) }
            item { ButtonsCard(data, state.range) }
            item { TrueRetentionCard(data) }
        }
    }
}

@Composable
private fun RangeChips(
    current: StatsRange,
    onRangeSelected: (StatsRange) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatsRange.entries.forEach { range ->
            FilterChip(
                selected = range == current,
                onClick = { onRangeSelected(range) },
                label = { Text(rangeLabel(range)) },
            )
        }
    }
}

private fun rangeLabel(range: StatsRange): String =
    when (range) {
        StatsRange.MONTH -> "31d"
        StatsRange.THREE_MONTHS -> "90d"
        StatsRange.YEAR -> "365d"
        StatsRange.ALL -> TR.statisticsRangeAllTime()
    }

@Composable
fun StatsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun LoadingCard(loading: Boolean) {
    StatsCard(title = TR.statisticsTitle()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) CircularProgressIndicator() else Text(TR.statisticsNoData())
        }
    }
}

@Composable
private fun NoData(modifier: Modifier = Modifier) {
    Text(
        TR.statisticsNoData(),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
    )
}

@Composable
private fun SummaryTable(rows: List<Pair<String, String>>) {
    rows.forEach { (label, value) ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TodayCard(
    data: GraphsResponse,
    studiedTodayMessage: String,
) {
    val today = data.today
    val lines =
        if (today.answerCount == 0) {
            listOf(TR.statisticsTodayNoCards())
        } else {
            val again = today.answerCount - today.correctCount
            val againLine =
                TR.statisticsTodayAgainCount() + " $again (" + formatPercent(again * 100.0 / today.answerCount) + "%)"
            val typeCounts =
                TR.statisticsTodayTypeCounts(
                    today.learnCount,
                    today.reviewCount,
                    today.relearnCount,
                    today.earlyReviewCount,
                )
            val matureLine =
                if (today.matureCount > 0) {
                    TR.statisticsTodayCorrectMature(
                        today.matureCorrect,
                        today.matureCount,
                        formatPercent(today.matureCorrect * 100.0 / today.matureCount),
                    )
                } else {
                    TR.statisticsTodayNoMatureCards()
                }
            listOf(studiedTodayMessage, againLine, typeCounts, matureLine)
        }
    StatsCard(title = TR.statisticsTodayTitle()) {
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun CalendarCard(
    data: GraphsResponse,
    prefs: GraphPreferences?,
) {
    var year by remember { mutableStateOf(LocalDate.now().year) }
    val firstDay =
        when (prefs?.calendarFirstDayOfWeek) {
            GraphPreferences.Weekday.MONDAY -> DayOfWeek.MONDAY
            GraphPreferences.Weekday.FRIDAY -> DayOfWeek.FRIDAY
            GraphPreferences.Weekday.SATURDAY -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    val counts =
        reviewsToDateCounts(
            data.reviews.countMap.entries.associate { (k, v) ->
                k.toLong() to
                    (v.learn + v.relearn + v.young + v.mature + v.filtered).toLong()
            },
            LocalDate.now(),
        )
    StatsCard(title = TR.statisticsCalendarTitle()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { year -= 1 }) { Text("‹") }
            Text(
                year.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { year += 1 }) { Text("›") }
        }
        CalendarHeatmap(year = year, counts = counts, firstDayOfWeek = firstDay, todayRing = Color(COLOR_TODAY_RING))
    }
}

@Composable
private fun FutureDueCard(
    data: GraphsResponse,
    range: StatsRange,
    showBacklog: Boolean,
) {
    val futureDue = data.futureDue
    val map = futureDue.futureDueMap.entries.associate { (k, v) -> k.toLong() to v.toLong() }
    StatsCard(title = TR.statisticsFutureDueTitle(), subtitle = TR.statisticsFutureDueSubtitle()) {
        if (map.isEmpty()) {
            NoData()
        } else {
            val minDay = if (showBacklog) minOf(0, map.keys.min()) else 0
            val maxDay =
                when (range) {
                    StatsRange.ALL -> map.keys.max()
                    else -> minOf(range.days.toLong(), map.keys.max())
                }
            val bars = bucketDays(map, maxBars = 36, minDay = minDay, maxDay = maxDay)
            if (bars.isEmpty()) {
                NoData()
            } else {
                HistogramChart(
                    bins = bars.map { listOf(it) },
                    seriesPerBin = bars.map { listOf(SeriesSpec("", COLOR_BAR)) },
                    xLabel = { it.x0.toString() },
                )
                val total = map.filterKeys { it in minDay..maxDay }.values.sum()
                val periodDays = (maxDay - minDay).coerceAtLeast(1)
                SummaryTable(
                    listOf(
                        TR.statisticsTotal() to TR.statisticsReviews(total.toInt()),
                        TR.statisticsAverage() to TR.statisticsReviewsPerDay((total / periodDays).toInt()),
                        TR.statisticsDueTomorrow() to TR.statisticsReviews(map[1L]?.toInt() ?: 0),
                        TR.statisticsDailyLoad() to TR.statisticsReviewsPerDay(futureDue.dailyLoad),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ReviewsCard(
    data: GraphsResponse,
    range: StatsRange,
) {
    var showTime by remember { mutableStateOf(false) }
    val source = if (showTime) data.reviews.timeMap else data.reviews.countMap
    val converted =
        source.entries.associate { (k, v) ->
            k.toLong() to ReviewsPerDay(v.learn.toLong(), v.relearn.toLong(), v.young.toLong(), v.mature.toLong(), v.filtered.toLong())
        }
    val minDay =
        when (range) {
            StatsRange.MONTH -> -30L
            StatsRange.THREE_MONTHS -> -89L
            StatsRange.YEAR -> -364L
            StatsRange.ALL -> minOf(-1L, converted.keys.minOrNull() ?: -1L)
        }
    val bins = bucketReviews(converted, maxBars = 36, minDay = minDay, maxDay = 0)
    val series =
        listOf(
            SeriesSpec(TR.statisticsCountsMatureCards(), COLOR_MATURE),
            SeriesSpec(TR.statisticsCountsYoungCards(), COLOR_YOUNG),
            SeriesSpec(TR.statisticsCountsRelearningCards(), COLOR_RELEARN),
            SeriesSpec(TR.statisticsCountsLearningCards(), COLOR_LEARN),
            SeriesSpec(TR.statisticsCountsFilteredCards(), COLOR_FILTERED),
        )
    val studiedDays = converted.filterKeys { it in minDay..0 }.count { it.value.total > 0 }
    val periodDays = 0 - minDay + 1
    val total = bins.sumOf { bin -> bin.sumOf { it.value } }
    StatsCard(
        title = TR.statisticsReviewsTitle(),
        subtitle = if (showTime) TR.statisticsReviewsTimeSubtitle() else TR.statisticsReviewsCountSubtitle(),
    ) {
        if (bins.isEmpty() || total == 0L) {
            NoData()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TR.statisticsReviewsTimeCheckbox(), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showTime, onCheckedChange = { showTime = it }, modifier = Modifier.padding(start = 8.dp))
            }
            HistogramChart(
                bins = bins,
                seriesPerBin = bins.map { series },
                yFormat = if (showTime) ::formatMillis else ::formatCompact,
                xLabel = { it.x0.toString() },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                series.forEach { LegendEntry(it) }
            }
            SummaryTable(
                listOf(
                    TR.statisticsDaysStudied() to "$studiedDays/$periodDays",
                    TR.statisticsTotal() to if (showTime) formatMillis(total) else TR.statisticsReviews(total.toInt()),
                    TR.statisticsAverageOverPeriod() to
                        if (showTime) {
                            formatMillis(total / periodDays)
                        } else {
                            TR.statisticsReviewsPerDay((total / periodDays).toInt())
                        },
                ),
            )
        }
    }
}

@Composable
private fun CardCountsCard(
    data: GraphsResponse,
    separateInactive: Boolean,
) {
    val specs =
        listOf(
            SeriesSpec(TR.statisticsCountsNewCards(), COLOR_NEW),
            SeriesSpec(TR.statisticsCountsLearningCards(), COLOR_LEARN),
            SeriesSpec(TR.statisticsCountsRelearningCards(), COLOR_RELEARN),
            SeriesSpec(TR.statisticsCountsYoungCards(), COLOR_YOUNG),
            SeriesSpec(TR.statisticsCountsMatureCards(), COLOR_MATURE),
            SeriesSpec(TR.statisticsCountsSuspendedCards(), COLOR_SUSPENDED),
            SeriesSpec(TR.statisticsCountsBuriedCards(), COLOR_BURIED),
        )

    fun segmentsOf(counts: GraphsResponse.CardCounts.Counts): List<Pair<SeriesSpec, Long>> =
        listOf(
            counts.newCards.toLong(),
            counts.learn.toLong(),
            counts.relearn.toLong(),
            counts.young.toLong(),
            counts.mature.toLong(),
            counts.suspended.toLong(),
            counts.buried.toLong(),
        ).mapIndexed { idx, value -> specs[idx] to value }.filter { it.second > 0 }
    val including = segmentsOf(data.cardCounts.includingInactive)
    StatsCard(title = TR.statisticsCountsTitle()) {
        if (including.isEmpty()) {
            NoData()
        } else {
            Text(TR.statisticsCountsTotalCards(), style = MaterialTheme.typography.labelMedium)
            HorizontalStackedBar(segments = including)
            if (separateInactive) {
                Text(
                    TR.statisticsCountsSeparateSuspendedBuriedCards(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalStackedBar(segments = segmentsOf(data.cardCounts.excludingInactive))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                specs.forEach { LegendEntry(it) }
            }
        }
    }
}

@Composable
private fun IntervalsCard(data: GraphsResponse) {
    val map =
        data.intervals.intervalsMap.entries
            .associate { (k, v) -> k.toLong() to v.toLong() }
    StatsCard(title = TR.statisticsIntervalsTitle(), subtitle = TR.statisticsIntervalsSubtitle()) {
        if (map.isEmpty()) {
            NoData()
        } else {
            val bars = bucketDays(map, maxBars = 30, minDay = map.keys.min(), maxDay = map.keys.max())
            HistogramChart(
                bins = bars.map { listOf(it) },
                seriesPerBin = bars.map { listOf(SeriesSpec("", COLOR_BAR)) },
                xLabel = { (it.x0 + 1).toString() },
            )
            val median = weightedMedian(map)
            SummaryTable(listOf(TR.statisticsMedianInterval() to (median?.let { formatDays(it) } ?: "N/A")))
        }
    }
}

@Composable
private fun EasesCard(data: GraphsResponse) {
    EaseHistogramCard(
        title = TR.statisticsCardEaseTitle(),
        subtitle = TR.statisticsCardEaseSubtitle(),
        map =
            data.eases.easesMap.entries
                .associate { (k, v) -> k.toLong() to v.toLong() },
        average = data.eases.average.toDouble(),
        medianLabel = TR.statisticsMedianEase(),
        percentDivisor = 10.0,
    )
}

@Composable
private fun StabilityCard(data: GraphsResponse) {
    val map =
        data.stability.intervalsMap.entries
            .associate { (k, v) -> k.toLong() to v.toLong() }
    StatsCard(title = TR.statisticsCardStabilityTitle(), subtitle = TR.statisticsCardStabilitySubtitle()) {
        if (map.isEmpty()) {
            NoData()
        } else {
            val bars = bucketDays(map, maxBars = 30, minDay = map.keys.min(), maxDay = map.keys.max())
            HistogramChart(
                bins = bars.map { listOf(it) },
                seriesPerBin = bars.map { listOf(SeriesSpec("", COLOR_BAR)) },
                xLabel = { (it.x0 + 1).toString() },
            )
            val median = weightedMedian(map)
            SummaryTable(listOf(TR.statisticsMedianStability() to (median?.let { formatDays(it) } ?: "N/A")))
        }
    }
}

@Composable
private fun DifficultyCard(data: GraphsResponse) {
    EaseHistogramCard(
        title = TR.statisticsCardDifficultyTitle(),
        subtitle = TR.statisticsCardDifficultySubtitle2(),
        map =
            data.difficulty.easesMap.entries
                .associate { (k, v) -> k.toLong() to v.toLong() },
        average = data.difficulty.average.toDouble(),
        medianLabel = TR.statisticsMedianDifficulty(),
        percentDivisor = 10.0,
    )
}

@Composable
private fun EaseHistogramCard(
    title: String,
    subtitle: String,
    map: Map<Long, Long>,
    average: Double,
    medianLabel: String,
    percentDivisor: Double,
) {
    StatsCard(title = title, subtitle = subtitle) {
        if (map.isEmpty()) {
            NoData()
        } else {
            val bars = bucketDays(map, maxBars = 24, minDay = map.keys.min(), maxDay = map.keys.max())
            HistogramChart(
                bins = bars.map { listOf(it) },
                seriesPerBin = bars.map { listOf(SeriesSpec("", COLOR_BAR)) },
                xLabel = { formatPercent(it.x0 / percentDivisor) },
            )
            val median = weightedMedian(map)
            SummaryTable(
                listOf(
                    medianLabel to (median?.let { formatPercent(it / percentDivisor) } ?: "N/A"),
                    TR.statisticsAverage() to formatPercent(average),
                ),
            )
        }
    }
}

@Composable
private fun RetrievabilityCard(data: GraphsResponse) {
    val retrievability = data.retrievability
    val map = retrievability.retrievabilityMap.entries.associate { (k, v) -> k.toLong() to v.toLong() }
    StatsCard(title = TR.statisticsCardRetrievabilityTitle(), subtitle = TR.statisticsRetrievabilitySubtitle()) {
        if (map.isEmpty()) {
            NoData()
        } else {
            val bars = bucketDays(map, maxBars = 24, minDay = map.keys.min(), maxDay = map.keys.max())
            HistogramChart(
                bins = bars.map { listOf(it) },
                seriesPerBin = bars.map { listOf(SeriesSpec("", COLOR_BAR)) },
                xLabel = { formatPercent(it.x0 / 10.0) },
            )
            SummaryTable(
                listOf(
                    TR.statisticsAverageRetrievability() to formatPercent(retrievability.average * 100.0) + "%",
                    TR.statisticsEstimatedTotalKnowledge() to retrievability.sumByCard.toLong().toString(),
                ),
            )
        }
    }
}

@Composable
private fun AddedCard(data: GraphsResponse) {
    val map =
        data.added.addedMap.entries
            .associate { (k, v) -> k.toLong() to v.toLong() }
    StatsCard(title = TR.statisticsAddedTitle(), subtitle = TR.statisticsAddedSubtitle()) {
        if (map.isEmpty()) {
            NoData()
        } else {
            val bars = bucketDays(map, maxBars = 30, minDay = map.keys.min(), maxDay = 0)
            HistogramChart(
                bins = bars.map { listOf(it) },
                seriesPerBin = bars.map { listOf(SeriesSpec("", COLOR_BAR)) },
                xLabel = { it.x0.toString() },
            )
            SummaryTable(listOf(TR.statisticsTotal() to map.values.sum().toString()))
        }
    }
}

@Composable
private fun HoursCard(
    data: GraphsResponse,
    range: StatsRange,
) {
    val hours =
        when (range) {
            StatsRange.MONTH -> data.hours.oneMonthList
            StatsRange.THREE_MONTHS -> data.hours.threeMonthsList
            StatsRange.YEAR -> data.hours.oneYearList
            StatsRange.ALL -> data.hours.allTimeList
        }
    val bars = hours.mapIndexed { idx, hour -> Bar(idx.toLong(), idx.toLong() + 1, hour.total.toLong()) }
    val maxTotal = bars.maxOfOrNull { it.value } ?: 0L
    val total = hours.sumOf { it.total.toLong() }
    val correct = hours.sumOf { it.correct.toLong() }
    StatsCard(title = TR.statisticsHoursTitle(), subtitle = TR.statisticsHoursSubtitle()) {
        if (total == 0L) {
            NoData()
        } else {
            HistogramChart(
                bins = bars.map { listOf(it) },
                seriesPerBin =
                    bars.map { hourBar ->
                        val ratio = hourBar.value.toFloat() / maxTotal
                        listOf(SeriesSpec("", blendRamp(0.15f + 0.7f * ratio).toLong()))
                    },
                xLabel = { if (it.x0 % 2 == 0L) it.x0.toString() else "" },
                labelEvery = 24,
            )
            SummaryTable(
                listOf(
                    TR.statisticsTotal() to TR.statisticsReviews(total.toInt()),
                    TR.statisticsAverage() to formatPercent(correct * 100.0 / total) + "%",
                ),
            )
        }
    }
}

@Composable
private fun ButtonsCard(
    data: GraphsResponse,
    range: StatsRange,
) {
    val buttons =
        when (range) {
            StatsRange.MONTH -> data.buttons.oneMonth
            StatsRange.THREE_MONTHS -> data.buttons.threeMonths
            StatsRange.YEAR -> data.buttons.oneYear
            StatsRange.ALL -> data.buttons.allTime
        }
    val series =
        listOf(
            SeriesSpec(TR.statisticsCountsLearningCards(), COLOR_LEARN),
            SeriesSpec(TR.statisticsCountsYoungCards(), COLOR_YOUNG),
            SeriesSpec(TR.statisticsCountsMatureCards(), COLOR_MATURE),
        )
    val counts =
        listOf(
            buttons.learningList.map { it.toLong() },
            buttons.youngList.map { it.toLong() },
            buttons.matureList.map { it.toLong() },
        )
    val bins: List<List<Bar>> =
        (0 until 4).map { buttonIdx ->
            List(3) { seriesIdx -> Bar(buttonIdx.toLong(), buttonIdx.toLong() + 1, counts[seriesIdx].getOrElse(buttonIdx) { 0 }) }
        }
    StatsCard(title = TR.statisticsAnswerButtonsTitle(), subtitle = TR.statisticsAnswerButtonsSubtitle()) {
        if (bins.all { bin -> bin.all { it.value == 0L } }) {
            NoData()
        } else {
            HistogramChart(
                bins = bins,
                seriesPerBin = bins.map { series },
                xLabel = { (it.x0 + 1).toString() },
                labelEvery = 4,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                series.forEach { LegendEntry(it) }
            }
        }
    }
}

@Composable
private fun TrueRetentionCard(data: GraphsResponse) {
    val retention = data.trueRetention
    StatsCard(title = TR.statisticsTrueRetentionTitle(), subtitle = TR.statisticsTrueRetentionSubtitle()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            TableCell(TR.statisticsTrueRetentionRange(), weight = 2f, header = true)
            TableCell(TR.statisticsTrueRetentionYoung(), weight = 1f, header = true)
            TableCell(TR.statisticsTrueRetentionMature(), weight = 1f, header = true)
            TableCell(TR.statisticsTrueRetentionTotal(), weight = 1f, header = true)
        }
        val rows =
            listOf(
                TR.statisticsTrueRetentionToday() to retention.today,
                TR.statisticsTrueRetentionYesterday() to retention.yesterday,
                TR.statisticsTrueRetentionWeek() to retention.week,
                TR.statisticsTrueRetentionMonth() to retention.month,
                TR.statisticsTrueRetentionYear() to retention.year,
                TR.statisticsTrueRetentionAllTime() to retention.allTime,
            )
        rows.forEach { (title, row) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                TableCell(title, weight = 2f)
                TableCell(retentionPercent(row.youngPassed, row.youngFailed), weight = 1f)
                TableCell(retentionPercent(row.maturePassed, row.matureFailed), weight = 1f)
                TableCell(retentionPercent(row.youngPassed + row.maturePassed, row.youngFailed + row.matureFailed), weight = 1f)
            }
        }
    }
}

private fun retentionPercent(
    passed: Int,
    failed: Int,
): String {
    val total = passed + failed
    if (total == 0) return TR.statisticsTrueRetentionNotApplicable()
    return formatPercent(passed * 100.0 / total) + "%"
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    header: Boolean = false,
) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(weight),
    )
}
