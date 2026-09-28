package dev.handoff.core.text

import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferPath
import dev.handoff.core.handoff.TransferStep

/**
 * User-facing wording for transfer steps and results, shared by the Android and Windows apps so
 * both describe a handoff identically.
 */
object HandoffTexts {
    fun step(step: TransferStep): String {
        val peer = step.peerName ?: "the other device"
        return when (step.kind) {
            StepKind.REQUESTING_RELEASE -> "Requesting release from $peer…"
            StepKind.RELEASED -> "Released."
            StepKind.PEER_NOT_CONNECTED -> "$peer was not connected."
            StepKind.PEER_UNREACHABLE -> "$peer did not respond."
            StepKind.RELEASE_TIMEOUT -> "$peer did not respond in time."
            StepKind.RELEASE_REFUSED -> "$peer could not release it${step.detail?.let { " ($it)" } ?: ""}."
            StepKind.PEER_BUSY -> "$peer is handing it to another device."
            StepKind.DIRECT_TAKEOVER -> "Trying direct takeover…"
            StepKind.CONNECTING -> "Connecting…"
            StepKind.RETRYING -> "Retrying…"
            StepKind.VERIFYING -> "Verifying…"
            StepKind.CONNECTED -> "Connected."
            StepKind.ALREADY_CONNECTED -> "Already connected here."
            StepKind.FAILED -> "Unable to connect."
        }
    }

    fun result(result: HandoffResult): String = when (result) {
        is HandoffResult.Success -> when (result.path) {
            TransferPath.COORDINATED -> "Connected. Handed over cleanly."
            TransferPath.DIRECT_TAKEOVER -> "Connected by direct takeover."
            TransferPath.UNCONTESTED -> "Connected."
            TransferPath.MULTIPOINT_JOIN -> "Connected alongside the other devices."
        }
        HandoffResult.AlreadyConnected -> "Already connected here."
        HandoffResult.InProgress -> "A move is already in progress."
        HandoffResult.MissingLocalMapping -> "This headset isn't mapped on this device yet."
        is HandoffResult.Failed -> failure(result.reason)
    }

    fun failure(reason: FailureReason): String = when (reason) {
        FailureReason.MISSING_LOCAL_MAPPING -> "This headset isn't mapped on this device yet."
        FailureReason.BLUETOOTH_OFF -> "Bluetooth is off."
        FailureReason.PERMISSION_DENIED -> "Handoff needs the Nearby devices (Bluetooth) permission."
        FailureReason.UNSUPPORTED -> "This Android build doesn't allow apps to switch Bluetooth audio. Use Bluetooth settings instead."
        FailureReason.DEVICE_NOT_BONDED -> "The headset isn't paired with this device. Pair it in Bluetooth settings first."
        FailureReason.CONNECT_FAILED -> "The Bluetooth stack refused the connection."
        FailureReason.VERIFY_TIMEOUT -> "The headset didn't connect. Is it on and nearby?"
        FailureReason.CONTENTION -> "Another device is taking the headset right now."
        FailureReason.RATE_LIMITED -> "Too many attempts. Wait a minute and try again."
        FailureReason.INTERNAL -> "Something went wrong. See Diagnostics."
    }

    fun compatibility(level: CompatibilityLevel): String = when (level) {
        CompatibilityLevel.SUPPORTED -> "Supported"
        CompatibilityLevel.EXPERIMENTAL -> "Experimental"
        CompatibilityLevel.UNSUPPORTED -> "Unsupported on this Android build"
    }
}
