package dev.handoff.core.mesh.transport

import dev.handoff.core.model.PeerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps each linked device's last working address per network across restarts, in this app's
 * private storage. Addresses never leave the device; they only let a restarted app reach linked
 * devices straight away instead of waiting for mDNS (which some networks block).
 */
class EndpointMemory(private val file: File) {
    @Serializable
    private data class Entry(val peerId: String, val network: String, val host: String, val port: Int, val updatedAtMs: Long)

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

    /** Restores into [directory] now, then saves whenever a new working address is learned. */
    @OptIn(FlowPreview::class)
    fun attach(directory: PeerDirectory, scope: CoroutineScope, localNetworks: () -> Set<String>): Job {
        directory.restore(load(), localNetworks())
        return scope.launch {
            directory.presence
                .map { directory.knownNetworks() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SAVE_DEBOUNCE_MS)
                .collect(::save)
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 2_000L
    }
}
