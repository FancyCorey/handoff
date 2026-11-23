package dev.handoff.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** Stops a passive search when playback starts. The explicit move path bypasses this guard. */
internal class NearbyPlaybackGuard(
    private val isMusicActive: () -> Boolean,
    private val subscribe: (onChange: (playing: Boolean) -> Unit) -> AutoCloseable?,
) {
    fun isPlaying(): Boolean = isMusicActive()

    suspend fun whileIdle(onPlayback: () -> Unit, search: suspend () -> Unit) = coroutineScope {
        if (isPlaying()) {
            onPlayback()
            return@coroutineScope
        }

        val playbackStarted = CompletableDeferred<Unit>()
        val subscription = subscribe { playing -> if (playing) playbackStarted.complete(Unit) }
            ?: return@coroutineScope
        try {
            // Register first, then check again to catch playback starting during registration.
            if (isPlaying() || playbackStarted.isCompleted) {
                onPlayback()
                return@coroutineScope
            }
            val job = async(start = CoroutineStart.LAZY) { search() }
            try {
                select<Unit> {
                    playbackStarted.onAwait { onPlayback() }
                    job.onAwait { }
                }
            } finally {
                job.cancel()
            }
        } finally {
            subscription.close()
        }
    }
}
