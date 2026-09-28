package dev.handoff.app.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** LAN IPv4 addresses of this device, for the pairing QR code. */
object NetworkAddresses {
    @Suppress("DEPRECATION") // allNetworks: a one-shot snapshot is exactly what the QR code needs.
    fun lanIpv4(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return try {
            cm.allNetworks
                .filter { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                }
                .flatMap { network -> cm.getLinkProperties(network)?.linkAddresses.orEmpty() }
                .map { it.address }
                .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
