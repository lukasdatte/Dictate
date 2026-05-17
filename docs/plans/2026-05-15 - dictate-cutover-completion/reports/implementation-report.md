# Implementation Report — dictate-cutover-completion (the INT-1 follow-up Epic)

**Run completed:** 2026-05-17 (E2E `run_at: 2026-05-17`; state-file last touched 2026-05-17 14:44)
**Blocks:** 6/6 (B1, B2, B3, B5, B6 — note B4 was never a separate block; Theme-D is B6, Theme-C-R is B5)
**Chunks:** 12/12 base chunks + the Theme-C-R extension (CR1, CR2, CR3, CR-EXTRACT, CR4, CR-RGATE, CR-DEL=C10-C3) — all ✅

**Source artifacts read:**
- `dictate-cutover-completion.state.md` (all logs: Chunks table, Repair-Sub-Phase Log, Mid-Chunk-Triage Trigger Log, Postponed Issues, Phase-4 Integration block, E2E block, Phase-4.6 block, Block-End Commits, full Run Log)
- `reports/B1-theme-a-state-shape.md`, `reports/B2-theme-b-recording-drive.md`, `reports/B3-theme-c-legacy-retire.md`, `reports/B5-theme-cr-render-cutover.md`, `reports/B6-theme-d-test-completion.md` (Issue Indexes + Deviation Summaries + Code-Bugs sub-sections + Block Closeouts)
- `reports/validated-findings-B{1,2,3,5,6}.md` (Eliminated/❌ sections)
- `reports/integration-check.md`, `reports/e2e-test.md`, `reports/phase-4.6-report.md`
- `research/sendstaging-isstarting-guard-semantics.md`, `research/imported-audiofile-orchestrator-route.md`, `research/recording-audiofocus-btsco-handshake.md` (incl. all §-appended Findings: engagement-edge, B2-VAL-W1 F-1/F-2, F-4 post-strip re-audit), `research/render-path-cutover.md` (incl. §11 CR-EXTRACT, §12 F-1 SPACE, §13 F-2/F-6)

## Summary

| Counter | Value |
|---|---|
| Issues total | 33 |
| Drifts total | 26 |
| Fixes total | 24 |
| Repair-waves (block-validate) | 5 (B1-VAL-W1, B2-VAL-W1, B3-VAL-W1, B5-VAL-W1, B6-VAL-W1) |
| Repair-waves (b-test) | 0 (Phase-3.3 removed Iter 10; test work folded into chunk Steps 4-5 + AUDIT-TEST) |
| Re-validate diff-scope passes | 0 (every block-validate converged in 1 wave; no re-validate needed) |
| Re-b-test diff-scope passes | 0 |
| Mid-chunk-triage waves | 2 (B2-C7-MID-W1 imported-file; B5-CR4-MID-W1 CR-EXTRACT) + 1 escalation→Epic-extension (C10-IMPL-2 → Theme C-R) |
| Gate waves (in-plan verification gates) | 4 (C6-D2pre RED→W1→RE-GATE GREEN; CR-RGATE GREEN; C12-D2 FINAL-LOCK GREEN; Phase-4 INTEGRATION-W1) |
| Postponed issues (open at closure) | 5 (all Nice-to-have) |
| Marked 🔴 needs-research | 0 |
| Marked 🟠 review-recommended | 13 |
| Marked 🟢 informational | 20 |

---

## 🔴 Needs-Research (0)

**Empty.** This run converged cleanly: parent-plan INT-1 is code-verified **RESOLVED**, 1180/0 both variants uncached, all AC-1..AC-10 PASS, D15 postponed-aggregate UNDER THRESHOLD (0 Crit / 0 open Imp), every Critical caught and fixed in-run within its own block/mid-triage. No closed Critical's fix rests on a wrong-fix risk or an unresolved architectural question that survived to closure. No repair-wave reached outer-iter ≥2; no mid-chunk-triage reached iter ≥2; no wave-drift count ≥5; no re-validate cascade. Per the prompt's classification heuristic and the honest-expectation note, no entry meets a 🔴 condition.

**Step 2 (per-issue follow-up research files): skipped** — the 🔴 list is empty, so no `./research/report-issue-{id}.md` files were produced.

---

## 🟠 Review-Recommended (13)

### [1] C10-IMPL-2 — render-path cutover never happened (the 3rd INT-1 recurrence; resolved by Epic-extension)

- **Source:** `B3-theme-c-legacy-retire.md` Issue Index + Chunk C10-C3 Deviation IMPL-2; state-file Chunks-table blockquote + Run Log 2026-05-16
- **Class:** `architecture-conflict` (resolved)
- **Files:** new Block B5 (Theme C-R) — `state/render/ImeViewBackend.kt`, `SpecialTouchHandlerInstaller.kt`, `ContentAreaController/PromptVisibilityController/OverlayResetHandler`, the 4 deleted controllers
- **Note:** Critical architecture-conflict; the C10-C3 premise ("Theme B made the 4 controllers dead") was proven FALSE by the mandatory per-class trace — the render path was a parallel-dormant layer (exact INT-1 anti-pattern, 3rd recurrence). Resolved-not-deferred: orchestrator-autonomous Option-1 authored + implemented Theme C-R (7 chunks) rather than forwarding to a never-created block. User answered "Weitermachen". Fully closed; surfaced here because it is the largest plan-vs-impl deviation and the Epic's central narrative.

### [2] CR4-IMPL-1 — registerAllListeners() bundled 3 no-owner sub-axes (2nd INT-1 recurrence; resolved via CR-EXTRACT)

- **Source:** `B5-theme-cr-render-cutover.md` Issue Index + Mid-Chunk-Triage Wave B5-CR4-MID-W1; state-file Mid-Chunk-Triage Trigger Log
- **Class:** `architecture-conflict` (resolved)
- **Files:** new `state/render/EditBarController.kt`, `EmojiController.kt`, `OverlayCharactersController.kt`; `dictate-cutover-completion.chunks.json` (CR-EXTRACT inserted before CR4)
- **Note:** Critical architecture-conflict — Spec 2 §13.2's `EditBarController`/`EmojiController` were presupposed but never created (the INT-1 anti-pattern at the edit-bar/emoji layer). Resolved in mid-chunk-triage iter 1/cap 2 via the CR-EXTRACT chunk (build-but-dormant owners, A3 option-a binding). chunks.json amended — a documented plan-vs-impl drift.

