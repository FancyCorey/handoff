package dev.handoff.core.ownership

import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.StatusRequest
import dev.handoff.core.mesh.protocol.StatusResponse
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.PeerTransport
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a peer said about itself in its latest status reply. */
data class PeerInfo(val displayName: String, val platform: String?, val bluetoothEnabled: Boolean, val seenAtMs: Long)

/** [OwnershipRepository] that refreshes by sending STATUS_REQUEST to every linked peer. */
class MeshOwnershipRepository(
    private val selfId: PeerId,
    private val transport: PeerTransport,
    private val peers: () -> Collection<PeerId>,
    private val clock: () -> Long = System::currentTimeMillis,
) : PeerReportStore() {
    private val _peerInfo = MutableStateFlow<Map<PeerId, PeerInfo>>(emptyMap())

    /** Latest self-description of each peer (name, platform), from STATUS_RESPONSE. */
    val peerInfo: StateFlow<Map<PeerId, PeerInfo>> = _peerInfo.asStateFlow()

    override suspend fun refresh(timeoutMs: Long) {
        coroutineScope {
            peers().filter { it != selfId }.map { peer -> async { refreshPeer(peer, timeoutMs) } }.awaitAll()
        }
    }

    /** Returns the peer's status response, or null if it could not be reached. */
    suspend fun refreshPeer(peer: PeerId, timeoutMs: Long): StatusResponse? {
        val request = StatusRequest(PeerMessage.newCommandId(), clock(), selfId.value)
        val result = try {
            transport.request(peer, request, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val response = (result as? CommandResult.Reply)?.message as? StatusResponse ?: return null
        val now = clock()
        _peerInfo.update { it + (peer to PeerInfo(response.displayName, response.platform, response.bluetoothEnabled, now)) }
        recordSnapshot(
            peer,
            response.devices.map {
                PeerReport(
                    peerId = peer,
                    logicalId = LogicalDeviceId(it.logicalDeviceId),
                    connected = it.connected,
                    generation = it.generation,
                    receivedAtMs = now,
                    displayName = it.displayName,
                    fingerprint = it.fingerprint,
                    deviceType = it.deviceType,
                    batteryPercent = it.batteryPercent,
                )
            },
        )
        return response
    }
}
