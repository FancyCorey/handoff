package dev.handoff.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits this device's set of LAN addresses whenever it may have changed (Wi-Fi switch,
 * Ethernet, hotspot). A new value means the host is on a different network, so discovery must
 * restart there; pairings are unaffected because they are bound to identity keys, not networks.
 */
class NetworkMonitor(private val context: Context) {

    @OptIn(FlowPreview::class)
    fun lanAddresses(): Flow<Set<String>> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val emit = { trySend(NetworkAddresses.lanIpv4(context).toSet()) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                emit()
            }

            override fun onLost(network: Network) {
                emit()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                emit()
            }
        }
        // Turning this device's own hotspot on or off does not change its default network.
        val tethering = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                emit()
            }
        }
        emit()
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess
        val tetherRegistered = runCatching {
            val filter = IntentFilter(ACTION_TETHER_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(tethering, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(tethering, filter)
            }
        }.isSuccess
        awaitClose {
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
            if (tetherRegistered) runCatching { context.unregisterReceiver(tethering) }
        }
    }.debounce(SETTLE_MS).distinctUntilChanged()

    private companion object {
        /** Networks flap while switching; act once things settle. */
        const val SETTLE_MS = 2_000L

        /** ConnectivityManager.ACTION_TETHER_STATE_CHANGED (a hidden constant, but a public broadcast). */
        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    }
}
