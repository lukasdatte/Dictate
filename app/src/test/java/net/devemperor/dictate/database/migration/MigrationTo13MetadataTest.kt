package net.devemperor.dictate.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM smoke test for [MIGRATION_12_13] metadata — pins the version pair (12 → 13).
 *
 * Behavioural verification (the `peers`/`subscriptions` tables are created, the Double-Enum CHECKs
 * accept valid + reject invalid `kind`/`mode` values, the schema validates against 13.json, the peer
 * FK CASCADEs) lives in the instrumented suite `app/src/androidTest/.../MigrationTo13Test.kt` (same
 * split as [MigrationTo12MetadataTest]).
 *
 * @see MIGRATION_12_13
 */
class MigrationTo13MetadataTest {

    @Test
    fun `MIGRATION_12_13 declares startVersion = 12`() {
        assertEquals(12, MIGRATION_12_13.startVersion)
    }

    @Test
    fun `MIGRATION_12_13 declares endVersion = 13`() {
        assertEquals(13, MIGRATION_12_13.endVersion)
    }
}
