package dev.handoff.app.runtime

import dev.handoff.core.handoff.HandoffCoordinator
import dev.handoff.core.handoff.HandoffResult
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.store.LogicalDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one entry point for "Move here" from UI, Quick Settings tile, notifications and
 * automatic switching. Transfers run in the application scope so leaving a screen, collapsing
 * the shade or the tile unbinding never cancels a transfer half-way.
 *
 * The networking runtime is held for the duration of the transfer. If it was not already
 * running (background mode off), LAN discovery gets a moment to find peers first.
 */
class HandoffActions(
    private val appScope: CoroutineScope,
    private val coordinator: HandoffCoordinator,
    private val devices: LogicalDeviceRepository,
    private val runtime: HandoffRuntime,
) {
    fun moveHere(logicalId: LogicalDeviceId, trigger: TransferTrigger, onResult: (HandoffResult) -> Unit = {}): Job =
        appScope.launch {
            val holder = "transfer:${logicalId.value}:${System.nanoTime()}"
            val wasRunning = runtime.running.value
            try {
                runtime.acquire(holder)
                if (!wasRunning) {
                    delay(DISCOVERY_SETTLE_MS)
                    runtime.refreshNow()
                }
                val device = devices.find(logicalId)
                val result = if (device == null) HandoffResult.MissingLocalMapping else coordinator.moveToThisDevice(device, trigger)
                onResult(result)
            } finally {
                withContext(NonCancellable) { runtime.release(holder) }
            }
        }

    private companion object {
        const val DISCOVERY_SETTLE_MS = 1_500L
    }
}
