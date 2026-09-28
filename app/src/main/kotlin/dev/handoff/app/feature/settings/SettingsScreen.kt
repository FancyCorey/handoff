package dev.handoff.app.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import dev.handoff.app.BuildConfig
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.AppSettings
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.app.service.HandoffService
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.Hint
import dev.handoff.app.ui.SectionHeader
import dev.handoff.core.handoff.AutoSwitchMode
import dev.handoff.core.model.LogicalDeviceId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class SettingsViewModel(
    private val context: Context,
    private val settings: SettingsRepository,
    private val identity: KeystoreIdentityProvider,
    overview: OverviewRepository,
) : ViewModel() {
    val state: StateFlow<AppSettings> = settings.settings
    val name: StateFlow<String> = identity.displayName
    val devices: StateFlow<List<DeviceOverview>> = overview.devices
    val keyFingerprint: String = identity.keyFingerprint()

    fun rename(value: String) = identity.rename(value)

    fun setBackground(enabled: Boolean) = viewModelScope.launch {
        settings.setBackgroundEnabled(enabled)
        if (enabled) HandoffService.start(context) else HandoffService.stop(context)
    }

    fun setRestoreOnBoot(value: Boolean) = viewModelScope.launch { settings.setRestoreOnBoot(value) }
    fun setAutoMode(mode: AutoSwitchMode) = viewModelScope.launch { settings.setAutoSwitchMode(mode) }
    fun setPreferred(id: LogicalDeviceId?) = viewModelScope.launch { settings.setPreferredDevice(id) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var editingName by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    HandoffScaffold(title = "Settings", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("This device")
            ListItem(
                headlineContent = { Text("Device name") },
                supportingContent = { Text(name) },
                modifier = Modifier.clickable { editingName = name },
            )
            ListItem(headlineContent = { Text("Identity key") }, supportingContent = { Text(vm.keyFingerprint) })

            SectionHeader("Background")
            ListItem(
                headlineContent = { Text("Stay reachable in the background") },
                supportingContent = {
                    Text("Lets your other devices take the headset while this one is locked. Shows a silent notification.")
                },
                trailingContent = { Switch(checked = s.backgroundEnabled, onCheckedChange = { vm.setBackground(it) }) },
            )
            ListItem(
                headlineContent = { Text("Start after reboot") },
                trailingContent = { Switch(checked = s.restoreOnBoot, enabled = s.backgroundEnabled, onCheckedChange = { vm.setRestoreOnBoot(it) }) },
            )
            ListItem(
                headlineContent = { Text("Battery optimization") },
                supportingContent = {
                    Text(
                        "Some manufacturers stop background apps aggressively. If other devices can't reach this one while it " +
                            "is locked, set Handoff's battery usage to “Unrestricted” in app settings.",
                    )
                },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )

            SectionHeader("Quick Settings tile")
            if (devices.isEmpty()) Hint("Map a headset first.")
            devices.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.device.displayName) },
                    leadingContent = {
                        RadioButton(selected = s.preferredDevice == d.device.logicalId, onClick = { vm.setPreferred(d.device.logicalId) })
                    },
                    modifier = Modifier.clickable { vm.setPreferred(d.device.logicalId) },
                )
            }

            SectionHeader("Automatic switching (experimental)")
            Hint("When audio starts playing on this device and the tile headset is elsewhere. Manual Move here always works.")
            listOf(
                AutoSwitchMode.OFF to "Off",
                AutoSwitchMode.ASK to "Ask with a notification",
                AutoSwitchMode.AUTO to "Move automatically",
            ).forEach { (mode, label) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = s.autoSwitchMode == mode, onClick = { vm.setAutoMode(mode) }) },
                    modifier = Modifier.clickable { vm.setAutoMode(mode) },
                )
            }
            if (s.autoSwitchMode != AutoSwitchMode.OFF && !s.backgroundEnabled) {
                Hint("Automatic switching only runs while “Stay reachable in the background” is on.")
            }

            SectionHeader("About")
            ListItem(headlineContent = { Text("Handoff ${BuildConfig.VERSION_NAME}") }, supportingContent = { Text("MIT License · no account, no cloud, no telemetry") })
            ListItem(headlineContent = { Text("Open-source licenses") }, modifier = Modifier.clickable { showLicenses = true })
            ListItem(
                headlineContent = { Text("Android Bluetooth settings") },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
    }

    editingName?.let { current ->
        var value by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editingName = null },
            title = { Text("Device name") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(value)
                    editingName = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = null }) { Text("Cancel") } },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source licenses") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
                    Text(
                        "Portions of the Android Bluetooth audio connection approach were adapted from PodSwitch by Felip6499 " +
                            "(MIT License, Copyright (c) 2026 Felip6499).\n\n" +
                            "ZXing and zxing-android-embedded (Apache License 2.0), AndroidX and Jetpack Compose (Apache License 2.0), " +
                            "Kotlin and kotlinx libraries (Apache License 2.0), Koin (Apache License 2.0).\n\n" +
                            "Full texts are in THIRD_PARTY_NOTICES.md in the source repository.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}
