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

## References

- Block-report: `../reports/B2-theme-b-recording-drive.md#chunk-c6-d2pre` (gate verdict + worklist #1/#2) + `#gate-repair-wave-b2-c6-w1` (as-built)
- Plan: `../dictate-cutover-completion.md` §4 Block B3, §6.1 R-1
- ADR-0002 §"The two allowed modes", §"Self-cascade", §"Frozen snapshot"
- ADR-0001 §"Main-Thread Confined Dispatch" (the re-entrancy that motivated the engagement-edge fix)
- Spec 1 §15.1 / §15.1.x / §15.2 / §15.3 / §15.5
