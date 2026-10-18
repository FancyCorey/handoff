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
import android.media.AudioManager
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
import kotlinx.coroutines.delay
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
 *   device over the network, and briefly while Home is open. Never while audio is playing, since
 *   searching shares the radio with the headphones.
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

    private data class Sighting(val device: BluetoothDevice, val psm: Int, val atMs: Long)

    private val seen = ConcurrentHashMap<PeerId, Sighting>()
    private val scanLock = Mutex()

    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var advertiser: AdvertiseCallback? = null
    private var jobs = emptyList<Job>()

    override fun available(): Boolean =
        enabled() && adapter?.isEnabled == true && granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)

    /** Start the beacon and the channel (while Handoff runs). Does nothing when unavailable. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (server != null || !available() || !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val socket = try {
            adapter?.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            log("Nearby: couldn't open a channel (${e.message})")
            null
        } catch (e: SecurityException) {
            null
        } ?: return
        server = socket
        jobs = listOf(
            scope.launch(Dispatchers.IO) { acceptLoop(socket, this) },
            scope.launch { advertiseLoop(socket.psm) },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        stopAdvertising()
        runCatching { server?.close() }
        server = null
    }

    /** Start or stop to match the setting, Bluetooth and permissions. */
    fun reconcile(scope: CoroutineScope) {
        if (available() && granted(Manifest.permission.BLUETOOTH_ADVERTISE)) start(scope) else stop()
    }

    // ---- serving ------------------------------------------------------------------------

    private suspend fun acceptLoop(socket: BluetoothServerSocket, scope: CoroutineScope) {
        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
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
                }
            }
        }
    }

    private suspend fun advertiseLoop(psm: Int) {
        while (true) {
            startAdvertising(psm)
            // A new code every 15 minutes, a moment after the slot changes.
            delay(tags.msUntilNextSlot() + 1_000)
            stopAdvertising()
        }
    }

    private fun startAdvertising(psm: Int) {
        val le = adapter?.bluetoothLeAdvertiser ?: return
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
            override fun onStartFailure(errorCode: Int) {
                log("Nearby: couldn't advertise (error $errorCode)")
            }
        }
        try {
            le.startAdvertising(settings, data, callback)
            advertiser = callback
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
            // Bluetooth turned off meanwhile.
        }
    }

    private fun stopAdvertising() {
        val callback = advertiser ?: return
        advertiser = null
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    // ---- reaching others ----------------------------------------------------------------

    override suspend fun open(peerId: PeerId, timeoutMs: Long): NearbyStream? {
        if (!available()) return null
        val deadline = clock() + timeoutMs
        val cached = seen[peerId]?.takeIf { clock() - it.atMs <= SIGHTING_TTL_MS }
        cached?.let { sighting -> connect(sighting, deadline)?.let { return it } }
        // Not seen lately, or its channel changed (Handoff restarted there): look for it.
        seen.remove(peerId)
        val found = scan(minOf(SCAN_FOR_MOVE_MS, deadline - clock()), peerId) ?: run {
            log("Nearby: ${name(peerId)} wasn't found")
            return null
        }
        return connect(found, deadline)
    }

    /**
     * A short look for linked devices while Home is open, so they show as Nearby. Skipped while
     * audio plays (searching shares the radio with the headphones).
     */
    suspend fun lookAround() {
        if (!available() || audio?.isMusicActive == true) return
        scan(SCAN_AROUND_MS, target = null)
    }

    private suspend fun connect(sighting: Sighting, deadline: Long): NearbyStream? = withContext(Dispatchers.IO) {
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
    private suspend fun scan(durationMs: Long, target: PeerId?): Sighting? = scanLock.withLock {
        if (durationMs <= 0) return null
        target?.let { seen[it] }?.takeIf { clock() - it.atMs <= SIGHTING_TTL_MS }?.let { return it }
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<Sighting>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onSeen(result, target, found)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onSeen(it, target, found) }
            override fun onScanFailed(errorCode: Int) {
                log("Nearby: couldn't search (error $errorCode)")
                found.cancel()
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        try {
            withTimeoutOrNull(durationMs) { runCatching { found.await() }.getOrNull() }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
                // Bluetooth turned off meanwhile: the scan is gone anyway.
            }
        }
    }

    private fun onSeen(result: ScanResult, target: PeerId?, found: CompletableDeferred<Sighting>) {
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

    private fun granted(permission: String) = appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Handoff's Bluetooth LE service (random, Handoff's own). */
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("5a0d7c2e-8f3b-4e61-9c47-2b8e1d6f0a93"))

        const val SCAN_FOR_MOVE_MS = 6_000L
        const val SCAN_AROUND_MS = 4_000L
        const val CONNECT_LIMIT_MS = 8_000L
        const val SIGHTING_TTL_MS = 120_000L
        const val SERVE_LIMIT_MS = 30_000L
    }
}
