package dev.handoff.core.mesh.security

import dev.handoff.core.mesh.protocol.ProtocolCodec
import dev.handoff.core.model.PeerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

const val HANDSHAKE_VERSION = 1

@Serializable
enum class HandshakeMode {
    /** Normal session between already-trusted peers. */
    SESSION,

    /** First contact after scanning a QR invitation. */
    PAIRING,
}

@Serializable
sealed class HandshakeMessage

@Serializable
@SerialName("CLIENT_HELLO")
data class ClientHello(
    val version: Int,
    val mode: HandshakeMode,
    val clientPeerId: String,
    val ephemeralKey: String,
    val nonce: String,
) : HandshakeMessage()

@Serializable
@SerialName("SERVER_HELLO")
data class ServerHello(
    val version: Int,
    val serverPeerId: String,
    val ephemeralKey: String,
    val nonce: String,
    val identityKey: String,
    val signature: String,
) : HandshakeMessage()

@Serializable
@SerialName("REJECT")
data class HandshakeReject(val reason: String) : HandshakeMessage()

/** Sent encrypted. Proves the client's identity key; in PAIRING mode also the QR token. */
@Serializable
@SerialName("CLIENT_FINISH")
data class ClientFinish(
    val identityKey: String,
    val displayName: String,
    val signature: String,
    val pairingProof: String? = null,
    /**
     * The TCP port the client's own server listens on. Lets the server reach the client back
     * even when it isn't on the default port (e.g. another app holds it). Older clients omit it.
     */
    val listenPort: Int? = null,
) : HandshakeMessage()

/** Sent encrypted. Confirms the server accepted the client (after user approval when pairing). */
@Serializable
@SerialName("SERVER_FINISH")
data class ServerFinish(
    val accepted: Boolean,
    val displayName: String,
    val reason: String? = null,
) : HandshakeMessage()

class HandshakeFailure(message: String) : IOException(message)

/** Server-side policy hooks used while authenticating a client. */
interface HandshakeAuthority {
    /** Stored identity key for a trusted peer, or null if the peer is not trusted. */
    fun trustedKey(peerId: PeerId): ByteArray?

    /** True while a pairing invitation is open and unexpired. */
    fun pairingOpen(): Boolean

    /** Verify (and consume, if valid) the one-time pairing token proof. */
    fun consumePairingProof(proof: ByteArray, transcript: ByteArray): Boolean
}

class ClientSession(
    val channel: SecureChannel,
    val serverPeerId: PeerId,
    /** The server's identity key, already checked against the pin. */
    val serverIdentityKey: ByteArray,
    /** Short authentication string both sides can display during pairing. */
    val sas: String,
)

class ServerSession(
    val channel: SecureChannel,
    val clientPeerId: PeerId,
    val mode: HandshakeMode,
    val clientIdentityKey: ByteArray,
    val clientDisplayName: String,
    val sas: String,
    /** Where the client's own server listens, if it said so. */
    val clientListenPort: Int? = null,
)

/**
 * SIGMA-style authenticated key exchange:
 *
 * ```
 * C -> S  CLIENT_HELLO  { mode, idC, ephC, nonceC }
 * S -> C  SERVER_HELLO  { idS, ephS, nonceS, keyS, sigS(th) }
 *         th = H(mode, idC, ephC, nonceC, idS, ephS, nonceS, keyS)
 *         k_c2s, k_s2c = HKDF(salt = th, ikm = ECDH(ephC, ephS))
 * C -> S  [enc] CLIENT_FINISH { keyC, name, sigC(th, keyC), pairingProof? }
 * S -> C  [enc] SERVER_FINISH { accepted }
 * ```
 *
 * The client pins keyS (from the trust store, or from the QR code while pairing). The server
 * accepts keyC only if it matches the trust store, or, while pairing, if the client proves
 * knowledge of the one-time QR token and the user approves.
 */
object Handshake {
    private val TRANSCRIPT_LABEL = "handoff-handshake-v1".toByteArray()
    private val SERVER_SIG_LABEL = "handoff-server-signature-v1".toByteArray()
    private val CLIENT_SIG_LABEL = "handoff-client-signature-v1".toByteArray()
    private val PAIRING_PROOF_LABEL = "handoff-pairing-proof-v1".toByteArray()
    private const val NONCE_BYTES = 32

