package dev.handoff.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffTextsTest {
    private val self = "This device"

    @Test
    fun `where a headset is reads naturally`() {
        assertEquals("Connected here", HandoffTexts.whereConnected(listOf(self), self))
        assertEquals("Here and on Tab", HandoffTexts.whereConnected(listOf(self, "Tab"), self))
        assertEquals("On Tab", HandoffTexts.whereConnected(listOf("Tab"), self))
        assertEquals("On Tab and Studio PC", HandoffTexts.whereConnected(listOf("Tab", "Studio PC"), self))
        assertEquals("Here and on Tab, Phone and Studio PC", HandoffTexts.whereConnected(listOf(self, "Tab", "Phone", "Studio PC"), self))
        assertEquals("On another device", HandoffTexts.whereConnected(emptyList(), self))
    }
}
