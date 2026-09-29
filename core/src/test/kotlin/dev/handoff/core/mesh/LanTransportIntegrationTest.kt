package dev.handoff.core.mesh

import dev.handoff.core.mesh.transport.EndpointSource
import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.fakes.HEADSET_ID
import dev.handoff.core.fakes.TestHost
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.DeviceLocks
import dev.handoff.core.handoff.HandoffRequestHandler
import dev.handoff.core.mesh.pairing.PairingClient
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.pairing.PairingOutcome
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.ReleaseResult
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.mesh.protocol.StatusRequest
import dev.handoff.core.mesh.protocol.StatusResponse
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.PairingInvitation
import dev.handoff.core.mesh.security.SoftwareIdentityProvider
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.LanPeerTransport
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.mesh.transport.PeerServer
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.store.InMemoryLogicalDeviceRepository
import dev.handoff.core.store.InMemoryTrustedPeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two complete mesh nodes talking over real loopback TCP sockets: QR pairing, authenticated
 * encrypted requests, replay and freshness checks, and a real release request.
 */
class LanTransportIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    inner class Node(name: String) {
        val identity = SoftwareIdentityProvider(PeerId.random(), name)
        val id = identity.identity().peerId
        val trust = InMemoryTrustedPeerRepository()
        val directory = PeerDirectory()
        val events = InMemoryEventLog()
        val pairing = PairingManager(identity, trust, events)
        val transport: LanPeerTransport = LanPeerTransport(identity, trust, directory, events, listenPort = { server.port.value })
        val devices = InMemoryLogicalDeviceRepository()

        // Real request handler backed by a fake Bluetooth stack.
        val bluetoothHost = TestHost(name, TestScope())
        val handler = HandoffRequestHandler(
            identity, devices, bluetoothHost.bluetooth,
            AudioReleaseHandler(devices, bluetoothHost.bluetooth, DeviceLocks(), events),
            MeshOwnershipRepository(id, transport, { trust.peers.value.map { it.peerId } }),
            events, "test",
        )
        val server = PeerServer(identity, trust, pairing, handler, directory, events)
        val port = server.start(scope, preferredPort = 0)
        val pairingClient: PairingClient = PairingClient(identity, trust, directory, events, listenPort = { server.port.value })

        fun ping(commandId: String = PeerMessage.newCommandId(), timestamp: Long = System.currentTimeMillis()) =
            Ping(commandId, timestamp, id.value)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun pair(a: Node, b: Node, approve: Boolean = true): PairingOutcome = runBlocking {
        val invitation = a.pairing.createInvitation(listOf("127.0.0.1"), a.port).invitation
        var codeOnB: String? = null
        val outcome = async { b.pairingClient.pair(invitation) { codeOnB = it } }
        val pending = withTimeout(5_000) {
            while (a.pairing.pendingApproval.value == null) delay(10)
            a.pairing.pendingApproval.value!!
        }
        assertEquals(b.id, pending.peerId)
        assertEquals("both screens show the same code", codeOnB, pending.sas)
        a.pairing.respond(approve)
        withTimeout(5_000) { outcome.await() }
    }

    private fun linked(): Pair<Node, Node> {
        val a = Node("tablet")
        val b = Node("phone")
        assertTrue(pair(a, b) is PairingOutcome.Paired)
        a.directory.onDiscovered(b.id, "127.0.0.1", b.port)
        return a to b
    }

    @Test
    fun `QR pairing establishes mutual trust and authenticated requests work both ways`() = runBlocking {
        val (a, b) = linked()
        assertNotNull(a.trust.find(b.id))
        assertNotNull(b.trust.find(a.id))
        assertEquals("tablet", b.trust.find(a.id)!!.displayName)

        val fromB = b.transport.request(a.id, b.ping(), 3_000)
        assertTrue(fromB is CommandResult.Reply && fromB.message is Pong)
        val fromA = a.transport.request(b.id, a.ping(), 3_000)
        assertTrue(fromA is CommandResult.Reply && fromA.message is Pong)
        assertTrue(b.transport.isReachable(a.id, 3_000))
    }

    @Test
    fun `declined pairing stores no trust`() {
        val a = Node("tablet")
        val b = Node("phone")
        val outcome = pair(a, b, approve = false)
        assertTrue(outcome is PairingOutcome.Declined)
        assertNull(a.trust.find(b.id))
        assertNull(b.trust.find(a.id))
    }

    @Test
    fun `invitation token is single use`() = runBlocking {
        val a = Node("tablet")
        val b = Node("phone")
        val c = Node("stranger")
        val invitation = a.pairing.createInvitation(listOf("127.0.0.1"), a.port).invitation
        val first = async { b.pairingClient.pair(invitation) {} }
        withTimeout(5_000) { while (a.pairing.pendingApproval.value == null) delay(10) }
        a.pairing.respond(true)
        assertTrue(first.await() is PairingOutcome.Paired)

        val replay = c.pairingClient.pair(invitation) {}
        assertTrue("$replay", replay is PairingOutcome.Expired)
        assertNull(a.trust.find(c.id))
    }

