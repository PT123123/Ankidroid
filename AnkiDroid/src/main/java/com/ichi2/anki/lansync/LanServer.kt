// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.InputStream
import java.net.URLDecoder

private typealias Status = NanoHTTPD.Response.Status
private typealias IStatus = NanoHTTPD.Response.IStatus

/**
 * The sync API this device offers to its peers, bound to every interface (`0.0.0.0`).
 *
 * v2 routes (SPEC-v2 §5):
 * - `GET  /`                liveness, contains both magics so v1 phones still accept us
 * - `GET  /info`            identity + `modes` + `roles` + kid list, no key material
 * - `POST /pair/begin`      creates the 6-digit/5-minute pairing session
 * - `POST /pair/commit`     consumes it and swaps wrapped `device_secret`s
 * - `POST /devices/sync`    trust-list exchange, public fields only, envelope-protected
 * - `POST /round/notify`    "I have changes", triggers a pull round, envelope-protected
 * - `POST /apkg/export`     raw `.apkg` bytes, request envelope-authenticated, nonce echoed in
 *                           `X-Anki-Xfer` (§4.3: the bulk leg authenticates, it does not encrypt)
 * - `POST /apkg/import`     merge with a profile-routing guard; envelope in `X-Anki-Envelope`
 * - `POST /hub/grant`       exists for symmetry; Android is hub *client* only, so 403 here
 * - `GET  /state`           current progress + recent log/round pages, loopback only, no envelope
 *
 * Unpaired callers never reach a data-plane route: the envelope must resolve to a kid stored at
 * pairing time (default-deny). The legacy plaintext `/export` + `/import` pair only works when the
 * user explicitly enabled the v1 downgrade, and every round run through it is tagged
 * [LanProtocol.PLAINTEXT_MARKER] in the activity log.
 *
 * Every refusal is a §5 error code in a JSON body — including the 4xx NanoHTTPD happens to have
 * enum entries for — because a desktop client branches on `body["error"]` and a plaintext body
 * there reads to it as a broken server.
 */
