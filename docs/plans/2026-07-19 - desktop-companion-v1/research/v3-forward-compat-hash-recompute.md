# Repair Research — v3 forward-compat hash recompute

**Date:** 2026-07-20T00:40:00+02:00
**Triggered by:** Finding `logic-C-1` [Important] — `CatalogImport.importV3` recompute check defeats the `ignoreUnknownKeys` forward-compat mechanism on the SAF file-import path.
**Agent-ID:** repair-research / v3-forward-compat-hash-recompute

## Sources

1. `shared/src/main/kotlin/net/devemperor/dictate/shared/config/CatalogCodec.kt` — `json { ignoreUnknownKeys = true }` (L68-71) with the documented forward-compat intent (L64-67); `decode` (L80-91) drops unknown keys into a typed `CatalogFileV3`.
2. `app/src/main/java/net/devemperor/dictate/config/CatalogImport.kt` — the §5.3 recompute check (L81-109): `contentHash(entry.entity, …serializer())` over the **decoded** object, compared to the carried hash; mismatch → `Result.Invalid("contentHash mismatch (corrupt or tampered file) …")`.
3. `shared/src/main/kotlin/net/devemperor/dictate/shared/config/CanonicalJson.kt` — canonical form: `canonicalString(value, serializer)` (L69-72) = `canonicalize(stripEnvelope(encodeToJsonElement(...)))`; `stripEnvelope` strips `ENVELOPE_FIELDS` (incl. `contentHash`) from the **top object only** (L51-52, L74-80); `canonicalize`/`stripEnvelope` are `private`.
4. `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ContentHash.kt` — `contentHash(value, serializer)` = sha256-hex of `CanonicalJson.canonicalBytes`.
5. Spec `research/entitaetenmodell-android.md` §5.3 (L680-694, the recompute-and-compare integrity invariant) and §5.4 (L723-731, "der Export-Datei-Body wird über `CanonicalJson` erzeugt (byte-reproduzierbar); … sodass ein Empfänger sie **unabhängig nachrechnen** kann").
6. `shared/.../CatalogCodecTest.kt::decode_unknownAdditiveField_isTolerated` (L74-83) and `app/.../CatalogImportExportTest.kt::v3Import_rejectsTamperedContentHash` (L189-196) — the two guarantees the fix must simultaneously satisfy.

## Findings

### Root cause — the recompute hashes the *lossy typed projection*, not the *file bytes*

The forward-compat contract (codec doc L64-67, spec §5.4) is: a newer writer may add a payload field; an older reader tolerates it (`ignoreUnknownKeys`) and can still **independently recompute** each entity's `contentHash`. Those two clauses are only mutually consistent if the reader recomputes over the *bytes the writer hashed*, which include the unknown field.

`CatalogImport.importV3` breaks the second clause. It recomputes over the **decoded** typed entity:

```
writer:  contentHash = sha256( canonical( payload_with_futureField ) )   → carried in file
reader:  decode drops futureField  →  entity_without_futureField
         recomputed = sha256( canonical( entity_without_futureField ) )  ≠ carried
         →  Result.Invalid("contentHash mismatch (corrupt or tampered file)")
```

The decode step (`ignoreUnknownKeys`) is exactly what discards the field before the hash sees it. So the recompute can *never* reproduce a hash that a newer writer computed over a superset payload. A perfectly valid, untampered file from a newer app version is rejected as "corrupt or tampered."

### Scope — dormant now, live on the first additive field (the feature's core scenario)

Within Block C the payload schema is single-version, so `decode` drops nothing and `recomputed == carried` for every file (all current tests pass). The defect activates the first time any of the five payload DTOs gains an additive field and a newer writer ships it — i.e. the cross-version file-sharing scenario the v3 format exists for. `CatalogCodecTest::decode_unknownAdditiveField_isTolerated` gives false confidence: it carries **no** `contentHash`, so `importV3`'s `carried.isNotEmpty()` guard (L105) skips the check and the mismatch is never exercised. The bug lives one layer *above* the codec test, on the DB-backed import path.

### Why "just drop the check" is the wrong fix

Spec §5.3 mandates the recompute-and-compare at import ("bei Abweichung → Warnung/Ablehnung … Block E verschärft das für Peers"), and `v3Import_rejectsTamperedContentHash` guards it. The check is cheap corruption/tamper detection and must survive. (Note: it is *not* what keeps the stored hash correct — `ConfigRepository` recomputes on every upsert per the recompute-on-write invariant — but removing the import check silently drops the tamper signal the spec asks for and a test pins.) So the fix must **keep** rejecting a tampered file while **accepting** an additive-field file.

### The fix — recompute from the raw file bytes, not the decoded object

Because the file body is produced by `CanonicalJson.canonicalString(file, …)`, every entity payload sits in the file already in canonical form *with its envelope fields present* (envelope stripping happens only on the top `CatalogFileV3` object, never on nested payloads). Re-parsing that sub-object into a `JsonElement` and running it through `stripEnvelope` + `canonicalize` reproduces **exactly** the bytes the writer hashed — including any unknown additive field, because unknown keys are not in `ENVELOPE_FIELDS` and `JsonElement` parsing is schema-less (it drops nothing). This is idempotent (`canonicalize` of an already-canonical, re-parsed element is a fixpoint), so same-version files keep matching, and a tampered payload value still produces a different hash → still rejected.

This also fixes it *once* for Block E: when the peer path adds its own recompute (§5.3 "Block E verschärft das für Peers"), reusing the same raw-based helper keeps peer sync forward-compatible instead of re-introducing this exact bug on the cross-version wire.

## Implementation Hints

Three small edits; no behaviour change for same-version files, tamper detection preserved.

