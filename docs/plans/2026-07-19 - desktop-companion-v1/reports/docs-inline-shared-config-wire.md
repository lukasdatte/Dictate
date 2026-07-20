# Inline-Anchor Report — `shared-config-wire`

**Date:** 2026-07-20T17:25:00+02:00
**Agent:** doc-worker-inline (`shared-config-wire`)
**Scope:** `shared/src/main/kotlin/net/devemperor/dictate/shared/{config,protocol,client,sync}/` (21 `.kt` files)

## Summary

The group was already exceptionally well anchored: every file carries a strong 3–8
line module/class header (responsibility + non-obvious patterns + invariants), gotcha
comments where the code obeys a non-guessable constraint (e.g. the `and 0xFF`
sign-extension mask in `ContentHash.kt`, the `{value}` log-redaction rule in
`Validations.kt`), and inline ADR references in prose (`ADR-0015`/`0016`/`0017`/
`0019`/`0020`). The `config/` sub-group additionally carries trailing
`@see entitaetenmodell-android.md §X` tags.

The one genuine gap: the **five new peer-catalog family files** referenced
`peer-katalog.md §X` only in prose and referenced **none** of the promoted, accepted
`ADR-0034` (peer-catalog) — despite ADR-0034 being the load-bearing decision record
for the entire subsystem. Their `config/` siblings already establish the trailing-`@see`
convention. Added the missing anchor (spec § + ADR-0034) to exactly those five files.

## Anchors added

| File | Anchor added | Symbol |
|---|---|---|
| `config/CatalogPayloadGraft.kt` | `@see peer-katalog.md §6.2` + `@see 0034-peer-catalog.md` | `object CatalogPayloadGraft` |
| `client/CatalogClient.kt` | `@see peer-katalog.md §3.5` + `@see 0034-peer-catalog.md` | `class CatalogClient` |
| `sync/CatalogSubscriberStore.kt` | `@see peer-katalog.md §6` + `@see 0034-peer-catalog.md` | `interface CatalogSubscriberStore` |
| `sync/CatalogSyncEngine.kt` | `@see peer-katalog.md §6` + `@see 0034-peer-catalog.md` | `class CatalogSyncEngine` |
| `sync/NotificationPort.kt` | `@see peer-katalog.md §7` + `@see 0034-peer-catalog.md` | `interface NotificationPort` |

Each anchor is a two-line `@see` block appended inside the existing class/object KDoc
header (spec § first, then ADR). Section numbers verified against the live spec
headings; both targets confirmed to exist on disk.

## Anchors updated / removed

None. No stale paths, no comment-noise (code-restating) comments found in the group.

## Skips (intentional — no anchor needed)

- **`protocol/` files** (`Dtos.kt`, `Endpoints.kt`, `ErrorEnvelope.kt`, `Validations.kt`,
  `ProtocolCodec.kt`, `ProtocolVersion.kt`) — pre-existing wire module, not introduced by
  this plan. Already carry their governing `ADR-0016` in the header and inline
  `peer-katalog.md §3.x` markers **at each catalog DTO/validation group** (the sanctioned
  inline form). Adding a header-level `@see ADR-0034` would duplicate the per-DTO markers
  and read as noise; left untouched.
- **`config/` codec + hash files** (`CanonicalJson.kt`, `CatalogCodec.kt`, `ContentHash.kt`,
  `ConfigEnums.kt`, `ConfigValidations.kt`, `Entities.kt`) — already carry
  `@see entitaetenmodell-android.md §X` (their governing spec, ADR-0030 territory), strong
  headers, and dated/justified gotchas. Nothing missing.
- **`client/` + `sync/` non-catalog files** (`DispatchClient.kt`, `DispatchError.kt`,
  `WireResponse.kt`, `Cursor.kt`, `SyncClient.kt`, `SyncSource.kt`) — governed by ADR-0015/
  0019/0020, already referenced inline; headers complete. Not peer-catalog scope.

## Files modified

- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/CatalogPayloadGraft.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/client/CatalogClient.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/CatalogSubscriberStore.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/CatalogSyncEngine.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/NotificationPort.kt`

**Out-of-scope (drift) edits:** none.

## Self-check

- Every added `@see` resolves: `peer-katalog.md` present with §3.5/§6/§6.2/§7 headings
  confirmed; `docs/decisions/0034-peer-catalog.md` present and `Status: Accepted`.
- No code logic, imports, or formatting touched — each file's diff is exactly +3 lines
  (blank comment separator + two `@see` lines) inside the existing KDoc block.
- No comment noise added; no code-restating comments found to remove.

## Notes for final (bugs / name-smells — not edited)

None. The group is internally consistent; naming and layering are clean.
