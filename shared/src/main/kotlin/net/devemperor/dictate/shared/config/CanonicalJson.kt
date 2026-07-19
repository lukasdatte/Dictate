package net.devemperor.dictate.shared.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one deterministic byte-form of a config value — the input to every `contentHash` and the
 * body of every v3 catalog file.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). It MUST be the single instance: `encodeDefaults` and `explicitNulls`
 * have to be identical on `:app` and `:companion` or the hashes drift and the Block E peer-sync
 * breaks. That is why this lives once in `:shared`, never as a per-platform copy.
 *
 * ## The canonical form (a subset of RFC 8785 / JCS)
 *
 * Enough for byte-stability without the full number canonicalisation, because the model has no
 * floating-point values — temperatures and the like are strings in `parameterDefaults`/
 * `parameterOverrides` (§4.5):
 *
 * 1. Serialise the value to a `JsonElement` with `encodeDefaults = true` (so the hash does not
 *    depend on whether a value was set explicitly or by default) and `explicitNulls = false`
 *    (a null payload field is an ABSENT key, never `"field":null`).
 * 2. Strip the [ENVELOPE_FIELDS] on the **top object only** (§4.2) — so two copies of the same
 *    payload with a different `id`/`visibility`/`sourceRef` hash identically (Block E fork-dedup).
 * 3. Canonicalise recursively: object members sorted by key (UTF-16 code-unit order = Kotlin
 *    `String.compareTo` = JCS), arrays kept in order (significant — e.g. `orderedPrompts`), string
 *    primitives minimally escaped (RFC 8259 §7 mandatory escapes only), integers plain decimal.
 * 4. Emit compact (no whitespace) UTF-8.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §5.1, §4.2
 */
object CanonicalJson {

    /**
     * The polymorphic discriminator key of [CatalogEntry] — `"kind"` per §5.4. Declared here so the
     * canonical [json] (which serialises the sealed union when emitting a catalog file) and
     * [CatalogCodec]'s decoding `Json` share ONE source of truth for the discriminator name.
     */
    const val CATALOG_DISCRIMINATOR = "kind"

    /**
     * Field names excluded from the canonical (hash-relevant) form (§4.2). Removed on the top object
     * only, so a nested payload object that happened to reuse one of these names is untouched.
     */
    val ENVELOPE_FIELDS: Set<String> =
        setOf("id", "contentHash", "updatedAt", "visibility", "sourceRef", "subscriptionMode")

    /**
     * `encodeDefaults = true` and `explicitNulls = false` are load-bearing (see class doc). The
     * discriminator matches [CatalogCodec] so a catalog file serialises with `"kind":"provider"`.
     */
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = CATALOG_DISCRIMINATOR
    }

    /** The canonical UTF-8 bytes of [value] — the input to [contentHash]. */
    fun <T> canonicalBytes(value: T, serializer: KSerializer<T>): ByteArray =
        canonicalString(value, serializer).toByteArray(Charsets.UTF_8)

    /** The canonical string of [value] — used for the v3 file body (byte-reproducible, §5.4). */
    fun <T> canonicalString(value: T, serializer: KSerializer<T>): String {
        val tree = json.encodeToJsonElement(serializer, value)
        return canonicalize(stripEnvelope(tree))
    }

    /** Remove [ENVELOPE_FIELDS] from the top object only; leave arrays/primitives/nested objects. */
    private fun stripEnvelope(element: JsonElement): JsonElement =
        if (element is JsonObject) {
            JsonObject(element.filterKeys { it !in ENVELOPE_FIELDS })
        } else {
            element
        }

    private fun canonicalize(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            // Kotlin's natural String order compares UTF-16 code units — exactly JCS's key order.
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${encodeString(key)}:${canonicalize(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalize(it)
        }
        is JsonPrimitive -> canonicalizePrimitive(element)
    }

    private fun canonicalizePrimitive(primitive: JsonPrimitive): String = when {
        // Cannot normally occur (explicitNulls = false drops nulls); handled for totality.
        primitive is JsonNull -> "null"
        primitive.isString -> encodeString(primitive.content)
        // Booleans and integers: the raw token is already canonical (`true`/`false`, plain decimal
        // with no leading zeros/plus/exponent). No floating-point values exist in the model (§5.1).
        else -> primitive.content
    }

    /**
     * JSON string with RFC 8259 §7 minimal escaping: only the mandatory escapes (`" \ \b \f \n \r
     * \t`) plus `\u00xx` (lowercase, per JCS) for the remaining control chars < 0x20. Non-ASCII
     * (umlauts, emoji) stays literal UTF-8 — no unnecessary `\u` escapes.
     */
    private fun encodeString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c.code) {
                0x22 -> sb.append("\\\"")   // "
                0x5C -> sb.append("\\\\")   // backslash
                0x08 -> sb.append("\\b")
                0x0C -> sb.append("\\f")
                0x0A -> sb.append("\\n")
                0x0D -> sb.append("\\r")
                0x09 -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
