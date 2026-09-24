// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The routes that can be answered without a collection: who we are, the default-deny posture of the
 * data plane, pairing, and the envelope checks a peer's requests have to pass. Opening an actual
 * `.apkg` merge needs two devices (see docs/lansync), so it is not covered here.
 *
 * The pairing tests run *both sides* of the handshake in one process: the server derives its key
 * from the phone's own store, and the test then recomputes it as the caller would. A mismatch in
 * ordering, kid derivation or wrap format fails here long before it can fail between two devices.
 */
@RunWith(AndroidJUnit4::class)
class LanServerTest {
    private lateinit var server: LanSyncServer
    private lateinit var base: String

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

    private val store: LanStore
        get() = LanStore.instance

    @Before
    fun setUp() {
        // port 0 lets the OS pick a free one, so the test does not race other suites
        testContext().getSharedPreferences("lan_sync_secrets", Context.MODE_PRIVATE).edit { clear() }
        store.allowPlaintextV1 = false
        server = LanSyncServer(0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
        store.allowPlaintextV1 = false
    }

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = call(path, null, headers)

    private fun post(
        path: String,
        body: String = "",
        mime: String = MIME_JSON,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = call(path, body to mime, headers)

    private fun call(
        path: String,
        payload: Pair<String, String>?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val request =
            Request
                .Builder()
                .url("$base$path")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .apply {
                    if (payload == null) get() else post(payload.first.toRequestBody(payload.second.toMediaType()))
                }.build()
        return http.newCall(request).execute().use { response -> response.code to response.body.string() }
    }

    private inline fun <reified T> decode(body: String): T = LanProtocol.json.decodeFromString(body)

    private fun v2Headers() = mapOf(LanProtocol.REQUEST_HEADER to LanProtocol.REQUEST_HEADER_VALUE)

    /** A peer this server accepted through a real pairing, seen from the caller's side. */
    private class LanPaired(
        val peerId: String,
        val kid: String,
        val shared: ByteArray,
    )

    private fun pairWithServer(peerId: String = PEER_ID): LanPaired {
        val begin = decode<PairBeginResponse>(post("/pair/begin").second)
        val ours = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val (code, body) = commit(begin.pairCode, peerId, ours)
        assertEquals(body, 200, code)
        val answer = decode<PairCommitResponse>(body)
        val theirs = LanCrypto.unwrapSecret(begin.pairCode, answer.envelope)
        val shared = LanCrypto.combineSecrets(ours, theirs, peerId, store.deviceId)
        // SPEC-v2 §4.1: the commit answer carries no key id - both sides derive it, so a response
        // that *told* us our own kid would be a second source of truth to disagree with.
        assertEquals("the commit answer describes us", store.deviceId, answer.peerInfo.id)
        return LanPaired(peerId, LanCrypto.kidOf(shared), shared)
    }

    private fun commit(
        pairCode: String,
        peerId: String,
        ourContribution: ByteArray,
    ): Pair<Int, String> =
        post(
            "/pair/commit",
            LanProtocol.json.encodeToString(
                PairCommitRequest.serializer(),
                PairCommitRequest(
                    pairCode = pairCode,
                    peerInfo = LanPeerInfo(id = peerId, name = "Peer", modes = listOf(LanProtocol.MODE_APKG)),
                    envelope = LanCrypto.wrapSecret(pairCode, ourContribution),
                ),
            ),
        )

    private fun envelope(
        shared: ByteArray,
        route: String,
        kid: String,
        plain: String,
    ): String =
        LanProtocol.json.encodeToString(
            LanEnvelope.serializer(),
            LanCrypto.sealEnvelope(shared, route, kid, plain.toByteArray()),
        )

    private fun openResponse(
        shared: ByteArray,
        route: String,
        body: String,
    ): String = String(LanCrypto.openEnvelope(shared, route, decode(body), window = LanReplayWindow()))

    // region discovery surface

    @Test
    fun `the root path advertises both protocol generations`() {
        val (code, body) = get("/")
        assertEquals(200, code)
        assertTrue(body, body.contains(LanProtocol.MAGIC))
        assertTrue(body, body.contains(LanProtocol.MAGIC_V1))
    }

    @Test
    fun `info answers v2 to a v2 caller and v1 to a phone that never upgraded`() {
        val (code, body) = get("/info", v2Headers())
        assertEquals(200, code)
        val info = decode<LanPeerInfo>(body)
        assertEquals(store.deviceId, info.id)
        assertEquals(server.listeningPort, info.port)
        assertEquals(LanProtocol.VERSION, info.protocol)
        assertTrue(info.modes.contains(LanProtocol.MODE_APKG))
        // SPEC-v2 §4.4: an un-upgraded phone has no idea what `device_id` means, so `/info` has to
        // fall back to the field names it can read - otherwise the downgrade path never starts.
        val legacy = decode<LanPeerInfoV1>(get("/info").second)
        assertEquals(store.deviceId, legacy.id)
        assertEquals(LanProtocol.VERSION_V1, legacy.protocol)
    }

    @Test
    fun `unknown paths are not found`() {
        val (code, body) = get("/sync")
        assertEquals(404, code)
        // §5: even a 404 is a JSON body carrying a code - a desktop client that cannot parse the
        // body treats the whole server as broken.
        assertTrue(body, body.contains(LanError.NOT_FOUND.wire))
    }

    @Test
    fun `state is a loopback read and not part of the envelope plane`() {
        val (code, body) = get("/state")
        assertEquals(200, code)
        assertTrue(body, body.contains("rounds"))
    }

    // endregion

    // region default deny

    @Test
    fun `the v2 data plane is default-deny for unauthenticated callers`() {
        // a browser, or a phone that never paired, must not be able to pull the collection
        val (exportCode, exportBody) = post("/apkg/export", "{}")
        assertEquals(403, exportCode)
        assertTrue(exportBody, exportBody.contains(LanError.NEED_MAGIC.wire))
        // claiming the magic header without a kid at all is still refused
        val (kidlessCode, kidlessBody) = post("/devices/sync", "{}", headers = v2Headers())
        assertEquals(403, kidlessCode)
        assertTrue(kidlessBody, kidlessBody.contains(LanError.NOT_PAIRED.wire))
        // and so is a kid that was never issued by a pairing
        val (unknownCode, unknownBody) =
            post("/devices/sync", "{}", headers = v2Headers() + (LanProtocol.KID_HEADER to "ffffffff"))
        assertEquals(403, unknownCode)
        assertTrue(unknownBody, unknownBody.contains(LanError.NOT_PAIRED.wire))
    }

    @Test
    fun `a refusal that leaves the body unread hangs up instead of poisoning the next request`() {
        val request =
            Request
                .Builder()
                .url("$base/devices/sync")
                .header(LanProtocol.REQUEST_HEADER, LanProtocol.REQUEST_HEADER_VALUE)
                .post("{}".toRequestBody(MIME_JSON.toMediaType()))
                .build()
        http.newCall(request).execute().use { response ->
            assertEquals(403, response.code)
            // The two bytes of body are still in the socket when we answer. OkHttp reuses
            // connections, and NanoHTTPD would have parsed those bytes as the *next* request's
            // status line - which reached the caller as a meaningless 400.
            assertEquals("close", response.header("Connection"))
        }
        assertEquals(403, post("/devices/sync", "{}", headers = v2Headers()).first)
    }

    @Test
    fun `the hub data plane is never served from a phone`() {
        val peer = pairWithServer()
        val (code, body) =
            post(
                "/hub/grant",
                envelope(peer.shared, "hub/grant", peer.kid, "{}"),
                headers = v2Headers() + (LanProtocol.KID_HEADER to peer.kid),
            )
        assertEquals(403, code)
        assertTrue(body, body.contains(LanError.HUB_OFF.wire))
    }

    @Test
    fun `plaintext v1 is refused until the user explicitly allows the downgrade`() {
        val (gateCode, gateBody) = get("/export")
        assertEquals(403, gateCode)
        assertTrue(gateBody, gateBody.contains(LanError.V1_DISABLED.wire))

        store.allowPlaintextV1 = true
        try {
            // even with the gate open, the legacy header guard from v1 still stands: no browser or
            // captive portal may push a package in
            val (headerCode, headerBody) = post("/import", "not an apkg", mime = MIME_PACKAGE)
            assertEquals(403, headerCode)
            // SPEC-v2 §5: a refusal is a §5 code in a JSON body on both platforms, and the message
            // names the header the caller forgot - the only hint a v1 phone can act on.
            assertTrue(headerBody, headerBody.contains(LanError.V1_DISABLED.wire))
            assertTrue(headerBody, headerBody.contains(LanProtocol.LEGACY_REQUEST_HEADER))
        } finally {
            store.allowPlaintextV1 = false
        }
    }

    // endregion

    // region pairing

    @Test
    fun `pair begin issues a six digit code but no security code yet`() {
        val (code, body) = post("/pair/begin")
        assertEquals(200, code)
        val begin = decode<PairBeginResponse>(body)
        assertTrue(begin.pairCode, Regex("\\d{6}").matches(begin.pairCode))
        // SPEC-v2 §4.1: the security code is a hash of the shared secret, which does not exist
        // before commit - showing anything here would be a code the user could not trust.
        assertNull(begin.securityCode)
    }

    @Test
    fun `a commit with an unknown code gets an uninformative 401`() {
        val (code, body) = commit("000000", PEER_ID, LanCrypto.randomBytes(LanCrypto.KEY_BYTES))
        assertEquals(401, code)
        assertTrue(body, body.contains(LanError.PAIR_INVALID.wire))
    }

    @Test
    fun `a commit body that is not a commit request is refused in the §5 vocabulary`() {
        // Desktop answers the same mistake with 401 bad_envelope (`_load_json(body, "bad_envelope")`),
        // so a phone may not answer it with a plaintext 400.
        val (code, body) = post("/pair/commit", "not json at all")
        assertEquals(401, code)
        assertTrue(body, body.contains(LanError.BAD_ENVELOPE.wire))
    }

    @Test
    fun `a commit wrapped under the wrong code never reaches the trust list`() {
        val begin = decode<PairBeginResponse>(post("/pair/begin").second)
        val (code, _) =
            post(
                "/pair/commit",
                LanProtocol.json.encodeToString(
                    PairCommitRequest.serializer(),
                    PairCommitRequest(
                        pairCode = begin.pairCode,
                        peerInfo = LanPeerInfo(id = PEER_ID, name = "Peer"),
                        // wrapped under a different code than the one being committed
                        envelope = LanCrypto.wrapSecret("999999", LanCrypto.randomBytes(LanCrypto.KEY_BYTES)),
                    ),
                ),
            )
        assertEquals(401, code)
        assertEquals("", store.securityCodeFor(PEER_ID))
        assertFalse(store.devices.value.any { it.id == PEER_ID && it.paired })
    }

    @Test
    fun `a commit with a live code derives the same key on both sides`() {
        val begin = decode<PairBeginResponse>(post("/pair/begin").second)
        val ours = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val (code, body) = commit(begin.pairCode, PEER_ID, ours)
        assertEquals(body, 200, code)
        val answer = decode<PairCommitResponse>(body)
        val theirs = LanCrypto.unwrapSecret(begin.pairCode, answer.envelope)
        val shared = LanCrypto.combineSecrets(ours, theirs, PEER_ID, store.deviceId)
        assertFalse("a pairing must contribute a fresh secret per side", ours.contentEquals(theirs))
        // Both sides ran the same KDF over the same two halves: identical key, identical kid.
        val kid = LanCrypto.kidOf(shared)
        assertEquals(LanCrypto.securityCodeOf(shared), answer.securityCode)
        assertEquals(PEER_ID, answer.peerId)

        // The server registered exactly that derived kid, and publishes it in `/info` so the peer
        // knows what it may address us with.
        assertArrayEquals(shared, store.secretForKid(kid))
        assertEquals(PEER_ID, store.peerIdForKid(kid))
        assertEquals(answer.securityCode, store.securityCodeFor(PEER_ID))
        assertTrue(decode<LanPeerInfo>(get("/info", v2Headers()).second).kids.contains(kid))

        // The code is burned: committing again says so, in the code the UI branches on.
        val (againCode, againBody) = commit(begin.pairCode, PEER_ID, LanCrypto.randomBytes(LanCrypto.KEY_BYTES))
        assertEquals(409, againCode)
        assertTrue(againBody, againBody.contains(LanError.PAIR_CONSUMED.wire))
    }

    // endregion

    // region envelope plane

    @Test
    fun `a paired peer's enveloped notify is accepted and answered in the same envelope`() {
        val peer = pairWithServer()
        val (code, body) =
            post(
                "/round/notify",
                envelope(peer.shared, "round/notify", peer.kid, "{}"),
                headers = v2Headers() + (LanProtocol.KID_HEADER to peer.kid),
            )
        assertEquals(body, 200, code)
        // The body is an envelope, so "queued" can only be found on the other side of the key.
        assertTrue("answer was not a round/notify envelope: $body", openResponse(peer.shared, "round/notify", body).contains("queued"))
    }

    @Test
    fun `ciphertext cannot be carried from one route to another`() {
        val peer = pairWithServer()
        // a genuine envelope for /round/notify, replayed against /devices/sync
        val (code, body) =
            post(
                "/devices/sync",
                envelope(peer.shared, "round/notify", peer.kid, "{}"),
                headers = v2Headers() + (LanProtocol.KID_HEADER to peer.kid),
            )
        assertEquals(401, code)
        // route is the HKDF info, so the second key cannot even open the first route's payload
        assertTrue(body, body.contains(LanError.DECRYPT_FAILED.wire))
    }

    @Test
    fun `a tampered envelope is refused`() {
        val peer = pairWithServer()
        val sealed = decode<LanEnvelope>(envelope(peer.shared, "round/notify", peer.kid, "{}"))
        val ct = LanCrypto.unBase64(sealed.ct)!!
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        val (code, body) =
            post(
                "/round/notify",
                LanProtocol.json.encodeToString(LanEnvelope.serializer(), sealed.copy(ct = LanCrypto.base64(ct))),
                headers = v2Headers() + (LanProtocol.KID_HEADER to peer.kid),
            )
        assertEquals(401, code)
        assertTrue(body, body.contains(LanError.DECRYPT_FAILED.wire))
    }

    @Test
    fun `an envelope that opens onto a non-JSON payload says bad payload`() {
        // Distinct from bad_envelope on the wire (SPEC-v2 §5): the key worked, the contents did not.
        val peer = pairWithServer()
        val (code, body) =
            post(
                "/devices/sync",
                envelope(peer.shared, "devices/sync", peer.kid, "not json at all"),
                headers = v2Headers() + (LanProtocol.KID_HEADER to peer.kid),
            )
        assertEquals(401, code)
        assertTrue(body, body.contains(LanError.BAD_PAYLOAD.wire))
    }

    @Test
    fun `an envelope addressed with someone else's kid is refused`() {
        val peer = pairWithServer()
        val (code, body) =
            post(
                "/round/notify",
                envelope(peer.shared, "round/notify", "deadbeef", "{}"),
                headers = v2Headers() + (LanProtocol.KID_HEADER to "deadbeef"),
            )
        assertEquals(403, code)
        assertTrue(body, body.contains(LanError.NOT_PAIRED.wire))
    }

    // endregion

    @Test
    fun `forgetting a peer drops its key material`() {
        val secret = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        store.remember(LanPeerInfo(id = "peer-z", name = "Peer Z"), "10.0.0.9", LanSource.UDP)
        store.recordPairing(LanPeerInfo(id = "peer-z", name = "Peer Z"), secret)
        val kid = LanCrypto.kidOf(secret)
        assertNotNull(store.secretForKid(kid))
        assertEquals(secret.size, LanCrypto.KEY_BYTES)
        store.forget("peer-z")
        assertNull(store.secretForKid(kid))
        assertEquals("", store.securityCodeFor("peer-z"))
        assertFalse(store.devices.value.any { it.id == "peer-z" })
        store.clearLog()
    }

    private companion object {
        const val MIME_JSON = "application/json"
        const val MIME_PACKAGE = "application/octet-stream"
        const val PEER_ID = "2f2f2f2f-2f2f-42f2-8f2f-2f2f2f2f2f2f"
    }
}
