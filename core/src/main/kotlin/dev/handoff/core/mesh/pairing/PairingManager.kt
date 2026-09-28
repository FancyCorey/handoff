package dev.handoff.core.mesh.pairing

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.mesh.security.PairingInvitation
import dev.handoff.core.mesh.security.ServerSession
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The inviting side of peer linking.
 *
 * 1. [createInvitation] mints a single-use token and exposes the QR payload.
 * 2. The scanning device connects in PAIRING mode and proves it knows the token.
 * 3. [pendingApproval] asks the user on this device to confirm; both screens show the same
 *    six-digit code.
 * 4. Only on approval is the peer written to the trust store.
 */
class PairingManager(
    private val identity: IdentityProvider,
    private val trust: TrustedPeerRepository,
    private val events: EventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val invitationTtlMs: Long = 5 * 60_000L,
    private val approvalTimeoutMs: Long = APPROVAL_TIMEOUT_MS,
) {
    data class PendingPairing(val peerId: PeerId, val displayName: String, val sas: String)

    data class ActiveInvitation(val invitation: PairingInvitation, val expiresAtMs: Long)

    private val _invitation = MutableStateFlow<ActiveInvitation?>(null)
    val invitation: StateFlow<ActiveInvitation?> = _invitation.asStateFlow()

    private val _pending = MutableStateFlow<PendingPairing?>(null)
    val pendingApproval: StateFlow<PendingPairing?> = _pending.asStateFlow()

    private var token: ByteArray? = null
    private var decision: CompletableDeferred<Boolean>? = null

    @Synchronized
    fun createInvitation(hosts: List<String>, port: Int): ActiveInvitation {
        val me = identity.identity()
        val newToken = Crypto.randomBytes(PairingInvitation.TOKEN_BYTES)
        token = newToken
        val invitation = PairingInvitation(
            peerId = me.peerId.value,
            keyHash = PairingInvitation.keyHashOf(me.publicKey),
            token = newToken,
            hosts = hosts,
            port = port,
        )
        return ActiveInvitation(invitation, clock() + invitationTtlMs).also { _invitation.value = it }
    }

    @Synchronized
    fun cancelInvitation() {
        token = null
        _invitation.value = null
    }

    @Synchronized
    fun pairingOpen(): Boolean {
        val active = _invitation.value ?: return false
        if (clock() >= active.expiresAtMs) {
            cancelInvitation()
            return false
        }
        return token != null && _pending.value == null
    }

    /** Single use: a valid proof consumes the token so the QR code cannot be reused. */
    @Synchronized
    fun consumePairingProof(proof: ByteArray, labelledTranscript: ByteArray): Boolean {
        if (!pairingOpen()) return false
        val current = token ?: return false
        val expected = Handshake.pairingProof(current, labelledTranscript)
        if (!Crypto.constantTimeEquals(expected, proof)) return false
        token = null
        return true
    }

    /** Suspends until the user decides (or the request times out). Persists trust on approval. */
    suspend fun awaitApproval(session: ServerSession): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (_pending.value != null) return false
            decision = deferred
            _pending.value = PendingPairing(session.clientPeerId, session.clientDisplayName, session.sas)
        }
        val approved = withTimeoutOrNull(approvalTimeoutMs) { deferred.await() } ?: false
        synchronized(this) {
            decision = null
            _pending.value = null
            _invitation.value = null
        }
        if (approved) {
            trust.upsert(
                TrustedPeer(
                    peerId = session.clientPeerId,
                    displayName = session.clientDisplayName,
                    publicKey = session.clientIdentityKey,
                    pairedAtMs = clock(),
                ),
            )
            events.record(EventType.PEER_PAIRED, peerId = session.clientPeerId, details = mapOf("role" to "inviter"))
        } else {
            events.record(EventType.PEER_AUTH_FAILED, peerId = session.clientPeerId, details = mapOf("reason" to "pairing declined"))
        }
        return approved
    }

    fun respond(approved: Boolean) {
        synchronized(this) { decision }?.complete(approved)
    }

    companion object {
        const val APPROVAL_TIMEOUT_MS = 90_000L
    }
}
