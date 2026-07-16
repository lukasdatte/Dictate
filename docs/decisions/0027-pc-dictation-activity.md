# ADR-0027: PC-Dictation Activity — a Third Render Host with a PC-only Terminal Mode

**Status:** Accepted
**Subsystem:** ui-mode, state, windows
**Scope:** Project-Wide
**Date:** 2026-07-16
**Author:** Lukas + Claude

> **Plain-language summary.** Dictate is a keyboard (IME). We add a normal
> full-screen app screen — "PC Dictation" — that shows the same keyboard grid
> plus the session history, but where *every* output is sent to the paired
> Windows PC instead of being typed into a local text field. This ADR records
> two decisions: (1) that screen is a **third render host** reusing the
> existing keyboard rendering rather than a new UI, and (2) "send everything
> to the PC" is a **new transient mode-state** (`pcOnly`) threaded through the
> existing send/gate seams — not a new send path.
>
> Jargon on first use: **render host** = a place the keyboard UI is drawn
> (today: the IME view, and the floating overlay widget); **terminal** = the
> point where a finished dictation is delivered; **seam** = an existing
> extension point in the code where behaviour is already branched.

## Research

- **Multi-backend rendering already exists (ADR-0004, ADR-0008).**
  `KeyboardLayoutManager` fans every `DictateUiState` emit out to a *list* of
  attached `RenderBackend`s; `ImeViewBackend` and `OverlayBackend` are two
  members that render the same state onto different view instances. The
  manager's `attachBackend` guard is by object identity, so a third backend
  can coexist. `ImeViewBackend` takes a `MotionSurface` + a `LogicalButtonId →
  View` map + `ModuleServices` — none of which is IME-specific.
- **State lives in the foreground service (ADR-0003).** `DictateOrchestrator`
  + `DictateUiStateStore` + `KeyboardLayoutManager` are owned by
  `DictatePipelineService`, reachable by any component that binds it. The
  overlay backend is the blueprint: same renderer classes, other view
  instances, reactive attach.
- **One PC-send primitive (ADR-0019).** `WindowsDispatchCoordinator.dispatch`
  is the single Windows-dispatch path; `WindowsAutoSend.shouldDivertToPc` is
  the single "should this go to the PC?" gate. The two terminal producers (the
  IME seam and the headless sink) both call the same primitive.
- **Headless completion has a documented gap (ADR-0011).** A pipeline can
  complete with no IME bound; the service-side fallback surfaces a
  `committed=false` "Tap to paste" pending part — which presumes an IME host.
- **Config-resolution is IME-runtime-bound (verified 2026-07-16).** A fresh
  recording's `JobRequest` is built by a `PipelineConfigResolver` the IME
  registers; without one, `DefaultPipelineConfigResolver.resolveFresh`
  **throws** (`PipelineRunnerSubsystemAdapter.kt`). So a headless Activity
  recording cannot resolve its config unless the Activity registers a resolver
  itself. (The `resolveExternalDictationStart` path only covers *start*, not
  the stop-and-send config — the external-start feature relies on an IME being
  bound for the eventual send. **Follow-up:** a pure-headless stop-and-send —
  e.g. the overlay widget's Send with no IME bound — drops the affordance
  click (`DictatePipelineService.kt`, `imeSideAffordance` null → logged drop);
  the same latent gap. Out of scope here; documented for a later plan.)

## Context

The IME is only usable while a text field is focused in some app. Users want a
standalone surface to dictate *to their PC*: open an app screen, speak, and the
transcript appears on the Windows companion — with the full keyboard grid
available as a remote keyboard and the history available for re-sending. There
is no local `InputConnection` on such a screen, so the IME's "commit into the
host field" and "Tap to paste" fallbacks do not apply.

Two questions had to be resolved:

1. **How is the screen built?** A bespoke UI would duplicate the keyboard grid,
   its MotionScene, the record/recording animation, and the history panel.
2. **How does "everything goes to the PC" integrate?** Auto-send-to-PC already
   exists (ADR-0019) but is a *persistent user toggle* that deliberately
   excludes non-dictation output (e.g. text pills). The Activity needs a
   *stronger, transient* guarantee: while it is open, every terminal goes to
   the PC, and a failure must not try to fall back to a local host that isn't
   there.

