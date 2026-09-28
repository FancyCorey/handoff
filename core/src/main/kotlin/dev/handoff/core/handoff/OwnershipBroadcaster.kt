package dev.handoff.core.handoff

import dev.handoff.core.mesh.protocol.OwnershipChanged
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.PeerTransport
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Pushes this host's ownership/connection changes to linked peers. Best effort, bounded. */
class OwnershipBroadcaster(
    private val selfId: PeerId,
    private val transport: PeerTransport,
    private val targets: () -> Collection<PeerId>,
    private val timeoutMs: Long = 3_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns the peers that acknowledged. */
    suspend fun announce(
        logicalId: LogicalDeviceId,
        senderConnected: Boolean,
        owner: PeerId?,
        generation: Long,
    ): Set<PeerId> = coroutineScope {
        targets().filter { it != selfId }.map { peer ->
            async {
                val message = OwnershipChanged(
                    commandId = PeerMessage.newCommandId(),
                    timestamp = clock(),
                    senderPeerId = selfId.value,
                    logicalDeviceId = logicalId.value,
                    senderConnected = senderConnected,
                    ownerPeerId = owner?.value,
                    generation = generation,
                )
                peer.takeIf { transport.request(peer, message, timeoutMs) is CommandResult.Reply }
            }
        }.awaitAll().filterNotNull().toSet()
    }
}
