package dev.handoff.app.di

import dev.handoff.core.bluetooth.DemoBluetoothAudioController
import dev.handoff.core.mesh.transport.DiscoveryTags
import dev.handoff.app.mesh.NetworkAddresses
import dev.handoff.core.mesh.transport.networkPrefix
import android.util.Log
import dev.handoff.app.BuildConfig
import dev.handoff.app.automation.PlaybackWatcher
import dev.handoff.app.feature.device.DeviceDetailViewModel
import dev.handoff.app.feature.device.MapDeviceViewModel
import dev.handoff.app.feature.diagnostics.BluetoothTestViewModel
import dev.handoff.app.feature.diagnostics.DiagnosticsViewModel
import dev.handoff.app.feature.home.HomeViewModel
import dev.handoff.app.feature.peers.AddPeerViewModel
import dev.handoff.app.feature.peers.PeersViewModel
import dev.handoff.app.feature.peers.ScanPeerViewModel
import dev.handoff.app.feature.settings.SettingsViewModel
import dev.handoff.app.feature.setup.SetupViewModel
import dev.handoff.app.feature.transfer.TransferViewModel
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.mesh.NetworkMonitor
import dev.handoff.app.mesh.NsdPeerDiscovery
import dev.handoff.app.persistence.HandoffDatabase
import dev.handoff.app.persistence.PrefsCompatibilityStore
import dev.handoff.app.persistence.RoomLogicalDeviceRepository
import dev.handoff.app.persistence.RoomTransferHistory
import dev.handoff.app.persistence.RoomTrustedPeerRepository
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.app.service.HandoffService
import dev.handoff.app.service.Notifications
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.bluetooth.CompatibilityStore
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothDiagnosticsSource
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.handoff.AudioReleaseHandler
import dev.handoff.core.handoff.AutoSwitchPolicy
import dev.handoff.core.handoff.DefaultHandoffCoordinator
import dev.handoff.core.handoff.DeviceLocks
import dev.handoff.core.handoff.HandoffCoordinator
import dev.handoff.core.handoff.HandoffPolicy
import dev.handoff.core.handoff.HandoffRequestHandler
import dev.handoff.core.handoff.OwnershipBroadcaster
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.TransferHistory
import dev.handoff.core.mesh.pairing.PairingClient
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.security.CommandGuard
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.transport.LanPeerTransport
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.mesh.transport.PeerServer
import dev.handoff.core.mesh.transport.PeerTransport
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.MappingReconciler
import dev.handoff.core.ownership.MeshOwnershipRepository
import dev.handoff.core.ownership.OwnershipRepository
import dev.handoff.core.ownership.OwnershipResolver
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

private val APP_SCOPE = named(HandoffService.APP_SCOPE)

