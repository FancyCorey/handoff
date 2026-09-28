package dev.handoff.core.handoff

import dev.handoff.core.ownership.Ownership

enum class AutoSwitchMode { OFF, ASK, AUTO }

/**
 * Decides what to do when media playback starts on this host (Milestone 7, optional).
 * The decision never performs a switch itself: [Decision.Switch] means "call the normal
 * [HandoffCoordinator]", so automatic switching shares every safeguard of manual handoff.
 *
 * Anti-ping-pong rules:
 *  - Nothing happens if this host released the headset to a peer within [recentReleaseMs]
 *    (audio playing on both devices would otherwise bounce the headset back and forth).
 *  - At most one prompt/switch per [cooldownMs].
 *  - Never for multipoint or conflicting ownership; those need a human decision.
 */
class AutoSwitchPolicy(
    private val cooldownMs: Long = 30_000,
    private val recentReleaseMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Decision {
        data class Ignore(val reason: String) : Decision
        data object Ask : Decision
        data object Switch : Decision
    }

    @Volatile private var lastTriggerMs: Long? = null

    fun onPlaybackStarted(
        mode: AutoSwitchMode,
        hasPreferredDevice: Boolean,
        localConnected: Boolean,
        ownership: Ownership,
        lastReleaseToPeerMs: Long?,
    ): Decision {
        val now = clock()
        return when {
            mode == AutoSwitchMode.OFF -> Decision.Ignore("off")
            !hasPreferredDevice -> Decision.Ignore("no preferred headset")
            localConnected || ownership == Ownership.Local -> Decision.Ignore("already here")
            ownership is Ownership.Multipoint || ownership is Ownership.Conflict -> Decision.Ignore("multiple hosts connected")
            lastReleaseToPeerMs != null && now - lastReleaseToPeerMs < recentReleaseMs -> Decision.Ignore("recently handed to a peer")
            lastTriggerMs?.let { now - it < cooldownMs } == true -> Decision.Ignore("cooldown")
            else -> {
                lastTriggerMs = now
                if (mode == AutoSwitchMode.ASK) Decision.Ask else Decision.Switch
            }
        }
    }
}
