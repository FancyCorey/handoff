package dev.handoff.desktop.mesh

import dev.handoff.core.mesh.transport.DiscoveryTags
import java.util.Timer
import kotlin.concurrent.timerTask
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * mDNS/DNS-SD for Windows via JmDNS, wire-compatible with Android's NsdManager:
 * `_handoff._tcp.local.`, instance `handoff-<daily tag>` (see [DiscoveryTags]), TXT `v=2`.
 * Nothing advertised identifies this PC for longer than a day, and only linked devices are
 * resolved. Discovery only yields addresses; every connection still pins the peer's key.
 */
class JmdnsDiscovery(
    private val directory: PeerDirectory,
    private val events: EventLog,
    private val tags: DiscoveryTags,
    private val ownKey: () -> ByteArray,
) {
    private val instances = mutableListOf<JmDNS>()
    private var self: PeerId? = null
    private var port = 0
    private var advertisedDay = 0L
    private val rotation = Timer("handoff-mdns-rotation", true).apply {
        schedule(timerTask { rotateIfNewDay() }, ROTATION_CHECK_MS, ROTATION_CHECK_MS)
    }

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            tags.resolve(event.name)?.let { directory.onDiscoveryLost(it) }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val peer = tags.resolve(info.name) ?: return
            val address = info.inet4Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull() ?: return
            val wasOnline = directory.isOnline(peer)
            directory.onDiscovered(peer, address.hostAddress, info.port)
            if (!wasOnline) events.record(EventType.PEER_ONLINE, peerId = peer, details = mapOf("source" to "mdns"))
        }
    }

    /** Blocking (JmDNS probes the network); call from a background thread. */
    @Synchronized
    fun start(selfId: PeerId, port: Int) {
        stop()
        self = selfId
        this.port = port
        advertisedDay = tags.currentDay()
        val name = tags.instanceName(ownKey())
        for (address in lanAddresses()) {
            try {
                // The mDNS host name is advertised too, so it uses the rotating name as well.
                val dns = JmDNS.create(address, name)
                dns.addServiceListener(TYPE, listener)
                dns.registerService(ServiceInfo.create(TYPE, name, port, 0, 0, mapOf("v" to "2")))
                instances += dns
            } catch (_: Exception) {
                // One bad interface must not stop discovery on the others.
            }
        }
    }

    @Synchronized
    fun stop() {
        instances.forEach { dns ->
            runCatching {
                dns.unregisterAllServices()
                dns.close()
            }
        }
        instances.clear()
    }

    /** The advertised tag changes once a day; re-register under the new name. */
    @Synchronized
    private fun rotateIfNewDay() {
        val selfId = self ?: return
        if (instances.isNotEmpty() && tags.currentDay() != advertisedDay) start(selfId, port)
    }

    companion object {
        const val TYPE = "_handoff._tcp.local."
        private const val RESOLVE_TIMEOUT_MS = 3_000L
        private const val ROTATION_CHECK_MS = 10 * 60_000L
        private val VIRTUAL = listOf("virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "wsl", "loopback", "bluetooth", "tap", "vpn", "tailscale", "zerotier")

        /** Windows Mobile Hotspot runs on a "Wi-Fi Direct Virtual Adapter": a real local network, not a VM. */
        private const val HOTSPOT = "wi-fi direct"

        /**
         * Private IPv4 addresses on real, up interfaces, including this PC's own Mobile Hotspot
         * (skips VM, VPN and Bluetooth adapters).
         */
        fun lanAddresses(): List<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .filter { nif ->
                    val names = listOf(nif.displayName.lowercase(), nif.name.lowercase())
                    names.any { HOTSPOT in it } || VIRTUAL.none { v -> names.any { v in it } }
                }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
