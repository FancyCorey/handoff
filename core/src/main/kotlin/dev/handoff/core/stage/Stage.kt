package dev.handoff.core.stage

import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.PeerId
import dev.handoff.core.overview.DeviceOverview
import dev.handoff.core.overview.PeerOverview
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.text.HandoffTexts

/**
 * The "stage" from docs/DESIGN.md: where a pair of headphones is, drawn as device tiles with the
 * headphones as a dot above the device that has them, and an arc towards this device when they
 * can move here. Both apps draw it from this one description, so they always agree.
 */
data class Stage(
    /** Left to right; this device is always last. */
    val tiles: List<StageTile>,
    /** How the arcs from [StageTile.arcFrom] tiles to this device are drawn. */
    val arc: Arc,
    /** Nothing to do: a short row with no arc, line or button. */
    val compact: Boolean,
    /** Bluetooth is off here: the status says where the headphones were last, and there is no action. */
    val dimmed: Boolean,
    val status: String,
    val statusTone: Tone,
    /** The one line under the stage, or null. */
    val line: String?,
    val lineTone: Tone,
    val action: StageAction,
    /** The stage as one sentence for screen readers. */
    val spoken: String,
)

data class StageTile(
    val name: String,
    /** [dev.handoff.core.handoff.Platforms] value, or null when unknown. */
    val platform: String?,
    val isThis: Boolean,
    /** Can't be reached now: drawn dashed and labelled "Offline". */
    val offline: Boolean,
    val mark: Mark,
    /** An arc starts above this tile. */
    val arcFrom: Boolean = false,
    /** A "+N" tile standing in for this many devices that play no part. */
    val folded: Int = 0,
)

/** What is drawn above a tile. */
enum class Mark {
    NONE,

    /** The headphones are here (solid dot). */
    HOLDS,

    /** Probably here, but Handoff can't know (dashed dot with "?"). */
    MAYBE,

    /** The empty spot an arc ends on (dashed circle). Only above this device. */
    TARGET,

    /** This device would join the others (circle with a plus). Only above this device. */
    JOIN,
}

enum class Arc { NONE, DASHED, SOLID }

enum class Tone {
    NORMAL,

    /** Elsewhere, and can move here. */
    ACCENT,

    /** Here. */
    POSITIVE,
    WARNING,
}

enum class StageAction(val label: String?) {
    NONE(null),
    MOVE_HERE(HandoffTexts.MOVE_HERE),
    USE_ONLY_HERE(HandoffTexts.USE_ONLY_HERE),
    CONNECT_HERE_TOO(HandoffTexts.CONNECT_HERE_TOO),
    TURN_ON_BLUETOOTH(HandoffTexts.TURN_ON_BLUETOOTH),
    ADD_HERE(HandoffTexts.ADD_HERE),
    CANCEL(HandoffTexts.CANCEL),
}

object StageBuilder {
    /** Routine explanations under the stage stop after this many successful moves. */
    const val LEARNING_MOVES = 3

    /** More devices than this and the ones that play no part fold into a "+N" tile. */
    private const val MAX_TILES = 3

