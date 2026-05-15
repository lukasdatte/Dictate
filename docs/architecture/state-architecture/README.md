---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: Entry point for the Dictate state architecture — links the ADR contracts to the implementer-facing teaching material, walkthroughs, and forbidden-pattern catalogue.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005
---

# Dictate State Architecture

This directory is the **teaching material** for the modular-orchestrator
state architecture introduced in the keyboard-layout-refactor. It is
**complementary** to the ADRs under `docs/decisions/0001..0005`:

- **ADRs** are the **contract** — short, binding, append-only after
  Acceptance. Reverse only via supersede.
- **This directory** is the **lesson** — long, explanatory, with
  ASCII diagrams, code shapes, walkthroughs. Free to edit as the
  code evolves.

If an ADR says **what** and this directory says **how**, they
co-evolve. If they ever contradict, the ADR wins — file an issue
to fix this directory.

## Who reads what

| Reader role | First read | Then |
|---|---|---|
| Implementer adding a new button | [`adding-a-button.md`](adding-a-button.md) | (only one if the rest is already known) |
| Implementer adding a new module/state-axis | [`adding-a-module.md`](adding-a-module.md) | [`state-and-actions.md`](state-and-actions.md), [`cross-module-cascade.md`](cross-module-cascade.md) |
| Implementer adding a new sub-keyboard | [`adding-a-sub-keyboard.md`](adding-a-sub-keyboard.md) | [`rendering.md`](rendering.md), [`wiring-ui.md`](wiring-ui.md) |
| Reviewer auditing a PR | [`forbidden-patterns.md`](forbidden-patterns.md) | the relevant topic page below |
| New maintainer onboarding | this README | [`state-and-actions.md`](state-and-actions.md), then top-down |
| Plan author / architect | ADRs first, then this README | the topic page that matches the scope |

## Topic pages

| File | Topic | Owner ADR |
|---|---|---|
| [`state-and-actions.md`](state-and-actions.md) | `DictateUiState` shape (13 axes + 1 top-level flag), Action sealed hierarchy, `dispatch`-loop, `DispatchOutcome`, ReducerContext | ADR-0001 §"Required mechanics" |
| [`modules.md`](modules.md) | `DictateModule<S, A, E>` interface, lens pattern, `ModuleId`-enum, `DictateModuleRegistry.all`, init-time sanity check, `ModuleServices`-DI | ADR-0001 §"Module inventory" |
| [`effects-and-failures.md`](effects-and-failures.md) | `SideEffect` per module, `runEffect`/`emitAction`, `EffectFailure`-routing via `originModuleId`, `reduceFailure`-hook, `effect.toString()` convention | ADR-0001 §"Required mechanics" + ADR-0002 §"EffectFailure routing" |
| [`cross-module-cascade.md`](cross-module-cascade.md) | Mode 1, Mode 2, forbidden Mode 3, self-cascade, frozen snapshot, `MAX_CASCADE_DEPTH`, coupling-matrix notation, self-read convention | ADR-0002 (whole) |
| [`rendering.md`](rendering.md) | `RenderBackend` interface, multi-backend pattern, `LayoutCatalog`, `LayoutMode` + `sceneStateId`, `LogicalButtonId`, MotionScene + `motion:visibilityMode="ignore"`, `firstRender`-flag, shared `applySlotToView`-helper, `computeLayoutMode` truth table | ADR-0004 (whole) |
| [`wiring-ui.md`](wiring-ui.md) | Click-Listener once-wiring, `stateRef`/`modeRef` backend fields, nullable resolver idiom, special-touch handlers, long-click per button, memory-leak structural protection, click→reducer→state-emit→render flow | ADR-0001 §"UI-Wiring boundary" + ADR-0004 §"Required mechanics" |
| [`triangle-fsm.md`](triangle-fsm.md) | KEYBOARD / WIDGET / HOVER, `computeViewMode` truth table, T1–T7, `userPrefersWidget` transience, Geist-Widget structural guard (T7), permission gate | ADR-0005 (whole) |
| [`adding-a-module.md`](adding-a-module.md) | Walkthrough: how to add a new module (BatterySaverModule example) — 8 steps from sub-state to registry wiring | ADR-0001 §"Module inventory" |
| [`adding-a-button.md`](adding-a-button.md) | Walkthrough: how to add a button (INSERT_COMMA example) — 7 steps from `LogicalButtonId` to test | ADR-0004 §"LogicalButtonId catalogue" |
| [`adding-a-sub-keyboard.md`](adding-a-sub-keyboard.md) | Walkthrough: two variants (A: new ContentArea, B: new RenderBackend window) | ADR-0004 §"Required mechanics" |
| [`forbidden-patterns.md`](forbidden-patterns.md) | The 14 hard-forbidden patterns (a–n) with example, rationale, correct alternative | ADRs 0001 + 0002 + 0004 (forbidden-pattern subset per §"Failure Modes") |

