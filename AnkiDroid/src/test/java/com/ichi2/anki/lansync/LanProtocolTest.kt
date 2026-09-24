// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanProtocolTest {
    private val info =
        LanPeerInfo(
            id = "device-1",
            name = "Pixel 8",
            port = 5601,
            sentAt = 123L,
        )

    @Test
    fun `announce survives a round trip`() {
        val packet = encodeAnnounce(info)
        assertEquals(info, decodeAnnounce(packet, packet.size))
    }

    @Test
    fun `announce ignores trailing bytes of a reused buffer`() {
        val packet = encodeAnnounce(info)
        val buffer = packet.copyOf(packet.size + 512)
        assertEquals(info, decodeAnnounce(buffer, packet.size))
    }

    @Test
    fun `foreign traffic on the announce port is dropped`() {
        val foreign = "SOME OTHER PROTOCOL {\"id\":\"x\"}".toByteArray()
        assertNull(decodeAnnounce(foreign, foreign.size))
    }

    @Test
    fun `truncated and malformed packets are dropped`() {
        val packet = encodeAnnounce(info)
        assertNull(decodeAnnounce(packet, packet.size - 3))
        val broken = "${LanProtocol.MAGIC} {not json".toByteArray()
        assertNull(decodeAnnounce(broken, broken.size))
    }

    @Test
    fun `announce without an id or with an unusable port is dropped`() {
        for (candidate in listOf(info.copy(id = " "), info.copy(port = 0), info.copy(port = 70_000))) {
            val packet = encodeAnnounce(candidate)
            assertNull(decodeAnnounce(packet, packet.size))
        }
    }

    @Test
    fun `v2 announce carries the new modes roles kids and profile fields`() {
        val v2 =
            info.copy(
                modes = listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB),
                roles = listOf(LanProtocol.ROLE_HUB),
                kids = listOf("aabbccdd"),
                profile = "Joe",
            )
        val packet = encodeAnnounce(v2)
        val decoded = assertNotNull(decodeAnnounce(packet, packet.size))
        assertEquals(v2, decoded)
    }

    @Test
    fun `a v1 announce packet still decodes so un-upgraded phones are found`() {
        val text =
            """{"id":"old","name":"Old phone","port":5600,"protocol":1,"modes":["apkg"],"brandNew":true}"""
        val packet = "${LanProtocol.MAGIC_V1} $text".toByteArray()
        val decoded = assertNotNull(decodeAnnounce(packet, packet.size))
        assertEquals("old", decoded.id)
        assertEquals(LanProtocol.VERSION_V1, decoded.protocol)
    }

    @Test
    fun `a v2 device also broadcasts the legacy packet so old phones still see it`() {
        val v2Only =
            info.copy(
                modes = listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB),
                roles = listOf(LanProtocol.ROLE_P2P, LanProtocol.ROLE_HUB),
                kids = listOf("aabbccdd"),
                profile = "Joe",
            )
        val packet = encodeV1Announce(v2Only, "1.0")
        val decoded = assertNotNull(decodeAnnounce(packet, packet.size))
        assertEquals(LanProtocol.VERSION_V1, decoded.protocol)
        // SPEC-v2 §4.4: a v1 peer only ever syncs packages point to point, so the projection must
        // drop the v2-only capabilities instead of advertising them and failing later.
        assertEquals(listOf(LanProtocol.MODE_APKG), decoded.modes)
        assertEquals(listOf(LanProtocol.ROLE_P2P), decoded.roles)
        assertTrue(decoded.kids.isEmpty())
        assertEquals("", decoded.profile)
        assertEquals(0, decoded.hubPort)
        // The addressable core survives the translation in both directions.
        assertEquals(v2Only.id, decoded.id)
        assertEquals(v2Only.name, decoded.name)
        assertEquals(v2Only.port, decoded.port)
        assertEquals(v2Only.platform, decoded.platform)
        assertEquals(v2Only.sentAt, decoded.sentAt)
    }

    @Test
    fun `magic and protocol field must agree`() {
        // a v2 payload cannot masquerade as v1 and vice versa (SPEC-v2 §3)
        val v2Body = LanProtocol.json.encodeToString(LanPeerInfo.serializer(), info)
        val lying1 = "${LanProtocol.MAGIC_V1} $v2Body".toByteArray()
        assertNull(decodeAnnounce(lying1, lying1.size))
        val v1Body = LanProtocol.json.encodeToString(LanPeerInfo.serializer(), info.copy(protocol = LanProtocol.VERSION_V1))
        val lying2 = "${LanProtocol.MAGIC} $v1Body".toByteArray()
        assertNull(decodeAnnounce(lying2, lying2.size))
    }

    @Test
    fun `announce from another protocol version is dropped`() {
        // Hand-built: encodeAnnounce always stamps its own version, so the only way to pose a
        // v3 peer is to write the packet a future phone would.
        val body = LanProtocol.json.encodeToString(LanPeerInfo.serializer(), info.copy(protocol = LanProtocol.VERSION + 1))
        val packet = "${LanProtocol.MAGIC} $body".toByteArray()
        assertNull(decodeAnnounce(packet, packet.size))
    }

    @Test
    fun `unknown fields are tolerated so a newer peer can still be used`() {
        val text =
            """{"device_id":"a","name":"b","port":5600,"protocol":2,"ts":0,"somethingNew":42}"""
        val packet = "${LanProtocol.MAGIC} $text".toByteArray()
        val decoded = assertNotNull(decodeAnnounce(packet, packet.size))
        assertEquals("a", decoded.id)
    }

    @Test
    fun `subnet broadcast address covers the common Wi-Fi ranges`() {
        assertEquals("192.168.1.255", subnetBroadcastAddress("192.168.1.10"))
        assertEquals("10.0.0.255", subnetBroadcastAddress("10.0.0.7"))
        assertEquals("172.16.5.255", subnetBroadcastAddress("172.16.5.200"))
        assertEquals("192.168.0.255", subnetBroadcastAddress("192.168.0.255"))
    }

    @Test
    fun `subnet broadcast address respects the prefix length`() {
        assertEquals("192.168.1.15", subnetBroadcastAddress("192.168.1.3", 28))
        assertEquals("10.1.255.255", subnetBroadcastAddress("10.1.2.3", 16))
    }

    @Test
    fun `subnet broadcast address refuses unusable input`() {
        assertNull(subnetBroadcastAddress("not-an-ip"))
        assertNull(subnetBroadcastAddress("192.168.1"))
        assertNull(subnetBroadcastAddress("256.1.1.1"))
        assertNull(subnetBroadcastAddress("127.0.0.1"))
        assertNull(subnetBroadcastAddress("0.0.0.0"))
        assertNull(subnetBroadcastAddress("255.255.255.255"))
        assertNull(subnetBroadcastAddress("192.168.1.10", 32))
        assertNull(subnetBroadcastAddress("192.168.1.10", -1))
    }

    @Test
    fun `IPv4 parsing rejects anything that is not four dotted octets`() {
        assertEquals("192.168.1.2", IpAddress.format(IpAddress.parse("192.168.1.2")!!))
        assertEquals("192.168.1.2", IpAddress.format(IpAddress.parse("192.168.001.002")!!))
        assertNull(IpAddress.parse(""))
        assertNull(IpAddress.parse("fe80::1"))
        assertNull(IpAddress.parse("192.168.1.2%wlan0"))
        assertNull(IpAddress.parse("192.168.1.2 "))
        assertEquals("0.0.0.0", IpAddress.format(IpAddress.parse("0.0.0.0")!!))
    }

    @Test
    fun `site local ranges are recognised`() {
        for (octets in listOf(listOf(10, 0, 0, 1), listOf(172, 16, 0, 1), listOf(172, 31, 0, 1), listOf(192, 168, 0, 1))) {
            assertTrue(IpAddress.isSiteLocal(octets))
        }
        for (octets in listOf(listOf(172, 15, 0, 1), listOf(172, 32, 0, 1), listOf(8, 8, 8, 8), listOf(127, 0, 0, 1), listOf(192, 168))) {
            assertFalse(IpAddress.isSiteLocal(octets))
        }
    }

    @Test
    fun `device URLs are built from the stored address and port`() {
        val device = LanDevice("id", "name", "192.168.1.9", 5600, LanSource.UDP, 0L)
        assertEquals("http://192.168.1.9:5600/info", device.url("/info"))
        assertEquals("http://192.168.1.9:5600/export", device.url("export"))
    }

    // region mode negotiation (SPEC-v2 §6.3)

    private val ours =
        LanPeerInfo(
            id = "me",
            name = "Phone",
            modes = listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB),
            roles = listOf(LanProtocol.ROLE_P2P),
        )

    private fun peer(
        modes: List<String>,
        roles: List<String>,
        protocol: Int = LanProtocol.VERSION,
    ) = LanPeerInfo(id = "them", name = "Peer", modes = modes, roles = roles, protocol = protocol)

    @Test
    fun `hub wins over apkg when the peer serves the hub role`() {
        val hubPeer = peer(listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB), listOf(LanProtocol.ROLE_P2P, LanProtocol.ROLE_HUB))
        assertEquals(LanDataMode.HUB, negotiateDataMode(ours, hubPeer))
    }

    @Test
    fun `hub mode without the hub role falls back to apkg`() {
        // a peer advertising hub in `modes` but never acting as hub server cannot be synced via hub
        val odd = peer(listOf(LanProtocol.MODE_HUB), listOf(LanProtocol.ROLE_P2P))
        assertNull(negotiateDataMode(ours, odd))
        val both = peer(listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB), listOf(LanProtocol.ROLE_P2P))
        assertEquals(LanDataMode.APKG, negotiateDataMode(ours, both))
    }

    @Test
    fun `apkg is picked when it is the only shared mode`() {
        val apkgOnly = peer(listOf(LanProtocol.MODE_APKG), listOf(LanProtocol.ROLE_P2P))
        assertEquals(LanDataMode.APKG, negotiateDataMode(ours, apkgOnly))
    }

    @Test
    fun `no shared mode and v1 peers negotiate to nothing`() {
        assertNull(negotiateDataMode(ours, peer(listOf("carrier-pigeon"), listOf(LanProtocol.ROLE_P2P))))
        assertNull(
            negotiateDataMode(
                ours,
                peer(listOf(LanProtocol.MODE_APKG, LanProtocol.MODE_HUB), listOf(LanProtocol.ROLE_HUB), protocol = LanProtocol.VERSION_V1),
            ),
        )
    }

    // endregion
}
