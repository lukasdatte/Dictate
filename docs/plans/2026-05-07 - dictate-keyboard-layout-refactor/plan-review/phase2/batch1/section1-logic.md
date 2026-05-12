# Phase 2 — Section 1 Logic Review: "State-Modell + Modul-System"

**Reviewer:** Logic & Edge-Cases
**Spec under review:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.md`
**Sections:** §3 (Datenmodell DictateUiState), §4.1–§4.10 (DictateOrchestrator + Modul-Plugin), §5 (LocalBinder), §15.1–§15.6 (Modul-Inventar)
**Out of scope:** §6–§14 (handled by Section 2)
**Date:** 2026-05-10

---

## Summary

Spec is broadly coherent — the modular orchestrator pattern is well-thought-through and the sub-state decomposition is a clean answer to the 30-field god-object problem. However, the *runtime contract* of `dispatch()` and the cross-module cascade has a number of unresolved logic gaps that will bite the implementer. Most are concentrated around four themes:

1. **Reentrancy / cascade safety** — recursion has no fixed-point guarantee, no cycle detection, no ordering, and no concurrency lock. Phase-1 issue 1.1.5/1.1.7 are real and the spec did not add the corresponding mitigation.
2. **Reducer purity is leaky** — `buildContext` does a hardware read, `runEffect` is `Unit`-returning so failures vanish, and `emitAction` from inside an effect re-enters `dispatch` without contract.
3. **Action routing** is type-class-based (`KClass<A>`) but two corner cases (`Action.NoOp`, sealed sub-sub-classes if any are added) silently fall through and only log at WARN.
4. **Sub-state-combo invariants** — the spec proves SRP per axis, but doesn't define which cross-axis combinations are forbidden (e.g. `recording.Active` + `audio.bluetoothSco.phase = Disconnected` while `useBluetoothMic = true`), and there is no place where they are enforced.

The findings below are sorted Critical → Important → Nice-to-have within each category.

---

## Findings

### Issue L-1: Cascade has no fixed-point / cycle / depth guarantee

- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** §4.3 `DictateOrchestrator.dispatch`, lines 442–478 (esp. step 5+6)
- **Description:** Step 5 collects cascade-actions from *all* observers (one observer per non-acting module), step 6 dispatches each one recursively. Three independent failure modes:
  1. **No cycle detection.** Module A reacts to B's state by emitting `b-action`. B's reducer mutates state again → A's observer fires again → infinite recursion until stack overflow. Realistic example: `AudioModule.onCrossModuleStateChange` (§15.3) emits `RecordingAction.PauseRecording` whenever `audioFocusGranted` flipped to false. A future `RecordingModule` observer that emits `AudioAction.ToggleAudioFocusPref` on a Pause-after-active transition closes the loop. There is no compiler help here and no test will catch it without targeted construction.
  2. **No depth cap.** Even non-cyclic chains (A → B → C → D) recurse synchronously. With 13 modules the spec offers no upper bound, so a misbehaving observer can exhaust the main-thread stack.
  3. **Cascade-snapshot drift.** `prevGlobal`/`nextGlobal` are captured *before* the cascade dispatches run. Each cascade dispatch updates the store, so by the time observer-call N+1 fires its own cascade, the “next” it receives is the *post-N* state, which may already differ from what observer N saw — observers may emit obsolete actions. The spec doesn't decide whether observers should see the *original* `next` or the *current* state, and the code uses `store.snapshot` (current). This is a silent semantic choice.
- **Example scenario:** AudioFocus is lost during recording → AudioModule emits `PauseRecording`. A future `BluetoothModule` observes `recording: Active → Paused` and emits `AudioAction.ToggleAudioFocusPref` (because BT-SCO should release focus on pause). AudioModule's reducer flips a sub-state, its own observer re-evaluates the now-current state vs the original `prev`, sees focus still ungranted, emits `PauseRecording` again. The Recording reducer returns `null` (already Paused — F1 violation), the spec says "log + return". So we're saved by accident, but only because pause→pause is illegal. A different chain that *is* legal in both states (e.g. `LayoutAction.SetContentArea`) would loop forever.
- **Suggestion:**
  - Add a depth counter or a per-dispatch action-class set; refuse to re-dispatch the same `(moduleId, action::class)` within one outer `dispatch`.
  - Document the snapshot semantics explicitly: observers see `prev = pre-trigger`, `next = post-trigger`, **not** the moving state. Re-snapshot per observer is a footgun.
  - Add a hard depth cap (e.g. 8) with a logged abort when exceeded — this is what other reducer-stacks (Redux-toolkit, Compose-MVI) do.
  - Phase-1 1.1.5 is acknowledged in `validated-findings-phase1.md` but the spec text in §4.3 still has no mitigation. Either add an option from phase 1 or call out explicitly that this is deferred.

---

### Issue L-2: `runEffect` is `Unit`-returning — failures are invisible to the orchestrator

- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Critical
- **Location:** §4.2 line 369; §15.2 lines 2361–2378
- **Description:** `fun runEffect(effect: E, services: ModuleServices)` returns `Unit`. There is no `try/catch` around the `forEach { effect -> typedModule.runEffect(effect, services) }` in `dispatch` (§4.3:467–468). Three problems:
  1. **A throwing effect kills the dispatch** — the cascade-section (steps 5+6) is then never reached, so observers don't see the state change they are supposed to compensate for. The state has *already been written* (step 3), so the system is in a half-mutated state. Example: `Effect.AllocateMediaRecorder` throws `IOException` because the mic is busy. State is now `RecordingState.Preparing`, but no timer started, no border-glow, and no rollback. The next `dispatch(StopRecording)` from the user reduces from `Preparing` → fails (only `Active`/`Paused` allow Stop) → null → log → user is stuck in Preparing forever.
  2. **No retry / no error-action.** If `services.audioFocus.request()` fails, there's no mechanism to emit `Action.AudioAction.OnAudioFocusGrantChanged(granted=false)` automatically. Subsystems must do this themselves (callback-into-`emitAction`), but the contract isn't documented.
  3. **The `when`-block in §15.2 returns the effect of the last call** (Kotlin's last-expression rule). E.g. `Effect.ReleaseMediaRecorder -> services.recordingHardware.release()` — if `release()` returns `Boolean` (it likely does on Android `MediaRecorder`-wrappers), the `when` no longer types as `Unit` and the `return = when{}` form silently propagates a return value. Minor, but a refactor-trap.
- **Example scenario:** User taps Record. `dispatch(StartRecording)` → state goes to `Preparing` → `runEffect(AllocateMediaRecorder)` throws because the previous session's `MediaRecorder` wasn't released. Exception bubbles up through `dispatch()` to `KeyboardLayoutManager.onClick` (§5 IME-side, viewScope). State is now `Preparing`, no actual recorder. User taps Stop → `Preparing` rejects `StopRecording` (F1) → no-op. User taps Cancel → `CancelRecording` works, releases `MediaRecorder` (which was never allocated → another exception?), goes to `Idle`. Recovery only works because of the explicit Cancel path.
- **Suggestion:**
  - Wrap the per-effect call in `try { typedModule.runEffect(effect, services) } catch (t: Throwable) { handleEffectFailure(module.id, effect, t) }`.
  - Define a `EffectFailure(moduleId, effect, throwable)` cross-cutting Action that any module can opt-in to react to (or document the policy: "effect failures are logged and swallowed; the state has already moved").
  - Make `runEffect` return `EffectResult` (sealed: `Success | Failure(throwable)`) so failures have a typed channel without exceptions.
  - At minimum: spec the contract — “effects MUST NOT throw; subsystems wrap their own exceptions and emit explicit failure-actions via `services.emitAction`.”

---

### Issue L-3: `emitAction` from EffectHandler reenters `dispatch` synchronously — race + stack risk

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §4.7 line 642 (`emitAction: (Action) -> Unit`), §4.3
- **Description:** `ModuleServices` exposes `emitAction: (Action) -> Unit` so EffectHandlers can fire follow-up actions. The contract isn't defined:
  - **Synchronous re-entry?** If `emitAction(x)` calls `orchestrator.dispatch(x)` directly inside `runEffect`, we are still inside step 4 of the *outer* dispatch. The outer's step 5 (cascade) hasn't run yet. The inner dispatch's `prevGlobal` is the post-step-3 state of the outer, so the cascade-prev/next semantics drift further.
  - **Async via scope?** If `emitAction` posts to a CoroutineScope (`services.scope`), the order of state changes becomes nondeterministic — especially under `Dispatchers.Main.immediate` (§7.1) where things may run on the same tick.
- **Example scenario:** `RecordingModule.runEffect(Effect.AllocateMediaRecorder)` succeeds and the subsystem callback fires `emitAction(MediaRecorderReady)` synchronously inside the same call. Outer dispatch is still in step 4; inner dispatch executes step 1–6, ending up `Active`, runs StartTimer/etc. Outer dispatch continues to step 5: cascade-observers compare `prevGlobal=Idle` against `nextGlobal=store.snapshot` — but snapshot is now `Active` (not `Preparing` as outer step 3 left it). AudioModule's observer sees `Idle → Active` directly and *skips* the AudioFocus-request that should have fired on `Idle → Preparing`. Logic gap, hard to test.
- **Suggestion:**
  - Pick one contract:
    - (a) `emitAction` is **always async** via scope (`scope.launch { dispatch(x) }`) and `dispatch` is documented as not-reentrant. Add `require(!inDispatch.get())` at the top of `dispatch` to enforce.
    - (b) `emitAction` re-enters synchronously, but only **after** the current `dispatch` completes — implement via a queue that the outer `dispatch` drains before returning.
  - Document the chosen semantics in §4.3 and §4.7.
  - Either way, the cascade snapshot must be re-evaluated, see L-1.

---

### Issue L-4: `findModule` falls through to `firstOrNull{isAssignableFrom}` — silent ambiguity

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §4.3 lines 480–485
- **Description:** `findModule` first does an exact `KClass`-lookup (cheap, registry-built map), then falls back to a linear scan with `actionClass.java.isAssignableFrom(action::class.java)`. Three issues:
  1. **Ambiguity is unresolved.** Two modules each declaring `actionClass = Action::class` (or some other parent) — `firstOrNull` picks the *first registered*, silently. The init-time check in `DictateModuleRegistry` (§4.8) only catches *duplicate* `actionClass` references, not *overlapping* class hierarchies. If a module registers `actionClass = Action::class` (e.g. by typo or a fallback module) it would absorb every action.
  2. **Inner `sealed class` extension footgun.** Spec 2 defines `Action.RecordingAction` as a sealed class. If someone later adds `sealed class StaffRecordingAction : Action.RecordingAction()` inside a new module, that new sub-sub-class still routes to RecordingModule via the assignable-from path — the new module won't get its actions. No compile error.
  3. **`Action.NoOp` always falls through.** Phase-1 1.1.4 calls this out. Currently it logs `WARN` "Keine Modul-Zuordnung für $action" — so the log gets spammed every time a slot is unbound. The spec doesn't decide between the three options (drop NoOp, early-return, log-DEBUG); §15.5 is silent.
- **Example scenario:** Developer adds an `AudioBalanceModule` for stereo balance, declares its actions `sealed class AudioBalanceAction : Action.AudioAction()` (because it semantically belongs to audio). `actionClass = Action.AudioBalanceAction::class` — the registry-init check passes. At runtime: a `AudioBalanceAction.SetLeft` action is dispatched. `moduleByActionClass[AudioBalanceAction.SetLeft::class]` misses (the map keys are the sealed bases, not their concrete subclasses). Fallback scan: `AudioModule.actionClass = Action.AudioAction::class`, `AudioAction::class.java.isAssignableFrom(AudioBalanceAction.SetLeft::class.java)` is *true*. AudioModule wins, AudioBalanceModule is dead. Silent.
- **Suggestion:**
  - Drop the assignable-from fallback; require that `moduleByActionClass[action::class]` hits, by indexing **all** sealed leaves at startup. Use Kotlin reflection: walk `Action::class.sealedSubclasses` recursively, validate each leaf maps to exactly one module's `actionClass` chain.
  - At init, **error** (not warn) if any leaf is ambiguous or unrouted.
  - Decide and document the `Action.NoOp` policy per phase-1 1.1.4. The spec under review still leaves this open.

---

### Issue L-5: `buildContext` is not pure — Phase-1 1.1.7 is unmitigated

- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Important
- **Location:** §4.3 lines 487–490
- **Description:** `buildContext(global)` calls `servicesFactory.get().recordingHardware.currentAudioFile()`. This is a synchronous read of mutable hardware state inside the reducer call path. Three problems:
  1. **Reducer purity is broken.** §4.2 line 359 says “Pure function — keine Hardware-Calls, deterministisch.” The orchestrator violates this on every dispatch by calling a hardware getter before invoking `reduce`.
  2. **Race risk.** If the recorder is being released on a different thread (e.g. `Effect.ReleaseMediaRecorder` running async on `Dispatchers.IO`), `currentAudioFile()` may return null *or* a stale file. The reducer then makes the wrong decision (e.g. `RecordingState.Active.CancelRecording` only emits `DeleteAudioFile` if `ctx.recordingAudioFile != null`).
  3. **Test pain.** Every reducer test must inject a fake `recordingHardware`, even though most reducers don't read the file at all. The `ReducerContext` should only contain *plain data*.
- **Example scenario:** User taps Cancel during `RecordingState.Active`. `dispatch(CancelRecording)`. Step before reduce: `buildContext` reads `recordingHardware.currentAudioFile()` → returns `null` because a previous `Effect.StopMediaRecorder` from a *different* dispatch (e.g. an internal Stop-Pipeline effect) just set the field to null. Reducer emits `[StopMediaRecorder, StopTimer, StopBorderGlow, StopAmplitudeStream]` *without* `DeleteAudioFile`. The orphan audio file leaks.
- **Suggestion:**
  - Push the `audioFile` into `RecordingState.Active` itself (it's the only state that has one) — then `ctx.recordingAudioFile` becomes irrelevant; the reducer reads it from the state. The recorder subsystem reports the file via `Action.RecordingAction.MediaRecorderReady(audioFile: File)` and the reducer stores it.
  - Alternative: make `recordingAudioFile` lazy in `ReducerContext` and only invoke it if a reducer actually reads it. Phase-1 option C.
  - Either way, drop the synchronous hardware read from the orchestrator. State is the source of truth, not the subsystem.

---

### Issue L-6: Cross-module sub-state invariants are unenforced

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §3 (DictateUiState top-level + sub-states), §15 (per-module reducers)
- **Description:** Each module owns its sub-state and reduces it independently. The spec doesn't define which cross-axis combinations are valid. A few that *could* exist after legal individual transitions but are nonsensical together:
  - `recording = RecordingState.Active(useBluetooth=true)` + `audio.bluetoothSco.phase = Disconnected` → recording with BT-mic but no SCO link.
  - `recording = Active` + `pipeline = ReprocessStaging(...)` → recording during reprocessing (the spec elsewhere implies these are mutually exclusive, but the per-module reducers don't enforce it; only the IME UI assumes it).
  - `viewMode = HOVER` + `recording = Idle` — is HOVER allowed when not recording? §15.4 cross-module rule says "Recording-Active+View-hidden → HOVER", but the symmetric rule (Recording goes Idle → leave HOVER) isn't spelled out.
  - `pendingSessions` includes a session with `status=COMPLETED` while `pipeline = Running(...)` for the same session → state-store could observe both because the DB-subscriber and the pipeline-reducer write independently.
- **Example scenario:** AudioFocus-loss cascade fires `PauseRecording` (L-1 example). `RecordingModule.reduce(Active, PauseRecording)` returns `RecordingState.Paused`. BluetoothModule's observer sees `Active → Paused` and emits `AudioAction.OnBluetoothScoStateChanged(Disconnected)`. AudioModule reduces, `audio.bluetoothSco.phase = Disconnected`. Now we are `recording=Paused(useBluetooth=N/A — RecordingState.Paused has no useBluetooth field)` + `audio.useBluetoothMic=true` + `bluetoothSco.phase=Disconnected`. The next `Action.RecordingAction.ResumeRecording` constructs `RecordingState.Active(useBluetooth = ctx.audio.useBluetoothMic)` = `Active(useBluetooth=true)` — but SCO is *off*. The MediaRecorder will use the built-in mic and the user thinks they're recording into BT.
- **Suggestion:**
  - Add an "Invariants" sub-section to §3 listing forbidden sub-state combinations.
  - Decide where to enforce them: (a) a final invariant-check step in `DictateUiStateStore.update` that throws/logs if violated, or (b) a sanity test suite that fuzzes states. The store-level check is the clean answer (single point of truth).
  - The `RecordingState.Paused` data class is missing `useBluetooth` — note that `Active(useBluetooth=true) → Paused → Resume` recomputes from `ctx.audio.useBluetoothMic`, which can have changed. Either propagate `useBluetooth` through `Paused`, or define `useBluetoothMic` is sticky-during-session.

---

### Issue L-7: Reducer-`null`-return + `Action.NoOp` together create three failure indistinguishables

- **Category:** [LOGIC] / [CLEAN]
- **Severity:** Important
- **Location:** §4.2 line 362; §4.3 lines 458–461; §15.2 line 2309
- **Description:** Phase-1 1.1.4 already flagged this. After reading the spec end-to-end I see three different "this dispatch did nothing" paths, all logged at WARN, all distinguishable only by their log messages:
  1. `findModule(action) == null` → "Keine Modul-Zuordnung für $action" — bug or `Action.NoOp`.
  2. `reduce(...)` returns `null` → "Action $action ungültig im aktuellen State" — F1 reject (e.g. Stop while Idle).
  3. The reducer returns `TransitionResult(state, emptyList())` where `nextState == subState` (no actual change) — *no* log, but functionally equivalent to (1)/(2).
  Tests can't distinguish these via observable behavior; debugging by log is fragile because `Log.w` is filterable away in production.
- **Example scenario:** A test fires `Action.RecordingAction.PauseRecording` from `RecordingState.Idle`. Spec says reducer returns null (line 2310). Test sees state unchanged. Same observable result as if the test mistyped and fired some action no module knows. Both produce a `Log.w`. The test passes either way.
- **Suggestion:**
  - Make `dispatch` return a `DispatchOutcome` (sealed: `Applied | Rejected(reason) | Unrouted | NoOp`) — the IME side ignores it for normal flows, but tests can assert. This is a small interface change with high test value.
  - Decide on `Action.NoOp` per phase-1 1.1.4. If it stays, special-case at the top of `dispatch` (`if (action === NoOp) return Applied`) — don't let it traverse the routing machinery.

---

### Issue L-8: Modus 3 (Atomic Cross-Axis-Update) is unimplemented but referenced

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Important
- **Location:** §15.5 lines 2470–2480
- **Description:** Phase-1 1.1.3 already raised this. §15.5 declares three cross-module modes, but **mode 3** (atomic cross-axis update) has no code path in `DictateOrchestrator.dispatch` — only modes 1 and 2 are wired. The example given ("Pipeline-Done betrifft 4 Achsen") is exactly the case that the cascade approach (mode 2) would handle by emitting four cascade actions, and that breaks atomicity (between `dispatch(PipelineAction.PipelineDone)` and the cascade actions, observers see partial state). Either:
  - Mode 3 should be removed from §15.5 (it's a documented option that doesn't exist), **or**
  - Mode 3 should be wired (e.g. a `Module.atomicReduce(prev, sub) -> Map<ModuleId, NewSubState>` extension hook that the orchestrator collects + applies in one `store.update`).
- **Example scenario:** `PipelineDone(text, sessionId)` is dispatched. PipelineModule's reducer flips `pipeline = Idle`. Cascade fires:
  - ResendModule emits `MarkLastAudio(true)` → updates `resend.lastAudioExists`
  - LivePromptModule emits `ChainNext(text)` → updates `livePrompt.pendingChain` and triggers `Effect.SubmitChainPipeline` → which dispatches `Action.PipelineAction.TriggerPipeline` → state goes back to `Preparing`.
  - Observer of `pipeline: Idle → Preparing` (e.g. `ResendModule` again?) — but `pipeline: Idle` was a transient state never observed by anyone unless they re-snapshot per cascade step (cf. L-1).

  Subscribers (UI views via `state.collect`) may see the transient `Idle` and flash buttons enabled for one frame, then re-disable on `Preparing`. Visual glitch, hard to reproduce.
- **Suggestion:**
  - Decide: drop mode 3, or implement it. Don't leave it in the spec as a phantom.
  - If kept, define the API: it's not just a docs question; the orchestrator needs an additional hook on the module interface and an extra step in `dispatch`.
  - For visual-glitch avoidance regardless of mode 3: `_state.value = ...` only emits if the new state is `!=` the old state — verify that `MutableStateFlow.update` already coalesces, but multiple emits during cascade still produce N intermediate values to observers.

---

### Issue L-9: `LayoutModule` aggregates four disjoint axes — Phase-1 1.1.5 unmitigated

- **Category:** [LOGIC] / [CLEAN]
- **Severity:** Nice-to-have (already raised in phase-1, listed here for completeness)
- **Location:** §15.1 row 5, §3 fields `contentArea`, `layout`
- **Description:** `LayoutModule` covers `contentArea`, `singleRowMode`, `smallMode`, `animationsEnabled`. These are four axes pasted together because they happen to be "layout-ish". `contentArea` (a 3-state enum) is FSM-flavoured; the others are independent booleans driven by Prefs. SRP-wise the module owns four axes whose only common thread is "rendered by KeyboardLayoutManager". The single reducer will end up with disjoint `when` arms.
- **Suggestion:** Split `LayoutModule` into `ContentAreaModule` (FSM) + `LayoutPrefsModule` (pref-mirror only, no actions of its own — the PrefMirror writes directly). The split is cheap if the action-class hierarchy is set up right.

---

### Issue L-10: `RecordingState.Paused` loses `useBluetooth`

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §3 line 14 (`object Paused : RecordingState()` in `RecordingState.kt`); §15.2 lines 2347–2358
- **Description:** `RecordingState.Paused` is `object` (no fields). `Active(useBluetooth)` → `Paused` → `Resume` reconstructs `Active(useBluetooth = ctx.audio.useBluetoothMic)`. Two issues:
  - User changed the `useBluetoothMic` Pref *during* the pause (Pref UI accessible from Settings while keyboard is visible) — Resume now flips mic mid-session.
  - Cross-axis invariant L-6: SCO can have torn down during pause; resuming re-asks for SCO without the user's awareness.
- **Suggestion:** Convert `Paused` to `data class Paused(val useBluetooth: Boolean)` so the Pause→Resume axis is hermetic. Update §3, §15.2 lines 2348/2349/2355/2356 accordingly.

---

### Issue L-11: `DictateModuleRegistry.init` doesn't catch all duplication classes

- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Nice-to-have
- **Location:** §4.8 lines 675–684
- **Description:** The registry checks duplicate `id` and duplicate `actionClass` references. It does not check:
  - **Unrouted action leaves** — see L-4. A new sealed leaf added to `Action.PipelineAction` after the registry was built would silently route via assignable-from to PipelineModule, even if conceptually it belongs elsewhere.
  - **Sub-state-field coverage** — every field in `DictateUiState` should have exactly one owner-module. The check could verify that each module's `read`/`write` references a distinct getter via reflection, but currently there's no check at all.
  - **Initial-state consistency** — `DictateUiState.initial()` (§3 lines 107–123) and each module's `initialState()` are independent. If they drift, the orchestrator's first `dispatch` could see the store-initial state for some axes and the module-initial state for others.
- **Suggestion:**
  - Add at registry init: `Action::class.sealedSubclasses` walk, assert every leaf maps to exactly one module.
  - Add at registry init: `require(modules.all { it.read(DictateUiState.initial()) == it.initialState() })` — keeps the two initial-state sources in sync.

---

### Issue L-12: `PipelinePrefMirror.sync(key)` is unguarded against Pref-Storms

- **Category:** [PERFORMANCE] (not in scope per instruction) / [ROBUSTNESS]
- **Severity:** Nice-to-have
- **Location:** §4.5 lines 575–600
- **Description:** Each preference change fires `OnSharedPreferenceChangeListener` on the main thread, which calls `store.update {...}`. There is no debouncing / coalescing. If the user rapidly changes Theme & AccentColor & OutputSpeed in a settings screen, three `state.value = ...` emissions fire sequentially; every collector recomposes three times. Robustness-wise this is OK (each emission is consistent), but the PrefMirror also has no fallback for keys in the `else -> current` branch — if a future Pref is wired to write but PrefMirror is not extended, it silently drops. Consider warning at debug-build time.

  Also: `sp.getFloat(key, 1.0f)` for `OverlayPositionPortraitX` repeats the default `1.0f` across the file; same value lives in `OverlayState` (`positionPortraitX = 1.0f`). Two places to update on default change. Phase-2 SSoT consideration.

---

### Issue L-13: `runEffect` for some modules has no `else` branch — exhaustivity assumed but not asserted

- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Location:** §15.2 lines 2361–2378; §15.3 lines 2423–2428
- **Description:** Both `runEffect`-`when` blocks lack an `else` branch. Kotlin will require exhaustivity because the `when` is *used as expression* (assigned to `Unit` return). That works for `sealed interface Effect` on the `RecordingModule` — but if `Effect` is later changed to a non-sealed interface (e.g. `interface Effect : SideEffect`), the `when` will silently compile and miss new cases.

  Pattern to enforce: end every reducer-`when` and every effect-`when` with `}.also { /* exhaustive */ }`, or use `is Effect.X -> ...; is Effect.Y -> ... }` form so `kotlin.Nothing` infers when missing — but the spec doesn't call this out.
- **Suggestion:** Convention note in §4.2: "All Effect interfaces MUST be `sealed interface`. All `runEffect`/`reduce` `when`-blocks are used as expressions (`= when { ... }`) so the compiler enforces exhaustivity."

---

### Issue L-14: `notifyImeViewShown/Hidden` synchronous-dispatch assumption

- **Category:** [LOGIC]
- **Severity:** Nice-to-have
- **Location:** §5 lines 750–751
- **Description:** `LocalBinder.notifyImeViewShown()` calls `dispatch(Action.ViewModeAction.OnImeViewShown)` synchronously from the IME-onCreateInputView (the binder runs on the IME's thread, which is the main thread). If `dispatch` throws (per L-2), the IME's view-creation crashes and the keyboard becomes unavailable system-wide for all apps until the IME is restarted.

  Suggestion: same as L-2 — wrap `dispatch` in a try/catch at the top, never let an effect-failure propagate out of the binder boundary.

---

### Issue L-15: `viewMode` axis owner is `ViewModeModule`, but `notifyImeViewShown/Hidden` is plumbed via the LocalBinder

- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Location:** §4.1 line 293, §5 lines 750–751, §15.1 row 4
- **Description:** Slight clean-code smell. The two lifecycle hooks are implemented as `dispatch(Action.ViewModeAction.OnImeViewShown)` — which is fine. But if a developer later adds a non-ViewMode reaction to "IME view shown" (e.g. AudioFocus auto-request), they will need to either: (a) extend `ViewModeAction` to include non-ViewMode-axis semantics (semantic creep), or (b) cross-module observe `OnImeViewShown`. Option (b) is correct, but it's not obvious from the naming.
- **Suggestion:** Rename to `Action.LifecycleAction.OnImeViewShown/Hidden` (a new "Lifecycle" axis whose module is just a router) — or document the convention "any module may observe ViewModeActions; viewmode-naming is historical".

---

### Issue L-16: `onCrossModuleStateChange` naming is inconsistent with effect/action terminology

- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Location:** §4.2 line 377
- **Description:** Naming convention drift across the module:
  - `reduce(state, action, ctx)` — verb
  - `runEffect(effect, services)` — verb-noun
  - `onCrossModuleStateChange(prev, next)` — `on…` prefix breaks the pattern; this is also a *function that returns actions to fire*, not a notification-only hook.
- **Suggestion:** `observeAndCascade(prev, next): List<Action>` or `cascadeFrom(prev, next): List<Action>`. Names should reveal intent (returns cascade) and stay consistent (verb-first).

---

### Issue L-17: `ModuleServices.emitAction` undocumented contract / re-dispatch semantics

- **Category:** [INTEGRATION] / [CLEAN]
- **Severity:** Nice-to-have (covered partially by L-3 critical)
- **Location:** §4.7 line 642
- **Description:** The field is declared as `val emitAction: (Action) -> Unit` with a single-line comment. The contract isn't specified — Sync? Async? Allowed inside `runEffect`? Allowed at any time? Thread-safe? Whether it counts toward the cascade depth (L-1)? The implementer will guess.
- **Suggestion:** Add a JSDoc-equivalent KDoc block: "Posts an Action to the orchestrator. Always delivered asynchronously via `services.scope` to avoid reentrancy. Permitted from any thread. Counts as a fresh dispatch cycle for cascade-depth purposes."

---

### Issue L-18: `RecordingState.Paused.Cancel/Stop` is `TODO()`

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §15.2 lines 2355–2356
- **Description:** Two reducer arms are stubbed:
  ```kotlin
  Action.RecordingAction.StopRecording -> /* analog Active.Stop */ TODO()
  Action.RecordingAction.CancelRecording -> /* analog Active.Cancel */ TODO()
  ```
  TODO() throws at runtime. As example code in the spec it's understandable, but a reader could copy-paste this into the implementation. The "analog Active" comment also misleads: the Active.Cancel arm does `effect.recordingAudioFile?.let { Effect.DeleteAudioFile(it) }` — but `Paused`-state has no audioFile in the data class (per L-10). The translation isn't trivially analog.
- **Suggestion:** Either provide the full reducer arms (the `Paused`-Cancel needs to delete the WIP audio file too) or a clear "TBD: see migration plan §9.1" pointer. Don't ship `TODO()` in a spec example.

---

### Issue L-19: `pendingSessions` axis has no actions but `actionClass: KClass<A>` is mandatory

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §15.1 row 12 ("PendingSessionsModule — DB-Subscriber, kein Reducer"); §4.2 line 349 (`val actionClass: KClass<A>`)
- **Description:** `PendingSessionsModule` is described as a DB-subscriber, no reducer needed. But `DictateModule.actionClass` is non-nullable. What does `PendingSessionsModule.actionClass` point to? If it points to a dummy sealed interface with zero subclasses, the registry's uniqueness check passes — but no action will ever route there. If it points to a real action like `Action.PendingSessionsAction.OnDbUpdate`, the DB-subscriber must `dispatch(OnDbUpdate(list))` rather than writing to the store directly. The latter is consistent with single-dispatch (F-8) but the spec doesn't say which approach is taken.
- **Example scenario:** Implementer ignores the question and lets `PendingSessionsModule` write directly via `store.update {...}` (since no reducer), bypassing dispatch. Now there are two write paths: dispatch + this back-door. Cascade observers won't fire when `pendingSessions` changes via the back-door — `LivePromptModule` can't observe pending-completed sessions.
- **Suggestion:** Add an explicit subsection: "DB-subscriber modules: define an `OnDbUpdate(...)` action and dispatch it from the subscriber. Reducer simply replaces the field." This keeps single-dispatch invariant.

---

### Issue L-20: `DictateUiState.copy()` over PersistentList — mutation perf vs allocation

- **Category:** [PERFORMANCE] (not in scope) / [LOGIC]
- **Severity:** Nice-to-have
- **Location:** §3 line 102 (`pendingSessions: PersistentList<PendingSession>`)
- **Description:** Every dispatch produces a new `DictateUiState` via `copy()`. `PersistentList` is structurally shared (good). But if a reducer naively does `current.copy(pendingSessions = current.pendingSessions + newSession)`, the `+` operator on `PersistentList` allocates a new persistent list. That's correct but easy to write as `current.pendingSessions.toMutableList().apply { add(newSession) }.toPersistentList()` — which loses the structural sharing. Spec doesn't show the recommended idiom.
- **Suggestion:** Add a usage note in §3 with the canonical mutation pattern:
  ```kotlin
  // ✓ structural-share preserved
  pendingSessions = current.pendingSessions.add(newSession)
  // ✗ allocates fresh list
  pendingSessions = (current.pendingSessions + newSession).toPersistentList()
  ```

---

## Summary Table

| #    | Category                  | Severity     | Issue                                                                                                  | Description |
|------|---------------------------|--------------|--------------------------------------------------------------------------------------------------------|-------------|
| L-1  | [LOGIC]                   | Critical     | Cascade has no fixed-point/cycle/depth guarantee                                                       | Recursive dispatch in `dispatch()` step 6 has no cycle detection, no depth cap, snapshot-drift between cascade steps undefined. |
| L-2  | [LOGIC] [ROBUSTNESS]      | Critical     | `runEffect` is `Unit`-returning — failures invisible                                                   | A throwing effect leaves state mutated, kills cascade, no error-channel; subsystems must handle errors out-of-band. |
| L-3  | [LOGIC]                   | Important    | `emitAction` reentrancy semantics undefined                                                            | Sync vs async re-dispatch from EffectHandler is unspecified; sync reentry breaks cascade snapshot semantics. |
| L-4  | [LOGIC]                   | Important    | `findModule` falls through to `isAssignableFrom` — silent ambiguity                                    | Two modules with overlapping action-class hierarchies route to the first registered, no error. NoOp routing unresolved. |
| L-5  | [LOGIC] [ROBUSTNESS]      | Important    | `buildContext` does synchronous hardware read — phase-1 1.1.7 unmitigated                              | Reducer purity broken; race risk vs async release; tests need fake hardware unnecessarily. |
| L-6  | [LOGIC]                   | Important    | Cross-module sub-state invariants unenforced                                                           | No place enforces that e.g. `Active(useBluetooth=true)` requires `bluetoothSco.phase=Connected`. |
| L-7  | [LOGIC] [CLEAN]            | Important    | Three indistinguishable "no-op" outcomes for dispatch                                                  | unrouted vs F1-reject vs same-state-write all observable as "nothing changed", only differ in log. |
| L-8  | [LOGIC] [INTEGRATION]      | Important    | Modus 3 (Atomic Cross-Axis) declared but not wired — phase-1 1.1.3 unmitigated                         | §15.5 documents three modes; only two have code paths. |
| L-9  | [LOGIC] [CLEAN]            | Nice-to-have | `LayoutModule` aggregates 4 disjoint axes — phase-1 1.1.5 unmitigated                                  | SRP smell: contentArea (FSM) + 3 boolean prefs in one module. Split suggested. |
| L-10 | [LOGIC]                   | Important    | `RecordingState.Paused` loses `useBluetooth`                                                           | Pause→Resume re-reads `useBluetoothMic` Pref, can change mid-session; Paused should carry the field. |
| L-11 | [LOGIC] [ROBUSTNESS]      | Nice-to-have | Registry-init checks are incomplete                                                                    | Unrouted leaves, sub-state ownership, initial-state consistency aren't asserted. |
| L-12 | [ROBUSTNESS]              | Nice-to-have | PrefMirror has no fallback warning, default-value duplication                                          | `else -> current` silently drops new prefs; default `1.0f` repeated in PrefMirror + OverlayState. |
| L-13 | [CLEAN]                   | Nice-to-have | `runEffect`/`reduce` exhaustivity convention not documented                                            | All `Effect` types should be `sealed interface` and all `when`-blocks expression-form to enforce. |
| L-14 | [LOGIC]                   | Nice-to-have | `notifyImeViewShown/Hidden` synchronous + uncaught                                                     | If `dispatch` throws (cf. L-2), IME crashes during view creation. |
| L-15 | [CLEAN]                   | Nice-to-have | `OnImeViewShown/Hidden` semantically belongs to a "Lifecycle" axis, not ViewMode                       | Rename or document convention. |
| L-16 | [CLEAN]                   | Nice-to-have | `onCrossModuleStateChange` naming inconsistent with `reduce`/`runEffect`                               | Rename to `cascadeFrom(prev, next)` for verb-first / intent-revealing. |
| L-17 | [INTEGRATION] [CLEAN]      | Nice-to-have | `emitAction` contract undocumented (related to L-3)                                                     | KDoc the sync/async/thread/depth semantics. |
| L-18 | [LOGIC]                   | Important    | `RecordingState.Paused.Stop/Cancel` is `TODO()`                                                        | Spec ships placeholder reducer arms; the analog isn't trivial because Paused has no audioFile field. |
| L-19 | [LOGIC]                   | Important    | `PendingSessionsModule` actionClass-vs-DB-subscriber contract unclear                                  | Unspecified whether DB writes go through dispatch (consistent) or back-door (breaks cascade observability). |
| L-20 | [LOGIC]                   | Nice-to-have | `PersistentList` mutation idiom not shown                                                              | Naive `+`-with-toPersistentList breaks structural sharing — add a §3 usage note. |

---

## Notes on Phase-1 Issues

The four Phase-1 logic findings handed to me as "known":

| Phase-1 ID | Status in Phase-2 review |
|-----------|--------------------------|
| 1.1.5 (Cross-Module-Cascade rekursiv ohne Loop-Schutz) | **Confirmed and expanded** — see L-1. Spec text in §4.3 is unchanged; cascade-depth/cycle/snapshot are all unmitigated. |
| 1.1.7 (`buildContext` ist nicht pure)                  | **Confirmed and expanded** — see L-5. Phase-1 option C (lazy) acceptable but spec didn't pick. |
| 1.1.8 (Modus 3 deklariert, nicht verdrahtet)           | **Confirmed** — see L-8. §15.5 still lists three modes; only two have code paths. |
| 1.1.3 (`Action.NoOp` vs reducer-null-return)            | **Confirmed and expanded** — see L-7 (and L-4). The spec hasn't picked one of phase-1's three options; I added a fourth angle (a `DispatchOutcome` return). |

All four phase-1 findings remain open in the spec under review.

---

## Methodology

- Read §3, §4.1–§4.10, §5, §15.1–§15.6 of `1-pipeline-service.md` (~1100 lines of plan text).
- Cross-checked phase-1 findings (`plan-review/validated-findings-phase1.md`).
- Read related sections in `2-keyboard-layout.md` (§3.3 Action hierarchy) for action-routing context.
- Read existing source: `Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`, `PipelineUiState.kt` to confirm sealed-class contracts and the `Paused` no-fields finding (L-10).
- Searched for thread-safety hints (`Dispatchers.Main.immediate`, `MutableStateFlow`) — orchestrator dispatches on the main thread; this informs L-3, L-14.

No edits were made to plan or code files.
