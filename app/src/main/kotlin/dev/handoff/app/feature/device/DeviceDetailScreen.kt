package dev.handoff.app.feature.device

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
import dev.handoff.app.ui.StageView
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.Texts
import dev.handoff.app.ui.toneColor
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageAction
import dev.handoff.core.stage.StageRepository
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.text.HandoffTexts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class DeviceDetailViewModel(
    logicalId: String,
    private val overview: OverviewRepository,
    private val devices: LogicalDeviceRepository,
    private val settings: SettingsRepository,
    private val actions: HandoffActions,
    stages: StageRepository,
) : ViewModel() {
    private val id = LogicalDeviceId(logicalId)

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

    fun forget(onDone: () -> Unit) = viewModelScope.launch {
        devices.remove(id)
        if (settings.current().preferredDevice == id) settings.setPreferredDevice(null)
        onDone()
    }
}

@Composable
fun DeviceDetailScreen(
    logicalId: String,
    onBack: () -> Unit,
    onMoveStarted: (String) -> Unit,
    onMap: () -> Unit,
    vm: DeviceDetailViewModel = koinViewModel(key = logicalId) { parametersOf(logicalId) },
) {
    val overview by vm.device.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val preferred by vm.isPreferred.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val o = overview
    val s = stage

    HandoffScaffold(title = o?.device?.displayName ?: "Headphones", onBack = onBack) { padding ->
        if (o == null || s == null) {
            Text("These headphones were removed.", Modifier.padding(padding).padding(20.dp))
            return@HandoffScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneColor(s.statusTone), modifier = Modifier.weight(1f))
                        if (!s.dimmed) o.batteryPercent?.let { BatteryChip(it) }
                    }
                    StageView(s, Modifier.fillMaxWidth(), steps = o.transfer?.steps.orEmpty().takeIf { o.transferRunning }.orEmpty())
                    s.line?.let { Text(it, fontSize = 12.sp, color = toneColor(s.lineTone, quiet = true)) }
                    StageButton(s.action, {
                        when (s.action) {
                            StageAction.MOVE_HERE, StageAction.USE_ONLY_HERE, StageAction.CONNECT_HERE_TOO -> {
                                vm.moveHere()
                                onMoveStarted(logicalId)
                            }
                            StageAction.CANCEL -> vm.cancel()
                            StageAction.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
                            StageAction.ADD_HERE -> onMap()
                            StageAction.NONE -> Unit
                        }
                    })
                    if (o.device.localDeviceId != null) OutlineButton("Choose different headphones", onMap)
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
