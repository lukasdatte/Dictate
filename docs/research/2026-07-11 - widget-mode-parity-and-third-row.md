---
date: 2026-07-11
author: Lukas + Claude (implementation session)
status: Accepted
context: Five point-packages (P1–P5) that bring the floating overlay widget to functional parity with the keyboard for concurrent-recording flows and add a third direct-editing row (delete | space | enter) with press-and-hold continuous delete.
related-plan: n/a (point-feature series, no plan folder)
related-adrs: ADR-0009, ADR-0010
---

# Widget-Mode Parity and Third Row

Make the floating overlay widget a **self-sufficient dictation surface** while
an input field is available: a recording started during a live pipeline run is
sendable (P1), a follow-up recording can be armed from the widget itself (P2),
closing the widget hands a live recording to the returning keyboard instead of
freezing it (P3), a third row offers Delete / Space / Enter for small editing
tasks without unfolding the IME (P4), and holding Delete deletes continuously
with the keyboard's acceleration curve (P5).

## 1. Vision and Motivation

### 1.1 Why this series exists

The overlay widget (ADR-0008 surface axis) started as a *recording remote
control*: start, pause, send, close. Everything beyond that required unfolding
the keyboard. Four user reports showed that this made the widget a dead end in
exactly the flows it was built for:

- Closing the widget mid-recording appeared to *stop* the recording — the
  returning keyboard showed a frozen timer (P3 report).
- While a pipeline run was processing, the widget offered no way to queue the
  next thought (the keyboard has a secondary-record button for this,
  ADR-0009), and even a recording that *did* coexist with a run could not be
  sent from the widget (P1/P2 reports).
- Micro-edits around a dictation ("dictate → space → dictate more → enter →
  fix one character") forced a full keyboard unfold for a single keystroke
  (P4/P5 report).

### 1.2 What problem this solves

The series closes the WIDGET-mode parity gap with the keyboard for the
concurrent-recording feature set (ADR-0009) and adds direct editing, while
keeping HOVER (no input target) deliberately minimal. The result is one
decision axis — *is there a host editor to commit into?* — instead of a
per-button patchwork.

### 1.3 Discarded Alternatives

- **Secondary-record button in HOVER too:** ~~rejected — a recording started
  with no `InputConnection` target could never be *sent*, only deferred. That
  is the "startable but never sendable" anti-pattern already rejected as
  ADR-0009 Alt-3 for the keyboard; the overlay follows the same rule via the
  `imeViewVisible` gate.~~ **Superseded 2026-07-12 (Decision 3 — HOVER-send).**
  The premise is void: sending in HOVER is now enabled by user decision, and a
  HOVER send is *not* lost — it defers to a pending part offered on the next
  keyboard open (ADR-0009 deferred-insertion + ADR-0011 headless-completion).
  "Startable but never sendable" no longer applies, so the secondary-record
  button appears in HOVER too and the `imeViewVisible` gate is removed. See the
  Change-History entry below.
- **`ButtonSlot.longClickResolver` for continuous delete (P5):** rejected — a
  long-click resolver fires *one* action per long-press; continuous delete
  needs a self-re-scheduling repeat loop with an acceleration curve, which is
  a stateful policy, not a single action.
- **Reusing the IME's `onBackspaceLongClicked` loop for P5:** rejected — that
  loop is bound to the keyboard View and the IME service's `Handler` /
  `isDeleting` fields. Only the pure acceleration curve
  (`BackspaceDeleteSpeedCurve`) is shared, so keyboard and widget cannot
  drift apart on timing while the lifecycle wiring stays surface-local.
- **Unconditional pause on widget close (pre-P3 behaviour):** rejected —
  pausing is only right when *nobody* can take over. With the IME view still
  on screen (WIDGET), pausing produced the "recording stopped" misread.

### 1.4 What this buys us

1. WIDGET mode is now a full dictation loop: record → send → queue the next
   recording → small edits — without unfolding the keyboard.
2. One canonical predicate (`DictateUiState.canCommitToHost`) gates every
   host-commit affordance, so WIDGET vs HOVER cannot drift per button.
3. Keyboard and widget share the same ADR-0009 precedence and the same delete
   acceleration curve — single sources of truth, two surfaces.

## 2. Acceptance Criteria

1. **P1 — recording-wins precedence:** `resolveOverlayRecordAction` /
   `...Enabled` / `...ButtonText` evaluate `recording` Active|Paused *before*
   `isPipelineLive`, matching `LayoutCatalog.forKeyboard` (ADR-0009
   "recording wins over SEND_MODE"). A recording coexisting with a live run
   is sendable from the overlay record button when `canCommitToHost`.
2. **P2 — secondary record:** `OVERLAY_RECORD_SECONDARY` is visible iff
   pipeline is live (Preparing|Running) AND recording is Idle — on **both**
   surface axes (the former `imeViewVisible` gate was removed 2026-07-12,
   Decision 3 / HOVER-send). It shares Row 2's centre slot with Pause (never
   both visible); the tap reuses `resolveSecondaryRecordAction` verbatim and no
   `imeSideAffordance` hook fires.
