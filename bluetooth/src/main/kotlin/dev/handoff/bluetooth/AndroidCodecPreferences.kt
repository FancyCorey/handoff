package dev.handoff.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import dev.handoff.core.model.BluetoothDeviceId
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opt-in SBC preference for an already bonded A2DP headset, scoped to its local address. */
class AndroidCodecPreferences internal constructor(
    private val context: Context,
    private val proxies: ProfileProxyProvider,
    private val bondedDevice: (BluetoothDeviceId) -> BluetoothDevice?,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
    enabled: Boolean,
) {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    val supported: Boolean = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private val status = MutableStateFlow<Map<BluetoothDeviceId, CodecPreferenceStatus>>(emptyMap())
    val states: StateFlow<Map<BluetoothDeviceId, CodecPreferenceStatus>> = status.asStateFlow()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val links = MutableStateFlow<Map<String, Link>>(emptyMap())
    private val accessChanges = MutableStateFlow<Map<String, Long>>(emptyMap())

    private data class Link(
        val connection: Int? = null,
        val session: SbcConnection = SbcConnection(),
        val playing: Boolean? = null,
        val broadcast: CodecBroadcast? = null,
    )

    private data class CodecBroadcast(val codec: String, val sequence: Long)

    private val audioChanges = callbackFlow {
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                trySend(Unit)
            }
        }
        try {
            audio?.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        } catch (_: RuntimeException) {
            // A2DP playing broadcasts can still signal a pause.
        }
        awaitClose { runCatching { audio?.unregisterAudioPlaybackCallback(callback) } }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    @Suppress("DEPRECATION")
    fun hasAccess(id: BluetoothDeviceId): Boolean {
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 35) {
                manager?.myAssociations.orEmpty().any { matches(it, id.address) }
            } else {
                // The legacy list filters out application-managed associations for us.
                manager?.associations.orEmpty().any { it.equals(id.address, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /** The caller launches the supplied Android consent activity with its intent sender. */
    fun requestAccess(id: BluetoothDeviceId, onPrompt: (IntentSender) -> Unit, onResult: (Boolean) -> Unit) {
        context.mainExecutor.execute {
            if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onResult(false)
                return@execute
            }
            val device = eligibleDevice(id)
            if (device == null) {
                onResult(false)
                return@execute
            }
            if (hasAccess(id)) {
                notifyAccessChanged(id.address)
                onResult(true)
                return@execute
            }
            associate(device, onPrompt, onResult)
        }
    }

    fun canRequestAccess(id: BluetoothDeviceId): Boolean = supported && eligibleDevice(id) != null

    private fun eligibleDevice(id: BluetoothDeviceId): BluetoothDevice? =
        runCatching { bondedDevice(id)?.takeUnless(::isLeOnly) }.getOrNull()

    private fun isLeOnly(device: BluetoothDevice): Boolean = try {
        device.type == BluetoothDevice.DEVICE_TYPE_LE
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    @RequiresApi(33)
    private fun associate(device: BluetoothDevice, onPrompt: (IntentSender) -> Unit, onResult: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        fun finish(approved: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            notifyAccessChanged(device.address)
            onResult(approved)
        }
        try {
            val service = manager ?: return finish(false)
            val request = AssociationRequest.Builder()
                .setSingleDevice(true)
                .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
                .build()
            service.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    if (completed.get()) return
                    try {
                        onPrompt(intentSender)
                    } catch (_: Exception) {
                        finish(false)
                    }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    finish(matches(associationInfo, device.address) && hasAccess(BluetoothDeviceId(device.address)))
                }

                override fun onFailure(errorMessage: CharSequence?) = finish(false)
            })
        } catch (_: RuntimeException) {
            finish(false)
        }
    }

    @RequiresApi(33)
    private fun matches(association: AssociationInfo, address: String): Boolean {
        if (Build.VERSION.SDK_INT >= 35 && association.isSelfManaged) return false
        return association.deviceMacAddress?.toString().equals(address, ignoreCase = true)
    }

    private fun notifyAccessChanged(address: String) {
        val key = address.uppercase()
        accessChanges.update { it + (key to ((it[key] ?: 0L) + 1L)) }
    }

    /** Keep this coroutine alive while the local preference is enabled. Cancellation stops it. */
    suspend fun maintainSbc(id: BluetoothDeviceId) = withContext(Dispatchers.IO) {
        val key = id.address.uppercase()
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        mutex.withLock {
            fun report(value: CodecPreferenceStatus) = status.update { it + (id to value) }
            try {
                if (!supported) {
                    report(CodecPreferenceStatus.UNAVAILABLE)
                    awaitCancellation()
                }
                val connectedLink = links.map { it[key]?.let { link -> link.session to link.connection } }.distinctUntilChanged()
                val access = accessChanges.map { it[key] }.distinctUntilChanged()
                // Seed an existing connection even if it opened before our process started.
                links.update { if (key in it) it else it + (key to Link()) }
                combine(proxies.proxy, connectedLink, access) { proxy, link, _ -> proxy to link }
                    .collectLatest { (profile, link) ->
                        if (!hasAccess(id)) {
                            report(CodecPreferenceStatus.ACCESS_NEEDED)
                            return@collectLatest
                        }
                        val proxy = profile as? BluetoothA2dp
                        val device = eligibleDevice(id)
                        if (proxy == null || device == null || link == null || !link.second.canBeConnected() ||
                            !isConnected(proxy, device)
                        ) {
                            report(CodecPreferenceStatus.WAITING_FOR_CONNECTION)
                            return@collectLatest
                        }
                        maintainConnected(id, key, proxy, device, link.first, ::report)
                    }
            } finally {
                status.update { it - id }
            }
        }
    }

    private suspend fun maintainConnected(
        id: BluetoothDeviceId,
        key: String,
        proxy: BluetoothA2dp,
        device: BluetoothDevice,
        session: SbcConnection,
        report: (CodecPreferenceStatus) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val worker = currentCoroutineContext()
        fun currentLink(): Boolean = proxies.current === proxy && links.value[key]?.let {
            it.session === session && it.connection.canBeConnected()
        } == true
        fun valid(): Boolean = currentLink() && isConnected(proxy, device)
        fun idle(): Boolean {
            val before = links.value[key]
            if (!mayChangeCodec(proxy, device, before?.playing)) return false
            val after = links.value[key]
            // A playback event that arrived during the system checks takes precedence.
            return after === before || after?.playing != true
        }
        var latestReadSequence = 0L
        fun read(): SbcRead {
            latestReadSequence = links.value[key]?.broadcast?.sequence ?: 0L
            return readForPreference(id, proxy, device)
        }
        fun write(): SbcWrite {
            if (!hasAccess(id)) return SbcWrite.ACCESS_NEEDED
            if (!valid()) return SbcWrite.DISCONNECTED
            if (!idle()) return SbcWrite.WAITING_FOR_PAUSE
            if (!currentLink()) return SbcWrite.DISCONNECTED
            worker.ensureActive()
            val failure = invokeCodecSetter(proxy, device)
            if (failure != null) {
                log("Handoff couldn't request SBC for $id ($failure)")
                return SbcWrite.UNAVAILABLE
            }
            log("Handoff requested SBC for $id")
            return SbcWrite.REQUESTED
        }

        val result = session.apply(
            read = ::read,
            isStillConnected = ::valid,
            mayChangeCodec = ::idle,
            awaitPause = {
                val playing = links.map { it[key]?.playing }.distinctUntilChanged().map { Unit }
                merge(playing, audioChanges).onStart { emit(Unit) }.first { !valid() || idle() }
            },
            write = ::write,
            report = report,
        )
        if (!valid()) return report(CodecPreferenceStatus.WAITING_FOR_CONNECTION)
        report(result)
        var confirmed = result == CodecPreferenceStatus.SBC_CONFIRMED
        when (result) {
            CodecPreferenceStatus.SBC_CONFIRMED -> log("Handoff verified SBC for $id")
            CodecPreferenceStatus.UNAVAILABLE -> log("Handoff couldn't confirm SBC for $id")
            else -> return
        }
        links.map { it[key]?.broadcast }.distinctUntilChanged().collect { observation ->
            if (observation == null || observation.sequence <= latestReadSequence || !valid()) return@collect
            val reportedSbc = observation.codec.substringBefore(' ') == "SBC"
            if (!reportedSbc && confirmed) {
                confirmed = false
                report(CodecPreferenceStatus.UNAVAILABLE)
                log("Android reported ${observation.codec} after SBC confirmation for $id")
            } else if (reportedSbc && !confirmed && read() == SbcRead.ACTIVE) {
                confirmed = true
                report(CodecPreferenceStatus.SBC_CONFIRMED)
                log("Handoff verified SBC for $id")
            }
        }
    }

    private fun Int?.canBeConnected(): Boolean = this == null || this == BluetoothProfile.STATE_CONNECTED

    private fun isConnected(proxy: BluetoothA2dp, device: BluetoothDevice): Boolean = try {
        proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun mayChangeCodec(proxy: BluetoothA2dp, device: BluetoothDevice, eventPlaying: Boolean?): Boolean {
        val playing = try {
            proxy.isA2dpPlaying(device)
        } catch (_: SecurityException) {
            return false
        } catch (_: RuntimeException) {
            eventPlaying
        } catch (_: LinkageError) {
            eventPlaying
        }
        val music = runCatching { audio?.isMusicActive }.getOrNull()
        return playing == false && music != true
    }

    internal fun onSystemEvent(action: String, intent: Intent, device: BluetoothDevice?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON
        ) {
            links.update { current -> current.mapValues { Link(connection = BluetoothProfile.STATE_DISCONNECTED) } }
            return
        }
        val key = device?.address?.uppercase() ?: return
        when (action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                updateLink(key) { link ->
                    when (state) {
                        BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING -> {
                            if (link.connection == state) link else Link(connection = state)
                        }
                        BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_CONNECTED -> link.copy(connection = state)
                        else -> link
                    }
                }
            }
            BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothA2dp.STATE_PLAYING || state == BluetoothA2dp.STATE_NOT_PLAYING) {
                    updateLink(key) { link ->
                        if (link.connection.canBeConnected()) link.copy(playing = state == BluetoothA2dp.STATE_PLAYING)
                        else link
                    }
                }
            }
            BluetoothLinkLog.ACTION_CODEC_CONFIG_CHANGED -> {
                val codec = BluetoothLinkLog.codecFromBroadcast(intent) ?: return
                updateLink(key) { link ->
                    if (link.connection.canBeConnected() || link.connection == BluetoothProfile.STATE_CONNECTING) {
                        link.copy(broadcast = CodecBroadcast(codec, (link.broadcast?.sequence ?: 0L) + 1L))
                    } else {
                        link
                    }
                }
            }
        }
    }

    private fun updateLink(key: String, change: (Link) -> Link) {
        links.update { it + (key to change(it[key] ?: Link())) }
    }

    internal fun codecSnapshot(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "unknown (Android 12)"
        val proxy = proxies.current as? BluetoothA2dp ?: return "unknown"
        if (!isConnected(proxy, device)) return "unknown (A2DP disconnected)"
        val key = device.address.uppercase()
        val session = links.value[key]?.session
        val reading = readCodec(proxy, device)
        val link = links.value[key]
        if (proxies.current !== proxy || link?.session !== session || !link?.connection.canBeConnected()) {
            return "unknown (A2DP connection changed)"
        }
        BluetoothLinkLog.describe(reading.status?.codecConfig)?.let { return "$it (current read)" }
        val result = reading.failure?.let { "not shared ($it)" } ?: "unknown"
        val broadcast = link?.broadcast ?: return result
        return "$result · last broadcast on this link: ${broadcast.codec}"
    }

    @RequiresApi(33)
    @Suppress("DEPRECATION")
    private fun readForPreference(id: BluetoothDeviceId, proxy: BluetoothA2dp, device: BluetoothDevice): SbcRead {
        val reading = readCodec(proxy, device)
        if (reading.failure != null) return if (!hasAccess(id)) SbcRead.ACCESS_NEEDED else SbcRead.UNAVAILABLE
        val status = reading.status ?: return SbcRead.NOT_READY
        val codec = status.codecConfig ?: return SbcRead.NOT_READY
        if (codec.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC) return SbcRead.ACTIVE
        val selectable = status.codecsSelectableCapabilities.orEmpty()
        if (selectable.isEmpty()) return SbcRead.NOT_READY
        return if (selectable.any { it.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC }) {
            SbcRead.SELECTABLE
        } else {
            SbcRead.UNAVAILABLE
        }
    }

    @RequiresApi(33)
    private data class CodecReading(val status: BluetoothCodecStatus? = null, val failure: String? = null)

    @RequiresApi(33)
    private fun readCodec(proxy: BluetoothA2dp, device: BluetoothDevice): CodecReading {
        val method = getter ?: return CodecReading(failure = "method unavailable")
        return try {
            CodecReading(status = method.invoke(proxy, device) as? BluetoothCodecStatus)
        } catch (e: InvocationTargetException) {
            CodecReading(failure = e.targetException?.javaClass?.simpleName ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            CodecReading(failure = e.javaClass.simpleName)
        } catch (e: LinkageError) {
            CodecReading(failure = e.javaClass.simpleName)
        }
    }

    @RequiresApi(33)
    @Suppress("DEPRECATION")
    private fun invokeCodecSetter(proxy: BluetoothA2dp, device: BluetoothDevice): String? {
        val method = setter ?: return "method unavailable"
        return try {
            val config = BluetoothCodecConfig.Builder()
                .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .build()
            method.invoke(proxy, device, config)
            null
        } catch (e: InvocationTargetException) {
            e.targetException?.javaClass?.simpleName ?: e.javaClass.simpleName
        } catch (e: Exception) {
            e.javaClass.simpleName
        } catch (e: LinkageError) {
            e.javaClass.simpleName
        }
    }

    private val getter by lazy { findCodecMethod("getCodecStatus", BluetoothDevice::class.java) }

    private val setter by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null
        else findCodecMethod("setCodecConfigPreference", BluetoothDevice::class.java, BluetoothCodecConfig::class.java)
    }

    private fun findCodecMethod(name: String, vararg parameters: Class<*>): Method? =
        runCatching { BluetoothA2dp::class.java.getMethod(name, *parameters) }.getOrNull()
}
