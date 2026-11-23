package dev.handoff.core.mesh

import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.fakes.HEADSET_ID
import dev.handoff.core.fakes.TestHost
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.DeviceLocks
import dev.handoff.core.handoff.HandoffRequestHandler
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.protocol.Ack
import dev.handoff.core.mesh.protocol.OwnershipChanged
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Two linked devices that can't reach each other over the network but are nearby: requests go
 * over the nearby channel instead, with the same handshake and encryption. Loopback sockets
 * stand in for the Bluetooth channel.
 */
class NearbyTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = ConcurrentLinkedQueue<ServerSocket>()
    data class OpenPolicy(val allowDiscovery: Boolean, val allowDuringPlayback: Boolean)

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
            listeners.add(socket)
            scope.launch {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { client.use { server.serveNearby(it.getInputStream(), it.getOutputStream(), "test-device") } }
                }
            }
        }

        /** What a peer uses to reach this device nearby. */
        val opens = ConcurrentLinkedQueue<OpenPolicy>()
        var cachedSighting = true
        var musicPlaying = false
        val reachable = object : NearbyChannel {
            override fun available() = true
            override suspend fun open(
                peerId: PeerId,
                timeoutMs: Long,
                allowDiscovery: Boolean,
                allowDuringPlayback: Boolean,
            ): NearbyStream? {
                opens.add(OpenPolicy(allowDiscovery, allowDuringPlayback))
                if (!allowDuringPlayback && musicPlaying) return null
                if (!allowDiscovery && !cachedSighting) return null
                val socket = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                return NearbyStream(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
            }
        }

        fun nearbyTo(
            other: Node,
            canBeNearby: Boolean = true,
            clock: () -> Long = System::currentTimeMillis,
        ): FallbackPeerTransport {
            val nearby = NearbyPeerTransport(identity, trust, directory, events, other.reachable)
            return FallbackPeerTransport(network, nearby, directory, clock = clock, canBeNearby = { canBeNearby }, events = events)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        listeners.forEach { it.close() }
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
        assertEquals(List(2) { OpenPolicy(true, true) }, a.opens.toList())
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
        assertTrue(a.opens.isEmpty())
    }

    @Test
    fun `status updates to a device nearby are spaced out`() = runBlocking {
        val (a, b) = linked()
        var now = 0L
        val transport = b.nearbyTo(a, clock = { now })
        b.directory.onNearbySeen(a.id)
        val status = { StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value) }

        assertTrue(transport.request(a.id, status(), 2_000) is CommandResult.Reply)
        now = 29_999
        assertEquals(CommandResult.Unreachable, transport.request(a.id, status(), 2_000))
        now = 30_000
        assertTrue(transport.request(a.id, status(), 2_000) is CommandResult.Reply)
        assertEquals(List(2) { OpenPolicy(false, false) }, a.opens.toList())
    }

    @Test
    fun `recent presence without a usable cached sighting does not allow status discovery`() = runBlocking {
        val (a, b) = linked()
        a.cachedSighting = false
        b.directory.onNearbySeen(a.id)
        val transport = b.nearbyTo(a)
        val status = StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value)

        assertEquals(CommandResult.Unreachable, transport.request(a.id, status, 1_000))
        assertTrue(transport.isReachable(a.id, 1_000))
        assertEquals(listOf(OpenPolicy(false, false), OpenPolicy(true, true)), a.opens.toList())
    }

    @Test
    fun `a status attempt cannot block a cached ownership announcement during playback`() = runBlocking {
        val (a, b) = linked()
        a.musicPlaying = true
        b.directory.onNearbySeen(a.id)
        val transport = b.nearbyTo(a, clock = { 0L })
        val status = StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value)
        val update = OwnershipChanged(
            commandId = PeerMessage.newCommandId(), timestamp = System.currentTimeMillis(),
            senderPeerId = b.id.value, logicalDeviceId = HEADSET_ID.value,
            senderConnected = true, ownerPeerId = b.id.value, generation = 1,
        )

        assertEquals(CommandResult.Unreachable, transport.request(a.id, status, 1_000))
        val reply = transport.request(a.id, update, 1_000) as CommandResult.Reply
        assertTrue(reply.message is Ack)
        val nextUpdate = update.copy(commandId = PeerMessage.newCommandId(), generation = 2)
        assertEquals(CommandResult.Unreachable, transport.request(a.id, nextUpdate, 1_000))
        assertEquals(listOf(OpenPolicy(false, false), OpenPolicy(false, true)), a.opens.toList())
    }

    @Test
    fun `concurrent passive refreshes reserve one nearby attempt per peer`() = runBlocking {
        val (a, b) = linked()
        a.cachedSighting = false
        b.directory.onNearbySeen(a.id)
        val transport = b.nearbyTo(a, clock = { 0L })
        val start = CompletableDeferred<Unit>()
        val requests = List(64) {
            async(Dispatchers.Default) {
                start.await()
                val status = StatusRequest(PeerMessage.newCommandId(), System.currentTimeMillis(), b.id.value)
                transport.request(a.id, status, 1_000)
            }
        }

        start.complete(Unit)
        assertTrue(requests.awaitAll().all { it == CommandResult.Unreachable })
        assertEquals(listOf(OpenPolicy(false, false)), a.opens.toList())
        val decision = b.events.events.value.single { it.type == EventType.BLUETOOTH_OPERATION }
        assertEquals(a.id, decision.peerId)
        assertEquals("network unreachable", decision.details["reason"])
        assertEquals("cached only", decision.details["discovery"])
    }

    @Test
    fun `devices that can't be nearby are not looked for`() = runBlocking {
        val (a, b) = linked()
        val transport = b.nearbyTo(a, canBeNearby = false)

        assertFalse(transport.isReachable(a.id, 1_000))
        assertTrue(a.opens.isEmpty())
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
