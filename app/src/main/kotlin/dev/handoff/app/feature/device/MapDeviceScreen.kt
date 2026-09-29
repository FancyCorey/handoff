package dev.handoff.app.feature.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.core.overview.AnnouncedDevice
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.Hint
import dev.handoff.app.ui.SectionHeader
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.store.LogicalDeviceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class MapDeviceViewModel(
    bluetooth: AndroidBluetoothAudioController,
    private val devices: LogicalDeviceRepository,
    overview: OverviewRepository,
    private val identity: KeystoreIdentityProvider,
    private val settings: SettingsRepository,
) : ViewModel() {
    val bonded: StateFlow<List<AudioDevice>> =
        bluetooth.bondedAudioDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val announced: StateFlow<List<AnnouncedDevice>> = overview.announced
    val mapped: StateFlow<List<LogicalAudioDevice>> = devices.devices

    /** Peer-announced headsets that are the same physical device (same address fingerprint). */
    fun matchesFor(device: AudioDevice): List<AnnouncedDevice> =
        announced.value.filter { it.fingerprint != null && it.fingerprint == device.fingerprint }

    /**
     * Map [device] (bonded on this host) to [target], or to a new logical headset if null.
     * A bonded device maps to exactly one logical headset on this host.
     */
    fun map(device: AudioDevice, target: AnnouncedDevice?, onDone: () -> Unit) = viewModelScope.launch {
        val self = identity.identity()
        val id = target?.logicalId ?: LogicalDeviceId.random()
        devices.devices.value
            .filter { it.localDeviceId == device.id && it.logicalId != id }
            .forEach { devices.remove(it.logicalId) }
        val existing = devices.find(id)
        val hosts = buildList {
            add(HostMapping(self.peerId, self.displayName))
            target?.announcedByIds?.zip(target.announcedBy)?.forEach { (peer, name) -> add(HostMapping(peer, name)) }
            existing?.hostMappings?.filter { h -> none { it.hostId == h.hostId } }?.forEach { add(it) }
        }.distinctBy { it.hostId }
        devices.upsert(
            LogicalAudioDevice(
                logicalId = id,
                displayName = existing?.displayName ?: target?.displayName ?: device.name,
                deviceType = device.kind,
                fingerprint = device.fingerprint,
                localDeviceId = device.id,
                multipoint = existing?.multipoint ?: false,
                lastKnownOwner = existing?.lastKnownOwner,
                ownershipGeneration = existing?.ownershipGeneration ?: 0,
                hostMappings = hosts,
            ),
        )
        if (settings.current().preferredDevice == null) settings.setPreferredDevice(id)
        onDone()
    }
}

@Composable
fun MapDeviceScreen(onBack: () -> Unit, vm: MapDeviceViewModel = koinViewModel()) {
    val bonded by vm.bonded.collectAsStateWithLifecycle()
    val announced by vm.announced.collectAsStateWithLifecycle()
    val mapped by vm.mapped.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<AudioDevice?>(null) }

    HandoffScaffold(title = "Add headset", onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Hint(
                    "Pick the headset as it appears on this device. It must already be paired in Android Bluetooth settings. " +
                        "Add the same headset on each of your devices.",
                )
            }
            item { SectionHeader("Paired audio devices") }
            if (bonded.isEmpty()) {
                item { Hint("No paired audio devices found. Pair your headset in Bluetooth settings, or grant the Bluetooth permission.") }
            }
            items(bonded, key = { it.fingerprint }) { device ->
                val already = mapped.firstOrNull { it.localDeviceId == device.id }
                val match = vm.matchesFor(device).firstOrNull()
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                device.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                                if (device.likelyA2dp) null else "may not play music",
                                already?.let { "already added" },
                                match?.let { "also on ${it.announcedBy.joinToString()}" },
                            ).joinToString(" · "),
                        )
                    },
                    modifier = Modifier.clickable { selected = device },
                )
            }
        }
    }

    selected?.let { device ->
        val matches = vm.matchesFor(device)
        val others = announced.filter { a -> matches.none { it.logicalId == a.logicalId } && mapped.none { it.logicalId == a.logicalId } }
        var choice by remember(device) { mutableStateOf(matches.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Add ${device.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Is this a headset your other devices already know?", style = MaterialTheme.typography.bodyMedium)
                    (matches + others).forEach { option ->
                        ListItem(
                            headlineContent = { Text(option.displayName) },
                            supportingContent = {
                                Text(
                                    (if (option in matches) "Recommended · same Bluetooth address · " else "") +
                                        "on ${option.announcedBy.joinToString()}",
                                )
                            },
                            leadingContent = { RadioButton(selected = choice == option, onClick = { choice = option }) },
                            modifier = Modifier.clickable { choice = option },
                        )
                    }
                    ListItem(
                        headlineContent = { Text("New headset") },
                        supportingContent = { Text("Not added on any other device yet") },
                        leadingContent = { RadioButton(selected = choice == null, onClick = { choice = null }) },
                        modifier = Modifier.clickable { choice = null },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.map(device, choice) { onBack() }
                    selected = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}
