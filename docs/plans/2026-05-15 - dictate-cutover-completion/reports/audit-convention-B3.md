# Audit Report: convention (Block 3, scope: full-block)

**Agent-ID:** B3-AUDIT-CONVENTION
**Date:** 2026-05-16
**Knowledge skills used:** knowledge-reference (versioned-envelope pattern — to confirm `LanguageResolver` delegates to the SoT rather than reimplementing the envelope)
**Files inspected:** 18
- `app/src/main/java/net/devemperor/dictate/preferences/LanguageResolver.kt` (new)
- `app/src/main/java/net/devemperor/dictate/DictateApplication.java`
- `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/core/ImePipelineConfigResolver.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesPlugin.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesLegacyMigration.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/versioned/VersionedPrefs.kt`
- `app/src/test/java/net/devemperor/dictate/preferences/LanguageResolverTest.kt` (new) + the 6 other touched test files (mock/Robolectric grep only)

## Summary

- Critical: 0
- Important: 0
- Nice-to-have: 2

## Verified-clean convention checkpoints

The following were checked systematically and **hold**:

1. **New-code-Kotlin / legacy-Java-stays-Java (CLAUDE.md):** `LanguageResolver`
   is new code, written in Kotlin as an `object`. `DictateApplication.java`,
   `DictateInputMethodService.java`, `PreferencesFragment.java` were modified
   in place and **not** converted to Kotlin. PASS.
2. **Prefs only via `DictatePrefs` sealed class / no raw SP keys
   (CLAUDE.md):** `LanguageResolver` does **not** touch raw `SharedPreferences`
   string keys. It reads the curated list via `VersionedPrefs.load(prefs,
   InputLanguagesPlugin)` and writes **exclusively** through the existing
   `persistInputLanguagesAndPos` SoT (LanguageResolver.kt:111, :130);
   position reads go through `prefs.get(Pref.InputLanguagePos)` (the typed
   sealed accessor). Grep for `.edit()` / `.put(` / `VersionedPrefs.save` in
   `LanguageResolver.kt` returns only a KDoc mention — zero raw write paths.
   The versioned-envelope pattern (knowledge-reference) is respected: the
   resolver is a consumer of the envelope SoT, not a re-implementer. PASS.
3. **Stateless-helper/singleton convention:** `LanguageResolver` is a
   stateless `object` with `private const val TAG`. This matches every
   sibling in `preferences/`: `LanguageLabelResolver` (object),
   `InputLanguagesLegacyMigration` (object), `PrefsMigration` (object),
   `VersionedPrefs` (object with `private const val TAG = "VersionedPrefs"`
   — `LanguageResolver` mirrors this with `"LanguageResolver"`). The KDoc
   explicitly documents the no-mutable-state / thread-safety contract.
   PASS.
4. **KDoc on new public APIs:** every public function of `LanguageResolver`
   (`curatedLanguages`, `effectiveLanguage`, `setLanguage`,
   `setCuratedLanguages`) carries KDoc; the object header documents the
   why/boot-before-bind/threading. The new `RecordingState.audioFileOrNull`
   extension and `Action.LanguageAction.RefreshFromPref(effective)` payload
   are KDoc'd including `@property`. PASS.
5. **AC-5 literal-zero KDoc-scrub did not over-delete / leave dangling
   references:** `grep -rn "LanguageController" app/src/main` → zero hits.
   Every scrubbed KDoc reference was **replaced** with an accurate pointer
   (`LanguageResolver` / `LanguageState` / `preferences.LanguageResolver`),
   not blanked. Spot-checked `TextResolvers.kt`, `ImePipelineConfigResolver.kt`,
   `PipelineRunnerSubsystemAdapter.kt` (4 sites), `DictatePipelineService.kt`,
   `InputLanguagesPlugin.kt`, `InputLanguagesLegacyMigration.kt`,
   `VersionedPrefs.kt`, `KeyboardUiController.kt`, `PipelineUiStateReader.kt`
   — all rewrites preserve the original explanatory intent and update the
   "Used by" / cross-reference lists correctly. No meaningful doc lost; no
   dangling `{@link}` / `[...]` reference to the deleted class. PASS.
6. **`RecordingState.audioFileOrNull` follows the `isActiveOrPaused`-sibling
   convention:** top-level extension `val` placed directly after
   `isActiveOrPaused` in `DictateUiState.kt`, exhaustive sealed `when`
   (idiomatic `is` arms for the data classes + bare-object `Idle` arm),
   `File?` return. Matches the documented project convention the C9-C2
   Dev-1 deviation cites. PASS.
7. **K-1 handwritten fakes only:** grep of all 7 changed test files for
   `mockk` / `Mockito` / `org.mockito` / `mock(` returns **only** KDoc
   strings asserting "no Mockito / handwritten fake". `LanguageResolverTest`
   uses the project's handwritten `FakeSharedPreferences` (testutil),
   `@Before` `setUp`, plain JUnit asserts — fully handwritten-fake. PASS.
8. **K-4 (no Robolectric in pure resolver/reducer tests):** grep of changed
   test files for `Robolectric` / `RobolectricTestRunner` / `@Config`
   returns only KDoc strings stating these are pure-JVM / no-Robolectric.
   `LanguageResolverTest` and `LanguageModuleTest` are pure JVM. PASS.
9. **ADR-0001 single-dispatch:** `RefreshFromPref(effective: String)` is a
   pure data payload (a resolved code computed Pre-Dispatch per Spec 1
   §4.11). The reducer is I/O-free and idempotent (no-change → `null`). No
   second dispatcher or side-channel introduced. PASS.
