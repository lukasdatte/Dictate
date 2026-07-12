package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke tests for [MIGRATION_8_9] metadata — verifies the version
 * numbers wired into Room's [androidx.room.migration.Migration] superclass are
 * exactly (8 → 9).
 *
 * Behavioural verification (sessions recreate preserves rows, origin CHECK
 * accepts REVIEW_REFINEMENT + rejects unknown, type CHECK accepts the three
 * values + rejects unknown, indices preserved) lives in the instrumented suite
 * `app/src/androidTest/.../MigrationTo9Test.kt` (same split as
 * [MigrationTo8MetadataTest]).
 *
 * @see MIGRATION_8_9
 */
class MigrationTo9MetadataTest {

    @Test
    fun `MIGRATION_8_9 declares startVersion = 8`() {
        assertEquals(8, MIGRATION_8_9.startVersion)
    }

    @Test
    fun `MIGRATION_8_9 declares endVersion = 9`() {
        assertEquals(9, MIGRATION_8_9.endVersion)
    }
}
