package dev.handoff.bluetooth.publicapi

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import dev.handoff.bluetooth.ProfileProxyProvider
import dev.handoff.bluetooth.api.BluetoothConnectionStrategy
import dev.handoff.bluetooth.api.StrategyResult

/**
 * Public SDK only.
 *
 * As of Android 16 there is **no public, third-party-callable API** that initiates or tears
 * down an A2DP connection to an already-bonded device. (`BluetoothA2dp.connect/disconnect` are
 * hidden; `setConnectionPolicy` is a `@SystemApi` requiring BLUETOOTH_PRIVILEGED.)
 *
 * This strategy therefore only succeeds when the public A2DP state already satisfies the
 * request, and otherwise reports [StrategyResult.NotApplicable] so the controller moves on.
 * It is first in the chain so that a future public API can be added here and automatically
 * take precedence over hidden-API strategies.
 */
internal class PublicApiStrategy(private val proxies: ProfileProxyProvider) : BluetoothConnectionStrategy {
    override val name: String = NAME
    override val usesHiddenApi: Boolean = false

    override fun isSupported(): Boolean = true

    override fun describe(): String = "public SDK: state observation only; no public A2DP connect/disconnect exists"

    override suspend fun connect(device: BluetoothDevice): StrategyResult =
        if (state(device) == BluetoothProfile.STATE_CONNECTED) {
            StrategyResult.Accepted
        } else {
            StrategyResult.NotApplicable("no public SDK method can initiate an A2DP connection")
        }

    override suspend fun disconnect(device: BluetoothDevice): StrategyResult =
        if (state(device) == BluetoothProfile.STATE_DISCONNECTED) {
            StrategyResult.Accepted
        } else {
            StrategyResult.NotApplicable("no public SDK method can end an A2DP connection")
        }

    private fun state(device: BluetoothDevice): Int? = try {
        proxies.current?.getConnectionState(device)
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val NAME = "PublicApiStrategy"
    }
}
