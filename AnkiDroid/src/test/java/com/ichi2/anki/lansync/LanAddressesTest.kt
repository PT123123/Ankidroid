// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNull

class LanAddressesTest {
    private fun interface_(
        name: String,
        vararg addresses: String,
    ) = LanNetworkInterface(name, addresses.toList())

    @Test
    fun `only private IPv4 addresses can be announced to peers`() {
        for (usable in listOf("192.168.1.23", "10.30.0.4", "172.20.8.9", "192.168.1.23%wlan0")) {
            assertTrue(usable, LanAddresses.isReachableIpv4(usable))
        }
        for (rejected in listOf("127.0.0.1", "0.0.0.0", "169.254.9.9", "8.8.8.8", "fe80::1", "", "not-an-ip")) {
            assertFalse(rejected, LanAddresses.isReachableIpv4(rejected))
        }
    }

    @Test
    fun `Wi-Fi wins over VPN and mobile interfaces`() {
        val interfaces =
            listOf(
                interface_("lo", "127.0.0.1"),
                interface_("tun0", "10.251.34.7"),
                interface_("rmnet_data0", "10.6.0.2"),
                interface_("wlan0", "192.168.1.23"),
            )
        assertEquals("192.168.1.23", LanAddresses.preferredAddress(interfaces))
    }

    @Test
    fun `an address only appears when its interface actually carries one`() {
        // wlan0 is up but has no lease yet: announcing nothing beats announcing a VPN address.
        val interfaces = listOf(interface_("wlan0", "127.0.0.1"), interface_("tun0", "10.251.34.7"))
        assertEquals("10.251.34.7", LanAddresses.preferredAddress(interfaces))
        assertEquals(emptyList<String>(), LanAddresses.allUsableAddresses(listOf(interface_("wlan0", "127.0.0.1"))))
    }

    @Test
    fun `wired and hotspot adapters follow Wi-Fi in the preference order`() {
        assertEquals(
            "192.168.9.9",
            LanAddresses.preferredAddress(listOf(interface_("ap0", "10.15.0.1"), interface_("eth0", "192.168.9.9"))),
        )
        assertEquals(
            "192.168.9.9",
            LanAddresses.preferredAddress(listOf(interface_("rnd0", "10.15.0.1"), interface_("wlan1", "192.168.9.9"))),
        )
    }

    @Test
    fun `a device with no usable address announces nothing`() {
        assertNull(LanAddresses.preferredAddress(listOf(interface_("lo", "127.0.0.1"), interface_("tun0", "1.1.1.1"))))
        assertNull(LanAddresses.preferredAddress(emptyList()))
    }

    @Test
    fun `announces go to the global and the subnet broadcast address`() {
        assertEquals(
            listOf("255.255.255.255", "192.168.1.255"),
            LanAddresses.broadcastTargets("192.168.1.23"),
        )
        // /31 point-to-point links have no broadcast address worth sending to twice
        assertEquals(listOf("255.255.255.255"), LanAddresses.broadcastTargets("0.0.0.0"))
    }

    @Test
    fun `every interface contributes its own address`() {
        val interfaces =
            listOf(
                interface_("lo", "127.0.0.1"),
                interface_("wlan0", "192.168.1.23"),
                interface_("tun0", "10.251.34.7"),
                interface_("ap0", "172.16.42.1"),
            )
        assertEquals(
            setOf("192.168.1.23", "10.251.34.7", "172.16.42.1"),
            LanAddresses.allUsableAddresses(interfaces).toSet(),
        )
    }
}
