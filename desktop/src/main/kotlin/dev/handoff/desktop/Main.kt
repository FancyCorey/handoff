package dev.handoff.desktop

import dev.handoff.desktop.store.defaultDataDir
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.Redaction
import dev.handoff.core.overview.AnnouncedDevice
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.text.HandoffTexts
import dev.handoff.desktop.mesh.JmdnsDiscovery
import dev.handoff.desktop.ui.AddHeadsetDialog
import dev.handoff.desktop.ui.ApprovalDialog
import dev.handoff.desktop.ui.DiagnosticsDialog
import dev.handoff.desktop.ui.HandoffTheme
import dev.handoff.desktop.ui.HeadsetDialog
import dev.handoff.desktop.ui.HomeActions
import dev.handoff.desktop.ui.HomeScreen
import dev.handoff.desktop.ui.LinkPhoneDialog
import dev.handoff.desktop.ui.Resources
import dev.handoff.desktop.ui.SettingsDialog
import dev.handoff.desktop.ui.status
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val startMinimized = "--minimized" in args
    val instance = SingleInstance(defaultDataDir())
    if (!instance.acquire()) {
        // Already running (often hidden in the tray): show that window instead of a second copy.
        if (!startMinimized) instance.requestShow()
        exitProcess(0)
    }
    val showRequests = MutableStateFlow(0)
    instance.watch { showRequests.update { it + 1 } }
    val app = DesktopApp()
    app.start()
    application {
        var windowVisible by remember { mutableStateOf(!startMinimized) }
        val windowState = rememberWindowState(size = DpSize(460.dp, 780.dp), position = WindowPosition(Alignment.Center))
        val shows by showRequests.collectAsState()
        LaunchedEffect(shows) {
            if (shows > 0) {
                windowVisible = true
                windowState.isMinimized = false
            }
        }
        val tray = rememberTrayState()
        val settings by app.settings.settings.collectAsState()
        val devices by app.overview.devices.collectAsState()
        val preferred = devices.firstOrNull { it.device.logicalId.value == settings.preferredDevice } ?: devices.singleOrNull()

        fun quit() {
            app.shutdown()
            exitApplication()
            exitProcess(0)
        }

        Tray(
            icon = Resources.trayPainter,
            state = tray,
            tooltip = preferred?.let { "Handoff · ${it.device.displayName}: ${status(it, false).first}" } ?: "Handoff",
            onAction = { windowVisible = true },
            menu = {
                if (preferred != null && preferred.device.localDeviceId != null && !preferred.connectedHere) {
                    Item("Move ${preferred.device.displayName} here", onClick = {
                        app.moveHere(preferred.device.logicalId, TransferTrigger.QUICK_SETTINGS_TILE) { result ->
                            tray.sendNotification(Notification("Handoff", HandoffTexts.result(result)))
                        }
                    })
                    Separator()
                }
                Item("Open Handoff", onClick = { windowVisible = true })
                Item("Quit Handoff", onClick = ::quit)
            },
        )

        Window(
            // Closing hides to the tray: Handoff must keep running so linked devices can reach it.
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = "Handoff",
            icon = Resources.logoPainter,
            state = windowState,
        ) {
            LaunchedEffect(shows) { if (shows > 0) window.toFront() }
            HandoffTheme { App(app) }
        }
    }
}

private sealed interface Sheet {
    data object Link : Sheet
    data object AddHeadset : Sheet
    data object Settings : Sheet
    data object Diagnostics : Sheet
    data class Headset(val id: LogicalDeviceId) : Sheet
}

