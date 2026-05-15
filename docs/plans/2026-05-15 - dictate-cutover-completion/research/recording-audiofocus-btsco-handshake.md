# Research — Recording AudioFocus + Bluetooth-SCO Handshake (new orchestrator path)

**Date:** 2026-05-15
**Triggered by:** C6-IMPL-1 (gate-RED-blocking), C5-IMPL-1, C6-IMPL-2 (C7-scoping)
**Block:** B2 — Theme B Recording-Drive Cutover
**Agent-ID:** B2-C6-RES-1 → B2-C6-REPAIR-1
**Repair-wave:** B2-C6-W1

---

## Sources

1. **Spec 1 (SoT)** — `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md`
   - §15.1 Modul-Übersicht, row 3 (AudioModule cross-module observer): *"ja (AudioFocus-Loss → Recording.Pause; **Recording.Preparing → AudioFocus-Request**)"*
   - §15.1.x Cross-Module-Coupling-Matrix — row `Audio`: `Audio × Recording = R(state.audio.audioFocusGranted) C(RecordingAction.PauseRecording)`
   - §15.2 RecordingModule reference reducer (no audio-focus / no SCO in the recording reducer)
   - §15.3 AudioModule reference + the **Phase-B S-4 KDoc note** that removed the `if (Idle → Preparing) { ... }` observer arm
   - §15.5 Cross-Module-Effect-Modi (Mode 1 / Mode 2; Mode 3 forbidden)
2. **ADR-0002** — `docs/decisions/0002-state-cross-module-cascade.md` — Mode-1 (own SideEffect) / Mode-2 (Action-Cascade via `onCrossModuleStateChange`) allowed; Mode-3 (atomic cross-axis update) forbidden in Phase 1. Self-cascade allowed.
3. **Legacy reference behaviour** — `core/RecordingStateController.kt` (`startRecording:128-140`, `proceedStartRecording:325-336` `gate.request()`, `stopRecording:145-159`, `togglePause:164-180`, `cancelRecording:217-225`, `onScoConnected:300-309`, `onScoFailed:317-321`) + `core/BluetoothScoManager.kt` (`startSco:121-141`, timeout→`onScoFailed`).
4. **Existing wiring (already in place, no change needed)** — `core/DictatePipelineService.kt:331-380` (RecordingHardwareAdapter, AudioFocusSubsystemAdapter, BluetoothScoManager callback → `OnBluetoothScoStateChanged`), `:858-881` (AudioFocus listener → `OnAudioFocusGrantChanged`). `AudioModule.Effect.RequestAudioFocus/ReleaseAudioFocus/StartBluetoothSco/StopBluetoothSco` + `runEffect` arms (`AudioModule.kt:50-94`) + their adapters all exist; only the **emission** is missing.

## Findings

### The gate-flagged gap (consensus, conclusive from code-trace)

`RecordingHardwareAdapter.allocate` (`:54-92`) only `setAudioSource(...)` + `prepare()`s. It does **not** request audio-focus and does **not** start SCO. `RecordingModule.reduce(StartRecording→Preparing)` emits only `Effect.AllocateMediaRecorder`; `Preparing→Active` emits MediaRecorder/Timer/Amplitude/Glow/Notification; no Audio effect anywhere. `AudioModule` has the four Audio effects + `runEffect` arms but **no reducer arm and no observer** emits `RequestAudioFocus`/`StartBluetoothSco`. The `AudioModule.kt:28-33` KDoc claims the adapter handles it — provably false (stale Phase-B S-4 dormant-layer comment).

Legacy did both for ~100% of users (`Pref.AudioFocus` default `true`; SCO only for the BT-mic opt-in subset but for them it genuinely routes the headset mic). C7 deletes the legacy fallback → shipped regression unless closed first.

### Spec-faithful mechanism — what the spec actually prescribes

Two spec statements appear to conflict:

- **§15.1 row 3** explicitly lists the AudioModule observer arm **`Recording.Preparing → AudioFocus-Request`**.
- **§15.3 Phase-B S-4 KDoc** *removed* that observer arm, asserting audio-focus "läuft als Effect direkt im RecordingModule beim Preparing-Übergang (Effect.AllocateMediaRecorder kapselt das im RecordingHardwareSubsystem.allocate-Pfad)".

The §15.3 note's premise is **factually wrong against the shipped adapter** — `allocate` does not touch audio-focus. The note's *stated rationale* ("AudioFocus-Lifecycle in zwei Modulen verteilt — SRP-Verstoß") is sound and is the binding constraint: **audio-focus lifecycle must live in exactly one module, AudioModule (it owns the `audio` axis).** The §15.1 observer arm is the spec-faithful realisation of that constraint via **ADR-0002 Mode 2** (RecordingModule transition observed by AudioModule's `onCrossModuleStateChange`) **+ Mode 1** (AudioModule's own reducer emits its own `Effect.RequestAudioFocus`). No cross-axis write → no Mode-3. This restores exactly what §15.1 prescribes and discards only the §15.3 false-premise paragraph (a stale dormant-layer comment, same class of error the gate flagged in the KDoc).

