// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The v2 envelope crypto (SPEC-v2 §4): AES-256-GCM keyed per route via HKDF-SHA256, with a
 * timestamp window and nonce de-duplication against replay.
 *
 * Deliberately built on `javax.crypto` only - HKDF is a twelve-line HMAC construction and pulling
 * in a crypto dependency for it is not worth the APK weight and audit burden.
 *
 * Concrete wire decisions are pinned by SPEC-v2 §4.6 and asserted against the shared
 * `vectors.json`; the desktop implementation is the authority, and changing any byte here without
 * regenerating the vectors breaks pairing between the two platforms.
 * - HKDF-SHA256 as RFC 5869 (extract with the given salt, expand to 32 bytes with `info`).
 * - Envelope key: `HKDF(ikm=shared_secret, salt="anki-lan-sync/2", info=<route>)`.
 * - AAD = UTF-8 bytes of `"<kid>|<ts>"` with `ts` in whole seconds — the separator is load-bearing.
 * - `kid` and the 4-hex security code are *derived from the shared secret*, never random: the
 *   security code is the only human-checkable proof that no one sat between the two devices.
 * - Pairing wrap key: `HKDF(ikm=pairCode, salt="anki-lan-sync/2/pair", info="wrap")`; the wrapped
 *   contribution is a normal [LanEnvelope] with `kid = "pair"`, not a bare `b64(nonce||ct)`.
 * - Bulk `.apkg` legs travel as **raw bytes** (SPEC-v2 §4.3): authentication is the envelope,
 *   confidentiality is the "same LAN" premise, exactly like hub mode's plain-HTTP sync.
 */
object LanCrypto {
    const val HKDF_SALT = "anki-lan-sync/2"
    const val KX_SALT = "anki-lan-sync/2/kx"
    const val PAIR_SALT = "anki-lan-sync/2/pair"
    const val PAIR_INFO = "wrap"
    const val PAIR_KID = "pair"

    /** The route a pairing contribution travels on: the envelope's HKDF info and its AAD context. */
    const val PAIR_ROUTE = "pair/wrap"

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /**
     * The 8-hex key id a peer addresses us with (SPEC-v2 §4.1). Derived, not random: two devices
     * that paired must independently arrive at the *same* kid, and a peer cannot pick a kid that
     * collides with one we already use.
     */
    fun kidOf(sharedSecret: ByteArray): String = hmac(sharedSecret, "kid".toByteArray()).toHex().substring(0, 8)

    /** The 4-hex human-verifiable code (SPEC-v2 §4.1); only computable once both contributions are in. */
    fun securityCodeOf(sharedSecret: ByteArray): String = sha256(sharedSecret).toHex().substring(0, 4)

