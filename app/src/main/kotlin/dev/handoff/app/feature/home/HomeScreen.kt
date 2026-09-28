package dev.handoff.app.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.handoff.app.identity.KeystoreIdentityProvider
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.app.ui.BrandHeader
import dev.handoff.app.ui.HeroIcon
import dev.handoff.app.ui.IconBadge
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionHeader
import dev.handoff.app.ui.StatusPill
import dev.handoff.app.ui.Texts
import dev.handoff.app.ui.Tone
import dev.handoff.app.ui.kindIcon
import dev.handoff.app.ui.platformIcon
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.ownership.Ownership
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class HomeViewModel(
    overview: OverviewRepository,
    private val actions: HandoffActions,
    private val runtime: HandoffRuntime,
    identity: KeystoreIdentityProvider,
) : ViewModel() {
    val devices: StateFlow<List<DeviceOverview>> = overview.devices
    val peers: StateFlow<List<PeerOverview>> = overview.peers
    val selfName: StateFlow<String> = identity.displayName

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
    onMapDevice: () -> Unit,
    onPeers: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    vm: HomeViewModel = koinViewModel(),
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val selfName by vm.selfName.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader(title = "Handoff", subtitle = selfName) {
                IconButton(onClick = onDiagnostics) { Icon(Icons.Outlined.BugReport, contentDescription = "Diagnostics", tint = Color.White) }
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
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        SectionHeader("Your audio") {
                            TextButton(onClick = onMapDevice) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add headset")
                            }
                        }
                    }
                    if (devices.isEmpty()) {
                        item {
                            EmptyCard(
                                icon = Icons.Filled.Headphones,
                                title = "No headset yet",
                                body = "Choose headphones that are already paired with this device in Android Bluetooth settings.",
                                button = "Add headset",
                                onClick = onMapDevice,
                            )
                        }
                    }
                    items(devices, key = { it.device.logicalId.value }) { overview ->
                        HeadsetCard(
                            overview = overview,
                            onMove = {
                                vm.moveHere(overview.device.logicalId)
                                onMoveStarted(overview.device.logicalId.value)
                            },
                            onOpen = { onOpenDevice(overview.device.logicalId.value) },
                        )
                    }

                    item {
                        SectionHeader("Your devices") {
                            TextButton(onClick = onPeers) {
                                Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (peers.isEmpty()) "Link a device" else "Manage")
                            }
                        }
                    }
                    item {
                        SectionCard {
                            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
                            DeviceRow(platformIcon(if (tablet) Platforms.ANDROID_TABLET else Platforms.ANDROID_PHONE), selfName, "This device", Tone.ACTIVE)
                            peers.forEach { p ->
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                DeviceRow(
                                    platformIcon(p.platform),
                                    p.peer.displayName,
                                    if (p.online) "Online" else "Offline",
                                    if (p.online) Tone.POSITIVE else Tone.NEUTRAL,
                                    onClick = onPeers,
                                )
                            }
                            if (peers.isEmpty()) {
                                Text(
                                    "Link your other phone, tablet or Windows PC so they can hand the headset to each other.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadsetCard(overview: DeviceOverview, onMove: () -> Unit, onOpen: () -> Unit) {
    SectionCard(Modifier.animateContentSize().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(kindIcon(overview.device.deviceType), size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        overview.device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val (label, tone) = Texts.status(overview)
                    StatusPill(label, tone)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val transfer = overview.transfer
            when {
                overview.transferRunning && transfer != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                    Text(
                        transfer.steps.lastOrNull()?.let(Texts::step) ?: "Starting…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                overview.device.localDeviceId == null ->
                    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Set up on this device") }
                overview.connectedHere -> Unit
                else -> Button(onClick = onMove, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (overview.ownership is Ownership.Multipoint) "Move media here" else "Move here", style = MaterialTheme.typography.titleSmall)
                }
            }
            val failed = transfer?.result as? HandoffResult.Failed
            AnimatedVisibility(visible = !overview.transferRunning && failed != null) {
                failed?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(Texts.result(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, status: String, tone: Tone, onClick: (() -> Unit)? = null) {
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
        StatusPill(status, tone)
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, body: String, button: String, onClick: () -> Unit) {
    SectionCard {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeroIcon(icon, size = 64.dp)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}
