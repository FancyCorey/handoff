package dev.handoff.app.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.handoff.app.ui.OutlineButton
import dev.handoff.app.ui.PrimaryButton
import dev.handoff.app.ui.SectionCard
import dev.handoff.app.ui.SectionLabel
import dev.handoff.app.ui.tokens
import dev.handoff.core.help.Help
import dev.handoff.core.help.HelpAction
import java.util.Date

/**
 * The plain-words part of Help and diagnostics (docs/DESIGN.md): what happened, what to do, what
 * Handoff checked, and a report to share.
 */
fun LazyListScope.helpItems(
    help: Help,
    onAction: (HelpAction) -> Unit,
    onTryAgain: () -> Unit,
    onShare: () -> Unit,
    showFine: Boolean,
    onShowFine: () -> Unit,
) {
    // What happened.
    item {
        SectionCard(Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val h = help.headline
                Text(
                    (if (!h.failed && help.problems.isEmpty()) "✓ " else "") + h.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        h.failed -> MaterialTheme.colorScheme.error
                        help.problems.isEmpty() -> tokens.online
                        else -> tokens.warning
                    },
                    modifier = Modifier.semantics { heading() },
                )
                h.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                h.atMs?.let { Text(whenText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    help.tip?.let { tip ->
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
                    .background(tokens.accentSoft).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tip.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.onAccentSoft)
                    Text(tip.detail, style = MaterialTheme.typography.bodySmall, color = tokens.onAccentSoft)
                }
                TextButton(onClick = { onAction(tip.action) }, modifier = Modifier.height(48.dp)) { Text(tip.action.label, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    // What to do: numbered only when there is more than one step, with Try again under them.
    if (help.steps.isNotEmpty() || help.tryAgain) {
        item { SectionLabel("What to do", Modifier.semantics { heading() }) }
        item {
            SectionCard {
                help.steps.forEachIndexed { i, step ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    val numbered = help.steps.size > 1
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {
                            if (numbered) contentDescription = "Step ${i + 1} of ${help.steps.size}: ${step.title}. ${step.detail.orEmpty()}"
                        },
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (numbered) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(tokens.accentSoft), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tokens.onAccentSoft)
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(step.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        step.action?.let { a ->
                            TextButton(onClick = { onAction(a) }, modifier = Modifier.height(40.dp)) { Text(a.label, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                if (help.tryAgain) {
                    PrimaryButton("Try again", onTryAgain, Modifier.padding(16.dp))
                }
            }
        }
    }

    // What Handoff checked: problems first; the fine ones fold away while there are problems.
    item { SectionLabel("What Handoff checked", Modifier.semantics { heading() }) }
    item {
        SectionCard {
            help.problems.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                CheckRow(c.title, c.detail, ok = false)
            }
            val folded = help.problems.isNotEmpty() && !showFine
            if (help.problems.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (folded) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Show ${help.fine.size} checks that are fine") { onShowFine() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckIcon(ok = true)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (help.fine.size == 1) "1 other check is fine" else "${help.fine.size} other checks are fine",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.online, modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                help.fine.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    CheckRow(c.title, c.detail, ok = true)
                }
            }
        }
    }

    // A report to share, and what it contains.
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlineButton("Share a report", onShare)
            Text(
                "Includes your device model, device and headphone names, and what Handoff tried. Use it when you report a problem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CheckIcon(ok: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (ok) tokens.online else MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (ok) Icons.Filled.Check else Icons.Filled.PriorityHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CheckRow(title: String, detail: String?, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {
            contentDescription = (if (ok) "OK: " else "Problem: ") + title + (detail?.let { ". $it" } ?: "")
        },
        verticalAlignment = Alignment.Top,
    ) {
        CheckIcon(ok)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (ok) FontWeight.Normal else FontWeight.Medium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** "Today 7:24", "Yesterday 21:10", or the date. */
fun whenText(ms: Long): String {
    val day = 24 * 3_600_000L
    val zone = java.util.TimeZone.getDefault()
    val days = (System.currentTimeMillis() + zone.getOffset(System.currentTimeMillis())) / day - (ms + zone.getOffset(ms)) / day
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(ms))
    return when (days) {
        0L -> "Today $time"
        1L -> "Yesterday $time"
        else -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(ms))
    }
}
