# AUDIT-TEST Findings — Block 6

**Agent-ID:** `B6-AUDIT-TEST` · **Scope:** full-block (`git diff cef46be..HEAD`, HEAD=3aa254d)
**Block:** B6 Theme-D test-completion — C11-D1 (Espresso UI-1..10 bodies + Robolectric mirrors) + C12-D2 (verification-only, zero delta)
**SoT:** Spec 2 §14.2 Integration-Tests table + §1.1 bug-symptom map (#1/#2/#3a/#3b), parent reviewed plan §1.1 (`2026-05-07 - dictate-keyboard-layout-refactor`)
**Suite runs:** uncached, both variants, 2× different order — see Coverage / stdout.

---

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|----|-------|----------|-------------------|--------|

none. Step-0 check: C11-D1 found exactly one **test-bug** (its own UI-1 exact-set-equality, fixed in Step 4) and documented it correctly in `### Code-Bugs Found During Test Self-Review` (B6-theme-d-test-completion.md:125-135) with file:line (`KeyboardLayoutRenderMirrorTest.kt:166`), bug-symptom (`AssertionError`), root-cause (catalog gained WIDGET_TOGGLE post-§14.2-prose), fix (exact-set → `containsAll`), and research-source (B5 F-2 / §1.1 #1 invariant). No production code changed by any IMPL-TEST/IMPL-TEST-FIX agent — confirmed `git diff cef46be..HEAD -- app/src` = the 2 C11 test files only; commit 397bfbd touched only the 2 test files + block-report; commits 6f31230/3aa254d are state/report only. C12-D2 zero-delta confirmed (no `DictateCutoverFinalE2ETest.kt` — RR-4 vacuous-duplication avoidance correctly documented as Dev-1). **No `test-agent-undocumented-code-fix` finding.**

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|----|-------|----------|-----------|--------|
| AUDIT-TEST-B6-1 | UI-4 + UI-10 §1.1 #3a assertions are **partially vacuous** for the SEND_MODE TRASH/PAUSE eliminator they claim to guard | Nice-to-have | `KeyboardLayoutUiTest.kt:262-271, 442-453` + mirror `KeyboardLayoutRenderMirrorTest.kt:235-244, 378-389` | open |

**AUDIT-TEST-B6-1 detail.** UI-4 / UI-10 assert `TRASH==GONE` / `PAUSE==GONE` in a pipeline (`Running`/`Preparing`) state and label it the "§1.1 #3a eliminator (TRASH/PAUSE hardcoded `{ false }` in SEND_MODE)". Non-vacuity test: if the SEND_MODE `visibilityPredicate = { false }` for TRASH/PAUSE (LayoutCatalog.kt:297, 310, 335, 355) were reverted to `::isTrashVisible` / `::isPauseVisible`, would the test go RED? `isTrashVisible`/`isPauseVisible` = `recording.isActiveOrPaused || pipeline is ReprocessStaging` (LayoutPredicates.kt:73-91). In the chosen states (`pipelineRunning()` / `Preparing`: recording=Idle, pipeline NOT ReprocessStaging) both predicates evaluate **false anyway** → TRASH/PAUSE would still render GONE → **the test stays GREEN even with the eliminator reverted.** UI-4/UI-10 therefore guard the *mode-selection* (`forKeyboard` → SEND_MODE) and the *outcome* but **not the hardcoded-false eliminator itself** — a true §1.1 #3a regression test would need a state where `isTrashVisible` would be `true` (i.e. `recording.isActiveOrPaused`) while in SEND_MODE, which `forKeyboard` cannot produce (Active never selects SEND_MODE), so the only honest pin is a *direct catalog-slot* assertion.

**Mitigation (why NTH, not Important):** the §1.1 #3a hardcoded-false eliminator **is** independently and non-vacuously pinned by the pre-existing `VisibilityMatrixTest` (in the 1172): the "TWO_ROW + pipeline-running (cross-mode)" row (`VisibilityMatrixTest.kt:226-240`) applies the *standard* TWO_ROW slots against a pipeline-running state and asserts TRASH/PAUSE=false there — and `LayoutCatalogTest`/`VisibilityMatrixTest` pin the SEND_MODE slots' `{ false }` directly with the "bug #3a fix" comment (`VisibilityMatrixTest.kt:180-202, 277+`). So §1.1 #3a is covered; UI-4/UI-10's #3a label overstates what *those two tests* add (they are render-path *integration* re-confirmation, valuable for the mode-selection + applySlotToView path, but not the sole or even the sharp #3a guard). This is a **labelling/coverage-attribution** weakness, not a coverage hole. Repair option (optional, NTH): add one render-path assertion that drives the SEND_MODE *catalog mode object directly* against an `active()`-style state (bypassing `forKeyboard`) so a revert of the `{ false }` literal turns the render-path test RED too — or downgrade the in-test `(§1.1 #3a)` claim to "SEND_MODE structural-GONE outcome (eliminator pinned by VisibilityMatrixTest)".

**All other UI-N are non-vacuous** (per-test verdict in stdout): UI-1 (containsAll-8 → RED if trash/pause dropped from single-row slot list), UI-2 (isResendVisible/isTrash/isPause predicate-driven), UI-3 (F-13 counters flow verbatim through the injected formatter — RED if completedSteps/totalSteps were placeholders), UI-5 (REPROCESS_STAGING `enabledResolver={false}`/`alphaResolver={0.4f}` → RED if reverted), UI-6/UI-7 (mode-flip + controls-survival, assertNotEquals on the toggle), **UI-8 + UI-9 strongly non-vacuous** — UI-9 is the sharp guard for the Spec 2 §8.5 forbidden-pattern (j): if cooldown logic moved into `visibilityPredicate`, `isResendVisible` would drop RESEND during cooldown → RED; UI-8's 3-frame per-toggle check → RED if §1.1 #3b re-parenting-drop regressed.

**Dev-1 verdict (UI-1 subset-present vs exact-set):** **Spec-faithful, does NOT weaken the test.** §14.2 prose ("alle 8 Buttons") predates the WIDGET_TOGGLE slot (parent-plan S-6 Finding 3 / B5 F-2 → KEYBOARD_SINGLE_ROW now has 9 slots), so exact-set equality is *impossible to satisfy* and an "all 8 literally VISIBLE" assertion would itself be **vacuous-wrong** (TRASH/PAUSE/RESEND are GONE-by-predicate in Idle — that is correct catalog behaviour). The §1.1 #1 invariant per the parent §1.1 table is *structural*: "trash/pause not **dropped** on the single-row toggle (MotionLayout no-re-parent)". `slots.containsAll(originalSingleRowButtons)` is exactly that invariant and stays RED if any of the 8 are dropped from the mode. The remaining 5 unconditional buttons are additionally asserted `VISIBLE`. The deviation is documented (B6 report Dev-1) with the correct rationale. Faithful.

**Dev-2 verdict (UI-3 deterministic test-local LayoutStrings formatter):** **Spec-faithful, preserves the §1.1/F-13 invariant.** §14.2's `"1/3 0:01"` is explicitly illustrative of *counter shape*; the SoT intent (parent §1.1 / F-13) is "live `completedSteps/totalSteps` render, not placeholders". The test injects a deterministic `formatPipelineLabel(done,total,autoEnter,elapsedMs)` and asserts `"1/3  1000ms"` from `Running(completedSteps=1, totalSteps=3, elapsedMs=1000)` — the F-13 fields flow through *verbatim*, so a placeholder-counter regression (e.g. `"…/…"`) turns it RED. Mirrors the existing `state.layout.testLayoutStrings` convention; kept source-set-local to avoid a cross-source-set `internal` dependency (justified, documented Dev-2). The literal `"1/3 0:01"` would have tested the production `Context.getString` formatter (out of §14.2 scope, C14 wiring) instead of the F-13 plumbing — the deviation strengthens, not weakens, the targeted assertion. Faithful.

**Body ↔ mirror equivalence verdict:** **byte-identical — a divergence would be a real regression, not a harness artefact.** Diffed `KeyboardLayoutUiTest.kt` vs `KeyboardLayoutRenderMirrorTest.kt`: identical `render()` harness (real `KeyboardLayoutManager.computeLayoutMode` + production `applySlotToView` over real `MaterialButton` Views), identical fixtures (`idle`/`idleWithLastAudio`/`active`/`pipelineRunning`), identical `originalSingleRowButtons` set, identical assertions per UI-N **including UI-3's `"1/3  1000ms"`** (both `uiTestLayoutStrings()` and `mirrorLayoutStrings()` define byte-identical `formatPipelineLabel`). The only differences are harness-justified and assert-neutral: mirror runner `@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])` + `ContextThemeWrapper(app, R.style.Theme_Dictate)` (Robolectric needs an explicit themed context) vs body `@RunWith(AndroidJUnit4::class)` + raw application context. **No mirror asserts LESS than its Espresso twin** — every `assertEquals`/`assertTrue`/`assertNotEquals` is present 1:1 in both. OQ-4 holds: the mirror is a faithful CI proxy for the device body.

**K-1 / K-4 verdict:** **clean.** Zero Mockito/MockK in either file (no `mock(`, `mockk`, `@Mock`, `every {`, `whenever(` — grep-confirmed); both drive the **real production** `LayoutCatalog` + `KeyboardLayoutManager` + `applySlotToView`. The only test-doubles are two hand-written deterministic `LayoutStrings` data builders (`uiTestLayoutStrings()` / `mirrorLayoutStrings()`) — handwritten fakes, no mock framework, mirroring the existing `state.layout.testLayoutStrings` convention. Robolectric in the mirror is the documented, justified opt-out (OQ-4 / §14.2 — assertions read real `View.visibility`/`alpha`/`isEnabled`/`text`, only observable through real Android Views; same shape as existing `ImeViewBackendTest`/`LayoutCatalogTest`). The androidTest body uses the real render-path harness against real Espresso-readable Views (no mock framework). K-1/K-4 satisfied.

**Test-naming:** all 10 follow `ui{N}_<state>_<expectedBehavior>` (behavior + condition) — compliant, no "works"/"test1".

**Cross-chunk regression:** none. The block diff is purely additive — `KeyboardLayoutUiTest.kt` grew from `@Ignore` stubs to real bodies, `KeyboardLayoutRenderMirrorTest.kt` is wholly new; **zero existing test file modified, zero src/main delta**. No sibling test weakened. Full uncached suite both variants ×2 orders = identical 1172/0/0 (debug) · 1172/0/0/16-skip (release) — no test that was green pre-block is now red. C12-D2 zero-delta verified (`git diff cef46be..HEAD -- app/src` = C11 files only).

## Coverage

| File | Branches % | Lines % | Untested-Branches |
|------|-----------|---------|-------------------|
| (pure-test block — no production-code delta; coverage-threshold check is N/A) | — | — | The block adds **test coverage only** (10 Robolectric mirrors land in the 1172; 10 Espresso bodies compile in androidTest, run on-device per OQ-4). No new/changed production file to measure branch-coverage against. AC-9 regression invariant ≥946: **1172 ≥ 946** with wide margin, identical across both variants and both orderings. |

**Suite execution (uncached, both variants, 2× different order):**

| Variant | Run #1 (debug→release) | Run #2 (release→debug) |
|---------|------------------------|------------------------|
| `testDebugUnitTest`   | **1172 tests, 0 fail, 0 err, 0 skip**  | **1172 tests, 0 fail, 0 err, 0 skip**  |
| `testReleaseUnitTest` | **1172 tests, 0 fail, 0 err, 16 skip** | **1172 tests, 0 fail, 0 err, 16 skip** |

- `assembleDebug` ✅ · `compileDebugAndroidTestKotlin` ✅ (Espresso UI-1..10 bodies compile in the androidTest source-set).
- `KeyboardLayoutRenderMirrorTest`: **10/10 green** in both runs (all `ui1_*`..`ui10_*` present + passing).
- The **16 release-skips are by-design** and **disjoint from the 10 mirrors**: they are the `assumeTrue(BuildConfig.DEBUG)`-guarded audit-ledger / no-double-write assertions (`RenderPathCutoverGateTest`, `VisibilityWriteAuditLoggerTest`, `RenderGateTest`, `KeyboardLayoutManagerTest`, `OverlayCharactersControllerTest` — Spec 2 §10 no-double-write is a debug-build acceptance criterion). The mirrors carry no `BuildConfig.DEBUG` guard → run green in *both* variants. Not a coverage gap.
- **C12-D2's 1172/0/0 (debug) · 1172/0/0/16-skip (release) independently CONFIRMED**, order-independent, zero R-7 flake (all 3 R-7 axes were already closed — any flake would be a new regression; none observed across either ordering). No KSP-cache env-flake (kspCaches removed pre-build; no split workaround needed).

## Cross-Chunk-Regressions

none.

## Helper-Konsolidierung

none requiring action. `uiTestLayoutStrings()` (androidTest) and `mirrorLayoutStrings()` (test) are near-identical `LayoutStrings` builders — this is **intentional First-Use** documented in the C11-D1 report ("deliberately local to avoid a cross-source-set `internal` dependency on `state.layout`'s `testLayoutStrings`"). They live in different Gradle source-sets (`androidTest` vs `test`) which cannot share a helper without promoting it to a published `internal`/`main` symbol — consolidation would *introduce* a cross-source-set coupling the C11 author explicitly (and correctly) avoided. The byte-identical `formatPipelineLabel` is load-bearing for the UI-3 body↔mirror equivalence guarantee. No consolidation finding.
