package dev.handoff.core.stage

import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.overview.OverviewRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The stage of every pair of headphones, kept up to date for the screens that draw them. */
class StageRepository(
    scope: CoroutineScope,
    overview: OverviewRepository,
    self: PeerId,
    thisPlatform: String,
    moves: StateFlow<Int>,
    /** Whether this device is on a network its other devices could share (Wi-Fi, Ethernet, its own hotspot). */
    val onNetwork: Flow<Boolean> = flowOf(true),
) {
    val stages: StateFlow<Map<LogicalDeviceId, Stage>> = combine(overview.devices, overview.peers, moves, onNetwork) { devices, peers, done, net ->
        devices.associate { it.device.logicalId to StageBuilder.build(it, peers, self, thisPlatform, done, net) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
