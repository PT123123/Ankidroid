// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.InetAddress

/**
 * Wire constants of the LAN sync control plane, modelled on `aw-sync-rust` and specified in
 * `docs/lan-sync/SPEC-v2.md` (the authoritative document; field names, routes and error codes here
 * must match it exactly).
 *
 * v1 (`ANKIPLUS-LAN/1`) stays decodable so an un-upgraded phone can still be found and, when the
 * user explicitly allows plaintext v1, synced with. Everything protocol=2 is new: pairing,
 * per-route AES-256-GCM envelopes, mode negotiation and the hub data plane.
 */
object LanProtocol {
    /** v2 protocol version; a peer reporting >=1 is listed, peers reporting 1 are plaintext-only. */
    const val VERSION = 2

    /** v1 protocol version, kept for the downgrade path only. */
    const val VERSION_V1 = 1

    /** Prefix of the v2 UDP announce packets (SPEC-v2 §3). */
    const val MAGIC = "ANKI-LAN/2"

    /** Prefix of the legacy v1 announce packets; still decoded, never re-encoded. */
    const val MAGIC_V1 = "ANKIPLUS-LAN/1"

    /** Preferred TCP port of the local HTTP sync API. */
    const val HTTP_PORT = 5600

    /** Extra ports tried when [HTTP_PORT] is taken: `HTTP_PORT + 1 … HTTP_PORT + n`. */
    const val HTTP_PORT_FALLBACKS = 10

    /** UDP announce/listen port, shared by every peer. */
    const val UDP_PORT = 46000

    /** mDNS/NSD service type of v2 peers (SPEC-v2 §3.1). */
    const val NSD_SERVICE_TYPE = "_ankisync._tcp"

    /** Legacy v1 service type; still browsed so un-upgraded phones keep showing up. */
    const val NSD_SERVICE_TYPE_V1 = "_ankiplus-sync._tcp"

    /** Service instance name prefix of the legacy protocol; v2 uses `<name>-<id8>`. */
    const val NSD_NAME_PREFIX = "AnkiPlus-"

    const val ANNOUNCE_INTERVAL_MS = 5_000L

    /** A peer not heard from for this long is shown as offline. */
    const val ONLINE_WINDOW_MS = 20_000L

    /** Header marking a request as v2 LAN sync traffic. */
    const val REQUEST_HEADER = "X-Anki-Sync"
    const val REQUEST_HEADER_VALUE = MAGIC

    /** Header marking a request as v1 LAN sync traffic (downgrade path only). */
    const val LEGACY_REQUEST_HEADER = "X-Ankiplus-Lansync"
    const val LEGACY_REQUEST_HEADER_VALUE = MAGIC_V1

    /** Envelope headers (SPEC-v2 §4.2). JSON routes carry the envelope as the body itself. */
    const val KID_HEADER = "X-Anki-Kid"
    const val ENVELOPE_HEADER = "X-Anki-Envelope"

    /** On `POST /apkg/export` the raw package has the body, so the answer echoes the request nonce. */
    const val XFER_HEADER = "X-Anki-Xfer"

    /** Sent URL-encoded by the peer, purely so each side can name the other in its activity log. */
    const val PEER_NAME_HEADER = "X-Anki-Peer"
    const val PEER_ID_HEADER = "X-Anki-Peer-Id"

    /** Legacy v1 identity headers; the v1 data plane still uses these exact names. */
    const val LEGACY_PEER_NAME_HEADER = "X-Ankiplus-Peer"
    const val LEGACY_PEER_ID_HEADER = "X-Ankiplus-Peer-Id"

    /** Inbound packages above this size are refused (1 GiB). */
    const val MAX_IMPORT_BYTES = 1L shl 30

    /** Envelope timestamps further than this from local wall clock are rejected (SPEC-v2 §4.2). */
    const val REPLAY_WINDOW_SECONDS = 300L

    /** Data plane modes and control plane roles (SPEC-v2 §5/§6). */
    const val MODE_APKG = "apkg"
    const val MODE_HUB = "hub"
    const val ROLE_P2P = "p2p"
    const val ROLE_HUB = "hub"

