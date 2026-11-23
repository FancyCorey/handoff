package dev.handoff.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SbcConnectionTest {
    @Test
    fun `playback defers the write and an SBC readback confirms it`() = runTest {
        val stack = Stack(playing = true)
        val applied = async { stack.apply() }
        runCurrent()

        assertEquals(CodecPreferenceStatus.WAITING_FOR_PAUSE, stack.states.last())
        assertEquals(0, stack.writes)

        stack.pause()
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, applied.await())
        assertEquals(1, stack.writes)
    }

    @Test
    fun `already active SBC is confirmed during playback and the session will not write again`() = runTest {
        val stack = Stack(playing = true).apply { reading = SbcRead.ACTIVE }
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, stack.apply())
        assertEquals(0, stack.writes)

        stack.reading = SbcRead.SELECTABLE
        stack.pause()
        assertEquals(CodecPreferenceStatus.UNAVAILABLE, stack.apply())
        assertEquals(0, stack.writes)
    }

    @Test
    fun `a request that is ignored is not repeated on the same connection`() = runTest {
        val stack = Stack().apply { readingAfterWrite = SbcRead.SELECTABLE }
        assertEquals(CodecPreferenceStatus.UNAVAILABLE, stack.apply())
        assertEquals(1, stack.writes)

        assertEquals(CodecPreferenceStatus.UNAVAILABLE, stack.apply())
        assertEquals(1, stack.writes)

        stack.connection = SbcConnection()
        stack.readingAfterWrite = SbcRead.ACTIVE
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, stack.apply())
        assertEquals(2, stack.writes)
    }

    @Test
    fun `waits briefly for negotiation and gives up when status never arrives`() = runTest {
        val stack = Stack().apply { reading = SbcRead.NOT_READY }
        assertEquals(CodecPreferenceStatus.UNAVAILABLE, stack.apply())
        assertEquals(0, stack.writes)
        assertEquals(5, stack.reads)

        val negotiation = Stack().apply { readyAfterReads = 3 }
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, negotiation.apply())
        assertEquals(4, negotiation.reads)
        assertEquals(1, negotiation.writes)
    }

    @Test
    fun `an access requirement and an unavailable codec never attempt a write`() = runTest {
        val denied = Stack().apply { reading = SbcRead.ACCESS_NEEDED }
        assertEquals(CodecPreferenceStatus.ACCESS_NEEDED, denied.apply())
        assertEquals(0, denied.writes)

        val unavailable = Stack().apply { reading = SbcRead.UNAVAILABLE }
        assertEquals(CodecPreferenceStatus.UNAVAILABLE, unavailable.apply())
        assertEquals(0, unavailable.writes)
    }

    @Test
    fun `disconnect or opt out while waiting does not write when playback stops`() = runTest {
        val disconnected = Stack(playing = true)
        val first = async { disconnected.apply() }
        runCurrent()
        disconnected.connected = false
        disconnected.pause()
        assertEquals(CodecPreferenceStatus.WAITING_FOR_CONNECTION, first.await())
        assertEquals(0, disconnected.writes)

        val optedOut = Stack(playing = true)
        val second = async { optedOut.apply() }
        runCurrent()
        second.cancelAndJoin()
        optedOut.pause()
        assertTrue(second.isCancelled)
        assertEquals(0, optedOut.writes)
    }

    @Test
    fun `resuming a pending verification does not send another codec request`() = runTest {
        val stack = Stack().apply { readingAfterWrite = SbcRead.NOT_READY }
        val first = async { stack.apply() }
        runCurrent()
        assertEquals(1, stack.writes)
        first.cancelAndJoin()

        stack.reading = SbcRead.ACTIVE
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, stack.apply())
        assertEquals(1, stack.writes)
    }

    @Test
    fun `cancellation at the setter preflight leaves the same connection available`() = runTest {
        val stack = Stack()
        stack.beforeFinalCheck = { throw CancellationException("Opted out during preflight") }
        val first = async { stack.apply() }
        first.join()

        assertTrue(first.isCancelled)
        assertEquals(0, stack.writes)

        stack.beforeFinalCheck = null
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, stack.apply())
        assertEquals(1, stack.writes)
    }

    @Test
    fun `playback starting at the final check releases the request until pause`() = runTest {
        val stack = Stack()
        stack.beforeFinalCheck = { stack.playing = true }
        val applied = async { stack.apply() }
        runCurrent()

        assertEquals(CodecPreferenceStatus.WAITING_FOR_PAUSE, stack.states.last())
        assertEquals(0, stack.writes)

        stack.pause()
        assertEquals(CodecPreferenceStatus.SBC_CONFIRMED, applied.await())
        assertEquals(1, stack.writes)
    }

    private class Stack(var playing: Boolean = false) {
        var connection = SbcConnection()
        var connected = true
        var reading = SbcRead.SELECTABLE
        var readingAfterWrite = SbcRead.ACTIVE
        var readyAfterReads = 1
        var beforeFinalCheck: (() -> Unit)? = null
        var reads = 0
        var writes = 0
        val states = mutableListOf<CodecPreferenceStatus>()
        private val paused = CompletableDeferred<Unit>()

        fun pause() {
            playing = false
            paused.complete(Unit)
        }

        suspend fun apply() = connection.apply(
            read = { if (++reads < readyAfterReads) SbcRead.NOT_READY else reading },
            isStillConnected = { connected },
            mayChangeCodec = { !playing },
            awaitPause = { paused.await() },
            write = write@{
                beforeFinalCheck?.invoke()
                beforeFinalCheck = null
                if (playing) return@write SbcWrite.WAITING_FOR_PAUSE
                assertTrue(connected)
                assertFalse(playing)
                writes++
                reading = readingAfterWrite
                SbcWrite.REQUESTED
            },
            report = { states += it },
        )
    }
}
