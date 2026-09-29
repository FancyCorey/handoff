package dev.handoff.app

import dev.handoff.app.update.AppUpdates
import dev.handoff.core.text.HandoffTexts
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.handoff.app.feature.device.DeviceDetailScreen
import dev.handoff.app.feature.device.MapDeviceScreen
import dev.handoff.app.feature.diagnostics.BluetoothTestScreen
import dev.handoff.app.feature.diagnostics.DiagnosticsScreen
import dev.handoff.app.feature.home.HomeScreen
import dev.handoff.app.feature.peers.AddPeerScreen
import dev.handoff.app.feature.peers.PeersScreen
import dev.handoff.app.feature.peers.ScanPeerScreen
import dev.handoff.app.feature.settings.SettingsScreen
import dev.handoff.app.feature.setup.SetupScreen
import dev.handoff.app.feature.transfer.TransferScreen
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.app.service.HandoffService
import dev.handoff.app.ui.HandoffTheme
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.mesh.pairing.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

class MainActivity : ComponentActivity() {
    private val runtime: HandoffRuntime by inject()
    private val settings: SettingsRepository by inject()
    private val bluetooth: AndroidBluetoothAudioController by inject()
    private val pairing: PairingManager by inject()
    private val updates: AppUpdates by inject()
    private val appScope: CoroutineScope by inject(named(HandoffService.APP_SCOPE))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) updates.maybeAutoCheck()
        lifecycleScope.launch {
            val initial = settings.current()
            setContent { HandoffTheme { App(startAtSetup = !initial.onboardingComplete) } }
        }
    }

    override fun onStart() {
        super.onStart()
        bluetooth.refresh()
        lifecycleScope.launch {
            runtime.acquire(UI_HOLDER)
            runtime.setUiVisible(true)
            val s = settings.current()
            if (s.onboardingComplete && s.backgroundEnabled) HandoffService.start(applicationContext)
        }
    }

    override fun onStop() {
        runtime.setUiVisible(false)
        // Not lifecycleScope: it may be cancelled before the release runs, leaking the hold.
        appScope.launch { withContext(NonCancellable) { runtime.release(UI_HOLDER) } }
        super.onStop()
    }

    @Composable
    private fun App(startAtSetup: Boolean) {
        val nav = rememberNavController()
        val pending by pairing.pendingApproval.collectAsStateWithLifecycle()

        NavHost(nav, startDestination = if (startAtSetup) Routes.SETUP else Routes.HOME) {
            composable(Routes.SETUP) {
                SetupScreen(onDone = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenDevice = { nav.navigate(Routes.device(it)) },
                    onMoveStarted = { nav.navigate(Routes.transfer(it)) },
                    onOpenTransfer = { nav.navigate(Routes.transferProgress(it)) },
                    onMapDevice = { nav.navigate(Routes.MAP) },
                    onPeers = { nav.navigate(Routes.PEERS) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(
                Routes.DEVICE,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                DeviceDetailScreen(
                    logicalId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onMoveStarted = { nav.navigate(Routes.transfer(it)) },
                    onMap = { nav.navigate(Routes.MAP) },
                )
            }
            composable(Routes.MAP) { MapDeviceScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PEERS) {
                PeersScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(Routes.ADD_PEER) },
                    onScan = { nav.navigate(Routes.SCAN_PEER) },
                )
            }
            composable(Routes.ADD_PEER) { AddPeerScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SCAN_PEER) { ScanPeerScreen(onBack = { nav.popBackStack() }) }
            composable(
                Routes.TRANSFER,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("view") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                TransferScreen(
                    logicalId = entry.arguments?.getString("id").orEmpty(),
                    viewOnly = entry.arguments?.getBoolean("view") ?: false,
                    onDone = { nav.popBackStack() },
                    onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { nav.popBackStack() }, onBluetoothTest = { nav.navigate(Routes.BT_TEST) })
            }
            composable(Routes.BT_TEST) { BluetoothTestScreen(onBack = { nav.popBackStack() }) }
        }

        pending?.let { request ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Link ${request.displayName}?") },
                text = {
                    Column {
                        Text("Only continue if the other device shows this code:")
                        Text(
                            request.sas.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(HandoffTexts.LINK_WARNING, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { pairing.respond(true) }) { Text("Link") } },
                dismissButton = { TextButton(onClick = { pairing.respond(false) }) { Text("Decline") } },
            )
        }
    }

    private companion object {
        const val UI_HOLDER = "ui"
    }
}

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val DEVICE = "device/{id}"
    const val MAP = "map"
    const val PEERS = "peers"
    const val ADD_PEER = "peers/add"
    const val SCAN_PEER = "peers/scan"
    const val TRANSFER = "transfer/{id}?view={view}"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val BT_TEST = "diagnostics/bluetooth"

    fun device(id: String) = "device/$id"
    fun transfer(id: String) = "transfer/$id"

    /** The latest move for a headset, running or finished, without starting one. */
    fun transferProgress(id: String) = "transfer/$id?view=true"
}
