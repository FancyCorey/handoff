package dev.handoff.core.text

import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTextsTest {
    private val self = "This phone"

    @Test
    fun `where headphones are reads naturally`() {
        assertEquals("Here", HandoffTexts.whereConnected(listOf(self), self))
        assertEquals("Here and on Tab", HandoffTexts.whereConnected(listOf(self, "Tab"), self))
        assertEquals("On Tab", HandoffTexts.whereConnected(listOf("Tab"), self))
        assertEquals("On Tab and Studio PC", HandoffTexts.whereConnected(listOf("Tab", "Studio PC"), self))
        assertEquals("Here and on Tab, Phone and Studio PC", HandoffTexts.whereConnected(listOf(self, "Tab", "Phone", "Studio PC"), self))
        assertEquals("Not connected", HandoffTexts.whereConnected(emptyList(), self))
    }

    @Test
    fun `errors name this device the way it calls itself`() {
        HandoffTexts.me = "This PC"
        try {
            assertEquals("Not added on this PC", HandoffTexts.result(HandoffResult.MissingLocalMapping))
            assertEquals("This PC can't move headphones", HandoffTexts.failure(FailureReason.UNSUPPORTED))
            assertEquals("Studio PC doesn't recognize this PC", HandoffTexts.failureTitle(FailureReason.PEER_NOT_LINKED, "Studio PC"))
        } finally {
            HandoffTexts.me = "This device"
        }
    }

    @Test
    fun `every error has one fix and short words`() {
        for (reason in FailureReason.entries) {
            val failed = HandoffResult.Failed(reason, "Studio PC")
            assertNotNull(HandoffTexts.fix(failed))
            assertTrue("$reason title", HandoffTexts.result(failed).split(' ').size <= 8)
            HandoffTexts.help(failed)?.let { assertTrue("$reason help", it.split(' ').size <= 12) }
        }
        val unreachable = HandoffResult.Failed(FailureReason.OWNER_UNREACHABLE, "Studio PC")
        assertNull(HandoffTexts.help(unreachable))
        assertEquals("Open Handoff on Studio PC, or put both on the same Wi-Fi.", HandoffTexts.more(unreachable))
    }

    @Test
    fun `steps are short`() {
        assertEquals("Studio PC let go", HandoffTexts.step(TransferStep(StepKind.RELEASED, "Studio PC")))
        assertEquals("Asking Studio PC to let go…", HandoffTexts.step(TransferStep(StepKind.REQUESTING_RELEASE, "Studio PC")))
    }
}
