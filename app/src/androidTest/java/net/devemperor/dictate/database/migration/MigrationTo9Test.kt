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
 * Instrumented tests for [MIGRATION_8_9] (Paket 3 / ADR-0014).
 *
 * **Local-only — no CI run today** (same status as `MigrationTo8Test`).
 * Execute via `./gradlew connectedDebugAndroidTest` on a device/emulator.
 *
 * Coverage:
 *  1. sessions recreate preserves legacy rows.
 *  2. origin CHECK accepts REVIEW_REFINEMENT (the new value) and rejects unknown.
 *  3. type CHECK accepts the three values (retrofit) and rejects unknown.
 *  4. all five sessions indices survive the recreate.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo9Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun seedSession(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String = "s1",
        type: String = "RECORDING",
        origin: String = "KEYBOARD",
    ) {
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin, audio_file_paths, audio_duration_seconds)
            VALUES ('$id', '$type', 1000, 'COMPLETED', '$origin', '', 5)
            """.trimIndent()
        )
    }

    // 1
    @Test
    fun migrate8To9_preservesLegacySession() {
        helper.createDatabase(TEST_DB, 8).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        db.query("SELECT type, origin FROM sessions WHERE id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("RECORDING", c.getString(0))
            assertEquals("KEYBOARD", c.getString(1))
        }
    }

    // 2
    @Test
    fun migrate8To9_originCheck_acceptsReviewRefinement() {
        helper.createDatabase(TEST_DB, 8).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        // The whole point of v9: the new origin value is now insertable.
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin, audio_duration_seconds)
            VALUES ('s2', 'RECORDING', 1100, 'COMPLETED', 'REVIEW_REFINEMENT', 3)
            """.trimIndent()
        )
    }

    // 2b
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate8To9_originCheck_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 8).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin, audio_duration_seconds)
            VALUES ('bad', 'RECORDING', 1200, 'COMPLETED', 'BOGUS_ORIGIN', 1)
            """.trimIndent()
        )
    }

    // 3
    @Test
    fun migrate8To9_typeCheck_acceptsKnownTypes() {
        helper.createDatabase(TEST_DB, 8).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        listOf("RECORDING", "REWORDING", "POST_PROCESSING").forEachIndexed { i, t ->
            db.execSQL(
                """
                INSERT INTO sessions (id, type, created_at, status, origin, audio_duration_seconds)
                VALUES ('t$i', '$t', ${1300 + i}, 'COMPLETED', 'KEYBOARD', 0)
                """.trimIndent()
            )
        }
    }

    // 3b
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate8To9_typeCheck_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 8).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        db.execSQL(
            """
            INSERT INTO sessions (id, type, created_at, status, origin, audio_duration_seconds)
            VALUES ('badtype', 'BOGUS_TYPE', 1400, 'COMPLETED', 'KEYBOARD', 0)
            """.trimIndent()
        )
    }

    // 4
    @Test
    fun migrate8To9_preservesSessionIndices() {
        helper.createDatabase(TEST_DB, 8).use { db -> seedSession(db) }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list('sessions')").use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) indices.add(c.getString(nameCol))
        }
        assertTrue(indices.contains("index_sessions_parent_session_id"))
        assertTrue(indices.contains("index_sessions_type"))
        assertTrue(indices.contains("index_sessions_created_at"))
        assertTrue(indices.contains("index_sessions_origin"))
        assertTrue(indices.contains("index_sessions_status"))
    }

    companion object {
        private const val TEST_DB = "migration-test-9"
    }
}
