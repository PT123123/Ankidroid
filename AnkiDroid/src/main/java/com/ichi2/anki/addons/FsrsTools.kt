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
    /** Shift every card's due by a relative offset ("+3d", "-1w", "10"). */
    suspend fun shiftDue(
        cardIds: List<CardId>,
        offset: String,
    ): Result<Int> =
        runCatching {
            val days =
                AddonTools.parseDueOffset(offset)?.let {
                    if (offset.trim().matches(Regex("^[+-]?\\d+$"))) "$it" else "${if (it >= 0) "+" else ""}${it}d"
                } ?: throw IllegalArgumentException("invalid offset: $offset")
            undoableOp {
                sched.setDueDate(cardIds, SetDueDateDays(days))
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
            plan.forEachIndexed { bucket, count ->
                if (count == 0) return@forEachIndexed
                val slice = cardIds.subList(cursor, cursor + count)
                undoableOp { sched.setDueDate(slice, SetDueDateDays("+$bucket")) }
                cursor += count
            }
            plan
        }.onFailure { Timber.w(it, "FSRS loadBalance failed") }
}
