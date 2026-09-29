package dev.handoff.app.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN IPv4 addresses of this device, for the pairing QR code and network-change detection. */
object NetworkAddresses {
    @Suppress("DEPRECATION") // allNetworks: a one-shot snapshot is exactly what the QR code needs.
    fun lanIpv4(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return hotspotIpv4()
        val joined = try {
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
        } catch (_: SecurityException) {
            emptyList()
        }
        return (joined + hotspotIpv4()).distinct()
    }

    /**
     * Addresses of this device's own hotspot (Wi-Fi, USB or Bluetooth tethering). The hotspot
     * is a local network the device hosts, not one it joins, so ConnectivityManager does not list
     * it. Only private addresses on tethering interfaces qualify; mobile data never does.
     */
    private fun hotspotIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && TETHERING.matches(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    private val TETHERING = Regex("""^(ap|swlan|softap|wlan|rndis|ncm|bt-pan|p2p)[-\w]*$""")
}
