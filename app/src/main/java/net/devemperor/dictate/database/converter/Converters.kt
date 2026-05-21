package net.devemperor.dictate.database.converter

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    fun toBoolean(value: Int): Boolean = value != 0

    /**
     * `List<String>` ↔ pipe-delimited string (ADR-0007 §"Schema Change").
     *
     * Pipe is chosen over JSON to keep the converter pure-JVM testable
     * (no `org.json` dependency, no kotlinx.serialization round-trip).
     * Audio file paths from `CacheDirAudioFileRepository` follow
     * `{sessionId}_seg{N}.m4a` under `cacheDir/audio/` and never contain
     * `|`, so the delimiter is unambiguous.
     *
     * Empty list round-trips through the empty string — Room's default
     * value `''` on `audio_file_paths` therefore deserialises to
     * `emptyList()`, matching the entity-side Kotlin default.
     *
     * @see net.devemperor.dictate.database.entity.SessionEntity.audioFilePaths
     */
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(DELIMITER)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(DELIMITER)

    internal companion object {
        internal const val DELIMITER = "|"
    }
}
