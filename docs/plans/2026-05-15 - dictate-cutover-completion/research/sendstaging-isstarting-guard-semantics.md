# Research — SendStaging `isStarting` Guard Semantics

**Date:** 2026-05-15
**Triggered by:** F-1 (validated-findings-B1.md — merged AUDIT-PLAN-AND-API-B1-1 + AUDIT-LOGIC-B1-1)
**Block:** B1 (Theme A — State-Shape)
**Agent-ID:** B1-VAL-RES-1

---

## 1. Question

`PipelineUiState.ReprocessStaging.isStarting`, the `else if (state.isStarting)
null` guard branch in `PipelineModule.reduce`'s `SendStaging` arm, and the
`LayoutCatalog.kt:390-393` comment collectively describe a double-click guard
that is **inert in production** (grep: zero production writers of
`isStarting = true`; the only writer is `PipelineModuleTest.kt:196`). Decide
between:

- **(a)** Wire `isStarting` as a real guard preserving the runner handshake.
- **(b)** Delete the inert trio, document the FSM `ReprocessStaging →
  Preparing` edge as the canonical single-submit guard, and adjust Epic
  §2 AC-4 + §4-A1 + the F-12 test.

## 2. Sources

| # | Source | What it tells us |
|---|--------|------------------|
| S1 | `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md:261` (Spec 1 §3, the **canonical new-state-module spec**) | The authoritative new-architecture definition is `data class ReprocessStaging(val sessionId: String, val transcript: String) : PipelineUiState` — **no `isStarting` field**. The new state architecture never specified `isStarting`. |
| S2 | `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt:52` (legacy class) | `isStarting` exists only on the **legacy** `core/PipelineUiState.ReprocessStaging`. `grep -rn "isStarting" app/src/main/java/.../core/` → the field is **declared but never read anywhere**, even in legacy code. No disabled-button UX was ever wired to it. The "spec-intended disable Send while starting" UX cited by the audit (legacy `core/PipelineUiState.kt:52`) is a *code comment on a dead field*, not a wired behaviour or a spec requirement. |
| S3 | `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt:103-106` + ADR-0001 §"Main-Thread Confined Dispatch" (`docs/decisions/0001-...:155`) | Dispatch is **main-thread confined**. Re-entrant `dispatch()` from inside `runEffect` is forbidden pattern (h); effect handlers re-enter only via `emitAction` which *posts* to the scope. `store.update` runs exactly once per accepted action. Consequence: two `SendStaging` taps are **serialized** — the first reduces `ReprocessStaging → Preparing`, the second arrives with `state.pipeline is Preparing` and falls to the `else -> null` arm. The FSM edge **is** the sequential double-submit guard. |
| S4 | `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:175-180` (`resolveSendStagingAction`) | The only `SendStaging` producer. It reads `state.pipeline` synchronously inside the click handler and emits `SendStaging(sessionId)`. There is **no optimistic-UI writer, no async resolver, no `Preparing`-successor flag** anywhere that would set `isStarting=true` before the FSM flips. There is no place a legitimate `isStarting` writer would live that the `→Preparing` edge does not already cover. |
| S5 | `docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md:188-193` (Epic §2 AC-4) + `:314-326` (Epic §4 Block A1) | AC-4 names the guard `(!isStarting)`; §4-A1 prescribes the literal `if (state.isStarting) null else copy(isStarting=true)`. C1-A1 (Dev-2, IMPL-PLAN-FIX-1) correctly deviated: the literal pseudo-code **strands the reprocess job** because `StartPipeline` only transitions from `PipelineUiState.Preparing` (verified `PipelineModule.kt:148-172`). The Epic pseudo-code is a mechanical port of the legacy dead field, not a validated design. |
| S6 | `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:390-393` | The `enabledResolver` does **not** read `isStarting` (`{ state -> state.pipeline is PipelineUiState.ReprocessStaging }`); the comment falsely claims the field is "not yet on `ReprocessStaging`" (C1-A1 added it) and that "C14 will fold it in" — C14 is not a real planned chunk in this Epic. The spec-disabled-button UX it references has no spec home (see S2). |

## 3. Findings

