package dev.handoff.core.fakes

import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.DefaultHandoffCoordinator
import dev.handoff.core.handoff.DeviceLocks
import dev.handoff.core.handoff.HandoffPolicy
import dev.handoff.core.handoff.HandoffRequestHandler
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.InMemoryTransferHistory
import dev.handoff.core.handoff.OwnershipBroadcaster
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.mesh.security.SoftwareIdentityProvider
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.ownership.OwnershipResolver
import dev.handoff.core.store.InMemoryLogicalDeviceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.runBlocking

val HEADSET_ID = LogicalDeviceId("00000000-0000-0000-0000-00000000b0d5")

/**
 * A complete simulated Handoff host: real coordinator, release handler, request handler and
 * ownership repository, with fake Bluetooth and an in-memory transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestHost(
    val name: String,
    scope: TestScope,
    val policy: HandoffPolicy = HandoffPolicy(),
) {
    val id = PeerId("peer-$name")
    val clock: () -> Long = { scope.testScheduler.currentTime }
    val identity = SoftwareIdentityProvider(id, name)
    val bluetooth = FakeBluetoothAudioController(name)
    val transport = FakePeerTransport(id)
    val devices = InMemoryLogicalDeviceRepository()
    val locks = DeviceLocks()
    val events = InMemoryEventLog(clock = clock)
    val history = InMemoryTransferHistory()
    val linked = mutableSetOf<PeerId>()
    val localBt = BluetoothDeviceId("AA:BB:CC:DD:EE:${name.hashCode().and(0xff).toString(16).padStart(2, '0')}")

    val ownership = MeshOwnershipRepository(id, transport, { linked }, clock)
    val releaseHandler = AudioReleaseHandler(devices, bluetooth, locks, events, policy, clock)
    val handler = HandoffRequestHandler(identity, devices, bluetooth, releaseHandler, ownership, events, "test", clock)
    val coordinator = DefaultHandoffCoordinator(
        selfId = id,
        bluetooth = bluetooth,
        transport = transport,
        ownership = ownership,
        resolver = OwnershipResolver(id, clock = clock),
        devices = devices,
        locks = locks,
        events = events,
        broadcaster = OwnershipBroadcaster(id, transport, { linked }, clock = clock),
        history = history,
        peerName = { peer -> peer.value.removePrefix("peer-") },
        onlinePeers = { transport.reachable.toSet() },
        backgroundScope = scope.backgroundScope,
        policy = policy,
        clock = clock,
    )

    fun mapHeadset(allHosts: List<TestHost>, multipoint: Boolean = false, headset: SimulatedHeadset? = null) {
        runBlocking {
            devices.upsert(
                LogicalAudioDevice(
                    logicalId = HEADSET_ID,
                    displayName = "Buds",
                    deviceType = AudioDeviceKind.HEADPHONES,
                    fingerprint = "fp-buds",
                    localDeviceId = localBt,
                    multipoint = multipoint,
                    hostMappings = allHosts.map { HostMapping(it.id, "Buds") },
                ),
            )
        }
        headset?.attach(bluetooth, localBt)
    }

    fun link(other: TestHost) {
        transport.route(other.id, other.handler)
        linked += other.id
    }

    fun goOffline(other: TestHost) {
        transport.reachable -= other.id
    }

    suspend fun moveHere(trigger: TransferTrigger = TransferTrigger.MANUAL): HandoffResult =
        coordinator.moveToThisDevice(devices.find(HEADSET_ID)!!, trigger)

    companion object {
        /** Build fully linked hosts sharing one headset. */
        fun mesh(
            scope: TestScope,
            vararg names: String,
            multipoint: Boolean = false,
            policy: HandoffPolicy = HandoffPolicy(),
            headset: SimulatedHeadset = SimulatedHeadset(multipoint),
        ): Pair<List<TestHost>, SimulatedHeadset> {
            val hosts = names.map { TestHost(it, scope, policy) }
            hosts.forEach { h ->
                h.mapHeadset(hosts, multipoint, headset)
                hosts.filter { it !== h }.forEach { h.link(it) }
            }
            return hosts to headset
        }
    }
}
