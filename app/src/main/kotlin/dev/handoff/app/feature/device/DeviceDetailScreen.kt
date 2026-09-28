package dev.handoff.app.feature.device

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import dev.handoff.app.ui.HeroIcon
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.StatusPill
import dev.handoff.app.ui.kindIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.KeyValue
import dev.handoff.app.ui.SectionHeader
import dev.handoff.app.ui.Texts
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.store.LogicalDeviceRepository
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
) : ViewModel() {
    private val id = LogicalDeviceId(logicalId)

    val device: StateFlow<DeviceOverview?> = overview.devices
        .map { list -> list.firstOrNull { it.device.logicalId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isPreferred: StateFlow<Boolean> = settings.settings
        .map { it.preferredDevice == id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun peerName(peer: dev.handoff.core.model.PeerId) = overview.peerName(peer)

    fun moveHere() {
        actions.moveHere(id, TransferTrigger.MANUAL)
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
    val preferred by vm.isPreferred.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    val o = overview

    HandoffScaffold(title = o?.device?.displayName ?: "Headset", onBack = onBack) { padding ->
        if (o == null) {
            Text("This headset is no longer mapped.", Modifier.padding(padding).padding(16.dp))
            return@HandoffScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroIcon(kindIcon(o.device.deviceType), size = 80.dp)
                    Text(o.device.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val (label, tone) = Texts.status(o)
                    StatusPill(label, tone)
                    if (o.device.localDeviceId != null && !o.connectedHere && !o.transferRunning) {
                        Button(
                            onClick = {
                                vm.moveHere()
                                onMoveStarted(logicalId)
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Move here")
                        }
                    }
                    OutlinedButton(onClick = onMap, modifier = Modifier.fillMaxWidth()) {
                        Text(if (o.device.localDeviceId == null) "Set up on this device" else "Choose a different paired device")
                    }
                }
            }

            SectionHeader("Status")
            KeyValue("This device", o.localState.name.lowercase().replaceFirstChar { it.uppercase() })
            KeyValue("Type", o.device.deviceType.name.lowercase().replace('_', ' '))
            KeyValue("Mapped on", o.device.hostMappings.joinToString { vm.peerName(it.hostId) }.ifEmpty { "This device" })
            KeyValue("Last known owner", o.device.lastKnownOwner?.let(vm::peerName) ?: "—")
            KeyValue("Ownership generation", o.device.ownershipGeneration.toString())

            SectionHeader("Options")
            ListItem(
                headlineContent = { Text("Quick Settings tile headset") },
                supportingContent = { Text("The tile moves this headset.") },
                trailingContent = { Switch(checked = preferred, onCheckedChange = { vm.setPreferred(it) }) },
            )
            ListItem(
                headlineContent = { Text("Multipoint headset") },
                supportingContent = {
                    Text("Can stay connected to two devices. Move here then joins instead of disconnecting the others.")
                },
                trailingContent = { Switch(checked = o.device.multipoint, onCheckedChange = { vm.setMultipoint(it) }) },
            )
            TextButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text("Forget on this device", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget headset?") },
            text = { Text("Handoff stops managing it on this device. The Bluetooth pairing is not changed.") },
            confirmButton = { TextButton(onClick = { vm.forget(onBack) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
