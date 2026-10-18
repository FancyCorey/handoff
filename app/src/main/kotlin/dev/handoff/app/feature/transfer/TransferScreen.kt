package dev.handoff.app.feature.transfer

import dev.handoff.core.help.NetworkCheck
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import dev.handoff.app.ui.MoveIcon
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.ui.PrimaryButton
import dev.handoff.app.ui.StageView
import dev.handoff.app.ui.SystemScreens
import dev.handoff.app.ui.toneColor
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageRepository
import dev.handoff.core.text.HandoffTexts
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.ui.Brand
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.HeroIcon
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.Texts
import dev.handoff.core.handoff.HandoffCoordinator
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.HandoffState
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.OverviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class TransferViewModel(
    logicalId: String,
    /** Opened to look at the latest move (running or finished), not to start a new one. */
    private val viewOnly: Boolean,
    coordinator: HandoffCoordinator,
    private val actions: HandoffActions,
    overview: OverviewRepository,
    stages: StageRepository,
) : ViewModel() {
    private val id = LogicalDeviceId(logicalId)

    /** When starting a move, ignore the previous move's finished result. */
    private val since = MutableStateFlow(if (viewOnly) 0L else System.currentTimeMillis() - STALE_SLACK_MS)

    val transfer: StateFlow<HandoffState?> = combine(coordinator.transfers, since) { all, openedAt ->
        // A move that is still running is always shown, so the screen can be left and reopened.
        all[id]?.takeIf { it.result == null || it.startedAtMs >= openedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun cancel() {
        actions.cancel(id)
    }

    val deviceName: StateFlow<String> = overview.devices
        .map { list -> list.firstOrNull { it.device.logicalId == id }?.device?.displayName ?: "Headphones" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Headphones")

    val stage: StateFlow<Stage?> = stages.stages.map { it[id] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A failed move's title and reason, naming the network problem when that is why. */
    val explained: StateFlow<Pair<String, String?>?> = combine(transfer, overview.peers, stages.onNetwork) { t, peers, net ->
        (t?.result as? HandoffResult.Failed)?.let { failed -> NetworkCheck.explain(failed, peers.firstOrNull { it.peer.peerId == t.ownerPeer }, net) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() {
        since.value = System.currentTimeMillis() - STALE_SLACK_MS
        actions.moveHere(id, TransferTrigger.MANUAL)
    }

    private companion object {
        const val STALE_SLACK_MS = 1_000L
    }
}

@Composable
fun TransferScreen(
    logicalId: String,
    onDone: () -> Unit,
    onDiagnostics: () -> Unit,
    onPeers: () -> Unit,
    onAdd: () -> Unit,
    viewOnly: Boolean = false,
    vm: TransferViewModel = koinViewModel(key = "transfer-$logicalId-$viewOnly") { parametersOf(logicalId, viewOnly) },
) {
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val t = transfer
    val result = t?.result
    val explained by vm.explained.collectAsStateWithLifecycle()
    var showSteps by remember(t?.startedAtMs) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // When the move ends, its result (and why it failed) is the first thing on screen.
    LaunchedEffect(result) { if (result != null) scroll.animateScrollTo(0) }

    HandoffScaffold(title = name, onBack = onDone) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            stage?.let { s ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneColor(s.statusTone))
                    StageView(s, Modifier.fillMaxWidth(), steps = t?.steps.orEmpty().takeIf { result == null }.orEmpty())
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val failed = result != null && !result.isGood() && result != HandoffResult.Cancelled && result != HandoffResult.InProgress
                Text(
                    when {
                        result is HandoffResult.Failed -> explained?.first ?: Texts.result(result)
                        result != null -> Texts.result(result)
                        t == null -> HandoffTexts.STARTING
                        else -> t.steps.lastOrNull()?.let(Texts::step) ?: HandoffTexts.STARTING
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                (if (result is HandoffResult.Failed) explained?.second else result?.let(Texts::help))?.let { help ->
                    Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result?.let(HandoffTexts::more)?.let { detail ->
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (result is HandoffResult.Success) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.timings.releaseMs?.let { TimingChip("Let go", "%.1f s".format(it / 1000.0)) }
                    result.timings.connectMs?.let { TimingChip("Connect", "%.1f s".format(it / 1000.0)) }
                    TimingChip("Total", "%.1f s".format(result.timings.totalMs / 1000.0))
                }
            }

            // What happened, step by step: progress is always in words, even without motion. Once
            // the move has ended, the steps fold away under the result.
            if (result != null && t.steps.isNotEmpty()) {
                TextButton(onClick = { showSteps = !showSteps }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showSteps) "Hide the steps" else "Show the steps")
                }
            }
            if (t != null && t.steps.isNotEmpty() && (result == null || showSteps)) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        t.steps.forEachIndexed { index, step ->
                            val last = index == t.steps.lastIndex
                            TimelineRow(
                                text = Texts.step(step),
                                state = when {
                                    step.kind == StepKind.FAILED -> StepState.FAILED
                                    step.kind in WARNING_STEPS -> StepState.WARNING
                                    last && result == null -> StepState.ACTIVE
                                    else -> StepState.DONE
                                },
                                showConnector = !last,
                            )
                        }
                    }
                }
            }

            when {
                result == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton(HandoffTexts.CANCEL, vm::cancel)
                    Text(
                        HandoffTexts.CAN_LEAVE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                result.isGood() || result == HandoffResult.InProgress -> PrimaryButton("Done", onDone)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fix = HandoffTexts.fix(result) ?: HandoffTexts.Fix.TRY_AGAIN
                    PrimaryButton(
                        HandoffTexts.label(fix),
                        icon = if (fix == HandoffTexts.Fix.MOVE_HERE) MoveIcon else null,
                        onClick = {
                            when (fix) {
                                HandoffTexts.Fix.TRY_AGAIN, HandoffTexts.Fix.MOVE_HERE -> vm.retry()
                                HandoffTexts.Fix.LINK_AGAIN -> onPeers()
                                HandoffTexts.Fix.ADD_HERE -> onAdd()
                                HandoffTexts.Fix.TURN_ON_BLUETOOTH -> SystemScreens.turnOnBluetooth(context)
                                HandoffTexts.Fix.ALLOW -> SystemScreens.appSettings(context)
                                HandoffTexts.Fix.OPEN_BLUETOOTH_SETTINGS -> SystemScreens.bluetoothSettings(context)
                            }
                        },
                    )
                    TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Help and diagnostics") }
                }
            }
        }
    }
}

private fun HandoffResult.isGood() = this is HandoffResult.Success || this == HandoffResult.AlreadyConnected

private val WARNING_STEPS = setOf(
    StepKind.PEER_UNREACHABLE, StepKind.RELEASE_TIMEOUT, StepKind.RELEASE_REFUSED, StepKind.PEER_BUSY, StepKind.RETRYING,
    StepKind.CANCELLED,
)

private enum class StepState { DONE, ACTIVE, WARNING, FAILED }

@Composable
private fun TimelineRow(text: String, state: StepState, showConnector: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val (icon, tint) = when (state) {
                StepState.DONE -> Icons.Filled.CheckCircle to Brand.Success
                StepState.ACTIVE -> Icons.Filled.RadioButtonChecked to MaterialTheme.colorScheme.primary
                StepState.WARNING -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
                StepState.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            if (showConnector) Box(Modifier.width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 1.dp, bottom = 12.dp))
    }
}

@Composable
private fun TimingChip(label: String, value: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
