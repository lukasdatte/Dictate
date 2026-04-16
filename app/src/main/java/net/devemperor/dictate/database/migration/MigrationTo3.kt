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
        // 0. Drop partial unique indices created by the old onOpen callback
        //    ("idx_transcriptions_current", "idx_processing_steps_current").
        //    They were a DB-level safety net for is_current uniqueness, but
        //    Room's migration validator rejects them because no Entity declares
        //    them. Atomicity is already guaranteed by @Transaction-annotated
        //    DAO methods, so the partial indices are redundant.
        db.execSQL("DROP INDEX IF EXISTS idx_transcriptions_current")
        db.execSQL("DROP INDEX IF EXISTS idx_processing_steps_current")

        // 1. Create the new table with CHECK constraints.
        //
        // Room-validator quirks (discovered at runtime on device):
        //   - `audio_duration_seconds` must NOT declare a SQL DEFAULT. The entity
        //     has a Kotlin default (`= 0`) but no `@ColumnInfo(defaultValue = …)`,
        //     so Room's expected schema is `defaultValue='undefined'`. A SQL
        //     `DEFAULT 0` makes the found schema mismatch the expected one.
        //   - The FOREIGN KEY reference must target the FINAL table name
        //     (`sessions`), not the transient `sessions_new`. SQLite keeps the
        //     textual reference as-is through `ALTER TABLE ... RENAME TO`, so
        //     using `sessions_new` here would leave the FK text stuck at the
        //     transient name and trip Room's schema check.
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_duration_seconds INTEGER NOT NULL,
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
                FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE CASCADE
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

        // 5. Rebuild `transcriptions` to drop SQL-level DEFAULTs on
        //    prompt_tokens / completion_tokens. The entity uses Kotlin
        //    defaults (no @ColumnInfo(defaultValue)), so Room expects
        //    defaultValue='undefined'. The DEFAULT 0 inherited from
        //    MIGRATION_1_2 trips the schema validator.
        db.execSQL(
            """
            CREATE TABLE transcriptions_new (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                is_current INTEGER NOT NULL,
                text TEXT NOT NULL,
                model_used TEXT NOT NULL,
                provider TEXT NOT NULL,
                prompt_tokens INTEGER NOT NULL,
                completion_tokens INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO transcriptions_new
            SELECT id, session_id, version, is_current, text, model_used,
                   provider, prompt_tokens, completion_tokens, duration_ms,
                   created_at
            FROM transcriptions
            """.trimIndent()
        )
        db.execSQL("DROP TABLE transcriptions")
        db.execSQL("ALTER TABLE transcriptions_new RENAME TO transcriptions")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_session_id ON transcriptions (session_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transcriptions_session_id_version ON transcriptions (session_id, version)")

        // 6. Rebuild `processing_steps` for the same reason: prompt_tokens
        //    and completion_tokens had SQL DEFAULT 0 in MIGRATION_1_2.
        db.execSQL(
            """
            CREATE TABLE processing_steps_new (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                step_type TEXT NOT NULL,
                chain_index INTEGER NOT NULL,
                version INTEGER NOT NULL,
                is_current INTEGER NOT NULL,
                input_text TEXT NOT NULL,
                output_text TEXT,
                model_used TEXT NOT NULL,
                provider TEXT NOT NULL,
                prompt_used TEXT,
                prompt_entity_id INTEGER,
                previous_step_id TEXT,
                previous_transcription_id TEXT,
                source_session_id TEXT,
                prompt_tokens INTEGER NOT NULL,
                completion_tokens INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO processing_steps_new
            SELECT id, session_id, step_type, chain_index, version, is_current,
                   input_text, output_text, model_used, provider, prompt_used,
                   prompt_entity_id, previous_step_id, previous_transcription_id,
                   source_session_id, prompt_tokens, completion_tokens,
                   duration_ms, status, error_message, created_at
            FROM processing_steps
            """.trimIndent()
        )
        db.execSQL("DROP TABLE processing_steps")
        db.execSQL("ALTER TABLE processing_steps_new RENAME TO processing_steps")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_session_id ON processing_steps (session_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_processing_steps_session_id_chain_index_version ON processing_steps (session_id, chain_index, version)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_previous_step_id ON processing_steps (previous_step_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_previous_transcription_id ON processing_steps (previous_transcription_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_source_session_id ON processing_steps (source_session_id)")
    }
}
