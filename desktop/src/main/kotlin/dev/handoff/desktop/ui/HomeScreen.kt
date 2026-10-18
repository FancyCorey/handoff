package dev.handoff.desktop.ui

import dev.handoff.core.help.NetworkProblem
import dev.handoff.core.help.NetworkCheck
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferStep
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageAction
import dev.handoff.core.stage.Tone
import dev.handoff.core.text.HandoffTexts
import dev.handoff.desktop.DesktopUpdates
import kotlinx.coroutines.delay

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
    val openWifi: () -> Unit,
)

@Composable
fun HomeScreen(
    pcName: String,
    adapter: AdapterState,
    devices: List<DeviceOverview>,
    stages: Map<LogicalDeviceId, Stage>,
    preferred: String?,
    peers: List<PeerOverview>,
    onNetwork: Boolean,
    update: DesktopUpdates.State,
    actions: HomeActions,
) {
    // Devices on two networks can't reach each other: the most common reason a move fails.
    // A card that already names it doesn't need the notice too.
    val networkProblem = NetworkCheck.anyProblem(onNetwork, peers)?.takeIf { p -> stages.values.none { it.status == p.title } }
    // The tray headphones come first; every pair gets the same card.
    val ordered = devices.sortedByDescending { it.device.logicalId.value == preferred }

    fun act(d: DeviceOverview, action: StageAction) = when (action) {
        StageAction.MOVE_HERE, StageAction.USE_ONLY_HERE, StageAction.CONNECT_HERE_TOO -> actions.moveHere(d)
        StageAction.CANCEL -> actions.cancelMove(d)
        StageAction.TURN_ON_BLUETOOTH -> actions.openBluetooth()
        StageAction.ADD_HERE -> actions.addHeadset()
        StageAction.NONE -> Unit
    }

    fun fix(d: DeviceOverview, fix: HandoffTexts.Fix) = when (fix) {
        HandoffTexts.Fix.TRY_AGAIN, HandoffTexts.Fix.MOVE_HERE -> actions.moveHere(d)
        HandoffTexts.Fix.LINK_AGAIN -> actions.linkPhone()
        HandoffTexts.Fix.ADD_HERE -> actions.addHeadset()
        HandoffTexts.Fix.TURN_ON_BLUETOOTH, HandoffTexts.Fix.ALLOW, HandoffTexts.Fix.OPEN_BLUETOOTH_SETTINGS -> actions.openBluetooth()
    }

    // Surface provides the on-background content colour (dark mode text would be black otherwise).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(pcName, actions)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (adapter != AdapterState.ON) {
                    item {
                        Notice(
                            if (adapter == AdapterState.NOT_AVAILABLE) "No Bluetooth" else HandoffTexts.BLUETOOTH_OFF,
                            HandoffTexts.BLUETOOTH_OFF_HELP,
                            "Bluetooth settings",
                            warning = true,
                            onClick = actions.openBluetooth,
                        )
                    }
                }
                networkProblem?.let { problem ->
                    item {
                        when (problem) {
                            NetworkProblem.NotOnWifi -> Notice(problem.title, problem.help, HandoffTexts.WIFI_SETTINGS, warning = true, onClick = actions.openWifi)
                            is NetworkProblem.OtherWifi -> Notice(problem.title, problem.help, "Hotspot settings", warning = true, onClick = actions.openHotspot)
                        }
                    }
                }
                if (update is DesktopUpdates.State.Available) {
                    item { Notice("Update available", null, "Update", onClick = actions.settings) }
                }
                item { Section("Your headphones", if (devices.isNotEmpty()) "Add" else null, actions.addHeadset) }
                if (devices.isEmpty()) item { AddHeadphones(actions.addHeadset) }
                items(ordered, key = { it.device.logicalId.value }) { d ->
                    stages[d.device.logicalId]?.let { stage ->
                        HeadsetCard(d, stage, { a -> act(d, a) }, { f -> fix(d, f) }, { actions.openHeadset(d) }) { failed ->
                            NetworkCheck.explain(failed, peers.firstOrNull { it.peer.peerId == d.transfer?.ownerPeer }, onNetwork)
                        }
                    }
                }
                item { Section("Your devices", if (peers.isNotEmpty()) "Link a phone" else null, actions.linkPhone) }
                item { DeviceList(pcName, peers, actions.linkPhone) }
                if (networkProblem == null && peers.isNotEmpty() && peers.none { it.online }) {
                    item { Notice(HandoffTexts.DIRECT_TITLE, HandoffTexts.DIRECT_HELP, "Hotspot settings", onClick = actions.openHotspot) }
                }
            }
        }
    }
}

/** The branded header: the mark, "Handoff", this PC's name, refresh and settings. */
@Composable
private fun Header(pcName: String, actions: HomeActions) {
    Box(Modifier.fillMaxWidth().background(Brand.gradient).padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(44.dp, onBrand = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Handoff", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(pcName, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
            IconButton(onClick = actions.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White) }
            IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White) }
        }
    }
}

