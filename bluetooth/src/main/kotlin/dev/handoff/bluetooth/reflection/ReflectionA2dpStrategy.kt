/*
 * The idea of invoking the hidden BluetoothA2dp.connect(BluetoothDevice) through reflection on
 * the A2DP profile proxy, followed by polling the public A2DP connection state, is adapted from
 * PodSwitch by Felip6499 (https://github.com/Felip6499/PodSwitch), MIT License,
 * Copyright (c) 2026 Felip6499. See THIRD_PARTY_NOTICES.md for the full license text.
 */
package dev.handoff.bluetooth.reflection

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import dev.handoff.bluetooth.ProfileProxyProvider
import dev.handoff.bluetooth.api.BluetoothConnectionStrategy
import dev.handoff.bluetooth.api.StrategyResult
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.MethodAvailability

/**
 * EXPERIMENTAL / NON-SDK. Calls the hidden `BluetoothA2dp.connect(BluetoothDevice)` and
 * `BluetoothA2dp.disconnect(BluetoothDevice)` methods.
 *
 * Both are `@hide @UnsupportedAppUsage` in AOSP (verified against the API 34 framework source):
 * reachable by reflection for third-party apps today, guarded only by BLUETOOTH_CONNECT, but
 * outside the public SDK and free to disappear or be blocked in any Android release or OEM
 * build. They also block the calling thread on a synchronous binder round-trip, so they are
 * only ever invoked off the main thread via [HiddenMethodInvoker].
 *
 * A method that fails to resolve is marked [MethodAvailability.MISSING] for the life of the
 * process and never retried, so an unsupported build is not hammered.
 */
internal class ReflectionA2dpStrategy(
    private val proxies: ProfileProxyProvider,
    private val invoker: HiddenMethodInvoker = HiddenMethodInvoker(),
) : BluetoothConnectionStrategy {

    override val name: String = NAME
    override val usesHiddenApi: Boolean = true

    @Volatile var connectAvailability = MethodAvailability.UNKNOWN
        private set

    @Volatile var disconnectAvailability = MethodAvailability.UNKNOWN
        private set

    @Volatile private var lastDetail = "not probed yet"

    override fun isSupported(): Boolean =
        !(connectAvailability == MethodAvailability.MISSING && disconnectAvailability == MethodAvailability.MISSING)

    override fun describe(): String = "non-SDK hidden API; connect=$connectAvailability, disconnect=$disconnectAvailability ($lastDetail)"

    /** Resolve both methods without invoking them. */
    fun probe() {
        lastDetail = "resolved by probe"
        connectAvailability = availabilityOf(CONNECT)
        disconnectAvailability = availabilityOf(DISCONNECT)
    }

    override suspend fun connect(device: BluetoothDevice): StrategyResult = call(CONNECT, device)

    override suspend fun disconnect(device: BluetoothDevice): StrategyResult = call(DISCONNECT, device)

    private fun availabilityOf(method: String): MethodAvailability =
        when (val r = invoker.resolve(BluetoothA2dp::class.java, method, BluetoothDevice::class.java)) {
            is HiddenMethodInvoker.Resolution.Found -> MethodAvailability.AVAILABLE
            is HiddenMethodInvoker.Resolution.Missing -> {
                lastDetail = r.detail
                MethodAvailability.MISSING
            }
        }

    private suspend fun call(method: String, device: BluetoothDevice): StrategyResult {
        if (availability(method) == MethodAvailability.MISSING) {
            return StrategyResult.Failed(BluetoothError.UNSUPPORTED, "hidden $method() is unavailable on this Android build")
        }
        val proxy = proxies.await(PROXY_TIMEOUT_MS)
            ?: return StrategyResult.Failed(BluetoothError.PROFILE_UNAVAILABLE, "A2DP profile proxy not connected")

        return when (val result = invoker.invokeBoolean(proxy, method, device, BluetoothDevice::class.java)) {
            is HiddenMethodInvoker.Invocation.Returned -> {
                setAvailability(method, MethodAvailability.AVAILABLE)
                if (result.value) {
                    StrategyResult.Accepted
                } else {
                    // The framework returns false for "already in that state" as well as refusals.
                    StrategyResult.Failed(BluetoothError.REJECTED, "$method() returned false")
                }
            }
            is HiddenMethodInvoker.Invocation.MethodMissing -> {
                lastDetail = result.detail
                setAvailability(method, MethodAvailability.MISSING)
                StrategyResult.Failed(BluetoothError.UNSUPPORTED, result.detail)
            }
            is HiddenMethodInvoker.Invocation.Denied -> {
                setAvailability(method, MethodAvailability.BLOCKED)
                StrategyResult.Failed(BluetoothError.PERMISSION_DENIED, "$method(): ${result.detail}")
            }
            is HiddenMethodInvoker.Invocation.Threw -> StrategyResult.Failed(BluetoothError.INTERNAL, "$method() threw ${result.detail}")
            HiddenMethodInvoker.Invocation.TimedOut -> StrategyResult.Failed(BluetoothError.TIMEOUT, "$method() did not return in time")
        }
    }

    private fun availability(method: String) = if (method == CONNECT) connectAvailability else disconnectAvailability

    private fun setAvailability(method: String, value: MethodAvailability) {
        if (method == CONNECT) connectAvailability = value else disconnectAvailability = value
    }

    companion object {
        const val NAME = "ReflectionA2dpStrategy"
        private const val CONNECT = "connect"
        private const val DISCONNECT = "disconnect"
        private const val PROXY_TIMEOUT_MS = 3_000L
    }
}
