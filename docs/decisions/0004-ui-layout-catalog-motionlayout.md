# ADR-0004: UI — LayoutCatalog + MotionLayout

**Status:** Proposed
**Subsystem:** ui-rendering
**Scope:** Project-Wide
**Date:** 2026-05-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001.** ADR-0001 owns the state-mutation
> pipeline; this ADR owns the rendering side. The
> `RenderBackend` interface consumes `DictateUiState` via `state.collect`,
> reads slot resolvers, and applies properties to Android views — no
> mutation, no state writes. The single-dispatch boundary is preserved
> across both ADRs.

## Research

The decision rests on three research artefacts referenced from plan §1.3:

- `research/main-button-area-inventory.md` — Capability inventory of the
  9 main buttons, the 4 state axes, and the visibility matrix. Made
  the per-slot predicate / resolver model concrete.
- `research/motionlayout-architecture-options.md` — Evaluation of 5+
  layout-switching patterns: imperative ConstraintSet rewriting,
  programmatic ConstraintSet animations, ViewStub-based mode swap,
  MotionScene-XML with `VISIBILITY_MODE_IGNORE`, and Compose. The
  recommendation: **MotionLayout + flat MotionScene** with
  `motion:visibilityMode="ignore"` on every state-driven button.
- `research/_pending-layout-container-architecture/_pending-layout-container-architecture.md`
  — Confirmed the MotionLayout recommendation with concrete
  modifications and two spike validations (transition timing,
  inflation cost).
- Spec 2 §3 (`LogicalButtonId`, `ButtonSlot`, `RowDescriptor`,
  `LayoutMode`), Spec 2 §4 (`KeyboardLayoutManager` API),
  Spec 2 §5 (`RenderBackend` interface), Spec 2 §5.1 (`SlotRenderer.applySlotToView`
  F-7 helper), Spec 2 §6 (`ImeViewBackend` implementation),
  Spec 2 §7 (MotionScene XML), Spec 2 §8.6 (`LayoutCatalog.forKeyboard`),
  Spec 2 §11.6 (Click-Listener-Lifecycle / memory-leak analysis).
- Plan §1.1 bugs #1 and #2 (asymmetric re-parenting): root cause was
  programmatic re-parent across modes. MotionLayout eliminates the
  bug class **structurally** — there is no re-parent, just a
  transition between ConstraintSets.

## Context

Before this refactor, the IME main-button-area used **imperative
ConstraintSet rewriting + view re-parenting** per layout mode:

```kotlin
// Pre-refactor (KeyboardLayoutModeController.kt:60-74, 183-191):
// In single-row mode, several buttons were re-parented into input_row.
// In two-row mode, they were re-parented back to action_row.
// originalParents-Map was the band-aid for the bug class "asymmetric
// re-parenting" (plan §1.1 bugs #1, #2, #3a).
```

The visibility was a separate concern: each button's `View.visibility`
was set imperatively, often by multiple code paths
(`MainButtonsController`, `RecordingUiController`,
`DictateInputMethodService` directly). `resend_btn` had 5 distinct
visibility mutators (Spec 1 §13.1 audit). The state machine was
implicit: button state was derived from the visibility set by whichever
controller ran last.

We needed a rendering model that:

1. Eliminates re-parenting (no more `originalParents`-Map; no more
   asymmetric-revert bugs).
2. Centralises the per-button decision (predicate + icon + text +
   enabled + action) in **one place**.
3. Allows multiple **render surfaces** (IME-View, content area
   container, overlay window) to share the same decision logic.
4. Keeps click-listener wiring on a once-per-attach lifecycle so
   we don't leak lambdas on every render tick.

## Decision

UI rendering is **declarative**: a `LayoutCatalog` holds
`LayoutMode` instances, each with a list of `ButtonSlot`s carrying
**predicates and resolvers**. A `RenderBackend` iterates the slots
and applies properties via a shared `SlotRenderer.applySlotToView`
helper. The KEYBOARD render path uses **MotionScene XML** as the
positional source-of-truth; the OVERLAY path uses static XML. Multiple
render backends run in parallel (`ImeViewBackend` + `ContentAreaController`
+ `OverlayBackend`) — list-based, not single-backend.
Click-listeners are wired **once in `attach()`**; their lambdas
reference backend fields (`stateRef` / `modeRef`), not the per-render
arguments. `motion:visibilityMode="ignore"` is mandatory on every
state-driven button in the MotionScene XML.

