package dev.handoff.app.feature.home

import dev.handoff.app.ui.MAX_CONTENT_WIDTH
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.ads.HomeAdSlot
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.app.ui.BatteryChip
import dev.handoff.app.ui.BrandHeader
import dev.handoff.app.ui.IconBadge
import dev.handoff.app.ui.OtherCopies
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.ui.PrimaryButton
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionHeader
import dev.handoff.app.ui.StageButton
import dev.handoff.app.ui.StatusPill
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.ThisDevice
import dev.handoff.app.ui.Tone
import dev.handoff.app.ui.platformIcon
import dev.handoff.app.ui.toneColor
import dev.handoff.app.ui.tokens
import dev.handoff.app.update.UpdateService
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferStep
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageAction
import dev.handoff.core.stage.StageRepository
import dev.handoff.core.text.HandoffTexts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class HomeViewModel(
    overview: OverviewRepository,
    private val actions: HandoffActions,
    private val runtime: HandoffRuntime,
    identity: KeystoreIdentityProvider,
    updates: UpdateService,
    stages: StageRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val update: StateFlow<String?> = updates.newerVersion
    val devices: StateFlow<List<DeviceOverview>> = overview.devices
    val peers: StateFlow<List<PeerOverview>> = overview.peers
    val selfName: StateFlow<String> = identity.displayName
    val stages: StateFlow<Map<LogicalDeviceId, Stage>> = stages.stages
    val preferred: StateFlow<LogicalDeviceId?> = settings.settings.map { it.preferredDevice }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun cancel(id: LogicalDeviceId) {
        actions.cancel(id)
    }

    fun moveHere(id: LogicalDeviceId) {
        actions.moveHere(id, TransferTrigger.MANUAL)
    }

    suspend fun refresh() = runtime.refreshNow()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDevice: (String) -> Unit,
    onOpenTransfer: (String) -> Unit,
    onMapDevice: () -> Unit,
    onPeers: () -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel = koinViewModel(),
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val selfName by vm.selfName.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val stages by vm.stages.collectAsStateWithLifecycle()
    val preferred by vm.preferred.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var otherCopy by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { otherCopy = OtherCopies.testBuildInstalled(context) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    // The Quick Settings headphones come first; every pair gets the same card.
    val ordered = devices.sortedByDescending { it.device.logicalId == preferred }
    val bluetoothOff = stages.values.any { it.dimmed }

    fun act(o: DeviceOverview, action: StageAction) {
        val id = o.device.logicalId
        when (action) {
            StageAction.MOVE_HERE, StageAction.USE_ONLY_HERE, StageAction.CONNECT_HERE_TOO -> vm.moveHere(id)
            StageAction.CANCEL -> vm.cancel(id)
            StageAction.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
            StageAction.ADD_HERE -> onOpenDevice(id.value)
            StageAction.NONE -> Unit
        }
    }

    fun fix(o: DeviceOverview, fix: HandoffTexts.Fix) {
        when (fix) {
            HandoffTexts.Fix.TRY_AGAIN, HandoffTexts.Fix.MOVE_HERE -> vm.moveHere(o.device.logicalId)
            HandoffTexts.Fix.LINK_AGAIN -> onPeers()
            HandoffTexts.Fix.ADD_HERE -> onOpenDevice(o.device.logicalId.value)
            HandoffTexts.Fix.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
            HandoffTexts.Fix.ALLOW -> SystemScreens.appSettings(context)
            HandoffTexts.Fix.OPEN_BLUETOOTH_SETTINGS -> SystemScreens.bluetoothSettings(context)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader(title = "Handoff", subtitle = selfName) {
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White) }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        vm.refresh()
                        refreshing = false
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().wrapContentWidth().widthIn(max = MAX_CONTENT_WIDTH),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                ) {
                    if (otherCopy) {
                        item {
                            Notice("An old copy of Handoff is installed", "It can confuse your other devices.", "Remove it", warning = true) {
                                OtherCopies.openTestBuildInfo(context)
                            }
                        }
                    }
                    if (bluetoothOff) {
                        item {
                            Notice(HandoffTexts.BLUETOOTH_OFF, HandoffTexts.BLUETOOTH_OFF_HELP, HandoffTexts.TURN_ON, warning = true) {
                                SystemScreens.turnOnBluetooth(context)
                            }
                        }
                    }
                    update?.let {
                        item { Notice("Update available", null, "Update", onClick = onSettings) }
                    }

                    item {
                        SectionHeader("Your headphones") {
                            if (devices.isNotEmpty()) SectionAction("Add", onMapDevice)
                        }
                    }
                    if (devices.isEmpty()) {
                        item { AddHeadphones(onMapDevice) }
                    }
                    items(ordered, key = { it.device.logicalId.value }) { o ->
                        stages[o.device.logicalId]?.let { stage ->
                            HeadsetCard(
                                o, stage,
                                onAct = { act(o, it) },
                                onFix = { fix(o, it) },
                                onOpen = { if (o.transferRunning) onOpenTransfer(o.device.logicalId.value) else onOpenDevice(o.device.logicalId.value) },
                                onDetails = { onOpenTransfer(o.device.logicalId.value) },
                            )
                        }
                    }

                    item {
                        SectionHeader("Your devices") {
                            if (peers.isNotEmpty()) SectionAction("Link a device", onPeers)
                        }
                    }
                    item { DeviceList(selfName, ThisDevice.platform(context), peers, onPeers) }
                    if (peers.isNotEmpty() && peers.none { it.online }) {
                        item {
                            Notice(HandoffTexts.DIRECT_TITLE, HandoffTexts.DIRECT_HELP, "Hotspot settings") { SystemScreens.hotspot(context) }
                        }
                    }
                }
            }
            // Google Play edition only: one banner below the list. Nothing in the GitHub edition.
            HomeAdSlot()
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SectionAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(48.dp)) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