**Decision: restore the AudioModule cross-module observer (§15.1 row 3), Mode-2 cascade → Mode-1 effect. No new module, no Mode-3, no RecordingModule audio-focus ownership.**

### Cascade mechanics (verified against the orchestrator)

`DictateOrchestrator.dispatchInternal` Step 5-6 (`:353-379`): after a state write, `registry.all.flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }` is dispatched recursively at `depth+1` (frozen snapshot, `MAX_CASCADE_DEPTH=8`). So when `RecordingModule` transitions `Idle→Preparing`, AudioModule's observer sees the `(prev,next)` tuple and can cascade an `AudioAction`; that action routes to `AudioModule.reduce`, which emits the Audio effect in its own `runEffect`. Self-cascade is irrelevant here (different module owns the observer) — pure Mode-2. Real depth: `StartRecording`(d0) → AudioModule observer cascade `RequestAudioFocus-action`(d1). Well within the cap.

A direct `AudioAction` carrying intent is needed because `onCrossModuleStateChange` may only return Actions (Pure-Function-Vertrag §15.3). New action: `Action.AudioAction.OnRecordingActiveChanged(active: Boolean)` is rejected — it duplicates state already derivable. Instead the observer reads the `(prev,next)` recording transition directly and cascades **existing-shaped** intent actions. Two new `AudioAction` leaves are the minimal spec-faithful surface:

- `Action.AudioAction.RecordingStarted` — observer fires it on `Idle→Preparing`; AudioModule reducer emits `RequestAudioFocus` (gated on `audioFocusEnabledPref`) and, if `useBluetoothMic`, `StartBluetoothSco`.
- `Action.AudioAction.RecordingEnded` — observer fires it on `(Active|Paused|Preparing)→Idle` and on `Active→Paused`; reducer emits `ReleaseAudioFocus` + `StopBluetoothSco`.

These are AudioModule-owned actions (audio axis) — SRP-clean.

### Audio-focus timing parity with legacy

| Legacy site | Behaviour | New-path equivalent |
|---|---|---|
| `proceedStartRecording:326` `if (audioFocusEnabled) gate.request()` | request before recorder start, gated on `Pref.AudioFocus` | observer: `Idle→Preparing` ⇒ cascade `RecordingStarted` ⇒ reducer emits `RequestAudioFocus` iff `state.audioFocusEnabledPref` |
| `stopRecording:150` / `cancelRecording:221` `if (audioFocusEnabled) gate.abandon()` | abandon on stop/cancel | observer: `*→Idle` ⇒ cascade `RecordingEnded` ⇒ reducer emits `ReleaseAudioFocus` |
| `togglePause:168` Active→Paused `gate.abandon()` | abandon on pause | observer: `Active→Paused` ⇒ `RecordingEnded` ⇒ `ReleaseAudioFocus` |
| `togglePause:172` Paused→Active `gate.request()` | re-request on resume | observer: `Paused→Active` ⇒ `RecordingStarted` ⇒ `RequestAudioFocus` |

`audioFocusEnabledPref` already mirrors `Pref.AudioFocus` (default `true`, `DictateUiState.kt:322` + PrefMirror §4.5 `Pref.AudioFocus.key` binding). Gating in the **reducer** (pure, reads `state.audioFocusEnabledPref`) keeps the request gated exactly like legacy's `audioFocusEnabled` field, default-on for 100% of users.

### Bluetooth-SCO Preparing handshake — the 🟡 part

Legacy: `startRecording` — if `useBluetooth && bluetoothScoManager.isBluetoothAvailable`, set `Preparing(useBluetooth=true)` + `startSco(2500)`; **wait**; `onScoConnected` → `proceedStartRecording(VOICE_COMMUNICATION)`, `onScoFailed`/timeout → `proceedStartRecording(MIC)`. I.e. the recorder source is decided **after** the SCO outcome, not at start.

The new `RecordingModule` decides `useBluetooth` at `Idle→Preparing` (`ctx.global.audio.useBluetoothMic`) and immediately emits `AllocateMediaRecorder(useBluetooth=…)`. There is **no SCO-wait** — the gate's silent-quality-loss finding (allocate `VOICE_COMMUNICATION` with no live SCO records phone mic).

Spec §15.2/§15.3 do **not** specify a SCO-wait sub-FSM (the legacy callback machine was never ported). A full Preparing-sub-FSM that defers `allocate()` until `bluetoothSco.phase==Connected` would be a substantial RecordingModule reducer rewrite (new intermediate states, MediaRecorderReady re-sequencing, failure-arm widening) — high blast radius, weak spec grounding, fragile under the gate's "proven not assumed" bar.

