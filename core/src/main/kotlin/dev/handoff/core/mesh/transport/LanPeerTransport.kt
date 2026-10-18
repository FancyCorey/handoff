package dev.handoff.core.mesh.transport

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

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
    /** This device's own server port, sent in the handshake so peers can reach it back. */
    private val listenPort: () -> Int? = { null },
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
                // An address that doesn't answer, or answers as some other Handoff (for example a
                // second copy on the same device, or a new device on an old address), is the wrong
                // address: try the next one.
                if (result != CommandResult.Unreachable && !(result is CommandResult.Rejected && result.isWrongEndpoint())) break
            }
            when (result) {
                is CommandResult.Reply -> Unit
                is CommandResult.Rejected -> {
                    events.record(EventType.PEER_AUTH_FAILED, peerId = peerId, details = mapOf("reason" to result.reason))
                    if (result.isWrongEndpoint()) directory.onContactFailed(peerId)
                }
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
            val outcome = StreamExchange.run(
                socket.getInputStream(), socket.getOutputStream(), identity, peerId, pinnedKey, message, listenPort(),
                beforeRead = { socket.soTimeout = remaining(deadline) },
            )
            if (outcome.answered) directory.onContactSucceeded(peerId, endpoint.host, endpoint.port)
            return outcome.result
        } catch (e: IOException) {
            return CommandResult.Unreachable
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun remaining(deadline: Long): Int = (deadline - clock()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}

/** The handshake failed or the session was refused: nothing was delivered to the right peer. */
internal fun CommandResult.Rejected.isWrongEndpoint(): Boolean =
    reason.startsWith("handshake:") || reason == "session refused" || reason.startsWith("not trusted")
