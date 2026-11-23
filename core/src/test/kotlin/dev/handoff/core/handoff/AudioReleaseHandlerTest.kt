package dev.handoff.core.handoff

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.fakes.HEADSET_ID
import dev.handoff.core.fakes.TestHost
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioReleaseHandlerTest {
    private val phone = PeerId("peer-phone")
    private val laptop = PeerId("peer-laptop")

    @Test
    fun `connected headset is released and verified`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)

        val outcome = host.releaseHandler.release(HEADSET_ID, phone)

        assertEquals(ReleaseStatus.RELEASED, outcome.status)
        assertEquals(1, host.bluetooth.disconnectCalls)
        assertEquals(phone, host.devices.find(HEADSET_ID)!!.lastKnownOwner)
    }

    @Test
    fun `the release says whether the Bluetooth link has closed too`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        host.bluetooth.linkClosedAnswers.add(false)

        val open = host.releaseHandler.release(HEADSET_ID, phone)
        assertEquals(ReleaseStatus.RELEASED, open.status)
        assertEquals(false, open.linkClosed)

        // Asked again: nothing held any more, and now the link is closed.
        val again = host.releaseHandler.release(HEADSET_ID, phone)
        assertEquals(ReleaseStatus.NOT_CONNECTED, again.status)
        assertEquals(true, again.linkClosed)
    }

    private fun guarded(host: TestHost, scope: TestScope, holderHasIt: () -> Boolean = { true }) = AudioReleaseHandler(
        host.devices, host.bluetooth, host.locks, host.events, host.policy, host.clock,
        guardScope = scope.backgroundScope, holderHasIt = { _, _, _ -> holderHasIt() },
    )

    @Test
    fun `headset that comes back by itself after letting go is let go again`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        val handler = guarded(host, this)

        assertEquals(ReleaseStatus.RELEASED, handler.release(HEADSET_ID, phone).status)
        testScheduler.advanceTimeBy(5_000)
        host.bluetooth.setConnected(host.localBt, true) // the system reconnected it
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()

        assertEquals(2, host.bluetooth.disconnectCalls)
        assertFalse(host.bluetooth.isConnectedNow(host.localBt))
        assertTrue(host.events.events.value.any { it.details["status"] == "came back by itself; release requested" })
    }

    @Test
    fun `the guard leaves it alone once it is meant to be here again`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        val handler = guarded(host, this)

        handler.release(HEADSET_ID, phone)
        // Moved back here with Handoff: this device owns it again.
        host.devices.applyOwnership(HEADSET_ID, host.id, 99)
        host.bluetooth.setConnected(host.localBt, true)
        testScheduler.advanceTimeBy(70_000); testScheduler.runCurrent()

        assertEquals(1, host.bluetooth.disconnectCalls)
        assertTrue(host.bluetooth.isConnectedNow(host.localBt))
    }

    @Test
    fun `the guard leaves it alone after the new holder let go`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        var phoneHasIt = true
        val handler = guarded(host, this) { phoneHasIt }

        handler.release(HEADSET_ID, phone)
        testScheduler.advanceTimeBy(10_000)
        // Switched back from Bluetooth settings: the phone loses it and reports that a moment later.
        host.bluetooth.setConnected(host.localBt, true)
        testScheduler.advanceTimeBy(1_000)
        phoneHasIt = false
        testScheduler.advanceTimeBy(70_000); testScheduler.runCurrent()

        assertEquals(1, host.bluetooth.disconnectCalls)
        assertTrue(host.bluetooth.isConnectedNow(host.localBt))
    }

    @Test
    fun `the guard does nothing for a move that was cancelled or failed`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        // The phone never confirmed having it.
        val handler = guarded(host, this) { false }

        handler.release(HEADSET_ID, phone)
        testScheduler.advanceTimeBy(5_000)
        host.bluetooth.setConnected(host.localBt, true) // connected here again by hand
        testScheduler.advanceTimeBy(70_000); testScheduler.runCurrent()

        assertEquals(1, host.bluetooth.disconnectCalls)
        assertTrue(host.bluetooth.isConnectedNow(host.localBt))
    }

    @Test
    fun `the guard waits while a move here is running, then stands down`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        val handler = guarded(host, this)

        handler.release(HEADSET_ID, phone)
        // A move back here starts: it holds the lock, connects, and ends up owning the headset.
        val lock = host.locks.forDevice(HEADSET_ID)
        lock.lock()
        host.bluetooth.setConnected(host.localBt, true)
        testScheduler.advanceTimeBy(10_000)
        host.devices.applyOwnership(HEADSET_ID, host.id, 99)
        lock.unlock()
        testScheduler.advanceTimeBy(70_000); testScheduler.runCurrent()

        assertEquals(1, host.bluetooth.disconnectCalls)
        assertTrue(host.bluetooth.isConnectedNow(host.localBt))
    }

    @Test
    fun `each release starts a fresh guard for the new holder`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        val handler = guarded(host, this)

        handler.release(HEADSET_ID, phone)
        testScheduler.advanceTimeBy(50_000)
        // Moved back here, then released to the laptop: a new full minute starts.
        host.devices.applyOwnership(HEADSET_ID, host.id, 99)
        host.bluetooth.setConnected(host.localBt, true)
        handler.release(HEADSET_ID, laptop)
        testScheduler.advanceTimeBy(40_000)
        host.bluetooth.setConnected(host.localBt, true) // came back by itself, 40 s into the new guard
        testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()

        assertEquals(3, host.bluetooth.disconnectCalls)
        assertFalse(host.bluetooth.isConnectedNow(host.localBt))
    }

    @Test
    fun `not connected is reported without touching Bluetooth`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }

        assertEquals(ReleaseStatus.NOT_CONNECTED, host.releaseHandler.release(HEADSET_ID, phone).status)
        assertEquals(0, host.bluetooth.disconnectCalls)
    }

    @Test
    fun `unknown logical device`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }

        assertEquals(ReleaseStatus.DEVICE_UNKNOWN, host.releaseHandler.release(LogicalDeviceId("nope"), phone).status)
    }

    @Test
    fun `busy while this host is transferring the same headset`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        host.locks.forDevice(HEADSET_ID).lock()

        assertEquals(ReleaseStatus.BUSY, host.releaseHandler.release(HEADSET_ID, phone).status)
        assertEquals(0, host.bluetooth.disconnectCalls)
    }

    @Test
    fun `other requesters are refused during the grace window, same requester is not`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        assertEquals(ReleaseStatus.RELEASED, host.releaseHandler.release(HEADSET_ID, phone).status)

        assertEquals(ReleaseStatus.BUSY, host.releaseHandler.release(HEADSET_ID, laptop).status)
        assertEquals(ReleaseStatus.NOT_CONNECTED, host.releaseHandler.release(HEADSET_ID, phone).status)

        testScheduler.advanceTimeBy(HandoffPolicy().releaseGraceMs + 1)
        assertEquals(ReleaseStatus.NOT_CONNECTED, host.releaseHandler.release(HEADSET_ID, laptop).status)
    }

    @Test
    fun `unsupported disconnect is reported as UNSUPPORTED`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        host.bluetooth.disconnectResult = BluetoothOperationResult.Failed(BluetoothError.UNSUPPORTED, "Reflection", "missing")

        assertEquals(ReleaseStatus.UNSUPPORTED, host.releaseHandler.release(HEADSET_ID, phone).status)
    }

    @Test
    fun `permission denied is reported`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        host.bluetooth.adapterState.value = AdapterState.NO_PERMISSION

        assertEquals(ReleaseStatus.PERMISSION_DENIED, host.releaseHandler.release(HEADSET_ID, phone).status)
    }

    @Test
    fun `disconnect that never takes effect is a TIMEOUT`() = runTest {
        val host = TestHost("tablet", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        host.bluetooth.disconnectTakesEffect = false

        assertEquals(ReleaseStatus.TIMEOUT, host.releaseHandler.release(HEADSET_ID, phone).status)
    }
}
