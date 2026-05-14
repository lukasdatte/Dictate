# ADR-0002: State — Cross-Module Cascade

**Status:** Proposed
**Subsystem:** state
**Scope:** Project-Wide
**Date:** 2026-05-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001.** ADR-0001 owns the modular-orchestrator
> foundation (single-dispatch, pure reducers, lens, per-module ownership).
> This ADR adds the rules that govern *how modules influence each other* —
> the cascade modes, the snapshot semantics, the depth guard, and the
> failure-channel routing.

## Research

The cross-module-cascade rules are the product of three iterations
recorded in Spec 1 + KG-RSB resolutions:

- Spec 1 §15.5 (Cross-Module-Effect-Modi) — the canonical
  Mode 1 / Mode 2 / Mode 3 catalogue with anti-pattern table.
- KG-RSB-2 RESOLUTION (Spec 1 §4.3, 2026-05-11) — the **self-cascade
  bug**: the earlier `dispatchInternal` Step 5 had a self-filter
  (`it.id != module.id`) that deterministically blocked self-cascades
  (RecordingModule observing its own Idle → Preparing transition to
  fire `OverlayAction.ResetSuppressBit`). The filter was a
  belt-and-suspenders "infinite-loop guard" that produced a real
  production bug: HOVER-Overlay would not reopen after the first
  user-close in a session. The fix is to **remove the filter** and
  rely on `MAX_CASCADE_DEPTH` instead.
- KG-RSB-3 RESOLUTION (Spec 1 §15.1.x, 2026-05-11) — the **self-read
  notation** for the cross-module coupling matrix: a module reading
  its own axis as cascade trigger does not get a `R(state.x)`
  diagonal entry; only the `C(Action.Y.Z)` consequence in the
  cross-module cell.
- Phase-B S-9 report (2026-05-13) — found that Spec 3 §7.3 (T1/T2
  transitions) accidentally used Mode-3 cross-axis mutation
  (`ViewModeModule.reduce` writing to `viewMode + layout.smallMode +
  overlay.userPrefersWidget` atomically), while Spec 3 §6.1 used the
  correct Mode-2 cascade. The duplicate-truth was resolved by
  rewriting §7.3 onto the Mode-2 form (LayoutModule + OverlayModule
  cascade via `onCrossModuleStateChange`).
- Spec 1 §4.3 (DictateOrchestrator.dispatchInternal) — the
  EffectFailure-routing via `originModuleId` (not via KClass)
  was added in Phase-B S-3 (2026-05-13) because all modules emit
  the same Action subtype as failure (`Action.EffectFailure`).
  KClass-routing would have routed every failure to a single arbitrary
  module — the origin-routing decouples failure handling per module.

## Context

Once we accept ADR-0001's "one owner per state axis" rule, we still
need to answer: how does *Recording → Pipeline* coordination happen?
Three mechanisms came up in the design:

1. **Mode 1 — own SideEffect.** Module A's reducer emits an Effect
   that touches hardware in another subsystem (e.g.
   `RecordingModule.Effect.AllocateMediaRecorder` triggers the
   `RecordingHardware` subsystem). No cross-axis state mutation.
2. **Mode 2 — Action-Cascade.** Module B observes Module A's state
   change via `onCrossModuleStateChange(prev, next): List<Action>`
   and emits actions targeted at its own (or third) modules. The
   orchestrator dispatches those actions recursively.
