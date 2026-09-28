package dev.handoff.core.mesh.transport

import dev.handoff.core.model.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ordered by trust: earlier sources are tried first. */
enum class EndpointSource {
    LAST_SUCCESS,
    DISCOVERY,
    PAIRING,

    /** Source address of an authenticated inbound session, with the peer's default listen port. */
    INBOUND,
}

data class PeerEndpoint(
    val host: String,
    val port: Int,
    val source: EndpointSource,
    val updatedAtMs: Long,
)

data class PeerPresence(
    val peerId: PeerId,
    val online: Boolean,
    /** Currently advertised on the LAN via mDNS/NSD. */
    val discovered: Boolean,
    val lastContactMs: Long?,
    val lastFailureMs: Long?,
    val endpoints: List<PeerEndpoint>,
    /** The most recent contact attempt failed and nothing positive has happened since. */
    val failing: Boolean = false,
)

/**
 * Where trusted peers can be reached and whether they look online.
 *
 * Fed by LAN discovery (NSD), by the pairing QR and by the outcome of every request. The
 * address from the last successful contact is tried first, so a peer stays reachable even when
 * mDNS is flaky on a given Wi-Fi network.
 */
class PeerDirectory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onlineTtlMs: Long = 90_000,
) {
    private val state = MutableStateFlow<Map<PeerId, PeerPresence>>(emptyMap())
    val presence: StateFlow<Map<PeerId, PeerPresence>> = state.asStateFlow()

    fun onDiscovered(peerId: PeerId, host: String, port: Int) = mutate(peerId) {
        it.copy(
            discovered = true,
            failing = false,
            endpoints = it.endpoints.upsert(PeerEndpoint(host, port, EndpointSource.DISCOVERY, clock())),
        )
    }

    fun onDiscoveryLost(peerId: PeerId) = mutate(peerId) { it.copy(discovered = false) }

    fun onPairingEndpoint(peerId: PeerId, host: String, port: Int) = mutate(peerId) {
        it.copy(endpoints = it.endpoints.upsert(PeerEndpoint(host, port, EndpointSource.PAIRING, clock())))
    }

    fun onContactSucceeded(peerId: PeerId, host: String, port: Int) = mutate(peerId) {
        it.copy(
            lastContactMs = clock(),
            failing = false,
            endpoints = it.endpoints.upsert(PeerEndpoint(host, port, EndpointSource.LAST_SUCCESS, clock())),
        )
    }

    /**
     * The peer opened an authenticated session to us, which proves it is online. Its source
     * port is ephemeral, but its address with the default listen port is a useful fallback
     * endpoint when mDNS is unreliable on the network.
     */
    fun onInboundContact(peerId: PeerId, host: String? = null, listenPort: Int? = null) = mutate(peerId) {
        val endpoints = if (host != null && listenPort != null) {
            it.endpoints.upsert(PeerEndpoint(host, listenPort, EndpointSource.INBOUND, clock()))
        } else {
            it.endpoints
        }
        it.copy(lastContactMs = clock(), failing = false, endpoints = endpoints)
    }

    fun onContactFailed(peerId: PeerId) = mutate(peerId) { it.copy(lastFailureMs = clock(), failing = true) }

    fun forget(peerId: PeerId) = state.update { it - peerId }

    /**
     * This host joined a different network (Wi-Fi change, Ethernet plugged in, hotspot...).
     * Every address learned so far belongs to the old network, so drop them all and wait for
     * discovery on the new one. Trust is unaffected: links are bound to identity keys, not to a
     * network, so peers reappear as soon as they are found on the new network.
     */
    fun onNetworkChanged() = state.update { all ->
        all.mapValues { (_, p) ->
            val next = p.copy(discovered = false, failing = false, lastContactMs = null, endpoints = emptyList())
            next.copy(online = computeOnline(next))
        }
    }

    /** Candidate endpoints, most trustworthy first, de-duplicated by host:port. */
    fun endpointsFor(peerId: PeerId): List<PeerEndpoint> {
        val endpoints = state.value[peerId]?.endpoints.orEmpty()
        return endpoints
            .sortedWith(compareBy<PeerEndpoint> { it.source.ordinal }.thenByDescending { it.updatedAtMs })
            .distinctBy { "${it.host}:${it.port}" }
    }

    fun isOnline(peerId: PeerId): Boolean = state.value[peerId]?.let(::computeOnline) ?: false

    fun onlinePeers(): Set<PeerId> = state.value.values.filter(::computeOnline).map { it.peerId }.toSet()

    /** Recompute time-based online flags (call periodically while the UI is visible). */
    fun refreshOnlineFlags() = state.update { all -> all.mapValues { (_, p) -> p.copy(online = computeOnline(p)) } }

    private fun computeOnline(p: PeerPresence): Boolean {
        val now = clock()
        val recentContact = p.lastContactMs != null && now - p.lastContactMs <= onlineTtlMs
        return !p.failing && (recentContact || p.discovered)
    }

    private fun mutate(peerId: PeerId, change: (PeerPresence) -> PeerPresence) {
        state.update { all ->
            val current = all[peerId] ?: PeerPresence(peerId, false, false, null, null, emptyList())
            val next = change(current)
            all + (peerId to next.copy(online = computeOnline(next)))
        }
    }

    private fun List<PeerEndpoint>.upsert(endpoint: PeerEndpoint): List<PeerEndpoint> =
        (filterNot { it.source == endpoint.source } + endpoint)
}