    /** The 6-digit pairing code (SPEC-v2 §4.1). */
    fun newPairCode(): String = "%06d".format(random.nextInt(1_000_000))

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256; returns [length] bytes (max 8160, fine for our 32-byte keys). */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = KEY_BYTES,
    ): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + info + counter.toByte())
            val take = minOf(previous.size, length - written)
            previous.copyInto(output, written, 0, take)
            written += take
            counter++
        }
        return output
    }

    /** Key for one route: every route gets its own derived key, never reused across them. */
    fun routeKey(
        deviceSecret: ByteArray,
        route: String,
    ): ByteArray = hkdf(deviceSecret, HKDF_SALT.toByteArray(), route.toByteArray())

    /**
     * The single shared secret both sides derive from their two 32-byte contributions
     * (SPEC-v2 §4.1). Ordering the halves by `device_id` — not by who initiated — is what makes the
     * two devices arrive at the same bytes; role-ordered concatenation gives each side a different
     * permutation of the same material and pairing fails on the spot.
     */
    fun combineSecrets(
        own: ByteArray,
        peer: ByteArray,
        ownDeviceId: String,
        peerDeviceId: String,
    ): ByteArray {
        val (first, second) = if (ownDeviceId <= peerDeviceId) own to peer else peer to own
        return hkdf(first + second, KX_SALT.toByteArray(), "pair".toByteArray())
    }

    /** Key that wraps the exchanged contribution during pairing (SPEC-v2 §4.1). */
    fun pairWrapKey(pairCode: String): ByteArray =
        hkdf(
            ikm = pairCode.toByteArray(),
            salt = PAIR_SALT.toByteArray(),
            info = PAIR_INFO.toByteArray(),
        )

    /** AES-GCM under [key] with AAD `kid||ts`; the base64 strings are the wire form (SPEC-v2 §4.2). */
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = gcm(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext)

    private fun gcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        if (nonce.size != NONCE_BYTES) throw LanSecurityException("bad nonce size", LanError.BAD_ENVELOPE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    /** `"<kid>|<ts>"` as AAD — the separator is part of the wire format (SPEC-v2 §4.2). */
    fun envelopeAad(
        kid: String,
        tsSeconds: Long,
    ): ByteArray = "$kid|$tsSeconds".toByteArray()

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unBase64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    /**
     * The `X-Anki-Envelope` *header* form: urlsafe alphabet, **with** padding, of the same envelope
     * JSON the body carries (Python's `urlsafe_b64encode`). Inside the envelope the fields stay on
     * the standard table, so these two helpers never substitute for [base64]/[unBase64].
     */
    fun base64Url(text: String): String = Base64.getUrlEncoder().encodeToString(text.toByteArray())

    fun unBase64Url(text: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(text)) }.getOrNull()
            // OkHttp and some servers strip trailing '='; re-pad to a multiple of 4 before giving up.
            ?: runCatching { String(Base64.getUrlDecoder().decode(text.padEnd((text.length + 3) / 4 * 4, '='))) }
                .getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { ("%02x").format(it) }

    /** Encrypts a whole byte[] into a wire envelope with a fresh nonce and second-granular ts. */
    fun sealEnvelope(
        deviceSecret: ByteArray,
        route: String,
        kid: String,
        plaintext: ByteArray,
        nowSeconds: Long = lanNow() / 1000,
        nonce: ByteArray = randomBytes(NONCE_BYTES),
    ): LanEnvelope {
        val envelope = LanEnvelope(kid = kid, ts = nowSeconds, nonce = base64(nonce), ct = "")
        val ct = seal(routeKey(deviceSecret, route), nonce, plaintext, envelopeAad(kid, nowSeconds))
        return envelope.copy(ct = base64(ct))
    }

    /**
     * Verifies and decrypts an envelope: constant work on tag failure, `ts` inside the replay
     * window, and a nonce never seen before within [window]. Throws [LanSecurityException] (→ 401)
     * on any violation.
     */
    fun openEnvelope(
        deviceSecret: ByteArray,
        route: String,
        envelope: LanEnvelope,
        nowSeconds: Long = lanNow() / 1000,
        window: LanReplayWindow = LanReplayWindow(),
    ): ByteArray {
        if (envelope.kid.isBlank()) throw LanSecurityException("missing kid", LanError.BAD_ENVELOPE)
        if (kotlin.math.abs(envelope.ts - nowSeconds) > LanProtocol.REPLAY_WINDOW_SECONDS) {
            throw LanSecurityException("timestamp outside the ${LanProtocol.REPLAY_WINDOW_SECONDS}s window", LanError.STALE_TS)
        }
        val nonce = unBase64(envelope.nonce) ?: throw LanSecurityException("bad nonce encoding", LanError.BAD_ENVELOPE)
        val ct = unBase64(envelope.ct) ?: throw LanSecurityException("bad ciphertext encoding", LanError.BAD_ENVELOPE)
        if (!window.accept(envelope.kid, envelope.ts, nonce)) throw LanSecurityException("replayed nonce", LanError.REPLAY)
        return runCatching { open(routeKey(deviceSecret, route), nonce, ct, envelopeAad(envelope.kid, envelope.ts)) }
            .getOrElse { throw LanSecurityException("envelope failed authentication", LanError.DECRYPT_FAILED) }
    }

    /**
     * Wraps a pairing contribution under the code-derived key. The wire form is a normal
     * [LanEnvelope] with `kid = "pair"` (SPEC-v2 §4.1) — not a bare `b64(nonce||ct)` — so the
     * pairing step and every later request speak the same envelope shape.
     */
    fun wrapSecret(
        pairCode: String,
        plain: ByteArray,
        nowSeconds: Long = lanNow() / 1000,
        nonce: ByteArray = randomBytes(NONCE_BYTES),
    ): LanEnvelope = sealEnvelope(pairWrapKey(pairCode), PAIR_ROUTE, PAIR_KID, plain, nowSeconds, nonce)

    /** Decrypts a [wrapSecret] payload; any failure means "wrong code or tampered", never details. */
    fun unwrapSecret(
        pairCode: String,
        wrapped: LanEnvelope,
        nowSeconds: Long = lanNow() / 1000,
    ): ByteArray {
        if (kotlin.math.abs(wrapped.ts - nowSeconds) > LanProtocol.REPLAY_WINDOW_SECONDS) {
            throw LanSecurityException("pairing envelope timestamp out of range", LanError.PAIR_INVALID)
        }
        val key = routeKey(pairWrapKey(pairCode), PAIR_ROUTE)
        val raw = unBase64(wrapped.nonce) ?: throw LanSecurityException("bad wrap encoding", LanError.BAD_ENVELOPE)
        val ct = unBase64(wrapped.ct) ?: throw LanSecurityException("bad wrap encoding", LanError.BAD_ENVELOPE)
        return runCatching { open(key, raw, ct, envelopeAad(wrapped.kid, wrapped.ts)) }
            .getOrElse { throw LanSecurityException("pair wrap failed authentication", LanError.PAIR_INVALID) }
    }
}

/** The wire envelope (SPEC-v2 §4.2): `{"kid":..,"ts":..,"nonce":b64,"ct":b64}`. */
@Serializable
data class LanEnvelope(
    val kid: String = "",
    val ts: Long = 0L,
    val nonce: String = "",
    val ct: String = "",
)

/**
 * Any envelope/pairing verification failure; the server answers with [code]'s HTTP status and
 * never says why loudly. [code] is the SPEC-v2 §5 wire code, so a client can tell "ask for a new
 * pairing code" apart from "we are not paired yet" without parsing prose.
 */
class LanSecurityException(
    message: String,
    val code: LanError = LanError.DECRYPT_FAILED,
) : IllegalStateException(message)

/**
 * Sliding nonce de-duplication window (SPEC-v2 §4.2): remembers `(kid, ts, nonce)` triples that
 * authenticated within the replay window and refuses to re-accept them. Bounded so a long-running
 * server cannot be walked into OOM by flooding fresh envelopes.
 */
class LanReplayWindow(
    private val maxEntries: Int = 4096,
) {
    private val seen: MutableList<Triple<String, Long, ByteArray>> = Collections.synchronizedList(mutableListOf())

    /** True when this nonce is new (and records it); false means replay → reject. */
    fun accept(
        kid: String,
        ts: Long,
        nonce: ByteArray,
    ): Boolean {
        synchronized(seen) {
            if (seen.any { it.first == kid && it.second == ts && it.third.contentEquals(nonce) }) return false
            seen.add(Triple(kid, ts, nonce.copyOf()))
            while (seen.size > maxEntries) seen.removeAt(0)
        }
        return true
    }
}
