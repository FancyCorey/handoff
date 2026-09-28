package dev.handoff.core.mesh.transport

import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.model.PeerId

/**
 * Request/response messaging with trusted peers. The LAN implementation is the only one today;
 * an Internet relay can implement the same interface later without touching the domain.
 */
interface PeerTransport {
    suspend fun request(peerId: PeerId, message: PeerMessage, timeoutMs: Long): CommandResult

    /** A quick authenticated round-trip (PING/PONG). */
    suspend fun isReachable(peerId: PeerId, timeoutMs: Long): Boolean
}

sealed interface CommandResult {
    /** A correlated reply from the authenticated peer. */
    data class Reply(val message: PeerMessage) : CommandResult

    /** No known endpoint, or the connection could not be established. */
    data object Unreachable : CommandResult

    /** Connected, but no reply arrived within the deadline. */
    data object Timeout : CommandResult

    /** Authentication failed, the peer is not trusted, or it answered with an error. */
    data class Rejected(val reason: String) : CommandResult
}
