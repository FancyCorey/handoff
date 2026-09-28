package dev.handoff.core.ownership

import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.InMemoryLogicalDeviceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingReconcilerTest {
    private val self = PeerId("self")
    private val tab = PeerId("tab")

    private fun local(id: String, fingerprint: String = "fp", hosts: List<HostMapping> = emptyList()) = LogicalAudioDevice(
        logicalId = LogicalDeviceId(id),
        displayName = "Buds",
        deviceType = AudioDeviceKind.HEADPHONES,
        fingerprint = fingerprint,
        localDeviceId = BluetoothDeviceId("00:11:22:33:44:55"),
        hostMappings = hosts,
    )

    private fun reports(vararg r: PeerReport) = r.groupBy { it.peerId }.mapValues { (_, v) -> v.associateBy { it.logicalId } }

    @Test
    fun `same headset under a smaller peer id is adopted`() = runTest {
        val repo = InMemoryLogicalDeviceRepository(listOf(local("bbbb")))
        val reconciler = MappingReconciler(self, { "Phone" }, repo)

        reconciler.reconcile(reports(PeerReport(tab, LogicalDeviceId("aaaa"), false, 0, 0, "Buds", "fp")), setOf(tab))

        assertNull(repo.find(LogicalDeviceId("bbbb")))
        val adopted = repo.find(LogicalDeviceId("aaaa"))!!
        assertEquals(setOf(self, tab), adopted.hostMappings.map { it.hostId }.toSet())
    }

    @Test
    fun `larger peer id is not adopted (the peer converges instead)`() = runTest {
        val repo = InMemoryLogicalDeviceRepository(listOf(local("aaaa")))
        MappingReconciler(self, { "Phone" }, repo)
            .reconcile(reports(PeerReport(tab, LogicalDeviceId("zzzz"), false, 0, 0, "Buds", "fp")), setOf(tab))
        assertEquals(listOf(LogicalDeviceId("aaaa")), repo.devices.value.map { it.logicalId })
    }

    @Test
    fun `different headsets with the same name are never merged`() = runTest {
        val repo = InMemoryLogicalDeviceRepository(listOf(local("bbbb", fingerprint = "fp-1")))
        MappingReconciler(self, { "Phone" }, repo)
            .reconcile(reports(PeerReport(tab, LogicalDeviceId("aaaa"), false, 0, 0, "Buds", "fp-2")), setOf(tab))
        assertEquals(listOf(LogicalDeviceId("bbbb")), repo.devices.value.map { it.logicalId })
    }

    @Test
    fun `hosts that stop reporting a headset are dropped, silent hosts are kept`() = runTest {
        val laptop = PeerId("laptop")
        val repo = InMemoryLogicalDeviceRepository(
            listOf(local("aaaa", hosts = listOf(HostMapping(self, "Phone"), HostMapping(tab, "Buds"), HostMapping(laptop, "Buds")))),
        )
        // tab reported a snapshot without the headset; laptop said nothing.
        val snapshot = mapOf(tab to emptyMap<LogicalDeviceId, PeerReport>())
        MappingReconciler(self, { "Phone" }, repo).reconcile(snapshot, setOf(tab, laptop))
        assertEquals(setOf(self, laptop), repo.find(LogicalDeviceId("aaaa"))!!.hostMappings.map { it.hostId }.toSet())
    }

    @Test
    fun `unlinked peers are removed from host mappings`() = runTest {
        val repo = InMemoryLogicalDeviceRepository(listOf(local("aaaa", hosts = listOf(HostMapping(self, "Phone"), HostMapping(tab, "Buds")))))
        MappingReconciler(self, { "Phone" }, repo).reconcile(emptyMap(), trustedPeers = emptySet())
        assertEquals(listOf(self), repo.find(LogicalDeviceId("aaaa"))!!.hostMappings.map { it.hostId })
    }
}
