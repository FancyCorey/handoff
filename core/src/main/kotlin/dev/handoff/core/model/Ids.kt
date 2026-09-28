package dev.handoff.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Identity of one Handoff installation (a "host"). Stable for the lifetime of the install. */
@Serializable
@JvmInline
value class PeerId(val value: String) {
    override fun toString(): String = value

    /** Short, log-friendly form. Not a secret; used to keep logs readable. */
    val short: String get() = value.take(8)

    companion object {
        fun random(): PeerId = PeerId(UUID.randomUUID().toString())
    }
}

/** Identity of a logical headset shared by all hosts that map it. */
@Serializable
@JvmInline
value class LogicalDeviceId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): LogicalDeviceId = LogicalDeviceId(UUID.randomUUID().toString())
    }
}

/**
 * The host-local identifier of a bonded Bluetooth device (its Bluetooth address on Android).
 *
 * This value is sensitive: it never leaves the host in plaintext. Use [DeviceFingerprint]
 * for cross-host matching and [Redaction] for logs.
 */
@JvmInline
value class BluetoothDeviceId(val address: String) {
    override fun toString(): String = Redaction.address(address)
}
