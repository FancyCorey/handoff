package dev.handoff.app.ui

import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.model.AudioDeviceKind
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
        Ownership.Local -> HandoffTexts.HERE
        is Ownership.Peer, is Ownership.Multipoint -> where(o)
        is Ownership.Conflict -> where(o)
        Ownership.None -> "Not connected"
        is Ownership.Unknown -> when {
            o.device.localDeviceId == null -> "Not mapped on this device"
            o.localState == AudioConnectionState.UNAVAILABLE -> "Bluetooth unavailable"
            owner.lastKnownOwner != null -> "Not here · last seen on another device"
            else -> "Not connected here"
        }
    }

    private fun where(o: DeviceOverview) = HandoffTexts.whereConnected(o.holders, HandoffTexts.me)

    fun kind(kind: AudioDeviceKind): String = HandoffTexts.kind(kind)

    fun localState(state: AudioConnectionState): String = when (state) {
        AudioConnectionState.CONNECTED -> "Connected"
        AudioConnectionState.CONNECTING -> "Connecting…"
        AudioConnectionState.DISCONNECTING -> "Disconnecting…"
        AudioConnectionState.DISCONNECTED -> "Not connected"
        AudioConnectionState.UNAVAILABLE -> "Unavailable"
    }

    fun step(step: TransferStep): String = HandoffTexts.step(step)

    fun result(result: HandoffResult): String = HandoffTexts.result(result)

    fun help(result: HandoffResult): String? = HandoffTexts.help(result)

    fun offline(probablyOtherNetwork: Boolean): String = HandoffTexts.offline(probablyOtherNetwork)

    fun failure(reason: FailureReason): String = HandoffTexts.failure(reason)

    fun compatibility(level: CompatibilityLevel): String = HandoffTexts.compatibility(level)
}
