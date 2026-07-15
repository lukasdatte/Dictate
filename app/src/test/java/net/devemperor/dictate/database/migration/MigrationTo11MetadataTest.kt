package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke tests for [MIGRATION_10_11] metadata — verifies the version
 * numbers wired into Room's [androidx.room.migration.Migration] superclass are
 * exactly (10 → 11).
 *
 * Behavioural verification (prompts recreate preserves rows, `[bracketed]`
 * prompts/names classify to TEXT + stripped, `type` CHECK rejects unknown values,
 * flags survive) lives in the instrumented suite
 * `app/src/androidTest/.../MigrationTo11Test.kt` (same split as
 * [MigrationTo10MetadataTest]).
 *
 * @see MIGRATION_10_11
 */
class MigrationTo11MetadataTest {

    @Test
    fun `MIGRATION_10_11 declares startVersion = 10`() {
        assertEquals(10, MIGRATION_10_11.startVersion)
    }

    @Test
    fun `MIGRATION_10_11 declares endVersion = 11`() {
        assertEquals(11, MIGRATION_10_11.endVersion)
    }
}
