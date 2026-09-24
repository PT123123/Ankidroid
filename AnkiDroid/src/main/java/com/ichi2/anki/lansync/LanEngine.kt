// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import anki.import_export.ImportAnkiPackageUpdateCondition
import anki.import_export.exportAnkiPackageOptions
import anki.import_export.exportLimit
import anki.import_export.importAnkiPackageOptions
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.libanki.exportAnkiPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the engine is doing right now, for the progress line on the LAN screen. */
enum class LanStep { EXPORTING, SENDING, REQUESTING, RECEIVING, IMPORTING, PROBING, PAIRING, HUB_SYNC }

@Serializable
data class LanProgress(
    val peer: String,
    val step: LanStep,
)

/** Outcome of one peer round: the two legs, each of which can fail independently. */
data class LanRoundResult(
    val device: LanDevice,
    val mode: LanDataMode?,
    val pushed: LanLogEntry?,
    val pulled: LanLogEntry?,
) {
    /** A hub round has a single leg (`pushed == null`), so only the legs that ran must be OK. */
    val ok: Boolean
        get() =
            listOfNotNull(pushed, pulled).any { it.result == LanResult.OK } &&
                listOfNotNull(pushed, pulled).all { it.result == LanResult.OK }
}

/**
 * Moves collection data between peers (SPEC-v2 §6).
 *
 * The data plane is chosen per round from both sides' advertised modes/roles - `hub` when the peer
 * serves a hub we can sync against through the *official* rslib path (real incremental sync, graves
 * propagation), otherwise `apkg`: an export of the whole collection with scheduling, deck configs
 * and media, merged on the other side. Deletions do not propagate in apkg mode, and a card
 * reviewed on both devices resolves by import order - see docs/lansync before changing this.
 *
 * v2 legs run inside AES-256-GCM envelopes keyed per route (SPEC-v2 §4.2). Plaintext v1 rounds
 * only happen when the user explicitly enabled the downgrade and are tagged in the log.
 *
 * Every leg is one-way and short-lived: the collection lock is only ever held for local work, never
 * across a network call, so two devices syncing at the same time cannot deadlock each other.
 */
object LanEngine {
    private const val MIME = "application/octet-stream"
    private const val COPY_BUFFER = 64 * 1024

    private val busy = AtomicBoolean(false)

    /** Whether a local leg currently holds the collection gate; surfaced by `GET /state`. */
    val isBusy: Boolean get() = busy.get()

    private val _progress = MutableStateFlow<LanProgress?>(null)
    val progress: StateFlow<LanProgress?> = _progress.asStateFlow()

    /** Files are reused between rounds; each leg overwrites its own before use. */
    private val outgoing: File by lazy { File(appContext.cacheDir, "lan_sync_out.apkg") }
    private val incoming: File by lazy { File(appContext.cacheDir, "lan_sync_in.apkg") }

    /** Response nonces of this process; the server-side twin lives in [LanSyncServer]. */
    private val responseWindow = LanReplayWindow()

