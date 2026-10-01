package dev.handoff.desktop.ui

import dev.handoff.desktop.DesktopUpdates
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.text.HandoffTexts
import dev.handoff.core.model.AudioConnectionState

/** Callbacks from the home screen to the app shell. */
class HomeActions(
    val moveHere: (DeviceOverview) -> Unit,
    val cancelMove: (DeviceOverview) -> Unit,
    val openHeadset: (DeviceOverview) -> Unit,
    val addHeadset: () -> Unit,
    val linkPhone: () -> Unit,
    val settings: () -> Unit,
    val diagnostics: () -> Unit,
    val refresh: () -> Unit,
    val openHotspot: () -> Unit,
)

@Composable
fun HomeScreen(
    pcName: String,
    adapter: AdapterState,
    devices: List<DeviceOverview>,
    peers: List<PeerOverview>,
    released: Set<String>,
    update: DesktopUpdates.State,
    actions: HomeActions,
) {
    // Surface provides the on-background content colour (dark mode text would be black otherwise).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize()) {
        Header(pcName, adapter, actions)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (update is DesktopUpdates.State.Available) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Handoff ${update.manifest.version} is available", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            TextButton(onClick = actions.settings) { Text("Update") }
                        }
                    }
                }
            }
            item { SectionTitle("Your audio", action = "Add headset", onAction = actions.addHeadset) }
            if (devices.isEmpty()) {
                item {
                    EmptyCard(
                        icon = Icons.Filled.Headphones,
                        title = "No headset yet",
                        body = "Pick headphones that are already paired with this PC in Windows Bluetooth settings.",
                        button = "Add headset",
                        onClick = actions.addHeadset,
                    )
                }
            }
            items(devices, key = { it.device.logicalId.value }) { d ->
                HeadsetCard(
                    d,
                    released = d.device.localDeviceId?.address?.uppercase() in released,
                    onMove = { actions.moveHere(d) },
                    onCancel = { actions.cancelMove(d) },
                    onMore = { actions.openHeadset(d) },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionTitle("Your devices", action = "Link a phone", onAction = actions.linkPhone) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column {
                        DeviceRow(Icons.Filled.Computer, pcName, "This PC", Tone.ACTIVE)
                        peers.forEach { p ->
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            DeviceRow(
                                platformIcon(p.platform),
                                p.peer.displayName,
                                if (p.online) "Online" else HandoffTexts.offline(p.probablyOtherNetwork),
                                when {
                                    p.online -> Tone.POSITIVE
                                    p.probablyOtherNetwork -> Tone.WARNING
                                    else -> Tone.NEUTRAL
                                },
                            )
                        }
                        if (peers.isEmpty()) {
                            Text(
                                "Link your phone or tablet: open Handoff there, go to My devices → Scan a Handoff code, and scan the code from “Link a phone”.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
            if (peers.isNotEmpty() && peers.none { it.online }) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(HandoffTexts.DIRECT_TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(HandoffTexts.DIRECT_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = actions.openHotspot, contentPadding = PaddingValues(0.dp)) { Text("Open Mobile hotspot settings") }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Header(pcName: String, adapter: AdapterState, actions: HomeActions) {
    Box(Modifier.fillMaxWidth().background(Brand.gradient).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Handoff", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    when (adapter) {
                        AdapterState.ON -> "$pcName · Bluetooth on"
                        AdapterState.NOT_AVAILABLE -> "$pcName · no Bluetooth adapter"
                        else -> "$pcName · Bluetooth is off"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            HeaderButton(Icons.Filled.Refresh, "Refresh", actions.refresh)
            HeaderButton(Icons.Outlined.BugReport, "Diagnostics", actions.diagnostics)
            HeaderButton(Icons.Filled.Settings, "Settings", actions.settings)
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
}

@Composable
private fun SectionTitle(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Icon(if (action == "Link a phone") Icons.Outlined.QrCode2 else Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(action)
        }
    }
}

@Composable
private fun HeadsetCard(d: DeviceOverview, released: Boolean, onMove: () -> Unit, onCancel: () -> Unit, onMore: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge { Icon(kindIcon(d.device.deviceType), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val (label, tone) = status(d, released)
                        StatusPill(label, tone)
                        d.batteryPercent?.let { BatteryChip(it) }
                    }
                }
                IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
            }
            val t = d.transfer
            when {
                d.transferRunning && t != null -> TransferProgress(t.steps.map(HandoffTexts::step), onCancel)
                d.device.localDeviceId == null -> OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("Choose this headset on this PC") }
                d.connectedHere -> Unit
                else -> Button(onClick = onMove, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (d.ownership is Ownership.Multipoint) "Move media here" else "Move here")
                }
            }
            AnimatedVisibility(visible = !d.transferRunning && t?.result != null && t.result !is HandoffResult.Success) {
                t?.let { ResultLine(it.result ?: return@let, it.steps.map(HandoffTexts::step)) }
            }
        }
    }
}

/** Every step so far, newest last, with a way to stop the move. */
@Composable
private fun TransferProgress(steps: List<String>, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
        StepList(steps.ifEmpty { listOf("Starting…") }, activeLast = true)
        TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) { Text("Cancel") }
    }
}

