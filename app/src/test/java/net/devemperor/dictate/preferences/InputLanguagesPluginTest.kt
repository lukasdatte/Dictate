package net.devemperor.dictate.preferences

import net.devemperor.dictate.preferences.versioned.VersionedPluginRegistry
import net.devemperor.dictate.preferences.versioned.VersionedPrefs
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [InputLanguagesPlugin].
 *
 * Verifies the sanitize contract (label-sort, deduplication, allowlist
 * filter, default-on-empty) and the round-trip through [VersionedPrefs].
 *
 * Quality-Gate K-1: handwritten fakes only ([FakeSharedPreferences]),
 * resource-loading bypassed via [LanguageLabelResolver.initializeForTest].
 */
class InputLanguagesPluginTest {

    @Before
    fun setUp() {
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("detect", "en", "de", "fr", "es"),
            labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español")
        )
        // Plugins do not self-register — register explicitly. Re-registration
        // is idempotent for the same instance.
        VersionedPluginRegistry.register(InputLanguagesPlugin)
    }

    // ── sanitize ──

    @Test
    fun `sanitize sorts known codes by display label`() {
        // Input: en, de, fr — sorted by lowercased label: deutsch, english, français.
        val sorted = InputLanguagesPlugin.sanitize(listOf("en", "de", "fr"))
        assertEquals(listOf("de", "en", "fr"), sorted)
    }

    @Test
    fun `sanitize drops unknown codes`() {
        val cleaned = InputLanguagesPlugin.sanitize(listOf("en", "xyz", "de"))
        assertEquals(listOf("de", "en"), cleaned)
    }

    @Test
    fun `sanitize deduplicates repeated entries`() {
        val cleaned = InputLanguagesPlugin.sanitize(listOf("en", "de", "en", "de", "en"))
        assertEquals(listOf("de", "en"), cleaned)
    }

    @Test
    fun `sanitize falls back to default for empty input`() {
        val sanitized = InputLanguagesPlugin.sanitize(emptyList())
        // Default is ["detect", "en"] — sorted by label: auto-detect, english.
        assertEquals(listOf("detect", "en"), sanitized)
    }

    @Test
    fun `sanitize falls back to default when all entries are unknown`() {
        val sanitized = InputLanguagesPlugin.sanitize(listOf("xyz", "abc", "lol"))
        assertEquals(listOf("detect", "en"), sanitized)
    }

    @Test
    fun `default value is itself sorted (no default-path loophole)`() {
        // sanitize on the default itself produces the same list — no surprise.
        val sanitized = InputLanguagesPlugin.sanitize(InputLanguagesPlugin.defaultValue)
        assertEquals(listOf("detect", "en"), sanitized)
    }

    // ── round-trip via VersionedPrefs ──

    @Test
    fun `save then load round-trips the sanitized list`() {
        val prefs = FakeSharedPreferences()
        VersionedPrefs.save(prefs, InputLanguagesPlugin, listOf("fr", "en", "de"))
        val loaded = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        assertEquals(listOf("de", "en", "fr"), loaded)
    }

    @Test
    fun `load on fresh prefs returns the sanitized default`() {
        val prefs = FakeSharedPreferences()
        val loaded = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        assertEquals(listOf("detect", "en"), loaded)
    }

    // ── plugin metadata ──

    @Test
    fun `plugin metadata matches v1 contract`() {
        assertEquals("net.devemperor.dictate.input_languages", InputLanguagesPlugin.name)
        assertEquals(1, InputLanguagesPlugin.currentVersion)
        assertEquals(listOf("detect", "en"), InputLanguagesPlugin.defaultValue)
        assertTrue(
            "v1 plugin must have no migrations",
            InputLanguagesPlugin.migrations.isEmpty()
        )
    }

    @Test
    fun `plugin survives re-registration after registry reset`() {
        // The Plugin object is loaded exactly once per JVM. Other tests
        // (VersionedPluginRegistryTest) reset the registry between cases —
        // simulate that and verify our plugin can be re-registered without
        // throwing the duplicate-instance guard.
        VersionedPluginRegistry.reset()
        VersionedPluginRegistry.register(InputLanguagesPlugin)
        val resolved = VersionedPluginRegistry.findByName(InputLanguagesPlugin.name)
        assertNotNull(resolved)
        assertEquals(InputLanguagesPlugin, resolved)
    }
}
