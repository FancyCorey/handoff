package dev.handoff.core.handoff

import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.MappingReconciler
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The event-driven jobs that keep a host in sync with its peers. Shared by the Android and
 * Windows apps; each platform decides *when* to run them (see their runtimes).
 *
 *  - Push: when this host's connection to a mapped headset changes (also outside Handoff, e.g.
 *    via system Bluetooth settings or a third device stealing it), tell peers.
 *  - Reconcile: keep logical-device ids and host mappings consistent with peer reports.
 *  - Refresh: ask a peer for fresh status as soon as it appears on the network.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MeshSync(
    private val selfId: PeerId,
    private val devices: LogicalDeviceRepository,
    private val bluetooth: BluetoothAudioController,
    private val ownership: MeshOwnershipRepository,
    private val reconciler: MappingReconciler,
    private val broadcaster: OwnershipBroadcaster,
    private val coordinator: HandoffCoordinator,
    private val directory: PeerDirectory,
    private val trust: TrustedPeerRepository,
    /** Called when a logical id converged with a peer's, so settings can follow it. */
    private val onRekey: suspend (from: LogicalDeviceId, to: LogicalDeviceId) -> Unit = { _, _ -> },
    private val statusTimeoutMs: Long = 2_500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun launchIn(scope: CoroutineScope): List<Job> = listOf(
        scope.launch { pushLocalConnectionChanges() },
        scope.launch { reconcileMappings() },
        scope.launch { refreshPeersWhenTheyAppear(scope) },
    )

    suspend fun pushLocalConnectionChanges() {
        devices.devices
            .map { list -> list.mapNotNull { d -> d.localDeviceId?.let { d.logicalId to it } } }
            .distinctUntilChanged()
            .flatMapLatest { pairs ->
                if (pairs.isEmpty()) {
                    emptyFlow()
                } else {
                    merge(
                        *pairs.map { (logicalId, btId) ->
                            bluetooth.connectionState(btId)
                                .map { it == AudioConnectionState.CONNECTED }
                                .distinctUntilChanged()
                                .drop(1)
                                .map { logicalId to it }
                        }.toTypedArray(),
                    )
                }
            }
            .collect { (logicalId, connected) -> onLocalChange(logicalId, connected) }
    }

    internal suspend fun onLocalChange(logicalId: LogicalDeviceId, connected: Boolean) {
        val device = devices.find(logicalId) ?: return
        // A running or just-finished transfer announces itself; don't double-count the generation.
        val transfer = coordinator.transfers.value[logicalId]
        val recentlyAnnounced = transfer != null && (
            !transfer.phase.isTerminal ||
                (transfer.phase == TransferPhase.COMPLETE && clock() - transfer.startedAtMs < ANNOUNCE_DEDUP_MS)
            )
        if (connected && !recentlyAnnounced) {
            val generation = device.ownershipGeneration + 1
            devices.applyOwnership(logicalId, selfId, generation)
            broadcaster.announce(logicalId, senderConnected = true, owner = selfId, generation = generation)
        } else if (!connected) {
            broadcaster.announce(logicalId, senderConnected = false, owner = null, generation = device.ownershipGeneration)
        }
    }

    suspend fun reconcileMappings() {
        ownership.reports.debounce(500).collect { reports ->
            val changes = runCatching { reconciler.reconcile(reports, trust.peers.value.map { it.peerId }.toSet()) }
                .getOrDefault(emptyList())
            changes.filterIsInstance<MappingReconciler.Change.Rekey>().forEach { onRekey(it.from, it.to) }
        }
    }

    suspend fun refreshPeersWhenTheyAppear(scope: CoroutineScope) {
        var online = emptySet<PeerId>()
        directory.presence.collect { presence ->
            val now = presence.values.filter { it.online }.map { it.peerId }.toSet()
            val appeared = now - online
            online = now
            appeared.filter { trust.find(it) != null }.forEach { peer ->
                scope.launch { ownership.refreshPeer(peer, statusTimeoutMs) }
            }
        }
    }

    private companion object {
        const val ANNOUNCE_DEDUP_MS = 20_000L
    }
}
