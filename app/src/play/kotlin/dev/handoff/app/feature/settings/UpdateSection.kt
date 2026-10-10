package dev.handoff.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.handoff.app.BuildConfig
import dev.handoff.app.update.PlayStoreUpdates
import dev.handoff.app.update.UpdateService
import org.koin.compose.koinInject

/** Google Play edition: nothing to download here, Google Play keeps Handoff up to date. */
@Composable
fun UpdateSettings(updates: UpdateService = koinInject()) {
    ListItem(
        headlineContent = { Text("Updates") },
        supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · through Google Play") },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { (updates as? PlayStoreUpdates)?.openStoreListing() },
    )
}
