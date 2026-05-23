package net.devemperor.dictate.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the [SessionStatus] enum and its Double-Enum
 * invariants (Spec 1 §6.1 + `docs/DATABASE-PATTERNS.md`).
 *
 * The CHECK-constraint half of the Double-Enum is verified by the
 * instrumented [net.devemperor.dictate.database.migration.MigrationTo4Test]
 * (local-only, requires a device/emulator). These JVM tests guard the
 * Kotlin half: that the value set stays in sync with the migration's
 * SQL `CHECK (status IN (...))` literal.
 */
class SessionStatusTest {

    /**
     * Hard-coded reference set kept side-by-side with the SQL literal in
     * [net.devemperor.dictate.database.migration.MIGRATION_3_4]. A
     * mismatch — either direction — is a Double-Enum break and would
     * surface as an `SQLiteConstraintException` at runtime.
     */
    private val expectedValues = setOf(
        "RECORDING",
        "RECORDING_INTERRUPTED",
        "RECORDED",
        "TRANSCRIBING",
        "COMPLETED",
        "FAILED",
        "CANCELLED"
    )

    @Test
    fun `enum has exactly seven variants matching the M5_6 CHECK constraint`() {
        val actualNames = SessionStatus.values().map { it.name }.toSet()
        assertEquals(
            "SessionStatus.values() must match the CHECK list in MIGRATION_5_6 — " +
                "see docs/DATABASE-PATTERNS.md 'Adding a new enum value'",
            expectedValues,
            actualNames
        )
    }

    @Test
    fun `valueOf accepts every expected name`() {
        expectedValues.forEach { name ->
            // Throws IllegalArgumentException on miss — we want the
            // assertion failure to point at *which* name failed.
            assertEquals(name, SessionStatus.valueOf(name).name)
        }
    }

    @Test
    fun `valueOf rejects unknown names`() {
        val invalid = listOf("running", "Recording", "DONE", "TRANSCRIBED", "")
        invalid.forEach { name ->
            val rejected = runCatching { SessionStatus.valueOf(name) }.isFailure
            assertTrue("valueOf should reject '$name'", rejected)
        }
    }

    @Test
    fun `live states are distinct from terminal states`() {
        // Documentation contract — the State-Machine in §6.1 treats
        // these two groups differently: live states get downgraded by
        // PipelineRecovery on cold-start; terminal states stay put.
        val live = setOf(
            SessionStatus.RECORDING,
            SessionStatus.RECORDING_INTERRUPTED,
            SessionStatus.TRANSCRIBING,
        )
        val terminal = setOf(
            SessionStatus.RECORDED,
            SessionStatus.COMPLETED,
            SessionStatus.FAILED,
            SessionStatus.CANCELLED
        )
        assertTrue(live.intersect(terminal).isEmpty())
        assertEquals(
            "Every variant must be live OR terminal, not both",
            SessionStatus.values().size,
            live.size + terminal.size
        )
    }
}
