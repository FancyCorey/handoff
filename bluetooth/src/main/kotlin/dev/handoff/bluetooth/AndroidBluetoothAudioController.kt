package dev.handoff.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.handoff.bluetooth.api.BluetoothConnectionStrategy
import dev.handoff.bluetooth.api.StrategyResult
import dev.handoff.bluetooth.oem.FutureOemStrategy
import dev.handoff.bluetooth.publicapi.PublicApiStrategy
import dev.handoff.bluetooth.reflection.CompanionProfileReleaser
import dev.handoff.bluetooth.reflection.HiddenMethodInvoker
import dev.handoff.bluetooth.reflection.ReflectionA2dpStrategy
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothDiagnostics
import dev.handoff.core.bluetooth.BluetoothDiagnosticsSource
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.CompatibilityLevel
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.bluetooth.DisconnectReason
import dev.handoff.core.bluetooth.MethodAvailability
import dev.handoff.core.bluetooth.OperationRecord
import dev.handoff.core.bluetooth.OperationThrottle
import dev.handoff.core.bluetooth.StrategyInfo
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.DeviceFingerprint
import dev.handoff.core.model.Redaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Persists whether a strategy has been verified to work on this device (drives "Supported"). */
interface CompatibilityStore {
    fun isVerified(strategy: String): Boolean
    fun markVerified(strategy: String)
}

/**
 * Android implementation of [BluetoothAudioController].
 *
 * - Observation (bonded devices, A2DP state, adapter state) uses only public SDK APIs.
 * - Connect/disconnect run through an ordered chain of [BluetoothConnectionStrategy]s; hidden
 *   API use is confined to [ReflectionA2dpStrategy].
 * - Every result is structured; no exception from the Bluetooth stack escapes this class.
 */
