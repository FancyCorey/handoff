package dev.handoff.core.ownership

import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.PeerId

/**
 * Combines this host's authoritative local state with peers' reports into an [Ownership].
 *
 * Rules:
 *  - Local state is authoritative for this host; `null` means it could not be read.
 *  - A peer report counts only if it is fresh and the peer is currently online.
 *  - More than one holder is [Ownership.Multipoint] for multipoint headsets, otherwise
 *    [Ownership.Conflict]. Neither triggers automatic disconnection of other hosts.
 *  - "Nobody connected" is only [Ownership.None] when every host that maps the headset has
 *    reported; a silent host could still hold it, so that is [Ownership.Unknown].
 */
class OwnershipResolver(
    private val selfId: PeerId,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun resolve(
        device: LogicalAudioDevice,
        localConnected: Boolean?,
        reports: List<PeerReport>,
        onlinePeers: Set<PeerId>,
    ): Ownership {
        val now = clock()
        val fresh = reports.filter {
            it.logicalId == device.logicalId &&
                it.peerId != selfId &&
                it.peerId in onlinePeers &&
                now - it.receivedAtMs <= staleAfterMs
        }
        val holders = buildSet {
            if (localConnected == true) add(selfId)
            fresh.filter { it.connected }.forEach { add(it.peerId) }
        }

        return when {
            holders.size > 1 ->
                if (device.multipoint) Ownership.Multipoint(holders) else Ownership.Conflict(holders)
            holders.size == 1 -> {
                val holder = holders.single()
                if (holder == selfId) Ownership.Local else Ownership.Peer(holder)
            }
            localConnected == null -> Ownership.Unknown(device.lastKnownOwner)
            else -> {
                val reported = fresh.map { it.peerId }.toSet()
                val silentHosts = device.hostMappings
                    .map { it.hostId }
                    .filter { it != selfId && it !in reported }
                if (silentHosts.isEmpty()) {
                    Ownership.None
                } else {
                    Ownership.Unknown(device.lastKnownOwner?.takeIf { it in silentHosts })
                }
            }
        }
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 120_000L
    }
}
