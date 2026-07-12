package net.devemperor.dictate.history

import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the in-keyboard history-panel row predicates
 * (Paket 3 / ADR-0014).
 *
 * `isPendingInsertion()` MUST match the computed-boolean ORDER-BY key in
 * `SessionDao.pagedHistoryPanel` (COMPLETED AND inserted_at IS NULL AND
 * final_output_text IS NOT NULL). This test is the pin that keeps the Kotlin
 * predicate and the SQL in sync (the SQL cannot call the Kotlin).
 */
class SessionRowPredicatesTest {

    private fun session(
        status: SessionStatus = SessionStatus.COMPLETED,
        insertedAt: Long? = null,
        finalOutput: String? = "out",
        input: String? = null,
    ) = SessionEntity(
        id = "s",
        type = "RECORDING",
        createdAt = 1L,
        targetAppPackage = null,
        language = null,
        audioFilePath = null,
        status = status.name,
        finalOutputText = finalOutput,
        inputText = input,
        insertedAt = insertedAt,
    )

    // ── isPendingInsertion ────────────────────────────────────────────

    @Test
    fun `completed with text and not inserted is pending`() {
        assertTrue(session().isPendingInsertion())
    }

    @Test
    fun `already inserted is not pending`() {
        assertFalse(session(insertedAt = 123L).isPendingInsertion())
    }

    @Test
    fun `null final output is not pending`() {
        assertFalse(session(finalOutput = null).isPendingInsertion())
    }

    @Test
    fun `non-completed status is not pending`() {
        assertFalse(session(status = SessionStatus.TRANSCRIBING).isPendingInsertion())
        assertFalse(session(status = SessionStatus.FAILED).isPendingInsertion())
        assertFalse(session(status = SessionStatus.RECORDING).isPendingInsertion())
    }

    // ── hasInsertableText ─────────────────────────────────────────────

    @Test
    fun `has insertable text when final output present`() {
        assertTrue(session(finalOutput = "hello", input = null).hasInsertableText())
    }

    @Test
    fun `has insertable text when only input present`() {
        assertTrue(session(finalOutput = null, input = "transcript").hasInsertableText())
    }

    @Test
    fun `no insertable text when both null or empty`() {
        assertFalse(session(finalOutput = null, input = null).hasInsertableText())
        assertFalse(session(finalOutput = "", input = "").hasInsertableText())
    }
}
