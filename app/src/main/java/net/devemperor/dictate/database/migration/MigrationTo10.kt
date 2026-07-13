package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M9 → M10 recreates `text_insertions` to (ADR-0019):
 *
 * 1. **Add the `insertion_method` CHECK constraint** — the LAST outstanding Double-Enum debt
 *    in docs/DATABASE-PATTERNS.md (`step_type` went in v8, `sessions.type` in v9). After this
 *    migration the "retrofit when next touched" table is empty.
 * 2. **Widen it by the new value `WINDOWS_DISPATCH`** so the audit trail can tell a text that
 *    went to the Android host field apart from one delivered to the paired PC.
 * 3. **Add the nullable `target_device_id` column** so a dispatch audit row records WHERE the
 *    text went, not just HOW (serviceability — plan §2.1a).
 *
 * **Why a table-recreate?** SQLite has no `ALTER TABLE … ADD/DROP CHECK`; (1) and (2) need a
 * rebuild anyway, so (3) rides along for free instead of costing a second migration (same
 * pattern as MIGRATION_8_9).
 *
 * **Retrofit safety (git-verified):** `insertion_method` has only ever been written from
 * `InsertionMethod.name` (the sole writer is `SessionManager.logTextInsertion`), so every
 * existing row already holds 'COMMIT' or 'PASTE'. The CHECK cannot reject legacy data.
 *
 * **Bestandsdaten:** copied 1:1. `target_device_id` backfills to NULL = "does not apply"
 * (DATABASE-PATTERNS Migration Rule 1 — no synthetic sentinel).
 *
 * Room's `validateMigration` compares column/index/FK metadata only and ignores CHECK
 * constraints, so the new CHECK is invisible to schema validation — exactly how the existing
 * `status`/`origin`/`type` CHECKs already coexist with Room.
 *
 * @see net.devemperor.dictate.database.entity.InsertionMethod
 * @see docs/DATABASE-PATTERNS.md
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE text_insertions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                session_id TEXT,
                timestamp INTEGER NOT NULL,
                inserted_text TEXT NOT NULL,
                replaced_text TEXT,
                target_app_package TEXT,
                cursor_position INTEGER,
                source_step_id TEXT,
                source_transcription_id TEXT,
                insertion_method TEXT NOT NULL
                    CHECK (insertion_method IN ('COMMIT', 'PASTE', 'WINDOWS_DISPATCH')),
                target_device_id TEXT,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO text_insertions_new (
                id, session_id, timestamp, inserted_text, replaced_text,
                target_app_package, cursor_position, source_step_id,
                source_transcription_id, insertion_method
            )
            SELECT id, session_id, timestamp, inserted_text, replaced_text,
                   target_app_package, cursor_position, source_step_id,
                   source_transcription_id, insertion_method
            FROM text_insertions
            """.trimIndent()
        )
        db.execSQL("DROP TABLE text_insertions")
        db.execSQL("ALTER TABLE text_insertions_new RENAME TO text_insertions")
        // The four indices are required by the Entity definition; omitting any makes Room's
        // open-time validateMigration() throw "Migration didn't properly handle: text_insertions".
        // No index on target_device_id — V1 knows exactly one target, filtering on it is never a
        // hot path.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_text_insertions_session_id ON text_insertions(session_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_text_insertions_timestamp ON text_insertions(timestamp)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_text_insertions_source_step_id ON text_insertions(source_step_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_text_insertions_source_transcription_id ON text_insertions(source_transcription_id)"
        )
    }
}
