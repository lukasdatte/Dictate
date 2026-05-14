# ADR-0001: State — Modular Orchestrator Pattern

**Status:** Accepted
**Subsystem:** state
**Scope:** Project-Wide
**Date:** 2026-05-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0002.** This ADR defines the modular orchestrator
> mechanics (single dispatch, pure reducers, lens-based sub-state, registry,
> module interface). ADR-0002 builds the cross-module-cascade rules on top of
> that foundation. Together they form the complete state-mutation contract.

## Research

The decision rests on the iterative architectural work documented in the
parent plan and Spec 1:

- Plan `dictate-keyboard-layout-refactor.reviewed.md` §1.1 ("Symptom-Geschichte"):
  five distinct production bugs were traced to **distributed state mutation**
  — `RecordingStateController`, `RecordingUiController`, `KeyboardUiController`,
  `KeyboardStateManager`, and `DictateInputMethodService` all wrote into the
  same logical state axes (visibility, layout, recording lifecycle), causing
  silent overwrites and race conditions.
- Plan §1.3 (research-evidence): `_pending-state-machine-visibility-owners.md`
  catalogued 27 visibility mutations on a single attribute (`resend_btn`),
  of which 5 were structurally problematic. The diagnosis: **no single owner
  per state axis**.
- Spec 1 §4.1 + §15 (architecture iteration F-1 → F-8 → F-10 → F-11): an
  initial `PipelineStateManager` design carried five responsibilities
  (state mutation + pref sync + FSM + recovery + JobExecutor-init) and
  scaled to a 30+-field "knows-everything" state class — the same SRP
  anti-pattern that motivated the refactor. The eventual landing point
  is the modular `DictateOrchestrator` + per-axis `DictateModule`
  decomposition.
- Spec 1 §13.3 + §15.7 — SOLID verification per module showing the
  pattern preserves SRP/OCP/LSP/ISP/DIP/DRY.
- Excel-EKL module-augmentation pattern (TypeScript) — proven prior art for
  per-axis plugin modules with type-safe action routing; adapted here
  to Kotlin via `sealed interface DictateModule<S, A, E>` +
  `object`-singletons.

## Context

Before this refactor, the IME's UI state lived in a hybrid:
`SharedPreferences` for some toggles, `RecordingStateController` for the
recording lifecycle, `KeyboardUiController` for pipeline state,
`KeyboardStateManager` for layout flags, and the service itself for
ad-hoc fields. Five concrete bugs (plan §1.1) traced back to "multiple
writers per logical state axis". The product requirement to support
keyboard-switch survival (plan §1.2) makes this worse: state must outlive
the IME-Service lifecycle and live in a separate process-resident
container.

We needed a state-mutation contract that:

1. Enforces a single mutation entry point.
2. Splits state into per-domain axes with a single module owning each axis.
3. Allows modules to be added or evolved without touching central code.
4. Stays testable — reducers must be pure functions so that tests run on
   the JVM without an Android `Context`.

## Decision

Adopt the **`DictateOrchestrator` + 13 `DictateModule`** pattern with
**Single Dispatch**, **Pure-Reducer Invariant**, and **Lens-based
sub-state encapsulation**.

State mutation runs exclusively through `orchestrator.dispatch(action: Action)`
on the main thread. Each sub-state axis (e.g. `recording`, `pipeline`,
`audio`) has exactly one owner module; modules communicate only via the
global state and the action pipe, never directly.

### Scope of this Convention

Project-wide for **all UI-state mutation** in the `state/` subsystem of
the `DictatePipelineService` (Spec 1 §3, §4). Out of scope:

