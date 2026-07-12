package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke tests for [MIGRATION_7_8] metadata — verifies the version
 * numbers wired into Room's [androidx.room.migration.Migration] superclass are
 * exactly (7 → 8).
 *
 * Behavioural verification (processing_steps recreate preserves rows + adds the
 * new columns, step_type/response_format/role CHECKs enforced, indices + FKs
 * preserved, conversation_messages created) lives in the instrumented suite
 * `app/src/androidTest/.../MigrationTo8Test.kt` (same split as
 * [MigrationTo5MetadataTest] / `MigrationTo4Test`).
 *
 * @see MIGRATION_7_8
 */
class MigrationTo8MetadataTest {

    @Test
    fun `MIGRATION_7_8 declares startVersion = 7`() {
        assertEquals(7, MIGRATION_7_8.startVersion)
    }

    @Test
    fun `MIGRATION_7_8 declares endVersion = 8`() {
        assertEquals(8, MIGRATION_7_8.endVersion)
    }
}
