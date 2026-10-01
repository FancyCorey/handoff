package dev.handoff.core.text

import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferPath
import dev.handoff.core.handoff.TransferStep

/**
 * User-facing wording for transfer steps and results, shared by the Android and Windows apps so
 * both describe a handoff identically. Every failure has a short title and a "what to do" line.
 */
object HandoffTexts {
    fun step(step: TransferStep): String {
        val peer = step.peerName ?: "the other device"
        return when (step.kind) {
            StepKind.REQUESTING_RELEASE -> "Asking $peer to let go…"
            StepKind.RELEASED -> "$peer let go."
            StepKind.PEER_NOT_CONNECTED -> "$peer wasn't connected to it."
            StepKind.PEER_UNREACHABLE -> "Couldn't reach $peer."
            StepKind.RELEASE_TIMEOUT -> "$peer didn't answer in time."
            StepKind.RELEASE_REFUSED -> "$peer couldn't let go${step.detail?.let { ": $it" } ?: ""}."
            StepKind.PEER_BUSY -> "$peer is handing it to another device."
            StepKind.DIRECT_TAKEOVER -> "Connecting directly instead…"
            StepKind.CONNECTING -> "Connecting…"
            StepKind.RETRYING -> "Trying again…"
            StepKind.VERIFYING -> "Checking the connection…"
            StepKind.CONNECTED -> "Connected."
            StepKind.ALREADY_CONNECTED -> "Already connected here."
            StepKind.AUTO_RETRY -> "That didn't work. Trying once more…"
            StepKind.CANCELLED -> "Cancelled."
            StepKind.FAILED -> "Couldn't connect."
        }
    }

    /** One-line headline for any result. */
    fun result(result: HandoffResult): String = when (result) {
        is HandoffResult.Success -> when (result.path) {
            TransferPath.COORDINATED -> "Connected. Handed over cleanly."
            TransferPath.DIRECT_TAKEOVER -> "Connected directly."
            TransferPath.UNCONTESTED -> "Connected."
            TransferPath.MULTIPOINT_JOIN -> "Connected alongside the other devices."
        }
        HandoffResult.AlreadyConnected -> "Already connected here."
        HandoffResult.InProgress -> "A move is already in progress."
        HandoffResult.MissingLocalMapping -> failureTitle(FailureReason.MISSING_LOCAL_MAPPING, null)
        HandoffResult.Cancelled -> "Move cancelled."
        is HandoffResult.Failed -> failureTitle(result.reason, result.detail)
    }

    /** What to do next, for results that need it. */
    fun help(result: HandoffResult): String? = when (result) {
        is HandoffResult.Failed -> failureHelp(result.reason, result.detail)
        HandoffResult.MissingLocalMapping -> failureHelp(FailureReason.MISSING_LOCAL_MAPPING, null)
        HandoffResult.Cancelled -> "If the other device had already let go, the headset is free: press Move here on the device you want."
        else -> null
    }

    fun failureTitle(reason: FailureReason, detail: String?): String = when (reason) {
        FailureReason.OWNER_UNREACHABLE -> "Couldn't reach ${detail ?: "the other device"}"
        FailureReason.OWNER_REFUSED -> "The other device couldn't let go"
        FailureReason.PEER_NOT_LINKED -> "${detail ?: "The other device"} doesn't recognise this device"
        FailureReason.HEADSET_NOT_RESPONDING -> "The headset didn't connect"
        FailureReason.MISSING_LOCAL_MAPPING -> "Not set up on this device"
        FailureReason.BLUETOOTH_OFF -> "Bluetooth is off"
        FailureReason.PERMISSION_DENIED -> "Bluetooth permission needed"
        FailureReason.UNSUPPORTED -> "This device can't switch headsets"
        FailureReason.DEVICE_NOT_BONDED -> "Not paired with this device"
        FailureReason.CONTENTION -> "Another device is taking it"
        FailureReason.RATE_LIMITED -> "Too many attempts"
        FailureReason.INTERNAL -> "Something went wrong"
    }

