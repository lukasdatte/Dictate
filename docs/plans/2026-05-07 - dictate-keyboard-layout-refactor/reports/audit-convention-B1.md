# Audit Report: convention (Block 1, scope: full-block)

**Agent-ID:** B1-AUDIT-CONVENTION
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (general patterns); knowledge-doc-format (Inline-Anchor: header / `@see` / gotcha). Project CLAUDE.md (Key Conventions) consulted as primary baseline.
**Files inspected:** 8
- `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (diff)
- `app/src/main/AndroidManifest.xml`
- `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt`
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt`
- Companions: `app/build.gradle`, `gradle/libs.versions.toml`, `app/src/main/res/values/strings.xml`

## Summary

- Critical: 0
- Important: 2
- Nice-to-have: 5

K-1 (handwritten fakes only) and K-4 (no Android Context unless explicitly justified) both clean: no Mockito / MockK references in the block diff; the only Robolectric tests are documented as the canonical K-4 opt-out (state-file Test-Strategy row for B1 explicitly anticipates "Robolectric only for FGS-boot-latency test in chunk C2"). Prefs access still goes through `DictatePrefsKt.get(...)` everywhere it appears in the diff (no raw-string-key regressions). No AI-SDK / DB leakage in B1 (out of scope for both chunks). Findings below are stylistic-consistency-class issues that will compound across blocks if not addressed.

## Findings

### AUDIT-CONVENTION-B1-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:75` (vs. file-level convention)
- **Description:** The new `predResendVisible` introduces a brand-new naming convention (`pred` prefix for boolean predicates) that has zero prior usage in the codebase. Every other boolean function in `core/` uses the standard Kotlin `isXxx` prefix: `KeyboardUiController.isPipelineRunning / isPipelineActive / isBusy / isReprocessStaging`, `ActiveJobRegistry.isAnyActive / isActive`, `PipelineOrchestrator.isRunning`, `BluetoothScoManager.isBluetoothAvailable`, `EditorIdentity.isSame`, `AutoFormattingService.isEnabled`. The plan does call the helper `predResendVisible` (Spec 1 §11.2.2 step 1) and Block 4 / 5 will reference the name when lifting the body into LayoutCatalog — but those references are still inside the same plan, not Kotlin code that has already shipped. Once a third block files a `predXxx` helper, the codebase has a permanent two-convention split (`isXxx` for state-bound helpers, `predXxx` for "pure visibility predicate"). No prior knowledge-skill or ADR establishes the `pred` prefix as a distinct concept.
- **Why it matters:** Same-operation-two-ways drift that will be very expensive to undo later — every future predicate lifted from a UI controller will have to pick a side. The companion function on the same file (`resolveResendVisibility`) already uses the standard `resolveXxx` Kotlin verb-prefix (which matches `PromptService.resolveWhisperStylePrompt`, `LanguageLabelResolver.resolveLabel`, `AIOrchestrator.resolveParameters`), so the inconsistency lives in one file.
- **Suggested fix scope:** small (one-file rename + plan annotation that the rename is the agreed convention — the plan text uses `predResendVisible` as a working-title, not a hard contract). Either rename `predResendVisible` → `isResendVisible` to align with the existing `isXxx` convention (preferred), or document a new section in CLAUDE.md / a knowledge skill stating `predXxx` is the convention for pure UI-state predicates and call it out so Block 4 + 5 stay consistent.
- **Suggested fix:** Rename to `isResendVisible` (or `shouldShowResend`) and update the four call sites in `KeyboardVisibilityPredicatesTest.kt` + the two `resolveResendVisibility` call paths in `RecordingUiController.kt` + `DictateInputMethodService.java`. Plan's working-title can be referenced in the KDoc.

