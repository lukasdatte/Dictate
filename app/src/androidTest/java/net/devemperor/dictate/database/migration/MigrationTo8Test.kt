package net.devemperor.dictate.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.devemperor.dictate.database.DictateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MIGRATION_7_8] (ADR-0012).
 *
 * **Local-only — no CI run today** (same status as `MigrationTo4Test`).
 * Execute via `./gradlew connectedDebugAndroidTest` on a device/emulator.
 *
 * Coverage:
 *  1. processing_steps recreate preserves legacy rows and backfills the two new
 *     columns to NULL.
 *  2. step_type CHECK accepts all four values (incl. CONVERSATION_TURN) and
 *     rejects unknown ones (Double-Enum retrofit).
 *  3. response_format CHECK accepts the three kinds + NULL, rejects unknown.
 *  4. conversation_messages exists; role CHECK accepts SYSTEM/USER/ASSISTANT and
 *     rejects unknown.
 *  5. conversation_messages FK cascades on session DELETE; processing_steps
 *     indices survive the recreate.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo8Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun seedSessionWithStep(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sessionId: String = "s1",
        stepId: String = "st1",
        stepType: String = "QUEUED_PROMPT"
    ) {
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin, audio_file_paths, audio_duration_seconds)
            VALUES ('$sessionId', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', '', 5)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO processing_steps (
                id, session_id, step_type, chain_index, version, is_current,
                input_text, output_text, model_used, provider, prompt_used,
                prompt_entity_id, previous_step_id, previous_transcription_id,
                source_session_id, prompt_tokens, completion_tokens, duration_ms,
                status, error_message, created_at
            ) VALUES (
                '$stepId', '$sessionId', '$stepType', 0, 1, 1,
                'in', 'out', 'gpt', 'openai', 'do x',
                NULL, NULL, NULL, NULL, 1, 2, 100, 'SUCCESS', NULL, 1100
            )
            """.trimIndent()
        )
    }

    // 1
    @Test
    fun migrate7To8_preservesLegacyStep_backfillsNewColumnsToNull() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.query(
            "SELECT step_type, output_text, assistant_message, response_format FROM processing_steps WHERE id = 'st1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("QUEUED_PROMPT", c.getString(0))
            assertEquals("out", c.getString(1))
            assertTrue("assistant_message must backfill NULL", c.isNull(2))
            assertTrue("response_format must backfill NULL", c.isNull(3))
        }
    }

    // 2
    @Test
    fun migrate7To8_stepTypeCheck_acceptsConversationTurn() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.execSQL(
            """
            INSERT INTO processing_steps (
                id, session_id, step_type, chain_index, version, is_current,
                input_text, output_text, model_used, provider, prompt_used,
                prompt_entity_id, previous_step_id, previous_transcription_id,
                source_session_id, prompt_tokens, completion_tokens, duration_ms,
                status, error_message, created_at, assistant_message, response_format
            ) VALUES (
                'ct1', 's1', 'CONVERSATION_TURN', 1, 1, 1,
                'in', 'out', 'gpt', 'openai', NULL,
                NULL, NULL, NULL, NULL, 1, 2, 100, 'SUCCESS', NULL, 1200,
                'explained', 'JSON_SCHEMA'
            )
            """.trimIndent()
        )
    }

    // 2b
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate7To8_stepTypeCheck_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.execSQL(
            """
            INSERT INTO processing_steps (
                id, session_id, step_type, chain_index, version, is_current,
                input_text, model_used, provider, prompt_tokens, completion_tokens,
                duration_ms, status, created_at
            ) VALUES ('bad', 's1', 'BOGUS_TYPE', 2, 1, 1, 'in', 'gpt', 'openai', 0, 0, 1, 'SUCCESS', 1300)
            """.trimIndent()
        )
    }

    // 3
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate7To8_responseFormatCheck_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.execSQL(
            """
            INSERT INTO processing_steps (
                id, session_id, step_type, chain_index, version, is_current,
                input_text, model_used, provider, prompt_tokens, completion_tokens,
                duration_ms, status, created_at, response_format
            ) VALUES ('rf', 's1', 'CONVERSATION_TURN', 3, 1, 1, 'in', 'gpt', 'openai', 0, 0, 1, 'SUCCESS', 1400, 'BOGUS')
            """.trimIndent()
        )
    }

    // 4
    @Test
    fun migrate7To8_conversationMessages_roleCheck() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        listOf("SYSTEM", "USER", "ASSISTANT").forEachIndexed { i, role ->
            db.execSQL(
                """
                INSERT INTO conversation_messages (id, session_id, turn_index, seq, role, content, step_id, created_at)
                VALUES ('m$i', 's1', $i, $i, '$role', 'c', NULL, 2000)
                """.trimIndent()
            )
        }
        db.query("SELECT COUNT(*) FROM conversation_messages WHERE session_id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
    }

    // 4b
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate7To8_conversationMessages_rejectsUnknownRole() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.execSQL(
            """
            INSERT INTO conversation_messages (id, session_id, turn_index, seq, role, content, step_id, created_at)
            VALUES ('bad', 's1', 0, 0, 'ROBOT', 'c', NULL, 2000)
            """.trimIndent()
        )
    }

    // 5
    @Test
    fun migrate7To8_conversationMessages_cascadeOnSessionDelete() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.execSQL(
            """
            INSERT INTO conversation_messages (id, session_id, turn_index, seq, role, content, step_id, created_at)
            VALUES ('m1', 's1', 0, 0, 'USER', 'hi', NULL, 2000)
            """.trimIndent()
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM sessions WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM conversation_messages WHERE session_id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("conversation_messages must cascade on session delete", 0, c.getInt(0))
        }
    }

    // 6
    @Test
    fun migrate7To8_preservesProcessingStepIndices() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedSessionWithStep(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list('processing_steps')").use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) indices.add(c.getString(nameCol))
        }
        assertTrue(indices.contains("index_processing_steps_session_id"))
        assertTrue(indices.contains("index_processing_steps_session_id_chain_index_version"))
        assertTrue(indices.contains("index_processing_steps_previous_step_id"))
        assertTrue(indices.contains("index_processing_steps_previous_transcription_id"))
        assertTrue(indices.contains("index_processing_steps_source_session_id"))
    }

    companion object {
        private const val TEST_DB = "migration-test-8"
    }
}
