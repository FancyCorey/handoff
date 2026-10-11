package dev.handoff.desktop.ui

import dev.handoff.core.help.HelpAction
import dev.handoff.core.help.Help
import dev.handoff.core.stage.Stage
import dev.handoff.desktop.DesktopUpdates
import dev.handoff.core.update.UpdateManifest
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import dev.handoff.core.text.OpenSourceNotices
import androidx.compose.foundation.layout.PaddingValues
import dev.handoff.core.text.HandoffTexts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.overview.AnnouncedDevice
import dev.handoff.core.overview.DeviceOverview
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage

fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

private fun qrImage(text: String, size: Int = 480): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) for (x in 0 until size) img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
    return img.toComposeImageBitmap()
}

/** Shows this PC's one-time pairing QR code. */
@Composable
fun LinkPhoneDialog(invitation: PairingManager.ActiveInvitation?, linkedName: String?, error: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = { Text(if (linkedName != null) HandoffTexts.linkedWith(linkedName) else "Scan this with your phone") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    linkedName != null -> Text(HandoffTexts.LINKED_ONCE)
                    error != null -> Text(error)
                    invitation == null -> CircularProgressIndicator()
                    else -> {
                        Text("In Handoff, tap Link a device.")
                        val code = invitation.invitation.encode()
                        val image = remember(code) { qrImage(code) }
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                            Image(image, contentDescription = "Link code", modifier = Modifier.size(260.dp))
                        }
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(invitation) {
                            while (true) {
                                now = System.currentTimeMillis()
                                delay(1_000)
                            }
                        }
                        val left = ((invitation.expiresAtMs - now) / 1000).coerceAtLeast(0)
                        Text(
                            "Expires in %d:%02d".format(left / 60, left % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { copyToClipboard(code) }) { Text("Copy the code") }
                    }
                }
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = onRetry) { Text("Try again") } else TextButton(onClick = onClose) { Text(if (linkedName != null) "Done" else "Close") }
        },
    )
}

/** Global "Link <device>?" approval with the short verification code. */
@Composable
fun ApprovalDialog(pending: PairingManager.PendingPairing, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Link ${pending.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(HandoffTexts.linkCheck(pending.displayName))
                Text(
                    pending.sas.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    HandoffTexts.LINK_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Link") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Decline") } },
    )
}

