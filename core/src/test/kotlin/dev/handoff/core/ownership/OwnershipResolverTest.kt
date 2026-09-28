package dev.handoff.core.ownership

import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnershipResolverTest {
    private val self = PeerId("self")
    private val tab = PeerId("tab")
    private val laptop = PeerId("laptop")
    private var now = 1_000_000L
    private val resolver = OwnershipResolver(self, staleAfterMs = 60_000, clock = { now })
    private val id = LogicalDeviceId("buds")

    private fun device(multipoint: Boolean = false, hosts: List<PeerId> = listOf(self, tab), lastKnown: PeerId? = null) =
        LogicalAudioDevice(
            logicalId = id,
            displayName = "Buds",
            deviceType = AudioDeviceKind.HEADPHONES,
            fingerprint = "fp",
            localDeviceId = BluetoothDeviceId("00:11:22:33:44:55"),
            multipoint = multipoint,
            lastKnownOwner = lastKnown,
            hostMappings = hosts.map { HostMapping(it, "Buds") },
        )

    private fun report(peer: PeerId, connected: Boolean, ageMs: Long = 0) =
        PeerReport(peer, id, connected, generation = 1, receivedAtMs = now - ageMs)

    @Test
    fun `local connection is LOCAL`() {
        assertEquals(Ownership.Local, resolver.resolve(device(), true, listOf(report(tab, false)), setOf(tab)))
    }

    @Test
    fun `single remote holder is PEER`() {
        assertEquals(Ownership.Peer(tab), resolver.resolve(device(), false, listOf(report(tab, true)), setOf(tab)))
    }

    @Test
    fun `all hosts reporting disconnected is NONE`() {
        assertEquals(Ownership.None, resolver.resolve(device(), false, listOf(report(tab, false)), setOf(tab)))
    }

    @Test
    fun `silent mapped host makes ownership UNKNOWN with last-known hint`() {
        val result = resolver.resolve(device(lastKnown = tab), false, emptyList(), emptySet())
        assertEquals(Ownership.Unknown(tab), result)
    }

    @Test
    fun `stale reports are ignored`() {
        val result = resolver.resolve(device(), false, listOf(report(tab, true, ageMs = 120_000)), setOf(tab))
        assertEquals(Ownership.Unknown(null), result)
    }

    @Test
    fun `reports from offline peers are ignored`() {
        val result = resolver.resolve(device(), false, listOf(report(tab, true)), emptySet())
        assertEquals(Ownership.Unknown(null), result)
    }

    @Test
    fun `unreadable local state is UNKNOWN`() {
        assertEquals(Ownership.Unknown(null), resolver.resolve(device(), null, listOf(report(tab, false)), setOf(tab)))
    }

    @Test
    fun `several holders of a multipoint headset is MULTIPOINT`() {
        val result = resolver.resolve(
            device(multipoint = true, hosts = listOf(self, tab, laptop)),
            true,
            listOf(report(tab, true), report(laptop, false)),
            setOf(tab, laptop),
        )
        assertEquals(Ownership.Multipoint(setOf(self, tab)), result)
    }

    @Test
    fun `several holders of a single-point headset is CONFLICT`() {
        val result = resolver.resolve(
            device(hosts = listOf(self, tab, laptop)),
            false,
            listOf(report(tab, true), report(laptop, true)),
            setOf(tab, laptop),
        )
        assertEquals(Ownership.Conflict(setOf(tab, laptop)), result)
    }

    @Test
    fun `reports about other headsets do not count`() {
        val other = PeerReport(tab, LogicalDeviceId("other"), true, 1, now)
        assertEquals(Ownership.None, resolver.resolve(device(hosts = listOf(self)), false, listOf(other), setOf(tab)))
    }
}
