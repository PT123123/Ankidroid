// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Automatic rounds and self-heal for LAN sync (SPEC-v2 §8).
 *
 * - Period: one of 10/300/1800 seconds stored in [LanStore.scheduleSeconds], re-read on every tick
 *   so changing the setting takes effect within 5 s without restarting the loop.
 * - "Don't wait for the next round" triggers: the enable switch going false→true, a peer going
 *   offline→online, and a debounced local-write signal (5 s debounce, 15 s minimum spacing).
 * - Self-heal: offline devices are re-probed every 30 s; a failed probe opens a 6 s rediscovery
 *   window to pick up a renewed DHCP address, rate-limited to one window per 30 s.
 *
 * The scheduler never touches sockets or the collection itself - it just decides *when* to ask
 * [onRound] (a full round with one peer), [onReProbeOffline] and [onRediscover] to do their thing.
 */
class LanScheduler(
    private val scope: CoroutineScope,
    private val store: LanStore,
    private val onRound: suspend (LanDevice) -> Unit,
    private val onReProbeOffline: suspend () -> Unit,
    private val onRediscover: () -> Unit,
) {
    private var periodJob: Job? = null
    private var healJob: Job? = null
    private var writeJob: Job? = null

    @Volatile private var lastRoundAt = 0L

    @Volatile private var lastWriteTriggerAt = 0L

    @Volatile private var lastRedetectAt = 0L
    private var running = false

    val isRunning: Boolean get() = running

    /** Called from `show()`; idempotent while running. */
    fun start() {
        if (running) return
        running = true
        periodJob = scope.launch { periodLoop() }
        healJob = scope.launch { healLoop() }
        Timber.d("LAN scheduler started")
    }

    fun stop() {
        running = false
        periodJob?.cancel()
        healJob?.cancel()
        writeJob?.cancel()
        periodJob = null
        healJob = null
        writeJob = null
    }

    private suspend fun periodLoop() {
        var lastPeriodicAt = 0L
        while (scope.isActive && running) {
            val seconds = store.scheduleSeconds
            if (seconds > 0) {
                val millis = seconds * 1000L
                val now = lanNow()
                if (now - lastRoundAt >= millis && now - lastPeriodicAt >= millis) {
                    lastPeriodicAt = now
                    runScheduledRounds()
                }
            }
            delay(READ_CONFIG_MS)
        }
    }

    private suspend fun healLoop() {
        while (scope.isActive && running) {
            delay(HEAL_INTERVAL_MS)
            if (!store.enabled) continue
            runCatching { onReProbeOffline() }
                .onFailure { Timber.d(it, "offline re-probe failed") }
        }
    }

    /** One round with every online, usable peer - sequential, the collection lock allows no better. */
    private suspend fun runScheduledRounds() {
        lastRoundAt = lanNow()
        store.devices.value
            .filter { store.isOnline(it.id) }
            .forEach { device ->
                runCatching { onRound(device) }
                    .onFailure { Timber.d(it, "scheduled round with %s failed", device.name) }
            }
    }

    /**
     * A user-visible state change that should not wait for the next period: switch off→on, peer
     * online again, or a finished round elsewhere. Bursts within [RUN_SOON_DEBOUNCE_MS] collapse.
     */
    fun runSoon(reason: String) {
        if (!running) return
        scope.launch {
            delay(RUN_SOON_DEBOUNCE_MS)
            Timber.d("LAN scheduler: round soon (%s)", reason)
            runScheduledRounds()
        }
    }

    /** The local collection changed (review/edit): 5 s debounce, at most once per 15 s. */
    fun noteLocalWrite() {
        if (!running) return
        val now = lanNow()
        if (now - lastWriteTriggerAt < WRITE_MIN_INTERVAL_MS) return
        writeJob?.cancel()
        writeJob =
            scope.launch {
                delay(WRITE_DEBOUNCE_MS)
                lastWriteTriggerAt = lanNow()
                runScheduledRounds()
            }
    }

    /**
     * A probe of an offline peer failed: their address probably changed (DHCP). Opens a short
     * rediscovery window; rate-limited so a peer that is simply powered off does not keep the
     * whole discovery machinery flapping.
     */
    fun noteProbeFailed(peerId: String) {
        val now = lanNow()
        if (now - lastRedetectAt < REDISCOVER_MIN_INTERVAL_MS) return
        lastRedetectAt = now
        Timber.d("LAN scheduler: opening %ds rediscovery window for %s", REDISCOVER_WINDOW_MS / 1000, peerId)
        onRediscover()
        scope.launch {
            delay(REDISCOVER_WINDOW_MS)
            runSoon("rediscovery window closed")
        }
    }

    companion object {
        /** How often the period loop re-reads the configured interval (SPEC: change ≤5s). */
        const val READ_CONFIG_MS = 5_000L

        /** Offline re-probe cadence (SPEC-v2 §8: 30 s). */
        const val HEAL_INTERVAL_MS = 30_000L

        /** Self-heal rate limit (SPEC-v2 §8: 30 s). */
        const val REDISCOVER_MIN_INTERVAL_MS = 30_000L

        /** "6s 重发现窗口" (SPEC-v2 §8). */
        const val REDISCOVER_WINDOW_MS = 6_000L

        const val WRITE_DEBOUNCE_MS = 5_000L
        const val WRITE_MIN_INTERVAL_MS = 15_000L
        const val RUN_SOON_DEBOUNCE_MS = 2_000L
    }
}