class LanSyncServer(
    port: Int,
) : NanoHTTPD(ANY_ADDRESS, port) {
    /** Called when a peer notifies it has changes; the manager schedules a pull round. */
    var onRoundNotify: ((peerId: String, peerName: String) -> Unit)? = null

    /** Replays are remembered across the whole server lifetime, not per request. */
    private val replayWindow = LanReplayWindow()

    /**
     * Whether *this* request's body reached a handler. One connection is served by one thread and
     * requests are handled back to back on it, so a thread local is per-request here; [serve] uses
     * it to notice a response that was produced while the caller's body was still in the socket.
     */
    private val bodyRead = ThreadLocal.withInitial { false }

    override fun serve(session: IHTTPSession): Response {
        bodyRead.set(false)
        val response =
            try {
                route(session)
            } catch (busy: LanBusyException) {
                Timber.i("Collection busy, refusing %s %s", session.method, session.uri)
                respond(Status.CONFLICT, MIME_JSON, errorJson(LanError.BUSY, BUSY_BODY))
            } catch (security: LanSecurityException) {
                Timber.i("Envelope rejected on %s %s: %s", session.method, session.uri, security.message)
                refused(security.code, security.message.orEmpty())
            } catch (e: Exception) {
                Timber.w(e, "LAN sync request failed: %s %s", session.method, session.uri)
                refused(LanError.INTERNAL_ERROR, e.message ?: "error")
            }
        // A reply sent *before* the body was read leaves those bytes in the socket, and NanoHTTPD
        // would hand them to the next request on this keep-alive connection as its request line -
        // which shows up as a meaningless 400 in a client (OkHttp) that reuses connections. The
        // body can be gigabytes on the import legs, so hang up rather than wait for it.
        if (bodyRead.get() != true && (session.header("content-length")?.toLongOrNull() ?: 0L) > 0L) {
            response.closeConnection(true)
        }
        return response
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/')
        if (session.method == Method.GET) {
            when (path) {
                "" ->
                    return respond(
                        Status.OK,
                        MIME_PLAINTEXT,
                        "anki LAN sync ${LanProtocol.MAGIC} (legacy ${LanProtocol.MAGIC_V1})",
                    )
                "/info" -> return infoResponse(session)
                // SPEC-v2 §5: `/state` is a control-console read, never a LAN-readable one.
                "/state" -> return stateResponse(session)
                "/export" -> return legacy(session, "/export") { exportLegacy(session) }
            }
        }
        if (session.method == Method.POST) {
            when (path) {
                "/pair/begin" -> return pairBegin(session)
                "/pair/commit" -> return pairCommit(session)
                "/devices/sync" -> return authenticated(session, path) { devicesSync(it) }
                "/round/notify" -> return authenticated(session, path) { roundNotify(it) }
                // The bulk legs differ from the JSON routes: `.apkg` bytes own the body, so the
                // envelope moves to a header and the answer is raw bytes (SPEC-v2 §4.3).
                "/apkg/export" -> return authenticated(session, path, envelopeFrom = ENVELOPE_BODY) { exportResponse(session, it) }
                "/apkg/import" -> return authenticated(session, path, envelopeFrom = ENVELOPE_HEADER) { importResponse(session, it) }
                "/hub/grant" -> return authenticated(session, path) {
                    refused(LanError.HUB_OFF, NOT_A_HUB)
                }
                "/import" -> return legacy(session, "/import") { importLegacy(session) }
            }
        }
        return refused(LanError.NOT_FOUND, session.uri)
    }

    // region v2 envelope plumbing

    /** Authenticated caller: which paired peer this is, and the kid/ts it addressed with. */
    private data class LanAuth(
        val peerId: String,
        val peerName: String,
        val kid: String,
        val ts: Long,
        val deviceSecret: ByteArray,
        /** The request envelope's own nonce, echoed back on the bulk export leg. */
        val nonce: String,
        val requestPlaintext: String,
    )

    /**
     * Verifies `X-Anki-Sync`/`X-Anki-Kid` plus the envelope, against the secret registered under
     * that kid. The envelope is the request body except on `apkg/import`, where the package occupies
     * the body and the envelope arrives in `X-Anki-Envelope` (SPEC-v2 §4.2).
     *
     * [route] is the path without its leading slash, because it is also the HKDF `info` both sides
     * must agree on byte for byte.
     */
    private fun authenticated(
        session: IHTTPSession,
        path: String,
        envelopeFrom: String = ENVELOPE_BODY,
        block: (LanAuth) -> Response,
    ): Response {
        val route = path.trimStart('/')
        if (session.header(LanProtocol.REQUEST_HEADER) != LanProtocol.REQUEST_HEADER_VALUE) {
            return respond(Status.FORBIDDEN, MIME_JSON, errorJson(LanError.NEED_MAGIC, "missing ${LanProtocol.REQUEST_HEADER}"))
        }
        val kid =
            session.header(LanProtocol.KID_HEADER)?.takeIf { it.isNotBlank() }
                ?: return respond(Status.FORBIDDEN, MIME_JSON, errorJson(LanError.NOT_PAIRED, "missing kid header"))
        val store = LanStore.instance
        val deviceSecret =
            store.secretForKid(kid)
                ?: return respond(Status.FORBIDDEN, MIME_JSON, errorJson(LanError.NOT_PAIRED, "kid not paired"))
        val envelopeJson =
            when (envelopeFrom) {
                ENVELOPE_HEADER -> session.header(LanProtocol.ENVELOPE_HEADER)?.let(LanCrypto::unBase64Url)
                // The body is the envelope here, so it must be read fully before decryption.
                else -> readBody(session)
            } ?: return refused(LanError.BAD_ENVELOPE, "missing envelope")
        val envelope =
            runCatching { LanProtocol.json.decodeFromString(LanEnvelope.serializer(), envelopeJson) }
                .getOrNull() ?: return refused(LanError.BAD_ENVELOPE, "malformed envelope")
        if (envelope.kid != kid) return refused(LanError.KID_MISMATCH)
        val plaintext =
            runCatching { LanCrypto.openEnvelope(deviceSecret, route, envelope, window = replayWindow) }
                .getOrElse { error ->
                    // Whatever the envelope actually failed on - stale ts, replayed nonce, wrong
                    // key - is the answer the caller needs; it is never a reason to hint at the key.
                    return if (error is LanSecurityException) {
                        refused(error.code, error.message.orEmpty())
                    } else {
                        refused(LanError.BAD_ENVELOPE, "unreadable envelope")
                    }
                }
        val peerId = store.peerIdForKid(kid) ?: ""
        val auth =
            LanAuth(
                peerId = peerId,
                peerName =
                    session
                        .header(LanProtocol.PEER_NAME_HEADER)
                        ?.let { raw ->
                            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                        }.orEmpty(),
                kid = kid,
                ts = envelope.ts,
                deviceSecret = deviceSecret,
                nonce = envelope.nonce,
                requestPlaintext = String(plaintext),
            )
        return block(auth)
    }

    /** Seals a JSON answer for an authenticated caller, keyed by the *request's* route. */
    private fun sealed(
        auth: LanAuth,
        route: String,
        json: String,
    ): Response {
        val envelope =
            LanCrypto.sealEnvelope(
                auth.deviceSecret,
                route.trimStart('/'),
                auth.kid,
                json.toByteArray(),
                nonce = LanCrypto.randomBytes(LanCrypto.NONCE_BYTES),
            )
        return respond(Status.OK, MIME_JSON, LanProtocol.json.encodeToString(LanEnvelope.serializer(), envelope))
    }

    // endregion

    // region pairing

    /**
     * SPEC-v2 §5: a pairing code may only be minted by something running *on this phone*.
     *
     * Our own screen does not go through this route at all ([LanStore.pairing] is opened in
     * process), so the loopback gate costs no feature - what it removes is a LAN caller's ability to
     * put a code on the user's screen and then pair against it unprompted.
     */
    private fun pairBegin(session: IHTTPSession): Response {
        if (!isLoopback(session.remoteIpAddress)) {
            return refused(LanError.LOOPBACK_ONLY, "a pairing code is minted locally")
        }
        val store = LanStore.instance
        store.pairing.prune()
        val pairing = store.pairing.beginLocal()
        val body =
            LanProtocol.json.encodeToString(
                PairBeginResponse.serializer(),
                PairBeginResponse(
                    pairCode = pairing.pairCode,
                    expiresIn = ((pairing.expiresAt - lanNow()) / 1000L).coerceAtLeast(0L),
                    // SPEC-v2 §4.1: the security code is a hash of the shared key, so it can only be
                    // checked *after* commit - never phrase it as a precondition for confirming.
                    hint = "对方输入本码完成配对后，两端会各显示一个 4 位安全码；核对一致才算信任建立，不一致请解除配对重来",
                ),
            )
        Timber.i("LAN sync pairing session opened")
        return respond(Status.OK, MIME_JSON, body)
    }

    private fun pairCommit(session: IHTTPSession): Response {
        val raw = readBody(session) ?: return refused(LanError.BAD_ENVELOPE, "commit body required")
        val request =
            runCatching { LanProtocol.json.decodeFromString(PairCommitRequest.serializer(), raw) }
                .getOrNull() ?: return refused(LanError.BAD_ENVELOPE, "malformed commit body")
        val store = LanStore.instance
        when (store.pairing.commit(request.pairCode)) {
            LanCommitOutcome.WRONG_CODE ->
                return respond(Status.UNAUTHORIZED, MIME_JSON, errorJson(LanError.PAIR_INVALID, "invalid pairing code"))
            LanCommitOutcome.EXPIRED ->
                return respond(Status.GONE, MIME_JSON, errorJson(LanError.PAIR_EXPIRED, "pairing code expired"))
            LanCommitOutcome.CONSUMED ->
                return respond(Status.CONFLICT, MIME_JSON, errorJson(LanError.PAIR_CONSUMED, "pairing code already consumed"))
            LanCommitOutcome.OK -> Unit
        }
        val theirs =
            runCatching { LanCrypto.unwrapSecret(request.pairCode, request.envelope) }
                .getOrElse {
                    // The code was already consumed by the check above: a caller that guessed the
                    // code but not the wrap key gets a 401 and a burned code (SPEC-v2 §4.1 - the
                    // 6-digit code is enumerable, so a matching code may not be re-used to try
                    // another envelope).
                    Timber.i("LAN sync commit failed unwrap: %s", it.message)
                    return respond(Status.UNAUTHORIZED, MIME_JSON, errorJson(LanError.PAIR_INVALID, "invalid pairing code"))
                }
        if (theirs.size != LanCrypto.KEY_BYTES) return refused(LanError.BAD_PEER_INFO, "contribution is not 32 bytes")
        if (store.isSelf(request.peerInfo.id)) return refused(LanError.BAD_PEER_INFO, "cannot pair with self")
        // A fresh contribution per pairing, never re-used and never sent anywhere else (SPEC-v2 §2).
        val ours = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val shared = LanCrypto.combineSecrets(ours, theirs, store.deviceId, request.peerInfo.id)
        store.remember(request.peerInfo, session.remoteIpAddress ?: "", LanSource.MANUAL)
        store.recordPairing(request.peerInfo, shared)
        val body =
            LanProtocol.json.encodeToString(
                PairCommitResponse.serializer(),
                PairCommitResponse(
                    peerInfo = store.selfInfo(listeningPort),
                    envelope = LanCrypto.wrapSecret(request.pairCode, ours),
                    securityCode = LanCrypto.securityCodeOf(shared),
                    peerId = request.peerInfo.id,
                ),
            )
        return respond(Status.OK, MIME_JSON, body)
    }

    // endregion

    // region control-plane data routes

    /** `GET /state`：只给本机回环读（SPEC-v2 §5），所以它不参与信封认证。 */
    private fun stateResponse(session: IHTTPSession): Response {
        if (!isLoopback(session.remoteIpAddress)) {
            return refused(LanError.LOOPBACK_ONLY, "/state is a local control-console read")
        }
        val store = LanStore.instance
        val json =
            LanProtocol.json.encodeToString(
                StateResponse.serializer(),
                StateResponse(
                    busy = LanEngine.isBusy,
                    progress = LanEngine.progress.value?.let { LanProgress(it.peer, it.step) },
                    log = store.log.value.take(50),
                    rounds = store.rounds.value.take(50),
                ),
            )
        return respond(Status.OK, MIME_JSON, json)
    }

    private fun isLoopback(address: String?): Boolean =
        address != null && (address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:1")

    private fun roundNotify(auth: LanAuth): Response {
        Timber.i("LAN sync round notify from %s", auth.peerName.ifBlank { auth.peerId })
        onRoundNotify?.invoke(auth.peerId, auth.peerName)
        return sealed(auth, "round/notify", """{"queued":true}""")
    }

    /**
     * Exchanges the trust list, public fields only: no kid, no secret, no IP beyond what the
     * peer can already observe (SPEC-v2 §4.3/§7). Response `since` cursor is wall-clock.
     */
    private fun devicesSync(auth: LanAuth): Response {
        val request =
            runCatching { LanProtocol.json.decodeFromString(DevicesSyncRequest.serializer(), auth.requestPlaintext) }
                .getOrNull() ?: return refused(LanError.BAD_PAYLOAD, "malformed devices body")
        val store = LanStore.instance
        request.devices.forEach { incoming ->
            // Only refresh what the wire actually carries (SPEC-v2 §5: public fields only).
            // Addresses and pairing state belong to local discovery, never to a peer's say-so.
            val known = store.devices.value.firstOrNull { it.id == incoming.peerId }
            if (known != null && !store.isSelf(incoming.peerId)) {
                store.remember(
                    LanPeerInfo(
                        id = incoming.peerId,
                        name = incoming.name,
                        port = known.port,
                        protocol = incoming.protocol,
                        modes = known.modes,
                        roles = known.roles,
                        platform = incoming.kind,
                        profile = known.profile,
                    ),
                    known.ip,
                    known.source,
                )
            }
        }
        val json =
            LanProtocol.json.encodeToString(
                DevicesSyncResponse.serializer(),
                DevicesSyncResponse(devices = store.devices.value.map { it.publicProjection() }, now = lanNow() / 1000.0),
            )
        return sealed(auth, "devices/sync", json)
    }

    // endregion

    // region apkg data plane (authenticated, not encrypted — SPEC-v2 §4.3)

    /**
     * Streams the collection as **raw `.apkg` bytes**. The caller was authenticated by its envelope;
     * the response carries no envelope of its own, so it echoes the request nonce in `X-Anki-Xfer`
     * to bind these bytes to that one verified request. The collection lock is held only while
     * exporting, never while the bytes go over the wire.
     */
    private fun exportResponse(
        session: IHTTPSession,
        auth: LanAuth,
    ): Response {
        val started = lanNow()
        val peer = auth.peerName.ifBlank { "peer" }
        val file = runBlocking { LanEngine.exportPackage(peer) }
        LanStore.instance.log(
            LanLogEntry(
                at = lanNow(),
                deviceId = auth.peerId,
                deviceName = peer,
                direction = LanDirection.PULL,
                result = LanResult.OK,
                bytes = file.length(),
                millis = lanNow() - started,
                detail = session.remoteIpAddress.orEmpty(),
            ),
        )
        val response = respond(Status.OK, MIME_PACKAGE, file.inputStream(), file.length())
        response.addHeader(LanProtocol.XFER_HEADER, auth.nonce)
        return response
    }

    private fun importResponse(
        session: IHTTPSession,
        auth: LanAuth,
    ): Response {
        val declared =
            session.header("content-length")?.toLongOrNull()
                ?: return refused(LanError.LENGTH_REQUIRED, "import needs Content-Length")
        if (declared > LanProtocol.MAX_IMPORT_BYTES) {
            return refused(LanError.TOO_LARGE, "package too large")
        }
        val meta =
            runCatching {
                LanProtocol.json.decodeFromString(ImportMeta.serializer(), auth.requestPlaintext.ifBlank { "{}" })
            }.getOrNull() ?: return refused(LanError.BAD_PAYLOAD, "malformed import meta")
        val store = LanStore.instance
        // Profile-routing guard: a package announced for another profile must not silently land in
        // whatever profile happens to be open right now (v1 defect, SPEC-v2 §7).
        if (meta.profile.isNotBlank() && meta.profile != store.activeProfileName) {
            return respond(
                Status.FORBIDDEN,
                MIME_JSON,
                errorJson(
                    LanError.PROFILE_MISMATCH,
                    "package is for profile '${meta.profile}', this device shows '${store.activeProfileName}'",
                ),
            )
        }
        val peer = auth.peerName.ifBlank { "peer" }
        val started = lanNow()
        return try {
            val bytes =
                runBlocking {
                    // The importer reads exactly `declared` bytes, so the socket is left
                    // at a request boundary whatever it then decides.
                    bodyRead.set(true)
                    LanEngine.receiveAndImportRaw(
                        body = session.inputStream,
                        declaredBytes = declared,
                        peerName = peer,
                    )
                }
            store.log(
                LanLogEntry(
                    at = lanNow(),
                    deviceId = auth.peerId,
                    deviceName = peer,
                    direction = LanDirection.PUSH,
                    result = LanResult.OK,
                    bytes = bytes,
                    millis = lanNow() - started,
                    detail = session.remoteIpAddress.orEmpty(),
                ),
            )
            sealed(auth, "apkg/import", """{"imported":$bytes}""")
        } catch (busy: LanBusyException) {
            store.log(busyEntry(auth, peer, started))
            respond(Status.CONFLICT, MIME_JSON, errorJson(LanError.BUSY, BUSY_BODY))
        }
    }

    private fun busyEntry(
        auth: LanAuth,
        peer: String,
        started: Long,
    ): LanLogEntry =
        LanLogEntry(
            at = lanNow(),
            deviceId = auth.peerId,
            deviceName = peer,
            direction = LanDirection.PUSH,
            result = LanResult.BUSY,
            millis = lanNow() - started,
            detail = BUSY_BODY,
            errorCode = LanError.BUSY.wire,
        )

    // endregion

    // region legacy v1 plaintext downgrade

    /**
     * v1 `/export` + `/import` unchanged in shape (aw-sync-rust era phones), but off unless the
     * user explicitly allows the plaintext downgrade; rounds through here carry the
     * `SECURITY: plaintext-v1` marker in the log (SPEC-v2 §4.2).
     */
    private fun legacy(
        session: IHTTPSession,
        path: String,
        block: (IHTTPSession) -> Response,
    ): Response {
        if (!LanStore.instance.allowPlaintextV1) {
            return refused(LanError.V1_DISABLED, "plaintext v1 is disabled; enable it in LAN sync settings")
        }
        if (path == "/import" && session.header(LanProtocol.LEGACY_REQUEST_HEADER) != LanProtocol.LEGACY_REQUEST_HEADER_VALUE) {
            return refused(LanError.V1_DISABLED, "missing ${LanProtocol.LEGACY_REQUEST_HEADER}")
        }
        return block(session)
    }

    private fun exportLegacy(session: IHTTPSession): Response {
        val started = lanNow()
        val peer = session.legacyPeerName()
        val file = runBlocking { LanEngine.exportPackage(peer) }
        LanStore.instance.log(
            legacyEntry(peer, session.legacyPeerId(), LanDirection.PULL, LanResult.OK, file.length(), started, session.remoteIpAddress),
        )
        return respond(Status.OK, MIME_PACKAGE, file.inputStream(), file.length())
    }

    private fun importLegacy(session: IHTTPSession): Response {
        val declared =
            session.header("content-length")?.toLongOrNull()
                ?: return refused(LanError.LENGTH_REQUIRED, "import needs Content-Length")
        if (declared > LanProtocol.MAX_IMPORT_BYTES) {
            return refused(LanError.TOO_LARGE, "package too large")
        }
        // NanoHTTPD 2.3 leaves the body in the socket stream once the headers are parsed.
        val peer = session.legacyPeerName()
        val started = lanNow()
        return try {
            val bytes =
                runBlocking {
                    bodyRead.set(true)
                    LanEngine.receiveAndImport(session.inputStream, declared, peer)
                }
            LanStore.instance.log(
                legacyEntry(peer, session.legacyPeerId(), LanDirection.PUSH, LanResult.OK, bytes, started, session.remoteIpAddress),
            )
            respond(Status.OK, MIME_PLAINTEXT, "imported $bytes bytes")
        } catch (busy: LanBusyException) {
            LanStore.instance.log(
                legacyEntry(peer, session.legacyPeerId(), LanDirection.PUSH, LanResult.BUSY, 0L, started, BUSY_BODY),
            )
            respond(Status.CONFLICT, MIME_PLAINTEXT, BUSY_BODY)
        } catch (e: Exception) {
            LanStore.instance.log(
                legacyEntry(peer, session.legacyPeerId(), LanDirection.PUSH, LanResult.ERROR, 0L, started, e.message.orEmpty()),
            )
            throw e
        }
    }

    private fun legacyEntry(
        peer: String,
        peerId: String,
        direction: LanDirection,
        result: LanResult,
        bytes: Long,
        startedAt: Long,
        detail: String?,
    ): LanLogEntry =
        LanLogEntry(
            at = lanNow(),
            deviceId = peerId,
            deviceName = peer,
            direction = direction,
            result = result,
            bytes = bytes,
            millis = lanNow() - startedAt,
            detail = detail.orEmpty(),
            errorCode = LanProtocol.PLAINTEXT_MARKER,
        )

    // endregion

    // region plumbing

    private fun respond(
        status: Status,
        mime: String,
        body: String,
    ): Response = newFixedLengthResponse(status, mime, body)

    private fun respond(
        status: Status,
        mime: String,
        body: ByteArray,
        length: Long,
    ): Response = newFixedLengthResponse(status, mime, body.inputStream(), length)

    private fun respond(
        status: Status,
        mime: String,
        body: InputStream,
        length: Long,
    ): Response = newFixedLengthResponse(status, mime, body, length)

    /**
     * NanoHTTPD 2.3 keys its parsed headers by `name.toLowerCase()` - hyphen included - so look
     * that spelling up first and fall back to the underscored one for the sake of any fork that
     * normalizes differently. Getting this wrong fails *closed* (a missing `X-Anki-Sync` reads as
     * an unauthenticated caller), which is why it has to be handled here rather than at 6 call sites.
     */
    private fun IHTTPSession.header(name: String): String? {
        val lower = name.lowercase()
        return headers[lower] ?: headers[lower.replace('-', '_')]
    }

    private fun IHTTPSession.legacyPeerName(): String =
        header(LanProtocol.LEGACY_PEER_NAME_HEADER)
            ?.let { raw -> runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) }
            ?.takeIf { it.isNotBlank() }
            ?: "peer"

    private fun IHTTPSession.legacyPeerId(): String = header(LanProtocol.LEGACY_PEER_ID_HEADER).orEmpty()

    /**
     * Reads a small plaintext body (pairing endpoints). NanoHTTPD 2.3 only hands the raw socket
     * stream to handlers, so read exactly `content-length` bytes and never trust more.
     */
    private fun readBody(session: IHTTPSession): String? {
        val declared = session.header("content-length")?.toLongOrNull() ?: return null
        if (declared !in 1..MAX_CONTROL_BODY) return null
        bodyRead.set(true)
        val buffer = ByteArray(declared.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = session.inputStream.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read)
    }

    private fun errorJson(
        code: LanError,
        message: String = code.wire,
    ): String = """{"error":"${code.wire}","message":"${message.escapeJson()}"}"""

    /**
     * The §5 answer for [code]: the code's own HTTP status and a JSON body. NanoHTTPD's `Status`
     * enum has no 507, so the status is built from the code rather than looked up - a peer must see
     * the same numbers from a phone as from the desktop.
     */
    private fun refused(
        code: LanError,
        message: String = code.wire,
    ): Response = newFixedLengthResponse(code.toStatus(), MIME_JSON, errorJson(code, message))

    private fun LanError.toStatus(): IStatus =
        object : IStatus {
            override fun getRequestStatus(): Int = status

            // NanoHTTPD's status line is `"HTTP/1.1 " + getDescription()`, i.e. the description
            // carries the number ("403 Forbidden"); a bare reason phrase is not a parsable status
            // line and clients fail on it before ever seeing the JSON body.
            override fun getDescription(): String = "$status ${wire.replace('_', ' ')}"
        }

    /**
     * `GET /info` (SPEC-v2 §5), answered in whichever protocol the caller speaks.
     *
     * A v2 client sends the magic header and gets `device_id`/`kind`/`ts`; an un-upgraded fork
     * phone sends nothing and understands only `id`/`platform` - one merged payload cannot serve
     * both because the two disagree on `protocol`, which is exactly the field that must not lie.
     */
    private fun infoResponse(session: IHTTPSession): Response {
        val store = LanStore.instance
        val speaksV2 = session.header(LanProtocol.REQUEST_HEADER) == LanProtocol.REQUEST_HEADER_VALUE
        val info = store.selfInfo(listeningPort)
        val body =
            if (speaksV2) {
                LanProtocol.json.encodeToString(LanPeerInfo.serializer(), info)
            } else {
                LanProtocol.json.encodeToString(LanPeerInfoV1.serializer(), info.asV1(store.appVersion))
            }
        return respond(Status.OK, MIME_JSON, body)
    }

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val ANY_ADDRESS = "0.0.0.0"
        const val MIME_JSON = "application/json"
        const val MIME_PACKAGE = "application/octet-stream"

        /** Which leg carries the envelope: the body normally, the header once the package owns it. */
        const val ENVELOPE_BODY = "body"
        const val ENVELOPE_HEADER = "header"
        const val BUSY_BODY = "collection busy"
        private const val NOT_A_HUB = "this device does not serve the hub data plane"

        /** Pairing payloads are tiny; anything bigger is a scan, not a commit. */
        private const val MAX_CONTROL_BODY = 64L * 1024
    }
}

