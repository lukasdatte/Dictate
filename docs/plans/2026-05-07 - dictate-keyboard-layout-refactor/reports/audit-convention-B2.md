# Audit Report: convention (Block 2, scope: full-block)

**Agent-ID:** B2-AUDIT-CONVENTION
**Date:** 2026-05-15T01:19:39Z
**Knowledge skills used:** knowledge-doc-format, knowledge-reference
**Files inspected:** 30
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiStateStore.kt`
- `app/src/main/java/net/devemperor/dictate/state/InsertionTarget.kt`
- `app/src/main/java/net/devemperor/dictate/state/ModuleId.kt`
- `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt`
- `app/src/main/java/net/devemperor/dictate/state/SideEffect.kt`
- `app/src/main/java/net/devemperor/dictate/state/TestOnlyModules.kt`
- `app/src/main/java/net/devemperor/dictate/state/TransitionResult.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/*.kt` (14 module files)
- `app/src/test/java/net/devemperor/dictate/state/*.kt` (sampled)
- `app/src/test/java/net/devemperor/dictate/testutil/FakeModuleServices.kt`
- `app/src/test/java/net/devemperor/dictate/testutil/FakeSharedPreferences.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/proguard-rules.pro`
- `gradle/libs.versions.toml`
- `app/build.gradle`

## Summary

- Critical: 0
- Important: 2
- Nice-to-have: 4

## Findings

### AUDIT-CONVENTION-B2-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt:48-124` (class KDoc)
- **Description:** The new `DictateOrchestrator` (state-action-router) shares the name root "Orchestrator" with the legacy `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (audio-pipeline runner), but the `DictateOrchestrator` class KDoc contains **no naming disambiguation**. The legacy `PipelineOrchestrator` is referenced only in `core/DictatePipelineService.kt` lines 72-83 ("What is still B3 territory") and in the B2 block report's C7 "Overlooked points / known gaps" section. A reader landing in `DictateOrchestrator.kt` first has no signal that another type with a confusable name exists, lives in a different package, and serves a different role.
- **Why it matters:** Both orchestrator types co-exist throughout Block 2's lifetime and Block 3's migration window (per the C7 deviation: "Two PipelineOrchestrator types now co-exist with the IME-vs-Service split"). Without a one-line disambiguation in the new class's KDoc, code-reviewers, future implementers, and the agentic-implementer all risk routing references and lookups to the wrong type. The disambiguation belongs in `DictateOrchestrator`'s class KDoc (1-2 lines), in `architecture/state-architecture/README.md`, or in both — the engineering-principles SSoT rule says "documented once, referenced from there".
- **Suggested fix scope:** small (one-file edit: 2-4 KDoc lines in `DictateOrchestrator.kt`; optionally a mirroring sentence in `architecture/state-architecture/README.md`).
- **Suggested fix:** Add to the `DictateOrchestrator` class KDoc (after "Composition root of the state-mutation pipeline."): *"**Note on naming.** This is the **state-action-router** introduced by ADR-0001. The legacy `net.devemperor.dictate.core.PipelineOrchestrator` is the **audio-pipeline runner** (transcription/completion + DAO writes); the two are unrelated and co-exist during the Block 2 → Block 3 migration."* Same content in `state-architecture/README.md`.

### AUDIT-CONVENTION-B2-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:158-172`
- **Description:** `OverlayModule.runEffect` writes to `SharedPreferences` using **raw string keys** (`"overlay_pos_portrait_x"`, `"overlay_pos_portrait_y"`, `"overlay_pos_landscape_x"`, `"overlay_pos_landscape_y"`, `"overlay_onboarding_shown"`, `"overlay_onboarding_dismissed"`). This violates the project-wide convention from `CLAUDE.md`: *"Preferences are always accessed through `DictatePrefs.kt` sealed class — never use raw string keys."* The C7 deviation note acknowledges that four of the six keys (the overlay-position ones) are also accessed raw from `PipelinePrefMirror.kt` (as named `companion object` constants `OVERLAY_POS_PORTRAIT_X_KEY` etc.), but the two onboarding keys (`overlay_onboarding_shown`, `overlay_onboarding_dismissed`) are inlined as bare string literals in `OverlayModule` without even the constant-extraction guard — a future SP-key rename or migration would silently miss them.
- **Why it matters:** Two failure modes:
  1. **Type-safety drift.** `Pref` sealed-class entries carry a `defaultValue` + a typed read function. Raw-key callers re-implement that contract per-callsite; if a default ever needs to change, the `Pref` entry is updated centrally while the raw-key callers drift.
  2. **Grep-ability + migration safety.** A future `PrefsMigration.kt` entry that renames the underlying key will catch every `Pref.OverlayOnboardingShown` reference at compile time, but raw `"overlay_onboarding_shown"` literals will compile and silently target the old key.
- **Suggested fix scope:** small (add 6 entries to `DictatePrefs.kt`'s `Pref` sealed class; replace the raw literals in `OverlayModule.kt` and `PipelinePrefMirror.kt`).
- **Suggested fix:** Promote the 6 overlay keys to `Pref.OverlayPositionPortraitX/Y`, `Pref.OverlayPositionLandscapeX/Y`, `Pref.OverlayOnboardingShown`, `Pref.OverlayOnboardingDismissed` in `DictatePrefs.kt`. Replace the raw-key usages in both `OverlayModule.runEffect` and `PipelinePrefMirror.applyChange` / `initialMirror` with the typed entries. The C7 block-report flags this as "Phase-2 cleanup" but the `CLAUDE.md` convention is project-wide — Phase 1 is the right time to fix it since the module-write surface is fresh.

### AUDIT-CONVENTION-B2-3

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:8-10` (and sibling modules: `PendingSessionsModule.kt:7-11`, `LayoutModule.kt:7-8`)
- **Description:** Import order is **non-uniform across the 14 modules**. Examples:
  - `PipelineModule.kt`: `kotlinx.coroutines.launch` → `java.io.File` → `kotlin.reflect.KClass` (Kotlin Style Guide says single alphabetical block; this is interleaved).
  - `PendingSessionsModule.kt`: `kotlinx.collections.immutable.*` (×3) → `kotlinx.coroutines.launch` → `kotlin.reflect.KClass` (`kotlinx` before `kotlin`).
  - `LayoutModule.kt`: `net.devemperor.dictate.core.ContentArea` → `kotlin.reflect.KClass` (third-party `net.devemperor` before `kotlin`).
  - `OverlayModule.kt`, `RecordingModule.kt`: `java.io.File` → `kotlin.reflect.KClass` (alphabetical OK).
  - `AudioModule.kt` / `ResendModule.kt` / `LivePromptModule.kt` / `LanguageModule.kt` / `ThemingModule.kt` / `FeatureToggleModule.kt` / `InterruptionModule.kt` / `ViewModeModule.kt`: only `kotlin.reflect.KClass` (trivially fine).
  - `KeyboardInputModule.kt`: `android.content.ClipData` → `kotlin.reflect.KClass` (alphabetical OK).
- **Why it matters:** This is the textbook "same-operation-two-ways" drift the convention-audit topic exists to catch. The project has no committed formatter (`CLAUDE.md`: "No linter or formatter is configured."), so the convention has to live in reviewer-discipline; an inconsistency this small in the first 10 lines of every module file is the place readers train their pattern-matcher on. Future developers will reproduce whichever variant they last looked at.
- **Suggested fix scope:** small (mechanical — re-order imports in `PipelineModule.kt`, `PendingSessionsModule.kt`, `LayoutModule.kt` to single alphabetical block by full FQN).
- **Suggested fix:** Apply the IntelliJ default "Optimize Imports" pass across all 14 module files. The convention should land in `architecture/state-architecture/adding-a-module.md` (one line: "imports sorted alphabetically as a single block — IDE default") so the next module that lands doesn't recreate the drift.

### AUDIT-CONVENTION-B2-4

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:60-63` vs `AudioModule.kt:35-37` vs `LayoutModule.kt:45-49`
- **Description:** The number of `@see` anchors per module varies considerably and is inconsistent in the ordering of doc-types:
  - Most modules have 3 anchors: `state-type` → `Action-type` → `Spec §` (e.g. `AudioModule`, `ResendModule`, `LanguageModule`, `ThemingModule`).
  - `RecordingModule.kt` has 7 anchors and starts with state/Action then **two ADR pointers** before the spec § (a richer pattern).
  - `LayoutModule.kt` has 5 anchors including a `ContentArea` cross-ref (rare).
  - `ViewModeModule.kt` has 4 anchors and starts with `state-type` then jumps to `ADR-0005`.
  - `KeyboardInputModule.kt` has only **2** anchors (no `Action` anchor — the smallest set in the block).
  - `PipelineModule.kt` has 4 anchors but the `data class` `PipelineUiState` is **not** in the `@see` list (only the module's KDoc).
- **Why it matters:** `knowledge-doc-format` SKILL §"Inline anchors" recommends one anchor per decision point. The block has a consistent **intent** (state + action + spec + ADRs that bind), but the **execution** is inconsistent — readers can't predict whether they'll find the binding ADR in the `@see` list of a given module. The richer pattern (state + action + ADR-0001 + ADR-0002 + spec §) is the implementer-ready bar; the leaner pattern (state + action + spec §) is acceptable for axis-pure modules but should be declared as such.
- **Suggested fix scope:** small (one-file edits across 14 modules to harmonise the anchor set to a documented baseline).
- **Suggested fix:** Adopt a minimal-anchor convention in `architecture/state-architecture/adding-a-module.md`: *"Every module's class KDoc carries at least: (a) `@see` for its `XxxState`/sub-state type, (b) `@see` for its `Action.XxxAction`, (c) `@see` for the spec `§15.x` and any binding ADR (ADR-0001 always; ADR-0002 if it emits cross-module cascades)."* Then bring KeyboardInputModule and the leaner modules up to that floor. The richer pattern (RecordingModule) is fine to keep.

### AUDIT-CONVENTION-B2-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/InterruptionModule.kt:42`
- **Description:** `InterruptionModule` declares its sub-state as `InterruptionState?` — a nullable type — while all 13 other modules declare a non-nullable state type. The KDoc explains the `null` default ("the axis is unmodelled until Phase-2 populates the listener wiring"), and the `read`/`write` signatures correctly thread the nullable through. But this is the **only** Phase-1 stub module, and it sets a precedent for future Phase-2 modules to follow.
- **Why it matters:** Two confusable patterns now exist for "module not yet active in Phase 1":
  1. **Null sub-state** (`InterruptionModule` — `InterruptionState?`).
  2. **Non-null sub-state with reducer returning `null`** (`LanguageModule.RefreshFromPref`, `FeatureToggleModule.ToggleVibration`, `LivePromptModule.ChainNext` for missing-pending case).
  The two patterns express the same concept ("dispatch lands but no state change in Phase 1") with different mechanics. A future Phase-2 module-author has to pick one; without a guide, drift is likely.
- **Suggested fix scope:** small (one paragraph in `architecture/state-architecture/adding-a-module.md` documenting the decision).
- **Suggested fix:** Add a sub-section to `adding-a-module.md` titled "Phase-stub patterns": (a) if the sub-state's shape itself is unknown until Phase 2 → nullable type with `initialState() = null`; (b) if the shape is known and only the reducer's behaviour is Phase-2 deferred → non-nullable type with `reduce` returning `null` for the deferred arms. `InterruptionModule` follows (a), `FeatureToggleModule.ToggleVibration` follows (b). Documenting the picking-rule cuts the drift risk.

### AUDIT-CONVENTION-B2-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/Action.kt` — package-level structure
- **Description:** All `data class` Action variants in `Action.kt` carry inline KDoc (e.g. `RecordingAction.StartRecording`, `PipelineAction.TriggerPipeline`), but `data object` variants are inconsistently documented:
  - Some have KDoc (e.g. `PipelineAction.ClearManualPasteFlag:181`, `RecordingAction.StopRecordingAndSend:119-124`, `ResendAction.ResendLastAudioLong:243`).
  - Most don't (e.g. `RecordingAction.PauseRecording:114`, `RecordingAction.ResumeRecording:115`, `RecordingAction.StopRecording:116`, `RecordingAction.CancelRecording:117`, `ViewModeAction.ToggleViewModeWidget:190` is documented, but `OnImeViewShown` and `OnImeViewHidden` are not, etc.).
  - `FeatureToggleAction`'s five `data object` toggles have **no** per-action KDoc — the only signal that one of them (`ToggleVibration`) is special is in the module-level KDoc, not the action-level.
- **Why it matters:** The `data object` actions are the leaves the reducer-`when` blocks branch on. For most their semantics are clear from the name (`StopRecording` = stop the recording). But cases like `ResendAction.MarkLastAudio(exists)` — only documented as "Cross-module cascade target — emitted after PipelineDone" — and `FeatureToggleAction.ToggleVibration` (the deviation case) need their special status near the leaf, not only in the module-level prose. A reader doing "go to declaration" from a `when` arm lands on the leaf, not the module.
- **Suggested fix scope:** small (add a one-line KDoc to the ~5-8 `data object` leaves that have non-obvious semantics: `ToggleVibration`, `RefreshFromPref`, `MarkLastAudio`, `OnPipelineDone`, `ResendCooldownExpired`, `ClearManualPasteFlag`).
- **Suggested fix:** Where a `data object` is a cross-module cascade target or carries a documented special case, add a 1-line KDoc that names the special status. `// Cross-module cascade target — emitted after PipelineDone` is sufficient.

## Out-of-scope observations (other topics)

- (LOGIC) `PipelineModule.runEffect` uses `services.scope.launch { ... }` for the suspend `sessionRepo.markInserted/markFailed` call. The block-report flags this as IMPL-3 — the "fire-and-forget" pattern is acknowledged but the failure of the SP-write itself does not loop back into the state. Worth a LOGIC-audit-agent check.
- (PLAN-AND-API) The C7 deviation `DictateUiStateObserver.kt` Java bridge NOT added — the plan §4.4 cites it as a Block-2 acceptance pre-condition. Worth a PLAN-AND-API check.

## Coverage

- **Files audited (full-block scope):** all 14 production module files; all production state-package files; both testutil files; relevant ProGuard + Gradle config; block report B2.
- **Files skipped (with reason):** the 27 test files in `app/src/test/java/net/devemperor/dictate/state/` were spot-checked (DictateOrchestratorTest, FakeSharedPreferences, FakeAudioFocusGate, FakeModuleServices) but not read end-to-end — test coverage of conventions is the AUDIT-TEST agent's primary surface, not this one. Relevant K-1 / K-4 checks across the test tree were verified by grep (no Mockito/MockK, no Robolectric in state/).
- **Knowledge-skill checkpoints applied:**
  - `knowledge-doc-format` §"Inline anchors" — three-anchor pattern (module header / `@see` plan/ADR / gotcha comment) — used to evaluate AUDIT-CONVENTION-B2-4 + B2-6.
  - `knowledge-doc-format` §"SSoT — anti-redundancy rule" — used to evaluate the cross-module-cascade and reducer-purity references (verified: modules reference Coupling-Matrix § rather than redefining).
  - `knowledge-reference` Quick-Reference table — no `versioned-envelope` / `plugin-system` patterns in B2 scope.

**Verification: project-convention checklist from CLAUDE.md:**

- [x] New code Kotlin / legacy Java (B2 is 100% Kotlin in production + tests).
- [x] No raw-string-key preference access *except* in `OverlayModule.runEffect` (raised as B2-2 above).
- [x] No AI-SDK calls from module reducers (`grep` finds zero `AIOrchestrator`/`RunnerFactory`/`openai`/`anthropic` in `state/`).
- [x] Database access through interfaces — `PipelineSessionRepoSubsystem` interface; concrete DAO wiring deferred to B3.
- [x] K-1 (handwritten fakes only) — `grep mockk|mockito` in `app/src/test/java/.../state/` returns zero matches.
- [x] K-4 (no Android Context in JVM tests of state/) — `grep "Robolectric|@RunWith"` in `app/src/test/java/.../state/` finds only a comment-mention; the Robolectric service test lives in `core/` per the documented K-4 exception.
- [x] ProGuard keep-rule covers `Action` sealedSubclasses; `DictateModule::class.sealedSubclasses` is **not** invoked (verified by grep) so no separate keep-rule needed.
- [x] All 14 module files named `XxxModule.kt`; all 14 action sub-classes named `XxxAction`; all 14 module-id `data object`s match `ModuleId.{Name}`; all 14 test files named `XxxModuleTest.kt` — perfect alignment.
- [x] Sealed-class declaration style: `sealed class Action` (extensible with constructor args) + 14 inner `sealed class XxxAction : Action()` + leaves as `data class` / `data object` (Kotlin 1.9+ idiom) — consistent across the block.
- [x] State-axes: `sealed interface RecordingState/PipelineUiState` (tagged union with no shared behaviour); `enum class ViewMode/ScoPhase`; `data class` for flat-data axes — picking-rule is consistent.
- [x] Effect surfaces: `sealed interface Effect : SideEffect` — all 14 modules use the same form. Empty (`Effect`) for the 7 pref-mirror modules; populated for the 7 effect-emitting modules. No drift.
- [x] All `Pref` reads in production code (`PipelinePrefMirror.kt`) use the typed `Pref.X` entries — modulo the documented `Pref.OverlayPos*` gap (which is the B2-2 finding above).
- [x] Cross-module-cascade rule is defined ONCE (ADR-0002 + `architecture/state-architecture/cross-module-cascade.md`); per-module KDoc references "Coupling-Matrix §15.1.x" rather than redefining the rule.
- [x] Reducer-purity rule is defined ONCE (ADR-0001 + `architecture/state-architecture/forbidden-patterns.md`); per-module KDoc references ADR-0001 §"Pure-Reducer Invariant" rather than restating.
