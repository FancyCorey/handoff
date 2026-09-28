package dev.handoff.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.handoff.app.MainActivity
import dev.handoff.app.R
import dev.handoff.core.model.LogicalDeviceId

class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
                    .apply { description = context.getString(R.string.channel_service_desc) },
                NotificationChannel(CHANNEL_PROMPTS, context.getString(R.string.channel_prompts), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_prompts_desc) },
            ),
        )
    }

    fun serviceNotification(): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(context.getString(R.string.notif_service_text))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notif_action_stop),
                    NotificationActionReceiver.stopIntent(context),
                ).build(),
            )
            .build()

    fun showAsk(logicalId: LogicalDeviceId, deviceName: String, ownerName: String) {
        val notification = Notification.Builder(context, CHANNEL_PROMPTS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.notif_ask_title))
            .setContentText(context.getString(R.string.notif_ask_text, deviceName, ownerName))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setTimeoutAfter(ASK_TIMEOUT_MS)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notif_action_move_here),
                    NotificationActionReceiver.moveHereIntent(context, logicalId),
                ).build(),
            )
            .build()
        runCatching { manager.notify(ID_ASK, notification) }
    }

    fun cancelAsk() = manager.cancel(ID_ASK)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_SERVICE = "handoff_service"
        const val CHANNEL_PROMPTS = "handoff_prompts"
        const val ID_SERVICE = 1
        const val ID_ASK = 2
        private const val ASK_TIMEOUT_MS = 60_000L
    }
}
