package dev.handoff.app.ads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The advertising slot of the Google Play edition, as seen by the rest of the app.
 *
 * Deliberately narrow and one-way: nothing is ever passed to it (no headsets, device names,
 * addresses, peers or transfers), and nothing in Handoff waits for it. Moving a headset,
 * linking, background mode, the tile and diagnostics work the same whether ads load, fail or
 * are turned off. The GitHub edition binds [NoOpAdService] and contains no advertising SDK.
 */
interface AdService {
    /** True while a banner may be shown: consent allows ads and the user hasn't removed them. */
    val canShowAds: StateFlow<Boolean>
}

/** No ads, ever. */
object NoOpAdService : AdService {
    override val canShowAds: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}
