package dev.handoff.app.feature.peers

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.handoff.app.ui.Brand
import dev.handoff.app.ui.HeroIcon
import dev.handoff.app.ui.IconBadge
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionHeader
import dev.handoff.app.ui.StatusPill
import dev.handoff.app.ui.Tone
import dev.handoff.app.ui.platformIcon
import android.annotation.SuppressLint
import androidx.activity.compose.LocalActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.view.WindowManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.handoff.app.mesh.NetworkAddresses
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.core.overview.OverviewRepository
import dev.handoff.core.overview.PeerOverview
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.ui.Hint
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.pairing.PairingClient
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.pairing.PairingOutcome
import dev.handoff.core.mesh.security.PairingInvitation
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.mesh.transport.PeerServer
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.OwnershipRepository
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

// ---- Peer list -----------------------------------------------------------------------------

class PeersViewModel(
    overview: OverviewRepository,
    private val trust: TrustedPeerRepository,
    private val ownership: OwnershipRepository,
    private val directory: PeerDirectory,
    private val events: EventLog,
) : ViewModel() {
    val peers: StateFlow<List<PeerOverview>> = overview.peers

    fun remove(peer: PeerId) = viewModelScope.launch {
        trust.remove(peer)
        ownership.forgetPeer(peer)
        directory.forget(peer)
        events.record(EventType.PEER_REMOVED, peerId = peer)
    }
}

@Composable
fun PeersScreen(onBack: () -> Unit, onAdd: () -> Unit, onScan: () -> Unit, vm: PeersViewModel = koinViewModel()) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<PeerOverview?>(null) }

    HandoffScaffold(title = "My devices", onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                SectionCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HeroIcon(Icons.Outlined.QrCode2, size = 48.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                "Link another phone, tablet or Windows PC running Handoff. Show the code on one device and scan it on the other.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan a Handoff code")
                        }
                        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Show my code instead") }
                    }
                }
            }
            item { SectionHeader("Linked") }
            if (peers.isEmpty()) item { Hint("No linked devices yet. Links work on any Wi-Fi both devices share; you only link once.") }
            if (peers.isNotEmpty()) {
                item {
                    SectionCard {
                        peers.forEachIndexed { index, p ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(platformIcon(p.platform), container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(p.peer.displayName, style = MaterialTheme.typography.bodyLarge)
                                    StatusPill(if (p.online) "Online" else "Offline", if (p.online) Tone.POSITIVE else Tone.NEUTRAL)
                                }
                                IconButton(onClick = { removing = p }) { Icon(Icons.Outlined.LinkOff, contentDescription = "Unlink") }
                            }
                        }
                    }
                }
            }
            item {
                Hint(
                    "Linked devices can ask this device to release a headset. Unlinking stops that immediately. " +
                        "Unlink on both devices to remove the link completely.",
                )
            }
        }
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unlink ${p.peer.displayName}?") },
            text = { Text("It will no longer be able to move headsets to or from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(p.peer.peerId)
                    removing = null
                }) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

// ---- Show QR (inviting side) ---------------------------------------------------------------

data class AddPeerState(
    val invitation: PairingInvitation? = null,
    val expiresAtMs: Long = 0,
    val error: String? = null,
    val linkedName: String? = null,
)

// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class AddPeerViewModel(
    private val context: Context,
    private val pairing: PairingManager,
    private val server: PeerServer,
    private val runtime: HandoffRuntime,
    private val trust: TrustedPeerRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(AddPeerState())
    val state: StateFlow<AddPeerState> = _state.asStateFlow()

    init {
        val before = trust.peers.value.map { it.peerId }.toSet()
        viewModelScope.launch {
            runtime.acquire(HOLDER)
            createInvitation()
            trust.peers.collect { peers ->
                peers.firstOrNull { it.peerId !in before }?.let { _state.value = _state.value.copy(linkedName = it.displayName) }
            }
        }
        viewModelScope.launch {
            // The manager clears the invitation once it is used (approved or declined).
            pairing.invitation.collect { current ->
                val shown = _state.value.invitation
                if (current == null && shown != null && _state.value.linkedName == null) {
                    _state.value = _state.value.copy(invitation = null, error = "This code is no longer valid (declined or already used).")
                }
            }
        }
    }

    fun createInvitation() = viewModelScope.launch {
        val port = server.port.filterNotNull().first()
        val hosts = NetworkAddresses.lanIpv4(context)
        if (hosts.isEmpty()) {
            _state.value = AddPeerState(error = "Connect to Wi-Fi first. Both devices must be on the same network.")
            return@launch
        }
        val active = pairing.createInvitation(hosts, port)
        val invitation = active.invitation
        _state.value = AddPeerState(invitation = invitation, expiresAtMs = active.expiresAtMs)
        delay(active.expiresAtMs - System.currentTimeMillis())
        if (_state.value.invitation == invitation && _state.value.linkedName == null) {
            _state.value = _state.value.copy(invitation = null, error = "Code expired.")
        }
    }

    fun copyLink() {
        val uri = _state.value.invitation?.encode() ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Handoff link", uri))
    }

    override fun onCleared() {
        pairing.cancelInvitation()
        appScope.launch { withContext(NonCancellable) { runtime.release(HOLDER) } }
    }

    private companion object {
        const val HOLDER = "pairing"
    }
}