### AUDIT-CONVENTION-B1-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:1-19`
- **Description:** KDoc style for the new file is consistent with the rest of the codebase (`/** ... */`), and the `@see` inline anchors are present per `knowledge-doc-format` §"Inline anchors" — good. However, the `@see` URI form differs across the two new files. `DictatePipelineService.kt:51-52` uses a path with a literal embedded space (`docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/...`) which is not valid Kdoc link syntax; the same path is used in `DictatePipelineServiceTest.kt:53-54` and `KeyboardVisibilityPredicates.kt:50-51`. IntelliJ Kdoc-linker will render these as plain text rather than resolve them. Compare with the more typical `@see docs/decisions/0003-service-foreground-pipeline-architecture.md §"Required mechanics"` form on `DictatePipelineService.kt:51` (which works because the path has no spaces). The mixed form means readers cannot reliably ctrl-click the plan reference.
- **Why it matters:** Once additional inline-anchor `@see` references land in subsequent blocks, the same path will be re-copied — entrenching a broken link form across the codebase. The `knowledge-doc-format` skill specifies inline anchors should be navigable.
- **Suggested fix scope:** small (one-file pattern — adopt a single form: either backticked path-with-spaces in `@see` blocks (`@see \`docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/.../1-pipeline-service.reviewed.md\` §7`), or move the path into a constant / replace with a markdown link in the inline-comment form already used elsewhere in the codebase).
- **Suggested fix:** Pick one convention for "plan path with spaces in @see anchor" and document it once (CLAUDE.md or `knowledge-doc-format` skill addendum). Apply to all three new files in B1 before the form becomes load-bearing.

### AUDIT-CONVENTION-B1-3

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:300`
- **Description:** The `LocalBinder.service` getter returns a direct reference to the hosting service instance (`val service: DictatePipelineService get() = this@DictatePipelineService`). This is an architecture-by-stub: the contract is documented as the eventual `state: StateFlow<DictateUiState>` surface (Spec 1 §5, ADR-0003), but the Block-2 placeholder is a wide-open back door. The KDoc says "Block 1b can layer `state: StateFlow<DictateUiState>` on top without changing the binder's sealed signature" — but the `service` accessor itself widens the binder API: any IME-side caller can reach into arbitrary service-private fields once they have the binder. The IME-side currently does NOT use this (it only assigns `pipelineBinder` and never dereferences `.service`), but the convention shapes Block 1b: the spec's "sealed contract" is undermined the moment a Block 1b caller does `binder.service.someInternal()` instead of going through `state` / `dispatch`.
- **Why it matters:** Same-operation-two-ways drift waiting to happen — Block 1b will be tempted to use `binder.service` as an escape hatch. If the contract is `state + dispatch`, the binder should expose only those (the binder can hold a private reference to the service and route through it).
- **Suggested fix scope:** small. Either narrow `service` to `internal` (no IME-side access), or rename it to make the test-only intent explicit (`@VisibleForTesting val serviceForTests: DictatePipelineService`). Since IME-side does not use it, the simplest fix is `internal val service: DictatePipelineService` — Block 1b's coordinator can still see it from within the same module.
- **Suggested fix:** Mark `LocalBinder.service` as `internal` (Kotlin module visibility) so the binder's IME-facing public surface is just `dispatch`. Block 1b layers `state` on top as planned. Add a one-line KDoc note that `service` is intentionally module-internal to enforce the sealed contract.

### AUDIT-CONVENTION-B1-4

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:312`
- **Description:** `@Suppress("UNUSED_PARAMETER")` on `dispatch(action: Any)` masks a real warning that the parameter is never read. This is fine for a Block-2 stub, but the suppression is annotation-class — once Block 1b widens to `dispatch(action: Action): DispatchOutcome` and actually consumes `action`, the `@Suppress` must be removed. There's no mechanism (lint rule, ktlint TODO marker) reminding Block 1b to drop the annotation. Compare with `JobExecutor.kt:183` which uses the same `@Suppress("UNUSED_PARAMETER")` — both will need to be revisited.
- **Why it matters:** Drift accumulation — `@Suppress` annotations that survive their intended lifetime are a known low-grade rot. Block 1b's diff-review may not remember to remove this.
- **Suggested fix scope:** small. Add `// TODO(Block 1b): drop @Suppress once dispatch routes to orchestrator` or similar marker on the same line as the annotation, so a grep `TODO(Block` from Block 1b implementer surfaces it.
- **Suggested fix:** Append a comment to the `@Suppress` line (`@Suppress("UNUSED_PARAMETER") // TODO(Block 1b): remove when action is forwarded to orchestrator`).

