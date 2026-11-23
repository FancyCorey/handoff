package dev.handoff.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.handoff.core.mesh.transport.NearbyChannel
import dev.handoff.core.mesh.transport.NearbyStream
import dev.handoff.core.mesh.transport.NearbyTags
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reaches linked Android devices nearby over Bluetooth Low Energy when the network can't.
 *
 * - **Beacon.** While Handoff runs, this device advertises Handoff's service with an 8-byte code
 *   that only linked devices can recognize ([NearbyTags], it changes every 15 minutes) and the
 *   number of the channel to connect to. No name, no fixed identifier.
 * - **Channel.** A Bluetooth LE channel (L2CAP, no pairing) that carries Handoff's usual
 *   authenticated, encrypted handshake and requests, served by the same server as the network.
 * - **Looking.** Only for linked devices' beacons: during a move that can't reach the other
 *   device over the network, and briefly while Home is open and music is idle.
 */
class BleNearbyChannel(
    context: Context,
    private val tags: NearbyTags,
    private val ownPublicKey: () -> ByteArray,
    private val directory: PeerDirectory,
    /** The "Reach devices over Bluetooth too" setting. */
    private val enabled: () -> Boolean,
    /** Serves one incoming connection: the peer server's nearby entry point. */
    private val serve: suspend (InputStream, OutputStream, String) -> Unit,
    /** Notes for the Bluetooth log in diagnostics. */
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : NearbyChannel {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val playback = NearbyPlaybackGuard({ audio?.isMusicActive == true }, ::subscribeToPlayback)

    private data class Sighting(val device: BluetoothDevice, val psm: Int, val atMs: Long)

    private val seen = ConcurrentHashMap<PeerId, Sighting>()
    private val scanLock = Mutex()
    private val lastNotes = mutableMapOf<String, Long>()

    private class Listener(val socket: BluetoothServerSocket) {
        val clients = NearbyServerClients()
        var advertiser: AdvertiseCallback? = null
        lateinit var acceptJob: Job
        lateinit var advertiseJob: Job
    }

    private var listener: Listener? = null

    override fun available(): Boolean =
        enabled() && granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN) && adapter?.isEnabled == true

    /** Start the beacon and the channel (while Handoff runs). Does nothing when unavailable. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (listener != null || !available() || !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val socket = try {
            adapter?.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            if (shouldNote("listen failed")) log("Nearby: couldn't open a channel (${e.message})")
            null
        } catch (e: SecurityException) {
            null
        } ?: return
        val started = Listener(socket)
        listener = started
        started.acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(started, this) }
        started.advertiseJob = scope.launch { advertiseLoop(started) }
        log("Nearby: channel listening")
    }

    @Synchronized
    fun stop() {
        val stopped = listener ?: return
        listener = null
        stopped.advertiseJob.cancel()
        stopAdvertising(stopped)
        stopped.acceptJob.cancel()
        runCatching { stopped.socket.close() }
        stopped.clients.close()
        log("Nearby: channel stopped")
    }

    /** Start or stop to match the setting, Bluetooth and permissions. */
    @Synchronized
    fun reconcile(scope: CoroutineScope) {
        if (available() && granted(Manifest.permission.BLUETOOTH_ADVERTISE)) start(scope) else stop()
    }

    // ---- serving ------------------------------------------------------------------------

    private fun acceptLoop(listener: Listener, scope: CoroutineScope) {
        while (scope.isActive) {
            val client = try {
                listener.socket.accept()
            } catch (_: IOException) {
                break
            }
            if (!listener.clients.add(client)) break
            scope.launch(Dispatchers.IO) {
                // A Bluetooth channel has no read timeout of its own.
                val watchdog = launch {
                    delay(SERVE_LIMIT_MS)
                    runCatching { client.close() }
                }
                try {
                    serve(client.inputStream, client.outputStream, client.remoteDevice?.address ?: "unknown")
                } catch (_: IOException) {
                } finally {
                    watchdog.cancel()
                    runCatching { client.close() }
                    listener.clients.remove(client)
                }
            }
        }
    }

    private suspend fun advertiseLoop(listener: Listener) {
        advertise(listener, rotate = false)
        while (true) {
            // A new code every 15 minutes, a moment after the slot changes.
            delay(tags.msUntilNextSlot() + 1_000)
            advertise(listener, rotate = true)
        }
    }

    @Synchronized
    private fun advertise(session: Listener, rotate: Boolean) {
        if (listener !== session) return
        if (rotate) {
            log("Nearby: rotating beacon")
            stopAdvertising(session)
        }
        val psm = session.socket.psm
        val payload = tags.ownTag(ownPublicKey()) + byteArrayOf((psm shr 8).toByte(), psm.toByte())
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(SERVICE, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                synchronized(this@BleNearbyChannel) {
                    if (listener === session && session.advertiser === this) {
                        log(if (rotate) "Nearby: beacon rotated" else "Nearby: beacon started")
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                synchronized(this@BleNearbyChannel) {
                    if (listener === session && session.advertiser === this) {
                        stopAdvertising(session)
                        log("Nearby: couldn't advertise (error $errorCode)")
                    }
                }
            }
        }
        try {
            val le = adapter?.bluetoothLeAdvertiser ?: return
            session.advertiser = callback
            le.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {
            session.advertiser = null
        } catch (_: IllegalStateException) {
            // Bluetooth turned off meanwhile.
            session.advertiser = null
        }
    }

    /** Called with the channel monitor held so a stop cannot race a rotation or a new listener. */
    private fun stopAdvertising(session: Listener) {
        val callback = session.advertiser ?: return
        session.advertiser = null
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    // ---- reaching others ----------------------------------------------------------------

    override suspend fun open(
        peerId: PeerId,
        timeoutMs: Long,
        allowDiscovery: Boolean,
        allowDuringPlayback: Boolean,
    ): NearbyStream? {
        if (!available()) return null
        if (!canContact(allowDuringPlayback)) return null
        val deadline = clock() + timeoutMs
        return openFromSighting(
            cached = recentSighting(peerId),
            allowDiscovery = allowDiscovery,
            connect = { connect(it, deadline, allowDuringPlayback) },
            discover = { stale ->
                // Only remove the exact failed sighting. Another search may have updated it.
                if (stale != null) seen.remove(peerId, stale)
                val found = scan(minOf(SCAN_FOR_MOVE_MS, deadline - clock()), peerId)
                if (found == null) log("Nearby: ${name(peerId)} wasn't found")
                found
            },
        )
    }

    /**
     * A short look for linked devices while Home is open, so they show as Nearby. Searching
     * shares the radio with the headphones, so music also ends a search already in progress.
     */
    suspend fun lookAround() {
        if (!available()) return
        playback.whileIdle(onPlayback = ::noteSearchDuringPlayback) {
            scan(SCAN_AROUND_MS, target = null)
        }
    }

    private fun subscribeToPlayback(onChange: (Boolean) -> Unit): AutoCloseable? {
        val manager = audio ?: return null
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                // Android sends ordinary apps only active playback configurations.
                val playingMedia = configs.any {
                    val usage = it.audioAttributes.usage
                    usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
                }
                onChange(playingMedia || manager.isMusicActive)
            }
        }
        return try {
            manager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
            AutoCloseable { runCatching { manager.unregisterAudioPlaybackCallback(callback) } }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun noteSearchDuringPlayback() {
        if (shouldNote("search during playback")) log("Nearby: paused background search for music")
    }

    private fun canContact(allowDuringPlayback: Boolean): Boolean {
        if (allowDuringPlayback || !playback.isPlaying()) return true
        if (shouldNote("status during playback")) log("Nearby: skipped background contact while music played")
        return false
    }

    private fun recentSighting(peerId: PeerId): Sighting? =
        seen[peerId]?.takeIf { clock() - it.atMs in 0..SIGHTING_TTL_MS }

    private suspend fun connect(sighting: Sighting, deadline: Long, allowDuringPlayback: Boolean): NearbyStream? = withContext(Dispatchers.IO) {
        if (!available() || deadline <= clock()) return@withContext null
        if (!canContact(allowDuringPlayback)) return@withContext null
        val socket = try {
            sighting.device.createInsecureL2capChannel(sighting.psm)
        } catch (_: Exception) {
            return@withContext null
        }
        // connect() blocks without a timeout of its own: closing the socket ends it.
        val opened = withTimeoutOrNull((deadline - clock()).coerceIn(1, CONNECT_LIMIT_MS)) {
            val done = CompletableDeferred<Boolean>()
            val thread = Thread {
                done.complete(
                    try {
                        socket.connect()
                        true
                    } catch (_: Exception) {
                        false
                    },
                )
            }
            thread.start()
            try {
                done.await()
            } finally {
                if (!done.isCompleted) runCatching { socket.close() }
            }
        }
        if (opened != true) {
            runCatching { socket.close() }
            log("Nearby: couldn't connect (${if (opened == null) "timed out" else "refused"})")
            return@withContext null
        }
        NearbyStream(socket.inputStream, socket.outputStream) { runCatching { socket.close() } }
    }

    /** Looks for linked devices' beacons for up to [durationMs], or until [target] is found. */
    private suspend fun scan(durationMs: Long, target: PeerId?): Sighting? {
        if (durationMs <= 0) return null
        return withTimeoutOrNull(durationMs) { scanLock.withLock { scanLocked(target) } }
    }

    private suspend fun scanLocked(target: PeerId?): Sighting? {
        if (!available()) return null
        if (target == null && playback.isPlaying()) {
            noteSearchDuringPlayback()
            return null
        }
        target?.let(::recentSighting)?.let { return it }
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<Sighting?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
            override fun onScanFailed(errorCode: Int) {
                if (!found.isActive) return
                if (shouldNote("scan error $errorCode")) log("Nearby: couldn't search (error $errorCode)")
                found.complete(null)
            }
            private fun record(result: ScanResult) {
                if (found.isActive) onSeen(result, target, found)
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        currentCoroutineContext().ensureActive()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: SecurityException) {
            found.cancel()
            return null
        } catch (_: IllegalStateException) {
            found.cancel()
            return null
        }
        val noteScan = target != null || shouldNote("background scan")
        return try {
            if (noteScan) log(if (target == null) "Nearby: background search started" else "Nearby: move search started")
            found.await()
        } finally {
            found.cancel()
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
                // Bluetooth turned off meanwhile: the scan is gone anyway.
            }
            if (noteScan) log(if (target == null) "Nearby: background search ended" else "Nearby: move search ended")
        }
    }

    private fun onSeen(result: ScanResult, target: PeerId?, found: CompletableDeferred<Sighting?>) {
        val payload = result.scanRecord?.getServiceData(SERVICE) ?: return
        if (payload.size < NearbyTags.TAG_BYTES + 2) return
        val peer = tags.resolve(payload.copyOf(NearbyTags.TAG_BYTES)) ?: return
        val psm = ((payload[NearbyTags.TAG_BYTES].toInt() and 0xFF) shl 8) or (payload[NearbyTags.TAG_BYTES + 1].toInt() and 0xFF)
        val sighting = Sighting(result.device, psm, clock())
        if (seen.put(peer, sighting) == null) log("Nearby: found ${name(peer)}")
        directory.onNearbySeen(peer)
        if (peer == target) found.complete(sighting)
    }

    private fun name(peer: PeerId): String = tags.nameOf(peer) ?: "a linked device"

    private fun shouldNote(key: String): Boolean = synchronized(lastNotes) {
        val now = clock()
        val previous = lastNotes[key]
        if (previous != null && now - previous in 0 until NOTE_INTERVAL_MS) return@synchronized false
        lastNotes[key] = now
        true
    }

    private fun granted(permission: String) = appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Handoff's Bluetooth LE service (random, Handoff's own). */
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("5a0d7c2e-8f3b-4e61-9c47-2b8e1d6f0a93"))

        const val SCAN_FOR_MOVE_MS = 6_000L
        const val SCAN_AROUND_MS = 4_000L
        const val CONNECT_LIMIT_MS = 8_000L
        const val SIGHTING_TTL_MS = 120_000L
        const val SERVE_LIMIT_MS = 30_000L
        private const val NOTE_INTERVAL_MS = 60_000L
    }
}

/** A passive contact gets one cached attempt. Only a move can look for a new sighting. */
internal suspend fun <Sighting : Any, Connection : Any> openFromSighting(
    cached: Sighting?,
    allowDiscovery: Boolean,
    connect: suspend (Sighting) -> Connection?,
    discover: suspend (failedSighting: Sighting?) -> Sighting?,
): Connection? {
    if (cached != null) connect(cached)?.let { return it }
    if (!allowDiscovery) return null
    val sighting = discover(cached) ?: return null
    return connect(sighting)
}
