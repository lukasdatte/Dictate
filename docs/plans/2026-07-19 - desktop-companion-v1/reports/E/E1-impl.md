# E1 — Katalog-Protokoll + Server-Seite — Implementation Report

**Chunk:** E1 (Block E) · **Date:** 2026-07-20 · **Plan:** desktop-companion-v1

## What I did

Shipped the peer-catalog **wire family** (`:shared`: catalog DTOs + `supportsCatalog`
health flag + Konform validations + `CatalogClient`, all additive, no `ProtocolVersion`
bump) and the **companion server side** (`CatalogService` with deterministic root-hash,
three authenticated `/v1/catalog*` routes, credential delivery gated by a separate call
that writes an audit row, `SqlDelightCatalogRepository`/`SqlDelightCatalogAuditLog`).
Added the E1-owned schema `peers`/`subscriptions`/`catalog_access_log` as **Migration
`4.sqm`** (v4→v5) — the entity tables stay D3's per D7. All 5 acceptance criteria (AC1–AC5)
are covered by unit + real-HTTP E2E tests.

## Acceptance criteria → evidence

| AC | Criterion | Where proven |
|----|-----------|--------------|
| AC1 | Additivity: `supportsCatalog=false` default, bare 404 → `EndpointMissing`, `ProtocolVersion.CURRENT` stays 1 | `CatalogClientTest.index_bare404_isEndpointMissing_*`, `CatalogE2ETest.health_reportsCatalogSupport_*` / `aCompanionWithoutTheCatalogService_*` |
| AC2 | Konform completeness: every catalog DTO has a `Validation<T>` + a violation case | `ValidationsTest` (index/entity/credential valid + rejected cases) |
| AC3 | Root-hash determinism: same set→same, reorder→same, one change→new | `CatalogServiceTest.rootHash_*` |
| AC4 | Auth-parity: index/entity/credential need a valid device secret (401 else) | `CatalogE2ETest.catalogIndex_withoutPairing_*`, `catalogEntityAndCredential_withoutPairing_*` |
| AC5 | Credential isolation + audit line per delivery | `CatalogServiceTest.credential_delivers*`, `index_carriesTheCredentialButNotItsSecret`, `CatalogE2ETest.credential_reachesTheClientOnlyViaTheCredentialCall_andIsAudited` |

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| No `catalog/` package with `VisibilityWire`/`SubscriptionModeWire` | spec §10, §5.2/§5.3 | Reused `:shared.config.Visibility`/`SubscriptionMode` for `subscriptions.mode` (subset CHECK `SUBSCRIBE`/`ONE_SHOT`); `subscriptions.kind`/`access_log.kind` use the new `:shared.protocol.CatalogEntityKindWire` | D3 already unified on `config.*` enums and **explicitly rejected** the parallel `catalog.*Wire` copies (Companion.sq header lines 191-196). Re-introducing them would re-create exactly the drift D3 removed | E2 reads `subscriptions.mode` as `config.SubscriptionMode` — consistent with the entity tables | ✅ yes (D4 alignment with D3) |
| Entity-tables → `peers` FK (`source_peer_id ... ON DELETE SET NULL`) **deferred to E2** | spec §5.2; D3 note "cascade is E1's to add" | `peers` table created; FK not added (would need a 4-table recreate migration) | Not required by any E1 AC; the 4 entity tables are provenance-empty until E2, which owns both the provenance writes AND the peer-deletion cascade — so E2 adds the FK safely on still-empty tables. D3's own note: "E2's sync/delete code path is the referential-integrity authority (no non-NULL source_peer_id is written before E2)" | E2 must add the FK (table-recreate) when it starts writing provenance / deleting peers | ⚠️ deferred to E2 — see issue E1-1 |
| `CatalogEntry.label` cap widened from `MAX_DEVICE_NAME_LENGTH` (64) to `MAX_CATALOG_LABEL_LENGTH` (200) | spec §3.3 sketch used `MAX_DEVICE_NAME_LENGTH` | New `Endpoints.MAX_CATALOG_LABEL_LENGTH = 200` used in the label validation | The label IS a config entity's name/label (`ConfigValidations.MAX_LABEL`/`MAX_NAME` = 200); the 64-cap would make `index()` encode throw a 500 on a legitimately long-named shared entity | none (wider bound, still validated) | ✅ yes — regression test `CatalogServiceTest.index_toleratesALongEntityName_*` |
| `CatalogAuditLog` modelled as port (`domain/port`) + impl (`data/SqlDelightCatalogAuditLog`) | spec §10 lists `domain/CatalogAuditLog.kt` | Interface in `domain/port`, SqlDelight impl in `data/` | Matches the repo's domain/data separation (DB access lives in `data/`, ports in `domain/port`) and keeps `CatalogService` unit-testable against a fake | none | ✅ yes (D4) |
| `CatalogClient` returns `DispatchResult`/`DispatchError` (added `EntityGone`), not a parallel `CatalogError`/`CatalogResult` | spec §3.5 NOTE + AC1/§6.4 name `CatalogError.*` | Reused the generic result/error; extracted shared response plumbing into `WireResponse.kt` | The `DispatchResult` KDoc explicitly warns against a parallel result family; the NOTE itself says "reuse `DispatchResult<T>`/`DispatchError`" | E2's sync engine consumes the same `DispatchError.EntityGone` | ✅ yes |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| E1-1 | Important | Entity-tables → `peers` FK (`ON DELETE SET NULL`) not yet added; deferred to E2 which owns provenance writes + peer deletion. Until then referential integrity of `source_peer_id` is a code invariant (no non-NULL value written pre-E2), matching D3's header note. `companion/.../db/Companion.sq` (peers section) / `migrations/4.sqm` | delegated | plan-deviation-resolved |

