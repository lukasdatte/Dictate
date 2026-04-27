package net.devemperor.dictate.preferences.versioned

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [VersionedMigrator].
 *
 * Stack: JUnit 4 + handwritten plugin fakes (no Mockito/MockK/Robolectric).
 * Pattern mirrors `ActiveJobRegistryTest`/`SessionTrackerTest`.
 *
 * Codec is irrelevant for migration logic — these tests use a pass-through
 * `AnyCodec` that hands back the raw decoded value untouched. Migrations
 * see and return `Map<String, Any?>` for shape evolution.
 */
class VersionedMigratorTest {

    /** Pass-through codec — keeps the raw shape so migrations can mutate it. */
    private object PassThroughCodec : JsonCodec<Any?> {
        override fun encode(value: Any?): Any = value ?: org.json.JSONObject.NULL
        override fun decode(raw: Any?): Any? = raw
    }

    private class TestPlugin(
        currentVersion: Int,
        defaultValue: Any?,
        onMissingMigration: OnMissingMigration = OnMissingMigration.RESET_TO_DEFAULT,
        override val migrations: Map<Int, MigrationFn> = emptyMap()
    ) : VersionedPlugin<Any?>(
        name = "test",
        currentVersion = currentVersion,
        defaultValue = defaultValue,
        codec = PassThroughCodec,
        onMissingMigration = onMissingMigration
    )

    // ────────────────────────────── No migration needed ──────────────────────────────

    @Test
    fun `returns data unchanged when version matches current`() {
        val plugin = TestPlugin(currentVersion = 2, defaultValue = "default")
        val result = VersionedMigrator.migrate(plugin, Versioned(2, "hello"))

        assertEquals(2, result.data.version)
        assertEquals("hello", result.data.value)
        assertFalse(result.migrated)
        assertEquals(2, result.fromVersion)
        assertEquals(2, result.toVersion)
    }

    // ────────────────────────────── Sequential forward migration ─────────────────────

    @Test
    fun `migrates v1 to v2 with single step`() {
        val plugin = TestPlugin(
            currentVersion = 2,
            defaultValue = mapOf<String, Any?>(),
            migrations = mapOf<Int, MigrationFn>(
                1 to { old ->
                    @Suppress("UNCHECKED_CAST")
                    (old as Map<String, Any?>) + ("addedInV2" to true)
                }
            )
        )
        val result = VersionedMigrator.migrate(
            plugin,
            Versioned(1, mapOf("name" to "alice"))
        )

        assertEquals(2, result.data.version)
        assertEquals(
            mapOf("name" to "alice", "addedInV2" to true),
            result.data.value
        )
        assertTrue(result.migrated)
        assertEquals(1, result.fromVersion)
        assertEquals(2, result.toVersion)
    }

    @Test
    fun `migrates v1 through v2 to v3 sequentially`() {
        val plugin = TestPlugin(
            currentVersion = 3,
            defaultValue = mapOf<String, Any?>(),
            migrations = mapOf<Int, MigrationFn>(
                1 to { old ->
                    @Suppress("UNCHECKED_CAST")
                    (old as Map<String, Any?>) + ("addedInV2" to true)
                },
                2 to { old ->
                    @Suppress("UNCHECKED_CAST")
                    val map = old as Map<String, Any?>
                    map - "addedInV2" + ("transformedInV3" to true)
                }
            )
        )
        val result = VersionedMigrator.migrate(
            plugin,
            Versioned(1, mapOf("original" to "data"))
        )

        assertEquals(3, result.data.version)
        assertEquals(
            mapOf("original" to "data", "transformedInV3" to true),
            result.data.value
        )
        assertTrue(result.migrated)
        assertEquals(1, result.fromVersion)
        assertEquals(3, result.toVersion)
    }

    @Test
    fun `migrates from intermediate version v2 to v3 only`() {
        val plugin = TestPlugin(
            currentVersion = 3,
            defaultValue = mapOf<String, Any?>(),
            migrations = mapOf<Int, MigrationFn>(
                1 to { _ -> fail("v1 migration must not run when starting from v2"); error("unreachable") },
                2 to { old ->
                    @Suppress("UNCHECKED_CAST")
                    (old as Map<String, Any?>) + ("v3Field" to 42)
                }
            )
        )
        val result = VersionedMigrator.migrate(
            plugin,
            Versioned(2, mapOf("base" to true))
        )

        assertEquals(3, result.data.version)
        assertEquals(
            mapOf("base" to true, "v3Field" to 42),
            result.data.value
        )
    }

