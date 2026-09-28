package dev.handoff.core.mesh.security

import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.model.PeerId
import kotlin.math.abs

/**
 * Application-level freshness and duplicate handling, on top of the per-session frame counters.
 *
 *  - Commands whose timestamp is outside [freshnessWindowMs] of local time are rejected.
 *  - A command id seen before from the same peer is not executed again. If its response is
 *    still cached, the same response is returned (idempotent retry); otherwise it is rejected.
 */
class CommandGuard(
    private val freshnessWindowMs: Long = DEFAULT_FRESHNESS_WINDOW_MS,
    private val capacity: Int = 2048,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Verdict {
        data object Fresh : Verdict
        data object Stale : Verdict

        /** [cached] is null while the original is still being processed. */
        data class Duplicate(val cached: PeerMessage?) : Verdict
    }

    private class Entry(val seenAtMs: Long, var response: PeerMessage?)

    private val seen = LinkedHashMap<String, Entry>(64, 0.75f, false)

    @Synchronized
    fun check(from: PeerId, message: PeerMessage): Verdict {
        val now = clock()
        evict(now)
        if (abs(now - message.timestamp) > freshnessWindowMs) return Verdict.Stale
        val key = key(from, message.commandId)
        seen[key]?.let { return Verdict.Duplicate(it.response) }
        seen[key] = Entry(now, null)
        return Verdict.Fresh
    }

    @Synchronized
    fun remember(from: PeerId, commandId: String, response: PeerMessage) {
        seen[key(from, commandId)]?.response = response
    }

    private fun evict(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.seenAtMs > 2 * freshnessWindowMs || seen.size > capacity) {
                iterator.remove()
            } else {
                break
            }
        }
    }

    private fun key(from: PeerId, commandId: String) = "${from.value}/$commandId"

    companion object {
        const val DEFAULT_FRESHNESS_WINDOW_MS = 5 * 60_000L
    }
}
