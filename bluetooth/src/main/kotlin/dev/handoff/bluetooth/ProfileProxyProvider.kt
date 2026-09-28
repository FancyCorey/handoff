package dev.handoff.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds one public profile proxy (`BluetoothAdapter.getProfileProxy(profile)`): A2DP, HEADSET
 * (HFP, calls) or LE_AUDIO. Delivered asynchronously; disappears whenever Bluetooth turns off.
 */
internal class ProfileProxyProvider(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    val profile: Int,
    private val onChange: () -> Unit,
) {
    private val _proxy = MutableStateFlow<BluetoothProfile?>(null)
    val proxy: StateFlow<BluetoothProfile?> = _proxy.asStateFlow()
    val current: BluetoothProfile? get() = _proxy.value

    @Volatile private var requested = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == this@ProfileProxyProvider.profile) {
                _proxy.value = proxy
                onChange()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == this@ProfileProxyProvider.profile) {
                _proxy.value = null
                requested = false
                onChange()
            }
        }
    }

    @Synchronized
    fun acquire() {
        if (requested || _proxy.value != null || adapter == null) return
        requested = try {
            adapter.getProfileProxy(context, listener, profile)
        } catch (_: RuntimeException) {
            false
        }
    }

    suspend fun await(timeoutMs: Long): BluetoothProfile? {
        current?.let { return it }
        acquire()
        return withTimeoutOrNull(timeoutMs) { _proxy.filterNotNull().first() }
    }

    /** Bluetooth turned off: the proxy is dead even if the disconnect callback is late. */
    @Synchronized
    fun invalidate() {
        _proxy.value = null
        requested = false
    }
}
