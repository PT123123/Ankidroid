// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 PT123123 <31439216+PT123123@users.noreply.github.com>

package com.ichi2.anki.lansync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Finds peers on the local network, two ways at once:
 *
 * - **mDNS/NSD** (`_ankisync._tcp`, plus the legacy `_ankiplus-sync._tcp`) is the reliable one on
 *   Android; the platform multicast resolver handles AP settings that block raw broadcasts.
 * - **UDP announce** (`ANKI-LAN/2`, v1 packets still decoded) on [LanProtocol.UDP_PORT] is the
 *   aw-sync-rust approach and still works on plain home routers, so it runs alongside NSD and wins
 *   whenever NSD is unavailable.
 *
 * Both only report `(ip, port)` candidates: [LanSyncManager] probes them over HTTP before trusting
 * anything, so a spoofed announce still has to answer as a sync service.
 */
class LanDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    private val onEndpoint: suspend (ip: String, port: Int, source: LanSource, info: LanPeerInfo?) -> Unit,
) {
    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as NsdManager }.getOrNull()
    private val wifiManager: WifiManager? =
        runCatching { context.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()

    @Volatile private var active = false
    private var httpPort = LanProtocol.HTTP_PORT
    private val registrationListeners = java.util.Collections.synchronizedList(mutableListOf<NsdManager.RegistrationListener>())
    private val discoveryListeners = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NsdManager.DiscoveryListener>>())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var announceThread: Thread? = null
    private var listenThread: Thread? = null

    fun start(port: Int) {
        if (active) return
        active = true
        httpPort = port
        acquireMulticastLock()
        registerNsd(port)
        browseNsd()
        announceThread = thread(start = true, isDaemon = true, name = "lan-announce") { announceLoop() }
        listenThread = thread(start = true, isDaemon = true, name = "lan-listen") { listenLoop() }
        Timber.i("LAN discovery started on port %d", port)
    }

    fun stop() {
        if (!active) return
        active = false
        announceThread?.interrupt()
        listenThread?.interrupt()
        announceThread = null
        listenThread = null
        unregisterNsd()
        runCatching { multicastLock?.release() }
        multicastLock = null
        Timber.i("LAN discovery stopped")
    }

    // region UDP

    private fun acquireMulticastLock() {
        multicastLock =
            runCatching {
                wifiManager?.createMulticastLock("ankiplus-lansync")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
    }

    private fun announceLoop() {
        while (active) {
            val localIp = LanAddresses.preferredAddress()
            if (localIp == null) {
                // No usable Wi-Fi address: stay silent rather than announce a VPN/loopback peer
                // address, which every device would then try to sync with.
                sleep(LanProtocol.ANNOUNCE_INTERVAL_MS)
                continue
            }
            val info = LanStore.instance.selfInfo(httpPort)
            // SPEC-v2 §4.4: a v2 device sends *both* announces, so a phone that never upgraded
            // still finds us. The v1 packet is the legacy magic and the legacy JSON shape.
            val packets = listOf(encodeAnnounce(info), encodeV1Announce(info, LanStore.instance.appVersion))
            for (target in LanAddresses.broadcastTargets(localIp)) {
                for (packet in packets) {
                    runCatching { sendAnnouncement(packet, target, localIp) }
                        .onFailure { Timber.w(it, "UDP announce to %s failed", target) }
                }
            }
            sleep(LanProtocol.ANNOUNCE_INTERVAL_MS)
        }
    }

    private fun sendAnnouncement(
        payload: ByteArray,
        target: String,
        localIp: String,
    ) {
        // Bound to the Wi-Fi address so the packet leaves through that interface instead of the
        // default (possibly VPN) route.
        DatagramSocket(0, InetAddress.getByName(localIp)).use { socket ->
            socket.broadcast = true
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName(target), LanProtocol.UDP_PORT)
            socket.send(packet)
        }
    }

    private fun listenLoop() {
        while (active) {
            val socket =
                runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(LanProtocol.UDP_PORT))
                        soTimeout = 1_000
                    }
                }.getOrElse { error ->
                    // Another app owns the announce port; NSD and manual add still work.
                    Timber.w(error, "Could not listen on UDP %d, discovery limited", LanProtocol.UDP_PORT)
                    return
                }
            try {
                val buffer = ByteArray(2048)
                while (active) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    } catch (closed: SocketException) {
                        break
                    }
                    val info = decodeAnnounce(packet.data, packet.length) ?: continue
                    if (LanStore.instance.isSelf(info.id)) continue
                    val ip = packet.address?.hostAddress ?: continue
                    if (!LanAddresses.isReachableIpv4(ip)) continue
                    report(ip, info.port, LanSource.UDP, info)
                }
            } finally {
                socket.close()
            }
        }
    }

    // endregion

    // region NSD

    private fun registerNsd(port: Int) {
        val manager = nsdManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Timber.i("NSD registration needs API 28+, relying on UDP announce")
            return
        }
        val id8 = LanStore.instance.deviceId.take(8)
        val v2 =
            NsdServiceInfo().apply {
                // SPEC-v2 §3.1: instance `<name>-<id8>`, TXT only carries v/id/kind/roles.
                serviceName = "${LanStore.instance.deviceName.take(24).ifBlank { "anki" }}-$id8"
                serviceType = LanProtocol.NSD_SERVICE_TYPE
                this.port = port
                setAttribute("v", LanProtocol.VERSION.toString())
                setAttribute("id", id8)
                setAttribute("kind", "android")
                setAttribute("roles", LanProtocol.ROLE_P2P)
            }
        val v1 =
            NsdServiceInfo().apply {
                // The name prefix is how v1 phones pick peers out of the service type (§4.4).
                serviceName = "${LanProtocol.NSD_NAME_PREFIX}${LanStore.instance.deviceName.take(24)}"
                serviceType = LanProtocol.NSD_SERVICE_TYPE_V1
                this.port = port
                setAttribute("id", id8)
            }
        for (service in listOf(v2, v1)) {
            register(manager, service)
        }
    }

    private fun register(
        manager: NsdManager,
        service: NsdServiceInfo,
    ) {
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Timber.d("LAN sync service registered as %s", info.serviceName)
                }

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) = logFailure("register", errorCode)

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) = logFailure("unregister", errorCode)

                private fun logFailure(
                    what: String,
                    code: Int,
                ) {
                    Timber.w("LAN sync NSD %s of %s failed (%d)", what, service.serviceType, code)
                }
            }
        registrationListeners.add(listener)
        runCatching { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Timber.w(it, "NSD registerService(%s) threw", service.serviceType) }
    }

    private fun browseNsd() {
        browse(LanProtocol.NSD_SERVICE_TYPE) { info -> true }
        // v1 phones advertise under the old type and name prefix; keep finding them.
        browse(LanProtocol.NSD_SERVICE_TYPE_V1) { info -> info.serviceName.startsWith(LanProtocol.NSD_NAME_PREFIX) }
    }

    private fun browse(
        serviceType: String,
        matches: (NsdServiceInfo) -> Boolean,
    ) {
        val manager = nsdManager ?: return
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.w("LAN discovery start failed (%d)", errorCode)
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (matches(info)) {
                        resolve(manager, info)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
            }
        discoveryListeners.add(serviceType to listener)
        runCatching { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Timber.w(it, "NSD discoverServices(%s) threw", serviceType) }
    }

    private fun resolve(
        manager: NsdManager,
        info: NsdServiceInfo,
    ) {
        runCatching {
            @Suppress("DEPRECATION")
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        Timber.d("LAN peer resolve failed (%d)", errorCode)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val ip = hostAddressOf(serviceInfo) ?: return
                        if (!LanAddresses.isReachableIpv4(ip)) return
                        report(ip, portOf(serviceInfo), LanSource.NSD, null)
                    }
                },
            )
        }.onFailure { Timber.d(it, "resolveService threw") }
    }

    /**
     * `getHost()` is deprecated in favour of `getHostAddresses()`, but the latter needs
     * T Extensions 7 while this app supports API 24, and the deprecated accessor still returns the
     * first address. Peers are verified over HTTP anyway, so a wrong address cannot join the list.
     */
    private fun hostAddressOf(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }

    /** NsdServiceInfo only carries the port from API 28; registration needs it too, so older
     * devices announce the default one and the HTTP probe confirms what is actually serving. */
    private fun portOf(info: NsdServiceInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.port.takeIf { it > 0 }
        } else {
            null
        } ?: LanProtocol.HTTP_PORT

    private fun unregisterNsd() {
        val manager = nsdManager ?: return
        registrationListeners.toList().forEach { listener -> runCatching { manager.unregisterService(listener) } }
        registrationListeners.clear()
        discoveryListeners.toList().forEach { (_, listener) -> runCatching { manager.stopServiceDiscovery(listener) } }
        discoveryListeners.clear()
    }

    // endregion

    private fun report(
        ip: String,
        port: Int,
        source: LanSource,
        info: LanPeerInfo?,
    ) {
        scope.launch {
            runCatching { onEndpoint(ip, port, source, info) }.onFailure { Timber.w(it, "endpoint handling failed") }
        }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