### 3.1 Consensus

- The **canonical new-state spec (S1) does not contain `isStarting`.** Its
  presence on the new `state/DictateUiState.kt` `ReprocessStaging` is a
  carry-over from the legacy class, mechanically transcribed into the Epic
  AC-4/§4-A1 pseudo-code without re-validation.
- `isStarting` is **inert at three sites** and protects nothing. The real
  single-submit protection is the FSM `ReprocessStaging → Preparing` edge
  plus main-thread-confined serial dispatch (S3).
- The "disable Send while starting" UX the audit worried about losing under
  option (b) **does not exist in any spec and was never wired even in legacy
  code** (S2). Option (b) does not regress a shipped behaviour — there is no
  behaviour to regress. The audit's "loses the spec disabled-button UX"
  caveat is based on a dead legacy code-comment, not a spec acceptance
  criterion.

### 3.2 Decision: **Option (b) — delete the inert trio.**

Rationale (D4 long-term-highest-quality + D5 research-more + Epic-AC honesty):

1. **Spec-alignment beats Epic-pseudo-code-literalism.** The canonical
   new-architecture spec (Spec 1 §3, S1) is the single source of truth for
   the new `state/` module shape and explicitly defines `ReprocessStaging`
   *without* `isStarting`. Option (a) would entrench a field the canonical
   spec rejects, in order to wire a UX (disabled Send button) that no spec
   ever requested and no code ever implemented — that is gold-plating
   against the spec. The Epic §4-A1 pseudo-code is a derived artefact that
   was already proven wrong (it strands the job, S5); the right correction
   is to fix the Epic to match Spec 1, not to fix the code to match a
   broken Epic literal.
2. **A shipped state-field that does nothing is a serviceability trap
   (D4).** B2 (FGS notification) and B3 (recording-drive cutover) build on
   this exact `PipelineUiState`/`SendStaging` seam. A maintainer extending
   B2/B3 who sees `isStarting` + a guard branch + a comment will reasonably
   believe `isStarting` is the live double-submit guard and may "repair"
   the *actual* guard (the `→Preparing` edge), breaking the runner
   handshake. Removing the trio and documenting the FSM edge as canonical
   makes the seam unambiguous *before B2* (the routing recommendation's
   gate condition).
3. **Option (a) cannot be wired without inventing a writer with no caller.**
   S3+S4 show dispatch is main-thread-confined and the sole `SendStaging`
   producer reads state synchronously. There is no async/optimistic-UI path
   that the FSM edge does not already cover; `isStarting` could only be set
   by the very same reducer reduction that also transitions to `Preparing`,
   at which point it is redundant with the `Preparing`/`else→null` guard
   and still does not feed any `enabledResolver` (no UX consumer exists).
   Option (a) would add type-surface and an `enabledResolver` change for
   zero behavioural gain — the opposite of D4.
4. **Lower code-risk, one coherent story.** Option (b) collapses the
   doc-drift cluster (F-1 doc-leg + F-2 + F-3) and the F-1 mechanism into a
   single consistent narrative: "the FSM `ReprocessStaging → Preparing`
   edge, on main-thread-confined serial dispatch, is the canonical
   single-submit guard."

### 3.3 B2 / B3 forward-impact of option (b)

- **B2 (FGS notification):** consumes `PipelineUiState.Running`
  counters + `Preparing`/`Idle` transitions. It does **not** read
  `ReprocessStaging.isStarting`. Removing the field has **zero** B2 impact;
  it *improves* B2 by removing an ambiguous flag from the seam B2 reads.
- **B3 (recording-drive cutover):** routes the IME's `preAllocatedId` into
  `StartRecording`; the `SendStaging`/reprocess path's single-submit
  guarantee is now explicitly the FSM `→Preparing` edge. B3 must not
  re-introduce an `isStarting`-style optimistic flag; if a future
  optimistic-UI disable is ever genuinely required, it belongs in a
  later Theme-C/D UI block as an `enabledResolver` reading a *derived*
  predicate (e.g. `pipeline is Preparing`), **not** a new state field —
  record as a forward-note (not a B1 obligation).
