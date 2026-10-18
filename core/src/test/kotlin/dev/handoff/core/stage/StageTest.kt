package dev.handoff.core.stage

import dev.handoff.core.handoff.Platforms
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.text.HandoffTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StageTest {
    private val self = PeerId("self")
    private val pc = PeerId("pc")
    private val tab = PeerId("tab")

    private fun peer(id: PeerId, name: String, online: Boolean = true, platform: String = Platforms.WINDOWS) =
        PeerOverview(TrustedPeer(id, name, ByteArray(0), 0), online, null, platform)

    private val studio = peer(pc, "Studio PC")
    private val tablet = peer(tab, "Tab", platform = Platforms.ANDROID_TABLET)

    private fun overview(
        ownership: Ownership,
        state: AudioConnectionState = AudioConnectionState.DISCONNECTED,
        multipoint: Boolean = false,
        mapped: Boolean = true,
        battery: Int? = 80,
    ) = DeviceOverview(
        device = LogicalAudioDevice(
            LogicalDeviceId("buds"), "Aurora Buds", AudioDeviceKind.HEADPHONES, null,
            BluetoothDeviceId("0A:DE:40:00:00:01").takeIf { mapped }, multipoint = multipoint,
        ),
        localState = state,
        ownership = ownership,
        holders = emptyList(),
        transfer = null,
        batteryPercent = battery,
    )

    private fun build(o: DeviceOverview, peers: List<PeerOverview> = listOf(studio), moves: Int = 0) =
        StageBuilder.build(o, peers, self, Platforms.ANDROID_PHONE, moves)

    @Before
    fun me() {
        HandoffTexts.me = "This phone"
    }

    @Test
    fun `on another device it can move here along a dashed arc`() {
        val s = build(overview(Ownership.Peer(pc)))
        assertEquals("On Studio PC", s.status)
        assertEquals(Arc.DASHED, s.arc)
        assertEquals(StageAction.MOVE_HERE, s.action)
        assertEquals("Studio PC lets go, then they connect here.", s.line)
        assertEquals(listOf(Mark.HOLDS, Mark.TARGET), s.tiles.map { it.mark })
        assertTrue(s.tiles.first().arcFrom)
        assertEquals("This phone", s.tiles.last().name)
        assertEquals("Aurora Buds. On Studio PC. Battery 80 percent. Move here is available.", s.spoken)
    }

    @Test
    fun `routine line disappears after three moves`() {
        assertNull(build(overview(Ownership.Peer(pc)), moves = 3).line)
    }

    @Test
    fun `here only is compact with nothing to do`() {
        val s = build(overview(Ownership.Local, AudioConnectionState.CONNECTED))
        assertEquals("Here", s.status)
        assertEquals(Tone.POSITIVE, s.statusTone)
        assertTrue(s.compact)
        assertEquals(StageAction.NONE, s.action)
        assertEquals(Mark.HOLDS, s.tiles.last().mark)
    }

    @Test
    fun `here and elsewhere warns and offers use only here`() {
        val s = build(overview(Ownership.Conflict(setOf(self, tab)), AudioConnectionState.CONNECTED), listOf(studio, tablet))
        assertEquals("Here and on Tab", s.status)
        assertEquals(Tone.WARNING, s.statusTone)
        assertEquals("Sounds on Tab can interrupt your audio.", s.line)
        assertEquals(StageAction.USE_ONLY_HERE, s.action)
    }

    @Test
    fun `two others converge when stay connected is off`() {
        val s = build(overview(Ownership.Conflict(setOf(pc, tab))), listOf(studio, tablet))
        assertEquals("On Studio PC and Tab", s.status)
        assertEquals("Both let go, then they connect here.", s.line)
        assertEquals(2, s.tiles.count { it.arcFrom })
    }

    @Test
    fun `stay connected joins instead of moving`() {
        val s = build(overview(Ownership.Multipoint(setOf(pc, tab)), multipoint = true), listOf(studio, tablet))
        assertEquals(StageAction.CONNECT_HERE_TOO, s.action)
        assertEquals(Mark.JOIN, s.tiles.last().mark)
        assertEquals(Arc.NONE, s.arc)
        assertEquals("The others stay connected too.", s.line)
    }

    @Test
    fun `unknown location is a question mark`() {
        val s = build(overview(Ownership.Unknown(pc)))
        assertEquals("Last seen on Studio PC", s.status)
        assertEquals("Handoff asks Studio PC first.", s.line)
        assertEquals(Mark.MAYBE, s.tiles.first().mark)
    }

    @Test
    fun `offline holder is dashed and connects directly`() {
        val s = build(overview(Ownership.Unknown(pc)), listOf(peer(pc, "Studio PC", online = false)))
        assertEquals("Studio PC is offline", s.status)
        assertEquals(Tone.WARNING, s.statusTone)
        assertEquals("If they're still on Studio PC, open Handoff there first.", s.line)
        assertTrue(s.tiles.first().offline)
    }

    @Test
    fun `a holder on another network says so`() {
        val s = build(overview(Ownership.Unknown(pc)), listOf(PeerOverview(TrustedPeer(pc, "Studio PC", ByteArray(0), 0), false, 0, Platforms.WINDOWS, probablyOtherNetwork = true)))
        assertEquals("Studio PC is on a different Wi-Fi", s.status)
        assertEquals("Connect both to the same Wi-Fi, or use a hotspot.", s.line)
        assertEquals(Tone.WARNING, s.lineTone)
    }

    @Test
    fun `no Wi-Fi here is named on the card`() {
        val s = StageBuilder.build(overview(Ownership.Unknown(pc)), listOf(peer(pc, "Studio PC", online = false)), self, Platforms.ANDROID_PHONE, 0, onNetwork = false)
        assertEquals("This phone isn't on Wi-Fi", s.status)
    }

    @Test
    fun `bluetooth off says where they were last and offers nothing`() {
        val s = build(overview(Ownership.Unknown(pc), AudioConnectionState.UNAVAILABLE))
        assertTrue(s.dimmed)
        assertEquals("Last seen on Studio PC", s.status)
        assertEquals(StageAction.NONE, s.action)
        assertEquals("Aurora Buds. Last seen on Studio PC.", s.spoken)
        assertEquals("Bluetooth is off", build(overview(Ownership.Unknown(null), AudioConnectionState.UNAVAILABLE)).status)
    }

    @Test
    fun `not added here offers add here`() {
        val s = build(overview(Ownership.Peer(pc), mapped = false))
        assertEquals("Not added on this phone", s.status)
        assertEquals(StageAction.ADD_HERE, s.action)
    }

    @Test
    fun `devices with no part fold into more`() {
        val extra = listOf(studio, tablet, peer(PeerId("a"), "Laptop"), peer(PeerId("b"), "Phone 2"))
        val s = build(overview(Ownership.Peer(pc)), extra)
        assertEquals(listOf("Studio PC", "More", "This phone"), s.tiles.map { it.name })
        assertEquals(3, s.tiles[1].folded)
        assertFalse(s.tiles[1].isThis)
    }

    @Test
    fun `three devices all fit`() {
        val s = build(overview(Ownership.Peer(pc)), listOf(studio, tablet))
        assertEquals(listOf("Studio PC", "Tab", "This phone"), s.tiles.map { it.name })
    }
}
