package dev.handoff.desktop.bluetooth

import dev.handoff.core.bluetooth.BluetoothOperationResult
import dev.handoff.core.bluetooth.ConnectReason
import dev.handoff.core.bluetooth.DisconnectReason
import dev.handoff.core.model.BluetoothDeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real-hardware check of the Windows release/connect path, on the PC running the tests.
 * Skipped unless a headset is named; it moves real audio, so only run it on purpose:
 *
 * `./gradlew :desktop:test --tests '*WindowsHeadsetHardwareTest*' -Dhandoff.hw.headset="ATH-M20xBT" -Dhandoff.hw.out=hw.txt`
 *
 * The headset must be on, in range and free (not playing on another device). The test connects
 * it, runs [CYCLES] release→connect cycles through [WindowsBluetoothAudioController], and leaves
 * it connected to this PC.
 */
class WindowsHeadsetHardwareTest {
    private class MemoryReleasedStore : ReleasedServicesStore {
        override val released: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override suspend fun setReleased(address: String, released: Boolean) = Unit
    }

    @Test
    fun `release and connect a real headset`() = runBlocking {
        val name = System.getProperty("handoff.hw.headset")
        assumeTrue("set -Dhandoff.hw.headset to run", !name.isNullOrBlank())
        val out = File(System.getProperty("handoff.hw.out") ?: "handoff-hw.txt").apply { writeText("") }
        fun log(line: String) = out.appendText(line + "\n").also { println(line) }

        val device = Win32Bluetooth.rememberedDevices().firstOrNull { it.isAudio && it.name.contains(name!!, ignoreCase = true) }
            ?: error("no paired audio device matching \"$name\"")
        val id = BluetoothDeviceId(device.address)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bt = WindowsBluetoothAudioController(scope, MemoryReleasedStore())
        bt.refresh()
        log("headset: ${device.name}, connected at start: ${device.connected}")

        if (!bt.isConnected(id)) {
            val started = System.currentTimeMillis()
            log("initial connect: ${bt.connect(id, ConnectReason.DEBUG).describe()}, verified=${bt.verifyConnected(id, CONNECT_TIMEOUT_MS)} in ${System.currentTimeMillis() - started} ms")
        }
        var passed = 0
        repeat(CYCLES) { cycle ->
            val releaseStart = System.currentTimeMillis()
            val release = bt.disconnect(id, DisconnectReason.DEBUG)
            val released = bt.verifyDisconnected(id, RELEASE_TIMEOUT_MS)
            val releaseMs = System.currentTimeMillis() - releaseStart
            val connectStart = System.currentTimeMillis()
            val connect = bt.connect(id, ConnectReason.DEBUG)
            val connected = bt.verifyConnected(id, CONNECT_TIMEOUT_MS)
            val connectMs = System.currentTimeMillis() - connectStart
            if (released && connected) passed++
            log(
                "cycle ${cycle + 1}: release ${release.describe()} verified=$released ${releaseMs} ms; " +
                    "connect ${connect.describe()} verified=$connected ${connectMs} ms",
            )
        }
        log("battery: ${bt.batteryLevels.value[device.address.uppercase()] ?: "not reported"}")
        log("result: $passed/$CYCLES cycles passed")
        scope.cancel()
        assertTrue("$passed/$CYCLES cycles passed", passed == CYCLES)
    }

    private fun BluetoothOperationResult.describe() = when (this) {
        is BluetoothOperationResult.Requested -> "ok"
        BluetoothOperationResult.AlreadyInState -> "already"
        is BluetoothOperationResult.Failed -> "failed ${error.name}: $detail"
    }

    private companion object {
        const val CYCLES = 3
        const val RELEASE_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 25_000L
    }
}
