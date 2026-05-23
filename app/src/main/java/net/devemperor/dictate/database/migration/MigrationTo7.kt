package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M6 → M7 backfills the `sessions.audio_file_paths` column for any row
 * whose recording was started before [recording-stack-completion Block A1]
 * began persisting segment paths into the column. Closes the last
 * "audio_file_paths is empty but the legacy audio_file_path points at a
 * file on disk" gap so post-A3 readers can rely on `audioFilePaths`
 * exclusively and drop the `effectiveAudioFilePaths` bridge.
 *
 * **Why no table-recreate?** No CHECK constraint changes, no column
 * rename, no schema delta — just a one-shot UPDATE. Room's
 * `validateMigration()` only checks the schema fingerprint, not the row
 * content, so an in-place UPDATE passes without recreating the table.
 *
 * **Backfill rule (single-element list):** the legacy column holds a
 * single path string (e.g. `/data/.../files/recordings/uuid.m4a`). The
 * new column uses pipe-delimited encoding
 * ([net.devemperor.dictate.database.converter.Converters.fromStringList]);
 * a single-element list has no delimiter, so copying the string
 * verbatim is already the correct encoded form. `MIGRATION_4_5` used
 * the same shortcut.
 *
 * **Idempotency:** the `WHERE audio_file_paths = ''` guard keeps the
 * UPDATE no-op for rows that have already been written by Block A1's
 * `SessionDao.updateAudioFilePaths` since the post-A1 boot. Re-running
 * the migration (test scenarios, debug-build reinstall) is safe.
 *
 * **Why the legacy column stays:** dropping `audio_file_path` is a
 * separate concern (out of scope for this plan — would touch every
 * still-existing reader plus an ALTER TABLE with a follow-up
 * table-recreate cycle). The column is read by a small set of legacy
 * queries (`findOrphanedTerminalAudio`, `findAllAudioFilePaths`,
 * `markLegacyAudioSessionsFailed`); they continue to work and produce
 * the same results as before. A dedicated cleanup plan can drop the
 * column once those queries migrate (the M-series doesn't go beyond M7
 * for now).
 *
 * @see net.devemperor.dictate.database.entity.SessionEntity.audioFilePaths
 * @see net.devemperor.dictate.database.converter.Converters
 * @see docs/plans/2026-05-22 - dictate-recording-stack-completion/dictate-recording-stack-completion.md §3 A3
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE sessions
            SET audio_file_paths = audio_file_path
            WHERE audio_file_paths = '' AND audio_file_path IS NOT NULL
            """.trimIndent()
        )
    }
}