/**
 * Plaintext pairing endpoints (pre-trust, so not enveloped; SPEC-v2 §4.1).
 *
 * Field names are the desktop wire names verbatim: `security_code` is null at begin because the
 * code is a hash of the shared secret, which only exists after commit.
 */
@Serializable
data class PairBeginResponse(
    @SerialName("pair_code") val pairCode: String,
    @SerialName("security_code") val securityCode: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0L,
    val hint: String = "",
)

@Serializable
data class PairCommitRequest(
    @SerialName("pair_code") val pairCode: String,
    @SerialName("peer_info") val peerInfo: LanPeerInfo,
    /** The sender's 32-byte contribution, wrapped in a §4.2 envelope with `kid = "pair"`. */
    val envelope: LanEnvelope,
)

@Serializable
data class PairCommitResponse(
    @SerialName("peer_info") val peerInfo: LanPeerInfo,
    val envelope: LanEnvelope,
    @SerialName("security_code") val securityCode: String = "",
    @SerialName("peer_id") val peerId: String = "",
)

/**
 * One trust-list row as it crosses the wire (SPEC-v2 §5: public fields only). No secret, no
 * security code, and no address beyond what the peer can already observe on its own socket.
 */
@Serializable
data class LanPublicDevice(
    @SerialName("peer_id") val peerId: String,
    val name: String,
    val kind: String,
    val protocol: Int = LanProtocol.VERSION,
    val kid: String = "",
    val paired: Boolean = false,
    @SerialName("last_seen") val lastSeen: Double = 0.0,
)

