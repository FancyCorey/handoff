package dev.handoff.desktop.ui

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
        title = { Text(if (linkedName != null) "Linked" else "Link a phone or tablet") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    linkedName != null -> Text("$linkedName can now move your headphones to and from this PC.")
                    error != null -> Text(error)
                    invitation == null -> CircularProgressIndicator()
                    else -> {
                        Text("On your phone open Handoff → My devices → Scan a Handoff code, and point it at this code.")
                        val code = invitation.invitation.encode()
                        val image = remember(code) { qrImage(code) }
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                            Image(image, contentDescription = "Pairing QR code", modifier = Modifier.size(260.dp))
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
                            "Works once · expires in %d:%02d · you'll confirm a 6-digit code here".format(left / 60, left % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { copyToClipboard(code) }) { Text("Copy code as text") }
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
                Text("Only continue if the phone shows this code:")
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
        title = { Text(if (selected == null) "Add headset" else "Is this a headset your phone knows?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val chosen = selected
                if (chosen == null) {
                    Text(
                        "Headsets paired with this PC in Windows Bluetooth settings:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bonded.isEmpty()) Text("No paired audio devices found. Pair your headset in Windows Settings → Bluetooth & devices.")
                    bonded.forEach { device ->
                        OptionRow(
                            title = device.name,
                            subtitle = if (device.fingerprint in mapped) "already added" else device.kind.name.lowercase().replace('_', ' '),
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
                            title = option.displayName,
                            subtitle = (if (option in matches) "Recommended · same Bluetooth address · " else "") + "on ${option.announcedBy.joinToString()}",
                            selected = target == option,
                        ) { target = option }
                    }
                    OptionRow(title = "New headset", subtitle = "Not added on any other device yet", selected = target == null) { target = null }
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
private fun OptionRow(title: String, subtitle: String, selected: Boolean, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) icon() else RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HeadsetDialog(
    overview: DeviceOverview,
    preferred: Boolean,
    released: Boolean,
    onPreferred: (Boolean) -> Unit,
    onMultipoint: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(overview.device.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (label, tone) = status(overview, released)
                StatusPill(label, tone)
                Spacer(Modifier.height(4.dp))
                ToggleRow("Tray headset", "“Move here” in the tray menu moves this headset.", preferred, onPreferred)
                ToggleRow("Multipoint headset", "Can stay connected to two devices; Move here then joins instead of disconnecting.", overview.device.multipoint, onMultipoint)
                if (released) {
                    Text(
                        "Handoff turned off this headset's Windows audio services when it moved it away, so Windows doesn't grab it back. " +
                            "Move here, or restore Windows audio to use it from Windows directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRestore) { Text("Restore Windows audio (connects it here)") }
                }
                TextButton(onClick = onForget) { Text("Forget on this PC", color = MaterialTheme.colorScheme.error) }
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
                OutlinedTextField(editing, { editing = it }, label = { Text("This PC's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ToggleRow(
                    "Start with Windows",
                    if (autostartAvailable) "Starts minimized to the tray so your phone can always reach this PC." else "Available in the installed app.",
                    startWithWindows,
                    onStartWithWindows,
                    enabled = autostartAvailable,
                )
                Text(
                    "Closing the window keeps Handoff running in the tray. Quit from the tray menu.\n" +
                        "Windows Firewall may ask to allow Handoff on private networks; allow it so linked devices can reach this PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateRow(update, autoUpdateCheck, canInstallUpdate, onAutoUpdateCheck, onCheckUpdate, onInstallUpdate, onReleasePage)
                Text(
                    "Handoff $version · identity key $keyFingerprint · MIT License",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { openInBrowser(HandoffTexts.REPO_URL) }, contentPadding = PaddingValues(0.dp)) { Text("Source code on GitHub") }
                    TextButton(onClick = { showLicenses = true }, contentPadding = PaddingValues(0.dp)) { Text("Open-source licenses") }
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
                    DesktopUpdates.State.Idle -> "Updates come from Handoff's GitHub releases and are signature-checked."
                    DesktopUpdates.State.Checking -> "Checking for updates…"
                    is DesktopUpdates.State.UpToDate -> "You're up to date (${state.latest})."
                    is DesktopUpdates.State.Available -> "Handoff ${state.manifest.version} is available."
                    is DesktopUpdates.State.Downloading -> "Downloading Handoff ${state.manifest.version}…"
                    is DesktopUpdates.State.Failed -> state.message
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCheck, enabled = state != DesktopUpdates.State.Checking) { Text("Check now") }
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
                    Button(onClick = { onInstall(manifest) }) { Text("Download and install") }
                }
                OutlinedButton(onClick = { onReleasePage(manifest) }) { Text("Release page") }
            }
        }
        ToggleRow("Check automatically", "Once a day. This contacts GitHub; nothing about you or your devices is sent.", autoCheck, onAutoCheck)
    }
}

/** Every bundled component, the PodSwitch credit and the full license texts. */
@Composable
fun LicensesDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Open-source licenses") },
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

@Composable
fun DiagnosticsDialog(report: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnostics") },
        text = {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = { copyToClipboard(report); onClose() }) { Text("Copy & close") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
