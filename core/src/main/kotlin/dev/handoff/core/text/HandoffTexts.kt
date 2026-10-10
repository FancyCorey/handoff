package dev.handoff.core.text

import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferPath
import dev.handoff.core.handoff.TransferStep

/**
 * Every message the Android and Windows apps show about headphones, moves and errors, so both say
 * the same thing. The wording is agreed in docs/COPY.md and follows the rules in docs/DESIGN.md
 * ("headphones" and "them", "move" never "switch", short statuses, errors with one fix).
 *
 * Sentences are whole strings with the names inserted, never built from pieces, so they can be
 * translated later.
 */
object HandoffTexts {
    /**
     * How this device calls itself: "This phone", "This tablet" or "This PC". Set once at start-up
     * by each app; holder lists use the same label.
     */
    @Volatile
    var me: String = "This device"

    /** [me] in the middle of a sentence: "this phone", "this PC". */
    val meInSentence: String get() = me.replaceFirstChar { it.lowercase() }

    private fun peer(name: String?) = name ?: "The other device"

    // ---- Moves ---------------------------------------------------------------------------------

    fun step(step: TransferStep): String {
        val peer = peer(step.peerName)
        return when (step.kind) {
            StepKind.REQUESTING_RELEASE -> "Asking $peer to let go…"
            StepKind.RELEASED -> "$peer let go"
            StepKind.PEER_NOT_CONNECTED -> "$peer didn't have them"
            StepKind.PEER_UNREACHABLE, StepKind.RELEASE_TIMEOUT -> "$peer didn't answer"
            StepKind.RELEASE_REFUSED -> "$peer couldn't let go"
            StepKind.PEER_BUSY -> "$peer is moving them elsewhere"
            StepKind.DIRECT_TAKEOVER -> "Connecting directly…"
            StepKind.CONNECTING -> "Connecting…"
            StepKind.RETRYING, StepKind.AUTO_RETRY -> "Trying again…"
            StepKind.VERIFYING -> "Making sure it worked…"
            StepKind.CONNECTED -> "Connected"
            StepKind.ALREADY_CONNECTED -> "Already here"
            StepKind.CANCELLED -> "Cancelled"
            StepKind.FAILED -> "Couldn't connect"
        }
    }

    /** One-line headline for any result. */
    fun result(result: HandoffResult): String = when (result) {
        is HandoffResult.Success -> if (result.path == TransferPath.MULTIPOINT_JOIN) "Connected here too" else "Moved here"
        HandoffResult.AlreadyConnected -> "Already here"
        HandoffResult.InProgress -> "Already moving"
        HandoffResult.MissingLocalMapping -> failureTitle(FailureReason.MISSING_LOCAL_MAPPING, null)
        HandoffResult.Cancelled -> "Cancelled"
        is HandoffResult.Failed -> failureTitle(result.reason, result.detail)
    }

    /** One short reason or next step, for results that need it. */
    fun help(result: HandoffResult): String? = when (result) {
        is HandoffResult.Failed -> failureHelp(result.reason)
        HandoffResult.Cancelled -> "If the other device already let go, tap Move here where you want them."
        else -> null
    }

    /** Longer advice behind "More", for the errors that have it. */
    fun more(result: HandoffResult): String? = (result as? HandoffResult.Failed)?.let { failureMore(it.reason, it.detail) }

    /** The one button that fixes a result, if any. */
    fun fix(result: HandoffResult): Fix? = when (result) {
        is HandoffResult.Failed -> fix(result.reason)
        HandoffResult.MissingLocalMapping -> Fix.ADD_HERE
        HandoffResult.Cancelled -> Fix.MOVE_HERE
        else -> null
    }

    /** What the button under an error does. Its words are [label]. */
    enum class Fix { TRY_AGAIN, MOVE_HERE, LINK_AGAIN, ADD_HERE, TURN_ON_BLUETOOTH, ALLOW, OPEN_BLUETOOTH_SETTINGS }

