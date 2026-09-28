package dev.handoff.bluetooth.reflection

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import dev.handoff.bluetooth.ProfileProxyProvider
import dev.handoff.core.bluetooth.MethodAvailability

/**
 * EXPERIMENTAL / NON-SDK. Releases the *other* audio profiles a headset holds besides A2DP.
 *
 * A single-point headset stays attached to a host as long as any profile is connected; typically
 * HFP (calls) survives an A2DP-only disconnect, and the headset then refuses the new host.
 * Found on hardware: a Galaxy Tab could not take OnePlus Bullets Wireless Z2 from a Galaxy S22
 * because only A2DP had been released.
 *
 * `BluetoothHeadset.disconnect(BluetoothDevice)` is `@hide @SystemApi` (API 34 source, guarded
 * by BLUETOOTH_CONNECT only); `BluetoothLeAudio.disconnect(BluetoothDevice)` is `@hide`. Either
 * may be blocked for third-party apps; failures are reported, never thrown.
 */
internal class CompanionProfileReleaser(
    private val profiles: List<Pair<String, ProfileProxyProvider>>,
    private val invoker: HiddenMethodInvoker,
) {
    private val availability = mutableMapOf<String, MethodAvailability>()

    fun availability(): Map<String, MethodAvailability> =
        profiles.associate { (name, _) -> name to (availability[name] ?: MethodAvailability.UNKNOWN) }

    fun probe() {
        for ((name, provider) in profiles) {
            val proxy = provider.current ?: continue
            availability[name] = when (invoker.resolve(proxy.javaClass, DISCONNECT, BluetoothDevice::class.java)) {
                is HiddenMethodInvoker.Resolution.Found -> MethodAvailability.AVAILABLE
                is HiddenMethodInvoker.Resolution.Missing -> MethodAvailability.MISSING
            }
        }
    }

    /** Names of non-A2DP audio profiles currently connected (or connecting) to [device]. */
    fun connected(device: BluetoothDevice): List<String> = profiles.mapNotNull { (name, provider) ->
        val state = try {
            provider.current?.getConnectionState(device)
        } catch (_: SecurityException) {
            null
        }
        name.takeIf { state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING }
    }

    /** Best-effort disconnect of every connected companion profile. Returns one line per attempt. */
    suspend fun releaseAll(device: BluetoothDevice): List<String> {
        val outcomes = mutableListOf<String>()
        for ((name, provider) in profiles) {
            if (name !in connected(device)) continue
            val proxy = provider.await(PROXY_TIMEOUT_MS)
            if (proxy == null) {
                outcomes += "$name: proxy unavailable"
                continue
            }
            val result = invoker.invokeBoolean(proxy, DISCONNECT, device, BluetoothDevice::class.java)
            availability[name] = when (result) {
                is HiddenMethodInvoker.Invocation.MethodMissing -> MethodAvailability.MISSING
                is HiddenMethodInvoker.Invocation.Denied -> MethodAvailability.BLOCKED
                else -> MethodAvailability.AVAILABLE
            }
            outcomes += "$name: ${
                when (result) {
                    is HiddenMethodInvoker.Invocation.Returned -> if (result.value) "released" else "refused"
                    is HiddenMethodInvoker.Invocation.MethodMissing -> "unavailable (${result.detail})"
                    is HiddenMethodInvoker.Invocation.Denied -> "denied (${result.detail})"
                    is HiddenMethodInvoker.Invocation.Threw -> "threw ${result.detail}"
                    HiddenMethodInvoker.Invocation.TimedOut -> "timed out"
                }
            }"
        }
        return outcomes
    }

    private companion object {
        const val DISCONNECT = "disconnect"
        const val PROXY_TIMEOUT_MS = 2_000L
    }
}
