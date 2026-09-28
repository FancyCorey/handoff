package dev.handoff.core.handoff

/** Turns transport-level rejections into explanations a person can act on. */
object HandoffDiagnosis {
    fun rejection(reason: String): String = when {
        reason.startsWith("STALE") ->
            "its clock and this device's differ by more than 5 minutes. Turn on automatic date & time on both"
        reason.startsWith("UNSUPPORTED_VERSION") || reason.startsWith("UNSUPPORTED_TYPE") ->
            "it runs a different Handoff version. Update Handoff on both devices"
        reason.contains("not trusted") || reason.contains("peer is not trusted") ->
            "it no longer has this device linked. Link the two devices again"
        reason.contains("key mismatch") || reason.contains("server identity mismatch") ->
            "Handoff there was reinstalled or reset. Unlink it here and link again"
        reason.startsWith("SENDER_MISMATCH") || reason.startsWith("handshake") || reason.startsWith("protocol") ->
            "the secure connection failed. Try again, or unlink and link again"
        else -> reason
    }
}
