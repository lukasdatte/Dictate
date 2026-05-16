# Audit Report: plan-and-api (Block 3, scope: full-block)

**Agent-ID:** B3-AUDIT-PLAN-AND-API
**Date:** 2026-05-16
**Knowledge skills used:** knowledge-typescript (type-contract / discriminated-union / exhaustiveness patterns, applied conceptually to the Kotlin sealed-class API surface — this is an Android Kotlin/Java repo, not a TS repo)
**Files inspected:** 13
- `app/src/main/java/net/devemperor/dictate/preferences/LanguageResolver.kt` (new)
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/DictateApplication.java`
- `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesPlugin.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/LanguageLabelResolver.kt`
- `app/src/test/java/net/devemperor/dictate/preferences/LanguageResolverTest.kt`
  (+ cross-checked test consumers: `DictateOrchestratorTest.kt`, `ModuleServicesTest.kt`, `LanguageModuleTest.kt`, `DictateUiStateTest.kt`)

## Summary

- Critical: 0
- Important: 0
- Nice-to-have: 2

## AC / Risk verdicts

| Item | Verdict | Evidence |
|------|---------|----------|
| **AC-5** (LanguageController removal) | **PASS** | `grep -rl "LanguageController" app/src/main` → exit 1, zero hits. `core/LanguageController.kt` + `LanguageControllerTest.kt` deleted. All consumers migrated (DictateApplication singleton + no-op reader removed; PreferencesFragment → `LanguageResolver.INSTANCE`; IME → `resolveEffectiveLanguage()`/`pushPermanentLanguageToOrchestrator()`/`setLanguageFromPicker()`; KeyboardUiController/PipelineUiStateReader = KDoc-only scrub, no dangling code ref). |
| **AC-6** (audioFile field removal) | **PASS** | `grep -n "private File audioFile" DictateInputMethodService.java` → exit 1, zero hits. All field use-sites re-sourced; new canonical `RecordingState.audioFileOrNull` accessor consumed at the one behaviour-bearing read (`stopRecording` → `captureFreshConfigSnapshot`). |
| **R-3** (boot-before-bind) | **SOUND** | The permanent SoT is the prefs file (stateless `LanguageResolver`, no cache → no cross-instance staleness). `onCreateInputView` → `pushPermanentLanguageToOrchestrator()` is `pipelineBinder != null`-guarded; `onServiceConnected` (`:397`) idempotently re-pushes once the binder exists (reducer reduces no-change refresh to `null`). All consumers migrated, no dangling LC ref. Design is the most sustainable option (SoT decoupled from both lifetimes). |
| **R-5** (audioFile non-recording reads) | **GREEN — confirmed** | Independently spot-checked 4 of 9 sites against code: (1) `startRecording :2350-2378` — method-local from `getAudioFileFactory().allocate()`, threaded into `StartRecording(...,audioFile,...)` → becomes RecordingState payload ✓; (2) `stopRecording :2434-2443` — from `state.recording` via `getAudioFileOrNull`, guarded by `isEffectiveRecordingActiveOrPaused()` + defensive null-bail ✓; (3) resend `handleReprocessSend :3473-3474` — from `session.getAudioFilePath()` (Room DB), field structurally uninvolved ✓; (4) legacy-migration / resend-visibility `:876/:1931/:2504/:2545` + import `:2004-2008` — from `new File(getCacheDir(), Pref.LastFileName)` / method-local, never the field ✓. Resend + legacy-migration genuinely unaffected — the C9-C2 claim that the field was structurally uninvolved is verified. |
| **F-15 side-effect** (RenderBackend `state.language.effective` was always `"system"` pre-C8) | **REAL & CORRECT** | Pre-C8 the `RefreshFromPref` reducer was a no-op (`data object` → returned `null`) AND `PipelinePrefMirror` does not mirror language → `LanguageState.effective` could never leave the `"system"` boot sentinel; the language-suffixed "Record" label never rendered. C8's payload-bearing `data class RefreshFromPref(effective)` + the writing reducer arm makes it live. The side-effect framing in the C8 block-report is accurate. |
| **C10-C3 disposition** | **CORRECT — not done by design** | Only the OQ-1 doc landed in B3's diff: commit `185f3f6` verified as additive KDoc on `PipelineOrchestrator.kt` (33-line "Cutover boundary" header, zero behaviour change). The 4 controller deletions correctly NOT performed (moved to new B5 per C10-IMPL-2 escalation). AC-7/AC-10 not in B3 scope post-narrowing; not audited. |
| **API contracts** | **CONSISTENT** | `RefreshFromPref(effective)` shape change: all 40+ consumers (production `LanguageModule.reduce` + IME dispatch site `:1645`; tests `DictateOrchestratorTest`/`ModuleServicesTest`/`LanguageModuleTest`) pass the `String` payload. `LanguageResolver` public API (`curatedLanguages`/`effectiveLanguage`/`setLanguage`/`setCuratedLanguages`) consumed consistently by IME + PreferencesFragment + tests; delegates to the **existing** `persistInputLanguagesAndPos` (InputLanguagesPlugin.kt:83) + `LanguageLabelResolver.allowed()` (real APIs, not stubs). `RecordingState.audioFileOrNull` single consumer (`DictateUiStateKt.getAudioFileOrNull` at IME `:2434`) + 5 tests. No stub / TODO / NotImplemented / throw-not-implemented introduced in the B3 main diff (grep clean). |
| **Deviation justifications** | **SPEC-FAITHFUL & JUSTIFIED** | C8 Dev-1 (RefreshFromPref `data object`→`data class`): justified — Spec 1 §4.11 Pre-Dispatch-Resolution + the `LanguageModule`/`Action` KDoc explicitly anticipated the payload promotion; mid-size, solution clear from plan knowledge, correctly flagged `plan-deviation-resolved`. C8 Dev-2 (`onServiceConnected` re-push): small + locally decidable, plan mandated documenting boot-before-bind ordering + the guard discipline. C9 Dev-1 (`RecordingState.audioFileOrNull`): justified — the plan *also* mandates "recording-active reads → orchestrator state"; the accessor follows the project's own documented `isActiveOrPaused` sibling convention (DRY, centralised), additive, no behaviour change to existing callers. All three are sustainable, well-documented, and do not silently drift. |

## Findings

### AUDIT-PLAN-AND-API-B3-1

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1611-1621` (`resolveEffectiveLanguage`) + C8 block-report "Overlooked points"
- **Description:** The ReprocessStaging language override has **two parallel carriers** during the C8→(retired) window: the legacy `PipelineUiState.ReprocessStaging.selectedLanguage` (via `KeyboardUiController`, read by `resolveEffectiveLanguage()` for the transcription config) **and** the new `LanguageState.override` (written by the `SetOverride` dispatch in `setLanguageFromPicker`). The transcription path reads only the legacy carrier; the new `LanguageState.override` is currently write-only with no production reader. This is intentional transitional duplication (the implementer documented it under "Overlooked points / known gaps"), but the duplication's removal was originally scoped to C10-C3, which was moved to the new B5. The cleanup obligation must travel with it.
- **Why it matters:** A SoT-duplication that outlives its planned removal window risks the two carriers diverging (e.g. a future change writing only one). It is correctly documented and currently behaviour-safe (single reader = legacy carrier, so R-1 transcription fidelity is preserved), so this is informational, not a defect — but it should be explicitly tracked on the B5 (Theme C-R / render-path-cutover) scope so it is not dropped along with the controller-retire move.
- **Suggested fix scope:** small (tracking only — no code change in B3; ensure B5 scope inherits "collapse the ReprocessStaging override read onto `LanguageState.override`").
- **Suggested fix:** No B3 code change. Orchestrator/consolidator: confirm the B5 (render-path-cutover) scope explicitly carries the "collapse legacy `ReprocessStaging.selectedLanguage` read onto `LanguageState.override`" item that C10-C3's "Overlooked points" hands off, so the transitional dual-carrier does not become permanent.

