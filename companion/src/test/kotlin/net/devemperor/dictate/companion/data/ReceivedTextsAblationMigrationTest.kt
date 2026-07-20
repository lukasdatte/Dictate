package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.DictateCompanionDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The `received_texts` ablation is **lossless** (acceptance criterion 3, desktop-host.md §3.4).
 *
 * A populated v2 fixture is migrated forward through 2.sqm and the result is checked row-by-row:
 * every received text becomes a `PHONE_SYNC` session plus a 1:1 `dispatch_state`, the origin maps
 * per ADR-0016 (`UNKNOWN` → `KEYBOARD`, the rest namesakes), `inserted_at` mirrors the dispatch
 * flag, the sync cursor pages identically to before, and `received_texts` is gone.
 *
 * The fixture is a faithful v2 database (real tables, `PRAGMA user_version = 2`), so `SchemaMigrator`
 * takes the migrate path and replays exactly 2.sqm — the same code an upgrading user runs. Per the
 * §3.4 WARNING the fixture carries ≥1 device and ≥2 received_texts, one `UNKNOWN`-origin and one
 * `dispatched = 0`, and a same-millisecond pair so the cursor tie-break is exercised.
 */
class ReceivedTextsAblationMigrationTest {

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Before
    fun seedV2AndMigrate() {
        driver.exec("PRAGMA foreign_keys = ON")

        // ── a faithful v2 database ───────────────────────────────────────────────────────────
        driver.exec("CREATE TABLE devices (device_id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, secret_hash TEXT NOT NULL, paired_at INTEGER NOT NULL, last_seen_at INTEGER)")
        driver.exec(V2_RECEIVED_TEXTS)
        driver.exec("CREATE INDEX received_texts_cursor ON received_texts(created_at, session_id)")
        driver.exec("CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        driver.exec("PRAGMA user_version = 2")

        driver.exec("INSERT INTO devices(device_id, name, secret_hash, paired_at) VALUES ('dev-1', 'Pixel 8', 'hash', 1)")
        // s-a and s-b share created_at = 100 → the cursor tie-break must pick the greater id (s-b).
        insertReceivedText("s-a", createdAt = 100, receivedAt = 50, origin = "KEYBOARD", dispatched = 1, lastOutcome = "TYPED_CTRL_V")
        insertReceivedText("s-b", createdAt = 100, receivedAt = 60, origin = "UNKNOWN", dispatched = 0, lastOutcome = null)
        insertReceivedText("s-c", createdAt = 99, receivedAt = 70, origin = "REVIEW_REFINEMENT", dispatched = 1, lastOutcome = "CLIPBOARD_ONLY")

        SchemaMigrator.migrate(driver, DictateCompanionDb.Schema)
    }

    @Test
    fun everyRowIsCarriedOver_intoSessionsAndDispatchState() {
        assertEquals("every received_text becomes a session", 3, count("SELECT COUNT(*) FROM sessions"))
        assertEquals("and a 1:1 dispatch_state", 3, count("SELECT COUNT(*) FROM dispatch_state"))
        assertEquals("all are the phone mirror", 3, count("SELECT COUNT(*) FROM sessions WHERE host_origin = 'PHONE_SYNC'"))
    }

    @Test
    fun theArchiveFieldsAreMappedFaithfully() {
        // The received text is the final output; type/status are the fixed phone-dictation shape.
        assertEquals("hello s-a", str("SELECT final_output_text FROM sessions WHERE id = 's-a'"))
        assertEquals("RECORDING", str("SELECT type FROM sessions WHERE id = 's-a'"))
        assertEquals("COMPLETED", str("SELECT status FROM sessions WHERE id = 's-a'"))
        assertEquals("[]", str("SELECT audio_file_paths FROM sessions WHERE id = 's-a'"))

        // UNKNOWN folds onto KEYBOARD (ADR-0016 landing default); the others are namesakes.
        assertEquals("KEYBOARD", str("SELECT origin FROM sessions WHERE id = 's-b'"))
        assertEquals("REVIEW_REFINEMENT", str("SELECT origin FROM sessions WHERE id = 's-c'"))

        // inserted_at mirrors the dispatch flag: set to received_at when dispatched, else NULL.
        assertEquals(50L, longOrNull("SELECT inserted_at FROM sessions WHERE id = 's-a'"))
        assertNull(longOrNull("SELECT inserted_at FROM sessions WHERE id = 's-b'"))
        assertEquals(70L, longOrNull("SELECT inserted_at FROM sessions WHERE id = 's-c'"))
    }

    @Test
    fun theSyncFieldsLandInDispatchState() {
        assertEquals("dev-1", str("SELECT device_id FROM dispatch_state WHERE session_id = 's-a'"))
        assertEquals(50L, longOrNull("SELECT received_at FROM dispatch_state WHERE session_id = 's-a'"))
        assertEquals(1L, longOrNull("SELECT dispatched FROM dispatch_state WHERE session_id = 's-a'"))
        assertEquals("TYPED_CTRL_V", str("SELECT last_outcome FROM dispatch_state WHERE session_id = 's-a'"))

        assertEquals(0L, longOrNull("SELECT dispatched FROM dispatch_state WHERE session_id = 's-b'"))
        assertNull(str("SELECT last_outcome FROM dispatch_state WHERE session_id = 's-b'"))
    }

    @Test
    fun theSyncCursorPagesIdenticallyToBefore() {
        // The old cursor was `received_texts ORDER BY created_at DESC, session_id DESC LIMIT 1`. The
        // new one is the same order over PHONE_SYNC sessions. Same-ms rows → the id breaks the tie.
        assertEquals(100L, longOrNull("SELECT created_at FROM sessions WHERE host_origin = 'PHONE_SYNC' ORDER BY created_at DESC, id DESC LIMIT 1"))
        assertEquals("s-b", str("SELECT id FROM sessions WHERE host_origin = 'PHONE_SYNC' ORDER BY created_at DESC, id DESC LIMIT 1"))
    }

    @Test
    fun theOldTableIsGone() {
        assertFalse(
            "received_texts must not exist after the ablation",
            count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'received_texts'") > 0,
        )
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────

    private fun insertReceivedText(id: String, createdAt: Long, receivedAt: Long, origin: String, dispatched: Int, lastOutcome: String?) {
        val outcome = lastOutcome?.let { "'$it'" } ?: "NULL"
        driver.exec(
            "INSERT INTO received_texts(session_id, device_id, text, created_at, received_at, origin, dispatched, last_outcome) " +
                "VALUES ('$id', 'dev-1', 'hello $id', $createdAt, $receivedAt, '$origin', $dispatched, $outcome)",
        )
    }

    private fun SqlDriver.exec(sql: String) = execute(identifier = null, sql = sql, parameters = 0)

    private fun count(sql: String): Int = driver.executeQuery(
        identifier = null, sql = sql, parameters = 0,
        mapper = { cursor -> cursor.next(); QueryResult.Value((cursor.getLong(0) ?: 0L).toInt()) },
    ).value

    private fun str(sql: String): String? = driver.executeQuery(
        identifier = null, sql = sql, parameters = 0,
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) },
    ).value

    private fun longOrNull(sql: String): Long? = driver.executeQuery(
        identifier = null, sql = sql, parameters = 0,
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)) },
    ).value

    private companion object {
        val V2_RECEIVED_TEXTS = """
            CREATE TABLE received_texts (
                session_id   TEXT NOT NULL PRIMARY KEY,
                device_id    TEXT NOT NULL,
                text         TEXT NOT NULL,
                created_at   INTEGER NOT NULL,
                received_at  INTEGER NOT NULL,
                origin       TEXT NOT NULL
                             CHECK (origin IN ('KEYBOARD','HISTORY_REPROCESS','POST_PROCESSING','REVIEW_REFINEMENT','UNKNOWN')),
                dispatched   INTEGER NOT NULL DEFAULT 0,
                last_outcome TEXT
                             CHECK (last_outcome IS NULL OR last_outcome IN ('TYPED_CTRL_V','CLIPBOARD_ONLY','FAILED')),
                FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE
            )
        """.trimIndent()
    }
}
