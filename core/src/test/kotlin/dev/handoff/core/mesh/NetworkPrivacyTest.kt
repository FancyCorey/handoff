package dev.handoff.core.mesh

import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.transport.ConnectionPolicy
import dev.handoff.core.mesh.transport.DiscoveryTags
import dev.handoff.core.mesh.transport.EndpointSource
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.store.InMemoryTrustedPeerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkPrivacyTest {
    private val day = 86_400_000L
    private val peerKey = Crypto.generateEcKeyPair().public.encoded
    private val peer = TrustedPeer(PeerId.random(), "Test phone", peerKey, 0)

    @Test
    fun `advertised name rotates daily and never contains the peer id`() {
        var now = 20_000 * day + 1_000
        val tags = DiscoveryTags(InMemoryTrustedPeerRepository(listOf(peer))) { now }
        val today = tags.instanceName(peerKey)
        assertFalse(today.contains(peer.peerId.value))
        assertEquals(DiscoveryTags.PREFIX.length + DiscoveryTags.TAG_CHARS, today.length)
        now += day
        assertNotEquals(today, tags.instanceName(peerKey))
    }

    @Test
    fun `linked peers resolve across midnight and strangers do not`() {
        var now = 20_000 * day
        val tags = DiscoveryTags(InMemoryTrustedPeerRepository(listOf(peer))) { now }
        val yesterdayName = tags.instanceName(peerKey)
        now += day
        assertEquals(peer.peerId, tags.resolve(yesterdayName))
        assertEquals(peer.peerId, tags.resolve("$yesterdayName (2)"))
        val stranger = Crypto.generateEcKeyPair().public.encoded
        assertNull(tags.resolve(tags.instanceName(stranger)))
        assertNull(tags.resolve("printer-1234"))
    }

    @Test
    fun `legacy names resolve only for linked peers`() {
        val tags = DiscoveryTags(InMemoryTrustedPeerRepository(listOf(peer)))
        assertEquals(peer.peerId, tags.resolve("handoff-" + peer.peerId.value))
        assertNull(tags.resolve("handoff-" + PeerId.random().value))
    }

    @Test
    fun `only local network addresses are admitted`() {
        val policy = ConnectionPolicy()
        listOf("192.168.1.5", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "fd00::1", "fe80::1").forEach {
            assertTrue(it, policy.admit(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "100.64.1.1", "2001:4860::8888").forEach {
            assertFalse(it, policy.admit(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `repeated failed handshakes block an address for a while`() {
        var now = 0L
        val policy = ConnectionPolicy(clock = { now }, maxFailuresPerWindow = 3, blockMs = 60_000)
        val attacker = InetAddress.getByName("192.168.1.66")
        val owner = InetAddress.getByName("192.168.1.10")
        repeat(3) {
            assertTrue(policy.admit(attacker))
            policy.onHandshakeFailed(attacker)
        }
        assertFalse(policy.admit(attacker))
        assertTrue(policy.admit(owner))
        now += 61_000
        assertTrue(policy.admit(attacker))
    }

    @Test
    fun `connection bursts are limited per address`() {
        var now = 0L
        val policy = ConnectionPolicy(clock = { now }, maxConnectionsPerWindow = 5, windowMs = 10_000)
        val host = InetAddress.getByName("192.168.1.66")
        repeat(5) { assertTrue(policy.admit(host)) }
        assertFalse(policy.admit(host))
        now += 10_000
        assertTrue(policy.admit(host))
    }

    @Test
    fun `returning to a known network restores the last working address`() {
        val directory = PeerDirectory()
        val id = peer.peerId
        directory.onContactSucceeded(id, "192.168.1.20", 47474)
        directory.onNetworkChanged(setOf("10.0.0"))
        directory.onContactSucceeded(id, "10.0.0.7", 47474)
        directory.onNetworkChanged(setOf("192.168.1"))
        val endpoints = directory.endpointsFor(id)
        assertEquals(listOf("192.168.1.20"), endpoints.map { it.host })
        assertEquals(EndpointSource.LAST_SUCCESS, endpoints.single().source)
        directory.onNetworkChanged(setOf("172.20.10"))
        assertTrue(directory.endpointsFor(id).isEmpty())
    }

    @Test
    fun `remembered addresses survive a restart and local networks come first`() {
        val file = java.io.File.createTempFile("endpoints", ".json").apply { deleteOnExit() }
        val id = peer.peerId
        val before = PeerDirectory()
        before.onContactSucceeded(id, "192.168.1.20", 47474)
        before.onNetworkChanged(setOf("10.0.0"))
        before.onContactSucceeded(id, "10.0.0.7", 47474)
        dev.handoff.core.mesh.transport.EndpointMemory(file).save(before.knownNetworks())

        val after = PeerDirectory()
        after.restore(dev.handoff.core.mesh.transport.EndpointMemory(file).load(), localNetworks = setOf("192.168.1"))
        val endpoints = after.endpointsFor(id)
        assertEquals(listOf("192.168.1.20", "10.0.0.7"), endpoints.map { it.host })
        assertEquals(listOf(EndpointSource.LAST_SUCCESS, EndpointSource.REMEMBERED), endpoints.map { it.source })
        assertFalse(after.isOnline(id))
    }

    @Test
    fun `a damaged endpoint file is ignored`() {
        val file = java.io.File.createTempFile("endpoints", ".json").apply { writeText("{not json"); deleteOnExit() }
        assertTrue(dev.handoff.core.mesh.transport.EndpointMemory(file).load().isEmpty())
    }
}
