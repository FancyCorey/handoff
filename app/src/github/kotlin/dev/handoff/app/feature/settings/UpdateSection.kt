package dev.handoff.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.handoff.app.persistence.SettingsRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.handoff.app.BuildConfig
import dev.handoff.app.update.AppUpdates
import dev.handoff.app.update.AppUpdates.State

/** GitHub edition: Handoff checks its signed GitHub releases and installs them itself. */
@Composable
fun UpdateSettings(updates: AppUpdates = koinInject(), settings: SettingsRepository = koinInject()) {
    val state by updates.state.collectAsStateWithLifecycle()
    val s by settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    UpdateSection(
        state = state,
        canInstallInApp = updates.canInstallInApp,
        autoCheck = s.autoUpdateCheck,
        onAutoCheck = { value ->
            scope.launch {
                settings.setAutoUpdateCheck(value)
                if (value) updates.check()
            }
        },
        updates = updates,
    )
}

/** Check for updates, the opt-in daily check, and installing a release that was found. */
@Composable
private fun UpdateSection(
    state: State,
    canInstallInApp: Boolean,
    autoCheck: Boolean,
    onAutoCheck: (Boolean) -> Unit,
    updates: AppUpdates,
) {
    ListItem(
        headlineContent = { Text("Check for updates") },
        supportingContent = {
            Text(
                when (state) {
                    State.Idle -> "Version ${BuildConfig.VERSION_NAME}"
                    State.Checking -> "Checking…"
                    is State.UpToDate -> "Up to date"
                    is State.Available -> "${state.manifest.version} is available"
                    is State.Downloading -> "Downloading…"
                    is State.NeedsInstallPermission -> "Allow Handoff to install updates."
                    is State.Failed -> state.message
                },
            )
        },
        trailingContent = { if (state == State.Checking) CircularProgressIndicator(Modifier.size(24.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = updates::check),
    )
    val manifest = when (state) {
        is State.Available -> state.manifest
        is State.Downloading -> state.manifest
        is State.NeedsInstallPermission -> state.manifest
        is State.Failed -> state.manifest
        else -> null
    }
    if (manifest != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (manifest.notes.isNotBlank()) {
                Text(manifest.notes, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            if (state is State.Downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state is State.NeedsInstallPermission -> {
                        Button(onClick = updates::openInstallPermission) { Text("Allow") }
                        OutlinedButton(onClick = { updates.downloadAndInstall(manifest) }) { Text("Update") }
                    }
                    canInstallInApp && state !is State.Downloading ->
                        Button(onClick = { updates.downloadAndInstall(manifest) }) { Text("Update") }
                    else -> Unit
                }
                OutlinedButton(onClick = { updates.openReleasePage(manifest) }) { Text("What's new") }
            }
        }
    }
    ListItem(
        headlineContent = { Text("Check automatically") },
        supportingContent = { Text("Once a day") },
        trailingContent = { Switch(checked = autoCheck, onCheckedChange = onAutoCheck) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