## Decision

**1. The Activity is a third render host, reusing `ImeViewBackend`.**
`PcDictationActivity` binds `DictatePipelineService`, builds its own view tree
(the `activity_dictate_keyboard_view` layout, reused verbatim via `<include>`),
constructs its own `ImeViewBackend`, and attaches it to the *same*
service-owned `KeyboardLayoutManager`. Both surfaces render the one live
`DictateUiState`. No rendering code is duplicated and no new UI state axis is
introduced for the grid.

**2. "PC-only" is a third, transient mode-state (`pcOnly`), threaded through
the existing seams — not a new send path.**
`FeatureToggles.pcOnly` is a new boolean, set by the Activity lifecycle
(`SetPcOnly(true)` on resume, `false` on pause), never persisted. It sits
*next to* `windowsAutoSendActive` rather than folding into it, because they
mean different things: `windowsAutoSendActive` is the persistent auto-send
toggle (paired + on) that excludes `STATIC_PROMPT`; `pcOnly` is a host-scoped
override that diverts **every** terminal source-independently. The two terminal
seams read `pcOnly` and call the widened gate
`WindowsAutoSend.shouldDivertToPc(source, sp, pcOnly)`, dispatching through the
**existing** `WindowsDispatchCoordinator`.

**3. Host-less error policy: no local pending part; a visible retry instead.**
A dispatch started in PC-only mode carries `suppressPendingFallback` (captured
into `InFlightDispatch`); the `WindowsDispatchModule` Failed arm then skips the
ADR-0011 pending part and surfaces `DispatchNotice.Error(kind, sessionId)`. The
Activity renders an error banner with a retry keyed on that `sessionId`; the
text stays durably recoverable via `final_output_text` (ADR-0013 §3).

**4. Foreground-host binder registrations with precedence over the IME.**
The Activity registers a `PipelineConfigResolver` (an `ImePipelineConfigResolver`
snapshotted at the RECORD send-tap, with headless-faithful values: no target
app, no live prompt, no keyboard-switch, `ALWAYS_INSERT` since the review panel
is IME-only) and a PC-only `KeyboardActionDispatcher` (wrapping `PcInputSink`).
Both go into dedicated `delegateForeground*` slots on the `LocalBinder`; the
consumer lambdas prefer the foreground slot and fall back to the IME's when it
is cleared on `onStop`. The IME's own registrations are never overwritten, so a
bound-but-hidden IME keeps working once the Activity closes.

**5. Three entries.** A third launcher icon (`activity-alias .core.PcDictation`),
a static app shortcut, and the keyboard's PC-key long-press (paired → the
Activity, unpaired → pairing). Alias + shortcut funnel through the
`StartPcDictationActivity` NoDisplay trampoline whose pure
`PcDictationLaunchPolicy` gates on mic permission + pairing.

## Alternatives Considered

- **Fold `pcOnly` into `windowsAutoSendActive`.** Rejected: it would either
  drag the persistent toggle's semantics (which exclude `STATIC_PROMPT`) into
  the Activity, or force every render/read site to special-case the source.
  Keeping them separate and OR-ing at the two seams is the smaller, clearer
  change.
- **Route the Activity's recording through the external-start path
  (`ACTION_START_DICTATION` / `resolveExternalDictationStart`).** Rejected
  after verification: that path resolves *start*, not the stop-and-send config,
  and relies on an IME being bound for the send. It cannot make a
  pure-headless recording resolve its `JobRequest`.
- **Overwrite the IME's single config-resolver / keyboard-actions slot.**
  Rejected: clearing it on Activity pause would strand a bound IME's
  registration until it rebinds, breaking IME recording after one Activity use.
  A separate precedence slot is collision-free.
- **A bespoke, non-keyboard PC-dictation UI.** Rejected: duplicates the grid,
  MotionScene, animations, and history; drifts from the IME over time.

## Reused mechanisms (no new architecture)

- **Live-key gestures** reuse `SpecialTouchHandlerInstaller` via a `pcOnlyMode`
  flag (null-IC, PC branches only) — the same handlers the IME installs, only
  the wiring differs.
