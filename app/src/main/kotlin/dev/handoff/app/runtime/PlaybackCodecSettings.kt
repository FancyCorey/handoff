package dev.handoff.app.runtime

import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.bluetooth.AndroidBluetoothAudioController
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.store.LogicalDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Applies local opt-ins while the process is alive, including a connection made outside Handoff. */
class PlaybackCodecSettings(
    private val scope: CoroutineScope,
    settings: SettingsRepository,
    devices: LogicalDeviceRepository,
    private val bluetooth: AndroidBluetoothAudioController,
) {
    private val optedIn = settings.settings.map { it.preferSbc }.distinctUntilChanged()
    private val mapped = devices.devices.map { stored ->
        stored.mapNotNullTo(mutableSetOf()) { device ->
            device.localDeviceId?.let { BluetoothDeviceId(it.address.uppercase()) }
        }
    }.distinctUntilChanged()

    fun start() {
        if (!bluetooth.codecPreferences.supported) return
        scope.launch {
            val running = mutableMapOf<BluetoothDeviceId, Job>()
            combine(optedIn, mapped) { choices, local -> choices intersect local }
                .distinctUntilChanged().collect { wanted ->
                    val obsolete = running.filterKeys { it !in wanted }
                    obsolete.forEach { (id, task) ->
                        task.cancel()
                        task.join()
                        running.remove(id)
                    }
                    wanted.forEach { id ->
                        if (id !in running) {
                            running[id] = launch { bluetooth.codecPreferences.maintainSbc(id) }
                        }
                    }
                }
        }
    }
}
