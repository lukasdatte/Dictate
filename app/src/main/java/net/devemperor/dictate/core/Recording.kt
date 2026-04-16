package net.devemperor.dictate.core

import java.io.File

/**
 * Value object representing a single audio recording on disk.
 * Holds the file reference and exposes self-queries about it.
 *
 * This is NOT a manager — it knows only about itself and does not
 * orchestrate multiple files or talk to the database.
 *
 * Duration extraction used to live here but was moved to
 * [RecordingRepository.extractDurationSeconds] (Open Decision SA-1,
 * resolved in Chunk 1): it pulled the Android framework dependency
 * ([android.media.MediaMetadataRetriever]) into this value object, which
 * broke layer purity and JVM testability, AND duplicated the logic used by
 * [net.devemperor.dictate.database.DurationHealingJob].
 */
data class Recording(
    val audioFile: File,
    val sessionId: String
) {
    fun exists(): Boolean = audioFile.exists()

    fun sizeBytes(): Long = if (audioFile.exists()) audioFile.length() else 0L

    fun delete(): Boolean = audioFile.exists() && audioFile.delete()
}
