# D3 — Review + Profil/Modell/Prompt-UI + Companion-Entitäts-Tabellen

**Chunk:** D3 · **Date:** 2026-07-20 · **Plan:** desktop-companion-v1 · **Spec:** desktop-host.md §8-9, peer-katalog.md §5.2

## What was done

Three deliverables in one focus area:

1. **Companion entity tables (Migration `3.sqm`, D5.b).** `provider_configs` / `model_refs` /
   `prompts` / `profiles` / `profile_prompts` in `Companion.sq` mirroring the C2 Room schema, Double-Enum
   over the existing `:shared.config` enums, adapters in `CompanionDatabase`, generated `4.db` snapshot.
   `CompanionConfigRepository` maps rows ⇄ C1 DTOs and recomputes `content_hash` on write (§5.3).
2. **Re-dictate over the review panel (§8.3, ADR-0013 §6).** New reducer/effects/controller/repo path:
   `StartRefinement → StopRefinement → RefinementTranscribed → RunContinuation → ReviewTurnCompleted`,
   producing a `REVIEW_REFINEMENT` session for the S2 take + a `ConversationContinuation` turn appended
   to the reviewed session, with a non-terminal panel update (iterative re-dictate). Full review panel
   UI (§8.4) replacing D2's placeholder `ConfirmRow`.
3. **Management UI (§9.2) + F20 profile wiring.** `ConfigViewModel` + `ManagementScreen`
   (profiles/providers/models/prompts CRUD + active-profile pointer), wired into `App.kt`. `ConfigProfileSource`
   feeds the active profile's `AmbiguityMode` into the pipeline (§8.1).

## Acceptance (Plan §2 Kriterium 7 + D3 line)

- Verdict matrix (5 rows, §8.2) — `DesktopReviewDecisionMatrixTest` (parametrised) ✓
- `REVIEW_REFINEMENT` session + `ConversationContinuation` turn + non-terminal panel — reducer tests +
  `DesktopDictationPipelineTest.reDictate_…` E2E (fake runner) ✓
- Entity-table migration + test — `verifyMigrations` green (3.sqm ↔ 4.db) + `ConfigEntityCheckParityTest`
  (CHECK accept/reject + C1 enum parity) ✓
- `:companion:build` green (verifyMigrations + assemble + test); `:app` compiles unchanged.