**Chosen SCO design (spec-faithful, minimal, sustainable):** keep the SCO route owned by AudioModule (Mode-2 cascade + Mode-1 effect, mirroring audio-focus) and gate the **recorder source** on SCO-ready inside RecordingModule's existing `Preparing→Active` arm — *not* a new sub-FSM:

1. `Idle→Preparing` observer (AudioModule) cascades `RecordingStarted`. If `useBluetoothMic`, reducer emits `StartBluetoothSco` (alongside `RequestAudioFocus`). `BluetoothScoManager.startSco` already has its own 2500 ms timeout → emits `OnBluetoothScoStateChanged(Failed)` on timeout (existing wiring, `BluetoothScoManager.kt:131-138` + `DictatePipelineService.kt:365-372`). **No new timer needed** — the subsystem owns the timeout, mirroring legacy exactly.
2. `RecordingModule.reduce(Idle, StartRecording)` no longer hard-codes `useBluetooth = ctx.global.audio.useBluetoothMic` into `AllocateMediaRecorder`. Instead `Preparing` is entered **without** allocating yet when `useBluetoothMic` is true; allocation waits for the SCO outcome.
3. The SCO outcome arrives as `OnBluetoothScoStateChanged` → `AudioModule.reduce` updates `audio.bluetoothSco.phase`. AudioModule's observer then cascades a new `Action.RecordingAction.ScoRouteResolved(useBluetooth: Boolean)` **only while `recording is Preparing` and no allocate has happened yet** (`Connected → useBluetooth=true`, `Failed → useBluetooth=false`). RecordingModule's `Preparing` arm handles `ScoRouteResolved` by emitting `AllocateMediaRecorder(useBluetooth=resolved)`.
4. Non-BT path (`useBluetoothMic=false`) is unchanged — `Idle→Preparing` emits `AllocateMediaRecorder(useBluetooth=false)` immediately (no SCO wait), exactly as today.

This is **one new RecordingAction + one new Preparing reducer arm + a `pendingScoAllocate` discriminator on `RecordingState.Preparing`** — bounded, no new FSM states, reuses the subsystem's own timeout. It is a genuine new state edge (the SCO-gated allocate), documented as a **D22 deviation** (the spec did not port the legacy SCO-wait; this is the spec-faithful minimal realisation of legacy parity within ADR-0002 Mode-1/2).

> **Refinement adopted during implementation (see Implementation Hints §SCO):** rather than a separate `pendingScoAllocate` Boolean *and* a `ScoRouteResolved` action, the simpler equivalent is: `Preparing` carries `awaitingSco: Boolean`. `Idle→Preparing` with `useBluetoothMic` sets `awaitingSco=true` and emits **no** allocate. `OnBluetoothScoStateChanged` arrives on the Audio axis; AudioModule's observer cascades `Action.RecordingAction.MediaRecorderReady`-sibling — no, that conflates. Final shape used: a dedicated `Action.RecordingAction.ScoRouteResolved(useBluetooth)` consumed only by `Preparing` when `awaitingSco`. See the block-report Gate-Repair subsection for the as-built reducer.

### Failure / timeout / edge matrix (SCO)

| Edge | Source | Result |
|---|---|---|
| SCO connects within 2500 ms | `BluetoothScoManager` broadcast → `OnBluetoothScoStateChanged(Connected)` | observer cascades `ScoRouteResolved(true)` → `AllocateMediaRecorder(useBluetooth=true)` → `VOICE_COMMUNICATION` |
| SCO timeout (2500 ms, subsystem-owned) | `BluetoothScoManager` timeoutRunnable → `onScoFailed` → `OnBluetoothScoStateChanged(Failed)` | observer cascades `ScoRouteResolved(false)` → `AllocateMediaRecorder(useBluetooth=false)` → `MIC` fallback (mirrors legacy `onScoFailed→MIC`) |
| SCO unavailable (no BT device) | legacy gated via `isBluetoothAvailable`; new path: `useBluetoothMic` pref true but no device ⇒ `startSco` still posts timeout ⇒ `Failed` ⇒ MIC fallback | safe degrade to MIC (same end-state as legacy `isBluetoothAvailable==false` → `proceedStartRecording(MIC)`) |
| CancelRecording while `awaitingSco` (Preparing, no recorder yet) | existing `Preparing+CancelRecording` arm | `ReleaseMediaRecorder`(no-op) + `DeleteAudioFile` + `DismissNotification`; plus `RecordingEnded` observer ⇒ `StopBluetoothSco`+`ReleaseAudioFocus` |
| `useBluetoothMic=false` | — | unchanged: immediate `AllocateMediaRecorder(useBluetooth=false)`, no SCO, no audio-focus regression (focus still requested via `RecordingStarted`) |

