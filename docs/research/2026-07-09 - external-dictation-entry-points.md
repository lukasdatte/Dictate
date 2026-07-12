---
date: 2026-07-09
author: Lukas + Claude (implementation session)
status: Accepted
context: External entry points (launcher alias for S-Pen/Edge/side-key pickers, static app shortcut, QS tile) that start a dictation without opening the keyboard, funnelled through one canonical service action.
related-plan: n/a (point feature, no plan folder)
related-adrs: ADR-0003, ADR-0005, ADR-0008, ADR-0009, ADR-0011
changelog:
  - 2026-07-09 — initial spec (external entry points)
  - 2026-07-12 — §6.1 headless-completion limitation RESOLVED (ADR-0011); D3 follow-up appended
---

# External Dictation Entry Points

Start a dictation **without opening the keyboard**, triggered from OS-level
surfaces: Samsung S-Pen Air Command, Edge panel, side-key double-press,
Routines, Tasker, a pinnable app shortcut, and a Quick-Settings tile. All
triggers funnel into one invisible trampoline activity and from there into a
single canonical service action; the recording then runs in the existing
overlay-widget (HOVER-capable) surface with the existing deferred-insertion
rules.

## 1. Vision and Motivation

### 1.1 Why this exists

Dictate's recording flow was reachable only from inside the IME (keyboard
record button, edit-bar widget toggle) — but the pipeline itself has been
IME-independent since ADR-0003 (FGS) and ADR-0008 (widget surface axis).
Samsung/Android launchers expose generic app-entry pickers (Air Command, Edge
panel, side-key) that list `MAIN`+`LAUNCHER` activities; exposing a second
launcher entry makes dictation one pen-click away.

### 1.2 What problem this solves

- No way to start dictating while no text field is focused (e.g. capture a
  thought, then choose where it goes — the ADR-0009 deferred-insertion flow
  already supports exactly this, it just had no external trigger).
- S-Pen / Edge / side-key / automation tools could only open the Settings
  activity, never a recording.

### 1.3 Discarded Alternatives

- **Direct service start from the tile / shortcut (no trampoline):** rejected
  — a mic-FGS may only start from a foreground context on Android 14+, and
  shortcut intents must target an activity anyway. The trampoline gives every
  trigger the same foreground window, permission gate, and degradation path.
- **A second "external start" reducer path in the state machine:** rejected —
  the policy reuses the canonical widget-open action (`ToggleViewModeWidget`,
  full T1 cascade) and the shared `resolveStartRecordingFromIdle` body, so the
  external trigger is byte-identical to an in-IME widget open + record tap.
  No parallel start semantics to maintain.
- **Headless pipeline-completion fallback** (service-side dispatch of
  `PipelineDone` when no IME callback delegate is bound): deliberately NOT
  built in this iteration — see §6 Known Limitations. Recreating the IME's
  completion logic service-side is the largest open architecture question and
  carries drift risk; the DB-persistence + recovery replay path already covers
  the data.

### 1.4 What this buys us

1. "Dictation" appears as a second app entry in every launcher-activity
   picker (Air Command, Edge panel, side-key, Routines, Tasker).
2. A pinnable static shortcut and a QS tile with zero duplicated start code.
3. One canonical internal entry (`ACTION_START_DICTATION`) that automation
   tools can also fire directly at the exported trampoline.

## 2. Acceptance Criteria

1. `StartDictationActivity` exists, is exported, uses `Theme.NoDisplay`,
   `taskAffinity=""`, `excludeFromRecents`, `noHistory`, and finishes in
   `onCreate`.
2. An `activity-alias` `.core.StartDictation` with `MAIN`+`LAUNCHER`, its own
   localized label (en/de/es/pt) and adaptive mic icon targets the trampoline;
   the main Settings launcher entry is unchanged.
3. `res/xml/shortcuts.xml` (plus the `src/debug` override with the literal
   debug applicationId) defines the pinnable "Dictation" shortcut;
   `DictationTileService` is declared with `BIND_QUICK_SETTINGS_TILE` and the
   `QS_TILE` intent-filter.
