---
name: info-feedback-channels
description: Full inventory of every user-facing info/feedback channel in Dictate (toasts, FGS notification, hints, vibration, status surfaces, error routing) — classified imperative vs. state-derived, the basis for a unified info-state model.
status: Research
---

# Info / Feedback Channel Inventory — the Whole Landscape

## 1. Purpose

Before unifying Dictate's user-facing information surfaces into a
state-derived model, we mapped **every** channel that tells the user
something. Companion to [infobar-territory-map.md](infobar-territory-map.md)
(which maps the InfoBar itself — not repeated here).

## 2. Findings

### 2.1 The headline problem — a single error fans to 4–5 surfaces, no owner

A single AI error (e.g. network failure) currently triggers, through
**six independent wirings**, with **no shared "error" fact in state**:

| Surface | Wiring | Class |
|---|---|---|
| InfoBar | `onPipelineError` → `showInfo(key)` | imperative-push |
| Vibration | `onPipelineError` → 300 ms one-shot (method arg) | one-shot effect |
| Record-button red | `StepFailed` → `Running.hasFailure` boolean | state-derived |
| Step-row ✕ | `StepFailed` → `StepRowItem.FAILED` | state-derived |
| Resend button | `onShowResend()` → `ResendState.lastAudioExists` | state-derived |
| FGS notification | `PipelineFailed` → `Idle` → notification **dismissed** | derived effect |

The error **kind** is destroyed by the FSM (`PipelineFailed` collapses
`pipeline → Idle`); `Running.hasFailure` is a bare boolean carrying no
kind. So the InfoBar and the red button derive the *same* event from
*two different sources* and can desync. The notification **contradicts**
the InfoBar: on a failure the notification *disappears* while the
InfoBar *shows* the error.

### 2.2 Toasts — all imperative, no state backing

- **`ToastSink`** (`state/ModuleServices.kt:500`, bound at
  `PipelineServiceStubSubsystems.kt:250`, wired
  `DictatePipelineService.kt:570`) is a plumbed effect-channel
  interface — but **zero modules call it.** Fully dead infrastructure.
- **IME-service operational toasts** — the unification-relevant set,
  each fired from 3–4 sites with no shared dispatcher:
  - `dictate_service_not_ready` — early tap before service bind
    (`DictateInputMethodService.java:3775,4207,5478`)
  - `dictate_storage_full` — `AudioFileFactory.allocate()` IOException
    (`:3815`)
  - `dictate_job_already_active` — `showJobBusyToast()`, fires 4×
    (`:4218,5254,5276,5484`) — a **cosmetic echo** of a reducer
    `Idle`-guard the FSM already enforces
  - `dictate_audio_file_missing` — audio file gone (`:5310,5455`)
  - `dictate_resend_focus_lost` — both InputConnection channels lost
    (`:5243`)
- **Activity-side toasts** (settings / history / prompts / onboarding) —
  out of scope (Activity surface, not the IME).

Toasts are fire-and-forget, do not survive recreation, are not
unit-testable, and have **no lifecycle** — the OS owns the ~2 s timeout.

### 2.3 FGS notification — already a clean sealed model, but separate

`PipelineNotificationCoordinator` renders a `NotificationStatus` sealed
interface (`ModuleServices.kt:452`): `Idle | Recording | Paused |
Pipeline | OverlayPermissionRequired`. Driven by reducer
`Effect.UpdateNotification` from `RecordingModule` / `PipelineModule` /
`OverlayModule` — so the *reducer* decides the status (state-derived
intent), the coordinator just renders it (imperative at the seam). It
is an **OS-owned surface**, visible when the keyboard is not, owned by
the FGS process, survives view recreation, unit-tested. Dead payload:
`NotificationStatus.Pipeline.step` is plumbed but `build()` never reads
it. Orphaned strings `dictate_notif_ready_to_insert` /
`dictate_action_insert` / `dictate_action_discard` — a never-built
"result ready" notification (cf. memory `project_pending_info_screen`).

### 2.4 Hints — the "windshield hint" does not exist yet

`OVERLAY_BLOCK_HINT` exists **only** as a proposed LayoutMode in
ADR-0006 — the placeholder for the unsolved problem "an info-item is
produced while the floating overlay is showing, but the overlay window
cannot host an InfoBar". Not built. The onboarding Activities
(`OnboardingActivity`, `OverlayPermissionOnboardingActivity`) are
imperative Activity-side flows; their outcomes are persisted prefs the
IME already reads.

### 2.5 Vibration — one informational use, the rest are control haptics

