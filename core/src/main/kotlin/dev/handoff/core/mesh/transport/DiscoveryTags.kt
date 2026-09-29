package dev.handoff.core.mesh.transport

import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.TrustedPeerRepository

/**
 * Rotating mDNS instance names, so a device on the same network cannot follow a Handoff device
 * from day to day by a fixed identifier.
 *
 * Each device advertises `handoff-<tag>`, where the tag is derived from its identity public key
 * and the current UTC day. The public key is only shared with linked devices (the server never
 * reveals it to an unknown client), so only linked devices can map a tag back to a peer. The TXT
 * record carries no identifier. Tags of the previous and next day are accepted to tolerate clock
 * skew around midnight.
 */
class DiscoveryTags(
    private val trust: TrustedPeerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun currentDay(): Long = Math.floorDiv(clock(), DAY_MS)

    /** The tag this device advertises today. */
    fun ownTag(publicKey: ByteArray): String = tagFor(publicKey, currentDay())

    /** Service instance name to advertise today. */
    fun instanceName(publicKey: ByteArray): String = PREFIX + ownTag(publicKey)

    /**
     * Maps an advertised instance name to a linked peer, or null for anything else (unlinked
     * devices, this device, other services). Legacy `handoff-<peerId>` names from Handoff 0.3 and
     * earlier are still understood.
     */
    fun resolve(instanceName: String?): PeerId? {
        if (instanceName == null || !instanceName.startsWith(PREFIX)) return null
        val rest = instanceName.removePrefix(PREFIX)
        legacyPeerId(rest)?.let { id -> return id.takeIf { trust.find(it) != null } }
        val tag = rest.take(TAG_CHARS).lowercase()
        if (tag.length != TAG_CHARS) return null
        val today = currentDay()
        return trust.peers.value.firstOrNull { peer ->
            (today - 1..today + 1).any { day -> Crypto.constantTimeEquals(tagFor(peer.publicKey, day).toByteArray(), tag.toByteArray()) }
        }?.peerId
    }

    private fun legacyPeerId(rest: String): PeerId? {
        val id = rest.take(LEGACY_ID_CHARS)
        return if (id.length == LEGACY_ID_CHARS && LEGACY_ID.matches(id)) PeerId(id) else null
    }

    companion object {
        const val PREFIX = "handoff-"
        const val TAG_CHARS = 20
        private const val LEGACY_ID_CHARS = 36
        private const val DAY_MS = 86_400_000L
        private val LEGACY_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun tagFor(publicKey: ByteArray, day: Long): String =
            Crypto.hmacSha256(publicKey, "handoff-mdns-v1|$day".toByteArray())
                .take(TAG_CHARS / 2)
                .joinToString("") { "%02x".format(it) }
    }
}
