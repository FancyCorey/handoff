package dev.handoff.core.handoff

import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.fakes.FakeBluetoothAudioController.ConnectStep
import dev.handoff.core.fakes.HEADSET_ID
import dev.handoff.core.fakes.TestHost
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.ReleaseResult
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.model.LogicalAudioDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffCoordinatorTest {

    private fun assertSuccess(result: HandoffResult, path: TransferPath, attempts: Int = 1) {
        assertTrue("expected success but was $result", result is HandoffResult.Success)
        result as HandoffResult.Success
        assertEquals(path, result.path)
        assertEquals(attempts, result.attempts)
    }

    @Test
    fun `successful remote release performs coordinated transfer`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.COORDINATED)
        assertEquals(listOf("phone"), headset.connectedHosts())
        assertEquals(1, phone.transport.sentOfType<ReleaseAudioDevice>().size)
        assertEquals(1, tablet.bluetooth.disconnectCalls)
        assertEquals(listOf(ConnectReason.USER_MOVE_HERE), phone.bluetooth.connectReasons)
        val state = phone.coordinator.state.value!!
        assertEquals(TransferPhase.COMPLETE, state.phase)
        assertEquals(
            listOf(StepKind.REQUESTING_RELEASE, StepKind.RELEASED, StepKind.CONNECTING, StepKind.VERIFYING, StepKind.CONNECTED),
            state.steps.map { it.kind },
        )
        assertEquals(phone.id, phone.devices.find(HEADSET_ID)!!.lastKnownOwner)
    }

    @Test
    fun `ownership change is announced to the previous owner`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)

        phone.moveHere()
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()

        val device = tablet.devices.find(HEADSET_ID)!!
        assertEquals(phone.id, device.lastKnownOwner)
        assertTrue(device.ownershipGeneration >= 1)
        assertTrue(tablet.ownership.reportsFor(HEADSET_ID).single { it.peerId == phone.id }.connected)
    }

    @Test
    fun `remote release returning NOT_CONNECTED still connects locally`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        // Tablet reports connected in status, but has dropped it by the time the release arrives.
        phone.transport.script(tablet.id) { msg ->
            if (msg is ReleaseAudioDevice) {
                CommandResult.Reply(release(msg, tablet.id.value, ReleaseStatus.NOT_CONNECTED))
            } else {
                headset.connect(tablet.bluetooth, tablet.localBt)
                CommandResult.Reply(tablet.handler.handle(phone.id, msg)).also {
                    headset.disconnect(tablet.bluetooth, tablet.localBt)
                }
            }
        }

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.COORDINATED)
        assertTrue(phone.coordinator.state.value!!.steps.any { it.kind == StepKind.PEER_NOT_CONNECTED })
    }

    @Test
    fun `unreachable peer falls back to direct takeover`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        phone.goOffline(tablet)

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.DIRECT_TAKEOVER)
        assertEquals(listOf("phone"), headset.connectedHosts())
        assertEquals(listOf(ConnectReason.DIRECT_TAKEOVER), phone.bluetooth.connectReasons)
        assertTrue(TransferPhase.DIRECT_TAKEOVER in phoneHistory(phone))
        assertEquals(0, tablet.bluetooth.disconnectCalls)
    }

    @Test
    fun `release timeout falls back to direct takeover`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        phone.transport.script(tablet.id) { msg ->
            if (msg is ReleaseAudioDevice) awaitCancellation()
            CommandResult.Reply(tablet.handler.handle(phone.id, msg))
        }

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.DIRECT_TAKEOVER)
        val steps = phone.coordinator.state.value!!.steps.map { it.kind }
        assertTrue(StepKind.RELEASE_TIMEOUT in steps)
        assertTrue(StepKind.DIRECT_TAKEOVER in steps)
        assertEquals(
            listOf(
                TransferPhase.IDLE, TransferPhase.RESOLVING_OWNER, TransferPhase.REQUESTING_RELEASE,
                TransferPhase.WAITING_RELEASE, TransferPhase.DIRECT_TAKEOVER, TransferPhase.CONNECTING,
                TransferPhase.VERIFYING, TransferPhase.COMPLETE,
            ),
            phoneHistory(phone),
        )
    }

    @Test
    fun `peer that refuses release (UNSUPPORTED) leads to direct takeover`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        tablet.bluetooth.disconnectResult =
            BluetoothOperationResult.Failed(BluetoothError.UNSUPPORTED, "ReflectionA2dpStrategy", "method missing")

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.DIRECT_TAKEOVER)
        assertEquals(listOf("phone"), headset.connectedHosts())
    }

    @Test
    fun `first connect attempt success does not retry`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.UNCONTESTED, attempts = 1)
        assertEquals(1, phone.bluetooth.connectCalls)
        assertFalse(TransferPhase.RETRYING in phoneHistory(phone))
    }

    @Test
    fun `second connect attempt success after verification timeout`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        phone.bluetooth.connectPlan += ConnectStep(becomesConnected = false)
        phone.bluetooth.connectPlan += ConnectStep(becomesConnected = true)

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.UNCONTESTED, attempts = 2)
        assertEquals(2, phone.bluetooth.connectCalls)
        assertEquals(ConnectReason.RETRY, phone.bluetooth.connectReasons[1])
        assertTrue(TransferPhase.RETRYING in phoneHistory(phone))
    }

    @Test
    fun `second attempt success after a rejected connect call`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        phone.bluetooth.connectPlan += ConnectStep(
            result = BluetoothOperationResult.Failed(BluetoothError.REJECTED, "ReflectionA2dpStrategy", "returned false"),
        )

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.UNCONTESTED, attempts = 2)
    }

    @Test
    fun `both connect attempts failing is a bounded failure`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        repeat(5) { phone.bluetooth.connectPlan += ConnectStep(becomesConnected = false) }

        val result = phone.moveHere()

        assertEquals(HandoffResult.Failed(FailureReason.HEADSET_NOT_RESPONDING, "the headset did not connect within 8 s"), result)
        // Two attempts per round, and exactly one automatic retry round: never more.
        assertEquals(4, phone.bluetooth.connectCalls)
        assertTrue(phone.coordinator.state.value!!.steps.any { it.kind == StepKind.AUTO_RETRY })
        assertEquals(TransferPhase.FAILED, phone.coordinator.state.value!!.phase)
        assertEquals("FAILED", phone.history.records.single().outcome)
    }

    @Test
    fun `unsupported connect is not retried`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        phone.bluetooth.connectPlan += ConnectStep(
            result = BluetoothOperationResult.Failed(BluetoothError.UNSUPPORTED, null, "no strategy"),
        )

        val result = phone.moveHere()

        assertEquals(FailureReason.UNSUPPORTED, (result as HandoffResult.Failed).reason)
        assertEquals(1, phone.bluetooth.connectCalls)
    }

    @Test
    fun `already connected returns immediately`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        headset.connect(phone.bluetooth, phone.localBt)

        val result = phone.moveHere()

        assertEquals(HandoffResult.AlreadyConnected, result)
        assertEquals(0, phone.bluetooth.connectCalls)
        assertTrue(phone.transport.sentOfType<ReleaseAudioDevice>().isEmpty())
    }

    @Test
    fun `missing local mapping is reported`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        val unmapped = phone.devices.find(HEADSET_ID)!!.copy(localDeviceId = null)

        val result = phone.coordinator.moveToThisDevice(unmapped)

        assertEquals(HandoffResult.MissingLocalMapping, result)
        assertEquals(0, phone.bluetooth.connectCalls)
    }

    @Test
    fun `bluetooth off fails without touching the stack`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        phone.bluetooth.adapterState.value = AdapterState.OFF

        val result = phone.moveHere()

        assertEquals(FailureReason.BLUETOOTH_OFF, (result as HandoffResult.Failed).reason)
        assertEquals(0, phone.bluetooth.connectCalls)
    }

    @Test
    fun `repeated Move Here presses while running return InProgress`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        val gate = CompletableDeferred<Unit>()
        phone.transport.script(tablet.id) { msg ->
            if (msg is ReleaseAudioDevice) gate.await()
            CommandResult.Reply(tablet.handler.handle(phone.id, msg))
        }

        val first = async { phone.moveHere() }
        runCurrent()
        val second = phone.moveHere()
        val third = phone.moveHere()
        gate.complete(Unit)

        assertEquals(HandoffResult.InProgress, second)
        assertEquals(HandoffResult.InProgress, third)
        assertSuccess(first.await(), TransferPath.COORDINATED)
        assertEquals(1, phone.transport.sentOfType<ReleaseAudioDevice>().size)
    }

    @Test
    fun `peer busy means contention and no takeover`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        phone.transport.script(tablet.id) { msg ->
            if (msg is ReleaseAudioDevice) {
                CommandResult.Reply(release(msg, tablet.id.value, ReleaseStatus.BUSY))
            } else {
                CommandResult.Reply(tablet.handler.handle(phone.id, msg))
            }
        }

        val result = phone.moveHere()

        assertEquals(FailureReason.CONTENTION, (result as HandoffResult.Failed).reason)
        assertEquals(0, phone.bluetooth.connectCalls)
        assertEquals(listOf("tablet"), headset.connectedHosts())
    }

    @Test
    fun `simultaneous requests from two hosts give the headset to exactly one`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet", "laptop")
        val (phone, tablet, laptop) = hosts
        headset.connect(laptop.bluetooth, laptop.localBt)

        val a = async { phone.moveHere() }
        val b = async { tablet.moveHere() }
        val results = listOf(a.await(), b.await())

        assertEquals(1, results.count { it is HandoffResult.Success })
        assertEquals(1, results.count { it is HandoffResult.Failed && it.reason == FailureReason.CONTENTION })
        assertEquals(1, headset.connectedHosts().size)
        assertEquals(1, laptop.bluetooth.disconnectCalls)
    }

    @Test
    fun `multipoint headset joins without releasing other hosts`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet", "laptop", multipoint = true)
        val (phone, tablet, laptop) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        headset.connect(laptop.bluetooth, laptop.localBt)

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.MULTIPOINT_JOIN)
        assertTrue(phone.transport.sentOfType<ReleaseAudioDevice>().isEmpty())
        assertEquals(setOf("phone", "tablet", "laptop"), headset.connectedHosts().toSet())
    }

    @Test
    fun `conflicting single-point reports release every remote holder`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet", "laptop")
        val (phone, tablet, laptop) = hosts
        // Both report connected (a stale report on one of them); a single-point headset can't do that.
        tablet.bluetooth.setConnected(tablet.localBt, true)
        laptop.bluetooth.setConnected(laptop.localBt, true)

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.COORDINATED)
        assertEquals(2, phone.transport.sentOfType<ReleaseAudioDevice>().size)
        assertEquals(listOf("phone"), headset.connectedHosts())
    }

    @Test
    fun `retry does not steal the headset from a host that grabbed it meanwhile`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        // Phone's first connect doesn't take; meanwhile the tablet connects on its own.
        phone.bluetooth.connectPlan += ConnectStep(becomesConnected = false)
        phone.bluetooth.connectPlan += ConnectStep(becomesConnected = true)
        val job = async { phone.moveHere() }
        testScheduler.advanceTimeBy(2_000)
        headset.connect(tablet.bluetooth, tablet.localBt)

        val result = job.await()

        assertEquals(FailureReason.CONTENTION, (result as HandoffResult.Failed).reason)
        assertEquals(1, phone.bluetooth.connectCalls)
        assertEquals(listOf("tablet"), headset.connectedHosts())
    }

    @Test
    fun `twenty consecutive handoffs alternate without failure`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet")
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)

        repeat(20) { i ->
            val mover = if (i % 2 == 0) phone else tablet
            val result = mover.moveHere()
            assertSuccess(result, TransferPath.COORDINATED)
            assertEquals(listOf(mover.name), headset.connectedHosts())
            testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        }
        assertEquals(10, phone.history.records.size)
        assertEquals(10, tablet.history.records.size)
        val generations = listOf(phone, tablet).map { it.devices.find(HEADSET_ID)!!.ownershipGeneration }
        assertEquals(20L, generations.max())
    }

    @Test
    fun `every recorded transition is legal`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone")
        val phone = hosts[0]
        val device: LogicalAudioDevice = phone.devices.find(HEADSET_ID)!!
        // Sanity: normal run works, and the machine only ever produced legal transitions.
        phone.coordinator.moveToThisDevice(device)
        val history = phoneHistory(phone)
        history.zipWithNext().forEach { (from, to) ->
            assertTrue("$from -> $to", to in TransferStateMachine.TRANSITIONS.getValue(from))
        }
    }

    private fun phoneHistory(host: TestHost): List<TransferPhase> = host.coordinator.state.value!!.phases

    private fun release(msg: ReleaseAudioDevice, sender: String, status: ReleaseStatus) = ReleaseResult(
        commandId = PeerMessage.newCommandId(),
        timestamp = msg.timestamp,
        senderPeerId = sender,
        inReplyTo = msg.commandId,
        logicalDeviceId = msg.logicalDeviceId,
        status = status,
    )

    @Test
    fun `automatic retry round recovers a transfer that failed twice`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        repeat(2) { phone.bluetooth.connectPlan += ConnectStep(becomesConnected = false) }

        val result = phone.moveHere()

        assertSuccess(result, TransferPath.UNCONTESTED, attempts = 3)
        val steps = phone.coordinator.state.value!!.steps.map { it.kind }
        assertTrue(StepKind.AUTO_RETRY in steps)
        assertEquals(StepKind.CONNECTED, steps.last())
    }

    @Test
    fun `unreachable owner plus failed takeover names the owner`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "phone", "tablet", headset = dev.handoff.core.fakes.SimulatedHeadset(acceptsTakeover = false))
        val (phone, tablet) = hosts
        headset.connect(tablet.bluetooth, tablet.localBt)
        // The phone knows the tablet had it, but the tablet is on another network now.
        phone.devices.applyOwnership(HEADSET_ID, tablet.id, 1)
        phone.goOffline(tablet)

        val result = phone.moveHere()

        assertEquals(HandoffResult.Failed(FailureReason.OWNER_UNREACHABLE, "tablet"), result)
    }

    @Test
    fun `owner that cannot let go is reported with its reason`() = runTest {
        val (hosts, headset) = TestHost.mesh(
            this, "phone", "tablet",
            headset = dev.handoff.core.fakes.SimulatedHeadset(acceptsTakeover = false, withCallProfile = true),
        )
        val (phone, tablet) = hosts
        tablet.bluetooth.connect(tablet.localBt, ConnectReason.DEBUG)
        tablet.bluetooth.companionReleaseWorks = false

        val result = phone.moveHere() as HandoffResult.Failed

        assertEquals(FailureReason.OWNER_REFUSED, result.reason)
        assertTrue(result.detail!!, result.detail!!.startsWith("tablet: ") && result.detail!!.contains("HFP"))
    }

    @Test
    fun `bluetooth switched off during the transfer is not retried`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val phone = hosts[0]
        repeat(2) { phone.bluetooth.connectPlan += ConnectStep(becomesConnected = false) }
        val job = async { phone.moveHere() }
        testScheduler.advanceTimeBy(1_000)
        phone.bluetooth.adapterState.value = AdapterState.OFF

        val result = job.await() as HandoffResult.Failed

        assertEquals(FailureReason.HEADSET_NOT_RESPONDING, result.reason)
        assertEquals(2, phone.bluetooth.connectCalls)
    }

    @Test
    fun `a running move can be cancelled and the headset is free for the next one`() = runTest {
        val (hosts, _) = TestHost.mesh(this, "phone", "tablet")
        val (phone, _) = hosts
        phone.bluetooth.connectPlan += ConnectStep(latencyMs = 60_000) // a connect that hangs

        val move = async { phone.moveHere() }
        testScheduler.advanceTimeBy(3_000); runCurrent()
        assertTrue(phone.coordinator.cancel(HEADSET_ID))

        assertEquals(HandoffResult.Cancelled, move.await())
        val state = phone.coordinator.transfers.value.getValue(HEADSET_ID)
        assertEquals(HandoffResult.Cancelled, state.result)
        assertEquals(StepKind.CANCELLED, state.steps.last().kind)
        assertEquals("CANCELLED", phone.history.records.last().outcome)
        assertFalse("nothing left to cancel", phone.coordinator.cancel(HEADSET_ID))

        // The per-headset lock was released: a new move runs normally.
        assertTrue(phone.moveHere() is HandoffResult.Success)
    }

    @Test
    fun `a device that doesn't recognise this one is named instead of blaming the headset`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "tablet", "phone", headset = dev.handoff.core.fakes.SimulatedHeadset(acceptsTakeover = false))
        val (tablet, phone) = hosts
        headset.connect(phone.bluetooth, phone.localBt)
        // The link only exists on the tablet's side: the phone refuses every connection.
        tablet.transport.script(phone.id) { CommandResult.Rejected("handshake: server rejected: not trusted") }

        val result = tablet.moveHere()

        assertEquals(HandoffResult.Failed(FailureReason.PEER_NOT_LINKED, "phone"), result)
    }

    @Test
    fun `a silent linked device is blamed rather than the headset when nobody reported holding it`() = runTest {
        val (hosts, headset) = TestHost.mesh(this, "tablet", "phone", headset = dev.handoff.core.fakes.SimulatedHeadset(acceptsTakeover = false))
        val (tablet, phone) = hosts
        headset.connect(phone.bluetooth, phone.localBt)
        tablet.goOffline(phone)

        val result = tablet.moveHere()

        assertEquals(HandoffResult.Failed(FailureReason.OWNER_UNREACHABLE, "phone"), result)
    }
}