    /**
     * Builds the stage for [o]. [self] is this device's peer id, [thisPlatform] its platform,
     * [peers] the linked devices, and [movesDone] how many moves have succeeded here so far.
     */
    fun build(
        o: DeviceOverview,
        peers: List<PeerOverview>,
        self: PeerId,
        thisPlatform: String,
        movesDone: Int,
    ): Stage {
        val learning = movesDone < LEARNING_MOVES
        val byId = peers.associateBy { it.peer.peerId }
        val name = { id: PeerId -> byId[id]?.peer?.displayName ?: "Another device" }
        val online = { id: PeerId -> byId[id]?.online == true }
        val owner = o.ownership

        // Which other devices have the headphones, and how sure we are.
        val holders: Set<PeerId> = when (owner) {
            is Ownership.Peer -> setOf(owner.peerId)
            is Ownership.Multipoint -> owner.holders - self
            is Ownership.Conflict -> owner.holders - self
            else -> emptySet()
        }
        val lastSeen = (owner as? Ownership.Unknown)?.lastKnownOwner?.takeIf { it != self && it in byId }
        val hereToo = owner is Ownership.Local || (owner is Ownership.Multipoint && self in owner.holders) ||
            (owner is Ownership.Conflict && self in owner.holders) || o.connectedHere
        val running = o.transferRunning
        val source = o.transfer?.ownerPeer?.takeIf { running && it != self }

        fun state(): Draft = when {
            running -> {
                val from = source?.let(::setOf) ?: holders
                Draft(
                    HandoffTexts.MOVING_HERE, Tone.ACCENT,
                    o.transfer?.steps?.lastOrNull()?.let(HandoffTexts::step) ?: HandoffTexts.STARTING, Tone.NORMAL,
                    StageAction.CANCEL, Arc.SOLID, marks = from.associateWith { Mark.HOLDS }, arcFrom = from, target = Mark.TARGET,
                )
            }
            o.device.localDeviceId == null -> Draft(
                HandoffTexts.notAddedHere(), Tone.WARNING, null, Tone.NORMAL, StageAction.ADD_HERE, Arc.NONE,
                marks = holders.associateWith { Mark.HOLDS },
            )
            // Bluetooth is off here: one banner says so for all headphones; each says where it was last.
            o.localState == AudioConnectionState.UNAVAILABLE -> {
                val last = o.device.lastKnownOwner ?: (owner as? Ownership.Unknown)?.lastKnownOwner
                val status = when {
                    last == null -> HandoffTexts.BLUETOOTH_OFF
                    last == self -> HandoffTexts.LAST_SEEN_HERE
                    else -> HandoffTexts.lastSeenOn(name(last))
                }
                Draft(status, Tone.NORMAL, null, Tone.NORMAL, StageAction.NONE, Arc.NONE, dimmed = true)
            }
            hereToo && holders.isEmpty() -> Draft(HandoffTexts.HERE, Tone.POSITIVE, null, Tone.NORMAL, StageAction.NONE, Arc.NONE, here = Mark.HOLDS, compact = true)
            hereToo -> {
                val names = holders.map(name)
                val status = "Here and on ${HandoffTexts.names(names)}"
                if (o.device.multipoint) {
                    Draft(status, Tone.POSITIVE, null, Tone.NORMAL, StageAction.NONE, Arc.NONE, marks = holders.associateWith { Mark.HOLDS }, here = Mark.HOLDS)
                } else {
                    Draft(
                        status, Tone.WARNING, HandoffTexts.interrupts(names), Tone.WARNING, StageAction.USE_ONLY_HERE, Arc.NONE,
                        marks = holders.associateWith { Mark.HOLDS }, here = Mark.HOLDS,
                    )
                }
            }
            holders.size == 1 && !online(holders.first()) -> offline(holders.first(), name)
            holders.isNotEmpty() -> {
                val names = holders.map(name)
                val status = "On ${HandoffTexts.names(names)}"
                if (o.device.multipoint) {
                    Draft(
                        status, Tone.ACCENT, HandoffTexts.staysConnected(names), Tone.NORMAL, StageAction.CONNECT_HERE_TOO, Arc.NONE,
                        marks = holders.associateWith { Mark.HOLDS }, target = Mark.JOIN,
                    )
                } else {
                    val line = if (!learning) null else if (names.size == 1) HandoffTexts.letsGo(names[0]) else HandoffTexts.ALL_LET_GO
                    Draft(
                        status, Tone.ACCENT, line, Tone.NORMAL, StageAction.MOVE_HERE, Arc.DASHED,
                        marks = holders.associateWith { Mark.HOLDS }, arcFrom = holders, target = Mark.TARGET,
                    )
                }
            }
            lastSeen != null && !online(lastSeen) -> offline(lastSeen, name)
            lastSeen != null -> Draft(
                HandoffTexts.lastSeenOn(name(lastSeen)), Tone.NORMAL, HandoffTexts.asksFirst(name(lastSeen)), Tone.NORMAL,
                StageAction.MOVE_HERE, Arc.DASHED, marks = mapOf(lastSeen to Mark.MAYBE), arcFrom = setOf(lastSeen), target = Mark.TARGET,
            )
            else -> Draft(HandoffTexts.NOT_CONNECTED, Tone.NORMAL, null, Tone.NORMAL, StageAction.MOVE_HERE, Arc.NONE, target = Mark.TARGET)
        }

        val d = state()
        val tiles = tiles(peers, d, self = HandoffTexts.me, thisPlatform)
        val spokenAction = d.action.label?.takeIf { d.action != StageAction.CANCEL }
        return Stage(
            tiles = tiles,
            arc = if (d.arcFrom.isEmpty()) Arc.NONE else d.arc,
            compact = d.compact,
            dimmed = d.dimmed,
            status = d.status,
            statusTone = d.statusTone,
            line = d.line,
            lineTone = d.lineTone,
            action = d.action,
            spoken = HandoffTexts.spoken(o.device.displayName, d.status, o.batteryPercent.takeUnless { d.dimmed }, spokenAction),
        )
    }

    private fun offline(id: PeerId, name: (PeerId) -> String) = Draft(
        HandoffTexts.isOffline(name(id)), Tone.WARNING, HandoffTexts.ifStillThere(name(id)), Tone.NORMAL, StageAction.MOVE_HERE, Arc.DASHED,
        marks = mapOf(id to Mark.MAYBE), arcFrom = setOf(id), target = Mark.TARGET,
    )

    /** Other devices first (those with a part to play, then the rest), this device last. */
    private fun tiles(peers: List<PeerOverview>, d: Draft, self: String, thisPlatform: String): List<StageTile> {
        val involved = peers.filter { it.peer.peerId in d.marks || it.peer.peerId in d.arcFrom }
        val rest = peers - involved.toSet()
        val room = (MAX_TILES - 1 - involved.size).coerceAtLeast(0)
        // Fold the uninvolved devices into "+N" only when they don't all fit.
        val shown = if (rest.size <= room) involved + rest else involved + rest.take((room - 1).coerceAtLeast(0))
        val folded = rest.size - (shown.size - involved.size)
        val others = shown.map {
            StageTile(
                name = it.peer.displayName,
                platform = it.platform,
                isThis = false,
                offline = !it.online,
                mark = d.marks[it.peer.peerId] ?: Mark.NONE,
                arcFrom = it.peer.peerId in d.arcFrom,
            )
        }
        val more = if (folded > 0) listOf(StageTile("More", null, isThis = false, offline = false, mark = Mark.NONE, folded = folded)) else emptyList()
        val here = StageTile(self, thisPlatform, isThis = true, offline = false, mark = d.here ?: d.target ?: Mark.NONE)
        return others + more + here
    }

    private data class Draft(
        val status: String,
        val statusTone: Tone,
        val line: String?,
        val lineTone: Tone,
        val action: StageAction,
        val arc: Arc,
        val marks: Map<PeerId, Mark> = emptyMap(),
        val arcFrom: Set<PeerId> = emptySet(),
        val target: Mark? = null,
        val here: Mark? = null,
        val compact: Boolean = false,
        val dimmed: Boolean = false,
    )
}