    /** Log marker for any round that ran unauthenticated v1 plaintext (SPEC-v2 §4.2/§6.3). */
    const val PLAINTEXT_MARKER = "SECURITY: plaintext-v1"

    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

/** Which data plane a round actually used; exactly one per round, never mixed (SPEC-v2 §6.3). */
enum class LanDataMode { HUB, APKG, PLAINTEXT_V1 }

/**
 * The unified error codes of SPEC-v2 §5, mapped onto HTTP statuses by the server.
 *
 * The wire strings are the desktop's verbatim, which is why they are lower_snake and not
 * SCREAMING: a client on either platform branches on them (`pair_expired` → "ask for a new code"),
 * so renaming one here is a protocol change and needs the spec updated first.
 * `PROFILE_MISMATCH` is the one code a phone can produce and a desktop cannot: only AnkiDroid has
 * several profiles one package could land in the wrong place.
 */
enum class LanError(
    val wire: String,
    val status: Int,
) {
    BAD_PEER_INFO("bad_peer_info", 400),
    STALE_TS("stale_ts", 401),
    KID_MISMATCH("kid_mismatch", 401),
    REPLAY("replay", 401),
    DECRYPT_FAILED("decrypt_failed", 401),
    BAD_ENVELOPE("bad_envelope", 401),
    BAD_PAYLOAD("bad_payload", 401),
    XFER_MISMATCH("xfer_mismatch", 401),
    PAIR_INVALID("pair_invalid", 401),
    NOT_FOUND("not_found", 404),
    NEED_MAGIC("need_magic", 403),
    NOT_PAIRED("not_paired", 403),
    HUB_OFF("hub_off", 403),
    V1_DISABLED("v1_disabled", 403),
    LOOPBACK_ONLY("loopback_only", 403),
    PROFILE_MISMATCH("profile_mismatch", 403),
    BUSY("BUSY", 409),
    PAIR_CONSUMED("pair_consumed", 409),
    HUB_SEED_REQUIRED("hub_seed_required", 409),
    PAIR_EXPIRED("pair_expired", 410),
    LENGTH_REQUIRED("length_required", 411),
    PAIR_THROTTLED("pair_throttled", 429),
    TOO_LARGE("too_large", 507),
    INTERNAL_ERROR("internal_error", 500),
}

/**
 * What a peer says about itself, in the UDP announce and in `GET /info` (SPEC-v2 §5).
 *
 * The JSON keys are the desktop wire names verbatim (`device_id`, `kind`, `ts`, `hub_port`);
 * @SerialName keeps the Kotlin side idiomatic. Two fields say different things and must not be
 * conflated: `modes` is what data planes this device can *initiate*, `roles` says whether it is
 * actually *serving* a hub right now (§6.3).
 *
 * None of it carries a secret: `kids` only *names* the key slots so a peer knows which
 * `X-Anki-Kid` it may address us with; the key material never leaves the device.
 */
@Serializable
data class LanPeerInfo(
    @SerialName("device_id") val id: String,
    val name: String,
    val port: Int = LanProtocol.HTTP_PORT,
    @SerialName("kind") val platform: String = "android",
    val protocol: Int = LanProtocol.VERSION,
    @SerialName("ts") val sentAt: Long = 0L,
    val modes: List<String> = listOf(LanProtocol.MODE_APKG),
    val roles: List<String> = listOf(LanProtocol.ROLE_P2P),
    val kids: List<String> = emptyList(),
    /** Port of the hub this device is serving; 0 when it is not serving one (§6.3). */
    @SerialName("hub_port") val hubPort: Int = 0,
    /** Profile this device would read/write; import must not silently land elsewhere (§7). */
    val profile: String = "",
)

/**
 * The v1 shape of the same information (SPEC-v2 §4.4): un-upgraded phones require `id`/`platform`,
 * so v2 devices still announce themselves twice — once per protocol — instead of pretending one
 * payload serves both.
 */
@Serializable
data class LanPeerInfoV1(
    val id: String,
    val name: String,
    val port: Int = LanProtocol.HTTP_PORT,
    val platform: String = "android",
    val appVersion: String = "",
    val protocol: Int = LanProtocol.VERSION_V1,
    val sentAt: Long = 0L,
)

fun LanPeerInfo.asV1(appVersion: String = ""): LanPeerInfoV1 =
    LanPeerInfoV1(
        id = id,
        name = name,
        port = port,
        platform = platform,
        appVersion = appVersion,
        sentAt = sentAt,
    )

fun LanPeerInfoV1.asV2(): LanPeerInfo =
    LanPeerInfo(
        id = id,
        name = name,
        port = port,
        platform = platform,
        protocol = LanProtocol.VERSION_V1,
        sentAt = sentAt,
        modes = listOf(LanProtocol.MODE_APKG),
        roles = listOf(LanProtocol.ROLE_P2P),
    )

/** How a peer entered the list. */
enum class LanSource { NSD, UDP, MANUAL }

/** Which way the collection package travelled. */
enum class LanDirection { PUSH, PULL, PROBE }

enum class LanResult { OK, ERROR, BUSY }

/** A trusted peer, persisted across runs. */
@Serializable
data class LanDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val source: LanSource,
    val addedAt: Long,
    val lastSyncAt: Long = 0L,
    val protocol: Int = LanProtocol.VERSION,
    val modes: List<String> = listOf(LanProtocol.MODE_APKG),
    val roles: List<String> = listOf(LanProtocol.ROLE_P2P),
    val kind: String = "android",
    /** The peer's kid we address requests with; empty until paired. */
    val kid: String = "",
    /** True once a shared secret was exchanged through pairing (SPEC-v2 §4). */
    val paired: Boolean = false,
    /** `sha256(shared_secret)` prefix, shown so the two screens can be eyeballed against each other. */
    val securityCode: String = "",
    /** Profile the peer reports for its collection; routing guard for imports. */
    val profile: String = "",
) {
    fun url(path: String): String = "http://$ip:$port/${path.trimStart('/')}"
}