### Scope of this Convention

Project-wide for the **rendering surface** — the IME-View MotionLayout,
the content-area containers, and the overlay window. The convention
applies to every implementation of `RenderBackend`. Out of scope:

- Pre-refactor surfaces that are not state-driven (e.g. emoji-picker
  internal grid). Those keep their own rendering.
- The Settings activities and onboarding flows (they have their own
  static layouts, no `LayoutCatalog`-derived rendering).
- Compose-based screens (none currently; if added, they consume
  `state.collect` directly rather than via `RenderBackend`).

### Required mechanics (binding contract for Block 5)

1. **`LayoutCatalog` carries `LayoutMode`-objects.** Each mode has
   an `id: LayoutModeId`, a `backend: BackendType`, a
   `sceneStateId: Int?` (MotionScene `@id/` reference for keyboard
   modes), and a `rows: List<RowDescriptor>` with `ButtonSlot`s.
2. **`ButtonSlot` is the per-button declaration.** Fields:
   `logicalId: LogicalButtonId`, `widthPolicy`, `visibilityPredicate:
   (DictateUiState) -> Boolean`, `iconResolver`, `textResolver`,
   `enabledResolver`, `actionResolver: (DictateUiState, ModuleServices) -> Action?`.
   A `null` return from `actionResolver` is a silent no-op (ADR-0001
   §"UI-Wiring boundary"). `Action.NoOp` is forbidden (forbidden
   pattern (m)).
3. **`RenderBackend` interface — three methods:** `attach(onAction)`,
   `detach()`, `render(state, mode)`. `attach()` wires static
   handlers once; `render()` is called per state change.
4. **Multi-backend parallel rendering.** `KeyboardLayoutManager`
   holds a **list** of active backends, not a single one. The
   KEYBOARD path attaches `ImeViewBackend` + `ContentAreaController`
   simultaneously (R.10). All backends receive every render tick
   and decide per-mode whether they're active.
5. **`SlotRenderer.applySlotToView` is the single Slot→View mapper
   (F-7 DRY).** Top-level function shared by `ImeViewBackend` and
   `OverlayBackend`. A new slot property (e.g. `contentDescription`)
   gets added once here and benefits both backends.
6. **MotionScene XML is the positional SoT.** `res/xml/motion_scene_keyboard.xml`
   carries 5 keyboard `ConstraintSet`s
   (two_row, single_row, two_row_send, single_row_send,
   reprocess_staging). Buttons are direct children of the
   `MotionLayout` root — there is **no nested `action_row` /
   `input_row` container** (the "flat hierarchy" L2 fix).
   `motion:visibilityMode="ignore"` is present on every state-driven
   button.
7. **Click-Listener once-wiring (L8).** `wireStaticHandlers()` runs
   in `attach()`. Listener-lambdas reference `stateRef` /
   `modeRef` backend fields (single source per backend lifetime).
   Per-render-tick rewiring is forbidden pattern (l) — memory-leak
   vector measured in Spec 2 §11.6.
8. **`firstRender` flag (R.14).** On the first `render()` call
   after `attach()`, the backend uses
   `motionLayout.jumpToState(sceneId)` to avoid a 250ms
   animation from the initial state. Subsequent renders use
   `transitionToState`. `detach()` resets the flag.
9. **Nullable resolver idiom (R.3).** `slot.actionResolver(s,
   services)?.let { onAction?.invoke(it) }` — `null` returns
   are silently dropped before reaching the orchestrator. This
   structurally prevents `DispatchOutcome.Unrouted` log-spam for
   "wrong-state" clicks.
10. **State-driven buttons NEVER call `view.visibility` directly
    from click-listeners.** Visibility + enabled are managed in the
    `render` loop via `visibilityPredicate` + `enabledResolver`.

### Backend stack

