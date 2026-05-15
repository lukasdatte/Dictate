package net.devemperor.dictate.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the [SessionEntity] data class invariants
 * introduced by the M3→M4 migration (Spec 1 §6.1).
 *
 * Schema-level invariants (CHECK constraint, table layout, FK
 * cascade) live in the instrumented [MigrationTo4Test]; these tests
 * cover only the Kotlin-side defaults + enum accessors that JVM tests
 * can verify without a real SQLite.
 */
class SessionEntityTest {

    private fun newEntity(
        status: String = SessionStatus.RECORDED.name,
        insertedAt: Long? = null
    ) = SessionEntity(
        id = "test",
        type = SessionType.RECORDING.name,
        createdAt = 1000L,
        targetAppPackage = null,
        language = null,
        audioFilePath = null,
        audioDurationSeconds = 0L,
        parentSessionId = null,
        status = status,
        origin = SessionOrigin.KEYBOARD.name,
        insertedAt = insertedAt
    )

    @Test
    fun `insertedAt defaults to null on construction`() {
        val entity = newEntity()
        assertNull(entity.insertedAt)
    }

    @Test
    fun `insertedAt is preserved through copy`() {
        val ts = 9_999_999L
        val entity = newEntity(insertedAt = ts).copy(status = SessionStatus.COMPLETED.name)
        assertEquals(ts, entity.insertedAt)
    }

    @Test
    fun `statusEnum returns the matching enum value`() {
        SessionStatus.values().forEach { status ->
            val entity = newEntity(status = status.name)
            assertEquals(
                "statusEnum must round-trip for $status",
                status,
                entity.statusEnum
            )
        }
    }

    @Test
    fun `statusEnum falls back to RECORDED for unknown string`() {
        // Downgrade scenario: a v4 row with RECORDING/TRANSCRIBING
        // surfaces in an older app that doesn't know the value. The
        // entity's fallback (Spec 1 §6.1.3 "Doppel-Sicherung") keeps
        // the row visible as "not processed".
        val entity = newEntity(status = "FUTURE_STATUS")
        assertEquals(SessionStatus.RECORDED, entity.statusEnum)
    }
}