4. All three triggers converge on
   `PipelineActionRouter.ACTION_START_DICTATION`; the router arm calls the
   injected hook; the service hook refreshes the overlay-permission axis and
   dispatches `resolveExternalDictationStart(state, services)` in order.
5. The policy's behaviour table (§3.2) is pinned by JVM unit tests
   (`ExternalDictationStartPolicyTest`), the router arm + redelivery guard by
   `PipelineActionRouterTest`, the permission gate by
   `StartDictationLaunchPolicyTest`; `./gradlew testDebugUnitTest` passes.
6. A `START_FLAG_REDELIVERY` re-delivery of `ACTION_START_DICTATION` is
   suppressed (no spontaneous mic arm after an OOM-kill).
7. Both build variants compile (`assembleDebug`, `assembleRelease`).

## 3. Architecture Specification

### 3.1 Entry chain

```
S-Pen / Edge / side-key          long-press shortcut          QS tile
 (launcher alias, MAIN+LAUNCHER)  (res/xml/shortcuts.xml)      (DictationTileService)
        │                               │                          │ startActivityAndCollapse
        └───────────────┬───────────────┘──────────────────────────┘
                        ↓ launches
┌─────────────────────────────────────────────────────────────────────┐
│  StartDictationActivity (invisible, Theme.NoDisplay)                │
│  decideStartDictationLaunch(hasMic, hasOverlay):                    │
│    mic missing     → toast + DictateSettingsActivity (auto-request) │
│    overlay missing → OverlayPermissionOnboardingActivity            │
│    both granted    → startForegroundService(ACTION_START_DICTATION) │
└─────────────────────────────────────────────────────────────────────┘
                        ↓ onStartCommand (app is foreground ⇒ mic-FGS legal)
┌─────────────────────────────────────────────────────────────────────┐
│  DictatePipelineService                                             │
│  PipelineActionRouter.dispatch(intent, flags)                       │
│    → onExternalDictationStart hook (posted behind startForeground)  │
│      1. OverlayPermissionObserver.refresh()   (axis re-sync)        │
│      2. resolveExternalDictationStart(state, services)              │
│      3. orchestrator.dispatch(each action, in order)                │
└─────────────────────────────────────────────────────────────────────┘
                        ↓ existing machinery, unchanged
   widget Visible (OverlayBackend attach) + RecordingModule StartRecording
```

### 3.2 Start policy behaviour table

`resolveExternalDictationStart` (in
`app/src/main/java/net/devemperor/dictate/state/ExternalDictationStartPolicy.kt`):

| recording            | widget  | viewMode   | emitted actions                                      |
|----------------------|---------|------------|------------------------------------------------------|
| Idle                 | Hidden  | KEYBOARD   | `ToggleViewModeWidget`, `StartRecording`             |
| Idle                 | Hidden  | not KEYB.  | `ResetSuppressBit`, `ToggleWidget`, `StartRecording` |
| Idle                 | Visible | any        | `StartRecording`                                     |
| Active/Paused/Prep./Interrupted | Hidden | (as above) | widget-surfacing actions only            |
| Active/Paused/Prep./Interrupted | Visible | any | *nothing* (full no-op)                          |

Key properties:

- **Canonical widget open.** From KEYBOARD the policy emits the same action
  the edit-bar widget toggle emits (`ToggleViewModeWidget`, permission-gated
  in `ViewModeModule`, full T1 cascade). Outside KEYBOARD it uses the direct
  `ResetSuppressBit` + `WidgetAction.ToggleWidget` pair (T1 has no arm there;
  from WIDGET the toggle would *close*).
- **Shared start body.** The recording action comes from
  `resolveStartRecordingFromIdle` (made `internal` for this purpose) — same
  allocation, UUID mint, B2 auto-continuation lookup, and IOException→toast
  side-channel as the keyboard/overlay record buttons.
- **ADR-0009 honoured for free.** A live pipeline does not block the start;
  the new run queues behind the active one (identical to the keyboard's
  secondary record button).