- No `EmitPipelineTrigger`/`SubmitReprocess`/`TriggerPipeline` effect
  shape changes — option (b) is pure deletion + doc + plan/test text.

## 4. Implementation Hints (concrete, for the repair leg)

1. **`app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`** —
   delete `val isStarting: Boolean = false` from `ReprocessStaging`
   (`:250`) and its `@property isStarting ...` KDoc block (`:238-246`).
   Keep `ReprocessStaging(sessionId, transcript)` exactly matching Spec 1 §3.
2. **`app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`**
   — in the `SendStaging` arm (`:289-320`) remove the
   `else if (state.isStarting) null` branch and its F-12 comment
   (`:292-298`); collapse to: sessionId-mismatch → `null`, else the
   `Preparing` + `SubmitReprocess` + `UpdateNotification` transition.
   Replace with a short comment documenting the canonical guard:
   *"Single-submit guard: the first SendStaging transitions
   `ReprocessStaging → Preparing`; a second tap arrives with
   `pipeline is Preparing` and falls to `else -> null`. Dispatch is
   main-thread-confined (ADR-0001) so the two taps are serialized — the
   FSM edge is the guard, not a state flag."*
3. **`app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:390-393`**
   — replace the stale comment with: *"Enabled whenever the pipeline is
   in `ReprocessStaging`. Single-submit is guarded by the FSM
   `ReprocessStaging → Preparing` edge (PipelineModule `SendStaging` arm),
   not by an enabled-state flag."* `enabledResolver` body unchanged
   (already correct).
4. **`docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md`**
   — Epic §2 AC-4 (`:193`): change "the SendStaging double-click guard
   (`!isStarting`)" → "the SendStaging single-submit guard (the FSM
   `ReprocessStaging → Preparing` edge — a second tap arrives in
   `Preparing` and reduces to `null`)". Drop `isStarting` from the AC-4
   `ReprocessStaging has isStarting: Boolean (F-12)` clause (`:188-189`):
   keep F-12 as "the SendStaging single-submit guard is the FSM
   `→Preparing` edge". Epic §4 Block A1 (`:314-315`): replace the
   `if (state.isStarting) null else copy(isStarting=true)` pseudo-code
   with the FSM-edge description; add a one-line note: *"(Plan-deviation
   B1-VAL-W1, option (b): the legacy `isStarting` field is not carried
   into the new `state/` module — Spec 1 §3 defines
   `ReprocessStaging(sessionId, transcript)` without it; the FSM
   `→Preparing` edge on main-thread-confined dispatch is the canonical
   single-submit guard. See research/sendstaging-isstarting-guard-semantics.md.)"*
5. **`app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt`**
   — the F-12 contract changes from "field-flag no-op" to "FSM-edge no-op":
   - Delete `F-12 ReprocessStaging defaults isStarting to false` (`:187-190`)
     — field removed.
   - Rewrite `F-12 SendStaging while isStarting true is a no-op`
     (`:192-199`) as `F-12 second SendStaging after the first is a no-op
     (FSM →Preparing edge guards single-submit)`: first
     `reduce(ReprocessStaging(sid,"x"), SendStaging(sid))` → assert
     `nextState is Preparing`; then `reduce(thatPreparingState,
     SendStaging(sid))` → assert `assertNull(result)` (the `else -> null`
     arm). This asserts the *real* guard. Not a regression — it is the
     documented test-contract update for option (b).
   - `F-12 SendStaging with isStarting false still submits once`
     (`:202-210`) → drop the `isStarting = false` arg
     (`ReprocessStaging(sid, transcript = "x")`); assertions unchanged.
   - `F-12 SendStaging with mismatched sessionId is rejected ...`
     (`:212-217`) → drop the `isStarting = false` arg; assertions unchanged.

## 5. References

- Block-report: `../reports/B1-theme-a-state-shape.md#block-validate-repair-wave-1`
- Validated findings: `../reports/validated-findings-B1.md` (F-1)
- Plan: `../dictate-cutover-completion.md` (§2 AC-4, §4 Block A1)
- Canonical state spec: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §3 (`:261`)
- ADR-0001 §"Main-Thread Confined Dispatch"
