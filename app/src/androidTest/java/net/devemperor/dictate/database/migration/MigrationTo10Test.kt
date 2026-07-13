package net.devemperor.dictate.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * Instrumented tests for [MIGRATION_9_10] (ADR-0019).
 *
 * **Local-only — no CI run today** (same status as `MigrationTo9Test`).
 * Execute via `./gradlew connectedDebugAndroidTest` on a device/emulator.
 *
 * Coverage:
 *  1. text_insertions recreate preserves legacy rows; target_device_id backfills to NULL.
 *  2. insertion_method CHECK accepts the new WINDOWS_DISPATCH value.
 *  3. insertion_method CHECK rejects an unknown value.
 *  4. all four text_insertions indices survive the recreate.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo10Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun seedSession(db: SupportSQLiteDatabase, id: String = "s1") {
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin, audio_duration_seconds)
            VALUES ('$id', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', 5)
            """.trimIndent()
        )
    }

    private fun seedInsertion(db: SupportSQLiteDatabase, id: Long, sessionId: String, method: String) {
        db.execSQL(
            """
            INSERT INTO text_insertions (id, session_id, timestamp, inserted_text, insertion_method)
            VALUES ($id, '$sessionId', 2000, 'hello', '$method')
            """.trimIndent()
        )
    }

    // 1
    @Test
    fun migrate9To10_preservesLegacyRows_andBackfillsTargetDeviceIdNull() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            seedSession(db)
            seedInsertion(db, id = 1, sessionId = "s1", method = "COMMIT")
            seedInsertion(db, id = 2, sessionId = "s1", method = "PASTE")
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        db.query(
            "SELECT insertion_method, target_device_id FROM text_insertions ORDER BY id"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("COMMIT", c.getString(0))
            assertNull(c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals("PASTE", c.getString(0))
            assertNull(c.getString(1))
        }
    }

    // 2
    @Test
    fun migrate9To10_insertionMethodCheck_acceptsWindowsDispatch() {
        helper.createDatabase(TEST_DB, 9).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        // The whole point of v10: the new value is now insertable, with a target device id.
        db.execSQL(
            """
            INSERT INTO text_insertions (session_id, timestamp, inserted_text, insertion_method, target_device_id)
            VALUES ('s1', 3000, 'to pc', 'WINDOWS_DISPATCH', 'device-1')
            """.trimIndent()
        )
        db.query(
            "SELECT target_device_id FROM text_insertions WHERE insertion_method = 'WINDOWS_DISPATCH'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("device-1", c.getString(0))
        }
    }

    // 3
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate9To10_insertionMethodCheck_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 9).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        db.execSQL(
            """
            INSERT INTO text_insertions (session_id, timestamp, inserted_text, insertion_method)
            VALUES ('s1', 3000, 'bad', 'NOT_A_METHOD')
            """.trimIndent()
        )
    }

    // 4
    @Test
    fun migrate9To10_preservesInsertionIndices() {
        helper.createDatabase(TEST_DB, 9).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list('text_insertions')").use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) indices.add(c.getString(nameCol))
        }
        assertTrue(indices.contains("index_text_insertions_session_id"))
        assertTrue(indices.contains("index_text_insertions_timestamp"))
        assertTrue(indices.contains("index_text_insertions_source_step_id"))
        assertTrue(indices.contains("index_text_insertions_source_transcription_id"))
    }

    companion object {
        private const val TEST_DB = "migration-test-10"
    }
}
