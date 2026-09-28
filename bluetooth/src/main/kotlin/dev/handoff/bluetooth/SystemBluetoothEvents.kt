package dev.handoff.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Listens to the Bluetooth system broadcasts that can change what Handoff shows. All of these
 * are protected broadcasts (only the system/Bluetooth stack can send them), so the receiver is
 * registered exported — RECEIVER_NOT_EXPORTED can drop broadcasts sent from the Bluetooth
 * process on some builds.
 */
internal class SystemBluetoothEvents(
    private val context: Context,
    private val onEvent: (action: String, intent: Intent) -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onEvent(intent.action ?: return, intent)
        }
    }

    @Synchronized
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
