package dev.handoff.app.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.Hint
import dev.handoff.app.ui.KeyValue
import dev.handoff.app.ui.SectionHeader
import dev.handoff.app.ui.Texts
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothDiagnostics
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.bluetooth.DisconnectReason
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.BluetoothDeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Milestone 0 feasibility screen: raw connect / disconnect / verify against any bonded device,
 * showing which strategy was used and every structured failure. Talks to the controller only.
 */
class BluetoothTestViewModel(private val bluetooth: AndroidBluetoothAudioController) : ViewModel() {
    val devices: StateFlow<List<AudioDevice>> =
        bluetooth.allBondedDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val diagnostics: StateFlow<BluetoothDiagnostics> = bluetooth.diagnostics
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun state(id: BluetoothDeviceId) = bluetooth.connectionState(id)

    fun connect(device: AudioDevice) = run("connect ${device.name}") {
        val op = bluetooth.connect(device.id, ConnectReason.DEBUG)
        val verified = if (op is BluetoothOperationResult.Failed) null else bluetooth.verifyConnected(device.id, VERIFY_MS)
        describe(op) + (verified?.let { if (it) " → verified CONNECTED" else " → NOT verified within ${VERIFY_MS} ms" } ?: "")
    }

    fun disconnect(device: AudioDevice) = run("disconnect ${device.name}") {
        val op = bluetooth.disconnect(device.id, DisconnectReason.DEBUG)
        val verified = if (op is BluetoothOperationResult.Failed) null else bluetooth.verifyDisconnected(device.id, VERIFY_MS)
        val left = bluetooth.connectedProfiles(device.id)
        describe(op) + (verified?.let { if (it) " → verified fully released" else " → still connected: ${left.joinToString()}" } ?: "") +
            (bluetooth.diagnostics.value.lastDisconnect?.outcome?.let { " [$it]" } ?: "")
    }

    fun verify(device: AudioDevice) = run("verify ${device.name}") {
        val profiles = bluetooth.connectedProfiles(device.id)
        if (profiles.isEmpty()) "no audio profile connected" else "connected: ${profiles.joinToString()}"
    }

    fun probe() = run("probe") {
        bluetooth.probe()
        val d = bluetooth.diagnostics.value
        "connect()=${d.reflectionConnect}, disconnect()=${d.reflectionDisconnect}, " +
            d.companionDisconnect.entries.joinToString { "${it.key} disconnect()=${it.value}" } +
            ", ${Texts.compatibility(d.compatibility)}"
    }

    fun clear() = _log.update { emptyList() }

    private fun run(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val text = runCatching { block() }.getOrElse { "threw ${it.javaClass.simpleName}" }
            _log.update { (listOf("$label: $text (${System.currentTimeMillis() - started} ms)") + it).take(50) }
        }
    }

    private fun describe(op: BluetoothOperationResult): String = when (op) {
        is BluetoothOperationResult.Requested -> "requested via ${op.strategy} in ${op.durationMs} ms"
        BluetoothOperationResult.AlreadyInState -> "already in that state"
        is BluetoothOperationResult.Failed -> "FAILED ${op.error} via ${op.strategy ?: "-"}: ${op.detail}"
    }

    private companion object {
        const val VERIFY_MS = 8_000L
    }
}

@Composable
fun BluetoothTestScreen(onBack: () -> Unit, vm: BluetoothTestViewModel = koinViewModel()) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()

    HandoffScaffold(title = "Bluetooth test", onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Hint(
                    "Developer tool. Connect and Disconnect call the Bluetooth layer directly (no peers involved) " +
                        "and then verify the A2DP state.",
                )
            }
            item { SectionHeader("Strategies") }
            item { KeyValue("Compatibility", Texts.compatibility(diagnostics.compatibility)) }
            item { KeyValue("Adapter", diagnostics.adapterState.name) }
            item { KeyValue("A2DP proxy", if (diagnostics.a2dpProxyConnected) "connected" else "not connected") }
            items(diagnostics.strategies) { s ->
                KeyValue(s.name + if (s.usesHiddenApi) " (non-SDK)" else "", if (s.supported) "available" else "unavailable")
            }
            item { TextButton(onClick = vm::probe, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Probe hidden methods") } }

            item { SectionHeader("Bonded devices") }
            if (devices.isEmpty()) item { Hint("No bonded devices (or Bluetooth permission not granted).") }
            items(devices, key = { it.fingerprint }) { device ->
                val stateFlow = remember(device.id) { vm.state(device.id) }
                val state by stateFlow.collectAsState(initial = AudioConnectionState.UNAVAILABLE)
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${device.id} · ${device.kind} · ${if (device.likelyA2dp) "likely A2DP" else "not A2DP"} · $state",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.connect(device) }) { Text("Connect") }
                            OutlinedButton(onClick = { vm.disconnect(device) }) { Text("Disconnect") }
                            TextButton(onClick = { vm.verify(device) }) { Text("Verify") }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth()) {
                    SectionHeader("Results")
                    TextButton(onClick = vm::clear) { Text("Clear") }
                }
            }
            items(log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
            }
        }
    }
}
