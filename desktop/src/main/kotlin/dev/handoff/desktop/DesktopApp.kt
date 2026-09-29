package dev.handoff.desktop

import dev.handoff.core.mesh.transport.EndpointMemory
import dev.handoff.core.bluetooth.DemoBluetoothAudioController
import dev.handoff.core.mesh.transport.DiscoveryTags
import dev.handoff.core.mesh.transport.networkPrefix
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.DefaultHandoffCoordinator
import dev.handoff.core.handoff.DeviceLocks
import dev.handoff.core.handoff.HandoffPolicy
import dev.handoff.core.handoff.HandoffRequestHandler
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.MeshSync
import dev.handoff.core.handoff.OwnershipBroadcaster
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.mesh.pairing.PairingClient
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.transport.LanPeerTransport
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.mesh.transport.PeerServer
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.ownership.MappingReconciler
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.ownership.OwnershipResolver
import dev.handoff.desktop.bluetooth.WindowsBluetoothAudioController
import dev.handoff.desktop.mesh.JmdnsDiscovery
import dev.handoff.desktop.store.DesktopIdentityProvider
import dev.handoff.desktop.store.DesktopTransferHistory
import dev.handoff.desktop.store.FileLogicalDeviceRepository
import dev.handoff.desktop.store.FileTrustedPeerRepository
import dev.handoff.desktop.store.SettingsStore
import dev.handoff.desktop.store.defaultDataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Composition root of the Windows app. Everything except Bluetooth, storage and discovery is
 * the same :core code the Android app runs, so both speak exactly the same protocol.
 */
