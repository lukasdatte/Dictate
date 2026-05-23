# Audit Report: logic (Block 1, scope: full-block)

**Agent-ID:** B1-AUDIT-LOGIC
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-typescript (discriminated-union / exhaustive-check reasoning applied analogously to the pure-Kotlin sealed-FSM reducers; no TS-specific code)
**Files inspected:** 9
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt` (+ RecordingModuleTest.kt cross-read)

## Summary

- Critical: 0
- Important: 2
- Nice-to-have: 2

## Verdict on the SendStaging-guard correctness (IMPL-PLAN-FIX-1 C1-A1)

**The double-submit safety property HOLDS, but the `isStarting` mechanism is inert dead-code in production.**

The riskiest call (Dev-2 C1-A1) was: keep the existing `ReprocessStaging →
Preparing` edge instead of the literal `copy(isStarting=true)` pseudo-code,
and guard via `isStarting==true → null`. Tracing a real double-click:

1. First `SendStaging` (state `ReprocessStaging`, `isStarting=false`):
   sessionId matches, `isStarting==false` → `TransitionResult(nextState =
   Preparing, [SubmitReprocess, UpdateNotification])`. **State leaves
   `ReprocessStaging`.**
2. Second `SendStaging` (real double-tap, state now `Preparing`): the
   `SendStaging` arm's outer `when(state)` has only an `is ReprocessStaging`
   arm + `else -> null`. State is `Preparing` → **`else -> null`** → no
   second `SubmitReprocess`.

So the reprocess job is submitted **exactly once** — the safety property the
plan's AC-4 cares about is satisfied. **But** the protection comes entirely
from the FSM leaving `ReprocessStaging`, *not* from the `isStarting` bit:

- `grep -rn isStarting app/src/main` shows **no production code ever sets
  `isStarting = true`**. The `StartReprocessStaging` arm creates
  `ReprocessStaging(sessionId, transcript="")` (default `isStarting=false`);
  the `SendStaging` arm transitions to `Preparing` (not
  `copy(isStarting=true)`); no other producer of `ReprocessStaging` exists.
- Therefore `PipelineModule.kt:292` `else if (state.isStarting)` is a branch
  **unreachable in production** (`isStarting` is only ever `true` in test
  fixtures, `PipelineModuleTest.kt:196`).
- `LayoutCatalog.kt:390-393` still has a **stale comment** ("a field not yet
  on `ReprocessStaging`") and the `enabledResolver` deliberately does *not*
  read `isStarting`, so the spec-intended visual feedback (disable the Send
  button while starting) is also unwired.

This is coherence/dead-code debt, not a safety bug — flagged as
AUDIT-LOGIC-B1-1 (Important) so the consolidator can decide whether to (a)
delete the inert field+branch, or (b) wire the intended semantics. The
intended legacy semantics are explicit in `core/PipelineUiState.kt:52`:
`isStarting` "becomes true after Send pressed, before Registry-Running" —
i.e. the spec wanted first-tap to stay in `ReprocessStaging` with
`isStarting=true` (for a disabled-button render), not to jump straight to
`Preparing`. IMPL's call is defensible (the `Preparing` edge preserves the
runner handshake and the existing FSM test), but it leaves three artefacts
(field, guard branch, catalog comment) describing a mechanism that does
nothing.

## Findings

### AUDIT-LOGIC-B1-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:289-320` (guard) + `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:238-251` (field) + `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:390-393` (stale comment / unwired enabledResolver)
- **Description:** The F-12 `isStarting` double-click guard is **structurally unreachable in production**. No production transition ever sets `ReprocessStaging.isStarting = true` (the `SendStaging` arm goes `ReprocessStaging → Preparing` instead of `copy(isStarting=true)` per Dev-2 C1-A1). The `else if (state.isStarting)` branch at `PipelineModule.kt:292` can therefore never execute outside unit tests. Concurrently, `LayoutCatalog.kt:390-393` carries a stale comment claiming the field is "not yet on `ReprocessStaging`" (it *was* added by C1-A1) and its `enabledResolver` does not read `isStarting`, so the spec-intended "disable Send while starting" UX (legacy `core/PipelineUiState.kt:52`: "becomes true after Send pressed, before Registry-Running") is unimplemented. The actual double-submit protection works (via the FSM leaving `ReprocessStaging`), so this is not a safety regression — but the codebase now contains a field, a guard branch, and a catalog comment that collectively describe a mechanism that is inert.
- **Why it matters:** Dead/inert state-machine code is a serviceability hazard: a future maintainer reading the `isStarting` field + guard + KDoc will reasonably assume the guard is the live double-click protection and may "fix" the real protection (the `ReprocessStaging → Preparing` edge) without realising they've removed the only working guard. The stale `LayoutCatalog` comment actively misinforms (says the field doesn't exist when it does). Engineering-baseline rule: no dead code, comments must not contradict the code.
- **Suggested fix scope:** medium
- **Suggested fix:** Two coherent options for the consolidator/repair-phase to choose between — (a) **Wire the intended semantics:** make `SendStaging` first-tap `copy(isStarting=true)` + emit the SubmitReprocess effect while staying in `ReprocessStaging`; update `LayoutCatalog.kt:393` `enabledResolver` to `state.pipeline is ReprocessStaging && !it.isStarting`; the existing `else if (state.isStarting) null` then becomes the live guard. Risk: must verify the runner handshake still works without the `Preparing` edge (the Dev-2 rationale flagged this as the reason it was avoided — needs the `StartPipeline`-from-`Preparing` contract re-checked, so this is the research-needed branch). (b) **Delete the inert mechanism:** remove `isStarting` from `ReprocessStaging`, remove the `else if (state.isStarting)` branch, fix the `LayoutCatalog.kt:390-393` comment to state the protection is the FSM edge. Lower risk, but loses the spec-intended disabled-button UX (defer to a later Theme-C/D UI block with an explicit note). Decision needs the Epic §4-A1 vs Spec-§3 intent reconciled — recommend routing to the research-needed branch (🟡), as IMPL-PLAN-FIX-1 explicitly asked Block-Validate to confirm this call.

