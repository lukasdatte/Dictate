# D3 — Self-Fix (fresh eyes, diff-based)

**Chunk:** D3 · **Date:** 2026-07-20 · **Wave commit:** cc365ad · **Reviewer:** chunk-self-fix agent

## What I did

Fresh-eyes review of the D3 diff (review panel + re-dictate, config-entity tables, profile/model/prompt
management UI) across the three lenses. Verified the implementer's five deviations against the actual
C2 sources, applied one code-quality cleanup inline, and closed one untested correctness branch with a
new focused test. Full `:companion:test` + `verifySqlDelightMigration` green after the fixes.

## Verification of the implementer's deviations (all defensible)

- **Enum reuse (`:shared.config.*` instead of the spec's `catalog.*Wire`).** Confirmed against Room
  schema `12.json`: C2's `provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts` use the
  same envelope columns (`source_original_id`/`source_original_hash`) and the `:shared.config` enums.
  No `catalog` package exists; `ConfigEnums.kt` explicitly carries these as the shared wire enums.
  Parity-by-construction holds — the deviation is correct and better than a duplicate enum + drift test.
- **`content_hash` recompute-on-write.** Confirmed C2 `ConfigRepository` recomputes on every write
  ("never trusted from the wire"); the companion repo matches. The §5.2 DDL comment ("stored, not
  recomputed") concerns the *incoming-from-peer* sync case (Block E), not local create/edit. Consistent.
- **Omitted `peers` FK** — justified by the E1→D3 build ordering (SQLDelight validates FK targets at
  compile time). **`api_credentials` not mirrored** — matches the explicit 5-table §5.2/D5.b list.
  **`ConfigProfileSource` resolves only `AmbiguityMode`** — documented partial, the axis §8 needs is wired.
- **`ResponseFormatKind.valueOf(result.responseFormat.name)`** (continuation path) — verified both enums
  (`:shared-ai` and companion `domain.session`) carry the identical 3 values, so the mapping cannot throw.

## Fixes applied inline

| Lens | File | Fix |
|---|---|---|
| Code quality | `ui/panel/PanelWindow.kt` | `ReviewRow` used a fully-qualified `net.devemperor.dictate.companion.pipeline.ReviewUi` while importing four sibling pipeline types by name — added the `ReviewUi` import and used the short name (consistency, readability). |
| Test coverage | `data/DesktopSessionRepositoryTest.kt` (new) | `DesktopSessionRepository.appendErrorTurn` had 0 coverage and the §8.3 replay-hygiene guarantee ("a failed follow-up never re-enters the replay") was only asserted in code. Added a focused test: seed a reviewed session + first SUCCESS turn, append an ERROR turn, assert the ERROR step is persisted (auditable) but `loadConversation` replays only the SUCCESS turn. Behavioral test — pins the observable §8.3 guarantee the E2E happy-path does not exercise. |

## Issue table

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| D3-1 | Important | Config-entity schema deviates from the literal §5.2 DDL (enum source, column names, omitted `peers` FK). VERIFIED against Room `12.json` + C2 `ConfigRepository`: C2-parity reasoning holds, E1 can still add `peers` + the FK via table-recreate. `Companion.sq`, `3.sqm` | fixed-inline (verified) | plan-deviation-resolved |
| D3-2 | Important | §9.3 History-Screen-Ausbau not implemented — the plan's D3 line lists "Verwaltungs-/History-UI §9". `HistoryScreen` still shows only `PHONE_SYNC` sessions; desktop-dictated sessions are persisted but never surfaced (host_origin filter, transcript-vs-output detail, re-insert of `final_output_text` missing). Larger feature (query rewrite + UI) → correctly delegated. | delegated | none |
| D3-3 | Nice-to-have | §9.1 panel-top profile dropdown absent. Active profile IS selectable in `ManagementScreen` and drives the pipeline via `ConfigProfileSource` (F20 satisfied functionally); only the mini-panel convenience dropdown is missing. | delegated | none |
| D3-4 | Nice-to-have | §9.2 management editing is shallow (create/duplicate/delete/set-active + basic model/prompt create). Deep pickers (profile→prompt-order editor, `ParameterRegistry` model params, `ModelFetcher` network list) not built — the VM is the E3 seam. | delegated | none |

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/ui/panel/PanelWindow.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/data/DesktopSessionRepositoryTest.kt` (new)

## Files outside assigned scope (drift)

- `data/DesktopSessionRepositoryTest.kt` — NEW test file (not in `CHUNK_FILES`, which lists no test home
  for `DesktopSessionRepository.kt`). Justified: closes the §8.3 error-path coverage gap on an in-scope
  source file, in the existing per-repository test-file pattern (`CompanionConfigRepositoryTest`,
  `SqlDelightHistoryRepositoryTest`). The commit-agent must include this new file in the fix wave.

## Test result

`./gradlew :companion:verifySqlDelightMigration :companion:test` — BUILD SUCCESSFUL (full suite green,
incl. the new `DesktopSessionRepositoryTest`).