    fun label(fix: Fix): String = when (fix) {
        Fix.TRY_AGAIN -> "Try again"
        Fix.MOVE_HERE -> MOVE_HERE
        Fix.LINK_AGAIN -> "Link again"
        Fix.ADD_HERE -> ADD_HERE
        Fix.TURN_ON_BLUETOOTH -> TURN_ON_BLUETOOTH
        Fix.ALLOW -> "Allow"
        Fix.OPEN_BLUETOOTH_SETTINGS -> "Open Bluetooth settings"
    }

    fun fix(reason: FailureReason): Fix = when (reason) {
        FailureReason.PEER_NOT_LINKED -> Fix.LINK_AGAIN
        FailureReason.MISSING_LOCAL_MAPPING -> Fix.ADD_HERE
        FailureReason.BLUETOOTH_OFF -> Fix.TURN_ON_BLUETOOTH
        FailureReason.PERMISSION_DENIED -> Fix.ALLOW
        FailureReason.UNSUPPORTED, FailureReason.DEVICE_NOT_BONDED -> Fix.OPEN_BLUETOOTH_SETTINGS
        else -> Fix.TRY_AGAIN
    }

    fun failureTitle(reason: FailureReason, detail: String?): String = when (reason) {
        FailureReason.OWNER_UNREACHABLE -> "${peer(detail)} didn't answer"
        FailureReason.OWNER_REFUSED -> "The other device couldn't let go"
        FailureReason.PEER_NOT_LINKED -> "${peer(detail)} doesn't recognize $meInSentence"
        FailureReason.HEADSET_NOT_RESPONDING -> "The headphones didn't connect"
        FailureReason.MISSING_LOCAL_MAPPING -> notAddedHere()
        FailureReason.BLUETOOTH_OFF -> BLUETOOTH_OFF
        FailureReason.PERMISSION_DENIED -> "Handoff needs Bluetooth access"
        FailureReason.UNSUPPORTED -> "$me can't move headphones"
        FailureReason.DEVICE_NOT_BONDED -> "Not connected to $meInSentence yet"
        FailureReason.CONTENTION -> "Another device is moving them"
        FailureReason.RATE_LIMITED -> "Too many tries. Wait a minute."
        FailureReason.INTERNAL -> "Something went wrong"
    }

    /** The short reason under a failure title, or null when the title says enough. */
    fun failureHelp(reason: FailureReason): String? = when (reason) {
        FailureReason.OWNER_REFUSED -> "Disconnect them on the other device, then try again."
        FailureReason.HEADSET_NOT_RESPONDING -> "Check they're on, nearby and out of the case."
        FailureReason.PERMISSION_DENIED -> "Allow Nearby devices."
        FailureReason.DEVICE_NOT_BONDED -> "Connect them once in Bluetooth settings."
        else -> null
    }

    /** Longer advice behind "More". */
    fun failureMore(reason: FailureReason, detail: String?): String? = when (reason) {
        FailureReason.OWNER_UNREACHABLE -> "Open Handoff on ${detail ?: "the other device"}, or put both on the same Wi-Fi."
        FailureReason.PEER_NOT_LINKED ->
            "Unlink ${detail ?: "the other device"}, then link again. Remove any older copy of Handoff first."
        FailureReason.INTERNAL -> "If it keeps happening, share a report from Help and diagnostics."
        else -> null
    }

    /** Kept for callers that want a single sentence. */
    fun failure(reason: FailureReason): String = failureTitle(reason, null)

    /** Short label, used in Help and diagnostics. */
    fun compatibility(level: CompatibilityLevel): String = when (level) {
        CompatibilityLevel.SUPPORTED -> "Supported"
        CompatibilityLevel.EXPERIMENTAL -> "Experimental"
        CompatibilityLevel.UNSUPPORTED -> "Not supported"
    }

    /** The answer to "Can this phone move headphones?" during setup. */
    fun compatibilityAnswer(level: CompatibilityLevel): String = when (level) {
        CompatibilityLevel.SUPPORTED -> "Yes, it works."
        CompatibilityLevel.EXPERIMENTAL -> "Should work. Try your first move."
        CompatibilityLevel.UNSUPPORTED -> "No. Your other devices can still take them from here."
    }

    // ---- Where the headphones are ----------------------------------------------------------------

