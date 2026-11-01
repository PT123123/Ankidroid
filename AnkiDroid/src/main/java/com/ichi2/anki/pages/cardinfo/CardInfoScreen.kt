// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.pages.cardinfo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import anki.stats.CardStatsResponse
import anki.stats.RevlogEntry
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.pages.statistics.StatsCard
import com.ichi2.anki.pages.statistics.formatDays
import com.ichi2.anki.pages.statistics.formatEpochDay
import com.ichi2.anki.pages.statistics.formatEpochMs
import com.ichi2.anki.pages.statistics.formatMillis
import com.ichi2.anki.pages.statistics.formatPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class CardInfoViewModel : ViewModel() {
    private val _data = MutableStateFlow<CardStatsResponse?>(null)
    val data: StateFlow<CardStatsResponse?> = _data.asStateFlow()

    fun load(cardId: Long) {
        if (_data.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _data.value = withCol { backend.cardStats(cardId) }
            } catch (e: Exception) {
                Timber.w(e, "CardInfoViewModel: failed to load card stats")
            }
        }
    }
}

/** The native card info screen: card properties, the review timeline and the revlog history. */
@Composable
fun CardInfoScreen(data: CardStatsResponse?) {
    if (data == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PropertiesCard(data) }
        item { RevlogTimelineCard(data) }
        item { RevlogHistoryCard(data) }
    }
}

@Composable
private fun PropertiesCard(data: CardStatsResponse) {
    val rows = mutableListOf<Pair<String, String>>()
    rows.add(TR.cardStatsDeckName() to data.deck)
    rows.add(TR.cardStatsNoteType() to data.notetype)
    rows.add(TR.cardStatsCardTemplate() to data.cardType)
    if (data.preset.isNotBlank()) {
        rows.add(TR.cardStatsPreset() to data.preset)
    }
    when {
        data.hasDueDate() -> rows.add(TR.statisticsDueDate() to formatEpochDay(data.dueDate * 1000))
        data.hasDuePosition() -> rows.add(TR.statisticsDueDate() to "#${data.duePosition}")
    }
    if (data.interval > 0) {
        rows.add(TR.cardStatsInterval() to formatDays(data.interval.toLong()))
    }
    if (data.ease > 0) {
        rows.add(TR.cardStatsEase() to formatPercent(data.ease / 10.0) + "%")
    }
    rows.add(TR.cardStatsReviewCount() to data.reviews.toString())
    if (data.lapses > 0) {
        rows.add(TR.cardStatsLapseCount() to data.lapses.toString())
    }
    rows.add(TR.cardStatsAverageTime() to formatMillis((data.averageSecs * 1000).toLong()))
    rows.add(TR.cardStatsTotalTime() to formatMillis((data.totalSecs * 1000).toLong()))
    rows.add(TR.cardStatsAdded() to formatEpochDay(data.added * 1000))
    if (data.hasFirstReview()) {
        rows.add(TR.cardStatsFirstReview() to formatEpochDay(data.firstReview * 1000))
    }
    if (data.hasLatestReview()) {
        rows.add(TR.cardStatsLatestReview() to formatEpochDay(data.latestReview * 1000))
    }
    if (data.hasMemoryState()) {
        rows.add(TR.cardStatsFsrsStability() to formatDays(data.memoryState.stability.toLong()))
        rows.add(TR.cardStatsFsrsDifficulty() to formatPercent(data.memoryState.difficulty * 10.0) + "%")
    }
    if (data.hasFsrsRetrievability()) {
        rows.add(TR.cardStatsFsrsRetrievability() to formatPercent(data.fsrsRetrievability * 100.0) + "%")
    }
    StatsCard(title = data.notetype.ifBlank { TR.statisticsTitle() }) {
        rows.forEach { (label, value) -> PropertyRow(label, value) }
    }
}

