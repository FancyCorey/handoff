package dev.handoff.app.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.handoff.core.handoff.AutoSwitchMode
import dev.handoff.core.model.LogicalDeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AppSettings(
    val onboardingComplete: Boolean = false,
    /** Run the foreground service so peers can reach this device in the background. Opt-in: it needs a persistent notification. */
    val backgroundEnabled: Boolean = false,
    val restoreOnBoot: Boolean = true,
    val preferredDevice: LogicalDeviceId? = null,
    val autoSwitchMode: AutoSwitchMode = AutoSwitchMode.OFF,
    /** Opt-in: look for a new release on GitHub once a day. */
    val autoUpdateCheck: Boolean = false,
    val lastUpdateCheckMs: Long = 0,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.settingsStore

    val settings: StateFlow<AppSettings> = store.data.map(::toSettings).stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Repairs a preferred-headset id that no longer exists (e.g. it converged with a peer's id before this fix). */
    suspend fun repairPreferred(existing: Set<LogicalDeviceId>) {
        val current = current().preferredDevice
        if (current != null && current !in existing) setPreferredDevice(existing.firstOrNull())
    }

    /** Reads the persisted values (use before acting on settings at process start). */
    suspend fun current(): AppSettings = toSettings(store.data.first())

    private fun toSettings(p: Preferences) = AppSettings(
        onboardingComplete = p[ONBOARDING] ?: false,
        backgroundEnabled = p[BACKGROUND] ?: false,
        restoreOnBoot = p[BOOT] ?: true,
        preferredDevice = p[PREFERRED]?.let(::LogicalDeviceId),
        autoUpdateCheck = p[AUTO_UPDATE] ?: false,
        lastUpdateCheckMs = p[LAST_UPDATE_CHECK] ?: 0,
        autoSwitchMode = p[AUTO_MODE]?.let { runCatching { AutoSwitchMode.valueOf(it) }.getOrNull() } ?: AutoSwitchMode.OFF,
    )

    suspend fun setOnboardingComplete(value: Boolean) = store.edit { it[ONBOARDING] = value }
    suspend fun setBackgroundEnabled(value: Boolean) = store.edit { it[BACKGROUND] = value }
    suspend fun setRestoreOnBoot(value: Boolean) = store.edit { it[BOOT] = value }
    suspend fun setAutoSwitchMode(value: AutoSwitchMode) = store.edit { it[AUTO_MODE] = value.name }
    suspend fun setAutoUpdateCheck(value: Boolean) = store.edit { it[AUTO_UPDATE] = value }
    suspend fun setLastUpdateCheck(atMs: Long) = store.edit { it[LAST_UPDATE_CHECK] = atMs }
    suspend fun setPreferredDevice(value: LogicalDeviceId?) = store.edit {
        if (value == null) it.remove(PREFERRED) else it[PREFERRED] = value.value
    }

    private companion object {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val BACKGROUND = booleanPreferencesKey("background_enabled")
        val BOOT = booleanPreferencesKey("restore_on_boot")
        val PREFERRED = stringPreferencesKey("preferred_device")
        val AUTO_MODE = stringPreferencesKey("auto_switch_mode")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }
}
