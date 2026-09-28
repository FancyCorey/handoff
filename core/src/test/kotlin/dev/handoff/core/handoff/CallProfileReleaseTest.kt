package dev.handoff.core.handoff

import dev.handoff.core.fakes.HEADSET_ID
import dev.handoff.core.fakes.SimulatedHeadset
import dev.handoff.core.fakes.TestHost
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the first hardware report (Galaxy Tab S10 FE ← Galaxy S22 Ultra, OnePlus Bullets
 * Wireless Z2): the source released A2DP only, the headset stayed attached through HFP (calls),
 * refused the tablet, and both connect attempts timed out.
 */
class CallProfileReleaseTest {
    private fun busyRefusingHeadset() = SimulatedHeadset(acceptsTakeover = false, withCallProfile = true)

    @Test
    fun `coordinated move succeeds when the source also releases the call profile`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "tablet", "phone", headset = busyRefusingHeadset())
        val (tablet, phone) = hosts
        phone.bluetooth.connect(phone.localBt, dev.handoff.core.bluetooth.ConnectReason.DEBUG)
        assertEquals(setOf("A2DP", "HFP"), phone.bluetooth.connectedProfiles(phone.localBt))

        val result = tablet.moveHere()

        assertTrue("$result", result is HandoffResult.Success && result.path == TransferPath.COORDINATED)
        assertEquals(listOf("tablet"), headset.connectedHosts())
        assertTrue(phone.bluetooth.connectedProfiles(phone.localBt).isEmpty())
    }

    @Test
    fun `a call-only link still counts as holding the headset`() = runTest {
        val host = TestHost("phone", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.companions[host.localBt] = mutableSetOf("HFP")

        val outcome = host.releaseHandler.release(HEADSET_ID, PeerId("peer-tablet"))

        assertEquals(ReleaseStatus.RELEASED, outcome.status)
        assertTrue(host.bluetooth.connectedProfiles(host.localBt).isEmpty())
    }

    @Test
    fun `a call profile Android refuses to drop is reported precisely`() = runTest {
        val host = TestHost("phone", this).apply { mapHeadset(listOf(this)) }
        host.bluetooth.setConnected(host.localBt, true)
        host.bluetooth.companions[host.localBt] = mutableSetOf("HFP")
        host.bluetooth.companionReleaseWorks = false

        val outcome = host.releaseHandler.release(HEADSET_ID, PeerId("peer-tablet"))

        assertEquals(ReleaseStatus.FAILED, outcome.status)
        assertTrue(outcome.detail!!, outcome.detail!!.contains("HFP"))
    }

    @Test
    fun `stuck call profile surfaces the reason on the requesting device`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "tablet", "phone", headset = busyRefusingHeadset())
        val (tablet, phone) = hosts
        phone.bluetooth.connect(phone.localBt, dev.handoff.core.bluetooth.ConnectReason.DEBUG)
        phone.bluetooth.companionReleaseWorks = false

        val result = tablet.moveHere()

        assertTrue("$result", result is HandoffResult.Failed)
        val refused = tablet.coordinator.state.value!!.steps.first { it.kind == StepKind.RELEASE_REFUSED }
        assertTrue(refused.detail!!, refused.detail!!.contains("HFP"))
        // A2DP was dropped but the call link stuck, so the headset never accepted the tablet.
        assertEquals(setOf("HFP"), phone.bluetooth.connectedProfiles(phone.localBt))
        assertTrue(headset.connectedHosts().isEmpty())
    }
}
