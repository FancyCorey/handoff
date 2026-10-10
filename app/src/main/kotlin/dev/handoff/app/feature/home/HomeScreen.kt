package dev.handoff.app.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.handoff.app.ads.HomeAdSlot
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.app.ui.BatteryChip
import dev.handoff.app.ui.Logo
import dev.handoff.app.ui.MoveIcon
import dev.handoff.app.ui.OtherCopies
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.ui.PrimaryButton
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionLabel
import dev.handoff.app.ui.StageButton
import dev.handoff.app.ui.StageView
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.TonalButton
import dev.handoff.app.ui.platformIcon
import dev.handoff.app.ui.toneColor
import dev.handoff.app.ui.tokens
import dev.handoff.app.update.UpdateService
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageAction
import dev.handoff.core.stage.StageRepository
import dev.handoff.core.text.HandoffTexts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
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
    onMoveStarted: (String) -> Unit,
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

    // The Quick Settings headphones get the full stage; the others get compact cards.
    val main = devices.firstOrNull { it.device.logicalId == preferred } ?: devices.firstOrNull()
    val others = devices.filter { it !== main }

    fun act(o: DeviceOverview, action: StageAction) {
        val id = o.device.logicalId
        when (action) {
            StageAction.MOVE_HERE, StageAction.USE_ONLY_HERE, StageAction.CONNECT_HERE_TOO -> {
                vm.moveHere(id)
                onMoveStarted(id.value)
            }
            StageAction.CANCEL -> vm.cancel(id)
            StageAction.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
            StageAction.ADD_HERE -> onOpenDevice(id.value)
            StageAction.NONE -> Unit
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(selfName, onSettings)
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
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                    if (otherCopy) {
                        item {
                            Notice(
                                "An old copy of Handoff is installed",
                                "It can confuse your other devices.",
                                "Remove it",
                                warning = true,
                            ) { OtherCopies.openTestBuildInfo(context) }
                        }
                    }
                    update?.let {
                        item { Notice("Update available", null, "Update", onClick = onSettings) }
                    }
                    if (main == null) {
                        item { FirstRun(peers.isEmpty(), onMapDevice, onPeers) }
                    } else {
                        item(key = main.device.logicalId.value) {
                            val stage = stages[main.device.logicalId]
                            if (stage != null) {
                                HeadsetCard(
                                    main, stage,
                                    onAct = { act(main, it) },
                                    onOpen = { onOpenDevice(main.device.logicalId.value) },
                                    onProgress = { onOpenTransfer(main.device.logicalId.value) },
                                )
                            }
                        }
                        items(others, key = { it.device.logicalId.value }) { o ->
                            stages[o.device.logicalId]?.let { stage ->
                                CompactCard(
                                    o, stage,
                                    onAct = { act(o, it) },
                                    onOpen = {
                                        if (o.transferRunning) onOpenTransfer(o.device.logicalId.value) else onOpenDevice(o.device.logicalId.value)
                                    },
                                )
                            }
                        }
                        item {
                            TextButton(onClick = onMapDevice, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add headphones")
                            }
                        }
                    }
                    if (peers.isNotEmpty() || main != null) {
                        item { SectionLabel("Your devices", Modifier.padding(top = 8.dp)) }
                        item { DeviceChips(peers, onPeers) }
                    }
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

/** Slim header: the mark, the name, this device as a chip, and Settings. */
@Composable
private fun Header(selfName: String, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(30.dp)
        Spacer(Modifier.width(10.dp))
        Text("Handoff", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(
            selfName,
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
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }
}

/** Name and battery, then the status in its tone. */
@Composable
private fun Title(o: DeviceOverview, stage: Stage, maxLines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                o.device.displayName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!stage.dimmed) {
                o.batteryPercent?.let {
                    Spacer(Modifier.width(8.dp))
                    BatteryChip(it)
                }
            }
        }
        Text(stage.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneColor(stage.statusTone))
    }
}

@Composable
private fun HeadsetCard(o: DeviceOverview, stage: Stage, onAct: (StageAction) -> Unit, onOpen: () -> Unit, onProgress: () -> Unit) {
    val moving = o.transferRunning
    SectionCard(Modifier.animateContentSize().clickable(onClick = if (moving) onProgress else onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(o, stage)
            StageView(stage, Modifier.fillMaxWidth(), steps = o.transfer?.steps.orEmpty().takeIf { moving }.orEmpty())
            stage.line?.let { Text(it, fontSize = 12.sp, color = toneColor(stage.lineTone, quiet = true)) }
            Unfinished(o, onProgress)
            if (moving) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StageButton(StageAction.CANCEL, { onAct(StageAction.CANCEL) }) }
                    TextButton(onClick = onProgress) { Text(HandoffTexts.SHOW_PROGRESS) }
                }
            } else {
                StageButton(stage.action, { onAct(stage.action) })
            }
        }
    }
}

/** A move that failed or was cancelled: its title and reason, with the details one tap away. */
@Composable
private fun Unfinished(o: DeviceOverview, onProgress: () -> Unit) {
    val result = o.transfer?.result?.takeIf { !o.transferRunning && (it is HandoffResult.Failed || it == HandoffResult.Cancelled) } ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(HandoffTexts.result(result), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            HandoffTexts.help(result)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = onProgress) { Text("More") }
    }
}

@Composable
private fun CompactCard(o: DeviceOverview, stage: Stage, onAct: (StageAction) -> Unit, onOpen: () -> Unit) {
    SectionCard(
        Modifier
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StageView(stage, Modifier.width(64.dp), mini = true, steps = o.transfer?.steps.orEmpty().takeIf { o.transferRunning }.orEmpty())
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { Title(o, stage, maxLines = 2) }
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
private fun DeviceChips(peers: List<PeerOverview>, onPeers: () -> Unit) {
    val c = tokens
    val fade = MaterialTheme.colorScheme.background
    Box {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(peers, key = { it.peer.peerId.value }) { p ->
                val state = if (p.online) "online" else HandoffTexts.offline(p.probablyOtherNetwork)
                Row(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onPeers)
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
                    Text(p.peer.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    Spacer(Modifier.width(6.dp))
                    if (p.online) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.online))
                    } else {
                        Text(HandoffTexts.offline(p.probablyOtherNetwork), style = MaterialTheme.typography.labelMedium, color = c.warning)
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, c.tileOutline, RoundedCornerShape(50))
                        .clickable(onClick = onPeers)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text("Link a device", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
        }
        // A fade at the right edge says the row scrolls.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(24.dp)
                .height(40.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, fade))),
        )
    }
}

/** First run: an empty stage in dashed outline and the two steps. */
@Composable
private fun FirstRun(noLinks: Boolean, onAdd: () -> Unit, onLink: () -> Unit) {
    val c = tokens
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Two steps to start", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
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
            Text("Add your headphones, then link your other devices.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("Add headphones", onAdd)
            if (noLinks) OutlineButton("Link a device", onLink)
        }
    }
}

/** A short notice: title, an optional line, and one action. */
@Composable
private fun Notice(title: String, line: String?, action: String, warning: Boolean = false, onClick: () -> Unit) {
    SectionCard {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (warning) tokens.warning else Color.Unspecified)
                line?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