@Composable
fun AddPeerScreen(onBack: () -> Unit, vm: AddPeerViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    HandoffScaffold(title = "Show my code", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val invitation = state.invitation
            when {
                state.linkedName != null -> {
                    HeroIcon(Icons.Filled.Check, size = 96.dp, brush = SolidColor(Brand.Success))
                    Text("Linked with ${state.linkedName}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text("It can now move headsets to and from this device, on any Wi-Fi you both use.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                invitation != null -> {
                    Text(
                        "On the other device open Handoff → My devices → Scan a Handoff code.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    val bitmap = remember(invitation) { qrBitmap(invitation.encode()) }
                    FullBrightnessWhileShown()
                    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 2.dp) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier = Modifier.size(260.dp).padding(14.dp),
                        )
                    }
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(invitation) {
                        while (true) {
                            now = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }
                    val left = ((state.expiresAtMs - now) / 1000).coerceAtLeast(0)
                    StatusPill("Works once · expires in %d:%02d".format(left / 60, left % 60), Tone.ACTIVE)
                    Text(
                        "You'll confirm a 6-digit code on this device before anything is linked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = vm::copyLink) { Text("Copy code as text") }
                }
                state.error != null -> {
                    HeroIcon(Icons.Filled.PriorityHigh, size = 80.dp, brush = SolidColor(MaterialTheme.colorScheme.error))
                    Text(state.error!!, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = { vm.createInvitation() }) { Text("Show a new code") }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}

/** A bright, awake screen makes the camera on the other device lock on much faster. */
@Composable
private fun FullBrightnessWhileShown() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = previous }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// ---- Scan QR (joining side) ----------------------------------------------------------------

sealed interface ScanState {
    data object Idle : ScanState
    data object Connecting : ScanState
    data class WaitingForApproval(val code: String, val peerName: String) : ScanState
    data class Linked(val peerName: String) : ScanState
    data class Failed(val reason: String) : ScanState
}

class ScanPeerViewModel(private val client: PairingClient) : ViewModel() {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun pair(text: String) {
        val invitation = PairingInvitation.parse(text)
        if (invitation == null) {
            _state.value = ScanState.Failed("That isn't a Handoff code.")
            return
        }
        _state.value = ScanState.Connecting
        viewModelScope.launch {
            val outcome = client.pair(invitation) { code -> _state.value = ScanState.WaitingForApproval(code, "the other device") }
            _state.value = when (outcome) {
                is PairingOutcome.Paired -> ScanState.Linked(outcome.peer.displayName)
                is PairingOutcome.Declined -> ScanState.Failed("The other device declined the link.")
                PairingOutcome.Expired -> ScanState.Failed("That code has expired or was already used. Show a new one on the other device.")
                PairingOutcome.SelfInvitation -> ScanState.Failed("That's this device's own code.")
                is PairingOutcome.Failed -> ScanState.Failed(
                    "Couldn't link: ${outcome.reason}. Make sure both devices are on the same Wi-Fi and the code is still shown.",
                )
            }
        }
    }

    fun reset() {
        _state.value = ScanState.Idle
    }
}

@Composable
fun ScanPeerScreen(onBack: () -> Unit, vm: ScanPeerViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pasted by remember { mutableStateOf("") }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::pair) }

    HandoffScaffold(title = "Link a device", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (val s = state) {
                ScanState.Idle -> {
                    HeroIcon(Icons.Outlined.QrCodeScanner, size = 96.dp)
                    Text("Scan the code on your other device", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        "On the other phone or tablet: Handoff → My devices → Show my code.\nOn a Windows PC: Handoff → Link a phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = {
                            scanner.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false)
                                    .setPrompt("Scan the Handoff code"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan with camera")
                    }
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("…or paste a HANDOFF: code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { vm.pair(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Link")
                    }
                }
                ScanState.Connecting -> {
                    CircularProgressIndicator(Modifier.size(64.dp))
                    Text("Connecting…", style = MaterialTheme.typography.titleMedium)
                }
                is ScanState.WaitingForApproval -> {
                    Text("Confirm on ${s.peerName}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text("Check that ${s.peerName} shows this code, then tap Link there:", textAlign = TextAlign.Center)
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            s.code.chunked(3).joinToString("  "),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        )
                    }
                    CircularProgressIndicator()
                }
                is ScanState.Linked -> {
                    HeroIcon(Icons.Filled.Check, size = 96.dp, brush = SolidColor(Brand.Success))
                    Text("Linked with ${s.peerName}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text("You won't need to link again, even on another Wi-Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is ScanState.Failed -> {
                    HeroIcon(Icons.Filled.PriorityHigh, size = 80.dp, brush = SolidColor(MaterialTheme.colorScheme.error))
                    Text(s.reason, textAlign = TextAlign.Center)
                    Button(onClick = vm::reset) { Text("Try again") }
                }
            }
        }
    }
}
