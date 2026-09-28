package dev.handoff.app.feature.diagnostics

import dev.handoff.core.model.Redaction

/** Plain inputs for the export, so the redaction rules can be unit-tested without Android. */
data class ReportInput(
    val appVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val sections: List<Pair<String, List<Pair<String, String>>>>,
    val events: List<String>,
)

/**
 * Builds the shareable diagnostics text. Everything passes through [Redaction.scrub] so a
 * full Bluetooth address can never appear, and callers must only pass non-secret values
 * (never keys, tokens, invitation links or signatures).
 */
object DiagnosticsReport {
    fun build(input: ReportInput): String {
        val text = buildString {
            appendLine("Handoff diagnostics")
            appendLine("App version: ${input.appVersion}")
            appendLine("Android: ${input.androidRelease} (API ${input.sdkInt})")
            appendLine("Manufacturer: ${input.manufacturer}")
            appendLine("Model: ${input.model}")
            input.sections.forEach { (title, rows) ->
                appendLine()
                appendLine("== $title ==")
                rows.forEach { (k, v) -> appendLine("$k: $v") }
            }
            appendLine()
            appendLine("== Recent events ==")
            input.events.forEach { appendLine(it) }
        }
        return Redaction.scrub(text).replace(SECRET_PATTERN, "[redacted]")
    }

    /** Defence in depth: invitation links and base64 blobs long enough to be keys never leave. */
    private val SECRET_PATTERN = Regex("handoff://pair\\?d=[A-Za-z0-9_\\-]+|[A-Za-z0-9+/]{60,}={0,2}")
}
