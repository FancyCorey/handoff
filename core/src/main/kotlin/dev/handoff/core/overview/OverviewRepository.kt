package dev.handoff.core.overview

import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.handoff.HandoffCoordinator
import dev.handoff.core.handoff.HandoffState
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.ownership.OwnershipResolver
import dev.handoff.core.ownership.PeerReport
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DeviceOverview(
    val device: LogicalAudioDevice,
    val localState: AudioConnectionState,
    val ownership: Ownership,
    /** Human-readable holder names, "This device" first. */
    val holders: List<String>,
    val transfer: HandoffState?,
) {
    val connectedHere: Boolean get() = localState == AudioConnectionState.CONNECTED
    val transferRunning: Boolean get() = transfer != null && !transfer.phase.isTerminal
}

data class PeerOverview(
    val peer: TrustedPeer,
    val online: Boolean,
    val lastContactMs: Long?,
    /** From the peer's last status reply, e.g. "android-phone", "windows"; null if not heard yet. */
    val platform: String?,
)

/** A peer-announced headset, used by the mapping screen to link the same headset. */
data class AnnouncedDevice(
    val logicalId: LogicalDeviceId,
    val displayName: String,
    val fingerprint: String?,
    val announcedBy: List<String>,
    val announcedByIds: List<PeerId>,
)

/** Everything the dashboard, details screen and Quick Settings tile display. */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewRepository(
    appScope: CoroutineScope,
    private val identity: IdentityProvider,
    devices: LogicalDeviceRepository,
    bluetooth: BluetoothAudioController,
    ownership: MeshOwnershipRepository,
    private val resolver: OwnershipResolver,
    directory: PeerDirectory,
    private val trust: TrustedPeerRepository,
    coordinator: HandoffCoordinator,
    /** How this host is named in holder lists ("This device", "This PC"). */
    private val thisDeviceLabel: String = THIS_DEVICE,
) {
    fun peerName(peer: PeerId): String = when (peer) {
        identity.identity().peerId -> thisDeviceLabel
        else -> trust.find(peer)?.displayName ?: "Unknown device"
    }

    private val localStates = devices.devices.flatMapLatest { list ->
        val mapped = list.filter { it.localDeviceId != null }
        if (mapped.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(mapped.map { d -> bluetooth.connectionState(d.localDeviceId!!).map { d.logicalId to it } }) { it.toMap() }
        }
    }

    val devices: StateFlow<List<DeviceOverview>> = combine(
        devices.devices,
        localStates,
        ownership.reports,
        directory.presence,
        coordinator.transfers,
    ) { list, states, reports, presence, transfers ->
        val online = presence.values.filter { it.online }.map { it.peerId }.toSet()
        list.map { device ->
            val state = states[device.logicalId] ?: AudioConnectionState.UNAVAILABLE
            val localConnected = when (state) {
                AudioConnectionState.CONNECTED -> true
                AudioConnectionState.UNAVAILABLE -> if (device.localDeviceId == null) false else null
                else -> false
            }
            val reportsForDevice: List<PeerReport> = reports.values.mapNotNull { it[device.logicalId] }
            val owner = resolver.resolve(device, localConnected, reportsForDevice, online)
            DeviceOverview(device, state, owner, holderNames(owner), transfers[device.logicalId])
        }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peers: StateFlow<List<PeerOverview>> = combine(trust.peers, directory.presence, ownership.peerInfo) { peers, presence, info ->
        peers.map {
            PeerOverview(it, presence[it.peerId]?.online == true, presence[it.peerId]?.lastContactMs, info[it.peerId]?.platform)
        }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val announced: StateFlow<List<AnnouncedDevice>> = ownership.reports.map { reports ->
        reports.flatMap { (peer, byId) -> byId.values.map { peer to it } }
            .groupBy { it.second.logicalId }
            .map { (id, entries) ->
                AnnouncedDevice(
                    logicalId = id,
                    displayName = entries.first().second.displayName ?: "Headset",
                    fingerprint = entries.firstNotNullOfOrNull { it.second.fingerprint },
                    announcedBy = entries.map { peerName(it.first) },
                    announcedByIds = entries.map { it.first },
                )
            }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun holderNames(owner: Ownership): List<String> {
        val self = identity.identity().peerId
        fun names(ids: Set<PeerId>) = ids.sortedBy { if (it == self) "" else peerName(it) }.map(::peerName)
        return when (owner) {
            Ownership.Local -> listOf(thisDeviceLabel)
            is Ownership.Peer -> listOf(peerName(owner.peerId))
            is Ownership.Multipoint -> names(owner.holders)
            is Ownership.Conflict -> names(owner.holders)
            else -> emptyList()
        }
    }

    companion object {
        const val THIS_DEVICE = "This device"
    }
}
