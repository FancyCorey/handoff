package dev.handoff.desktop.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class Win32BluetoothTest {
    @Test
    fun `address formatting is most-significant byte first`() {
        assertEquals("00:1A:7D:DA:71:13", Win32Bluetooth.formatAddress(0x001A7DDA7113L))
    }

    @Test
    fun `audio class detection`() {
        // 0x240404: major class 0x04 (audio/video), minor 0x01 (wearable headset).
        val headset = WinBtDevice("00:00:00:00:00:01", "Buds", 0x240404, connected = false)
        assertEquals(true, headset.isAudio)
        assertEquals(0x01, headset.minorClass)
        assertEquals(false, WinBtDevice("x", "Phone", 0x5A020C, false).isAudio)
    }

    /** Read-only: lists remembered devices on the machine running the tests (Windows only). */
    @Test
    fun `lists remembered devices on this machine`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        println("available: ${Win32Bluetooth.available()} ${BthProps.loadError ?: ""}")
        val devices = Win32Bluetooth.rememberedDevices()
        println("radio on: ${Win32Bluetooth.radioOn()}")
        devices.forEach { println("${it.name} class=0x%06X audio=${it.isAudio} connected=${it.connected} addr=**:${it.address.takeLast(5)}".format(it.classOfDevice)) }
    }
}
