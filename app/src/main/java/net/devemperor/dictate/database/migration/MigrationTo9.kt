package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M8 → M9 recreates `sessions` to (Paket 3 / ADR-0014):
 *
 * 1. **Extend the `origin` CHECK** to accept the new `REVIEW_REFINEMENT` value
 *    (ADR-0013's dictated-review-refinement carrier recording, now tagged so the
 *    in-keyboard history panel can hide it).
 * 2. **Retrofit the `sessions.type` Double-Enum CHECK** — the last documented
 *    Double-Enum debt on this table (docs/DATABASE-PATTERNS.md listed
 *    `sessions.type` as "retrofit when next touched"). Since the table is
 *    already being recreated for (1), discharging (2) in the same step is free.
 *
 * **Why a table-recreate?** SQLite has no `ALTER TABLE … DROP/ADD CHECK`; the
 * only way to change a CHECK is to clone the table with the new definition, copy
 * every row, drop, and rename (same pattern as MIGRATION_5_6).
 *
 * **Retrofit safety (git-verified, same bar as the step_type retrofit in
 * MIGRATION_7_8):** `SessionType` was introduced in a single commit (`6608bfa`)
 * with exactly `{RECORDING, REWORDING, POST_PROCESSING}` and never changed; the
 * sole writer of `sessions.type` is `SessionManager.persistNewSession`, which
 * persists `SessionType.name`. So no existing row can violate the new `type`
 * CHECK on the `INSERT … SELECT`. The `origin` widening only *adds* a value, so
 * every existing origin still passes.
 *
 * Room's `validateMigration` compares column/index/FK metadata only and ignores
 * CHECK constraints, so both CHECKs are invisible to schema validation — exactly
 * how the existing `status`/`origin` CHECKs already coexist with Room.
 *
 * **Bestandsdaten:** every existing session is copied verbatim; no backfill.
 *
 * @see net.devemperor.dictate.database.entity.SessionOrigin.REVIEW_REFINEMENT
 * @see net.devemperor.dictate.database.entity.SessionType
 * @see docs/decisions/0014-in-keyboard-history-panel.md
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL
                    CHECK (type IN ('RECORDING', 'REWORDING', 'POST_PROCESSING')),
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
                    CHECK (origin IN (
                        'KEYBOARD', 'HISTORY_REPROCESS', 'POST_PROCESSING',
                        'REVIEW_REFINEMENT'
                    )),
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
        // All five indices are required by the Entity definition; omitting any
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