## Implementation Hints

### Audio-focus (Part 1 — near-mechanical)

- **`Action.kt`**: add `data object RecordingStarted : AudioAction()` and `data object RecordingEnded : AudioAction()`.
- **`AudioModule.reduce`**: add arms — `RecordingStarted` ⇒ `TransitionResult(state, listOf(Effect.RequestAudioFocus).takeIf{state.audioFocusEnabledPref}.orEmpty() + listOf(Effect.StartBluetoothSco).takeIf{state.useBluetoothMic}.orEmpty())`; `RecordingEnded` ⇒ `TransitionResult(state, listOf(Effect.ReleaseAudioFocus, Effect.StopBluetoothSco))`. State unchanged (effects only) — return the same `state` instance is acceptable here because the effect *is* the point; the reducer-null contract is about "action not relevant", which is not the case.
- **`AudioModule.onCrossModuleStateChange`**: extend the existing hook. Compute recording transitions from `(prev.recording, next.recording)`:
  - `prev is Idle && next is Preparing` → cascade `RecordingStarted`
  - `prev is Paused && next is Active` → cascade `RecordingStarted` (resume re-acquires focus, legacy `togglePause:172`)
  - `prev is Active && next is Paused` → cascade `RecordingEnded` (pause abandons focus, legacy `togglePause:168`)
  - `prev.isActiveOrPaused or prev is Preparing` && `next is Idle` → cascade `RecordingEnded`
  - Keep the existing AudioFocus-loss → `RecordingAction.PauseRecording` cascade unchanged. Cascade list order: focus-loss-pause first if both fire (they won't in the same tuple).
- **`AudioModule.kt:28-33` KDoc**: replace the stale "AudioFocus is requested as part of `RecordingModule.Effect.AllocateMediaRecorder` — the subsystem adapter takes care of it" with the real path: *"AudioFocus + BluetoothSco are emitted by this module's reducer (`RequestAudioFocus`/`StartBluetoothSco`/`ReleaseAudioFocus`/`StopBluetoothSco`) in reaction to RecordingModule FSM transitions, observed via `onCrossModuleStateChange` (ADR-0002 Mode-2 cascade → Mode-1 effect). Restores Spec 1 §15.1 row 3; the Phase-B S-4 'adapter handles it' note was a stale dormant-layer comment — `RecordingHardwareAdapter.allocate` provably does neither."* Add `@see` to this research file + the block-report.
- Update Spec 1 §15.1.x coupling-matrix `Audio` row: add `R(state.recording)` (Audio now observes the recording axis) and the new `C(RecordingAction.ScoRouteResolved)` cell. SSoT — the matrix is the canonical doc; a new observer-read without a matrix entry is a code-review violation (§15.1.x SRP-Konsequenz). *(Spec is in the parent-plan dir; this is a doc-deviation note — record in block-report Deviations; do not block on a spec edit if out of worktree scope, but the worktree copy at `docs/plans/2026-05-07 .../1-pipeline-service.reviewed.md` IS editable here.)*

### Bluetooth-SCO (Part 2 — the researched edge)

- **`DictateUiState.RecordingState.Preparing`**: add `val awaitingSco: Boolean = false`. Default keeps every existing constructor call + test green (data-class default arg).
- **`Action.RecordingAction`**: add `data class ScoRouteResolved(val useBluetooth: Boolean) : RecordingAction()`.
- **`RecordingModule.reduce(Idle, StartRecording)`**: if `ctx.global.audio.useBluetoothMic` → `Preparing(useBluetooth=true, …, awaitingSco=true)` with **empty** sideEffects (no allocate yet). Else current behaviour (`Preparing(useBluetooth=false, awaitingSco=false)` + `AllocateMediaRecorder(false)`).
- **`RecordingModule.reduce(Preparing, ScoRouteResolved)`**: only when `state.awaitingSco` → `Preparing(useBluetooth=action.useBluetooth, …, awaitingSco=false)` + `listOf(AllocateMediaRecorder(target?, action.useBluetooth, state.audioFile))`. The `target` is not in `Preparing` today — carry it: add `val target: InsertionTarget` to `Preparing` (default not possible — it's required for the deferred allocate). *Mitigation:* `Preparing` already needs `target` only when `awaitingSco`; add it as a nullable `val target: InsertionTarget? = null`, non-null exactly when `awaitingSco`. Reducer asserts non-null on the deferred-allocate arm. Document the nullable as a deviation rationale.
- **`AudioModule.onCrossModuleStateChange`**: when `next.recording is Preparing && (next.recording as Preparing).awaitingSco` and the SCO phase just resolved (`prev.audio.bluetoothSco.phase != next.audio.bluetoothSco.phase` and `next` phase ∈ {Connected, Failed}) → cascade `Action.RecordingAction.ScoRouteResolved(useBluetooth = next.audio.bluetoothSco.phase == ScoPhase.Connected)`. Guard against re-fire: only when `prev` was `Waiting`/`Disconnected`.
- No new timer: `BluetoothScoManager.startSco(2500)` already posts its own timeout → `OnScoFailed` → `OnBluetoothScoStateChanged(Failed, "sco-timeout")` (verified `DictatePipelineService.kt:365-372`). The `StartBluetoothSco` effect calls `services.bluetoothSco.start()` → `BluetoothScoSubsystemAdapter.start()` → `manager.startSco()` (default 2500 ms). Parity with legacy exactly.

### C6-IMPL-2 (doc-note only, no code)

Block-report already carries the C7 carve-out note (`### Chunk C7-B3` blockquote, lines ~1056-1063) — confirm/leave intact; reconcile Issue Index `C6-IMPL-2 → documented (C7-scoping)`.

## Findings (Update 2026-05-15 — repair self-check)

**Engagement-edge detection (non-obvious correctness fix).** The first
implementation detected recording-start as the named transition
`prev.recording is Idle && next.recording is Preparing`. This silently
dropped the audio-focus request on the **live** path while passing
every pure-reducer/observer unit test. Root cause: with
`Dispatchers.Main.immediate`, the `AllocateMediaRecorder` effect's
`RecordingHardwareAdapter.allocate` synchronously calls
`emitAction(MediaRecorderReady)`, and `emitAction` = `scope.launch`
on `Main.immediate` **executes synchronously when already on the main
thread**. So `MediaRecorderReady` re-enters the dispatch loop *inside*
Step 4 of the `StartRecording` dispatch, advancing the store
`Idle → Preparing → Active` **before** Step 5 captures the observer's
frozen `nextGlobal`. The observer therefore sees `Idle → Active`
(Preparing skipped), and a single-named-transition predicate misses
it. **Fix:** detect on the *engagement edge* — `!prev.isEngaged() &&
next.isEngaged()` where `isEngaged = isActiveOrPaused || is Preparing`
— which is robust to the collapsed tuple and still excludes the
`Preparing → Active` (already-engaged) non-start edge. The
`Paused → Active` resume edge is handled as an explicit second clause
(prev is engaged, so the engagement-edge clause cannot fire it).
Caught only by the new `DictateCutoverE2ETest` shadow-AudioManager
assertion — a concrete reason the E2E (Robolectric, K-4 justified
opt-out) earns its place on top of the pure-reducer coverage. This
re-entrancy is itself an ADR-0001 §"Main-Thread Confined Dispatch"
consequence; documented here so a future observer author does not
re-introduce the named-transition form.

## Findings (Update 2026-05-15 — Block-Validate B2-VAL-W1, F-1 + F-2)

**Triggered by:** F-1 (Critical, blocks-following — BT-SCO already-connected
hang) + F-2 (Important — audio-focus lost during `Preparing(awaitingSco)`
never re-acquired). One topic, one coherent redesign (consolidated by
`B2-VAL-SANITY`). **Agent-ID:** `B2-VAL-RES-1` → `B2-VAL-REPAIR-1`.

This **refines, does not contradict**, the C6-W1 design above. The
`awaitingSco` Preparing-window + the `ScoRouteResolved` edge-trigger + the
engagement-edge detection all stay. Two precise gaps are closed.

### F-1 — root cause (verified against code)

`StopBluetoothSco` → `BluetoothScoManager.release()` (`:147-154`) flips the
internal `_isScoStarted=false` but emits **no**
`OnBluetoothScoStateChanged(Disconnected)` synchronously — the
`SCO_AUDIO_STATE_DISCONNECTED` system broadcast is async and frequently
never arrives in the recording's lifetime (and never in unit/Robolectric
tests). So `audio.bluetoothSco.phase` is left **stale at `Connected`**
from the prior BT session.

Next BT recording: `RecordingStarted` → `StartBluetoothSco` →
`BluetoothScoManager.startSco()`. If `audioManager.isBluetoothScoOn==true`
(realistic: back-to-back BT dictations — the most common BT pattern — or
another app/the system already holds SCO), the early-return at
`BluetoothScoManager.kt:122-126` fires `onScoConnected()` **synchronously**
→ `emitAction(OnBluetoothScoStateChanged(Connected))` and `return true`
**without** arming the `postDelayed(timeoutRunnable, 2500)` (lines 131-138
are unreachable on that branch — no timeout-recovery).

`AudioModule.reduce(OnBluetoothScoStateChanged(Connected))` then hits the
`if (newSco != state.bluetoothSco)` guard (`:104`): the phase is **already
stale-`Connected`** ⇒ `newSco == state.bluetoothSco` ⇒ reducer returns
`null` (Rejected) ⇒ no state write ⇒ `prev == next` ⇒ the observer's
`justResolved = prevPhase != nextPhase` edge (`AudioModule.kt:231`) is
`Connected == Connected` ⇒ `false` ⇒ `ScoRouteResolved` **never
cascaded** ⇒ the deferred `AllocateMediaRecorder` never fires ⇒ recording
silently dead in `Preparing(awaitingSco=true)`, no audio, no error, no
timeout recovery.

### F-1 — design decision: prime the SCO phase to `Waiting` on handshake start (option ii)

Three options were on the table (per the consolidator):
(i) level-trigger the deferred allocate; (ii) synchronously reset/prime
the SCO phase on `StartBluetoothSco` so the connected broadcast is always
a real edge; (iii) a distinct SCO-settled signal.

**Chosen: (ii).** Rationale (D4 — fewest special-cases, most
maintainable, most spec-faithful):

- (iii) adds a new action/signal surface — more special-cases, rejected.
- (i) makes the observer fire `ScoRouteResolved` on *every* dispatch
  cycle while `Preparing && awaitingSco && phase∈{Connected,Failed}`;
  the reducer-arm `awaitingSco` guard idempotently absorbs the
  duplicates, but it turns a clean edge-triggered cascade into a
  level-triggered one — noisier, and it does **not** make the SCO phase
  a coherent state machine (the stale-`Connected`-after-stop hazard
  remains for any *other* future SCO-phase observer).
- (ii) makes `ScoPhase` a genuine state machine:
  `Disconnected → Waiting → (Connected | Failed) → …`. **Every** SCO
  handshake provably starts from `Waiting`, so
  `OnBluetoothScoStateChanged(Connected|Failed)` is *always* a real
  `Waiting → terminal` edge — the existing edge-trigger
  (`prevPhase != nextPhase`) keeps working unmodified, including the
  re-fire / duplicate-broadcast defence. One write, zero new actions,
  zero new FSM states.

**Where the prime happens:** in `AudioModule.reduce` on the
`RecordingStarted` arm, **only when `state.useBluetoothMic`** — the same
reducer arm that already emits `Effect.StartBluetoothSco`. The reducer
writes `bluetoothSco = BluetoothScoPublicState(phase = ScoPhase.Waiting,
failureReason = null)` in the *same* `TransitionResult.nextState`. This
is:

- **Pure** — a state write on the `audio` axis AudioModule owns
  (ADR-0001 pure-reducer clean; no hardware touched in `reduce`).
- **ADR-0002 Mode-1** — AudioModule's own reducer writing its own axis +
  emitting its own effect. No cross-axis write (no Mode-3).
- **Synchronous-before-effect** — the store write completes in dispatch
  Step 4 *before* Step 7 runs `runEffect(StartBluetoothSco)` →
  `services.bluetoothSco.start()` → `manager.startSco()`. So even when
  `startSco()` early-returns and re-enters dispatch synchronously
  (`Main.immediate`) with `OnBluetoothScoStateChanged(Connected)`, the
  phase is *already* `Waiting` ⇒ `Connected != Waiting` ⇒ real state
  write ⇒ observer sees `Waiting → Connected` ⇒ `justResolved` true ⇒
  `ScoRouteResolved(true)` cascades ⇒ recording proceeds. **Hang fixed.**

The `RecordingStarted` arm is also fired (per the C6-W1 observer) on the
`Paused → Active` resume edge — priming to `Waiting` there is correct too
(`BluetoothScoManager.reconnect` re-runs `startSco`, the resume genuinely
re-handshakes; legacy `togglePause` reconnected SCO on resume).

### Edge / failure matrix re-verification (F-1 fix)

| Case | Behaviour with the prime |
|---|---|
| **Already-connected** (the F-1 hang) | phase primed `→ Waiting`; sync `onScoConnected` → `Connected` is a real `Waiting→Connected` edge → `ScoRouteResolved(true)` → `VOICE_COMMUNICATION`. **Fixed.** |
| **Fresh-connect** (≤2500 ms) | phase `Waiting`; broadcast `Connected` is `Waiting→Connected` edge → `ScoRouteResolved(true)`. Unchanged-correct. |
| **Fail / timeout** | phase `Waiting`; subsystem `onScoFailed` → `Failed`, `Waiting→Failed` edge → `ScoRouteResolved(false)` → `MIC`. Unchanged-correct (subsystem-owned 2500 ms timeout on the *not-connected* branch still arms). |
| **Cancel-while-awaiting** | `CancelRecording`: `Preparing→Idle` + `RecordingEnded`→`StopBluetoothSco`. A *late* `OnBluetoothScoStateChanged(Connected)` now **does** write phase (`Waiting→Connected` real edge), but the observer guard `nextRec is Preparing && nextRec.awaitingSco` is `false` (recording is `Idle`) ⇒ `ScoRouteResolved` **not** cascaded. **Stale-resolve-after-cancel still defeated** — the guard is on the *recording* axis, untouched by the phase prime. |
| **Duplicate-resolve** | second `OnBluetoothScoStateChanged(Connected)` while phase already `Connected` ⇒ reducer-null ⇒ no edge ⇒ no re-cascade. Plus the reducer-arm `awaitingSco=false` second-guard. Unchanged-correct. |
| **Stale-resolve-after-cancel** | as Cancel-while-awaiting row — defeated by the unchanged `awaitingSco` recording-axis guard. |
| **SCO held by other app** | `isBluetoothScoOn==true` at start → same as already-connected row → resolves to `VOICE_COMMUNICATION`. (Legacy parity: legacy also `proceedStartRecording(VOICE_COMMUNICATION)` on the synchronous already-connected `onScoConnected`.) |

### F-2 — root cause + coherent fix (same Preparing redesign)

Audio-focus is requested on the `Idle→Preparing` engagement edge
(`RecordingStarted` → `RequestAudioFocus`). The F-2 gap: if focus is
**lost during the `Preparing(awaitingSco)` SCO wait**, (a) the
focus-loss→pause cascade is gated on `next.recording.isActiveOrPaused`
which excludes `Preparing` (correct — there is no recorder to pause yet,
and `PauseRecording` has no `Preparing` arm), and (b) the later
`Preparing→Active` is engaged→engaged so `RecordingStarted` does not
re-fire ⇒ the recording goes Active having lost focus, other apps duck
over it. **Legacy did not have this window** — legacy requested focus
in `proceedStartRecording`, i.e. *after* the SCO wait, right before
`MediaRecorder.start()`.

**Coherent fix (same observer, same Preparing redesign):** cascade
`RecordingStarted` **again** on the SCO-wait-resolved edge —
`prev.recording is Preparing && prev.recording.awaitingSco &&
next.recording is Preparing && !next.recording.awaitingSco` (the
deferred-allocate transition `ScoRouteResolved` produces). At that point
the reducer re-emits `RequestAudioFocus` (idempotent — `request()`
delegates to `AudioManager.requestAudioFocus`, re-requesting the same
`AudioFocusRequest` is a safe Android no-op / re-grant). This restores
**exact legacy timing**: focus is (re-)asserted after the SCO wait, right
before allocate — so a BT recording can never reach Active without focus
having been requested at the legacy point. The early `Idle→Preparing`
request is kept (harmless, strictly safer than legacy for the non-BT
path and the BT happy path).

This is **one coherent state-machine shape**, not two: the AudioModule
observer gains exactly one extra `RecordingStarted` trigger clause (the
SCO-wait-resolved edge), and the `RecordingStarted` reducer arm gains the
phase-prime write. No new actions, no new FSM states, no Mode-3, no
RecordingModule change, no IME change. SRP intact (focus + SCO lifecycle
stays AudioModule-owned, the `audio`-axis owner — Spec 1 §15.3
constraint). Spec 1 §15.1 row 3 (`Recording.Preparing →
AudioFocus-Request`) is *more* faithfully realised: focus is now tied to
the Preparing→capture boundary on the BT path exactly as the row
prescribes.

### Implementation Hints (F-1 + F-2)

- **`AudioModule.reduce`, `RecordingStarted` arm:** when
  `state.useBluetoothMic`, set
  `nextState = state.copy(bluetoothSco = BluetoothScoPublicState(phase =
  ScoPhase.Waiting, failureReason = null))`; keep the existing effect
  list (`RequestAudioFocus` gated on pref + `StartBluetoothSco`). When
  `!useBluetoothMic`, `nextState = state` unchanged (non-BT path emits
  no SCO effect, must not touch the phase). Update the arm KDoc.
- **`AudioModule.onCrossModuleStateChange`:** add a second
  `RecordingStarted` trigger clause for the SCO-wait-resolved edge:
  `prevRec is Preparing && prevRec.awaitingSco && nextRec is Preparing &&
  !nextRec.awaitingSco`. Order it so the existing engagement-edge clause
  and this one don't both append (they're mutually exclusive — one is
  `!engaged→engaged`, this one is `Preparing→Preparing`). The
  `ScoRouteResolved` cascade clause is unchanged.
