// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · ReviewCardInfo gate, 2026

package com.ichi2.anki.ui.windows.reviewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.Card
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate for the current-card stats strip (Advanced Review Bottom Bar port):
 * interval units follow the card type (learning = seconds, review = days),
 * ease is permille rendered as percent, labels come from the card-stats
 * translations.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCardInfoTest : RobolectricTest() {
    private fun card(
        ctype: Int,
        interval: Int,
        easeFactor: Int,
        reps: Int,
    ): Card =
        Card(
            anki.cards.Card
                .newBuilder()
                .setCtype(ctype)
                .setInterval(interval)
                .setEaseFactor(easeFactor)
                .setReps(reps)
                .build(),
        )

    @Test
    fun reviewCardShowsDaysEaseAndReps() {
        val text = ReviewCardInfo.text(card(ctype = 2, interval = 13, easeFactor = 2500, reps = 3))
        assertTrue(text, text.contains(TR.cardStatsInterval()))
        assertTrue(text, text.contains("13d"))
        assertTrue(text, text.contains(TR.cardStatsEase()))
        assertTrue(text, text.contains("250%"))
        assertTrue(text, text.contains(TR.cardStatsReviewCount()))
        assertTrue(text, text.contains("3"))
    }

    @Test
    fun learningIntervalIsSecondsNotDays() {
        val text = ReviewCardInfo.text(card(ctype = 1, interval = 600, easeFactor = 2500, reps = 1))
        // secondsValue keeps one decimal under 100: 600s -> "10.0m"
        assertTrue(text, text.contains("10.0m"))
        assertTrue(text, !text.contains("600d"))
    }

    @Test
    fun relearningAlsoUsesSeconds() {
        val text = ReviewCardInfo.text(card(ctype = 3, interval = 60, easeFactor = 2300, reps = 5))
        assertTrue(text, text.contains("1.0m"))
    }

    @Test
    fun newCardAndLongInterval() {
        val fresh = ReviewCardInfo.text(card(ctype = 0, interval = 0, easeFactor = 0, reps = 0))
        assertTrue(fresh, fresh.contains("0d"))
        assertTrue(fresh, fresh.contains("0%"))

        val yearPlus = ReviewCardInfo.text(card(ctype = 2, interval = 400, easeFactor = 2700, reps = 20))
        assertTrue(yearPlus, yearPlus.contains("1.1y"))
        assertTrue(yearPlus, yearPlus.contains("270%"))
    }
}
