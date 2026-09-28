package dev.handoff.core.handoff

import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.protocol.Ack
import dev.handoff.core.mesh.protocol.DeviceReport
import dev.handoff.core.mesh.protocol.ErrorCode
import dev.handoff.core.mesh.protocol.ErrorReply
import dev.handoff.core.mesh.protocol.Hello
import dev.handoff.core.mesh.protocol.OwnershipChanged
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.ReleaseResult
import dev.handoff.core.mesh.protocol.StatusRequest
import dev.handoff.core.mesh.protocol.StatusResponse
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.transport.PeerRequestHandler
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.OwnershipRepository
import dev.handoff.core.ownership.PeerReport
import dev.handoff.core.store.LogicalDeviceRepository

/** Dispatches authenticated requests from trusted peers. Runs headless in the service. */
class HandoffRequestHandler(
    private val identity: IdentityProvider,
    private val devices: LogicalDeviceRepository,
    private val bluetooth: BluetoothAudioController,
    private val releaseHandler: AudioReleaseHandler,
    private val ownership: OwnershipRepository,
    private val events: EventLog,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** This host's platform label, announced to peers (see [Platforms]). */
    private val platform: String = Platforms.UNKNOWN,
) : PeerRequestHandler {

    override suspend fun handle(from: PeerId, message: PeerMessage): PeerMessage {
        val me = identity.identity()
        val self = me.peerId.value
        return when (message) {
            is Ping -> Pong(PeerMessage.newCommandId(), clock(), self, inReplyTo = message.commandId)
            is Hello -> Hello(
                commandId = PeerMessage.newCommandId(),
                timestamp = clock(),
                senderPeerId = self,
                displayName = me.displayName,
                appVersion = appVersion,
                capabilities = CAPABILITIES,
                inReplyTo = message.commandId,
                platform = platform,
            )
            is StatusRequest -> StatusResponse(
                commandId = PeerMessage.newCommandId(),
                timestamp = clock(),
                senderPeerId = self,
                inReplyTo = message.commandId,
                displayName = me.displayName,
                bluetoothEnabled = bluetooth.adapterState.value == AdapterState.ON,
                devices = localReports(),
                platform = platform,
            )
            is ReleaseAudioDevice -> {
                if (message.requestingPeerId != from.value) {
                    return error(message, ErrorCode.SENDER_MISMATCH, "requestingPeerId must be the sender")
                }
                val outcome = releaseHandler.release(LogicalDeviceId(message.logicalDeviceId), from)
                ReleaseResult(
                    commandId = PeerMessage.newCommandId(),
                    timestamp = clock(),
                    senderPeerId = self,
                    inReplyTo = message.commandId,
                    logicalDeviceId = message.logicalDeviceId,
                    status = outcome.status,
                    detail = outcome.detail,
                    releaseDurationMs = outcome.durationMs,
                )
            }
            is OwnershipChanged -> {
                val logicalId = LogicalDeviceId(message.logicalDeviceId)
                ownership.record(
                    PeerReport(
                        peerId = from,
                        logicalId = logicalId,
                        connected = message.senderConnected,
                        generation = message.generation,
                        receivedAtMs = clock(),
                    ),
                )
                message.ownerPeerId?.let { devices.applyOwnership(logicalId, PeerId(it), message.generation) }
                events.record(
                    EventType.OWNERSHIP_UPDATE_RECEIVED, logicalId, from,
                    mapOf("connected" to message.senderConnected.toString(), "generation" to message.generation.toString()),
                )
                Ack(PeerMessage.newCommandId(), clock(), self, inReplyTo = message.commandId)
            }
            else -> error(message, ErrorCode.UNSUPPORTED_TYPE, message::class.simpleName ?: "unknown")
        }
    }

    /** This host's view of every headset it has mapped locally. */
    suspend fun localReports(): List<DeviceReport> = devices.devices.value.mapNotNull { device ->
        val local = device.localBluetoothMapping() ?: return@mapNotNull null
        DeviceReport(
            logicalDeviceId = device.logicalId.value,
            displayName = device.displayName,
            deviceType = device.deviceType,
            fingerprint = device.fingerprint,
            connected = bluetooth.adapterState.value == AdapterState.ON && bluetooth.isConnected(local),
            generation = device.ownershipGeneration,
            multipoint = device.multipoint,
        )
    }

    private fun error(request: PeerMessage, code: ErrorCode, text: String) = ErrorReply(
        commandId = PeerMessage.newCommandId(),
        timestamp = clock(),
        senderPeerId = identity.identity().peerId.value,
        inReplyTo = request.commandId,
        code = code,
        message = text,
    )

    companion object {
        val CAPABILITIES = listOf("release-v1", "status-v1", "ownership-v1")
    }
}