- **No `BluetoothScoManager` / `RecordingModule` / IME change** for
  F-1/F-2. The fix is entirely AudioModule-local (axis owner) — minimal
  blast radius, maximal SRP.
- **Tests (extend `AudioModuleTest`):** (1) `RecordingStarted` with
  `useBluetoothMic` primes `bluetoothSco.phase = Waiting` in
  `nextState`; (2) `RecordingStarted` without BT does **not** touch the
  phase; (3) already-connected hang: `prev` phase `Connected` (stale),
  `RecordingStarted` resets to `Waiting`, then a subsequent
  `OnBluetoothScoStateChanged(Connected)` is a real edge → observer
  cascades `ScoRouteResolved(true)` (the end-to-end no-hang proof);
  (4) the genuine-wait timeout path still produces `Failed → MIC`;
  (5) stale-resolve-after-cancel still defeated (cancel → `Idle`, late
  `Connected` writes phase but no `ScoRouteResolved`); (6) F-2: the
  SCO-wait-resolved edge (`Preparing awaitingSco → Preparing
  !awaitingSco`) cascades `RecordingStarted` again (focus re-requested);
  (7) F-2 reducer: that re-fired `RecordingStarted` re-emits
  `RequestAudioFocus`.

## Findings (Update 2026-05-15 — F-4 PipelineActionRouter post-strip re-audit)

