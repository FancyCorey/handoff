/*
 * Releasing and connecting a headset by toggling its audio services is adapted from
 * PodSwitch by Felip6499 (https://github.com/Felip6499/PodSwitch), MIT License,
 * Copyright (c) 2026 Felip6499. See THIRD_PARTY_NOTICES.md for the full license text.
 */
package dev.handoff.desktop.bluetooth

import dev.handoff.core.bluetooth.DemoBluetoothAudioController
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.bluetooth.DisconnectReason
import dev.handoff.core.bluetooth.OperationThrottle
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.DeviceFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.sun.jna.platform.win32.Guid

/** Records which headsets Handoff turned off on this PC, so they can be restored. */
interface ReleasedServicesStore {
    val released: StateFlow<Set<String>>
    suspend fun setReleased(address: String, released: Boolean)
}

/**
 * Windows implementation of [BluetoothAudioController], using only documented Win32 APIs.
 *
 *  - **Release:** disable the headset's audio services (A2DP sink, hands-free, headset). Windows
 *    drops the link and, importantly, does *not* auto-reconnect it while the services are off,
 *    so the old host can't steal the headset back.
 *  - **Connect:** disable then re-enable those services; Windows then pages the headset and
 *    reinstalls its audio endpoints (this can take several seconds).
 *  - **State:** `BLUETOOTH_DEVICE_INFO.fConnected` (the link state), polled; Windows has no
 *    public per-profile A2DP state.
 */
