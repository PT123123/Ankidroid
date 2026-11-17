// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · current-card stats strip for the reviewer
// bottom bar (port of the Advanced Review Bottom Bar add-on), 2026

package com.ichi2.anki.ui.windows.reviewer

import anki.stats.CardStatsResponse
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.pages.statistics.formatDays
import com.ichi2.anki.pages.statistics.formatMillis
import com.ichi2.anki.pages.statistics.formatPercent

/**
 * One line about the card being reviewed: interval, ease, review count, the
 * most recent ratings (port of the See Previous Card Ratings add-on) and an
 * estimate of the time left in the queue (port of the Review ETA add-on).
 * Labels reuse the card-stats and studying translations; the "Recent" prefix
 * and the ETA template are fork strings, passed in by the caller.
 */
object ReviewCardInfo {
    data class Data(
        val card: Card,
        val revlog: List<CardStatsResponse.StatsRevlogEntry>,
        val remaining: Int = 0,
    )

    fun text(card: Card): String {
        // learning intervals are stored in seconds, review intervals in days
        val interval =
            when (card.type) {
                CardType.Lrn, CardType.Relearning -> formatMillis(card.ivl * 1000L)
                else -> formatDays(card.ivl.toLong())
            }
        val ease = formatPercent(card.factor / 10.0) + "%"
        return "${TR.cardStatsInterval()} $interval · ${TR.cardStatsEase()} $ease · " +
            "${TR.cardStatsReviewCount()} ${card.reps}"
    }

    fun text(
        data: Data,
        recentLabel: String,
        etaLabel: String,
    ): String {
        val recent = recentRatingsLabel(data.revlog)
        var line = text(data.card)
        if (recent.isNotEmpty()) {
            line += " · $recentLabel $recent"
        }
        val eta = etaDurationMillis(data.revlog, data.remaining)
        if (eta > 0) {
            line += " · " + etaLabel.format(formatMillis(eta))
        }
        return line
    }

    /**
     * The last [maxEntries] answered ratings, oldest first. Manual reschedules
     * (kind 4) and set-due-date entries (kind 5) carry no real button press.
     */
    fun recentRatingsLabel(
        entries: List<CardStatsResponse.StatsRevlogEntry>,
        maxEntries: Int = 5,
    ): String =
        entries
            .filter { it.reviewKindValue !in 4..5 && it.buttonChosen in 1..4 }
            .sortedBy { it.time }
            .takeLast(maxEntries)
            .joinToString(" ") { ratingName(it.buttonChosen) }

    /** Mean answered seconds over the newest [maxEntries] real reviews; 0 when none. */
    fun averageTakenSecs(
        entries: List<CardStatsResponse.StatsRevlogEntry>,
        maxEntries: Int = 20,
    ): Double {
        val samples =
            entries
                .filter { it.reviewKindValue !in 4..5 && it.takenSecs > 0f }
                .sortedBy { it.time }
                .takeLast(maxEntries)
        return if (samples.isEmpty()) 0.0 else samples.sumOf { it.takenSecs.toDouble() } / samples.size
    }

    /** Estimated milliseconds left for [remaining] cards; 0 without usable samples. */
    fun etaDurationMillis(
        entries: List<CardStatsResponse.StatsRevlogEntry>,
        remaining: Int,
    ): Long {
        if (remaining <= 0) {
            return 0
        }
        val average = averageTakenSecs(entries)
        return if (average <= 0.0) 0 else Math.round(remaining * average * 1000)
    }

    private fun ratingName(buttonChosen: Int): String =
        when (buttonChosen) {
            1 -> TR.studyingAgain()
            2 -> TR.studyingHard()
            3 -> TR.studyingGood()
            else -> TR.studyingEasy()
        }
}
