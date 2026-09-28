package dev.handoff.app.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
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
        emit()
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }.debounce(SETTLE_MS).distinctUntilChanged()

    private companion object {
        /** Networks flap while switching; act once things settle. */
        const val SETTLE_MS = 2_000L
    }
}
