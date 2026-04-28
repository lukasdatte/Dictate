package net.devemperor.dictate.preferences.versioned

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VersionedSerializer].
 *
 * Covers round-trip, envelope detection across `Number` subtypes (W-10),
 * raw-value-as-v1 fallback, and the malformed-JSON safety net.
 */
class VersionedSerializerTest {

    /** Plain v1 plugin over a String list — all the fixture details we need. */
    private class StringListV1Plugin(
        defaultValue: List<String> = emptyList(),
        override val migrations: Map<Int, MigrationFn> = emptyMap(),
        currentVersion: Int = 1
    ) : VersionedPlugin<List<String>>(
        name = "test_strings",
        currentVersion = currentVersion,
        defaultValue = defaultValue,
        codec = StringListCodec
    )

    // ────────────────────────────── Round-trip ───────────────────────────────────────

    @Test
    fun `round-trip preserves value`() {
        val plugin = StringListV1Plugin()
        val serializer = VersionedSerializer(plugin)
        val original = listOf("en", "de", "fr")

        val json = serializer.serialize(original)
        val restored = serializer.deserialize(json)

        assertEquals(original, restored)
    }

    @Test
    fun `serialize emits envelope shape with version field`() {
        val plugin = StringListV1Plugin()
        val serializer = VersionedSerializer(plugin)

        val json = serializer.serialize(listOf("a", "b"))
        val obj = JSONObject(json)

        assertEquals(1, obj.getInt("version"))
        assertTrue("value must be present", obj.has("value"))
    }

    // ────────────────────────────── Envelope-detect (W-10) ───────────────────────────

    @Test
    fun `deserialize detects envelope with Int version`() {
        val plugin = StringListV1Plugin()
        val serializer = VersionedSerializer(plugin)

        // Hand-built envelope, version = Int (1)
        val json = """{"version": 1, "value": ["x", "y"]}"""
        assertEquals(listOf("x", "y"), serializer.deserialize(json))
    }

    @Test
    fun `deserialize detects envelope with Long version (W-10)`() {
        // org.json produces Long for large integer literals; the envelope-
        // check must still recognise it as a Number, not silently fall
        // through to raw-as-v1 (which would lose data).
        val plugin = StringListV1Plugin(currentVersion = 1)
        val serializer = VersionedSerializer(plugin)

        // Force a Long that fits into Int range — verifies that `is Number`
        // detection works for Long-typed `version` fields produced by org.json
        // for integer JSON literals.
        val obj = JSONObject().apply {
            put("version", 1L)  // Long literal, in-int-range
            put("value", org.json.JSONArray(listOf("a", "b")))
        }
        val result = serializer.deserialize(obj.toString())
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `deserialize on out-of-int-range Long version silently truncates and resets to default (W-10 accepted limitation)`() {
        // Pathological-path complement to the in-range Long test. This pins
        // the **accepted limitation** documented on
        // [VersionedSerializer.isVersionedEnvelope]: org.json's `getInt()`
        // silently truncates Long values that don't fit Int range, so a
        // `version: 3_000_000_000L` becomes some negative Int after
        // truncation. That truncated version has no matching migration
        // entry, which routes through `OnMissingMigration.RESET_TO_DEFAULT`
        // (the default strategy) and yields `plugin.defaultValue`.
        //
        // The behaviour is not "hard error" but it is also not silent raw-
        // as-v1 corruption: the value path collapses to the plugin default
        // instead of being interpreted as an unversioned legacy payload.
        // Pinning this here protects the contract against future refactors
        // that might replace `getInt` with `getLong` (which would silently
        // start accepting these versions and break the migration chain).
        val plugin = StringListV1Plugin(defaultValue = listOf("fallback"))
        val serializer = VersionedSerializer(plugin)

        val obj = JSONObject().apply {
            put("version", 3_000_000_000L) // > Int.MAX_VALUE; cannot fit in Int
            put("value", org.json.JSONArray(listOf("a", "b")))
        }

        val result = serializer.deserialize(obj.toString())
        assertEquals(listOf("fallback"), result)
    }

    @Test
    fun `deserialize detects envelope with Double version (W-10)`() {
        // {"version": 1.0, ...} is parsed as Double by org.json. The envelope
        // detect must still consider it an envelope; getInt() then enforces
        // the int-only contract by throwing on non-int Numbers.
        val plugin = StringListV1Plugin(
            defaultValue = listOf("default"),
            currentVersion = 1
        )
        val serializer = VersionedSerializer(plugin)

        val json = """{"version": 1.0, "value": ["a"]}"""
        // 1.0 -> getInt() = 1, identical effective version. Expected: parses
        // and decodes the value. (The legacy raw-as-v1 silent-corruption path
        // would yield default — we explicitly do NOT want that.)
        val result = serializer.deserialize(json)
        assertEquals(listOf("a"), result)
    }

    // ────────────────────────────── Raw-value-as-v1 ──────────────────────────────────

    @Test
    fun `raw JSON array without envelope is treated as v1 and migrates`() {
        // Migration v1 -> v2 prepends "migrated:" to each entry.
        val plugin = object : VersionedPlugin<List<String>>(
            name = "raw_legacy",
            currentVersion = 2,
            defaultValue = emptyList(),
            codec = StringListCodec
        ) {
            override val migrations: Map<Int, MigrationFn> = mapOf(
                1 to { old ->
                    val arr = old as org.json.JSONArray
                    org.json.JSONArray((0 until arr.length()).map { "migrated:${arr.getString(it)}" })
                }
            )
        }

        val serializer = VersionedSerializer(plugin)
        // Pre-versioning payload: bare array, no envelope.
        val json = """["en", "de"]"""
        val result = serializer.deserialize(json)

        assertEquals(listOf("migrated:en", "migrated:de"), result)
    }

    // ────────────────────────────── Malformed JSON ───────────────────────────────────

    @Test
    fun `malformed JSON returns plugin defaultValue`() {
        val plugin = StringListV1Plugin(defaultValue = listOf("fallback"))
        val serializer = VersionedSerializer(plugin)

        val result = serializer.deserialize("{this is not valid json")
        assertEquals(listOf("fallback"), result)
    }

    @Test
    fun `empty string returns plugin defaultValue`() {
        val plugin = StringListV1Plugin(defaultValue = listOf("fallback"))
        val serializer = VersionedSerializer(plugin)

        // JSONTokener throws on empty string; the catch in deserialize routes
        // to plugin defaultValue.
        val result = serializer.deserialize("")
        assertEquals(listOf("fallback"), result)
    }

    // ────────────────────────────── Sanitize on read & write ─────────────────────────

    @Test
    fun `sanitize is applied on serialize`() {
        // Plugin that drops blank entries on every write.
        val plugin = object : VersionedPlugin<List<String>>(
            name = "sanitizing",
            currentVersion = 1,
            defaultValue = emptyList(),
            codec = StringListCodec
        ) {
            override val migrations: Map<Int, MigrationFn> = emptyMap()
            override fun sanitize(value: List<String>): List<String> =
                value.filter { it.isNotBlank() }
        }

        val serializer = VersionedSerializer(plugin)
        val json = serializer.serialize(listOf("a", "", " ", "b"))
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("value")

        assertEquals(2, arr.length())
        assertEquals("a", arr.getString(0))
        assertEquals("b", arr.getString(1))
    }
}