class AndroidBluetoothAudioController(
    context: Context,
    private val compatibility: CompatibilityStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : BluetoothAudioController, BluetoothDiagnosticsSource {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val changes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val proxies = ProfileProxyProvider(appContext, adapter, BluetoothProfile.A2DP) { refresh() }
    private val companionProxies = buildList {
        add("HFP" to ProfileProxyProvider(appContext, adapter, BluetoothProfile.HEADSET) { refresh() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add("LE_AUDIO" to ProfileProxyProvider(appContext, adapter, BluetoothProfile.LE_AUDIO) { refresh() })
        }
    }
    private val companions = CompanionProfileReleaser(companionProxies, HiddenMethodInvoker())
    private val reflection = ReflectionA2dpStrategy(proxies)
    private val strategies: List<BluetoothConnectionStrategy> =
        listOf(PublicApiStrategy(proxies), reflection, FutureOemStrategy())
    private val throttle = OperationThrottle(maxOperations = 12, windowMs = 60_000, clock = clock)
    private val events = SystemBluetoothEvents(appContext, ::onSystemEvent)

    private val _adapterState = MutableStateFlow(readAdapterState())
    override val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    private val _diagnostics = MutableStateFlow(BluetoothDiagnostics())
    override val diagnostics: StateFlow<BluetoothDiagnostics> = _diagnostics.asStateFlow()

    @Volatile private var lastConnectStrategy: Pair<String, Long>? = null

    init {
        changes.tryEmit(Unit)
    }

    /** Register receivers and request the A2DP proxy. Idempotent; call from Application. */
    fun start() {
        events.register()
        reflection.probe()
        acquireProxies()
        refresh()
    }

    private fun acquireProxies() {
        proxies.acquire()
        companionProxies.forEach { it.second.acquire() }
    }

    /** Re-read everything (e.g. after the user grants BLUETOOTH_CONNECT). */
    fun refresh() {
        _adapterState.value = readAdapterState()
        if (_adapterState.value == AdapterState.ON) acquireProxies()
        updateDiagnostics()
        changes.tryEmit(Unit)
    }

    private fun onSystemEvent(action: String, intent: Intent) {
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state != BluetoothAdapter.STATE_ON) {
                proxies.invalidate()
                companionProxies.forEach { it.second.invalidate() }
            }
        }
        refresh()
    }

    // ---- observation -------------------------------------------------------------------

    override fun bondedAudioDevices(): Flow<List<AudioDevice>> =
        changes.map { readBonded(includeNonAudio = false) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Every bonded device, audio or not; for the internal Bluetooth test screen. */
    fun allBondedDevices(): Flow<List<AudioDevice>> =
        changes.map { readBonded(includeNonAudio = true) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun connectionState(deviceId: BluetoothDeviceId): Flow<AudioConnectionState> =
        changes.map { stateNow(deviceId) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun isConnected(deviceId: BluetoothDeviceId): Boolean = withContext(Dispatchers.IO) {
        if (_adapterState.value != AdapterState.ON) return@withContext false
        proxies.await(PROXY_WAIT_MS)
        stateNow(deviceId) == AudioConnectionState.CONNECTED
    }

    override suspend fun verifyConnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean {
        val ok = awaitState(deviceId, AudioConnectionState.CONNECTED, timeoutMs)
        if (ok) {
            lastConnectStrategy?.let { (strategy, at) ->
                if (clock() - at <= timeoutMs + VERIFY_ATTRIBUTION_SLACK_MS) {
                    compatibility.markVerified(strategy)
                    updateDiagnostics()
                }
            }
        }
        return ok
    }

    /** Released means *every* audio profile is down, not only A2DP; see [CompanionProfileReleaser]. */
    override suspend fun verifyDisconnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean =
        awaitCondition(timeoutMs) {
            stateNow(deviceId) != AudioConnectionState.CONNECTED &&
                stateNow(deviceId) != AudioConnectionState.DISCONNECTING &&
                companionsConnected(deviceId).isEmpty()
        }

    override suspend fun connectedProfiles(deviceId: BluetoothDeviceId): Set<String> = withContext(Dispatchers.IO) {
        proxies.await(PROXY_WAIT_MS)
        buildSet {
            if (stateNow(deviceId) == AudioConnectionState.CONNECTED) add("A2DP")
            addAll(companionsConnected(deviceId))
        }
    }

    private fun companionsConnected(deviceId: BluetoothDeviceId): List<String> {
        if (!hasConnectPermission()) return emptyList()
        val device = adapter?.let { bondedDevice(it, deviceId) } ?: return emptyList()
        return companions.connected(device)
    }

    private suspend fun awaitState(deviceId: BluetoothDeviceId, target: AudioConnectionState, timeoutMs: Long): Boolean =
        awaitCondition(timeoutMs) { stateNow(deviceId) == target }

    /** Broadcast-driven, with a slow poll as a safety net for OEMs that drop broadcasts. */
    private suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                proxies.await(PROXY_WAIT_MS)
                val poll = flow {
                    while (true) {
                        emit(Unit)
                        delay(VERIFY_POLL_MS)
                    }
                }
                merge(changes, poll).first { condition() }
            } != null
        }

    // ---- operations --------------------------------------------------------------------

    override suspend fun connect(deviceId: BluetoothDeviceId, reason: ConnectReason): BluetoothOperationResult =
        operate("connect", deviceId, AudioConnectionState.CONNECTED) { strategy, device -> strategy.connect(device) }
            .also { result ->
                if (result is BluetoothOperationResult.Requested) lastConnectStrategy = result.strategy to clock()
            }

    /**
     * Disconnect A2DP through the strategy chain, then release the companion profiles (HFP,
     * LE Audio) so a single-point headset is actually free for the next host.
     */
    override suspend fun disconnect(deviceId: BluetoothDeviceId, reason: DisconnectReason): BluetoothOperationResult =
        withContext(Dispatchers.IO) {
            val started = clock()
            val a2dp = operate("disconnect", deviceId, AudioConnectionState.DISCONNECTED) { strategy, device -> strategy.disconnect(device) }
            if (a2dp is BluetoothOperationResult.Failed && a2dp.error in FATAL_ERRORS) return@withContext a2dp
            val device = adapter?.let { bondedDevice(it, deviceId) } ?: return@withContext a2dp
            val outcomes = companions.releaseAll(device)
            if (outcomes.isEmpty()) return@withContext a2dp
            record("disconnect", (a2dp as? BluetoothOperationResult.Requested)?.strategy, "A2DP=${describe(a2dp)}; ${outcomes.joinToString("; ")}", started)
            if (a2dp is BluetoothOperationResult.AlreadyInState) {
                BluetoothOperationResult.Requested(CompanionProfileReleaser::class.java.simpleName, clock() - started)
            } else {
                a2dp
            }
        }

    private fun describe(result: BluetoothOperationResult) = when (result) {
        is BluetoothOperationResult.Requested -> "requested"
        BluetoothOperationResult.AlreadyInState -> "already disconnected"
        is BluetoothOperationResult.Failed -> result.error.name
    }

    private suspend fun operate(
        operation: String,
        deviceId: BluetoothDeviceId,
        alreadyState: AudioConnectionState,
        action: suspend (BluetoothConnectionStrategy, BluetoothDevice) -> StrategyResult,
    ): BluetoothOperationResult = withContext(Dispatchers.IO) {
        val started = clock()
        fun failed(error: BluetoothError, strategy: String?, detail: String) =
            BluetoothOperationResult.Failed(error, strategy, detail).also { record(operation, strategy, error.name, started) }

        if (!hasConnectPermission()) return@withContext failed(BluetoothError.PERMISSION_DENIED, null, "BLUETOOTH_CONNECT not granted")
        val adapter = adapter ?: return@withContext failed(BluetoothError.UNSUPPORTED, null, "no Bluetooth adapter")
        if (!isEnabled(adapter)) return@withContext failed(BluetoothError.BLUETOOTH_OFF, null, "Bluetooth is off")
        val device = bondedDevice(adapter, deviceId)
            ?: return@withContext failed(BluetoothError.DEVICE_NOT_BONDED, null, "${Redaction.address(deviceId.address)} is not bonded")
        proxies.await(PROXY_WAIT_MS)
            ?: return@withContext failed(BluetoothError.PROFILE_UNAVAILABLE, null, "A2DP profile proxy not available")
        if (stateNow(deviceId) == alreadyState) {
            record(operation, null, "ALREADY_IN_STATE", started)
            return@withContext BluetoothOperationResult.AlreadyInState
        }
        if (!throttle.tryAcquire()) {
            return@withContext failed(BluetoothError.RATE_LIMITED, null, "too many Bluetooth operations; paused briefly")
        }

        var lastFailure: StrategyResult.Failed? = null
        var lastFailedStrategy: String? = null
        for (strategy in strategies) {
            if (!strategy.isSupported()) continue
            val result = try {
                action(strategy, device)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                StrategyResult.Failed(BluetoothError.INTERNAL, "${strategy.name} threw ${e.javaClass.simpleName}")
            }
            when (result) {
                StrategyResult.Accepted -> {
                    record(operation, strategy.name, "ACCEPTED", started)
                    return@withContext BluetoothOperationResult.Requested(strategy.name, clock() - started)
                }
                is StrategyResult.NotApplicable -> continue
                is StrategyResult.Failed -> {
                    lastFailure = result
                    lastFailedStrategy = strategy.name
                }
            }
        }
        lastFailure?.let { return@withContext failed(it.error, lastFailedStrategy, it.detail) }
        failed(BluetoothError.UNSUPPORTED, null, "no available strategy can $operation A2DP on this Android build")
    }

    // ---- diagnostics -------------------------------------------------------------------

    override suspend fun probe() {
        withContext(Dispatchers.IO) {
            reflection.probe()
            companions.probe()
            refresh()
        }
    }

    private fun record(operation: String, strategy: String?, outcome: String, started: Long) {
        val entry = OperationRecord(operation, strategy, outcome, clock() - started, clock())
        _diagnostics.update {
            if (operation == "connect") {
                it.copy(lastConnect = entry, lastStrategyUsed = strategy ?: it.lastStrategyUsed)
            } else {
                it.copy(lastDisconnect = entry, lastStrategyUsed = strategy ?: it.lastStrategyUsed)
            }
        }
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        val state = _adapterState.value
        val level = when {
            state == AdapterState.NOT_AVAILABLE -> CompatibilityLevel.UNSUPPORTED
            reflection.connectAvailability == MethodAvailability.MISSING -> CompatibilityLevel.UNSUPPORTED
            compatibility.isVerified(ReflectionA2dpStrategy.NAME) -> CompatibilityLevel.SUPPORTED
            else -> CompatibilityLevel.EXPERIMENTAL
        }
        _diagnostics.update {
            it.copy(
                adapterState = state,
                a2dpProxyConnected = proxies.current != null,
                strategies = strategies.map { s -> StrategyInfo(s.name, s.isSupported(), s.usesHiddenApi, s.describe()) },
                reflectionConnect = reflection.connectAvailability,
                reflectionDisconnect = reflection.disconnectAvailability,
                companionDisconnect = companions.availability(),
                compatibility = level,
            )
        }
    }

    // ---- platform reads (all exception-safe) --------------------------------------------

    private fun hasConnectPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isEnabled(adapter: BluetoothAdapter): Boolean = try {
        adapter.isEnabled
    } catch (_: RuntimeException) {
        false
    }

    private fun readAdapterState(): AdapterState {
        val adapter = adapter ?: return AdapterState.NOT_AVAILABLE
        if (!hasConnectPermission()) return AdapterState.NO_PERMISSION
        return try {
            when (adapter.state) {
                BluetoothAdapter.STATE_ON -> AdapterState.ON
                BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
                else -> AdapterState.OFF
            }
        } catch (_: RuntimeException) {
            AdapterState.OFF
        }
    }

    private fun bondedDevice(adapter: BluetoothAdapter, id: BluetoothDeviceId): BluetoothDevice? = try {
        adapter.bondedDevices.orEmpty().firstOrNull { it.address.equals(id.address, ignoreCase = true) }
    } catch (_: SecurityException) {
        null
    }

    private fun stateNow(id: BluetoothDeviceId): AudioConnectionState {
        if (!hasConnectPermission()) return AudioConnectionState.UNAVAILABLE
        val adapter = adapter ?: return AudioConnectionState.UNAVAILABLE
        if (!isEnabled(adapter)) return AudioConnectionState.UNAVAILABLE
        val proxy = proxies.current ?: return AudioConnectionState.UNAVAILABLE
        return try {
            val device = adapter.getRemoteDevice(id.address.uppercase())
            when (proxy.getConnectionState(device)) {
                BluetoothProfile.STATE_CONNECTED -> AudioConnectionState.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> AudioConnectionState.CONNECTING
                BluetoothProfile.STATE_DISCONNECTING -> AudioConnectionState.DISCONNECTING
                else -> AudioConnectionState.DISCONNECTED
            }
        } catch (_: SecurityException) {
            AudioConnectionState.UNAVAILABLE
        } catch (_: IllegalArgumentException) {
            AudioConnectionState.UNAVAILABLE
        }
    }

    private fun readBonded(includeNonAudio: Boolean): List<AudioDevice> {
        if (!hasConnectPermission()) return emptyList()
        val adapter = adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().mapNotNull { device ->
                val cls = device.bluetoothClass
                val uuids = device.uuids.orEmpty().map { it.uuid.toString() }
                val audio = DeviceClassifier.isAudio(cls?.majorDeviceClass, uuids)
                if (!audio && !includeNonAudio) return@mapNotNull null
                AudioDevice(
                    id = BluetoothDeviceId(device.address),
                    name = device.alias ?: device.name ?: Redaction.address(device.address),
                    kind = DeviceClassifier.kind(cls?.majorDeviceClass, cls?.deviceClass),
                    likelyA2dp = DeviceClassifier.advertisesA2dpSink(uuids) ||
                        cls?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO,
                    fingerprint = DeviceFingerprint.of(device.address),
                )
            }.sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private companion object {
        val FATAL_ERRORS = setOf(
            BluetoothError.PERMISSION_DENIED, BluetoothError.BLUETOOTH_OFF,
            BluetoothError.DEVICE_NOT_BONDED, BluetoothError.RATE_LIMITED,
        )
        const val PROXY_WAIT_MS = 2_000L
        const val VERIFY_POLL_MS = 400L
        const val VERIFY_ATTRIBUTION_SLACK_MS = 5_000L
    }
}
