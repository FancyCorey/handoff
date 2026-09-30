package dev.handoff.app.update

import kotlinx.coroutines.flow.StateFlow

/**
 * How this edition of Handoff gets updates, as seen by the shared screens.
 *
 * The GitHub edition checks Handoff's signed GitHub releases and can install them itself
 * (src/github, `AppUpdates`). The Google Play edition leaves updates to Google Play and has no
 * way to download or install an APK (src/play, `PlayStoreUpdates`).
 */
interface UpdateService {
    /** A newer Handoff that can be installed from inside the app, or null. */
    val newerVersion: StateFlow<String?>

    /** Called once when the app opens. */
    fun onAppStart()
}