    fun client(
        input: DataInputStream,
        output: DataOutputStream,
        identity: IdentityProvider,
        mode: HandshakeMode,
        expectedServer: PeerId,
        /** Pins the server key: the trust-store key (session) or the QR fingerprint (pairing). */
        acceptServerKey: (ByteArray) -> Boolean,
        pairingToken: ByteArray? = null,
        listenPort: Int? = null,
    ): ClientSession {
        require(mode == HandshakeMode.SESSION || pairingToken != null) { "pairing requires a token" }
        val me = identity.identity()
        val ephemeral = Crypto.generateEcKeyPair()
        val clientNonce = Crypto.randomBytes(NONCE_BYTES)
        val hello = ClientHello(
            version = HANDSHAKE_VERSION,
            mode = mode,
            clientPeerId = me.peerId.value,
            ephemeralKey = Crypto.b64(ephemeral.public.encoded),
            nonce = Crypto.b64(clientNonce),
        )
        writePlain(output, hello)

        val serverHello = when (val reply = readPlain(input)) {
            is ServerHello -> reply
            is HandshakeReject -> throw HandshakeFailure("server rejected: ${reply.reason}")
            else -> throw HandshakeFailure("unexpected handshake message")
        }
        if (serverHello.version != HANDSHAKE_VERSION) throw HandshakeFailure("unsupported handshake version")
        if (serverHello.serverPeerId != expectedServer.value) throw HandshakeFailure("server identity mismatch")
        val serverKey = Crypto.unb64(serverHello.identityKey)
        if (!acceptServerKey(serverKey)) throw HandshakeFailure("server key mismatch")
        Crypto.decodeP256PublicKey(serverKey)

        val serverEphemeral = Crypto.unb64(serverHello.ephemeralKey)
        val serverNonce = Crypto.unb64(serverHello.nonce)
        val th = transcript(
            mode, me.peerId.value, ephemeral.public.encoded, clientNonce,
            serverHello.serverPeerId, serverEphemeral, serverNonce, serverKey,
        )
        if (!Crypto.verify(serverKey, SERVER_SIG_LABEL + th, Crypto.unb64(serverHello.signature))) {
            throw HandshakeFailure("server signature invalid")
        }

        val shared = Crypto.ecdh(ephemeral.private, decodeEphemeral(serverEphemeral))
        val channel = SecureChannel(
            input, output,
            sendKey = deriveKey(th, shared, C2S_INFO),
            receiveKey = deriveKey(th, shared, S2C_INFO),
            sendDirection = SecureChannel.CLIENT_TO_SERVER,
            receiveDirection = SecureChannel.SERVER_TO_CLIENT,
        )
        val finish = ClientFinish(
            identityKey = Crypto.b64(me.publicKey),
            displayName = me.displayName,
            signature = Crypto.b64(identity.sign(CLIENT_SIG_LABEL + th + me.publicKey)),
            pairingProof = pairingToken?.let { Crypto.b64(Crypto.hmacSha256(it, PAIRING_PROOF_LABEL + th)) },
            listenPort = listenPort,
        )
        writeEncrypted(channel, finish)
        return ClientSession(channel, expectedServer, serverKey, sas(th))
    }

    /** Blocks until the server confirms (or refuses) the session. */
    fun awaitServerFinish(session: ClientSession): ServerFinish =
        when (val msg = readEncrypted(session.channel)) {
            is ServerFinish -> msg
            else -> throw HandshakeFailure("unexpected handshake message")
        }

