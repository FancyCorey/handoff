package dev.handoff.desktop.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageAction
import dev.handoff.core.text.HandoffTexts
import dev.handoff.desktop.DesktopUpdates

/** Callbacks from the home screen to the app shell. */
class HomeActions(
    val moveHere: (DeviceOverview) -> Unit,
    val cancelMove: (DeviceOverview) -> Unit,
    val openHeadset: (DeviceOverview) -> Unit,
    val addHeadset: () -> Unit,
    val linkPhone: () -> Unit,
    val settings: () -> Unit,
    val refresh: () -> Unit,
    val openHotspot: () -> Unit,
    val openBluetooth: () -> Unit,
)

@Composable
fun HomeScreen(
    pcName: String,
    adapter: AdapterState,
    devices: List<DeviceOverview>,
    stages: Map<LogicalDeviceId, Stage>,
    preferred: String?,
    peers: List<PeerOverview>,
    update: DesktopUpdates.State,
    actions: HomeActions,
) {
    // The tray headphones get the full stage; the others get compact cards.
    val main = devices.firstOrNull { it.device.logicalId.value == preferred } ?: devices.firstOrNull()
    val others = devices.filter { it !== main }

    fun act(d: DeviceOverview, action: StageAction) = when (action) {
        StageAction.MOVE_HERE, StageAction.USE_ONLY_HERE, StageAction.CONNECT_HERE_TOO -> actions.moveHere(d)
        StageAction.CANCEL -> actions.cancelMove(d)
        StageAction.TURN_ON_BLUETOOTH -> actions.openBluetooth()
        StageAction.ADD_HERE -> actions.addHeadset()
        StageAction.NONE -> Unit
    }

    // Surface provides the on-background content colour (dark mode text would be black otherwise).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(pcName, adapter, actions)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (update is DesktopUpdates.State.Available) {
                    item { Notice("Update available", null, "Update", actions.settings) }
                }
                if (main == null) {
                    item { FirstRun(peers.isEmpty(), actions) }
                } else {
                    item(key = main.device.logicalId.value) {
                        stages[main.device.logicalId]?.let { HeadsetCard(main, it, { a -> act(main, a) }, { actions.openHeadset(main) }) }
                    }
                    items(others, key = { it.device.logicalId.value }) { d ->
                        stages[d.device.logicalId]?.let { CompactCard(d, it, { a -> act(d, a) }, { actions.openHeadset(d) }) }
                    }
                    item {
                        TextButton(onClick = actions.addHeadset) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add headphones")
                        }
                    }
                }
                if (main != null || peers.isNotEmpty()) {
                    item { SectionLabel("Your devices") }
                    item { DeviceChips(peers, actions.linkPhone) }
                }
                if (peers.isNotEmpty() && peers.none { it.online }) {
                    item { Notice(HandoffTexts.DIRECT_TITLE, HandoffTexts.DIRECT_HELP, "Hotspot settings", actions.openHotspot) }
                }
            }
        }
    }
}

/** Slim header: the mark, the name, this PC as a chip, and a warning only when Bluetooth needs attention. */
@Composable
private fun Header(pcName: String, adapter: AdapterState, actions: HomeActions) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(30.dp)
        Spacer(Modifier.width(10.dp))
        Text("Handoff", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(
            pcName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (adapter != AdapterState.ON) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (adapter == AdapterState.NOT_AVAILABLE) "No Bluetooth" else HandoffTexts.BLUETOOTH_OFF,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.warning,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = actions.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

@Composable
private fun Title(d: DeviceOverview, stage: Stage, maxLines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.device.displayName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!stage.dimmed) {
                d.batteryPercent?.let {
                    Spacer(Modifier.width(8.dp))
                    BatteryChip(it)
                }
            }
        }
        Text(stage.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneColor(stage.statusTone))
    }
}

