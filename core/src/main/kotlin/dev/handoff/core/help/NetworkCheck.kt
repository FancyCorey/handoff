package dev.handoff.core.help

import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.text.HandoffTexts

/**
 * Network problems Handoff can tell from what it already knows: this device isn't on Wi-Fi, or
 * the other device was last heard on a different network. Devices on two networks can't reach
 * each other, and that is the most common reason a move fails, so it is said plainly wherever
 * it applies (Home, the headphones' card, the move's result, Help and diagnostics).
 *
 * Handoff only knows the network a device was on when it last answered; if that device moved to
 * another network since, it looks like it simply isn't answering.
 */
sealed interface NetworkProblem {
    val title: String
    val help: String

    /** This device has no Wi-Fi (or Ethernet, or hotspot of its own). */
    data object NotOnWifi : NetworkProblem {
        override val title get() = HandoffTexts.notOnWifi()
        override val help get() = HandoffTexts.NOT_ON_WIFI_HELP
    }

    /**
     * A linked device was last heard on a different network than this one is on now. [nearbyOn]:
     * it could also be reached over Bluetooth nearby, but isn't nearby.
     */
    data class OtherWifi(val name: String, val nearbyOn: Boolean = false) : NetworkProblem {
        override val title get() = HandoffTexts.differentWifi(name)
        override val help get() = if (nearbyOn) HandoffTexts.NEAR_OR_SAME_WIFI else HandoffTexts.SAME_WIFI
    }
}

object NetworkCheck {
    /**
     * The network problem that matters for [peer] (or for this device alone, without one).
     * [nearbyOn]: this device reaches Android devices nearby over Bluetooth; one that is nearby is
     * online, so it has no problem.
     */
    fun problem(onNetwork: Boolean, peer: PeerOverview?, nearbyOn: Boolean = false): NetworkProblem? = when {
        !onNetwork -> NetworkProblem.NotOnWifi
        peer != null && !peer.online && peer.probablyOtherNetwork ->
            NetworkProblem.OtherWifi(peer.peer.displayName, nearbyOn && reachableNearby(peer))
        else -> null
    }

    /** Windows can't be reached over Bluetooth (yet). */
    fun reachableNearby(peer: PeerOverview): Boolean = peer.platform?.startsWith(Platforms.WINDOWS) != true

    /** The first network problem across all linked devices, for a notice on Home. */
    fun anyProblem(onNetwork: Boolean, peers: List<PeerOverview>, nearbyOn: Boolean = false): NetworkProblem? =
        problem(onNetwork, null) ?: peers.firstNotNullOfOrNull { problem(onNetwork, it, nearbyOn) }

    /**
     * The title and short reason for a failed move, naming the network problem when that is why
     * the other device didn't answer; otherwise the usual wording.
     */
    fun explain(result: HandoffResult.Failed, peer: PeerOverview?, onNetwork: Boolean, nearbyOn: Boolean = false): Pair<String, String?> {
        val network = problem(onNetwork, peer, nearbyOn).takeIf { result.reason == FailureReason.OWNER_UNREACHABLE || !onNetwork }
        return if (network != null) network.title to network.help else HandoffTexts.result(result) to HandoffTexts.help(result)
    }
}
