package dev.handoff.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.app.runtime.HandoffActions
import dev.handoff.core.handoff.TransferTrigger
import dev.handoff.core.model.LogicalDeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Restores the background service after reboot / app update, only if the user enabled it. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val settings: SettingsRepository by inject()
    private val appScope: CoroutineScope by inject(named(HandoffService.APP_SCOPE))

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        appScope.launch {
            try {
                val s = settings.current()
                if (s.onboardingComplete && s.backgroundEnabled && s.restoreOnBoot) {
                    HandoffService.start(context.applicationContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Actions from Handoff notifications. Not exported: only our own PendingIntents reach it. */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val actions: HandoffActions by inject()
    private val settings: SettingsRepository by inject()
    private val notifications: Notifications by inject()
    private val appScope: CoroutineScope by inject(named(HandoffService.APP_SCOPE))

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MOVE_HERE -> {
                notifications.cancelAsk()
                val id = intent.getStringExtra(EXTRA_LOGICAL_ID) ?: return
                actions.moveHere(LogicalDeviceId(id), TransferTrigger.NOTIFICATION)
            }
            ACTION_STOP -> {
                val pending = goAsync()
                appScope.launch {
                    try {
                        settings.setBackgroundEnabled(false)
                        HandoffService.stop(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_MOVE_HERE = "dev.handoff.action.MOVE_HERE"
        private const val ACTION_STOP = "dev.handoff.action.STOP_SERVICE"
        private const val EXTRA_LOGICAL_ID = "logicalId"

        fun moveHereIntent(context: Context, logicalId: LogicalDeviceId): PendingIntent = PendingIntent.getBroadcast(
            context,
            logicalId.value.hashCode(),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_MOVE_HERE)
                .putExtra(EXTRA_LOGICAL_ID, logicalId.value),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
