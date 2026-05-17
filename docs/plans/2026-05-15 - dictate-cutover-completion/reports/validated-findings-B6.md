# Validated Findings — Block 6

**Agent-ID:** B6-VAL-SANITY
**Date:** 2026-05-17
**Source audits:** `reports/audit-test-B6.md` (0 Crit / 0 Imp / 1 NTH) — the only materially-run audit topic for B6 (focused Block-Validate: B6 is pure-test, zero `src/main` delta; PLAN-AND-API / LOGIC are n/a — no production logic; C12-D2 already produced the holistic AC-1..10 table). Light CONVENTION pass folded in by the consolidator on the 2 test files.

## Summary

- 🟢 valid + auto-fixable: **1** (Critical: 0, Important: 0, Nice: 1)
- 🟡 valid + research-needed: **0**
- ❌ eliminated: **0**
- Carried postponed (untouched, correctly carried → Phase 4.7): **2** NTH

B6 is clean enough to converge: one near-trivial 🟢 doc-honesty fix, zero 🟡, zero ❌, zero residual. The Epic implementation is genuinely verification-locked (not falsely-green) — see *Validated-No-Residual*.

## Cross-cut patterns

- Single finding, single root (a labelling/coverage-attribution weakness in 2 sibling test files — body + mirror, same 4 assertion blocks). Domain-bundle: the 2 B6 test files (`KeyboardLayoutUiTest.kt` + `KeyboardLayoutRenderMirrorTest.kt`), UI-4 + UI-10 only. No systemic plan-deviation, no block-wide convention drift.

## Findings

### F-1 (was AUDIT-TEST-B6-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `app/src/androidTest/java/net/devemperor/dictate/ui/KeyboardLayoutUiTest.kt:259-271, 442-453` + mirror `app/src/test/java/net/devemperor/dictate/ui/KeyboardLayoutRenderMirrorTest.kt:235-244, 378-389`
- **Description:** UI-4 and UI-10 assert `TRASH==GONE` / `PAUSE==GONE` under the in-test label `(§1.1 #3a)` claiming to guard "the SEND_MODE hardcoded-`{ false }` eliminator". This is **partially vacuous for that specific eliminator literal**: independently verified —
  - `isTrashVisible`/`isPauseVisible` = `state.recording.isActiveOrPaused || state.pipeline is PipelineUiState.ReprocessStaging` (`LayoutPredicates.kt:73-75, 89-91`, confirmed).
  - UI-4 state = `pipelineRunning(singleRow=true)` → recording=Idle, pipeline=`Running` (NOT `ReprocessStaging`). UI-10 frame-2 state = `Preparing` → recording=Idle, pipeline=`Preparing`. In **both** states the two predicates evaluate `false` regardless of the eliminator.
  - Therefore reverting the SEND_MODE `visibilityPredicate = { false }` for TRASH/PAUSE back to `::isTrashVisible`/`::isPauseVisible` would leave UI-4/UI-10 **GREEN** — they pin the *mode-selection* (`forKeyboard` → SEND_MODE) + the GONE *outcome*, not the hardcoded-false literal the `(§1.1 #3a)` label claims.
