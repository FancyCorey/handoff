package dev.handoff.core.mesh.transport

import dev.handoff.core.mesh.protocol.ErrorReply
import dev.handoff.core.mesh.protocol.MalformedMessageException
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.PeerReply
import dev.handoff.core.mesh.protocol.ProtocolCodec
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.HandshakeFailure
import dev.handoff.core.mesh.security.HandshakeMode
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.security.ProtocolViolation
import dev.handoff.core.model.PeerId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/** What one request over an open stream came to, and whether the right peer answered at all. */
internal class ExchangeOutcome(val result: CommandResult, val answered: Boolean)

/**
 * The client side of one request over an already open stream, whatever carries it (a TCP socket
 * on the local network, or a Bluetooth channel to a device nearby): authenticated handshake,
 * one encrypted request, one encrypted reply.
 */
internal object StreamExchange {
    fun run(
        input: InputStream,
        output: OutputStream,
        identity: IdentityProvider,
        peerId: PeerId,
        pinnedKey: ByteArray,
        message: PeerMessage,
        listenPort: Int?,
        /** Called before each blocking read, to bound it (socket timeouts on TCP). */
        beforeRead: () -> Unit = {},
    ): ExchangeOutcome {
        try {
            val din = DataInputStream(BufferedInputStream(input))
            val dout = DataOutputStream(BufferedOutputStream(output))
            beforeRead()
            val session = Handshake.client(
                din, dout, identity, HandshakeMode.SESSION, peerId, { Crypto.constantTimeEquals(it, pinnedKey) },
                listenPort = listenPort,
            )
            beforeRead()
            val finish = Handshake.awaitServerFinish(session)
            if (!finish.accepted) return ExchangeOutcome(CommandResult.Rejected(finish.reason ?: "session refused"), false)

            session.channel.send(ProtocolCodec.encode(message))
            beforeRead()
            val replyBytes = session.channel.receive() ?: return ExchangeOutcome(CommandResult.Timeout, false)
            val reply = ProtocolCodec.decode(replyBytes)

            if (reply.senderPeerId != peerId.value) return ExchangeOutcome(CommandResult.Rejected("reply sender mismatch"), false)
            if (reply !is PeerReply || reply.inReplyTo != message.commandId) {
                return ExchangeOutcome(CommandResult.Rejected("uncorrelated reply"), false)
            }
            if (reply is ErrorReply) return ExchangeOutcome(CommandResult.Rejected("${reply.code}: ${reply.message}"), true)
            return ExchangeOutcome(CommandResult.Reply(reply), true)
        } catch (e: SocketTimeoutException) {
            return ExchangeOutcome(CommandResult.Timeout, false)
        } catch (e: HandshakeFailure) {
            return ExchangeOutcome(CommandResult.Rejected("handshake: ${e.message}"), false)
        } catch (e: ProtocolViolation) {
            return ExchangeOutcome(CommandResult.Rejected("protocol: ${e.message}"), false)
        } catch (e: MalformedMessageException) {
            return ExchangeOutcome(CommandResult.Rejected("malformed reply"), false)
        } catch (e: SecurityException) {
            return ExchangeOutcome(CommandResult.Rejected("security: ${e.message}"), false)
        } catch (e: java.security.GeneralSecurityException) {
            // e.g. the local Keystore refused to sign.
            return ExchangeOutcome(CommandResult.Rejected("crypto: ${e.javaClass.simpleName}"), false)
        } catch (e: IOException) {
            return ExchangeOutcome(CommandResult.Unreachable, false)
        }
    }
}
