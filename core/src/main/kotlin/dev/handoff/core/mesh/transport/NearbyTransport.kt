package dev.handoff.core.mesh.transport

import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
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
     * Opens a stream to [peerId], looking for it first if it hasn't been seen lately, within
     * [timeoutMs]. Null if it isn't nearby or the channel couldn't be opened.
     */
    suspend fun open(peerId: PeerId, timeoutMs: Long): NearbyStream?
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
        val stream = channel.open(peerId, timeoutMs) ?: return CommandResult.Unreachable
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
 * - Anything else (status updates) only goes to a device already seen nearby, at most once
 *   per [backgroundIntervalMs] each.
 */
class FallbackPeerTransport(
    private val network: PeerTransport,
    private val nearby: PeerTransport,
    private val directory: PeerDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
    /** At least this long for Bluetooth: finding the device and connecting take a few seconds. */
    private val nearbyBudgetMs: Long = 12_000,
    private val backgroundIntervalMs: Long = 30_000,
    /** False for devices that can't be reached over Bluetooth (Windows, for now). */
    private val canBeNearby: (PeerId) -> Boolean = { true },
) : PeerTransport {
    private val lastBackground = ConcurrentHashMap<PeerId, Long>()

    override suspend fun isReachable(peerId: PeerId, timeoutMs: Long): Boolean =
        network.isReachable(peerId, timeoutMs) || (canBeNearby(peerId) && nearby.isReachable(peerId, maxOf(timeoutMs, nearbyBudgetMs)))

    override suspend fun request(peerId: PeerId, message: PeerMessage, timeoutMs: Long): CommandResult {
        val first = network.request(peerId, message, timeoutMs)
        val notDelivered = first == CommandResult.Unreachable || (first is CommandResult.Rejected && first.isWrongEndpoint())
        if (!notDelivered || !worthTrying(peerId, message)) return first
        val second = nearby.request(peerId, message, maxOf(timeoutMs, nearbyBudgetMs))
        return if (second == CommandResult.Unreachable) first else second
    }

    private fun worthTrying(peerId: PeerId, message: PeerMessage): Boolean {
        if (!canBeNearby(peerId)) return false
        if (message is ReleaseAudioDevice || message is Ping) return true
        if (!directory.isNearby(peerId)) return false
        val now = clock()
        val last = lastBackground[peerId]
        if (last != null && now - last < backgroundIntervalMs) return false
        lastBackground[peerId] = now
        return true
    }
}