    private val http: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Runs [block] with exclusive access to the collection, waiting up to [timeoutMs] for a peer
     * request to finish first. Throws [LanBusyException] when it stays busy - an inbound request
     * that has to wait is answered with 409 rather than tying up a socket indefinitely.
     */
    private suspend fun <T> exclusive(
        timeoutMs: Long = 90_000L,
        block: suspend () -> T,
    ): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!busy.compareAndSet(false, true)) {
            if (System.nanoTime() > deadline) throw LanBusyException()
            delay(150)
        }
        try {
            return block()
        } finally {
            busy.set(false)
        }
    }

    /** Exports the whole collection and returns the file to hand over to a peer. */
    suspend fun exportPackage(peer: String): File =
        exclusive {
            _progress.value = LanProgress(peer, LanStep.EXPORTING)
            outgoing.delete()
            withCol {
                exportAnkiPackage(
                    outPath = outgoing.absolutePath,
                    options =
                        exportAnkiPackageOptions {
                            withScheduling = true
                            withDeckConfigs = true
                            withMedia = true
                            legacy = false
                        },
                    limit = exportLimit { wholeCollection = anki.generic.Empty.getDefaultInstance() },
                )
            }
            if (!outgoing.exists() || outgoing.length() == 0L) {
                throw IOException("Export produced no data")
            }
            outgoing
        }

    /** Merges a received package into the open collection. */
    suspend fun importPackage(
        file: File,
        peer: String,
    ) = exclusive {
        _progress.value = LanProgress(peer, LanStep.IMPORTING)
        withCol {
            importAnkiPackage(
                file.absolutePath,
                importAnkiPackageOptions {
                    mergeNotetypes = true
                    // IF_NEWER is what makes the exchange convergent: whichever side has the newer
                    // note wins, so syncing A->B->A does not ping-pong stale data back and forth.
                    updateNotes = ImportAnkiPackageUpdateCondition.IMPORT_ANKI_PACKAGE_UPDATE_CONDITION_IF_NEWER
                    updateNotetypes = ImportAnkiPackageUpdateCondition.IMPORT_ANKI_PACKAGE_UPDATE_CONDITION_IF_NEWER
                    withScheduling = true
                    withDeckConfigs = true
                },
            )
        }
        Unit
    }

    /** Streams an inbound plaintext body to disk outside the collection lock, then merges it (v1). */
    suspend fun receiveAndImport(
        body: InputStream,
        declaredBytes: Long,
        peerName: String,
    ): Long {
        incoming.delete()
        val bytes =
            withContext(Dispatchers.IO) {
                incoming.outputStream().use { output -> body.copyCapped(output, declaredBytes) }
            }
        try {
            importPackage(incoming, peerName)
        } finally {
            incoming.delete()
        }
        return bytes
    }

    /** Server side of `POST /apkg/import`: the body is the raw package, so stream it straight to disk. */
    suspend fun receiveAndImportRaw(
        body: InputStream,
        declaredBytes: Long,
        peerName: String,
    ): Long =
        withContext(Dispatchers.IO) {
            incoming.delete()
            val bytes = incoming.outputStream().use { output -> body.copyCapped(output, declaredBytes) }
            try {
                importPackage(incoming, peerName)
            } finally {
                incoming.delete()
            }
            bytes
        }

    /** [InputStream.copyTo] cannot abort mid-stream, so the cap is enforced while writing. */
    private fun InputStream.copyCapped(
        target: OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw LanPeerRejectException("Body exceeds the declared $limit bytes")
            target.write(buffer, 0, read)
        }
        return total
    }

    // region round orchestration (SPEC-v2 §6.3)

    /**
     * One full round with [device] in whichever data plane both sides support: for apkg that is
     * push then pull, for hub a single rslib sync against the peer's server. Each leg is logged
     * separately so a half-success is visible, and every round produces a [LanRoundDetail] row.
     */
    suspend fun round(device: LanDevice): LanRoundResult {
        val store = LanStore.instance
        val mode = selectMode(device) ?: throw LanPeerRejectException(roundRefusalReason(device))
        val started = lanNow()
        store.noteRoundMode(device.id, mode)
        val result =
            when (mode) {
                LanDataMode.HUB -> hubRound(device, started)
                LanDataMode.APKG -> apkgRoundV2(device)
                LanDataMode.PLAINTEXT_V1 -> plaintextV1Round(device)
            }
        val detail =
            LanRoundDetail(
                at = lanNow(),
                deviceId = device.id,
                deviceName = device.name,
                mode = mode,
                legs = listOfNotNull(result.pushed, result.pulled).size,
                okLegs = listOfNotNull(result.pushed, result.pulled).count { it.result == LanResult.OK },
                pushBytes = result.pushed?.bytes ?: 0L,
                pullBytes = result.pulled?.bytes ?: 0L,
                millis = lanNow() - started,
                error =
                    listOfNotNull(result.pushed, result.pulled)
                        .firstOrNull { it.result != LanResult.OK }
                        ?.let { it.errorCode.ifBlank { it.detail } }
                        .orEmpty(),
            )
        store.recordRound(detail)
        if (result.ok) {
            store.markSynced(device.id)
            runCatching { roundNotify(device) } // best-effort: tell the peer we moved too
        }
        _progress.value = null
        return result
    }

    /** Which data plane this round may use; null means the round must not run at all. */
    fun selectMode(device: LanDevice): LanDataMode? {
        val store = LanStore.instance
        if (device.protocol >= LanProtocol.VERSION && store.isPaired(device.id)) {
            return negotiateDataMode(store.selfInfo(port = 0), device.asPeerInfo())
        }
        if (device.protocol < LanProtocol.VERSION && store.allowPlaintextV1) return LanDataMode.PLAINTEXT_V1
        return null
    }

    private fun roundRefusalReason(device: LanDevice): String =
        if (device.protocol < LanProtocol.VERSION) {
            "peer speaks plaintext v1 and the plaintext downgrade is disabled - pair it or enable the downgrade"
        } else {
            "no shared data plane with ${device.name}; pair first"
        }

    private fun LanDevice.asPeerInfo(): LanPeerInfo =
        LanPeerInfo(
            id = id,
            name = name,
            port = port,
            protocol = protocol,
            platform = kind,
            modes = modes,
            roles = roles,
            profile = profile,
        )

    /** v2 apkg round: push, then pull. Both legs are enveloped; the package bytes themselves are not. */
    private suspend fun apkgRoundV2(device: LanDevice): LanRoundResult {
        val pushed = pushV2(device)
        val pulled = pullV2(device)
        return LanRoundResult(device, LanDataMode.APKG, pushed, pulled)
    }

    // endregion

    // region v2 legs

    private suspend fun pushV2(device: LanDevice): LanLogEntry {
        val started = lanNow()
        return runCatching {
            val (kid, secret) = requireOutbound(device)
            val file = exportPackage(device.name)
            _progress.value = LanProgress(device.name, LanStep.SENDING)
            val meta =
                LanProtocol.json.encodeToString(
                    ImportMeta.serializer(),
                    ImportMeta(profile = LanStore.instance.activeProfileName, bytes = file.length()),
                )
            postPackage(device, "apkg/import", meta, kid, secret, file)
            log(device, LanDirection.PUSH, LanResult.OK, file.length(), started, "")
        }.getOrElse { error ->
            Timber.w(error, "LAN v2 push to %s failed", device.name)
            log(device, LanDirection.PUSH, resultOf(error), 0L, started, messageOf(error))
        }
    }

    private suspend fun pullV2(device: LanDevice): LanLogEntry {
        val started = lanNow()
        return runCatching {
            val (kid, secret) = requireOutbound(device)
            _progress.value = LanProgress(device.name, LanStep.REQUESTING)
            incoming.delete()
            val bytes =
                pullPackage(device, "apkg/export", kid, secret) {
                    _progress.value = LanProgress(device.name, LanStep.RECEIVING)
                }
            importPackage(incoming, device.name)
            incoming.delete()
            log(device, LanDirection.PULL, LanResult.OK, bytes, started, "")
        }.getOrElse { error ->
            Timber.w(error, "LAN v2 pull from %s failed", device.name)
            log(device, LanDirection.PULL, resultOf(error), 0L, started, messageOf(error))
        }
    }

    private fun requireOutbound(device: LanDevice): Pair<String, ByteArray> =
        LanStore.instance.outboundFor(device.id) ?: throw LanSecurityException("no key material for ${device.name}; pair first")

    // endregion

    // region plaintext v1 downgrade legs (explicit user opt-in only)

    private suspend fun plaintextV1Round(device: LanDevice): LanRoundResult {
        val pushed = v1Push(device)
        val pulled = v1Pull(device)
        return LanRoundResult(device, LanDataMode.PLAINTEXT_V1, pushed, pulled)
    }

    private suspend fun v1Push(device: LanDevice): LanLogEntry {
        val started = lanNow()
        return runCatching {
            val file = exportPackage(device.name)
            _progress.value = LanProgress(device.name, LanStep.SENDING)
            val bytes = file.length()
            postFile(device.url("import"), file)
            log(device, LanDirection.PUSH, LanResult.OK, bytes, started, LanProtocol.PLAINTEXT_MARKER, LanProtocol.PLAINTEXT_MARKER)
        }.getOrElse { error ->
            Timber.w(error, "LAN v1 push to %s failed", device.name)
            log(device, LanDirection.PUSH, resultOf(error), 0L, started, messageOf(error), LanProtocol.PLAINTEXT_MARKER)
        }
    }

    private suspend fun v1Pull(device: LanDevice): LanLogEntry {
        val started = lanNow()
        return runCatching {
            _progress.value = LanProgress(device.name, LanStep.REQUESTING)
            incoming.delete()
            val bytes = getFile(device.url("export"), incoming) { _progress.value = LanProgress(device.name, LanStep.RECEIVING) }
            importPackage(incoming, device.name)
            incoming.delete()
            log(device, LanDirection.PULL, LanResult.OK, bytes, started, LanProtocol.PLAINTEXT_MARKER, LanProtocol.PLAINTEXT_MARKER)
        }.getOrElse { error ->
            Timber.w(error, "LAN v1 pull from %s failed", device.name)
            log(device, LanDirection.PULL, resultOf(error), 0L, started, messageOf(error), LanProtocol.PLAINTEXT_MARKER)
        }
    }

    // endregion

    // region hub round (official rslib path, no reimplementation)

    /**
     * `hub` mode: fetch the grant over an enveloped `GET /hub/grant`, point the *existing* custom
     * sync server preferences at it and let the official rslib sync run (SPEC-v2 §6.2). Deletes
     * propagate in this mode; nothing here speaks the sync protocol itself.
     */
    private suspend fun hubRound(
        device: LanDevice,
        startedAt: Long,
    ): LanRoundResult {
        val pushedLeg =
            runCatching {
                val (kid, secret) = requireOutbound(device)
                _progress.value = LanProgress(device.name, LanStep.HUB_SYNC)
                val grantJson = requestEncryptedJson(device, "hub/grant", "{}", kid, secret)
                val grant = LanProtocol.json.decodeFromString(LanHubGrant.serializer(), grantJson)
                LanHubClient.applyAndSync(grant, device.name)
                log(device, LanDirection.PULL, LanResult.OK, 0L, startedAt, "hub grant applied")
            }.getOrElse { error ->
                Timber.w(error, "LAN hub round with %s failed", device.name)
                log(device, LanDirection.PULL, resultOf(error), 0L, startedAt, messageOf(error), errorCodeOf(error))
            }
        return LanRoundResult(device, LanDataMode.HUB, pushed = null, pulled = pushedLeg)
    }

    // endregion

    /**
     * Asks [device] who it is; also the probe used when a peer is first discovered.
     *
     * The v2 magic header announces us to a v2 peer. A phone that never upgraded has no idea what
     * `device_id` means and answers in the v1 shape, so both DTOs are tried - the plaintext-v1
     * downgrade path (SPEC-v2 §4.4) starts exactly here, and refusing to decode v1 would make the
     * "allow plaintext v1" switch useless.
     */
    suspend fun probe(device: LanDevice): LanPeerInfo =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(device.url("info"))
                    .header(LanProtocol.REQUEST_HEADER, LanProtocol.REQUEST_HEADER_VALUE)
                    .get()
                    .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val raw = response.body.string()
                val info =
                    runCatching { LanProtocol.json.decodeFromString(LanPeerInfo.serializer(), raw) }
                        .getOrNull()
                        ?: runCatching {
                            LanProtocol.json.decodeFromString(LanPeerInfoV1.serializer(), raw).asV2()
                        }.getOrNull()
                        ?: throw LanPeerRejectException("Peer is not an anki sync service")
                if (info.id.isBlank()) throw LanPeerRejectException("Peer is not an anki sync service")
                if (info.protocol !in LanProtocol.VERSION_V1..LanProtocol.VERSION) {
                    throw LanPeerRejectException("Peer protocol ${info.protocol} not understood")
                }
                info
            }
        }

    // region pairing client

    /**
     * Consumes the peer's pairing code: wraps *our* fresh 32-byte contribution under the code,
     * takes theirs from the answer, and combines the two into the single shared key (SPEC-v2 §4.1).
     *
     * The returned device carries the 4-hex security code — that is the moment it can first exist,
     * since it is a hash of the shared key. The user compares it against the other screen now, and
     * unpairs if the two differ.
     */
    suspend fun pairCommit(
        device: LanDevice,
        pairCode: String,
    ): LanDevice {
        val store = LanStore.instance
        val ours = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val body =
            LanProtocol.json.encodeToString(
                PairCommitRequest.serializer(),
                PairCommitRequest(
                    pairCode = pairCode,
                    peerInfo = store.selfInfo(device.port),
                    envelope = LanCrypto.wrapSecret(pairCode, ours),
                ),
            )
        val response =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(device.url("pair/commit"))
                        .header(LanProtocol.REQUEST_HEADER, LanProtocol.REQUEST_HEADER_VALUE)
                        .post(jsonBody(body))
                        .applyPeerHeaders()
                        .build()
                http.newCall(request).execute()
            }
        response.use {
            when (it.code) {
                401 -> throw LanSecurityException("wrong pairing code")
                409 -> throw LanSecurityException("pairing code already consumed")
                410 -> throw LanSecurityException("pairing code expired")
                else -> if (!it.isSuccessful) throw LanPeerRejectException("pair/commit failed with HTTP ${it.code}")
            }
            val answer = LanProtocol.json.decodeFromString(PairCommitResponse.serializer(), it.body.string())
            val theirs = LanCrypto.unwrapSecret(pairCode, answer.envelope)
            if (theirs.size != LanCrypto.KEY_BYTES) throw LanSecurityException("peer returned a bad key")
            val shared = LanCrypto.combineSecrets(ours, theirs, store.deviceId, answer.peerInfo.id)
            store.remember(answer.peerInfo, device.ip, device.source)
            store.recordPairing(answer.peerInfo, shared)
            return store.devices.value.first { d -> d.id == answer.peerInfo.id }
        }
    }

    // endregion

    // region generic enveloped requests

    /** `POST /devices/sync`: exchange public trust-list fields (SPEC-v2 §5). */
    suspend fun devicesSync(device: LanDevice) {
        val (kid, secret) = requireOutbound(device)
        val store = LanStore.instance
        val request =
            LanProtocol.json.encodeToString(
                DevicesSyncRequest.serializer(),
                DevicesSyncRequest(devices = store.devices.value.map { it.publicProjection() }),
            )
        val answer = requestEncryptedJson(device, "devices/sync", request, kid, secret)
        val merged = LanProtocol.json.decodeFromString(DevicesSyncResponse.serializer(), answer)
        merged.devices.forEach { peer ->
            if (store.isSelf(peer.peerId)) return@forEach
            // The wire carries no addresses, so whatever local discovery knows stays authoritative.
            val known = store.devices.value.firstOrNull { it.id == peer.peerId } ?: return@forEach
            store.remember(
                LanPeerInfo(
                    id = peer.peerId,
                    name = peer.name,
                    port = known.port,
                    protocol = peer.protocol,
                    platform = peer.kind,
                    modes = known.modes,
                    roles = known.roles,
                    profile = known.profile,
                ),
                known.ip,
                known.source,
            )
        }
    }

    /** `POST /round/notify`: tell a paired peer we just changed things. */
    suspend fun roundNotify(device: LanDevice) {
        val (kid, secret) = requireOutbound(device)
        requestEncryptedJson(device, "round/notify", "{}", kid, secret)
    }

    /**
     * An enveloped JSON call: POST, with the envelope *as the request body* (SPEC-v2 §4.2).
     *
     * [route] is the path minus its leading slash — it is also the HKDF `info`, so this exact
     * string has to match what the desktop side derives a key from.
     */
    private suspend fun requestEncryptedJson(
        device: LanDevice,
        route: String,
        plaintextJson: String,
        kid: String,
        secret: ByteArray,
    ): String {
        val ts = lanNow() / 1000
        val envelope = LanCrypto.sealEnvelope(secret, route, kid, plaintextJson.toByteArray(), ts)
        return withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(device.url(route))
                    .applyPeerHeaders()
                    .header(LanProtocol.REQUEST_HEADER, LanProtocol.REQUEST_HEADER_VALUE)
                    .header(LanProtocol.KID_HEADER, kid)
                    .post(jsonBody(envelopeJson(envelope)))
                    .build()
            http.newCall(request).execute().use { response ->
                readStatus(response, device.url(route))
                val answer =
                    runCatching { LanProtocol.json.decodeFromString(LanEnvelope.serializer(), response.body.string()) }
                        .getOrNull() ?: throw LanSecurityException("peer returned a non-envelope body")
                openResponseEnvelope(secret, route, answer)
            }
        }
    }

    /**
     * `POST /apkg/import`: the package owns the body, so the envelope rides in `X-Anki-Envelope`
     * as urlsafe-base64 of the same JSON (SPEC-v2 §4.3). Not encrypted — same-LAN is the trust model.
     */
    private suspend fun postPackage(
        device: LanDevice,
        route: String,
        plaintextJson: String,
        kid: String,
        secret: ByteArray,
        file: File,
    ) {
        val ts = lanNow() / 1000
        val envelope = LanCrypto.sealEnvelope(secret, route, kid, plaintextJson.toByteArray(), ts)
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(device.url(route))
                    .applyPeerHeaders()
                    .header(LanProtocol.REQUEST_HEADER, LanProtocol.REQUEST_HEADER_VALUE)
                    .header(LanProtocol.KID_HEADER, kid)
                    .header(LanProtocol.ENVELOPE_HEADER, LanCrypto.base64Url(envelopeJson(envelope)))
                    .post(file.asRequestBody(MIME.toMediaType()))
                    .build()
            http.newCall(request).execute().use { response ->
                readStatus(response, device.url(route))
                val answer =
                    runCatching { LanProtocol.json.decodeFromString(LanEnvelope.serializer(), response.body.string()) }
                        .getOrNull() ?: throw LanSecurityException("peer returned a non-envelope body")
                openResponseEnvelope(secret, route, answer)
            }
        }
    }

    /**
     * `POST /apkg/export`: the envelope authenticates the request, the response is the raw package
     * and carries no envelope of its own — so `X-Anki-Xfer` must echo *this* request's nonce, which
     * is the only thing binding these bytes to a request that passed authentication (SPEC-v2 §4.3).
     */
    private suspend fun pullPackage(
        device: LanDevice,
        route: String,
        kid: String,
        secret: ByteArray,
        onStarted: () -> Unit,
    ): Long {
        val ts = lanNow() / 1000
        val envelope = LanCrypto.sealEnvelope(secret, route, kid, "{}".toByteArray(), ts)
        return withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(device.url(route))
                    .applyPeerHeaders()
                    .header(LanProtocol.REQUEST_HEADER, LanProtocol.REQUEST_HEADER_VALUE)
                    .header(LanProtocol.KID_HEADER, kid)
                    .post(jsonBody(envelopeJson(envelope)))
                    .build()
            http.newCall(request).execute().use { response ->
                readStatus(response, device.url(route))
                if (response.header(LanProtocol.XFER_HEADER) != envelope.nonce) {
                    throw LanSecurityException("export did not bind the request nonce")
                }
                onStarted()
                incoming.outputStream().use { output -> response.body.byteStream().copyTo(output) }
                incoming.length()
            }
        }
    }

    private fun envelopeJson(envelope: LanEnvelope): String = LanProtocol.json.encodeToString(LanEnvelope.serializer(), envelope)

    /** Verifies the ts window on responses; replay protection for responses is the peer's problem
     * to create, so we only check freshness and rely on AEAD for authenticity. */
    private fun openResponseEnvelope(
        secret: ByteArray,
        route: String,
        envelope: LanEnvelope,
    ): String {
        if (kotlin.math.abs(envelope.ts - lanNow() / 1000) > LanProtocol.REPLAY_WINDOW_SECONDS) {
            throw LanSecurityException("response timestamp outside the replay window")
        }
        val nonce = LanCrypto.unBase64(envelope.nonce) ?: throw LanSecurityException("bad response nonce")
        if (!responseWindow.accept(envelope.kid, envelope.ts, nonce)) throw LanSecurityException("replayed response")
        val ct = LanCrypto.unBase64(envelope.ct) ?: throw LanSecurityException("bad response ciphertext")
        return String(
            runCatching { LanCrypto.open(LanCrypto.routeKey(secret, route), nonce, ct, LanCrypto.envelopeAad(envelope.kid, envelope.ts)) }
                .getOrElse { throw LanSecurityException("response failed authentication") },
        )
    }

    /** Lets the peer name us in *its* activity log instead of an IP the user cannot read. */
    private fun Request.Builder.applyPeerHeaders(): Request.Builder =
        header(
            LanProtocol.PEER_NAME_HEADER,
            URLEncoder.encode(LanStore.instance.deviceName, "UTF-8"),
        ).header(LanProtocol.PEER_ID_HEADER, LanStore.instance.deviceId)

    private suspend fun postFile(
        url: String,
        file: File,
    ) = withContext(Dispatchers.IO) {
        val request =
            Request
                .Builder()
                .url(url)
                .header(LanProtocol.LEGACY_REQUEST_HEADER, LanProtocol.LEGACY_REQUEST_HEADER_VALUE)
                .applyPeerHeaders()
                .post(file.asRequestBody(MIME.toMediaType()))
                .build()
        http.newCall(request).execute().use { response -> readStatus(response, url) }
    }

    private suspend fun getFile(
        url: String,
        target: File,
        onStarted: () -> Unit,
    ): Long =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header(LanProtocol.LEGACY_REQUEST_HEADER, LanProtocol.LEGACY_REQUEST_HEADER_VALUE)
                    .applyPeerHeaders()
                    .get()
                    .build()
            http.newCall(request).execute().use { response ->
                readStatus(response, url)
                onStarted()
                target.outputStream().use { output -> response.body.byteStream().copyTo(output, COPY_BUFFER) }
            }
        }

    /** Turns a non-2xx answer into the matching exception, so the caller can back off on 409. */
    private fun readStatus(
        response: Response,
        url: String,
    ) {
        when {
            response.isSuccessful -> return
            response.code == 409 -> throw LanBusyException()
            // The peer's own §5 code is in the body, but the status already says which of the two
            // failures this is for the user: "not paired" versus "your envelope did not verify".
            response.code == 401 -> throw LanSecurityException("envelope rejected by $url", LanError.DECRYPT_FAILED)
            response.code == 403 -> throw LanSecurityException("refused (not paired?) by $url", LanError.NOT_PAIRED)
            else -> throw IOException("HTTP ${response.code} from $url")
        }
    }

    private fun resultOf(error: Throwable): LanResult =
        when (error) {
            is LanBusyException -> LanResult.BUSY
            else -> LanResult.ERROR
        }

    private fun errorCodeOf(error: Throwable): String =
        when (error) {
            is LanBusyException -> LanError.BUSY.wire
            is LanSecurityException -> error.code.wire
            else -> ""
        }

    private fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName

    private fun log(
        device: LanDevice,
        direction: LanDirection,
        result: LanResult,
        bytes: Long,
        startedAt: Long,
        detail: String,
        errorCode: String = "",
    ): LanLogEntry {
        val entry =
            LanLogEntry(
                at = lanNow(),
                deviceId = device.id,
                deviceName = device.name,
                direction = direction,
                result = result,
                bytes = bytes,
                millis = lanNow() - startedAt,
                detail = detail,
                errorCode = errorCode,
            )
        LanStore.instance.log(entry)
        return entry
    }

    private fun jsonBody(text: String): okhttp3.RequestBody = text.toRequestBody("application/json".toMediaType())
}

/**
 * The `hub` grant payload as returned by a desktop hub through `GET /hub/grant` (SPEC-v2 §5/§6.2):
 * a plain username/password pair for the bundled syncserver, whose `hkey` is `sha1("user:pass")`.
 */
@Serializable
data class LanHubGrant(
    val endpoint: String,
    val username: String,
    val password: String,
    val portRange: String = "",
    val profile: String = "",
)
