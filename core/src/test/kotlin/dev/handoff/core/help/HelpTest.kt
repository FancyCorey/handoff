package dev.handoff.core.help

import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.Platforms
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.handoff.TransferTimings
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.text.HandoffTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HelpTest {
    private val now = 10 * 3_600_000L
    private val s22 = PeerId("s22")
    private val pc = PeerId("pc")

    private fun peer(id: PeerId, name: String, platform: String, online: Boolean, agoMs: Long = 2 * 3_600_000L, otherWifi: Boolean = false) =
        PeerOverview(TrustedPeer(id, name, ByteArray(0), 0), online, now - agoMs, platform, otherWifi)

    private fun move(failure: FailureReason?, owner: PeerId? = s22, detail: String? = "Galaxy S22") = TransferRecord(
        LogicalDeviceId("buds"), "Aurora Buds", TransferTrigger.MANUAL, now - 60_000,
        if (failure == null) "SUCCESS" else "FAILED", null, failure, detail, owner, null, 2, TransferTimings(null, null, 9_000),
    )

    private fun input(
        last: TransferRecord?,
        peers: List<PeerOverview>,
        adapter: AdapterState = AdapterState.ON,
        staysReachable: Boolean? = true,
    ) = HelpInput(last, adapter, CompatibilityLevel.SUPPORTED, listOf("Aurora Buds" to true), peers, onNetwork = true, staysReachable, now)

    @Before
    fun me() {
        HandoffTexts.me = "This tablet"
    }

    @Test
    fun `a phone that didn't answer gets the open Handoff and Stay reachable advice`() {
        val help = HelpBuilder.build(input(move(FailureReason.OWNER_UNREACHABLE), listOf(peer(s22, "Galaxy S22", Platforms.ANDROID_PHONE, online = false, otherWifi = true))))
        assertEquals("Aurora Buds didn't move", help.headline.title)
        assertEquals("Galaxy S22 didn't answer, so they're probably still connected to it.", help.headline.reason)
        assertEquals(listOf("On Galaxy S22, open Handoff", "Connect both to the same Wi-Fi"), help.steps.map { it.title })
        assertTrue(help.steps[0].detail!!.contains("Stay reachable"))
        assertEquals("Handoff on Galaxy S22 isn't answering", help.problems.first().title)
        assertEquals("Last answer: 2 hours ago, on a different Wi-Fi.", help.problems.first().detail)
        assertTrue(help.tryAgain)
        assertNull(help.tip)
    }

    @Test
    fun `the Wi-Fi step only appears when the other device was on another network`() {
        val help = HelpBuilder.build(input(move(FailureReason.OWNER_UNREACHABLE), listOf(peer(s22, "Galaxy S22", Platforms.ANDROID_PHONE, online = false))))
        assertEquals(listOf("On Galaxy S22, open Handoff"), help.steps.map { it.title })
    }

    @Test
    fun `a PC gets PC advice`() {
        val help = HelpBuilder.build(input(move(FailureReason.OWNER_UNREACHABLE, owner = pc, detail = "Studio PC"), listOf(peer(pc, "Studio PC", Platforms.WINDOWS, online = false))))
        assertEquals("On Studio PC, start Handoff", help.steps[0].title)
        assertTrue(help.steps.any { it.title.contains("network access") })
    }

    @Test
    fun `a device that answers again says so`() {
        val help = HelpBuilder.build(input(move(FailureReason.OWNER_UNREACHABLE), listOf(peer(s22, "Galaxy S22", Platforms.ANDROID_PHONE, online = true))))
        assertEquals(listOf("Galaxy S22 is answering now"), help.steps.map { it.title })
        assertTrue(help.problems.isEmpty())
    }

    @Test
    fun `bluetooth off is fixed here`() {
        val help = HelpBuilder.build(input(move(FailureReason.BLUETOOTH_OFF, owner = null, detail = null), emptyList(), adapter = AdapterState.OFF))
        assertEquals(HelpAction.TURN_ON_BLUETOOTH, help.steps.single().action)
        assertEquals("Bluetooth is off", help.problems.single().title)
    }

    @Test
    fun `nothing wrong offers the Stay reachable tip`() {
        val help = HelpBuilder.build(input(move(null), listOf(peer(s22, "Galaxy S22", Platforms.ANDROID_PHONE, online = true)), staysReachable = false))
        assertEquals("No problems found", help.headline.title)
        assertFalse(help.headline.failed)
        assertTrue(help.steps.isEmpty() && help.problems.isEmpty())
        assertEquals(HelpAction.STAY_REACHABLE, help.tip!!.action)
        assertTrue("Aurora Buds are set up in this tablet's Bluetooth" in help.fine.map { it.title })
    }

    @Test
    fun `time since the last answer reads naturally`() {
        assertEquals("just now", HelpBuilder.ago(20_000))
        assertEquals("3 minutes ago", HelpBuilder.ago(3 * 60_000))
        assertEquals("2 hours ago", HelpBuilder.ago(2 * 3_600_000))
        assertEquals("3 days ago", HelpBuilder.ago(3 * 24 * 3_600_000L))
    }
}
