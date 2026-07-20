package net.devemperor.dictate.companion.fakes

import app.cash.sqldelight.db.SqlDriver
import org.junit.Assert.assertTrue

/**
 * Shared SQLDelight test plumbing for the `data`-package schema/parity tests.
 *
 * These three helpers were copied verbatim across the CHECK-parity and migration tests. Keeping one
 * copy means a single "CHECK constraint failed" matcher (and one no-parameter `exec` shim) — a
 * loosened assertion cannot silently drift between the parity suites.
 */

/** Fire a parameter-less statement — the SqlDelight driver call is noisy for one-off DDL/DML. */
fun SqlDriver.exec(sql: String) = execute(identifier = null, sql = sql, parameters = 0)

/** The `.name` set of an enum's entries — the unit of comparison for Double-Enum parity assertions. */
fun Iterable<Enum<*>>.names(): Set<String> = map { it.name }.toSet()

/**
 * Assert that [insert] is rejected by a SQLite CHECK constraint. The second half of every
 * Double-Enum test: a value the enum cannot produce must be refused by the column's CHECK.
 */
fun assertCheckFailure(insert: () -> Unit) {
    val failure = runCatching { insert() }.exceptionOrNull()
    assertTrue(
        "expected a CHECK constraint failure, got: $failure",
        failure?.message?.contains("CHECK constraint failed") == true,
    )
}