## Note on naming — DictateOrchestrator vs PipelineOrchestrator

The state architecture introduces `DictateOrchestrator` — the
**state-action-router** that owns the registry-driven dispatch loop
(`Action → reducer → state-write → effects → cascade`). It is unrelated
to the **legacy** `net.devemperor.dictate.core.PipelineOrchestrator`,
which is the **audio-pipeline runner** (transcription/completion +
DAO writes). The two co-exist during the Block 2 → Block 3 migration
window; B3 absorbs the legacy `PipelineOrchestrator` into the new
architecture as a `PipelineRunnerSubsystem` adapter behind the modular
orchestrator (Spec 1 §11.2.2). Keep the distinction in mind when
reading stack traces, KDoc references, and PR diffs.

## High-level architecture in 60 seconds

```
┌─────────────────────────────────────────────────────────────┐
│         DictatePipelineService  (Foreground Service)        │
│         — survives keyboard switch — ADR-0003               │
│                                                             │
│   DictateOrchestrator  (Composition Root, single dispatch)  │
│     dispatch(action) → module-registry routing — ADR-0001   │
│                                                             │
│   13 Modules — one owner per state axis — ADR-0001          │
│     RecordingModule, PipelineModule, AudioModule,           │
│     ViewModeModule (ADR-0005), OverlayModule, ResendModule, │
│     LivePromptModule, LanguageModule, LayoutModule,         │
│     FeatureToggleModule, ThemingModule, PendingSessionsModule, │
│     KeyboardInputModule                                     │
│                                                             │
│   Cross-module coordination via Mode-1 SideEffect / Mode-2  │
│     Action-Cascade — ADR-0002                               │
└─────────────────────────────────────────────────────────────┘
                          ▲
                          │ LocalBinder (in-process)
                          │  state: StateFlow<DictateUiState>
                          │  dispatch(action): DispatchOutcome
                          ▼
┌─────────────────────────────────────────────────────────────┐
│         DictateInputMethodService  (IME-Service)            │
│         — comes and goes per keyboard selection             │
│                                                             │
│   KeyboardLayoutManager (Triangle-FSM render orchestrator)  │
│     state.collect { backends.forEach { render(state) } }    │
│                                                             │
│   RenderBackends — ADR-0004                                 │
│     ImeViewBackend (KEYBOARD), ContentAreaController,       │
│     OverlayBackend (WIDGET + HOVER)                         │
│                                                             │
│   LayoutCatalog (declarative slot/predicate/resolver)       │
│   MotionScene XML for positions                             │
└─────────────────────────────────────────────────────────────┘
```

## Plan → topic-page map

Where each part of the parent plan §4.0 lives in this directory:

| Plan section | Topic page |
|---|---|
| §4.0.1.1 Reducer rules | [`state-and-actions.md`](state-and-actions.md) |
| §4.0.1.2 Action rules | [`state-and-actions.md`](state-and-actions.md) |
| §4.0.1.3 Effect rules | [`effects-and-failures.md`](effects-and-failures.md) |
| §4.0.1.4 Cross-module rules | [`cross-module-cascade.md`](cross-module-cascade.md) |
| §4.0.1.5 Forbidden patterns | [`forbidden-patterns.md`](forbidden-patterns.md) |
| §4.0.1.6 Who holds state (diagram) | [`state-and-actions.md`](state-and-actions.md), [`modules.md`](modules.md) |
| §4.0.1.7 Who holds UI-Wiring | [`wiring-ui.md`](wiring-ui.md) |
| §4.0.5 Module isolation (lens, channels, registry) | [`modules.md`](modules.md), [`cross-module-cascade.md`](cross-module-cascade.md) |
| §4.0.6.1 Walkthrough — new button | [`adding-a-button.md`](adding-a-button.md) |
| §4.0.6.2 Walkthrough — new sub-keyboard | [`adding-a-sub-keyboard.md`](adding-a-sub-keyboard.md) |
| §4.0.6.3 Walkthrough — new module | [`adding-a-module.md`](adding-a-module.md) |
| Spec 3 §7 Triangle-FSM (T1–T7) | [`triangle-fsm.md`](triangle-fsm.md) |

## Walkthrough Decision Tree (Plan §4.0.6.4)

Four questions, in order. Each leads to one or more topic pages in this directory.

### 1. Where does the new feature live?

