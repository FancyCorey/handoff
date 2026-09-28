package dev.handoff.core.ownership

import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.store.LogicalDeviceRepository

/**
 * Keeps each host's logical-device records consistent with what peers report.
 *
 *  - **Host mappings:** a peer that reports a logical id is recorded as mapping that headset,
 *    so ownership resolution knows which hosts could be holding it.
 *  - **Id convergence:** if a peer reports the *same headset* (equal address fingerprint)
 *    under a different logical id, both hosts deterministically adopt the lexicographically
 *    smaller id. This happens when the user mapped the headset on two hosts before they had
 *    exchanged status.
 */
class MappingReconciler(
    private val selfId: PeerId,
    private val selfName: () -> String,
    private val devices: LogicalDeviceRepository,
) {
    sealed interface Change {
        data class Rekey(val from: LogicalDeviceId, val to: LogicalDeviceId) : Change
        data class HostsUpdated(val logicalId: LogicalDeviceId, val hosts: List<HostMapping>) : Change
    }

    /** Pure planning step; exposed for tests. */
    fun plan(
        local: List<LogicalAudioDevice>,
        reports: Map<PeerId, Map<LogicalDeviceId, PeerReport>>,
        knownHosts: Set<PeerId>,
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val allReports = reports.values.flatMap { it.values }
        val localIds = local.map { it.logicalId }.toSet()
        val rekeyed = mutableMapOf<LogicalDeviceId, LogicalDeviceId>()

        for (device in local) {
            val fingerprint = device.fingerprint ?: continue
            val target = allReports
                .filter { it.fingerprint == fingerprint && it.logicalId != device.logicalId }
                .map { it.logicalId }
                .filter { it.value < device.logicalId.value && it !in localIds }
                .minByOrNull { it.value }
            if (target != null) {
                changes += Change.Rekey(device.logicalId, target)
                rekeyed[device.logicalId] = target
            }
        }

        for (device in local) {
            val id = rekeyed[device.logicalId] ?: device.logicalId
            val hosts = buildList {
                if (device.localDeviceId != null) add(HostMapping(selfId, selfName()))
                reports.forEach { (peer, byId) -> byId[id]?.let { add(HostMapping(peer, it.displayName ?: device.displayName)) } }
                // Keep previously known hosts that simply have not reported (offline). A host that
                // did report but no longer lists the headset has unmapped it and is dropped.
                device.hostMappings
                    .filter { old -> old.hostId != selfId && old.hostId !in reports.keys && old.hostId in knownHosts }
                    .forEach { add(it) }
            }.sortedBy { it.hostId.value }
            if (hosts != device.hostMappings.sortedBy { it.hostId.value }) {
                changes += Change.HostsUpdated(id, hosts)
            }
        }
        return changes
    }

    /** [trustedPeers] limits retained host mappings to peers that are still linked. */
    suspend fun reconcile(
        reports: Map<PeerId, Map<LogicalDeviceId, PeerReport>>,
        trustedPeers: Set<PeerId>,
    ): List<Change> {
        val changes = plan(devices.devices.value, reports.filterKeys { it in trustedPeers }, trustedPeers)
        for (change in changes) {
            when (change) {
                is Change.Rekey -> devices.rekey(change.from, change.to)
                is Change.HostsUpdated -> devices.find(change.logicalId)?.let {
                    devices.upsert(it.copy(hostMappings = change.hosts))
                }
            }
        }
        return changes
    }
}
