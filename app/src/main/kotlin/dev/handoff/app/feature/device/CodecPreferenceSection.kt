package dev.handoff.app.feature.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionLabel
import dev.handoff.bluetooth.CodecPreferenceStatus

enum class CodecAccessStep { IDLE, EXPLAIN, REQUESTING, DECLINED, INELIGIBLE, AUTOMATIC }

@Composable
internal fun CodecPreferenceSection(
    preferSbc: Boolean,
    access: CodecAccessStep,
    status: CodecPreferenceStatus?,
    automatic: () -> Unit,
    sbc: () -> Unit,
    askForAccess: () -> Unit,
    cancelAccess: () -> Unit,
) {
    val explaining = access == CodecAccessStep.EXPLAIN
    val accessControls = remember { BringIntoViewRequester() }
    LaunchedEffect(explaining) {
        if (explaining) {
            withFrameNanos {}
            accessControls.bringIntoView()
        }
    }
    SectionLabel("Bluetooth audio")
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.selectableGroup()) {
                CodecChoice("Automatic", selected = !preferSbc, enabled = access != CodecAccessStep.REQUESTING, onClick = automatic)
                CodecChoice("Prefer SBC", selected = preferSbc, enabled = access != CodecAccessStep.REQUESTING, onClick = sbc)
            }
            val message = when {
                explaining ->
                    "Allow Handoff to check and choose Bluetooth audio for these headphones. Android will ask you to approve access."
                access == CodecAccessStep.REQUESTING -> "Waiting for Android…"
                access == CodecAccessStep.DECLINED -> "Android access wasn’t granted."
                access == CodecAccessStep.INELIGIBLE -> "SBC needs headphones paired here that support Classic Bluetooth audio."
                !preferSbc && access == CodecAccessStep.AUTOMATIC -> "Android chooses the next time they connect."
                !preferSbc -> "If audio cuts out, try Prefer SBC."
                else -> when (status) {
                    CodecPreferenceStatus.WAITING_FOR_PAUSE -> "Pause audio to let Handoff try SBC."
                    CodecPreferenceStatus.CHECKING -> "Checking which Bluetooth audio they’re using…"
                    CodecPreferenceStatus.SBC_CONFIRMED -> "SBC is in use."
                    CodecPreferenceStatus.ACCESS_NEEDED -> "Allow access again so Handoff can use your choice."
                    CodecPreferenceStatus.UNAVAILABLE ->
                        "Android didn’t switch to SBC. You can try Bluetooth audio codec in Developer options."
                    CodecPreferenceStatus.WAITING_FOR_CONNECTION, null -> "Handoff will try SBC when they connect here."
                }
            }
            val restoring = preferSbc && status == CodecPreferenceStatus.ACCESS_NEEDED &&
                access in listOf(CodecAccessStep.IDLE, CodecAccessStep.DECLINED)
            Column(Modifier.bringIntoViewRequester(accessControls)) {
                Text(
                    message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (explaining || restoring) {
                    OutlineButton("Allow access", askForAccess)
                }
                if (explaining) {
                    TextButton(onClick = cancelAccess) { Text(if (preferSbc) "Cancel" else "Keep Automatic") }
                }
            }
        }
    }
}

@Composable
private fun CodecChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