10. **PipelineOrchestrator.kt KDoc boundary annotation — well-formed +
    knowledge-doc-format module-header conventions:** the OQ-1 block is a
    single additive section inside the existing class KDoc. Verified: one
    `/**` open, one `*/` close for the class doc (the new section is a
    `#`-heading **inside** the existing block, no nested `*/`, no second
    `/**`). It uses an ASCII delegation diagram in a fenced ``` block,
    `@see` anchors with fully-qualified names, and a Spec citation —
    consistent with the module-header anchor convention (header / `@see` /
    rationale). Additive only (no behaviour change; no existing doc text
    removed). PASS.

## Findings

### AUDIT-CONVENTION-B3-1

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1628-1639` (`resolveEffectiveLanguage`) and `:1797-1808` (`setLanguageFromPicker`)
- **Description:** The new private helpers re-derive the ReprocessStaging
  override with an inline `instanceof` cast + null/blank guard:
  `uiController != null && uiController.getState() instanceof
  PipelineUiState.ReprocessStaging` then cast-and-`getSelectedLanguage()`.
  The exact same staging-state-extraction shape is duplicated verbatim in
  both `resolveEffectiveLanguage()` and `setLanguageFromPicker()` (the
  guard expression `uiController != null && uiController.getState()
  instanceof PipelineUiState.ReprocessStaging` is byte-identical in both).
  The same operation is done two different shapes elsewhere too:
  `resolveEffectiveLanguage` trims+empty-checks the override
  (`override != null && !override.trim().isEmpty()`), whereas
  `setLanguageFromPicker` writes whatever it gets without that guard. This
  is a (mild) "same operation, two ways" convention smell — the staging-
  override extraction is a candidate for a single private helper (e.g.
  `private String stagingOverrideOrNull()`).
- **Why it matters:** Two copies of the staging-state probe can drift
  (one already differs on the blank-guard). It is the exact "same
  operation done two different ways across chunks" category this audit
  topic targets. Low blast radius (one file, IME-internal, post-cutover
  this collapses onto `LanguageState.override` in the blocked C10-C3
  anyway), hence Nice-to-have rather than Important.
- **Suggested fix scope:** small (one-file, mechanical — extract one
  private helper, call it from both sites).
- **Suggested fix:** Extract `private String reprocessStagingOverrideOrNull()`
  returning the trimmed non-blank override or `null`, and have both
  `resolveEffectiveLanguage()` and `setLanguageFromPicker()` branch on it.
  Note: the C8-C1 report's own "Overlooked points" already flags that the
  override has two carriers during the C8→C10 window and C10 must collapse
  it — so a reviewer may legitimately defer this to the (currently
  blocked) C10-C3 / render-path-cutover work rather than fix it now.

### AUDIT-CONVENTION-B3-2

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1644-1648`, `:1805-1809` (and pre-existing sites)
- **Description:** The new orchestrator-dispatch helpers wrap
  `pipelineBinder.dispatch(...)` in `try { ... } catch (Throwable t) {
  Log.w("DictateIME", "<msg> dispatch failed", t); }`. This matches the
  *pre-existing* deleted-bridge pattern exactly (the old
  `languageController bridge dispatch failed` catch used the same
  `catch (Throwable t)` + `Log.w("DictateIME", ...)` shape), so the new
  code is **convention-consistent with the surrounding file**. Flagged
  only as an observation: `catch (Throwable t)` (rather than the narrower
  exception the dispatch can actually throw) is broad, but it is the
  established in-file convention for guarded best-effort dispatch and the
  implementers correctly followed it rather than introducing a third
  error-handling style. No divergence — noted so the consolidator does
  not mistake the broad catch for a new inconsistency.
- **Why it matters:** Documents that the broad-catch is intentional
  in-file convention parity (not new drift), so it is not re-raised as a
  false-positive. No action recommended for this block.
- **Suggested fix scope:** small (none recommended — convention parity
  is correct; any narrowing should be a separate file-wide cleanup, not a
  B3 deviation).
- **Suggested fix:** No fix. Recorded for consolidator context only.

## Coverage

- Files audited: all 18 listed under "Files inspected" (full-block scope,
  every production + KDoc-scrub file in `git diff d39891a..HEAD`, plus
  mock/Robolectric grep across all 7 touched test files).
- Files skipped (with reason): The chunks.json / state.md / block-report /
  research markdown changes (process docs, not code). `LanguageController.kt`
  + `LanguageControllerTest.kt` (deleted — nothing to audit). C10-C3's
  4 controllers are **not** in the diff (deletion blocked by C10-IMPL-2
  architecture-conflict — out of convention-audit scope; flagged in the
  block-report Issue Index, not re-raised here).
- Knowledge-skill checkpoints applied: knowledge-reference / versioned-
  envelope — confirmed `LanguageResolver` is an envelope **consumer**
  delegating to `persistInputLanguagesAndPos` + `VersionedPrefs`, not a
  parallel re-implementation of the schema-versioned write path (no
  same-package drift hazard introduced).

## Out-of-scope observations

- (logic topic) `resolveEffectiveLanguage()` trims+blank-checks the
  staging override; `setLanguageFromPicker()` does not validate the
  picked code before `updateReprocessLanguage(code)` / `SetOverride(code)`.
  Asymmetry is benign (picker codes come from a curated stable-id map) but
  noted for the logic auditor.
- (plan-and-api topic) C10-C3's 4 controller deletions were not performed
  (Critical `architecture-conflict` C10-IMPL-2, already delegated). Pure
  convention audit cannot assess that; only the PipelineOrchestrator OQ-1
  KDoc landed for C10 and it is convention-clean (see checkpoint 10).
