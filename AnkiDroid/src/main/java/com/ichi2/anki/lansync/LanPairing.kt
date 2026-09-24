// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import kotlinx.serialization.Serializable

/** Result of validating a `/pair/commit` against the local session table (SPEC-v2 §4.1 errors). */
enum class LanCommitOutcome { OK, WRONG_CODE, EXPIRED, CONSUMED }

/**
 * One pairing session as the *responder* of `POST /pair/begin` sees it: the 6-digit code to read
 * out to the other device's UI, and when it dies.
 *
 * There is deliberately no security code here. That code is `sha256(shared_secret)` (SPEC-v2 §4.1),
 * and the secret only exists after both contributions arrive — so it cannot be known while the code
 * is being typed. It surfaces on the peer row once `commit` succeeds.
 */
@Serializable
data class LanPairSession(
    val pairCode: String,
    val createdAt: Long,
    val expiresAt: Long,
    val consumed: Boolean = false,
) {
    fun isLiveAt(now: Long): Boolean = !consumed && now < expiresAt
}

/**
 * The pairing-code state machine (SPEC-v2 §4.1): 6-digit decimal codes with a 5-minute TTL that
 * are consumed exactly once.
 *
 * Storage is injected as plain get/set functions so this file needs no Android and the unit tests
 * can drive it with a map; [LanStore] persists it in the app-global `lan_sync` preferences.
 *
 * Deliberately *not* leaked through any route: which codes exist and which don't. A wrong code is
 * always the same `WRONG_CODE` answer; expiry and consumption only become visible for a code the
 * asker already knows, which is exactly when the user needs to see it.
 */
class LanPairing(
    private val loadSessions: () -> List<LanPairSession>,
    private val saveSessions: (List<LanPairSession>) -> Unit,
    private val now: () -> Long = { lanNow() },
) {
    /**
     * Creates the responder-side session for the next `POST /pair/begin`. Only one live session at
     * a time: re-beginning *replaces* the previous code (it stops validating at all), rather than
     * letting stale codes pile up or stay usable after the user asked for a new one.
     */
    fun beginLocal(): LanPairSession {
        val at = now()
        val session =
            LanPairSession(
                pairCode = LanCrypto.newPairCode(),
                createdAt = at,
                expiresAt = at + TTL_MS,
            )
        saveSessions(loadSessions().filterNot { it.isLiveAt(at) } + session)
        return session
    }

    /** The session the responder UI should currently display, if any. */
    fun activeSession(): LanPairSession? = loadSessions().filter { it.isLiveAt(now()) }.maxByOrNull { it.createdAt }

    /**
     * Validates a presented [pairCode] and, when it matches a live session, consumes it.
     * The caller stores the exchanged secrets only on [LanCommitOutcome.OK].
     */
    fun commit(pairCode: String): LanCommitOutcome {
        val at = now()
        val sessions = loadSessions()
        val match = sessions.firstOrNull { it.pairCode == pairCode } ?: return LanCommitOutcome.WRONG_CODE
        val outcome =
            when {
                match.consumed -> LanCommitOutcome.CONSUMED
                at >= match.expiresAt -> LanCommitOutcome.EXPIRED
                else -> LanCommitOutcome.OK
            }
        if (outcome == LanCommitOutcome.OK) {
            saveSessions(sessions.map { if (it === match) it.copy(consumed = true) else it })
        }
        return outcome
    }

    /** Drops expired and consumed sessions; called opportunistically on begin/commit. */
    fun prune() {
        val at = now()
        saveSessions(loadSessions().filter { it.createdAt + TTL_MS > at && !it.consumed })
    }

    companion object {
        /** Five minutes, one use (SPEC-v2 §4.1). */
        const val TTL_MS = 5 * 60_000L
    }
}
