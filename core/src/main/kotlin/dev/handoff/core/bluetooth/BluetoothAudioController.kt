package dev.handoff.core.bluetooth

import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.BluetoothDeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The domain's only view of the local Bluetooth stack.
 *
 * Implementations own every platform detail, including any non-SDK (hidden API) access.
 * Nothing above this interface may know *how* a connect or disconnect is performed.
 */
interface BluetoothAudioController {

    val adapterState: StateFlow<AdapterState>

    fun bondedAudioDevices(): Flow<List<AudioDevice>>

    fun connectionState(deviceId: BluetoothDeviceId): Flow<AudioConnectionState>

    /** Current A2DP state, waiting briefly for the profile proxy if needed. */
    suspend fun isConnected(deviceId: BluetoothDeviceId): Boolean

    suspend fun connect(deviceId: BluetoothDeviceId, reason: ConnectReason): BluetoothOperationResult

    suspend fun disconnect(deviceId: BluetoothDeviceId, reason: DisconnectReason): BluetoothOperationResult

    /**
     * Wait until the A2DP profile reports CONNECTED for [deviceId], or [timeoutMs] elapses.
     * A successful [connect] is only a request; this is the proof.
     */
    suspend fun verifyConnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean

    /**
     * Wait until the headset is fully released by this host: A2DP *and* every other audio
     * profile (HFP calls, LE Audio) disconnected, or [timeoutMs] elapses. A single-point headset
     * that still holds a call profile refuses connections from other hosts.
     */
    suspend fun verifyDisconnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean

    /**
     * Last known battery level (0..100) per headset, keyed by upper-case Bluetooth address.
     * Best effort: many headsets don't report it, and platforms expose it differently.
     */
    val batteryLevels: StateFlow<Map<String, Int>> get() = NoBattery.levels

    /** Audio profiles ("A2DP", "HFP", "LE_AUDIO") currently connected to [deviceId]. */
    suspend fun connectedProfiles(deviceId: BluetoothDeviceId): Set<String> =
        if (isConnected(deviceId)) setOf("A2DP") else emptySet()
}

private object NoBattery {
    val levels: StateFlow<Map<String, Int>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
}

enum class AdapterState { ON, OFF, TURNING_ON, TURNING_OFF, NO_PERMISSION, NOT_AVAILABLE }

enum class ConnectReason { USER_MOVE_HERE, DIRECT_TAKEOVER, RETRY, AUTOMATIC, DEBUG }

enum class DisconnectReason { PEER_RELEASE_REQUEST, USER, DEBUG }

/** Structured Bluetooth failure categories. Never a raw exception. */
enum class BluetoothError {
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    DEVICE_NOT_BONDED,

    /** The A2DP profile proxy could not be obtained in time. */
    PROFILE_UNAVAILABLE,

    /** No strategy on this Android build can perform the operation. */
    UNSUPPORTED,

    /** The stack refused the request (hidden call returned false). */
    REJECTED,
    TIMEOUT,

    /** Local safety throttle tripped to avoid hammering the Bluetooth stack. */
    RATE_LIMITED,
    INTERNAL,
}

sealed interface BluetoothOperationResult {
    /** The request was accepted by [strategy]. Verification is still required. */
    data class Requested(val strategy: String, val durationMs: Long) : BluetoothOperationResult

    /** The device was already in the requested state; nothing was done. */
    data object AlreadyInState : BluetoothOperationResult

    data class Failed(
        val error: BluetoothError,
        val strategy: String?,
        val detail: String,
    ) : BluetoothOperationResult
}

/** How much trust the UI should place in Bluetooth switching on this Android build. */
enum class CompatibilityLevel {
    /** A connect through the active strategy was verified on this device before. */
    SUPPORTED,

    /** The required calls resolve, but no verified switch has happened yet. */
    EXPERIMENTAL,

    /** The required calls are missing or blocked on this build. */
    UNSUPPORTED,
}
