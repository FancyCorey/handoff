package dev.handoff.core.ownership

import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Distributed ownership state: the latest report each peer made about each headset. */
interface OwnershipRepository {
    /** peer -> (logical device -> latest report). */
    val reports: StateFlow<Map<PeerId, Map<LogicalDeviceId, PeerReport>>>

    /** Replace everything known about [peerId] with a full status snapshot. */
    fun recordSnapshot(peerId: PeerId, snapshot: List<PeerReport>)

    /** Merge a single report (e.g. from OWNERSHIP_CHANGED). Older generations are ignored. */
    fun record(report: PeerReport)

    fun forgetPeer(peerId: PeerId)

    fun reportsFor(logicalId: LogicalDeviceId): List<PeerReport> =
        reports.value.values.mapNotNull { it[logicalId] }

    /** Actively ask reachable peers for fresh status. Bounded by [timeoutMs]; never throws. */
    suspend fun refresh(timeoutMs: Long)
}

/** State-holding part of [OwnershipRepository]; subclasses supply [refresh]. */
abstract class PeerReportStore : OwnershipRepository {
    private val state = MutableStateFlow<Map<PeerId, Map<LogicalDeviceId, PeerReport>>>(emptyMap())
    override val reports: StateFlow<Map<PeerId, Map<LogicalDeviceId, PeerReport>>> = state.asStateFlow()

    override fun recordSnapshot(peerId: PeerId, snapshot: List<PeerReport>) {
        state.update { it + (peerId to snapshot.associateBy { r -> r.logicalId }) }
    }

    override fun record(report: PeerReport) {
        state.update { all ->
            val forPeer = all[report.peerId].orEmpty()
            val existing = forPeer[report.logicalId]
            if (existing != null && existing.generation > report.generation) {
                all
            } else {
                all + (report.peerId to (forPeer + (report.logicalId to report)))
            }
        }
    }

    override fun forgetPeer(peerId: PeerId) {
        state.update { it - peerId }
    }
}