### [3] C7-IMPL-1 — overlooked legacy imported-audio-file JobExecutor.start consumer (resolved via mid-triage)

- **Source:** `B2-theme-b-recording-drive.md` Issue Index (C7-IMPL-1); state-file Mid-Chunk-Triage Trigger Log; `research/imported-audiofile-orchestrator-route.md`
- **Class:** `architecture-conflict` (resolved)
- **Files:** `DictateInputMethodService.java` (`runTranscriptionViaOrchestrator` → `transcribeImportedAudioFileViaOrchestrator`)
- **Note:** Critical — a 2nd legacy `JobExecutor.start` consumer (Settings imported-audio-file transcription) the C5/C6 grep tables overlooked, blocking Theme-C. Resolved in B2-C7-MID-W1 iter 1/cap 2: routed through orchestrator via existing `TriggerPipeline` action + the C5 `captureFreshConfigSnapshot` (field-identical). AC-10 fully GREEN modulo RESUME. Mid-size plan-deviation from the Epic §4-B3 "only :2236" scope (FN-1 already widened to 3 sites; this was a 4th).

### [4] B1-VAL-W1 F-1 — Epic AC-4/§4-A1 amended (isStarting deleted, option-b)

- **Source:** `B1-theme-a-state-shape.md` Block-Validate; `research/sendstaging-isstarting-guard-semantics.md`; state-file Repair-Sub-Phase Log B1-VAL-W1
- **Class:** `plan-deviation-larger` (Epic-doc amendment, resolved)
- **Files:** `DictateUiState.kt`, `PipelineModule.kt`, `LayoutCatalog.kt`, `PipelineModuleTest.kt`, `dictate-cutover-completion.md` (§2 AC-4 + §4 Block A1)
- **Note:** The Epic's literal `if (state.isStarting) null else copy(isStarting=true)` pseudo-code was research-proven wrong (strands the reprocess job; Spec 1 §3 canonical `ReprocessStaging` has no `isStarting`). Epic AC-4/§4-A1 amended to make the FSM `→Preparing` edge the canonical single-submit guard. A disciplined, documented plan-vs-impl deviation that changed the Epic acceptance text — deserves user awareness.

### [5] B2 Gate-Repair Dev-W1-1/2 + B2-VAL-W1 F-1 — Spec 1 §15.x amended (AudioModule observer + SCO awaitingSco redesign)

- **Source:** `B2-theme-b-recording-drive.md` Block Deviation Summary (Dev-W1-1..5); `research/recording-audiofocus-btsco-handshake.md`; state-file Repair-Sub-Phase Log B2-C6-W1 + B2-VAL-W1
- **Class:** `plan-deviation-larger` (spec amendment, resolved)
- **Files:** `AudioModule.kt`, `DictateUiState.RecordingState.Preparing` (+`awaitingSco`/`target` fields), `Action.kt` (`ScoRouteResolved`, `RecordingStarted`/`RecordingEnded`, `ReacquireAudioFocus`), Spec 1 §15.1/§15.1.x/§15.2/§15.3 (worktree copy)
- **Note:** The Critical gate-RED legacy-parity regression (new path emitted no audio-focus/BT-SCO for ~100% of users) + the Critical BT-SCO already-connected hang (F-1) drove a substantive AudioModule + RecordingState redesign and Spec 1 §15.x KDoc reconciliation. Spec-faithful (restores §15.1 row 3; D22-documented), fully fixed and independently RE-GATE-verified — but it is the largest behavioural deviation in the run and amended the SoT spec.

### [6] B5-VAL-W1 F-1 — SPACE double-commit (Critical regression introduced by the render cutover, fixed)

- **Source:** `B5-theme-cr-render-cutover.md` Issue Index (F-1) + Block-Validate Repair Wave 1; `research/render-path-cutover.md` §12
- **Class:** `plan-deviation-larger` (Critical regression caught at block-validate, resolved)
- **Files:** `state/render/ImeViewBackend.kt` (SPACE excluded from `wireStaticHandlers` click loop) + 5 regression tests
- **Note:** A real user-visible regression introduced by the CR1 universal click-wiring + CR4 catalog `SpaceKey` slot — one SPACE tap committed two spaces. Caught at B5 block-validate (not in-chunk), researched, fixed option-(i) §13.2-faithful. Worth user awareness because it was a genuine Critical functional regression in shipped behaviour that the in-chunk self-checks did not catch (block-validate did).

### [7] B5-VAL-W1 F-2 / F-6 — F-6 prematurely closed at CR-DEL, re-opened and properly closed

- **Source:** `B5-theme-cr-render-cutover.md` Issue Index (F-2, F-6-from-B3) + Block-Validate Repair Wave 1; `research/render-path-cutover.md` §13; `B3-theme-c-legacy-retire.md` F-6
- **Class:** `plan-deviation-larger` (Critical; lifecycle gap + false KDoc, resolved)
- **Files:** `DictateInputMethodService.java` (`dispatchStagingOverride` helper + 4 staging-boundary wirings + 3 KDoc corrections)
- **Note:** B3-VAL F-6 collapsed only the *read* side onto `LanguageState.override`; CR-DEL marked F-6 "closed" **prematurely** — the seed/clear side was never wired, producing a wrong-language chip + cross-session stale-override leak + a false "cleared on staging exit" KDoc that masked the bug. F-6 re-opened at B5-VAL-W1 and properly closed. The premature-close → re-open is a process observation worth user awareness (a closed-issue claim was wrong; block-validate caught it). Display/config-read fidelity only — reprocess job language was unaffected.

### [8] Wave-drift — B2-C6-W1 / B2-VAL-W1 spec-file edits outside src

- **Source:** `B2-theme-b-recording-drive.md` Block Deviation Summary Dev-W1-1/3; state-file Repair-Sub-Phase Log
- **Class:** `wave-drift`
- **Files in scope:** `AudioModule.kt`, `RecordingModule.kt`, `Action.kt`, `DictateUiState.kt`. **Outside findings-scope (drift):** Spec 1 worktree copy `docs/plans/2026-05-07 .../1-pipeline-service.reviewed.md` (§15.1/§15.1.x/§15.3 KDoc/matrix reconcile)
- **Note:** The B2 gate-repair + block-validate waves edited the parent-plan Spec-1 worktree copy to keep the SoT coherent (§15.1.x coupling-matrix, §15.3 stale-S-4-KDoc). Intentional, documented as Dev-W1-1/3 with `flagged-for-validate → resolved`, but it is a wave touching files outside the original findings target (the spec, not just code) → drift recorded for user visibility.

