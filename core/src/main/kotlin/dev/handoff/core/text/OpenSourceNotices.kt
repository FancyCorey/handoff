package dev.handoff.core.text

/**
 * The full open-source notices shipped inside both apps: every bundled component, its license,
 * the PodSwitch credit and the complete license texts. Source: `dev/handoff/core/NOTICES.txt`,
 * mirrored for readers of the repository in THIRD_PARTY_NOTICES.md.
 */
object OpenSourceNotices {
    /** The notices exactly as shipped, hard-wrapped at about 72 columns. */
    val text: String by lazy {
        OpenSourceNotices::class.java.getResourceAsStream("/dev/handoff/core/NOTICES.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("NOTICES.txt missing from the build")
    }

    /** The same text with hard-wrapped paragraphs joined, so it wraps to any screen width. */
    val readable: String by lazy { reflow(text) }

    private val LIST_ITEM = Regex("""^(- |\* |\(\w{1,3}\) |\d{1,2}\. |[A-Z][A-Z ,.'()-]+$)""")

    internal fun reflow(source: String): String {
        val out = StringBuilder()
        var previous = ""
        for (raw in source.lines()) {
            val line = raw.trim()
            val continues = line.isNotEmpty() && previous.isNotEmpty() &&
                !previous.startsWith("===") && !line.startsWith("===") &&
                !previous.endsWith(":") && !LIST_ITEM.containsMatchIn(line) &&
                !LIST_ITEM.matches(previous)
            when {
                continues -> out.append(' ').append(line)
                out.isEmpty() -> out.append(line)
                else -> out.append('\n').append(line)
            }
            previous = line
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n")
    }
}
