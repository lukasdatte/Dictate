# Audit Report: convention (Block 1, scope: full-block)

**Agent-ID:** B1-AUDIT-CONVENTION
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (plugin-system / versioned-envelope — neither applies to a pure-Kotlin reducer field-addition; no realignment baseline drawn from it). Project `CLAUDE.md` + parent-plan Quality-Gates (K-1/K-4, DictatePrefs, Kotlin-new-code, sealed-reducer) used as the convention baseline.
**Files inspected:** 12
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (read-only — UUID-convention reference)
- `app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/RecordingModuleTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/layout/ActionResolversTest.kt`

## Summary

- Critical: 0
- Important: 0
- Nice-to-have: 2

## Findings

### AUDIT-CONVENTION-B1-1

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:730-732`
- **Description:** The `formatPipelineLabel` lambda inside `buildLayoutStrings()` still carries the stale comment *"Live values come from `PipelineUiState.Running`. The resolver currently passes 0s pending pipeline-state extension (Spec 2 C12/C14 follow-up)."* This contradicts code shipped in this same block: chunk C1-A1 (F-13) made `resolveRecordButtonTextPipeline` (`TextResolvers.kt`) pass the real `pipe.completedSteps / pipe.totalSteps / pipe.elapsedMs` — the "passes 0s pending extension" statement is now false. C1-A1 correctly updated the parallel KDoc in `TextResolvers.kt` (removed the "picks default 0s … C14 wires the live values" wording) but missed this twin comment in the production consumer of the F-13 fields. Comment-staleness anti-pattern: a comment that actively misdescribes the now-shipped behavior is worse than no comment (engineering-baseline "comments that restate/contradict code are noise").
- **Why it matters:** A future reader of `buildLayoutStrings()` is told the pipeline label is a stubbed `0`-placeholder when it is in fact live. This is the exact convention-drift class this audit guards: the same fact (F-13 fields are now live) documented one way in `TextResolvers.kt` and the contradictory old way here. `DictatePipelineService.kt` is in C2-A2's modified file set and the `formatPipelineLabel` lambda is the production consumer of the F-13 counters, so this is in-block scope, not pre-existing untouched drift.
- **Suggested fix scope:** small (one-file, mechanical — single comment edit)
- **Suggested fix:** Replace the three comment lines at `:730-732` with a statement matching the `TextResolvers.kt` F-13 KDoc, e.g. *"Live `completedSteps/totalSteps/elapsedMs` come from `PipelineUiState.Running` via `resolveRecordButtonTextPipeline` (F-13, Epic §4 Block A1). This lambda only formats them as `N/M ↵ M:SS`."*

### AUDIT-CONVENTION-B1-2

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:60`
- **Description:** The new `newSessionId()` helper uses a fully-qualified inline `java.util.UUID.randomUUID().toString()` rather than an `import java.util.UUID` + `UUID.randomUUID().toString()`. Every other UUID-minting site in the codebase imports the type and uses the short name: `PipelineOrchestrator.kt:22` (`import java.util.UUID`) → `:709` / `:847` (`UUID.randomUUID().toString()`). `ActionResolvers.kt` itself imports every other type it uses (`Action`, `DictateUiState`, `InsertionTarget`, …) — the inline FQN is the lone exception in the file and an import-convention drift versus the established sibling `PipelineOrchestrator`. The helper's own KDoc even references *"matching the IME's `UUID.randomUUID().toString()` in … `PipelineOrchestrator`"*, so the intent was to mirror that call-shape; the FQN form diverges from it.
- **Why it matters:** "Same operation done two different ways across the codebase" (the topic's core check): UUID minting is `import + UUID.randomUUID()` everywhere else, FQN-inline here. Minor, but it is precisely the cross-site naming/import inconsistency this audit exists to surface, and it makes the file's import block an incomplete picture of its dependencies.
- **Suggested fix scope:** small (one-file, mechanical)
- **Suggested fix:** Add `import java.util.UUID` to the import block (alphabetically with the other imports) and change line 60 to `private fun newSessionId(): String = UUID.randomUUID().toString()`.

## Out-of-scope observations (for the consolidator)

- None requiring cross-topic routing. The C1-A1 `Dev-1` (`StepStarted` does not set `totalSteps`) and C2-A2 `Dev-1`/`Dev-2` (sessionId payload-widening, `StopRecordingAndSend` → `data object`) are plan-conformity / API-contract concerns already flagged as `IMPL-PLAN-FIX-*` for AUDIT-PLAN-AND-API / AUDIT-LOGIC — not convention findings, noted here only so the consolidator does not double-count them under convention.

## Coverage

- **Files audited:** all 8 production files in the B1 diff (`58bb9a1..HEAD`) + 3 representative test files (PipelineModuleTest, RecordingModuleTest, ActionResolversTest) + `PipelineOrchestrator.kt` as the UUID-convention reference.
- **Files skipped (with reason):** the ~10 mechanically contract-updated test files (`AudioModuleTest`, `ViewModeModuleTest`, `OverlayModuleTest`, `DictateUiStateTest`, `ActionHierarchyTest`, `render/*`, `layout/VisibilityMatrixTest`, `core/DictatePipelineServiceOverlayTransitionTest`, `LayoutCatalogTest`) — verified via `git diff` they contain only the mechanical `sessionId = "sid-test"` / payload-less `StopRecordingAndSend` / `(lang) -> …` fixture follow-through (Dev-3), no new convention surface; the state-file + block-report are docs, not code.
- **Knowledge-skill checkpoints applied:**
  - Kotlin-new-code: ✓ all new code is Kotlin (only `DictateInputMethodService.java` is the untouched legacy Java reference).
  - DictatePrefs sealed-class (no raw string keys): ✓ no preference access in the diff; F-15 reads `state.language.effective` (LanguageModule axis), not a raw key.
  - K-1 handwritten fakes / no Mockito-MockK: ✓ zero `mockk`/`Mockito`/`mock(`/`@Mock`/`Robolectric` in the new tests; reused `FixedAudioFileFactory` / `fakeModuleServices()` / `stubAudioFile()`.
  - K-4 no Android Context in JVM tests: ✓ no `android.content.Context` in the inspected pure-reducer tests.
  - Sealed-class pure-reducer pattern: ✓ all reducer arms remain pure; `elapsedMs` derived via `ctx.now` through the new private `elapsedSince()` helper (no side-effect in `reduce`); effects still flow via the established `Effect` mechanism.
  - `data object` vs `data class` convention: ✓ `StopRecordingAndSend` → `data object` matches the sibling payload-less actions (`PauseRecording`/`StopRecording`/`CancelRecording`); reducer arms correctly switched to singleton-equality `Action.RecordingAction.StopRecordingAndSend ->` matching the sibling `data object` match style.
  - Field naming (`isStarting`, `completedSteps`, `totalSteps`, `startedAtMs`, `elapsedMs`, `sessionId`): ✓ camelCase + `isXxx`-predicate consistent with surrounding `Running`/`ReprocessStaging` fields and the existing `PipelineUiState.*.sessionId` convention.
  - KDoc `@property` + `(F-NN, 2026-05-15)` stamp: ✓ matches the established sibling pattern in `PipelineModule.kt` (F-1/F-19/F-21) and the `@property` style across `DictateUiState.kt`.
  - DRY: ✓ both repeated concerns extracted to private helpers (`elapsedSince()`, `newSessionId()`) consistent with each other and the file-local helper style (`pipelineSessionId()`).
