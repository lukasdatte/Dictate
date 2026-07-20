package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.DictateCompanionDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the migration runner **actually migrates** — with an artificial v1 → v2 schema.
 *
 * At schema v1 there is nothing to migrate, and that is precisely the trap: a runner that is never
 * exercised is ceremony, and the first real migration would land on untested code, on a user's only
 * copy of their data. So the runner takes its [SqlSchema] as a parameter, and this test hands it a
 * fabricated one whose `migrate` adds a column. If the runner ever stops calling `migrate`, or stops
 * writing `PRAGMA user_version`, this fails — years before the first `.sqm` file exists.
 */
class SchemaMigratorTest {

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Test
    fun freshFile_isCreatedAndStamped() {
        SchemaMigrator.migrate(driver, schemaV1)

        assertEquals(1L, userVersion())
        assertTrue(tableExists("notes"))
    }

    @Test
    fun anOlderFile_isMigratedAndRestamped() {
        SchemaMigrator.migrate(driver, schemaV1)
        assertEquals(1L, userVersion())

        SchemaMigrator.migrate(driver, schemaV2)

        assertEquals("the runner must advance PRAGMA user_version, or it would migrate again", 2L, userVersion())
        assertTrue("the v1 → v2 migration must actually have run", columnExists("notes", "title"))
    }

    @Test
    fun anUpToDateFile_isLeftAlone() {
        SchemaMigrator.migrate(driver, schemaV1)
        // A second run must not re-create the schema (that would throw "table already exists") and
        // must not migrate anything.
        SchemaMigrator.migrate(driver, schemaV1)

        assertEquals(1L, userVersion())
    }

    @Test
    fun aFileFromTheFuture_isRefused() {
        SchemaMigrator.migrate(driver, schemaV2)

        // Downgrading means opening a file whose columns we do not know. Refusing loudly beats
        // corrupting the user's only copy of their history.
        val failure = runCatching { SchemaMigrator.migrate(driver, schemaV1) }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalStateException)
        assertTrue("${failure?.message}", failure!!.message!!.contains("newer version"))
    }

    @Test
    fun theRealSchema_createsTheRealTables() {
        SchemaMigrator.migrate(driver, DictateCompanionDb.Schema)

        assertEquals(DictateCompanionDb.Schema.version, userVersion())
        assertTrue(tableExists("devices"))
        assertTrue(tableExists("settings"))
        // The Room-parity session model (D1a). `received_texts` was ablated by 2.sqm — a fresh
        // install creates the session tables and never the old one.
        assertTrue(tableExists("sessions"))
        assertTrue(tableExists("dispatch_state"))
        assertFalse("received_texts must be gone after the ablation (2.sqm)", tableExists("received_texts"))
    }

    // ── The artificial schema pair ──────────────────────────────────────────────────────

    private val schemaV1 = fakeSchema(
        version = 1L,
        create = { it.exec("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)") },
        migrate = { _, _, _ -> error("v1 has nothing to migrate from") },
    )

    private val schemaV2 = fakeSchema(
        version = 2L,
        create = { it.exec("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL, title TEXT)") },
        migrate = { driver, from, to ->
            assertEquals(1L, from)
            assertEquals(2L, to)
            driver.exec("ALTER TABLE notes ADD COLUMN title TEXT")
        },
    )

    private fun fakeSchema(
        version: Long,
        create: (SqlDriver) -> Unit,
        migrate: (SqlDriver, Long, Long) -> Unit,
    ): SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {

        override val version: Long = version

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            create(driver)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            migrate(driver, oldVersion, newVersion)
            return QueryResult.Unit
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    private fun SqlDriver.exec(sql: String) = execute(identifier = null, sql = sql, parameters = 0)

    private fun userVersion(): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
    ).value

    private fun tableExists(name: String): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
        },
    ).value

    private fun columnExists(table: String, column: String): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = '$column'",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
        },
    ).value
}
