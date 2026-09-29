package dev.handoff.desktop

import dev.handoff.core.update.UpdateCheck
import dev.handoff.core.update.UpdateChecker
import dev.handoff.core.update.UpdateManifest
import dev.handoff.core.update.UpdatePlatform
import dev.handoff.desktop.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Windows side of updates from GitHub Releases. The installed app downloads the signed release's
 * MSI, checks it against the signed manifest and hands it to Windows Installer; a portable copy
 * (or a development run) opens the release page instead.
 */
class DesktopUpdates(
    private val version: String,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val checker: UpdateChecker = UpdateChecker(version),
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val manifest: UpdateManifest) : State
        data class Downloading(val manifest: UpdateManifest, val progress: Float) : State
        data class Failed(val message: String, val manifest: UpdateManifest? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True for the MSI-installed app; portable copies and development runs update by hand. */
    val canInstallInApp: Boolean = version != "dev" && installedAppDir() != null

    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        scope.launch {
            _state.value = State.Checking
            _state.value = when (val result = checker.check()) {
                is UpdateCheck.Available -> State.Available(result.manifest)
                is UpdateCheck.UpToDate -> State.UpToDate(result.latest)
                is UpdateCheck.Failed -> State.Failed(result.reason)
            }
            settings.update { it.copy(lastUpdateCheckMs = System.currentTimeMillis()) }
        }
    }

    fun maybeAutoCheck() {
        val s = settings.settings.value
        if (s.autoUpdateCheck && System.currentTimeMillis() - s.lastUpdateCheckMs > DAY_MS) check()
    }

    /**
     * Downloads and verifies the MSI, starts Windows Installer and calls [quit], so the installer
     * can replace this app's files. Windows Installer shows its own progress and prompts.
     */
    fun downloadAndInstall(manifest: UpdateManifest, quit: () -> Unit) {
        val asset = manifest.asset(UpdatePlatform.WINDOWS_MSI) ?: run {
            _state.value = State.Failed("This release has no Windows installer.", manifest)
            return
        }
        scope.launch {
            val file = File(File(System.getProperty("java.io.tmpdir"), "Handoff-update"), asset.name)
            _state.value = State.Downloading(manifest, 0f)
            if (!checker.download(asset, file) { _state.value = State.Downloading(manifest, it) }) {
                _state.value = State.Failed("The download didn't match the signed release, so it was discarded.", manifest)
                return@launch
            }
            try {
                ProcessBuilder("msiexec.exe", "/i", file.absolutePath).start()
                quit()
            } catch (e: Exception) {
                _state.value = State.Failed("Windows Installer couldn't start (${e.javaClass.simpleName}).", manifest)
            }
        }
    }

    fun openReleasePage(manifest: UpdateManifest?) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(manifest?.releaseUrl ?: "${UpdateChecker.RELEASE_PAGE_PREFIX}latest")) }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** %LOCALAPPDATA%\Handoff when this process runs from the MSI installation. */
        fun installedAppDir(): File? {
            val installDir = System.getenv("LOCALAPPDATA")?.let { File(it, "Handoff") } ?: return null
            val launchedFrom = System.getProperty("jpackage.app-path")?.let(::File)?.parentFile
                ?: System.getProperty("compose.application.resources.dir")?.let(::File)?.parentFile?.parentFile
            return installDir.takeIf { launchedFrom != null && launchedFrom.canonicalFile == it.canonicalFile }
        }
    }
}
