package dev.handoff.bluetooth

import android.bluetooth.BluetoothClass
import dev.handoff.core.model.AudioDeviceKind

/** Classifies bonded devices from their Bluetooth Class of Device and advertised service UUIDs. */
internal object DeviceClassifier {
    /** Assigned number for the A2DP "Audio Sink" service class. */
    const val A2DP_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"

    fun kind(majorClass: Int?, deviceClass: Int?): AudioDeviceKind = when (deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> AudioDeviceKind.HEADPHONES
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        -> AudioDeviceKind.HEADSET
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        -> AudioDeviceKind.SPEAKER
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> AudioDeviceKind.CAR_AUDIO
        else -> if (majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO) AudioDeviceKind.OTHER_AUDIO else AudioDeviceKind.UNKNOWN
    }

    fun advertisesA2dpSink(uuids: List<String>): Boolean = uuids.any { it.equals(A2DP_SINK_UUID, ignoreCase = true) }

    fun isAudio(majorClass: Int?, uuids: List<String>): Boolean =
        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO || advertisesA2dpSink(uuids)
}