    // ────────────────────────────── Future-version (W-8) ─────────────────────────────

    @Test
    fun `future version with RESET_TO_DEFAULT returns default at currentVersion`() {
        val plugin = TestPlugin(
            currentVersion = 1,
            defaultValue = "safe-default",
            onMissingMigration = OnMissingMigration.RESET_TO_DEFAULT
        )
        val result = VersionedMigrator.migrate(plugin, Versioned(2, "from-future"))

        assertEquals(1, result.data.version)
        assertEquals("safe-default", result.data.value)
        assertTrue(result.migrated)
        assertEquals(2, result.fromVersion)
        assertEquals(1, result.toVersion)
    }

    @Test
    fun `future version with THROW raises IllegalStateException`() {
        val plugin = TestPlugin(
            currentVersion = 1,
            defaultValue = "default",
            onMissingMigration = OnMissingMigration.THROW
        )
        try {
            VersionedMigrator.migrate(plugin, Versioned(2, "from-future"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention version mismatch, was: ${e.message}",
                e.message!!.contains("version 2 > current 1")
            )
        }
    }

    // ────────────────────────────── Missing migration ────────────────────────────────

    @Test
    fun `missing migration with RESET_TO_DEFAULT returns default`() {
        val plugin = TestPlugin(
            currentVersion = 2,
            defaultValue = "fresh",
            onMissingMigration = OnMissingMigration.RESET_TO_DEFAULT,
            migrations = emptyMap()
        )
        val result = VersionedMigrator.migrate(plugin, Versioned(1, "stale"))

        assertEquals(2, result.data.version)
        assertEquals("fresh", result.data.value)
        assertTrue(result.migrated)
    }

    @Test
    fun `missing intermediate migration with RESET_TO_DEFAULT returns default`() {
        val plugin = TestPlugin(
            currentVersion = 3,
            defaultValue = "fresh",
            onMissingMigration = OnMissingMigration.RESET_TO_DEFAULT,
            migrations = mapOf<Int, MigrationFn>(
                1 to { old -> old }
                // v2 -> v3 missing
            )
        )
        val result = VersionedMigrator.migrate(plugin, Versioned(1, "stale"))

        assertEquals(3, result.data.version)
        assertEquals("fresh", result.data.value)
    }

    @Test
    fun `missing migration with THROW raises IllegalStateException`() {
        val plugin = TestPlugin(
            currentVersion = 2,
            defaultValue = "default",
            onMissingMigration = OnMissingMigration.THROW,
            migrations = emptyMap()
        )
        try {
            VersionedMigrator.migrate(plugin, Versioned(1, "stale"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention missing migration, was: ${e.message}",
                e.message!!.contains("Missing migration")
            )
        }
    }

    // ────────────────────────────── Migration that throws ────────────────────────────

    @Test
    fun `migration throwing with RESET_TO_DEFAULT returns default`() {
        val plugin = TestPlugin(
            currentVersion = 2,
            defaultValue = "fallback",
            onMissingMigration = OnMissingMigration.RESET_TO_DEFAULT,
            migrations = mapOf<Int, MigrationFn>(
                1 to { _ -> throw RuntimeException("boom") }
            )
        )
        val result = VersionedMigrator.migrate(plugin, Versioned(1, "anything"))

        assertEquals(2, result.data.version)
        assertEquals("fallback", result.data.value)
        assertTrue(result.migrated)
    }

    @Test
    fun `migration throwing with THROW propagates as IllegalStateException`() {
        val plugin = TestPlugin(
            currentVersion = 2,
            defaultValue = "default",
            onMissingMigration = OnMissingMigration.THROW,
            migrations = mapOf<Int, MigrationFn>(
                1 to { _ -> throw RuntimeException("boom") }
            )
        )
        try {
            VersionedMigrator.migrate(plugin, Versioned(1, "anything"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention migration failure, was: ${e.message}",
                e.message!!.contains("Migration failed")
            )
            // Original cause should be preserved.
            assertTrue(e.cause is RuntimeException)
            assertEquals("boom", e.cause!!.message)
        }
    }
}
