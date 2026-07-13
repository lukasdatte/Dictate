package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Double-Enum rule, pinned (docs/DATABASE-PATTERNS.md).
 *
 * A finite-set column is modelled **twice** — as a Kotlin enum and as a SQL `CHECK` — and the two
 * halves can silently drift: someone adds a variant to `SessionOriginWire`, the mapper writes it,
 * and every insert of that variant fails at runtime on the user's machine. Or the CHECK is widened
 * and a typo'd value slips into the column, to be found months later by a crashing reader.
 *
 * So both directions are asserted here:
 * - every enum value the code can produce **must** be insertable, and
 * - a value the enum cannot produce **must** be rejected.
 *
 * The second half is what makes the first half worth anything: without it, a CHECK dropped by
 * accident would still let this test pass.
 */
class OriginCheckConstraintParityTest {

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Before
    fun setUp() {
        SchemaMigrator.migrate(driver)
        driver.exec(
            "INSERT INTO devices(device_id, name, secret_hash, paired_at) " +
                "VALUES ('test-device-0001', 'Pixel 8', 'hash', 1)",
        )
    }

    @Test
    fun everyOriginTheCodeCanProduce_isAccepted() {
        SessionOriginWire.entries.forEach { origin ->
            insertRow(sessionId = "session-${origin.name}", origin = origin.name, outcome = null)
        }

        assertEquals(SessionOriginWire.entries.size, rowCount())
    }

    @Test
    fun everyOutcomeTheCodeCanProduce_isAccepted() {
        InsertionOutcome.entries.forEach { outcome ->
            insertRow(sessionId = "session-${outcome.name}", origin = "KEYBOARD", outcome = outcome.name)
        }
        insertRow(sessionId = "session-null-outcome", origin = "KEYBOARD", outcome = null)

        assertEquals(InsertionOutcome.entries.size + 1, rowCount())
    }

    @Test
    fun theWireOutcomeEnum_isASubsetOfTheDomainOne() {
        // FAILED exists in the domain enum and not on the wire (a failed insertion is a 503, not an
        // outcome). If the wire enum ever grew a value the domain does not know, the DB column could
        // not store it — this is the compile-time-adjacent check for that.
        InsertionOutcomeWire.entries.forEach { wire ->
            assertNotNull("no domain outcome for wire value ${wire.name}", InsertionOutcome.valueOf(wire.name))
        }
    }

    @Test
    fun anOriginTheEnumCannotProduce_isRejected() {
        val failure = runCatching { insertRow("session-x", origin = "TELEPATHY", outcome = null) }.exceptionOrNull()

        assertTrue("$failure", failure!!.message!!.contains("CHECK constraint failed"))
        assertEquals(0, rowCount())
    }

    @Test
    fun anOutcomeTheEnumCannotProduce_isRejected() {
        val failure = runCatching { insertRow("session-x", origin = "KEYBOARD", outcome = "SORT_OF") }.exceptionOrNull()

        assertTrue("$failure", failure!!.message!!.contains("CHECK constraint failed"))
        assertEquals(0, rowCount())
    }

    private fun insertRow(sessionId: String, origin: String, outcome: String?) {
        val outcomeLiteral = outcome?.let { "'$it'" } ?: "NULL"
        driver.exec(
            "INSERT INTO received_texts" +
                "(session_id, device_id, text, created_at, received_at, origin, dispatched, last_outcome) " +
                "VALUES ('$sessionId', 'test-device-0001', 'hello', 1, 2, '$origin', 0, $outcomeLiteral)",
        )
    }

    private fun rowCount(): Int = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM received_texts",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value((cursor.getLong(0) ?: 0L).toInt())
        },
    ).value

    private fun SqlDriver.exec(sql: String) = execute(identifier = null, sql = sql, parameters = 0)
}
