# Block 1: Pre-Architecture + Service-Skeleton

> **This file is the logbook for Block 1.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Pre-Architecture and Service-Skeleton (plan-Block-1a + plan-Block-2)
**Implementation-Chunks:** C1-block1a-quick-wins (S/M, 400 score), C2-block2-pipeline-service-skeleton (M, 850 score)
**Workflow:** Iter-10 5-step workflow with orchestrator-split commits (no resume in this env — IMPL agent does Steps 1-5 internally per chunk, orchestrator splits the diff into Commit 1 (production) + Commit 2 (tests))
**Block-Start-Commit:** `bd8f1e6`
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:**
- Critical: 0
- Important: 0
- Nice-to-have: 0
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| — | — | — | — | — | — |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared sub-agent directives (issue handling, status schema, stdout
convention, research-file output, plan-deviation autonomy) live in
`prompts/agent-prompts.md` — read it before starting your task.

### Deviation Format

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|

### Issue Format

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|

---

## Implementation Logs

### Chunk C1-block1a-quick-wins — Quick-Wins in today's code

**Agent-IDs:**
- Steps 1-5 (Implementation + Self-fixes + Tests + Test-fix): `B1-C1-IMPL-FULL` (single fresh agent in this env — combines all 5 steps because resume-chain is unavailable; orchestrator splits diff into 2 commits)

**Status:** ✅ done (ready for orchestrator commit-split + AUDIT)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 2 (C1-block1a-quick-wins)
**Implementation-Commit (Commit 1, production):** ⏳ (orchestrator splits)
**Test-Commit (Commit 2, tests):** ⏳ (orchestrator splits)

#### Implementation (B1-C1-IMPL)

**What was done.**
Steps 1-5 executed in a single fresh-agent invocation. Three Block-1a quick-wins implemented end-to-end against today's code (no module architecture):

1. **`predResendVisible` helper** — new top-level Kotlin function in `KeyboardVisibilityPredicates.kt`. Pure boolean over four axes (`lastAudioFileExists`, `resendEnabled`, `recordingState`, `pipelineState`). Companion `resolveResendVisibility(...)` translates to `View.VISIBLE` / `View.GONE`. The signature mirrors the future LayoutCatalog RESEND-slot predicate (Spec 2 §3.2) so Block 5 lifts the body verbatim.

2. **5 of 6 resend-button mutation sites migrated to the helper:**
   - `RecordingUiController.applyIdleState` — was `if (getLastAudioFileExists()) VISIBLE else GONE`; now `resolveResendVisibility(...)`. The combined `getLastAudioFileExists` lambda is split into two independent axes (`getLastAudioFileExists` + `getResendEnabled`) at the constructor boundary so the predicate sees each axis separately.
   - `RecordingUiController.applyActiveState` — was unconditional `GONE`; now `resolveResendVisibility(...)` with `recordingState = Active(useBluetooth)`. The predicate naturally returns false.
   - `DictateInputMethodService.onStartInputView` Idle branch — was an inline `if (...)` with the same 4 axes; now a single `resolveResendVisibility(...)` call.
   - `DictateInputMethodService.runTranscriptionViaOrchestrator` — was unconditional `GONE`; now `resolveResendVisibility(...)` reading `recordingStateController.getState()` + `uiController.getState()` (Preparing at this point → predicate returns false).
   - The 6th site (`DictateInputMethodService.onShowResend`) is kept as explicit `setVisibility(View.VISIBLE)` because the PipelineOrchestrator fires this callback BEFORE `stopPipeline()` runs — the predicate would evaluate to false (pipeline still Running) and the button would never appear. Documented in code with a forward pointer to Block 5 (LayoutCatalog) which folds the predicate into a state-collector and re-orders the pipeline-completion sequence.

3. **recordButton.text/isEnabled hybrid centralised** — Spec 1 §11.2.2 step 2. The previously dual ownership (`RecordingUiController.applyIdleState / applyPreparingState / applyActiveState` + `KeyboardUiController.refreshRecordButtonFromState`) collapses into a single `KeyboardUiController.applyRecordButtonForRecording(state: RecordingState)` resolver. `RecordingUiController.onStateChanged` calls this resolver via a new constructor-injected callback before running its auxiliary (pause-button / animation / prompts) work. When the pipeline owns the button (non-Idle pipeline state), the resolver defers to the pipeline-axis branch — guaranteed single owner per frame. `KeyboardUiController` gained a new constructor parameter `dictateButtonTextProvider` for the Idle-label string.

4. **KSM.refresh quick-wins** — Spec 1 §11.2.2 step 3. Added `stateManager.refresh()` calls at the end of `DictateInputMethodService.onSingleRowModeToggled()` (after the ConstraintSet swap + bounce animation) and `onAudioFocusToggled()` (after pref + live-hook + icon refresh).

