package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M5 → M6 extends the `sessions.status` CHECK constraint to accept
 * the new `RECORDING_INTERRUPTED` value (B2 of
 * `dictate-widget-state-and-recovery`, ADR-0008 §"Auto-Continuation").
 *
 * **Why a table-recreate?** SQLite has no `ALTER TABLE … DROP
 * CHECK` — the CHECK constraint is bound to the table definition.
 * To widen the allowed set we create a `sessions_new` clone with
 * the extended CHECK, copy every row, drop the old table, and
 * rename. Indexes are re-created against the new table afterwards.
 *
 * **Backward compatibility:** every previously-allowed status value
 * is still in the new CHECK set; existing rows pass the recreate
 * unchanged. No backfill is needed — `RECORDING_INTERRUPTED` is
 * introduced as a *forward* path the next [net.devemperor.dictate.state.PipelineRecovery]
 * pass will populate.
 *
 * **Schema parity:** the column list, defaults, and FK rules are
 * copied verbatim from MIGRATION_3_4 / MIGRATION_4_5 — the only
 * delta is the additional CHECK token.
 *
 * @see net.devemperor.dictate.database.entity.SessionStatus.RECORDING_INTERRUPTED
 * @see net.devemperor.dictate.state.PipelineRecovery
 * @see docs/decisions/0008-ui-surface-axes-widget-state-and-ime-view.md
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_file_paths TEXT NOT NULL DEFAULT '',
                audio_duration_seconds INTEGER NOT NULL,
                parent_session_id TEXT,
                status TEXT NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN (
                        'RECORDING', 'RECORDING_INTERRUPTED', 'RECORDED',
                        'TRANSCRIBING', 'COMPLETED', 'FAILED', 'CANCELLED'
                    )),
                origin TEXT NOT NULL DEFAULT 'KEYBOARD'
                    CHECK (origin IN ('KEYBOARD', 'HISTORY_REPROCESS', 'POST_PROCESSING')),
                queued_prompt_ids TEXT,
                last_error_type TEXT
                    CHECK (last_error_type IS NULL OR last_error_type IN (
                        'INVALID_API_KEY', 'RATE_LIMITED', 'MODEL_NOT_FOUND',
                        'BAD_REQUEST', 'SERVER_ERROR', 'NETWORK_ERROR',
                        'UNKNOWN'
                    )),
                last_error_message TEXT,
                final_output_text TEXT,
                input_text TEXT,
                inserted_at INTEGER,
                FOREIGN KEY (parent_session_id) REFERENCES sessions(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sessions_new
            SELECT id, type, created_at, target_app_package, language,
                   audio_file_path, audio_file_paths, audio_duration_seconds,
                   parent_session_id, status, origin, queued_prompt_ids,
                   last_error_type, last_error_message, final_output_text,
                   input_text, inserted_at
            FROM sessions
            """.trimIndent()
        )
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")
        // Indexes re-created against the new table — full parity with
        // MIGRATION_3_4 (the canonical CREATE INDEX block). All five
        // are required by the Entity definition; omitting any of them
        // makes Room's open-time validateMigration() throw
        // "Migration didn't properly handle: sessions".
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sessions_parent_session_id ON sessions(parent_session_id)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_type ON sessions(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_origin ON sessions(origin)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_status ON sessions(status)")
    }
}
