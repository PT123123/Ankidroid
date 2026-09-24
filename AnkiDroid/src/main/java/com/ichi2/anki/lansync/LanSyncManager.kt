// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the LAN sync runtime: the HTTP server, discovery, the scheduler and the rounds - both the
 * ones the user starts and the automatic ones (SPEC-v2 §8).
 *
 * Lifecycle keeps v1's deliberate default: everything lives only while the LAN screen is in the
 * foreground ([show] / [hide]). The v2 addition is the explicit "keep online" opt-in
 * ([keepOnline]): a foreground service then holds the runtime up after the screen closes, with a
 * standing notification, because a permanently open port must not be something the user forgets.
 */
object LanSyncManager {
    /** Do not re-probe the same endpoint more often than this. */
    private const val PROBE_THROTTLE_MS = 15_000L

    private val store: LanStore = LanStore.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var server: LanSyncServer? = null
    private var discovery: LanDiscovery? = null
    private var addressJob: Job? = null
    private var appContext: Context? = null

    private val probedAt = ConcurrentHashMap<String, Long>()
    private val verifiedIds = ConcurrentHashMap.newKeySet<String>()

    /** Round-trigger bookkeeping so the same peer does not sync in a loop. */
    private val lastNotifyHandled = ConcurrentHashMap<String, Long>()

    private var screenVisible = false

    private val scheduler =
        LanScheduler(
            scope = scope,
            store = store,
            onRound = { device -> runRoundGuarded(device) },
            onReProbeOffline = { reProbeOfflinePeers() },
            onRediscover = { refreshSelfAddress() },
        )

    private val _serving = MutableStateFlow(false)
    val serving: StateFlow<Boolean> = _serving.asStateFlow()

    private val _selfAddress = MutableStateFlow<String?>(null)
    val selfAddress: StateFlow<String?> = _selfAddress.asStateFlow()

    private val _selfPort = MutableStateFlow(LanProtocol.HTTP_PORT)
    val selfPort: StateFlow<Int> = _selfPort.asStateFlow()

    val devices: StateFlow<List<LanDevice>> = store.devices
    val log: StateFlow<List<LanLogEntry>> = store.log
    val rounds: StateFlow<List<LanRoundDetail>> = store.rounds
    val progress: StateFlow<LanProgress?> = LanEngine.progress

    var enabled: Boolean
        get() = store.enabled
        set(value) {
            val was = store.enabled
            store.enabled = value
            if (!value) hide()
            // "开关 false→true" is a don't-wait-for-the-next-round trigger (SPEC-v2 §8).
            if (value && !was) scheduler.runSoon("enabled")
        }

    /** The "保持在线" switch; starts/stops the foreground service, which in turn holds the runtime. */
    var keepOnline: Boolean
        get() = store.keepOnline
        set(value) {
            store.keepOnline = value
            appContext?.let { context ->
                if (value) LanKeepAliveService.start(context) else LanKeepAliveService.stop(context)
            }
            if (!value && !screenVisible) hide()
        }

    var allowPlaintextV1: Boolean
        get() = store.allowPlaintextV1
        set(value) {
            store.allowPlaintextV1 = value
        }

    var scheduleSeconds: Int
        get() = store.scheduleSeconds
        set(value) {
            store.scheduleSeconds = value
        }

    val selfName: String
        get() = store.deviceName

    fun setSelfName(name: String) {
        store.deviceName = name
    }

    /** Starts serving and discovering. Cheap to call repeatedly; the screen calls it on resume. */
    fun show(context: Context) {
        if (!store.enabled) return
        screenVisible = true
        startRuntime(context)
    }

    fun hide() {
        screenVisible = false
        if (store.keepOnline) return // the foreground service owns the runtime while keep-online is on
        stopRuntime()
    }

    /** Called by [LanKeepAliveService] once it is in the foreground. */
    fun onKeepAliveStarted() {
        appContext?.let { startRuntime(it) }
    }

    fun onKeepAliveStopped() {
        if (!screenVisible) stopRuntime()
    }