3. **P3 — close handoff:** the widget X button pauses an Active recording
   only when `!imeViewVisible` (HOVER). In WIDGET the recording stays Active
   and the returning keyboard continues it (no `PauseMediaRecorder` effect).
4. **P4 — third row:** `OVERLAY_DELETE | OVERLAY_SPACE | OVERLAY_ENTER`
   render iff `canCommitToHost`; the row *container* also collapses (GONE) so
   no empty margin gap remains in HOVER; the actions reuse the existing
   `KeyboardInputAction` path (`Backspace` / `SpaceKey`) and the keyboard's
   `resolveEnterIcon` / `resolveEnterAction`.
5. **P5 — repeat delete:** holding the delete button past the platform
   long-press timeout deletes continuously with the
   `BackspaceDeleteSpeedCurve` cadence (50→25→10→5 ms); a short tap deletes
   exactly once; release, drag-steal (`ACTION_CANCEL`) and overlay teardown
   all stop the loop; teardown leaves no scheduled `Handler` callback.
6. All behaviours are pinned by JVM unit tests (red-first);
   `./gradlew testDebugUnitTest` passes.

## 3. Architecture Specification

### 3.1 Behaviour matrix — WIDGET vs HOVER

The single decision axis is `DictateUiState.canCommitToHost`
(== `imeViewVisible`: an `InputConnection` backs
`getCurrentInputConnection()`). WIDGET floats over a visible IME view;
HOVER floats over another app with no input target.

| Affordance | WIDGET (`canCommitToHost`) | HOVER (no host editor) | Why |
|---|---|---|---|
| Record button, recording Idle | Start recording | Start recording | Deferred insertion covers HOVER sends later (ADR-0009) |
| Record button, recording Active/Paused | **Send** (Stop&Send) — evaluated *before* pipeline-live (P1) | **Send** (Stop&Send) — defers to a pending part (2026-07-12 HOVER-send) | Deferred insertion; precedence parity with keyboard (ADR-0009 + ADR-0011) |
| Record button, pipeline live + recording Idle | Auto-enter toggle (fall-through after P1 reorder) | Auto-enter toggle | Unchanged pre-series behaviour |
| Secondary record (Row 2 centre, P2) | Visible iff pipeline live AND recording Idle | **Visible** iff pipeline live AND recording Idle (2026-07-12 HOVER-send) | A HOVER secondary recording is now sendable (deferred to a pending part) — anti-pattern void |
| Pause button | Visible while recording in flight | Visible while recording in flight | Shares the centre slot with secondary record — states are disjoint |
| Close (X) during Active recording (P3) | Recording **keeps running**; keyboard takes over | Recording **pauses** | Pause only when nobody can take over (`!imeViewVisible` gate) |
| Third row Delete/Space/Enter (P4) | Visible (buttons + container) | GONE (buttons + container collapse) | Keystrokes into a null IC are no-ops; don't show dead buttons |
| Delete press-and-hold (P5) | Continuous delete, keyboard curve | n/a (row hidden) | Same `BackspaceDeleteSpeedCurve` as the IME backspace |

### 3.2 P5 — repeat-delete architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  OverlayBackend.wireDeleteRepeat()          (thin View wiring)      │
│  OnTouchListener on overlay_delete_btn:                             │
│    DOWN   → controller.onPress()      (returns false: pressed state │
│             + parent drag-interception stay intact)                 │
│    UP     → controller.onRelease()    (true ⇒ consume, suppress the │
│             trailing click; false ⇒ short tap, click deletes once)  │
│    CANCEL → controller.cancel()       (drag steal / detach)         │
└─────────────────────────────────────────────────────────────────────┘
                                ↓ delegates policy to
