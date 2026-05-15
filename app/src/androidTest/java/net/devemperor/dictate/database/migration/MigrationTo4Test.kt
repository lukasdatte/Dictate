package net.devemperor.dictate.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.devemperor.dictate.database.DictateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MIGRATION_3_4] (Spec 1 §11.4.2).
 *
 * **Local-only — no CI run today** (Spec 1 §11.7.0a "CI-Integration").
 * Developers execute via `./gradlew connectedDebugAndroidTest` on a
 * connected device or emulator before opening the PR. A future CI
 * emulator plan will pick these tests up automatically.
 *
 * Coverage (8 cases, mapping to Spec 1 §11.4.2 + KG-SST-2/3/4/5 +
 * B3-VAL-W1 F-1 + F-2):
 *
 *  1. [migrate3To4_backfillsInsertedAt_asNull_forAllPreExistingRows]
 *     — the new column appears and **all** pre-existing rows
 *     (including COMPLETED) get `inserted_at = NULL` so the cleanup
 *     policy doesn't wipe months of user history on first
 *     post-upgrade idle-stop (Spec 1 §6.5, F-2).
 *  2. [migrate3To4_checkConstraint_acceptsNewStatusValues] — the new
 *     `RECORDING` / `TRANSCRIBING` enum values are insertable after
 *     migration (CHECK extends, doesn't replace).
 *  3. [migrate3To4_checkConstraint_rejectsUnknownStatus] — unknown
 *     status strings are still rejected (Double-Enum invariant
 *     intact post-migration).
 *  4. [migrate3To4_preservesAllLegacyStatuses] — round-trip: each of
 *     the four legacy status values comes through unchanged.
 *  5. [migrate3To4_preservesChildRows_processingStepsAndTranscriptions]
 *     — FK-cascade safety: child rows survive the
 *     DROP+RENAME (SQLite does not fire ON DELETE CASCADE on schema
 *     ops; see KDoc on [MIGRATION_3_4]).
 *  6. [migrate3To4_preservesIndices] — all five indices the migration
 *     recreates in step 4 are present afterwards.
 *  7. [migrate3To4_setsForeignKeyToSetNull] — row-level DELETE on a
 *     parent preserves POST_PROCESSING children with NULL
 *     `parent_session_id` (Spec 1 §6.5, F-1). The FK changed from
 *     `ON DELETE CASCADE` to `ON DELETE SET NULL`.
 *
 *  Plus the bonus [migrate1To4_chain_preservesData] case for the
 *  multi-step v1→v4 chain (KG-SST-3, exercises a backup-restore
 *  scenario where the user's DB was on v1).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo4Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    // 1 — B3-VAL-W1 F-2: all pre-existing rows get inserted_at = NULL.
    @Test
    fun migrate3To4_backfillsInsertedAt_asNull_forAllPreExistingRows() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds, final_output_text)
                VALUES ('s1', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', 10, 'Hello')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds, final_output_text)
                VALUES ('s2', 'RECORDING', 2000, 'RECORDED', 'KEYBOARD', 5, NULL)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.query("SELECT id, inserted_at FROM sessions ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("s1", c.getString(0))
            // Spec 1 §6.5 + F-2: pre-existing rows are immune to
            // deleteInsertedOlderThan — backfill is NULL, not
            // created_at. NULL means "the cleanup-marker doesn't
            // apply to this row".
            assertTrue(
                "COMPLETED pre-existing row must have inserted_at = NULL after M4 (F-2)",
                c.isNull(1)
            )
            assertTrue(c.moveToNext())
            assertEquals("s2", c.getString(0))
            // RECORDED row was already NULL pre-fix; stays NULL.
            assertTrue("inserted_at must be NULL for RECORDED rows", c.isNull(1))
        }
    }

    // 2
    @Test
    fun migrate3To4_checkConstraint_acceptsNewStatusValues() {
        // M3 schema does not yet accept RECORDING/TRANSCRIBING as
        // status (the CHECK list only covers the four legacy values).
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // After M4, both new variants must be insertable.
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin,
                audio_duration_seconds)
            VALUES ('r1', 'RECORDING', 3000, 'RECORDING', 'KEYBOARD', 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin,
                audio_duration_seconds)
            VALUES ('t1', 'RECORDING', 4000, 'TRANSCRIBING', 'KEYBOARD', 5)
            """.trimIndent()
        )
    }

    // 3
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate3To4_checkConstraint_rejectsUnknownStatus() {
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin,
                audio_duration_seconds)
            VALUES ('x1', 'RECORDING', 5000, 'BOGUS_STATUS', 'KEYBOARD', 0)
            """.trimIndent()
        )
    }

    // 4
    @Test
    fun migrate3To4_preservesAllLegacyStatuses() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            listOf("RECORDED", "COMPLETED", "FAILED", "CANCELLED").forEachIndexed { i, s ->
                db.execSQL(
                    """
                    INSERT INTO sessions (id, type, created_at, status, origin,
                        audio_duration_seconds)
                    VALUES ('s$i', 'RECORDING', ${1000 + i}, '$s', 'KEYBOARD', 0)
                    """.trimIndent()
                )
            }
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        val seen = mutableMapOf<String, String>()
        db.query("SELECT id, status FROM sessions ORDER BY id").use { c ->
            while (c.moveToNext()) seen[c.getString(0)] = c.getString(1)
        }
        assertEquals("RECORDED", seen["s0"])
        assertEquals("COMPLETED", seen["s1"])
        assertEquals("FAILED", seen["s2"])
        assertEquals("CANCELLED", seen["s3"])
    }

    // 5
    @Test
    fun migrate3To4_preservesChildRows_processingStepsAndTranscriptions() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds)
                VALUES ('parent', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', 5)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO transcriptions (id, session_id, version, is_current,
                    text, model_used, provider, prompt_tokens, completion_tokens,
                    duration_ms, created_at)
                VALUES ('t1', 'parent', 1, 1, 'hello', 'whisper-1', 'openai',
                    0, 0, 200, 1100)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO processing_steps (id, session_id, step_type,
                    chain_index, version, is_current, input_text, model_used,
                    provider, prompt_tokens, completion_tokens, duration_ms,
                    status, created_at)
                VALUES ('ps1', 'parent', 'PROMPT', 0, 1, 1, 'hello', 'gpt-4',
                    'openai', 0, 0, 300, 'SUCCESS', 1200)
                """.trimIndent()
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT COUNT(*) FROM transcriptions WHERE session_id = 'parent'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM processing_steps WHERE session_id = 'parent'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // FK-integrity check: inserting a new child row after migration
        // must still resolve the parent (i.e. the FK reference points
        // at the renamed `sessions` table, not at the dropped
        // `sessions_new` placeholder).
        db.execSQL(
            """
            INSERT INTO transcriptions (id, session_id, version, is_current,
                text, model_used, provider, prompt_tokens, completion_tokens,
                duration_ms, created_at)
            VALUES ('t2', 'parent', 2, 1, 'world', 'whisper-1', 'openai',
                0, 0, 200, 1300)
            """.trimIndent()
        )
    }

    // 6
    @Test
    fun migrate3To4_preservesIndices() {
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list('sessions')").use { c ->
            // PRAGMA index_list columns: seq, name, unique, origin, partial.
            while (c.moveToNext()) indices.add(c.getString(1))
        }
        // Expected explicit indices (the PRIMARY KEY produces an
        // implicit `sqlite_autoindex_sessions_1` that we ignore).
        listOf(
            "index_sessions_parent_session_id",
            "index_sessions_type",
            "index_sessions_created_at",
            "index_sessions_origin",
            "index_sessions_status"
        ).forEach { name ->
            assertTrue("Index $name missing after M4", indices.contains(name))
        }
    }

    // 7 — B3-VAL-W1 F-1: FK changed from CASCADE to SET NULL.
    @Test
    fun migrate3To4_setsForeignKeyToSetNull() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            // Parent + POST_PROCESSING child pre-migration.
            db.execSQL(
                """
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds)
                VALUES ('parent', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', 5)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds, parent_session_id)
                VALUES ('child', 'RECORDING', 2000, 'COMPLETED', 'POST_PROCESSING', 5, 'parent')
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // Room disables FK enforcement during migration; tests must
        // explicitly turn it back on to exercise the FK semantics
        // (otherwise row-level DELETE skips the FK action entirely).
        db.execSQL("PRAGMA foreign_keys = ON")

        // Row-level DELETE on the parent — the cleanup policy's hot
        // path. Under the pre-F-1 (CASCADE) semantics this would have
        // wiped the child silently.
        db.execSQL("DELETE FROM sessions WHERE id = 'parent'")

        // Child must SURVIVE with parent_session_id = NULL.
        db.query(
            "SELECT id, parent_session_id FROM sessions WHERE id = 'child'"
        ).use { c ->
            assertTrue("child row was deleted — FK still cascades?", c.moveToFirst())
            assertEquals("child", c.getString(0))
            assertTrue(
                "parent_session_id must be NULL after parent DELETE (F-1 SET NULL)",
                c.isNull(1)
            )
        }
    }

    // 8 (bonus — KG-SST-3, v1→v4 chain for backup-restore scenarios)
    @Test
    fun migrate1To4_chain_preservesData() {
        // Known v1 state: a RECORDING-type session with an audio file
        // but no transcription. MIGRATION_2_3 should infer status
        // 'RECORDED' for it (see MigrationTo3.kt:92-97), and
        // MIGRATION_3_4 should leave inserted_at NULL (not COMPLETED).
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, type, created_at, target_app_package, language,
                    audio_file_path, audio_duration_seconds, parent_session_id,
                    final_output_text, input_text
                )
                VALUES (
                    'sess-v1', 'RECORDING', 1000, 'com.example', 'en',
                    '/data/audio.m4a', 5, NULL,
                    NULL, NULL
                )
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 4, true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4
        )

        db.query(
            "SELECT id, type, status, origin, audio_file_path, inserted_at " +
                "FROM sessions WHERE id = 'sess-v1'"
        ).use { c ->
            assertEquals("v1 row vanished after v1→v4 chain", 1, c.count)
            c.moveToFirst()
            assertEquals("sess-v1", c.getString(0))
            assertEquals("RECORDING", c.getString(1))
            // MIGRATION_2_3 inferred status: RECORDING type + no
            // transcription + audio_file_path != null → RECORDED.
            assertEquals("RECORDED", c.getString(2))
            assertEquals("KEYBOARD", c.getString(3))
            assertEquals("/data/audio.m4a", c.getString(4))
            // MIGRATION_3_4 backfills inserted_at only for COMPLETED
            // rows — this row is RECORDED, so it must stay NULL.
            assertTrue(
                "inserted_at must stay NULL for RECORDED rows after v1→v4",
                c.isNull(5)
            )
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
