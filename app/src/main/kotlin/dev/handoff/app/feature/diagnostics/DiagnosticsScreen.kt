package dev.handoff.app.feature.diagnostics

import dev.handoff.bluetooth.BluetoothLinkLog
import dev.handoff.app.persistence.AppSettings
import kotlinx.coroutines.flow.combine
import dev.handoff.core.help.HelpInput
import dev.handoff.core.help.HelpBuilder
import dev.handoff.core.help.HelpAction
import dev.handoff.core.help.Help
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.app.ui.tokens
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.SectionLabel
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.PrimaryButton
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.mesh.NetworkAddresses
import dev.handoff.app.mesh.NetworkMonitor
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import dev.handoff.bluetooth.CodecPreferenceStatus
import dev.handoff.core.bluetooth.BluetoothDiagnostics
import dev.handoff.core.diagnostics.DiagnosticEvent
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.Redaction
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val actions: HandoffActions,
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
    private val appSettings: StateFlow<AppSettings> = settings.settings
    val running: StateFlow<Boolean> = runtime.running

    private val network: StateFlow<Set<String>> = NetworkMonitor(context).lanAddresses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkAddresses.lanIpv4(context).toSet())

    /** The plain-words help: what happened, what to do, what Handoff checked. */
    val help: StateFlow<Help> = combine(
        combine(transfers, bluetooth.adapterState, bt) { t: List<TransferRecord>, a: AdapterState, d: BluetoothDiagnostics -> Triple(t, a, d) },
        combine(devices, bonded, peers) { d: List<DeviceOverview>, b: List<AudioDevice>, p: List<PeerOverview> -> Triple(d, b, p) },
        combine(appSettings, network) { s: AppSettings, n: Set<String> -> s to n },
    ) { first, second, third ->
        val (t, adapter, d) = first
        val (devs, bonds, peerList) = second
        val (s, net) = third
        HelpBuilder.build(
            HelpInput(
                lastMove = t.firstOrNull(),
                adapter = adapter,
                compatibility = d.compatibility,
                headphones = devs.map { o -> o.device.displayName to (o.device.localDeviceId != null && bonds.any { it.id == o.device.localDeviceId }) },
                peers = peerList,
                onNetwork = net.isNotEmpty(),
                staysReachable = s.backgroundEnabled,
                nowMs = System.currentTimeMillis(),
                nearbyOn = s.nearbyEnabled,
            ),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HelpBuilder.build(HelpInput(null, AdapterState.ON, bt.value.compatibility, emptyList(), emptyList(), true, null, System.currentTimeMillis())),
    )

    /** Starts the last move again; returns its headphones' id so the screen can follow it. */
    fun tryAgain(): String? {
        val last = transfers.value.firstOrNull() ?: return null
        actions.moveHere(last.logicalId, TransferTrigger.MANUAL)
        return last.logicalId.value
    }

    fun lastMoveId(): String? = transfers.value.firstOrNull()?.logicalId?.value

    val linkLog: StateFlow<List<BluetoothLinkLog.Entry>> = bluetooth.linkLog.entries
    val codecStates = bluetooth.codecPreferences.states
    private val _codecSnapshots = MutableStateFlow<Map<BluetoothDeviceId, String>>(emptyMap())
    val codecSnapshots: StateFlow<Map<BluetoothDeviceId, String>> = _codecSnapshots

    init {
        viewModelScope.launch { bluetooth.probe() }
        viewModelScope.launch { refreshCodecSnapshots() }
    }

    fun sections(): List<Pair<String, List<Pair<String, String>>>> {
        val d = bt.value
        val preferred = settings.value.preferredDevice?.let { id -> devices.value.firstOrNull { it.device.logicalId == id } }
        val last = transfers.value.firstOrNull()
        val playbackChoices = devices.value.mapNotNull { headset ->
            val local = headset.device.localDeviceId ?: return@mapNotNull null
            val address = local.normalized()
            val preferSbc = address in settings.value.preferSbc
            val codec = codecSnapshots.value[address]
            if (!preferSbc && codec == null) return@mapNotNull null
            val choice = if (preferSbc) "Prefer SBC" else "Automatic"
            val status = codecStates.value[address]
            val waiting = if (preferSbc && (codec == null || status != CodecPreferenceStatus.SBC_CONFIRMED)) {
                status?.name?.replace('_', ' ')?.lowercase() ?: "waiting"
            } else {
                null
            }
            headset.device.displayName to listOfNotNull(choice, waiting, codec).joinToString(" · ")
        }
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
            "Bluetooth audio" to playbackChoices.ifEmpty { listOf("Preference" to "Automatic") },
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
            // Newest last, like the event log: link, profiles, streaming and codec around each move.
            "Bluetooth log" to linkLog.value.ifEmpty { listOf(BluetoothLinkLog.Entry(0, "nothing yet")) }.map { e ->
                (if (e.atMs == 0L) "—" else time(e.atMs)) to e.text
            },
            "Recent transfers" to transfers.value.map { r ->
                time(r.startedAtMs) to "${r.deviceName}: ${r.outcome} ${r.path ?: r.failure ?: ""} ${"%.1f".format(r.timings.totalMs / 1000.0)} s"
            },
        )
    }

    fun export() {
        viewModelScope.launch {
            refreshCodecSnapshots()
            shareReport()
        }
    }

    private suspend fun refreshCodecSnapshots() {
        val mapped = devices.value.mapNotNullTo(mutableSetOf()) { it.device.localDeviceId?.normalized() }
        _codecSnapshots.value = bluetooth.noteCurrentState(mapped)
    }

    private fun shareReport() {
        val report = DiagnosticsReport.build(
            ReportInput(
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.EDITION})",
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

    private fun BluetoothDeviceId.normalized(): BluetoothDeviceId = BluetoothDeviceId(address.uppercase())
}

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onBluetoothTest: () -> Unit,
    onMoveStarted: (String) -> Unit,
    onPeers: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onSettings: () -> Unit,
    vm: DiagnosticsViewModel = koinViewModel(),
) {
    // Collect everything the sections read so the screen recomposes when any of it changes.
    vm.bt.collectAsStateWithLifecycle()
    vm.transfers.collectAsStateWithLifecycle()
    vm.devices.collectAsStateWithLifecycle()
    vm.peers.collectAsStateWithLifecycle()
    vm.bonded.collectAsStateWithLifecycle()
    vm.settings.collectAsStateWithLifecycle()
    vm.running.collectAsStateWithLifecycle()
    vm.linkLog.collectAsStateWithLifecycle()
    vm.codecStates.collectAsStateWithLifecycle()
    vm.codecSnapshots.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val help by vm.help.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFine by remember { mutableStateOf(false) }
    var technical by remember { mutableStateOf(false) }

    fun act(action: HelpAction) {
        when (action) {
            HelpAction.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
            HelpAction.ALLOW_BLUETOOTH -> SystemScreens.appSettings(context)
            HelpAction.OPEN_BLUETOOTH_SETTINGS -> SystemScreens.bluetoothSettings(context)
            HelpAction.LINK_AGAIN -> onPeers()
            HelpAction.ADD_HERE -> vm.lastMoveId()?.let(onOpenDevice)
            HelpAction.STAY_REACHABLE -> onSettings()
            HelpAction.OPEN_WIFI_SETTINGS -> SystemScreens.wifiSettings(context)
            HelpAction.HOTSPOT_SETTINGS -> SystemScreens.hotspot(context)
        }
    }

    HandoffScaffold(title = "Help and diagnostics", onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            helpItems(
                help,
                onAction = ::act,
                onTryAgain = { vm.tryAgain()?.let(onMoveStarted) },
                onShare = vm::export,
                showFine = showFine,
                onShowFine = { showFine = true },
            )

            // Everything else, for developers.
            item {
                SectionCard(Modifier.clickable { technical = !technical }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Technical details", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(if (technical) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                }
            }
            if (technical) {
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
}