```
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 1 — KeyboardLayoutManager                          (top)     │
│  Type:   class                                                      │
│  File:   app/src/main/java/net/devemperor/dictate/                  │
│            keyboard/KeyboardLayoutManager.kt                        │
│  Form:   onStateChanged(state) → activeBackends.forEach { render }  │
└─────────────────────────────────────────────────────────────────────┘
                                ↓ dispatches state to every backend
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 2 — RenderBackend (interface)                                │
│  Type:   interface RenderBackend                                    │
│  File:   app/src/main/java/net/devemperor/dictate/                  │
│            keyboard/render/RenderBackend.kt                         │
│  Form:   attach(onAction) / detach() / render(state, mode)          │
└─────────────────────────────────────────────────────────────────────┘
              ↓                ↓                       ↓
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────────┐
│ ImeViewBackend      │  │ ContentAreaController│ │ OverlayBackend          │
│ (KEYBOARD)          │  │ (Container vis.)     │ │ (WIDGET + HOVER)        │
│                     │  │                      │ │                         │
│ Iterates LayoutMode │  │ Iterates contentArea │ │ Iterates OVERLAY_5BTN   │
│ slots; uses         │  │ enum, sets visibility│ │ slots via shared        │
│ MotionLayout.       │  │ on three containers. │ │ applySlotToView.        │
│ jumpToState /       │  │                      │ │                         │
│ transitionToState.  │  │                      │ │                         │
└─────────────────────┘  └─────────────────────┘  └─────────────────────────┘
                                ↓
                       ┌─────────────────────────────────────┐
                       │  Shared helper                       │
                       │  SlotRenderer.applySlotToView        │
                       │  (top-level function, F-7 / DRY)     │
                       │  visibility + enabled + icon + text  │
                       │  + alpha — single Slot→View mapper.  │
                       └─────────────────────────────────────┘
```

### LogicalButtonId catalogue (Spec 2 §3.1)

Authoritative list:

```kotlin
enum class LogicalButtonId {
    // KEYBOARD render surface
    RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE,
    TRASH, SPACE, PAUSE, ENTER,
    // OVERLAY render surface
    OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE,
}
```

A button-view-map at the backend (`buttonViews: Map<LogicalButtonId,
View>`) materialises each `LogicalButtonId` to a concrete `View` via
`findViewById`. A missing entry is a hard `error(...)` at first
render (Spec 2 §6 silent-skip-protection L8 / Issue 3.0.12). Adding
a new button is the §"adding-a-button" walkthrough (`docs/architecture/state-architecture/adding-a-button.md`).

## Alternatives Considered

1. **Keep imperative ConstraintSet rewriting + re-parenting.**
   The status quo. Rejected because plan §1.1 bugs #1, #2, #3a
   traced directly to re-parenting asymmetry. The "originalParents-Map"
   work-around (KeyboardLayoutModeController.kt:60-74) was the
   symptom: every new mode meant another map update or another bug.
2. **Jetpack Compose for the keyboard view.** Considered.
   Rejected because (a) the IME-View hierarchy interacts with
   MotionLayout's animation primitives in ways Compose does not
   yet replicate (PulseLayout-animation, `MotionScene` transitions);
   (b) the migration cost is enormous; (c) the rest of the app
   stays View-based. Phase-3-eval candidate, not Phase-1.
3. **Programmatic `ConstraintSet`-builder (no MotionScene XML).**
   `motionlayout-architecture-options.md` evaluation Option 4.
   Rejected because the constraint-set construction code would
   replicate XML in Kotlin without compile-time validation. The
   MotionScene XML is the cleanest declarative representation;
   `deriveConstraintsFrom` keeps duplication low across the 5
   keyboard states.
4. **One-backend-at-a-time (no multi-backend list).** Rejected
   because the content-area visibility (`MAIN_BUTTONS` / `QWERTZ` /
   `EMOJI_PICKER`) is conceptually orthogonal to the main-buttons
   layout. Modelling content-area as a per-slot
   `visibilityPredicate` would force every main-button slot to
   include a `state.layout.contentArea == ContentArea.MAIN_BUTTONS`
   gate — duplicate, error-prone. A second `RenderBackend`
   (`ContentAreaController`) is the SRP-clean shape (R.10).
5. **`Action.NoOp` for inert clicks.** Rejected because every
   `NoOp` would reach the orchestrator and log
   `DispatchOutcome.Unrouted` or `Rejected` — log-spam for
   normal user behaviour (clicking a button in cooldown).
   `actionResolver: (state) -> Action?` returning `null` filters
   structurally at the click-listener (forbidden pattern (m) +
   R.3).
