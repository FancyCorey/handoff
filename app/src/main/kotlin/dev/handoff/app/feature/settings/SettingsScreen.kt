package dev.handoff.app.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.BuildConfig
import dev.handoff.app.ads.AdPrivacySettings
import dev.handoff.app.edition.Edition
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.AppSettings
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.service.HandoffService
import dev.handoff.app.service.Notifications
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionLabel
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.ThisDevice
import dev.handoff.app.ui.platformIcon
import dev.handoff.app.ui.tokens
import dev.handoff.core.handoff.AutoSwitchMode
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.text.HandoffTexts
import dev.handoff.core.text.OpenSourceNotices
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
fun SettingsScreen(onBack: () -> Unit, onDiagnostics: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var editingName by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val listColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    // Notifications are optional: the background service runs without them, and "Ask me" prompts need them.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    HandoffScaffold(title = "Settings", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            IdentityCard(name, vm.keyFingerprint) { editingName = name }

            SectionLabel("When ${HandoffTexts.meInSentence} is locked")
            SectionCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice(
                        listOf(false to "Only while open", true to "Stay reachable"),
                        selected = s.backgroundEnabled,
                    ) {
                        if (it) askForNotifications()
                        vm.setBackground(it)
                    }
                    Text("Stay reachable shows a small notification.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.backgroundEnabled) {
                    ListItem(
                        headlineContent = { Text("Hide the notification") },
                        supportingContent = { Text("Handoff keeps working.") },
                        colors = listColors,
                        modifier = Modifier.clickable {
                            open(
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_SERVICE),
                            )
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Start after restart") },
                        trailingContent = { Switch(checked = s.restoreOnBoot, onCheckedChange = { vm.setRestoreOnBoot(it) }) },
                        colors = listColors,
                    )
                    ListItem(
                        headlineContent = { Text("Battery use") },
                        supportingContent = { Text("If moves fail while locked, set Handoff to Unrestricted.") },
                        colors = listColors,
                        modifier = Modifier.clickable { SystemScreens.appSettings(context) },
                    )
                }
            }

            SectionLabel("When something starts playing here")
            SectionCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice(
                        listOf(AutoSwitchMode.OFF to "Off", AutoSwitchMode.ASK to "Ask me", AutoSwitchMode.AUTO to HandoffTexts.MOVE_HERE),
                        selected = s.autoSwitchMode,
                    ) {
                        if (it == AutoSwitchMode.ASK) askForNotifications()
                        vm.setAutoMode(it)
                    }
                    val note = if (s.autoSwitchMode != AutoSwitchMode.OFF && !s.backgroundEnabled) {
                        "Works only with Stay reachable on."
                    } else {
                        "For the Quick Settings headphones, when they're on another device."
                    }
                    Text(note, fontSize = 12.sp, color = if (note.startsWith("Works")) tokens.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionLabel("Quick Settings tile")
            SectionCard {
                if (devices.isEmpty()) {
                    Text("Add headphones first.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                devices.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.device.displayName) },
                        leadingContent = {
                            RadioButton(selected = s.preferredDevice == d.device.logicalId, onClick = { vm.setPreferred(d.device.logicalId) })
                        },
                        colors = listColors,
                        modifier = Modifier.clickable { vm.setPreferred(d.device.logicalId) },
                    )
                }
            }

            SectionLabel("Updates")
            SectionCard { UpdateSettings() }

            SectionLabel("Help")
            SectionCard {
                LinkRow(
                    "Help and diagnostics",
                    "Share a report if something goes wrong",
                    onDiagnostics,
                )
                LinkRow("Privacy policy", null) { open(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }
                AdPrivacySettings()
            }

            SectionLabel("About")
            SectionCard {
                ListItem(
                    headlineContent = { Text("Handoff ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text(Edition.NAME) },
                    colors = listColors,
                )
                LinkRow("Source code", null) { open(Intent(Intent.ACTION_VIEW, Uri.parse(HandoffTexts.REPO_URL))) }
                LinkRow("Licenses", null) { showLicenses = true }
                LinkRow("Bluetooth settings", null) { SystemScreens.bluetoothSettings(context) }
            }
        }
    }

    editingName?.let { current ->
        var value by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editingName = null },
            title = { Text("Name ${HandoffTexts.meInSentence}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your other devices see this name.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
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
            title = { Text("Licenses") },
            text = {
                Text(
                    OpenSourceNotices.readable + Edition.EXTRA_NOTICES,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}

/** This device: its tile, its name (tap to rename) and its key. */
@Composable
private fun IdentityCard(name: String, key: String, onRename: () -> Unit) {
    val c = tokens
    val context = LocalContext.current
    SectionCard(Modifier.clickable(onClick = onRename)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.tile).border(2.dp, c.accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(platformIcon(ThisDevice.platform(context)), contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Tap to rename · key $key", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A choice with consequences, as pill-shaped segments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
