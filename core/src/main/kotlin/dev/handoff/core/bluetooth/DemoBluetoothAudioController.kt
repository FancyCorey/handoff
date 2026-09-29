package dev.handoff.core.bluetooth

import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.DeviceFingerprint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Made-up headsets for screenshots and demos, so no real device names or addresses appear.
 * Every demo host uses the same fixed addresses, so linked demo hosts recognise the headsets as
 * the same ones. Nothing here touches the real Bluetooth stack.
 */
class DemoBluetoothAudioController(
    initiallyConnected: Set<String> = emptySet(),
    /** How long a demo connect takes; raise it to try progress, leaving and cancelling a move. */
    private val connectMs: Long = CONNECT_MS,
) : BluetoothAudioController {

    private val connected = MutableStateFlow(initiallyConnected.map { it.uppercase() }.toSet())

    override val adapterState: StateFlow<AdapterState> = MutableStateFlow(AdapterState.ON).asStateFlow()

    override val batteryLevels: StateFlow<Map<String, Int>> = MutableStateFlow(BATTERY).asStateFlow()

    override fun bondedAudioDevices(): Flow<List<AudioDevice>> = flowOf(DEVICES)

    override fun connectionState(deviceId: BluetoothDeviceId): Flow<AudioConnectionState> =
        connected.map { if (key(deviceId) in it) AudioConnectionState.CONNECTED else AudioConnectionState.DISCONNECTED }

    override suspend fun isConnected(deviceId: BluetoothDeviceId): Boolean = key(deviceId) in connected.value

    override suspend fun connect(deviceId: BluetoothDeviceId, reason: ConnectReason): BluetoothOperationResult {
        if (isConnected(deviceId)) return BluetoothOperationResult.AlreadyInState
        delay(connectMs)
        connected.value = connected.value + key(deviceId)
        return BluetoothOperationResult.Requested(STRATEGY, connectMs)
    }

    override suspend fun disconnect(deviceId: BluetoothDeviceId, reason: DisconnectReason): BluetoothOperationResult {
        if (!isConnected(deviceId)) return BluetoothOperationResult.AlreadyInState
        delay(RELEASE_MS)
        connected.value = connected.value - key(deviceId)
        return BluetoothOperationResult.Requested(STRATEGY, RELEASE_MS)
    }

    override suspend fun verifyConnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.first { key(deviceId) in it } } != null

    override suspend fun verifyDisconnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.first { key(deviceId) !in it } } != null

    private fun key(id: BluetoothDeviceId) = id.address.uppercase()

    companion object {
        const val STRATEGY = "Demo"
        const val AURORA = "0A:DE:40:00:00:01"
        const val STUDIO = "0A:DE:40:00:00:02"
        private const val CONNECT_MS = 1_200L
        private const val RELEASE_MS = 350L

        val DEVICES = listOf(
            AudioDevice(BluetoothDeviceId(AURORA), "Aurora Buds", AudioDeviceKind.HEADSET, true, DeviceFingerprint.of(AURORA)),
            AudioDevice(BluetoothDeviceId(STUDIO), "Studio Headphones", AudioDeviceKind.HEADPHONES, true, DeviceFingerprint.of(STUDIO)),
        )
        private val BATTERY = mapOf(AURORA to 80, STUDIO to 55)
    }
}
