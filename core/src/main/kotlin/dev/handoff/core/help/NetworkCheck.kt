package dev.handoff.core.help

import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
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

    /** A linked device was last heard on a different network than this one is on now. */
    data class OtherWifi(val name: String) : NetworkProblem {
        override val title get() = HandoffTexts.differentWifi(name)
        override val help get() = HandoffTexts.SAME_WIFI
    }
}

object NetworkCheck {
    /** The network problem that matters for [peer] (or for this device alone, without one). */
    fun problem(onNetwork: Boolean, peer: PeerOverview?): NetworkProblem? = when {
        !onNetwork -> NetworkProblem.NotOnWifi
        peer != null && !peer.online && peer.probablyOtherNetwork -> NetworkProblem.OtherWifi(peer.peer.displayName)
        else -> null
    }

    /** The first network problem across all linked devices, for a notice on Home. */
    fun anyProblem(onNetwork: Boolean, peers: List<PeerOverview>): NetworkProblem? =
        problem(onNetwork, null) ?: peers.firstNotNullOfOrNull { problem(onNetwork, it) }

    /**
     * The title and short reason for a failed move, naming the network problem when that is why
     * the other device didn't answer; otherwise the usual wording.
     */
    fun explain(result: HandoffResult.Failed, peer: PeerOverview?, onNetwork: Boolean): Pair<String, String?> {
        val network = problem(onNetwork, peer).takeIf { result.reason == FailureReason.OWNER_UNREACHABLE || !onNetwork }
        return if (network != null) network.title to network.help else HandoffTexts.result(result) to HandoffTexts.help(result)
    }
}
