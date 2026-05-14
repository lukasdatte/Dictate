# ADR-0005: UI — Triangle-FSM (KEYBOARD / WIDGET / HOVER)

**Status:** Proposed
**Subsystem:** ui-mode
**Scope:** Project-Wide
**Date:** 2026-05-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001, ADR-0002, ADR-0003, ADR-0004.** This ADR
> defines the ViewMode FSM and its 7 transitions. ADR-0001 hosts the
> `ViewModeModule` that owns the `viewMode` axis; ADR-0002 carries the
> Mode-2 cascade machinery that T7 (HOVER → KEYBOARD on PipelineDone)
> depends on; ADR-0003 makes HOVER structurally possible (the
> pipeline-service outlives the IME); ADR-0004 renders the three modes
> via the appropriate backends.

## Research

The Triangle-FSM was synthesised from product requirements and from
the FSM-research in Spec 3:

- Plan §1.2 user-iteration requirements: WIDGET (user-toggle floating
  keyboard with InputConnection alive) + HOVER (auto-trigger floating
  notification UI when IME-View is gone but pipeline runs) + the
  differential close-behavior (WIDGET-close → KEYBOARD with SmallMode;
  HOVER-close → dismiss entirely).
- Plan §3.1 (Triangle-FSM diagram) — the canonical three-mode model
  with all six visible transitions.
- Spec 3 §7.1 (`computeViewMode` truth table), §7.3 (T1–T7
  code-snippets per transition), §11.9 (`userPrefersWidget`
  in-memory persistence rationale).
- Spec 3 §6.1 (Close-button differential behavior — WIDGET vs HOVER).
- Phase-B S-8 (2026-05-13) — found that the original FSM listed only
  T1–T6; T7 (HOVER → KEYBOARD on PipelineDone) was implicit (only
  visible via the cross-module-coupling matrix). The "Geist-Widget"
  bug class — overlay remaining visible after the pipeline has no
  reason to keep HOVER — was structurally undocumented. Phase-B S-8
  added T7 explicitly.
- Phase-B S-9 (2026-05-13) — found that Spec 3 §7.3 T1 and T2 used
  accidental Mode-3 cross-axis mutation in their snippets, while §6.1
  used the correct Mode-2 cascade form. The duplicate-truth was
  resolved by rewriting §7.3 onto the Mode-2 form.
- KG-RSB-2 RESOLUTION (Spec 1 §4.3, 2026-05-11) — the
  `Recording.Idle → Preparing → OverlayAction.ResetSuppressBit`
  self-cascade is part of the HOVER reopen flow. Without it, HOVER
  doesn't reopen after the first user-close.

## Context

The Triangle-FSM is the user-visible answer to two related questions:

1. **What does the user see during a recording-then-keyboard-switch
   flow?** Pre-refactor: nothing — the keyboard goes away, the
   recording continues in zombie mode, the user has no UI to send /
   cancel. Plan §1.2 demands a visible HOVER mode for this.
2. **Can the user toggle the keyboard into a floating widget?**
   Plan §1.2 demands WIDGET — a user-triggered floating
   variant with all the keyboard buttons plus a record-button (so
   the user can start new recordings even from WIDGET mode).

Both modes share the **same 5-button overlay layout**
(`OVERLAY_5BUTTON` in Spec 2 §3 / Spec 3 §3.1) — Record, Send, Pause,
Trash, Close. The only difference: in HOVER, Send is disabled (no
InputConnection); in WIDGET, all 5 are live. The shared layout was
explicitly chosen (OPEN-2 RESOLVED, plan §7).

The FSM needs to encode:

- HOVER is **automatic** — it appears when `imeViewVisible == false &&
  pipelineActive == true`. It is not user-triggerable.
- WIDGET is **user-triggered** — only via Widget-Toggle click;
  permission-gated.
- KEYBOARD is the default — both other modes degrade to KEYBOARD
  when their preconditions fall away (HOVER when pipeline is done;
  WIDGET when the user clicks close).