### AUDIT-LOGIC-B1-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:209-210` (KDoc on `Running.totalSteps`)
- **Description:** The `@property totalSteps` KDoc states the value is "set from `StartPipeline.totalSteps` on `Preparing → Running` **and refreshed by `StepStarted`** (defensive — keeps the label sane if the runner re-reports a different total mid-run)". The actual `StepStarted` reducer arm (`PipelineModule.kt:174-192`) does **not** refresh `totalSteps` — it only restamps `elapsedMs` (`state.copy(elapsedMs = elapsedSince(...))`), and the inline code comment at `PipelineModule.kt:178-182` plus Dev-1 (C1-A1) explicitly document that `StepStarted` carries no total and does NOT touch `totalSteps`. The KDoc on the data class therefore directly contradicts the reducer behaviour and the documented deviation.
- **Why it matters:** Doc-vs-code contradiction on a state-shape contract. A consumer (B2 notification, B4 record-button label) trusting the KDoc would assume `totalSteps` self-heals mid-run if the runner re-reports; it does not — `StartPipeline.totalSteps` is the sole authoritative write. This is exactly the "comments that capture what the reader cannot derive" inverted into "comments that misstate what the code does". Cheap to fix, high confusion cost if left.
- **Suggested fix scope:** small
- **Suggested fix:** Edit `DictateUiState.kt:209-210` to remove the "and refreshed by `StepStarted`" clause; replace with: "set once from `StartPipeline.totalSteps` on `Preparing → Running`; never re-stamped (`StepStarted` carries no total — see PipelineModule Dev-1). `0` means 'unknown' and the label formatter renders it as such." Pure documentation fix, no behaviour change.

