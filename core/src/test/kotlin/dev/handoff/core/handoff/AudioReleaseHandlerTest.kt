package dev.handoff.core.handoff

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
