package dev.handoff.core.mesh.security

import dev.handoff.core.model.DeviceIdentity
import dev.handoff.core.model.PeerId
import java.security.KeyPair

/**
 * This installation's long-term identity. On Android the private key lives in the Android
 * Keystore and never leaves it; only [sign] is exposed.
 */
interface IdentityProvider {
    fun identity(): DeviceIdentity

    fun sign(data: ByteArray): ByteArray
}

/** Software key identity for tests and JVM tooling. Not used by the Android app. */
class SoftwareIdentityProvider(
    private val peerId: PeerId = PeerId.random(),
    private var name: String = "Test device",
    private val keyPair: KeyPair = Crypto.generateEcKeyPair(),
) : IdentityProvider {
    override fun identity(): DeviceIdentity = DeviceIdentity(peerId, name, keyPair.public.encoded)

    override fun sign(data: ByteArray): ByteArray = Crypto.signWith(keyPair.private, data)

    fun rename(newName: String) {
        name = newName
    }
}
