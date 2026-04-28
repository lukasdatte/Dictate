package net.devemperor.dictate.preferences.versioned

import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VersionedPrefs].
 *
 * Closes the test gap flagged in post-implementation validation (Phase 0
 * I-1): the **self-heal-on-read** behaviour described in plan §0.9 was
 * previously only covered indirectly through [VersionedSerializer]
 * round-trip tests. These tests pin the actual `load → write-back if
 * differs → no-write if identical` contract on real `SharedPreferences`.
 */
class VersionedPrefsTest {

    /** Plain v1 plugin over a String list — fixture for the simple cases. */
    private class StringListV1Plugin(
        defaultValue: List<String> = emptyList()
    ) : VersionedPlugin<List<String>>(
        name = "test_strings",
        currentVersion = 1,
        defaultValue = defaultValue,
        codec = StringListCodec
    ) {
        override val migrations: Map<Int, MigrationFn> = emptyMap()
    }

    // ────────────────────────────── Raw-legacy → v1 envelope (self-heal #1) ──────────

    @Test
    fun `load rewrites raw legacy payload as v1 envelope`() {
        val prefs = FakeSharedPreferences()
        val plugin = StringListV1Plugin()
        // Pre-envelope payload at the plugin key — old install shape.
        prefs.edit().putString(plugin.name, """["en", "de"]""").apply()

        val result = VersionedPrefs.load(prefs, plugin)

        assertEquals(listOf("en", "de"), result)

        // Self-heal: the persisted value is now in envelope form.
        val rewritten = prefs.getString(plugin.name, null)
        assertNotNull("self-heal must have written a non-null value", rewritten)
        val obj = JSONObject(rewritten!!)
        assertEquals(1, obj.getInt("version"))
        val arr = obj.getJSONArray("value")
        assertEquals(2, arr.length())
        assertEquals("en", arr.getString(0))
        assertEquals("de", arr.getString(1))
    }

    // ────────────────────────────── Sanitize-drift → rewrite (self-heal #2) ──────────

    @Test
    fun `load rewrites sanitized form when on-disk envelope contains drift`() {
        val prefs = FakeSharedPreferences()
        // Plugin that drops blank entries on every encode/decode pass.
        val plugin = object : VersionedPlugin<List<String>>(
            name = "sanitizing_strings",
            currentVersion = 1,
            defaultValue = emptyList(),
            codec = StringListCodec
        ) {
            override val migrations: Map<Int, MigrationFn> = emptyMap()
            override fun sanitize(value: List<String>): List<String> =
                value.filter { it.isNotBlank() }
        }
        // Hand-built envelope that contains drift sanitize() will trim.
        val drifted = JSONObject().apply {
            put("version", 1)
            put("value", JSONArray(listOf("a", "", " ", "b")))
        }.toString()
        prefs.edit().putString(plugin.name, drifted).apply()

        val result = VersionedPrefs.load(prefs, plugin)

        assertEquals(listOf("a", "b"), result)

        // The on-disk form is the sanitized one now.
        val healed = prefs.getString(plugin.name, null)
        assertNotNull(healed)
        val arr = JSONObject(healed!!).getJSONArray("value")
        assertEquals(2, arr.length())
        assertEquals("a", arr.getString(0))
        assertEquals("b", arr.getString(1))
    }

    // ────────────────────────────── No-write-when-identical (self-heal contract) ──────

    @Test
    fun `load does not rewrite when serialized form matches on-disk JSON`() {
        val prefs = FakeSharedPreferences()
        val plugin = StringListV1Plugin()
        // Build the envelope exactly the way VersionedSerializer would — same
        // key order, same encoding — so the compare-then-write path stays inert.
        val canonical = VersionedSerializer(plugin).serialize(listOf("en", "de"))
        prefs.edit().putString(plugin.name, canonical).apply()

        val result = VersionedPrefs.load(prefs, plugin)

        assertEquals(listOf("en", "de"), result)
        // Byte-identical: no self-heal write performed.
        assertEquals(canonical, prefs.getString(plugin.name, null))
    }

    // ────────────────────────────── Migrated data → persist at new version ───────────

    @Test
    fun `load persists migrated payload at new currentVersion`() {
        val prefs = FakeSharedPreferences()
        // Plugin v2 with a v1→v2 migration that prepends "migrated:".
        val plugin = object : VersionedPlugin<List<String>>(
            name = "migrating_strings",
            currentVersion = 2,
            defaultValue = emptyList(),
            codec = StringListCodec
        ) {
            override val migrations: Map<Int, MigrationFn> = mapOf(
                1 to { old ->
                    val arr = old as JSONArray
                    JSONArray((0 until arr.length()).map { "migrated:${arr.getString(it)}" })
                }
            )
        }
        // On-disk: v1 envelope.
        val v1 = JSONObject().apply {
            put("version", 1)
            put("value", JSONArray(listOf("en", "de")))
        }.toString()
        prefs.edit().putString(plugin.name, v1).apply()

        val result = VersionedPrefs.load(prefs, plugin)

        assertEquals(listOf("migrated:en", "migrated:de"), result)

        // Self-heal: persisted form is now at the new version with migrated data.
        val healed = prefs.getString(plugin.name, null)
        assertNotNull(healed)
        val obj = JSONObject(healed!!)
        assertEquals(2, obj.getInt("version"))
        val arr = obj.getJSONArray("value")
        assertEquals("migrated:en", arr.getString(0))
        assertEquals("migrated:de", arr.getString(1))
    }

    // ────────────────────────────── No-key → defaultValue (no write) ─────────────────

    @Test
    fun `load returns defaultValue and writes nothing when key is absent`() {
        val prefs = FakeSharedPreferences()
        val plugin = StringListV1Plugin(defaultValue = listOf("fallback"))

        val result = VersionedPrefs.load(prefs, plugin)

        assertEquals(listOf("fallback"), result)
        // Defensive: load() must not seed the key with the default value.
        assertFalse("load must not write defaults", prefs.contains(plugin.name))
        assertNull(prefs.getString(plugin.name, null))
    }

    // ────────────────────────────── Malformed JSON → defaultValue + self-heal ────────

    @Test
    fun `load returns defaultValue and self-heals when on-disk JSON is malformed`() {
        val prefs = FakeSharedPreferences()
        val plugin = StringListV1Plugin(defaultValue = listOf("fallback"))
        prefs.edit().putString(plugin.name, "{not valid json").apply()

        val result = VersionedPrefs.load(prefs, plugin)

        assertEquals(listOf("fallback"), result)
        // Self-heal also rescues malformed JSON: the deserializer routes the
        // parse failure to defaultValue, then the load() compare-then-write
        // step re-serializes that default into a clean envelope. The next
        // load() therefore reads the canonical envelope without the malformed
        // payload going through the JSON parser again.
        val healed = prefs.getString(plugin.name, null)
        assertNotNull(healed)
        val obj = JSONObject(healed!!)
        assertEquals(1, obj.getInt("version"))
        val arr = obj.getJSONArray("value")
        assertEquals(1, arr.length())
        assertEquals("fallback", arr.getString(0))
    }

    // ────────────────────────────── Save round-trip ──────────────────────────────────

    @Test
    fun `save persists the envelope and load reads it back identically`() {
        val prefs = FakeSharedPreferences()
        val plugin = StringListV1Plugin()

        val saved = VersionedPrefs.save(prefs, plugin, listOf("en", "de"))
        assertTrue("save must report success", saved)

        val loaded = VersionedPrefs.load(prefs, plugin)
        assertEquals(listOf("en", "de"), loaded)
    }
}
