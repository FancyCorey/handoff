package dev.handoff.core.fakes

import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.bluetooth.DisconnectReason
import dev.handoff.core.model.AudioConnectionState
import dev.handoff.core.model.AudioDevice
import dev.handoff.core.model.BluetoothDeviceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Scriptable Bluetooth controller. Each connect() consumes the next [ConnectStep] (default:
 * accepted and effective). When attached to a [SimulatedHeadset], connecting may knock other
 * hosts off, like a real single-point headset.
 */
class FakeBluetoothAudioController(val hostName: String = "host") : BluetoothAudioController {

    data class ConnectStep(
        val result: BluetoothOperationResult = BluetoothOperationResult.Requested("FakeStrategy", 5),
        val becomesConnected: Boolean = true,
        val latencyMs: Long = 50,
    )

    override val adapterState = MutableStateFlow(AdapterState.ON)
    private val states = mutableMapOf<BluetoothDeviceId, MutableStateFlow<AudioConnectionState>>()
    val connectPlan = ArrayDeque<ConnectStep>()
    var disconnectResult: BluetoothOperationResult = BluetoothOperationResult.Requested("FakeStrategy", 5)
    var disconnectTakesEffect = true
    var headset: SimulatedHeadset? = null

    /** Non-A2DP profiles (e.g. "HFP") held per device. */
    val companions = mutableMapOf<BluetoothDeviceId, MutableSet<String>>()

    /** False models Android refusing to drop the call profile (hidden API blocked). */
    var companionReleaseWorks = true

    var connectCalls = 0
        private set
    var disconnectCalls = 0
        private set
    val connectReasons = mutableListOf<ConnectReason>()

    fun state(id: BluetoothDeviceId): MutableStateFlow<AudioConnectionState> =
        states.getOrPut(id) { MutableStateFlow(AudioConnectionState.DISCONNECTED) }

    fun setConnected(id: BluetoothDeviceId, connected: Boolean) {
        state(id).value = if (connected) AudioConnectionState.CONNECTED else AudioConnectionState.DISCONNECTED
    }

    override fun bondedAudioDevices(): Flow<List<AudioDevice>> = flowOf(emptyList())

    override fun connectionState(deviceId: BluetoothDeviceId): StateFlow<AudioConnectionState> = state(deviceId)

    override suspend fun isConnected(deviceId: BluetoothDeviceId): Boolean =
        state(deviceId).value == AudioConnectionState.CONNECTED

    override suspend fun connect(deviceId: BluetoothDeviceId, reason: ConnectReason): BluetoothOperationResult {
        connectCalls++
        connectReasons += reason
        val step = connectPlan.removeFirstOrNull() ?: ConnectStep()
        delay(step.latencyMs)
        if (step.result !is BluetoothOperationResult.Failed && step.becomesConnected) {
            headset?.connect(this, deviceId) ?: setConnected(deviceId, true)
            if (headset?.withCallProfile == true && isConnectedNow(deviceId)) companions.getOrPut(deviceId) { mutableSetOf() } += "HFP"
        }
        return step.result
    }

    override suspend fun disconnect(deviceId: BluetoothDeviceId, reason: DisconnectReason): BluetoothOperationResult {
        disconnectCalls++
        delay(30)
        if (disconnectResult !is BluetoothOperationResult.Failed && disconnectTakesEffect) {
            headset?.disconnect(this, deviceId) ?: setConnected(deviceId, false)
        }
        if (disconnectResult !is BluetoothOperationResult.Failed && companionReleaseWorks) companions.remove(deviceId)
        return disconnectResult
    }

    override suspend fun verifyConnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state(deviceId).first { it == AudioConnectionState.CONNECTED } } != null

    override suspend fun verifyDisconnected(deviceId: BluetoothDeviceId, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            state(deviceId).first { it == AudioConnectionState.DISCONNECTED }
            while (companions[deviceId].orEmpty().isNotEmpty()) delay(100)
        } != null

    override suspend fun connectedProfiles(deviceId: BluetoothDeviceId): Set<String> =
        buildSet {
            if (isConnectedNow(deviceId)) add("A2DP")
            addAll(companions[deviceId].orEmpty())
        }

    fun isConnectedNow(id: BluetoothDeviceId) = state(id).value == AudioConnectionState.CONNECTED

    fun holdsAnything(id: BluetoothDeviceId) = isConnectedNow(id) || companions[id].orEmpty().isNotEmpty()
}

/**
 * A physical headset bonded to several simulated hosts.
 *
 * @param acceptsTakeover single-point only: true = a new host's connect knocks the old one off;
 *   false = the headset ignores new hosts while another host holds any profile (e.g. OnePlus
 *   Bullets Wireless Z2 observed on hardware).
 * @param withCallProfile hosts also get an HFP link when they connect.
 */
class SimulatedHeadset(
    private val multipoint: Boolean = false,
    private val acceptsTakeover: Boolean = true,
    val withCallProfile: Boolean = false,
) {
    private val members = mutableListOf<Pair<FakeBluetoothAudioController, BluetoothDeviceId>>()

    fun attach(controller: FakeBluetoothAudioController, id: BluetoothDeviceId) {
        members += controller to id
        controller.headset = this
    }

    fun connect(controller: FakeBluetoothAudioController, id: BluetoothDeviceId) {
        val busyElsewhere = members.any { (c, otherId) -> c !== controller && c.holdsAnything(otherId) }
        if (!multipoint && !acceptsTakeover && busyElsewhere) return
        if (!multipoint) {
            members.filter { it.first !== controller }.forEach { (other, otherId) ->
                other.setConnected(otherId, false)
                other.companions.remove(otherId)
            }
        }
        controller.setConnected(id, true)
    }

    fun disconnect(controller: FakeBluetoothAudioController, id: BluetoothDeviceId) {
        controller.setConnected(id, false)
    }

    fun connectedHosts(): List<String> =
        members.filter { (c, id) -> c.state(id).value == AudioConnectionState.CONNECTED }.map { it.first.hostName }
}