/**
 * One pair of headphones: icon, name, status and battery, then whatever applies now: the move
 * in progress, the result of the last move, or one line and the button.
 */
@Composable
private fun HeadsetCard(
    o: DeviceOverview,
    stage: Stage,
    onAct: (StageAction) -> Unit,
    onFix: (HandoffTexts.Fix) -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
) {
    val transfer = o.transfer
    val result = transfer?.result

    // "Moved here" shows for a few seconds after a move this screen watched finishes.
    var watched by remember(o.device.logicalId) { mutableStateOf(false) }
    var justMoved by remember(o.device.logicalId) { mutableStateOf(false) }
    LaunchedEffect(o.transferRunning, result) {
        if (o.transferRunning) {
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
    // A failed move stays visible for a while, with the one button that fixes it.
    val failed = (result as? HandoffResult.Failed)?.takeIf {
        !o.transferRunning && System.currentTimeMillis() - transfer.startedAtMs < FAILURE_SHOWN_MS && !stage.dimmed
    }

    SectionCard(Modifier.animateContentSize().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Headphones, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(o.device.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (stage.dimmed) {
                            Text(stage.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            StatusPill(stage.status, stage.statusTone)
                            o.batteryPercent?.let { BatteryChip(it) }
                        }
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                o.transferRunning -> Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepBar(transfer?.steps.orEmpty())
                    Text(
                        transfer?.steps?.lastOrNull()?.let(HandoffTexts::step) ?: HandoffTexts.STARTING,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { OutlineButton(HandoffTexts.CANCEL, { onAct(StageAction.CANCEL) }) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDetails, modifier = Modifier.height(48.dp)) { Text("Details") }
                    }
                }
                failed != null -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(HandoffTexts.result(failed), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    (HandoffTexts.help(failed) ?: HandoffTexts.more(failed))?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDetails, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(40.dp)) { Text("What to do") }
                    val fix = HandoffTexts.fix(failed) ?: HandoffTexts.Fix.TRY_AGAIN
                    PrimaryButton(HandoffTexts.label(fix), { onFix(fix) }, Modifier.padding(top = 8.dp))
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
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Step ${reached.coerceAtLeast(1)} of 4" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** Every linked device in one list, this one first. */
@Composable
private fun DeviceList(selfName: String, selfPlatform: String, peers: List<PeerOverview>, onPeers: () -> Unit) {
    SectionCard {
        DeviceRow(platformIcon(selfPlatform), selfName) {
            Text(HandoffTexts.me, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        peers.forEach { p ->
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            DeviceRow(platformIcon(p.platform), p.peer.displayName, onClick = onPeers) {
                when {
                    p.online -> StatusPill("Online", Tone.POSITIVE)
                    p.probablyOtherNetwork -> StatusPill(HandoffTexts.offline(true), Tone.WARNING)
                    else -> StatusPill(HandoffTexts.offline(false), Tone.NEUTRAL)
                }
            }
        }
        if (peers.isEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onPeers).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(tokens.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = tokens.accent)
                }
                Spacer(Modifier.width(12.dp))
                Text("Link your PC or tablet", style = MaterialTheme.typography.bodyLarge, color = tokens.accent, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** First run: nothing added yet. */
@Composable
private fun AddHeadphones(onAdd: () -> Unit) {
    SectionCard {
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
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f)) }
        }
        TextButton(onClick = onClick, modifier = Modifier.height(48.dp)) { Text(action, fontWeight = FontWeight.SemiBold, color = if (warning) fg else tokens.accent) }
    }
}

private const val MOVED_SHOWN_MS = 4_000L
private const val FAILURE_SHOWN_MS = 5 * 60_000L
