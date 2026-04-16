package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration for the reprocess refactor. Adds the following columns to `sessions`:
 * - status (TEXT NOT NULL, CHECK constraint)
 * - origin (TEXT NOT NULL, CHECK constraint)
 * - queued_prompt_ids (TEXT)
 * - last_error_type (TEXT, CHECK constraint)
 * - last_error_message (TEXT)
 *
 * Since SQLite cannot add CHECK constraints via ALTER TABLE, we recreate the table.
 *
 * Orphan handling: sessions with type=RECORDING and no transcription but an
 * audio_file_path get status=RECORDED (they need healing, see DurationHealingJob).
 * All other legacy sessions get status=COMPLETED as a pragmatic default.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the new table with CHECK constraints
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_duration_seconds INTEGER NOT NULL DEFAULT 0,
                parent_session_id TEXT,
                status TEXT NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('RECORDED', 'COMPLETED', 'FAILED', 'CANCELLED')),
                origin TEXT NOT NULL DEFAULT 'KEYBOARD'
                    CHECK (origin IN ('KEYBOARD', 'HISTORY_REPROCESS', 'POST_PROCESSING')),
                queued_prompt_ids TEXT,
                -- Values MUST match net.devemperor.dictate.ai.AIProviderException.ErrorType
                -- (reused as the Double-Enum for this column — see docs/DATABASE-PATTERNS.md).
                -- Note: ErrorType.CANCELLED is intentionally EXCLUDED here — cancellation is
                -- expressed via sessions.status = CANCELLED with last_error_type = NULL.
                last_error_type TEXT
                    CHECK (last_error_type IS NULL OR last_error_type IN (
                        'INVALID_API_KEY', 'RATE_LIMITED', 'MODEL_NOT_FOUND',
                        'BAD_REQUEST', 'SERVER_ERROR', 'NETWORK_ERROR',
                        'UNKNOWN'
                    )),
                last_error_message TEXT,
                final_output_text TEXT,
                input_text TEXT,
                FOREIGN KEY (parent_session_id) REFERENCES sessions_new (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // 2. Copy existing rows, inferring status from current state
        //    - If RECORDING type, has audio_file_path, but no transcription -> RECORDED (needs healing)
        //    - Otherwise -> COMPLETED
        db.execSQL(
            """
            INSERT INTO sessions_new (
                id, type, created_at, target_app_package, language,
                audio_file_path, audio_duration_seconds, parent_session_id,
                status, origin, queued_prompt_ids,
                last_error_type, last_error_message,
                final_output_text, input_text
            )
            SELECT
                s.id, s.type, s.created_at, s.target_app_package, s.language,
                s.audio_file_path, s.audio_duration_seconds, s.parent_session_id,
                CASE
                    WHEN s.type = 'RECORDING'
                        AND s.audio_file_path IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM transcriptions t WHERE t.session_id = s.id)
                    THEN 'RECORDED'
                    ELSE 'COMPLETED'
                END AS status,
                'KEYBOARD' AS origin,
                NULL AS queued_prompt_ids,
                NULL AS last_error_type,
                NULL AS last_error_message,
                s.final_output_text, s.input_text
            FROM sessions s
            """.trimIndent()
        )

        // 3. Drop old table, rename new
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")

        // 4. Recreate indices (IF NOT EXISTS for consistency with Migrations.kt)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_parent_session_id ON sessions (parent_session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_type ON sessions (type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions (created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_origin ON sessions (origin)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_status ON sessions (status)")
    }
}
