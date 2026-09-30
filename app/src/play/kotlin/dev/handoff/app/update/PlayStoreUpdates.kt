package dev.handoff.app.update

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play edition: Google Play installs updates. This build contains no update download,
 * no installer hand-off and no REQUEST_INSTALL_PACKAGES permission; it can only open Handoff's
 * store listing.
 */
// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class PlayStoreUpdates(private val context: Context) : UpdateService {
    override val newerVersion: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override fun onAppStart() = Unit

    /** Handoff's page in the Play Store app, or on the web if the Play Store app is missing. */
    fun openStoreListing() {
        val id = context.packageName.removeSuffix(".debug")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
