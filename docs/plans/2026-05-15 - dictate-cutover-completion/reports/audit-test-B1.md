# AUDIT-TEST Findings — Block 1 (Theme A — State-Shape)

**Agent-ID:** `B1-AUDIT-TEST` · **Scope:** full-block · **Diff:** `58bb9a1..HEAD`
**Date:** 2026-05-15
**Test command:** `./gradlew testDebugUnitTest --rerun-tasks` (forced re-run, not cached)
**Build:** `./gradlew assembleDebug` — BUILD SUCCESSFUL

## Test-Suite Result (forced re-run at HEAD)

| Metric | Value |
|---|---|
| Suites | 93 |
| Tests | **964** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |

Matches the expected baseline exactly (parent plan 946 → C1-A1 +13 net
(`PipelineModuleTest` 24→37, `ActionResolversTest` +2) → 959 → C2-A2 +5
net (`RecordingModuleTest` 24→26, `ActionResolversTest` 31→33) → 964).
Build green; no compile breakage from the contract changes.

K-1 (handwritten fakes only) + K-4 (no Robolectric in pure-reducer
tests) verified — see Quality section. No JaCoCo configured; coverage
assessed by branch-inspection per the AUDIT-TEST prompt (no
threshold-fail).

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|---|---|---|---|---|
| — | none | — | — | — |

