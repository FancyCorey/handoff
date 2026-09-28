package dev.handoff.core.mesh.transport

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.protocol.ErrorReply
import dev.handoff.core.mesh.protocol.MalformedMessageException
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.PeerReply
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.protocol.ProtocolCodec
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.HandshakeFailure
import dev.handoff.core.mesh.security.HandshakeMode
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.security.ProtocolViolation
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Client side of the LAN transport. One short-lived TCP connection per request:
 * connect -> authenticated handshake -> one encrypted request -> one encrypted reply -> close.
 *
 * Every blocking step is bounded by the caller's deadline via socket timeouts, so a request
 * can never hang longer than [request]'s `timeoutMs` (plus a few ms).
 */
class LanPeerTransport(
    private val identity: IdentityProvider,
    private val trust: TrustedPeerRepository,
    private val directory: PeerDirectory,
    private val events: EventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Int = 2_500,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PeerTransport {

    override suspend fun isReachable(peerId: PeerId, timeoutMs: Long): Boolean {
        val ping = Ping(PeerMessage.newCommandId(), clock(), identity.identity().peerId.value)
        val result = request(peerId, ping, timeoutMs)
        return result is CommandResult.Reply && result.message is Pong
    }

    override suspend fun request(peerId: PeerId, message: PeerMessage, timeoutMs: Long): CommandResult {
        val trusted = trust.find(peerId) ?: return CommandResult.Rejected("peer is not trusted")
        val endpoints = directory.endpointsFor(peerId)
        if (endpoints.isEmpty()) return CommandResult.Unreachable

        return withContext(io) {
            val deadline = clock() + timeoutMs
            var result: CommandResult = CommandResult.Unreachable
            for (endpoint in endpoints) {
                val remaining = deadline - clock()
                if (remaining <= 0) {
                    result = CommandResult.Timeout
                    break
                }
                result = exchange(endpoint, peerId, trusted.publicKey, message, deadline)
                if (result != CommandResult.Unreachable) break
            }
            when (result) {
                is CommandResult.Reply -> Unit
                is CommandResult.Rejected -> events.record(
                    EventType.PEER_AUTH_FAILED, peerId = peerId, details = mapOf("reason" to result.reason),
                )
                else -> directory.onContactFailed(peerId)
            }
            result
        }
    }

    private fun exchange(
        endpoint: PeerEndpoint,
        peerId: PeerId,
        pinnedKey: ByteArray,
        message: PeerMessage,
        deadline: Long,
    ): CommandResult {
        val socket = Socket()
        try {
            val connectBudget = minOf(connectTimeoutMs.toLong(), deadline - clock()).coerceAtLeast(1)
            try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectBudget.toInt())
            } catch (e: IOException) {
                return CommandResult.Unreachable
            }
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            socket.soTimeout = remaining(deadline)
            val session = Handshake.client(input, output, identity, HandshakeMode.SESSION, peerId, { Crypto.constantTimeEquals(it, pinnedKey) })
            socket.soTimeout = remaining(deadline)
            val finish = Handshake.awaitServerFinish(session)
            if (!finish.accepted) return CommandResult.Rejected(finish.reason ?: "session refused")

            session.channel.send(ProtocolCodec.encode(message))
            socket.soTimeout = remaining(deadline)
            val replyBytes = session.channel.receive() ?: return CommandResult.Timeout
            val reply = ProtocolCodec.decode(replyBytes)

            if (reply.senderPeerId != peerId.value) return CommandResult.Rejected("reply sender mismatch")
            if (reply !is PeerReply || reply.inReplyTo != message.commandId) {
                return CommandResult.Rejected("uncorrelated reply")
            }
            directory.onContactSucceeded(peerId, endpoint.host, endpoint.port)
            if (reply is ErrorReply) return CommandResult.Rejected("${reply.code}: ${reply.message}")
            return CommandResult.Reply(reply)
        } catch (e: SocketTimeoutException) {
            return CommandResult.Timeout
        } catch (e: HandshakeFailure) {
            return CommandResult.Rejected("handshake: ${e.message}")
        } catch (e: ProtocolViolation) {
            return CommandResult.Rejected("protocol: ${e.message}")
        } catch (e: MalformedMessageException) {
            return CommandResult.Rejected("malformed reply")
        } catch (e: SecurityException) {
            return CommandResult.Rejected("security: ${e.message}")
        } catch (e: java.security.GeneralSecurityException) {
            // e.g. the local Keystore refused to sign.
            return CommandResult.Rejected("crypto: ${e.javaClass.simpleName}")
        } catch (e: IOException) {
            return CommandResult.Unreachable
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun remaining(deadline: Long): Int = (deadline - clock()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}