/** One line of the activity log shown at the bottom of the LAN sync screen. */
@Serializable
data class LanLogEntry(
    val at: Long,
    val deviceId: String,
    val deviceName: String,
    val direction: LanDirection,
    val result: LanResult,
    val bytes: Long = 0L,
    val millis: Long = 0L,
    val detail: String = "",
    /** SPEC-v2 §5 status-ish code (`BUSY`, `401`, `SECURITY: plaintext-v1`, exception class…). */
    val errorCode: String = "",
)

/**
 * Per-round detail record (task #5 of the v2 plan): which data plane ran, what each leg moved and
 * what failed. Persisted separately from the human-readable log so the UI can show one row per round.
 */
@Serializable
data class LanRoundDetail(
    val at: Long,
    val deviceId: String,
    val deviceName: String,
    val mode: LanDataMode,
    val legs: Int = 0,
    val okLegs: Int = 0,
    val pushBytes: Long = 0L,
    val pullBytes: Long = 0L,
    val millis: Long = 0L,
    val error: String = "",
)

/**
 * Encodes a v2 announce packet: `MAGIC <json>`. A protocol=2 device announces itself as
 * protocol=2; [encodeV1Announce] is the separate legacy packet un-upgraded phones need (§4.4).
 */
fun encodeAnnounce(info: LanPeerInfo): ByteArray = announceOf(LanProtocol.MAGIC, LanProtocol.VERSION, info)

/** The legacy-format broadcast, sent alongside the v2 one so v1 phones still see us. */
fun encodeV1Announce(
    info: LanPeerInfo,
    appVersion: String = "",
): ByteArray =
    "${LanProtocol.MAGIC_V1} ${LanProtocol.json.encodeToString(LanPeerInfoV1.serializer(), info.asV1(appVersion))}"
        .toByteArray(Charsets.UTF_8)

private fun announceOf(
    magic: String,
    protocol: Int,
    info: LanPeerInfo,
): ByteArray =
    "$magic ${LanProtocol.json.encodeToString(LanPeerInfo.serializer(), info.copy(protocol = protocol))}"
        .toByteArray(Charsets.UTF_8)

/**
 * Decodes an announce packet of *either* protocol, or null when it is not ours / not addressable.
 * The address comes from the receiving socket, not the packet: senders underestimating their own
 * LAN IP (VPN tunnel, multiple interfaces) is the common failure mode.
 *
 * The magic prefix and the `protocol` field must agree, so a v2 payload cannot masquerade as v1
 * (or vice versa); unknown JSON fields are ignored on both versions (SPEC-v2 §9).
 */
