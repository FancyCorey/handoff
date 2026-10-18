package dev.handoff.core.help

import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.text.HandoffTexts

/**
 * "Help and diagnostics" in plain words (docs/DESIGN.md): what happened to the last move, what to
 * do about it, and what Handoff checked. Built from data Handoff already has, the same way on
 * Android and Windows, so both give the same advice.
 */
data class Help(
    val headline: Headline,
    /** Numbered steps, in order. Empty when there is nothing to do. */
    val steps: List<Step>,
    /** Offers [Fix.TRY_AGAIN] under the steps. */
    val tryAgain: Boolean,
    /** Checks that found something, the ones that explain the last failure first. */
    val problems: List<Check>,
    /** Checks that are fine. Shown folded while there are problems. */
    val fine: List<Check>,
    /** A suggestion unrelated to any failure, only when nothing is wrong. */
    val tip: Tip?,
)

data class Headline(val title: String, val reason: String?, val failed: Boolean, val atMs: Long?)

data class Step(val title: String, val detail: String? = null, val action: HelpAction? = null)

data class Check(val title: String, val detail: String? = null)

data class Tip(val title: String, val detail: String, val action: HelpAction)

/** What a button on the help screen does. */
enum class HelpAction(val label: String) {
    TURN_ON_BLUETOOTH(HandoffTexts.TURN_ON),
    ALLOW_BLUETOOTH("Allow"),
    OPEN_BLUETOOTH_SETTINGS("Open"),
    LINK_AGAIN("Link again"),
    ADD_HERE(HandoffTexts.ADD_HERE),
    STAY_REACHABLE("Change"),
    OPEN_WIFI_SETTINGS("Open"),
    HOTSPOT_SETTINGS("Hotspot"),
}

data class HelpInput(
    /** The most recent move, if any. */
    val lastMove: TransferRecord?,
    val adapter: AdapterState,
    val compatibility: CompatibilityLevel,
    /** Each pair of headphones added here, and whether it is set up in this device's Bluetooth. */
    val headphones: List<Pair<String, Boolean>>,
    val peers: List<PeerOverview>,
    val onNetwork: Boolean,
    /** Android: this device answers while locked. Null where that isn't a choice (Windows). */
    val staysReachable: Boolean?,
    val nowMs: Long,
)

object HelpBuilder {
    fun build(input: HelpInput): Help {
        val move = input.lastMove
        val failure = move?.failure?.takeIf { move.outcome != "SUCCESS" }
        val peerOfFailure = move?.previousOwner?.let { id -> input.peers.firstOrNull { it.peer.peerId == id } }
            ?: move?.detail?.let { name -> input.peers.firstOrNull { it.peer.displayName == name } }

        val problems = mutableListOf<Check>()
        val fine = mutableListOf<Check>()
        fun check(ok: Boolean, good: String, bad: String, detail: String? = null) {
            if (ok) fine += Check(good) else problems += Check(bad, detail)
        }

        // The device the last move depended on comes first.
        val peers = input.peers.sortedByDescending { it == peerOfFailure }
        peers.forEach { p ->
            val bad = if (p.probablyOtherNetwork) HandoffTexts.differentWifi(p.peer.displayName) else "Handoff on ${p.peer.displayName} isn't answering"
            check(p.online, "${p.peer.displayName} is answering", bad, lastAnswer(p, input.nowMs))
        }
        check(
            input.adapter == AdapterState.ON,
            "Bluetooth is on",
            if (input.adapter == AdapterState.NO_PERMISSION) "Handoff can't use Bluetooth" else HandoffTexts.BLUETOOTH_OFF,
        )
        input.headphones.forEach { (name, ready) ->
            check(ready, "$name are set up in ${HandoffTexts.meInSentence}'s Bluetooth", "$name aren't set up in ${HandoffTexts.meInSentence}'s Bluetooth")
        }
        check(input.compatibility != CompatibilityLevel.UNSUPPORTED, "${HandoffTexts.me} can move headphones", "${HandoffTexts.me} can't move headphones")
        check(input.onNetwork, "${HandoffTexts.me} is on Wi-Fi", "${HandoffTexts.me} isn't on Wi-Fi")

        if (failure == null) {
            val headline = when {
                problems.isEmpty() -> Headline("No problems found", move?.let { "Last move: ${it.deviceName} moved here." }, failed = false, atMs = move?.startedAtMs)
                else -> Headline("Some things need attention", null, failed = false, atMs = null)
            }
            val tip = if (problems.isEmpty() && input.staysReachable == false) {
                Tip("Tip: choose Stay reachable", "So your other devices can take the headphones while ${HandoffTexts.meInSentence} is locked.", HelpAction.STAY_REACHABLE)
            } else {
                null
            }
            return Help(headline, steps = emptyList(), tryAgain = false, problems = problems, fine = fine, tip = tip)
        }

        // A network problem explains an unanswered request better than anything else.
        val network = NetworkCheck.problem(input.onNetwork, peerOfFailure).takeIf { failure == FailureReason.OWNER_UNREACHABLE || !input.onNetwork }
        val headline = Headline(
            "${move.deviceName} didn't move",
            network?.let { "${it.title}." } ?: headlineReason(failure, peerOfFailure?.peer?.displayName ?: move.detail),
            failed = true,
            atMs = move.startedAtMs,
        )
        val steps = when (network) {
            NetworkProblem.NotOnWifi -> listOf(Step("Connect ${HandoffTexts.meInSentence} to Wi-Fi", action = HelpAction.OPEN_WIFI_SETTINGS))
            is NetworkProblem.OtherWifi -> listOf(
                Step("Connect both to the same Wi-Fi", "Or turn on the hotspot on one and join it from the other.", HelpAction.HOTSPOT_SETTINGS),
            ) + steps(failure, peerOfFailure, move.detail).filterNot { it.title == "Connect both to the same Wi-Fi" }
            null -> steps(failure, peerOfFailure, move.detail)
        }
        return Help(headline, steps, tryAgain = true, problems = problems, fine = fine, tip = null)
    }