- Database row-level mutations (Room DAOs) — DB state has its own owner
  (the DAO) and is subscribed to via `PendingSessionsModule` (Spec 1
  §15.1 #12).
- Pure-rendering surfaces (`RenderBackend`-implementations) — they read
  state, never write it (see ADR-0004 §"Decision").
- The legacy `core.PipelineOrchestrator` (audio-pipeline runner) which
  remains untouched in Phase 1 (plan §7.1 Out-of-Scope).

### Required mechanics (binding contract for Block 1b…6)

1. **Single-Dispatch API** — `fun dispatch(action: Action): DispatchOutcome`
   on `DictateOrchestrator`, exposed via the `LocalBinder`. There is **no**
   other API on the binder beyond `state: StateFlow<DictateUiState>` and
   lifecycle hooks. Forwarder methods that parallel the action hierarchy
   (e.g. `binder.startRecording()`) are forbidden (forbidden pattern (i)
   below).
2. **Pure-Reducer Invariant (F1 + F2)** — each module's `reduce(state, action, ctx)`
   is a deterministic, pure function on `(S, A, ReducerContext) →
   TransitionResult<S, E>?`. Hardware/IO/threading/`else`-branches over
   sealed `Action`s are forbidden (forbidden patterns (b) + (c)).
3. **Lens Pattern** — every module implements `read(global): S` and
   `write(global, sub): DictateUiState` so the orchestrator can swap
   the sub-state atomically. The reducer signature operates on `S`, not
   `DictateUiState` — writing `state.audio = …` from `RecordingModule.reduce`
   is a compile error.
4. **`Action` sealed hierarchy** — one inner `sealed class` per module
   (e.g. `Action.RecordingAction`) plus a top-level
   `Action.EffectFailure(originModuleId, effect, reason)` failure channel
   (Spec 1 §3.3, §4.2).
5. **KClass-Lookup Routing** — `DictateOrchestrator` builds a
   `Map<KClass<out Action>, DictateModule<*, *, *>>` at init via
   `KClass.sealedSubclasses`. A duplicate routing is an init-time error.
   ProGuard must keep the `Action` hierarchy in release builds
   (Spec 1 §4.3 ProGuard-Keep block).
6. **`DictateModuleRegistry.all`** — single list of all 13 active modules
   (+ 1 Phase-2 stub). Order is part of the contract: cross-module-cascade
   order follows registry order (ADR-0002 §"Cascade-Order").
7. **`MAX_CASCADE_DEPTH = 8`** — guards against runaway recursion in
   cross-module cascades (delegated to ADR-0002).
8. **Main-Thread-Confined Dispatch** —
   `require(Looper.myLooper() == Looper.getMainLooper())` at the top of
   `dispatch()`. Async re-entry must go through `emitAction()` (a
   `scope.launch { dispatch(action) }`).

### State diagram — who holds state

```
┌──────────────────────────────────────────────────────────────┐
│  DictateUiStateStore                                          │
│                                                              │
│    private val _state = MutableStateFlow(initial)            │
│    val state: StateFlow<DictateUiState> = _state.asStateFlow()│
│                                                              │
│    fun update(reducer: (DictateUiState) -> DictateUiState)   │
└──────────────────────────────────────────────────────────────┘
       ▲                                            │
       │ Only mutator                               │ Read-only via collect{}
       │                                            │
       ▼                                            ▼
┌──────────────────────────┐         ┌─────────────────────────────┐
│  DictateOrchestrator     │         │  Subscribers                │
│  .dispatch(action)       │         │  - KeyboardLayoutManager    │
│                          │         │  - PipelineNotificationCoord│
│  Main-thread confined    │         │  - PrefMirror               │
└──────────────────────────┘         │  - DB-Subscriber            │
                                     └─────────────────────────────┘
```

Three invariants drive the diagram:

1. **State is read-only externally** — observers see it only via
   `state.collect { … }`.
2. **Mutation is single-channel** — `dispatch(action: Action)` is the
   only entrance; the raw `_state.value = …` setter is private to the
   store.
3. **Dispatch is main-thread confined** — async producers post their
   actions via `emitAction()`.

### UI-Wiring boundary (this ADR's §10)

State is consumed by `RenderBackend`-implementations (ADR-0004 governs
the rendering surface). The constraint from this ADR's side:

- `Action?`-returning resolvers from a `ButtonSlot` get fed into
  `onAction?.invoke(it)`. `null` is a silent no-op.
- Click-listeners are wired **once** in `attach()`; per-render rewiring
  is forbidden pattern (l) (memory-leak vector). Lambdas reference
  `stateRef`/`modeRef` backend fields.
- Visibility and enabled-checks are NOT done in the click listener —
  `visibilityPredicate` + `enabledResolver` manage them in the render
  loop (see ADR-0004).

### Module inventory (13 active + 1 Phase-2 stub)

The binding axis-ownership contract: **13 active modules + 1 Phase-2 stub**, each owning exactly one state axis.

| # | ModuleId | Axis it owns |
|---|----------|--------------|
| 1 | `RecordingModule` | recording lifecycle |
| 2 | `PipelineModule` | pipeline FSM |
| 3 | `AudioModule` | audio (focus / SCO / vibration) |
| 4 | `ViewModeModule` | viewMode (Triangle-FSM — see ADR-0005) |
| 5 | `OverlayModule` | overlay (position + permission + suppress + onboarding) |
| 6 | `ResendModule` | resend (last-audio + cooldown) |
| 7 | `LivePromptModule` | livePrompt (chain buffer + chaining) |
| 8 | `LanguageModule` | language (effective + reprocess override) |
| 9 | `LayoutModule` | layout (contentArea + Pref-mirror flags) |
| 10 | `FeatureToggleModule` | features (5 product-toggles) |
| 11 | `ThemingModule` | theming (theme + accent + overlay chars + speed) |
| 12 | `PendingSessionsModule` | pendingSessions (DB-subscriber) |
| 13 | `KeyboardInputModule` | n/a — `Unit`-state, effect-only (IME direct-input) |
| 14 | `InterruptionModule` | interruption (Phase 2 stub — call/headset/screen-off) |

Each module lives in `app/src/main/java/net/devemperor/dictate/state/modules/`
as one file containing State + Action + Reducer + SideEffect + EffectHandler
+ optional Cross-Module-Observer.

> Full per-module descriptions, observer flags, and the
> Cross-Module-Coupling-Matrix live in the teaching doc:
> see [state-architecture/modules.md §7.1 — Module inventory](../architecture/state-architecture/modules.md#71-module-inventar-13-active--1-phase-2-stub).
> (The ADR holds the binding axis-count + per-module axis-name
> contract; the architecture-doc holds the SoT inventory + matrix.)
> Spec 1 §15.1 (Modul-Inventar) is the upstream source of both.

## Alternatives Considered

1. **Adopt an MVI library (Orbit / MVIKotlin).** Would have given us
   middleware, time-travel, and an ecosystem. Rejected because the
   library APIs are general-purpose and would have forced us to fight
   them on per-module pure-reducer signatures and on Android's
   IME-Service lifecycle. The standalone path with `StateFlow` +
   sealed `Action` + per-module reducers is ~200 lines of orchestrator
   code and 0 KB APK impact. The library evaluation lives in the
   plan's iteration log (F-9, 2026-05-09).
2. **Single monolithic reducer with `when` over all action types.**
   Rejected on SRP/OCP grounds: every new state axis would touch a
   2000-line central `reduce()` function; Spec 1 §4.1 F-11 walks
   through the bloat trajectory.
3. **Free-form mutators (each subsystem owns a method on the binder).**
   This was the F-8 problem state. Each new use case duplicated the
   action data into a binder method signature, creating two parallel
   APIs that drifted (forbidden pattern (i)). The Single-Dispatch
   norm eliminates the duplication.
4. **Sub-class-based modules.** Would have replaced the sealed-interface
   contract with an abstract base class. Rejected because exhaustive
   compile-time module identification at the orchestrator is more
   valuable than the ergonomics of a base class — `sealed interface`
   gives the compiler full knowledge of the module population.
5. **Mode 3 (Atomic Cross-Axis-Update) in the reducer.** Considered
   for cases where two axes are logically inseparable. Rejected for
   Phase 1 (plan §7.1 Out-of-Scope). See ADR-0002 §"Forbidden Patterns
   (g)" for the routing decision.

## Consequences

**Positive:**

- Single mutation entry eliminates the multi-writer bug class (plan
  §1.1 bugs #1–#3b).
- Each module file is independently reviewable and unit-testable.
  Reducers are pure → JVM tests without Android `Context` (the
  test approach in §"Test approach" of the plan rests on this).
- Adding a new axis is **+1 file + 1 registry entry** (plan §4.0.6.3
  walkthrough). No central code is touched — OCP wins.
- The KClass-Lookup makes "which module handles this action?" an
  init-time-resolved fact; runtime cost is O(1) hash-lookup.
- The pattern aligns with the parallel cross-module-cascade rules
  in ADR-0002 (frozen snapshot, cascade depth) — the two ADRs are
  designed to compose.

**Negative:**

- Five files per axis instead of one (sub-state class + actions +
  reducer + effects + module-singleton). Trade-off: each file stays
  small, but the discoverability cost is real until a developer
  internalises the convention. The architecture-doc walkthroughs
  (`docs/architecture/state-architecture/adding-a-module.md` etc.)
  are the mitigation.
- Cross-module reads go through `ctx.global.x.y` (read-coupling) and
  cross-module writes go through Mode-2 cascades (see ADR-0002).
  A reducer that wants "change A and B atomically" can't —
  by design. Plan §7.1 documents Mode 3 as the Phase-2 escape
  hatch if a real use case arrives.
- Reflective `KClass.sealedSubclasses` is used at init. Release
  builds need a ProGuard-keep rule; the omission would silently
  strip the action hierarchy and make every dispatch return
  `DispatchOutcome.Unrouted` (Spec 1 §4.3 ProGuard block).

**Failure Modes:**

> Full descriptions, examples, alternatives, and rationale for each
> forbidden pattern live in the catalogue:
> [state-architecture/forbidden-patterns.md §3 — The 14 forbidden patterns](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
> The entries below are the ADR-0001 condensed summaries (problem +
> symptom + ADR-local mitigation cross-reference). Spec 1 §15.2 +
> forbidden-patterns.md §3 are the upstream SoTs.

- **(a) Direct `_state.value = …` outside the store** — Silently
  breaks single-dispatch ownership; no compile-time guard. Mitigation:
  the store's `update()` is the only public mutator API; `_state` is
  `private`. → see
  [forbidden-patterns.md §3 (a)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(b) Hardware/IO read in the reducer** — Makes tests
  non-deterministic and reintroduces the original race-condition
  class. Mitigation: hardware goes into `runEffect(effect, services)`,
  never into `reduce()` (Spec 1 §15.2 RecordingModule). → see
  [forbidden-patterns.md §3 (b)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(c) `else`-branch in `reduce`-`when` over sealed Actions** —
  Silently swallows new action variants on every compile-clean
  rebuild. Mitigation: expression-form `when` convention (Spec 1
  §4.2) forces exhaustivity. → see
  [forbidden-patterns.md §3 (c)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(e) `toMutableList()`-round-trip on `PersistentList`** —
  Destroys structural sharing → measurable performance regression.
  Mitigation: Spec 1 §3 "PersistentList-Mutations-Idiom" carries the
  diff convention. → see
  [forbidden-patterns.md §3 (e)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(h) Synchronous re-dispatch from an EffectHandler** —
  Re-entrant dispatch breaks the single-dispatch invariant. Mitigation:
  EffectHandlers post via `serviceScope.launch { orchestrator.dispatch(…) }`.
  → see
  [forbidden-patterns.md §3 (h)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(i) LocalBinder forwarder methods parallel to the Action hierarchy** —
  Bypass action routing, hide effect failures. Mitigation: the
  binder exposes `dispatch(action)` only; no per-action accessors.
  → see
  [forbidden-patterns.md §3 (i)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(m) `actionResolver` returning `Action.NoOp`** — Reintroduces
  the unreachable-routing log-spam that the `Action?`-resolver
  eliminated. Mitigation: `null` is the canonical no-op. → see
  [forbidden-patterns.md §3 (m)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **(n) Direct module-to-module call** — Breaks encapsulation; the
  compiler can't prevent it. Mitigation: code review + module-isolation
  pattern (modules only see `ModuleServices`, never each other). → see
  [forbidden-patterns.md §3 (n)](../architecture/state-architecture/forbidden-patterns.md#3-the-14-forbidden-patterns).
- **ProGuard strips `Action` hierarchy in release.** All dispatches
  become `Unrouted` — the entire IME is silently broken. Mitigation:
  the keep rule in `app/proguard-rules.pro` + the release smoke
  test `OrchestratorReleaseSmokeTest.kt` (Block 1b). (This is an
  ADR-local mitigation, not one of the 14 forbidden patterns.)

## References

- **Related Plan:** [dictate-keyboard-layout-refactor](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md) §4.0.1.1, §4.0.1.2, §4.0.1.6, §4.0.5
- **Related Spec:** [Spec 1 — Pipeline-Service](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md) §3, §4.1, §4.2, §4.3, §4.4, §4.8, §15
- **Related ADRs:**
  - **ADR-0002 — state-cross-module-cascade.** Defines the cross-module-effect modes (Mode 1, Mode 2; Mode 3 forbidden), self-cascade rule, `MAX_CASCADE_DEPTH`, and `EffectFailure`-routing — all of which sit on top of the single-dispatch + per-module-reducer foundation in this ADR.
  - **ADR-0003 — service-foreground-pipeline-architecture.** Hosts the orchestrator + module registry in a process-resident `DictatePipelineService` (Foreground Service). This ADR is layout-neutral but assumes a container that survives keyboard-switch.
  - **ADR-0004 — ui-layout-catalog-motionlayout.** Consumes the orchestrator's `StateFlow<DictateUiState>` via `RenderBackend.render(state, mode)`. The single-dispatch + null-resolver-no-op convention in this ADR §10 binds rendering surfaces.
  - **[ADR-0005 — UI Triangle-FSM (Keyboard/Widget/Hover)](0005-ui-triangle-fsm-keyboard-widget-hover.md)** — view-mode FSM that drives this orchestrator's view-mode-derived state.
- **Architecture docs:**
  - [state-architecture/state-and-actions.md](../architecture/state-architecture/state-and-actions.md)
  - [state-architecture/modules.md](../architecture/state-architecture/modules.md)
  - [state-architecture/forbidden-patterns.md](../architecture/state-architecture/forbidden-patterns.md)
  - [state-architecture/adding-a-module.md](../architecture/state-architecture/adding-a-module.md)
  - [state-architecture/adding-a-button.md](../architecture/state-architecture/adding-a-button.md)
- **Skill:** `~/.claude/skills/knowledge-adr-format/SKILL.md`

## Supersede Triggers (Forward-Looking Notes)

This ADR is one of the more stable of the five. Substantial
superseding would mean a different state-mutation paradigm
altogether (e.g. adopting an MVI library, or moving to a pure
EventSourcing model). Smaller revisions are expected to land as
append-only Decision-History entries:

- If `prefBindings()` becomes the single Pref-mirror source (plan
  §7.1 Phase-2 backlog), this ADR gets an addition under
  "Required mechanics" rather than a supersede.
- If the Phase-2 `InterruptionModule` stub becomes a full module
  with hardware integration, the inventory in this ADR gets a
  Decision-History entry.
- A supersede happens only if the pattern itself is replaced —
  e.g. moving from `sealed interface DictateModule` to a different
  plugin shape, or eliminating the orchestrator in favour of
  individual `StateFlow`s per module.

## Decision History

### 2026-05-14 — Accepted

**Trigger:** Block-0 audit-consolidation pass (B0-VAL-SANITY) — plan §4.0 binding-pre-code-contract closeout.

**Before:** Status: Proposed (per §4.0.1.0.3 lifecycle clause "Proposed during Block 0").

**After:** Status: Accepted (body now append-only per knowledge-adr-format §"Lifecycle and editing rules").

**Reasoning:** Block-0 acceptance criteria from plan §4.0.3 met; B0-AUDIT-PLAN-AND-API + B0-AUDIT-CONVENTION pass; ADR binds downstream Blocks 1b…6 per plan §4.0.4 "Bindender-Vertrag-Charakter".

### 2026-05-14 — Block-0 doc-set audit cleanup (B0-VAL-REPAIR)

**Trigger:** Validated findings F-9, F-10, F-11 from `reports/validated-findings-B0.md` (SSoT duplication + Phase-2-Superseding misplacement).

**Before:** The 13-module-inventory (F-9) was duplicated verbatim between this ADR §"Module inventory" and `modules.md §7.1`. The 14 forbidden-patterns Failure-Modes (F-10) were re-described paragraph-length inside §"Failure Modes" parallel to `forbidden-patterns.md §3`. The "Phase-2 Superseding Expectations" block lived inside `## Decision History` (F-11), conflicting with knowledge-adr-format §"Lifecycle and editing rules" (append-only after Accepted).

**After:** Module-inventory abridged to id + axis-name table + pointer to `modules.md §7.1` (the architecture-doc is now SoT). Forbidden-patterns Failure-Modes condensed to problem + symptom + cross-reference to `forbidden-patterns.md §3` per pattern. Phase-2-Superseding moved to a new top-level section `## Supersede Triggers (Forward-Looking Notes)` between `## References` and `## Decision History`.

**Reasoning:** SSoT-rule (knowledge-doc-format §"Anti-redundancy") demands one canonical home per topic — architecture-doc holds the long form, ADR holds the binding contract + pointer. Phase-2-Superseding is forward-looking content; the post-Accepted append-only invariant on Decision History blocks future revisions of those predictions.

### 2026-05-14 — Initial proposal

**Trigger:** Plan §4.0 mandates five ADRs as a binding pre-code contract
for Block 1b…6. This ADR captures the foundation (single-dispatch +
modular orchestrator + pure reducer + lens). The four sibling ADRs
build on it.

**Before:** No documented project-wide state-mutation contract. Five
distinct production bugs (plan §1.1) traced to distributed mutation.

**After:** Single mutation entry (`orchestrator.dispatch`), one owner
module per state axis (13 modules), pure reducers, lens-based
sub-state encapsulation. Mode-3 (atomic cross-axis update) explicitly
out-of-scope for Phase 1.

**Reasoning:** Spec 1 §4–§15 iterated F-1 → F-11 over a week of
plan-design review (2026-05-08 … 2026-05-13). The modular-orchestrator
landing was driven by the SRP/OCP failure of the earlier
`PipelineStateManager` design — that class accumulated five
responsibilities and would have grown linearly with new state axes.
The chosen pattern keeps the orchestrator at ~200 lines and pushes
domain logic into self-contained modules that can be added without
modifying the orchestrator (OCP).
