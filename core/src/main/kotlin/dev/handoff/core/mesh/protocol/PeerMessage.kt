package dev.handoff.core.mesh.protocol

import dev.handoff.core.model.AudioDeviceKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

const val PROTOCOL_VERSION = 1

/**
 * Application messages exchanged between trusted peers. They only ever travel inside an
 * authenticated, encrypted session (see `mesh.security`); the JSON form is never on the wire
 * in plaintext.
 *
 * Every message carries a unique [commandId] and a sender [timestamp] for replay protection
 * and request/response correlation. Responses set `inReplyTo` to the request's [commandId].
 */
@Serializable
sealed class PeerMessage {
    abstract val protocolVersion: Int
    abstract val commandId: String
    abstract val timestamp: Long
    abstract val senderPeerId: String

    companion object {
        fun newCommandId(): String = UUID.randomUUID().toString()
    }
}

/** Messages that expect a correlated reply. */
sealed interface PeerRequest

/** Messages that are replies. */
sealed interface PeerReply {
    val inReplyTo: String
}

@Serializable
@SerialName("HELLO")
data class Hello(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    val displayName: String,
    val appVersion: String,
    val capabilities: List<String> = emptyList(),
    /** Set when this HELLO answers another HELLO. */
    val inReplyTo: String? = null,
    /** e.g. "android-phone", "android-tablet", "windows". Optional; added in 0.2. */
    val platform: String? = null,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerRequest

@Serializable
@SerialName("STATUS_REQUEST")
data class StatusRequest(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerRequest

/** What a host knows about one logical headset it has mapped. */
@Serializable
data class DeviceReport(
    val logicalDeviceId: String,
    val displayName: String,
    val deviceType: AudioDeviceKind,
    val fingerprint: String?,
    val connected: Boolean,
    val generation: Long,
    val multipoint: Boolean,
    /** 0..100 when this host is connected and the headset reports it. Optional; added in 0.2. */
    val batteryPercent: Int? = null,
)

@Serializable
@SerialName("STATUS_RESPONSE")
data class StatusResponse(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val inReplyTo: String,
    val displayName: String,
    val bluetoothEnabled: Boolean,
    val devices: List<DeviceReport>,
    /** e.g. "android-phone", "android-tablet", "windows". Optional; added in 0.2. */
    val platform: String? = null,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerReply

@Serializable
@SerialName("RELEASE_AUDIO_DEVICE")
data class ReleaseAudioDevice(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    val logicalDeviceId: String,
    val requestingPeerId: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerRequest

@Serializable
enum class ReleaseStatus {
    RELEASED,
    NOT_CONNECTED,
    DEVICE_UNKNOWN,
    PERMISSION_DENIED,
    UNSUPPORTED,
    FAILED,
    TIMEOUT,
    BUSY,
}

@Serializable
@SerialName("RELEASE_RESULT")
data class ReleaseResult(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val inReplyTo: String,
    val logicalDeviceId: String,
    val status: ReleaseStatus,
    val detail: String? = null,
    /** Time the releasing host spent disconnecting and verifying. */
    val releaseDurationMs: Long? = null,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerReply

@Serializable
@SerialName("OWNERSHIP_CHANGED")
data class OwnershipChanged(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    val logicalDeviceId: String,
    /** The sender's current local connection state for the headset. */
    val senderConnected: Boolean,
    /** Who the sender believes owns the headset now (null = nobody). */
    val ownerPeerId: String?,
    val generation: Long,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerRequest

@Serializable
@SerialName("PING")
data class Ping(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerRequest

@Serializable
@SerialName("PONG")
data class Pong(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val inReplyTo: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerReply

@Serializable
@SerialName("ACK")
data class Ack(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val inReplyTo: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerReply

@Serializable
enum class ErrorCode { UNSUPPORTED_VERSION, STALE, DUPLICATE, SENDER_MISMATCH, MALFORMED, UNSUPPORTED_TYPE, INTERNAL }

@Serializable
@SerialName("ERROR")
data class ErrorReply(
    override val commandId: String,
    override val timestamp: Long,
    override val senderPeerId: String,
    override val inReplyTo: String,
    val code: ErrorCode,
    val message: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : PeerMessage(), PeerReply
