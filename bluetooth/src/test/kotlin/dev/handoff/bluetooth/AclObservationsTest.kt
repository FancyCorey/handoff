package dev.handoff.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AclObservationsTest {
    @Test
    fun `closing one transport does not mark an overlapping link as closed`() {
        for ((staysOpen, closesFirst) in listOf(AclTransport.CLASSIC to AclTransport.LE, AclTransport.LE to AclTransport.CLASSIC)) {
            val acl = AclObservations()
            acl.record("aa:bb:00:00:00:00", staysOpen, open = true)
            acl.record("AA:BB:00:00:00:00", closesFirst, open = true)
            acl.record("aa:bb:00:00:00:00", closesFirst, open = false)
            assertEquals(true, acl.isLinked("AA:BB:00:00:00:00"))

            acl.record("AA:BB:00:00:00:00", staysOpen, open = false)
            assertEquals(false, acl.isLinked("aa:bb:00:00:00:00"))
        }
    }

    @Test
    fun `untyped events keep the legacy latest observation`() {
        val acl = AclObservations()
        acl.record(HEADSET, AclTransport.CLASSIC, open = true)
        acl.record(HEADSET, AclTransport.UNSPECIFIED, open = false)
        assertEquals(false, acl.isLinked(HEADSET))

        acl.record(HEADSET, AclTransport.UNSPECIFIED, open = true)
        acl.record(HEADSET, AclTransport.LE, open = false)
        assertEquals(false, acl.isLinked(HEADSET))
    }

    @Test
    fun `adapter reset discards previous observations for every address`() {
        val acl = AclObservations()
        acl.record(HEADSET, AclTransport.CLASSIC, open = true)
        acl.record("00:11:22:33:44:99", AclTransport.LE, open = false)

        acl.clear()

        assertNull(acl.isLinked(HEADSET))
        assertNull(acl.isLinked("00:11:22:33:44:99"))
    }

    private companion object {
        const val HEADSET = "00:11:22:33:44:55"
    }
}