Central sink `DictateInputMethodService.vibrate()` (`:3383`), gated on
`vibrationEnabled`. **Only one vibration carries information:** the
300 ms error buzz in `onPipelineError` (`:4562`) — distinct from the
50 ms keystroke tick. Everything else is keystroke/control haptic
feedback (a property of a control, not an info notice). Inconsistency:
the `vibrate` flag is `true` for runtime errors but `false` for
preflight failures of the *same* `internet_error` class.

### 2.6 In-keyboard status surfaces — already state-derived

Record-button colour (red on `Running.hasFailure`,
`RecordButtonColorController`), pipeline step-row
(`PipelineStepRowRenderer` from `Running.stepHistory`), record-button
text label (pure `TextResolvers`), recording timer + amplitude (phase
state-derived, per-tick values deliberately side-channel per Spec 2
§11.5), auto-enter glyph (`AutoEnterRenderer`). All post-cutover
state-derived and unit-tested.

**Important distinction:** `Running.hasFailure` is a *non-terminal
step* failure — a step can fail while the pipeline keeps running and
ultimately succeeds (Q6). A terminal pipeline error is a *different*
fact. They must not be merged.

### 2.7 Other channels

- **Dialogs** — all imperative `MaterialAlertDialogBuilder`, all
  Activity-side. The **"What's new" changelog dialog**
  (`DictateSettingsActivity.java:178`) duplicates the InfoBar `update`
  notice — same trigger (`LastVersionCode < VERSION_CODE`), two
  surfaces.
- **Sounds** — none (notification is `setSilent(true)`).
- **Snackbars / badges** — none.

## 3. Conclusions

### 3.1 Genuine in-app info notices — unify into a state-derived model

- **The 5 pipeline-error types** — no natural state source today
  (the FSM drops the kind). Need a new typed axis.
- **The IME-service operational toasts** (`service_not_ready`,
  `storage_full`, `audio_file_missing`, `resend_focus_lost`) — same
  category as pipeline errors: transient, in-keyboard,
  error/warning notices. Currently imperative toasts. `job_already_active`
  is a cosmetic echo of a reducer guard — drop it, do not migrate.
- **App-lifecycle prompts** (`update/rate/donate`) — derived from
  persistent prefs, *not* transient events → a separate small axis.
- **The dead `ToastSink`** — delete (or repurpose for genuinely
  ephemeral feedback).

### 3.2 Correctly separate — do not fold in

- **FGS notification** — an OS-owned surface, cross-process (FGS vs.
  IME), already a clean sealed model. Keep separate — but it and the
  unified info-state should derive from the *same* FSMs so they cannot
  contradict (today the notification dismisses on an error the InfoBar
  shows).
- **Vibration** — a one-shot effect, not steady state. Stays an
  `Effect` fired on the state edge when an error notice is added.
- **Keystroke/control haptics, `setHint()` placeholders, Activity
  dialogs, audio-playback** — control affordances / Activity UX, not
  info notices.

### 3.3 Landmines for a unified model

1. The error kind is destroyed by the FSM — a *new* axis is mandatory.
2. `createdAt` cannot come from a pure reducer — the dispatching action
   must carry the timestamp.
3. Per-tick data (timer/amplitude) must stay side-channel — a unified
   model absorbs the *phase*, never the high-frequency *value*.
4. Vibration is an effect — modelling it needs edge-detection, not
   steady-state rendering. Effects and surfaces stay distinct.
5. The notification renders from a different process/lifecycle — a
   shared info-state must be reachable from both, or stay command-driven.
6. Toasts have no lifecycle — migrating a toast to an InfoBar item
   changes its lifetime (auto-vanish → lingers until dismissed). Each
   migrated toast needs an explicit auto-dismiss decision.
7. `Running.hasFailure` is non-terminal — a unified "is there an error"
   selector must distinguish a transient step failure from a terminal
   pipeline failure.
8. `OVERLAY_BLOCK_HINT` — info-items produced while the overlay shows
   have no surface; the unified model needs a defined behaviour there
   (the proposed LayoutMode is the placeholder).

## 4. Recommendation summary

One typed list axis `transientNotices: PersistentList<TransientNotice>`
(sealed hierarchy, per-variant payload) for pipeline errors + the
operational IME notices; a separate `appPrompts` axis for the
persistent-pref-driven update/rate/donate prompts; both feed
`InfoBarSelector`. Vibration becomes an `Effect` on the error-notice
edge. The FGS notification stays a separate surface but is re-derived
from the same FSMs. The record-button-colour / step-row stay on
`Running.hasFailure` (non-terminal step failure ≠ terminal error).