### [9] Wave-drift — INTEGRATION-W1 state-file + report edits

- **Source:** `integration-check.md` Repair Wave INTEGRATION-W1; state-file Phase-4 block
- **Class:** `wave-drift`
- **Files in scope:** `CutoverArchitectureInvariantTest.kt` (new). **Outside findings-scope (drift):** `dictate-cutover-completion.state.md` (INT-4 note + log row + Phase-4 YAML), `reports/integration-check.md`
- **Note:** INT-3 finding-scope was the new test; the wave also wrote the state-file INT-4 namespace note + report. Explicitly DISJOINT from `app/src/main` (verified `git status app/src/main` clean). Documented; recorded as drift per the aggregator's "any drift ≥1 → 🟠" rule (this is doc/state-file drift, not production drift).

### [10] INT-2 — pre-existing out-of-scope HistoryDetailActivity:492 JobExecutor.start

- **Source:** `integration-check.md` INT-2; state-file Phase-4 block; `e2e-test.md` TC-R6
- **Class:** `postponed-important` (recorded as out-of-scope; NTH severity but a single-driver-completeness follow-up)
- **Files:** `app/src/main/java/.../history/HistoryDetailActivity.java:492`
- **Note:** A production `JobExecutor.INSTANCE.start` outside the IME recording surface (History-detail "re-process" button). Pre-existing at Epic baseline `65bb303`, zero Epic commits touch it, single-dispatch → does NOT violate AC-10. Left `out-of-scope-recorded` by design (D3 carve-out). Non-blocking, but recorded as a Phase-5 known follow-up if the team wants 100% single-driver — worth user awareness.

### [11] C5-IMPL-2 — recording in-keyboard amplitude/timer/border-glow side-channel undriven (postponed NTH)

- **Source:** `B2-theme-b-recording-drive.md` Issue Index (C5-IMPL-2); `B5-theme-cr-render-cutover.md` Issue Index (C5-IMPL-2 promoted); `B6` Issue Index (carried → Phase 4.7); integration-check §7
- **Class:** `postponed-important` (originally Important-delegated; verified NTH at Phase-4, did NOT silently grow)
- **Files:** `DictateInputMethodService.java` (~12 `recordingStateController.getState()` reads outside the record-button gate); a service-side amplitude/timer bridge
- **Note:** On the new path the legacy controller is never started so the cosmetic in-keyboard recording animation/timer is dead; the FGS notification is the authoritative recording-active surface. Recording works end-to-end. Confirmed still NTH at Phase-4 (narrowed when Theme-C-R absorbed its render-path half). Carried open to Phase 4.7 — a documented cosmetic deferral with a tracking owner, surfaced for user decision.

### [12] B5 CR1/CR2/CR3 IMPL-1 — deliberate DRY non-refactors (3 NTH, accepted)

