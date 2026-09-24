// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.R
import com.ichi2.anki.common.android.appContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/** A peer plus the liveness flag the UI needs; refreshed from the tick in [LanSyncViewModel]. */
data class LanPeerRow(
    val device: LanDevice,
    val online: Boolean,
    /** Which data plane the last round used, for the mode badge; null until one runs. */
    val lastMode: LanDataMode? = null,
    /** Whether a round may run at all right now (paired v2, or v1 with the plaintext opt-in). */
    val usable: Boolean = false,
)

/**
 * State holder of the LAN sync screen. Everything network-shaped is delegated to
 * [LanSyncManager]; this only shapes it for Compose and reports the outcome of a round.
 */
class LanSyncViewModel : ViewModel() {
    private val manager = LanSyncManager

    private val tick = MutableStateFlow(0L)
    private val manual = MutableStateFlow(manager.enabled)

    val enabled: StateFlow<Boolean> = manual
    val serving: StateFlow<Boolean> = manager.serving
    val selfAddress: StateFlow<String?> = manager.selfAddress
    val selfPort: StateFlow<Int> = manager.selfPort
    val progress: StateFlow<LanProgress?> = manager.progress

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _selfName = MutableStateFlow(manager.selfName)
    val selfName: StateFlow<String> = _selfName.asStateFlow()

    private val _allowPlaintextV1 = MutableStateFlow(manager.allowPlaintextV1)
    val allowPlaintextV1: StateFlow<Boolean> = _allowPlaintextV1.asStateFlow()

    private val _keepOnline = MutableStateFlow(manager.keepOnline)
    val keepOnline: StateFlow<Boolean> = _keepOnline.asStateFlow()

    private val _scheduleSeconds = MutableStateFlow(manager.scheduleSeconds)
    val scheduleSeconds: StateFlow<Int> = _scheduleSeconds.asStateFlow()

    /** Responder-side pairing session to display (code + security code), refreshed on the tick. */
    private val _pairingSession = MutableStateFlow<LanPairSession?>(null)
    val pairingSession: StateFlow<LanPairSession?> = _pairingSession.asStateFlow()

    val rounds: StateFlow<List<LanRoundDetail>> =
        manager.rounds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The hub endpoint currently wired into the custom-sync-server settings, if any. */
    private val _hubEndpoint = MutableStateFlow(LanHubClient.currentHubEndpoint())
    val hubEndpoint: StateFlow<String?> = _hubEndpoint.asStateFlow()

    private fun refreshHubEndpoint() {
        _hubEndpoint.value = LanHubClient.currentHubEndpoint()
    }

    fun refreshSelfName() {
        _selfName.value = manager.selfName
    }

    /** Online flags go stale on their own, so the row list is rebuilt on a slow tick. */
    val peers: StateFlow<List<LanPeerRow>> =
        combine(manager.devices, tick) { devices, _ ->
            val latestModes = manager.rounds.value.associateBy({ it.deviceId }, { it.mode })
            devices
                .map {
                    LanPeerRow(
                        device = it,
                        online = manager.isOnline(it.id),
                        lastMode = latestModes[it.id] ?: LanStore.instance.lastRoundMode(it.id),
                        usable = LanEngine.selectMode(it) != null,
                    )
                }.sortedWith(
                    compareByDescending<LanPeerRow> { if (it.online) 1 else 0 }.thenBy { it.device.name.lowercase() },
                )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val log: StateFlow<List<LanLogEntry>> =
        manager.log.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val pendingMessages = Channel<Int>(Channel.BUFFERED)
    val messages = pendingMessages.receiveAsFlow()

    init {
        viewModelScope.launch {
            while (true) {
                tick.value = lanNow()
                _pairingSession.value = manager.activePairingSession()
                delay(TICK_MS)
            }
        }
    }

    /** The screen is visible: take over the server and discovery if the user enabled them. */
    fun startIfEnabled() {
        if (manager.enabled) manager.show(appContext)
    }

    fun stopRuntime() = manager.hide()

    fun setEnabled(value: Boolean) {
        manager.enabled = value
        manual.value = value
        if (value) manager.show(appContext)
    }

    fun setAllowPlaintextV1(value: Boolean) {
        manager.allowPlaintextV1 = value
        _allowPlaintextV1.value = value
        viewModelScope.launch { send(R.string.lansync_msg_setting_saved) }
    }

    fun setKeepOnline(value: Boolean) {
        manager.keepOnline = value
        _keepOnline.value = value
        if (value) manager.show(appContext)
        viewModelScope.launch { send(R.string.lansync_msg_setting_saved) }
    }

    fun setScheduleSeconds(value: Int) {
        manager.scheduleSeconds = value
        _scheduleSeconds.value = value
    }

    /** Responder half: generate/show our pairing code so the peer can type it. */
    fun beginPairingHere() {
        _pairingSession.value = manager.openLocalPairing()
    }

    fun dismissPairingHere() {
        _pairingSession.value = null
    }

    /** Initiator half: pair with [device] using the code read from its screen. */
    fun pairWithCode(
        device: LanDevice,
        code: String,
    ) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching { manager.pairWith(device, code) }
            _busy.value = false
            result
                .onSuccess {
                    refreshHubEndpoint()
                    send(R.string.lansync_msg_paired)
                }.onFailure { error ->
                    Timber.i(error, "Pairing with %s failed", device.name)
                    send(R.string.lansync_msg_pair_failed)
                }
        }
    }

    fun renameSelf(name: String) {
        if (name.isBlank()) return
        manager.setSelfName(name)
        refreshSelfName()
        viewModelScope.launch { send(R.string.lansync_msg_renamed) }
    }

    fun sync(device: LanDevice) = runRound(device.name) { listOf(manager.sync(device)) }

    fun syncAll() =
        runRound(
            manager.devices.value
                .firstOrNull { manager.isOnline(it.id) }
                ?.name
                .orEmpty(),
        ) {
            manager.syncAll()
        }

    fun addPeer(address: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching { manager.addManual(address) }
            _busy.value = false
            result.onSuccess { send(R.string.lansync_msg_added) }.onFailure { error ->
                Timber.i(error, "Manual add of %s failed", address)
                send(R.string.lansync_msg_add_failed)
            }
        }
    }

    fun forget(id: String) {
        manager.remove(id)
        refreshHubEndpoint()
        viewModelScope.launch { send(R.string.lansync_msg_removed) }
    }

    fun clearLog() = manager.clearLog()

    private fun runRound(
        peerName: String,
        block: suspend () -> List<LanRoundResult>,
    ) {
        if (!_busy.compareAndSet(false, true)) {
            Timber.i("Ignoring sync request while another round with %s runs", peerName)
            viewModelScope.launch { send(R.string.lansync_msg_busy) }
            return
        }
        viewModelScope.launch {
            val message =
                runCatching { block() }
                    .getOrNull()
                    ?.let { results ->
                        when {
                            results.all { it.ok } -> R.string.lansync_msg_sync_ok
                            results.any { it.pushed?.result == LanResult.OK || it.pulled?.result == LanResult.OK } ->
                                R.string.lansync_msg_sync_partial

                            else -> R.string.lansync_msg_sync_failed
                        }
                    } ?: R.string.lansync_msg_sync_failed
            _busy.value = false
            refreshHubEndpoint()
            manager.noteLocalWrite()
            send(message)
        }
    }

    private suspend fun send(
        @StringRes message: Int,
    ) {
        pendingMessages.send(message)
    }

    companion object {
        private const val TICK_MS = 2_000L
    }
}
