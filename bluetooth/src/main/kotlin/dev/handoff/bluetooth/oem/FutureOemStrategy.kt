package dev.handoff.bluetooth.oem

import android.bluetooth.BluetoothDevice
import dev.handoff.bluetooth.api.BluetoothConnectionStrategy
import dev.handoff.bluetooth.api.StrategyResult

/**
 * Extension point for OEM-specific or privileged mechanisms (e.g. a vendor SDK, or an opt-in
 * Shizuku mode). **Not implemented**: it always reports unsupported and is never selected.
 * It exists so the strategy chain and diagnostics already account for a third option.
 */
internal class FutureOemStrategy : BluetoothConnectionStrategy {
    override val name: String = "FutureOemStrategy"
    override val usesHiddenApi: Boolean = false

    override fun isSupported(): Boolean = false

    override fun describe(): String = "not implemented (placeholder for OEM/privileged strategies)"

    override suspend fun connect(device: BluetoothDevice): StrategyResult =
        StrategyResult.NotApplicable("not implemented")

    override suspend fun disconnect(device: BluetoothDevice): StrategyResult =
        StrategyResult.NotApplicable("not implemented")
}
