package dev.handoff.core.handoff

import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

enum class TransferTrigger { MANUAL, QUICK_SETTINGS_TILE, NOTIFICATION, AUTOMATIC }

enum class TransferPath {
    /** The previous owner released on request, then this host connected. */
    COORDINATED,

    /** This host connected without a confirmed release (owner offline/unknown/unresponsive). */
    DIRECT_TAKEOVER,

    /** Nobody held the headset. */
    UNCONTESTED,

    /** Multipoint headset: joined without disconnecting other hosts. */
    MULTIPOINT_JOIN,
}

enum class FailureReason {
    MISSING_LOCAL_MAPPING,
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    UNSUPPORTED,
    DEVICE_NOT_BONDED,

    /**
     * The device holding the headset could not be reached (different Wi-Fi, asleep, Handoff not
     * running, firewall) and connecting directly did not work either. detail = its name.
     */
    OWNER_UNREACHABLE,

    /** The holding device answered but could not let go, and taking over did not work. */
    OWNER_REFUSED,

    /** Nobody else held the headset (or it was released) but it did not connect: off, out of range, busy. */
    HEADSET_NOT_RESPONDING,

    /** Another host is taking the headset at the same time. */
    CONTENTION,
    RATE_LIMITED,
    INTERNAL,
}

data class TransferTimings(
    val releaseMs: Long?,
    val connectMs: Long?,
    val totalMs: Long,
)

sealed interface HandoffResult {
    data class Success(
        val path: TransferPath,
        val timings: TransferTimings,
        val strategy: String?,
        val attempts: Int,
    ) : HandoffResult

    data object AlreadyConnected : HandoffResult

    /** A transfer for this headset is already running; the request was not queued. */
    data object InProgress : HandoffResult

    data object MissingLocalMapping : HandoffResult

    /** The user stopped the transfer. */
    data object Cancelled : HandoffResult

    data class Failed(val reason: FailureReason, val detail: String?) : HandoffResult
}

/** User-visible progress lines. Mapped to localized strings by the UI. */
enum class StepKind {
    REQUESTING_RELEASE,
    RELEASED,
    PEER_NOT_CONNECTED,
    PEER_UNREACHABLE,
    RELEASE_TIMEOUT,
    RELEASE_REFUSED,
    PEER_BUSY,
    DIRECT_TAKEOVER,
    CONNECTING,
    RETRYING,
    VERIFYING,
    CONNECTED,
    ALREADY_CONNECTED,

    /** The whole transfer is being tried once more after a short pause. */
    AUTO_RETRY,
    CANCELLED,
    FAILED,
}

data class TransferStep(val kind: StepKind, val peerName: String? = null, val detail: String? = null)

/** Observable state of one transfer; the UI renders this. */
data class HandoffState(
    val logicalId: LogicalDeviceId,
    val deviceName: String,
    val trigger: TransferTrigger,
    val phase: TransferPhase,
    /** Every phase this transfer went through, in order (starts with IDLE). */
    val phases: List<TransferPhase>,
    val steps: List<TransferStep>,
    val attempt: Int,
    val takeover: Boolean,
    val ownerPeer: PeerId?,
    val result: HandoffResult?,
    val startedAtMs: Long,
)

data class TransferRecord(
    val logicalId: LogicalDeviceId,
    val deviceName: String,
    val trigger: TransferTrigger,
    val startedAtMs: Long,
    val outcome: String,
    val path: TransferPath?,
    val failure: FailureReason?,
    val detail: String?,
    val previousOwner: PeerId?,
    val strategy: String?,
    val attempts: Int,
    val timings: TransferTimings,
)

/**
 * Timeouts and retry limits. Every wait in a transfer is bounded by one of these.
 */
data class HandoffPolicy(
    val statusRefreshTimeoutMs: Long = 1_500,
    val reachabilityTimeoutMs: Long = 1_500,
    /** Must exceed the remote side's disconnect + verify budget. */
    val releaseTimeoutMs: Long = 9_000,
    /** Pause after a confirmed release so the headset is connectable again before we page it. */
    val releaseSettleDelayMs: Long = 1_500,
    val verifyTimeoutMs: Long = 8_000,
    val retryDelayMs: Long = 1_500,
    val maxConnectAttempts: Int = 2,
    /**
     * Full transfer rounds (owner lookup, release, connect, verify). 2 = one automatic retry of
     * the whole transfer after a transient failure; permanent failures are never retried.
     */
    val transferRounds: Int = 2,
    val autoRetryDelayMs: Long = 3_000,
    /** A peer that doesn't answer is probed once more after this pause before falling back. */
    val reachabilityRetryDelayMs: Long = 1_500,
    val ownershipBroadcastTimeoutMs: Long = 3_000,
    /** For multipoint headsets, "Move here" joins by default and never disconnects others. */
    val releaseOthersOnMultipoint: Boolean = false,
    /** Remote side: how long to wait for the local disconnect to be confirmed. */
    val remoteDisconnectVerifyMs: Long = 5_000,
    /** Remote side: after releasing to peer X, refuse other peers for this long (BUSY). */
    val releaseGraceMs: Long = 8_000,
)

/** One mutex per logical headset, shared by outgoing transfers and incoming release requests. */
class DeviceLocks {
    private val locks = ConcurrentHashMap<LogicalDeviceId, Mutex>()

    fun forDevice(id: LogicalDeviceId): Mutex = locks.getOrPut(id) { Mutex() }
}

interface TransferHistory {
    suspend fun add(record: TransferRecord)
}

class InMemoryTransferHistory : TransferHistory {
    val records = mutableListOf<TransferRecord>()

    override suspend fun add(record: TransferRecord) {
        synchronized(records) { records += record }
    }
}