    fun server(
        input: DataInputStream,
        output: DataOutputStream,
        identity: IdentityProvider,
        authority: HandshakeAuthority,
    ): ServerSession {
        val hello = readPlain(input) as? ClientHello ?: throw HandshakeFailure("expected CLIENT_HELLO")
        if (hello.version != HANDSHAKE_VERSION) {
            writePlain(output, HandshakeReject("unsupported version"))
            throw HandshakeFailure("unsupported handshake version")
        }
        val clientPeerId = PeerId(hello.clientPeerId)
        val trustedKey = authority.trustedKey(clientPeerId)
        when (hello.mode) {
            HandshakeMode.SESSION -> if (trustedKey == null) {
                writePlain(output, HandshakeReject("not trusted"))
                throw HandshakeFailure("untrusted peer ${clientPeerId.short}")
            }
            HandshakeMode.PAIRING -> if (!authority.pairingOpen()) {
                writePlain(output, HandshakeReject("pairing not open"))
                throw HandshakeFailure("pairing attempt while no invitation is open")
            }
        }

        val me = identity.identity()
        val clientEphemeral = Crypto.unb64(hello.ephemeralKey)
        val clientEphemeralKey = decodeEphemeral(clientEphemeral)
        val clientNonce = Crypto.unb64(hello.nonce)
        val ephemeral = Crypto.generateEcKeyPair()
        val serverNonce = Crypto.randomBytes(NONCE_BYTES)
        val th = transcript(
            hello.mode, hello.clientPeerId, clientEphemeral, clientNonce,
            me.peerId.value, ephemeral.public.encoded, serverNonce, me.publicKey,
        )
        writePlain(
            output,
            ServerHello(
                version = HANDSHAKE_VERSION,
                serverPeerId = me.peerId.value,
                ephemeralKey = Crypto.b64(ephemeral.public.encoded),
                nonce = Crypto.b64(serverNonce),
                identityKey = Crypto.b64(me.publicKey),
                signature = Crypto.b64(identity.sign(SERVER_SIG_LABEL + th)),
            ),
        )

        val shared = Crypto.ecdh(ephemeral.private, clientEphemeralKey)
        val channel = SecureChannel(
            input, output,
            sendKey = deriveKey(th, shared, S2C_INFO),
            receiveKey = deriveKey(th, shared, C2S_INFO),
            sendDirection = SecureChannel.SERVER_TO_CLIENT,
            receiveDirection = SecureChannel.CLIENT_TO_SERVER,
        )
        val finish = readEncrypted(channel) as? ClientFinish ?: throw HandshakeFailure("expected CLIENT_FINISH")
        val clientKey = Crypto.unb64(finish.identityKey)
        Crypto.decodeP256PublicKey(clientKey)
        if (!Crypto.verify(clientKey, CLIENT_SIG_LABEL + th + clientKey, Crypto.unb64(finish.signature))) {
            throw HandshakeFailure("client signature invalid")
        }
        when (hello.mode) {
            HandshakeMode.SESSION -> if (!Crypto.constantTimeEquals(clientKey, trustedKey!!)) {
                throw HandshakeFailure("client key does not match trust store")
            }
            HandshakeMode.PAIRING -> {
                val proof = finish.pairingProof?.let(Crypto::unb64)
                    ?: throw HandshakeFailure("missing pairing proof")
                if (!authority.consumePairingProof(proof, PAIRING_PROOF_LABEL + th)) {
                    throw HandshakeFailure("pairing proof invalid or expired")
                }
            }
        }
        return ServerSession(
            channel = channel,
            clientPeerId = clientPeerId,
            mode = hello.mode,
            clientIdentityKey = clientKey,
            clientDisplayName = finish.displayName.take(MAX_NAME_LENGTH),
            sas = sas(th),
            clientListenPort = finish.listenPort?.takeIf { it in 1..65535 },
        )
    }

    fun sendServerFinish(session: ServerSession, finish: ServerFinish) = writeEncrypted(session.channel, finish)

    /** Expected pairing proof for a token; used by the invitation holder to verify. */
    fun pairingProof(token: ByteArray, labelledTranscript: ByteArray): ByteArray =
        Crypto.hmacSha256(token, labelledTranscript)

    private fun transcript(
        mode: HandshakeMode,
        clientPeerId: String,
        clientEphemeral: ByteArray,
        clientNonce: ByteArray,
        serverPeerId: String,
        serverEphemeral: ByteArray,
        serverNonce: ByteArray,
        serverIdentityKey: ByteArray,
    ): ByteArray = Crypto.hashParts(
        TRANSCRIPT_LABEL,
        mode.name.toByteArray(),
        clientPeerId.toByteArray(),
        clientEphemeral,
        clientNonce,
        serverPeerId.toByteArray(),
        serverEphemeral,
        serverNonce,
        serverIdentityKey,
    )

    private fun deriveKey(th: ByteArray, shared: ByteArray, info: ByteArray) =
        Crypto.hkdf(salt = th, ikm = shared, info = info, length = 32)

    private fun decodeEphemeral(encoded: ByteArray) = try {
        Crypto.decodeP256PublicKey(encoded)
    } catch (e: Exception) {
        throw HandshakeFailure("invalid ephemeral key")
    }

    /** Six decimal digits derived from the transcript; identical on both sides. */
    internal fun sas(th: ByteArray): String {
        val digest = Crypto.hashParts("handoff-sas-v1".toByteArray(), th)
        val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return "%06d".format(value % 1_000_000)
    }

    private fun writePlain(output: DataOutputStream, message: HandshakeMessage) =
        FrameIO.write(output, encode(message))

    private fun readPlain(input: DataInputStream): HandshakeMessage =
        decode(FrameIO.read(input) ?: throw HandshakeFailure("connection closed during handshake"))

    private fun writeEncrypted(channel: SecureChannel, message: HandshakeMessage) =
        channel.send(encode(message))

    private fun readEncrypted(channel: SecureChannel): HandshakeMessage =
        decode(channel.receive() ?: throw HandshakeFailure("connection closed during handshake"))

    private fun encode(message: HandshakeMessage): ByteArray =
        ProtocolCodec.json.encodeToString(HandshakeMessage.serializer(), message).toByteArray()

    private fun decode(bytes: ByteArray): HandshakeMessage = try {
        ProtocolCodec.json.decodeFromString(HandshakeMessage.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw HandshakeFailure("malformed handshake message")
    }

    private val C2S_INFO = "handoff c2s v1".toByteArray()
    private val S2C_INFO = "handoff s2c v1".toByteArray()
    const val MAX_NAME_LENGTH = 64
}