- **The record-button pulse animation** reuses `RecordingAnimationController`
  fed by the service's single recording ticker through **additive** foreground
  tick sinks on the binder (timer + amplitude). Additive rather than precedence
  because multiple animation surfaces tick independently; the destructive
  `getMaxAmplitude` poll stays the service's single poller.

## Consequences

### Positive

- One rendering model, three hosts. The Activity inherits every future
  keyboard-layout change for free.
- "PC-only" is a 1-flag addition on proven seams; the persistent auto-send
  behaviour is byte-for-byte unchanged when `pcOnly == false`.
- The config-resolver precedence slot is a reusable primitive for any future
  foreground input host.

### Parity gaps (documented, intentional)

- **ENTER overlay-character picker is unavailable in the Activity.** It inserts
  special characters through the local `InsertionService` (an IC write) and has
  no PC command; in the IME's own PC-mode it likewise writes locally. The
  `SpecialTouchHandlerInstaller` `pcOnlyMode` therefore gates it (does not
  install it). ENTER as a plain key still routes to the PC via the catalog
  resolver. This is the "IC-read-bound feature, gated in PC-mode, documented"
  case. All other gestures (Space tap, cursor-swipe, backspace-swipe word
  selection) run in the Activity via their existing PC branches.

### Negative

- `pcOnly` is global state on a shared store. While the Activity is
  foreground, a bound-but-hidden IME view would also "see" `pcOnly` — harmless
  because it is not visible, but it means `pcOnly` is not strictly per-host.
  Mitigated by keeping the PC-mode *visual* signalling in the Activity's own
  chrome (the purple PC accent) rather than in the shared renderers.
- Two config-resolver slots (IME + foreground) is marginally more machinery
  than one; justified by the collision-free hand-off.

### Failure Modes

- **No config resolver registered when a fresh recording submits** → the
  service-side default throws and the session fails loudly (never silent
  data loss). The Activity registers its resolver in `onServiceConnected`, so
  the window is a fresh recording started before bind completes — degrades to a
  loud failure surfaced in the notice, not a wrong transcript.
- **Dispatch fails in PC-only mode** → no local pending part (by design); the
  error banner + retry is the recovery surface, and `final_output_text` keeps
  the text recoverable across process death.
- **Pairing lost while the Activity is open** → `WindowsDispatchCoordinator`
  maps a null target to `WINDOWS_UNAUTHORIZED`; the Activity shows the error.
  The entry policy requires pairing, so this is an edge (unpaired *during* use).

## References

- Plan: `pc-dictation-view` (this work).
- ADR-0003 (foreground pipeline service), ADR-0004 (layout catalog /
  multi-backend), ADR-0008 (surface axes), ADR-0011 (headless completion
  fallback), ADR-0013 (review panel / durable final text), ADR-0019 (auto-send
  terminal), ADR-0026 (keyboard-action routing).
- `docs/architecture/state-architecture/rendering.md` §6.1 (third render host).

## Decision History

- **2026-07-16 — Proposed (plan-scoped).**
  - **Trigger:** the `pc-dictation-view` feature.
  - **Before:** two render hosts (IME view, overlay widget); PC-send was a
    persistent toggle only; headless fresh-recording config was unresolvable.
  - **After:** a third render host; a transient `pcOnly` terminal mode on the
    existing seams; a foreground-host config-resolver precedence slot.
  - **Reasoning:** reuse over duplication for the UI; a separate transient
    state over overloading the persistent toggle; a precedence slot over
    overwriting the IME's registration.

- **2026-07-16 — Promoted and Accepted.**
  - **Trigger:** implementation of the `pc-dictation-view` feature completed
    and verified (build + unit tests green); promotion by the orchestrator.
  - **Before:** plan-scoped draft at `tmp/adrs/adr-pc-dictation-activity.md`
    with an `NNNN` placeholder.
  - **After:** `docs/decisions/0027-pc-dictation-activity.md`, Status
    Accepted, indexed in `docs/decisions/README.md`.
  - **Reasoning:** the decision is active in the codebase from this merge on.
