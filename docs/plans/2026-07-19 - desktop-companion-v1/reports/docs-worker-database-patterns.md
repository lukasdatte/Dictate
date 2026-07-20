# Doc Worker Report — database-patterns

**Date:** 2026-07-20T17:25:00+02:00
**Action:** update
**Target:** `docs/DATABASE-PATTERNS.md`
**Agent:** doc-worker (finalize)
**Range:** `c46cfe8..HEAD`

## Summary

Verification pass against the final shipped schema (Block F had already
refreshed the SQLDelight-parity section). Found one genuine staleness: the
Android-side **Double-Enum → "Applied columns" table** stopped at schema v11
(`prompts.type`) and did not cover the v12/v13 tables the plan added. Extended
it; the rest of the doc was verified accurate and left untouched.

## Verification results (no change needed)

- **SQLDelight Parity (Companion) section** — accurate against final code:
  - Migration-number table (`2.sqm` D1a / `3.sqm` D3 config-entity / `4.sqm` E1
    peers·subscriptions·catalog_access_log) matches `migrations/*.sqm` (1–4.sqm
    present; DB snapshots `1.db`–`5.db`, current schema v5).
  - All four cited parity-test paths exist: `CompanionSchemaParityTest.kt`,
    `ConfigEntityCheckParityTest.kt`, `CatalogCheckConstraintParityTest.kt`,
    `CompanionConfigWireEnumParityTest.kt` (+ `RoomParityReference.kt`).
  - `received_texts` retirement / `dispatch_state` claim unchanged (D1a scope,
    not in this worker's source set — left as Block-F authored).
- **Room DB version** = 13 (`DictateDatabase.kt`), schema exports through
  `13.json` — consistent with the two new migrations.
- **Denormalized Cache Columns**, **Migration Conventions** (data-preservation
  rule), **Versioning** placeholder — unaffected by this range.

## Changes applied

Section: **Double-Enum Pattern → "Applied columns (as of now)"**

- Appended the v12/v13 Double-Enum columns to the table:
  - `provider_configs.provider_type` (`ProviderType`), `.kind` (`ProviderKind`)
  - `api_credentials.provider_type` (`ProviderType`)
  - `model_refs.function` (`ModelFunction`)
  - `profiles.style_prompt_mode`, `.system_prompt_mode` (`PromptSelectionMode`),
    `.ambiguity_mode` (`AmbiguityModeValue`)
  - one consolidated row for the `visibility` + `subscription_mode` envelope
    columns shared by all six shareable tables (`Visibility`,
    `SubscriptionMode`) — avoids 12 repetitive rows
  - `subscriptions.kind` (`CatalogEntityKindWire`), `.mode` (`SubscriptionMode`,
    noting the **subset** CHECK `SUBSCRIBE`/`ONE_SHOT`)
  - Enum home / location column points at `shared/config/ConfigEnums.kt` and
    `shared/protocol/Dtos.kt` (verified the enums are declared there, not in
    per-enum files).
- Added a blockquote note explaining **why** these enums live in `:shared`
  rather than `app`'s `database/entity/` (cross-platform canonical enums whose
  CHECK literals + parity tests are shared with the companion), cross-linked to
  the SQLDelight-Parity section; clarifies the Room columns are still
  `String` + `xxxEnum` accessor (`ConfigRoomEntities.kt`, `PeerRoomEntities.kt`).
- Updated the "Columns that should be retrofitted … *None.*" paragraph: kept the
  three historical retrofits and added that v12/v13 extended the pattern to every
  new finite-set column from the outset (no retrofit debt incurred).

Values (enum vocabularies, CHECK subset, table/column names) cross-checked
against `MigrationTo12.kt`, `MigrationTo13.kt`, `ConfigRoomEntities.kt`,
`PeerRoomEntities.kt`, `3.sqm`, `4.sqm`, and `ConfigEnums.kt`.

## Notes for final

- **api_credentials asymmetry (not stale, possible clarity note):** the Android
  Room schema has an `api_credentials` table (now listed in the Applied-columns
  table); the companion SQLDelight schema deliberately has **no**
  `api_credentials` table — the Parity section correctly lists only
  `provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts` as the
  companion mirror. This is by design (credentials handled differently on
  desktop), so no contradiction, but a future editor might add one sentence to
  the Parity section making the intentional omission explicit if desired.
- No cross-doc link changes required; the anchor `#sqldelight-parity-companion`
  resolves within the same file.

## Files touched

- `docs/DATABASE-PATTERNS.md`
