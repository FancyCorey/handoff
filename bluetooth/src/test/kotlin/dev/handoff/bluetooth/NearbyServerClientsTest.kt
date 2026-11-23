package dev.handoff.bluetooth

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyServerClientsTest {
    @Test
    fun `shutdown closes active clients and closes any client accepted after shutdown`() {
        val clients = NearbyServerClients()
        val active = Client()
        val finished = Client()
        assertTrue(clients.add(active))
        assertTrue(clients.add(finished))
        clients.remove(finished)

        clients.close()
        clients.close()
        assertEquals(1, active.closes)
        assertEquals(0, finished.closes)

        val late = Client()
        assertFalse(clients.add(late))
        assertEquals(1, late.closes)
    }

    @Test
    fun `one failed close cannot strand the other clients`() {
        val clients = NearbyServerClients()
        val active = Client()
        clients.add(Closeable { throw IOException("Bluetooth turned off") })
        clients.add(active)

        clients.close()

        assertEquals(1, active.closes)
    }

    private class Client : Closeable {
        var closes = 0

        override fun close() {
            closes++
        }
    }
}
