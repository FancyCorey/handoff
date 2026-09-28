package dev.handoff.core.model

import dev.handoff.core.bluetooth.OperationThrottle
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.mesh.transport.PeerDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilitiesTest {

    @Test
    fun `addresses are redacted to the last two octets`() {
        assertEquals("**:**:**:**:EE:FF", Redaction.address("aa:bb:cc:dd:ee:ff"))
        assertEquals("**:**:**:**:EE:FF", BluetoothDeviceId("AA:BB:CC:DD:EE:FF").toString())
        assertEquals("device **:**:**:**:44:55 failed", Redaction.scrub("device 00:11:22:33:44:55 failed"))
    }

    @Test
    fun `event log never stores full addresses`() {
        val log = InMemoryEventLog()
        log.record(EventType.CONNECT_FAILED, details = mapOf("detail" to "connect(12:34:56:78:9A:BC) returned false"))
        val detail = log.events.value.single().details.getValue("detail")
        assertFalse(detail.contains("12:34:56"))
        assertTrue(detail.contains("9A:BC"))
    }

    @Test
    fun `fingerprint is stable, case-insensitive and does not contain the address`() {
        val a = DeviceFingerprint.of("aa:bb:cc:dd:ee:ff")
        assertEquals(a, DeviceFingerprint.of("AA:BB:CC:DD:EE:FF"))
        assertNotEquals(a, DeviceFingerprint.of("AA:BB:CC:DD:EE:FE"))
        assertEquals(24, a.length)
        assertFalse(a.contains("aabbcc"))
    }

    @Test
    fun `throttle limits operations per window`() {
        var now = 0L
        val throttle = OperationThrottle(maxOperations = 3, windowMs = 1_000, clock = { now })
        repeat(3) { assertTrue(throttle.tryAcquire()) }
        assertFalse(throttle.tryAcquire())
        now = 1_000
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `peer directory tracks online state and prefers the last good endpoint`() {
        var now = 0L
        val directory = PeerDirectory(clock = { now }, onlineTtlMs = 1_000)
        val peer = PeerId("p")
        directory.onDiscovered(peer, "10.0.0.2", 1)
        assertTrue(directory.isOnline(peer))
        directory.onContactSucceeded(peer, "10.0.0.3", 2)
        assertEquals("10.0.0.3", directory.endpointsFor(peer).first().host)
        directory.onContactFailed(peer)
        assertFalse(directory.isOnline(peer))
        now = 10
        directory.onContactSucceeded(peer, "10.0.0.3", 2)
        directory.onDiscoveryLost(peer)
        assertTrue(directory.isOnline(peer))
        now = 5_000
        assertFalse(directory.isOnline(peer))
    }

    @Test
    fun `inbound sessions add a lower-priority fallback endpoint`() {
        val directory = PeerDirectory(clock = { 0L })
        val peer = PeerId("p")
        directory.onInboundContact(peer, "192.168.1.7", 47_474)
        assertEquals(listOf("192.168.1.7:47474"), directory.endpointsFor(peer).map { "${it.host}:${it.port}" })
        directory.onDiscovered(peer, "192.168.1.8", 5_000)
        assertEquals("192.168.1.8", directory.endpointsFor(peer).first().host)
        assertTrue(directory.isOnline(peer))
    }

    @Test
    fun `network change forgets old addresses but keeps peers`() {
        val directory = PeerDirectory(clock = { 0L })
        val peer = PeerId("p")
        directory.onDiscovered(peer, "192.168.1.8", 47_474)
        directory.onContactSucceeded(peer, "192.168.1.8", 47_474)
        directory.onNetworkChanged()
        assertTrue(directory.endpointsFor(peer).isEmpty())
        assertFalse(directory.isOnline(peer))
        directory.onDiscovered(peer, "10.0.0.5", 47_474)
        assertEquals("10.0.0.5", directory.endpointsFor(peer).single().host)
        assertTrue(directory.isOnline(peer))
    }
}