- `userPrefersWidget` is **transient (in-memory)** — a new pipeline
  session starts in KEYBOARD by default; the user must re-toggle
  WIDGET if they want it.

## Decision

ViewMode is a deterministically-computed function:

```kotlin
fun computeViewMode(
    imeViewVisible: Boolean,
    userPrefersWidget: Boolean,
    pipelineActive: Boolean,
): ViewMode = when {
    imeViewVisible && userPrefersWidget    -> ViewMode.WIDGET
    imeViewVisible && !userPrefersWidget   -> ViewMode.KEYBOARD
    !imeViewVisible && pipelineActive      -> ViewMode.HOVER
    else                                    -> ViewMode.KEYBOARD
}
```

Three values: `ViewMode.KEYBOARD`, `ViewMode.WIDGET`, `ViewMode.HOVER`.
Seven transitions T1–T7 (all implemented as Mode-2 cascades — see
ADR-0002):

| ID | Transition | Trigger | Cascade chain |
|----|------------|---------|---------------|
| **T1** | KEYBOARD → WIDGET | User clicks Widget-Toggle (permission-gated) | `Action.ViewModeAction.ToggleViewModeWidget` → `ViewModeModule.reduce` sets `viewMode = WIDGET`; `OverlayModule.onCrossModuleStateChange` cascades `Action.OverlayAction.SetUserPrefersWidget(true)` |
| **T2** | WIDGET → KEYBOARD | User clicks Close in WIDGET | `Action.ViewModeAction.ToggleViewModeWidget` → `ViewModeModule.reduce` sets `viewMode = KEYBOARD`; `LayoutModule.onCrossModuleStateChange` cascades `Action.LayoutAction.SetSmallMode(true)`; `OverlayModule.onCrossModuleStateChange` cascades `Action.OverlayAction.SetUserPrefersWidget(false)` |
| **T3** | KEYBOARD → HOVER | Android: `onFinishInputView` + pipeline active | `Action.ViewModeAction.OnImeViewHidden` → `ViewModeModule.reduce` re-evaluates `computeViewMode(false, false, true) = HOVER` |
| **T4** | WIDGET → HOVER | Android: `onFinishInputView` + pipeline active, `userPrefersWidget == true` | `Action.ViewModeAction.OnImeViewHidden` → `computeViewMode(false, true, true) = HOVER` (WIDGET requires both visible view AND user-preference; without visible view, HOVER is correct since InputConnection is dead) |
| **T5** | HOVER → KEYBOARD | Android: `onStartInputView`, no widget-preference | `Action.ViewModeAction.OnImeViewShown` → `computeViewMode(true, false, *) = KEYBOARD` |
| **T6** | HOVER → WIDGET | Android: `onStartInputView` + `userPrefersWidget == true` | `Action.ViewModeAction.OnImeViewShown` → `computeViewMode(true, true, *) = WIDGET` (persistence-bit greift) |
| **T7** | HOVER → KEYBOARD via Pipeline-Done | `PipelineModule.onCrossModuleStateChange` observes `PipelineUiState.Done` and cascades `Action.ViewModeAction.OnPipelineDone` | The "Geist-Widget" structural guard — without T7 the overlay stays visible after pipeline-done, despite the auto-trigger condition (`pipelineActive`) being false |

### Scope of this Convention

