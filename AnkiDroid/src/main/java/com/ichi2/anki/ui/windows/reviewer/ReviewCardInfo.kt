// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · current-card stats strip for the reviewer
// bottom bar (port of the Advanced Review Bottom Bar add-on), 2026

package com.ichi2.anki.ui.windows.reviewer

import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.pages.statistics.formatDays
import com.ichi2.anki.pages.statistics.formatMillis
import com.ichi2.anki.pages.statistics.formatPercent

/**
 * One line about the card being reviewed: interval, ease and review count.
 * Labels reuse the card-stats translations, so no new fork strings are needed.
 */
object ReviewCardInfo {
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
}
