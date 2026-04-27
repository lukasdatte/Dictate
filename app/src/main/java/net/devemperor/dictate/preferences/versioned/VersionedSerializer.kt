package net.devemperor.dictate.preferences.versioned

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Serializes/deserializes a single [VersionedPlugin]'s value to/from its
 * on-disk JSON form.
 *
 * On write, the value is sanitized and wrapped in an envelope:
 * ```json
 * { "version": <currentVersion>, "value": <encoded> }
 * ```
 *
 * On read, the JSON is parsed and either:
 * - recognized as an envelope and routed through [VersionedMigrator], or
 * - treated as a "raw legacy" value (no envelope) and migrated **as if it
 *   were v1**. This lets pre-versioning data files migrate forward through
 *   `migrations[1]`, `migrations[2]`, ...
 *
 * Malformed JSON returns [VersionedPlugin.defaultValue] without further
 * processing. The caller (typically [VersionedPrefs]) is responsible for
 * any "self-heal write" if the deserialized value differs from the input.
 */
class VersionedSerializer<T>(private val plugin: VersionedPlugin<T>) {

    fun serialize(value: T): String {
        val sanitized = plugin.sanitize(value)
        val envelope = JSONObject().apply {
            put("version", plugin.currentVersion)
            put("value", plugin.codec.encode(sanitized))
        }
        return envelope.toString()
    }

    fun deserialize(json: String): T {
        val parsed: Any? = try {
            JSONTokener(json).nextValue()
        } catch (e: Throwable) {
            return plugin.defaultValue
        }

        val envelope: Versioned<Any?> = if (isVersionedEnvelope(parsed)) {
            val obj = parsed as JSONObject
            // getInt() throws JSONException for non-int Number; that's a hard
            // shape error (can't recover) and is caught by VersionedPrefs.
            // opt vs get: equivalent here because of the has(...) check above;
            // opt is defensive against future refactors that drop the precheck.
            Versioned(obj.getInt("version"), obj.opt("value"))
        } else {
            // Raw legacy payload: treat as v1 and let migrations forward it.
            Versioned(1, parsed)
        }

        val result = VersionedMigrator.migrate(plugin, envelope)
        return plugin.sanitize(result.data.value)
    }

    /**
     * Detect whether [parsed] is in the versioned envelope shape.
     *
     * Quality-Gate W-10: `org.json` returns `Integer`/`Long`/`Double` for
     * numeric JSON values, so a strict `is Int` check would silently treat
     * `{"version": 1.0, ...}` or `{"version": 1L, ...}` as raw legacy and
     * cause data corruption. `is Number` is the correct contract here;
     * [JSONObject.getInt] below will throw on non-int Numbers and that
     * surfaces as a hard error rather than silent loss.
     *
     * Edge case: `is Number` matches Int/Long/Double on Android org.json.
     * BigDecimal-sized version literals (>Long.MAX_VALUE) would be detected
     * but silently truncated by getInt() — accepted limitation; version
     * values are monotone small ints in practice.
     */
    private fun isVersionedEnvelope(parsed: Any?): Boolean =
        parsed is JSONObject &&
            parsed.has("version") &&
            parsed.has("value") &&
            parsed.get("version") is Number
}
