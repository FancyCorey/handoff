package dev.handoff.app.feature.setup

import androidx.compose.material3.CardDefaults
import dev.handoff.app.ui.PrimaryButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.graphics.Color
import dev.handoff.core.text.HandoffTexts
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
                        "Your headphones, on whichever device you pick up",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Tap Move here. Your other device lets go and this one connects.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Before you start", style = MaterialTheme.typography.titleSmall)
                            Text("• Connect your headphones to each device once.")
                            Text("• Install Handoff on each device.")
                        }
                    }
                    PrimaryButton("Continue", vm::next)
                }
                1 -> {
                    Text("Allow Bluetooth access", style = MaterialTheme.typography.headlineSmall)
                    PermissionRow(
                        why = "So Handoff can see and move your headphones. Android calls this Nearby devices.",
                        granted = state.bluetoothGranted,
                        onRequest = { launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) },
                    )
                    PrimaryButton("Continue", vm::next, enabled = state.bluetoothGranted)
                }
                2 -> {
                    Text("Name ${HandoffTexts.meInSentence}", style = MaterialTheme.typography.headlineSmall)
                    Text("Your other devices see this name.")
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = vm::setName,
                        singleLine = true,
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton("Continue", vm::next, enabled = state.name.isNotBlank())
                }
                else -> {
                    Text("Can ${HandoffTexts.meInSentence} move headphones?", style = MaterialTheme.typography.headlineSmall)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                HandoffTexts.compatibilityAnswer(diagnostics.compatibility),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (diagnostics.compatibility == CompatibilityLevel.UNSUPPORTED) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                            CheckRow("Connect", diagnostics.reflectionConnect)
                            CheckRow("Let go", diagnostics.reflectionDisconnect)
                            Text(
                                "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("When ${HandoffTexts.meInSentence} is locked", style = MaterialTheme.typography.titleSmall)
                            LockedChoice(state.stayReachable, vm::setStayReachable)
                            Text("Stay reachable shows a small notification.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Finish", { vm.finish(onDone) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockedChoice(stayReachable: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(false to "Only while open", true to "Stay reachable")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == stayReachable,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun PermissionRow(why: String, granted: Boolean, onRequest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(why, style = MaterialTheme.typography.bodyMedium)
            Row {
                if (granted) {
                    Text("Allowed", color = MaterialTheme.colorScheme.primary)
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