6. **Per-render-tick click-listener rewiring.** The naive
   implementation. Rejected because each `view.setOnClickListener
   { … }` call allocates a fresh lambda — at 60 Hz during recording
   that's 60 lambda allocations per button per second, plus the
   per-tick `removeOnClickListener` work. Spec 2 §11.6 measured
   the cost and L8 (once-wiring with `stateRef`) is the
   alternative.

## Consequences

**Positive:**

- Plan §1.1 bug class "asymmetric re-parenting" eliminated
  structurally. MotionLayout transitions don't re-parent — they
  just change `ConstraintSet`s. The "originalParents"-Map work-around
  is deleted (Spec 2 §9.1).
- `resend_btn` visibility consolidated from 5 mutators to 1
  `visibilityPredicate` in the slot (Spec 1 §13.1 audit's
  primary win).
- Adding a button is a +1 enum entry + 1 view-map entry + 1
  slot in the catalog + an optional action + an optional
  reducer arm (`docs/architecture/state-architecture/adding-a-button.md`).
  Multi-file but each diff is small and obvious.
- The shared `applySlotToView` helper means a new slot property
  (e.g. `contentDescription`) lands in one place and benefits
  every backend. F-7 / DRY.
- Click-Listener once-wiring eliminates a per-render allocation
  cost (lambda-per-button-per-render-tick). Spec 2 §11.6 makes
  the memory-leak math explicit.
- `motion:visibilityMode="ignore"` lets MotionScene transitions
  animate position without overriding the per-slot
  `visibilityPredicate`. The two layers don't fight.

**Negative:**

- MotionLayout has surprising edge cases. Spec 2 §11.3
  (PulseLayout spike) is the named risk. Mitigation: spike
  validation at the start of Block 5 (Spec 2 §11). Fallback to
  programmatic constraint-sets if PulseLayout breaks.
- `LayoutCatalog`-driven rendering puts a lot of logic in
  resolver lambdas. Reading a 200-line `LayoutCatalog.kt` is
  initially less direct than reading a 20-line
  `MainButtonsController.applyTwoRow()`. Mitigation: each
  resolver is a single line; cognitive complexity scales with
  the number of slots, not with the number of modes.
- The multi-backend list means every render tick fans out to
  every active backend. With three backends, that's 3× the work
  per tick. Trade-off: declarative consistency vs raw
  performance. Spec 2 §11.4 measures inflation cost and finds
  it acceptable.

**Failure Modes:**

- **Re-parenting in a slot's `actionResolver`** (forbidden
  pattern (d)). A developer tempted to "fix" a layout edge case
  by `view.parent.removeView(view); container.addView(view)`
  reintroduces the original bug class. Code-review only; no
  compile-time guard. Mitigation:
  `docs/architecture/state-architecture/forbidden-patterns.md`
  entry (d) with the bug history.
- **Missing `motion:visibilityMode="ignore"`** on a state-driven
  button (forbidden pattern (k)). MotionScene then animates
  the button's visibility from VISIBLE → GONE during a transition,
  overlaying the per-slot `visibilityPredicate`. Visual jump
  on transition. Mitigation: the `applySlotToView` documentation
  + a Block-5 acceptance manual test (Spec 2 §10).
- **`pred*Visible` containing cooldown logic** (forbidden pattern
  (j)). The `resend_btn` visibility predicate must NOT depend on
  `state.resend.resendCooldown` (that's an `enabledResolver`
  concern). Mixing the two reintroduces plan §1.1 bug #3b. Spec
  2 §8.5 + `forbidden-patterns.md` entry (j).
- **Click-Listener per render** (forbidden pattern (l)). Lambda
  leaks. Spec 2 §11.6 documents the memory math. Mitigation:
  `wireStaticHandlers()` is the only listener-attachment site;
  Block-5 acceptance includes an Espresso assertion that
  `setOnClickListener` runs once per `attach()`.
- **`actionResolver` returning `Action.NoOp`** (forbidden pattern
  (m)). Falls through to `dispatch(NoOp)` and logs
  `Unrouted`. The `Action.NoOp` symbol does not exist in the
  refactor (R.3); compile-error guards us.

## References

