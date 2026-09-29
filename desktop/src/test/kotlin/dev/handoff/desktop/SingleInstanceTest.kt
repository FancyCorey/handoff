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
        Thread.sleep(300) // let the watcher register

        val second = SingleInstance(dir)
        assertFalse(second.acquire())
        second.requestShow()
        assertTrue("first copy asked to show its window", shown.await(10, TimeUnit.SECONDS))
    }
}
