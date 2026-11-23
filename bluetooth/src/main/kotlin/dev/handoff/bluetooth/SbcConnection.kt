package dev.handoff.bluetooth

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

enum class CodecPreferenceStatus {
    WAITING_FOR_CONNECTION,
    WAITING_FOR_PAUSE,
    CHECKING,
    SBC_CONFIRMED,
    ACCESS_NEEDED,
    UNAVAILABLE,
}

internal enum class SbcRead { ACTIVE, SELECTABLE, NOT_READY, ACCESS_NEEDED, UNAVAILABLE }
internal enum class SbcWrite { REQUESTED, WAITING_FOR_PAUSE, DISCONNECTED, ACCESS_NEEDED, UNAVAILABLE }

/** One check-and-apply opportunity per A2DP connection, even if re-enabled mid-link. */
internal class SbcConnection {
    private enum class Attempt { OPEN, RESERVED, VERIFYING, DONE }

    private val attempt = AtomicReference(Attempt.OPEN)

    suspend fun apply(
        read: () -> SbcRead,
        isStillConnected: () -> Boolean,
        mayChangeCodec: () -> Boolean,
        awaitPause: suspend () -> Unit,
        write: () -> SbcWrite,
        report: (CodecPreferenceStatus) -> Unit,
    ): CodecPreferenceStatus {
        if (attempt.get() == Attempt.VERIFYING) {
            report(CodecPreferenceStatus.CHECKING)
            return verify(read, isStillConnected)
        }
        while (true) {
            if (!isStillConnected()) return CodecPreferenceStatus.WAITING_FOR_CONNECTION
            report(CodecPreferenceStatus.CHECKING)
            when (observeReady(read)) {
                SbcRead.ACTIVE -> {
                    attempt.set(Attempt.DONE)
                    return CodecPreferenceStatus.SBC_CONFIRMED
                }
                SbcRead.ACCESS_NEEDED -> return CodecPreferenceStatus.ACCESS_NEEDED
                SbcRead.NOT_READY, SbcRead.UNAVAILABLE -> return CodecPreferenceStatus.UNAVAILABLE
                SbcRead.SELECTABLE -> Unit
            }
            if (attempt.get() != Attempt.OPEN) return CodecPreferenceStatus.UNAVAILABLE
            if (!isStillConnected()) return CodecPreferenceStatus.WAITING_FOR_CONNECTION
            if (!mayChangeCodec()) {
                report(CodecPreferenceStatus.WAITING_FOR_PAUSE)
                awaitPause()
                continue
            }

            currentCoroutineContext().ensureActive()
            if (!attempt.compareAndSet(Attempt.OPEN, Attempt.RESERVED)) return CodecPreferenceStatus.UNAVAILABLE
            val writeResult = try {
                write()
            } catch (cancelled: CancellationException) {
                attempt.set(Attempt.OPEN)
                throw cancelled
            }
            when (writeResult) {
                SbcWrite.WAITING_FOR_PAUSE -> {
                    attempt.set(Attempt.OPEN)
                    report(CodecPreferenceStatus.WAITING_FOR_PAUSE)
                    awaitPause()
                }
                SbcWrite.DISCONNECTED -> {
                    attempt.set(Attempt.OPEN)
                    return CodecPreferenceStatus.WAITING_FOR_CONNECTION
                }
                SbcWrite.ACCESS_NEEDED -> {
                    attempt.set(Attempt.OPEN)
                    return CodecPreferenceStatus.ACCESS_NEEDED
                }
                SbcWrite.UNAVAILABLE -> {
                    attempt.set(Attempt.DONE)
                    return CodecPreferenceStatus.UNAVAILABLE
                }
                SbcWrite.REQUESTED -> {
                    attempt.set(Attempt.VERIFYING)
                    return verify(read, isStillConnected)
                }
            }
        }
    }

    private suspend fun verify(read: () -> SbcRead, isStillConnected: () -> Boolean): CodecPreferenceStatus {
        val result = awaitReadback(read, isStillConnected)
        if (result != CodecPreferenceStatus.WAITING_FOR_CONNECTION) attempt.set(Attempt.DONE)
        return result
    }

    private suspend fun awaitReadback(read: () -> SbcRead, isStillConnected: () -> Boolean): CodecPreferenceStatus {
        for (waitMs in RETRY_DELAYS_MS) {
            delay(waitMs)
            if (!isStillConnected()) return CodecPreferenceStatus.WAITING_FOR_CONNECTION
            when (read()) {
                SbcRead.ACTIVE -> return CodecPreferenceStatus.SBC_CONFIRMED
                SbcRead.ACCESS_NEEDED -> return CodecPreferenceStatus.ACCESS_NEEDED
                SbcRead.UNAVAILABLE -> return CodecPreferenceStatus.UNAVAILABLE
                SbcRead.SELECTABLE, SbcRead.NOT_READY -> Unit
            }
        }
        return CodecPreferenceStatus.UNAVAILABLE
    }

    private suspend fun observeReady(read: () -> SbcRead): SbcRead {
        for (waitMs in RETRY_DELAYS_MS) {
            delay(waitMs)
            val result = read()
            if (result != SbcRead.NOT_READY) return result
        }
        return SbcRead.NOT_READY
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(0, 200, 400, 800, 1_000)
    }
}
