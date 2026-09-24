// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import java.net.NetworkInterface

/** One interface and the IPv4 literals it carries. */
data class LanNetworkInterface(
    val name: String,
    val addresses: List<String>,
)

/**
 * Resolves the address other devices on the Wi-Fi can reach us at.
 *
 * A phone commonly has several interfaces at once (mobile data, VPN tunnel, loopback), and only
 * `wlan*` is answerable from the local network - advertising a VPN or hotspot address makes peers
 * try to connect to a host that is not there, which is why the selection is explicit rather than
 * "first non-loopback address".
 */
object LanAddresses {
    /** Interface name prefixes in preference order: Wi-Fi, then wired adapters, then anything. */
    private val PREFERRED_PREFIXES = listOf("wlan", "swlan", "eth", "usb")

    fun current(): List<LanNetworkInterface> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { networkInterface ->
                val addresses =
                    runCatching {
                        networkInterface.inetAddresses
                            ?.toList()
                            .orEmpty()
                            .mapNotNull { it.hostAddress }
                    }.getOrDefault(emptyList())
                LanNetworkInterface(networkInterface.name, addresses)
            }
        }.getOrDefault(emptyList())

    /** The address to announce, or null while the device is not on a usable network. */
    fun preferredAddress(interfaces: List<LanNetworkInterface>): String? =
        PREFERRED_PREFIXES
            .asSequence()
            .mapNotNull { prefix -> interfaces.firstOrNull { it.name.startsWith(prefix) }?.usableAddress() }
            .firstOrNull()
            ?: interfaces.asSequence().mapNotNull { it.usableAddress() }.firstOrNull()

    fun preferredAddress(): String? = preferredAddress(current())

    /** Every usable address, so a peer is announced on all of them. */
    fun allUsableAddresses(interfaces: List<LanNetworkInterface>): List<String> = interfaces.mapNotNull { it.usableAddress() }

    private fun LanNetworkInterface.usableAddress(): String? = addresses.firstOrNull { isReachableIpv4(it) }

    /** Site-local IPv4 only: no loopback, no `0.0.0.0`, no IPv6, no public address. */
    fun isReachableIpv4(text: String): Boolean {
        val stripped = text.substringBefore('%')
        val octets = IpAddress.parse(stripped)?.map { it.toInt() and 0xFF } ?: return false
        if (octets[0] == 0 || IpAddress.isLoopback(octets)) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        return IpAddress.isSiteLocal(octets)
    }

    /**
     * Broadcast targets for an announce: the global one plus the subnet-directed one, which is
     * what most access points actually forward.
     */
    fun broadcastTargets(localIp: String): List<String> =
        listOfNotNull("255.255.255.255", subnetBroadcastAddress(localIp).takeIf { it != "255.255.255.255" })
}
