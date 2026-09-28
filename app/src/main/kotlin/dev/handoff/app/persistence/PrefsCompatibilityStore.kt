package dev.handoff.app.persistence

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.handoff.bluetooth.CompatibilityStore

/**
 * Remembers which Bluetooth strategies were *verified* to work on this device. Keyed by the
 * OS build fingerprint so an OS update resets the device to "Experimental" until re-verified.
 */
class PrefsCompatibilityStore(context: Context) : CompatibilityStore {
    private val prefs = context.applicationContext.getSharedPreferences("bt_compat", Context.MODE_PRIVATE)
    private val build = Build.FINGERPRINT

    override fun isVerified(strategy: String): Boolean = prefs.getString("verified:$strategy", null) == build

    override fun markVerified(strategy: String) {
        if (!isVerified(strategy)) prefs.edit { putString("verified:$strategy", build) }
    }
}
