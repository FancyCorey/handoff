package dev.handoff.core.mesh

import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.security.CommandGuard
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.PairingInvitation
import dev.handoff.core.mesh.security.ProtocolViolation
import dev.handoff.core.mesh.security.SecureChannel
import dev.handoff.core.model.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class SecurityTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = Crypto.hkdf(
            salt = hex("000102030405060708090a0b0c"),
            ikm = ByteArray(22) { 0x0b },
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun `ecdsa signatures verify only with the right key and data`() {
        val a = Crypto.generateEcKeyPair()
        val b = Crypto.generateEcKeyPair()
        val sig = Crypto.signWith(a.private, "hello".toByteArray())
        assertTrue(Crypto.verify(a.public.encoded, "hello".toByteArray(), sig))
        assertFalse(Crypto.verify(b.public.encoded, "hello".toByteArray(), sig))
        assertFalse(Crypto.verify(a.public.encoded, "hellO".toByteArray(), sig))
        assertFalse(Crypto.verify(byteArrayOf(1, 2, 3), "hello".toByteArray(), sig))
    }

    private fun channelPair(): Triple<SecureChannel, ByteArrayOutputStream, (ByteArray) -> SecureChannel> {
        val key1 = Crypto.randomBytes(32)
        val key2 = Crypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val sender = SecureChannel(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(wire), key1, key2, 1, 2)
        val receiverFor = { bytes: ByteArray ->
            SecureChannel(DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream()), key2, key1, 2, 1)
        }
        return Triple(sender, wire, receiverFor)
    }

    @Test
    fun `encrypted frames round-trip in order`() {
        val (sender, wire, receiverFor) = channelPair()
        sender.send("one".toByteArray())
        sender.send("two".toByteArray())
        val receiver = receiverFor(wire.toByteArray())
        assertEquals("one", receiver.receive()!!.decodeToString())
        assertEquals("two", receiver.receive()!!.decodeToString())
        assertNull(receiver.receive())
    }

    @Test(expected = ProtocolViolation::class)
    fun `replayed frame is rejected`() {
        val (sender, wire, receiverFor) = channelPair()
        sender.send("release".toByteArray())
        val frame = wire.toByteArray()
        val receiver = receiverFor(frame + frame)
        receiver.receive()
        receiver.receive()
    }

    @Test(expected = ProtocolViolation::class)
    fun `tampered frame is rejected`() {
        val (sender, wire, receiverFor) = channelPair()
        sender.send("release".toByteArray())
        val frame = wire.toByteArray()
        frame[frame.size - 3] = (frame[frame.size - 3].toInt() xor 0x01).toByte()
        receiverFor(frame).receive()
    }

    @Test
    fun `plaintext is not visible on the wire`() {
        val (sender, wire, _) = channelPair()
        sender.send("RELEASE_AUDIO_DEVICE".toByteArray())
        assertFalse(wire.toByteArray().decodeToString(throwOnInvalidSequence = false).contains("RELEASE"))
    }

    @Test
    fun `command guard rejects stale and duplicate commands`() {
        var now = 10_000_000L
        val guard = CommandGuard(freshnessWindowMs = 60_000, clock = { now })
        val peer = PeerId("p")
        val fresh = Ping("id-1", now, "p")
        assertEquals(CommandGuard.Verdict.Fresh, guard.check(peer, fresh))
        assertEquals(CommandGuard.Verdict.Duplicate(null), guard.check(peer, fresh))
        val reply = Ping("r", now, "me")
        guard.remember(peer, "id-1", reply)
        assertEquals(CommandGuard.Verdict.Duplicate(reply), guard.check(peer, fresh))
        assertEquals(CommandGuard.Verdict.Stale, guard.check(peer, Ping("id-2", now - 61_000, "p")))
        assertEquals(CommandGuard.Verdict.Stale, guard.check(peer, Ping("id-3", now + 61_000, "p")))
        // Same command id from a different peer is a different command.
        assertEquals(CommandGuard.Verdict.Fresh, guard.check(PeerId("q"), fresh))
        now += 10 * 60_000
        assertEquals(CommandGuard.Verdict.Fresh, guard.check(peer, Ping("id-1", now, "p")))
    }

    private fun invitation(hosts: List<String> = listOf("192.168.1.20")) = PairingInvitation(
        peerId = java.util.UUID.randomUUID().toString(),
        keyHash = PairingInvitation.keyHashOf(Crypto.generateEcKeyPair().public.encoded),
        token = Crypto.randomBytes(16),
        hosts = hosts,
        port = 47474,
    )

    @Test
    fun `pairing invitation round-trips through the QR text`() {
        val original = invitation()
        val text = original.encode()
        assertTrue(text.startsWith("HANDOFF:"))
        val parsed = PairingInvitation.parse(text)!!
        assertEquals(original.peerId, parsed.peerId)
        assertEquals(listOf("192.168.1.20"), parsed.hosts)
        assertEquals(47474, parsed.port)
        assertArrayEquals(original.token, parsed.token)
        // Pasting a lower-cased copy still works.
        assertEquals(original, PairingInvitation.parse(text.lowercase()))
    }

    @Test
    fun `QR payload stays small and alphanumeric so it scans quickly`() {
        val text = invitation().encode()
        assertTrue("length ${text.length}", text.length <= 114) // QR version 4 at ECC L (33x33 modules)
        assertTrue(text.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567:" })
    }

    @Test
    fun `invitation pins the inviter key by fingerprint`() {
        val key = Crypto.generateEcKeyPair().public.encoded
        val inv = PairingInvitation(java.util.UUID.randomUUID().toString(), PairingInvitation.keyHashOf(key), ByteArray(16), emptyList(), 1)
        assertTrue(inv.matchesKey(key))
        assertFalse(inv.matchesKey(Crypto.generateEcKeyPair().public.encoded))
    }

    @Test
    fun `malformed invitations are rejected`() {
        assertNull(PairingInvitation.parse("https://example.com"))
        assertNull(PairingInvitation.parse("HANDOFF:%%%"))
        assertNull(PairingInvitation.parse("HANDOFF:AAAA"))
        val valid = invitation().encode()
        assertNull(PairingInvitation.parse(valid.dropLast(3)))
        assertNotNull(PairingInvitation.parse(valid))
        assertNull(PairingInvitation.parse(invitation(hosts = List(5) { "10.0.0.$it" }).encode().let { it + "AAAAAAA" }))
    }
}