@Composable
private fun Section(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(action)
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/**
 * One pair of headphones: icon, name, status and battery, then whatever applies now: the move
 * in progress, the result of the last move, or one line and the button.
 */
@Composable
private fun HeadsetCard(
    d: DeviceOverview,
    stage: Stage,
    onAct: (StageAction) -> Unit,
    onFix: (HandoffTexts.Fix) -> Unit,
    onOpen: () -> Unit,
    explain: (HandoffResult.Failed) -> Pair<String, String?>,
) {
    val transfer = d.transfer
    val result = transfer?.result
    var watched by remember(d.device.logicalId) { mutableStateOf(false) }
    var justMoved by remember(d.device.logicalId) { mutableStateOf(false) }
    var showSteps by remember(d.device.logicalId) { mutableStateOf(false) }
    LaunchedEffect(d.transferRunning, result) {
        if (d.transferRunning) {
            watched = true
        } else if (watched) {
            watched = false
            if (result is HandoffResult.Success || result == HandoffResult.AlreadyConnected) {
                justMoved = true
                delay(MOVED_SHOWN_MS)
                justMoved = false
            }
        }
    }
    val failed = (result as? HandoffResult.Failed)?.takeIf {
        !d.transferRunning && System.currentTimeMillis() - transfer.startedAtMs < FAILURE_SHOWN_MS && !stage.dimmed
    }

    Panel(Modifier.animateContentSize().clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(kindIcon(d.device.deviceType))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.device.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (stage.dimmed) {
                            Text(stage.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            StatusPill(stage.status, stage.statusTone)
                            d.batteryPercent?.let { BatteryChip(it) }
                        }
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                d.transferRunning -> Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepBar(transfer?.steps.orEmpty())
                    Text(
                        transfer?.steps?.lastOrNull()?.let(HandoffTexts::step) ?: HandoffTexts.STARTING,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showSteps) Steps(transfer?.steps.orEmpty())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { OutlineButton(HandoffTexts.CANCEL, { onAct(StageAction.CANCEL) }) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide details" else "Details") }
                    }
                }
                failed != null -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val (title, reason) = explain(failed)
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    (reason ?: HandoffTexts.more(failed))?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showSteps) Steps(transfer?.steps.orEmpty())
                    TextButton(onClick = { showSteps = !showSteps }, contentPadding = PaddingValues(0.dp)) { Text(if (showSteps) "Hide details" else "Details") }
                    val fix = HandoffTexts.fix(failed) ?: HandoffTexts.Fix.TRY_AGAIN
                    PrimaryButton(HandoffTexts.label(fix), { onFix(fix) })
                }
                else -> {
                    AnimatedVisibility(justMoved) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = tokens.online, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(HandoffTexts.MOVED_HERE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.online)
                        }
                    }
                    stage.line?.let {
                        Text(it, fontSize = 13.sp, color = toneColor(stage.lineTone, quiet = true), modifier = Modifier.padding(top = 10.dp))
                    }
                    val action = stage.action
                    if (action != StageAction.NONE && action != StageAction.CANCEL && action != StageAction.TURN_ON_BLUETOOTH) {
                        StageButton(action, { onAct(action) }, Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

/** Four steps of a move, filled in as they happen: asking, let go, connecting, checking. */
@Composable
private fun StepBar(steps: List<TransferStep>) {
    val reached = steps.maxOfOrNull {
        when (it.kind) {
            StepKind.REQUESTING_RELEASE -> 1
            StepKind.RELEASED, StepKind.PEER_NOT_CONNECTED, StepKind.DIRECT_TAKEOVER -> 2
            StepKind.CONNECTING, StepKind.RETRYING, StepKind.AUTO_RETRY -> 3
            StepKind.VERIFYING, StepKind.CONNECTED, StepKind.ALREADY_CONNECTED -> 4
            else -> 0
        }
    } ?: 0
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (i < reached.coerceAtLeast(1)) tokens.accent else tokens.accentSoft),
            )
        }
    }
}

@Composable
private fun Steps(steps: List<TransferStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEach { Text("·  " + HandoffTexts.step(it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Every linked device in one list, this PC first. */
@Composable
private fun DeviceList(pcName: String, peers: List<PeerOverview>, onLink: () -> Unit) {
    Panel {
        Column {
            DeviceRow(Icons.Filled.Computer, pcName) {
                Text(HandoffTexts.me, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            peers.forEach { p ->
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                DeviceRow(platformIcon(p.platform), p.peer.displayName) {
                    when {
                        p.online -> StatusPill("Online", Tone.POSITIVE)
                        p.probablyOtherNetwork -> StatusPill(HandoffTexts.offline(true), Tone.WARNING)
                        else -> StatusPill(HandoffTexts.offline(false), Tone.NORMAL)
                    }
                }
            }
            if (peers.isEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onLink).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(tokens.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = tokens.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Link your phone or tablet", style = MaterialTheme.typography.bodyLarge, color = tokens.accent, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** First run: nothing added yet. */
@Composable
private fun AddHeadphones(onAdd: () -> Unit) {
    Panel {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(Icons.Filled.Headphones, size = 56.dp)
            Text("Add your headphones", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose them from the ones connected to ${HandoffTexts.meInSentence}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Add headphones", onAdd, Modifier.padding(top = 6.dp), icon = Icons.Outlined.Add)
        }
    }
}

/** A short notice: title, an optional line, and one action. */
@Composable
private fun Notice(title: String, line: String?, action: String, warning: Boolean = false, onClick: () -> Unit) {
    val bg = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f)) }
        }
        TextButton(onClick = onClick) { Text(action, fontWeight = FontWeight.SemiBold, color = if (warning) fg else tokens.accent) }
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

private const val MOVED_SHOWN_MS = 4_000L
private const val FAILURE_SHOWN_MS = 5 * 60_000L