- **Mitigation confirmed (why NTH, not Important):** the §1.1 #3a eliminator literal **is** independently and non-vacuously pinned by the pre-existing `VisibilityMatrixTest` (in the green 1172). Verified directly: its parameterised case `"TWO_ROW_SEND + recording (cross-mode)"` applies `KEYBOARD_TWO_ROW_SEND_MODE` against `recordingActive()` (`recording.isActiveOrPaused == true`) and asserts `expectedTwoRowSendMode` with `TRASH→false, PAUSE→false` (`VisibilityMatrixTest.kt:180-202, 277+`). In that state `::isTrashVisible` would return `true`, so a revert of the `{ false }` literal turns `VisibilityMatrixTest` **RED**. The §1.1 #3a invariant is genuinely covered — the gap is purely an over-claiming **label**, not a coverage hole. (The remaining UI-N are non-vacuous per the audit's per-test verdict; spot-confirmed UI-9 as the sharp §8.5 forbidden-pattern guard and UI-1's `containsAll` subset as the correct §1.1 #1 structural invariant.)
- **Suggested fix (🟢, ~4 short doc/comment edits, D3 fix-every-polish):** Correct the over-claiming label so a future reader does not trust a vacuous guard. In both files, UI-4 + UI-10: change the inline comment + the two assertion messages from `(§1.1 #3a)` "hardcoded eliminator" wording to a truthful claim — they assert the **SEND_MODE structural-GONE outcome**; the `{ false }` eliminator literal itself is pinned by `VisibilityMatrixTest` (the "TWO_ROW_SEND + recording" cross-mode row). No assertion logic changes — message/comment text only. Concretely: replace `"UI-4 (§1.1 #3a): TRASH must be GONE in single-row send-mode"` → `"UI-4: TRASH GONE in single-row send-mode (SEND_MODE structural outcome; the {false} eliminator literal is pinned by VisibilityMatrixTest)"` and the equivalent for PAUSE / UI-10 TRASH+PAUSE, in both the Espresso body and the Robolectric mirror (keep body↔mirror byte-identical). Optionally adjust the UI-4/UI-10 section banner comment + the class KDoc §14.2 table footnote for #3a-attribution honesty.
- **Domain bundle candidate:** the 2 B6 test files, UI-4 + UI-10 only (4 assertion-message + 2 comment-banner edits, body+mirror kept identical).
- **Status:** fixed (B6-VAL-REPAIR-1, wave B6-VAL-W1 — text-only doc-honesty edit applied + self-check green; see block-report `### Block-Validate Repair Wave 1 (B6-VAL-REPAIR-1)`. Awaiting orchestrator wave-commit.)

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|
| (none) | — | AUDIT-TEST-B6's single NTH validated as real (technically-true vacuity for the labelled eliminator), classified 🟢 not ❌ per D3. The audit's own mitigation analysis (VisibilityMatrixTest covers #3a) independently re-verified by the consolidator. |

## Light CONVENTION fold (consolidator, 2 B6 test files)

Folded directly (no separate AUDIT-CONVENTION agent for this focused validate). Findings: **none**.

- **K-1 (no mock framework — re-affirmed):** grep-confirmed zero `mock(` / `mockk` / `@Mock` / `every {` / `whenever(` in either file. Both drive the **real production** `LayoutCatalog` + `KeyboardLayoutManager.computeLayoutMode` + `applySlotToView` over real `MaterialButton` Views. The only test-doubles are two hand-written deterministic `LayoutStrings` builders (`uiTestLayoutStrings()` / `mirrorLayoutStrings()`) — handwritten fakes, mirroring the existing `state.layout.testLayoutStrings` convention. Clean.
- **K-4 (Robolectric = justified opt-out — re-affirmed):** the mirror's `@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])` + `ContextThemeWrapper(app, R.style.Theme_Dictate)` is the documented OQ-4 / §14.2 CI-green proxy (assertions read real `View.visibility`/`alpha`/`isEnabled`/`text`, only observable through real Android Views — same shape as the existing `ImeViewBackendTest`/`LayoutCatalogTest`). The KDoc explicitly justifies it. Clean.
- **Test naming / structure:** all 10 follow `ui{N}_<state>_<expectedBehavior>` — behavior + condition, no "works"/"test1". Section-banner comments map each test to its §14.2 row + §1.1 bug. Matches sibling conventions (`VisibilityMatrixTest`, `LayoutCatalogTest`). No comment-noise.
- **Body ↔ mirror parity:** verified byte-identical render harness + fixtures (`idle`/`idleWithLastAudio`/`active`/`pipelineRunning`) + `originalSingleRowButtons` set + per-UI-N assertions including UI-3's `"1/3  1000ms"` (both `*LayoutStrings()` define a byte-identical `formatPipelineLabel`). Only differences are harness-justified + assert-neutral (Robolectric runner/themed-context vs AndroidJUnit4/raw-context). No mirror asserts less than its twin. The body↔mirror equivalence is documented in both class KDocs (OQ-4) — parity is documented, not implicit. No convention finding.
- **Helper-consolidation:** the two near-identical `*LayoutStrings()` builders live in different Gradle source-sets (`androidTest` vs `test`) — consolidation would *introduce* the cross-source-set `internal` coupling C11-D1 explicitly avoided, and would break the load-bearing byte-identical-`formatPipelineLabel` guarantee for the UI-3 parity. Intentional First-Use, correctly documented. No finding (re-affirms AUDIT-TEST's "Helper-Konsolidierung: none requiring action").

## Validated-No-Residual

The Epic implementation is genuinely **verification-locked, not falsely-green**. Consolidator-confirmed:

- **C12-D2 FINAL-LOCK is NOT vacuous.** AUDIT-TEST independently reproduced the full uncached suite **both variants × 2 different orders**: `testDebugUnitTest` = 1172/0/0/0-skip (both orders); `testReleaseUnitTest` = 1172/0/0/16-skip (both orders). Order-independent, zero R-7 flake (all 3 R-7 axes already closed). The 16 release-skips are the by-design `assumeTrue(BuildConfig.DEBUG)` no-double-write audit-ledger assertions — disjoint from the 10 mirrors (mirrors carry no `BuildConfig.DEBUG` guard, run green in both variants). Not a coverage gap.
- **Keystone + Triangle real-bound (RR-4).** `DictateCutoverE2ETest` 10/10 + `RenderPathCutoverGateTest` 5/5 drive the **real bound `DictatePipelineService`** on the new-orchestrator, legacy-FREE path (4 legacy controllers deleted, `LanguageController`/`audioFile`-field gone) — real binder + real Robolectric Views + real audit ledger, no vacuous duplication. C12-D2's RR-4 decision to write **no** `DictateCutoverFinalE2ETest.kt` (Dev-1) is sound: a third aggregating file re-asserts an already-complete cross-block trace. Confirmed not a coverage gap.
- **AC-1..AC-10 holistic (from C12-D2).** All ten PASS on the fully-cutover path: AC-1 (zero functional stub refs — the 1 hit is a comment), AC-2/AC-3 (recording→Active + StopAndSend→pipeline-via-new-runner + FGS notif lifecycle, `DictateCutoverE2ETest` green), AC-4 (F-10/F-12/F-13/F-15 state-shape, Theme-A reducer tests + real sessionId threading), AC-5/AC-6/AC-7 (LanguageController / `audioFile` field / 4 dead controllers DELETED — both build variants link green = the compile proof), AC-8 (Espresso 1-10 compile in androidTest + Robolectric mirror 10/10 green per OQ-4), AC-9 (1172 ≥ 946 with wide margin, +226 net coverage, no NET deletion), AC-10 (single-architecture — exactly one `JobExecutor.INSTANCE.start` documented-carve-out, zero `USE_LEGACY_RECORDING_DRIVE` in `app/src/main`, `PipelineOrchestrator` reachable only via the subsystem-adapter chain). Re-checked the C12-D2 table for internal consistency — coherent, no contradicting grep result.
- **No regression.** Block diff purely additive: `KeyboardLayoutUiTest.kt` un-`@Ignore`d to real bodies, `KeyboardLayoutRenderMirrorTest.kt` wholly new; zero existing test file modified, zero `src/main` delta (`git diff cef46be..HEAD -- app/src` = the 2 C11 test files only). No sibling test weakened; no green-pre-block test now red.
- **Documentation-gap check (AUDIT-TEST Step-0).** C11-D1's single test-bug (its own UI-1 exact-set→`containsAll` fix) is correctly documented in `### Code-Bugs Found During Test Self-Review` with file:line + symptom + root-cause + fix + research-source. **Zero** `test-agent-undocumented-code-fix`. C12-D2 zero-delta confirmed. No documentation-gap finding.

## Carried-Postponed Confirmation

| ID | Severity | Status | Disposition |
|----|----------|--------|-------------|
| C5-IMPL-2 | NTH | postponed | Confirmed correctly carried → Phase 4.7. Legacy recording amplitude/timer side-channel deferral. **Not B6-fixable** — B6 is pure-test, zero `src/main` delta; untouched by C11-D1 / C12-D2 (verified via the additive-only block diff). Remains `postponed`. |
| C10-C3-IMPL-1 | NTH | postponed | Confirmed correctly carried → Phase 4.7. (CR-DEL documented NTH). Not B6-fixable (same reason). Remains `postponed`. |

Both remain `postponed`, untouched by B6, correctly slated for Phase 4.7. No re-classification. B6 does not (and must not) attempt to fix them — out of scope for a pure-test block.

## Routing Recommendation

One 🟢 NTH (F-1) — a near-trivial doc-honesty repair wave (≤6 text-only edits across the 2 test files, body↔mirror kept identical, zero assertion-logic change). Zero 🟡, zero ❌, zero residual. Orchestrator may resume `B6-VAL-SANITY` as `B6-VAL-REPAIR` (resume-chain) to apply F-1, then `B6-VAL-REPAIR-VERIFY` self-check. B6 converges after this single trivial wave (or immediately, if the orchestrator elects to postpone the pure-doc NTH — both are defensible; D3 leans apply).
