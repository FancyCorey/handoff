package dev.handoff.app.feature.transfer

import android.net.Uri
import dev.handoff.core.handoff.FailureReason
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
    coordinator: HandoffCoordinator,
    private val actions: HandoffActions,
    overview: OverviewRepository,
) : ViewModel() {
    private val id = LogicalDeviceId(logicalId)

    /** Ignore a finished transfer from before this screen opened. */
    private val since = MutableStateFlow(System.currentTimeMillis() - STALE_SLACK_MS)

    val transfer: StateFlow<HandoffState?> = combine(coordinator.transfers, since) { all, openedAt ->
        all[id]?.takeIf { it.startedAtMs >= openedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val deviceName: StateFlow<String> = overview.devices
        .map { list -> list.firstOrNull { it.device.logicalId == id }?.device?.displayName ?: "headset" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "headset")

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
    vm: TransferViewModel = koinViewModel(key = "transfer-$logicalId") { parametersOf(logicalId) },
) {
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val t = transfer
    val result = t?.result

    HandoffScaffold(title = "Moving $name", onBack = onDone) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Hero: spinner while running, then a success or failure mark.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                when {
                    result == null -> Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 5.dp)
                        Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    result.isGood() -> HeroIcon(Icons.Filled.Check, size = 96.dp, brush = SolidColor(Brand.Success))
                    else -> HeroIcon(Icons.Filled.PriorityHigh, size = 96.dp, brush = SolidColor(MaterialTheme.colorScheme.error))
                }
            }
            Text(
                when {
                    result != null -> Texts.result(result)
                    t == null -> "Starting…"
                    else -> t.steps.lastOrNull()?.let(Texts::step) ?: "Starting…"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            result?.let(Texts::help)?.let { help ->
                Text(
                    help,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (result is HandoffResult.Success) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    result.timings.releaseMs?.let { TimingChip("Release", "%.1f s".format(it / 1000.0)) }
                    result.timings.connectMs?.let { TimingChip("Connect", "%.1f s".format(it / 1000.0)) }
                    TimingChip("Total", "%.1f s".format(result.timings.totalMs / 1000.0))
                }
            }

            // Timeline of what happened.
            if (t != null && t.steps.isNotEmpty()) {
                SectionCard(Modifier.padding(horizontal = 0.dp)) {
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

            when (result) {
                null -> Unit
                is HandoffResult.Success, HandoffResult.AlreadyConnected, HandoffResult.InProgress ->
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Done") }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                    val needsPermission = (result as? HandoffResult.Failed)?.reason == FailureReason.PERMISSION_DENIED
                    OutlinedButton(
                        onClick = {
                            val intent = if (needsPermission) {
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            } else {
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            }
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (needsPermission) "Open app settings" else "Open Bluetooth settings") }
                    TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Diagnostics") }
                }
            }
        }
    }
}

private fun HandoffResult.isGood() = this is HandoffResult.Success || this == HandoffResult.AlreadyConnected

private val WARNING_STEPS = setOf(
    StepKind.PEER_UNREACHABLE, StepKind.RELEASE_TIMEOUT, StepKind.RELEASE_REFUSED, StepKind.PEER_BUSY, StepKind.RETRYING,
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
