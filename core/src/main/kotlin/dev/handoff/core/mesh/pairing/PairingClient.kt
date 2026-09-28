package dev.handoff.core.mesh.pairing

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.HandshakeFailure
import dev.handoff.core.mesh.security.HandshakeMode
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.security.PairingInvitation
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
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

sealed interface PairingOutcome {
    data class Paired(val peer: TrustedPeer) : PairingOutcome
    data class Declined(val reason: String) : PairingOutcome
    data object Expired : PairingOutcome
    data object SelfInvitation : PairingOutcome
    data class Failed(val reason: String) : PairingOutcome
}

/** The scanning side of peer linking. */
class PairingClient(
    private val identity: IdentityProvider,
    private val trust: TrustedPeerRepository,
    private val directory: PeerDirectory,
    private val events: EventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Connect to the inviting host, authenticate it against the key in the QR code, prove the
     * token, and wait for the user on the other device to approve. [onCode] receives the
     * six-digit verification code to display while waiting.
     */
    suspend fun pair(invitation: PairingInvitation, onCode: (String) -> Unit): PairingOutcome = withContext(io) {
        val inviter = PeerId(invitation.peerId)
        if (inviter == identity.identity().peerId) return@withContext PairingOutcome.SelfInvitation
        val token = invitation.token

        val hosts = invitation.hosts.map { it to invitation.port } +
            directory.endpointsFor(inviter).map { it.host to it.port }
        var lastError = "no address in invitation"
        for ((host, port) in hosts.distinct()) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val session = Handshake.client(input, output, identity, HandshakeMode.PAIRING, inviter, invitation::matchesKey, token)
                onCode(session.sas)
                socket.soTimeout = (PairingManager.APPROVAL_TIMEOUT_MS + 10_000).toInt()
                val finish = Handshake.awaitServerFinish(session)
                if (!finish.accepted) return@withContext PairingOutcome.Declined(finish.reason ?: "declined")

                val peer = TrustedPeer(
                    peerId = inviter,
                    displayName = finish.displayName.take(Handshake.MAX_NAME_LENGTH),
                    publicKey = session.serverIdentityKey,
                    pairedAtMs = clock(),
                )
                trust.upsert(peer)
                directory.onContactSucceeded(inviter, host, port)
                events.record(EventType.PEER_PAIRED, peerId = inviter, details = mapOf("role" to "scanner"))
                return@withContext PairingOutcome.Paired(peer)
            } catch (e: HandshakeFailure) {
                // The inviter answered but refused or failed authentication: do not try other hosts.
                if (e.message?.contains("pairing not open") == true) return@withContext PairingOutcome.Expired
                return@withContext PairingOutcome.Failed(e.message ?: "handshake failed")
            } catch (e: IOException) {
                lastError = "could not reach $host:$port"
            } catch (e: SecurityException) {
                return@withContext PairingOutcome.Failed("invalid invitation")
            } finally {
                runCatching { socket.close() }
            }
        }
        PairingOutcome.Failed(lastError)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000
    }
}
