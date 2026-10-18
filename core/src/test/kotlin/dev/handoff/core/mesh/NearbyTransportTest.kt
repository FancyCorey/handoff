package dev.handoff.core.mesh

import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.fakes.HEADSET_ID
import dev.handoff.core.fakes.TestHost
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.DeviceLocks
import dev.handoff.core.handoff.HandoffRequestHandler
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.ReleaseResult
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.mesh.protocol.StatusRequest
import dev.handoff.core.mesh.security.SoftwareIdentityProvider
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.FallbackPeerTransport
import dev.handoff.core.mesh.transport.LanPeerTransport
import dev.handoff.core.mesh.transport.NearbyChannel
import dev.handoff.core.mesh.transport.NearbyPeerTransport
import dev.handoff.core.mesh.transport.NearbyStream
import dev.handoff.core.mesh.transport.NearbyTags
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.mesh.transport.PeerServer
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.store.InMemoryLogicalDeviceRepository
import dev.handoff.core.store.InMemoryTrustedPeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two linked devices that can't reach each other over the network but are nearby: requests go
 * over the nearby channel instead, with the same handshake and encryption. Loopback sockets
 * stand in for the Bluetooth channel.
 */
class NearbyTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    inner class Node(name: String) {
        val identity = SoftwareIdentityProvider(PeerId.random(), name)
        val id = identity.identity().peerId
        val trust = InMemoryTrustedPeerRepository()
        val directory = PeerDirectory()
        val events = InMemoryEventLog()
        val devices = InMemoryLogicalDeviceRepository()
        val network = LanPeerTransport(identity, trust, directory, events)
        val bluetoothHost = TestHost(name, TestScope())
        val handler = HandoffRequestHandler(
            identity, devices, bluetoothHost.bluetooth,
            AudioReleaseHandler(devices, bluetoothHost.bluetooth, DeviceLocks(), events),
            MeshOwnershipRepository(id, network, { trust.peers.value.map { it.peerId } }),
            events, "test",
        )
        val server = PeerServer(identity, trust, PairingManager(identity, trust, events), handler, directory, events)

        /** This device's "Bluetooth" listener: every connection is served as a nearby one. */
        private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { socket ->
            scope.launch {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { client.use { server.serveNearby(it.getInputStream(), it.getOutputStream(), "test-device") } }
                }
            }
        }

        /** What a peer uses to reach this device nearby. */
        val opened = AtomicInteger()
        val reachable = object : NearbyChannel {
            override fun available() = true
            override suspend fun open(peerId: PeerId, timeoutMs: Long): NearbyStream? {
                opened.incrementAndGet()
                val socket = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                return NearbyStream(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
            }
        }

        fun nearbyTo(other: Node, canBeNearby: Boolean = true): FallbackPeerTransport {
            val nearby = NearbyPeerTransport(identity, trust, directory, events, other.reachable)
            return FallbackPeerTransport(network, nearby, directory, canBeNearby = { canBeNearby })
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Linked earlier (keys exchanged), but now with no network address for each other. */
    private fun linked(): Pair<Node, Node> {
        val a = Node("tablet")
        val b = Node("phone")
        runBlocking {
            a.trust.upsert(TrustedPeer(b.id, "phone", b.identity.identity().publicKey, pairedAtMs = 0))
            b.trust.upsert(TrustedPeer(a.id, "tablet", a.identity.identity().publicKey, pairedAtMs = 0))
        }
        return a to b
    }

    private fun Node.ping() = Ping(PeerMessage.newCommandId(), System.currentTimeMillis(), id.value)

    @Test
    fun `a move reaches a device nearby when the network can't`() = runBlocking {
        val (a, b) = linked()
        a.bluetoothHost.mapHeadset(listOf(a.bluetoothHost))
        a.devices.upsert(a.bluetoothHost.devices.find(HEADSET_ID)!!)
        a.bluetoothHost.bluetooth.setConnected(a.bluetoothHost.localBt, true)
        val transport = b.nearbyTo(a)

        assertTrue(transport.isReachable(a.id, 2_000))
        val release = ReleaseAudioDevice(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value, HEADSET_ID.value, b.id.value)
        val result = transport.request(a.id, release, 2_000)

        assertEquals(ReleaseStatus.RELEASED, ((result as CommandResult.Reply).message as ReleaseResult).status)
        assertEquals(1, a.bluetoothHost.bluetooth.disconnectCalls)
        assertTrue("the requester sees the other device nearby", b.directory.isNearby(a.id))
        assertTrue("the other device sees the requester nearby", a.directory.isNearby(b.id))
        assertTrue(b.directory.presence.value[a.id]!!.online)
        assertTrue(b.directory.presence.value[a.id]!!.nearby)
    }

    @Test
    fun `status updates don't look for devices that haven't been seen nearby`() = runBlocking {
        val (a, b) = linked()
        val transport = b.nearbyTo(a)
        val status = StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value)

        assertEquals(CommandResult.Unreachable, transport.request(a.id, status, 1_000))
        assertEquals(0, a.opened.get())
    }

    @Test
    fun `status updates to a device nearby are spaced out`() = runBlocking {
        val (a, b) = linked()
        val transport = b.nearbyTo(a)
        b.directory.onNearbySeen(a.id)
        val status = { StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value) }

        assertTrue(transport.request(a.id, status(), 2_000) is CommandResult.Reply)
        assertEquals(CommandResult.Unreachable, transport.request(a.id, status(), 2_000))
        assertEquals(1, a.opened.get())
    }

    @Test
    fun `devices that can't be nearby are not looked for`() = runBlocking {
        val (a, b) = linked()
        val transport = b.nearbyTo(a, canBeNearby = false)

        assertFalse(transport.isReachable(a.id, 1_000))
        assertEquals(0, a.opened.get())
    }

    @Test
    fun `an unlinked device nearby is turned away`() = runBlocking {
        val (a, _) = linked()
        val stranger = Node("stranger")
        runBlocking { stranger.trust.upsert(TrustedPeer(a.id, "tablet", a.identity.identity().publicKey, pairedAtMs = 0)) }
        val transport = stranger.nearbyTo(a)

        val result = transport.request(a.id, stranger.ping(), 2_000)

        assertTrue("$result", result is CommandResult.Rejected)
        assertFalse(a.directory.isNearby(stranger.id))
    }

    @Test
    fun `beacon codes are recognized only for linked devices and change over time`() {
        val (a, b) = linked()
        var now = 1_000_000L
        val tags = NearbyTags(a.trust) { now }
        val bKey = b.identity.identity().publicKey
        val code = NearbyTags(b.trust) { now }.ownTag(bKey)

        assertEquals(b.id, tags.resolve(code))
        assertNull(tags.resolve(ByteArray(NearbyTags.TAG_BYTES)))
        now += NearbyTags.SLOT_MS
        assertNotNull("a slot either side is still accepted", tags.resolve(code))
        assertFalse(code.contentEquals(NearbyTags(b.trust) { now }.ownTag(bKey)))
        now += 2 * NearbyTags.SLOT_MS
        assertNull(tags.resolve(code))
    }
}