┌─────────────────────────────────────────────────────────────────────┐
│  OverlayDeleteRepeatController              (pure Kotlin, JVM-test) │
│  Phases: IDLE → PENDING (long-press timer) → REPEATING (tick loop)  │
│  Each tick: onDelete() + BackspaceDeleteSpeedCurve.nextDelay(...)   │
│  onRelease()/cancel() → IDLE + RepeatScheduler.cancelAll()          │
└─────────────────────────────────────────────────────────────────────┘
                                ↓ each tick dispatches via
┌─────────────────────────────────────────────────────────────────────┐
│  OverlayBackend.dispatchSlotAction(OVERLAY_DELETE)                  │
│  = the SAME slot-resolver → onAction path a tap uses                │
│  → Action.KeyboardInputAction.Backspace → KeyboardInputModule → IC  │
└─────────────────────────────────────────────────────────────────────┘
```

Key properties:

- **Policy/wiring split (SRP).** All timing and phase state lives in
  `OverlayDeleteRepeatController`; the `Handler`/`Looper` sits behind the
  `RepeatScheduler` interface (`HandlerRepeatScheduler` in production, a
  recording fake in tests), so the tick cadence and every stop path are
  JVM-testable without Robolectric.
- **Shared acceleration (DRY).** The curve is the existing pure
  `BackspaceDeleteSpeedCurve` — the same object the IME backspace cascade
  consumes. The acceleration applies to the tick scheduled *after* a
  threshold is crossed, identical to the IME's `Handler` loop semantics.
- **Drag coexistence.** The touch listener never consumes `ACTION_DOWN`, so
  `OverlayDragController`'s `onInterceptTouchEvent` keeps its chance to
  promote the gesture to a window drag; the drag steal delivers
  `ACTION_CANCEL` to the button, which maps to `controller.cancel()`. A
  still hold never crosses the drag threshold — the two gestures are
  mutually exclusive by construction.
- **Lifecycle.** `teardownOverlay()` cancels the controller *before* View
  refs are dropped (dedicated per-controller `Handler`, so
  `removeCallbacksAndMessages(null)` has a bounded blast radius); every
  scheduled callback re-checks the phase, so a stale tick is a no-op.
- **Tap semantics preserved.** A short tap (release before the long-press
  timeout) leaves the UP unconsumed and the framework click dispatches the
  single `Backspace` through the unchanged click path.

### 3.3 P1–P4 — code anchors

| Package | Behaviour home | Surface wiring |
|---|---|---|
| P1 precedence | `state/layout/ActionResolvers.kt` (`resolveOverlayRecordAction` / `...Enabled`), `state/layout/TextResolvers.kt` | `LayoutCatalog.OVERLAY_5BUTTON` Row 1 |
| P2 secondary record | `resolveSecondaryRecordAction` (shared with keyboard) | `LayoutCatalog` Row 2 slot + `res/layout/overlay_5button_layout.xml` (`overlay_record_secondary_btn`) |
| P3 close handoff | `state/modules/WidgetModule.kt` (W2 `CloseWidget(WIDGET_BUTTON)` arm, `!imeViewVisible` pause gate) | overlay X button (unchanged dispatch) |
| P4 third row | `state/DictateUiState.kt` (`canCommitToHost`), `LayoutCatalog` Row 3 slots | `OverlayBackend.applySlots` container collapse + layout Row 3 |
| P5 repeat delete | `state/render/overlay/OverlayDeleteRepeatController.kt` | `OverlayBackend.wireDeleteRepeat` / `dispatchSlotAction` / `teardownOverlay` |

## 4. Testing Approach

- **Unit (JVM, pure):**
  `state/render/overlay/OverlayDeleteRepeatControllerTest.kt` — tick
  cadence (50→25→10→5 across the thresholds), short-tap vs repeat release
  semantics, stale-tick phase guard, cancel paths, double-press guard.
- **Unit (Robolectric):**
  `state/render/overlay/OverlayBackendTest.kt` — P2/P4/P5 wiring (slot
  visibility, container collapse, dispatch identity, hold-repeats /
  short-tap / drag-cancel / detach-leak tests);
  `state/layout/ActionResolversTest.kt` + `TextResolversTest.kt` (P1
  precedence table); `state/WidgetModuleTest.kt` (P3 pause gate);
  `state/layout/LayoutCatalogTest.kt` (+`LayoutCatalogEnterSlotConsistencyTest`)
  for slot predicates and Enter parity.
- **Red-first evidence:** the P5 backend tests were run against the unwired
  backend (wiring stashed) and failed on all four wiring assertions before
  the wiring was restored.
- **Manual (device):** hold-to-delete in a real editor (acceleration +
  haptic-free release), drag the widget starting on the delete button,
  close-handoff with keyboard visible vs from HOVER.

## 5. Information Gaps

1. **Position re-clamp on row visibility changes (pre-existing, deliberately
   not fixed in P4).** `OverlayBackend.applyPosition` caches on
   `(portrait, normX, normY)` without the view height. When the third row
   appears/disappears (IME visibility flip), the window height changes but
   the cached position short-circuits re-clamping until the next position
   change — a bottom-docked widget can extend slightly off-screen until
   moved. **Owner:** follow-up fix (include measured height in the cache key
   or re-clamp on layout change); **fallback:** user drags the widget once.
2. **No haptic on P5 acceleration steps.** The IME backspace cascade fires
   `vibrate()` when the delay steps down; the overlay controller exposes the
   symmetric `onAdvance` hook but production wiring leaves it a no-op (the
   backend has no vibrator dependency today). **Owner:** follow-up UX polish
   if users miss the feedback; **fallback:** visual-only acceleration.
3. **`imeViewVisible` boot default `true`.** On a cold service start without
   any IME lifecycle event the axis is stale-true, so third-row/secondary
   gating can be briefly wrong until the first IME event. Pre-existing axis
   semantics (documented in the external-dictation spec §6.3), unchanged by
   this series.
4. **Cold-process HOVER send does not complete (residual, 2026-07-12
   HOVER-send).** When the IME process never bound at all, the send-tap
   `imeSideAffordance` snapshot lambda is the default no-op, so no fresh-config
   snapshot exists and `PipelineRunnerSubsystemAdapter.submit → resolveFresh`
   throws the R-1 tripwire `UnsupportedOperationException`. This is now
   **recoverable, not a hang**: `PipelineModule.reduceFailure` catches the
   `SubmitPipeline` failure and rolls `Preparing → Idle` + dismisses the FGS
   notification (previously the FSM stuck in "Sending …" forever, since
   `PipelineModule` had no failure arm). The send itself is still *lost* in
   that cold-process case — building a service-side fresh-config resolver so it
   actually completes is out of scope here and tracked against ADR-0011
   (headless-completion). In the normal HOVER case the IME service is alive
   (Dictate is the selected keyboard) and the snapshot runs, so the send
   completes and defers to a pending part. Note `captureFreshConfigSnapshot`
   reads `getCurrentInputEditorInfo()` live and may see a null/stale editor in
   HOVER, so `targetAppPackage` can be `null` — accepted (it only affects
   telemetry/target-app metadata, not the transcript).

## Decision Log

### D1 — One predicate for every host-commit affordance

**Trigger:** P4 design. **Decision:** the third row, the secondary-record
gate and the record-button send arm all key on `DictateUiState.canCommitToHost`
(== `imeViewVisible`); no per-button axis. **Rationale:** the 2026-05-22
regression (a "canCommitToHost" guard whose body checked `widget`) showed
name-vs-body drift is the failure mode; a single named predicate makes it
structurally impossible. **Alternatives:** per-slot custom predicates —
rejected as drift-prone.

### D2 — Repeat loop as a scheduler-injected policy class, not View code

**Trigger:** P5 design (P4 hand-off notes). **Decision:**
`OverlayDeleteRepeatController` owns phases + timing behind a
`RepeatScheduler` interface; `OverlayBackend` only maps touch events to
`onPress`/`onRelease`/`cancel`. **Rationale:** JVM-testable cadence, reuse of
`BackspaceDeleteSpeedCurve` (single acceleration SoT), thin lifecycle wiring.
**Alternatives:** `longClickResolver` (single action, no loop) and reusing
the IME's `onBackspaceLongClicked` (View-bound) — both rejected, §1.3.

### D3 — Pause-on-close gated on `!imeViewVisible` (P3)

**Trigger:** user report "closing the widget stops the recording".
**Decision:** the W2 close arm pauses only when no IME view can take over.
**Rationale:** pause is a safety net for a surface-less recording, not a
close side-effect; in WIDGET the keyboard resumes live controls seamlessly.
**Alternatives:** always pause (old behaviour, misread as stop) / never pause
(HOVER would leave a hot mic with no visible controls) — both rejected.

## Change History

### 2026-07-12 — HOVER-send enablement + reusable transient-notice primitive (Decision 3)

**Trigger:** user decision to lift the May-2026 "no send without an
`InputConnection`" rule. Sending in HOVER must work; the pipeline runs and the
result becomes a pending part offered on the next keyboard open.

**Changes:**

- **HOVER-send enabled (P1 arm).** `resolveOverlayRecordAction` now sends
  (Stop&Send) for Active/Paused on **both** surface axes — the
  `!imeViewVisible ⇒ null` gate is removed. `resolveOverlayRecordEnabled`
  returns `true` for Active/Paused regardless of `imeViewVisible`. The label
  resolver already read "Send" independent of the axis (no change needed). A
  HOVER send's transcript defers to a pending part (ADR-0009
  deferred-insertion → `AddPendingInsertSession` → "Tap to paste" InfoBar;
  ADR-0011 headless-completion covers the completion stage).
- **Secondary-record button in HOVER (P2).** The `OVERLAY_RECORD_SECONDARY`
  slot's `imeViewVisible` visibility gate is removed — it now appears in HOVER
  too (visible iff pipeline live AND recording Idle). The "startbar-aber-nicht-
  sendbar" (ADR-0009 Alt-3) rationale is void because a HOVER send now defers
  to a pending part. Supersedes §1.3 Discarded-Alternative bullet 1, AC #2, and
  the §3.1 matrix rows above.
- **Reusable transient-notice primitive ("OverlayTransientNotice").**
  State-driven (no `android.widget.Toast`), owned by `OverlayModule`:
  `TransientNotice(textRes, token)` on `OverlayState`; actions
  `ShowTransientNotice(textRes, durationMs)` / `ExpireTransientNotice(token)`;
  auto-expiry via `Effect.ScheduleNoticeExpiry` (a `scope.launch { delay(); emit }`
  mirroring `ResendModule`'s cooldown). The monotonic token makes overlap safe
  (an older expiry never clears a newer notice; a second Show before expiry
  wins). Rendered as a small `TextView` (`overlay_notice_tv`) under the buttons,
  coloured via `?attr/colorOnSurfaceVariant` (theme attr, ADR-0010). Any module
  may dispatch `ShowTransientNotice` with another string. The first live use:
  `OverlayModule.onCrossModuleStateChange` fires it (~3.5 s,
  `overlay_notice_pending_insert`) on a hover-send — observed as pipeline
  `Idle → Preparing` while `!imeViewVisible`; a keyboard-visible send does not
  trigger it.
- **Cold-process hang fix (R-1 tripwire).** `PipelineModule.reduceFailure` now
  recovers `Preparing → Idle` + `DismissNotification` when `SubmitPipeline`
  throws (previously no failure arm → the FSM hung in "Sending …" forever). See
  Information Gap §5.4 for the residual cold-process limitation.

**Not touched:** D3 (pause-on-close) and the close-handoff semantics are
unchanged — only the *send* and *secondary-record visibility* gates moved.

## References

- `docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md` —
  run queue, secondary recording, "recording wins" precedence, Alt-3
  anti-pattern
- `docs/decisions/0010-ui-icon-tint-theme-attrs.md` — no colour literals
  (all new UI reads theme attrs)
- `docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md` —
  the ADR-0009 spec this series extends to the overlay surface
- `docs/research/2026-07-09 - external-dictation-entry-points.md` — §6.3
  `imeViewVisible` boot-default gap (shared with §5.3 here)
- Commits: `f8008ff` (P3 close handoff), `c0297ae` (P1 recording-wins
  precedence), `9ea0677` (P4 third row), `c45cb25` (P2 secondary record),
  P5 feature commit (this session — repeat delete)
- Code: `state/render/overlay/OverlayDeleteRepeatController.kt`,
  `state/render/overlay/OverlayBackend.kt`, `state/layout/LayoutCatalog.kt`,
  `state/layout/ActionResolvers.kt`, `state/modules/WidgetModule.kt`,
  `core/BackspaceDeleteSpeedCurve.kt`,
  `res/layout/overlay_5button_layout.xml`
