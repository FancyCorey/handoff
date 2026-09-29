package dev.handoff.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * An older test build of Handoff (`dev.handoff.app.debug`) installs next to the released app
 * and runs its own copy of Handoff, with its own links. Two copies on one device confuse the
 * other devices, so the released app points this out.
 */
object OtherCopies {
    private const val TEST_BUILD = "dev.handoff.app.debug"

    fun testBuildInstalled(context: Context): Boolean =
        context.packageName != TEST_BUILD && try {
            context.packageManager.getPackageInfo(TEST_BUILD, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Android's app info page for the test build, where the user can uninstall it. */
    fun openTestBuildInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", TEST_BUILD, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