fun decodeAnnounce(
    packet: ByteArray,
    length: Int,
): LanPeerInfo? {
    val text = String(packet, 0, length, Charsets.UTF_8)
    val (magic, requiredProtocol) =
        when {
            text.startsWith("${LanProtocol.MAGIC} ") -> LanProtocol.MAGIC to LanProtocol.VERSION
            text.startsWith("${LanProtocol.MAGIC_V1} ") -> LanProtocol.MAGIC_V1 to LanProtocol.VERSION_V1
            else -> return null
        }
    val body = text.substring(magic.length + 1)
    val info =
        if (requiredProtocol == LanProtocol.VERSION) {
            runCatching { LanProtocol.json.decodeFromString(LanPeerInfo.serializer(), body) }.getOrNull()
        } else {
            runCatching { LanProtocol.json.decodeFromString(LanPeerInfoV1.serializer(), body) }
                .getOrNull()
                ?.asV2()
        } ?: return null
    if (info.id.isBlank() || info.port !in 1..65_535) return null
    if (info.protocol != requiredProtocol) return null
    return info
}

/**
 * Picks the data plane for a round from both peers' advertised modes/roles (SPEC-v2 §6.3):
 * intersection of `modes`, preference `hub > apkg`. A hub is only usable when the *peer* serves
 * the hub role; this device never acts as hub server on Android, so our own role is `p2p`.
 */
fun negotiateDataMode(
    ours: LanPeerInfo,
    theirs: LanPeerInfo,
): LanDataMode? {
    if (theirs.protocol < LanProtocol.VERSION) return null
    val shared = ours.modes.intersect(theirs.modes.toSet())
    return when {
        LanProtocol.MODE_HUB in shared && LanProtocol.ROLE_HUB in theirs.roles -> LanDataMode.HUB
        LanProtocol.MODE_APKG in shared -> LanDataMode.APKG
        else -> null
    }
}

/**
 * Directed broadcast address of the /24 the peer sits in, e.g. `192.168.1.10` → `192.168.1.255`.
 *
 * Android drops packets sent to the global `255.255.255.255` on many access points, so the subnet
 * broadcast has to go out as well. Returns null for anything that is not usable IPv4.
 */
fun subnetBroadcastAddress(
    localIp: String,
    prefixLength: Int = 24,
): String? {
    val raw = IpAddress.parse(localIp) ?: return null
    if (prefixLength !in 0..31) return null
    val octets = raw.map { it.toInt() and 0xFF }
    if (IpAddress.isLoopback(octets) || octets.all { it == 0 }) return null
    val address = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
    val mask = -1 shl (32 - prefixLength)
    val broadcast = address or mask.inv()
    if (broadcast == -1) return null
    return IpAddress.format(
        byteArrayOf(
            (broadcast shr 24).toByte(),
            (broadcast shr 16).toByte(),
            (broadcast shr 8).toByte(),
            broadcast.toByte(),
        ),
    )
}

/** Small IPv4 helpers, kept separate so unit tests need no Android classes. */
object IpAddress {
    fun parse(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return byteArrayOf(octets[0].toByte(), octets[1].toByte(), octets[2].toByte(), octets[3].toByte())
    }

    fun isLoopback(octets: List<Int>): Boolean = octets.firstOrNull() == 127

    fun isSiteLocal(octets: List<Int>): Boolean =
        octets.size == 4 &&
            (
                (octets[0] == 10) ||
                    (octets[0] == 192 && octets[1] == 168) ||
                    (octets[0] == 172 && octets[1] in 16..31)
            )

    fun format(raw: ByteArray): String = raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

/** Resolves `host` to an IPv4 literal, or null when it is not reachable by name here. */
fun resolveHostToIpv4(host: String): String? {
    val literal = IpAddress.parse(host)
    if (literal != null) return host
    return runCatching {
        InetAddress
            .getAllByName(host)
            .firstOrNull { it.hostAddress?.count { c -> c == '.' } == 3 }
            ?.hostAddress
    }.getOrNull()
}

/** Thrown when the collection is busy with another sync leg and the request has to back off. */
class LanBusyException : IllegalStateException("LAN sync engine is busy")

/**
 * Wall clock for peer timestamps.
 *
 * AnkiDroid's lint asks for the collection's `getTime()`, which accounts for the daily rollover -
 * irrelevant here: these stamps date log lines and measure transfers, and the code paths that need
 * them (announce listeners, HTTP handlers) must work without an open collection.
 */
@android.annotation.SuppressLint("DirectSystemCurrentTimeMillisUsage")
fun lanNow(): Long = System.currentTimeMillis()

/** Thrown when a peer answers with something that is not an anki sync service. */
class LanPeerRejectException(
    message: String,
) : SerializationException(message)
