package dev.handoff.bluetooth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbySightingTest {
    @Test
    fun `cached contacts do not scan after a missing or failed sighting`() = runTest {
        val attempts = mutableListOf<String>()

        for (cached in listOf(null, "old")) {
            val result = openFromSighting<String, String>(
                cached = cached,
                allowDiscovery = false,
                connect = {
                    attempts.add(it)
                    null
                },
                discover = { error("background contact started a scan") },
            )
            assertNull(result)
        }

        assertEquals(listOf("old"), attempts)
    }

    @Test
    fun `an explicit move looks again when a cached connection fails`() = runTest {
        val attempts = mutableListOf<String>()

        val result = openFromSighting(
            cached = "old",
            allowDiscovery = true,
            connect = {
                attempts.add(it)
                it.takeIf { it == "new" }
            },
            discover = { failed ->
                assertEquals("old", failed)
                "new"
            },
        )

        assertEquals("new", result)
        assertEquals(listOf("old", "new"), attempts)
    }

    @Test
    fun `a cached connection that succeeds is returned without scanning`() = runTest {
        val result = openFromSighting(
            cached = "peer",
            allowDiscovery = true,
            connect = { "connected to $it" },
            discover = { error("successful cached contact started a scan") },
        )

        assertEquals("connected to peer", result)
    }
}
