package dev.handoff.core.mesh.transport

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.protocol.OwnershipChanged
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.Ping
import dev.handoff.core.mesh.protocol.Pong
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** An open two-way stream to a device nearby (a Bluetooth channel on Android). */
class NearbyStream(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/**
 * Finds linked devices nearby and opens streams to them, without the network. On Android this
 * is Bluetooth Low Energy; Windows has none yet.
 */
interface NearbyChannel {
    /** False while switched off in Settings, or without Bluetooth or its permission. */
    fun available(): Boolean

    /**
     * Opens a stream to [peerId] within [timeoutMs]. When [allowDiscovery] is false, only an
     * existing sighting may be used. A channel may skip optional traffic during local media
     * playback unless [allowDuringPlayback] is true. Null if a stream couldn't be opened.
     */
    suspend fun open(peerId: PeerId, timeoutMs: Long, allowDiscovery: Boolean, allowDuringPlayback: Boolean): NearbyStream?
}

/**
 * The same authenticated, encrypted request as over the network ([LanPeerTransport]), carried
 * over a [NearbyChannel] instead of TCP. Nothing new to trust: the radio is just another wire.
 */
class NearbyPeerTransport(
    private val identity: IdentityProvider,
    private val trust: TrustedPeerRepository,
    private val directory: PeerDirectory,
    private val events: EventLog,
    private val channel: NearbyChannel,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PeerTransport {

    override suspend fun isReachable(peerId: PeerId, timeoutMs: Long): Boolean {
        val ping = Ping(PeerMessage.newCommandId(), clock(), identity.identity().peerId.value)
        val result = request(peerId, ping, timeoutMs)
        return result is CommandResult.Reply && result.message is Pong
    }

    override suspend fun request(peerId: PeerId, message: PeerMessage, timeoutMs: Long): CommandResult {
        val trusted = trust.find(peerId) ?: return CommandResult.Rejected("peer is not trusted")
        if (!channel.available()) return CommandResult.Unreachable
        val deadline = clock() + timeoutMs
        val discovery = message.allowsNearbyDiscovery()
        val stream = channel.open(
            peerId,
            timeoutMs,
            allowDiscovery = discovery,
            allowDuringPlayback = discovery || message is OwnershipChanged,
        ) ?: return CommandResult.Unreachable
        return withContext(io) {
            // A Bluetooth channel has no read timeout: closing it at the deadline ends a blocked read.
            val timedOut = AtomicBoolean(false)
            val outcome = coroutineScope {
                val watchdog = launch {
                    delay((deadline - clock()).coerceAtLeast(1))
                    timedOut.set(true)
                    runCatching { stream.close() }
                }
                try {
                    StreamExchange.run(stream.input, stream.output, identity, peerId, trusted.publicKey, message, listenPort = null)
                } finally {
                    watchdog.cancel()
                    runCatching { stream.close() }
                }
            }
            val result = if (timedOut.get() && !outcome.answered) CommandResult.Timeout else outcome.result
            if (outcome.answered) {
                directory.onNearbyContact(peerId)
            } else if (result is CommandResult.Rejected) {
                events.record(EventType.PEER_AUTH_FAILED, peerId = peerId, details = mapOf("reason" to result.reason, "via" to "bluetooth"))
            }
            result
        }
    }
}

/**
 * Tries the network first and, when the other device can't be reached that way, Bluetooth
 * nearby. Bluetooth is used sparingly, because it shares the radio with the headphones:
 *
 * - For moves (the reachability check and the release request) it looks for the device if
 *   needed, since that is when it matters.
 * - Status and ownership updates only use a cached sighting. Each has its own limit of one
 *   attempt per [backgroundIntervalMs] per peer so a status attempt can't delay an ownership update.
 */
class FallbackPeerTransport(
    private val network: PeerTransport,
    private val nearby: PeerTransport,
    private val directory: PeerDirectory,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** At least this long for Bluetooth: finding the device and connecting take a few seconds. */
    private val nearbyBudgetMs: Long = 12_000,
    private val backgroundIntervalMs: Long = 30_000,
    /** False for devices that can't be reached over Bluetooth (Windows, for now). */
    private val canBeNearby: (PeerId) -> Boolean = { true },
    private val events: EventLog? = null,
) : PeerTransport {
    private val lastStatusAttempt = ConcurrentHashMap<PeerId, Long>()
    private val lastOwnershipAttempt = ConcurrentHashMap<PeerId, Long>()

    override suspend fun isReachable(peerId: PeerId, timeoutMs: Long): Boolean {
        if (network.isReachable(peerId, timeoutMs)) return true
        if (!canBeNearby(peerId)) return false
        recordFallback(peerId, reason = "reachability check failed", allowDiscovery = true)
        return nearby.isReachable(peerId, maxOf(timeoutMs, nearbyBudgetMs))
    }

    override suspend fun request(peerId: PeerId, message: PeerMessage, timeoutMs: Long): CommandResult {
        val first = network.request(peerId, message, timeoutMs)
        val canFallback = first == CommandResult.Unreachable || (first is CommandResult.Rejected && first.isWrongEndpoint())
        if (!canFallback || !canBeNearby(peerId)) return first
        val allowDiscovery = message.allowsNearbyDiscovery()
        if (!allowDiscovery && !reserveBackgroundAttempt(peerId, message is OwnershipChanged)) return first

        recordFallback(
            peerId,
            reason = if (first == CommandResult.Unreachable) "network unreachable" else "wrong network endpoint",
            allowDiscovery = allowDiscovery,
        )
        val second = nearby.request(peerId, message, maxOf(timeoutMs, nearbyBudgetMs))
        return if (second == CommandResult.Unreachable) first else second
    }

    private fun reserveBackgroundAttempt(peerId: PeerId, ownership: Boolean): Boolean {
        if (!directory.isNearby(peerId)) return false
        val attempts = if (ownership) lastOwnershipAttempt else lastStatusAttempt
        var reserved = false
        attempts.compute(peerId) { _, last ->
            val now = clock()
            if (last != null && now - last < backgroundIntervalMs) last else {
                reserved = true
                now
            }
        }
        return reserved
    }

    private fun recordFallback(peerId: PeerId, reason: String, allowDiscovery: Boolean) {
        events?.record(
            EventType.BLUETOOTH_OPERATION,
            peerId = peerId,
            details = mapOf(
                "operation" to "nearby fallback",
                "reason" to reason,
                "discovery" to if (allowDiscovery) "allowed" else "cached only",
            ),
        )
    }
}

private fun PeerMessage.allowsNearbyDiscovery() = this is ReleaseAudioDevice || this is Ping
