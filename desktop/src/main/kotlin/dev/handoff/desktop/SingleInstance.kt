package dev.handoff.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import kotlin.concurrent.thread

/**
 * Keeps one Handoff per user and data folder. Two copies would compete for the peer port, mDNS
 * and the Bluetooth services. Opening Handoff again asks the running copy to show its window.
 *
 * Uses a lock file plus a "show" file in the data folder; no extra network port is opened.
 */
class SingleInstance(private val dataDir: File) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private val showFile get() = File(dataDir, SHOW_FILE)

    /** True if this process is now the only Handoff; false if another copy already runs. */
    fun acquire(): Boolean {
        dataDir.mkdirs()
        val c = RandomAccessFile(File(dataDir, LOCK_FILE), "rw").channel
        val l = try {
            c.tryLock()
        } catch (_: Exception) {
            null
        }
        if (l == null) {
            c.close()
            return false
        }
        channel = c
        lock = l
        showFile.delete()
        return true
    }

    /** Ask the running copy to bring its window to the front. */
    fun requestShow() {
        runCatching { showFile.writeText(System.currentTimeMillis().toString()) }
    }

    /** Calls [onShow] whenever another copy asks for the window. */
    fun watch(onShow: () -> Unit) {
        thread(isDaemon = true, name = "handoff-single-instance") {
            runCatching {
                FileSystems.getDefault().newWatchService().use { watcher ->
                    dataDir.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
                    while (true) {
                        val key = watcher.take()
                        val asked = key.pollEvents().any { it.context()?.toString() == SHOW_FILE }
                        key.reset()
                        if (asked && showFile.exists()) {
                            showFile.delete()
                            onShow()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val LOCK_FILE = "handoff.lock"
        const val SHOW_FILE = "show.request"
    }
}
