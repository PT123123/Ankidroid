// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · Exam Planner (B26), 2026

package com.ichi2.anki.addons

import anki.deck_config.updateDeckConfigsRequest
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.updateDeckConfigsRaw
import com.ichi2.anki.observability.undoableOp
import timber.log.Timber

/**
 * B26 · Exam Planner: compute the daily new-card target from the exam date and
 * apply it to a deck's config. The computation is AddonTools.examPlan (unit
 * tested); this object only performs the config mutation through the backend,
 * round-tripping the full config set so FSRS fields are preserved.
 */
object ExamPlanner {
    suspend fun applyDailyNew(
        deckId: DeckId,
        dailyNew: Int,
    ): Result<Unit> =
        runCatching {
            require(dailyNew > 0)
            undoableOp {
                val deckConfigs = decks.getDeckConfigsForUpdate(deckId)
                val currentLimits =
                    requireNotNull(deckConfigs.currentDeck.limits) { "no config limits for deck $deckId" }
                val newLimits =
                    currentLimits.toBuilder().apply { new = dailyNew }.build()
                val request =
                    updateDeckConfigsRequest {
                        targetDeckId = deckId
                        mode = anki.deck_config.UpdateDeckConfigsMode.UPDATE_DECK_CONFIGS_MODE_NORMAL
                        limits = newLimits
                        deckConfigs.allConfigList.forEach { configs.add(it.config) }
                    }
                updateDeckConfigsRaw(request.toByteArray())
            }
            Unit
        }.onFailure { Timber.w(it, "ExamPlanner applyDailyNew failed") }
}