**Files created (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt` — new helper file (103 lines incl. KDoc).

**Files modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` — split lambda inputs; injected `onRecordingStateChangedForRecordButton` callback; removed recordButton mutations from `applyIdleState` / `applyPreparingState` / `applyActiveState`; migrated 2 resend sites to the helper.
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt` — added `dictateButtonTextProvider` constructor param + `applyRecordButtonForRecording(state)` method.
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — wired the new constructor params; migrated 3 resend sites + 1 explicit-VISIBLE site to the helper / commented; added `stateManager.refresh()` to the two toggle handlers.

**Files created (tests, Commit 2):**
- `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt` — 17 JUnit 4 tests (handwritten, no fakes needed — predicate is pure) covering: happy path, each axis flipped alone, sealed-subclass-not-data semantics, multi-axis fail, `resolveResendVisibility` View-constant translation, consistency-with-predicate.

**Plan deviations.**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| D1 | Spec 1 §11.2.2 step 1 ("alle 6 verstreuten resendButton.visibility-Sites lesen sie") | Site #6 (`onShowResend`) kept as explicit `setVisibility(View.VISIBLE)` instead of routed through the helper. | At the moment the PipelineOrchestrator fires `onShowResend()`, the pipeline state in `KeyboardUiController` is still `Running` (see `PipelineOrchestrator.runStandalonePrompt` ordering: `callback.onPipelineCompleted` → `if (showResendButton) callback.onShowResend()` runs BEFORE the Service's `onPipelineFinished()` → `stopPipeline()` re-posts on the main handler). Predicate would evaluate to false and the resend button would never appear. The behavioural gating (`Pref.ResendButton` + audio-file existence) is preserved upstream in `runTranscriptionViaOrchestrator` (`showResend = ... && Pref.ResendButton`). | None — the deviation isolates one Java site behind a comment that references Spec 1 §9.4; Block 5 (LayoutCatalog) lifts the predicate into a state-collect subscriber and the explicit setter disappears entirely. | inline-fixed (kept explicit, documented in code + here) |
| D2 | Spec 1 §11.2.2 step 2 ("ein zentraler Resolver in KeyboardUiController, der die 8 verstreuten Sites in §13.4.1 ersetzt") | The resolver method `applyRecordButtonForRecording` covers the recording axis only; the pipeline axis stays in the existing `refreshRecordButtonFromState`. The two branches inside `applyRecordButtonForRecording` defer to each other (recording-axis runs when pipeline is Idle; otherwise the pipeline-axis is called). | A single function with two parameters (`recordingState`, `pipelineState`) would have required threading both arguments through every call site that mutates pipeline-state. The deferring approach gives the same "single owner" invariant with a minimum diff. Block 5 collapses both into one slot resolver per LayoutCatalog. | Block 5 collapses; no impact on Block 1b / 2 / 3 / 4. | inline-fixed |
| D3 | Spec 1 §11.2.2 step 3 ("onAudioFocusToggled → KSM.refresh()-Trigger") | The `onAudioFocusToggled` refresh call is added at the end of the existing 1-2-3 sequence (pref / live-hook / icon refresh) — step 4 in the new ordering, not interleaved. | Preserves the original Race-Window ordering invariant (Block-2 Quality-Gate W). The refresh is purely additive. | None. | inline-fixed |

**Issues.**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | — |

**Inline-fixed items.** See Deviations table; all three deviations were resolved inline with rationale + plan-pointer in code comments.

**Overlooked points / known gaps.**
- The race the plan calls out in Spec 1 §9.5 ("Bei rotation/restoreUiState ist die Reihenfolge nicht deterministisch") is mitigated structurally (single owner of recordButton appearance) but a full elimination requires the Block-5 state-driven subscriber. The current code still has a possible micro-window if `onStateChanged(Idle)` fires from `RecordingStateController` AFTER `restoreUiState` paints from the snapshot — but this matches the previous behaviour and any divergence would surface in the AUDIT-LOGIC phase.
- Block 4 (Spec 2 §14.2) extends the predicate to the full 25-case truth-table when LayoutCatalog lands — out of scope here.
- The `RecordingUiController.recordButton` field is now only referenced by `recordingAnimation.prepare(recordButton)` in `init`. Keeping the field for the animation-prepare seam is intentional; the animation hook is still recording-axis only.

#### Plan-Correctness Fix (B1-C1-IMPL-PLAN-FIX)

Combined into the single invocation above (Step 2). Self-review against Spec 1 §11.2.2 + §9.4 + §9.5 confirmed:
- Step 1 (predicate helper) — done; signature mirrors the future LayoutCatalog form.
- Step 2 (recordButton hybrid) — done via central resolver in `KeyboardUiController`; ordering invariant preserved (recording-axis runs only when pipeline is Idle).
- Step 3 (KSM.refresh quick-wins) — added to both toggle handlers without disturbing the existing 1-2-3 ordering.
- All three deviations documented above. None require orchestrator routing.

#### Self-Code Fix (B1-C1-IMPL-CODE-FIX)

Combined into the single invocation above (Step 3). Knowledge-skill grounding consulted:
- `knowledge-doc-format` §"Inline anchors" — `@see` pointers to the plan + spec sections added at the top of `KeyboardVisibilityPredicates.kt` and inside the resolver comment in `KeyboardUiController`.
- Engineering baseline (D7 — sustainable / SOLID / Clean Code): helper is a pure function with a thorough KDoc explaining the why; the dual-axis-deferring resolver in `KeyboardUiController` keeps the single-responsibility principle (each branch owns one axis) while still giving the "one entry point" invariant the spec requires.

#### Tests (B1-C1-IMPL-TEST)

**What was done.**
- New JUnit 4 test class `KeyboardVisibilityPredicatesTest.kt` with 17 tests:
  - Happy path (all 4 axes hold).
  - Each axis flipped alone (5 false cases for recording-state subclasses + pipeline-state subclasses + 2 boolean axes).
  - Bluetooth subclass-vs-data semantics check (the predicate's `is Active` / `is Preparing` ignores the data-class fields).
  - Two- and four-axis simultaneous failures.
  - `resolveResendVisibility` translation (VISIBLE for predicate true, GONE for predicate false).
  - Loop over all non-Idle pipeline states asserting GONE.
  - Consistency wrapper: predicate ↔ resolveResendVisibility never drift.

All 17 tests pass on `./gradlew test` (debug + release variants). Total suite still green (no regression).

**Files created (tests, Commit 2).**
- `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt`

**No code-bugs found while writing tests.** The predicate is pure and the truth-table is straightforward; tests caught no behavioural surprises.

#### Test-Review (B1-C1-IMPL-TEST-FIX)

Combined into the single invocation above (Step 5). Coverage assessment:
- All four predicate axes have at least one passing-true and one failing-false test.
- All three non-Idle pipeline subclasses (`Preparing`, `Running`, `ReprocessStaging`) covered.
- All three non-Idle recording subclasses (`Preparing`, `Active`, `Paused`) covered.
- Both `Active.useBluetooth=true` and `Preparing.useBluetooth=true` exercised to guard against an accidental Bluetooth-only check.
- The `resolveResendVisibility` wrapper is checked against the predicate (consistency-test) so future drift would be caught.

**No code-bugs found during test self-review.**

**Coverage gaps left intentionally:**
- Full 2^4 axis-combo matrix not enumerated — boolean-conjunction semantics is universally tested by "any-false-axis → false" cases. Adding 16 tests would not catch a class of bug the current 17 do not.
- 25-case full truth-matrix from Spec 2 §14.2 (Block 4) is out of scope for Block 1a.

---

### Chunk C2-block2-pipeline-service-skeleton — DictatePipelineService skeleton + FGS

**Agent-IDs:**
- Steps 1-5: `B1-C2-IMPL-FULL` (single fresh agent, orchestrator-split-commits pattern)

**Status:** ⏳ pending
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 3 (C2-block2-pipeline-service-skeleton)
**Implementation-Commit (Commit 1, production):** ⏳
**Test-Commit (Commit 2, tests):** ⏳

⏳ to be filled by IMPL agent

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B1-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B1.md` | — |
| convention | `B1-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B1.md` | — |
| logic | `B1-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B1.md` | — |
| test | `B1-AUDIT-TEST` | ⏳ | `./reports/audit-test-B1.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B1-VAL-SANITY`
**Output file:** `./reports/validated-findings-B1.md`

⏳ to be filled

---

## Block Deviation Summary

⏳ to be consolidated after both chunks + Block-Validate

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step workflow done, both commits per chunk):** ⏳
- **Block-Validate converged (4-topic audit + sanity-pass + repair-waves done):** ⏳
- **AUDIT-TEST: coverage thresholds met for new files, no cross-chunk regressions:** ⏳
- **Build/Lint green at block-end:** ⏳
- **Issue index reconciled (all ids closed/postponed/forwarded):** ⏳
- **Conventions section filled:** ⏳
- **Deviation list propagated to plan/state:** ⏳
- **Cross-block-API consumer info forwarded to Block 2:** ⏳ (B2 reads the DictatePipelineService skeleton API + the predResendVisible helper as foundation for modular orchestrator)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