Doc-trail is **complete**. Both chunks' `### Code-Bugs Found While
Writing Tests` sections correctly state "no production code-bugs". This
is factually verified: the two test commits (`ca5dbed` C1-A1,
`015b616` C2-A2) touch **only** `*Test.kt` files — zero `src/main`
changes. The two production feat commits (`9bacace`, `d236ab2`) precede
the test commits and contain all production logic. There are **no
undocumented IMPL-TEST / IMPL-TEST-FIX production code-fixes** anywhere
in the block range. The two pre-existing tests that were rewritten
(`StepStarted … restamps elapsedMs (F-13)` in C1-A1;
`StopRecordingAndSend from Active/Paused` → F-10 FSM-sessionId tests in
C2-A2) are documented with File:Line, Symptom, Root-Cause, Fix
(before→after), and Research source as plan-driven contract updates —
not code-bug fixes. Audit trail intact.

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|---|---|---|---|---|
| — | none | — | — | — |

- **Mock convention (K-1):** grep for `mockk` / `Mockito` / `mock(` /
  `@Mock` over the 4 Theme-A reducer test files
  (`PipelineModuleTest`, `RecordingModuleTest`,
  `state/layout/ActionResolversTest`, `state/layout/LayoutCatalogTest`)
  → **ZERO hits**. Pure-reducer tests use plain constructors + existing
  handwritten fakes (`fakeModuleServices`, `FixedAudioFileFactory`).
  Compliant.
- **Robolectric (K-4):** the Robolectric/`androidx.test` grep hits are
  all in **pre-existing** sibling render/IME test files
  (`ImeViewBackendTest`, `OverlayBackendTest`,
  `DictatePipelineServiceOverlayTransitionTest`,
  `PromptVisibilityControllerTest`) that legitimately require
  Robolectric for the Android view contract. The C2-A2 edits to these
  files added **no** new Robolectric usage — purely `sessionId =
  "sid-test"` constructor arguments. No Theme-A reducer test uses
  Robolectric. K-4 satisfied ("none expected for Theme A" — confirmed).
- **Test naming:** behaviour + condition, F-prefixed
  (`F-12 SendStaging while isStarting true is a no-op`,
  `F-10 sessionId minted at StartRecording survives the full FSM
  round-trip`). No `works` / `test 1`.
- **Assertions:** concrete — exact `elapsedMs = 3_500L`
  (5_000−1_500), `totalSteps = 4`, `startedAtMs = 5_000L`, label
  string equality `"Dictate (en)"` ≠ `"Dictate (de)"`, UUID-distinct
  per click, `SubmitReprocess` effect count == 1. No weak/structural-
  only assertions; no order-dependent snapshots.
- **Helper consistency:** reused `testLayoutStrings()` /
  `stubAudioFile()` from `LayoutCatalogTest.kt` and existing
  `fakeModuleServices()` / `FixedAudioFileFactory` from `testutil`. No
  new helpers introduced; no quasi-duplicates across chunks.

## Coverage (branch-inspection — no JaCoCo)

| File | New/changed branches | Inspection verdict |
|---|---|---|
| `PipelineModule.kt` — `StartPipeline` | Preparing+match / mismatch / non-Preparing | covered (`F-13 StartPipeline stamps…`, `StartPipeline with mismatched sessionId`, `StartPipeline transitions Preparing to Running`) |
| `PipelineModule.kt` — `StepStarted` | Running+match / mismatch / non-Running | covered (`StepStarted … restamps elapsedMs (F-13)`, `F-13 StepStarted with mismatched sessionId`, `F-13 StepStarted outside Running`) |
| `PipelineModule.kt` — `StepCompleted` | Running+match / mismatch / non-Running | covered (`F-13 StepCompleted increments…`, `… mismatched sessionId`, `… outside Running`) |
| `PipelineModule.kt` — `SendStaging` (F-12) | sessionId-mismatch / isStarting=true→null / isStarting=false→transition / non-staging | **all 4 covered** (`F-12 SendStaging while isStarting true is a no-op`, `… isStarting false still submits once`, `… mismatched sessionId rejected`; non-staging via existing default-arm tests) |
| `PipelineModule.kt` — `elapsedSince()` | positive / floored-at-0 | covered (`F-13 elapsedMs is floored at zero when ctx-now precedes startedAtMs` + positive paths in StepCompleted/StepStarted) |
| `RecordingModule.kt` — sessionId propagation | StartRecording→Preparing, MediaRecorderReady→Active, Pause→Paused, Resume→Active | covered end-to-end (`F-10 sessionId minted at StartRecording survives the full FSM round-trip` + `StartRecording from Idle … carried into the FSM`) |
| `RecordingModule.kt` — `StopRecordingAndSend` (payload-less) | Active reads state.sessionId / Paused reads state.sessionId | covered + **strengthened** (`F-10 StopRecordingAndSend from Active uses the FSM sessionId not an action payload`, `… from Paused …`) |
| `ActionResolvers.kt` — `newSessionId()` mint | distinct UUID per click | covered (`F-10 resolveRecordAction mints a fresh sessionId on each StartRecording` — asserts `a.sessionId != b.sessionId`) |
| `TextResolvers.kt` — `resolveRecordButtonText` (F-15) | en vs de effective language | covered (`F-15 resolveRecordButtonText label differs across two effective languages`) |
| `TextResolvers.kt` — `resolveRecordButtonTextPipeline` (F-13) | real Running fields / autoEnter false | covered (`F-13 resolveRecordButtonTextPipeline renders real Running counters not placeholders`, `… reflects autoEnter false`) |

Estimated branch coverage of the touched lines: ~100% of new/changed
reducer branches. Every match/mismatch/non-applicable-state arm of the
4 affected `PipelineModule` actions and the `RecordingModule`
sessionId-bearing transitions is exercised. Edge cases explicitly
tested: double-click guard (F-12), out-of-order/non-bearing state
(StepCompleted/StepStarted outside Running), sessionId on non-bearing
state (mismatch-rejected arms), elapsed floor-at-0 with non-monotonic
clock, full Pause/Resume FSM round-trip for sessionId.

## Cross-Chunk-Regressions

**none.** Forced (`--rerun-tasks`, uncached) full-suite run: 964/964
pass, 0 fail. No previously-green test went red. C2-A2 (the contract-
change chunk) did not break any C1-A1 or pre-existing path.

### Verdict on the 12 C2-A2 sibling-test edits (key block risk)

**All 12 sibling-test edits are legitimate mechanical compile-fixes —
NONE weakened, deleted, or behaviourally altered a coverage assertion.
No regression is hidden as a "fix".**

Per-file confirmation (diff `58bb9a1..HEAD`):

| File | Edit kind | Assertion impact |
|---|---|---|
| `state/AudioModuleTest.kt` | `sessionId = "sid-test"` added to 4 `RecordingState.*` ctors | none — bodies/assertions identical |
| `state/ViewModeModuleTest.kt` | `sessionId` on 1 `Active` ctor | none |
| `state/OverlayModuleTest.kt` | `sessionId` on 4 ctors | none |
| `state/DictateUiStateTest.kt` | `sessionId` on Preparing/Active/Paused ctors; equality test still asserts equality with matching sessionId | none — correctness preserved |
| `state/ActionHierarchyTest.kt` | `sessionId` on `StartRecording` ctors; equality/inequality tests unchanged in intent | none |
| `state/render/overlay/OverlayBackendTest.kt` | `sessionId` on 1 ctor | none |
| `state/render/PromptVisibilityControllerTest.kt` | `sessionId` on 3 ctors | none |
| `state/render/RecordingAnimationControllerTest.kt` | `sessionId` on 3 fixture helpers | none |
| `state/render/ImeViewBackendTest.kt` | `sessionId` on 1 ctor | none |
| `state/layout/VisibilityMatrixTest.kt` | `sessionId` on 5 ctors | none |
| `state/layout/LayoutCatalogTest.kt` | `testLayoutStrings().dictateButtonText` lambda `() ->` → `(effectiveLanguage) -> "Dictate ($effectiveLanguage)"` (F-15 fixture) — documented | none — correctly threads the new param; shared fixture upgraded, not weakened |
| `core/DictatePipelineServiceOverlayTransitionTest.kt` | `sessionId` on 2 `StartRecording` dispatches | none |

The two `RecordingModuleTest` `StopRecordingAndSend` renames are
**strengthening, not weakening**: the old assertion verified the
sessionId came from the action payload (`sid-42`/`sid-99`); the new
assertion verifies it comes from the FSM state
(`sid-active`/`sid-paused`) — this *is* the F-10 behavioural change and
is the correct, stronger assertion. Likewise the C1-A1 `StepStarted`
test rewrite tightened a `assertEquals(state, …)` no-op assertion into
an exact `state.copy(elapsedMs = 3_500L)` derived-value assertion while
preserving the side-effect-count check.

## Helper-Konsolidierung

**none.** No quasi-duplicate helpers introduced across C1-A1/C2-A2. All
helpers reused from already-committed `LayoutCatalogTest.kt` /
`testutil` (First-Use was not exercised — pure reducers + existing
fakes sufficed).

---

## Summary verdict

Block B1 (Theme A — State-Shape) test posture is **clean**:
- 964/964 tests pass at HEAD (forced re-run), build green.
- K-1/K-4 satisfied for all Theme-A reducer tests.
- New logic (F-12 guard both branches, F-13 counter arms + elapsed
  floor, F-10 sessionId propagation + payload-less stop, F-15
  language-aware label) has ~100% new-branch coverage with concrete
  assertions and explicit edge-case tests (double-click, out-of-order,
  non-bearing state, non-monotonic clock, full FSM round-trip).
- The 12 C2-A2 sibling-test edits are pure mechanical compile-fixes;
  none weakened coverage. No cross-chunk regression.
- Doc-trail complete; no undocumented production code-fixes.

No findings — nothing to route to the repair-sub-phase.