@Composable
private fun App(app: DesktopApp) {
    val scope = rememberCoroutineScope()
    val devices by app.overview.devices.collectAsState()
    val peers by app.overview.peers.collectAsState()
    val announced by app.overview.announced.collectAsState()
    val adapter by app.bluetooth.adapterState.collectAsState()
    val name by app.identity.displayName.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val releasedByHandoff by app.settings.released.collectAsState()
    val mediaOff by app.bluetooth.mediaOff.collectAsState()
    // Handed away by Handoff, or media turned off in Windows for any other reason: offer a restore.
    val released = releasedByHandoff + mediaOff
    val pending by app.pairing.pendingApproval.collectAsState()
    val update by app.updates.state.collectAsState()
    val bonded by remember { app.bluetooth.bondedAudioDevices() }.collectAsState(emptyList())
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    HomeScreen(
        pcName = name,
        adapter = adapter,
        devices = devices,
        peers = peers,
        released = released,
        update = update,
        actions = HomeActions(
            moveHere = { app.moveHere(it.device.logicalId) },
            cancelMove = { app.cancelMove(it.device.logicalId) },
            openHeadset = { sheet = if (it.device.localDeviceId == null) Sheet.AddHeadset else Sheet.Headset(it.device.logicalId) },
            addHeadset = { sheet = Sheet.AddHeadset },
            linkPhone = { sheet = Sheet.Link },
            settings = { sheet = Sheet.Settings },
            diagnostics = { sheet = Sheet.Diagnostics },
            refresh = { scope.launch { app.refreshNow() } },
            openHotspot = { runCatching { ProcessBuilder("explorer.exe", "ms-settings:network-mobilehotspot").start() } },
        ),
    )

    when (val s = sheet) {
        Sheet.Link -> LinkSheet(app) { sheet = null }
        Sheet.AddHeadset -> AddHeadsetDialog(
            bonded = bonded,
            announced = announced,
            mapped = devices.mapNotNull { it.device.fingerprint }.toSet(),
            onMap = { device, target ->
                scope.launch { mapHeadset(app, device, target) }
                sheet = null
            },
            onClose = { sheet = null },
        )
        Sheet.Settings -> SettingsDialog(
            name = name,
            keyFingerprint = app.identity.keyFingerprint(),
            version = app.version,
            startWithWindows = Autostart.isEnabled(),
            autostartAvailable = Autostart.launcherPath() != null,
            onRename = app.identity::rename,
            onStartWithWindows = { enabled ->
                Autostart.setEnabled(enabled)
                app.settings.update { it.copy(startWithWindows = enabled) }
            },
            update = update,
            autoUpdateCheck = settings.autoUpdateCheck,
            canInstallUpdate = app.updates.canInstallInApp,
            onAutoUpdateCheck = { on ->
                app.settings.update { it.copy(autoUpdateCheck = on) }
                if (on) app.updates.check()
            },
            onCheckUpdate = app.updates::check,
            onInstallUpdate = { manifest ->
                app.updates.downloadAndInstall(manifest) {
                    app.shutdown()
                    exitProcess(0)
                }
            },
            onReleasePage = app.updates::openReleasePage,
            onClose = { sheet = null },
        )
        Sheet.Diagnostics -> DiagnosticsDialog(report = diagnostics(app, devices)) { sheet = null }
        is Sheet.Headset -> {
            val d = devices.firstOrNull { it.device.logicalId == s.id }
            if (d == null) {
                sheet = null
            } else {
                val address = d.device.localDeviceId
                HeadsetDialog(
                    overview = d,
                    preferred = settings.preferredDevice == d.device.logicalId.value,
                    released = address?.address?.uppercase() in released,
                    onPreferred = { on -> app.settings.update { it.copy(preferredDevice = if (on) d.device.logicalId.value else null) } },
                    onMultipoint = { on -> scope.launch { app.devices.upsert(d.device.copy(multipoint = on)) } },
                    onRestore = { address?.let { a -> scope.launch { app.bluetooth.restore(a) } } },
                    onForget = {
                        scope.launch { app.devices.remove(d.device.logicalId) }
                        sheet = null
                    },
                    onClose = { sheet = null },
                )
            }
        }
        null -> Unit
    }

    pending?.let { request -> ApprovalDialog(request) { approved -> app.pairing.respond(approved) } }

    // First launch of the installed app: start with Windows so linked devices can always reach this PC.
    LaunchedEffect(Unit) {
        if (!settings.welcomeSeen) {
            if (Autostart.launcherPath() != null && settings.startWithWindows) Autostart.setEnabled(true)
            app.settings.update { it.copy(welcomeSeen = true) }
        }
    }
}

