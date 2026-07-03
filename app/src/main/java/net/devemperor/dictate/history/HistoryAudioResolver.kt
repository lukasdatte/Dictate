package net.devemperor.dictate.history

import java.io.File

/**
 * Single source of truth for "does this session have playable/re-runnable
 * audio, and which files" on the **history read side** (F-113).
 *
 * The history detail screen historically answered "has audio?" from the
 * legacy [net.devemperor.dictate.database.entity.SessionEntity.audioFilePath]
 * column alone. That column is frozen at segment 1 for never-uploaded
 * multi-segment RECORDED sessions, so play / re-run / delete all silently
 * ignored segments 2..N. This resolver aligns the read side with ADR-0007:
 * readers go through the multi-segment surface (`audio_file_paths`) first
 * and only fall back to the legacy column.
 *
 * Pure Kotlin with an injectable [fileExists] check so the resolution order
 * is unit-testable without touching the filesystem (spec D9 — new logic in
 * extracted, testable Kotlin).
 *
 * @see docs/decisions/0007-audio-multi-file-repository.md
 * @see docs/research/2026-07-02 - history-ui-overhaul.md §3.2
 */
class HistoryAudioResolver(
    private val fileExists: (String) -> Boolean = { File(it).exists() },
) {

    data class Resolution(val playablePaths: List<String>) {
        val available: Boolean get() = playablePaths.isNotEmpty()

        /** First playable path — the legacy single-file parameter for job dispatch. */
        val primaryPath: String? get() = playablePaths.firstOrNull()
    }

    /**
     * Resolution order (ADR-0007 read-side alignment):
     *  1. Multi-segment column entries that exist on disk win.
     *  2. Only when that set is empty does the legacy column apply
     *     (and only if it, too, exists on disk).
     *
     * Non-existent files are filtered out at every level, so
     * [Resolution.available] reflects real, playable audio.
     */
    fun resolve(audioFilePaths: List<String>, legacyAudioFilePath: String?): Resolution {
        val existingSegments = audioFilePaths.filter { it.isNotEmpty() && fileExists(it) }
        if (existingSegments.isNotEmpty()) {
            return Resolution(existingSegments)
        }

        val legacy = legacyAudioFilePath
            ?.takeIf { it.isNotEmpty() && fileExists(it) }
            ?.let { listOf(it) }
            .orEmpty()
        return Resolution(legacy)
    }
}
