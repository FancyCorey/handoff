package dev.handoff.core.handoff

import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.DisconnectReason
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.LogicalDeviceRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles an authenticated RELEASE_AUDIO_DEVICE request on the host that currently holds the
 * headset. Runs without any UI, so it works with the screen off.
 *
 * Contention rules:
 *  - If this host is itself mid-transfer for the headset (mutex held) -> BUSY.
 *  - After releasing to peer X, requests from any *other* peer within
 *    [HandoffPolicy.releaseGraceMs] get BUSY, so two requesters cannot ping-pong the headset.
 *    X itself may re-ask (idempotent).
 */
class AudioReleaseHandler(
    private val devices: LogicalDeviceRepository,
    private val bluetooth: BluetoothAudioController,
    private val locks: DeviceLocks,
    private val events: EventLog,
    private val policy: HandoffPolicy = HandoffPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Outcome(val status: ReleaseStatus, val detail: String?, val durationMs: Long)

    private data class RecentRelease(val to: PeerId, val atMs: Long)

    private val recentReleases = ConcurrentHashMap<LogicalDeviceId, RecentRelease>()

    /** When this host last released [logicalId] to a peer, if ever (this process). */
    fun lastReleaseAtMs(logicalId: LogicalDeviceId): Long? = recentReleases[logicalId]?.atMs

    suspend fun release(logicalId: LogicalDeviceId, requester: PeerId): Outcome {
        val started = clock()
        events.record(EventType.RELEASE_REQUEST_RECEIVED, logicalId, requester)
        val outcome = releaseInternal(logicalId, requester, started)
        events.record(
            EventType.RELEASE_PERFORMED, logicalId, requester,
            mapOf("status" to outcome.status.name, "durationMs" to outcome.durationMs.toString()) +
                (outcome.detail?.let { mapOf("detail" to it) } ?: emptyMap()),
        )
        return outcome
    }

    private suspend fun releaseInternal(logicalId: LogicalDeviceId, requester: PeerId, started: Long): Outcome {
        fun done(status: ReleaseStatus, detail: String? = null) = Outcome(status, detail, clock() - started)

        val device = devices.find(logicalId) ?: return done(ReleaseStatus.DEVICE_UNKNOWN, "not mapped on this host")
        val local = device.localBluetoothMapping() ?: return done(ReleaseStatus.DEVICE_UNKNOWN, "not mapped on this host")

        val mutex = locks.forDevice(logicalId)
        if (!mutex.tryLock()) return done(ReleaseStatus.BUSY, "transfer in progress on this host")
        try {
            recentReleases[logicalId]?.let { recent ->
                if (recent.to != requester && clock() - recent.atMs < policy.releaseGraceMs) {
                    return done(ReleaseStatus.BUSY, "just released to another device")
                }
            }
            when (bluetooth.adapterState.value) {
                AdapterState.ON -> Unit
                AdapterState.NO_PERMISSION -> return done(ReleaseStatus.PERMISSION_DENIED)
                AdapterState.NOT_AVAILABLE -> return done(ReleaseStatus.UNSUPPORTED)
                else -> return done(ReleaseStatus.NOT_CONNECTED, "Bluetooth is off")
            }
            // Holding the headset means *any* audio profile: a call-only (HFP) link still blocks it.
            if (bluetooth.connectedProfiles(local).isEmpty()) return done(ReleaseStatus.NOT_CONNECTED)

            when (val op = bluetooth.disconnect(local, DisconnectReason.PEER_RELEASE_REQUEST)) {
                is BluetoothOperationResult.Failed -> return when (op.error) {
                    BluetoothError.PERMISSION_DENIED -> done(ReleaseStatus.PERMISSION_DENIED, op.detail)
                    BluetoothError.UNSUPPORTED -> done(ReleaseStatus.UNSUPPORTED, op.detail)
                    BluetoothError.BLUETOOTH_OFF -> done(ReleaseStatus.NOT_CONNECTED, op.detail)
                    else -> done(ReleaseStatus.FAILED, "${op.error}: ${op.detail}")
                }
                is BluetoothOperationResult.Requested, BluetoothOperationResult.AlreadyInState -> Unit
            }
            if (!bluetooth.verifyDisconnected(local, policy.remoteDisconnectVerifyMs)) {
                val remaining = bluetooth.connectedProfiles(local)
                return if (remaining.isNotEmpty() && "A2DP" !in remaining) {
                    done(ReleaseStatus.FAILED, "still connected for ${remaining.joinToString()}; Android refused to release it")
                } else {
                    done(ReleaseStatus.TIMEOUT, "disconnect not confirmed within ${policy.remoteDisconnectVerifyMs} ms")
                }
            }
            recentReleases[logicalId] = RecentRelease(requester, clock())
            devices.applyOwnership(logicalId, requester, device.ownershipGeneration)
            return done(ReleaseStatus.RELEASED)
        } finally {
            mutex.unlock()
        }
    }
}
