// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · Corner Review (B25), 2026

package com.ichi2.anki.addons

import java.util.Calendar

/**
 * B25 · Android counterpart of the desktop corner-popup reminder. Android has
 * no always-on-top card window without a special permission, so the equivalent
 * is a timed "quick review" notification: tap it to enter the reviewer. The
 * timing decision is shared with the desktop via [AddonTools.CornerPolicy] and
 * is unit-testable here without AlarmManager.
 */
object CornerReview {
    /** Next firing time in minutes from now, respecting active hours and quota. */
    fun nextFireInMinutes(
        policy: AddonTools.CornerPolicy,
        hour: Int,
        shownToday: Int,
        normalReviewActive: Boolean,
    ): Int? {
        if (!AddonTools.cornerDue(policy, hour, shownToday, normalReviewActive)) return null
        // Do not schedule a popup past the active window.
        val remainingHours = policy.activeToHour - hour - 1
        return if (remainingHours <= 0) policy.intervalMin else minOf(policy.intervalMin, remainingHours * 60)
    }

    /** Wall-clock hour from a Calendar, kept separate for testability. */
    fun hourOf(calendar: Calendar): Int = calendar.get(Calendar.HOUR_OF_DAY)
}
