# Audit Report: plan-and-api (Block 1, scope: full-block)

**Agent-ID:** B1-AUDIT-PLAN-AND-API
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-typescript (type-contract grounding only — codebase is Kotlin/Java; no TS-specific pattern applies, used as discriminated-union/exhaustiveness baseline)
**Files inspected:** 13
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (FN-4 seam verification)
- `app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt`
- Epic §2 (AC-1/AC-3/AC-4), Epic §4 Block A1/A2
- Spec 1 §3 / §15.2 (via plan-reader headings + the priority-prompt section pointers)
- `dictate-cutover-completion.state.md` (FN-4 forward-note)

## Summary

- Critical: 0
- Important: 2
- Nice-to-have: 1

## Priority-Issue Verdicts (the 3 delegated `plan-deviation-resolved`)

### IMPL-PLAN-FIX-1 (C1-A1) — SendStaging keeps `→ Preparing` edge — **CONFIRMED-JUSTIFIED (with a Nice-to-have caveat)**

The deviation call is **upheld**. Epic §4 Block A1's pseudo-code
(`if (state.isStarting) null else copy(isStarting=true)`) is a sketch,
not a literal contract. Following it literally **would** break the
runner handshake: `PipelineModule.StartPipeline` only transitions from
`PipelineUiState.Preparing` (verified `PipelineModule.kt:148`), and the
existing pre-block test `SendStaging transitions ReprocessStaging to
Preparing` (`PipelineModuleTest.kt:171`) + the documented FSM contract
require the `ReprocessStaging → Preparing` + `SubmitReprocess` edge. A
literal `copy(isStarting=true)` staying in `ReprocessStaging` would
strand the reprocess job (no `Preparing` → `StartPipeline` never fires).

**AC-4 testable contract is met.** Epic §2 AC-4 only mandates: (a)
`ReprocessStaging.isStarting: Boolean` exists ✓, (b) reducer unit-tests
cover the SendStaging double-click guard (`!isStarting`) ✓
(`PipelineModuleTest.kt:185-217`). It does **not** mandate the FSM stay
in `ReprocessStaging`. The "SendStaging-while-starting → no-op"
behaviour is satisfied: `PipelineModule.kt:290` `else if
(state.isStarting) null`.

This is a confirmed-justified mid-size plan-deviation per D22 — no
re-routing needed. See the Nice-to-have finding below for a code-clarity
caveat that does **not** overturn the verdict.

### IMPL-PLAN-FIX-1 (C2-A2) — `sessionId` on RecordingState — **CONFIRMED-JUSTIFIED**

Verified line-by-line: the FSM transition graph is genuinely
**payload-only widened**, not structurally changed.
`RecordingModule.reduce` (`RecordingModule.kt:178-330`):

- Idle→Preparing: `sessionId = action.sessionId` (from `StartRecording`) ✓
- Preparing→Active (`MediaRecorderReady`): `sessionId = state.sessionId` ✓
- Active→Paused (`PauseRecording`): `sessionId = state.sessionId` ✓
- Paused→Active (`ResumeRecording`): `sessionId = state.sessionId` ✓
- Active/Paused→Idle (`StopRecordingAndSend`): reads `state.sessionId`
  for `EmitPipelineTrigger` ✓ (`:259`, `:314`)

Every non-terminal transition propagates `sessionId` verbatim — no
transition drops or re-derives it (same invariant class as the existing
`useBluetooth`/`audioFile` propagation). Spec 1 §3/§15.2 predate F-10
and show the variants without `sessionId`; Epic §4 Block A2 **explicitly
authorises** this: *"adding `sessionId` here is the clean source"*
(Epic §4-A2 line 45-46). Spec-faithfulness holds — §15.2's documented
transition arcs are all present and unchanged. **Confirmed-justified.**

### IMPL-PLAN-FIX-2 (C2-A2) — `StopRecordingAndSend` payload-less `data object` — **CONFIRMED-JUSTIFIED**

