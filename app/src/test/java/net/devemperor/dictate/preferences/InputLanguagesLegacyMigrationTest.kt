package net.devemperor.dictate.preferences

import net.devemperor.dictate.preferences.versioned.VersionedPluginRegistry
import net.devemperor.dictate.preferences.versioned.VersionedPrefs
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [InputLanguagesLegacyMigration].
 *
 * Verifies idempotence (already-migrated and fresh-install paths), the
 * pos-erhaltung best-effort, and that the resulting persisted shape goes
 * through the plugin's sanitize hook (label-sort, default-on-empty).
 */
class InputLanguagesLegacyMigrationTest {

    private val key = "net.devemperor.dictate.input_languages"

    @Before
    fun setUp() {
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("detect", "en", "de", "fr", "es"),
            labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español")
        )
        // Plugins do not self-register — register explicitly. Idempotent for
        // the same instance.
        VersionedPluginRegistry.register(InputLanguagesPlugin)
    }

    // ── happy path ──

    @Test
    fun `migrates legacy StringSet to versioned envelope, label-sorted`() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putStringSet(key, mutableSetOf("en", "de", "fr"))
            .putInt(Pref.InputLanguagePos.key, 0)
            .apply()

        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)

        val persisted = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        assertEquals(listOf("de", "en", "fr"), persisted)
        // Old key now holds a String, not a StringSet.
        val rawValue = prefs.getString(key, null)
        assertTrue(
            "after migration the value at the key must be a JSON String, was $rawValue",
            rawValue != null && rawValue.startsWith("{")
        )
    }

    // ── idempotence ──

    @Test
    fun `second run is no-op when already migrated`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet(key, mutableSetOf("de", "en")).apply()
        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)

        val firstSnapshot = prefs.getString(key, null)
        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)
        val secondSnapshot = prefs.getString(key, null)

        assertEquals(firstSnapshot, secondSnapshot)
    }

    @Test
    fun `fresh install (no key) is no-op and leaves prefs untouched`() {
        val prefs = FakeSharedPreferences()
        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)
        assertNull(prefs.getString(key, null))
    }

    // ── pos preservation ──

    @Test
    fun `active pos is re-anchored to the same code in the new sorted list`() {
        // Legacy set in some hash-iteration order; we only care that
        // legacyList[oldPos] gets carried into the new list.
        val legacy = linkedSetOf("en", "de", "fr") // order: en, de, fr
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putStringSet(key, legacy)
            .putInt(Pref.InputLanguagePos.key, 1) // legacyList[1] = "de"
            .apply()

        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)

        val persisted = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        // Sorted by label: de, en, fr.
        assertEquals(listOf("de", "en", "fr"), persisted)
        val newPos = prefs.getInt(Pref.InputLanguagePos.key, -1)
        assertEquals("de must remain the active code (pos 0 in sorted list)", 0, newPos)
    }

    @Test
    fun `out-of-range oldPos falls back to 0`() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putStringSet(key, mutableSetOf("en", "de"))
            .putInt(Pref.InputLanguagePos.key, 99) // way out of range
            .apply()

        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)

        val newPos = prefs.getInt(Pref.InputLanguagePos.key, -1)
        assertEquals(0, newPos)
    }

    @Test
    fun `empty StringSet is migrated to default`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet(key, mutableSetOf()).apply()

        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)

        val persisted = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        // Sanitize collapses empty to default (["detect", "en"]) and sorts.
        assertEquals(listOf("detect", "en"), persisted)
    }

    @Test
    fun `unknown codes are filtered out during migration`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet(key, mutableSetOf("en", "xyz", "de", "abc")).apply()

        InputLanguagesLegacyMigration.migrateFromLegacyStringSet(prefs)

        val persisted = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        assertEquals(listOf("de", "en"), persisted)
    }
}