val appModule = module {
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { HandoffDatabase.build(androidContext()) }
    single { SettingsRepository(androidContext(), get(APP_SCOPE)) }
    single { Notifications(androidContext()) }
    single<EventLog> { InMemoryEventLog(sink = { Log.i("Handoff", it.format()) }) }
    single { HandoffPolicy() }
    single { DeviceLocks() }
    single { PeerDirectory() }

    // Identity & persistence
    single { KeystoreIdentityProvider(androidContext()) } binds arrayOf(IdentityProvider::class)
    single { RoomTrustedPeerRepository(get<HandoffDatabase>().trustedPeers(), get(APP_SCOPE)) } binds arrayOf(TrustedPeerRepository::class)
    single { RoomLogicalDeviceRepository(get<HandoffDatabase>().logicalDevices(), get(APP_SCOPE)) } binds arrayOf(LogicalDeviceRepository::class)
    single { RoomTransferHistory(get<HandoffDatabase>().transferRecords()) } binds arrayOf(TransferHistory::class)
    single<CompatibilityStore> { PrefsCompatibilityStore(androidContext()) }

    // Bluetooth (the only module that touches android.bluetooth)
    single { AndroidBluetoothAudioController(androidContext(), get(), demo = demoBluetooth(androidContext())) } binds
        arrayOf(BluetoothAudioController::class, BluetoothDiagnosticsSource::class)

    // Peer mesh
    single { LanPeerTransport(get(), get(), get(), get()) } binds arrayOf(PeerTransport::class)
    single { PairingManager(get(), get(), get()) }
    single { PairingClient(get(), get(), get(), get()) }
    single { DiscoveryTags(get()) }
    single {
        val identity = get<IdentityProvider>()
        NsdPeerDiscovery(androidContext(), get(), get(), get()) { identity.identity().publicKey }
    }
    single { CommandGuard() }

    // Domain
    single { selfId(get()) }
    single { OwnershipResolver(get()) }
    single {
        val trust = get<TrustedPeerRepository>()
        MeshOwnershipRepository(get(), get(), { trust.peers.value.map { it.peerId } })
    } binds arrayOf(OwnershipRepository::class)
    single {
        val trust = get<TrustedPeerRepository>()
        OwnershipBroadcaster(get(), get(), { trust.peers.value.map { it.peerId } })
    }
    single { AudioReleaseHandler(get(), get(), get(), get(), get()) }
    single {
        val tablet = androidContext().resources.configuration.smallestScreenWidthDp >= 600
        HandoffRequestHandler(
            get(), get(), get(), get(), get(), get(), BuildConfig.VERSION_NAME,
            platform = if (tablet) Platforms.ANDROID_TABLET else Platforms.ANDROID_PHONE,
        )
    }
    single { PeerServer(get(), get(), get(), get<HandoffRequestHandler>(), get(), get(), get()) }
    single {
        val identity = get<KeystoreIdentityProvider>()
        MappingReconciler(get(), { identity.identity().displayName }, get())
    }
    single<HandoffCoordinator> {
        val trust = get<TrustedPeerRepository>()
        val directory = get<PeerDirectory>()
        DefaultHandoffCoordinator(
            selfId = get(),
            bluetooth = get(),
            transport = get(),
            ownership = get(),
            resolver = get(),
            devices = get(),
            locks = get(),
            events = get(),
            broadcaster = get(),
            history = get(),
            peerName = { peer -> trust.find(peer)?.displayName ?: "Unknown device" },
            onlinePeers = { directory.onlinePeers() },
            backgroundScope = get(APP_SCOPE),
            policy = get(),
        )
    }
    single { AutoSwitchPolicy() }

    // App runtime
    single {
        HandoffRuntime(
            appScope = get(APP_SCOPE),
            identity = get(),
            trust = get(),
            devices = get(),
            bluetooth = get(),
            directory = get(),
            server = get(),
            discovery = get(),
            ownership = get(),
            reconciler = get(),
            broadcaster = get(),
            coordinator = get(),
            events = get(),
            settings = get(),
            network = NetworkMonitor(androidContext()),
        )
    }
    single { HandoffActions(get(APP_SCOPE), get(), get(), get()) }
    single {
        val context = androidContext()
        OverviewRepository(
            get(APP_SCOPE), get(), get(), get(), get(), get(), get(), get(), get(),
            localNetworks = { NetworkAddresses.lanIpv4(context).mapNotNull(::networkPrefix).toSet() },
        )
    }
    single {
        val overview = get<OverviewRepository>()
        PlaybackWatcher(
            context = androidContext(),
            appScope = get(APP_SCOPE),
            settings = get(),
            devices = get(),
            bluetooth = get(),
            ownership = get(),
            resolver = get(),
            directory = get(),
            releaseHandler = get(),
            policy = get(),
            actions = get(),
            notifications = get(),
            peerName = overview::peerName,
            events = get(),
        )
    }

    // Screens
    viewModel { SetupViewModel(androidContext(), get(), get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get()) }
    viewModel { params -> DeviceDetailViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { MapDeviceViewModel(get(), get(), get(), get(), get()) }
    viewModel { PeersViewModel(get(), get(), get(), get(), get()) }
    viewModel { AddPeerViewModel(androidContext(), get(), get(), get(), get(), get(APP_SCOPE)) }
    viewModel { ScanPeerViewModel(get()) }
    viewModel { params -> TransferViewModel(params.get(), get(), get(), get()) }
    viewModel { SettingsViewModel(androidContext(), get(), get(), get()) }
    viewModel { DiagnosticsViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { BluetoothTestViewModel(get()) }
}

private fun selfId(identity: KeystoreIdentityProvider): PeerId = identity.identity().peerId

/**
 * Debug builds only: made-up headsets for screenshots, enabled by creating `files/demo` in the
 * app's private storage (`adb shell run-as dev.handoff.app.debug touch files/demo`). Each line
 * of the file may name a demo headset address that starts out connected here.
 */
private fun demoBluetooth(context: android.content.Context): DemoBluetoothAudioController? {
    if (!BuildConfig.DEBUG) return null
    val flag = java.io.File(context.filesDir, "demo")
    if (!flag.exists()) return null
    val connected = runCatching { flag.readLines() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return DemoBluetoothAudioController(connected)
}