3. **Mode 3 — Atomic Cross-Axis-Update.** A reducer writes more
   than one sub-state axis in one transition. Convenient when the
   axes are logically inseparable, but breaks SRP (the writer
   owns axes it doesn't conceptually own).

The Phase 1 architecture has no use case that genuinely needs
Mode 3 (Spec 1 §15.5 verdict). Mode 1 + Mode 2 together cover all
the identified cross-module flows: AudioFocus-Loss → Pause,
PipelineDone → Resend.MarkAvailable, ViewMode → Layout.SmallMode,
Recording.Preparing → Overlay.ResetSuppressBit, etc.

The remaining open questions on this layer were the **cascade depth
guard**, the **self-cascade rule**, and the **failure-channel
routing** — each producing its own resolved KG-marker.

## Decision

Cross-module effects run through **two allowed modes** with a strict
forbidden third.

### Scope of this Convention

Project-wide for **all modules implementing `DictateModule<S, A, E>`**
under `app/src/main/java/net/devemperor/dictate/state/modules/`.
The convention applies to every reducer + every
`onCrossModuleStateChange`-hook. The legacy `core.PipelineOrchestrator`
(audio-pipeline runner) is unaffected — it does not interact with
the modular state at all.

### The two allowed modes

| Mode | Mechanism | When to use |
|---|---|---|
| **Mode 1 — Own SideEffect** | Reducer emits a `SideEffect` of the **owning module's** sealed interface. The effect triggers hardware in some subsystem; the state mutation stays on the module's own axis. | Hardware/Pref operations tied to a state change in the same axis (e.g. `RecordingModule.reduce`: `Idle → Preparing` plus `Effect.AllocateMediaRecorder`). |
| **Mode 2 — Action-Cascade** | Module B observes a (prev, next) transition via `onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action>` and returns actions; the orchestrator dispatches them recursively at `depth+1`. | Cross-module reactions: AudioFocus-Loss → Pause, PipelineDone → Resend.MarkAvailable, ViewMode → Layout.SmallMode. |

### The forbidden third mode

**Mode 3 — Atomic Cross-Axis-Update** is forbidden in Phase 1:
a reducer mutates **its own axis plus another module's axis** in
one transition (`state.copy(viewMode = …, layout = …)` from
`ViewModeModule.reduce`). The corrective form is **Mode 2**:
`ViewModeModule.reduce` mutates only `viewMode`; `LayoutModule`
observes the (prev, next) tuple via `onCrossModuleStateChange` and
cascades `Action.LayoutAction.SetSmallMode(true)` to itself.

Mode 3 may be reinstated in Phase 2 only on the back of a concrete
use case that demonstrates a real race or atomicity requirement
unsolvable by helper consolidation (plan §7.1 Out-of-Scope).

### Self-cascade is allowed (KG-RSB-2 fix)

A module is allowed to observe **its own** state transition in
`onCrossModuleStateChange` and cascade actions. Example:
`RecordingModule.onCrossModuleStateChange` reads
`prev.recording is Idle && next.recording is Preparing` and
cascades `Action.OverlayAction.ResetSuppressBit`.

The earlier self-filter (`modules.filter { it.id != module.id }`)
in `dispatchInternal` Step 5 was **removed** and must not be
reintroduced. The regression test
`DictateOrchestratorTest.recordingModule_idleToPreparing_emits…`
guards against re-introduction.

### Frozen snapshot

Before Step 5 (cross-module observation), `prevGlobal` and
`nextGlobal` are snapshotted from the store. Every observer
in the cascade pass sees the same `(prev, next)` tuple — there
is no race where one observer sees a state mutated by an earlier
observer in the same pass. The recursive `dispatchInternal(cascadeAction,
depth+1)` calls re-snapshot for their own observation pass.

### `MAX_CASCADE_DEPTH = 8`

The only loop guard. On DEBUG builds, exceeding it raises `error()`;
on release builds, it logs an error and returns
`DispatchOutcome.Rejected(action, "cascade-loop")`. The IME never
crashes from a runaway cascade. Real cascade depths in the
designed flows are 1–3 (e.g. `RecordingDone →
ResendModule.onCrossModuleStateChange → Action.ResendAction.MarkAvailable`).

### EffectFailure routing via `originModuleId`

`Action.EffectFailure(originModuleId: ModuleId, effect: String, reason: String)`
is the failure channel. All modules emit the same Action subtype,
so KClass-routing would route every failure to one arbitrary
module. Instead, `dispatchInternal` Step 1a routes the failure
to the module identified by `originModuleId` (via a secondary
`moduleById: Map<ModuleId, DictateModule>` lookup). The module's
`reduceFailure(state, failure, ctx)` hook is invoked; default
return is `null` → `DispatchOutcome.Rejected("reducer-null")`
("no failure path defined" is semantically correct, not a bug).

### Cascade-order contract

Cascade actions follow the order of `DictateModuleRegistry.all`.
Each recursive `dispatchInternal(cascadeAction, depth+1)` produces
a **fresh** `(prevGlobal, nextGlobal)` snapshot — later cascades
see the state including earlier cascade mutations from this pass.
Modules **should not** plan a cascade that depends on another
cascade running before it; that's a Mode-3 use case and belongs in
the Phase-2 backlog (plan §7.1). Reordering
`DictateModuleRegistry.all` is a plan-relevant refactor verified by
`DictateOrchestratorCascadeOrderTest.kt`.

### Self-read in the coupling matrix (KG-RSB-3 convention)

Per-module cross-module-coupling is documented in the matrix
(Spec 1 §15.1.x). Self-reads — a module reading its own axis as
trigger for a cross-module cascade — are NOT entered as
`R(state.x)` on the matrix diagonal. The diagonal stays `—`, and
the cross-module cell shows only the `C(Action.Y.Z)` consequence.
This keeps the matrix scannable and avoids verbose `[self]R(...)`
markers.

## Alternatives Considered

1. **Allow Mode 3 from day one.** Rejected because there is no
   Phase-1 use case that requires it and admitting it would
   reintroduce the multi-owner-per-axis class of bug (plan §1.1).
   The Mode-3 anti-pattern table in Spec 1 §15.5 catalogues
   the resulting SRP-bruchs.
2. **Use Kotlin `flow.combine` for cross-module reaction
   instead of `onCrossModuleStateChange`.** Rejected because
   combine-style would make the cascade implicit (flow-collector
   side-effects) and hide depth — the cascade depth counter
   would become unimplementable. The explicit
   `List<Action>`-return is auditable and capped.
3. **Synchronous re-dispatch from a `runEffect` body.** Rejected
   because it would break the frozen-cascade-snapshot
   invariant (an effect would mutate state inside the same pass
   that other observers are looking at the old snapshot). The
   `services.emitAction()` async-via-`scope.launch` path is the
   sanctioned escape.
4. **Self-filter in `dispatchInternal` Step 5.** Was the original
   design. Rejected after KG-RSB-2 analysis: the filter blocked
   self-cascades and produced a real production bug
   (HOVER-Overlay reopen after first user-close). The
   `MAX_CASCADE_DEPTH` guard is sufficient.
5. **EffectFailure routing via KClass.** Rejected because
   `Action.EffectFailure` is a single subtype shared across all
   modules — KClass-routing would resolve to one arbitrary
   module. Origin-module routing via `ModuleId` is the only
   correct semantic.

## Consequences

**Positive:**

- Cross-module coordination is **auditable**: the matrix in Spec 1
  §15.1.x and the `onCrossModuleStateChange` hooks list every
  read and every cascade explicitly.
- Self-cascade unlocks the `RecordingModule.Idle → Preparing →
  OverlayAction.ResetSuppressBit` flow that the HOVER-Overlay
  reopen depends on (KG-RSB-2 production bug).
- The depth cap eliminates the "what if cascades loop?" class
  of risk without paying for a static analysis pass.
- EffectFailure routing per origin module keeps recovery logic
  in the module that knows the effect (e.g. `RecordingModule`
  rolls `Preparing → Idle` after `AllocateMediaRecorder` fails).
- The matrix convention (`R(...) / C(...)`) makes new cascades
  visible at code-review time: a `onCrossModuleStateChange`
  hook without a matrix-row update is a code-review violation.

**Negative:**

- Frozen-snapshot semantics force cascade observers to think in
  `(prev, next)` tuples, not in "what's the state right now?".
  This is the price of consistency: if you let observers see
  mutations from earlier observers in the same pass, you get
  order-dependent cascades, which are very hard to test.
- Mode 2 introduces an extra round-trip for any cross-axis
  effect: A's `reduce` emits state-change → store update →
  every module's `onCrossModuleStateChange` is called → B's
  cascade-action dispatched → B's `reduce` → store update.
  Compared to a hypothetical Mode-3 single-step, that's
  one extra dispatch. Acceptable because Mode 3 was the
  multi-owner-per-axis trap.
- The depth cap is a magic number (8). If a legitimate cascade
  needs depth >8, it's a Mode-3 use case in disguise and should
  be re-designed. The cap is conservative.

**Failure Modes:**

- **Re-introducing the self-filter** (forbidden pattern (f)
  in ADR-0001 §"Forbidden Patterns"). Future maintainer
  reading the cascade code might re-add `modules.filter { it.id
  != module.id }` as a "looks like an infinite-loop guard". The
  result: HOVER-Overlay no longer reopens after the first
  user-close in the session — the exact KG-RSB-2 bug. Mitigation:
  the ⚠-banner comment in `DictateOrchestrator.dispatchInternal`
  Step 5 + the regression test
  `DictateOrchestratorTest.recordingModule_idleToPreparing_emits…`
  (Spec 1 §10 R.RSB-FIX-A).
- **Cross-axis mutation in the reducer** (forbidden pattern (g)).
  A reducer writes `state.copy(viewMode = …, layout = …)`. The
  compiler does not stop this; only code review does. The
  Phase-B S-9 finding (Spec 3 §7.3) showed how subtle the
  duplicate-truth-quelle gets — two snippets in the same spec
  used different modes. Mitigation: the anti-pattern table in
  Spec 1 §15.5 + the architecture-doc page
  `cross-module-cascade.md` explicitly cataloguing the four
  forms (Mode 1 / Mode 2 / Mode 3 forbidden / Mode 2 with
  self-read).
- **Cascade ordering drift.** Reordering
  `DictateModuleRegistry.all` silently changes the cascade-effect
  sequence. Mitigation: `DictateOrchestratorCascadeOrderTest.kt`
  asserts a known ordering and Block-1b documents the order as
  part of acceptance.
- **`Action.EffectFailure` swallowing.** A module without an
  override returns `null` from `reduceFailure`; the resulting
  `DispatchOutcome.Rejected("reducer-null")` is silent (logged at
  debug only). A bug in the affected module's failure path won't
  surface until a manual test triggers the error. Acceptable
  because each module documents in Spec 1 §15.x whether it
  needs a failure path.

## References

- **Related Plan:** [dictate-keyboard-layout-refactor](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md) §4.0.1.3, §4.0.1.4, §4.0.1.5 (forbidden patterns (f, g))
- **Related Spec:** [Spec 1 — Pipeline-Service](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md) §4.2 (reduceFailure), §4.3 (dispatchInternal Step 5), §15.1.x (Coupling-Matrix), §15.2 (RecordingModule example), §15.5 (Cross-Module-Effect-Modi)
- **Related ADRs:**
  - **ADR-0001 — state-modular-orchestrator-pattern.** This ADR extends ADR-0001 by adding the cross-module-cascade rules on top of the single-dispatch + per-module-reducer foundation. ADR-0001 is a hard prerequisite — the mechanics defined here only make sense once one accepts the modular ownership model.
  - **ADR-0005 — ui-triangle-fsm-keyboard-widget-hover.** The T7 transition (HOVER → KEYBOARD on PipelineDone) is implemented as a Mode-2 cascade: `PipelineModule.onCrossModuleStateChange` cascades `Action.ViewModeAction.OnPipelineDone`. This ADR defines the cascade machinery; ADR-0005 names the specific cascade in question.
- **Architecture docs:**
  - [state-architecture/cross-module-cascade.md](../architecture/state-architecture/cross-module-cascade.md)
  - [state-architecture/effects-and-failures.md](../architecture/state-architecture/effects-and-failures.md)
  - [state-architecture/forbidden-patterns.md](../architecture/state-architecture/forbidden-patterns.md)
- **Skill:** `~/.claude/skills/knowledge-adr-format/SKILL.md`

## Decision History

### 2026-05-14 — Initial proposal

**Trigger:** Plan §4.0.1.4 + §4.0.1.5(f, g) defines cross-module
cascade rules as a binding pre-code contract. KG-RSB-2 and KG-RSB-3
resolutions (Spec 1, 2026-05-11) and Phase-B S-9 (Spec 3 §7.3
realignment, 2026-05-13) cemented the cascade rules.

**Before:** Earlier design had a self-filter in `dispatchInternal`
that produced a real production bug (HOVER-Overlay reopen failure).
Two specs (Spec 3 §6.1 and §7.3) showed conflicting cascade forms
(Mode 2 vs. accidental Mode 3).

**After:** Mode 1 (own SideEffect) and Mode 2 (Action-Cascade via
`onCrossModuleStateChange`) are the only allowed cross-module
mechanisms. Mode 3 (atomic cross-axis update) is Phase-2-Backlog.
Self-cascade is allowed (filter removed, depth-cap is sole guard).
`MAX_CASCADE_DEPTH = 8`. `EffectFailure` routes via `originModuleId`.
Frozen-snapshot semantics for cascade observers.

**Reasoning:** Two-mode cascade lets each module stay SRP-clean
(one owner per axis) while still allowing rich cross-module
coordination. The self-cascade-allowance is non-negotiable
(KG-RSB-2 production bug). The depth-cap-only loop guard is
sufficient because real cascades are 1–3 levels deep and any
deeper case is a Mode-3 disguise.

### Phase-2 Superseding Expectations

This ADR is the most-likely candidate for a partial supersede
when a Phase-2 use case requires Mode 3. The shape of that
supersede would be:

- Add a Mode-3 rule that names the specific use case (e.g.
  "InterruptionModule may atomically reset `recording + audio +
  pipeline` on incoming call"). Mode 3 stays forbidden by
  default; only the named use case is allowed.
- The supersede creates an ADR (e.g. ADR-NNNN-state-mode-3-call-interruption)
  with `Status: Supersedes ADR-0002` (or rather: "extends
  ADR-0002 §Mode 3 with a named exception"). The cross-link
  is bidirectional.

A full supersede of this ADR would mean a different cascade
mechanism altogether — e.g. moving to a flow-combine-based reactive
graph. Not anticipated for Phase 2.
