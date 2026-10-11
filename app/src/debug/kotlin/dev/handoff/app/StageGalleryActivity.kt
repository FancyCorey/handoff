package dev.handoff.app

import dev.handoff.core.text.HandoffTexts
import dev.handoff.core.help.HelpInput
import dev.handoff.core.help.HelpBuilder
import dev.handoff.core.help.Help
import dev.handoff.core.handoff.TransferTimings
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.app.ui.HandoffScaffold
import dev.handoff.app.feature.diagnostics.helpItems
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.handoff.app.ui.HandoffTheme
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.StageButton
import dev.handoff.app.ui.StageView
import dev.handoff.app.ui.toneColor
import dev.handoff.core.handoff.HandoffState
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferPhase
import dev.handoff.core.handoff.TransferStep
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.stage.StageBuilder

/**
 * Debug builds only: every stage state from docs/DESIGN.md, drawn from made-up devices, to check
 * the stage against the design. Open with
 * `adb shell am start -n dev.handoff.app.debug/dev.handoff.app.StageGalleryActivity`.
 */
class StageGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getIntExtra("from", 0)
        val helpCase = intent.getIntExtra("help", -1)
        if (helpCase >= 0) {
            HandoffTexts.me = "This tablet"
            setContent {
                HandoffTheme {
                    var showFine by remember { mutableStateOf(false) }
                    HandoffScaffold(title = "Help and diagnostics", onBack = {}) { padding ->
                        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                            helpItems(helpExamples()[helpCase], onAction = {}, onTryAgain = {}, onShare = {}, showFine = showFine, onShowFine = { showFine = true })
                        }
                    }
                }
            }
            return
        }
        setContent {
            HandoffTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LazyColumn(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(examples().drop(start)) { (title, o, peers) ->
                            val stage = StageBuilder.build(o, peers, SELF, Platforms.ANDROID_PHONE, 0)
                            SectionCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(title.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column {
                                        Text(o.device.displayName, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Text(stage.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneColor(stage.statusTone))
                                    }
                                    StageView(stage, Modifier.fillMaxWidth(), steps = o.transfer?.steps.orEmpty())
                                    stage.line?.let { Text(it, fontSize = 12.sp, color = toneColor(stage.lineTone, quiet = true)) }
                                    StageButton(stage.action, {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val SELF = PeerId("self")
        val PC = PeerId("pc")
        val TAB = PeerId("tab")

        fun peer(id: PeerId, name: String, platform: String, online: Boolean = true) =
            PeerOverview(TrustedPeer(id, name, ByteArray(0), 0), online, null, platform)

        val studio = peer(PC, "Living Room Galaxy S22 Ultra", Platforms.ANDROID_PHONE)
        val tab = peer(TAB, "Tab", Platforms.ANDROID_TABLET)

        fun o(
            name: String,
            ownership: Ownership,
            state: AudioConnectionState = AudioConnectionState.DISCONNECTED,
            multipoint: Boolean = false,
            transfer: HandoffState? = null,
        ) = DeviceOverview(
            LogicalAudioDevice(LogicalDeviceId(name), name, AudioDeviceKind.HEADPHONES, null, BluetoothDeviceId("0A:DE:40:00:00:01"), multipoint = multipoint),
            state, ownership, emptyList(), transfer, 80,
        )

        fun moving() = HandoffState(
            LogicalDeviceId("Aurora Buds"), "Aurora Buds", TransferTrigger.MANUAL, TransferPhase.CONNECTING, emptyList(),
            listOf(TransferStep(StepKind.REQUESTING_RELEASE, "Studio PC"), TransferStep(StepKind.RELEASED, "Studio PC"), TransferStep(StepKind.CONNECTING)),
            1, false, PC, null, 0,
        )

        /** The help screen's cases from docs/DESIGN.md, built by the real [HelpBuilder]. */
        fun helpExamples(): List<Help> {
            val now = System.currentTimeMillis()
            val s22 = PeerId("s22")
            fun p(id: PeerId, name: String, platform: String, online: Boolean, ago: Long, otherWifi: Boolean = false) =
                PeerOverview(TrustedPeer(id, name, ByteArray(0), 0), online, now - ago, platform, otherWifi)
            fun move(failure: FailureReason?, owner: PeerId?, detail: String?) = TransferRecord(
                LogicalDeviceId("buds"), "Aurora Buds", TransferTrigger.MANUAL, now - 90_000,
                if (failure == null) "SUCCESS" else "FAILED", null, failure, detail, owner, null, 2, TransferTimings(null, null, 9_000),
            )
            fun input(last: TransferRecord?, peers: List<PeerOverview>, adapter: AdapterState = AdapterState.ON, reachable: Boolean = true) =
                HelpInput(last, adapter, CompatibilityLevel.SUPPORTED, listOf("Aurora Buds" to true), peers, true, reachable, now)
            return listOf(
                HelpBuilder.build(input(move(FailureReason.OWNER_UNREACHABLE, s22, "Galaxy S22"), listOf(p(s22, "Galaxy S22", Platforms.ANDROID_PHONE, false, 2 * 3_600_000, otherWifi = true)))),
                HelpBuilder.build(input(move(FailureReason.BLUETOOTH_OFF, null, null), listOf(p(s22, "Galaxy S22", Platforms.ANDROID_PHONE, true, 0)), adapter = AdapterState.OFF)),
                HelpBuilder.build(input(move(null, null, null), listOf(p(s22, "Galaxy S22", Platforms.ANDROID_PHONE, true, 0)), reachable = false)),
                HelpBuilder.build(input(move(FailureReason.OWNER_UNREACHABLE, PC, "Studio PC"), listOf(p(PC, "Studio PC", Platforms.WINDOWS, false, 10 * 60_000)))),
            )
        }

        fun examples(): List<Triple<String, DeviceOverview, List<PeerOverview>>> = listOf(
            Triple("On another device", o("Aurora Buds", Ownership.Peer(PC)), listOf(studio)),
            Triple("Here only", o("Aurora Buds", Ownership.Local, AudioConnectionState.CONNECTED), listOf(studio)),
            Triple("Here and on another device", o("ATH-M20xBT", Ownership.Conflict(setOf(SELF, TAB)), AudioConnectionState.CONNECTED), listOf(tab)),
            Triple("Two others, stay connected off", o("ATH-M20xBT", Ownership.Conflict(setOf(PC, TAB))), listOf(studio, tab)),
            Triple("Two others, stay connected on", o("ATH-M20xBT", Ownership.Multipoint(setOf(PC, TAB)), multipoint = true), listOf(studio, tab)),
            Triple("Not sure where", o("Aurora Buds", Ownership.Unknown(PC)), listOf(studio)),
            Triple("Holder offline", o("Aurora Buds", Ownership.Unknown(PC)), listOf(peer(PC, "Studio PC", Platforms.WINDOWS, online = false))),
            Triple("Bluetooth off", o("Aurora Buds", Ownership.Unknown(null), AudioConnectionState.UNAVAILABLE), listOf(studio)),
            Triple("Moving", o("Aurora Buds", Ownership.Peer(PC), transfer = moving()), listOf(studio)),
            Triple(
                "Four or more devices",
                o("Aurora Buds", Ownership.Peer(PC)),
                listOf(studio, tab, peer(PeerId("l"), "Laptop", Platforms.WINDOWS), peer(PeerId("p"), "Old phone", Platforms.ANDROID_PHONE)),
            ),
        )
    }
}