**Triggered by:** F-4 routing rider (mandatory post-NUL-strip
logic/plan-conformance re-audit — the binary flag excluded the file from
the grep-based plan-and-api + logic audits).

**The NUL is load-bearing, not stray file-hygiene.** `grep -aPn "\x00"`
locates the single NUL **inside a character literal** on line 133:
`(action + '<NUL>' + sessionId).hashCode()` — a raw NUL char used as the
separator between `action` and `sessionId` when composing the
per-(action,sessionId) PendingIntent request-code. A blind `tr -d '\000'`
would turn `'<NUL>'` into `''` — an **empty char literal, which does not
compile** in Kotlin. The correct byte-clean fix is the explicit Unicode
escape `' '`: same runtime value (NUL separator → identical
`hashCode()`), valid source text, normal git diff. **This corrects the
F-4 "byte-identical content" suggested-fix** — content is *behaviourally*
identical, not byte-identical (the raw NUL becomes a 6-char escape).

**Logic / plan-conformance re-audit outcome (the 2-of-4 unreviewed
topics): CLEAN — no residual bug.**

- `dispatch()` mapping `ACTION_SEND → StopRecordingAndSend`: the §7.5
  spec sketch (`1-pipeline-service.reviewed.md:3999`) wrote
  `ACTION_SEND → StopRecording`; the implementation is the **correct
  FN-4 update** (the sketch predates FN-4 — `StopRecordingAndSend` is the
  payload-less data object whose reducer reads `state.recording`
  sessionId, B1-C2-A2 F-10). Recorded as **intentional supersession**,
  not a bug. The inline KDoc (`:67-71`) already documents this exactly.