    fun failureHelp(reason: FailureReason, detail: String?): String = when (reason) {
        FailureReason.OWNER_UNREACHABLE ->
            "The headset is probably still connected to ${detail ?: "the other device"}. Open Handoff there (or turn on " +
                "Stay reachable in the background on it), and make sure both devices are on the same Wi-Fi. Or disconnect " +
                "the headset there yourself."
        FailureReason.OWNER_REFUSED ->
            (detail?.let { "$it. " } ?: "") + "Disconnect the headset in that device's Bluetooth settings, then try again."
        FailureReason.PEER_NOT_LINKED ->
            "The link between the two devices only exists on this side, so it can't ask ${detail ?: "the other device"} " +
                "to let go of the headset. In My devices, unlink it and link the two devices again. If an older copy " +
                "of Handoff is installed on either device, uninstall it first."
        FailureReason.HEADSET_NOT_RESPONDING ->
            "Make sure it's switched on and nearby, and not connected to a phone or computer that doesn't run Handoff. " +
                "Some earbuds only connect when they're out of the case."
        FailureReason.MISSING_LOCAL_MAPPING -> "Add this headset on this device first."
        FailureReason.BLUETOOTH_OFF -> "Turn Bluetooth on and try again."
        FailureReason.PERMISSION_DENIED -> "Allow Handoff to use Nearby devices (Bluetooth) in its app settings."
        FailureReason.UNSUPPORTED -> "This system doesn't let apps switch Bluetooth audio. Connect the headset from Bluetooth settings instead."
        FailureReason.DEVICE_NOT_BONDED -> "Pair the headset in Bluetooth settings first, then choose it again in Handoff."
        FailureReason.CONTENTION -> (detail?.let { "$it. " } ?: "") + "Wait for it to finish, then try again."
        FailureReason.RATE_LIMITED -> "Wait a minute, then try again."
        FailureReason.INTERNAL -> "Try again. If it keeps happening, share the Diagnostics report."
    }

    /** Kept for callers that want a single sentence. */
    fun failure(reason: FailureReason): String = failureTitle(reason, null)

    fun compatibility(level: CompatibilityLevel): String = when (level) {
        CompatibilityLevel.SUPPORTED -> "Supported"
        CompatibilityLevel.EXPERIMENTAL -> "Experimental"
        CompatibilityLevel.UNSUPPORTED -> "Not supported on this device"
    }

    /** "Offline" wording for a linked device, with a hint when it was last seen on another network. */
    /**
     * Where a headset is, from its holder names: "Here and on Tab", "On Tab and Studio PC".
     * [self] is how this device appears in [holders] ("This device", "This PC").
     */
    fun whereConnected(holders: List<String>, self: String): String {
        val others = holders.filter { it != self }
        val list = when (others.size) {
            0 -> ""
            1 -> others[0]
            else -> others.dropLast(1).joinToString(", ") + " and " + others.last()
        }
        return when {
            others.size == holders.size -> if (list.isEmpty()) "On another device" else "On $list"
            others.isEmpty() -> "Connected here"
            else -> "Here and on $list"
        }
    }

    /** A headset type as people say it. */
    fun kind(kind: AudioDeviceKind): String = when (kind) {
        AudioDeviceKind.HEADPHONES -> "Headphones"
        AudioDeviceKind.HEADSET -> "Headset"
        AudioDeviceKind.SPEAKER -> "Speaker"
        AudioDeviceKind.CAR_AUDIO -> "Car audio"
        AudioDeviceKind.OTHER_AUDIO, AudioDeviceKind.UNKNOWN -> "Audio device"
    }

    fun offline(probablyOtherNetwork: Boolean): String = if (probablyOtherNetwork) "On another network" else "Offline"

    /** The project's home: source code, releases and issues. */
    const val REPO_URL = "https://github.com/FancyCorey/handoff"

    /** Shown when approving a new link. */
    const val LINK_WARNING = "Only link devices you own and have in front of you. A linked device can move your " +
        "headphones and see their names and battery level. Nobody else ever needs this code."

    const val DIRECT_TITLE = "No shared Wi-Fi?"

    /** How to connect two devices directly, with no router and no internet involved. */
    const val DIRECT_HELP = "Turn on the hotspot on one device and join it from the other. Handoff finds your " +
        "devices on that local network by itself; mobile data is not needed and nothing goes over the internet."
}