### 3.3 Hardening details

- **Redelivery guard:** the service returns `START_REDELIVER_INTENT`; the
  router suppresses a redelivered `ACTION_START_DICTATION` so an OOM-killed
  service does not spontaneously re-arm the microphone. Crash recovery of an
  interrupted recording is `PipelineRecovery`'s job.
- **FGS budget:** the external-start hook is posted (non-immediate main
  dispatcher) behind `startForegroundCompat`, so the policy's file IO never
  eats into the FGS 5-second window and the mic-FGS anchor exists before the
  MediaRecorder allocation cascade.
- **Permission-axis refresh:** `overlay.hasPermission` is normally refreshed
  only by IME lifecycle hooks; the hook calls
  `OverlayPermissionObserver.refresh()` first so the `ToggleViewModeWidget`
  reducer gate never rejects on a stale axis.
- **shortcuts.xml literal packages:** `android:targetPackage` is parsed by
  `system_server` against *system* resources — `@string` refs and manifest
  placeholders do not resolve. The debug variant (`applicationIdSuffix
  ".debug"`) therefore carries its own `src/debug/res/xml/shortcuts.xml`.

## 4. Edge-case behaviour

| Trigger situation | Behaviour | Why |
|---|---|---|
| Recording already running | No second start; widget is surfaced (or nothing if already visible) — live controls appear | Single-MediaRecorder invariant; same gate as `resolveSecondaryRecordAction` |
| Pipeline processing | New recording starts and queues behind the active run | ADR-0009 run-queue; parity with the keyboard secondary record button |
| Keyboard currently open | Widget opens over the keyboard + recording starts — exactly as if the user tapped the edit-bar widget toggle and then record | Uniform trigger semantics; nothing IME-side is touched |
| Interrupted recording surfaced (post-crash) | No start; widget shows the continue/discard controls | Continue-vs-discard is the user's explicit choice |
| Mic permission missing | Toast + `DictateSettingsActivity` (auto-requests RECORD_AUDIO) | No silent failure; settings is the established degrade path |
| Overlay permission missing | `OverlayPermissionOnboardingActivity` (explainer + grant button) | Without the widget, a started recording would have no visible control surface except the notification — refuse rather than hot-mic silently |
| Recovery race (trigger lands while `PipelineRecovery` runs) | Start-first: recovery Phase 5 is a documented no-op. Recovery-first: state is `Interrupted` → widget-surfacing only | Both orders converge on safe behaviour |
| OOM-kill + intent redelivery | Suppressed (§3.3) | No spontaneous mic arm |

## 5. Testing Approach

- **Unit (JVM):**
  `app/src/test/java/net/devemperor/dictate/state/ExternalDictationStartPolicyTest.kt`
  (behaviour table, 10 cases),
  `app/src/test/java/net/devemperor/dictate/core/PipelineActionRouterTest.kt`
  (Robolectric — real `Intent`; new arm, redelivery guard, legacy-arm pins),
  `app/src/test/java/net/devemperor/dictate/core/StartDictationLaunchPolicyTest.kt`
  (permission gate). All written red-first.
- **Manual (device):** trigger via alias from the launcher / Air Command with
  (a) idle state, (b) recording live, (c) keyboard open, (d) overlay
  permission revoked; QS tile from locked + unlocked; pin the shortcut.

## 6. Known Limitations / Information Gaps

1. **Headless pipeline completion.** ✅ **RESOLVED 2026-07-12 — see ADR-0011.**
   Pipeline UI-completion callbacks (`PipelineCallbackBridge`) used to be
   delegated to the IME and *dropped* while no IME had bound in the current
   process (fresh boot → external trigger → keyboard never opened), leaving
   `state.pipeline` stuck in `Running` while the DB row was already
   `COMPLETED`. ADR-0011 adds a service-side headless fallback: the two
   *terminal* callbacks (`onPipelineCompleted` / `onPipelineError`) now
   dispatch `PipelineDone(committed=false)` / `PipelineFailed` themselves when
   no delegate is bound, guarded by a process-wide
   `PipelineTerminalDispatchGuard` so exactly one terminal dispatch fires per
   session (across delegate-delivery, headless fallback, and the
   bind-reconciliation safety net). `committed=false` keeps text commit
   IME-exclusive — the transcript surfaces as a "Tap to paste" pending part.
   New classes: `core/PipelineTerminalDispatchGuard.kt`,
   `PipelineCallbackBridge.setHeadlessTerminalSink(...)`, wired in
   `DictatePipelineService.onCreate`.
