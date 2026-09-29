package dev.handoff.app.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.handoff.app.BuildConfig
import dev.handoff.app.persistence.SettingsRepository
import dev.handoff.core.update.UpdateCheck
import dev.handoff.core.update.UpdateChecker
import dev.handoff.core.update.UpdateManifest
import dev.handoff.core.update.UpdatePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Finds, downloads and installs Handoff updates from GitHub Releases (see [UpdateChecker] for
 * the signature and checksum checks). Android always shows its own confirmation before
 * installing, and only an APK signed with the same release key can replace this app.
 */
// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class AppUpdates(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val checker: UpdateChecker = UpdateChecker(BuildConfig.VERSION_NAME),
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val manifest: UpdateManifest) : State
        data class Downloading(val manifest: UpdateManifest, val progress: Float) : State
        data class NeedsInstallPermission(val manifest: UpdateManifest) : State
        data class Failed(val message: String, val manifest: UpdateManifest? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Debug builds have their own app id, so a release APK would install next to them. */
    val canInstallInApp: Boolean get() = !BuildConfig.DEBUG

    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        scope.launch {
            _state.value = State.Checking
            _state.value = when (val result = checker.check()) {
                is UpdateCheck.Available -> State.Available(result.manifest)
                is UpdateCheck.UpToDate -> State.UpToDate(result.latest)
                is UpdateCheck.Failed -> State.Failed(result.reason)
            }
            settings.setLastUpdateCheck(System.currentTimeMillis())
        }
    }

    /** Called at app start: checks at most once a day, and only if the user opted in. */
    fun maybeAutoCheck() {
        scope.launch {
            val s = settings.current()
            if (s.autoUpdateCheck && System.currentTimeMillis() - s.lastUpdateCheckMs > DAY_MS) check()
        }
    }

    fun downloadAndInstall(manifest: UpdateManifest) {
        val asset = manifest.asset(UpdatePlatform.ANDROID) ?: run {
            _state.value = State.Failed("This release has no Android download.", manifest)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsInstallPermission(manifest)
            return
        }
        scope.launch {
            val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, asset.name)
            _state.value = State.Downloading(manifest, 0f)
            val ok = checker.download(asset, file) { _state.value = State.Downloading(manifest, it) }
            if (!ok) {
                _state.value = State.Failed("The download didn't match the signed release, so it was discarded.", manifest)
                return@launch
            }
            _state.value = State.Available(manifest)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Android's "Install unknown apps" switch for Handoff. */
    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openReleasePage(manifest: UpdateManifest?) {
        val url = manifest?.releaseUrl ?: "${UpdateChecker.RELEASE_PAGE_PREFIX}latest"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
