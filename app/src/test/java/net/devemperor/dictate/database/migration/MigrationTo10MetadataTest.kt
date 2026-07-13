package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke tests for [MIGRATION_9_10] metadata — verifies the version numbers wired into
 * Room's [androidx.room.migration.Migration] superclass are exactly (9 → 10).
 *
 * Behavioural verification (text_insertions recreate preserves rows, insertion_method CHECK
 * accepts WINDOWS_DISPATCH + rejects unknown, target_device_id backfills NULL, indices preserved)
 * lives in the instrumented suite `app/src/androidTest/.../MigrationTo10Test.kt` (same split as
 * [MigrationTo9MetadataTest]).
 *
 * @see MIGRATION_9_10
 */
class MigrationTo10MetadataTest {

    @Test
    fun `MIGRATION_9_10 declares startVersion = 9`() {
        assertEquals(9, MIGRATION_9_10.startVersion)
    }

    @Test
    fun `MIGRATION_9_10 declares endVersion = 10`() {
        assertEquals(10, MIGRATION_9_10.endVersion)
    }
}
