package dev.handoff.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    @Test
    fun `a second copy is refused and shows the first window`() {
        val dir = Files.createTempDirectory("handoff-instance").toFile()
        val first = SingleInstance(dir)
        assertTrue(first.acquire())
        val shown = CountDownLatch(1)
        first.watch { shown.countDown() }

        val second = SingleInstance(dir)
        assertFalse(second.acquire())
        // The watcher starts on its own thread; on a busy machine it may not be listening yet, so ask
        // again until it answers (a real second copy starts long after the first one).
        val deadline = System.currentTimeMillis() + 10_000
        var answered = false
        while (!answered && System.currentTimeMillis() < deadline) {
            second.requestShow()
            answered = shown.await(500, TimeUnit.MILLISECONDS)
        }
        assertTrue("first copy asked to show its window", answered)
    }
}
