package dev.handoff.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Bluetooth access for moving headphones, plus finding linked devices nearby (one "Nearby devices" permission in Android's words). */
object NearbyPermissions {
    val ALL = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)

    /** Allowed Bluetooth access, but not yet the newer nearby parts (installed before Handoff 0.8). */
    fun missingNearby(context: Context): Boolean =
        granted(context, Manifest.permission.BLUETOOTH_CONNECT) && ALL.any { !granted(context, it) }

    private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