### AUDIT-LOGIC-B1-3

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:198-205` (`StepCompleted` arm)
- **Description:** `StepCompleted` does `state.copy(completedSteps = state.completedSteps + 1, …)` with no upper bound against `state.totalSteps`. If the runner emits more `StepCompleted` actions than `totalSteps` (mis-reporting, retry double-emit, or `totalSteps==0` "unknown"), `completedSteps` overruns and the live label renders nonsensically (e.g. `"4/3"` or `"1/0"`). The reducer trusts the runner contract entirely; there is no clamp and no test asserting the overrun behaviour.
- **Why it matters:** Low impact — the counter feeds only a cosmetic live label / FGS notification, and the runner is the authority on step count. But the `elapsedSince()` helper already establishes a defensive-flooring precedent for the sibling `elapsedMs` field (negative-floor at 0); `completedSteps` has no symmetric guard, which is a small consistency gap in the F-13 counter family. Not a correctness bug for any state transition.
- **Suggested fix scope:** small
- **Suggested fix:** Optional — `completedSteps = (state.completedSteps + 1).coerceAtMost(state.totalSteps.coerceAtLeast(state.completedSteps + 1))` is over-engineered; simpler is to leave the reducer trusting the runner and instead have `formatPipelineLabel` clamp the display (`completedSteps.coerceAtMost(totalSteps)` when `totalSteps > 0`). Recommend documenting the "runner is authoritative, label may show overrun if runner mis-reports" assumption in the `StepCompleted` arm comment rather than adding reducer logic — keeps the reducer minimal. Defer-acceptable; flag for the consolidator's judgement.

### AUDIT-LOGIC-B1-4

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/Action.kt:117-121` (`StartRecording.sessionId: String`) + `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:184-199`
- **Description:** `StartRecording.sessionId` is an unconstrained `String`. F-10's whole point is "no empty-string sentinel anywhere"; the current Block-1 mint sites (`ActionResolvers.newSessionId()` → `UUID.randomUUID().toString()`) guarantee non-empty, and the grep confirms zero `sessionId = ""` today. But the reducer carries `action.sessionId` into `Preparing` verbatim with no `require(sessionId.isNotBlank())` / no defensive rejection. When B3 flips the recording trigger to route the IME's `preAllocatedId` in, a regression there (empty/blank id) would silently re-introduce exactly the F-10 sentinel the block is removing, propagating through the whole FSM into `EmitPipelineTrigger` and the pipeline session key — with no fail-fast.
- **Why it matters:** This is a latent guard-rail gap, not a current bug (Block-1 callers are correct). It's a B3 cross-block concern and is already partially covered by IMPL-PLAN-FIX-2's B3 contract note. Raising it here so the F-10 invariant ("FSM is the single source, never empty") has an enforcement point rather than relying on every future caller's discipline.
- **Suggested fix scope:** small
- **Suggested fix:** Either (a) add `require(action.sessionId.isNotBlank()) { "F-10: StartRecording.sessionId must be non-blank" }` at the top of the `Idle + StartRecording` arm (fail-fast, matches the FSM-is-single-source invariant), or (b) explicitly document in the `StartRecording.sessionId` KDoc that callers MUST supply a non-blank UUID and that B3's recording-trigger cutover owns the enforcement. Defer-acceptable to the B3 cross-block contract already flagged (IMPL-PLAN-FIX-2); noting it so the invariant is not purely convention.

## Cross-check of the 3 delegated `plan-deviation-resolved` issues

| Issue | Holds under logic scrutiny? | Notes |
|-------|-----------------------------|-------|
| IMPL-PLAN-FIX-1 (C1-A1) — SendStaging keeps `→Preparing` edge, guard via `isStarting→null` | **Partially.** Safety property (exactly-once submit) holds. But the resolution leaves `isStarting`/guard-branch/catalog-comment inert — see AUDIT-LOGIC-B1-1. | The "is the guard actually correct" question: the *effective* guard is correct, but it is **not** the `isStarting` guard — it's the FSM-leaves-`ReprocessStaging` transition. The flagged-for-confirmation deviation needs the AUDIT-LOGIC-B1-1 decision (wire vs delete). |
| IMPL-PLAN-FIX-1 (C2-A2) — `sessionId` added to `RecordingState.Preparing/Active/Paused` | **Holds.** Spec 1 §3 (`:251-253`) + §15.2 (`:6360-6429`) predate F-10 and show no `sessionId`; Epic §4 Block A2 (`dictate-cutover-completion.md:344-346`) explicitly authorises "adding `sessionId` here is the clean source". FSM transition graph verified unchanged — every transition propagates `sessionId` verbatim (Idle→Preparing→Active⇄Paused→Idle), no drop/blank. `StopRecordingAndSend` under non-sessionId states (Idle/Preparing) hits `else -> null` (Rejected), no unsafe `state.sessionId` access (smart-cast scoped to Active/Paused). | Spec-faithful payload-only widening. No logic defect. |
| IMPL-PLAN-FIX-2 (C2-A2) — `StopRecordingAndSend` → `data object` (payload removed) | **Holds.** Spec 1 §3 resolver (`:1430-1431`) already shows payload-less `StopRecordingAndSend`; the only payloaded reference is the Epic §4 Block B3 forward-sketch, which A2 (the authoritative seam owner) refines. Reducer reads `state.sessionId` from the live FSM on both Active + Paused arms — id continuity verified via the FSM round-trip. | No logic defect. Correctly flagged as a hard B3 cross-block contract (B3 must dispatch `StartRecording(...,preAllocatedId)` + payload-less `StopRecordingAndSend`). Orchestrator must forward to B3. |

