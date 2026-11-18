// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · second add-on wave gate (B23-B28), 2026

package com.ichi2.anki.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic gate for the second add-on wave; no Robolectric, no collection. */
class AddonToolsTest {
    @Test
    fun ttsPlainTextStripsMarkupAndEntities() {
        assertEquals("Hello world again", AddonTools.ttsPlainText("<div>Hello <b>world</b><br>again</div>"))
        assertEquals("a &b", AddonTools.ttsPlainText("a&nbsp;&amp;b"))
        assertEquals("x", AddonTools.ttsPlainText("<p>x</p>"))
    }

    @Test
    fun ttsNamesAndChunks() {
        val name = AddonTools.ttsMediaName("Hello, World!", 42)
        assertTrue(name.startsWith("tts_42_"))
        assertTrue(name.endsWith(".mp3"))
        assertFalse(name.contains(' '))
        assertEquals(name, AddonTools.ttsMediaName("Hello, World!", 42))
        assertEquals("tts_7_tts.mp3", AddonTools.ttsMediaName("!!!", 7))
        assertEquals("[sound:$name]", AddonTools.ttsSoundTag(name))
        assertEquals("", AddonTools.ttsSoundTag(""))

        val chunks = AddonTools.ttsChunks("one two three four five", 9)
        assertTrue(chunks.size >= 2)
        chunks.forEach { assertTrue(it.length <= 9) }
        assertTrue(AddonTools.ttsChunks("   ", 10).isEmpty())
        assertTrue(AddonTools.ttsChunks("abc", 0).isEmpty())
        assertEquals(listOf("abc"), AddonTools.ttsChunks("abc", 100))
    }

    @Test
    fun dueOffsetsAndParsing() {
        assertEquals(3, AddonTools.parseDueOffset("+3"))
        assertEquals(-14, AddonTools.parseDueOffset("-2w"))
        assertEquals(30, AddonTools.parseDueOffset("1m"))
        assertEquals(null, AddonTools.parseDueOffset("garbage"))
        assertEquals(listOf(3, 8, 13), AddonTools.dueOffsets(listOf(0, 5, 10), 3))
        assertEquals(listOf(0, 0), AddonTools.dueOffsets(listOf(0, 1), -5))
        assertEquals(listOf(-5, -4), AddonTools.dueOffsets(listOf(0, 1), -5, allowPast = true))
    }

    @Test
    fun loadBalanceAndEasyDays() {
        assertEquals(listOf(4, 3, 3), AddonTools.loadBalancePlan(10, 3))
        assertEquals(listOf(2, 2, 2), AddonTools.loadBalancePlan(6, 3))
        assertTrue(AddonTools.loadBalancePlan(5, 0).isEmpty())
        val easy = AddonTools.easyDayPercentages(2, 0.5)
        assertEquals(7, easy.size)
        assertEquals(0.5, easy[0], 0.0001)
        assertEquals(1.0, easy[2], 0.0001)
        assertEquals(List(7) { 1.0 }, AddonTools.easyDayPercentages(7, 0.5))
    }

    @Test
    fun cornerPolicyAndCountdown() {
        val policy = AddonTools.CornerPolicy(enabled = true, activeFromHour = 8, activeToHour = 22)
        assertTrue(AddonTools.cornerDue(policy, 12, 5, false))
        assertFalse(AddonTools.cornerDue(policy, 7, 5, false))
        assertFalse(AddonTools.cornerDue(policy, 22, 5, false))
        assertFalse(AddonTools.cornerDue(policy, 12, 20, false))
        assertFalse(AddonTools.cornerDue(policy, 12, 5, true))
        assertEquals("即将弹出", AddonTools.cornerCountdownLabel(0))
        assertEquals("30 秒后复习", AddonTools.cornerCountdownLabel(30))
        assertEquals("10 分钟后复习", AddonTools.cornerCountdownLabel(600))
    }

    @Test
    fun cornerReviewNextFire() {
        val policy = AddonTools.CornerPolicy(enabled = true, intervalMin = 10, activeFromHour = 8, activeToHour = 22)
        assertEquals(10, CornerReview.nextFireInMinutes(policy, 12, 5, false))
        assertEquals(null, CornerReview.nextFireInMinutes(policy, 12, 20, false))
        assertEquals(null, CornerReview.nextFireInMinutes(policy, 12, 5, true))
        // 21:00 + 10 min still fits inside the window, so the normal interval is used
        assertEquals(10, CornerReview.nextFireInMinutes(policy, 21, 5, false))
        // a long interval is clipped to the remaining active window (2-hour policy)
        val longPolicy = policy.copy(intervalMin = 120)
        assertEquals(60, CornerReview.nextFireInMinutes(longPolicy, 20, 5, false))
    }

    @Test
    fun fsrsDueSpecIsAbsoluteNeverRelative() {
        // rslib's parse_due_date_str accepts only "N"/"N-M"/"N!";
        // a "+3d" string would throw at runtime even though parseDueOffset
        // happily reads it as input.
        assertEquals(8, FsrsTools.absoluteDue(5, "+3"))
        assertEquals(8, FsrsTools.absoluteDue(5, "+3d"))
        assertEquals(2, FsrsTools.absoluteDue(5, "-3d"))
        assertEquals(26, FsrsTools.absoluteDue(5, "3w"))
        assertEquals(1, FsrsTools.absoluteDue(0, "-30")) // clamped, never <= 0
        assertNull(FsrsTools.absoluteDue(5, "nonsense"))
    }

    @Test
    fun examPlanViability() {
        val plan = AddonTools.examPlan(100, 14, 7)
        assertTrue(plan.viable)
        assertEquals(8, plan.dailyNew)
        assertEquals(14, plan.studyDays)
        val packed = AddonTools.examPlan(100, 7, 3)
        assertEquals(3, packed.studyDays)
        assertEquals(34, packed.dailyNew)
        assertFalse(AddonTools.examPlan(0, 10, 5).viable)
        assertFalse(AddonTools.examPlan(100, 0, 5).viable)
        assertFalse(AddonTools.examPlan(100, 10, 0).viable)
    }

    @Test
    fun tagSearchIsQuoted() {
        assertEquals("tag:\"math\"", AddonTools.tagSearchQuery("math"))
        assertEquals("tag:\"lang en\"", AddonTools.tagSearchQuery("lang en"))
        assertEquals("tag:\"a\\\"b\"", AddonTools.tagSearchQuery("a\"b"))
    }
}
