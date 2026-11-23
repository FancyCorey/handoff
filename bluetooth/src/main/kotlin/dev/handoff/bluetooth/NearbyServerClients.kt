package dev.handoff.bluetooth

import java.io.Closeable

/** The accepted sockets owned by one listener. Closing the listener alone leaves these open. */
internal class NearbyServerClients : Closeable {
    private val clients = mutableSetOf<Closeable>()
    private var closed = false

    fun add(client: Closeable): Boolean {
        synchronized(this) {
            if (!closed) {
                clients.add(client)
                return true
            }
        }
        runCatching { client.close() }
        return false
    }

    @Synchronized
    fun remove(client: Closeable) {
        clients.remove(client)
    }

    override fun close() {
        val open = synchronized(this) {
            closed = true
            clients.toList().also { clients.clear() }
        }
        open.forEach { client -> runCatching { client.close() } }
    }
}