## Other focus-area trace results (no findings — documented for the consolidator)

- **F-13 counter reset across re-run (Idle→Running→Idle→Running):** Correct. Every `StartPipeline` from `Preparing` constructs a fresh `Running(completedSteps=0, totalSteps=action.totalSteps, startedAtMs=ctx.now, elapsedMs=0L)`. Pipeline always collapses to `Idle` (Done/Failed/Cancelled), so the next run re-stamps from zero. No stale carry-over.
- **F-13 out-of-order actions:** `StepStarted`/`StepCompleted` while state is `Preparing`/`Idle` → `else -> null` (Rejected), no crash, no counter mutation. Duplicate `StartPipeline` while already `Running` → `else -> null` (Rejected), no double-stamp. Safe.
- **F-13 `elapsedMs` negative:** `elapsedSince()` = `(now - startedAtMs).coerceAtLeast(0L)` — floor verified correct; covered by `PipelineModuleTest.kt:321` (`startedAtMs=9_000L > ctx.now=5_000L` → `elapsedMs=0`).
- **F-10 sessionId threading completeness:** Verified across all 11 reducer arms — every sessionId-bearing transition propagates `state.sessionId` verbatim; Idle-targeting arms correctly carry no id. No drop/blank/again-empty path. `StopRecordingAndSend` `state.sessionId` access is statically safe (smart-cast inside `is Active`/`is Paused` only).
- **F-15 null/empty language:** `LanguageState.effective` is non-nullable `String` (type-level null-impossible, boot default `"system"`). Production `dictateButtonText` lambda (`DictatePipelineService.kt:713-719`) explicitly handles `isEmpty()`/`"system"` → plain "Record". Correct.

## Coverage

- **Files audited:** all 8 production files in the B1 diff + `PipelineModuleTest.kt` (cross-read for guard/counter test coverage) + `RecordingModuleTest.kt` (cross-read for sessionId round-trip coverage).
- **Files skipped (with reason):** the ~11 mechanically-updated existing test suites (`AudioModuleTest.kt`, `ViewModeModuleTest.kt`, etc.) — pure `sessionId="sid-test"` / payload-less-ctor mechanical edits, no logic surface; correctly out of the logic-topic scope (test-quality is AUDIT-TEST's topic). State-file skipped (orchestrator-owned).
- **Knowledge-skill checkpoints applied:** sealed-FSM exhaustiveness (every `when(state)`/`when(action)` has an `else -> null` or is compile-exhaustive — verified no missing-branch / non-exhaustive path); discriminated-union smart-cast safety (`state.sessionId` accesses scoped to the correct `is`-narrowed branch); boundary/off-by-one (`completedSteps+1` overrun, `elapsedMs` negative floor); out-of-order/idempotency (duplicate/early actions → Rejected, not crash).

## Out-of-scope observations (for the consolidator to route)

- `resolveRecordButtonTextStaging` (`TextResolvers.kt:138-145`) still passes `formatStagingLabel(0)` (hard-coded duration). Already flagged by C1-A1/C2-A2 as a known out-of-F-12/F-13-scope gap (legacy `core/PipelineUiState.kt:50` has `audioDurationSeconds` the new `ReprocessStaging` lacks). Not a B1 logic defect — noted so it is not lost; owned by a later block per the block-report "Overlooked / Known Gaps".
- `resolveRecordActionPipeline` returns `Action.FeatureToggleAction.ToggleAutoEnter` for `Running` (auto-enter toggle) — unrelated to F-12/F-13/F-10/F-15, no logic issue, mentioned only because it touches the same `Running` state the F-13 counters live on.
