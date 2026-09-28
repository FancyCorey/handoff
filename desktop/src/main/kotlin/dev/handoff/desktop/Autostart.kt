package dev.handoff.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** Start-at-login via the per-user Run key (no admin rights needed). */
object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "Handoff"

    /** Path of the installed Handoff.exe, or null when running from a development JVM. */
    fun launcherPath(): String? = ProcessHandle.current().info().command().orElse(null)
        ?.takeIf { it.endsWith("Handoff.exe", ignoreCase = true) }

    fun isEnabled(): Boolean = try {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
    } catch (_: Throwable) {
        false
    }

    fun setEnabled(enabled: Boolean) {
        try {
            if (enabled) {
                val exe = launcherPath() ?: return
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, "\"$exe\" --minimized")
            } else if (isEnabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            }
        } catch (_: Throwable) {
        }
    }
}