@Composable
private fun HeadsetCard(d: DeviceOverview, stage: Stage, onAct: (StageAction) -> Unit, onOpen: () -> Unit) {
    val moving = d.transferRunning
    Panel(Modifier.animateContentSize().clip(RoundedCornerShape(24.dp)).clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(d, stage)
            StageView(stage, Modifier.fillMaxWidth(), steps = d.transfer?.steps.orEmpty().takeIf { moving }.orEmpty())
            stage.line?.let { Text(it, fontSize = 12.sp, color = toneColor(stage.lineTone, quiet = true)) }
            if (moving) Text(HandoffTexts.CAN_LEAVE, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Unfinished(d)
            StageButton(stage.action, { onAct(stage.action) })
        }
    }
}

/** A move that failed or was cancelled: title, reason, and the rest behind More. */
@Composable
private fun Unfinished(d: DeviceOverview) {
    val t = d.transfer ?: return
    val result = t.result?.takeIf { !d.transferRunning && (it is HandoffResult.Failed || it == HandoffResult.Cancelled) } ?: return
    var more by remember(t.startedAtMs) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(HandoffTexts.result(result), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        HandoffTexts.help(result)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (more) {
            HandoffTexts.more(result)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            t.steps.forEach { Text("·  " + HandoffTexts.step(it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            TextButton(onClick = { more = true }, contentPadding = PaddingValues(0.dp)) { Text("More") }
        }
    }
}

@Composable
private fun CompactCard(d: DeviceOverview, stage: Stage, onAct: (StageAction) -> Unit, onOpen: () -> Unit) {
    Panel(Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StageView(stage, Modifier.width(64.dp), mini = true, steps = d.transfer?.steps.orEmpty().takeIf { d.transferRunning }.orEmpty())
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { Title(d, stage, maxLines = 2) }
            val action = stage.action
            if (action == StageAction.MOVE_HERE || action == StageAction.CONNECT_HERE_TOO || action == StageAction.USE_ONLY_HERE) {
                Spacer(Modifier.width(8.dp))
                TonalButton(action.label!!, { onAct(action) }, compact = true, icon = MoveIcon.takeIf { action != StageAction.USE_ONLY_HERE })
            }
        }
    }
}

/** Linked devices as chips: the dot means online; words only for exceptions. */
@Composable
private fun DeviceChips(peers: List<PeerOverview>, onLink: () -> Unit) {
    val c = tokens
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        peers.forEach { p ->
            val state = if (p.online) "online" else HandoffTexts.offline(p.probablyOtherNetwork)
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clearAndSetSemantics { contentDescription = "${p.peer.displayName}, $state" }
                    .padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(c.tile).border(1.dp, c.tileOutline, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(platformIcon(p.platform), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(p.peer.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Spacer(Modifier.width(6.dp))
                if (p.online) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.online))
                } else {
                    Text(HandoffTexts.offline(p.probablyOtherNetwork), style = MaterialTheme.typography.labelMedium, color = c.warning)
                }
            }
        }
        Row(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, c.tileOutline, RoundedCornerShape(50))
                .clickable(onClick = onLink)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.accent)
            Spacer(Modifier.width(6.dp))
            Text("Link a phone", style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
    }
}

/** First run: an empty stage in dashed outline and the two steps. */
@Composable
private fun FirstRun(noLinks: Boolean, actions: HomeActions) {
    val c = tokens
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Two steps to start", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .drawBehind {
                        drawRoundRect(
                            c.tileOutline,
                            cornerRadius = CornerRadius(24.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MoveIcon, contentDescription = null, tint = c.tileOutline, modifier = Modifier.size(40.dp))
            }
            Text("Add your headphones, then link your phone.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("Add headphones", actions.addHeadset)
            if (noLinks) OutlineButton("Link a phone", actions.linkPhone)
        }
    }
}

/** A short notice: title, an optional line, and one action. */
@Composable
private fun Notice(title: String, line: String?, action: String, onClick: () -> Unit) {
    Panel {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                line?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = onClick) { Text(action) }
        }
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
