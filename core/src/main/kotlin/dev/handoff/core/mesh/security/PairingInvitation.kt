package dev.handoff.core.mesh.security

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Contents of the "Add device" QR code.
 *
 * The QR code is the out-of-band channel. It commits to the inviting host's identity key and
 * carries a single-use 128-bit token. It is kept deliberately tiny so the code is coarse and
 * scans instantly: a binary payload, Base32-encoded so QR uses its compact alphanumeric mode
 * (~100 characters → a 33×33 code at ECC level L).
 *
 * Instead of the full key it carries a 128-bit SHA-256 fingerprint of it; the scanner accepts
 * the key the server presents in the handshake only if it matches (a 128-bit commitment is
 * infeasible to forge). The inviting host enforces expiry; the name comes from the handshake.
 *
 * Layout: version(1) | peerId UUID(16) | keyHash(16) | token(16) | port(2) | hostCount(1) | IPv4×n
 */
class PairingInvitation(
    val peerId: String,
    val keyHash: ByteArray,
    val token: ByteArray,
    val hosts: List<String>,
    val port: Int,
) {
    init {
        require(keyHash.size == KEY_HASH_BYTES && token.size == TOKEN_BYTES && port in 1..65535)
    }

    fun matchesKey(identityKey: ByteArray): Boolean = Crypto.constantTimeEquals(keyHashOf(identityKey), keyHash)

    fun withHosts(newHosts: List<String>) = PairingInvitation(peerId, keyHash, token, newHosts, port)

    fun encode(): String {
        val uuid = UUID.fromString(peerId)
        val ipv4 = hosts.mapNotNull(::ipv4Bytes).take(MAX_HOSTS)
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.write(ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array())
        out.write(keyHash)
        out.write(token)
        out.write(port ushr 8)
        out.write(port and 0xff)
        out.write(ipv4.size)
        ipv4.forEach(out::write)
        return PREFIX + Base32.encode(out.toByteArray())
    }

    override fun equals(other: Any?): Boolean = other is PairingInvitation && other.encode() == encode()

    override fun hashCode(): Int = encode().hashCode()

    override fun toString(): String = "PairingInvitation(peer=${peerId.take(8)}, hosts=$hosts, port=$port)"

    companion object {
        const val PREFIX = "HANDOFF:"
        const val TOKEN_BYTES = 16
        const val KEY_HASH_BYTES = 16
        private const val VERSION = 2
        private const val MAX_HOSTS = 4

        fun keyHashOf(identityKey: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(identityKey).copyOf(KEY_HASH_BYTES)

        /** Returns null for anything that is not a well-formed Handoff invitation. */
        fun parse(text: String): PairingInvitation? = try {
            val trimmed = text.trim()
            if (!trimmed.uppercase().startsWith(PREFIX)) {
                null
            } else {
                val bytes = Base32.decode(trimmed.substring(PREFIX.length))
                val buffer = ByteBuffer.wrap(bytes)
                if (buffer.get().toInt() != VERSION) {
                    null
                } else {
                    val uuid = UUID(buffer.long, buffer.long)
                    val keyHash = ByteArray(KEY_HASH_BYTES).also { buffer.get(it) }
                    val token = ByteArray(TOKEN_BYTES).also { buffer.get(it) }
                    val port = ((buffer.get().toInt() and 0xff) shl 8) or (buffer.get().toInt() and 0xff)
                    val count = buffer.get().toInt() and 0xff
                    if (count > MAX_HOSTS || buffer.remaining() != count * 4) {
                        null
                    } else {
                        val hosts = (0 until count).map {
                            val ip = ByteArray(4).also { b -> buffer.get(b) }
                            ip.joinToString(".") { b -> (b.toInt() and 0xff).toString() }
                        }
                        PairingInvitation(uuid.toString(), keyHash, token, hosts, port)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }

        private fun ipv4Bytes(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
            return (InetAddress.getByName(host) as? Inet4Address)?.address
        }
    }
}

/** RFC 4648 Base32 without padding (uppercase; within the QR alphanumeric character set). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text.uppercase()) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base32" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