## Deviation table

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Entity provenance enums reuse `:shared.config.Visibility`/`SubscriptionMode` instead of new `catalog.*Wire` | peer-katalog §5.2 DDL | Columns typed `AS …config.Visibility`/`…config.SubscriptionMode`, C2 column names (`source_original_id`/`_hash`), NOT NULL 3-value `subscription_mode` | §5.2's stated goal is "one source shared with C2 Room"; C2 already uses the config enums, so reuse achieves parity-by-construction and avoids a redundant duplicate enum needing its own drift test (DRY, D4). Also keeps D3 self-contained (no dependency on E1's not-yet-existing `catalog` package). | E1 finds these tables + enums already present; uses the same config enums. Its own `subscriptions`/`access_log` may still introduce a 2-value `SubscriptionModeWire` for the journal (different column). | ✓ (documented) |
| `FOREIGN KEY (source_peer_id) REFERENCES peers` OMITTED | peer-katalog §5.2 DDL | The provenance FK is left out; the `source_peer_id` column stays | `peers` is E1's table (Migration 4.sqm, runs AFTER D3 per E1→D3), and SQLDelight validates FK targets at compile time — a forward ref would fail the build | E1, when it creates `peers`, may add the FK via table-recreate (its established pattern). Until E2, `source_peer_id` is only ever written NULL from here. | ✓ (documented, header comment) |
| `api_credentials` table not mirrored | peer-katalog §5.2 / Plan D5.b | Five tables created, not six | The §5.2 / D5.b list is explicit (5). Desktop credential handling = SecretStore + catalog delivery (Block B/E), not a local mirror in D3's scope | Block E credential path unaffected; no local credential table on desktop yet | ✓ (documented) |
| `ConfigProfileSource` resolves only `AmbiguityMode` from the active profile | §5.1 NOTE / §8.1 / §15 Gap 5 | The rest of profile→`AiConfig` (provider/model/key, auto-format/instructions/style-prompt) stays on the transitional `CompanionAiConfig` | Desktop has no credential→config resolver yet (Block B/E); the ambiguity axis is what the review flow (§8) needs, and it is wired. | Full desktop profile resolution is follow-up (see issues) | Partial (documented) |
| `CancelRefinement` has no `queue.cancel` | §8.4 (mentions `JobQueue.cancel`) | Cancel discards the S2 capture; a continuation already on the queue completes but its `ReviewTurnCompleted` is dropped by the reducer's `refining` guard | D1b's `JobQueue` has no cancel; the reducer state is the honest cancel authority (no torn state) | none | ✓ (documented) |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| D3-1 | Important | Entity-table schema deviates from the literal §5.2 DDL (enum source, column names, omitted `peers` FK). Audit should confirm the C2-parity reasoning and that E1 can still add `peers` + the FK. `Companion.sq` (config-entity block), `3.sqm` | fixed-inline | plan-deviation-resolved |
| D3-2 | Important | §9.3 History-Screen-Ausbau not implemented: `HistoryScreen` still shows only `PHONE_SYNC` sessions (host_origin filter, desktop-session view, transcript-vs-output detail, re-insert of `final_output_text` are missing). Desktop-dictated sessions are persisted but not surfaced. | delegated | none |
| D3-3 | Nice-to-have | §9.1 panel-top profile dropdown not added. The active profile is selectable in `ManagementScreen` and drives the pipeline via `ConfigProfileSource`; the convenience dropdown on the mini-panel itself is absent. | delegated | none |
| D3-4 | Nice-to-have | §9.2 management editing is shallow (create/duplicate/delete/set-active + basic model/prompt create). Deep pickers (profile→model/prompt-order editor, model parameter UI via `ParameterRegistry`, `ModelFetcher` network model list) are not built — the VM is the seam for E3 to extend. | delegated | none |

## Inline fixes applied

- `ChordMigrationSeedTest.kt` — added the four new table adapters to its hand-built `DictateCompanionDb`
  constructor (the generated ctor now requires them). In-scope test-companion of the schema change.

## Helper decisions

- Reused existing test fakes (`FakeAudioCapture`, `FakeTranscriptionRunner`, `FakeRunnerFactory`,
  `MutableClock`, `InlineJobQueue`). Added a `SequencedCompletionRunner` (scripted per-`converse` answers)
  for the iterative re-dictate E2E and an `ActiveProfileStore` seam so `ConfigViewModel` is testable
  without a settings table.

## Files outside assigned scope (drift)

- `companion/.../capture/JavaSoundAudioCaptureService.kt` and `shared/.../config/CanonicalJsonTest.kt`
  show as modified in the worktree but were **already modified at session start** (parallel-chunk work) —
  NOT touched by D3. The commit must stay file-scoped to D3's files below.

## Primitives reused

`ReviewDecision.decide`, `ConversationTurnBuilder.buildFollowUpUserMessage`, `ConversationReconstructor.toApiMessages`,
`StructuredResponse`, `ReconstructedTurn` (shared-ai); `contentHash` + C1 DTOs + serializers (`:shared.config`);
`EnumColumnAdapter` Double-Enum pattern; `SchemaMigrator`/`verifyMigrations`; `CompanionSchemaParityTest` pattern;
existing pipeline reducer/effects/controller; plain-VM + `StateFlow` UI pattern (`HistoryViewModel`).

## Test run

`:companion:build` — BUILD SUCCESSFUL (compile + `verifyMigrations` + full `:companion:test` + assemble).
`:app:compileDebugKotlin` — SUCCESSFUL (unaffected).
