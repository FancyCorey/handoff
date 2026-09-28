package dev.handoff.core.ownership

import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId

/**
 * Handoff's best-known view of which host holds a logical headset.
 * Bluetooth has no global owner concept; this is derived from local state plus peer reports.
 */
sealed interface Ownership {
    /** Not enough fresh information. [lastKnownOwner] is a hint only. */
    data class Unknown(val lastKnownOwner: PeerId?) : Ownership

    /** Every host that maps the headset reported "not connected". */
    data object None : Ownership

    data object Local : Ownership

    data class Peer(val peerId: PeerId) : Ownership

    /** Several hosts are connected and the headset is declared multipoint. */
    data class Multipoint(val holders: Set<PeerId>) : Ownership

    /** Several hosts claim a single-point headset; at least one report is stale or racing. */
    data class Conflict(val holders: Set<PeerId>) : Ownership
}

/** One peer's statement about one logical headset, stamped with the local receive time. */
data class PeerReport(
    val peerId: PeerId,
    val logicalId: LogicalDeviceId,
    val connected: Boolean,
    val generation: Long,
    val receivedAtMs: Long,
    val displayName: String? = null,
    val fingerprint: String? = null,
    val deviceType: AudioDeviceKind = AudioDeviceKind.UNKNOWN,
)