class WindowsBluetoothAudioController(
    private val scope: CoroutineScope,
    private val releasedStore: ReleasedServicesStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Made-up headsets for screenshots (`-Dhandoff.demo=true`); the real Bluetooth stack is left alone. */
    private val demo: DemoBluetoothAudioController? = null,
) : BluetoothAudioController {

    private val snapshot = MutableStateFlow<List<WinBtDevice>>(emptyList())
    private val _adapterState = MutableStateFlow(AdapterState.NOT_AVAILABLE)
    override val adapterState: StateFlow<AdapterState> = demo?.adapterState ?: _adapterState.asStateFlow()

    private val throttle = OperationThrottle(maxOperations = 12, windowMs = 60_000, clock = clock)

    data class Operation(val description: String, val atMs: Long)

    private val _batteryLevels = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Only for headsets connected to this PC: Windows keeps a stale value after disconnecting. */
    override val batteryLevels: StateFlow<Map<String, Int>> = demo?.batteryLevels ?: _batteryLevels.asStateFlow()

    private val _lastOperation = MutableStateFlow<Operation?>(null)
    val lastOperation: StateFlow<Operation?> = _lastOperation.asStateFlow()

    fun start() {
        if (demo != null) return
        scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                refresh()
                if (tick++ % BATTERY_EVERY_N_POLLS == 0) refreshBattery()
                delay(POLL_MS)
            }
        }
    }

    fun refresh() {
        if (demo != null) return
        _adapterState.value = when {
            !Win32Bluetooth.available() -> AdapterState.NOT_AVAILABLE
            Win32Bluetooth.radioOn() -> AdapterState.ON
            else -> AdapterState.OFF
        }
        snapshot.value = if (_adapterState.value == AdapterState.ON) Win32Bluetooth.rememberedDevices() else emptyList()
    }

    private fun refreshBattery() {
        val connected = snapshot.value.filter { it.isAudio && it.connected }.map { it.address }
        _batteryLevels.value = WinBattery.levels(connected)
    }

    // ---- observation -------------------------------------------------------------------

    override fun bondedAudioDevices(): Flow<List<AudioDevice>> = demo?.bondedAudioDevices() ?: snapshot.map { list ->
        list.filter { it.isAudio }.map { it.toAudioDevice() }.sortedBy { it.name.lowercase() }
    }.distinctUntilChanged()

    override fun connectionState(deviceId: BluetoothDeviceId): Flow<AudioConnectionState> = demo?.connectionState(deviceId) ?: snapshot.map { list ->
        when {
            _adapterState.value != AdapterState.ON -> AudioConnectionState.UNAVAILABLE
            else -> when (list.firstOrNull { it.address.equals(deviceId.address, ignoreCase = true) }?.connected) {
                true -> AudioConnectionState.CONNECTED
                false -> AudioConnectionState.DISCONNECTED
                null -> AudioConnectionState.UNAVAILABLE
            }
        }
    }.distinctUntilChanged()

    override suspend fun isConnected(deviceId: BluetoothDeviceId): Boolean = demo?.isConnected(deviceId) ?: withContext(Dispatchers.IO) {
        Win32Bluetooth.device(deviceId.address)?.connected == true
    }

    override suspend fun connectedProfiles(deviceId: BluetoothDeviceId): Set<String> =
        if (isConnected(deviceId)) setOf("BLUETOOTH") else emptySet()

    override suspend fun verifyConnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean = demo?.verifyConnected(deviceId, timeoutMs) ?:
        awaitLink(deviceId, connected = true, timeoutMs)

    override suspend fun verifyDisconnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean = demo?.verifyDisconnected(deviceId, timeoutMs) ?:
        awaitLink(deviceId, connected = false, timeoutMs)

    private suspend fun awaitLink(deviceId: BluetoothDeviceId, connected: Boolean, timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                while (Win32Bluetooth.device(deviceId.address)?.connected != connected) delay(VERIFY_POLL_MS)
                true
            } ?: false
        }.also { refresh() }

    // ---- operations --------------------------------------------------------------------

    override suspend fun connect(deviceId: BluetoothDeviceId, reason: ConnectReason): BluetoothOperationResult = demo?.connect(deviceId, reason) ?:
        withContext(Dispatchers.IO) {
            val started = clock()
            precheck(deviceId)?.let { return@withContext it }
            if (Win32Bluetooth.device(deviceId.address)?.connected == true) return@withContext BluetoothOperationResult.AlreadyInState
            if (!throttle.tryAcquire()) return@withContext rateLimited()

            // Off first (quietly: a service may not be installed), then on in order: calls, then media.
            SERVICES.forEach { Win32Bluetooth.setServiceState(deviceId.address, it, enable = false) }
            delay(TOGGLE_GAP_MS)
            var mediaResult = -1
            for (service in listOf(BthProps.HANDS_FREE, BthProps.HEADSET, BthProps.A2DP_SINK)) {
                val result = enableWithRetry(deviceId.address, service)
                if (service == BthProps.A2DP_SINK) mediaResult = result
            }
            releasedStore.setReleased(deviceId.address, false)
            record("connect: A2DP enable=${describe(mediaResult)}")
            when (mediaResult) {
                0 -> BluetoothOperationResult.Requested(STRATEGY, clock() - started)
                ERROR_ACCESS_DENIED -> BluetoothOperationResult.Failed(BluetoothError.PERMISSION_DENIED, STRATEGY, "Windows denied changing Bluetooth services")
                else -> BluetoothOperationResult.Failed(BluetoothError.REJECTED, STRATEGY, "enabling the audio service failed (${describe(mediaResult)})")
            }
        }

    override suspend fun disconnect(deviceId: BluetoothDeviceId, reason: DisconnectReason): BluetoothOperationResult = demo?.disconnect(deviceId, reason) ?:
        withContext(Dispatchers.IO) {
            val started = clock()
            precheck(deviceId)?.let { return@withContext it }
            if (!throttle.tryAcquire()) return@withContext rateLimited()
            val results = SERVICES.associateWith { Win32Bluetooth.setServiceState(deviceId.address, it, enable = false) }
            val media = results.getValue(BthProps.A2DP_SINK)
            releasedStore.setReleased(deviceId.address, true)
            record("disconnect: " + results.entries.joinToString { "${name(it.key)}=${describe(it.value)}" })
            when (media) {
                0, ERROR_SERVICE_DOES_NOT_EXIST -> BluetoothOperationResult.Requested(STRATEGY, clock() - started)
                ERROR_ACCESS_DENIED -> BluetoothOperationResult.Failed(BluetoothError.PERMISSION_DENIED, STRATEGY, "Windows denied changing Bluetooth services")
                else -> BluetoothOperationResult.Failed(BluetoothError.REJECTED, STRATEGY, "disabling the audio service failed (${describe(media)})")
            }
        }

    /**
     * Turn the audio services back on for a headset Handoff released, so Windows uses it again.
     * This makes Windows connect to it.
     */
    suspend fun restore(deviceId: BluetoothDeviceId): BluetoothOperationResult = connect(deviceId, ConnectReason.USER_MOVE_HERE)

    private fun precheck(deviceId: BluetoothDeviceId): BluetoothOperationResult.Failed? {
        refresh()
        return when {
            _adapterState.value == AdapterState.NOT_AVAILABLE ->
                BluetoothOperationResult.Failed(BluetoothError.UNSUPPORTED, STRATEGY, "no Bluetooth support on this PC")
            _adapterState.value != AdapterState.ON ->
                BluetoothOperationResult.Failed(BluetoothError.BLUETOOTH_OFF, STRATEGY, "Bluetooth is off")
            snapshot.value.none { it.address.equals(deviceId.address, ignoreCase = true) } ->
                BluetoothOperationResult.Failed(BluetoothError.DEVICE_NOT_BONDED, STRATEGY, "the headset isn't paired with this PC")
            else -> null
        }
    }

    private suspend fun enableWithRetry(address: String, service: Guid.GUID): Int {
        var result = -1
        repeat(ENABLE_RETRIES) {
            result = Win32Bluetooth.setServiceState(address, service, enable = true)
            if (result == 0 || result == ERROR_SERVICE_DOES_NOT_EXIST || result == ERROR_ACCESS_DENIED) return result
            delay(ENABLE_RETRY_DELAY_MS)
        }
        return result
    }

    private fun rateLimited() =
        BluetoothOperationResult.Failed(BluetoothError.RATE_LIMITED, STRATEGY, "too many Bluetooth operations; paused briefly")

    private fun record(text: String) {
        _lastOperation.value = Operation(text, clock())
    }

    private fun WinBtDevice.toAudioDevice() = AudioDevice(
        id = BluetoothDeviceId(address),
        name = name,
        kind = when (minorClass) {
            0x06 -> AudioDeviceKind.HEADPHONES
            0x01, 0x02 -> AudioDeviceKind.HEADSET
            0x05, 0x07, 0x0A -> AudioDeviceKind.SPEAKER
            0x08 -> AudioDeviceKind.CAR_AUDIO
            else -> AudioDeviceKind.OTHER_AUDIO
        },
        likelyA2dp = true,
        fingerprint = DeviceFingerprint.of(address),
    )

    companion object {
        const val STRATEGY = "WindowsServiceToggle"
        private const val POLL_MS = 1_500L
        private const val BATTERY_EVERY_N_POLLS = 10
        private const val VERIFY_POLL_MS = 400L
        private const val TOGGLE_GAP_MS = 1_000L
        private const val ENABLE_RETRIES = 3
        private const val ENABLE_RETRY_DELAY_MS = 1_000L
        private const val ERROR_ACCESS_DENIED = 5
        private const val ERROR_SERVICE_DOES_NOT_EXIST = 1060
        private val SERVICES = listOf(BthProps.A2DP_SINK, BthProps.HANDS_FREE, BthProps.HEADSET)

        private fun name(guid: Guid.GUID) = when (guid) {
            BthProps.A2DP_SINK -> "A2DP"
            BthProps.HANDS_FREE -> "HFP"
            else -> "HSP"
        }

        private fun describe(code: Int) = when (code) {
            0 -> "ok"
            ERROR_ACCESS_DENIED -> "access denied"
            ERROR_SERVICE_DOES_NOT_EXIST -> "not installed"
            else -> "error $code"
        }
    }
}
