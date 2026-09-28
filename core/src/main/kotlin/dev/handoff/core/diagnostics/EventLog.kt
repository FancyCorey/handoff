package dev.handoff.core.diagnostics

import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.Redaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EventType {
    TRANSFER_STARTED,
    OWNER_RESOLVED,
    RELEASE_SENT,
    RELEASE_ACK,
    RELEASE_FAILED,
    RELEASE_TIMEOUT,
    CONNECT_ATTEMPT,
    CONNECT_VERIFIED,
    CONNECT_FAILED,
    DIRECT_TAKEOVER_STARTED,
    TRANSFER_COMPLETE,
    TRANSFER_FAILED,

    RELEASE_REQUEST_RECEIVED,
    RELEASE_PERFORMED,
    OWNERSHIP_UPDATE_RECEIVED,
    PEER_PAIRED,
    PEER_REMOVED,
    PEER_AUTH_FAILED,
    COMMAND_REJECTED,
    PEER_ONLINE,
    PEER_OFFLINE,
    BLUETOOTH_OPERATION,
    AUTO_SWITCH_TRIGGERED,
    SERVICE_STATE,
}

data class DiagnosticEvent(
    val type: EventType,
    val atMs: Long,
    val logicalId: LogicalDeviceId? = null,
    val peerId: PeerId? = null,
    val details: Map<String, String> = emptyMap(),
) {
    fun format(): String = buildString {
        append(type.name)
        logicalId?.let { append(" device=").append(it.value.take(8)) }
        peerId?.let { append(" peer=").append(it.short) }
        details.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
    }
}

/**
 * Structured, local-only event log. Every value is scrubbed of Bluetooth addresses on the way
 * in, so nothing recorded here can leak a full address. Keys, tokens and signatures must
 * never be passed as details.
 */
interface EventLog {
    val events: StateFlow<List<DiagnosticEvent>>

    fun record(
        type: EventType,
        logicalId: LogicalDeviceId? = null,
        peerId: PeerId? = null,
        details: Map<String, String> = emptyMap(),
    )
}

class InMemoryEventLog(
    private val capacity: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (DiagnosticEvent) -> Unit = {},
) : EventLog {
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    override fun record(
        type: EventType,
        logicalId: LogicalDeviceId?,
        peerId: PeerId?,
        details: Map<String, String>,
    ) {
        val event = DiagnosticEvent(
            type = type,
            atMs = clock(),
            logicalId = logicalId,
            peerId = peerId,
            details = details.mapValues { Redaction.scrub(it.value) },
        )
        _events.update { (it + event).takeLast(capacity) }
        runCatching { sink(event) }
    }
}