## Inline fixes applied

- `app/.../windows/DispatchOutcomeMapper.kt`: added the `DispatchError.EntityGone` arm (exhaustive `when` over the sealed class would otherwise not compile). Mapped to `WINDOWS_UNREACHABLE` for completeness — a dictation dispatch never produces a catalog-only error.
- `companion/.../data/ChordMigrationSeedTest.kt`: added `catalog_access_logAdapter` to the hand-built `DictateCompanionDb(...)` constructor (the new enum-column table requires an adapter now).

## Helper decisions

- **Extracted `shared/.../client/WireResponse.kt`** (internal `classifyWireError` / `parseWire` / `describeWire`) shared by `DispatchClient` and `CatalogClient` — `DispatchClient.classifyError` had to change anyway (new `ErrorCode.CATALOG_ENTITY_NOT_FOUND` breaks its exhaustive `when`), so the classification became one implementation instead of two.
- **New `companion/.../fakes/FakeSecretStore.kt`** (reusable in-memory `SecretStore`) — a private copy existed only inside `ProfileBackedAiConfigTest`; the catalog tests need it too. Left the private copy untouched (out of scope).
- **Reused** `CompanionConfigRepository` (D3) as the read seam for `SqlDelightCatalogRepository` rather than new SQL; `CanonicalJson.canonicalString` for the entity payload (byte-identical to the stored `contentHash`); `Secrets.sha256` for the root hash.

## Files modified

Primary (`:shared`): `protocol/Endpoints.kt`, `protocol/Dtos.kt`, `protocol/Validations.kt`,
`protocol/ErrorEnvelope.kt`, `client/DispatchError.kt`, `client/DispatchClient.kt`;
new: `client/CatalogClient.kt`, `client/WireResponse.kt`.

Primary (`companion`): `server/CompanionServer.kt`, `domain/HealthService.kt`,
`domain/DomainErrors.kt`, `server/plugins/StatusPagesSetup.kt`, `CompanionContainer.kt`,
`data/CompanionDatabase.kt`, `db/Companion.sq`; new: `domain/CatalogService.kt`,
`domain/port/CatalogEntityRepository.kt`, `domain/port/CatalogAuditLog.kt`,
`data/SqlDelightCatalogRepository.kt`, `data/SqlDelightCatalogAuditLog.kt`,
`server/routes/CatalogRoutes.kt`, `db/migrations/4.sqm`, `db/databases/5.db`.

Tests: `shared/.../ValidationsTest.kt`, new `shared/.../client/CatalogClientTest.kt`,
new `companion/.../domain/CatalogServiceTest.kt`, new `companion/.../server/CatalogE2ETest.kt`,
new `companion/.../data/CatalogCheckConstraintParityTest.kt`,
new `companion/.../fakes/FakeSecretStore.kt`, `companion/.../data/ChordMigrationSeedTest.kt`.

**Out-of-scope (drift):**
- `app/.../windows/DispatchOutcomeMapper.kt` — mandatory exhaustive-`when` fix for the new `DispatchError.EntityGone` variant.
- `companion/.../data/ChordMigrationSeedTest.kt` — mandatory adapter arg for the new `catalog_access_log` table.

## Test-run result

- `./gradlew :shared:test` — green.
- `./gradlew :companion:test --rerun-tasks` — green (60 classes, 408 test methods, 0 failures).
- `./gradlew :companion:verifyMainDictateCompanionDbMigration` — green (4.sqm replays byte-identical to Companion.sq / 5.db).
- `./gradlew :app:testDebugUnitTest` — green; `:app:compileDebugKotlin` — green.
