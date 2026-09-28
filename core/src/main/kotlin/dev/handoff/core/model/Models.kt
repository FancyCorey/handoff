package dev.handoff.core.model

import kotlinx.serialization.Serializable

/** Coarse classification of a bonded Bluetooth device, derived from its Bluetooth class. */
@Serializable
enum class AudioDeviceKind { HEADPHONES, HEADSET, SPEAKER, CAR_AUDIO, OTHER_AUDIO, UNKNOWN }

/** A device bonded with *this* host, as seen by the local Bluetooth stack. */
data class AudioDevice(
    val id: BluetoothDeviceId,
    val name: String,
    val kind: AudioDeviceKind,
    /** True when the device advertises the A2DP sink service or an audio device class. */
    val likelyA2dp: Boolean,
    val fingerprint: String,
)

/** Local A2DP connection state of one bonded device. */
enum class AudioConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,

    /** Bluetooth off, permission missing or A2DP profile proxy not available. */
    UNAVAILABLE,
}

/** Whether a host has this logical headset mapped to one of its bonded devices. */
data class HostMapping(
    val hostId: PeerId,
    /** Name the headset has on that host. */
    val alias: String,
)

/**
 * One physical headset as understood across every linked host.
 *
 * Each host stores its own copy. [localDeviceId] is this host's mapping to a bonded
 * Bluetooth device; it is never shared with peers.
 */
data class LogicalAudioDevice(
    val logicalId: LogicalDeviceId,
    val displayName: String,
    val deviceType: AudioDeviceKind,
    /** [DeviceFingerprint] of the headset address, when known. Used to match across hosts. */
    val fingerprint: String?,
    val localDeviceId: BluetoothDeviceId?,
    /** User-declared: the headset can hold more than one host connection at once. */
    val multipoint: Boolean = false,
    val lastKnownOwner: PeerId? = null,
    /** Monotonic ownership generation; the highest generation wins when hosts disagree. */
    val ownershipGeneration: Long = 0,
    /** Hosts (including this one) known to have this headset mapped. */
    val hostMappings: List<HostMapping> = emptyList(),
) {
    fun localBluetoothMapping(): BluetoothDeviceId? = localDeviceId

    fun isMappedOn(host: PeerId): Boolean = hostMappings.any { it.hostId == host }
}

/** This installation's public identity. */
data class DeviceIdentity(
    val peerId: PeerId,
    val displayName: String,
    /** X.509 SubjectPublicKeyInfo encoding of the P-256 identity key. */
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DeviceIdentity && other.peerId == peerId && other.displayName == displayName &&
            other.publicKey.contentEquals(publicKey)

    override fun hashCode(): Int = peerId.hashCode()
}

/** A peer the user explicitly linked with. Commands are only accepted from these. */
data class TrustedPeer(
    val peerId: PeerId,
    val displayName: String,
    val publicKey: ByteArray,
    val pairedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is TrustedPeer && other.peerId == peerId && other.displayName == displayName &&
            other.publicKey.contentEquals(publicKey) && other.pairedAtMs == pairedAtMs

    override fun hashCode(): Int = peerId.hashCode()
}