    /**
     * Where headphones are, from their holder names: "Here", "On Tab", "Here and on Tab",
     * "On Tab and Studio PC". [self] is how this device appears in [holders].
     */
    fun whereConnected(holders: List<String>, self: String): String {
        val others = holders.filter { it != self }
        val list = names(others)
        return when {
            others.size == holders.size -> if (list.isEmpty()) NOT_CONNECTED else "On $list"
            others.isEmpty() -> HERE
            else -> "Here and on $list"
        }
    }

    /** "Tab", "Tab and Studio PC", "Tab, Phone and Studio PC". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    const val HERE = "Here"
    const val NOT_CONNECTED = "Not connected"
    const val MOVING_HERE = "Moving here"
    const val BLUETOOTH_OFF = "Bluetooth is off"

    fun lastSeenOn(name: String) = "Last seen on $name"
    fun isOffline(name: String) = "$name is offline"
    fun notAddedHere() = "Not added on $meInSentence"

    /** Lines under the stage. The routine ones are shown only for the first few moves. */
    fun letsGo(name: String) = "$name lets go, then they connect here."
    const val ALL_LET_GO = "Both let go, then they connect here."
    fun interrupts(names: List<String>) = "Sounds on ${names(names)} can interrupt your audio."
    fun staysConnected(names: List<String>) =
        if (names.size == 1) "${names[0]} stays connected too." else "The others stay connected too."
    fun asksFirst(name: String) = "Handoff asks $name first."
    const val CONNECTS_DIRECTLY = "Connects directly instead."

    const val MOVE_HERE = "Move here"
    const val USE_ONLY_HERE = "Use only here"
    const val CONNECT_HERE_TOO = "Connect here too"
    const val TURN_ON_BLUETOOTH = "Turn on Bluetooth"
    const val ADD_HERE = "Add here"
    const val CANCEL = "Cancel"
    const val SHOW_PROGRESS = "Show progress"
    const val STARTING = "Starting…"
    const val CAN_LEAVE = "You can leave. The move keeps going."

    /** Read aloud for a whole stage: "Aurora Buds. On Studio PC. Battery 80 percent. Move here is available." */
    fun spoken(name: String, status: String, battery: Int?, action: String?): String =
        listOfNotNull(name, status, battery?.let { "Battery $it percent" }, action?.let { "$it is available" })
            .joinToString(". ", postfix = ".")

    // ---- Headphone settings ----------------------------------------------------------------------

    /** The option that lets headphones stay on several devices at once (multipoint). */
    const val STAY_CONNECTED_TITLE = "Stay connected to other devices"
    const val STAY_CONNECTED_HELP = "Leave off unless you want your headphones on several devices at once."

    /** Headphone type as people say it. */
    fun kind(kind: AudioDeviceKind): String = when (kind) {
        AudioDeviceKind.HEADPHONES -> "Headphones"
        AudioDeviceKind.HEADSET -> "Headset"
        AudioDeviceKind.SPEAKER -> "Speaker"
        AudioDeviceKind.CAR_AUDIO -> "Car audio"
        AudioDeviceKind.OTHER_AUDIO, AudioDeviceKind.UNKNOWN -> "Audio device"
    }

    /** Shown on a device chip instead of the online dot. */
    fun offline(probablyOtherNetwork: Boolean): String = if (probablyOtherNetwork) "Other Wi-Fi" else "Offline"

    /** The project's home: source code, releases and issues. */
    const val REPO_URL = "https://github.com/FancyCorey/handoff"

    // ---- Linking ---------------------------------------------------------------------------------

    /** Shown when approving a new link. */
    const val LINK_WARNING = "Only link your own devices."

    fun linkQuestion(name: String) = "Link $name?"
    fun linkCheck(name: String) = "Does $name show this number?"
    fun linkedWith(name: String) = "Linked with $name"
    const val LINKED_ONCE = "You won't need to do this again."

    // ---- Tips --------------------------------------------------------------------------------------

    const val DIRECT_TITLE = "No shared Wi-Fi?"

    /** How to connect two devices directly, with no router and no internet involved. */
    const val DIRECT_HELP = "Turn on the hotspot on one device and join it from the other."
}
