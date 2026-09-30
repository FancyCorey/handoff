package dev.handoff.app.feature.setup

import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.width
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.bluetooth.MethodAvailability
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import dev.handoff.app.ui.Brand
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.handoff.app.ui.Logo
import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.service.HandoffService
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.Texts
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class SetupState(
    val step: Int = 0,
    val bluetoothGranted: Boolean = false,
    /** Chosen on the last setup step; off unless the user switches it on. */
    val stayReachable: Boolean = false,
    val name: String = "",
)

// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class SetupViewModel(
    private val context: Context,
    private val settings: SettingsRepository,
    private val bluetooth: AndroidBluetoothAudioController,
    private val identity: KeystoreIdentityProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupState(name = identity.displayName.value))
    val state: StateFlow<SetupState> = _state.asStateFlow()
    val diagnostics: StateFlow<BluetoothDiagnostics> = bluetooth.diagnostics

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        _state.update {
            it.copy(
                bluetoothGranted = granted(Manifest.permission.BLUETOOTH_CONNECT),
            )
        }
        bluetooth.refresh()
        viewModelScope.launch { bluetooth.probe() }
    }

    fun next() = _state.update { it.copy(step = it.step + 1) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setStayReachable(on: Boolean) = _state.update { it.copy(stayReachable = on) }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            identity.rename(_state.value.name)
            settings.setOnboardingComplete(true)
            if (_state.value.stayReachable) settings.setBackgroundEnabled(true)
            if (settings.current().backgroundEnabled) HandoffService.start(context)
            onDone()
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun SetupScreen(onDone: () -> Unit, vm: SetupViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPermissions()
    }
    LaunchedEffect(Unit) { vm.refreshPermissions() }

    HandoffScaffold(title = "Welcome", onBack = if (state.step > 0) vm::back else null) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { (state.step + 1) / 4f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
            )
            when (state.step) {
                0 -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Logo(112.dp) }
                    Text(
                        "Move your headphones between your devices",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Handoff coordinates which of your devices is connected to an already-paired Bluetooth headset. " +
                            "Press Move here and the other device lets go, then this one connects.",
                    )
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Before you start", style = MaterialTheme.typography.titleSmall)
                            Text("• Pair the headset with each device in Android Bluetooth settings.")
                            Text("• Install Handoff on each device and link them once. Links keep working on every Wi-Fi; devices just need to share a network when you move the headset.")
                            Text("• Handoff does not create real multipoint and never streams audio itself.")
                            Text("• Switching relies on non-public Android Bluetooth functions and may not work on every phone.")
                            Text("• ${dev.handoff.app.edition.Edition.PRIVACY_SUMMARY}")
                        }
                    }
                    Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                }
                1 -> {
                    Text("Permissions", style = MaterialTheme.typography.headlineSmall)
                    PermissionRow(
                        title = "Nearby devices (Bluetooth) — required",
                        why = "To see your paired headphones, their connection state, and to connect or release them.",
                        granted = state.bluetoothGranted,
                        onRequest = { launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) },
                    )
                    Text(
                        "Handoff does not ask for location or notifications, and does not scan for new Bluetooth devices.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = vm::next, enabled = state.bluetoothGranted, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                }
                2 -> {
                    Text("Name this device", style = MaterialTheme.typography.headlineSmall)
                    Text("Your other devices will show this name.")
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = vm::setName,
                        singleLine = true,
                        label = { Text("Device name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = vm::next, enabled = state.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                }
                else -> {
                    Text("Can this device switch headphones?", style = MaterialTheme.typography.headlineSmall)
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Texts.compatibility(diagnostics.compatibility), style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (diagnostics.compatibility) {
                                    CompatibilityLevel.SUPPORTED -> "Handoff has already moved headphones on this device."
                                    CompatibilityLevel.EXPERIMENTAL ->
                                        "Everything Handoff needs is available. It will show “Supported” after your first successful move."
                                    CompatibilityLevel.UNSUPPORTED ->
                                        "This device doesn't let apps connect headphones, so Handoff can't move them here. " +
                                            "Your other devices can still take the headphones from it."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            CheckRow("Connect headphones", diagnostics.reflectionConnect)
                            CheckRow("Let go of headphones", diagnostics.reflectionDisconnect)
                            Text(
                                "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Card {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Stay reachable in the background", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Lets your other devices take the headset from this one while Handoff is closed or the " +
                                        "screen is locked. Otherwise, open Handoff here before moving the headset away. " +
                                        "Android shows a small notification, which you can hide in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = state.stayReachable, onCheckedChange = vm::setStayReachable)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.finish(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onRequest: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodyMedium)
            Row {
                if (granted) {
                    Text("Granted", color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = onRequest) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, availability: MethodAvailability) {
    val ok = availability == MethodAvailability.AVAILABLE
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (ok) Brand.Success else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
