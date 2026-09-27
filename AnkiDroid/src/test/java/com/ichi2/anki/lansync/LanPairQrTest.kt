// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Local codec tests for the pairing QR ticket (SPEC-v2 §4.7). No network, no camera: only the
 * text format, parse tolerance, and the security-relevant field checks. Cross-implementation byte
 * alignment is pinned separately by [LanInteropVectorsTest] against `vectors.json`.
 */
class LanPairQrTest {
    private val id = "aaaaaaaa-1111-4111-8111-111111111111"

    @Test
    fun `encode then decode preserves every field`() {
        val text = LanPairQr.encode(id, "我的机子", "desktop", "192.168.1.20", 5611, "004200")
        val t = LanPairQr.decode(text)
        assertEquals(id, t.deviceId)
        assertEquals("我的机子", t.name)
        assertEquals("desktop", t.kind)
        assertEquals("192.168.1.20", t.host)
        assertEquals(5611, t.port)
        assertEquals("004200", t.pairCode)
    }

    @Test
    fun `canonical text is prefix plus compact json in declared key order`() {
        val text = LanPairQr.encode(id, "N", "android", "10.0.0.7", 5600, "123456")
        assert(text.startsWith(LanPairQr.MAGIC + " "))
        val body = text.substring(LanPairQr.MAGIC.length + 1)
        val order = listOf("\"t\"", "\"v\"", "\"id\"", "\"nk\"", "\"nm\"", "\"h\"", "\"p\"", "\"c\"")
        var last = -1
        for (key in order) {
            val at = body.indexOf(key)
            assert(at > last) { "key $key out of order in: $body" }
            last = at
        }
        assert(!body.contains(", ")) { "compact separators required" }
        assert(!body.contains(": ")) { "compact separators required" }
    }

    @Test
    fun `a foreign QR is not ours rather than a parse crash`() {
        val e =
            assertThrows(LanPairQrException::class.java) {
                LanPairQr.decode("https://example.com/")
            }
        assertEquals("not_our_qr", e.code)
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = LanPairQr.encode(id, "N", "desktop", "192.168.1.5", 5600, "111111")
        val mutated = text.dropLast(1) + ",\"future\":9}"
        val t = LanPairQr.decode(mutated)
        assertEquals(id, t.deviceId)
        assertEquals("111111", t.pairCode)
    }

    @Test
    fun `wrong type tag is rejected`() {
        val e =
            assertThrows(LanPairQrException::class.java) {
                LanPairQr.decode(LanPairQr.MAGIC + " {\"t\":\"other/9\"}")
            }
        assertEquals("bad_format", e.code)
    }

    @Test
    fun `old protocol is rejected`() {
        val body =
            "{\"t\":\"${LanPairQr.MAGIC}\",\"v\":1,\"id\":\"$id\",\"nk\":\"desktop\"," +
                "\"nm\":\"N\",\"h\":\"192.168.1.1\",\"p\":5600,\"c\":\"123456\"}"
        val e =
            assertThrows(LanPairQrException::class.java) {
                LanPairQr.decode(LanPairQr.MAGIC + " " + body)
            }
        assertEquals("old_protocol", e.code)
    }

    @Test
    fun `malformed or unsafe fields are rejected on decode`() {
        val cases =
            listOf(
                "\"c\":\"12345\"" to "bad_code", // 5 digits
                "\"c\":\"abcdef\"" to "bad_code",
                "\"h\":\"8.8.8.8\"" to "bad_address", // public
                "\"h\":\"169.254.1.2\"" to "bad_address", // link-local
                "\"h\":\"127.0.0.1\"" to "bad_address", // loopback
                "\"id\":\"\"" to "bad_id",
                "\"p\":70000" to "bad_port",
            )
        for ((replacement, expected) in cases) {
            val key = replacement.substringBefore(":")
            val good =
                LinkedHashMap<String, String>().apply {
                    put("t", "\"${LanPairQr.MAGIC}\"")
                    put("v", "2")
                    put("id", "\"$id\"")
                    put("nk", "\"desktop\"")
                    put("nm", "\"N\"")
                    put("h", "\"192.168.1.20\"")
                    put("p", "5600")
                    put("c", "\"123456\"")
                }
            val field = key.trim('"')
            good[field] = replacement.substringAfter(":")
            val body = good.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
            val e =
                assertThrows(LanPairQrException::class.java) {
                    LanPairQr.decode(LanPairQr.MAGIC + " " + body)
                }
            assertEquals("for $replacement", expected, e.code)
        }
    }

    @Test
    fun `a scanned ticket becomes a manual device pointing at its endpoint`() {
        val text = LanPairQr.encode(id, "Desk", "desktop", "192.168.7.30", 5607, "900000")
        val device = LanPairQr.decode(text).toManualDevice()
        assertEquals(id, device.id)
        assertEquals("Desk", device.name)
        assertEquals("192.168.7.30", device.ip)
        assertEquals(5607, device.port)
        assertEquals("desktop", device.kind)
        assertEquals(LanSource.MANUAL, device.source)
        // Not paired yet — that only becomes true once /pair/commit succeeds.
        assertEquals(false, device.paired)
    }

    @Test
    fun `encode validates its own inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            LanPairQr.encode("", "N", "desktop", "192.168.1.1", 5600, "123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanPairQr.encode(id, "N", "desktop", "192.168.1.1", 5600, "12345")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanPairQr.encode(id, "N", "desktop", "8.8.8.8", 5600, "123456")
        }
    }
}
