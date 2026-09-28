package dev.handoff.core.store

import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import kotlinx.coroutines.flow.StateFlow

/** Persistent trust store. Only peers in here may open a session. */
interface TrustedPeerRepository {
    val peers: StateFlow<List<TrustedPeer>>

    fun find(peerId: PeerId): TrustedPeer? = peers.value.firstOrNull { it.peerId == peerId }

    suspend fun upsert(peer: TrustedPeer)

    suspend fun remove(peerId: PeerId)
}

/** This host's logical headsets and their local mappings. */
interface LogicalDeviceRepository {
    val devices: StateFlow<List<LogicalAudioDevice>>

    fun find(logicalId: LogicalDeviceId): LogicalAudioDevice? =
        devices.value.firstOrNull { it.logicalId == logicalId }

    suspend fun upsert(device: LogicalAudioDevice)

    suspend fun remove(logicalId: LogicalDeviceId)

    /** Atomically change a device's logical id (used when two hosts converge on one id). */
    suspend fun rekey(from: LogicalDeviceId, to: LogicalDeviceId)

    /**
     * Record a new owner if [generation] is not older than the stored one.
     * Returns true if the record changed.
     */
    suspend fun applyOwnership(logicalId: LogicalDeviceId, owner: PeerId?, generation: Long): Boolean
}
