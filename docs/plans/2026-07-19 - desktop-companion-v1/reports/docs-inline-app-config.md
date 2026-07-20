# Inline-Anchor Worker Report — `app-config`

**Slug:** app-config
**Target:** `app/src/main/java/net/devemperor/dictate/config/`
**Timestamp:** 2026-07-20T17:25:00+02:00
**Plan range:** c46cfe8..HEAD

## Summary

The `app/config/` group (16 new `.kt` files) was already strongly anchored by the
implementation waves: every non-trivial file carries a module/object header
(responsibility + non-obvious patterns + invariants), gotcha comments where the code
obeys a protocol/hash quirk (e.g. the §5.3 recompute-on-raw-bytes note in
`CatalogImport`, the F12 "fingerprint only, never the key" note in `ConfigRoomEntities`),
and — for 13/16 — a formal `@see` spec/ADR tag. The discovery report classified this
group as "good — mostly anchored (spot-check gotchas)". My pass made two surgical
corrections; no new headers or gotchas were warranted.

## Anchors updated

### 1. Stale-path fix — dangling §8.5.1 → §8.5

The spec (`research/entitaetenmodell-android.md`) has no §8.5.1 sub-section; the
prompt-backfill hash-coverage rule ("der Hash deckt nur name/text/flags") lives in
**§8.5** point 1 (verified against spec lines 985–1002). Two files cited the
non-existent §8.5.1:

| File | Change |
|---|---|
| `PromptHashing.kt` | header prose `(spec §8.5.1)` → `(spec §8.5)`; `@see … §8.5.1` → `@see … §8.5` |
| `ConfigEntityMigration.kt` | prose `(§8.5.1)` → `(§8.5)` in `backfillPromptsAndCollect` KDoc |

### 2. Missing formal `@see` tag added (3 files)

The other 13 files in this directory uniformly follow the house pattern *prose section
ref in the header + a trailing consolidating `@see` tag*. Three files named their spec
sections only in prose and lacked the sanctioned `@see` tag; added to bring the group to
uniform 16/16 coverage. Targets verified to exist in the spec.

| File | Added `@see` | Decision point |
|---|---|---|
| `ConfigSecrets.kt` | §7.2 | SecretRef `"credential"` namespace addressing convention |
| `PromptProvenance.kt` | §7.3, §8.5 | v12 shareable-identity columns kept correct at Android write seams |
| `SourceRefMapping.kt` | §7.1, §10.5 | shared `SourceRef` null-guard (mapper + export) |

## Skips (no anchor warranted)

- `SourceRefMapping.kt` is a single top-level `internal fun`, not a service/module — the
  existing header is appropriate; no separate class header needed.
- `dao/ConfigDaos.kt`, `entity/ConfigRoomEntities.kt`, `ConfigEntityMapper`,
  `ConfigRepository`, `ConfigEntityMigration`, `ConfigEntitySetup`, `ActiveProfile`,
  `CatalogExport`, `CatalogImport`, `ConfigWireMapping`, `PrefsBackup`,
  `ProfileListMutations`: headers + `@see` + gotchas already present, resolve, and
  match the plan. No change.
- No comment noise removed (none found) and no logic-restating comments present.

## Verification

- `grep '8\.5\.1'` over the group → **no matches** (dangling ref eliminated).
- All `@see` targets (§4.5, §4.8, §5.3, §7.1–§7.4, §8, §8.4, §8.5, §8.6, §9, §9.2,
  §9.4, §10, §10.1, §10.3–§10.5, §13 D3/D4) confirmed as extant headings/points in
  `entitaetenmodell-android.md`.
- ADR-0024 and ADR-0030 exist under `docs/decisions/`; ADR-0024 refs in
  `CatalogExport`/`CatalogImport`/`PromptHashing` resolve.
- `git diff` confirms all 5 edits are KDoc-comment-only; no code, imports, or
  formatting touched.

## Files outside assigned scope (drift)

None.

## Notes for final (bugs / name-smells noticed — not edited)

None.