    /** Why, in one sentence. The steps say what to do. */
    private fun headlineReason(failure: FailureReason, peer: String?): String? = when (failure) {
        FailureReason.OWNER_UNREACHABLE -> "${peer ?: "The other device"} didn't answer, so they're probably still connected to it."
        FailureReason.INTERNAL -> null
        else -> HandoffTexts.failureTitle(failure, peer) + "."
    }

    /** What to do, for the device the move depended on and the way it failed. */
    private fun steps(failure: FailureReason, peer: PeerOverview?, detail: String?): List<Step> {
        val name = peer?.peer?.displayName ?: detail ?: "the other device"
        return when (failure) {
            FailureReason.OWNER_UNREACHABLE -> when {
                peer?.online == true -> listOf(Step("$name is answering now"))
                peer?.platform == Platforms.WINDOWS -> listOfNotNull(
                    Step("On $name, start Handoff", "It stays running near the clock. To start it with Windows: Settings → Start with Windows."),
                    Step("If Windows asks about network access, choose Allow"),
                    Step("Connect both to the same Wi-Fi").takeIf { peer.probablyOtherNetwork },
                )
                else -> listOfNotNull(
                    Step("On $name, open Handoff", "So it answers even when locked: Settings → When this phone is locked → Stay reachable."),
                    Step("Connect both to the same Wi-Fi").takeIf { peer?.probablyOtherNetwork == true },
                )
            }
            FailureReason.OWNER_REFUSED -> listOf(Step("On $name, disconnect them in Bluetooth settings"))
            FailureReason.PEER_NOT_LINKED -> listOf(
                Step("Link ${HandoffTexts.meInSentence} and $name again", "Unlink $name in My devices first. Remove any older copy of Handoff.", HelpAction.LINK_AGAIN),
            )
            FailureReason.HEADSET_NOT_RESPONDING -> listOf(
                Step("Check the headphones are on, nearby and out of the case"),
                Step("Make sure no other phone or computer has them", "Devices without Handoff can't be asked to let go."),
            )
            FailureReason.MISSING_LOCAL_MAPPING -> listOf(Step("Add them on ${HandoffTexts.meInSentence}", action = HelpAction.ADD_HERE))
            FailureReason.BLUETOOTH_OFF -> listOf(Step("Turn on Bluetooth", action = HelpAction.TURN_ON_BLUETOOTH))
            FailureReason.PERMISSION_DENIED -> listOf(Step("Allow Handoff to use Nearby devices", action = HelpAction.ALLOW_BLUETOOTH))
            FailureReason.UNSUPPORTED -> listOf(
                Step("Connect them in Bluetooth settings instead", "${HandoffTexts.me} doesn't let apps connect headphones.", HelpAction.OPEN_BLUETOOTH_SETTINGS),
            )
            FailureReason.DEVICE_NOT_BONDED -> listOf(Step("Connect them once in Bluetooth settings", action = HelpAction.OPEN_BLUETOOTH_SETTINGS))
            FailureReason.CONTENTION -> listOf(Step("Wait a moment", "Another device was moving them at the same time."))
            FailureReason.RATE_LIMITED -> listOf(Step("Wait a minute"))
            FailureReason.INTERNAL -> listOf(Step("If it happens again, share a report"))
        }
    }

    /** "Last answer: 2 hours ago, on a different Wi-Fi." */
    private fun lastAnswer(p: PeerOverview, now: Long): String? {
        if (p.online) return null
        val at = p.lastContactMs ?: return "It hasn't answered yet."
        val ago = ago(now - at)
        return if (p.probablyOtherNetwork) "Last answer: $ago, on a different Wi-Fi." else "Last answer: $ago."
    }

    fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            minutes < 120 -> "1 hour ago"
            minutes < 48 * 60 -> "${minutes / 60} hours ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}
