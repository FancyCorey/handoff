package dev.handoff.core.mesh.transport

import dev.handoff.core.model.PeerId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps each linked device's last working address per network across restarts, in this app's
 * private storage. Addresses never leave the device; they only let a restarted app reach linked
 * devices straight away instead of waiting for mDNS (which some networks block).
 */
class EndpointMemory(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private data class Entry(val peerId: String, val network: String, val host: String, val port: Int, val updatedAtMs: Long)

    private data class Address(val network: String, val host: String, val port: Int)

    private val serializer = ListSerializer(Entry.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<PeerId, Map<String, PeerEndpoint>> = try {
        if (!file.exists()) {
            emptyMap()
        } else {
            json.decodeFromString(serializer, file.readText()).groupBy { PeerId(it.peerId) }.mapValues { (_, entries) ->
                entries.associate { it.network to PeerEndpoint(it.host, it.port, EndpointSource.LAST_SUCCESS, it.updatedAtMs) }
            }
        }
    } catch (_: Exception) {
        emptyMap() // A damaged file only costs one discovery round.
    }

    fun save(known: Map<PeerId, Map<String, PeerEndpoint>>) {
        val entries = known.flatMap { (peer, networks) ->
            networks.map { (network, e) -> Entry(peer.value, network, e.host, e.port, e.updatedAtMs) }
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, entries))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /** Restores into [directory] now, then saves when a working address or its priority changes. */
    @OptIn(FlowPreview::class)
    fun attach(directory: PeerDirectory, scope: CoroutineScope, localNetworks: () -> Set<String>): Job {
        directory.restore(load(), localNetworks())
        val baseline = directory.knownNetworks()
        return scope.launch {
            directory.presence
                .map { peers -> peers.mapValues { it.value.knownNetworks }.filterValues { it.isNotEmpty() } }
                // Supply the baseline explicitly: the first live emission may already know a new address.
                .onStart { emit(baseline) }
                .distinctUntilChangedBy(::addressOrder)
                .drop(1)
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { known -> withContext(io) { save(known) } }
        }
    }

    /** Restoration uses relative recency to choose and prioritize addresses. */
    private fun addressOrder(known: Map<PeerId, Map<String, PeerEndpoint>>): Map<PeerId, List<Address>> =
        known.mapValues { (_, networks) ->
            networks.entries.sortedByDescending { it.value.updatedAtMs }
                .map { (network, endpoint) -> Address(network, endpoint.host, endpoint.port) }
        }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 2_000L
    }
}