/** Choose a headset paired with this PC, and (if known) the same headset on linked devices. */
@Composable
fun AddHeadsetDialog(
    bonded: List<AudioDevice>,
    announced: List<AnnouncedDevice>,
    mapped: Set<String>,
    onMap: (AudioDevice, AnnouncedDevice?) -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf<AudioDevice?>(null) }
    var target by remember { mutableStateOf<AnnouncedDevice?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (selected == null) "Add headphones" else "Already added on another device?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val chosen = selected
                if (chosen == null) {
                    Text(
                        "Choose them from the list. Add them on each of your devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bonded.isEmpty()) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleSmall)
                        Text("Connect your headphones once in Bluetooth settings.")
                        TextButton(onClick = ::openBluetoothSettings, contentPadding = PaddingValues(0.dp)) { Text("Open Bluetooth settings") }
                    }
                    bonded.forEach { device ->
                        OptionRow(
                            title = device.name,
                            subtitle = HandoffTexts.kind(device.kind) + if (device.fingerprint in mapped) " · added" else "",
                            selected = false,
                            icon = { Icon(kindIcon(device.kind), contentDescription = null) },
                        ) {
                            selected = device
                            target = announced.firstOrNull { it.fingerprint == device.fingerprint }
                        }
                    }
                } else {
                    val matches = announced.filter { it.fingerprint == chosen.fingerprint }
                    val others = announced.filter { it !in matches }
                    (matches + others).forEach { option ->
                        OptionRow(
                            title = "${option.displayName} on ${HandoffTexts.names(option.announcedBy)}",
                            subtitle = if (option in matches) "Recommended" else null,
                            selected = target == option,
                        ) { target = option }
                    }
                    OptionRow(title = "No, these are new", subtitle = null, selected = target == null) { target = null }
                }
            }
        },
        confirmButton = {
            val chosen = selected
            if (chosen != null) TextButton(onClick = { onMap(chosen, target) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { if (selected != null) selected = null else onClose() }) { Text(if (selected != null) "Back" else "Cancel") } },
    )
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) icon() else RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun HeadsetDialog(
    overview: DeviceOverview,
    stage: Stage?,
    preferred: Boolean,
    released: Boolean,
    onPreferred: (Boolean) -> Unit,
    onMultipoint: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onChoose: () -> Unit,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from ${HandoffTexts.meInSentence}?") },
            text = { Text("They stay connected in Bluetooth settings.") },
            confirmButton = { TextButton(onClick = onForget) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(overview.device.displayName) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stage?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill(it.status, it.statusTone)
                        overview.batteryPercent?.let { level -> BatteryChip(level) }
                    }
                    it.line?.let { line -> Text(line, style = MaterialTheme.typography.bodySmall, color = toneColor(it.lineTone, quiet = true)) }
                }
                if (released) {
                    Text(
                        "Windows won't reconnect them by itself while they're on another device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRestore, contentPadding = PaddingValues(0.dp)) { Text("Connect to this PC") }
                }
                ToggleRow("Tray menu", "Move here in the tray menu moves these headphones.", preferred, onPreferred)
                ToggleRow(HandoffTexts.STAY_CONNECTED_TITLE, HandoffTexts.STAY_CONNECTED_HELP, overview.device.multipoint, onMultipoint)
                Column {
                    TextButton(onClick = onChoose, contentPadding = PaddingValues(0.dp)) { Text("Choose different headphones") }
                    TextButton(onClick = { confirmRemove = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Remove from ${HandoffTexts.meInSentence}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SettingsDialog(
    name: String,
    keyFingerprint: String,
    version: String,
    startWithWindows: Boolean,
    autostartAvailable: Boolean,
    onRename: (String) -> Unit,
    onStartWithWindows: (Boolean) -> Unit,
    update: DesktopUpdates.State,
    autoUpdateCheck: Boolean,
    canInstallUpdate: Boolean,
    onAutoUpdateCheck: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (UpdateManifest) -> Unit,
    onReleasePage: (UpdateManifest?) -> Unit,
    onDiagnostics: () -> Unit,
    onClose: () -> Unit,
) {
    var editing by remember { mutableStateOf(name) }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesDialog(onClose = { showLicenses = false })
        return
    }
    AlertDialog(
        onDismissRequest = { onRename(editing); onClose() },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    editing,
                    { editing = it },
                    label = { Text("Name") },
                    supportingText = { Text("Your other devices see this name.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    "Start with Windows",
                    if (autostartAvailable) "So your phone can always reach this PC" else "Available in the installed app",
                    startWithWindows,
                    onStartWithWindows,
                    enabled = autostartAvailable,
                )
                Text(
                    "Closing keeps Handoff in the tray.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateRow(update, autoUpdateCheck, canInstallUpdate, onAutoUpdateCheck, onCheckUpdate, onInstallUpdate, onReleasePage)
                Text(
                    "Handoff $version · key $keyFingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    TextButton(onClick = onDiagnostics, contentPadding = PaddingValues(0.dp)) { Text("Help and diagnostics") }
                    TextButton(onClick = { openInBrowser(HandoffTexts.REPO_URL) }, contentPadding = PaddingValues(0.dp)) { Text("Source code") }
                    TextButton(onClick = { showLicenses = true }, contentPadding = PaddingValues(0.dp)) { Text("Licenses") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(editing); onClose() }) { Text("Done") } },
    )
}

private fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** Updates from GitHub Releases: check, opt-in daily check, and install a found release. */
@Composable
private fun UpdateRow(
    state: DesktopUpdates.State,
    autoCheck: Boolean,
    canInstall: Boolean,
    onAutoCheck: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onInstall: (UpdateManifest) -> Unit,
    onReleasePage: (UpdateManifest?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state) {
                    DesktopUpdates.State.Idle -> "Check for updates"
                    DesktopUpdates.State.Checking -> "Checking…"
                    is DesktopUpdates.State.UpToDate -> "Up to date"
                    is DesktopUpdates.State.Available -> "${state.manifest.version} is available"
                    is DesktopUpdates.State.Downloading -> "Downloading…"
                    is DesktopUpdates.State.Failed -> state.message
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCheck, enabled = state != DesktopUpdates.State.Checking) { Text("Check") }
        }
        val manifest = when (state) {
            is DesktopUpdates.State.Available -> state.manifest
            is DesktopUpdates.State.Downloading -> state.manifest
            is DesktopUpdates.State.Failed -> state.manifest
            else -> null
        }
        if (manifest != null) {
            if (manifest.notes.isNotBlank()) {
                Text(manifest.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
            }
            if (state is DesktopUpdates.State.Downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canInstall && state !is DesktopUpdates.State.Downloading) {
                    Button(onClick = { onInstall(manifest) }) { Text("Update") }
                }
                OutlinedButton(onClick = { onReleasePage(manifest) }) { Text("What's new") }
            }
        }
        ToggleRow("Check automatically", "Once a day", autoCheck, onAutoCheck)
    }
}

/** Every bundled component, the PodSwitch credit and the full license texts. */
@Composable
fun LicensesDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Licenses") },
        text = {
            Text(
                OpenSourceNotices.readable,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * Help and diagnostics: what happened, what to do, what Handoff checked (docs/DESIGN.md), with
 * the full text report under Technical details.
 */
@Composable
fun DiagnosticsDialog(
    help: Help,
    report: String,
    onTryAgain: () -> Unit,
    onAction: (HelpAction) -> Unit,
    onClose: () -> Unit,
) {
    var showFine by remember { mutableStateOf(false) }
    var technical by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Help and diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val h = help.headline
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        (if (!h.failed && help.problems.isEmpty()) "✓ " else "") + h.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            h.failed -> MaterialTheme.colorScheme.error
                            help.problems.isEmpty() -> tokens.online
                            else -> tokens.warning
                        },
                    )
                    h.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                help.tip?.let { Text("${it.title}. ${it.detail}", style = MaterialTheme.typography.bodySmall, color = tokens.onAccentSoft) }
                if (help.steps.isNotEmpty() || help.tryAgain) {
                    Text("WHAT TO DO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    help.steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            if (help.steps.size > 1) Text("${i + 1}.", modifier = Modifier.width(22.dp), fontWeight = FontWeight.SemiBold)
                            Column(Modifier.weight(1f)) {
                                Text(step.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            step.action?.let { a -> TextButton(onClick = { onAction(a) }) { Text(a.label) } }
                        }
                    }
                    if (help.tryAgain) Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                Text("WHAT HANDOFF CHECKED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                help.problems.forEach { CheckLine(it.title, it.detail, ok = false) }
                if (help.problems.isNotEmpty() && !showFine) {
                    TextButton(onClick = { showFine = true }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (help.fine.size == 1) "✓  1 other check is fine" else "✓  ${help.fine.size} other checks are fine", color = tokens.online)
                    }
                } else {
                    help.fine.forEach { CheckLine(it.title, it.detail, ok = true) }
                }
                Text(
                    "The report includes your device names, headphone names and what Handoff tried. Use it when you report a problem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { technical = !technical }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (technical) "Hide technical details" else "Technical details")
                }
                if (technical) {
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = { copyToClipboard(report) }) { Text("Copy the report") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun CheckLine(title: String, detail: String?, ok: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(if (ok) "✓" else "!", color = if (ok) tokens.online else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (ok) FontWeight.Normal else FontWeight.Medium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Windows' own Bluetooth settings page. */
fun openBluetoothSettings() {
    runCatching { ProcessBuilder("explorer.exe", "ms-settings:bluetooth").start() }
}
