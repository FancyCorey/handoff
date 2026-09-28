package dev.handoff.core.model

import java.security.MessageDigest

/** Helpers that keep Bluetooth addresses and secrets out of logs and exports. */
object Redaction {
    private val MAC = Regex("(?i)\\b([0-9a-f]{2}[:-]){5}([0-9a-f]{2})\\b")

    /** `AA:BB:CC:DD:EE:FF` -> `**:**:**:**:EE:FF`. */
    fun address(address: String): String {
        val parts = address.split(':', '-')
        if (parts.size != 6) return "**redacted**"
        return "**:**:**:**:${parts[4].uppercase()}:${parts[5].uppercase()}"
    }

    /** Replace every Bluetooth-address-looking token inside free text. */
    fun scrub(text: String): String = MAC.replace(text) { address(it.value) }
}

/**
 * A one-way fingerprint of a headset's Bluetooth address.
 *
 * Classic (BR/EDR) audio devices expose the same public address to every host they are
 * bonded with, so two hosts can recognise "the same headset" by comparing fingerprints
 * without ever exchanging the raw address. The fingerprint travels only inside the
 * authenticated, encrypted peer channel.
 */
object DeviceFingerprint {
    private const val DOMAIN = "handoff-audio-device-v1|"

    fun of(address: String): String {
        val normalized = address.trim().uppercase().replace('-', ':')
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((DOMAIN + normalized).toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
