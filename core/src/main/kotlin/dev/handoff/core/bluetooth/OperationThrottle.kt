package dev.handoff.core.bluetooth

import java.util.ArrayDeque

/**
 * Sliding-window guard against hammering the Bluetooth stack (for example a bug in
 * automatic switching). Bounded retries in the coordinator stay well below the limit.
 */
class OperationThrottle(
    private val maxOperations: Int = 10,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val timestamps = ArrayDeque<Long>()

    /** Returns true and records the operation if it is allowed. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (timestamps.isNotEmpty() && now - timestamps.peekFirst() >= windowMs) {
            timestamps.pollFirst()
        }
        if (timestamps.size >= maxOperations) return false
        timestamps.addLast(now)
        return true
    }
}
