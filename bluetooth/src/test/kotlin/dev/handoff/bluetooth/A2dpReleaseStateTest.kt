package dev.handoff.bluetooth

import dev.handoff.core.model.AudioConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2dpReleaseStateTest {
    @Test
    fun `a connecting A2DP profile still needs to be released`() {
        assertTrue(AudioConnectionState.CONNECTING.hasA2dpToRelease())
        assertFalse(AudioConnectionState.CONNECTING.allowsA2dpReleaseVerification())
    }

    @Test
    fun `connected and disconnected states keep their established release behavior`() {
        assertTrue(AudioConnectionState.CONNECTED.hasA2dpToRelease())
        assertFalse(AudioConnectionState.CONNECTED.allowsA2dpReleaseVerification())
        assertFalse(AudioConnectionState.DISCONNECTING.allowsA2dpReleaseVerification())
        assertFalse(AudioConnectionState.DISCONNECTED.hasA2dpToRelease())
        assertTrue(AudioConnectionState.DISCONNECTED.allowsA2dpReleaseVerification())
    }

    @Test
    fun `unreadable A2DP state retains the best effort fallback`() {
        assertFalse(AudioConnectionState.UNAVAILABLE.hasA2dpToRelease())
        assertTrue(AudioConnectionState.UNAVAILABLE.allowsA2dpReleaseVerification())
    }
}
