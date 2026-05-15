package net.devemperor.dictate.migration

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric tests for [LegacyAudioFileMigration] (Spec 1 §4.11.6.2 KG-AFF-2).
 *
 * **Coverage map (Plan-AC §10 Block 4):**
 *
 *  - Pref flag short-circuits a second run — idempotence primary gate.
 *  - Legacy file `cacheDir/audio.m4a` is deleted when present.
 *  - DB sessions with the legacy path in `RECORDING`/`RECORDED`/`TRANSCRIBING`
 *    are promoted to `FAILED` with the canonical reason string.
 *  - DB sessions already in `FAILED`/`CANCELLED`/`COMPLETED` keep their
 *    original `last_error_message` (Phase-B S-7 idempotence secondary gate).
 *  - Sessions referencing a different audio path are untouched.
 *  - Second run is a no-op (flag set ⇒ DB UPDATE never re-fires).
 *
 * Uses an in-memory Room database so the test is deterministic and the
 * legacy-path SQL UPDATE can be asserted against actual row state.
 *
 * @see net.devemperor.dictate.migration.LegacyAudioFileMigration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyAudioFileMigrationTest {

    private lateinit var context: Context
    private lateinit var db: DictateDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any pref-flag from previous tests (Robolectric isolates
        // SharedPreferences per test, but defensive reset costs nothing).
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(LegacyAudioFileMigration.FLAG_PREF)
            .apply()
        // The DictateDatabase singleton may carry rows across tests
        // (Robolectric reuses application state across the JVM). Clear
        // the sessions table at setup so each test starts with an empty
        // DB without resetting the singleton (no test-only reset method
        // exists, and the underlying SQLite file is short-lived enough
        // to make table-truncate the right granularity).
        db = DictateDatabase.getInstance(context)
        db.sessionDao().deleteAll()
    }

    @After
    fun tearDown() {
        db.sessionDao().deleteAll()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(LegacyAudioFileMigration.FLAG_PREF)
            .apply()
        // Delete any stray legacy file we might have created.
        File(context.cacheDir, LegacyAudioFileMigration.LEGACY_NAME).delete()
    }

    private fun legacyPath(): String =
        File(context.cacheDir, LegacyAudioFileMigration.LEGACY_NAME).absolutePath

    private fun insertSession(
        id: String,
        status: SessionStatus,
        audioPath: String?,
        existingErrorMessage: String? = null,
        createdAt: Long = 1_000L,
    ) {
        val entity = SessionEntity(
            id = id,
            createdAt = createdAt,
            type = "RECORDING",
            targetAppPackage = "com.example",
            status = status.name,
            language = "en",
            audioFilePath = audioPath,
            lastErrorType = if (status == SessionStatus.FAILED) "UNKNOWN" else null,
            lastErrorMessage = existingErrorMessage,
        )
        db.sessionDao().insert(entity)
    }

    // ─── Idempotence: pref flag short-circuits second run ──────────────

    @Test
    fun `pref flag short-circuits the migration`() {
        // Pre-set the flag — migration must NOT touch the legacy file.
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(LegacyAudioFileMigration.FLAG_PREF, true)
            .apply()

        val legacy = File(context.cacheDir, LegacyAudioFileMigration.LEGACY_NAME).apply {
            parentFile?.mkdirs()
            writeText("legacy-content")
        }

        LegacyAudioFileMigration.run(context)

        assertTrue("legacy file should survive when flag is set", legacy.exists())
        legacy.delete()
    }

    // ─── Legacy file deletion ──────────────────────────────────────────

    @Test
    fun `run deletes legacy audio file when present`() {
        val legacy = File(context.cacheDir, LegacyAudioFileMigration.LEGACY_NAME).apply {
            parentFile?.mkdirs()
            writeText("legacy-content")
        }
        assertTrue("pre: legacy file should exist", legacy.exists())

        LegacyAudioFileMigration.run(context)

        assertFalse("legacy file MUST be deleted by run()", legacy.exists())
    }

    @Test
    fun `run is safe when legacy file is absent`() {
        val legacy = File(context.cacheDir, LegacyAudioFileMigration.LEGACY_NAME)
        if (legacy.exists()) legacy.delete()

        // Must not throw, must still set the pref flag.
        LegacyAudioFileMigration.run(context)

        assertTrue(
            "pref flag must be set even when no legacy file was present",
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(LegacyAudioFileMigration.FLAG_PREF, false),
        )
    }

    // ─── DAO promotion of recoverable rows ─────────────────────────────

    @Test
    fun `run promotes recoverable legacy-path sessions to FAILED`() {
        val legacy = legacyPath()
        insertSession("recording-row", SessionStatus.RECORDING, legacy)
        insertSession("recorded-row", SessionStatus.RECORDED, legacy)
        insertSession("transcribing-row", SessionStatus.TRANSCRIBING, legacy)

        LegacyAudioFileMigration.run(context)

        val dao = db.sessionDao()
        listOf("recording-row", "recorded-row", "transcribing-row").forEach { id ->
            val row = dao.getById(id)
            assertNotNull("session $id should still exist", row)
            assertEquals(
                "session $id should be FAILED",
                SessionStatus.FAILED.name,
                row!!.status,
            )
            assertEquals(
                "session $id should carry the canonical reason",
                LegacyAudioFileMigration.REASON,
                row.lastErrorMessage,
            )
        }
    }

    // ─── DAO preserves historical error message on already-terminal rows ───

    @Test
    fun `run preserves last_error_message on already-FAILED rows`() {
        val legacy = legacyPath()
        insertSession(
            id = "old-failed",
            status = SessionStatus.FAILED,
            audioPath = legacy,
            existingErrorMessage = "openai_rate_limit",
        )
        insertSession(
            id = "old-cancelled",
            status = SessionStatus.CANCELLED,
            audioPath = legacy,
            existingErrorMessage = "user_cancelled_manually",
        )
        insertSession(
            id = "old-completed",
            status = SessionStatus.COMPLETED,
            audioPath = legacy,
            existingErrorMessage = null,
        )

        LegacyAudioFileMigration.run(context)

        val dao = db.sessionDao()
        val failed = dao.getById("old-failed")!!
        assertEquals("FAILED status untouched", SessionStatus.FAILED.name, failed.status)
        assertEquals(
            "historical error must survive Phase-B S-7 idempotence filter",
            "openai_rate_limit",
            failed.lastErrorMessage,
        )

        val cancelled = dao.getById("old-cancelled")!!
        assertEquals(SessionStatus.CANCELLED.name, cancelled.status)
        assertEquals("user_cancelled_manually", cancelled.lastErrorMessage)

        val completed = dao.getById("old-completed")!!
        assertEquals(SessionStatus.COMPLETED.name, completed.status)
    }

    // ─── Non-legacy paths are untouched ────────────────────────────────

    @Test
    fun `run leaves non-legacy-path sessions untouched`() {
        val otherPath = File(context.cacheDir, "audio/rec_42_dead.m4a").absolutePath
        insertSession("recording-other", SessionStatus.RECORDING, otherPath)

        LegacyAudioFileMigration.run(context)

        val row = db.sessionDao().getById("recording-other")!!
        assertEquals(
            "non-legacy-path row keeps its status",
            SessionStatus.RECORDING.name,
            row.status,
        )
    }

    // ─── Second-run idempotence ────────────────────────────────────────

    @Test
    fun `second run is a no-op (flag set)`() {
        val legacy = legacyPath()
        insertSession("recording-row", SessionStatus.RECORDING, legacy)

        // First run promotes + sets the flag.
        LegacyAudioFileMigration.run(context)

        // Manually downgrade the row again to verify the second run won't
        // re-promote (flag short-circuits before the DAO query fires).
        // Use a unique id so the foreign-key onDelete-cascade from the
        // previous-FAILED-row doesn't interfere.
        val dao = db.sessionDao()
        dao.deleteById("recording-row")
        dao.insert(
            SessionEntity(
                id = "recording-row",
                createdAt = 2_000L,
                type = "RECORDING",
                targetAppPackage = "com.example",
                status = SessionStatus.RECORDING.name,
                language = "en",
                audioFilePath = legacy,
                lastErrorType = null,
                lastErrorMessage = null,
            ),
        )

        LegacyAudioFileMigration.run(context)   // second run

        val row = dao.getById("recording-row")!!
        assertEquals(
            "second run must NOT promote the manually-downgraded row",
            SessionStatus.RECORDING.name,
            row.status,
        )
    }

    // ─── Flag flip-on-completion ───────────────────────────────────────

    @Test
    fun `run sets the pref flag after completing`() {
        // Pre-condition: flag absent.
        assertFalse(
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(LegacyAudioFileMigration.FLAG_PREF, false),
        )

        LegacyAudioFileMigration.run(context)

        assertTrue(
            "pref flag MUST be set after first successful run",
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(LegacyAudioFileMigration.FLAG_PREF, false),
        )
    }
}
