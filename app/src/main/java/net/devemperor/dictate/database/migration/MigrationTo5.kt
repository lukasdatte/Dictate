package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M4 → M5 adds the `audio_file_paths` column to the `sessions` table
 * (ADR-0007 Multi-File Audio Repository).
 *
 * **Why a parallel column, not a rename?** This migration is
 * deliberately ADDITIVE. The legacy `audio_file_path` (singular) stays
 * in place during the migration window so existing readers keep
 * working without code changes. Callers migrate incrementally to
 * `audio_file_paths` via [net.devemperor.dictate.database.entity.SessionEntity.effectiveAudioFilePaths];
 * the legacy column is removed in a future `MIGRATION_5_6` once every
 * caller has been converted to populate the list.
 *
 * **Backfill rule:** Each existing row's `audio_file_paths` is
 * initialised to the legacy `audio_file_path` value (single-segment
 * "list" — no delimiter needed for one entry) when non-null, or empty
 * string (decoded as `emptyList()`) when the legacy column is NULL.
 * This preserves the invariant
 * `effectiveAudioFilePaths == listOfNotNull(audio_file_path)`
 * pre-/post-migration so the dual-column window is transparent to
 * readers.
 *
 * **Pipe delimiter** matches
 * [net.devemperor.dictate.database.converter.Converters.fromStringList] /
 * [net.devemperor.dictate.database.converter.Converters.toStringList].
 * Audio paths from `CacheDirAudioFileRepository` follow a strict
 * `{sessionId}_seg{N}.m4a` pattern under `cacheDir/audio/`, so the
 * delimiter is unambiguous.
 *
 * **No CHECK constraint, no FK changes** — pure `ALTER TABLE ADD
 * COLUMN`. SQLite has supported this since v3.2, well below the
 * project's min-SDK 26.
 *
 * **No index** on `audio_file_paths`. The column is consulted by
 * recovery + cleanup (rare, batched), never on the hot UI-list path
 * — same trade-off rationale as `inserted_at` (see `MIGRATION_3_4`
 * KDoc).
 *
 * @see net.devemperor.dictate.database.entity.SessionEntity.audioFilePaths
 * @see net.devemperor.dictate.database.converter.Converters
 * @see docs/decisions/0007-audio-multi-file-repository.md
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ADD COLUMN with NOT NULL DEFAULT '' — SQLite fills the default
        // into every existing row in one statement.
        db.execSQL(
            "ALTER TABLE sessions ADD COLUMN audio_file_paths TEXT NOT NULL DEFAULT ''"
        )
        // Backfill: copy each non-NULL legacy path verbatim. A single
        // entry needs no delimiter (the pipe convention only matters
        // for two+ segments, which a freshly migrated row never has).
        // NULL legacy rows keep the default `''` → empty list.
        db.execSQL(
            """
            UPDATE sessions
            SET audio_file_paths = audio_file_path
            WHERE audio_file_path IS NOT NULL
            """.trimIndent()
        )
    }
}
