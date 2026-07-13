package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import net.devemperor.dictate.companion.db.DictateCompanionDb

/**
 * The versioned migration runner — **live from schema v1**, not retrofitted at v2.
 *
 * This is the point of the whole file. A companion that ships without a runner has, at its first
 * real migration, no idea what the user's database file looks like: created by which version, with
 * which columns, migrated by whom? The answer has to be *recorded from the very first release*, and
 * `PRAGMA user_version` is where SQLite records it.
 *
 * `PRAGMA user_version` is the on-disk version:
 * - **0** — a fresh (or empty) file → create the current schema and stamp it.
 * - **< current** — an older file → run the `.sqm` migrations and stamp the new version.
 * - **> current** — a *newer* file. Refuse. Downgrading by silently running a migration backwards is
 *   not a thing SQLDelight can do, and quietly opening it would corrupt data written by a version
 *   whose columns we do not know.
 *
 * `.sqm` files live in `src/main/sqldelight/.../migrations/` and are named after the version they
 * migrate **from** (`1.sqm` = v1 → v2). `verifyMigrations = true` in the Gradle block plays them
 * against the checked-in schema snapshot on every build, so a forgotten migration cannot merge —
 * the SQLDelight equivalent of Room's `exportSchema` + `MigrationTestHelper`.
 */
object SchemaMigrator {

    /** The schema is a parameter so `SchemaMigratorTest` can prove the runner on an artificial v1 → v2. */
    fun migrate(
        driver: SqlDriver,
        schema: SqlSchema<QueryResult.Value<Unit>> = DictateCompanionDb.Schema,
    ) {
        val current = schema.version
        val onDisk = readUserVersion(driver)

        when {
            onDisk == 0L -> {
                schema.create(driver)
                writeUserVersion(driver, current)
            }

            onDisk < current -> {
                schema.migrate(driver, onDisk, current)
                writeUserVersion(driver, current)
            }

            onDisk > current -> error(
                "The companion database is from a newer version ($onDisk > $current). " +
                    "Downgrading is not supported — install the newer companion.",
            )
            // onDisk == current: nothing to do. Deliberately not stamping it again, so that a
            // read-only or locked file opens instead of failing on a pointless write.
        }
    }

    private fun readUserVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
    ).value

    /**
     * Interpolated, not bound: SQLite's PRAGMA grammar takes no bind parameters. The value is a
     * `Long` straight out of the generated schema, so there is nothing here for an injection to hook
     * into.
     */
    private fun writeUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
    }
}