- `ACTION_INSERT`/`ACTION_DISMISS` → `ConfirmInsertion(sessionId)` /
  `DismissResult(sessionId)` with `EXTRA_SESSION_ID`: the actions exist
  and decode correctly; no current `NotificationStatus` arm emits the
  Insert/Discard buttons. Confirmed **intentional half-wiring for a
  later result-stage block** (the buttons + their `pendingIntentFor`
  call-sites land when the result-stage notification is wired — out of
  B2 recording-drive scope). The `requestCodeFor` per-session distinct
  request-code (the line carrying the NUL) is *defensive correctness for
  that future*: it prevents a `FLAG_UPDATE_CURRENT` collision silently
  re-targeting `[Einfügen]` for session A onto session B. Not a B2
  defect.
- `pendingIntentFor` → `PendingIntent.getService(... FLAG_IMMUTABLE)` is
  §7.5-faithful (FGS survives keyboard switch; immutable PendingIntent
  mandatory API 31+). `dispatchAction` lambda seam mirrors the
  established `emitAction` adapter pattern (KDoc `:24-32`) — correct,
  unit-testable, no construction-order coupling.

**Conclusion:** PipelineActionRouter.kt is logic- and plan-conformant
once grep-visible. The only change required is the NUL→`' '`
escape (file-hygiene + compileability). No behavioural fix.

## References

- Block-report: `../reports/B2-theme-b-recording-drive.md#chunk-c6-d2pre` (gate verdict + worklist #1/#2) + `#gate-repair-wave-b2-c6-w1` (as-built) + `#block-validate-repair-wave-1-b2-val-repair-1` (F-1..F-9)
- Plan: `../dictate-cutover-completion.md` §4 Block B3, §6.1 R-1
- ADR-0002 §"The two allowed modes", §"Self-cascade", §"Frozen snapshot"
- ADR-0001 §"Main-Thread Confined Dispatch" (the re-entrancy that motivated the engagement-edge fix)
- Spec 1 §15.1 / §15.1.x / §15.2 / §15.3 / §15.5
