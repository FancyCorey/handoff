package dev.handoff.core.handoff

import dev.handoff.core.handoff.TransferPhase.COMPLETE
import dev.handoff.core.handoff.TransferPhase.CONNECTING
import dev.handoff.core.handoff.TransferPhase.DIRECT_TAKEOVER
import dev.handoff.core.handoff.TransferPhase.FAILED
import dev.handoff.core.handoff.TransferPhase.IDLE
import dev.handoff.core.handoff.TransferPhase.REQUESTING_RELEASE
import dev.handoff.core.handoff.TransferPhase.RESOLVING_OWNER
import dev.handoff.core.handoff.TransferPhase.RETRYING
import dev.handoff.core.handoff.TransferPhase.VERIFYING
import dev.handoff.core.handoff.TransferPhase.WAITING_RELEASE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateMachineTest {

    private fun run(vararg phases: TransferPhase): TransferStateMachine =
        TransferStateMachine().apply { phases.forEach(::moveTo) }

    @Test
    fun `coordinated path is legal`() {
        val m = run(RESOLVING_OWNER, REQUESTING_RELEASE, WAITING_RELEASE, CONNECTING, VERIFYING, COMPLETE)
        assertEquals(COMPLETE, m.phase)
        assertEquals(7, m.history.size)
    }

    @Test
    fun `timeout to takeover with one retry is legal`() {
        val m = run(
            RESOLVING_OWNER, REQUESTING_RELEASE, WAITING_RELEASE, DIRECT_TAKEOVER,
            CONNECTING, VERIFYING, RETRYING, CONNECTING, VERIFYING, COMPLETE,
        )
        assertEquals(COMPLETE, m.phase)
    }

    @Test
    fun `already connected completes straight from resolution`() {
        assertEquals(COMPLETE, run(RESOLVING_OWNER, COMPLETE).phase)
    }

    @Test
    fun `every active phase can fail`() {
        TransferPhase.entries.filter { it != IDLE && !it.isTerminal }.forEach { phase ->
            assertTrue("$phase -> FAILED", FAILED in TransferStateMachine.TRANSITIONS.getValue(phase))
        }
    }

    @Test
    fun `terminal phases have no exits`() {
        assertTrue(TransferStateMachine.TRANSITIONS.getValue(COMPLETE).isEmpty())
        assertTrue(TransferStateMachine.TRANSITIONS.getValue(FAILED).isEmpty())
    }

    @Test(expected = IllegalTransitionException::class)
    fun `cannot connect before resolving owner`() {
        run(CONNECTING)
    }

    @Test(expected = IllegalTransitionException::class)
    fun `cannot complete without verification`() {
        run(RESOLVING_OWNER, REQUESTING_RELEASE, WAITING_RELEASE, CONNECTING, COMPLETE)
    }

    @Test(expected = IllegalTransitionException::class)
    fun `cannot retry forever from a terminal state`() {
        run(RESOLVING_OWNER, FAILED, RETRYING)
    }

    @Test
    fun `transitions are reported to the listener`() {
        val seen = mutableListOf<Pair<TransferPhase, TransferPhase>>()
        val m = TransferStateMachine { from, to -> seen += from to to }
        m.moveTo(RESOLVING_OWNER)
        m.moveTo(DIRECT_TAKEOVER)
        assertEquals(listOf(IDLE to RESOLVING_OWNER, RESOLVING_OWNER to DIRECT_TAKEOVER), seen)
        assertFalse(m.canMoveTo(COMPLETE))
    }
}
