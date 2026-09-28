package dev.handoff.app.ui

import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.TransferStep
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.text.HandoffTexts

/** All user-facing wording for domain states lives here, in one place. */
object Texts {
    fun ownership(o: DeviceOverview): String = when (val owner = o.ownership) {
        Ownership.Local -> "Connected: This device"
        is Ownership.Peer -> "Connected: ${o.holders.joinToString()}"
        is Ownership.Multipoint -> "Connected: ${o.holders.joinToString(" + ")}"
        is Ownership.Conflict -> "Reported on: ${o.holders.joinToString(" + ")}"
        Ownership.None -> "Not connected"
        is Ownership.Unknown -> when {
            o.device.localDeviceId == null -> "Not mapped on this device"
            o.localState == AudioConnectionState.UNAVAILABLE -> "Bluetooth unavailable"
            owner.lastKnownOwner != null -> "Not here · last seen on another device"
            else -> "Not connected here"
        }
    }

    /** Short status label + tone for the status pill. */
    fun status(o: DeviceOverview): Pair<String, Tone> = when (val owner = o.ownership) {
        Ownership.Local -> "Connected here" to Tone.POSITIVE
        is Ownership.Peer -> "On ${o.holders.firstOrNull() ?: "another device"}" to Tone.ACTIVE
        is Ownership.Multipoint -> "On ${o.holders.joinToString(" + ")}" to Tone.ACTIVE
        is Ownership.Conflict -> "Reported on ${o.holders.joinToString(" + ")}" to Tone.WARNING
        Ownership.None -> "Not connected" to Tone.NEUTRAL
        is Ownership.Unknown -> when {
            o.device.localDeviceId == null -> "Not set up on this device" to Tone.WARNING
            o.localState == AudioConnectionState.UNAVAILABLE -> "Bluetooth unavailable" to Tone.WARNING
            owner.lastKnownOwner != null -> "Last seen on another device" to Tone.NEUTRAL
            else -> "Not connected here" to Tone.NEUTRAL
        }
    }

    fun step(step: TransferStep): String = HandoffTexts.step(step)

    fun result(result: HandoffResult): String = HandoffTexts.result(result)

    fun help(result: HandoffResult): String? = HandoffTexts.help(result)

    fun offline(probablyOtherNetwork: Boolean): String = HandoffTexts.offline(probablyOtherNetwork)

    fun failure(reason: FailureReason): String = HandoffTexts.failure(reason)

    fun compatibility(level: CompatibilityLevel): String = HandoffTexts.compatibility(level)
}