### AUDIT-CONVENTION-B1-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:319-360`
- **Description:** The new `ServiceConnection` is declared inline as an anonymous class on the field site (~50 lines). The existing Java file already has comparable patterns (`PromptQueueManager.PromptQueueCallback`, `recordingStateCallback`) but those are usually extracted to dedicated callback fields. The inline anonymous class works but is harder to navigate (IDE outline won't surface the four ServiceConnection callbacks separately) and the closure-over-`this` for the rebind path (`bindService(intent, this, BIND_AUTO_CREATE)`) is non-obvious.
- **Why it matters:** Future onSiteConnect-handler additions (Block 1b adds `state.collect { ... }` invocation here) will further inflate the anonymous class. Other large callback bodies in this file have been extracted as named inner classes.
- **Suggested fix scope:** small. Either extract `pipelineConnection` to a named private inner class (`private class PipelineConnection extends ServiceConnection { ... }`), or keep it inline but add `// ── Block 2 — ServiceConnection ──` section markers matching the section-marker style already used in the same file (`// ===== Block 2 — DictatePipelineService bind state =====` is one section marker; the inline-class body has none).
- **Suggested fix:** Add a section marker comment before each of the four callbacks inside the anonymous class so the IDE outline + grep can locate them. Block 1b extracts to a named class once state-collect lands.

### AUDIT-CONVENTION-B1-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/AndroidManifest.xml:25-28`
- **Description:** Block 2 adds four `<uses-permission>` entries in one group (FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW). The comment block describing them is good. However: `SYSTEM_ALERT_WINDOW` is a Block-6 permission declared early "so the manifest is touched only once" — but mixing a Block-6 permission into a Block-2 group invites the same problem the comment tries to prevent: a future block will copy the pattern and pre-declare its permissions, until the grouping no longer matches reality. Compare with `<service>` ordering: B2's `DictatePipelineService` entry comes immediately after the existing `DictateInputMethodService` entry, which matches a "service-declaration cluster" convention — that ordering is clean.
- **Why it matters:** Cross-block pre-declaration risks leaving stale permissions in the Manifest if Block 6 changes scope. The trade-off is documented but the convention "permissions for blocks that aren't yet wired" should be explicit.
- **Suggested fix scope:** small. Either keep SYSTEM_ALERT_WINDOW separate (its own `<!-- Block 6 -->` group below the Block 2 group), or move it to Block 6's own commit. Document the chosen convention.
- **Suggested fix:** Split into two comment blocks: one for B2 permissions (3 entries), one for B6 (1 entry) with a `<!-- TODO(Block 6) -->` header. Reduces the merge-conflict surface if Block 6's scope shifts.

### AUDIT-CONVENTION-B1-7

- **Severity:** Nice-to-have
- **File:** `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt:332-337`
- **Description:** `KeyboardVisibilityPredicatesTest` declares a private `data class Quadruple<A,B,C,D>` for the consistency-check destructuring. Two issues: (a) Kotlin's stdlib does not ship `Quadruple` (only `Pair` / `Triple`) so the workaround is justified, but the comment says "Avoids pulling in a `data class`-per-test artifact" which is misleading — `Quadruple` IS the test artifact. (b) The same pattern will be needed in `LanguageControllerTest`, `RecordingStateControllerTest`, and any future predicate test with more than three axes; declaring a private copy per test is the "Same-operation-two-ways" anti-pattern this audit topic checks for. There's already `app/src/test/java/net/devemperor/dictate/core/` with multiple controller tests but no shared `TestTuples.kt` helper.
- **Why it matters:** Block 4 + 5 will need the same data class for their 25-case truth-table tests (Spec 2 §14.2). Three copies of `data class Quadruple` is a drift trigger.
- **Suggested fix scope:** small. Extract `data class Quadruple` to a shared test helper (`app/src/test/java/net/devemperor/dictate/core/TestTuples.kt`) and keep it open for future N-tuple needs. Document in the file's header.
- **Suggested fix:** Move the `Quadruple` declaration to a sibling file in the test package (`internal data class Quadruple<...>`); update the import. Or use `Pair<Pair<A,B>, Pair<C,D>>` which the Kotlin stdlib provides natively (less readable but zero new artifact).

## Coverage

- **Files audited:** all production + test files in the B1 diff (`git diff bd8f1e6..HEAD`).
- **Files skipped (with reason):** none. The B1 diff also touches `gradle/libs.versions.toml`, `app/build.gradle`, `app/src/main/res/values/strings.xml`, and the B1 block-report — those were inspected for convention drift (clean — comment style + new entries follow the existing structure).
- **Knowledge-skill checkpoints applied:**
  - CLAUDE.md "Key Conventions": Kotlin-for-new-code ✓ (DictatePipelineService.kt, KeyboardVisibilityPredicates.kt both Kotlin); Java legacy preserved ✓ (DictateInputMethodService.java still Java); prefs via DictatePrefs ✓ (all six new pref reads use `DictatePrefsKt.get(sp, Pref.X.INSTANCE)`); no AI-SDK in B1 diff ✓; no DB code in B1 diff ✓.
  - K-1 (handwritten fakes only): no `org.mockito` / `io.mockk` imports in either new test file ✓.
  - K-4 (no Android Context in JVM unit tests): explicit Robolectric opt-out for `DictatePipelineServiceTest.kt` is documented in (a) class-level KDoc (lines 25-55), (b) `gradle/libs.versions.toml` comment block (lines 22-27), (c) `app/build.gradle` (lines 50-56 + 80-86), (d) state-file `### Test-Strategy` row for B1. The four-place documentation chain is solid. `KeyboardVisibilityPredicatesTest.kt` is pure JVM (no Robolectric) — class KDoc explicitly states "K-4: JVM unit runner, no Robolectric / no Android Context" ✓.
  - Naming consistency: predicate file vs. ServiceXxx vs. test class — see Findings B1-1 (predicate-prefix drift). `DictatePipelineService` matches Dictate-prefix-pattern (DictateInputMethodService / DictateDatabase / DictateApplication) ✓. Test class names follow `XxxTest` ✓.
  - KDoc + inline-anchor patterns (`knowledge-doc-format`): `@see` anchors present in both new Kotlin files ✓ but with the path-with-spaces formatting issue noted in Finding B1-2.
  - Import ordering: both new Kotlin files follow the codebase's existing alphabetical-within-group order ✓.
  - Logging: `DictatePipelineService.kt` has no `Log.*` calls — explicitly removed during Step 3 (Self-Code-Fix) per block report (clean). `DictateInputMethodService.java` adds one `Log.e("DictateIME", ...)` call in the new `onNullBinding` callback (line 362) — matches the existing `Log.w("DictateIME", ...)` tag convention in the file ✓.
  - Constructor parameter style: `KeyboardUiController(views, stateManager, dictateButtonTextProvider = { "" })` uses Kotlin default-arg + Java named-call ✓.

## Out-of-scope observations (for the consolidator)

- **LOGIC topic candidate:** `LocalBinder.dispatch(action: Any)` accepts arbitrary Any payloads with no type discipline whatsoever. Block 2 documents this as intentional, but the test (`localBinderDispatch_isNoOp_butCountsInvocations`) passes string literals as actions. If the actual `Action` sealed class lands in Block 1b with strict pattern-matching, the test will need to be rewritten — flag for AUDIT-LOGIC's branch-coverage review.
- **TEST topic candidate:** `KeyboardVisibilityPredicatesTest` documents 17 tests but actually contains 16 `@Test` methods (the file's audit report references "17 JUnit 4 tests"). Cross-reference with AUDIT-TEST's coverage assessment.
- **PLAN-AND-API topic candidate:** Deviation D5 (`JobExecutor.initialize` not moved to Service.onCreate per Spec 1 §11.2.2 Block-2 sub-step 7) is flagged Important + delegated. The deferred fix is documented but the IME-side `initLongLivedObjects:389` still wires JobExecutor against the IME service rather than against the new `DictatePipelineService` — verify the deferral does not leak into Block 1b's composition-root design.
