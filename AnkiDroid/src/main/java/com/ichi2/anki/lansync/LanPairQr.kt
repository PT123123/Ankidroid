// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The pairing QR ticket (SPEC-v2 §4.7): plaintext handoff of a connection endpoint plus the
 * one-use 6-digit pairing code, so a phone can scan a laptop screen (or a desktop can read a saved
 * QR image) and pair without anyone typing digits.
 *
 * This is a *codec*, not a route: nothing new goes on the wire. The scanner hands `c` to
 * `/pair/commit` at `h:p`; the trust-list row is written from the commit answer's `peer_info`, so
 * the id and name claimed by the QR are display-only. The security anchor is unchanged — putting
 * the code in the QR is no more exposed than printing it on screen for the user to read out, so the
 * post-commit security-code check (SPEC-v2 §4.1) is still the only thing that stops a man in the
 * middle.
 *
 * The serialized form is `MAGIC + space + compact JSON` with a fixed key order (`t v id nk nm h p
 * c`), byte-for-byte pinned by `vectors.json`'s `pair_qr` section on both ends.
 */
@Serializable
data class LanPairTicket(
    // Declaration order IS the wire key order; kotlinx does not sort. Do not reorder these.
    @SerialName("t") val type: String,
    @SerialName("v") val protocol: Int,
    @SerialName("id") val deviceId: String,
    @SerialName("nk") val kind: String,
    @SerialName("nm") val name: String,
    @SerialName("h") val host: String,
    @SerialName("p") val port: Int,
    @SerialName("c") val pairCode: String,
)

/** Local parse/validation failure. `code` picks the user-facing string; there is no peer involved. */
class LanPairQrException(
    val code: String,
) : Exception(code)

object LanPairQr {
    /** Text prefix and the `t` field value. Distinct from `v` on purpose: `t` changes the ticket
     *  layout, `v` tracks the control-plane protocol version. */
    const val MAGIC = "anki-lan-pair/1"
    const val PROTOCOL = LanProtocol.VERSION

    private const val DEFAULT_PORT = LanProtocol.HTTP_PORT
    private val PAIR_CODE = Regex("\\d{6}")

    /** Build the canonical ticket text. The *showing* side calls this; it validates its own inputs. */
    fun encode(
        deviceId: String,
        name: String,
        kind: String,
        host: String,
        port: Int,
        pairCode: String,
    ): String {
        require(deviceId.isNotEmpty()) { "device_id must not be empty" }
        require(PAIR_CODE.matches(pairCode)) { "pair code must be 6 decimal digits" }
        require(LanAddresses.isReachableIpv4(host)) { "can only advertise a site-local IPv4" }
        val effectivePort = if (port == 0) DEFAULT_PORT else port
        require(effectivePort in 1..65535) { "port out of range: $port" }
        val ticket = LanPairTicket(MAGIC, PROTOCOL, deviceId, kind, name, host, effectivePort, pairCode)
        return "$MAGIC " + toAscii(LanProtocol.json.encodeToString(LanPairTicket.serializer(), ticket))
    }

    /**
     * Escapes every non-ASCII character as `\uXXXX`, so the ticket text is pure ASCII. QR decoders
     * guess the charset of a byte-mode payload (OpenCV mangles a CJK device name in our tests; zxing's
     * writer falls back to ISO-8859-1 unless told otherwise) and JSON escapes are unambiguous, so this
     * removes the whole class instead of relying on each side to ask for UTF-8. Mirrors Python's
     * `json.dumps(ensure_ascii=True)`, lowercase hex, which is what `vectors.json` pins.
     */
    private fun toAscii(json: String): String {
        if (json.all { it.code in 0x20..0x7E }) return json
        val out = StringBuilder(json.length + 16)
        for (char in json) {
            if (char.code in 0x20..0x7E) {
                out.append(char)
            } else {
                out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
            }
        }
        return out.toString()
    }

    /**
     * Decode and validate. A foreign QR (any other text) yields [LanPairQrException] with
     * `not_our_qr` rather than throwing a parse error — the camera reads arbitrary codes.
     * Unknown JSON fields are ignored for forward-compatibility (§9).
     */
    fun decode(text: String): LanPairTicket {
        val prefix = "$MAGIC "
        if (!text.startsWith(prefix)) throw LanPairQrException("not_our_qr")
        val ticket =
            try {
                LanProtocol.json.decodeFromString(LanPairTicket.serializer(), text.substring(prefix.length))
            } catch (e: Exception) {
                throw LanPairQrException("bad_format")
            }
        if (ticket.type != MAGIC) throw LanPairQrException("bad_format")
        if (ticket.protocol < PROTOCOL) throw LanPairQrException("old_protocol")
        if (ticket.deviceId.isEmpty()) throw LanPairQrException("bad_id")
        if (!PAIR_CODE.matches(ticket.pairCode)) throw LanPairQrException("bad_code")
        if (!LanAddresses.isReachableIpv4(ticket.host)) throw LanPairQrException("bad_address")
        if (ticket.port !in 1..65535) throw LanPairQrException("bad_port")
        return ticket
    }
}

/**
 * The provisional [LanDevice] a scanned ticket points at: enough for `pairCommit` to reach the
 * peer at `ip:port` and hand over the code. The full trust-list row is written by
 * [LanEngine.pairCommit] from the peer's own answer, not from what the QR claimed.
 */
fun LanPairTicket.toManualDevice(): LanDevice =
    LanDevice(
        id = deviceId,
        name = name,
        ip = host,
        port = port,
        source = LanSource.MANUAL,
        addedAt = lanNow(),
        kind = kind,
    )
