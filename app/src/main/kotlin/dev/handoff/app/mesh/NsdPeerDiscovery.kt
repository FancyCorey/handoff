package dev.handoff.app.mesh

import dev.handoff.core.mesh.transport.DiscoveryTags
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.transport.PeerDirectory
import dev.handoff.core.model.PeerId
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * mDNS/DNS-SD advertisement and discovery via Android's [NsdManager] (no location or scan
 * permission required).
 *
 * Advertised: `_handoff._tcp`, service name `handoff-<daily tag>` (see [DiscoveryTags]), TXT
 * `v=2` only, so nothing on the network identifies this device for longer than a day. Only
 * linked devices are resolved; everything else is ignored.
 * Discovery only provides *addresses*; it grants no trust. A spoofed advertisement leads to a
 * failed handshake, because every connection pins the peer's stored identity key.
 */
class NsdPeerDiscovery(
    context: Context,
    private val directory: PeerDirectory,
    private val events: EventLog,
    private val tags: DiscoveryTags,
    private val ownKey: () -> ByteArray,
) {
    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var selfId: PeerId? = null
    private var port = 0
    private var advertisedDay = 0L
    private var rotation: ScheduledFuture<*>? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val legacyResolveQueue = ArrayDeque<NsdServiceInfo>()
    private var legacyResolving = false

    @Synchronized
    fun start(self: PeerId, port: Int) {
        val nsd = nsd ?: return
        stop()
        selfId = self
        this.port = port
        advertisedDay = tags.currentDay()
        val info = NsdServiceInfo().apply {
            serviceName = tags.instanceName(ownKey())
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("v", "2")
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = log("registered ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = log("registration failed $errorCode")
            override fun onServiceUnregistered(info: NsdServiceInfo) = log("unregistered")
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = log("unregistration failed $errorCode")
        }
        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("discovery started")
            override fun onDiscoveryStopped(serviceType: String) = log("discovery stopped")
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = log("discovery failed $errorCode")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = log("stop discovery failed $errorCode")
            override fun onServiceFound(info: NsdServiceInfo) = onFound(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                tags.resolve(info.serviceName)?.let {
                    directory.onDiscoveryLost(it)
                    events.record(EventType.PEER_OFFLINE, peerId = it, details = mapOf("source" to "mdns"))
                }
            }
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            registration = reg
        } catch (e: RuntimeException) {
            log("registerService threw ${e.javaClass.simpleName}")
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
            discovery = disc
        } catch (e: RuntimeException) {
            log("discoverServices threw ${e.javaClass.simpleName}")
        }
        rotation = executor.scheduleWithFixedDelay(::rotateIfNewDay, ROTATION_CHECK_MIN, ROTATION_CHECK_MIN, TimeUnit.MINUTES)
    }

    /** The advertised tag changes once a day; re-register under the new name. */
    @Synchronized
    private fun rotateIfNewDay() {
        val self = selfId ?: return
        if (registration != null && tags.currentDay() != advertisedDay) start(self, port)
    }

    @Synchronized
    fun stop() {
        rotation?.cancel(false)
        rotation = null
        val nsd = nsd ?: return
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        infoCallbacks.clear()
        legacyResolveQueue.clear()
        legacyResolving = false
        registration = null
        discovery = null
    }

    private fun onFound(info: NsdServiceInfo) {
        val peer = tags.resolve(info.serviceName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveModern(info, peer)
        } else {
            enqueueLegacy(info)
        }
    }

    /** Android 14+: callback-based resolution that also tracks address changes. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveModern(info: NsdServiceInfo, peer: PeerId) {
        val nsd = nsd ?: return
        if (infoCallbacks.containsKey(peer.value)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                infoCallbacks.remove(peer.value)
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                val address = updated.hostAddresses.firstOrNull { it is Inet4Address } ?: updated.hostAddresses.firstOrNull()
                address?.let { onResolved(updated, it) }
            }

            override fun onServiceLost() {
                directory.onDiscoveryLost(peer)
            }

            override fun onServiceInfoCallbackUnregistered() {
                infoCallbacks.remove(peer.value)
            }
        }
        try {
            nsd.registerServiceInfoCallback(info, executor, callback)
            infoCallbacks[peer.value] = callback
        } catch (e: RuntimeException) {
            log("registerServiceInfoCallback threw ${e.javaClass.simpleName}")
        }
    }

    /** Before Android 14 only one resolve may run at a time, so resolutions are queued. */
    @Synchronized
    private fun enqueueLegacy(info: NsdServiceInfo) {
        legacyResolveQueue.addLast(info)
        if (!legacyResolving) resolveNextLegacy()
    }

    @Synchronized
    @Suppress("DEPRECATION")
    private fun resolveNextLegacy() {
        val nsd = nsd ?: return
        val next = legacyResolveQueue.removeFirstOrNull()
        if (next == null) {
            legacyResolving = false
            return
        }
        legacyResolving = true
        try {
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolveNextLegacy()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        info.host?.let { onResolved(info, it) }
                        resolveNextLegacy()
                    }
                },
            )
        } catch (e: RuntimeException) {
            legacyResolving = false
        }
    }

    private fun onResolved(info: NsdServiceInfo, address: InetAddress) {
        val peer = tags.resolve(info.serviceName) ?: return
        val wasOnline = directory.isOnline(peer)
        directory.onDiscovered(peer, address.hostAddress ?: return, info.port)
        if (!wasOnline) events.record(EventType.PEER_ONLINE, peerId = peer, details = mapOf("source" to "mdns"))
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "HandoffNsd"
        const val SERVICE_TYPE = "_handoff._tcp."
        const val ROTATION_CHECK_MIN = 10L
    }
}
