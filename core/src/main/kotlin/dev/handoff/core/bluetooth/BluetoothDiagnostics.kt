package dev.handoff.core.bluetooth

import kotlinx.coroutines.flow.StateFlow

/** Resolution status of one hidden method, as probed on this device. */
enum class MethodAvailability { UNKNOWN, AVAILABLE, MISSING, BLOCKED }

data class StrategyInfo(
    val name: String,
    val supported: Boolean,
    /** True for strategies that use non-SDK interfaces. */
    val usesHiddenApi: Boolean,
    val detail: String,
)

data class OperationRecord(
    val operation: String,
    val strategy: String?,
    val outcome: String,
    val durationMs: Long,
    val atMs: Long,
)

data class BluetoothDiagnostics(
    val adapterState: AdapterState = AdapterState.NOT_AVAILABLE,
    val a2dpProxyConnected: Boolean = false,
    val strategies: List<StrategyInfo> = emptyList(),
    val reflectionConnect: MethodAvailability = MethodAvailability.UNKNOWN,
    val reflectionDisconnect: MethodAvailability = MethodAvailability.UNKNOWN,
    /** Hidden `disconnect()` on the other audio profiles (e.g. "HFP", "LE_AUDIO"). */
    val companionDisconnect: Map<String, MethodAvailability> = emptyMap(),
    val compatibility: CompatibilityLevel = CompatibilityLevel.EXPERIMENTAL,
    val lastStrategyUsed: String? = null,
    val lastConnect: OperationRecord? = null,
    val lastDisconnect: OperationRecord? = null,
)

/** Read-only diagnostics exposed by the Bluetooth layer. */
interface BluetoothDiagnosticsSource {
    val diagnostics: StateFlow<BluetoothDiagnostics>

    /** Re-resolve method availability without invoking anything. Safe to call any time. */
    suspend fun probe()
}
