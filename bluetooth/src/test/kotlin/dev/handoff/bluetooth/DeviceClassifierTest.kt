package dev.handoff.bluetooth

import android.bluetooth.BluetoothClass
import dev.handoff.core.model.AudioDeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClassifierTest {
    private val av = BluetoothClass.Device.Major.AUDIO_VIDEO

    @Test
    fun `audio classes map to kinds`() {
        assertEquals(AudioDeviceKind.HEADPHONES, DeviceClassifier.kind(av, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals(AudioDeviceKind.HEADSET, DeviceClassifier.kind(av, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertEquals(AudioDeviceKind.SPEAKER, DeviceClassifier.kind(av, BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertEquals(AudioDeviceKind.CAR_AUDIO, DeviceClassifier.kind(av, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertEquals(AudioDeviceKind.OTHER_AUDIO, DeviceClassifier.kind(av, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR))
        assertEquals(AudioDeviceKind.UNKNOWN, DeviceClassifier.kind(BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.PHONE_SMART))
        assertEquals(AudioDeviceKind.UNKNOWN, DeviceClassifier.kind(null, null))
    }

    @Test
    fun `a2dp sink uuid marks a device as audio even with a generic class`() {
        val uuids = listOf("0000110B-0000-1000-8000-00805F9B34FB")
        assertTrue(DeviceClassifier.advertisesA2dpSink(uuids))
        assertTrue(DeviceClassifier.isAudio(BluetoothClass.Device.Major.UNCATEGORIZED, uuids))
        assertFalse(DeviceClassifier.isAudio(BluetoothClass.Device.Major.PHONE, listOf("0000110a-0000-1000-8000-00805f9b34fb")))
    }
}
