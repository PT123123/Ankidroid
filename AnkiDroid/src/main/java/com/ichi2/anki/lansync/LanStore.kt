// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.android.appContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-level persistence for LAN sync v2: identity, the trust list, pairing state, key material,
 * the activity log and per-round details.
 *
 * Two preference files, deliberately split by sensitivity (SPEC-v2 §7):
 * - `lan_sync` - everything the `/devices` route may talk about (public fields only) plus the log.
 * - `lan_sync_secrets` - every `device_secret` ever held, in both directions. This file is *never*
 *   serialized into any HTTP payload; pairing is the only way key material moves, wrapped under a
 *   pairing code (SPEC-v2 §4.1).
 *
 * Still app-global, not profile-namespaced: the identity describes the hardware, and each profile
 * announcing itself as a separate device was a v1 bug kept fixed here (see docs/lansync README
 * "Where the state lives").
 */
class LanStore internal constructor(
    context: Context,
) {
    /**
     * Deliberately the *unwrapped* application context: [ProfileContextWrapper] namespaces every
     * preference file per profile, while the device identity and the peer list describe the hardware.
     */
    private val globalContext: Context =
        (context.applicationContext as? ContextWrapper)?.baseContext ?: context.applicationContext

    private fun globalPrefs(name: String): SharedPreferences = globalContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val prefs: SharedPreferences = globalPrefs(PREFS_FILE)

    private val secretPrefs: SharedPreferences = globalPrefs(SECRETS_FILE)

    private val _devices = MutableStateFlow(readDevices())
    val devices: StateFlow<List<LanDevice>> = _devices.asStateFlow()

    private val _log = MutableStateFlow(readLog())
    val log: StateFlow<List<LanLogEntry>> = _log.asStateFlow()

    private val _rounds = MutableStateFlow(readRounds())
    val rounds: StateFlow<List<LanRoundDetail>> = _rounds.asStateFlow()

    /** When each peer was last heard from. In-memory only: it changes every announce. */
    private val seenAt = ConcurrentHashMap<String, Long>()

    /** Stable identity of this device, generated on first use and kept forever. */
    val deviceId: String
        get() =
            prefs.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString(KEY_DEVICE_ID, id) } }

    /** User-visible name of this device; editable, defaults to the hardware model. */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        set(value) {
            prefs.edit { putString(KEY_DEVICE_NAME, value.trim()) }
        }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            if (value != enabled) {
                prefs.edit { putBoolean(KEY_ENABLED, value) }
                Timber.i("LAN sync %s", if (value) "enabled" else "disabled")
            }
        }

    /** "保持在线": keep serving via a foreground service after the screen goes away (SPEC-v2 §8). */
    var keepOnline: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ONLINE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_KEEP_ONLINE, value) }
        }

    /**
     * Explicit user opt-in for the v1 plaintext downgrade. Off by default: unpaired/plain text
     * data-plane requests are refused (SPEC-v2 §4.2 "v1 降级"), and rounds that do run in this
     * mode are tagged [LanProtocol.PLAINTEXT_MARKER] in the log.
     */
    var allowPlaintextV1: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_V1, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ALLOW_V1, value) }
        }

    /** Auto-sync period in seconds: one of 10/300/1800 (SPEC-v2 §8). */
    var scheduleSeconds: Int
        get() = prefs.getInt(KEY_SCHEDULE_SECONDS, SCHEDULE_OFF)
        set(value) {
            prefs.edit { putInt(KEY_SCHEDULE_SECONDS, value) }
        }

    /** Version name advertised to peers, cached in prefs because the lookup is not free. */
    val appVersion: String
        get() =
            prefs.getString(KEY_APP_VERSION, null)
                ?: installedVersion.also { prefs.edit { putString(KEY_APP_VERSION, it) } }

    /** Read during construction so the device-level singleton retains no [Context]. */
    private val installedVersion: String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

    /** The pairing-code state machine over the `pair_codes` key of [prefs]. */
    val pairing: LanPairing =
        LanPairing(
            loadSessions = {
                runCatching {
                    prefs
                        .getString(KEY_PAIR_SESSIONS, null)
                        ?.let { LanProtocol.json.decodeFromString(PairSessionList.serializer(), it).sessions }
                }.getOrNull() ?: emptyList()
            },
            saveSessions = { list ->
                prefs.edit {
                    putString(KEY_PAIR_SESSIONS, LanProtocol.json.encodeToString(PairSessionList.serializer(), PairSessionList(list)))
                }
            },
        )

    /** Human-readable name of the profile currently open; used for import routing (SPEC-v2 §7). */
    val activeProfileName: String
        get() =
            runCatching {
                val manager = AnkiDroidApp.profileManager
                val id = manager.activeProfileId
                manager.getAllProfiles()[id]?.displayName?.value ?: id.value
            }.getOrNull() ?: "profile"

    fun selfInfo(port: Int): LanPeerInfo =
        LanPeerInfo(
            id = deviceId,
            name = deviceName,
            port = port,
            platform = "android",
            sentAt = lanNow(),
            modes = listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB),
            roles = listOf(LanProtocol.ROLE_P2P),
            kids = pairedKids(),
            profile = activeProfileName,
        )

    /** The legacy advertisement of the same device, so un-upgraded v1 phones can still find us. */
    fun selfInfoV1(port: Int): LanPeerInfoV1 = selfInfo(port).asV1(appVersion)

    fun isSelf(id: String): Boolean = id == deviceId

    /**
     * Registers a peer seen at [ip]:[port], or refreshes the entry if it is already trusted.
     *
     * @return the stored device, and whether anything actually changed (used to avoid rewriting
     * the persisted list on every announce).
     */
    fun remember(
        info: LanPeerInfo,
        ip: String,
        source: LanSource,
    ): Pair<LanDevice, Boolean> {
        val now = lanNow()
        seenAt[info.id] = now
        val existing = _devices.value.firstOrNull { it.id == info.id }
        val paired = existing?.paired == true
        val device =
            existing?.copy(
                name = info.name.ifBlank { existing.name },
                ip = ip,
                port = info.port,
                source = if (existing.source == LanSource.MANUAL) LanSource.MANUAL else source,
                protocol = info.protocol,
                modes = info.modes.ifEmpty { existing.modes },
                roles = info.roles.ifEmpty { existing.roles },
                kind = info.platform,
                profile = info.profile.ifBlank { existing.profile },
            ) ?: LanDevice(
                id = info.id,
                name = info.name.ifBlank { ip },
                ip = ip,
                port = info.port,
                source = source,
                addedAt = now,
                protocol = info.protocol,
                modes = info.modes,
                roles = info.roles,
                kind = info.platform,
                profile = info.profile,
                paired = paired,
            )
        val changed = existing != device
        if (changed) {
            _devices.value = _devices.value.filterNot { it.id == device.id } + device
            writeDevices()
        }
        return device to changed
    }

    fun forget(id: String) {
        seenAt.remove(id)
        _devices.value = _devices.value.filterNot { it.id == id }
        writeDevices()
        val secrets = readSecrets()
        val dropped = secrets.shared.filter { it.peerId == id }
        writeSecrets(secrets.copy(shared = secrets.shared.filterNot { it.peerId == id }))
        lastRoundModes.remove(id)
        Timber.i("LAN sync forgot %s and dropped %d shared key(s)", id, dropped.size)
    }

    fun markSynced(id: String) {
        val now = lanNow()
        _devices.value = _devices.value.map { if (it.id == id) it.copy(lastSyncAt = now) else it }
        writeDevices()
    }

    fun isOnline(id: String): Boolean = (lanNow() - (seenAt[id] ?: 0L)) < LanProtocol.ONLINE_WINDOW_MS

    /** Records "heard from this peer now"; true when that flipped it offline→online. */
    fun touch(id: String): Boolean {
        val wasOffline = !isOnline(id)
        seenAt[id] = lanNow()
        return wasOffline
    }

    fun log(entry: LanLogEntry) {
        _log.value = (listOf(entry) + _log.value).take(MAX_LOG_ENTRIES)
        prefs.edit { putString(KEY_LOG, LanProtocol.json.encodeToString(LogList.serializer(), LogList(_log.value))) }
    }

    fun clearLog() {
        _log.value = emptyList()
        prefs.edit { remove(KEY_LOG) }
    }

    /** One row per completed round, newest first, capped like the log. */
    fun recordRound(detail: LanRoundDetail) {
        _rounds.value = (listOf(detail) + _rounds.value).take(MAX_ROUND_DETAILS)
        prefs.edit { putString(KEY_ROUNDS, LanProtocol.json.encodeToString(RoundList.serializer(), RoundList(_rounds.value))) }
    }

    // region key material (lan_sync_secrets; never exported over /devices)

    private val lastRoundModes = ConcurrentHashMap<String, LanDataMode>()

    /** Which data plane the most recent round with a peer used, for the UI mode badge. */
    fun lastRoundMode(peerId: String): LanDataMode? = lastRoundModes[peerId]

    fun noteRoundMode(
        peerId: String,
        mode: LanDataMode,
    ) {
        lastRoundModes[peerId] = mode
    }

    /**
     * Stores the one symmetric key a finished pairing produced (SPEC-v2 §4.1). Both directions use
     * it: the kid is *derived* from the key, so both devices compute the same kid independently and
     * each recognizes the other's requests without ever exchanging a lookup table.
     */
    fun recordPairing(
        peer: LanPeerInfo,
        sharedSecret: ByteArray,
    ) {
        val kid = LanCrypto.kidOf(sharedSecret)
        val securityCode = LanCrypto.securityCodeOf(sharedSecret)
        val now = lanNow()
        val secrets = readSecrets()
        writeSecrets(
            secrets.copy(
                shared =
                    secrets.shared.filterNot { it.peerId == peer.id } +
                        SharedSecret(peer.id, kid, LanCrypto.base64(sharedSecret), now),
            ),
        )
        _devices.value =
            _devices.value.map {
                if (it.id == peer.id) it.copy(kid = kid, paired = true, securityCode = securityCode) else it
            }
        writeDevices()
        Timber.i("LAN sync paired with %s (%s, kid %s)", peer.name, peer.id, kid)
    }

    /** The 4-hex code the user compares on both screens; empty until paired. */
    fun securityCodeFor(peerId: String): String = _devices.value.firstOrNull { it.id == peerId }?.securityCode ?: ""

    /** Kids another device may address us with, published in `/info` (SPEC-v2 §4.2). */
    fun pairedKids(): List<String> = readSecrets().shared.map { it.kid }

    /** Which paired device an inbound envelope's kid belongs to; null when unknown. */
    fun peerIdForKid(kid: String): String? = readSecrets().shared.firstOrNull { it.kid == kid }?.peerId

    /** Resolves the secret an inbound envelope's kid points at; null means "not paired". */
    fun secretForKid(kid: String): ByteArray? = readSecrets().shared.firstOrNull { it.kid == kid }?.let { LanCrypto.unBase64(it.secretB64) }

    /** The (kid, secret) pair we address [peerId] with; null means "not paired". */
    fun outboundFor(peerId: String): Pair<String, ByteArray>? =
        readSecrets().shared.firstOrNull { it.peerId == peerId }?.let {
            it.kid to (LanCrypto.unBase64(it.secretB64) ?: return null)
        }

    fun isPaired(peerId: String): Boolean = readSecrets().shared.any { it.peerId == peerId }

    private fun readSecrets(): SecretsFile =
        runCatching {
            secretPrefs.getString(KEY_SECRETS, null)?.let { LanProtocol.json.decodeFromString(SecretsFile.serializer(), it) }
        }.getOrNull() ?: SecretsFile()

    private fun writeSecrets(file: SecretsFile) {
        secretPrefs.edit { putString(KEY_SECRETS, LanProtocol.json.encodeToString(SecretsFile.serializer(), file)) }
    }

    // endregion

    /**
     * One-shot v1 trust-table migration (SPEC-v2 §9): entries persisted by protocol=1 builds lack
     * `paired`/`kid`, which JSON defaults already resolve to "unpaired", but they must also stop
     * being auto-trusted for data planes. Marking the migration lets later code distinguish
     * "legacy, plaintext if allowed" from "seen but never verified".
     */
    private fun migrateV1IfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val v1Peers = _devices.value.filter { it.protocol < LanProtocol.VERSION }
        if (v1Peers.isNotEmpty()) {
            _devices.value = _devices.value.map { it.copy(paired = false) }
            writeDevices()
            v1Peers.forEach {
                log(
                    LanLogEntry(
                        at = lanNow(),
                        deviceId = it.id,
                        deviceName = it.name,
                        direction = LanDirection.PROBE,
                        result = LanResult.OK,
                        detail = "migrated from v1; unpaired until paired again",
                    ),
                )
            }
        }
        prefs.edit { putBoolean(KEY_MIGRATED, true) }
    }

    private fun writeDevices() {
        prefs.edit {
            putString(
                KEY_DEVICES,
                LanProtocol.json.encodeToString(DeviceList.serializer(), DeviceList(_devices.value)),
            )
        }
    }

    private fun readDevices(): List<LanDevice> =
        runCatching {
            prefs
                .getString(KEY_DEVICES, null)
                ?.let { LanProtocol.json.decodeFromString(DeviceList.serializer(), it).devices }
        }.getOrNull() ?: emptyList()

    private fun readLog(): List<LanLogEntry> =
        runCatching {
            prefs
                .getString(KEY_LOG, null)
                ?.let { LanProtocol.json.decodeFromString(LogList.serializer(), it).entries }
        }.getOrNull() ?: emptyList()

    private fun readRounds(): List<LanRoundDetail> =
        runCatching {
            prefs
                .getString(KEY_ROUNDS, null)
                ?.let { LanProtocol.json.decodeFromString(RoundList.serializer(), it).entries }
        }.getOrNull() ?: emptyList()

    @Serializable
    private class DeviceList(
        val devices: List<LanDevice> = emptyList(),
    )

    @Serializable
    private class LogList(
        val entries: List<LanLogEntry> = emptyList(),
    )

    @Serializable
    private class RoundList(
        val entries: List<LanRoundDetail> = emptyList(),
    )

    @Serializable
    private class PairSessionList(
        val sessions: List<LanPairSession> = emptyList(),
    )

    /** The symmetric key one pairing produced, plus the kid derived from it (SPEC-v2 §4.1). */
    @Serializable
    private data class SharedSecret(
        val peerId: String,
        val kid: String,
        val secretB64: String,
        val createdAt: Long,
    )

    @Serializable
    private data class SecretsFile(
        val shared: List<SharedSecret> = emptyList(),
    )

    companion object {
        private const val PREFS_FILE = "lan_sync"
        private const val SECRETS_FILE = "lan_sync_secrets"
        private const val KEY_DEVICE_ID = "lanDeviceId"
        private const val KEY_DEVICE_NAME = "lanDeviceName"
        private const val KEY_APP_VERSION = "lanAppVersion"
        private const val KEY_ENABLED = "lanSyncEnabled"
        private const val KEY_DEVICES = "lanPeers"
        private const val KEY_LOG = "lanLog"
        private const val KEY_ROUNDS = "lanRounds"
        private const val KEY_PAIR_SESSIONS = "pair_codes"
        private const val KEY_SECRETS = "device_secrets"
        private const val KEY_ALLOW_V1 = "lanAllowPlaintextV1"
        private const val KEY_KEEP_ONLINE = "lanKeepOnline"
        private const val KEY_SCHEDULE_SECONDS = "lanScheduleSeconds"
        private const val KEY_MIGRATED = "lanMigratedToV2"
        private const val MAX_LOG_ENTRIES = 200
        private const val MAX_ROUND_DETAILS = 200

        /** [scheduleSeconds] value meaning "no auto-sync". */
        const val SCHEDULE_OFF = 0

        /** The three allowed periods (SPEC-v2 §8). */
        val SCHEDULE_PERIODS = listOf(10, 300, 1800)

        val instance: LanStore by lazy { LanStore(appContext) }

        private fun defaultDeviceName(): String {
            val model = Build.MODEL ?: ""
            val manufacturer = Build.MANUFACTURER ?: ""
            return listOf(manufacturer, model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android" }
        }
    }

    init {
        migrateV1IfNeeded()
    }
}
