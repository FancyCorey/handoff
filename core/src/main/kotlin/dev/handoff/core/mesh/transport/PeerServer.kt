package dev.handoff.core.mesh.transport

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.pairing.PairingManager
import dev.handoff.core.mesh.protocol.ErrorCode
import dev.handoff.core.mesh.protocol.ErrorReply
import dev.handoff.core.mesh.protocol.MalformedMessageException
import dev.handoff.core.mesh.protocol.PROTOCOL_VERSION
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.PeerReply
import dev.handoff.core.mesh.protocol.ProtocolCodec
import dev.handoff.core.mesh.security.CommandGuard
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.HandshakeAuthority
import dev.handoff.core.mesh.security.HandshakeMode
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.security.ServerFinish
import dev.handoff.core.mesh.security.ServerSession
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Handles one authenticated application request and returns the reply. */
fun interface PeerRequestHandler {
    suspend fun handle(from: PeerId, message: PeerMessage): PeerMessage
}

/**
 * LAN listener. Accepts TCP connections, runs the server side of the handshake and serves
 * encrypted requests from trusted peers only. Unknown peers are rejected before any
 * application message is read; pairing connections are only accepted while an invitation is
 * open and still require explicit user approval.
 */
class PeerServer(
    private val identity: IdentityProvider,
    private val trust: TrustedPeerRepository,
    private val pairing: PairingManager,
    private val handler: PeerRequestHandler,
    private val directory: PeerDirectory,
    private val events: EventLog,
    private val guard: CommandGuard = CommandGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConcurrentConnections: Int = 8,
    private val idleTimeoutMs: Int = 10_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _port = MutableStateFlow<Int?>(null)
    val port: StateFlow<Int?> = _port.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val permits = Semaphore(maxConcurrentConnections)

    private val authority = object : HandshakeAuthority {
        override fun trustedKey(peerId: PeerId): ByteArray? = trust.find(peerId)?.publicKey
        override fun pairingOpen(): Boolean = pairing.pairingOpen()
        override fun consumePairingProof(proof: ByteArray, transcript: ByteArray): Boolean =
            pairing.consumePairingProof(proof, transcript)
    }

    /**
     * Bind and start accepting. Tries [preferredPort] first so the address peers remember stays
     * valid across restarts, then falls back to an ephemeral port. Returns the bound port.
     */
    @Synchronized
    fun start(scope: CoroutineScope, preferredPort: Int = DEFAULT_PORT): Int {
        serverSocket?.let { return it.localPort }
        val socket = bind(preferredPort)
        serverSocket = socket
        _port.value = socket.localPort
        acceptJob = scope.launch(io) { acceptLoop(socket, this) }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        _port.value = null
    }

    private fun bind(preferredPort: Int): ServerSocket {
        if (preferredPort > 0) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort))
                }
            } catch (_: BindException) {
                // fall through to an ephemeral port
            } catch (_: IOException) {
            }
        }
        return ServerSocket().apply { bind(InetSocketAddress(0)) }
    }

    private fun acceptLoop(server: ServerSocket, scope: CoroutineScope) {
        while (scope.isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            if (!permits.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            scope.launch(io) {
                try {
                    serve(socket)
                } finally {
                    permits.release()
                    runCatching { socket.close() }
                }
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = idleTimeoutMs
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val session = try {
            Handshake.server(input, output, identity, authority)
        } catch (e: Exception) {
            events.record(EventType.PEER_AUTH_FAILED, details = mapOf("reason" to (e.message ?: e.javaClass.simpleName)))
            return
        }
        when (session.mode) {
            HandshakeMode.PAIRING -> servePairing(socket, session)
            HandshakeMode.SESSION -> serveSession(session, socket.inetAddress?.hostAddress)
        }
    }

    private suspend fun servePairing(socket: Socket, session: ServerSession) {
        socket.soTimeout = (PairingManager.APPROVAL_TIMEOUT_MS + 5_000).toInt()
        val approved = pairing.awaitApproval(session)
        val me = identity.identity()
        runCatching {
            Handshake.sendServerFinish(
                session,
                ServerFinish(accepted = approved, displayName = me.displayName, reason = if (approved) null else "declined"),
            )
        }
    }

    private suspend fun serveSession(session: ServerSession, remoteHost: String?) {
        val peer = session.clientPeerId
        Handshake.sendServerFinish(session, ServerFinish(accepted = true, displayName = identity.identity().displayName))
        directory.onInboundContact(peer, remoteHost, DEFAULT_PORT)
        while (true) {
            val bytes = try {
                session.channel.receive() ?: return
            } catch (e: IOException) {
                return
            }
            val reply = try {
                respond(peer, ProtocolCodec.decode(bytes))
            } catch (e: MalformedMessageException) {
                events.record(EventType.COMMAND_REJECTED, peerId = peer, details = mapOf("reason" to "malformed"))
                error("", ErrorCode.MALFORMED, "malformed message")
            }
            try {
                session.channel.send(ProtocolCodec.encode(reply))
            } catch (e: IOException) {
                return
            }
        }
    }

    internal suspend fun respond(peer: PeerId, message: PeerMessage): PeerMessage {
        if (message.senderPeerId != peer.value) {
            events.record(EventType.PEER_AUTH_FAILED, peerId = peer, details = mapOf("reason" to "sender mismatch"))
            return error(message.commandId, ErrorCode.SENDER_MISMATCH, "sender does not match session")
        }
        if (message.protocolVersion != PROTOCOL_VERSION) {
            return error(message.commandId, ErrorCode.UNSUPPORTED_VERSION, "protocol ${message.protocolVersion}")
        }
        if (message is PeerReply) {
            return error(message.commandId, ErrorCode.UNSUPPORTED_TYPE, "unsolicited reply")
        }
        when (val verdict = guard.check(peer, message)) {
            CommandGuard.Verdict.Stale -> {
                events.record(EventType.COMMAND_REJECTED, peerId = peer, details = mapOf("reason" to "stale"))
                return error(message.commandId, ErrorCode.STALE, "timestamp outside freshness window")
            }
            is CommandGuard.Verdict.Duplicate -> {
                events.record(EventType.COMMAND_REJECTED, peerId = peer, details = mapOf("reason" to "duplicate"))
                return verdict.cached ?: error(message.commandId, ErrorCode.DUPLICATE, "command already in progress")
            }
            CommandGuard.Verdict.Fresh -> Unit
        }
        val reply = try {
            handler.handle(peer, message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error(message.commandId, ErrorCode.INTERNAL, e.javaClass.simpleName)
        }
        guard.remember(peer, message.commandId, reply)
        return reply
    }

    private fun error(inReplyTo: String, code: ErrorCode, text: String) = ErrorReply(
        commandId = PeerMessage.newCommandId(),
        timestamp = clock(),
        senderPeerId = identity.identity().peerId.value,
        inReplyTo = inReplyTo,
        code = code,
        message = text,
    )

    companion object {
        const val DEFAULT_PORT = 47_474
    }
}