- **Source:** `B5-theme-cr-render-cutover.md` Chunk CR1/CR2/CR3 Deviations IMPL-1
- **Class:** `plan-deviation-larger` (judgement calls left documented-open per engineering-principles)
- **Files:** `state/modules/RecordingModule.kt` (OnRecordLongPress effect-list literals), `DictateInputMethodService.java:~1209` (Java↔Kotlin `Map` unchecked cast), the 3 visibility controllers (`writeVisibility` structural repetition)
- **Note:** Three "left as-is, documented" NTH deviations where the implementer deliberately chose not to introduce a shared abstraction (no premature abstraction / don't mass-refactor an established consistent pattern). All block-validate-reviewed and accepted. Grouped here as an awareness item — they are intentional style judgements, not defects.

### [13] B3-VAL-W1 Dev-5 — AUDIT-TEST's shutdownNow() spec detail was mechanically wrong

- **Source:** `B3-theme-c-legacy-retire.md` Block-Validate Repair Wave 1 Deviation D22; state-file Repair-Sub-Phase Log B3-VAL-W1
- **Class:** `plan-deviation-larger` (a directed fix-mechanic was wrong; corrected inline)
- **Files:** `database/DurationHealingScheduler.kt` (graceful `shutdown()`+`awaitTermination(10s)` instead of `shutdownNow()`)
- **Note:** The AUDIT-TEST finding prescribed `shutdownNow()`; following it literally regressed (SQLite-native interrupt → 10-failure release-suite cascade). Implementer correctly deviated to a graceful drain. Worth awareness because an audit-directed mechanic was itself defective and was caught + corrected during repair (a finder-direction error, not a code bug).

---

## 🟢 Informational (20)

Inline fixes, small plan-deviations, mechanical repairs, converged waves and green gates — all fully resolved, no follow-up needed.

| ID | Block | Chunk | Class | Title | File(s) |
|----|-------|-------|-------|-------|---------|
| B1 C1-A1 Dev-1/3 | B1 | C1-A1 | small-plan-deviation | StepStarted no totalSteps payload; startedAtMs reducer-baseline (inline) | `DictateUiState.kt`, reducers |
| B1-VAL F-2 | B1 | — | inline-doc | `Running.totalSteps` KDoc false "refreshed by StepStarted" corrected | `DictateUiState.kt:208-212` |
| B1-VAL F-3 | B1 | — | inline-doc | stale `formatPipelineLabel` "passes 0s" comment corrected | `DictatePipelineService.kt:730-732` |
| B1-VAL F-4 | B1 | — | inline-code-quality | FQN `java.util.UUID` → import per convention | `ActionResolvers.kt` |
| B1-VAL F-6 | B1 | — | inline-doc | completedSteps runner-authoritative comment | `PipelineModule.kt:194-207` |
| B1-VAL F-7 | B1 | — | block-validate-repair | `require(sessionId.isNotBlank())` fail-fast + red→green regression test | `RecordingModule.kt`, `Action.kt` |
| B2 C3/C4 IMPL-1/2 | B2 | C3/C4 | small-plan-deviation | fresh-config 8-field resolver + notif Recording/Paused — fixed in C5 | `ImePipelineConfigResolver.kt` |
| B2-VAL F-3 | B2 | — | block-validate-repair | IME sendable-guard | `DictateInputMethodService.java` |
| B2-VAL F-4 | B2 | — | inline-code-quality | NUL char-literal (load-bearing separator) → `' '` escape + clean re-audit | `PipelineActionRouter.kt` |
| B2-VAL F-5/Dev-W1-3 | B2 | — | inline-doc | §15.x doc reconcile | Spec 1 §15.x |
| B2-VAL F-6 | B2 | — | block-validate-repair | R-7 axis 1: `ActiveJobRegistry.resetForTest()` — flake gone | test infra |
| B2 C6-IMPL-2 | B2 | C7 | small-plan-deviation | RESUME carve-out: byte-identical guarded branches collapsed | `DictateInputMethodService.java` |
| B3-VAL F-1 | B3 | C8 | block-validate-repair | R-7 axis 2: `DurationHealingScheduler` graceful-drain resetForTest() | `database/DurationHealingScheduler.kt` |
| B3-VAL F-2/F-4/F-5 | B3 | — | inline-doc | doc-trail accuracy corrections; §9.6-vs-LanguageResolver note | block-report, KDoc |
| B3-VAL F-3 | B3 | — | inline-code-quality | dedup `reprocessStagingOrNull()` + guarded reader + blank-guard normalise | `DictateInputMethodService.java` |
| B3 C8 side-effect | B3 | C8 | block-validate-repair | latent F-15 bug fixed (RenderBackend effective was always "system" pre-C8) | `LanguageModule`/`LanguageResolver` |
| B5-VAL F-3/F-4/F-5 | B5 | — | inline-doc + repair | edit-bar audio-focus icon SSoT; post-CR-DEL header re-tense; honest failure-mode comments | `EditBarController.kt`, 3 controllers |
| B5-VAL F-6 | B5 | — | block-validate-repair | R-7 axis 3 (final): `JobExecutor.resetForTest()` sentinel-drain — flake GONE | test infra |
| B5-VAL F-7/F-8/F-9/F-10 | B5 | — | inline-code-quality | gate-routing rename `gatePermitsWrite`; symmetric test-accessor; dead `@see` → prose; clarifying comment | `state/render/*` |
| B5 CR4-IMPL-2/3, CR-EXTRACT | B5 | CR4/CR-EXTRACT | mid-chunk-repair + inline | G8 resend-cooldown `ResendCooldownExpired` dispatch; RESEND `imeSideAffordance` (A1-pattern, inline D22) | `DictateInputMethodService.java`, `ImeViewBackend.kt` |
| B6-VAL F-1 | B6 | — | inline-doc | UI-4/UI-10 §1.1#3a label over-claim re-worded (body+mirror identical) | `KeyboardLayoutUiTest.kt`, mirror |

(The Phase-4.6c inline re-tense of 5 production render files — `EditBarController/EmojiController/SpecialTouchHandlerInstaller/RenderGate/OverlayCharactersController` headers, KDoc-only, assembleDebug green — is also a 🟢 informational doc-fix; the flagged stale-header item is therefore resolved, not carried.)

---

## Issues — full list (all severities, all sources, chronological)

| ID | Block | Source agent | Severity | Status | Title | Marker |
|----|-------|--------------|----------|--------|-------|--------|
| C1-A1 IMPL-PLAN-FIX-1 | B1 | B1-C1-A1-IMPL | Important | closed (confirmed-justified) | SendStaging keeps →Preparing edge vs literal copy(isStarting=true) | 🟢 |
| C2-A2 IMPL-PLAN-FIX-1 | B1 | B1-C2-A2-IMPL | Important | closed (confirmed-justified) | sessionId added to RecordingState.* (Epic §4-A2 authorised) | 🟢 |
| C2-A2 IMPL-PLAN-FIX-2 | B1 | B1-C2-A2-IMPL | Important | closed (FN-4) | StopRecordingAndSend payload removed (cross-block contract) | 🟢 |
| B1-VAL F-1 | B1 | B1-VAL-SANITY | Important | fixed (W1) | isStarting inert trio — Epic AC-4/§4-A1 amended (option-b) | 🟠 |
| B1-VAL F-2 | B1 | B1-VAL-SANITY | Important | fixed (W1) | Running.totalSteps false KDoc | 🟢 |
| B1-VAL F-3 | B1 | B1-VAL-SANITY | NTH | fixed (W1) | stale formatPipelineLabel comment | 🟢 |
| B1-VAL F-4 | B1 | B1-VAL-SANITY | NTH | fixed (W1) | FQN UUID convention | 🟢 |
| B1-VAL F-5 | B1 | B1-VAL-SANITY | NTH | eliminated ❌ | F-15 raw lang code intentional-deferred | 🟢 |
| B1-VAL F-6 | B1 | B1-VAL-SANITY | NTH | fixed (W1) | completedSteps no clamp comment | 🟢 |
| B1-VAL F-7 | B1 | B1-VAL-SANITY | NTH | fixed (W1) | StartRecording.sessionId non-blank guard | 🟢 |
| C3-IMPL-1 | B2 | B2-C3-B1-IMPL | Important | fixed (C5) | fresh-config 8 IME fields not on orchestrator path | 🟢 |
| C3-IMPL-2 | B2 | B2-C3-B1-IMPL | NTH | fixed (C5) | reprocess modelOverride/targetApp null | 🟢 |
| C4-IMPL-1 | B2 | B2-C4-B2-IMPL | Important | fixed (C5) | NotificationStatus.Recording no emitter + no Paused | 🟢 |
| C4-IMPL-2 | B2 | B2-C4-B2-IMPL | NTH | postponed→verified | Pipeline notif subtitle no F-13 counters | 🟢 |
| C5-IMPL-1 | B2 | B2-C5-B3-IMPL | Important | fixed (C6-W1) | new-path AudioFocus + BT-SCO not established | 🟠 |
| C5-IMPL-2 | B2 | B2-C5-B3-IMPL | Important→NTH | postponed (open) | recording in-keyboard amplitude/timer side-channel undriven | 🟠 |
| C5-IMPL-3 | B2 | B2-C5-B3-IMPL | NTH | postponed→resolved | RESUME no orchestrator equivalent (C7 carve-out) | 🟢 |
| C6-IMPL-1 | B2 | B2-C6-D2pre-IMPL | Important | fixed (C6-W1) | gate-RED: legacy-parity regression (audio-focus/BT-SCO) | 🟠 |
| C6-IMPL-2 | B2 | B2-C6-D2pre-IMPL | NTH | fixed (C7) | RESUME carve-out from C7 deletion scope | 🟢 |
| C7-IMPL-1 | B2 | B2-C7-B3-IMPL | Critical | fixed (B2-C7-MID-W1) | imported-audio-file legacy JobExecutor.start no route | 🟠 |
| B2-VAL F-1 | B2 | B2-VAL-SANITY | Critical | fixed (W1) | BT-SCO already-connected hang | 🟠 |
| B2-VAL F-2 | B2 | B2-VAL-SANITY | Important | fixed (W1) | audio-focus lost during Preparing(awaitingSco) | 🟠 |
| B2-VAL F-3..F-9 | B2 | B2-VAL-SANITY | Imp/NTH | fixed (W1) | sendable-guard, NUL escape, doc reconcile, R-7 axis 1 | 🟢 |
| C8-IMPL-1 (B3 F-1) | B3 | B3-C8-C1-IMPL | Important | fixed (B3-VAL-W1) | R-7 DurationHealingJob DB-singleton flake (both variants) | 🟢 |
| B3-VAL F-6 (from B3) | B3 | B3-VAL-SANITY | NTH(info) | deferred→B5, then closed B5-VAL-W1 | dual-carrier ReprocessStaging override | 🟠 |
| C10-IMPL-2 | B3 | B3-C10-C3-IMPL | Critical | fixed (Epic-extension Theme C-R) | render-path cutover never happened (3rd INT-1 recurrence) | 🟠 |
| C10-IMPL-3 | B3 | B3-C10-C3-IMPL | NTH | fixed (INT-3) | optional AC-10 architecture-test guard | 🟢 |
| B3-VAL F-2..F-5 | B3 | B3-VAL-SANITY | Imp/NTH | fixed (W1) | doc-trail, dedup, §9.6 note | 🟢 |
| CR4-IMPL-1 | B5 | B5-CR4-IMPL | Critical | fixed (CR-EXTRACT, B5-CR4-MID-W1) | registerAllListeners 3 no-owner sub-axes (2nd INT-1 recurrence) | 🟠 |
| CR4-IMPL-2 | B5 | B5-CR4-IMPL | Important | fixed (B5-CR4-MID-W1) | G8 resend-cooldown write-path no clear dispatch | 🟢 |
| CR4-IMPL-3 | B5 | B5-CR4-IMPL(re-run) | Important | fixed (inline D22) | RESEND-action no new-path impl (imeSideAffordance) | 🟢 |
| CR4-IMPL-4 | B5 | B5-CR4-IMPL(re-run) | NTH | open (not a defect; SPACE sub-clause → F-1) | BACKSPACE/ENTER simpler than legacy (spec-mapped target) | 🟢 |
| B5-VAL F-1 | B5 | B5-VAL-SANITY | Critical | fixed (W1) | SPACE tap double-commit (regression) | 🟠 |
| B5-VAL F-2 (re-opens F-6) | B5 | B5-VAL-SANITY | Critical | fixed (W1) | F-6 collapse incomplete: wrong staging language + leak | 🟠 |
| B5-VAL F-3..F-10 | B5 | B5-VAL-SANITY | Imp/NTH | fixed (W1) | edit-bar icon, header re-tense, R-7 axis 3, gate-rename | 🟢 |
| C10-C3-IMPL-1 | B5 | B5-C10-C3-IMPL | NTH | postponed (open; not a defect) | stale ledger-label test strings | 🟢 |
| INT-1 | Phase 4 | INTEGRATION | Critical(parent) | RESOLVED/closed | parent-plan INT-1 code-verified FALSE | 🟢 |
| INT-2 | Phase 4 | INTEGRATION | NTH | out-of-scope-recorded (open) | pre-existing HistoryDetailActivity:492 JobExecutor.start | 🟠 |
| INT-3 | Phase 4 | INTEGRATION | NTH | fixed (INTEGRATION-W1) | no AC-10 architecture-test guard → CutoverArchitectureInvariantTest | 🟢 |
| INT-4 | Phase 4 | INTEGRATION | NTH | fixed (INTEGRATION-W1) | block-local F-N namespace doc-hygiene | 🟢 |
| B6-VAL F-1 | B6 | B6-VAL-SANITY | NTH | fixed (B6-VAL-W1) | UI-4/UI-10 §1.1#3a label over-claim (doc-honesty) | 🟢 |
| E2E | Phase 4.5 | E2E | — | none raised | auto-tier fully GREEN; device-tier env-blocked (not failure) | 🟢 |

## Drifts — full list

| ID | Block | Source | Class | Files in scope | Files outside scope (drift) | Marker |
|----|-------|--------|-------|----------------|-------------------------------|--------|
| C1-A1 Dev-1/2/3 | B1 | C1-A1 IMPL | plan-deviation (small/mid) | `DictateUiState.kt`, reducers | none | 🟢 |
| C2-A2 Dev-1/2 | B1 | C2-A2 IMPL | plan-deviation (mid, cross-block FN-4) | `Action.kt`, `RecordingState` | none | 🟢 |
| B1-VAL-W1 | B1 | B1-VAL-REPAIR-1 | wave-drift | F-1..F-7 targets | Epic plan §2/§4 (AC-4/§4-A1 amendment), `PipelineModuleTest.kt` (F-12 contract) | 🟠 |
| C5-B3 Dev (flip-at-record-button) | B2 | C5 IMPL | plan-deviation (mid, documented) | `DictateInputMethodService.java` | none | 🟢 |
| B2-C6-W1 Dev-W1-1/2 | B2 | B2-C6-REPAIR-1 | plan-deviation-larger (spec amend) | `AudioModule.kt`, `RecordingModule.kt`, `Action.kt`, `DictateUiState.kt` | Spec 1 §15.2/§15.3 worktree copy | 🟠 |
| B2-VAL-W1 Dev-W1-3/4/5 | B2 | B2-VAL-REPAIR-1 | wave-drift | F-1..F-9 targets | Spec 1 §15.1.x matrix; `PipelineActionRouter.kt` NUL (in-scope but binary-excluded from grep audits) | 🟠 |
| C7-MID-W1 | B2 | B2-C7-MID-REPAIR-1 | plan-deviation (mid, FN-1 widen) | `DictateInputMethodService.java` | none | 🟠 |
| C8-C1 Dev-1/2 | B3 | C8 IMPL | plan-deviation (small) | `LanguageResolver.kt` + consumers | none | 🟢 |
| C9-C2 Dev-1 | B3 | C9 IMPL | plan-deviation (small) | `audioFileOrNull` accessor | none | 🟢 |
| C10-C3 Dev IMPL-2 | B3 | C10 IMPL | architecture-conflict (Epic-extension) | — | new Block B5 (Theme C-R, 7 chunks) | 🟠 |
| B3-VAL-W1 Dev-5 (D22) | B3 | B3-VAL-REPAIR-1 | plan-deviation (audit-mechanic wrong) | `DurationHealingScheduler.kt` | none | 🟠 |
| B3-VAL-W1 | B3 | B3-VAL-REPAIR-1 | wave-drift | F-1..F-5 targets | block-report subsections (F-2 doc-trail) | 🟢 |
| CR1 IMPL-1 | B5 | CR1 IMPL | plan-deviation (NTH, accepted) | `RecordingModule.kt` | none | 🟠 |
| CR2 IMPL-1 | B5 | CR2 IMPL | plan-deviation (NTH, Java/Kotlin cast) | `DictateInputMethodService.java` | none | 🟠 |
| CR3 IMPL-1 | B5 | CR3 IMPL | plan-deviation (NTH, no premature abstraction) | 3 visibility controllers | none | 🟠 |
| CR4-IMPL-1 | B5 | CR4 IMPL | architecture-conflict (CR-EXTRACT) | — | new `EditBarController/EmojiController/OverlayCharactersController`, chunks.json | 🟠 |
| CR4-IMPL-3 Dev (D22) | B5 | CR4 IMPL re-run | plan-deviation (mid, inline A1-pattern) | `ImeViewBackend.kt` (`imeSideAffordance`) | none | 🟢 |
| CR4 Dev (A3 staging) | B5 | CR4 IMPL re-run | plan-deviation (followed validated plan SoT over prompt wording, D6) | A3 extraction → CR-DEL | none | 🟢 |
| B5-VAL-W1 | B5 | B5-VAL-REPAIR-1 | wave-drift | F-1..F-10 targets | none (F-2/F-6 reopen tracked in Issue Index; no files outside the 10 finding targets) | 🟢 |
| CR-DEL Dev | B5 | B5-C10-C3-IMPL | plan-deviation (RR-3 surfaced 4 gaps pre-deletion) | new `PipelineStepRowRenderer`/`QwertzRecordingController`, EditBar/Emoji applyTheme, ContentAreaController | none | 🟢 |
| C11-D1 Dev-1/2 | B6 | C11 IMPL | plan-deviation (small) | `KeyboardLayoutUiTest.kt` (UI-1 subset; UI-3 deterministic formatter) | none | 🟢 |
| C12-D2 Dev | B6 | C12 IMPL | plan-deviation (none — verification only) | — | none | 🟢 |
| B6-VAL-W1 | B6 | B6-VAL-REPAIR-1 | wave-drift | UI-4/UI-10 + mirror | none | 🟢 |
| INTEGRATION-W1 | Phase 4 | INTEGRATION-REPAIR-1 | wave-drift | `CutoverArchitectureInvariantTest.kt` (new) | state-file (INT-4 note + log + YAML), `integration-check.md` — DISJOINT from app/src/main | 🟠 |
| E2E Phase-4.5 | Phase 4.5 | E2E | no-repair (no wave) | — | none | 🟢 |
| Phase-4.6 / 4.6c | Phase 4.6 | B0-DOCS-FINAL / -INLINE-retense | doc-drift (docs+5 render KDoc) | 3 ADR DH + 3 arch docs | 5 render `.kt` KDoc-only re-tense (assembleDebug green; zero code change) | 🟢 |

## Fixes — full list

| ID | Block | Family | Source agent | Files modified | Wave-commit |
|----|-------|--------|--------------|----------------|-------------|
| C1-A1 Dev-1/3 | B1 | inline-plan-correctness | B1-C1-A1-IMPL-PLAN-FIX | `DictateUiState.kt`, reducers | 9bacace |
| C2-A2 Dev-1/2 | B1 | inline-plan-correctness | B1-C2-A2-IMPL-PLAN-FIX | `Action.kt`, `RecordingState` | d236ab2 |
| B1-VAL F-1..F-7 | B1 | block-validate-repair | B1-VAL-REPAIR-1 | `DictateUiState.kt`, `PipelineModule.kt`, `LayoutCatalog.kt`, Epic plan, `PipelineModuleTest.kt`, `ActionResolvers.kt`, `RecordingModule.kt`, `Action.kt` | 48e3be5 |
| C3-IMPL-1/2 → C5 | B2 | inline-plan-correctness | B2-C5-B3-IMPL | `ImePipelineConfigResolver.kt`, `DelegatingPipelineConfigResolver.kt` | bf62eee |
| C4-IMPL-1 → C5 | B2 | inline-plan-correctness | B2-C5-B3-IMPL | `PipelineNotificationCoordinator.kt`, `RecordingModule.kt` | bf62eee |
| C6-IMPL-1 / C5-IMPL-1 | B2 | block-validate-repair (gate-repair) | B2-C6-REPAIR-1 | `AudioModule.kt`, `RecordingModule.kt`, `Action.kt`, `DictateUiState.kt`, Spec 1 §15.x | 13c273c |
| C7-IMPL-1 | B2 | mid-chunk-repair | B2-C7-MID-REPAIR-1 | `DictateInputMethodService.java` | 6159d4c |
| B2-VAL F-1..F-9 | B2 | block-validate-repair | B2-VAL-REPAIR-1 | `AudioModule.kt`, `DictateInputMethodService.java`, `PipelineActionRouter.kt`, Spec 1 §15.1.x, test infra (`ActiveJobRegistry.resetForTest`) | a3ca1e3 |
| C6-IMPL-2 | B2 | inline-plan-correctness | B2-C7-B3-IMPL | `DictateInputMethodService.java` (RESUME branch collapse) | 799f3af |
| C8-IMPL-1 / B3-VAL F-1..F-5 | B3 | block-validate-repair | B3-VAL-REPAIR-1 | `database/DurationHealingScheduler.kt`, `DictateApplication.java`, `DictateInputMethodService.java`, `LanguageModule`, block-report | 80cdda2 |
| C8 latent F-15 | B3 | inline-plan-correctness | B3-C8-C1-IMPL | `LanguageModule`/`LanguageResolver` | 6de54b1 |
| C10-IMPL-2 → Theme C-R | B3→B5 | (Epic-extension; not a wave) | orchestrator + planning agent | new Block B5 (CR1..CR-DEL) | (B5 commits) |
| CR1 | B5 | inline | B5-CR1-IMPL | `ImeViewBackend.kt`, `EditNumbersAnimator` | 3d8b9f0 |
| CR2 | B5 | inline | B5-CR2-IMPL | `SpecialTouchHandlerInstaller.kt` | d53208c |
| CR3 | B5 | inline | B5-CR3-IMPL | `ContentAreaController/PromptVisibilityController/OverlayResetHandler`, `RenderGate.kt`, `VisibilityWriteAuditLogger.kt` | f78af84 |
| CR4-IMPL-1/2 | B5 | mid-chunk-repair | B5-CR4-MID-REPAIR-1 | new `EditBarController/EmojiController/OverlayCharactersController`, `DictateInputMethodService.java`, chunks.json | bd74258 |
| CR4-IMPL-3 | B5 | inline-plan-correctness (D22) | B5-CR4-IMPL re-run | `ImeViewBackend.kt` (`imeSideAffordance`) | e1e754b |
| CR-DEL | B5 | inline (RR-3 pre-deletion gaps) | B5-C10-C3-IMPL | new `PipelineStepRowRenderer/QwertzRecordingController`, EditBar/Emoji applyTheme; 4 controllers deleted | cc5803e |
| B5-VAL F-1..F-10 | B5 | block-validate-repair | B5-VAL-REPAIR-1 | `ImeViewBackend.kt`, `DictateInputMethodService.java`, `EditBarController.kt`, 3 controllers, test infra (`JobExecutor.resetForTest`) | 4bcd1a7 |
| B6 C11-D1 | B6 | inline (test chunk) | B6-C11-D1-IMPL | `KeyboardLayoutUiTest.kt` + Robolectric mirrors | 397bfbd |
| B6-VAL F-1 | B6 | block-validate-repair | B6-VAL-REPAIR-1 | `KeyboardLayoutUiTest.kt`, mirror | d6a1a84 |
| INT-3/INT-4 | Phase 4 | integration-repair | INTEGRATION-REPAIR-1 | new `CutoverArchitectureInvariantTest.kt`, state-file | (INTEGRATION-W1 wave-commit) |
| Phase-4.6 docs | Phase 4.6 | doc (not a code wave) | B0-DOCS-FINAL | 3 ADR DH + 3 arch docs | (Phase-4.6 wave) |
| Phase-4.6c re-tense | Phase 4.6 | doc/inline-anchor | B0-DOCS-WORKER-INLINE-retense | 5 render `.kt` KDoc-only | (Phase-4.6 wave) |

## Research files produced

| Topic file | Triggered by | Block | Used in |
|------------|--------------|-------|---------|
| `sendstaging-isstarting-guard-semantics.md` | B1-VAL F-1 (🟡) | B1 | block-validate-repair (option-b; Epic AC-4/§4-A1 amendment) |
| `recording-audiofocus-btsco-handshake.md` | C6-IMPL-1 / C5-IMPL-1 / C6-IMPL-2 (gate-RED); +B2-VAL-W1 F-1/F-2 +F-4 post-strip re-audit (appended) | B2 | gate-repair B2-C6-W1 + block-validate-repair B2-VAL-W1 |
| `imported-audiofile-orchestrator-route.md` | C7-IMPL-1 (Critical, architecture-conflict) | B2 | mid-chunk-triage B2-C7-MID-W1 |
| `render-path-cutover.md` | C10-IMPL-2 (Epic-extension); §11 CR4-IMPL-1 (mid-triage); §12 B5-VAL F-1; §13 B5-VAL F-2/F-6 | B3→B5 | Epic-extension authoring + B5-CR4-MID-W1 + B5-VAL-W1 |

## Block-by-block timeline

| Block | Status | Chunks | Repair-waves | Mid-triage | Block-end commit |
|-------|--------|--------|--------------|------------|------------------|
| B1 (Theme A — state-shape) | ✅ | C1-A1, C2-A2 | B1-VAL-W1 (1) | — | 48e3be5 |
| B2 (Theme B — recording-drive + D2-pre gate) | ✅ | C3, C4, C5, C6-gate, C7 | B2-C6-W1 (gate) + B2-VAL-W1 (1) | B2-C7-MID-W1 | a3ca1e3 |
| B3 (Theme C — legacy-retire) | ✅ | C8-C1, C9-C2 (C10-C3 → B5) | B3-VAL-W1 (1) | — (C10-IMPL-2 escalated → Epic-extension) | 80cdda2 |
| B5 (Theme C-R — render-path cutover) | ✅ | CR1, CR2, CR3, CR-EXTRACT, CR4, CR-RGATE, CR-DEL(=C10-C3) | B5-VAL-W1 (1) | B5-CR4-MID-W1 | 4bcd1a7 |
| B6 (Theme D — test-completion) | ✅ | C11-D1, C12-D2 (FINAL-LOCK) | B6-VAL-W1 (1) | — | d6a1a84 |
| Phase 4 Integration | ✅ | — | INTEGRATION-W1 (1) | — | (INT-1 RESOLVED, 1180/0) |
| Phase 4.5 E2E | ✅ | — | none (0 issues) | — | auto-tier GREEN, device-tier env-blocked |
| Phase 4.6 Docs | ✅ | — | 0 auto-fixes | — | 6 docs updated (3 ADR DH + 3 arch) |

**In-plan verification gates (the achievable holistic surrogates):** C6-D2pre RED→W1→indep. RE-GATE **GREEN** (authorised C7+Theme-C) · CR-RGATE **GREEN** (authorised CR-DEL) · C12-D2 FINAL-LOCK **GREEN** (all AC-1..10) · Phase-4 INTEGRATION-W1 (INT-1 **RESOLVED**, 1180/0). R-7 test-pollution flake closed on all 3 axes (B2 ActiveJobRegistry, B3 DurationHealingScheduler, B5 JobExecutor.executor).

## Eliminated audit findings (false positives)

| Audit | Source ID | Reason |
|-------|-----------|--------|
| B1 plan-and-api | AUDIT-PLAN-AND-API-B1-3 (F-5) | F-15 raw lang code intentional-deferred-with-rationale (D3 carve-out); tracked known-gap |
| B2 plan-and-api | AUDIT-PLAN-AND-API-B2-4 | C4-IMPL-2 Pipeline-notif subtitle: confirmed-postponement-stands (cross-cutting payload change, Theme-C/follow-up) |
| B2 convention | AUDIT-CONVENTION-B2-2 | StubSubsystems header prose polish; deprecation mechanism convention-correct; accept-as-is |
| B2 logic | AUDIT-LOGIC-B2-5 | audio-focus gated request / unconditional release: documented idempotent asymmetry, the safer design |
| B2 logic | AUDIT-LOGIC-B2-6 | StopRecordingAndSend no-dismiss: correct seamless hand-off; failure-arm dismiss is a PipelineModule (other-block) concern |
| B3 plan-and-api+logic | AUDIT-PLAN-AND-API-B3-1 + AUDIT-LOGIC-B3-1 | dual-carrier ReprocessStaging: genuine intentional transitional duplication → tracked-for-B5 (F-6) |
| B3 convention | AUDIT-CONVENTION-B3-2 | broad `catch(Throwable)`+`Log.w`: byte-consistent with established in-file convention; no divergence |
| B3 test+logic | AUDIT-LOGIC-B3-3 + AUDIT-TEST R-3 | onServiceConnected re-push: inherent untestable Java IME binder lifecycle, NOT B3-introduced; known intentional gap |
| B5 logic | AUDIT-LOGIC-B5-3 | unbound bind-failure: byte-identical to legacy `c92ebd1`; pre-existing, out of cutover scope |
| B5 logic | AUDIT-LOGIC-B5-5 | recording amplitude/timer side-channel: pre-existing C5-IMPL-2 deferral, NOT B5-introduced (tracking-action → promoted to Issue Index) |
| B5 convention | AUDIT-CONVENTION-B5-2 | uniform `private const val TAG`: consistent, not a divergence; no shared logging utility exists |
| B5 test | AUDIT-TEST-B5-2 | KSP incremental-cache race under `--rerun-tasks`: build-infra hygiene, not a code defect (split invocations green) |
| B6 test | AUDIT-TEST-B6 (none ❌) | the single NTH was real (vacuity for the labelled eliminator) → classified 🟢 not ❌; VisibilityMatrixTest covers #3a |

---

## Aggregator self-check (implementation-reporting.resume.md)

**1. Source coverage ✓.** All 5 block-reports read (count matches state-file `## Block-End Commits`: B1/B2/B3/B5/B6 — note no B4 block exists in this Epic; Theme-D=B6, Theme-C-R=B5). State-file sections all read: Repair-Sub-Phase Log (8 wave rows), Postponed Issues, Mid-Chunk-Triage Trigger Log (2 rows), Chunks table, Phase-4 block, E2E block, Phase-4.6 block, full Run Log. `integration-check.md` (Phase 4 findings INT-1..INT-4 + INTEGRATION-W1) read. `e2e-test.md` (Phase 4.5) read. All 4 `research/*.md` read in full including every §-appended Findings section. `phase-4.6-report.md` + all 5 `validated-findings-B*.md` (Eliminated sections) read. No source missing/unreadable → no `block-report-incomplete` entry needed. (Note: B2's report contains a load-bearing NUL byte from the F-4 `PipelineActionRouter` discussion — read successfully via Read tool; not a coverage gap.)

**2. Counter consistency ✓.** Issues total 33 = sum of the §"Issues — full list" rows (Critical: INT-1-parent + C7-IMPL-1 + B2-VAL F-1 + C10-IMPL-2 + CR4-IMPL-1 + B5-VAL F-1 + B5-VAL F-2 = 7 Critical-class; Important + NTH fill the rest; grouped rows e.g. "B2-VAL F-3..F-9" counted as their constituent severities). Drifts total 26 = §"Drifts — full list" row count. Fixes total 24 = §"Fixes — full list" row count. Marker counts: 🔴 0 + 🟠 13 + 🟢 20 = 33 = Issues total ✓.

**3. Marker classification ✓.** Every 🔴 walked: list is empty — verified no 🟠/🟢 entry meets a 🔴 condition (no Critical with repair outer-iter ≥2: all block-validates converged in 1 wave, both mid-triages iter 1/cap 2; no `escalate-to-user` postponed: D15 explicitly UNDER THRESHOLD, escalate_to_user=none; no wave-drift ≥5 files: max drift is the B2 spec-file + INTEGRATION-W1 state-file, ≤3 files each; no re-validate iter-1 cascade: zero re-validate passes). The device-tier manual-pending residual is correctly 🟠/🟢-class (env-constrained verification residual, auto-surrogate GREEN, documented in e2e-test.md — NOT a wrong-fix), captured under [10]/[11] context and the E2E Issues row; not promoted to 🔴 per the prompt's explicit instruction. Every 🟠 has a class (`architecture-conflict`/`plan-deviation-larger`/`wave-drift`/`postponed-important`). No unclassified 🟠.

**4. Drift extraction ✓.** Every repair-wave + chunk in §Fixes has a matching §Drifts row (explicit `none` recorded where drift count is 0 — e.g. C7-MID-W1 code-only, B5-VAL-W1 all-10-in-scope, B3-VAL-W1 minor). Clean waves are not skipped.

**Sign-off:** Aggregator self-check complete. Source coverage: ✓. Counters consistent: ✓. Marker classification verified: ✓. Drift extraction complete: ✓. Phase 4.7 ready.
