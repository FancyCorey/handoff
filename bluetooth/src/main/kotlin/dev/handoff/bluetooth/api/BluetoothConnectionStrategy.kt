package dev.handoff.bluetooth.api

import android.bluetooth.BluetoothDevice
import dev.handoff.core.bluetooth.BluetoothError

/**
 * One way of asking the Android Bluetooth stack to connect or disconnect A2DP.
 *
 * Strategies are tried in order by the controller. They are internal to the `:bluetooth`
 * module; nothing outside it can call a strategy directly.
 */
interface BluetoothConnectionStrategy {
    val name: String

    /** True if this strategy relies on non-SDK (hidden) Android interfaces. */
    val usesHiddenApi: Boolean

    fun isSupported(): Boolean

    /** Human-readable support detail for diagnostics. */
    fun describe(): String

    suspend fun connect(device: BluetoothDevice): StrategyResult

    suspend fun disconnect(device: BluetoothDevice): StrategyResult
}

sealed interface StrategyResult {
    /** The stack accepted the request. The caller must still verify the resulting state. */
    data object Accepted : StrategyResult

    /** This strategy cannot perform the operation; the next strategy should be tried. */
    data class NotApplicable(val reason: String) : StrategyResult

    data class Failed(val error: BluetoothError, val detail: String) : StrategyResult
}
