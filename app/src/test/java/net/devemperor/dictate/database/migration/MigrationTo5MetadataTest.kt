package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke tests for [MIGRATION_4_5] metadata — verifies the
 * version numbers wired into Room's [androidx.room.migration.Migration]
 * superclass are exactly (4 → 5).
 *
 * Behavioural verification (ADD COLUMN succeeds, backfill correct,
 * pre-existing indices preserved) belongs in the instrumented migration
 * suite under `app/src/androidTest/` (same split as
 * [MigrationTo4MetadataTest] / `MigrationTo4Test`).
 *
 * @see MIGRATION_4_5
 */
class MigrationTo5MetadataTest {

    @Test
    fun `MIGRATION_4_5 declares startVersion = 4`() {
        assertEquals(4, MIGRATION_4_5.startVersion)
    }

    @Test
    fun `MIGRATION_4_5 declares endVersion = 5`() {
        assertEquals(5, MIGRATION_4_5.endVersion)
    }
}
