package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M7 → M8 lays the schema foundation for post-processing conversations
 * (ADR-0012).
 *
 * **Two parts:**
 *
 * 1. **Recreate `processing_steps`** to add `assistant_message` +
 *    `response_format` AND to retrofit the long-owed `step_type` Double-Enum
 *    CHECK (docs/DATABASE-PATTERNS.md listed `step_type` as "retrofit when next
 *    touched"). SQLite cannot add/alter a CHECK in place, so the table is
 *    recreated. The retrofit is provably safe: git history shows `StepType`
 *    only ever had `{AUTO_FORMAT, REWORDING, QUEUED_PROMPT}`, and the sole
 *    writers (`SessionManager.appendProcessingStep` / `regenerateProcessingStep`)
 *    persist `StepType.name`, so no existing row can violate the new CHECK on
 *    the `INSERT … SELECT`.
 *
 * 2. **Create `conversation_messages`** — the persisted conversation log
 *    (`role` Double-Enum CHECK). Created AFTER the `processing_steps` recreate
 *    because its `step_id` FK references that table.
 *
 * Room's `validateMigration` compares column/index/FK metadata only and ignores
 * CHECK constraints, so the added CHECKs are invisible to schema validation —
 * exactly how the existing `status`/`SessionStatus` CHECK coexists with Room.
 *
 * **Bestandsdaten:** every existing step keeps its values; the two new columns
 * backfill to NULL. No `conversation_messages` rows are created for legacy
 * sessions.
 *
 * @see net.devemperor.dictate.database.entity.ConversationMessageEntity
 * @see net.devemperor.dictate.database.entity.MessageRole
 * @see net.devemperor.dictate.database.entity.ResponseFormatKind
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── 1. Recreate processing_steps with the two new columns + CHECKs ──
        db.execSQL(
            """
            CREATE TABLE processing_steps_new (
                id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                step_type TEXT NOT NULL
                    CHECK (step_type IN ('AUTO_FORMAT','REWORDING','QUEUED_PROMPT','CONVERSATION_TURN')),
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
                assistant_message TEXT,
                response_format TEXT
                    CHECK (response_format IS NULL OR response_format IN ('JSON_SCHEMA','TOOL_USE','TEXT_FALLBACK')),
                PRIMARY KEY(id),
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO processing_steps_new (
                id, session_id, step_type, chain_index, version, is_current,
                input_text, output_text, model_used, provider, prompt_used,
                prompt_entity_id, previous_step_id, previous_transcription_id,
                source_session_id, prompt_tokens, completion_tokens, duration_ms,
                status, error_message, created_at
            )
            SELECT
                id, session_id, step_type, chain_index, version, is_current,
                input_text, output_text, model_used, provider, prompt_used,
                prompt_entity_id, previous_step_id, previous_transcription_id,
                source_session_id, prompt_tokens, completion_tokens, duration_ms,
                status, error_message, created_at
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

        // ── 2. Create conversation_messages (FK references the fresh table) ──
        db.execSQL(
            """
            CREATE TABLE conversation_messages (
                id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                turn_index INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL CHECK (role IN ('SYSTEM','USER','ASSISTANT')),
                content TEXT NOT NULL,
                step_id TEXT,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(step_id) REFERENCES processing_steps(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_messages_session_id ON conversation_messages (session_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversation_messages_session_id_seq ON conversation_messages (session_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_messages_step_id ON conversation_messages (step_id)")
    }
}
