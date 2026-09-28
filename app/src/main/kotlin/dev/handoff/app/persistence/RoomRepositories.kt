package dev.handoff.app.persistence

import dev.handoff.core.handoff.FailureReason
import dev.handoff.core.handoff.TransferHistory
import dev.handoff.core.handoff.TransferPath
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.handoff.TransferTimings
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.store.TrustedPeerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room-backed repositories. Each keeps an in-memory [StateFlow] mirror so hot paths (the
 * handshake trust check, the release handler) read synchronously. [awaitLoaded] suspends until
 * the first database read, and the peer server is not started before that.
 */
class RoomTrustedPeerRepository(private val dao: TrustedPeerDao, scope: CoroutineScope) : TrustedPeerRepository {
    private val state = MutableStateFlow<List<TrustedPeer>>(emptyList())
    override val peers: StateFlow<List<TrustedPeer>> = state.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            dao.observeAll().collect { rows ->
                state.value = rows.map { TrustedPeer(PeerId(it.peerId), it.displayName, it.publicKey, it.pairedAtMs) }
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    override suspend fun upsert(peer: TrustedPeer) {
        dao.upsert(TrustedPeerEntity(peer.peerId.value, peer.displayName, peer.publicKey, peer.pairedAtMs))
        state.value = state.value.filterNot { it.peerId == peer.peerId } + peer
    }

    override suspend fun remove(peerId: PeerId) {
        dao.delete(peerId.value)
        state.value = state.value.filterNot { it.peerId == peerId }
    }
}

class RoomLogicalDeviceRepository(private val dao: LogicalDeviceDao, scope: CoroutineScope) : LogicalDeviceRepository {
    private val state = MutableStateFlow<List<LogicalAudioDevice>>(emptyList())
    override val devices: StateFlow<List<LogicalAudioDevice>> = state.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            dao.observeAll().collect { rows ->
                state.value = rows.map(::toModel)
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    override suspend fun upsert(device: LogicalAudioDevice) {
        dao.upsert(toEntity(device))
        state.value = state.value.filterNot { it.logicalId == device.logicalId } + device
    }

    override suspend fun remove(logicalId: LogicalDeviceId) {
        dao.delete(logicalId.value)
        state.value = state.value.filterNot { it.logicalId == logicalId }
    }

    override suspend fun rekey(from: LogicalDeviceId, to: LogicalDeviceId) {
        dao.rekey(from.value, to.value)
        dao.get(to.value)?.let { row ->
            state.value = state.value.filterNot { it.logicalId == from || it.logicalId == to } + toModel(row)
        }
    }

    override suspend fun applyOwnership(logicalId: LogicalDeviceId, owner: PeerId?, generation: Long): Boolean {
        val changed = dao.applyOwnership(logicalId.value, owner?.value, generation)
        if (changed) {
            dao.get(logicalId.value)?.let { row ->
                state.value = state.value.map { if (it.logicalId == logicalId) toModel(row) else it }
            }
        }
        return changed
    }

    private fun toModel(e: LogicalDeviceEntity) = LogicalAudioDevice(
        logicalId = LogicalDeviceId(e.logicalId),
        displayName = e.displayName,
        deviceType = runCatching { AudioDeviceKind.valueOf(e.deviceType) }.getOrDefault(AudioDeviceKind.UNKNOWN),
        fingerprint = e.fingerprint,
        localDeviceId = e.localAddress?.let(::BluetoothDeviceId),
        multipoint = e.multipoint,
        lastKnownOwner = e.lastKnownOwner?.let(::PeerId),
        ownershipGeneration = e.ownershipGeneration,
        hostMappings = decodeHosts(e.hostMappingsJson),
    )

    private fun toEntity(d: LogicalAudioDevice) = LogicalDeviceEntity(
        logicalId = d.logicalId.value,
        displayName = d.displayName,
        deviceType = d.deviceType.name,
        fingerprint = d.fingerprint,
        localAddress = d.localDeviceId?.address,
        multipoint = d.multipoint,
        lastKnownOwner = d.lastKnownOwner?.value,
        ownershipGeneration = d.ownershipGeneration,
        hostMappingsJson = encodeHosts(d.hostMappings),
    )

    private fun encodeHosts(hosts: List<HostMapping>): String =
        JSONArray().apply {
            hosts.forEach { put(JSONObject().put("hostId", it.hostId.value).put("alias", it.alias)) }
        }.toString()

    private fun decodeHosts(json: String): List<HostMapping> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HostMapping(PeerId(o.getString("hostId")), o.optString("alias"))
        }
    }.getOrDefault(emptyList())
}

class RoomTransferHistory(private val dao: TransferRecordDao) : TransferHistory {
    override suspend fun add(record: TransferRecord) {
        dao.insert(
            TransferRecordEntity(
                logicalId = record.logicalId.value,
                deviceName = record.deviceName,
                trigger = record.trigger.name,
                startedAtMs = record.startedAtMs,
                outcome = record.outcome,
                path = record.path?.name,
                failure = record.failure?.name,
                detail = record.detail,
                previousOwner = record.previousOwner?.value,
                strategy = record.strategy,
                attempts = record.attempts,
                releaseMs = record.timings.releaseMs,
                connectMs = record.timings.connectMs,
                totalMs = record.timings.totalMs,
            ),
        )
        dao.trim(KEEP)
    }

    fun recent(limit: Int = 50): Flow<List<TransferRecord>> = dao.observeRecent(limit).map { rows ->
        rows.map {
            TransferRecord(
                logicalId = LogicalDeviceId(it.logicalId),
                deviceName = it.deviceName,
                trigger = runCatching { TransferTrigger.valueOf(it.trigger) }.getOrDefault(TransferTrigger.MANUAL),
                startedAtMs = it.startedAtMs,
                outcome = it.outcome,
                path = it.path?.let { p -> runCatching { TransferPath.valueOf(p) }.getOrNull() },
                failure = it.failure?.let { f -> runCatching { FailureReason.valueOf(f) }.getOrNull() },
                detail = it.detail,
                previousOwner = it.previousOwner?.let(::PeerId),
                strategy = it.strategy,
                attempts = it.attempts,
                timings = TransferTimings(it.releaseMs, it.connectMs, it.totalMs),
            )
        }
    }

    private companion object {
        const val KEEP = 200
    }
}
