package dev.handoff.core.mesh.transport

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Decides which inbound connections the peer server will even talk to.
 *
 * - Only local-network addresses (private IPv4, link-local, IPv6 unique-local, loopback): Handoff
 *   never needs to be reached from the internet, even if a router forwards the port.
 * - A per-address budget of new connections, and a short block after repeated failed handshakes,
 *   so a device on the network cannot hammer the server or guess at it quickly.
 */
class ConnectionPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConnectionsPerWindow: Int = 30,
    private val windowMs: Long = 10_000,
    private val maxFailuresPerWindow: Int = 8,
    private val failureWindowMs: Long = 60_000,
    private val blockMs: Long = 60_000,
) {
    private class Budget(var windowStart: Long, var connections: Int, var failureStart: Long, var failures: Int, var blockedUntil: Long)

    private val budgets = HashMap<String, Budget>()

    @Synchronized
    fun admit(address: InetAddress?): Boolean {
        if (address == null || !isLocal(address)) return false
        val now = clock()
        prune(now)
        val b = budgets.getOrPut(address.hostAddress) { Budget(now, 0, now, 0, 0) }
        if (now < b.blockedUntil) return false
        if (now - b.windowStart >= windowMs) {
            b.windowStart = now
            b.connections = 0
        }
        b.connections++
        return b.connections <= maxConnectionsPerWindow
    }

    /** A connection from [address] failed the handshake (unknown peer, bad signature, garbage). */
    @Synchronized
    fun onHandshakeFailed(address: InetAddress?) {
        val key = address?.hostAddress ?: return
        val now = clock()
        val b = budgets.getOrPut(key) { Budget(now, 0, now, 0, 0) }
        if (now - b.failureStart >= failureWindowMs) {
            b.failureStart = now
            b.failures = 0
        }
        b.failures++
        if (b.failures >= maxFailuresPerWindow) b.blockedUntil = now + blockMs
    }

    private fun prune(now: Long) {
        if (budgets.size < MAX_TRACKED) return
        budgets.entries.removeIf { (_, b) -> now >= b.blockedUntil && now - b.windowStart >= windowMs && now - b.failureStart >= failureWindowMs }
    }

    companion object {
        private const val MAX_TRACKED = 256

        fun isLocal(address: InetAddress): Boolean = when {
            address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress -> true
            address is Inet6Address -> (address.address[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique local
            else -> false
        }
    }
}