@Composable
private fun StepList(steps: List<String>, activeLast: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEachIndexed { i, step ->
            val current = activeLast && i == steps.lastIndex
            Text(
                (if (current) "›  " else "✓  ") + step,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultLine(result: HandoffResult, steps: List<String>) {
    var showSteps by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ok = result is HandoffResult.AlreadyConnected
        val cancelled = result == HandoffResult.Cancelled
        Icon(
            when {
                ok -> Icons.Filled.CheckCircle
                cancelled -> Icons.Filled.Close
                else -> Icons.Filled.Error
            },
            contentDescription = null,
            tint = when {
                ok -> Brand.Success
                cancelled -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(HandoffTexts.result(result), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            HandoffTexts.help(result)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (steps.isNotEmpty()) {
                TextButton(onClick = { showSteps = !showSteps }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showSteps) "Hide steps" else "Show steps")
                }
                if (showSteps) StepList(steps, activeLast = false)
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, status: String, tone: Tone) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(container = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        StatusPill(status, tone)
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, body: String, button: String, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}

/** How this PC appears in holder lists (DesktopApp passes the same label to OverviewRepository). */
private const val THIS_PC = "This PC"

fun status(d: DeviceOverview, released: Boolean): Pair<String, Tone> = when (val o = d.ownership) {
    Ownership.Local -> "Connected to this PC" to Tone.POSITIVE
    is Ownership.Peer -> HandoffTexts.whereConnected(d.holders, THIS_PC) to Tone.ACTIVE
    is Ownership.Multipoint ->
        HandoffTexts.whereConnected(d.holders, THIS_PC) to (if (d.localState == AudioConnectionState.CONNECTED) Tone.POSITIVE else Tone.ACTIVE)
    is Ownership.Conflict -> "Reported: ${HandoffTexts.whereConnected(d.holders, THIS_PC).replaceFirstChar { it.lowercase() }}" to Tone.WARNING
    Ownership.None -> (if (released) "Handed off · not on this PC" else "Not connected") to Tone.NEUTRAL
    is Ownership.Unknown -> when {
        d.device.localDeviceId == null -> "Not set up on this PC" to Tone.WARNING
        o.lastKnownOwner != null -> "Last seen on another device" to Tone.NEUTRAL
        else -> "Not connected here" to Tone.NEUTRAL
    }
}

@Composable
fun BatteryChip(percent: Int) {
    val icon = when {
        percent >= 90 -> Icons.Filled.BatteryFull
        percent >= 60 -> Icons.Filled.Battery5Bar
        percent >= 35 -> Icons.Filled.Battery3Bar
        percent >= 15 -> Icons.Filled.Battery2Bar
        else -> Icons.Filled.BatteryAlert
    }
    val tint = if (percent < 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = "Battery", tint = tint, modifier = Modifier.size(16.dp))
        Text("$percent%", style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

fun kindIcon(kind: AudioDeviceKind): ImageVector = when (kind) {
    AudioDeviceKind.HEADPHONES -> Icons.Filled.Headphones
    AudioDeviceKind.SPEAKER, AudioDeviceKind.CAR_AUDIO -> Icons.Filled.Speaker
    else -> Icons.Filled.Headset
}

fun platformIcon(platform: String?): ImageVector = when (platform) {
    Platforms.WINDOWS -> Icons.Filled.Computer
    Platforms.ANDROID_TABLET -> Icons.Filled.TabletAndroid
    Platforms.ANDROID_PHONE -> Icons.Filled.PhoneAndroid
    else -> Icons.Filled.Devices
}