@Composable
private fun PropertyRow(
    label: String,
    value: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun RevlogTimelineCard(data: CardStatsResponse) {
    val entries = data.revlogList.sortedBy { it.time }
    StatsCard(title = TR.statisticsReviewsTitle()) {
        if (entries.isEmpty()) {
            Text(
                TR.statisticsNoData(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            RevlogTimeline(entries)
        }
    }
}

/** Dots over time; height is the card's interval (log scale), colour the review kind. */
@Composable
private fun RevlogTimeline(entries: List<CardStatsResponse.StatsRevlogEntry>) {
    val labelPx = with(LocalDensity.current) { 8.dp.toPx() }
    val times = entries.map { it.time }
    val minTime = times.min()
    val maxTime = times.max()
    val span = (maxTime - minTime).coerceAtLeast(1)
    val maxDays = maxOf(1.0, entries.maxOf { it.interval.toDouble() })
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(vertical = 8.dp),
    ) {
        val leftPad = 2f * labelPx
        val chartWidth = size.width - leftPad
        // guide lines at day boundaries
        for (days in intArrayOf(1, 10, 100)) {
            val t = (Math.log10(days.toDouble()) / Math.log10(maxDays + 1)).toFloat()
            if (t in 0f..1f) {
                val y = size.height * (1 - t)
                drawLine(
                    gridColor,
                    Offset(leftPad, y),
                    Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
        }
        for (entry in entries) {
            val x = leftPad + chartWidth * (entry.time - minTime) / span
            val days = entry.interval / 86_400.0
            val y =
                size.height * (1 - (Math.log10(days + 1) / Math.log10(maxDays + 1)).toFloat().coerceIn(0f, 1f))
            drawCircle(color = kindColor(entry.reviewKind), radius = labelPx / 2.2f, center = Offset(x, y))
        }
    }
}

@Composable
private fun RevlogHistoryCard(data: CardStatsResponse) {
    val entries = data.revlogList.sortedByDescending { it.time }
    StatsCard(title = TR.cardStatsReviewLogType()) {
        if (entries.isEmpty()) {
            Text(
                TR.statisticsNoData(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            entries.forEach { entry ->
                val date = formatEpochMs(entry.time)
                val rating =
                    when (entry.buttonChosen) {
                        1 -> TR.studyingAgain()
                        2 -> TR.studyingHard()
                        3 -> TR.studyingGood()
                        4 -> TR.studyingEasy()
                        else -> "—"
                    }
                val intervalText =
                    if (entry.interval >= 86_400) formatDays(entry.interval / 86_400L) else formatMillis(entry.interval * 1000L)
                Text(
                    "$date · ${kindLabel(entry.reviewKind)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = kindColor(entry.reviewKind),
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "$rating · $intervalText · " +
                        formatPercent(entry.ease / 10.0) + "%" +
                        " · " + formatMillis((entry.takenSecs * 1000).toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun kindLabel(kind: RevlogEntry.ReviewKind): String =
    when (kind) {
        RevlogEntry.ReviewKind.LEARNING -> TR.cardStatsReviewLogTypeLearn()
        RevlogEntry.ReviewKind.REVIEW -> TR.cardStatsReviewLogTypeReview()
        RevlogEntry.ReviewKind.RELEARNING -> TR.cardStatsReviewLogTypeRelearn()
        RevlogEntry.ReviewKind.FILTERED -> TR.cardStatsReviewLogTypeFiltered()
        RevlogEntry.ReviewKind.MANUAL -> TR.cardStatsReviewLogTypeManual()
        RevlogEntry.ReviewKind.RESCHEDULED -> TR.cardStatsReviewLogTypeRescheduled()
        else -> "—"
    }

private fun kindColor(kind: RevlogEntry.ReviewKind): Color =
    when (kind) {
        RevlogEntry.ReviewKind.LEARNING -> Color(0xFFFB8C00)
        RevlogEntry.ReviewKind.REVIEW -> Color(0xFF2E7D32)
        RevlogEntry.ReviewKind.RELEARNING -> Color(0xFFE53935)
        RevlogEntry.ReviewKind.FILTERED -> Color(0xFF8E24AA)
        else -> Color(0xFF9E9E9E)
    }
