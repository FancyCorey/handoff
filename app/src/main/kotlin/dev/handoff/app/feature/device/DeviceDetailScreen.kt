package dev.handoff.app.feature.device

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.handoff.app.ui.StatusPill
import dev.handoff.app.ui.IconBadge
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.ui.BatteryChip
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.KeyValue
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionLabel
import dev.handoff.app.ui.StageButton
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.Texts
import dev.handoff.app.ui.toneColor
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageAction
import dev.handoff.core.stage.StageRepository
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.text.HandoffTexts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class DeviceDetailViewModel(
    logicalId: String,
    private val overview: OverviewRepository,
    private val devices: LogicalDeviceRepository,
    private val settings: SettingsRepository,
    private val actions: HandoffActions,
    stages: StageRepository,
    bluetooth: AndroidBluetoothAudioController,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val id = LogicalDeviceId(logicalId)
    private val codecs = bluetooth.codecPreferences
    private var pendingCodecDevice = savedState.get<String>("codecAccessAddress")?.let(::BluetoothDeviceId)
    private val _codecAccess = MutableStateFlow(
        if (pendingCodecDevice == null) CodecAccessStep.IDLE else CodecAccessStep.REQUESTING,
    )
    private val _codecPrompt = MutableStateFlow<IntentSender?>(null)

    val codecAccess: StateFlow<CodecAccessStep> = _codecAccess
    val codecPrompt: StateFlow<IntentSender?> = _codecPrompt
    val codecSupported = codecs.supported
    val codecStates = codecs.states
    val codecChoices = settings.settings.map { it.preferSbc }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val device: StateFlow<DeviceOverview?> = overview.devices
        .map { list -> list.firstOrNull { it.device.logicalId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stage: StateFlow<Stage?> = stages.stages.map { it[id] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isPreferred: StateFlow<Boolean> = settings.settings
        .map { it.preferredDevice == id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun peerName(peer: dev.handoff.core.model.PeerId) = overview.peerName(peer)

    fun moveHere() {
        actions.moveHere(id, TransferTrigger.MANUAL)
    }

    fun cancel() {
        actions.cancel(id)
    }

    fun setPreferred(value: Boolean) = viewModelScope.launch { settings.setPreferredDevice(if (value) id else null) }

    fun setMultipoint(value: Boolean) = viewModelScope.launch {
        devices.find(id)?.let { devices.upsert(it.copy(multipoint = value)) }
    }

    private fun currentCodecDevice(): BluetoothDeviceId? =
        devices.find(id)?.localDeviceId?.let { BluetoothDeviceId(it.address.uppercase()) }

    fun chooseSbc() {
        val local = currentCodecDevice() ?: return
        if (!codecs.canRequestAccess(local)) {
            _codecAccess.value = CodecAccessStep.INELIGIBLE
        } else if (codecs.hasAccess(local)) {
            _codecAccess.value = CodecAccessStep.IDLE
            viewModelScope.launch { settings.setPreferSbc(local, true) }
        } else {
            _codecAccess.value = CodecAccessStep.EXPLAIN
        }
    }

    fun chooseAutomatic() {
        val local = currentCodecDevice() ?: return
        pendingCodecDevice = null
        savedState.remove<String>("codecAccessAddress")
        _codecPrompt.value = null
        _codecAccess.value = CodecAccessStep.AUTOMATIC
        viewModelScope.launch { settings.setPreferSbc(local, false) }
    }

    fun cancelCodecAccess() {
        _codecAccess.value = CodecAccessStep.IDLE
    }

    fun askForCodecAccess() {
        val local = currentCodecDevice() ?: return
        if (pendingCodecDevice != null) return
        if (!codecs.canRequestAccess(local)) {
            _codecAccess.value = CodecAccessStep.INELIGIBLE
            return
        }
        pendingCodecDevice = local
        _codecAccess.value = CodecAccessStep.REQUESTING
        codecs.requestAccess(
            local,
            onPrompt = { sender ->
                viewModelScope.launch {
                    if (pendingCodecDevice == local) _codecPrompt.value = sender
                }
            },
            onResult = { approved ->
                viewModelScope.launch { finishCodecAccess(local, approved) }
            },
        )
    }

    fun codecPromptLaunched() {
        savedState["codecAccessAddress"] = pendingCodecDevice?.address
        _codecPrompt.value = null
    }

    fun codecActivityFinished(approved: Boolean) {
        val local = pendingCodecDevice ?: return
        viewModelScope.launch {
            // Android can deliver the activity result before the association callback.
            if (approved && !codecs.hasAccess(local)) delay(1_500)
            finishCodecAccess(local, approved)
        }
    }

    private suspend fun finishCodecAccess(local: BluetoothDeviceId, approved: Boolean) {
        if (local != pendingCodecDevice) return
        pendingCodecDevice = null
        savedState.remove<String>("codecAccessAddress")
        _codecPrompt.value = null
        _codecAccess.value = CodecAccessStep.IDLE
        if (approved && currentCodecDevice() == local && codecs.hasAccess(local)) {
            settings.setPreferSbc(local, true)
        } else if (currentCodecDevice() == local) {
            _codecAccess.value = CodecAccessStep.DECLINED
        }
    }

    fun forget(onDone: () -> Unit) = viewModelScope.launch {
        currentCodecDevice()?.let { local ->
            val alsoMapped = devices.devices.value.any {
                it.logicalId != id && it.localDeviceId?.address.equals(local.address, ignoreCase = true)
            }
            if (!alsoMapped) settings.setPreferSbc(local, false)
        }
        devices.remove(id)
        if (settings.current().preferredDevice == id) settings.setPreferredDevice(null)
        onDone()
    }
}

@Composable
fun DeviceDetailScreen(
    logicalId: String,
    onBack: () -> Unit,
    onMap: () -> Unit,
    vm: DeviceDetailViewModel = koinViewModel(key = logicalId) { parametersOf(logicalId) },
) {
    val overview by vm.device.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val preferred by vm.isPreferred.collectAsStateWithLifecycle()
    val codecAccess by vm.codecAccess.collectAsStateWithLifecycle()
    val codecPrompt by vm.codecPrompt.collectAsStateWithLifecycle()
    val codecStates by vm.codecStates.collectAsStateWithLifecycle()
    val codecChoices by vm.codecChoices.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val o = overview
    val s = stage
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.codecActivityFinished(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(codecPrompt) {
        codecPrompt?.let { sender ->
            vm.codecPromptLaunched()
            try {
                accessLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (_: RuntimeException) {
                vm.codecActivityFinished(false)
            }
        }
    }

    HandoffScaffold(title = o?.device?.displayName ?: "Headphones", onBack = onBack) { padding ->
        if (o == null || s == null) {
            Text("These headphones were removed.", Modifier.padding(padding).padding(20.dp))
            return@HandoffScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconBadge(Icons.Filled.Headphones, size = 64.dp)
                    Text(o.device.displayName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (s.dimmed) {
                            Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            StatusPill(s.status, s.statusTone)
                            o.batteryPercent?.let { BatteryChip(it) }
                        }
                    }
                    if (o.transferRunning) {
                        Text(
                            o.transfer?.steps?.lastOrNull()?.let(HandoffTexts::step) ?: HandoffTexts.STARTING,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlineButton(HandoffTexts.CANCEL, vm::cancel)
                    } else {
                        s.line?.let { Text(it, fontSize = 13.sp, color = toneColor(s.lineTone, quiet = true), textAlign = TextAlign.Center) }
                        if (s.action != StageAction.NONE && s.action != StageAction.CANCEL) {
                            StageButton(s.action, {
                                when (s.action) {
                                    StageAction.MOVE_HERE, StageAction.USE_ONLY_HERE, StageAction.CONNECT_HERE_TOO -> vm.moveHere()
                                    StageAction.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
                                    StageAction.ADD_HERE -> onMap()
                                    else -> Unit
                                }
                            })
                        }
                    }
                }
            }

            SectionLabel("About them")
            SectionCard {
                Column(Modifier.padding(vertical = 8.dp)) {
                    KeyValue("Type", Texts.kind(o.device.deviceType))
                    KeyValue("Added on", o.device.hostMappings.joinToString { vm.peerName(it.hostId) }.ifEmpty { HandoffTexts.me })
                }
            }

            SectionLabel("Options")
            SectionCard {
                val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                ListItem(
                    headlineContent = { Text("Quick Settings tile") },
                    supportingContent = { Text("The tile moves these headphones.") },
                    trailingContent = { Switch(checked = preferred, onCheckedChange = { vm.setPreferred(it) }) },
                    colors = colors,
                )
                ListItem(
                    headlineContent = { Text(HandoffTexts.STAY_CONNECTED_TITLE) },
                    supportingContent = { Text(HandoffTexts.STAY_CONNECTED_HELP) },
                    trailingContent = { Switch(checked = o.device.multipoint, onCheckedChange = { vm.setMultipoint(it) }) },
                    colors = colors,
                )
                if (o.device.localDeviceId != null) {
                    ListItem(
                        headlineContent = { Text("Choose different headphones") },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                        colors = colors,
                        modifier = Modifier.clickable(onClick = onMap),
                    )
                }
            }
            val local = o.device.localDeviceId?.let { BluetoothDeviceId(it.address.uppercase()) }
            if (vm.codecSupported && local != null) {
                CodecPreferenceSection(
                    preferSbc = local in codecChoices,
                    access = codecAccess,
                    status = codecStates[local],
                    automatic = vm::chooseAutomatic,
                    sbc = vm::chooseSbc,
                    askForAccess = vm::askForCodecAccess,
                    cancelAccess = vm::cancelCodecAccess,
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { confirmForget = true }) {
                    Text("Remove from ${HandoffTexts.meInSentence}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Remove from ${HandoffTexts.meInSentence}?") },
            text = { Text("They stay connected in Bluetooth settings.") },
            confirmButton = { TextButton(onClick = { vm.forget(onBack) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}