class DesktopApp(dataDir: File = defaultDataDir()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val version: String = System.getProperty("handoff.version") ?: "dev"

    val events = InMemoryEventLog()
    val settings = SettingsStore(dataDir)
    val updates = DesktopUpdates(version, scope, settings)
    val identity = DesktopIdentityProvider(dataDir).apply {
        // Demo/screenshot runs show a made-up PC name instead of the real computer name.
        System.getProperty("handoff.demo.name")?.takeIf { it.isNotBlank() }?.let(::rename)
    }
    val selfId: PeerId = identity.identity().peerId
    val trust = FileTrustedPeerRepository(dataDir)
    val devices = FileLogicalDeviceRepository(dataDir)
    val history = DesktopTransferHistory()
    val bluetooth = WindowsBluetoothAudioController(scope, settings, demo = demoBluetooth())
    val directory = PeerDirectory().also {
        // Last working peer addresses survive restarts, for networks where mDNS is blocked.
        EndpointMemory(File(dataDir, "peer-endpoints.json"))
            .attach(it, scope) { JmdnsDiscovery.lanAddresses().mapNotNull { a -> a.hostAddress?.let(::networkPrefix) }.toSet() }
    }
    private val transport: LanPeerTransport = LanPeerTransport(identity, trust, directory, events, listenPort = { server.port.value })
    val pairing = PairingManager(identity, trust, events)
    val pairingClient: PairingClient = PairingClient(identity, trust, directory, events, listenPort = { server.port.value })

    /** Windows reinstalls audio endpoints on connect, which takes longer than on Android. */
    private val policy = HandoffPolicy(verifyTimeoutMs = 15_000, retryDelayMs = 2_000, remoteDisconnectVerifyMs = 6_000)
    private val locks = DeviceLocks()
    private val trustedIds = { trust.peers.value.map { it.peerId } }
    val ownership = MeshOwnershipRepository(selfId, transport, trustedIds)
    private val resolver = OwnershipResolver(selfId)
    private val releaseHandler = AudioReleaseHandler(devices, bluetooth, locks, events, policy)
    private val handler = HandoffRequestHandler(
        identity, devices, bluetooth, releaseHandler, ownership, events, version, platform = Platforms.WINDOWS,
    )
    val server = PeerServer(identity, trust, pairing, handler, directory, events)
    private val broadcaster = OwnershipBroadcaster(selfId, transport, trustedIds)
    val coordinator = DefaultHandoffCoordinator(
        selfId = selfId,
        bluetooth = bluetooth,
        transport = transport,
        ownership = ownership,
        resolver = resolver,
        devices = devices,
        locks = locks,
        events = events,
        broadcaster = broadcaster,
        history = history,
        peerName = { peer -> trust.find(peer)?.displayName ?: "Unknown device" },
        onlinePeers = { directory.onlinePeers() },
        backgroundScope = scope,
        policy = policy,
    )
    private val reconciler = MappingReconciler(selfId, { identity.identity().displayName }, devices)
    private val discovery = JmdnsDiscovery(directory, events, DiscoveryTags(trust)) { identity.identity().publicKey }
    val overview = OverviewRepository(scope, identity, devices, bluetooth, ownership, resolver, directory, trust, coordinator, thisDeviceLabel = "This PC",
        localNetworks = { JmdnsDiscovery.lanAddresses().mapNotNull { it.hostAddress?.let(::networkPrefix) }.toSet() },
    )

    private val sync = MeshSync(
        selfId, devices, bluetooth, ownership, reconciler, broadcaster, coordinator, directory, trust,
        onRekey = { from, to ->
            if (settings.settings.value.preferredDevice == from.value) settings.update { it.copy(preferredDevice = to.value) }
        },
    )
    private val jobs = mutableListOf<Job>()

    fun start() {
        updates.maybeAutoCheck()
        bluetooth.start()
        // `-Dhandoff.port` for when 47474 is taken (e.g. an emulator port forward during testing).
        val port = server.start(scope, System.getProperty("handoff.port")?.toIntOrNull() ?: PeerServer.DEFAULT_PORT)
        events.record(EventType.SERVICE_STATE, details = mapOf("state" to "started", "port" to port.toString()))
        scope.launch(Dispatchers.IO) { discovery.start(selfId, port) }
        jobs += sync.launchIn(scope)
        jobs += scope.launch(Dispatchers.IO) { followNetworkChanges(port) }
        // A PC is not battery-constrained: keep peer status fresh in the background too.
        jobs += scope.launch {
            while (isActive) {
                refreshNow()
                delay(REFRESH_MS)
            }
        }
    }

    /**
     * JmDNS is bound to the PC's current network addresses. When they change (another Wi-Fi,
     * Ethernet, hotspot), drop the old peer addresses and restart discovery on the new network.
     * Links are bound to identity keys, not networks, so nothing has to be re-linked.
     */
    private suspend fun followNetworkChanges(port: Int) {
        var current = lanAddressKey()
        while (scope.isActive) {
            delay(NETWORK_POLL_MS)
            val next = lanAddressKey()
            if (next != current) {
                current = next
                events.record(EventType.SERVICE_STATE, details = mapOf("state" to "network changed", "lanAddresses" to next.size.toString()))
                directory.onNetworkChanged(next.mapNotNull(::networkPrefix).toSet())
                discovery.start(selfId, port)
                refreshNow()
            }
        }
    }

    private fun lanAddressKey(): Set<String> = JmdnsDiscovery.lanAddresses().mapNotNull { it.hostAddress }.toSet()

    suspend fun refreshNow() {
        runCatching { ownership.refresh(STATUS_TIMEOUT_MS) }
        directory.refreshOnlineFlags()
        bluetooth.refresh()
    }

    fun moveHere(id: LogicalDeviceId, trigger: TransferTrigger = TransferTrigger.MANUAL, onResult: (HandoffResult) -> Unit = {}) {
        scope.launch {
            val device = devices.find(id)
            onResult(if (device == null) HandoffResult.MissingLocalMapping else coordinator.moveToThisDevice(device, trigger))
        }
    }

    /** Stops a running move for [id]; returns false if none was running. */
    fun cancelMove(id: LogicalDeviceId): Boolean = coordinator.cancel(id)

    fun shutdown() {
        jobs.forEach { it.cancel() }
        runCatching { discovery.stop() }
        server.stop()
        scope.cancel()
    }

    private companion object {
        const val REFRESH_MS = 15_000L
        const val STATUS_TIMEOUT_MS = 2_500L
        const val NETWORK_POLL_MS = 3_000L
    }
}

/**
 * `-Dhandoff.demo=true` swaps in made-up headsets for screenshots; `-Dhandoff.demo.connected`
 * lists demo addresses that start out connected to this PC.
 */
private fun demoBluetooth(): DemoBluetoothAudioController? {
    if (System.getProperty("handoff.demo") != "true") return null
    val connected = System.getProperty("handoff.demo.connected").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return DemoBluetoothAudioController(connected)
}
