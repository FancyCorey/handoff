package dev.handoff.core.handoff

/** Phases of one "Move here" transfer. */
enum class TransferPhase {
    IDLE,
    RESOLVING_OWNER,
    REQUESTING_RELEASE,
    WAITING_RELEASE,
    CONNECTING,
    VERIFYING,
    RETRYING,
    DIRECT_TAKEOVER,
    COMPLETE,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == COMPLETE || this == FAILED
}

class IllegalTransitionException(from: TransferPhase, to: TransferPhase) :
    IllegalStateException("illegal transfer transition $from -> $to")

/**
 * Explicit transition table for a transfer. Every phase change goes through [moveTo]; an
 * undeclared transition is a programming error and throws [IllegalTransitionException].
 */
class TransferStateMachine(
    private val onTransition: (from: TransferPhase, to: TransferPhase) -> Unit = { _, _ -> },
) {
    var phase: TransferPhase = TransferPhase.IDLE
        private set

    private val _history = mutableListOf(TransferPhase.IDLE)
    val history: List<TransferPhase> get() = _history.toList()

    fun canMoveTo(next: TransferPhase): Boolean = next in TRANSITIONS.getValue(phase)

    fun moveTo(next: TransferPhase) {
        if (!canMoveTo(next)) throw IllegalTransitionException(phase, next)
        val previous = phase
        phase = next
        _history += next
        onTransition(previous, next)
    }

    companion object {
        private val ANY_ACTIVE_TO_FAILED = TransferPhase.FAILED

        val TRANSITIONS: Map<TransferPhase, Set<TransferPhase>> = mapOf(
            TransferPhase.IDLE to setOf(TransferPhase.RESOLVING_OWNER),
            TransferPhase.RESOLVING_OWNER to setOf(
                TransferPhase.REQUESTING_RELEASE,
                TransferPhase.CONNECTING, // nobody holds it, or multipoint join
                TransferPhase.DIRECT_TAKEOVER, // owner unknown
                TransferPhase.COMPLETE, // already connected locally
                ANY_ACTIVE_TO_FAILED,
            ),
            TransferPhase.REQUESTING_RELEASE to setOf(
                TransferPhase.WAITING_RELEASE,
                TransferPhase.DIRECT_TAKEOVER, // peer unreachable
                ANY_ACTIVE_TO_FAILED,
            ),
            TransferPhase.WAITING_RELEASE to setOf(
                TransferPhase.CONNECTING, // released / not connected
                TransferPhase.REQUESTING_RELEASE, // next holder (conflict / multipoint exclusive)
                TransferPhase.DIRECT_TAKEOVER, // timeout / refused
                ANY_ACTIVE_TO_FAILED, // contention
            ),
            TransferPhase.DIRECT_TAKEOVER to setOf(TransferPhase.CONNECTING, ANY_ACTIVE_TO_FAILED),
            TransferPhase.CONNECTING to setOf(
                TransferPhase.VERIFYING,
                TransferPhase.RETRYING, // connect request itself failed
                ANY_ACTIVE_TO_FAILED,
            ),
            TransferPhase.VERIFYING to setOf(
                TransferPhase.COMPLETE,
                TransferPhase.RETRYING,
                ANY_ACTIVE_TO_FAILED,
            ),
            TransferPhase.RETRYING to setOf(
                TransferPhase.CONNECTING,
                TransferPhase.RESOLVING_OWNER, // automatic retry of the whole transfer
                ANY_ACTIVE_TO_FAILED,
            ),
            TransferPhase.COMPLETE to emptySet(),
            TransferPhase.FAILED to emptySet(),
        )
    }
}
