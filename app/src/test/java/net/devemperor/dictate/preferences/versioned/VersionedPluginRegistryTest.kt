package net.devemperor.dictate.preferences.versioned

import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VersionedPluginRegistry].
 *
 * Covers register/lookup, the duplicate-instance guard, [VersionedPluginRegistry.reset]
 * test-seam, and [VersionedPluginRegistry.migrateAll]'s isolation contract
 * (Quality-Gate W-9: a single broken plugin must not stop the others).
 *
 * Uses a handwritten [FakeSharedPreferences] — no Mockito.
 */
class VersionedPluginRegistryTest {

    @Before
    fun setUp() {
        VersionedPluginRegistry.reset()
    }

    @After
    fun tearDown() {
        VersionedPluginRegistry.reset()
    }

    // ────────────────────────────── register / findByName ────────────────────────────

    @Test
    fun `register then findByName returns the same instance`() {
        val plugin = StringListPlugin(name = "alpha")
        VersionedPluginRegistry.register(plugin)
        assertSame(plugin, VersionedPluginRegistry.findByName("alpha"))
    }

    @Test
    fun `findByName returns null for unknown plugin`() {
        assertNull(VersionedPluginRegistry.findByName("nope"))
    }

    @Test
    fun `register twice with the same instance is idempotent`() {
        val plugin = StringListPlugin(name = "alpha")
        VersionedPluginRegistry.register(plugin)
        VersionedPluginRegistry.register(plugin) // should not throw
        assertSame(plugin, VersionedPluginRegistry.findByName("alpha"))
        assertEquals(1, VersionedPluginRegistry.all().size)
    }

    @Test
    fun `register with different instance under same name throws`() {
        VersionedPluginRegistry.register(StringListPlugin(name = "alpha"))
        try {
            VersionedPluginRegistry.register(StringListPlugin(name = "alpha"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention duplicate, was: ${e.message}",
                e.message!!.contains("Duplicate")
            )
        }
    }

    // ────────────────────────────── reset ────────────────────────────────────────────

    @Test
    fun `reset clears all registered plugins`() {
        VersionedPluginRegistry.register(StringListPlugin(name = "a"))
        VersionedPluginRegistry.register(StringListPlugin(name = "b"))
        assertEquals(2, VersionedPluginRegistry.all().size)

        VersionedPluginRegistry.reset()
        assertEquals(0, VersionedPluginRegistry.all().size)
        assertNull(VersionedPluginRegistry.findByName("a"))
    }

    // ────────────────────────────── migrateAll (W-9) ─────────────────────────────────

    @Test
    fun `migrateAll continues past plugin that throws (W-9)`() {
        // Plugin "boom" will throw during deserialize because its migration
        // step throws and onMissingMigration = THROW. Plugin "ok" must still
        // be loaded.
        val boomPlugin = ThrowingPlugin(name = "boom")
        val okPlugin = StringListPlugin(name = "ok")

        val prefs = FakeSharedPreferences().apply {
            // Provide stored data that triggers a migration on read.
            edit()
                .putString("boom", """{"version": 1, "value": "anything"}""")
                .putString("ok", """{"version": 1, "value": ["x", "y"]}""")
                .apply()
        }

        VersionedPluginRegistry.register(boomPlugin)
        VersionedPluginRegistry.register(okPlugin)

        // Should not throw. The "boom" plugin's load fails internally and is
        // logged; "ok" should still be eager-loaded successfully.
        VersionedPluginRegistry.migrateAll(prefs)

        // "ok" was readable and decoded — sanity-check via load again.
        val okValue = VersionedPrefs.load(prefs, okPlugin)
        assertEquals(listOf("x", "y"), okValue)
    }

    // ────────────────────────────── Test fixtures ────────────────────────────────────

    /** Simple v1 String-list plugin for registry tests. */
    private class StringListPlugin(name: String) : VersionedPlugin<List<String>>(
        name = name,
        currentVersion = 1,
        defaultValue = emptyList(),
        codec = StringListCodec
    ) {
        override val migrations: Map<Int, MigrationFn> = emptyMap()
    }

    /**
     * Plugin whose migration always throws. Combined with `THROW` strategy,
     * its [VersionedPrefs.load] surfaces the exception — exactly what
     * [VersionedPluginRegistry.migrateAll] must catch per-plugin (W-9).
     */
    private class ThrowingPlugin(name: String) : VersionedPlugin<List<String>>(
        name = name,
        currentVersion = 2,
        defaultValue = emptyList(),
        codec = StringListCodec,
        onMissingMigration = OnMissingMigration.THROW
    ) {
        override val migrations: Map<Int, MigrationFn> = mapOf(
            1 to { _ -> throw RuntimeException("boom") }
        )
    }

    // FakeSharedPreferences hoisted to net.devemperor.dictate.testutil for re-use
    // across LanguageResolver + Migration tests (Chunk 3).
}
