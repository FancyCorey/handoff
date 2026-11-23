package dev.handoff.bluetooth

import dev.handoff.core.model.AudioConnectionState

internal fun AudioConnectionState.hasA2dpToRelease(): Boolean =
    this == AudioConnectionState.CONNECTED || this == AudioConnectionState.CONNECTING

/** Preserve best-effort release on setups where the platform cannot read the A2DP profile state. */
internal fun AudioConnectionState.allowsA2dpReleaseVerification(): Boolean =
    this == AudioConnectionState.DISCONNECTED || this == AudioConnectionState.UNAVAILABLE
