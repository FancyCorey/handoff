package dev.handoff.core.mesh.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/** Length-prefixed frames with a hard size limit, so a peer cannot make us allocate freely. */
object FrameIO {
    const val MAX_FRAME_BYTES = 64 * 1024

    fun write(output: DataOutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "frame too large" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    /** Returns null on a clean end of stream before a frame starts. */
    fun read(input: DataInputStream): ByteArray? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 0 || length > MAX_FRAME_BYTES) throw ProtocolViolation("invalid frame length $length")
        return ByteArray(length).also { input.readFully(it) }
    }
}

class ProtocolViolation(message: String) : IOException(message)

/**
 * AES-256-GCM record layer on top of a TCP stream.
 *
 * Each direction has its own key and a strictly increasing 64-bit counter used as the nonce.
 * A replayed, reordered, dropped or modified frame fails decryption or the counter check and
 * the connection is torn down. Keys are per-session (ephemeral ECDH), so frames recorded from
 * one session are useless in any other.
 */
class SecureChannel internal constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendDirection: Byte,
    private val receiveDirection: Byte,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun send(plaintext: ByteArray) {
        val counter = sendCounter++
        val ciphertext = Crypto.aesGcmEncrypt(sendKey, nonce(sendDirection, counter), plaintext, aad(counter))
        val frame = ByteBuffer.allocate(8 + ciphertext.size).putLong(counter).put(ciphertext).array()
        FrameIO.write(output, frame)
    }

    /** Returns null when the peer closed the stream cleanly. */
    fun receive(): ByteArray? {
        val frame = FrameIO.read(input) ?: return null
        if (frame.size < 8 + 16) throw ProtocolViolation("short frame")
        val buffer = ByteBuffer.wrap(frame)
        val counter = buffer.long
        if (counter != receiveCounter) throw ProtocolViolation("unexpected frame counter (replay or reorder)")
        val ciphertext = ByteArray(frame.size - 8).also { buffer.get(it) }
        val plaintext = try {
            Crypto.aesGcmDecrypt(receiveKey, nonce(receiveDirection, counter), ciphertext, aad(counter))
        } catch (e: Exception) {
            throw ProtocolViolation("frame authentication failed")
        }
        receiveCounter++
        return plaintext
    }

    private fun nonce(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(0).put(0).put(0).put(direction).putLong(counter).array()

    private fun aad(counter: Long): ByteArray =
        FRAME_LABEL + ByteBuffer.allocate(8).putLong(counter).array()

    companion object {
        private val FRAME_LABEL = "handoff-frame-v1".toByteArray()
        internal const val CLIENT_TO_SERVER: Byte = 1
        internal const val SERVER_TO_CLIENT: Byte = 2
    }
}
