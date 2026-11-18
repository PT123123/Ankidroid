// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · FSRS Helper tools (B24), 2026

package com.ichi2.anki.addons

import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.sched.SetDueDateDays
import com.ichi2.anki.observability.undoableOp
import timber.log.Timber

/**
 * B24 · FSRS Helper scheduling tools. Port of the postpone/advance, load-balance
 * and easy-days actions. All mutations go through `undoableOp` so a single
 * Ctrl-Z restores the whole batch; the decisions themselves live in AddonTools.
 */
object FsrsTools {
    /**
     * Resolves a relative offset against a card's current due into the ABSOLUTE
     * day spec rslib expects (`"N"`, `"N-M"`, `"N!"`). Relative "+3d" strings
     * are rejected by rslib's parse_due_date_str at runtime — never send them.
     * Returns null on an unparseable offset.
     */
    fun absoluteDue(
        currentDue: Int,
        offset: String,
    ): Int? {
        val delta = AddonTools.parseDueOffset(offset) ?: return null
        return maxOf(1, currentDue + delta)
    }

    /** Shift every card's due by a relative offset ("+3", "-1", "10"). */
    suspend fun shiftDue(
        cardIds: List<CardId>,
        offset: String,
    ): Result<Int> =
        runCatching {
            require(absoluteDue(0, offset) != null) { "invalid offset: $offset" }
            undoableOp {
                for (cid in cardIds) {
                    val absolute = absoluteDue(getCard(cid).due, offset)!!
                    sched.setDueDate(listOf(cid), SetDueDateDays("$absolute"))
                }
            }
            cardIds.size
        }.onFailure { Timber.w(it, "FSRS shiftDue failed") }

    /**
     * Load balance: spread [cardIds] over [days] by offsetting each card's due
     * to its bucket. Cards are distributed in their existing order so the plan
     * is deterministic and reproducible.
     */
    suspend fun loadBalance(
        cardIds: List<CardId>,
        days: Int,
    ): Result<List<Int>> =
        runCatching {
            require(days > 0)
            val plan = AddonTools.loadBalancePlan(cardIds.size, days)
            require(plan.isNotEmpty())
            var cursor = 0
            undoableOp {
                plan.forEachIndexed { bucket, count ->
                    if (count == 0) return@forEachIndexed
                    val slice = cardIds.subList(cursor, cursor + count)
                    cursor += count
                    // Bucket 0 would be today; spread over the coming days so
                    // nothing lands retroactively. Absolute "N" is required.
                    sched.setDueDate(slice, SetDueDateDays("${bucket + 1}"))
                }
            }
            plan
        }.onFailure { Timber.w(it, "FSRS loadBalance failed") }
}
