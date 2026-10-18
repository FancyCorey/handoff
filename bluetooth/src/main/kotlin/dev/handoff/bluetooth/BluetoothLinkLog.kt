package dev.handoff.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Build
import dev.handoff.core.model.Redaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A timeline of what the Bluetooth stack reports about audio devices, next to what Handoff did,
 * for working out audio problems such as cut-outs after a move: when the Bluetooth link to the
 * headphones opened and closed, which profiles were connected, when audio started and stopped
 * streaming, and which codec was in use.
 *
 * Everything here comes from public system broadcasts (the codec one where the system shares it)
 * and is kept in memory only. Addresses are shortened, as everywhere in diagnostics.
 */
class BluetoothLinkLog(private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val atMs: Long, val text: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Bluetooth link (ACL) state per address, as last broadcast. */
    private val links = mutableMapOf<String, Boolean>()

    fun isLinked(address: String): Boolean? = synchronized(links) { links[address.uppercase()] }

    fun note(text: String) {
        _entries.update { (it + Entry(clock(), text)).takeLast(CAPACITY) }
    }

    /** Records one system broadcast about [device]; returns true if it was about an audio device. */
    fun onBroadcast(action: String, intent: Intent, device: BluetoothDevice?, isAudio: Boolean): Boolean {
        if (device == null || !isAudio) return false
        val who = label(device)
        val text = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                synchronized(links) { links[device.address.uppercase()] = true }
                "Link opened · $who"
            }
            BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> "Link closing · $who"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                synchronized(links) { links[device.address.uppercase()] = false }
                "Link closed · $who"
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "Media (A2DP) ${state(intent)} · $who"
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "Calls (HFP) ${state(intent)} · $who"
            ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> "LE Audio ${state(intent)} · $who"
            BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                val playing = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothA2dp.STATE_PLAYING
                (if (playing) "Audio streaming" else "Audio stopped") + " · $who"
            }
            ACTION_CODEC_CONFIG_CHANGED -> "Codec ${codec(intent) ?: "changed (details not shared by the system)"} · $who"
            else -> return false
        }
        note(text)
        return true
    }

    private fun state(intent: Intent): String = when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        else -> "state ${intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)}"
    }

    @Suppress("DEPRECATION")
    private fun codec(intent: Intent): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val status = try {
            intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return describe(status.codecConfig)
    }

    private fun label(device: BluetoothDevice): String {
        val name = try {
            device.alias ?: device.name
        } catch (_: SecurityException) {
            null
        }
        return "${name ?: "device"} (${Redaction.address(device.address)})"
    }

    companion object {
        private const val CAPACITY = 300

        /** Hidden constant, stable string; delivered where the system allows it. */
        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED = "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"

        /** "AAC 44.1 kHz 16-bit stereo", from a codec configuration. */
        fun describe(config: BluetoothCodecConfig?): String? {
            if (config == null || Build.VERSION.SDK_INT < 33) return null
            val type = when (config.codecType) {
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
                else -> "type ${config.codecType}"
            }
            val rate = when (config.sampleRate) {
                BluetoothCodecConfig.SAMPLE_RATE_44100 -> "44.1 kHz"
                BluetoothCodecConfig.SAMPLE_RATE_48000 -> "48 kHz"
                BluetoothCodecConfig.SAMPLE_RATE_88200 -> "88.2 kHz"
                BluetoothCodecConfig.SAMPLE_RATE_96000 -> "96 kHz"
                else -> null
            }
            val bits = when (config.bitsPerSample) {
                BluetoothCodecConfig.BITS_PER_SAMPLE_16 -> "16-bit"
                BluetoothCodecConfig.BITS_PER_SAMPLE_24 -> "24-bit"
                BluetoothCodecConfig.BITS_PER_SAMPLE_32 -> "32-bit"
                else -> null
            }
            return listOfNotNull(type, rate, bits).joinToString(" ")
        }
    }
}
