package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke test for [MIGRATION_11_12] metadata — pins the version pair (11 → 12).
 *
 * Behavioural verification (five new tables created, prompts recreated with the uuid + envelope
 * columns, all Double-Enum CHECKs accept valid + reject invalid values, rows preserved) lives in
 * the instrumented suite `app/src/androidTest/.../MigrationTo12Test.kt` (same split as
 * [MigrationTo11MetadataTest]).
 *
 * @see MIGRATION_11_12
 */
class MigrationTo12MetadataTest {

    @Test
    fun `MIGRATION_11_12 declares startVersion = 11`() {
        assertEquals(11, MIGRATION_11_12.startVersion)
    }

    @Test
    fun `MIGRATION_11_12 declares endVersion = 12`() {
        assertEquals(12, MIGRATION_11_12.endVersion)
    }
}
