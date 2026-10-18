package dev.handoff.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** The Android settings screens that Handoff's fix buttons open. */
object SystemScreens {
    private fun open(context: Context, intent: Intent, fallback: Intent? = null) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent.addFlags(flags))
        } catch (_: ActivityNotFoundException) {
            fallback?.let { context.startActivity(it.addFlags(flags)) }
        } catch (_: SecurityException) {
            fallback?.let { context.startActivity(it.addFlags(flags)) }
        }
    }

    fun bluetoothSettings(context: Context) = open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun wifiSettings(context: Context) = open(context, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))

    fun appSettings(context: Context) =
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    /**
     * "Turn on Bluetooth": Android's own prompt when Handoff may use Bluetooth, else the app's
     * settings so the Nearby devices permission can be allowed first.
     */
    fun turnOnBluetooth(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            appSettings(context)
        } else {
            open(context, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    /** The system "Hotspot & tethering" screen; not a public constant, so fall back to wireless settings. */
    fun hotspot(context: Context) = open(context, Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
}