- **Related Plan:** [dictate-keyboard-layout-refactor](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md) §3.3, §4.0.1.7 (UI-Wiring), §4.0.1.5 (forbidden patterns (d, j, k, l, m)), §4.0.6.1 (adding-a-button walkthrough), §4.0.6.2 (adding-a-sub-keyboard walkthrough)
- **Related Spec:** [Spec 2 — Keyboard-Layout](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md) §3 (data model), §4 + §4.1 (KeyboardLayoutManager + multi-backend), §5 (RenderBackend interface), §5.1 (SlotRenderer), §6 (ImeViewBackend), §7 (MotionScene XML), §8 (LayoutCatalog), §11.4 (inflation cost), §11.6 (click-listener lifecycle)
- **Related Phase-2 Research:** `motionlayout-architecture-options.md`, `_pending-layout-container-architecture/_pending-layout-container-architecture.md`, `main-button-area-inventory.md`
- **Related ADRs:**
  - **ADR-0001 — state-modular-orchestrator-pattern.** This ADR consumes the orchestrator's `StateFlow<DictateUiState>` and emits `Action`s via `onAction(action)`. The single-dispatch boundary + nullable-resolver idiom + once-wiring directly enforce ADR-0001's UI-Wiring §10 rules.
  - **ADR-0005 — ui-triangle-fsm-keyboard-widget-hover.** This ADR implements ADR-0005's Triangle-FSM via render-backend switching: `KEYBOARD → ImeViewBackend + ContentAreaController`, `WIDGET → OverlayBackend (5-button)`, `HOVER → OverlayBackend (5-button with Send disabled)`. The shared `OVERLAY_5BUTTON` LayoutMode is the structural foundation for ADR-0005's mode-merge.
- **Architecture docs:**
  - [state-architecture/rendering.md](../architecture/state-architecture/rendering.md)
  - [state-architecture/wiring-ui.md](../architecture/state-architecture/wiring-ui.md)
  - [state-architecture/adding-a-button.md](../architecture/state-architecture/adding-a-button.md)
  - [state-architecture/adding-a-sub-keyboard.md](../architecture/state-architecture/adding-a-sub-keyboard.md)
- **Skill:** `~/.claude/skills/knowledge-adr-format/SKILL.md`

## Decision History

### 2026-05-14 — Initial proposal

**Trigger:** Plan §4.0.1.0 and §4.0.1.7 mandate this ADR as a binding
pre-code contract for Block 5 (Keyboard-Layout-Catalog) and Block 6
(Floating-Overlay). The research artefacts named above had
converged on MotionLayout + LayoutCatalog by 2026-05-08; the
architectural iteration F-7 (shared SlotRenderer) and R.10
(multi-backend) landed during Spec-2 review.

**Before:** Imperative ConstraintSet rewriting + view re-parenting.
Visibility set across 5+ controllers, no single owner per button.
The "originalParents"-Map was the bug-class symptom.

**After:** Declarative `LayoutCatalog` with predicates / resolvers
per slot. MotionScene XML for keyboard modes; static XML for
overlay. Multi-backend rendering. Shared `applySlotToView` helper.
Click-listener once-wiring. `motion:visibilityMode="ignore"`
mandatory.

**Reasoning:** Plan §1.1 bugs #1, #2, #3a, #3b are the dominant
signal — they cluster in one mechanism (mode-switch). Eliminating
the mechanism (no more re-parent, no more 5 mutators per
button) is more durable than fixing each bug individually.
MotionLayout + the slot-resolver model is the smallest set of
abstractions that gives us declarative rendering with reasonable
implementation cost (no Compose migration).

### Phase-2 Superseding Expectations

This ADR is one of the most stable (along with ADR-0001).
A supersede would mean a significant render-stack change:

- **Compose adoption.** If the wider app moves to Compose,
  the keyboard view follows. The supersede creates a new
  ADR (e.g. ADR-NNNN-ui-compose-keyboard) and this one is
  marked Superseded.
- **Per-mode XML inflation** (no MotionLayout). Counter-decision
  if MotionLayout proves to have unfixable edge cases in some
  Android version. The PulseLayout-spike (Spec 2 §11.3) is the
  early-warning signal.

Small revisions land as Decision-History additions: a new
`LogicalButtonId`, a new slot property (`contentDescription`,
`tint`), a new `BackendType` value. These are append-only and
do not require a supersede.