The A2-vs-B3 plan-internal inconsistency is correctly resolved in favour
of A2 (the block that owns this seam's design). The id-flows-via-
`StartRecording` seam is coherent and verified end-to-end:

- `StartRecording(target, audioFile, sessionId)` — sessionId entry point ✓
- carried Preparing→Active→Paused ✓
- `StopRecordingAndSend` (payload-less `data object`) reducer reads
  `state.sessionId` ✓
- `EmitPipelineTrigger(sessionId = state.sessionId, audioFile =
  state.audioFile)` — pipeline trigger gets the same id ✓

The `data object` change is correctly reflected at **all** call-sites
(verified by grep, see API-contract section). The cross-block forward-
note **FN-4** in `dictate-cutover-completion.state.md:288` is correct
and complete: it names the exact B3 dispatch contract
(`StartRecording(target, audioFile, preAllocatedId)` then payload-less
`StopRecordingAndSend()`), pins the IME seam (`:2213` `preAllocatedId =
UUID.randomUUID().toString()` — verified present in
`DictateInputMethodService.java:2213`), and explicitly states it
supersedes the Epic §4-B3/§3 literal `StopRecordingAndSend(realSessionId)`
wording. No API-contract gap. **Confirmed-justified.**

## Findings

### AUDIT-PLAN-AND-API-B1-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:283-318` (the `SendStaging` arm) + `DictateUiState.kt:222` (`ReprocessStaging.isStarting`)
- **Description:** `PipelineUiState.ReprocessStaging.isStarting` is
  **never set to `true` anywhere in production code**. Grep across
  `app/src/main/` for `isStarting = true` / `copy(isStarting` returns
  zero hits; the only writer is a test-constructed state
  (`PipelineModuleTest.kt:196`). The first `SendStaging` tap transitions
  `ReprocessStaging → Preparing` (correct per Dev-2), so the FSM leaves
  `ReprocessStaging`; a second `SendStaging` then falls through the outer
  `is PipelineUiState.ReprocessStaging` type-guard (state is now
  `Preparing`) and returns `null` via `else -> null`. **The actual
  double-submit protection is provided by the state transition, not by
  the `isStarting` field.** The `else if (state.isStarting) null` branch
  (`PipelineModule.kt:290`) is therefore **dead in production** — it can
  only be hit if some not-yet-existing code path optimistically sets
  `isStarting=true` while still in `ReprocessStaging` (the IMPL agent's
  Dev-2 rationale anticipates "the UI/resolver optimistically marks
  isStarting before the FSM flips", but no such code exists in B1).
- **Why it matters:** Plan-treue: Epic §4-A1's F-12 intent is a
  double-click *guard via `isStarting`*. As implemented the guard is a
  vestigial field whose protective effect is incidental to the FSM
  transition. This is **functionally adequate for AC-4** (the no-op-on-
  second-tap behaviour holds, and the unit-test asserts it), so it is
  **not Critical** — but it is a latent plan-deviation: a future block
  (B2/B3) that drives `SendStaging` from a real UI surface may
  reasonably assume `isStarting` is the live guard and not realise the
  protection actually comes from the `→ Preparing` transition. If a
  later change ever makes `SendStaging` re-entrant *before* the
  transition commits (e.g. synchronous double-dispatch within one
  render-tick on the same `ReprocessStaging` state), the `isStarting`
  branch still protects — but only if something sets it, which nothing
  does. The field's contract (KDoc `DictateUiState.kt` "Set to `true` by
  the first `SendStaging` action") is **factually wrong**: the first
  `SendStaging` does NOT set `isStarting=true`, it transitions to
  `Preparing`.
- **Suggested fix scope:** small (documentation/contract) — the cleanest
  resolution is a KDoc correction on `ReprocessStaging.isStarting` to
  state accurately that the field is a *defensive guard for a
  same-tick re-dispatch before the FSM leaves `ReprocessStaging`*, and
  that the primary double-submit protection is the `→ Preparing`
  transition. Optionally, the `SendStaging` non-guard branch could set
  the field on the (now-`Preparing`) successor — but `Preparing` has no
  `isStarting`, so that is not viable; the documentation fix is the
  correct minimal action. **Does not overturn the IMPL-PLAN-FIX-1
  (C1-A1) verdict** — the deviation call (keep `→ Preparing`) is right;
  only the field's self-description drifted.
- **Suggested fix:** Correct the `DictateUiState.kt` `ReprocessStaging.isStarting`
  KDoc: replace "Set to `true` by the first `SendStaging` action; a
  second `SendStaging` while `isStarting` is already `true` is a no-op"
  with an accurate description — the first `SendStaging` transitions the
  FSM to `Preparing` (which is what prevents the double-submit); the
  `isStarting` flag is a defensive guard reserved for a future
  same-tick re-dispatch path where the FSM has not yet left
  `ReprocessStaging`, and is not currently written by any production
  path. Route via repair-sub-phase (documentation-only, no behaviour
  change).

### AUDIT-PLAN-AND-API-B1-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:166-187` (`StepStarted` arm) + Epic §4 Block A1 line 16-17
- **Description:** Epic §4-A1 prescribes "`StartPipeline`/`StepStarted`
  sets `totalSteps`". The implementation has **only** `StartPipeline`
  set `totalSteps` (from `action.totalSteps`); `StepStarted` restamps
  `elapsedMs` only and does **not** touch `totalSteps` (documented as
  Dev-1 in the block-report). The IMPL rationale is structurally sound:
  `Action.PipelineAction.StepStarted(sessionId, stepName)` carries **no**
  `totalSteps` field, so the `StepStarted` arm is structurally incapable
  of setting a total it never receives. I verified the action shape —
  `StepStarted` indeed has no `totalSteps` payload. Additionally, the
  KDoc on `Running.totalSteps` (`DictateUiState.kt`) claims it is
  "refreshed by `StepStarted` (defensive …)" — this is **inconsistent
  with the actual reducer**, which does not refresh it in `StepStarted`.
- **Why it matters:** Plan-treue: the Epic literal is unsatisfiable
  given the action shape (a genuine plan-vs-API contradiction the IMPL
  agent resolved correctly — `StartPipeline` is the sole authoritative
  `totalSteps` source). The deviation itself is **justified** (no
  downstream consumer relies on `StepStarted` mutating `totalSteps`; the
  live label reads `totalSteps` off `Running` which `StartPipeline`
  already populated). The residual issue is a **doc/code mismatch**:
  `Running.totalSteps` KDoc asserts `StepStarted` refreshes it, which it
  does not. A future maintainer reading the KDoc would expect a refresh
  behaviour that isn't there.
- **Suggested fix scope:** small (documentation) — correct the
  `Running.totalSteps` KDoc to drop the "refreshed by `StepStarted`"
  clause and state that `StartPipeline.totalSteps` is the sole source
  (matching Dev-1).
- **Suggested fix:** Edit `DictateUiState.kt` `Running.totalSteps` KDoc:
  remove "and refreshed by `StepStarted` (defensive — keeps the label
  sane if the runner re-reports a different total mid-run)"; replace
  with a note that `StartPipeline.totalSteps` is the single
  authoritative source because `StepStarted` carries no total in its
  payload (cross-ref Dev-1). Route via repair-sub-phase
  (documentation-only).

### AUDIT-PLAN-AND-API-B1-3

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:713-719` (`dictateButtonText` lambda)
- **Description:** F-15 acceptance (Epic §2 AC-4 / §4-A2) requires
  `dictateButtonText` to be language-aware via `LanguageModule` state
  and that the label "differs across two `LanguageState.effective`
  values". The implementation produces `"Record (en)"` vs `"Record"`
  for `system`/empty. This satisfies the testable contract. Minor
  observation: the production label format `"${record} ($effectiveLanguage)"`
  embeds the raw language *code* (e.g. `"en"`, `"de"`) rather than a
  localised/display name. The Epic §4-A2 example and the
  `TextResolvers.kt` KDoc both say `"Dictate (en)"` so the code-suffix
  form matches the spec example; the IMPL agent's own block-report
  ("Overlooked / Known Gaps") already flags this as a deliberate
  Phase-1 baseline that a later Theme-C/D block can polish. No action
  required for B1 — flagged only so the consolidator has the full
  picture; the F-15 testable contract is met.
- **Why it matters:** No plan-treue or API-contract violation; purely a
  UX-polish note already self-documented by the implementer. Listed for
  completeness per "document it" directive.
- **Suggested fix scope:** small (deferred, out of B1 scope) — no fix in
  this block; tracked by the IMPL agent's known-gap note for a later
  Theme-C/D block.
- **Suggested fix:** None for B1. (Future: resolve the effective
  language code to a localised display string if product wants a
  human-readable language label.)

## API-contract verification (full call-site sweep)

| Contract change | Expected | Verified |
|---|---|---|
| `StopRecordingAndSend` `data class(sessionId)` → `data object` | all dispatch + reducer call-sites payload-less | ✓ `ActionResolvers.kt:105-106` (Active/Paused → `StopRecordingAndSend`), `LayoutCatalog.kt:524` (OVERLAY_SEND), `RecordingModule.kt:259,314` (`Action.RecordingAction.StopRecordingAndSend ->` singleton-match — correct Kotlin for `data object`; both arms read `state.sessionId`). No remaining `StopRecordingAndSend(` call with an argument anywhere in `app/src/main`. |
| `StartRecording` gains non-defaulted `sessionId: String` | all construction sites pass a sessionId | ✓ `ActionResolvers.kt:98-101` (`sessionId = newSessionId()`), `ActionResolvers.kt:222` (overlay, `sessionId = newSessionId()`). No other production `StartRecording(` constructor call exists (the other grep hits are KDoc/comment references or the unrelated Java `proceedStartRecording`). |
| `dictateButtonText: () -> CharSequence` → `(String) -> CharSequence` | all producers + consumers updated | ✓ producer `DictatePipelineService.kt:713` (`{ effectiveLanguage -> … }`), consumer `TextResolvers.kt:102` (`strings.dictateButtonText(state.language.effective)`). The `KeyboardUiController.kt:43` `dictateButtonTextProvider: () -> String` is a **separate legacy field** (different type, different class, legacy-retire surface owned by Theme-C) — not the `LayoutStrings.dictateButtonText` contract; correctly untouched. |
| `RecordingState.Preparing/Active/Paused` gain non-defaulted `sessionId: String` | all constructors updated | ✓ all production constructors in `RecordingModule.kt` (5 transition sites) pass `sessionId`; `grep 'sessionId = ""' app/src` → **ZERO** (main AND test) — F-10 sentinel fully removed, satisfies Epic §2 AC-4 + AC-3. |
| `Running` gains 4 defaulted fields; `ReprocessStaging` gains 1 defaulted field | additive, source-compatible | ✓ all defaulted (`= 0`/`= 0L`/`= false`); existing construction sites unaffected — non-destructive per chunks.json B1 invariant. |

## Stubs / half-finished implementations

**None introduced by the B1 diff.** The grep hits for
"stub/placeholder" in changed files are all **pre-existing,
out-of-B1-scope** scoping anchors owned by later blocks:
`PipelineServiceStubSubsystems.pipelineRunner`/`.notificationCoordinator`
(`DictatePipelineService.kt:419,421`) are the documented C3/C4 stub
surface (Epic §4 Block B1/B2); `DictateUiState.kt:72` Phase-2 audio
stub; `Action.kt:324` Phase-1 stub reducer arm; `LayoutCatalog.kt:38`
B5/C16 placeholder. None are in B1's plan-prescribed scope and none
were added/modified by commits `58bb9a1..HEAD`. The B1 diff itself
*removes* placeholders (TextResolvers `0,0,…,0L` → real `Running`
fields; B4-resolver placeholder replaced) — net stub reduction. No
`TODO: implement` / `throw NotImplementedError` / placeholder-return
introduced.

## Coverage

- Files audited: all 8 production files in the `58bb9a1..HEAD`
  production diff + `DictateInputMethodService.java` (FN-4 seam
  cross-check) + `PipelineModuleTest.kt` (guard-reachability cross-check)
  + Epic §2/§4-A1/§4-A2 + Spec 1 §3/§15.2 (via plan-reader).
- Files skipped (with reason): the 13 test-only contract-update files
  (`AudioModuleTest`, `ViewModeModuleTest`, etc.) — mechanical
  `sessionId = "sid-test"` / payload-less follow-through; out of
  plan-and-api scope (test-quality is AUDIT-TEST's topic). The
  state-file + block-report are inputs, not audit targets.
- Knowledge-skill checkpoints applied: discriminated-union /
  exhaustiveness baseline (knowledge-typescript) — verified the
  `data object` `when`-arm change preserves exhaustive matching in
  `RecordingModule.reduce` (singleton-match `Action.RecordingAction.StopRecordingAndSend ->`
  is correct; no non-exhaustive `when` introduced; all FSM arcs retain
  their `else -> null` rejection semantics).

## Out-of-scope observations (for the consolidator)

- (logic topic) The `elapsedSince(startedAtMs, now).coerceAtLeast(0L)`
  floor (`PipelineModule.kt:368`) defends against
  `now < startedAtMs`; with the defaulted `startedAtMs = 0L`
  (test-only) and a real `ctx.now` this yields a large positive
  elapsed, not a bug — but a logic-agent may want to confirm no
  production path constructs `Running` with `startedAtMs = 0L` (only
  `StartPipeline` constructs it, always with `ctx.now`). Noted for
  AUDIT-LOGIC.
- (convention topic) `newSessionId()` (`ActionResolvers.kt`) uses
  `java.util.UUID.randomUUID().toString()` matching the IME's `:2213`
  pattern — consistent; noted for AUDIT-CONVENTION as a positive
  consistency point, no issue.