/**
 * Public projection of a trust-list row, used both when serving `/devices/sync` and when calling
 * it. Our view of a peer's IP/port stays home: the SPEC only exports identity (SPEC-v2 §5), and
 * `last_seen` crosses as epoch seconds because that is what the desktop filters `since` against.
 */
fun LanDevice.publicProjection(): LanPublicDevice =
    LanPublicDevice(
        peerId = id,
        name = name,
        kind = kind,
        protocol = protocol,
        kid = kid,
        paired = paired,
        lastSeen = lastSyncAt / 1000.0,
    )

@Serializable
data class DevicesSyncRequest(
    val devices: List<LanPublicDevice> = emptyList(),
    val since: Double = 0.0,
)

@Serializable
data class DevicesSyncResponse(
    val devices: List<LanPublicDevice> = emptyList(),
    /** Server wall clock (seconds) to use as the caller's next `since`. */
    val now: Double = 0.0,
)

@Serializable
data class StateResponse(
    val busy: Boolean = false,
    val progress: LanProgress? = null,
    val log: List<LanLogEntry> = emptyList(),
    val rounds: List<LanRoundDetail> = emptyList(),
)

/** Metadata an encrypted import announces before the stream is decrypted. */
@Serializable
data class ImportMeta(
    val profile: String = "",
    val bytes: Long = 0L,
)
