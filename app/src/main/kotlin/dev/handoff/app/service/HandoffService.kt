package dev.handoff.app.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.handoff.app.automation.PlaybackWatcher
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffRuntime
import dev.handoff.core.handoff.AutoSwitchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

/**
 * Foreground service (type `connectedDevice`) that keeps Handoff reachable while the device is
 * locked, in a bag or the app is in the background. It holds the [HandoffRuntime] (peer server
 * + LAN discovery) and, when enabled, the playback watcher for Ask/Auto switching.
 *
 * It does no periodic work of its own; it only keeps the process eligible to answer requests.
 */
class HandoffService : Service() {
    private val runtime: HandoffRuntime by inject()
    private val settings: SettingsRepository by inject()
    private val playback: PlaybackWatcher by inject()
    private val notifications: Notifications by inject()
    private val appScope: CoroutineScope by inject(named(APP_SCOPE))
    private var settingsJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!enterForeground()) {
            stopSelf()
            return
        }
        appScope.launch { runtime.acquire(HOLDER) }
        settingsJob = appScope.launch {
            settings.settings.map { it.autoSwitchMode }.distinctUntilChanged().collect { mode ->
                if (mode == AutoSwitchMode.OFF) playback.stop() else playback.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        settingsJob?.cancel()
        playback.stop()
        appScope.launch { withContext(NonCancellable) { runtime.release(HOLDER) } }
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SERVICE,
            notifications.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: SecurityException) {
        // connectedDevice requires BLUETOOTH_CONNECT to be granted (Android 14+).
        Log.w(TAG, "cannot start foreground service: ${e.message}")
        false
    } catch (e: IllegalStateException) {
        Log.w(TAG, "cannot start foreground service: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "HandoffService"
        private const val HOLDER = "service"
        const val APP_SCOPE = "appScope"

        fun canRun(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** Returns false if Android refused (e.g. background start restrictions). Never throws. */
        fun start(context: Context): Boolean {
            if (!canRun(context)) return false
            return try {
                context.startForegroundService(Intent(context, HandoffService::class.java))
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "background start not allowed")
                false
            } catch (e: IllegalStateException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandoffService::class.java))
        }
    }
}
