package dev.handoff.core.stage

import dev.handoff.core.handoff.HandoffCoordinator
import dev.handoff.core.handoff.HandoffResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Counts successful moves on this device, so the stage can stop explaining routine moves once
 * people know them ([StageBuilder.LEARNING_MOVES]). Counting stops there; nothing else needs it.
 */
class MoveCount(
    scope: CoroutineScope,
    coordinator: HandoffCoordinator,
    private val load: suspend () -> Int,
    private val save: suspend (Int) -> Unit,
) {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        scope.launch {
            _count.value = load()
            val counted = mutableSetOf<Pair<String, Long>>()
            coordinator.transfers.collect { all ->
                if (_count.value >= StageBuilder.LEARNING_MOVES) return@collect
                for (t in all.values) {
                    if (t.result !is HandoffResult.Success) continue
                    if (!counted.add(t.logicalId.value to t.startedAtMs)) continue
                    _count.value += 1
                    save(_count.value)
                }
            }
        }
    }
}