Project-wide for the **ViewMode axis** — `state.viewMode` lives in
`ViewModeModule` (Spec 1 §15.1 #4). The FSM rules in this ADR are
the binding contract for:

- Block 5 (Keyboard-Layout-Catalog) — the `KeyboardLayoutManager`
  switches `RenderBackend` based on `state.viewMode`.
- Block 6 (Floating-Overlay) — the `OverlayBackend` window
  lifecycle is keyed on `state.viewMode in [WIDGET, HOVER]`.

Out of scope:

- The Phase-2 "fourth ViewMode" (e.g. PIP / picture-in-picture) is
  explicitly a supersede trigger (plan §4.0.1.0.3 superseding
  expectations).
- The Phase-2 STANDALONE_OVERLAY service (plan §7.1) which would
  decouple HOVER from the IME's lifecycle. That changes the
  imeViewVisible truth table; supersede candidate.

### Required mechanics (binding contract for Block 5–6)

1. **`computeViewMode` is the SoT.** It lives in `ViewModeModule`
   (Spec 1 §15.1) — pure function, no side effects, no Android
   API. Reducers and tests call it.
2. **`userPrefersWidget` is transient.** Lives in
   `state.overlay.userPrefersWidget` (Spec 1 §3, Phase-1 1.0.6).
   Not persisted to DB or SharedPreferences. A new pipeline session
   starts WIDGET-off by default.
3. **WIDGET is permission-gated.** Before T1 fires, the click-listener
   checks `state.overlay.hasPermission`. If missing,
   `actionResolver` returns `Action.OverlayAction.RequestOverlayPermission`
   (separate flow, Spec 3 §5) instead of `ToggleViewModeWidget`.
   In `ViewModeModule.reduce`, if `ctx.global.overlay.hasPermission
   == false`, return `null` (the toggle is a silent no-op until
   permission is granted).
4. **Close-button differential.**
   - In WIDGET: `actionResolver` for `OVERLAY_CLOSE` returns
     `Action.ViewModeAction.ToggleViewModeWidget` → T2 fires.
   - In HOVER: `actionResolver` for `OVERLAY_CLOSE` returns
     `Action.ViewModeAction.CloseOverlay` → overlay dismisses;
     `userPrefersWidget` is reset to false; `suppressAutoOverlayUntilNextSession`
     is set so HOVER does not auto-reopen until the next
     pipeline session (Spec 3 §6.2).
5. **`Recording.Idle → Preparing` self-cascade resets the suppress-bit.**
   `RecordingModule.onCrossModuleStateChange` (Spec 1 §15.2)
   cascades `Action.OverlayAction.ResetSuppressBit` when
   recording transitions out of `Idle`. Without this cascade,
   HOVER never reopens after the first user-close in a session
   (KG-RSB-2 bug). The self-cascade-allowance (ADR-0002 §"Self-cascade
   is allowed") is the structural prerequisite.
6. **T7 is mandatory (Geist-Widget structural guard).**
   `PipelineModule.onCrossModuleStateChange` observes
   `prev.pipeline !is PipelineUiState.Done && next.pipeline is
   PipelineUiState.Done` and cascades
   `Action.ViewModeAction.OnPipelineDone`. The ViewModeModule
   reducer re-runs `computeViewMode(state != HOVER, *, false) = KEYBOARD`.
   Without T7, the overlay would remain visible despite
   `pipelineActive == false` — the "Geist-Widget" bug class.

### Two-input truth table

```
imeViewVisible | userPrefersWidget | pipelineActive | ViewMode
---------------|---------------------|----------------|----------
   true        |        true          |       *        | WIDGET
   true        |        false         |       *        | KEYBOARD
   false       |          *           |     true       | HOVER
   false       |          *           |     false      | KEYBOARD
```

`pipelineActive` is computed at the call site:
`ctx.global.pipeline !is PipelineUiState.Idle || ctx.global.recording.isActiveOrPaused`.

### Architecture-visible structure

```
                   ┌──────────────────────────────┐
                   │        KEYBOARD              │
                   │   (full keyboard, normal)    │
                   │                              │
                   │  - Two-Row / Single-Row      │
                   │  - Send-mode variants        │
                   │  - ReprocessStaging          │
                   │  - InputConnection alive     │
                   └──────────────────────────────┘
                       │   ▲                  ▲
                       │   │                  │
              T1: user │   │ T2: user         │  T5: IME view returns
              clicks   │   │ clicks Close     │      (no widget-pref)
              Widget-  │   │ in WIDGET        │
              Toggle   │   │ (→ SmallMode)    │
                       ▼   │                  │
                   ┌─────────────────────────┐  │
                   │       WIDGET            │  │
                   │   (user choice, float)  │  │
                   │                         │  │
                   │   - 5 buttons           │  │
                   │   - Send works          │  │
                   │   - InputConnection alv │  │
                   └─────────────────────────┘  │
                       │   ▲                    │
                       │   │  T6: IME view      │
              T4: view │   │  returns + widget- │
              hidden + │   │  pref persists     │
              pipeline │   │                    │
              active   │   │                    │
                       ▼   │                    │
                   ┌─────────────────────────┐  │
                   │      HOVER (auto)       │──┘
                   │                         │  T7 (also via Pipeline-Done
                   │   - 5 buttons (same     │      cascade, "Geist-Widget"
                   │     layout as WIDGET)   │      structural guard)
                   │   - Send DISABLED       │
                   │   - Close → dismiss     │
                   │   - InputConnection ∅   │
                   └─────────────────────────┘
                              ▲
                              │
                              │ T3: view hidden + pipeline active
                              │     (from KEYBOARD)
```

## Alternatives Considered

1. **Two modes (KEYBOARD + OVERLAY) — single floating mode for
   both auto + user-toggle.** Rejected because HOVER's Send button
   must be disabled (no InputConnection) while WIDGET's is alive.
   Modelling that as a "send-enabled flag" inside one OVERLAY mode
   pushes responsibility into every consumer. Three modes with
   shared layout (`OVERLAY_5BUTTON`) keep the user-facing layout
   identical while the FSM stays explicit.
2. **Four modes — split WIDGET into WIDGET_RECORDING / WIDGET_IDLE.**
   Rejected because WIDGET-Record is fully captured by the
   `RecordingState` axis. ViewMode is orthogonal to recording
   state; mixing the two would double the ViewMode cardinality
   for no information gain.
3. **WIDGET persists across pipeline sessions (DB-backed
   `userPrefersWidget`).** Considered. Rejected after iteration:
   plan §1.2 framed WIDGET as an opt-in for a specific use
   case (long dictation while doing something else on screen).
   Persisting across sessions would lock the user into WIDGET
   until they remember to toggle off. The in-memory bit
   resets per pipeline session — a natural cleanup boundary
   (Spec 3 §11.9 rationale).
4. **T7 implemented as `ViewModeModule.runEffect` (Mode 1).**
   Rejected because T7 is a state-change on the ViewMode axis,
   triggered by another axis (`pipeline`). Mode 1 fits hardware
   triggers on the owning module's axis; Mode 2 (Action-Cascade)
   is the correct form for cross-axis triggers. ADR-0002 mandates
   Mode 2 here.
5. **Send-button enabled-toggling in HOVER via a state flag
   (`overlay.sendEnabled`).** Rejected because the toggling rule
   is structural (HOVER means InputConnection ∅, period) and not
   driven by user input. The `OVERLAY_5BUTTON` LayoutCatalog
   entry's `OVERLAY_SEND` slot derives `enabledResolver` from
   `state.viewMode == ViewMode.WIDGET` directly — no extra
   state flag.

## Consequences

**Positive:**

- Three modes cover the entire user-visible UI space (keyboard
  view + floating overlay variants). No "what mode am I in?"
  ambiguity.
- HOVER's Geist-Widget bug is structurally guarded by T7. No
  conditional cleanup code in the render path.
- The shared `OVERLAY_5BUTTON` layout means the user sees
  identical visuals in WIDGET and HOVER — only the Send button's
  enabled state differs. UX consistency.
- `userPrefersWidget` transience is a deliberate UX choice that
  matches the intermittent nature of the use case.
- The FSM is computed (pure function) — every reducer is
  testable on the JVM without an Android emulator. The truth
  table can be exhaustively tested.

**Negative:**

- Two close-button behaviors (WIDGET → SmallMode KEYBOARD; HOVER →
  dismiss) live in the same shared `OVERLAY_CLOSE` slot, branched
  on `state.viewMode` in the `actionResolver`. Discoverability
  cost: a developer changing the Close behavior must understand
  the differential. Mitigation:
  `docs/architecture/state-architecture/triangle-fsm.md` carries
  the differential explicitly.
- The FSM has 7 transitions, of which T4 + T7 are subtle (auto-trigger
  with persistence-bit; cascade-only). Documentation cost is
  real.
- Permission-gating WIDGET means T1 can silently no-op (when
  permission is missing). The UI needs to surface that — the
  onboarding flow (Spec 3 §5) handles the user-facing prompt.

**Failure Modes:**

- **T7 not implemented — Geist-Widget bug.** Removing the T7
  cascade or accidentally observing the wrong pipeline state
  leaves HOVER visible after pipeline-done. The user sees a
  floating overlay with no purpose. Mitigation: the cross-module-
  coupling-matrix entry `Pipeline × ViewMode = R(state.pipeline)
  C(ViewModeAction.OnPipelineDone)` (Spec 1 §15.1.x) + the
  Block-6 acceptance test `pipeline_done_in_hover_cascades_to_keyboard`.
- **`Recording.Idle → Preparing` cascade not firing — HOVER won't
  reopen.** KG-RSB-2 bug class. If a future maintainer re-adds
  the self-filter to `dispatchInternal` (forbidden pattern (f),
  ADR-0001 §"Forbidden Patterns"), HOVER fails the second-time-
  open test. Mitigation: the ⚠-banner in Spec 1 §4.3 + the
  regression test `DictateOrchestratorTest.recordingModule_idleToPreparing_emits…`.
- **`userPrefersWidget` accidentally persisted to DB/Prefs.** A
  developer adds `userPrefersWidget` to `PipelinePrefMirror.sync()`
  thinking "this should be a Pref like the others". WIDGET
  becomes sticky across sessions; user complaints follow.
  Mitigation: the field is on `state.overlay` (not `state.layout`
  or `state.features`); the architecture-doc `triangle-fsm.md`
  has a sub-section "WHY transient".
- **Permission-loss while in WIDGET.** Edge case: user revokes
  overlay permission via Android settings while WIDGET is
  active. The `OverlayPermissionObserver` (Spec 3 §5.0) updates
  `state.overlay.hasPermission = false`; an `OverlayModule.onCrossModuleStateChange`
  cascade transitions to KEYBOARD. Spec 3 §11.6
  Window-Lifecycle-Edge-Cases covers this.
- **T1 silent-no-op without permission.** If WIDGET is toggled
  but permission is missing, the reducer returns `null` and
  nothing visible happens to the user. The Onboarding flow (Spec
  3 §5.3) surfaces the permission prompt at click time, so
  the no-op is masked by the onboarding-UI. A regression that
  removes the onboarding-UI would expose this. Mitigation:
  Block-6 acceptance manual test "click Widget-Toggle without
  permission → see onboarding dialog".

## References

- **Related Plan:** [dictate-keyboard-layout-refactor](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md) §1.2 (user-iteration requirements), §3.1 (Triangle-FSM diagram), §4.0.1.0 (ADR-5 decision-kernsatz), §7 OPEN-1, OPEN-2
- **Related Specs:**
  - [Spec 3 — Floating-Overlay](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md) §6 (Close-button differential), §7.1 (computeViewMode), §7.3 (T1–T7 code-snippets), §11.9 (userPrefersWidget transience rationale)
  - [Spec 1 — Pipeline-Service](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md) §15.1 (Module-Inventar #4 ViewModeModule), §15.1.x (Coupling-Matrix), §15.2 (RecordingModule self-cascade)
- **Related ADRs:**
  - **ADR-0001 — state-modular-orchestrator-pattern.** `ViewModeModule` lives in the registry and obeys the single-dispatch + pure-reducer contract from ADR-0001. The `computeViewMode` function is the reducer body.
  - **ADR-0002 — state-cross-module-cascade.** Every transition in this ADR (T1–T7) is implemented as a Mode-2 cascade. The cascade machinery (frozen snapshot, depth cap, self-cascade allowance, registry order) defined in ADR-0002 is a hard prerequisite.
  - **ADR-0003 — service-foreground-pipeline-architecture.** HOVER is structurally possible only because the pipeline-service outlives the IME-Service. Without ADR-0003's container, `imeViewVisible == false && pipelineActive == true` is unreachable (the pipeline dies with the IME).
  - **ADR-0004 — ui-layout-catalog-motionlayout.** Renders the three modes via `RenderBackend` switching: KEYBOARD → `ImeViewBackend + ContentAreaController`; WIDGET + HOVER → `OverlayBackend` with the shared `OVERLAY_5BUTTON` LayoutMode.
- **Architecture docs:**
  - [state-architecture/triangle-fsm.md](../architecture/state-architecture/triangle-fsm.md)
  - [state-architecture/cross-module-cascade.md](../architecture/state-architecture/cross-module-cascade.md)
  - [state-architecture/rendering.md](../architecture/state-architecture/rendering.md)
- **Skill:** `~/.claude/skills/knowledge-adr-format/SKILL.md`

## Decision History

### 2026-05-14 — Initial proposal

**Trigger:** Plan §3.1 + §4.0.1.0 define the Triangle-FSM as a binding
pre-code contract. Phase-B S-8 (2026-05-13) added T7 explicitly to
Spec 3 §7.3; Phase-B S-9 (same day) realigned T1+T2 from accidental
Mode-3 to correct Mode-2 cascade form. OPEN-1 + OPEN-2 user
decisions (2026-05-08) fixed close-button differential and 5-button
shared layout.

**Before:** No documented contract. The implementation in Spec 3 §7.3
showed two different cascade forms across §6.1 and §7.3 (duplicate
truth). T7 was implicit (visible only through the coupling matrix).

**After:** Three modes (KEYBOARD / WIDGET / HOVER), seven transitions
T1–T7 (all Mode-2 cascades), `computeViewMode` as the deterministic
function, `userPrefersWidget` transient. T7 mandatory as the
Geist-Widget structural guard. Close-button behavior branches on
ViewMode at the `actionResolver`-level.

**Reasoning:** Three modes was the smallest cardinality that
distinguishes "user-toggled floating + Send live" from "auto-floating
+ Send dead" without forcing a per-feature flag inside one mode.
The shared `OVERLAY_5BUTTON` LayoutMode (OPEN-2 resolution) lets us
keep WIDGET/HOVER visually consistent. T7 is structural — it
eliminates a UX-failure class by construction rather than via
imperative cleanup code.

### Phase-2 Superseding Expectations

This ADR has the highest superseding-probability of the five:

- **Fourth ViewMode (PIP / picture-in-picture).** Likely Phase-2
  candidate. The supersede would add a fourth `ViewMode` value
  and extend the truth table. Existing T1–T7 stay valid; new
  transitions land as T8+.
- **Different close behavior in HOVER** (e.g. "minimise to small
  pill" instead of "dismiss entirely"). Supersede revises T2
  / T7 + the `OVERLAY_CLOSE` resolver. Likely if user feedback
  during Phase-1 testing surfaces a discoverability issue.
- **STANDALONE_OVERLAY service** (plan §7.1). Decouples HOVER
  from the IME-Service lifecycle. The truth table changes:
  `imeViewVisible` is no longer the only "IME-View dead" signal.
  Supersede revises `computeViewMode` and the T3 / T4 / T5 / T6
  triggers.

Smaller revisions (adding a transition between existing modes,
e.g. WIDGET → HOVER via long-press) land as Decision-History
entries with a new T-ID. The truth table form is stable.
