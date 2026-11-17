// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · ReviewCardInfo gate, 2026

package com.ichi2.anki.ui.windows.reviewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.stats.CardStatsResponse
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.Card
import org.junit.Assert.assertEquals
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

    private fun revlog(
        button: Int,
        kind: Int = 1,
        time: Long = 0L,
        taken: Float = 0f,
    ): CardStatsResponse.StatsRevlogEntry =
        CardStatsResponse.StatsRevlogEntry
            .newBuilder()
            .setButtonChosen(button)
            .setReviewKindValue(kind)
            .setTime(time)
            .setTakenSecs(taken)
            .build()

    @Test
    fun recentRatingsKeepsLastFiveOldestFirst() {
        val entries =
            listOf(
                revlog(1, time = 1),
                revlog(2, time = 2),
                revlog(3, time = 3),
                revlog(4, time = 4),
                revlog(1, time = 5),
                revlog(3, time = 6),
                revlog(2, time = 7),
            )
        val expected =
            listOf(TR.studyingGood(), TR.studyingEasy(), TR.studyingAgain(), TR.studyingGood(), TR.studyingHard())
                .joinToString(" ")
        assertEquals(expected, ReviewCardInfo.recentRatingsLabel(entries))
    }

    @Test
    fun recentRatingsSortsByTimeNotListOrder() {
        val entries = listOf(revlog(3, time = 9), revlog(1, time = 2))
        assertEquals(
            "${TR.studyingAgain()} ${TR.studyingGood()}",
            ReviewCardInfo.recentRatingsLabel(entries),
        )
    }

    @Test
    fun recentRatingsSkipsManualRescheduleAndInvalidButtons() {
        val entries =
            listOf(
                revlog(3, kind = 4, time = 1), // manual reschedule
                revlog(2, kind = 5, time = 2), // set due date
                revlog(0, kind = 1, time = 3), // no button recorded
                revlog(3, kind = 0, time = 4), // learning review counts
            )
        assertEquals(TR.studyingGood(), ReviewCardInfo.recentRatingsLabel(entries))
    }

    @Test
    fun recentRatingsEmptyCases() {
        assertEquals("", ReviewCardInfo.recentRatingsLabel(emptyList()))
        assertEquals("", ReviewCardInfo.recentRatingsLabel(listOf(revlog(3, kind = 4))))
    }

    @Test
    fun infoLineAppendsRecentOnlyWhenPresent() {
        val c = card(ctype = 2, interval = 13, easeFactor = 2500, reps = 3)
        val base = ReviewCardInfo.text(c)
        val withRatings =
            ReviewCardInfo.text(ReviewCardInfo.Data(c, listOf(revlog(1), revlog(3))), "Recent", "%1\$s left (est.)")
        assertEquals("$base · Recent ${TR.studyingAgain()} ${TR.studyingGood()}", withRatings)
        assertEquals(base, ReviewCardInfo.text(ReviewCardInfo.Data(c, emptyList()), "Recent", "%1\$s left (est.)"))
    }

    @Test
    fun averageTakenSecsFiltersAndCaps() {
        assertEquals(0.0, ReviewCardInfo.averageTakenSecs(emptyList()), 0.0001)
        val entries = (1..25).map { revlog(3, time = it.toLong(), taken = it.toFloat()) }
        // newest 20 of 1..25 -> 6..25, mean 15.5
        assertEquals(15.5, ReviewCardInfo.averageTakenSecs(entries), 0.0001)
        assertEquals(24.0, ReviewCardInfo.averageTakenSecs(entries, maxEntries = 3), 0.0001)

        val dirty =
            listOf(
                revlog(3, kind = 4, time = 1, taken = 100f), // manual reschedule
                revlog(3, kind = 5, time = 2, taken = 100f), // set due date
                revlog(3, kind = 1, time = 3, taken = 0f), // no timing recorded
                revlog(3, kind = 1, time = 4, taken = 10f),
                revlog(3, kind = 0, time = 5, taken = 20f), // learning counts
            )
        assertEquals(15.0, ReviewCardInfo.averageTakenSecs(dirty), 0.0001)
        assertEquals(0.0, ReviewCardInfo.averageTakenSecs(dirty.take(3)), 0.0001)
    }

    @Test
    fun etaDurationNeedsRemainingAndSamples() {
        val samples = listOf(revlog(3, time = 1, taken = 10f), revlog(3, time = 2, taken = 10f))
        assertEquals(0L, ReviewCardInfo.etaDurationMillis(samples, remaining = 0))
        assertEquals(0L, ReviewCardInfo.etaDurationMillis(emptyList(), remaining = 5))
        assertEquals(0L, ReviewCardInfo.etaDurationMillis(listOf(revlog(3, kind = 4, taken = 10f)), remaining = 5))
        assertEquals(30_000L, ReviewCardInfo.etaDurationMillis(samples, remaining = 3))
    }

    @Test
    fun infoLineAppendsEtaWhenQueueRemains() {
        val c = card(ctype = 2, interval = 13, easeFactor = 2500, reps = 3)
        val base = ReviewCardInfo.text(c)
        val data = ReviewCardInfo.Data(c, listOf(revlog(3, taken = 10f)), remaining = 3)
        // secondsValue keeps one decimal under 100: 30s -> "30.0s"
        assertEquals(
            "$base · Recent ${TR.studyingGood()} · 30.0s left (est.)",
            ReviewCardInfo.text(data, "Recent", "%1\$s left (est.)"),
        )
        // same history but an empty queue: no estimate
        assertEquals(
            "$base · Recent ${TR.studyingGood()}",
            ReviewCardInfo.text(data.copy(remaining = 0), "Recent", "%1\$s left (est.)"),
        )
    }
}
