package net.devemperor.dictate.shared.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Rebuild a config-entity DTO from a verified catalog payload — the one place the subscriber side turns
 * a pulled payload back into a local copy (peer-katalog.md §6.2).
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop companion
 * (ADR-0015). It lives here, not in each host's store, for the same reason [CanonicalJson] does: the
 * graft is hash-critical (a per-platform copy could drift the two sides' reconstruction and break the
 * Block E fork-dedup / drift-detection), so both `AndroidCatalogSubscriberStore` and
 * `SqlDelightCatalogSubscriberStore` call THIS.
 *
 * ## What a graft is
 *
 * The wire [payload] is the source entity's canonical serialization — **envelope-stripped** by
 * construction ([CanonicalJson.canonicalString] removes [CanonicalJson.ENVELOPE_FIELDS] before
 * emitting). So it carries only the hash-relevant *payload* half (a prompt's `name`/`text`, a
 * provider's `label`/`baseUrl`, …) and none of the envelope (`id`, `visibility`, `sourceRef`,
 * `subscriptionMode`, …). [graft] keeps [existing]'s envelope — the copy's LOCAL identity and
 * provenance stay put — and replaces its payload half with the source's:
 *
 * ```
 *   result = existing.envelope  ⊕  source.payload
 * ```
 *
 * `contentHash`/`updatedAt` are envelope fields too, so they come from [existing] here; the caller's
 * repository `save()` recomputes them on write (the denormalised-cache rule, [contentHash]). Because
 * the payload half is byte-for-byte the source's and the hash is payload-only, the recomputed
 * `contentHash` equals the source's verified `contentHash` — which is exactly why a subscribed copy
 * mirrors its source's hash even though its local `id` may differ.
 *
 * Defensive: any envelope-named key that somehow rode along inside [payload] is dropped, never allowed
 * to overwrite the local envelope — a malicious/broken peer cannot rewrite our `id` or provenance
 * through the payload. (Verify-before-write already re-canonicalizes and would reject such a payload;
 * this is belt-and-suspenders.)
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §6.2
 * @see docs/decisions/0034-peer-catalog.md
 */
object CatalogPayloadGraft {

    /**
     * [existing] with its payload half replaced by [payload]'s. [serializer] is the DTO's serializer
     * (e.g. `PromptV3Entity.serializer()`). The result is ready to hand to the host repository's
     * `save()`, which recomputes `contentHash` + stamps `updatedAt`.
     */
    fun <T> graft(existing: T, serializer: KSerializer<T>, payload: String): T {
        val envelope = CanonicalJson.json.encodeToJsonElement(serializer, existing).jsonObject
            .filterKeys { it in CanonicalJson.ENVELOPE_FIELDS }
        val payloadFields = CanonicalJson.json.parseToJsonElement(payload).jsonObject
            .filterKeys { it !in CanonicalJson.ENVELOPE_FIELDS }
        return CanonicalJson.json.decodeFromJsonElement(serializer, JsonObject(envelope + payloadFields))
    }
}
