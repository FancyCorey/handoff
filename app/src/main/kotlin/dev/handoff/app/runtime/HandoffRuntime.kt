package dev.handoff.app.runtime

import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.mesh.NetworkMonitor
import dev.handoff.app.mesh.NsdPeerDiscovery
import dev.handoff.app.persistence.RoomLogicalDeviceRepository
import dev.handoff.app.persistence.RoomTrustedPeerRepository
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.handoff.HandoffCoordinator
import dev.handoff.core.handoff.MeshSync
import dev.handoff.core.handoff.OwnershipBroadcaster
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.mesh.transport.PeerServer
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.MappingReconciler
import dev.handoff.core.ownership.MeshOwnershipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the lifetime of Handoff's networking: the peer server, LAN discovery, and the
 * event-driven jobs that keep peers in sync. Started while *anyone* holds it — the foreground
 * service (background operation) and/or the visible UI — and stopped when nobody does.
 *
 * There is no background polling: peers are refreshed when they appear on the LAN, when this
 * host's connection state changes (pushed to peers), and periodically only while the UI is
 * visible.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class HandoffRuntime(
    private val appScope: CoroutineScope,
    private val identity: KeystoreIdentityProvider,
    private val trust: RoomTrustedPeerRepository,
    private val devices: RoomLogicalDeviceRepository,
    private val bluetooth: AndroidBluetoothAudioController,
    private val directory: PeerDirectory,
    private val server: PeerServer,
    private val discovery: NsdPeerDiscovery,
    private val ownership: MeshOwnershipRepository,
    private val reconciler: MappingReconciler,
    private val broadcaster: OwnershipBroadcaster,
    private val coordinator: HandoffCoordinator,
    private val events: EventLog,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {
    private val lock = Mutex()
    private val holders = mutableSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var uiRefreshJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val selfId: PeerId get() = identity.identity().peerId

    private val sync = MeshSync(
        selfId = identity.identity().peerId,
        devices = devices,
        bluetooth = bluetooth,
        ownership = ownership,
        reconciler = reconciler,
        broadcaster = broadcaster,
        coordinator = coordinator,
        directory = directory,
        trust = trust,
        // Keep settings pointing at the same headset when its logical id converges with a peer's.
        onRekey = { from, to -> if (settings.current().preferredDevice == from) settings.setPreferredDevice(to) },
        statusTimeoutMs = STATUS_TIMEOUT_MS,
    )

    suspend fun acquire(holder: String) = lock.withLock {
        holders += holder
        if (!_running.value) startLocked()
    }

    suspend fun release(holder: String) = lock.withLock {
        holders -= holder
        if (holders.isEmpty() && _running.value) stopLocked()
    }

    /** While the UI is visible, keep presence and ownership fresh every few seconds. */
    fun setUiVisible(visible: Boolean) {
        uiRefreshJob?.cancel()
        uiRefreshJob = if (!visible) {
            null
        } else {
            appScope.launch {
                while (isActive) {
                    refreshNow()
                    delay(UI_REFRESH_MS)
                }
            }
        }
    }

    suspend fun refreshNow() {
        if (!_running.value) return
        runCatching { ownership.refresh(STATUS_TIMEOUT_MS) }
        directory.refreshOnlineFlags()
        bluetooth.refresh()
    }

    private suspend fun startLocked() {
        trust.awaitLoaded()
        devices.awaitLoaded()
        settings.repairPreferred(devices.devices.value.filter { it.localDeviceId != null }.map { it.logicalId }.toSet())
        val port = server.start(appScope)
        discovery.start(selfId, port)
        _running.value = true
        events.record(EventType.SERVICE_STATE, details = mapOf("state" to "started", "port" to port.toString()))

        jobs += sync.launchIn(appScope)
        jobs += appScope.launch { followNetworkChanges(port) }
        jobs += appScope.launch { refreshNow() }
    }

    /**
     * On a different Wi-Fi the old addresses and the old mDNS registration are useless: drop
     * them, re-advertise and re-discover on the new network. Links survive; nothing to redo.
     */
    private suspend fun followNetworkChanges(port: Int) {
        network.lanAddresses().drop(1).collect { addresses ->
            events.record(EventType.SERVICE_STATE, details = mapOf("state" to "network changed", "lanAddresses" to addresses.size.toString()))
            directory.onNetworkChanged()
            discovery.start(selfId, port)
            refreshNow()
        }
    }

    private fun stopLocked() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        discovery.stop()
        server.stop()
        _running.value = false
        events.record(EventType.SERVICE_STATE, details = mapOf("state" to "stopped"))
    }

    private companion object {
        const val UI_REFRESH_MS = 10_000L
        const val STATUS_TIMEOUT_MS = 2_500L
    }
}
