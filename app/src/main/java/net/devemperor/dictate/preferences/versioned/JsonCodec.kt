package net.devemperor.dictate.preferences.versioned

import org.json.JSONArray

/**
 * Per-type bridge between a domain value [T] and its `org.json`-compatible
 * representation (`JSONObject`, `JSONArray`, primitives).
 *
 * Kotlin replacement for the TypeScript `zod`-based schemas in the excel_ekl
 * port. Codecs are instantiated as singletons (one per shape) and reused by
 * many [VersionedPlugin]s.
 *
 * Implementations should:
 * - [encode] never throw for a well-formed [T]
 * - [decode] throw [IllegalArgumentException] on shape mismatch (caught by
 *   [VersionedMigrator] and routed through the plugin's error strategy)
 */
interface JsonCodec<T> {
    /** Encode [value] to a JSON-friendly object (JSONObject/JSONArray/primitive). */
    fun encode(value: T): Any

    /** Decode the raw value from a parsed JSON tree back to [T]. */
    fun decode(raw: Any?): T
}

/** Codec for `List<String>`, persisted as a `JSONArray` of strings. */
object StringListCodec : JsonCodec<List<String>> {
    override fun encode(value: List<String>): Any = JSONArray(value)

    override fun decode(raw: Any?): List<String> = when (raw) {
        is JSONArray -> List(raw.length()) { raw.getString(it) }
        null -> emptyList()
        else -> throw IllegalArgumentException(
            "StringListCodec: expected JSONArray, got ${raw::class.simpleName}"
        )
    }
}

/** Codec for `List<Int>`, persisted as a `JSONArray` of ints. */
object IntListCodec : JsonCodec<List<Int>> {
    override fun encode(value: List<Int>): Any = JSONArray(value)

    override fun decode(raw: Any?): List<Int> = when (raw) {
        is JSONArray -> List(raw.length()) { raw.getInt(it) }
        null -> emptyList()
        else -> throw IllegalArgumentException(
            "IntListCodec: expected JSONArray, got ${raw::class.simpleName}"
        )
    }
}