    @Test
    fun `invitation with a substituted key is refused by the scanner`() = runBlocking {
        val a = Node("tablet")
        val b = Node("phone")
        val real = a.pairing.createInvitation(listOf("127.0.0.1"), a.port).invitation
        val forged = PairingInvitation(
            real.peerId, PairingInvitation.keyHashOf(Crypto.generateEcKeyPair().public.encoded), real.token, real.hosts, real.port,
        )
        val outcome = b.pairingClient.pair(forged) {}
        assertTrue("$outcome", outcome is PairingOutcome.Failed)
        assertNull(b.trust.find(a.id))
    }

    @Test
    fun `untrusted peer cannot send commands`() = runBlocking {
        val a = Node("tablet")
        val intruder = Node("intruder")
        // The intruder knows a's address and even "trusts" a, but a does not trust it.
        intruder.trust.upsert(dev.handoff.core.model.TrustedPeer(a.id, "tablet", a.identity.identity().publicKey, 0))
        intruder.directory.onDiscovered(a.id, "127.0.0.1", a.port)
        a.bluetoothHost.bluetooth.setConnected(a.bluetoothHost.localBt, true)

        val result = intruder.transport.request(
            a.id,
            ReleaseAudioDevice(PeerMessage.newCommandId(), System.currentTimeMillis(), intruder.id.value, HEADSET_ID.value, intruder.id.value),
            3_000,
        )

        assertTrue("$result", result is CommandResult.Rejected)
        assertEquals(0, a.bluetoothHost.bluetooth.disconnectCalls)
    }

    @Test
    fun `removing trust revokes access`() = runBlocking {
        val (a, b) = linked()
        a.trust.remove(b.id)
        val result = b.transport.request(a.id, b.ping(), 3_000)
        assertTrue("$result", result is CommandResult.Rejected)
    }

    @Test
    fun `stale commands are rejected`() = runBlocking {
        val (a, b) = linked()
        val stale = b.ping(timestamp = System.currentTimeMillis() - 10 * 60_000)
        val result = b.transport.request(a.id, stale, 3_000)
        assertTrue("$result", result is CommandResult.Rejected && result.reason.startsWith("STALE"))
    }

    @Test
    fun `duplicate command ids get the cached reply and do not execute twice`() = runBlocking {
        val (a, b) = linked()
        a.bluetoothHost.mapHeadset(listOf(a.bluetoothHost))
        a.devices.upsert(a.bluetoothHost.devices.find(HEADSET_ID)!!)
        a.bluetoothHost.bluetooth.setConnected(a.bluetoothHost.localBt, true)
        val release = ReleaseAudioDevice(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value, HEADSET_ID.value, b.id.value)

        val first = b.transport.request(a.id, release, 10_000)
        val second = b.transport.request(a.id, release, 10_000)

        assertEquals(ReleaseStatus.RELEASED, ((first as CommandResult.Reply).message as ReleaseResult).status)
        assertEquals(first, second)
        assertEquals(1, a.bluetoothHost.bluetooth.disconnectCalls)
    }

    @Test
    fun `sender field must match the authenticated peer`() = runBlocking {
        val (a, b) = linked()
        val spoofed = Ping(PeerMessage.newCommandId(), System.currentTimeMillis(), "someone-else")
        val result = b.transport.request(a.id, spoofed, 3_000)
        assertTrue("$result", result is CommandResult.Rejected && result.reason.startsWith("SENDER_MISMATCH"))
    }

    @Test
    fun `status request reports mapped headsets`() = runBlocking {
        val (a, b) = linked()
        a.bluetoothHost.mapHeadset(listOf(a.bluetoothHost))
        a.devices.upsert(a.bluetoothHost.devices.find(HEADSET_ID)!!)
        a.bluetoothHost.bluetooth.setConnected(a.bluetoothHost.localBt, true)

        val result = b.transport.request(a.id, StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value), 3_000)

        val status = (result as CommandResult.Reply).message as StatusResponse
        assertEquals(HEADSET_ID.value, status.devices.single().logicalDeviceId)
        assertTrue(status.devices.single().connected)
        assertEquals("fp-buds", status.devices.single().fingerprint)
    }

    @Test
    fun `offline peer is unreachable quickly`() = runBlocking {
        val (a, b) = linked()
        a.server.stop()
        val started = System.currentTimeMillis()
        val result = b.transport.request(a.id, b.ping(), 3_000)
        assertEquals(CommandResult.Unreachable, result)
        assertTrue(System.currentTimeMillis() - started < 3_500)
        assertTrue(!b.directory.isOnline(a.id))
    }

    @Test
    fun `a device's real listening port is recorded when it connects in`() = runBlocking {
        val (a, b) = linked()
        assertTrue(b.transport.request(a.id, b.ping(), 3_000) is CommandResult.Reply)
        val inbound = a.directory.presence.value.getValue(b.id).endpoints.single { it.source == EndpointSource.INBOUND }
        assertEquals("B's server port, not the default port", b.port, inbound.port)
    }

    @Test
    fun `an address answered by another copy of Handoff is skipped`() = runBlocking {
        val (a, b) = linked()
        // A second, unlinked copy of Handoff sits on the address A tries first.
        val otherCopy = Node("other copy")
        a.directory.onContactSucceeded(b.id, "127.0.0.1", otherCopy.port)
        assertEquals(otherCopy.port, a.directory.endpointsFor(b.id).first().port)

        val result = a.transport.request(b.id, a.ping(), 5_000)

        assertTrue("$result", result is CommandResult.Reply && result.message is Pong)
        assertEquals("the working address is now tried first", b.port, a.directory.endpointsFor(b.id).first().port)
    }
}
