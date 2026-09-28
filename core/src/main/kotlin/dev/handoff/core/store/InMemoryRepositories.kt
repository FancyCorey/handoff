package dev.handoff.core.store

import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory implementations. Used by tests and as reference semantics for persistent ones. */
class InMemoryTrustedPeerRepository(initial: List<TrustedPeer> = emptyList()) : TrustedPeerRepository {
    private val state = MutableStateFlow(initial)
    override val peers: StateFlow<List<TrustedPeer>> = state.asStateFlow()

    override suspend fun upsert(peer: TrustedPeer) {
        state.update { list -> list.filterNot { it.peerId == peer.peerId } + peer }
    }

    override suspend fun remove(peerId: PeerId) {
        state.update { list -> list.filterNot { it.peerId == peerId } }
    }
}

class InMemoryLogicalDeviceRepository(
    initial: List<LogicalAudioDevice> = emptyList(),
) : LogicalDeviceRepository {
    private val state = MutableStateFlow(initial)
    override val devices: StateFlow<List<LogicalAudioDevice>> = state.asStateFlow()

    override suspend fun upsert(device: LogicalAudioDevice) {
        state.update { list -> list.filterNot { it.logicalId == device.logicalId } + device }
    }

    override suspend fun remove(logicalId: LogicalDeviceId) {
        state.update { list -> list.filterNot { it.logicalId == logicalId } }
    }

    override suspend fun rekey(from: LogicalDeviceId, to: LogicalDeviceId) {
        state.update { list ->
            val existing = list.firstOrNull { it.logicalId == from } ?: return@update list
            list.filterNot { it.logicalId == from || it.logicalId == to } + existing.copy(logicalId = to)
        }
    }

    override suspend fun applyOwnership(
        logicalId: LogicalDeviceId,
        owner: PeerId?,
        generation: Long,
    ): Boolean {
        var changed = false
        state.update { list ->
            list.map { device ->
                if (device.logicalId == logicalId && generation >= device.ownershipGeneration &&
                    (device.lastKnownOwner != owner || device.ownershipGeneration != generation)
                ) {
                    changed = true
                    device.copy(lastKnownOwner = owner, ownershipGeneration = generation)
                } else {
                    device
                }
            }
        }
        return changed
    }
}
