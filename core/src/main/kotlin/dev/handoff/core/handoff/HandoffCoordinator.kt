package dev.handoff.core.handoff

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import dev.handoff.core.bluetooth.AdapterState
import dev.handoff.core.bluetooth.BluetoothAudioController
import dev.handoff.core.bluetooth.BluetoothError
import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.diagnostics.EventLog
import dev.handoff.core.diagnostics.EventType
import dev.handoff.core.mesh.protocol.PeerMessage
import dev.handoff.core.mesh.protocol.ReleaseAudioDevice
import dev.handoff.core.mesh.protocol.ReleaseResult
import dev.handoff.core.mesh.protocol.ReleaseStatus
import dev.handoff.core.mesh.transport.CommandResult
import dev.handoff.core.mesh.transport.PeerTransport
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.ownership.Ownership
import dev.handoff.core.ownership.OwnershipRepository
import dev.handoff.core.ownership.OwnershipResolver
import dev.handoff.core.ownership.PeerReport
import dev.handoff.core.store.LogicalDeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface HandoffCoordinator {
    /** The most recently started transfer (any headset). */
    val state: StateFlow<HandoffState?>

    /** Latest transfer state per headset. */
    val transfers: StateFlow<Map<LogicalDeviceId, HandoffState>>

    suspend fun moveToThisDevice(
        audioDevice: LogicalAudioDevice,
        trigger: TransferTrigger = TransferTrigger.MANUAL,
    ): HandoffResult

    /**
     * Stops the running transfer for [logicalId], if any; it then ends with
     * [HandoffResult.Cancelled]. Returns false when nothing was running.
     */
    fun cancel(logicalId: LogicalDeviceId): Boolean
}

/**
 * The single implementation of "Move here". Manual, tile, notification and automatic triggers
 * all run through [moveToThisDevice]; there is no second switching path.
 *
 * Algorithm (see docs/ARCHITECTURE.md):
 *  1. Per-headset mutex (tryLock: repeated presses return [HandoffResult.InProgress]).
 *  2. Already connected locally -> done.
 *  3. Refresh peer status, resolve ownership.
 *  4. Owner is a reachable peer -> RELEASE_AUDIO_DEVICE; RELEASED/NOT_CONNECTED -> settle, connect.
 *     BUSY -> fail with CONTENTION (another host is mid-transfer; taking over would ping-pong).
 *     Unreachable / timeout / refusal / unknown owner -> direct takeover.
 *  5. Connect, verify A2DP, retry once, never more than [HandoffPolicy.maxConnectAttempts].
 *  6. Record the new ownership generation and tell peers.
 */
