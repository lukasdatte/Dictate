package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. sessions table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_duration_seconds INTEGER NOT NULL DEFAULT 0,
                parent_session_id TEXT,
                final_output_text TEXT,
                input_text TEXT,
                FOREIGN KEY (parent_session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_parent_session_id ON sessions(parent_session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_type ON sessions(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions(created_at)")

        // 2. transcriptions table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS transcriptions (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                is_current INTEGER NOT NULL,
                text TEXT NOT NULL,
                model_used TEXT NOT NULL,
                provider TEXT NOT NULL,
                prompt_tokens INTEGER NOT NULL DEFAULT 0,
                completion_tokens INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_session_id ON transcriptions(session_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transcriptions_session_id_version ON transcriptions(session_id, version)")

        // 3. processing_steps table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS processing_steps (
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
                prompt_tokens INTEGER NOT NULL DEFAULT 0,
                completion_tokens INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_session_id ON processing_steps(session_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_processing_steps_session_id_chain_index_version ON processing_steps(session_id, chain_index, version)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_previous_step_id ON processing_steps(previous_step_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_previous_transcription_id ON processing_steps(previous_transcription_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_steps_source_session_id ON processing_steps(source_session_id)")

        // 4. completion_log table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS completion_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                session_id TEXT,
                step_id TEXT,
                transcription_id TEXT,
                system_prompt TEXT,
                user_prompt TEXT,
                success INTEGER NOT NULL,
                error_message TEXT,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_completion_log_session_id ON completion_log(session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_completion_log_timestamp ON completion_log(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_completion_log_step_id ON completion_log(step_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_completion_log_transcription_id ON completion_log(transcription_id)")

        // 5. text_insertions table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS text_insertions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                session_id TEXT,
                timestamp INTEGER NOT NULL,
                inserted_text TEXT NOT NULL,
                replaced_text TEXT,
                target_app_package TEXT,
                cursor_position INTEGER,
                source_step_id TEXT,
                source_transcription_id TEXT,
                insertion_method TEXT NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_text_insertions_session_id ON text_insertions(session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_text_insertions_timestamp ON text_insertions(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_text_insertions_source_step_id ON text_insertions(source_step_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_text_insertions_source_transcription_id ON text_insertions(source_transcription_id)")
    }
}

