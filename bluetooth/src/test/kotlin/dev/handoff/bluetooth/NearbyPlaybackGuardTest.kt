package dev.handoff.bluetooth

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NearbyPlaybackGuardTest {
    @Test
    fun `active music skips the search without registering a callback`() = runTest {
        val audio = Playback(playing = true)
        var skipped = false

        audio.guard.whileIdle(onPlayback = { skipped = true }) { error("search started during music") }

        assertTrue(skipped)
        assertEquals(0, audio.registrations)
    }

    @Test
    fun `music that starts during registration skips the search`() = runTest {
        val audio = Playback()
        audio.onRegister = { audio.playing = true }
        var skipped = false

        audio.guard.whileIdle(onPlayback = { skipped = true }) { error("search started during music") }

        assertTrue(skipped)
        assertEquals(1, audio.registrations)
        assertNull(audio.callback)
    }

    @Test
    fun `a playback change cancels a running search only when music starts`() = runTest {
        val audio = Playback()
        var stoppedForPlayback = false
        var scanning = false
        val job = launch {
            audio.guard.whileIdle(onPlayback = { stoppedForPlayback = true }) {
                scanning = true
                try {
                    awaitCancellation()
                } finally {
                    scanning = false
                }
            }
        }
        runCurrent()
        assertTrue(scanning)

        audio.callback?.invoke(false)
        runCurrent()
        assertTrue(scanning)
        assertFalse(stoppedForPlayback)

        audio.playing = true
        audio.callback?.invoke(true)
        job.join()
        assertFalse(scanning)
        assertTrue(stoppedForPlayback)
        assertNull(audio.callback)
    }

    @Test
    fun `normal completion and caller cancellation unregister the callback`() = runTest {
        val audio = Playback()
        audio.guard.whileIdle(onPlayback = { error("music did not start") }) { }
        assertNull(audio.callback)

        val job = launch {
            audio.guard.whileIdle(onPlayback = { error("music did not start") }) { awaitCancellation() }
        }
        runCurrent()
        job.cancel()
        job.join()

        assertEquals(2, audio.registrations)
        assertNull(audio.callback)
    }

    private class Playback(var playing: Boolean = false) {
        var callback: ((Boolean) -> Unit)? = null
        var onRegister: () -> Unit = {}
        var registrations = 0
        val guard = NearbyPlaybackGuard({ playing }) { listener ->
            callback = listener
            registrations++
            onRegister()
            AutoCloseable { callback = null }
        }
    }
}
