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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.handoff.app.BuildConfig
import dev.handoff.app.update.AppUpdates
import dev.handoff.app.update.AppUpdates.State

/** Check for updates, the opt-in daily check, and installing a release that was found. */
@Composable
fun UpdateSection(
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
                    State.Idle -> "Version ${BuildConfig.VERSION_NAME}. Updates come from Handoff's GitHub releases and are signature-checked."
                    State.Checking -> "Checking…"
                    is State.UpToDate -> "You're up to date (${state.latest})."
                    is State.Available -> "Handoff ${state.manifest.version} is available."
                    is State.Downloading -> "Downloading Handoff ${state.manifest.version}…"
                    is State.NeedsInstallPermission -> "Allow Handoff to install updates, then tap Install."
                    is State.Failed -> state.message
                },
            )
        },
        trailingContent = { if (state == State.Checking) CircularProgressIndicator(Modifier.size(24.dp)) },
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
                        Button(onClick = updates::openInstallPermission) { Text("Allow installs") }
                        OutlinedButton(onClick = { updates.downloadAndInstall(manifest) }) { Text("Install") }
                    }
                    canInstallInApp && state !is State.Downloading ->
                        Button(onClick = { updates.downloadAndInstall(manifest) }) { Text("Download and install") }
                    else -> Unit
                }
                OutlinedButton(onClick = { updates.openReleasePage(manifest) }) { Text("Release page") }
            }
        }
    }
    ListItem(
        headlineContent = { Text("Check automatically") },
        supportingContent = { Text("Once a day, when Handoff opens. This contacts GitHub; nothing about you or your devices is sent.") },
        trailingContent = { Switch(checked = autoCheck, onCheckedChange = onAutoCheck) },
    )
}
