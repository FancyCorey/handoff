package dev.handoff.desktop.mesh

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
 * `_handoff._tcp.local.`, instance `handoff-<peerId>`, TXT `id=<peerId>`, `v=1`.
 * Discovery only yields addresses; every connection still pins the peer's key.
 */
class JmdnsDiscovery(private val directory: PeerDirectory, private val events: EventLog) {
    private val instances = mutableListOf<JmDNS>()
    private var self: PeerId? = null

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            peerIdFromName(event.name)?.let { directory.onDiscoveryLost(it) }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val peer = info.getPropertyString("id")?.let(::PeerId) ?: peerIdFromName(info.name) ?: return
            if (peer == self) return
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
        for (address in lanAddresses()) {
            try {
                val dns = JmDNS.create(address, "handoff-" + selfId.short)
                dns.addServiceListener(TYPE, listener)
                dns.registerService(
                    ServiceInfo.create(TYPE, PREFIX + selfId.value, port, 0, 0, mapOf("id" to selfId.value, "v" to "1")),
                )
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

    private fun peerIdFromName(name: String?): PeerId? {
        if (name == null || !name.startsWith(PREFIX)) return null
        val id = name.removePrefix(PREFIX).take(36)
        return if (id.length == 36) PeerId(id) else null
    }

    companion object {
        const val TYPE = "_handoff._tcp.local."
        private const val PREFIX = "handoff-"
        private const val RESOLVE_TIMEOUT_MS = 3_000L
        private val VIRTUAL = listOf("virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "wsl", "loopback", "bluetooth", "tap", "vpn", "tailscale", "zerotier")

        /** Private IPv4 addresses on real, up interfaces (skips VM, VPN and Bluetooth adapters). */
        fun lanAddresses(): List<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .filter { nif -> VIRTUAL.none { nif.displayName.lowercase().contains(it) || nif.name.lowercase().contains(it) } }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