2. **Samsung picker dedup.** Some Samsung picker builds may deduplicate
   launcher entries per package (alias hidden). **Owner:** on-device
   verification by the user; **fallback:** the QS tile + shortcut still work,
   and Tasker/Routines can fire the exported intent directly.
3. **`imeViewVisible` boot default `true`.** On a cold service start without
   any IME lifecycle event the axis is stale-true, so the overlay record
   button acts as "send" while Active even though no keyboard is up; the send
   then follows the deferred-insertion path. Pre-existing axis semantics, not
   changed by this feature.

## Decision Log

### D1 — Trampoline activity as the single funnel

**Trigger:** design session 2026-07-09. **Decision:** all OS triggers launch
`StartDictationActivity`; only it fires `ACTION_START_DICTATION`.
**Rationale:** one foreground window for mic-FGS legality, one permission
gate, one degradation path. **Alternatives:** direct service start from
tile/shortcut — rejected (§1.3).

### D2 — Overlay permission is required, not best-effort

**Trigger:** "overlay permission missing" edge case. **Decision:** refuse to
start recording; route to the onboarding explainer. **Rationale:** without
the widget the only stop control is the FGS notification — an easy way to
leave the mic hot unnoticed. **Alternatives:** start anyway with
notification-only controls — rejected as a silent-hot-mic risk.

### D3 — No headless completion fallback in this iteration

**Trigger:** infra research finding that `PipelineCallbackBridge` drops
callbacks with no IME delegate. **Decision:** document as known limitation
(§6.1) instead of building a service-side completion path. **Rationale:**
the IME completion handler is the most intricate code in the app; a partial
duplicate risks drift, and DB-persistence + recovery replay already cover
the data. **Alternatives:** fallback delegate dispatching state actions
service-side — deferred to a dedicated follow-up.

**Follow-up (2026-07-12) — implemented, see ADR-0011.** The deferred
service-side fallback was built. The D3 drift fear is sidestepped: the
fallback does NOT recreate the IME completion handler (no headless text
commit) — it dispatches `PipelineDone(committed=false)`, reusing the existing
deferred-insertion path so the transcript surfaces as a "Tap to paste" pending
part. A process-wide `PipelineTerminalDispatchGuard` keeps the headless
fallback, the IME delegate-delivery, and the bind-reconciliation mutually
exclusive per session (exactly one terminal dispatch). This note does not
rewrite D3's original "deferred" decision — it records that the follow-up it
named has since landed.

## References

- `docs/decisions/0003-service-foreground-pipeline-architecture.md` — FGS pipeline host
- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md` — viewMode FSM, T1/T2 cascades
- `docs/decisions/0008-ui-surface-axes-widget-state-and-ime-view.md` — widget axis (attach source of truth)
- `docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md` — run queue + deferred insertion
- `docs/decisions/0011-pipeline-headless-completion-fallback.md` — resolves the §6.1 headless-completion limitation (D3 follow-up)
- `docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md` — secondary-recording spec
- Code: `state/ExternalDictationStartPolicy.kt`, `core/StartDictationActivity.kt`,
  `core/StartDictationLaunchPolicy.kt`, `core/DictationTileService.kt`,
  `core/PipelineActionRouter.kt`, `core/DictatePipelineService.kt`
  (`handleExternalDictationStart`), `app/src/main/AndroidManifest.xml`,
  `res/xml/shortcuts.xml` (+ `src/debug` override)
