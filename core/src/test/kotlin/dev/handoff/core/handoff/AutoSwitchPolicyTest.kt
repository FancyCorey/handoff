package dev.handoff.core.handoff

import dev.handoff.core.handoff.AutoSwitchPolicy.Decision
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.Ownership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSwitchPolicyTest {
    private var now = 1_000_000L
    private val policy = AutoSwitchPolicy(cooldownMs = 30_000, recentReleaseMs = 60_000, clock = { now })
    private val remote = Ownership.Peer(PeerId("tab"))

    private fun decide(mode: AutoSwitchMode, ownership: Ownership = remote, local: Boolean = false, released: Long? = null) =
        policy.onPlaybackStarted(mode, hasPreferredDevice = true, localConnected = local, ownership = ownership, lastReleaseToPeerMs = released)

    @Test
    fun `off never acts`() {
        assertTrue(decide(AutoSwitchMode.OFF) is Decision.Ignore)
    }

    @Test
    fun `ask prompts and auto switches when the headset is elsewhere`() {
        assertEquals(Decision.Ask, decide(AutoSwitchMode.ASK))
        now += 31_000
        assertEquals(Decision.Switch, decide(AutoSwitchMode.AUTO))
    }

    @Test
    fun `nothing happens when already connected here`() {
        assertTrue(decide(AutoSwitchMode.AUTO, ownership = Ownership.Local) is Decision.Ignore)
        assertTrue(decide(AutoSwitchMode.AUTO, local = true) is Decision.Ignore)
    }

    @Test
    fun `cooldown suppresses repeated triggers`() {
        assertEquals(Decision.Switch, decide(AutoSwitchMode.AUTO))
        now += 5_000
        assertEquals(Decision.Ignore("cooldown"), decide(AutoSwitchMode.AUTO))
    }

    @Test
    fun `recently handing the headset to a peer prevents grabbing it back`() {
        assertEquals(Decision.Ignore("recently handed to a peer"), decide(AutoSwitchMode.AUTO, released = now - 10_000))
        assertEquals(Decision.Switch, decide(AutoSwitchMode.AUTO, released = now - 61_000))
    }

    @Test
    fun `multipoint needs a human decision`() {
        val multi = Ownership.Multipoint(setOf(PeerId("a"), PeerId("b")))
        assertTrue(decide(AutoSwitchMode.AUTO, ownership = multi) is Decision.Ignore)
    }
}
