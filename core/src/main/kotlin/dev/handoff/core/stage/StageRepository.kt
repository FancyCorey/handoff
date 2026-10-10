package dev.handoff.core.stage

import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.overview.OverviewRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
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
) {
    val stages: StateFlow<Map<LogicalDeviceId, Stage>> = combine(overview.devices, overview.peers, moves) { devices, peers, done ->
        devices.associate { it.device.logicalId to StageBuilder.build(it, peers, self, thisPlatform, done) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
