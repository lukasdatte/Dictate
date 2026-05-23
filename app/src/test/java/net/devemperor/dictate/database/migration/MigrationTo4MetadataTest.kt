package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke tests for [MIGRATION_3_4] metadata — verifies the
 * version numbers wired into Room's [androidx.room.migration.Migration]
 * superclass are exactly (3 → 4).
 *
 * The behavioural part of the migration (CHECK constraint, backfill,
 * FK cascade, indices, v1→v4 chain) lives in the instrumented
 * `MigrationTo4Test` in `app/src/androidTest/` — that suite is
 * **local-only** today (Spec 1 §11.7.0a CI-Integration). Keeping the
 * version metadata in a JVM test means a typo like `Migration(2, 4)`
 * surfaces on the regular `./gradlew test` run without needing a
 * device.
 */
class MigrationTo4MetadataTest {

    @Test
    fun `MIGRATION_3_4 declares startVersion = 3`() {
        assertEquals(3, MIGRATION_3_4.startVersion)
    }

    @Test
    fun `MIGRATION_3_4 declares endVersion = 4`() {
        assertEquals(4, MIGRATION_3_4.endVersion)
    }
}
