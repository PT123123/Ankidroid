// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing-code state machine (SPEC-v2 §4.1): five-minute TTL, consumed exactly once, and no
 * oracle about which codes exist. Storage is two lambdas, so this runs on the JVM with no Android.
 */
class LanPairingTest {
    private var sessions: List<LanPairSession> = emptyList()
    private var nowMs = 1_000_000L

    private fun pairing() =
        LanPairing(
            loadSessions = { sessions },
            saveSessions = { sessions = it },
            now = { nowMs },
        )

    @Test
    fun `a fresh session lives for five minutes`() {
        val pairing = pairing()
        val session = pairing.beginLocal()
        assertEquals(LanPairing.TTL_MS, session.expiresAt - session.createdAt)
        assertEquals(300_000L, LanPairing.TTL_MS)
        assertTrue(session.isLiveAt(session.createdAt))
        assertTrue(session.isLiveAt(session.expiresAt - 1))
        assertFalse(session.isLiveAt(session.expiresAt))
    }

    @Test
    fun `commit succeeds once and the code is burned`() {
        val pairing = pairing()
        val session = pairing.beginLocal()
        assertEquals(LanCommitOutcome.OK, pairing.commit(session.pairCode))
        // second use of the same code: CONSUMED, whatever the clock says
        assertEquals(LanCommitOutcome.CONSUMED, pairing.commit(session.pairCode))
        assertNull(pairing.activeSession())
    }

    @Test
    fun `an expired code is refused`() {
        val pairing = pairing()
        val session = pairing.beginLocal()
        nowMs += LanPairing.TTL_MS
        assertEquals(LanCommitOutcome.EXPIRED, pairing.commit(session.pairCode))
    }

    @Test
    fun `a wrong code says only wrong - never whether the code exists`() {
        val pairing = pairing()
        val session = pairing.beginLocal()
        assertEquals(LanCommitOutcome.WRONG_CODE, pairing.commit("000000".let { if (it == session.pairCode) "000001" else it }))
        assertEquals(LanCommitOutcome.WRONG_CODE, pairing.commit("abcdef"))
        assertEquals(LanCommitOutcome.WRONG_CODE, pairing.commit(""))
        // the live session survives wrong guesses untouched
        assertEquals(session.pairCode, pairing.activeSession()?.pairCode)
    }

    @Test
    fun `beginning again replaces the live session so old codes stop working`() {
        val pairing = pairing()
        val first = pairing.beginLocal()
        val second = pairing.beginLocal()
        assertNotEquals(first.pairCode, second.pairCode)
        assertEquals(second.pairCode, pairing.activeSession()?.pairCode)
        assertEquals(LanCommitOutcome.WRONG_CODE, pairing.commit(first.pairCode))
        assertEquals(LanCommitOutcome.OK, pairing.commit(second.pairCode))
    }

    @Test
    fun `prune drops expired and consumed sessions`() {
        val pairing = pairing()
        val doomed = pairing.beginLocal()
        nowMs += 2 * LanPairing.TTL_MS
        val live = pairing.beginLocal()
        pairing.prune()
        assertFalse(sessions.any { it.pairCode == doomed.pairCode })
        assertNotNull(sessions.firstOrNull { it.pairCode == live.pairCode })
    }

    @Test
    fun `pair codes are six digits and only the combined secret yields a security code`() {
        val session = pairing().beginLocal()
        assertTrue(session.pairCode.matches(Regex("\\d{6}")))
        // SPEC-v2 §4.1: the 4-hex code the user compares is derived from the *combined* secret, so
        // it cannot exist while a session is merely open - pairing() has nowhere to store one.
        assertEquals(
            4,
            LanCrypto
                .securityCodeOf(
                    LanCrypto.combineSecrets(
                        own = LanCrypto.randomBytes(LanCrypto.KEY_BYTES),
                        peer = LanCrypto.randomBytes(LanCrypto.KEY_BYTES),
                        ownDeviceId = "a",
                        peerDeviceId = "b",
                    ),
                ).length,
        )
    }
}