    private fun startRuntime(context: Context) {
        appContext = context.applicationContext
        if (_serving.value) return
        val started = startServer() ?: return
        server = started
        started.onRoundNotify = { peerId, peerName -> onPeerNotify(peerId, peerName) }
        _selfPort.value = started.listeningPort
        discovery =
            LanDiscovery(context, scope) { ip, port, source, info ->
                onEndpoint(ip, port, source, info)
            }.also { it.start(started.listeningPort) }
        scheduler.start()
        _serving.value = true
        addressJob =
            scope.launch {
                while (true) {
                    val previous = _selfAddress.value
                    _selfAddress.value = LanAddresses.preferredAddress()
                    // is_online-style edge: our own address appearing is worth a re-announce tick.
                    if (previous == null && _selfAddress.value != null) scheduler.runSoon("address acquired")
                    delay(3_000)
                }
            }
    }

    private fun stopRuntime() {
        scheduler.stop()
        discovery?.stop()
        discovery = null
        addressJob?.cancel()
        addressJob = null
        runCatching { server?.stop() }
        server = null
        _serving.value = false
    }

    /** Binds the first free port from [LanProtocol.HTTP_PORT]; a neighbour already using it is common. */
    private fun startServer(): LanSyncServer? {
        for (offset in 0..LanProtocol.HTTP_PORT_FALLBACKS) {
            val candidate = LanSyncServer(LanProtocol.HTTP_PORT + offset)
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Timber.i("LAN sync serving on port %d", candidate.listeningPort)
                return candidate
            } catch (error: IOException) {
                runCatching { candidate.stop() }
                Timber.d(error, "Port %d unavailable", LanProtocol.HTTP_PORT + offset)
            }
        }
        Timber.w("No free LAN sync port in %d..%d", LanProtocol.HTTP_PORT, LanProtocol.HTTP_PORT + LanProtocol.HTTP_PORT_FALLBACKS)
        return null
    }

    /** Runs one round, swallowing (but logging) failures so callers stay fire-and-forget safe. */
    private suspend fun runRoundGuarded(device: LanDevice): LanRoundResult? =
        runCatching { LanEngine.round(device) }
            .onFailure { Timber.d(it, "round with %s refused", device.name) }
            .getOrNull()

    suspend fun sync(device: LanDevice): LanRoundResult = LanEngine.round(device)

    /** Syncs every peer currently answering *and usable*, one at a time (SPEC-v2 §6.3). */
    suspend fun syncAll(): List<LanRoundResult> =
        devices.value
            .filter { store.isOnline(it.id) && LanEngine.selectMode(it) != null }
            .mapNotNull { runRoundGuarded(it) }

    // region pairing

    /**
     * Initiator half of pairing: `POST /pair/commit` with the code the user read off the *peer's*
     * screen.
     *
     * There is deliberately no `/pair/begin` call here. A code is minted by whichever device
     * *displays* it (SPEC-v2 §5 keeps that route loopback-only), and both the desktop and this
     * phone's own screen do that locally - so asking a peer to mint one over the LAN would be
     * refused by the desktop and would put a code on the user's screen that they never asked for.
     */
    suspend fun pairWith(
        device: LanDevice,
        pairCode: String,
    ): LanDevice {
        val paired = LanEngine.pairCommit(device, pairCode.trim())
        scheduler.runSoon("paired with ${paired.name}")
        return paired
    }

    /** Responder half: the code our own UI shows for the next `/pair/commit`. */
    fun openLocalPairing(): LanPairSession = store.pairing.beginLocal()

    fun activePairingSession(): LanPairSession? = store.pairing.activeSession()

    // endregion

    /**
     * Adds a peer by address: `192.168.1.20`, `192.168.1.20:5600` or `host.local:5600`.
     *
     * @throws LanPeerRejectException when nothing answering at that address is a sync peer.
     */
    suspend fun addManual(spec: String): LanDevice {
        val trimmed = spec.trim().removePrefix("http://").removeSuffix("/")
        val host = trimmed.substringBefore(':', trimmed)
        val port = trimmed.substringAfter(':', "").toIntOrNull() ?: LanProtocol.HTTP_PORT
        val ip = resolveHostToIpv4(host) ?: throw LanPeerRejectException("Cannot resolve $host")
        val info = LanEngine.probe(LanDevice(id = "", name = host, ip = ip, port = port, source = LanSource.MANUAL, addedAt = 0L))
        if (store.isSelf(info.id)) throw LanPeerRejectException("This is this device")
        verifiedIds.add(info.id)
        return store.remember(info.copy(name = info.name.ifBlank { host }), ip, LanSource.MANUAL).first
    }

    fun remove(id: String) {
        verifiedIds.remove(id)
        store.forget(id)
    }

    fun isOnline(id: String): Boolean = store.isOnline(id)

    fun clearLog() = store.clearLog()

    /** The user reviewed/edited (or a round finished): schedule a debounced round if enabled. */
    fun noteLocalWrite() {
        scheduler.noteLocalWrite()
    }

    private fun onPeerNotify(
        peerId: String,
        peerName: String,
    ) {
        val now = lanNow()
        if (now - (lastNotifyHandled[peerId] ?: 0L) < LanScheduler.WRITE_MIN_INTERVAL_MS) return
        lastNotifyHandled[peerId] = now
        val device = store.devices.value.firstOrNull { it.id == peerId } ?: return
        Timber.i("LAN sync: %s notifies changes, pulling", peerName.ifBlank { device.name })
        scope.launch { runRoundGuarded(device) }
    }

    /** Self-heal (SPEC-v2 §8): 30 s re-probe of peers we believe offline. */
    private suspend fun reProbeOfflinePeers() {
        store.devices.value
            .filterNot { store.isOnline(it.id) }
            .forEach { device ->
                val info =
                    runCatching { LanEngine.probe(device) }
                        .getOrNull()
                if (info == null) {
                    scheduler.noteProbeFailed(device.id)
                } else {
                    val wasOffline = store.touch(device.id)
                    store.remember(info, device.ip, device.source)
                    if (wasOffline) scheduler.runSoon("peer online")
                }
            }
    }

    private fun refreshSelfAddress() {
        scope.launch { _selfAddress.value = LanAddresses.preferredAddress() }
    }

    private suspend fun onEndpoint(
        ip: String,
        port: Int,
        source: LanSource,
        info: LanPeerInfo?,
    ) {
        if (info != null) {
            // The announce already identifies the peer; only verify over HTTP once per device.
            if (store.isSelf(info.id)) return
            val wasOffline = !store.isOnline(info.id)
            val unverified = info.id !in verifiedIds
            store.remember(info, ip, source)
            if (wasOffline) scheduler.runSoon("peer online") // is_online 0→1 trigger (SPEC-v2 §8)
            if (unverified) verify(ip, port, source)
            return
        }
        val key = "$ip:$port"
        val now = lanNow()
        if (now - (probedAt[key] ?: 0L) < PROBE_THROTTLE_MS) return
        probedAt[key] = now
        verify(ip, port, source)
    }

    private suspend fun verify(
        ip: String,
        port: Int,
        source: LanSource,
    ) {
        val guess = LanDevice(id = "", name = ip, ip = ip, port = port, source = source, addedAt = 0L)
        val info =
            runCatching { LanEngine.probe(guess) }
                .getOrElse { error ->
                    Timber.d(error, "Peer probe at %s failed", ip)
                    return
                }
        if (store.isSelf(info.id)) return
        val wasOffline = !store.isOnline(info.id)
        val isNew = store.devices.value.none { it.id == info.id }
        val device = store.remember(info, ip, source).first
        verifiedIds.add(info.id)
        if (wasOffline) scheduler.runSoon("peer online")
        if (isNew) {
            store.log(
                LanLogEntry(
                    at = lanNow(),
                    deviceId = device.id,
                    deviceName = device.name,
                    direction = LanDirection.PROBE,
                    result = LanResult.OK,
                    detail = "$ip:$port protocol=${info.protocol}",
                ),
            )
        }
    }
}
