// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The v2 envelope crypto (SPEC-v2 §4.2): AES-256-GCM keyed per route via HKDF, ts window and
 * nonce de-duplication. Pure JVM - `javax.crypto` needs no Android.
 *
 * The *exact wire bytes* are [LanInteropVectorsTest]'s job; this class covers the properties the
 * vectors cannot: that tampering, replays, stale timestamps and the wrong secret all fail.
 */
class LanCryptoTest {
    private val secret = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
    private val kid = "aabbccdd"
    private val route = "state"
    private val now = lanNow() / 1000

    private fun sealEnvelope(
        plaintext: String,
        ts: Long = now,
    ): LanEnvelope = LanCrypto.sealEnvelope(secret, route, kid, plaintext.toByteArray(), nowSeconds = ts)

    private fun open(
        envelope: LanEnvelope,
        withSecret: ByteArray = secret,
        onRoute: String = route,
        atSeconds: Long = now,
        window: LanReplayWindow = LanReplayWindow(),
    ): String = String(LanCrypto.openEnvelope(withSecret, onRoute, envelope, nowSeconds = atSeconds, window = window))

    @Test
    fun `hkdf matches the RFC 5869 test vector for SHA-256`() {
        // RFC 5869 Appendix A.1, test case 1
        val ikm = ByteArray(22) { 0x0b }
        val salt = (0x00..0x0c).map { it.toByte() }.toByteArray()
        val info = (0xf0..0xf9).map { it.toByte() }.toByteArray()
        val expected =
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertArrayEquals(expected.hexToBytes(), LanCrypto.hkdf(ikm, salt, info, 42))
        // A.3 covers the empty-salt branch (HKDF substitutes 32 zero bytes, not the empty key).
        val emptySaltOkm =
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        assertArrayEquals(
            emptySaltOkm.hexToBytes(),
            LanCrypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42),
        )
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i ->
            Integer.parseInt(substring(i * 2, i * 2 + 2), 16).toByte()
        }

    @Test
    fun `route keys differ per route so one compromise cannot open another`() {
        assertFalse(LanCrypto.routeKey(secret, "state").contentEquals(LanCrypto.routeKey(secret, "apkg/import")))
    }

    @Test
    fun `envelope round trip returns the plaintext`() {
        val envelope = sealEnvelope("""{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", open(envelope))
    }

    @Test
    fun `tampered ciphertext is refused`() {
        val envelope = sealEnvelope("attack at dawn")
        val raw = LanCrypto.unBase64(envelope.ct)!!
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        assertThrows(LanSecurityException::class.java) { open(envelope.copy(ct = LanCrypto.base64(raw))) }
    }

    @Test
    fun `aad mismatch is refused - wrong kid ts route or secret all fail authentication`() {
        val envelope = sealEnvelope("payload")
        // different route → different derived key AND different AAD
        assertThrows(LanSecurityException::class.java) { open(envelope, onRoute = "hub/grant") }
        // kid bound into the AAD
        assertThrows(LanSecurityException::class.java) {
            LanCrypto.openEnvelope(secret, route, envelope.copy(kid = "deadbeef"), nowSeconds = now)
        }
        // ts bound into the AAD
        assertThrows(LanSecurityException::class.java) {
            LanCrypto.openEnvelope(secret, route, envelope.copy(ts = envelope.ts + 1), nowSeconds = now + 1)
        }
        // another pair's secret
        assertThrows(LanSecurityException::class.java) { open(envelope, withSecret = LanCrypto.randomBytes(32)) }
    }

    @Test
    fun `aad is the kid, a pipe and the second-granular ts`() {
        assertArrayEquals(
            "6d77a43d|1700000000".toByteArray(),
            LanCrypto.envelopeAad("6d77a43d", 1_700_000_000L),
        )
    }

    @Test
    fun `timestamps outside the 300s window are refused`() {
        val envelope = sealEnvelope("late", ts = now - LanProtocol.REPLAY_WINDOW_SECONDS - 1)
        assertThrows(LanSecurityException::class.java) { open(envelope) }
        val early = sealEnvelope("early", ts = now + LanProtocol.REPLAY_WINDOW_SECONDS + 1)
        assertThrows(LanSecurityException::class.java) { open(early) }
        // right at the edge is still accepted
        assertEquals("late", open(sealEnvelope("late", ts = now - LanProtocol.REPLAY_WINDOW_SECONDS), atSeconds = now))
    }

    @Test
    fun `a replayed envelope is refused by the shared nonce window`() {
        val envelope = sealEnvelope("once")
        val window = LanReplayWindow()
        assertEquals("once", open(envelope, window = window))
        assertThrows(LanSecurityException::class.java) { open(envelope, window = window) }
        // a fresh window (e.g. a different server) does not know about it
        assertEquals("once", open(envelope))
    }

    @Test
    fun `sealEnvelope uses distinct nonces so two identical payloads are not replayable`() {
        val a = sealEnvelope("same")
        val b = sealEnvelope("same")
        assertNotEquals(a.nonce, b.nonce)
        val window = LanReplayWindow()
        open(a, window = window)
        open(b, window = window)
    }

    @Test
    fun `a pairing contribution wraps into a normal envelope with kid pair`() {
        val code = "123456"
        val contribution = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val wrapped = LanCrypto.wrapSecret(code, contribution)
        assertEquals(LanCrypto.PAIR_KID, wrapped.kid)
        assertArrayEquals(contribution, LanCrypto.unwrapSecret(code, wrapped))
        assertThrows(LanSecurityException::class.java) { LanCrypto.unwrapSecret("654321", wrapped) }
        // tampered wrap
        val raw = LanCrypto.unBase64(wrapped.ct)!!
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x40).toByte()
        assertThrows(LanSecurityException::class.java) {
            LanCrypto.unwrapSecret(code, wrapped.copy(ct = LanCrypto.base64(raw)))
        }
    }

    @Test
    fun `pair wrap keys are code-specific`() {
        assertFalse(LanCrypto.pairWrapKey("000001").contentEquals(LanCrypto.pairWrapKey("000002")))
    }

    @Test
    fun `combining is order independent so both devices derive the same secret`() {
        val a = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val b = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val idA = "11111111-1111-4111-8111-111111111111"
        val idB = "22222222-2222-4222-8222-222222222222"
        assertArrayEquals(
            LanCrypto.combineSecrets(a, b, idA, idB),
            LanCrypto.combineSecrets(b, a, idB, idA),
        )
    }

    @Test
    fun `kid and security code are derived from the secret, not random`() {
        val other =
            LanCrypto.combineSecrets(
                LanCrypto.randomBytes(32),
                LanCrypto.randomBytes(32),
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
            )
        val shared = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        assertEquals(8, LanCrypto.kidOf(shared).length)
        assertEquals(4, LanCrypto.securityCodeOf(shared).length)
        assert(LanCrypto.kidOf(shared).all { it in '0'..'9' || it in 'a'..'f' })
        // Derivation, so the same secret always says the same thing - and a different one does not.
        assertEquals(LanCrypto.kidOf(shared), LanCrypto.kidOf(shared.copyOf()))
        assertNotEquals(LanCrypto.kidOf(shared), LanCrypto.kidOf(other))
        assertNotEquals(LanCrypto.securityCodeOf(shared), LanCrypto.securityCodeOf(other))
    }

    @Test
    fun `pair codes are six digits`() {
        val code = LanCrypto.newPairCode()
        assertEquals(6, code.length)
        assert(code.all { it.isDigit() })
    }

    @Test
    fun `the envelope header is urlsafe base64 of the envelope json`() {
        val envelope = sealEnvelope("""{"since":0}""")
        val json = LanProtocol.json.encodeToString(LanEnvelope.serializer(), envelope)
        val header = LanCrypto.base64Url(json)
        assertFalse("header must not use the standard alphabet", header.contains('+') || header.contains('/'))
        assertEquals(json, LanCrypto.unBase64Url(header))
        // a header that arrived without its padding still decodes
        assertEquals(json, LanCrypto.unBase64Url(header.trimEnd('=')))
    }
}
