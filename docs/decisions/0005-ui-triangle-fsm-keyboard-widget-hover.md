# ADR-0005: UI — Triangle-FSM (KEYBOARD / WIDGET / HOVER)

**Status:** Superseded by [ADR-0008](0008-ui-surface-axes-widget-state-and-ime-view.md) (2026-05-21)
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

Three view modes (`KEYBOARD`, `WIDGET`, `HOVER`) and seven transitions (T1–T7):
- **T1:** `KEYBOARD → WIDGET` — user clicks Widget-Toggle.
- **T2:** `WIDGET → KEYBOARD` — user clicks Close in WIDGET (→ SmallMode).
- **T3:** `KEYBOARD → HOVER` — IME view hidden + pipeline active.
- **T4:** `WIDGET → HOVER` — IME view hidden + pipeline active (was WIDGET).
- **T5:** `HOVER → KEYBOARD` — view returns, no widget-pref.
- **T6:** `HOVER → WIDGET` — view returns + widget-pref persists.
- **T7:** `HOVER → KEYBOARD` — via Pipeline-Done cascade ("Geist-Widget" structural guard).

> Full state-diagram + truth-table + per-transition example lives in
> the teaching doc: see
> [state-architecture/triangle-fsm.md §3 "The three modes"](../architecture/state-architecture/triangle-fsm.md#3-the-three-modes)
> and [§5 "The seven transitions T1–T7"](../architecture/state-architecture/triangle-fsm.md#5-the-seven-transitions-t1t7)
> for the canonical ASCII state-diagram (the ADR holds the binding
> contract; the architecture-doc holds the SoT diagram).

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

- **Related Plans:**
  - [dictate-keyboard-layout-refactor](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md) §1.2 (user-iteration requirements), §3.1 (Triangle-FSM diagram), §4.0.1.0 (ADR-0005 decision-kernsatz), §7 OPEN-1, OPEN-2 — the plan that motivated this ADR.
  - [dictate-cutover-completion](../plans/2026-05-15%20-%20dictate-cutover-completion/dictate-cutover-completion.md) — the Epic that flipped the IME recording-trigger to dispatch and completed the render-path cutover (4 legacy controllers deleted, `RenderBackend` sole driver; see Decision History 2026-05-17). §8 of that plan references this ADR (bidirectional).
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

## Supersede Triggers (Forward-Looking Notes)

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

## Decision History

### 2026-05-21 — Bidirectional render-sync: viewMode no longer gates the render-fan-out

**Trigger:** On-device verification (Samsung SM-S948B, Android 16) of the dictate-widget-integration + dictate-pipeline-render-and-state-unification plans showed that while the widget is open and the keyboard is visibly still on-screen, the keyboard surface is "frozen": pipeline-label updates do not appear on the keyboard's `record_btn`, click resolvers on keyboard buttons read a stale state snapshot, and the pause/SEND buttons on the keyboard tile do nothing (or do something based on stale state). Symmetric inverse exists for keyboard-only renders to an open overlay backend. The user's UX expectation is that both surfaces are simultaneously visible AND simultaneously live: clicking a button on either surface dispatches the same orchestrator action; both surfaces re-render the resulting state.

**Before:** `KeyboardLayoutManager.renderTo` selected a single `mode = computeLayoutMode(state)` per render-tick, then filtered each attached backend with `if (backend.backendType == null || backend.backendType == mode.backend)`. Since `computeLayoutMode` returns `forKeyboard(state)` (→ `BackendType.IME_VIEW`) for `ViewMode.KEYBOARD` and `OVERLAY_5BUTTON` (→ `BackendType.OVERLAY_WINDOW`) for `ViewMode.{WIDGET, HOVER}`, the filter excluded the IME backend whenever the widget was up. `ImeViewBackend.render()` never ran → side-channel renderers (`PipelineStepRowRenderer`, `AutoEnterRenderer`, `RecordButtonColorController`, `RecordingAnimationController`) had no `onState` call → `stateRef` (read by keyboard-side click listeners) stayed frozen on the last KEYBOARD-mode snapshot. This was the structural implementation of the "KEYBOARD ↔ WIDGET mutually exclusive" assumption baked into the 2026-05-14 accepted form of this ADR.

**After:** `KeyboardLayoutManager.renderTo` picks the mode **per backend** via a new `modeForBackend(backend, state)` helper:

```kotlin
when (backend.backendType) {
    BackendType.IME_VIEW       -> catalog.forKeyboard(state)
    BackendType.OVERLAY_WINDOW -> catalog.OVERLAY_5BUTTON
    null                       -> computeLayoutMode(state)
}
```

Every attached backend now renders on every state-emit. `state.viewMode` retains its role as a discriminator for click-conditioning (HOVER-SEND-block, overlay-action-resolvers etc.) and for the cross-cutting `ContentAreaController` (`backendType == null` consumes `computeLayoutMode`), but it no longer chooses *which* surface participates in a given render tick. `computeLayoutMode` stays as a public "informational" selector for legacy and external callers, but the render-fan-out no longer routes through it.

**Reasoning:** The "mutually exclusive" KEYBOARD-vs-WIDGET premise was a UX heuristic the original ADR adopted because no concrete product-driven counter-requirement existed at the time. The 2026-05-21 device session made the counter-requirement explicit: with the Triangle-FSM giving the user a WIDGET surface that *floats over* the keyboard (not replaces it), the keyboard surface must keep tracking live state — otherwise the user faces a half-frozen UI where buttons either silently do nothing (stale `actionResolver(state) == null`) or do the wrong thing (stale Active when state is Idle, etc.). The fix removes a single artificial gate; the wider state-flow architecture (Single SoT + per-backend renderer-bundles + Single-Writer-per-Axis) is already designed for this — every renderer-class is parameterised on its View instance, so two backends with their own bundles co-exist without writer collision.

The Triangle-FSM remains semantically valid: KEYBOARD/WIDGET/HOVER still describe distinct *user-intent* states, and they still drive cross-module concerns (overlay-permission gating, HOVER-Send-block, overlay-position persistence). What changes is only that the render-fan-out no longer reads `viewMode` as a participation gate. The seven transitions T1–T7 are unchanged.

**Reference:** `app/src/main/java/net/devemperor/dictate/state/layout/KeyboardLayoutManager.kt` (modeForBackend + renderTo); `app/src/test/java/net/devemperor/dictate/state/layout/KeyboardLayoutManagerTest.kt` (four new regression-locks: IME backend rendered in WIDGET/HOVER, overlay backend rendered in KEYBOARD, all-backends-on-every-emit fan-out).

### 2026-05-21 — A3 disposition flip: extract-and-preserve → extract-and-re-architect (Plan dictate-render-cutover-completion-vol2)

**Trigger:** Post-Epic device verification on Samsung SM-S948B uncovered two visible regressions (AE-↵ icon never paints; Row 1 ↔ Row 2 spacing collapses in Idle / Send mode) plus a partially-absent rote text-color on `Running.hasFailure`. Five hotfix-iterations per symptom (documented in commit `e7d4b2e`) each fixed the catalog path correctly while the legacy 100 ms-tick renderer kept overwriting the same view a frame later. Three audit agents (`research/d-audit-r1.md`, `research/d-audit-ae.md`, `research/d-legacy-map.md`) traced the root cause to the still-active A3-option-a "extract-and-preserve" disposition: the 4 legacy controllers were deleted (AC-RR-7 grep-zero) but their `BLEIBT`-halves were relocated into `PipelineStepRowRenderer` / `QwertzRecordingController` carrying a parallel `core.PipelineUiState` sealed class with its own 100 ms ElapsedTimer + direct `record_btn` writer — a dual-writer with the Catalog/`SlotRenderer` that the Epic intentionally kept (option-a is preserve-behaviour by definition).

**Before:** A3-option-a "Extract-and-Preserve" — `PipelineStepRowRenderer` owns a `core.PipelineUiState` (Idle / Preparing / Running / ReprocessStaging) sealed class + 100 ms ElapsedTimer + imperative mutator API (`preparePipeline`, `startPipeline`, `addRunningStep`, `completeStep`, `failStep`, `stopPipeline`, `toggleAutoEnter`, `enterReprocessStaging`, …) + direct writes to `record_btn.text`/`setTextColor`/`setCompoundDrawablesRelativeWithIntrinsicBounds`. The Catalog/`SlotRenderer` writes the same `record_btn.text`/`isEnabled`/`alpha` from `state.PipelineUiState`. Two hand-coded bridges in `DictateInputMethodService.java` (`toggleAutoEnterOverride` dual-dispatch and `onStepStarted_dispatchOrchestratorSync`) synchronise the two state mirrors. `record_btn.text` has two simultaneous writers per state-emit; the 100 ms tick wins. The Row 2 chain (`space_btn` / `pause_btn` / `enter_btn`) anchors Top **and** Bottom on `trash_btn`, which the catalog hides in Idle / Send mode — ConstraintLayout collapses the anchor to 0 × 0 and re-centres the wrap_content siblings around the collapsed line.

**After:** A3-option-c "Extract-and-Re-Architect" — `PipelineStepRowRenderer` becomes a **pure reactive consumer** of `state.PipelineUiState`. The `core.PipelineUiState` sealed class is deleted; the imperative mutator API is deleted; the two hand-coded bridges are deleted. Step-row inflate is driven by diffing `state.PipelineUiState.Running.stepHistory: PersistentList<StepRowItem>` against the inflated row views (new state migration: `stepHistory` + `StepStatus` + `StepRowItem` + `Running.hasFailure: Boolean`). `record_btn` is owned by three side-channel renderers analog to `RecordingAnimationController`: `AutoEnterRenderer` (left + right compound drawables, including the dynamic AutoEnter ↵ `BitmapDrawable`), `RecordButtonColorController` (`setTextColor` — red on `Running.hasFailure`, white elsewhere), and the Catalog/`SlotRenderer` (text / enabled / alpha). The Row 2 chain is re-anchored on `record_pulse_layout` (Row 1's always-visible PulseLayout) — no GONE-anchor-collapse possible. A new `PipelineUiStateObserver` Java-friendly StateFlow collector (`core/PipelineUiStateObserver.kt`) absorbs the legacy `PipelineUiCallback` non-renderer responsibilities (`syncQueueOrder`, `refreshLanguageChip`, QWERTZ `enterPipelineDisplay` / `updatePipelineTimer`).

The `StepFailed` reducer-arm semantics is clarified into two arms (Q6 decision): the `Preparing → StepFailed` arm preserves the legacy `→ Idle + DismissNotification` (upload failure before any step row), the `Running → StepFailed` arm sets `Running(hasFailure = true)` + marks the last step row `FAILED` **without** dispatching `DismissNotification` — the pipeline keeps running because `executeQueuedPrompts` continues with the next queued prompt. Only `PipelineDone` / `PipelineFailed` / `CancelPipeline` actually end the pipeline; the red colour persists until then.

**Reasoning:** Five hotfix iterations per symptom (R1 spacing, AE ↵ icon) attacked the wrong renderer — each iteration cleaned up the Catalog path while Legacy kept the last word. The pattern "every future feature that touches `record_btn` re-encounters the same dual-writer race" is structurally unsolvable under A3-option-a. The engineering-baseline §1 "Prefer the most sustainable solution" mandates the re-architecture — five visible symptoms in one Epic-Closure cycle is the third recurrence of the INT-1 parallel-dormant anti-pattern (this time at the per-axis writer level inside one component, not across components — but the same class). The Single-Writer-per-Axis invariant becomes the load-bearing rule: for every UI property of `record_btn` (text / icon-left / icon-right / enabled / alpha / setTextColor) exactly one code path is allowed to write it; the dual-writer disease is structurally eliminated, not papered over. The Q3 SoT decision (no double-bookkeeping `currentStepName` field — derive as extension property from `stepHistory.lastOrNull { RUNNING }?.stepName`) is the rule applied to state too.

The fix is locked structurally by `CutoverArchitectureInvariantTest` (existing) — the legacy `core.PipelineUiState` sealed class is gone (`grep -r 'core\.PipelineUiState' app/src/main/` returns zero non-comment hits); the renderer's imperative mutator API is gone (compile-time check); the three side-channels are wired in `ImeViewBackend.render` in deterministic order (Catalog → AutoEnterRenderer → RecordButtonColorController → PipelineStepRowRenderer → RecordingAnimationController). 6 commits land the migration: Phase 1 (additive Catalog helpers + 29 snapshot tests), Phase 2 (transitional bridge + helpers), Phase 3 (atomic flip: refresh stops + AutoEnterRenderer), Phase 4 (Row 2 Constraint-Chain decoupling), Phase 5.A (state migrations: hasFailure + stepHistory + RecordButtonColorController), Phase 5.B (renderer-to-consumer flip + delete), Phase 6 (legacy-file delete + this entry).

**Reference:** `docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md` (the full plan + §7 Q1-Q6 architecture decisions); `app/src/main/java/net/devemperor/dictate/state/render/AutoEnterRenderer.kt` + `RecordButtonColorController.kt` + the reduced `PipelineStepRowRenderer.kt`; `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt` (Q6 two-arm StepFailed reducer); `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` (Running.hasFailure + Running.stepHistory + StepStatus + StepRowItem + currentStepName extension); `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateObserver.kt` (StateFlow collector that replaces PipelineUiCallback); `app/src/main/res/xml/motion_scene_keyboard.xml` (Row 2 anchor on `record_pulse_layout`).

### 2026-05-20 — Catalog-click affordance-hook symmetry (post-cutover R2/R3/R4 hotfix)

**Trigger:** Post-cutover device verification on Samsung SM-S948B uncovered three coupled regressions the auto-tier suite (1180/0/0) missed: (R2) RECORD click on Active|Paused — endless "Sending…" with no transcription; (R3) the pipeline progress UI never appears; (R4) the active-prompt step-row stays empty. Read-only triage traced all three to a single missing wiring: the catalog-driven RECORD click reached `Action.RecordingAction.StopRecordingAndSend` → `PipelineModule.SubmitPipeline` → `PipelineRunnerSubsystemAdapter.submit` → `ImePipelineConfigResolver.resolveFresh` with an **empty** `freshSnapshots` map, fired the loud `UnsupportedOperationException` R-1 silent-data-loss tripwire, the orchestrator's `EffectFailure` arm swallowed it, and `state.pipeline` froze in `Preparing` forever (R3 + R4 are then downstream of R2 — the `PromptVisibilityController` truth-table requires `Running` to show the progress view; `PipelineStepRowRenderer.addRunningStep` requires `JobExecutor.PipelineCallback.onStepStarted` callbacks that never come because `JobExecutor.start` never sees a `JobRequest`).

**Before:** Theme-C-R / CR4 wired the `imeSideAffordance` hook for **RESEND click** (legacy `onResendClicked` DB-lookup → insert/resume — `ImeViewBackend.wireStaticHandlers` click branch at L375-377; the IME-side affordance is the only carrier because the catalog `ResendLastAudio` → `ResendModule` only arms the cooldown). For **RECORD long-press** the hook was wired (Idle→Settings+picker / Active→autoSwitch — `ImeViewBackend` long-press branch at L408-415 + `imeSideAffordance` lambda `RECORD && isLongPress` branch). But for **RECORD click** the hook was missing on the click branch — an **asymmetric gate**. The auto-tier suite (`DictateCutoverE2ETest.ac3_t7_stopAndSend_…`) covers the orchestrator side end-to-end but **pre-arranges** `resolver.snapshotFresh(...)` before the dispatch (test:362-380), structurally hiding the bug; the unit tests for `ImeViewBackend` cover RESEND-click + RECORD-long-press affordance firings but not RECORD-click.

**After:** The affordance gate is now symmetric across the catalog-driven {RESEND, RECORD} click set:

1. `ImeViewBackend.kt` click branch fires `imeSideAffordance(id, false)` for **both** `LogicalButtonId.RESEND` and `LogicalButtonId.RECORD` (no behaviour change for RESEND; RECORD click now triggers the IME-side hook).
2. `DictateInputMethodService.imeSideAffordance` lambda gains a `RECORD && !isLongPress` branch that invokes a new helper `prepareCatalogStopRecordingIfActive()`. The helper is self-gating: it bails on non-Active|Paused, on missing binder / sessionId / audioFile (mirroring `stopRecording()`'s pre-dispatch guards), and otherwise runs the R-1 `captureFreshConfigSnapshot` + `primePipelineUiForNewPath` + `newPathRecordingSessionId=null` work that the legacy `stopRecording()` performs.
3. The catalog still owns the dispatch (`onAction.invoke(StopRecordingAndSend)`); the affordance fires **before** the dispatch so the snapshot is in `freshSnapshots[sessionId]` by the time the orchestrator's async `SubmitPipeline` runs `resolveFresh`. The legacy `stopRecording()` keeps doing snapshot + prime + dispatch in one shot for the QWERTZ `onSend` path (which bypasses the catalog).

The fix is locked structurally by `CutoverArchitectureInvariantTest`:

- `(e) catalogStopRecordingAffordanceHelperIsWired` — asserts the `prepareCatalogStopRecordingIfActive()` helper exists exactly once **and** is invoked at least once (helper-is-dead-code would otherwise be silent), with the paired `commentStripperIsSound_catalogStopRecordingHelper` self-test proving non-vacuity.
- `(f) imeViewBackendClickBranchFiresAffordanceForBothResendAndRecord` — asserts the click-branch gate around `imeSideAffordance(id, false)` names **both** `LogicalButtonId.RESEND` **and** `LogicalButtonId.RECORD` within a 200-char window (refactor-tolerant — `||`, `in setOf`, etc. all match), with the paired `commentStripperIsSound_affordanceHookSymmetry` self-test.

Behaviourally locked by a new `ImeViewBackendTest` test (`RECORD click fires the IME-side affordance — post-cutover R2 hotfix — catalog symmetry to RESEND`), sibling to the existing `RESEND click fires the IME-side affordance` test.

R1 (Row-1 ↔ Row-2 vertical overlap on devices with gesture-nav) is orthogonal and was fixed in the same hotfix wave: the legacy `LinearLayout-vertical` stacking gave Row 1 ↔ Row 2 an implicit 16dp gap via `input_row.layout_marginBottom`, which C13/C15's MotionLayout-flattening dropped without replacement. `motion_scene_keyboard.xml two_row_state` now carries `motion:layout_marginTop="16dp"` on `trash_btn` (Row-2-start); `activity_dictate_keyboard_view.xml` gives `main_buttons_cl` `paddingBottom="16dp"` to restore the bottom safe-area. Derived states (`*_send_mode_state`, `reprocess_staging_state`) inherit via `deriveConstraintsFrom`; `single_row_state` collapses Row 2 onto Row 1 and overrides `trash_btn` entirely (no effect).

**Reasoning:** This is a **symmetry rule**, not a new architectural axis — the catalog-driven {RESEND, RECORD} click set was already supposed to be symmetric (both have a non-trivial IME-side legacy effect that has no orchestrator-side representation; both belong in the `imeSideAffordance` hook). The Epic's "anti-pattern caught 3× and spec-faithfully resolved" narrative missed this fourth instance precisely because it manifested at the **micro** level (a single asymmetric `if` gate) rather than the macro level the integration check searched for. The architecture-invariant test locks the symmetry class so the next refactor that touches the click-listener gate cannot silently re-open it.

The general rule going forward: **a catalog-driven click on `LogicalButtonId.{RESEND, RECORD}` that triggers an action whose effect requires IME-runtime imperative state (R-1 silent-data-loss class — JobRequest field snapshots, pipeline-step-row prime, DB-side last-session lookup) MUST fire a symmetric `imeSideAffordance(id, false)` hook before the catalog dispatch.** The affordance is self-gating on the relevant state predicate (RESEND: `!inCooldown`; RECORD: `Active|Paused`); the hook is the only seam where the IME — which owns the runtime state the orchestrator cannot see — can keep the dispatched effect non-silent.

**Reference:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt` (click branch — the symmetric `RESEND || RECORD` gate); `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (`imeSideAffordance` lambda + `prepareCatalogStopRecordingIfActive()` helper); `app/src/test/java/net/devemperor/dictate/core/CutoverArchitectureInvariantTest.kt` (e) + (f) + stripper-soundness self-tests; `app/src/test/java/net/devemperor/dictate/state/render/ImeViewBackendTest.kt` "RECORD click fires the IME-side affordance" test; `app/src/main/res/xml/motion_scene_keyboard.xml` (Row-1 ↔ Row-2 margin) + `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (bottom safe-area padding).

### 2026-05-17 — IME recording-trigger flipped to dispatch; render-path cutover (Epic dictate-cutover-completion)

**Trigger:** Epic `dictate-cutover-completion` Theme-B (IME recording-trigger flip) + Theme-C-R (the render-path cutover). The 2026-05-15 entry pinned the IME-activation *view* contract; this entry records the *recording-trigger* flip and the legacy render-controller retirement that were still outstanding (the INT-1 parallel-dormant anti-pattern at the render layer). Code-verified in `reports/integration-check.md` Central Verdict §2/§3.

**Before:** The IME-activation view contract (`OnImeViewShown/Hidden`) was pinned (2026-05-15 entry), but recording was still triggered via the legacy `JobExecutor.INSTANCE.start` call-sites, and the legacy render controllers (`MainButtonsController` / `RecordingUiController` / `KeyboardUiController` / `KeyboardStateManager`) were the live render path, attached **in parallel** to the `RenderBackend` (`ImeViewBackend` etc.). The render side was nominally on `RenderBackend` but the legacy controllers were still the production drivers.

**After:** The IME recording-trigger dispatches `RecordingAction.StartRecording` / `StopRecordingAndSend` instead of `JobExecutor.start` (one documented RESUME carve-out — `startResumeJob` — survives and is regression-locked by `CutoverArchitectureInvariantTest`). The render-path cutover (Theme-C-R, gated on a GREEN CR-RGATE) deletes the 4 legacy render controllers; `RenderBackend` is the **sole render driver** (`doubleWriteCount == 0`). The ~16 controller behaviour-groups were ported to `RenderBackend` owners — `ImeViewBackend`, `SpecialTouchHandlerInstaller`, `ContentAreaController`, `PromptVisibilityController`, `OverlayResetHandler`, the new `EditBarController` / `EmojiController` / `OverlayCharactersController`, `QwertzRecordingController`, `PipelineStepRowRenderer` (the per-group→owner map is the SoT table in `research/render-path-cutover.md` §3 / §11). The safe-cutover mechanic is the staged build-but-dormant → `RenderGate`-armed → atomic per-axis flip → delete pattern (CR1–CR3 attach owners dormant behind a `RenderGate`; CR4 flips them live per-axis atomically; CR-DEL deletes the legacy controllers only after the RR-3 per-class responsibility-trace is GREEN). The RR-2 visibility-double-write risk is guarded by `core/audit/VisibilityWriteAuditLogger` (strict-mode double-write detection). `OnRecordLongPress` becomes a 2-mode model (Idle → Settings+file-picker / Active → autoSwitch+stop) via a `ButtonSlot.longClickResolver` (Spec 2 §13.2 / render-path-cutover.md §7 A1).

**Reasoning:** Triangle-FSM's render side was nominally on `RenderBackend` while the legacy controllers were still live — the third recurrence of the INT-1 parallel-dormant anti-pattern (render layer). The flip + deletion completes ADR-0005's intended end-state (the FSM drives `RenderBackend` switching per ADR-0004) and ADR-0001's single-dispatch on the recording axis; it is an append, not a supersede (the three-mode FSM, the seven transitions T1–T7, and `computeViewMode` are unchanged). The staged RenderGate mechanic is the safe-cutover answer to RR-1 (silent listener overwrite) / RR-2 (blank-UI premature drive-removal): the new owners build dormant and the legacy drive is removed only in the same chunk the new path takes over, gated and per-class-traced — never both wired at once.

**Reference:** `docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md` (the per-group→owner SoT — §3 + §11; not duplicated here); `docs/plans/2026-05-15 - dictate-cutover-completion/reports/B5-theme-cr-render-cutover.md`; `docs/plans/2026-05-15 - dictate-cutover-completion/reports/integration-check.md` Central Verdict §2/§3; Spec 2 §9.x / §13.x.

### 2026-05-15 — IME-activation contract pinned (B5 repair-wave, F-1/F-2/F-3)

**Trigger:** B5-VAL-SANITY validated-findings F-1 (IME never dispatches
`OnImeViewShown/Hidden`), F-2 (permission-onboarding unreachable), F-3
(`observer.refresh()` not wired). The Triangle-FSM, OverlayModule,
ViewModeModule arms, and the attach/detach collapse were implemented +
unit-green but inert in production because
`DictateInputMethodService.java` was never wired to drive them.

**Before:** ADR-0005 §"Required mechanics" + the Decision T-table
*named* `onFinishInputView`/`onStartInputView` as the T3/T4/T5/T6
triggers, but no production code produced `OnImeViewShown/Hidden`; the
WIDGET-toggle `actionResolver` was permission-blind; the onboarding
info-bar + Settings-launch path was undelivered (C17→C18 forward-drop);
`observer.refresh()` had no IME call site.

**After:** The IME-activation contract is now production-binding and
explicit: `onStartInputView` dispatches `OnImeViewShown` + calls
`overlayPermissionObserver.refresh()` (refresh BEFORE the dispatch so
the FSM sees fresh `hasPermission`); `onFinishInputView` dispatches
`OnImeViewHidden` on **all** paths — the legacy 3-state early-returns
were refactored to a single tail so the recording-active /
pipeline-running branches (the *primary* HOVER use-case) also fire it.
The WIDGET-toggle `actionResolver` is permission-aware
(`resolveWidgetToggleAction`: `hasPermission ? ToggleViewModeWidget :
ShowOverlayOnboarding`); a new `Action.OverlayAction.ShowOverlayOnboarding`
arm sets `onboardingPending`; a Spec 3 §5.4 auto-cleanup cascade arm
clears it on the (prev≠WIDGET)→WIDGET edge. The in-IME info-bar is
owned by the IME service (a documented deviation from the Spec 3 §5.3
sketch which put it in `ImeViewBackend` — justified by the B4
`ImeViewBackend` button-map-only contract; the IME already owns the
symmetric `InfoBarController` surface). The IME observes
`state.overlay.onboardingPending` via a dedicated
`OverlayOnboardingObserver` bridge (the IME is a Java
`InputMethodService`, not a `LifecycleOwner`, and the production render
path is service-side — the bridge gives the IME the single sub-axis it
needs without widening `ImeViewBackend`). The Grant button launches
`ACTION_MANAGE_OVERLAY_PERMISSION` with `FLAG_ACTIVITY_NEW_TASK`
(mandatory from a non-Activity Context).
`Effect.OpenOverlayPermissionSettings` remains a structural placeholder
(launch owned by the IME-side handler per Spec 3 §5.2); a new
`Effect.NotifyOverlayPermissionRequired` + `NotificationStatus.OverlayPermissionRequired`
implements the Spec 3 §9 O7 permission-free notification fallback,
emitted by the runtime-permission-loss cascade.

**Reasoning:** The hook choice was not a free decision — ADR-0005's own
T-table, Spec 1 §11 (lines 2672-2676), and the architecture-doc
`triangle-fsm.md` §5 independently dictate
`onStartInputView`/`onFinishInputView`. The trigger-arm
(`ShowOverlayOnboarding`) resolves Spec 3 §5.4's explicitly-open
"Auslöser TBD" toward a dedicated single-purpose action (SRP) over
overloading `RequestOverlayPermission` (the explainer bar must appear
*before* the user decides to context-switch to Settings). The
`restarting` flag is deliberately ignored at the `OnImeViewShown`
dispatch — the reducer is idempotent (no-ops when `computeViewMode ==
current`), and suppressing on `restarting=true` would break T6
(rotation while in HOVER must recompute when the view returns). This
entry pins the contract so a future reader knows the FSM's *production*
trigger surface, not just the reducer arms.

### 2026-05-14 — Accepted

**Trigger:** Block-0 audit-consolidation pass (B0-VAL-SANITY) — plan §4.0 binding-pre-code-contract closeout.

**Before:** Status: Proposed (per §4.0.1.0.3 lifecycle clause "Proposed during Block 0").

**After:** Status: Accepted (body now append-only per knowledge-adr-format §"Lifecycle and editing rules").

**Reasoning:** Block-0 acceptance criteria from plan §4.0.3 met; B0-AUDIT-PLAN-AND-API + B0-AUDIT-CONVENTION pass; ADR binds downstream Blocks 1b…6 per plan §4.0.4 "Bindender-Vertrag-Charakter".

### 2026-05-14 — Block-0 doc-set audit cleanup (B0-VAL-REPAIR)

**Trigger:** Validated findings F-4 (Triangle-FSM diagram SSoT), F-11 (Phase-2-Superseding placement), F-12 (`ADR-5` → `ADR-0005` shorthand).

**Before:** §"Architecture-visible structure" duplicated the KEYBOARD/WIDGET/HOVER ASCII state-diagram from `triangle-fsm.md §3` with wording drift (`Send-Mode-Varianten` vs. `Send-mode variants`, `InputConnection alv` vs. `InputConnection alive`) — proof the SSoT-rule had already been lost (F-4). "Phase-2 Superseding Expectations" lived inside `## Decision History` (F-11). References → Related Plan cited `(ADR-5 decision-kernsatz)` using the 1-digit shorthand instead of the 4-digit `ADR-0005` form used everywhere else (F-12).

**After:** §"Architecture-visible structure" compacted to a 7-bullet T1–T7 summary + pointer to `triangle-fsm.md §3 + §5` (architecture-doc is now SoT for the state-diagram). Phase-2-Superseding moved to new top-level section `## Supersede Triggers (Forward-Looking Notes)` between `## References` and `## Decision History`. `ADR-5` → `ADR-0005`.

**Reasoning:** SSoT-rule (knowledge-doc-format §"Anti-redundancy") demands one canonical home per topic — already-drifted wording proves the duplicate was untenable. Forward-looking content does not belong inside an append-only audit log. 4-digit ADR identifiers are the convention across the doc-set; mixed forms erode it.

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

### 2026-05-21 — Superseded by ADR-0008

**Trigger:** The Triangle-FSM accumulated structural pressure that the
three-Mode-Enum form could no longer absorb cleanly:

1. **Truth-Table-Konflikt (Row 3 fix, 2026-05-21):** Bug #121
   ("Widget verschwindet bei IME-Close ohne Recording") forced a
   Row 3 (`!imeView && userPrefersWidget → WIDGET`) into
   `computeViewMode`. Row 3 collides semantically with Row 4
   (`!imeView && pipelineActive → HOVER`); the resolution by
   row-priority discards the *origin* of the WIDGET state.
2. **Bidirectional-Render-Migration (A3-Phase, 2026-05-21):**
   `KeyboardLayoutManager.modeForBackend` introduced parallel
   render paths (Keyboard- and Widget-Surface can be live
   simultaneously). The "exclusive Mode-Wahl" semantics of the
   enum became structurally false — but the State-Model did not
   follow.
3. **Crash-Recovery-Requirement (2026-05-21):** The
   Widget-State-and-Recovery plan needs to know after Pipeline-End
   whether to return to KEYBOARD (Pipeline triggered) or remain in
   WIDGET (User preference). With a Mode-Enum that has no Origin
   field, this question is structurally unanswerable.

**Before:** ViewMode-Enum (KEYBOARD | WIDGET | HOVER) with five-row
truth-table, T1-T7 transitions, `userPrefersWidget` transient on
`overlay`.

**After:** Superseded by ADR-0008 — `WidgetState` (Hidden |
Visible(origin: WidgetOrigin)) + `imeViewVisible: Boolean` as two
orthogonal axes. W1-W8 transitions. Origin is now an explicit,
typesafe field. Bidirectional-Render is structurally visible (both
axes can be true). The Row-3 patch is obsolete — sticky-widget is
guaranteed by W5 (USER-Origin survives `OnImeViewShown`).

**Reasoning:** The Triangle-FSM solved the problems of 2026-05-14
correctly. But the three-Mode-Enum form collapsed three orthogonal
concepts (which surface is rendered, who triggered it, is the
keyboard visible) into one variable. Each subsequent change pressed
on the join points: T7 fixed the Geist-Widget class, Row 3 fixed
sticky-widget, Bidirectional-Render broke the exclusive semantics,
Crash-Recovery needed an Origin field that doesn't fit the enum.
Two-axes is the smallest restructure that makes all of these
structurally clean. See ADR-0008 §"Alternatives" for the full
weighing — flat enum, twin booleans, and 4-Mode-Enum variants were
all considered and rejected.
