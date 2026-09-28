package dev.handoff.core.fakes

import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.PeerRequestHandler
import dev.handoff.core.mesh.transport.PeerTransport
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-memory transport. Peers are either routed to a real [PeerRequestHandler] (to simulate
 * another Handoff host) or to a scripted responder.
 */
class FakePeerTransport(private val selfId: PeerId) : PeerTransport {
    val reachable = mutableSetOf<PeerId>()
    private val handlers = mutableMapOf<PeerId, PeerRequestHandler>()
    private val scripted = mutableMapOf<PeerId, suspend (PeerMessage) -> CommandResult>()
    val sent = mutableListOf<Pair<PeerId, PeerMessage>>()
    var networkLatencyMs = 20L

    fun route(peer: PeerId, handler: PeerRequestHandler) {
        handlers[peer] = handler
        reachable += peer
    }

    fun script(peer: PeerId, responder: suspend (PeerMessage) -> CommandResult) {
        scripted[peer] = responder
        reachable += peer
    }

    inline fun <reified T : PeerMessage> sentOfType(): List<Pair<PeerId, T>> =
        sent.filter { it.second is T }.map { it.first to it.second as T }

    override suspend fun request(peerId: PeerId, message: PeerMessage, timeoutMs: Long): CommandResult {
        sent += peerId to message
        if (peerId !in reachable) return CommandResult.Unreachable
        return withTimeoutOrNull(timeoutMs) {
            delay(networkLatencyMs)
            scripted[peerId]?.invoke(message)
                ?: handlers[peerId]?.let { CommandResult.Reply(it.handle(selfId, message)) }
                ?: CommandResult.Unreachable
        } ?: CommandResult.Timeout
    }

    override suspend fun isReachable(peerId: PeerId, timeoutMs: Long): Boolean = peerId in reachable
}
