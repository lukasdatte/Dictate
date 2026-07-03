package net.devemperor.dictate.history

/**
 * Pure, JVM-testable humanization of a persisted session `last_error_message`
 * for the F-053 error surface (spec §3.3).
 *
 * The pipeline persists a bare `partial:N` marker into `last_error_message`
 * when only N of the recording's segments could be recovered (ADR-0007). The
 * raw marker is developer-facing; the history detail screen renders it as a
 * human-readable partial-recovery note instead. Extracting the parse as a pure
 * function keeps the mapping unit-testable without an Activity (spec D9).
 */
object SessionErrorFormatter {

    private val PARTIAL_MARKER = Regex("""^partial:(\d+)$""")

    /**
     * If [message] is a bare partial-recovery marker (`"partial:N"`), returns N
     * (the recovered-segment count); otherwise null. Leading/trailing
     * whitespace is tolerated; any surrounding non-marker text is NOT — a
     * mixed message is a real error and rendered verbatim.
     */
    fun partialSegmentCount(message: String?): Int? {
        val trimmed = message?.trim() ?: return null
        return PARTIAL_MARKER.matchEntire(trimmed)?.groupValues?.get(1)?.toIntOrNull()
    }
}