class DefaultHandoffCoordinator(
    private val selfId: PeerId,
    private val bluetooth: BluetoothAudioController,
    private val transport: PeerTransport,
    private val ownership: OwnershipRepository,
    private val resolver: OwnershipResolver,
    private val devices: LogicalDeviceRepository,
    private val locks: DeviceLocks,
    private val events: EventLog,
    private val broadcaster: OwnershipBroadcaster,
    private val history: TransferHistory,
    private val peerName: (PeerId) -> String,
    private val onlinePeers: () -> Set<PeerId>,
    private val backgroundScope: CoroutineScope,
    private val policy: HandoffPolicy = HandoffPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : HandoffCoordinator {

    private val _state = MutableStateFlow<HandoffState?>(null)
    override val state: StateFlow<HandoffState?> = _state.asStateFlow()

    private val _transfers = MutableStateFlow<Map<LogicalDeviceId, HandoffState>>(emptyMap())
    override val transfers: StateFlow<Map<LogicalDeviceId, HandoffState>> = _transfers.asStateFlow()

    private val running = ConcurrentHashMap<LogicalDeviceId, Job>()
    private val cancelRequested: MutableSet<LogicalDeviceId> = ConcurrentHashMap.newKeySet()

    override fun cancel(logicalId: LogicalDeviceId): Boolean {
        val job = running[logicalId] ?: return false
        cancelRequested += logicalId
        job.cancel()
        return true
    }

    override suspend fun moveToThisDevice(audioDevice: LogicalAudioDevice, trigger: TransferTrigger): HandoffResult {
        val mutex = locks.forDevice(audioDevice.logicalId)
        if (!mutex.tryLock()) {
            events.record(EventType.TRANSFER_FAILED, audioDevice.logicalId, details = mapOf("reason" to "already in progress"))
            return HandoffResult.InProgress
        }
        try {
            val run = Run(audioDevice, trigger)
            val id = audioDevice.logicalId
            return try {
                // A child job, so the user can cancel just this transfer (see [cancel]).
                coroutineScope {
                    val work = async { run.execute() }
                    running[id] = work
                    try {
                        work.await()
                    } finally {
                        running.remove(id)
                    }
                }
            } catch (e: CancellationException) {
                if (cancelRequested.remove(id)) return withContext(NonCancellable) { run.cancelled() }
                withContext(NonCancellable) { run.fail(FailureReason.INTERNAL, "cancelled") }
                throw e
            } catch (e: IllegalTransitionException) {
                run.fail(FailureReason.INTERNAL, e.message)
            } catch (e: Exception) {
                run.fail(FailureReason.INTERNAL, e.javaClass.simpleName)
            }
        } finally {
            mutex.unlock()
        }
    }

    private sealed interface ReleaseOutcome {
        data class Released(val durationMs: Long) : ReleaseOutcome
        data class Contention(val peer: PeerId) : ReleaseOutcome
        data class Fallback(val cause: TakeoverCause, val peer: PeerId?, val reason: String) : ReleaseOutcome
    }

    /** Why a transfer fell back to direct takeover; decides the final error if that fails too. */
    private enum class TakeoverCause { OWNER_UNREACHABLE, OWNER_REFUSED, OWNER_UNKNOWN }

    private companion object {
        /** How a peer's server reports a device it has no link with. */
        const val NOT_TRUSTED = "not trusted"
    }

    /** Both connect attempts of a round failed. */
    private class RoundExhausted(val detail: String?) : Exception()

    /** One transfer attempt; owns its state machine and published state. */
    private inner class Run(val device: LogicalAudioDevice, val trigger: TransferTrigger) {
        val id = device.logicalId
        val startedAt = clock()
        val machine = TransferStateMachine()
        var steps = listOf<TransferStep>()
        var attempt = 0
        var takeover = false
        var ownerPeer: PeerId? = null
        var strategy: String? = null
        var releaseMs: Long? = null
        var connectStartedAt: Long? = null
        var path: TransferPath? = null

        /** Hosts that held the headset when ownership was resolved (we are taking it from them). */
        var initialHolders: Set<PeerId> = emptySet()

        var takeoverCause: TakeoverCause? = null
        var refusal: String? = null
        var round = 1

        suspend fun execute(): HandoffResult {
            move(TransferPhase.RESOLVING_OWNER)
            events.record(EventType.TRANSFER_STARTED, id, details = mapOf("trigger" to trigger.name))

            val local = device.localBluetoothMapping()
                ?: return fail(FailureReason.MISSING_LOCAL_MAPPING, "headset is not mapped on this device")
                    .let { HandoffResult.MissingLocalMapping }

            when (bluetooth.adapterState.value) {
                AdapterState.ON -> Unit
                AdapterState.NO_PERMISSION -> return fail(FailureReason.PERMISSION_DENIED, "Bluetooth permission not granted")
                AdapterState.NOT_AVAILABLE -> return fail(FailureReason.UNSUPPORTED, "no Bluetooth adapter")
                else -> return fail(FailureReason.BLUETOOTH_OFF, "Bluetooth is off")
            }

            if (bluetooth.isConnected(local)) {
                step(StepKind.ALREADY_CONNECTED)
                move(TransferPhase.COMPLETE)
                publish(HandoffResult.AlreadyConnected)
                val generation = nextGeneration()
                devices.applyOwnership(id, selfId, generation)
                announce(generation)
                return HandoffResult.AlreadyConnected
            }

            while (true) {
                try {
                    return attemptRound(local)
                } catch (e: RoundExhausted) {
                    val bluetoothStillOn = bluetooth.adapterState.value == AdapterState.ON
                    if (round >= policy.transferRounds || !bluetoothStillOn) return fail(classify(), e.detail)
                    // One automatic retry of the whole transfer: networks settle, a peer wakes up,
                    // a headset finishes switching. Contention and permanent errors never get here.
                    move(TransferPhase.RETRYING)
                    step(StepKind.AUTO_RETRY)
                    events.record(EventType.CONNECT_FAILED, id, details = mapOf("autoRetry" to "round ${round + 1}"))
                    delay(policy.autoRetryDelayMs)
                    if (bluetooth.isConnected(local)) return succeed()
                    // Never take the headset back from a device that grabbed it during the pause.
                    refreshStatus()
                    contender()?.let { peer -> return fail(FailureReason.CONTENTION, "${peerName(peer)} connected to the headset") }
                    round++
                    takeover = false
                    takeoverCause = null
                    refusal = null
                    path = null
                    move(TransferPhase.RESOLVING_OWNER)
                }
            }
        }

        /** Owner lookup, release, connect and verify. Throws [RoundExhausted] if the connect fails. */
        private suspend fun attemptRound(local: BluetoothDeviceId): HandoffResult {
            refreshStatus()
            val current = devices.find(id) ?: device
            val owner = resolver.resolve(current, localConnected = false, ownership.reportsFor(id), onlinePeers())
            events.record(EventType.OWNER_RESOLVED, id, details = mapOf("owner" to describe(owner)))
            initialHolders = when (owner) {
                is Ownership.Peer -> setOf(owner.peerId)
                is Ownership.Conflict -> owner.holders
                is Ownership.Multipoint -> owner.holders
                is Ownership.Unknown -> setOfNotNull(owner.lastKnownOwner)
                else -> emptySet()
            }

            val releaseTargets: List<PeerId>? = when (owner) {
                is Ownership.Peer -> listOf(owner.peerId)
                is Ownership.Conflict -> (owner.holders - selfId).toList()
                is Ownership.Multipoint ->
                    if (policy.releaseOthersOnMultipoint) (owner.holders - selfId).toList() else emptyList()
                Ownership.None, Ownership.Local -> emptyList()
                is Ownership.Unknown -> {
                    ownerPeer = owner.lastKnownOwner
                    null
                }
            }

            when {
                releaseTargets == null -> startTakeover(TakeoverCause.OWNER_UNKNOWN, "owner unknown")
                releaseTargets.isEmpty() -> {
                    path = if (owner is Ownership.Multipoint) TransferPath.MULTIPOINT_JOIN else TransferPath.UNCONTESTED
                }
                else -> {
                    ownerPeer = releaseTargets.first()
                    when (val outcome = requestReleases(releaseTargets)) {
                        is ReleaseOutcome.Released -> {
                            path = TransferPath.COORDINATED
                            releaseMs = outcome.durationMs
                            delay(policy.releaseSettleDelayMs)
                        }
                        is ReleaseOutcome.Contention -> return fail(
                            FailureReason.CONTENTION,
                            "${peerName(outcome.peer)} is handing the headset to another device",
                        )
                        is ReleaseOutcome.Fallback -> {
                            outcome.peer?.let { ownerPeer = it }
                            startTakeover(outcome.cause, outcome.reason)
                        }
                    }
                }
            }
            return connectWithVerification(local)
        }

        private suspend fun requestReleases(targets: List<PeerId>): ReleaseOutcome {
            val started = clock()
            for (peer in targets) {
                val name = peerName(peer)
                move(TransferPhase.REQUESTING_RELEASE)
                step(StepKind.REQUESTING_RELEASE, name)
                if (!reachable(peer)) {
                    step(StepKind.PEER_UNREACHABLE, name)
                    events.record(EventType.RELEASE_FAILED, id, peer, mapOf("reason" to "unreachable"))
                    return ReleaseOutcome.Fallback(TakeoverCause.OWNER_UNREACHABLE, peer, "$name unreachable")
                }
                val command = ReleaseAudioDevice(
                    commandId = PeerMessage.newCommandId(),
                    timestamp = clock(),
                    senderPeerId = selfId.value,
                    logicalDeviceId = id.value,
                    requestingPeerId = selfId.value,
                )
                events.record(EventType.RELEASE_SENT, id, peer, mapOf("commandId" to command.commandId.take(8)))
                move(TransferPhase.WAITING_RELEASE)
                val result = transport.request(peer, command, policy.releaseTimeoutMs)
                val reply = (result as? CommandResult.Reply)?.message as? ReleaseResult
                when {
                    reply != null && reply.logicalDeviceId == id.value -> when (reply.status) {
                        ReleaseStatus.RELEASED, ReleaseStatus.NOT_CONNECTED -> {
                            step(if (reply.status == ReleaseStatus.RELEASED) StepKind.RELEASED else StepKind.PEER_NOT_CONNECTED, name)
                            events.record(EventType.RELEASE_ACK, id, peer, mapOf("status" to reply.status.name))
                            // The peer no longer holds it; do not wait for its own broadcast.
                            ownership.record(
                                PeerReport(peer, id, connected = false, generation = device.ownershipGeneration, receivedAtMs = clock()),
                            )
                        }
                        ReleaseStatus.BUSY -> {
                            step(StepKind.PEER_BUSY, name)
                            events.record(EventType.RELEASE_FAILED, id, peer, mapOf("status" to "BUSY"))
                            return ReleaseOutcome.Contention(peer)
                        }
                        else -> {
                            step(StepKind.RELEASE_REFUSED, name, reply.detail ?: reply.status.name)
                            events.record(
                                EventType.RELEASE_FAILED, id, peer,
                                mapOf("status" to reply.status.name, "detail" to (reply.detail ?: "-")),
                            )
                            refusal = "$name: ${reply.detail ?: reply.status.name.lowercase().replace('_', ' ')}"
                            return ReleaseOutcome.Fallback(TakeoverCause.OWNER_REFUSED, peer, refusal!!)
                        }
                    }
                    result is CommandResult.Timeout -> {
                        step(StepKind.RELEASE_TIMEOUT, name)
                        events.record(EventType.RELEASE_TIMEOUT, id, peer)
                        return ReleaseOutcome.Fallback(TakeoverCause.OWNER_UNREACHABLE, peer, "$name did not respond")
                    }
                    result is CommandResult.Unreachable -> {
                        step(StepKind.PEER_UNREACHABLE, name)
                        events.record(EventType.RELEASE_FAILED, id, peer, mapOf("reason" to "unreachable"))
                        return ReleaseOutcome.Fallback(TakeoverCause.OWNER_UNREACHABLE, peer, "$name unreachable")
                    }
                    else -> {
                        val why = (result as? CommandResult.Rejected)?.reason ?: "unexpected reply"
                        val explained = HandoffDiagnosis.rejection(why)
                        step(StepKind.RELEASE_REFUSED, name, explained)
                        events.record(EventType.RELEASE_FAILED, id, peer, mapOf("reason" to why))
                        refusal = "$name: $explained"
                        return ReleaseOutcome.Fallback(TakeoverCause.OWNER_REFUSED, peer, refusal!!)
                    }
                }
            }
            return ReleaseOutcome.Released(clock() - started)
        }

        /** A peer that doesn't answer gets one more chance (Wi-Fi waking up, network just changed). */
        private suspend fun reachable(peer: PeerId): Boolean {
            if (transport.isReachable(peer, policy.reachabilityTimeoutMs)) return true
            delay(policy.reachabilityRetryDelayMs)
            return transport.isReachable(peer, policy.reachabilityTimeoutMs)
        }

        /** The final error after every round failed to connect. */
        private fun classify(): FailureReason {
            val silent = ownership.unreachableSince(startedAt)
            // A linked device that doesn't recognise us probably still holds the headphones.
            if (silent.values.any { it?.contains(NOT_TRUSTED) == true }) return FailureReason.PEER_NOT_LINKED
            return when (takeoverCause) {
                TakeoverCause.OWNER_UNREACHABLE -> FailureReason.OWNER_UNREACHABLE
                TakeoverCause.OWNER_REFUSED -> FailureReason.OWNER_REFUSED
                TakeoverCause.OWNER_UNKNOWN, null ->
                    // Nobody reported holding the headphones, but a linked device may simply not
                    // have answered: then that device, not the headphones, is the likely cause.
                    if (ownerPeer != null || silent.isNotEmpty()) FailureReason.OWNER_UNREACHABLE else FailureReason.HEADSET_NOT_RESPONDING
            }
        }

        private fun startTakeover(cause: TakeoverCause, reason: String) {
            takeoverCause = cause
            path = TransferPath.DIRECT_TAKEOVER
            takeover = true
            move(TransferPhase.DIRECT_TAKEOVER)
            step(StepKind.DIRECT_TAKEOVER, detail = reason)
            events.record(EventType.DIRECT_TAKEOVER_STARTED, id, ownerPeer, mapOf("reason" to reason))
        }

        private suspend fun connectWithVerification(local: BluetoothDeviceId): HandoffResult {
            var lastDetail: String? = null
            for (n in 1..policy.maxConnectAttempts) {
                if (n > 1) {
                    move(TransferPhase.RETRYING)
                    step(StepKind.RETRYING)
                    delay(policy.retryDelayMs)
                    // If another host grabbed the headset meanwhile, do not steal it back.
                    refreshStatus()
                    contender()?.let { peer ->
                        return fail(FailureReason.CONTENTION, "${peerName(peer)} connected to the headset")
                    }
                }
                attempt = (round - 1) * policy.maxConnectAttempts + n
                move(TransferPhase.CONNECTING)
                step(StepKind.CONNECTING)
                if (connectStartedAt == null) connectStartedAt = clock()
                val reason = when {
                    takeover -> ConnectReason.DIRECT_TAKEOVER
                    n > 1 -> ConnectReason.RETRY
                    trigger == TransferTrigger.AUTOMATIC -> ConnectReason.AUTOMATIC
                    else -> ConnectReason.USER_MOVE_HERE
                }
                events.record(EventType.CONNECT_ATTEMPT, id, details = mapOf("attempt" to "$n", "reason" to reason.name))
                when (val op = bluetooth.connect(local, reason)) {
                    is BluetoothOperationResult.Failed -> {
                        events.record(
                            EventType.CONNECT_FAILED, id,
                            details = mapOf("attempt" to "$n", "error" to op.error.name, "strategy" to (op.strategy ?: "-"), "detail" to op.detail),
                        )
                        op.strategy?.let { strategy = it }
                        nonRetryable(op.error)?.let { return fail(it, op.detail) }
                        lastDetail = op.detail
                        continue
                    }
                    is BluetoothOperationResult.Requested -> strategy = op.strategy
                    BluetoothOperationResult.AlreadyInState -> Unit
                }
                move(TransferPhase.VERIFYING)
                step(StepKind.VERIFYING)
                if (bluetooth.verifyConnected(local, policy.verifyTimeoutMs)) {
                    return succeed()
                }
                events.record(EventType.CONNECT_FAILED, id, details = mapOf("attempt" to "$n", "error" to "VERIFY_TIMEOUT"))
                lastDetail = "the headset did not connect within ${policy.verifyTimeoutMs / 1000} s"
            }
            throw RoundExhausted(refusal ?: finalDetail(lastDetail))
        }

        /** Detail for the final error: who we couldn't reach, or what the stack said. */
        private fun finalDetail(technical: String?): String? = when (classify()) {
            FailureReason.PEER_NOT_LINKED ->
                ownership.unreachableSince(startedAt).filterValues { it?.contains(NOT_TRUSTED) == true }.keys.joinToString(" and ", transform = peerName)
            FailureReason.OWNER_UNREACHABLE ->
                ownerPeer?.let(peerName) ?: ownership.unreachableSince(startedAt).keys.joinToString(" and ", transform = peerName).ifEmpty { null }
            else -> technical
        }

        private suspend fun succeed(): HandoffResult {
            val now = clock()
            val timings = TransferTimings(
                releaseMs = releaseMs,
                connectMs = connectStartedAt?.let { now - it },
                totalMs = now - startedAt,
            )
            val result = HandoffResult.Success(path ?: TransferPath.UNCONTESTED, timings, strategy, attempt)
            step(StepKind.CONNECTED)
            move(TransferPhase.COMPLETE)
            publish(result)
            events.record(EventType.CONNECT_VERIFIED, id, details = mapOf("attempt" to "$attempt", "strategy" to (strategy ?: "-")))
            val generation = nextGeneration()
            devices.applyOwnership(id, selfId, generation)
            events.record(
                EventType.TRANSFER_COMPLETE, id, ownerPeer,
                mapOf(
                    "path" to result.path.name,
                    "releaseMs" to (timings.releaseMs?.toString() ?: "-"),
                    "connectMs" to (timings.connectMs?.toString() ?: "-"),
                    "totalMs" to timings.totalMs.toString(),
                ),
            )
            announce(generation)
            record("SUCCESS", result.path, null, null, timings)
            return result
        }

        /** The user stopped the transfer. Whatever already happened (e.g. a release) stays done. */
        suspend fun cancelled(): HandoffResult {
            if (!machine.phase.isTerminal) {
                step(StepKind.CANCELLED)
                if (machine.phase == TransferPhase.IDLE) machine.moveTo(TransferPhase.RESOLVING_OWNER)
                move(TransferPhase.FAILED)
            }
            publish(HandoffResult.Cancelled)
            events.record(EventType.TRANSFER_FAILED, id, ownerPeer, mapOf("reason" to "cancelled by user"))
            record("CANCELLED", path, null, "cancelled by user", TransferTimings(releaseMs, connectStartedAt?.let { clock() - it }, clock() - startedAt))
            return HandoffResult.Cancelled
        }

        suspend fun fail(reason: FailureReason, detail: String?): HandoffResult {
            val result = HandoffResult.Failed(reason, detail)
            if (!machine.phase.isTerminal) {
                step(StepKind.FAILED, detail = detail)
                if (machine.phase == TransferPhase.IDLE) machine.moveTo(TransferPhase.RESOLVING_OWNER)
                move(TransferPhase.FAILED)
            }
            publish(result)
            events.record(EventType.TRANSFER_FAILED, id, ownerPeer, mapOf("reason" to reason.name, "detail" to (detail ?: "-")))
            record("FAILED", path, reason, detail, TransferTimings(releaseMs, connectStartedAt?.let { clock() - it }, clock() - startedAt))
            return result
        }

        /** A host that newly connected to the headset during this transfer. */
        private fun contender(): PeerId? = ownership.reportsFor(id)
            .firstOrNull {
                it.connected && it.receivedAtMs >= startedAt && it.peerId != selfId && it.peerId !in initialHolders
            }
            ?.peerId

        private suspend fun refreshStatus() {
            try {
                ownership.refresh(policy.statusRefreshTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Stale status only degrades to direct takeover; never fail the transfer here.
            }
        }

        private fun nextGeneration(): Long {
            val reported = ownership.reportsFor(id).maxOfOrNull { it.generation } ?: 0
            val stored = devices.find(id)?.ownershipGeneration ?: device.ownershipGeneration
            return maxOf(reported, stored) + 1
        }

        private fun announce(generation: Long) {
            backgroundScope.launch {
                runCatching { broadcaster.announce(id, senderConnected = true, owner = selfId, generation = generation) }
            }
        }

        private suspend fun record(outcome: String, path: TransferPath?, failure: FailureReason?, detail: String?, timings: TransferTimings) {
            runCatching {
                history.add(
                    TransferRecord(id, device.displayName, trigger, startedAt, outcome, path, failure, detail, ownerPeer, strategy, attempt, timings),
                )
            }
        }

        private fun nonRetryable(error: BluetoothError): FailureReason? = when (error) {
            BluetoothError.BLUETOOTH_OFF -> FailureReason.BLUETOOTH_OFF
            BluetoothError.PERMISSION_DENIED -> FailureReason.PERMISSION_DENIED
            BluetoothError.DEVICE_NOT_BONDED -> FailureReason.DEVICE_NOT_BONDED
            BluetoothError.UNSUPPORTED -> FailureReason.UNSUPPORTED
            BluetoothError.RATE_LIMITED -> FailureReason.RATE_LIMITED
            BluetoothError.PROFILE_UNAVAILABLE, BluetoothError.REJECTED,
            BluetoothError.TIMEOUT, BluetoothError.INTERNAL,
            -> null
        }

        private fun move(next: TransferPhase) {
            machine.moveTo(next)
            publish(null)
        }

        private fun step(kind: StepKind, peerName: String? = null, detail: String? = null) {
            steps = steps + TransferStep(kind, peerName, detail)
            publish(null)
        }

        private fun publish(result: HandoffResult?) {
            val snapshot = HandoffState(
                logicalId = id,
                deviceName = device.displayName,
                trigger = trigger,
                phase = machine.phase,
                phases = machine.history,
                steps = steps,
                attempt = attempt,
                takeover = takeover,
                ownerPeer = ownerPeer,
                result = result,
                startedAtMs = startedAt,
            )
            _state.value = snapshot
            _transfers.update { it + (id to snapshot) }
        }

        private fun describe(owner: Ownership): String = when (owner) {
            is Ownership.Peer -> "peer:${owner.peerId.short}"
            is Ownership.Multipoint -> "multipoint:${owner.holders.size}"
            is Ownership.Conflict -> "conflict:${owner.holders.size}"
            is Ownership.Unknown -> "unknown(last=${owner.lastKnownOwner?.short ?: "-"})"
            Ownership.None -> "none"
            Ownership.Local -> "local"
        }
    }
}
