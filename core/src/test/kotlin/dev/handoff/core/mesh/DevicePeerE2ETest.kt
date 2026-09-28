package dev.handoff.core.mesh

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.handoff.core.diagnostics.InMemoryEventLog
import dev.handoff.core.mesh.pairing.PairingClient
import dev.handoff.core.mesh.pairing.PairingOutcome
import dev.handoff.core.mesh.protocol.ErrorReply
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.StatusRequest
import dev.handoff.core.mesh.security.PairingInvitation
import dev.handoff.core.mesh.security.SoftwareIdentityProvider
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.LanPeerTransport
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.InMemoryTrustedPeerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Manual end-to-end check against a real Handoff install (device or emulator). Skipped unless
 * `-Dhandoff.e2e.qr=<screenshot.png>` is given. The JVM acts as a second Handoff peer:
 *
 * 1. Show "Add device" on the device and screenshot it.
 * 2. `adb forward tcp:47474 tcp:47474`
 * 3. `./gradlew :core:test --tests '*DevicePeerE2ETest*' -Dhandoff.e2e.qr=shot.png -Dhandoff.e2e.out=out.txt`
 * 4. Approve the link on the device when its code matches the one written to the out file.
 */
class DevicePeerE2ETest {
    @Test
    fun `pair with a real device and exchange authenticated commands`() = runBlocking {
        val qr = System.getProperty("handoff.e2e.qr")
        assumeTrue("set -Dhandoff.e2e.qr to run", !qr.isNullOrBlank())
        val out = File(System.getProperty("handoff.e2e.out") ?: "handoff-e2e.txt")
        fun log(line: String) = out.appendText(line + "\n").also { println(line) }
        out.writeText("")

        val image = ImageIO.read(File(qr))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text
        val invitation = PairingInvitation.parse(text) ?: error("QR is not a Handoff invitation")
        log("invitation peer=${invitation.peerId.take(8)} hosts=${invitation.hosts} port=${invitation.port}")

        val identity = SoftwareIdentityProvider(PeerId.random(), "JVM test peer")
        val trust = InMemoryTrustedPeerRepository()
        val directory = PeerDirectory()
        val events = InMemoryEventLog()
        val host = System.getProperty("handoff.e2e.host") ?: "127.0.0.1"
        val outcome = PairingClient(identity, trust, directory, events)
            .pair(invitation.withHosts(listOf(host))) { code -> log("CODE $code") }
        log("pairing outcome: $outcome")
        check(outcome is PairingOutcome.Paired)
        val device = PeerId(invitation.peerId)
        val transport = LanPeerTransport(identity, trust, directory, events)
        fun now() = System.currentTimeMillis()
        val me = identity.identity().peerId.value

        log("PING -> ${transport.request(device, Ping(PeerMessage.newCommandId(), now(), me), 5_000)}")
        log("STATUS -> ${transport.request(device, StatusRequest(PeerMessage.newCommandId(), now(), me), 5_000)}")
        log(
            "RELEASE(unknown) -> " +
                transport.request(device, ReleaseAudioDevice(PeerMessage.newCommandId(), now(), me, "no-such-headset", me), 10_000),
        )
        val stale = transport.request(device, Ping(PeerMessage.newCommandId(), now() - 3_600_000, me), 5_000)
        log("STALE PING -> $stale")
        val spoof = transport.request(device, Ping(PeerMessage.newCommandId(), now(), "someone-else"), 5_000)
        log("SPOOFED SENDER -> $spoof")

        // An unlinked peer that knows the address and the device key must be refused.
        val strangerTrust = InMemoryTrustedPeerRepository(listOf(trust.find(device)!!))
        val strangerDir = PeerDirectory().apply { onDiscovered(device, host, invitation.port) }
        val stranger = SoftwareIdentityProvider(PeerId.random(), "stranger")
        val refused = LanPeerTransport(stranger, strangerTrust, strangerDir, events)
            .request(device, Ping(PeerMessage.newCommandId(), now(), stranger.identity().peerId.value), 5_000)
        log("UNLINKED PEER -> $refused")
        check(refused is CommandResult.Rejected)
        check((stale as? CommandResult.Rejected)?.reason?.startsWith("STALE") == true)
        check(spoof is CommandResult.Rejected || (spoof as? CommandResult.Reply)?.message is ErrorReply)
        log("E2E OK")
    }
}