### 1. `CanonicalJson.kt` — expose a canonical form for an already-parsed element

`canonicalString(value, serializer)` first-serialises then canonicalises. Add a sibling that takes a `JsonElement` (the raw file's payload sub-object) and applies the *same* `stripEnvelope` + `canonicalize`. This keeps `CanonicalJson` the single source of truth for the canonical form — do **not** re-implement canonicalisation in `CatalogImport`.

```kotlin
import kotlinx.serialization.json.JsonElement   // already partially imported

/**
 * Canonical string of an ALREADY-PARSED element — the raw-bytes path used to verify a carried
 * `contentHash` without a lossy typed round-trip (forward-compat: unknown additive keys survive,
 * because a JsonElement drops nothing on parse). Same envelope-strip + key-sort as
 * [canonicalString]; idempotent on an element that is itself canonical.
 */
fun canonicalString(element: JsonElement): String = canonicalize(stripEnvelope(element))
```

(`canonicalize`/`stripEnvelope` stay `private`; only this thin public overload is added.)

### 2. `ContentHash.kt` — a hash-from-element companion

```kotlin
import kotlinx.serialization.json.JsonElement

/**
 * `contentHash` of an already-parsed payload element — same digest as [contentHash], but over the
 * RAW file bytes so a superset payload from a newer writer verifies (forward-compat, §5.4).
 */
fun contentHashOfElement(element: JsonElement): String {
    val bytes = CanonicalJson.canonicalString(element).toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
```

Keep the `and 0xFF` mask (ContentHash.kt L22-24 explains why it is load-bearing). Optionally factor the digest→hex tail shared with `contentHash` into a private helper to stay DRY.

### 3. `CatalogImport.importV3` — recompute from the raw tree, drop the typed recompute

Replace the whole typed `mismatches` block (L82-109) with a raw-tree walk. After `decode` has already *validated* structure (so `entities` is present and every element has an `entity` object), parse the raw file once more into a `JsonElement` and hash each payload from it:

```kotlin
// §5.3 integrity check — recompute each carried contentHash from the RAW file bytes (NOT the
// decoded object), so an additive field from a newer writer survives into the hash and a valid
// cross-version file is accepted, while a tampered payload value still mismatches. (finding logic-C-1)
val mismatches = lenientJson.parseToJsonElement(raw)
    .jsonObject["entities"]!!.jsonArray
    .mapNotNull { entry ->
        val payload = entry.jsonObject["entity"]!!.jsonObject
        val carried = payload["contentHash"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val id = payload["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (carried.isNotEmpty() && carried != contentHashOfElement(payload)) id else null
    }
if (mismatches.isNotEmpty()) {
    return Result.Invalid("contentHash mismatch (corrupt or tampered file) for: ${mismatches.joinToString()}")
}
```

Notes for the fix agent:
- Add imports: `kotlinx.serialization.json.jsonArray`, `kotlinx.serialization.json.contentOrNull`, and `net.devemperor.dictate.shared.config.contentHashOfElement`. Remove the now-unused per-kind `contentHash(entry.entity, …serializer())` imports/uses and the five-branch `when` if nothing else needs it.
- The `!!` on `["entities"]`/`["entity"]` is safe: `decode` returned `Ok`, which means the typed `CatalogFileV3.serializer()` bound every `entity`; a malformed shape would already have returned `Malformed` above. A defensive `?: return Result.Malformed(...)` is acceptable but not required.
- Reuse the existing `lenientJson` field (L58) — no need for a second `Json`. `parseToJsonElement` ignores `ignoreUnknownKeys` (it is schema-less) and preserves every key.
- The typed `file.entities` is still used for the upsert loop below — leave that untouched. Only the hash-check source changes.

### 4. Regression tests (write red first, per the repo's regression-test rule)

- **New — app path (the real bug):** in `CatalogImportExportTest`, add `v3Import_acceptsAdditiveFieldFromNewerWriter`. Encode a `fullCatalog`, then for one entity inject an unknown payload field AND set that entity's `contentHash` to `contentHashOfElement` of the *modified* payload (simulating a newer writer that hashed the superset). Re-serialise and `importV3` → assert `Result.V3Imported`, not `Invalid`. On the unfixed code this fails with a hash mismatch; on the fix it passes. Keep `v3Import_rejectsTamperedContentHash` green (it changes a value *without* updating the hash → still mismatches).
- **Optional — shared unit:** in `ContentHashTest`/`CanonicalJsonTest`, assert `contentHashOfElement(parse(canonicalString(entity, serializer)))` equals `contentHash(entity, serializer)` for a representative entity (the idempotence/equivalence that makes same-version files keep matching), and that adding an unknown key changes the element hash (so tamper detection is intact at the primitive level).

### Block E follow-up (out of scope here, note in the fix report)

When the peer-sync path (Block E) adds its §5.3 recompute, it MUST call the same `contentHashOfElement` raw-based helper. Recomputing over the decoded typed object there would re-introduce this identical forward-compat break on the cross-version wire — the very scenario peer sync targets.

## References

- Finding `logic-C-1` (this repair topic).
- Spec `research/entitaetenmodell-android.md` §5.3 (recompute-on-write / import integrity), §5.4 (byte-reproducible file body, independent recompute), §10.4 (import dispatcher).
- `CatalogCodec.kt` L64-71 (forward-compat intent), `CanonicalJson.kt` L47-80 (`ENVELOPE_FIELDS`, `stripEnvelope`, `canonicalize`), `ContentHash.kt` L20-27.
- Tests: `CatalogCodecTest::decode_unknownAdditiveField_isTolerated`, `CatalogImportExportTest::v3Import_rejectsTamperedContentHash`.
- ADR-0016 (typed-DTO codec / DecodeResult pattern the config layer mirrors).
