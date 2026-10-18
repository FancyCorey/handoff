package dev.handoff.core.mesh.transport

import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.TrustedPeerRepository

/**
 * The short code a device puts in its Bluetooth beacon so linked devices nearby can recognize
 * it, while nobody else can follow it around.
 *
 * The code is derived from the device's identity public key and the current 15-minute slot. The
 * public key is only shared with linked devices, so only they can map a code back to a device;
 * to anyone else it is 8 random bytes that change every 15 minutes. Codes of the slots before
 * and after are accepted, for clocks that are a little off.
 */
class NearbyTags(
    private val trust: TrustedPeerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun currentSlot(): Long = Math.floorDiv(clock(), SLOT_MS)

    /** The code this device advertises now. */
    fun ownTag(publicKey: ByteArray): ByteArray = tagFor(publicKey, currentSlot())

    /** Milliseconds until the code changes, to advertise the next one on time. */
    fun msUntilNextSlot(): Long = SLOT_MS - Math.floorMod(clock(), SLOT_MS)

    fun nameOf(peerId: PeerId): String? = trust.find(peerId)?.displayName

    /** The linked device that advertises [tag], or null for anything else. */
    fun resolve(tag: ByteArray?): PeerId? {
        if (tag == null || tag.size < TAG_BYTES) return null
        val code = tag.copyOf(TAG_BYTES)
        val slot = currentSlot()
        return trust.peers.value.firstOrNull { peer ->
            (slot - 1..slot + 1).any { Crypto.constantTimeEquals(tagFor(peer.publicKey, it), code) }
        }?.peerId
    }

    companion object {
        const val TAG_BYTES = 8
        const val SLOT_MS = 15 * 60_000L

        fun tagFor(publicKey: ByteArray, slot: Long): ByteArray =
            Crypto.hmacSha256(publicKey, "handoff-ble-v1|$slot".toByteArray()).copyOf(TAG_BYTES)
    }
}
