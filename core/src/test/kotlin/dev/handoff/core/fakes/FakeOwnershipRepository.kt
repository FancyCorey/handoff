package dev.handoff.core.fakes

import dev.handoff.core.ownership.PeerReportStore

/** Ownership store whose refresh is scripted by the test. */
class FakeOwnershipRepository(
    private val onRefresh: suspend FakeOwnershipRepository.() -> Unit = {},
) : PeerReportStore() {
    var refreshCount = 0
        private set

    override suspend fun refresh(timeoutMs: Long) {
        refreshCount++
        onRefresh()
    }
}