- **A new button on an existing layout?** → see [`adding-a-button.md`](adding-a-button.md).
- **A new sub-keyboard variant?** → see [`adding-a-sub-keyboard.md`](adding-a-sub-keyboard.md) (Variant A: declarative-only; Variant B: with module).
- **A new module owning its own state axis?** → see [`adding-a-module.md`](adding-a-module.md).

### 2. What does the feature touch?

- **State?** Action shape + reducer entry → see [`state-and-actions.md`](state-and-actions.md) + [`modules.md`](modules.md).
- **Effects?** Effect declaration + failure routing → see [`effects-and-failures.md`](effects-and-failures.md).
- **UI rendering?** LayoutCatalog entry + MotionLayout transition → see [`rendering.md`](rendering.md) + [`wiring-ui.md`](wiring-ui.md).
- **Cross-module behaviour?** Mode 1 or Mode 2 cascade — Mode 3 is forbidden → see [`cross-module-cascade.md`](cross-module-cascade.md).

### 3. What needs testing?

Four test types: reducer-purity, effect-runner, cascade-flow, UI-integration. Each walkthrough's `## Testing` section (or the `## Common mistakes` section, where testing is folded in) lists the relevant types.

### 4. Where do inline anchors go?

Three anchor types per the inline-anchor convention (`~/.claude/snippets/engineering-principles.md` → `knowledge-doc-format` skill §"Inline anchors"): module-header `@see <ADR>`, gotcha comments, plan-section pointers. The checklist for a new module is in [`adding-a-module.md`](adding-a-module.md) (Module-design checklist section).

## Properties this Architecture Guarantees

Read the ADRs for the contract; this list summarises the **invariants
the implementation must preserve**:

1. **Single-Owner-per-Sub-State.** Each of the 13 state axes has
   exactly one module that writes it. Cross-axis writes from a
   reducer are a forbidden pattern. (ADR-0001)
2. **Pure-Reducer.** Reducers are deterministic, hardware-free,
   non-threading. JVM-testable without Android `Context`. (ADR-0001)
3. **Single-Dispatch.** `orchestrator.dispatch(action: Action)` is
   the only mutation entry. No parallel LocalBinder forwarder
   methods. (ADR-0001)
4. **Frozen-Cascade-Snapshot.** Every cascade-observer sees the
   same `(prev, next)` tuple in a given dispatch pass. (ADR-0002)
5. **Self-Cascade Allowed.** A module observing its own axis is
   valid; the self-filter that broke HOVER-reopen was removed
   (KG-RSB-2). The `MAX_CASCADE_DEPTH = 8` is the only loop guard.
   (ADR-0002)
6. **Mode 3 Forbidden.** No atomic cross-axis mutation in Phase 1.
   Mode 1 (own SideEffect) + Mode 2 (Action-Cascade) cover all
   identified flows. (ADR-0002)
7. **Foreground Service Survives Keyboard-Switch.** Pipeline state
   lives in `DictatePipelineService` (FGS type=microphone), bound
   via LocalBinder in the same process. (ADR-0003)
8. **No WorkManager.** Recovery is DB-replay + manual User-Resume.
   (ADR-0003)
9. **Declarative Rendering.** `LayoutCatalog` (predicates / resolvers)
   + MotionScene XML. No imperative re-parenting. No per-slot
   visibility mutators. (ADR-0004)
10. **Click-Listener Once-Wiring.** Lambdas wired in `attach()`,
    reference `stateRef`/`modeRef` backend fields. No per-render
    rewiring. (ADR-0004)
11. **`motion:visibilityMode="ignore"`.** Mandatory on every
    state-driven button. MotionScene transitions don't fight the
    per-slot `visibilityPredicate`. (ADR-0004)
12. **Triangle-FSM is Computed.** `computeViewMode(imeViewVisible,
    userPrefersWidget, pipelineActive) -> ViewMode` is pure. Seven
    transitions T1–T7, all implemented as Mode-2 cascades.
    (ADR-0005)
13. **`userPrefersWidget` Transient.** In-memory only. Each new
    pipeline session starts WIDGET-off. (ADR-0005)
14. **T7 Mandatory.** Pipeline-Done → re-evaluate ViewMode →
    HOVER becomes KEYBOARD. Structural guard against the
    Geist-Widget bug class. (ADR-0005)

## References

- [Parent plan](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md)
- [Spec 1 — Pipeline-Service](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 2 — Keyboard-Layout](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 3 — Floating-Overlay](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
- [ADR Index](../../decisions/README.md)
- **UDOC convention skill:** `~/.claude/skills/knowledge-doc-format/SKILL.md`