@Composable
private fun LinkSheet(app: DesktopApp, onClose: () -> Unit) {
    val invitation by app.pairing.invitation.collectAsState()
    val peers by app.trust.peers.collectAsState()
    val before = remember { peers.map { it.peerId }.toSet() }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val linked = peers.firstOrNull { it.peerId !in before }?.displayName

    // Approving on this PC completes the link for both devices: close without another click.
    LaunchedEffect(linked) {
        if (linked != null) {
            delay(1_500)
            onClose()
        }
    }

    LaunchedEffect(attempt) {
        error = null
        val port = app.server.port.filterNotNull().first()
        val hosts = JmdnsDiscovery.lanAddresses().mapNotNull { it.hostAddress }
        if (hosts.isEmpty()) {
            error = "This PC isn't on a network. Connect it to the same Wi-Fi or LAN as your phone."
        } else {
            app.pairing.createInvitation(hosts, port)
            shown = true
        }
    }
    LinkPhoneDialog(
        invitation = invitation,
        linkedName = linked,
        error = error ?: if (shown && invitation == null && linked == null) "That code was used or declined. Show a new one." else null,
        onRetry = {
            shown = false
            attempt++
        },
        onClose = {
            app.pairing.cancelInvitation()
            onClose()
        },
    )
}

/** Same rules as the Android mapping screen: one logical headset per bonded device. */
private suspend fun mapHeadset(app: DesktopApp, device: AudioDevice, target: AnnouncedDevice?) {
    val self = app.identity.identity()
    val id = target?.logicalId ?: LogicalDeviceId.random()
    app.devices.devices.value.filter { it.localDeviceId == device.id && it.logicalId != id }.forEach { app.devices.remove(it.logicalId) }
    val existing = app.devices.find(id)
    val hosts = buildList {
        add(HostMapping(self.peerId, self.displayName))
        target?.announcedByIds?.zip(target.announcedBy)?.forEach { (peer, name) -> add(HostMapping(peer, name)) }
        existing?.hostMappings?.forEach { add(it) }
    }.distinctBy { it.hostId }
    app.devices.upsert(
        LogicalAudioDevice(
            logicalId = id,
            displayName = existing?.displayName ?: target?.displayName ?: device.name,
            deviceType = device.kind,
            fingerprint = device.fingerprint,
            localDeviceId = device.id,
            multipoint = existing?.multipoint ?: false,
            lastKnownOwner = existing?.lastKnownOwner,
            ownershipGeneration = existing?.ownershipGeneration ?: 0,
            hostMappings = hosts,
        ),
    )
    if (app.settings.settings.value.preferredDevice == null) app.settings.update { it.copy(preferredDevice = id.value) }
}

/** Redacted, shareable diagnostics text (no full Bluetooth addresses, keys or tokens). */
private fun diagnostics(app: DesktopApp, devices: List<DeviceOverview>): String {
    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    val text = buildString {
        appendLine("Handoff for Windows ${app.version}")
        appendLine("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Java: ${System.getProperty("java.version")}")
        appendLine("Bluetooth adapter: ${app.bluetooth.adapterState.value}")
        appendLine("Strategy: WindowsServiceToggle (documented Win32 BluetoothSetServiceState)")
        app.bluetooth.lastOperation.value?.let { appendLine("Last Bluetooth operation: ${time.format(Date(it.atMs))} ${it.description}") }
        appendLine("Peer server port: ${app.server.port.value ?: "not listening"}")
        appendLine("LAN addresses: ${JmdnsDiscovery.lanAddresses().mapNotNull { it.hostAddress }.joinToString().ifEmpty { "none" }}")
        appendLine()
        appendLine("== Headsets ==")
        devices.forEach { d ->
            appendLine("${d.device.displayName}: ${status(d, false).first} · local ${d.localState} · mapped here ${d.device.localDeviceId != null}")
        }
        appendLine()
        appendLine("== Linked devices ==")
        app.overview.peers.value.forEach { appendLine("${it.peer.displayName}: ${if (it.online) "online" else "offline"} (${it.platform ?: "platform not reported yet"})") }
        appendLine()
        appendLine("== Recent transfers ==")
        app.history.records.value.take(10).forEach { r ->
            appendLine("${time.format(Date(r.startedAtMs))} ${r.deviceName}: ${r.outcome} ${r.path ?: r.failure ?: ""} ${"%.1f".format(r.timings.totalMs / 1000.0)} s ${r.detail ?: ""}")
        }
        appendLine()
        appendLine("== Recent events ==")
        app.events.events.value.takeLast(120).forEach { appendLine("${time.format(Date(it.atMs))} ${it.format()}") }
    }
    return Redaction.scrub(text)
}
