package dev.handoff.core.mesh

import dev.handoff.core.mesh.protocol.Ack
import dev.handoff.core.mesh.protocol.DeviceReport
import dev.handoff.core.mesh.protocol.ErrorCode
import dev.handoff.core.mesh.protocol.ErrorReply
import dev.handoff.core.mesh.protocol.Hello
import dev.handoff.core.mesh.protocol.MalformedMessageException
import dev.handoff.core.mesh.protocol.OwnershipChanged
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.protocol.ProtocolCodec
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.ReleaseResult
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.mesh.protocol.StatusRequest
import dev.handoff.core.mesh.protocol.StatusResponse
import dev.handoff.core.model.AudioDeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {
    private val all: List<PeerMessage> = listOf(
        Hello("c1", 1, "a", displayName = "Phone", appVersion = "0.1.0", capabilities = listOf("release-v1")),
        StatusRequest("c2", 2, "a"),
        StatusResponse(
            "c3", 3, "b", inReplyTo = "c2", displayName = "Tab", bluetoothEnabled = true,
            devices = listOf(DeviceReport("L", "Buds", AudioDeviceKind.HEADPHONES, "fp", true, 4, false)),
        ),
        ReleaseAudioDevice("c4", 4, "a", logicalDeviceId = "L", requestingPeerId = "a"),
        ReleaseResult("c5", 5, "b", inReplyTo = "c4", logicalDeviceId = "L", status = ReleaseStatus.RELEASED, releaseDurationMs = 120),
        OwnershipChanged("c6", 6, "a", logicalDeviceId = "L", senderConnected = true, ownerPeerId = "a", generation = 7),
        Ping("c7", 7, "a"),
        Pong("c8", 8, "b", inReplyTo = "c7"),
        Ack("c9", 9, "b", inReplyTo = "c6"),
        ErrorReply("c10", 10, "b", inReplyTo = "c4", code = ErrorCode.STALE, message = "old"),
    )

    @Test
    fun `every message type round-trips`() {
        all.forEach { message -> assertEquals(message, ProtocolCodec.decode(ProtocolCodec.encode(message))) }
    }

    @Test
    fun `type discriminator uses protocol names`() {
        val names = all.map { ProtocolCodec.encodeToString(it).substringAfter("\"type\":\"").substringBefore('"') }
        assertEquals(
            listOf(
                "HELLO", "STATUS_REQUEST", "STATUS_RESPONSE", "RELEASE_AUDIO_DEVICE", "RELEASE_RESULT",
                "OWNERSHIP_CHANGED", "PING", "PONG", "ACK", "ERROR",
            ),
            names,
        )
    }

    @Test
    fun `release command carries version, id and timestamp`() {
        val json = ProtocolCodec.encodeToString(all[3])
        listOf("\"protocolVersion\":1", "\"commandId\":\"c4\"", "\"logicalDeviceId\":\"L\"", "\"requestingPeerId\":\"a\"", "\"timestamp\":4")
            .forEach { assertTrue("$it in $json", json.contains(it)) }
    }

    @Test
    fun `documented release example decodes`() {
        val example = """
            {"protocolVersion":1,"commandId":"uuid","type":"RELEASE_AUDIO_DEVICE",
             "logicalDeviceId":"uuid-l","requestingPeerId":"uuid-p","timestamp":0,"senderPeerId":"uuid-p"}
        """.trimIndent()
        val decoded = ProtocolCodec.decode(example) as ReleaseAudioDevice
        assertEquals("uuid-l", decoded.logicalDeviceId)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val decoded = ProtocolCodec.decode("""{"type":"PING","commandId":"x","timestamp":1,"senderPeerId":"a","future":42}""")
        assertEquals(Ping("x", 1, "a"), decoded)
    }

    @Test(expected = MalformedMessageException::class)
    fun `unknown type is rejected`() {
        ProtocolCodec.decode("""{"type":"DISCONNECT_HEADPHONES","commandId":"x","timestamp":1,"senderPeerId":"a"}""")
    }

    @Test(expected = MalformedMessageException::class)
    fun `garbage is rejected`() {
        ProtocolCodec.decode("not json")
    }

    @Test(expected = MalformedMessageException::class)
    fun `missing required fields are rejected`() {
        ProtocolCodec.decode("""{"type":"RELEASE_AUDIO_DEVICE","commandId":"x","timestamp":1,"senderPeerId":"a"}""")
    }
}