### AUDIT-PLAN-AND-API-B3-2

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/preferences/LanguageResolver.kt` (new file, package `preferences`) vs Spec 1 §9.6 line 4254
- **Description:** Spec 1 §9.6 prescribes `LanguageController` → "wandert direkt in den Modul-Reducer; keine Adapter-Phase nötig" (moves directly into the module-reducer; no adapter phase needed). The implementation instead extracted the pref-only resolution/write algorithm into a **separate stateless `preferences/LanguageResolver` object**, keeping the `LanguageModule` reducer strictly I/O-free. This is a literal divergence from the §9.6 phrasing, but it is the **correct** reading of the spec as a whole: Spec 1 §4.11 Pre-Dispatch-Resolution + ADR-0002 mandate the reducer be a pure transition function — putting `SharedPreferences` I/O "directly in the module-reducer" would violate the pure-reducer invariant that §4.11/§15.x enforce. The §9.6 one-liner is a coarse migration-table entry; §4.11 is the binding constraint. The deviation is captured in the C8 block-report's "Files modified — drift classification" (`LanguageResolver.kt` as the implied extraction target per §9.6 "wandert direkt in den Modul-Reducer" + the R-3 unbound-path SoT requirement).
- **Why it matters:** This is a documentation/traceability nicety, not a code defect — the chosen design is the *more* spec-faithful one (it honours the pure-reducer invariant §9.6's terse phrasing would otherwise contradict). Recording it so a future reader reconciling §9.6 against the code does not mistake the `preferences/LanguageResolver` placement for unplanned drift.
- **Suggested fix scope:** small (documentation reconciliation only).
- **Suggested fix:** No code change. Optional: a one-line note in the C8 deviation table (or a §9.6 SoT margin note in the spec, if the spec is editable in this Epic) clarifying that "wandert direkt in den Modul-Reducer" is satisfied by the pref-only algorithm living in `preferences/LanguageResolver` (called Pre-Dispatch per §4.11) rather than literally inside `LanguageModule.reduce`, because the reducer must stay I/O-free.

## Out-of-scope observations

- None requiring cross-topic routing. (Build/test gating — `assembleDebug` + `testDebugUnitTest` 1048/0/0 — is documented in the C9-C2 block-report and is the orchestrator's Block-Validate gate, not a plan-and-api finding. C8-IMPL-1 `LegacyAudioFileMigrationTest` release-suite flake is already delegated to B3 AUDIT-TEST; out of plan-and-api scope.)

## Coverage

- Files audited: all 13 listed above (full-block scope; B3 narrowed to C8-C1 + C9-C2 production + the C10 OQ-1 doc-only delta).
- Files skipped (with reason): the 4 controller classes (`MainButtonsController`/`RecordingUiController`/`KeyboardStateManager`/`KeyboardUiController` deletion) — correctly NOT done in B3 (moved to B5 per C10-IMPL-2); KDoc-only scrub files (`TextResolvers.kt`, `ImePipelineConfigResolver.kt`, `PipelineRunnerSubsystemAdapter.kt`, `DictatePipelineService.kt`, `InputLanguagesLegacyMigration.kt`, `VersionedPrefs.kt`) — verified comment-only via diff inspection, no API/plan surface.
- Knowledge-skill checkpoints applied: sealed-class `when` exhaustiveness (the `LanguageModule.reduce` `when (action)` is exhaustive over `SetOverride`+`RefreshFromPref` with no `else` → a future `LanguageAction` subtype is a compile error; the discriminated-union/exhaustiveness contract holds); type-contract consistency across the `RefreshFromPref(effective: String)` payload-shape change (all producers/consumers aligned); accessor-as-canonical-extraction (`audioFileOrNull` exhaustive sealed `when`, all 4 `RecordingState` arms).
