package dev.handoff.app.feature.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.BuildConfig
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.RoomTransferHistory
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.overview.PeerOverview
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.KeyValue
import dev.handoff.app.ui.SectionHeader
import dev.handoff.app.ui.Texts
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothDiagnostics
import dev.handoff.core.diagnostics.DiagnosticEvent
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.Redaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class DiagnosticsViewModel(
    private val context: Context,
    private val bluetooth: AndroidBluetoothAudioController,
    events: EventLog,
    history: RoomTransferHistory,
    overview: OverviewRepository,
    settings: SettingsRepository,
    private val identity: KeystoreIdentityProvider,
    runtime: HandoffRuntime,
) : ViewModel() {
    val bt: StateFlow<BluetoothDiagnostics> = bluetooth.diagnostics
    val events: StateFlow<List<DiagnosticEvent>> = events.events
    val transfers: StateFlow<List<TransferRecord>> =
        history.recent(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val devices: StateFlow<List<DeviceOverview>> = overview.devices
    val peers: StateFlow<List<PeerOverview>> = overview.peers
    val bonded: StateFlow<List<AudioDevice>> =
        bluetooth.bondedAudioDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settings.settings
    val running: StateFlow<Boolean> = runtime.running

    init {
        viewModelScope.launch { bluetooth.probe() }
    }

    fun sections(): List<Pair<String, List<Pair<String, String>>>> {
        val d = bt.value
        val preferred = settings.value.preferredDevice?.let { id -> devices.value.firstOrNull { it.device.logicalId == id } }
        val last = transfers.value.firstOrNull()
        return listOf(
            "Bluetooth" to listOf(
                "Bluetooth adapter" to d.adapterState.name,
                "A2DP profile proxy" to if (d.a2dpProxyConnected) "connected" else "not connected",
                "Compatibility" to Texts.compatibility(d.compatibility),
                "Bluetooth strategy" to (d.lastStrategyUsed ?: d.strategies.firstOrNull { it.supported && it.usesHiddenApi }?.name ?: "none"),
                "Reflection connect()" to d.reflectionConnect.name,
                "Reflection disconnect()" to d.reflectionDisconnect.name,
            ) + d.companionDisconnect.map { (profile, availability) -> "Reflection $profile disconnect()" to availability.name } + listOf(
                "Last connect" to (d.lastConnect?.let { "${it.outcome} via ${it.strategy ?: "-"} in ${it.durationMs} ms" } ?: "—"),
                "Last disconnect" to (d.lastDisconnect?.let { "${it.outcome} via ${it.strategy ?: "-"} in ${it.durationMs} ms" } ?: "—"),
            ) + d.strategies.map { "Strategy ${it.name}" to "${if (it.supported) "available" else "unavailable"}; ${it.detail}" },
            "Preferred headset" to listOf(
                "Headset" to (preferred?.device?.displayName ?: "none"),
                "Local mapping" to (preferred?.device?.localDeviceId?.toString() ?: "not mapped"),
                "Bond state" to when {
                    preferred?.device?.localDeviceId == null -> "—"
                    bonded.value.any { it.id == preferred.device.localDeviceId } -> "BONDED"
                    else -> "NOT BONDED"
                },
                "A2DP" to (preferred?.localState?.name ?: "—"),
                "Ownership" to (preferred?.let { Texts.ownership(it) } ?: "—"),
            ),
            "Peers" to listOf("Service" to if (running.value) "running" else "stopped") +
                peers.value.map { it.peer.displayName to if (it.online) "Online" else "Offline" },
            "Last transfer" to if (last == null) {
                listOf("Result" to "none yet")
            } else {
                listOf(
                    "Headset" to last.deviceName,
                    "Result" to "${last.outcome}${last.failure?.let { " ($it)" } ?: ""}",
                    "Path" to (last.path?.name ?: "—"),
                    "Trigger" to last.trigger.name,
                    "Strategy" to (last.strategy ?: "—"),
                    "Attempts" to last.attempts.toString(),
                    "Release" to (last.timings.releaseMs?.let { "$it ms" } ?: "—"),
                    "Bluetooth connect" to (last.timings.connectMs?.let { "$it ms" } ?: "—"),
                    "Total handoff" to "%.2f s".format(last.timings.totalMs / 1000.0),
                    "Detail" to (last.detail ?: "—"),
                )
            },
            "Recent transfers" to transfers.value.map { r ->
                time(r.startedAtMs) to "${r.deviceName}: ${r.outcome} ${r.path ?: r.failure ?: ""} ${"%.1f".format(r.timings.totalMs / 1000.0)} s"
            },
        )
    }

    fun export() {
        val report = DiagnosticsReport.build(
            ReportInput(
                appVersion = BuildConfig.VERSION_NAME,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sections = sections() + ("This device" to listOf("Identity key fingerprint" to identity.keyFingerprint())),
                events = events.value.takeLast(200).map { "${time(it.atMs)} ${it.format()}" },
            ),
        )
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Handoff diagnostics")
            .putExtra(Intent.EXTRA_TEXT, report)
        context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun time(ms: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onBluetoothTest: () -> Unit, vm: DiagnosticsViewModel = koinViewModel()) {
    // Collect everything the sections read so the screen recomposes when any of it changes.
    vm.bt.collectAsStateWithLifecycle()
    vm.transfers.collectAsStateWithLifecycle()
    vm.devices.collectAsStateWithLifecycle()
    vm.peers.collectAsStateWithLifecycle()
    vm.bonded.collectAsStateWithLifecycle()
    vm.settings.collectAsStateWithLifecycle()
    vm.running.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()

    HandoffScaffold(
        title = "Diagnostics",
        onBack = onBack,
        actions = { IconButton(onClick = vm::export) { Icon(Icons.Default.Share, contentDescription = "Export") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("Device") }
            item { KeyValue("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") }
            item { KeyValue("Manufacturer", Build.MANUFACTURER) }
            item { KeyValue("Device model", Build.MODEL) }
            vm.sections().forEach { (title, rows) ->
                item { SectionHeader(title) }
                items(rows) { (k, v) -> KeyValue(k, v) }
            }
            item {
                OutlinedButton(onClick = onBluetoothTest, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Bluetooth test (developer)")
                }
            }
            item { SectionHeader("Event log") }
            items(events.takeLast(150).reversed()) { e ->
                Text(
                    "${vm.time(e.atMs)} ${Redaction.scrub(e.format())}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}
