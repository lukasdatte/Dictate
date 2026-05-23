# Audit Report: logic (Block 2, scope: full-block)

**Agent-ID:** B2-AUDIT-LOGIC
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-typescript (discriminated-union / exhaustive-when patterns applied to Kotlin sealed classes)
**Files inspected:** 21
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiStateStore.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/AudioModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LivePromptModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/FeatureToggleModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/ThemingModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PendingSessionsModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/KeyboardInputModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/InterruptionModule.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/test/java/net/devemperor/dictate/state/DictateOrchestratorTest.kt` (cascade-depth boundary spot-check)

## Summary

- Critical: 1
- Important: 3
- Nice-to-have: 4

## Findings

### AUDIT-LOGIC-B2-1

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:271-311`
- **Description:** Three `PipelineAction` variants reach the reducer but produce **no observable state change** because their target field lives outside the module's owned axis:
  - `NotifyResultNeedsManualPaste` should set the top-level `DictateUiState.lastResultNeedsManualPaste = true` (per spec §15.1 and the field's own KDoc on line 80 of `DictateUiState.kt`). Reducer arm at line 292 explicitly returns `null` with a long comment acknowledging "the flag is read directly from the global state — its mutation is performed by C7 wiring via a separate (out-of-band) `_state.update` on PrefMirror init". Audit grep of the whole codebase finds **no such out-of-band write** anywhere — neither in `PipelinePrefMirror`, nor `PipelineRecovery`, nor `DictatePipelineService`. The flag is permanently `false`.
  - `ClearManualPasteFlag` (line 311) symmetrically returns `null` with no mutation path. Even if `lastResultNeedsManualPaste` were ever `true`, the user-paste action couldn't clear it.
  - `PersistenceError` arm at line 271 correctly settles Pipeline back to `Idle` + emits `DismissNotification`, but the spec'd R.17 / Issue 2.1.21 also expects the manual-paste flag to be raised if the failure happened post-text-extraction. With the same lens-write limitation, that secondary mutation is missing.
- **Why it matters:** The IME service-death recovery path documented in Spec 1 §11.6 + R.18 relies on this flag to tell the user "your dictated text is on the clipboard — please paste". With the flag stuck at `false`, that user-affordance path is dead code today. This is a **functional regression** the moment the legacy IME pipeline is rewired through this module (which is the very next block, B3). The reducer's own KDoc comment claims "Phase 1" intent, but the data axis is owned by this module — the lens write IS the path. Cross-axis writes via lens (writing both `pipeline` and `lastResultNeedsManualPaste` in one `state.copy`) would be a **Mode-3 violation**; the correct fix is to fold `lastResultNeedsManualPaste` into `PipelineUiState` (where it conceptually belongs as a transient post-pipeline marker) or into `PendingSessionsState` (where the recovery UI lives), so the module's existing lens can carry the mutation as a Mode-1 write.
- **Suggested fix scope:** medium — needs an architectural call on the field's owning axis, then a one-line reducer arm. Recommend delegating to orchestrator/triage for a Mode-1-or-relocation decision (the in-code comment's "out-of-band write" plan is not yet realised and the as-shipped code is non-functional for this user path).

### AUDIT-LOGIC-B2-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt:65-102` (specifically the `store` field and `sync`/`detach` interaction)
- **Description:** The private mutable field `private var store: DictateUiStateStore?` is mutated from the Main thread (in `attach`/`detach`) but **read from arbitrary threads** in `sync(key)` (Android's `OnSharedPreferenceChangeListener` fires on the thread that called `apply()`/`commit()` — typically a background disk thread for `apply`). Without `@Volatile` or another publication barrier, the JVM/Dalvik memory model does **not** guarantee that a `detach()`-side write to `null` is visible to a concurrent reader on a different thread.

  The class KDoc at line 48-54 explicitly acknowledges the multi-thread listener fire and asserts thread-safety of `DictateUiStateStore.update` (CAS-loop on MutableStateFlow), but the **`store` field reference itself** is not the store internals — it is a plain `var` read in `sync()`. A late listener-fire after `detach()` could either:
  - See the stale non-null reference, run `applyChange(...)` on the dying store (`store.update` mutates a now-orphaned StateFlow — benign but wasteful), or
  - See the new `null` correctly and bail (intended).
  The current code's race is benign in practice — `MutableStateFlow.update` doesn't throw post-cancel — but the **detach-vs-fire ordering is not strictly enforced**.

  Additionally: the listener that `attach()` registers IS the same instance field `listener` (line 74), and the listener captures `this` (lambda over the inner `sync`). On `detach()` the unregister call IS correct (same instance). The unregister itself is not the race; the race is the post-unregister in-flight callback.

- **Why it matters:** Under load (e.g., user toggles a Pref during shutdown), a stale background listener could write into a logically-dead store. The store mutation is harmless, but it can mask a real bug-class in tests: a test that asserts "post-`detach`, no further state changes happen" could pass on one run and fail on another. There IS a test for this — `DictateOrchestratorInitOrderTest.shutdown detaches the SP listener` per the B2-C7 report — but the test verifies the listener-unregister call, not the memory-model race.
- **Suggested fix scope:** small — annotate `store` with `@Volatile`, or change the access pattern so `sync` reads `store` once into a local before the `targetStore ?: return` check (already done), but with the `@Volatile` guarantee added. The change is one annotation.

### AUDIT-LOGIC-B2-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:57-60` + `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt:158-162` (init block)
- **Description:** The orchestrator's `init` block does `scope.launch { recovery.recover(store) }`. The `recovery.recover` body is a `suspend fun` that calls `sessionRepo.loadPending()` (which the B3 implementation is expected to dispatch to `Dispatchers.IO`) and then `store.update { … }`. **There is no `try/catch` around the suspend call.** If `sessionRepo.loadPending()` throws (DB corruption, missing table, `SQLiteException` from a partial migration), the throwable propagates up through `scope.launch`. The `serviceScope` uses `SupervisorJob()` (per `DictatePipelineService` line 110-111), so the throw is **swallowed by the supervisor** — no `CoroutineExceptionHandler` is attached. The service still boots, but:
  - `pendingSessions` stays at its initial empty state.
  - The user sees no recovery UI for legitimately-pending sessions.
  - No logcat error tag identifies which subsystem failed (the supervisor's default handler logs as anonymous).
- **Why it matters:** A DB upgrade gone wrong is the most likely cause of recovery failure, and silent suppression makes the issue undiagnosable from `adb logcat | grep DictatePipelineSvc`. The spec at §6.3 + §11.6.2 mentions B3 adds "the full recovery algorithm" — implying the Phase-1 baseline is supposed to at least catch + log. The current Phase-1 baseline catches nothing.
- **Suggested fix scope:** small — wrap the suspend call in a `try { ... } catch (t: Throwable) { Log.e(TAG, "Pipeline recovery failed", t) }` either inside `PipelineRecovery.recover` (cleaner — the class owns the failure contract) or inside the `scope.launch` body in `DictateOrchestrator.init`. The orchestrator's `dispatchInternal` already has a similar throw-to-Log pattern at step 4 (line 327-337) for `runEffect` failures, so the convention is established.

### AUDIT-LOGIC-B2-4

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:218-228` (HOVER → KEYBOARD CancelRecording-priority cascade)
- **Description:** The cancel-cascade on HOVER → KEYBOARD chooses between `Action.RecordingAction.CancelRecording` and `Action.PipelineAction.CancelPipeline` via an `if/else if/else`:
  ```kotlin
  when {
      next.recording.isActiveOrPaused || next.recording is RecordingState.Preparing -> CancelRecording
      next.pipeline !is PipelineUiState.Idle                                          -> CancelPipeline
      else                                                                            -> // nothing
  }
  ```
  The spec's C-3 priority is "Recording > Pipeline". But there is a real-world flow where **both** are in flight simultaneously: the "Send" path. `Action.RecordingAction.StopRecordingAndSend` transitions Recording from Active → Idle (RecordingModule.reduce line 197-205) but **also** intends to trigger the Pipeline via downstream cascade (per the action's KDoc on `Action.kt:120`). At the precise mid-cascade moment between the Send-click-induced cascade fanout and the user-induced HOVER→KEYBOARD cascade, Pipeline could be Running while Recording has already moved to Idle. The else-branch is correct here (Recording done, Pipeline running → CancelPipeline fires). But the more interesting case: the user closes the HOVER overlay during a still-recording-AND-pipeline-staging window — currently we cancel Recording only; the Pipeline (Preparing stage) keeps running and produces a transcription the user already abandoned.

  The PipelineModule's reducer for `CancelPipeline(sessionId=null)` line 195-212 cancels the currently-active pipeline regardless of session, so a follow-up `CancelPipeline` would handle it. But it has to be **dispatched** — and the current cascade only fires one of the two.
- **Why it matters:** Worst case: a partially-recorded clip's pipeline run completes, the result is auto-inserted into the IME, but the user already opted out of it by closing the overlay. Hard to reproduce in normal use (requires sub-second timing). But: the cancel-cascade is the user's "abort" gesture, and not propagating it to BOTH in-flight axes is a leak of state across the abort boundary.
- **Suggested fix scope:** small — change the `when` to emit both actions in sequence when both are non-Idle:
  ```kotlin
  if (next.recording.isActiveOrPaused || next.recording is Preparing) cascade += CancelRecording
  if (next.pipeline !is Idle) cascade += CancelPipeline(sessionId = null)
  ```
  The cascade list is dispatched serially at depth+1 with re-snapshotting, so each cancellation sees the result of the earlier one. The C-3 priority semantic ("cancel recording first") is preserved by list-order.

  Alternatively, document the current "cancel-the-most-recent-axis-only" choice explicitly in the spec — this is a design-philosophy call. Either way, the silent dual-axis cancel is non-obvious and warrants either a documented choice or a fix.

### AUDIT-LOGIC-B2-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:233-256` (SendStaging → SubmitReprocess effect)
- **Description:** The `SendStaging` reducer emits `Effect.SubmitReprocess` with `audioFile = File("")` (an empty-string-pathed `File`) and a comment "Audio file is resolved by the runner from the DB session record — we pass a placeholder; the runner overwrites with the real path." This is a contract surface that depends on the **PipelineRunnerSubsystem** (B3 work) to know that an empty path means "look up by sessionId in the DB". The contract is not encoded in the type — a future PipelineRunner implementer (B3) reading only the type signature would either treat the empty path as a file-not-found error or attempt to open it as a zero-byte file.

  Similarly, `Effect.SubmitReprocess.queue = emptyList()` is passed even though the `Action.PipelineAction.SendStaging` is supposed to send the staging's accumulated queue (per Spec 1 §15.x ReprocessStaging-sub-FSM). The plan-deviation table in the B2-C5 report acknowledges queue-state lives on a different module (LanguageModule for `language`, but there is no module that owns the prompt-queue today). This is a Phase-1 conscious gap.
- **Why it matters:** Defensive-coding issue — better to make the contract explicit at the type level (e.g., `audioFile: File? = null` with the runner contract documented; `queue: List<Int>` defaulted from the staging state's own queue field if it existed). Today the contract is enforced only by KDoc + a future B3 implementer reading the comment.
- **Suggested fix scope:** medium — needs B3 input on the runner contract. Likely deferred to B3 with a clearer KDoc anchor on the Effect.

### AUDIT-LOGIC-B2-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:184-205` (Active → Idle on `StopRecording` / `StopRecordingAndSend`)
- **Description:** The Active → Idle reducer arm for `StopRecording` and `StopRecordingAndSend` **does not differentiate** between the two actions; they share the same `TransitionResult`:
  ```kotlin
  Action.RecordingAction.StopRecording, Action.RecordingAction.StopRecordingAndSend -> TransitionResult(
      nextState = RecordingState.Idle,
      sideEffects = listOf(StopMediaRecorder, StopTimer, StopBorderGlow, StopAmplitudeStream),
  )
  ```
  The KDoc on `Action.RecordingAction.StopRecordingAndSend` (Action.kt:120-126) says "stop recording AND trigger the pipeline. The `PipelineAction.Submit` is emitted by `RecordingModule`'s cross-module cascade on `Active/Paused → Idle` transitions where the trigger was this action."

  However, `RecordingModule.onCrossModuleStateChange` (line 344-353) only emits `Action.OverlayAction.ResetSuppressBit` on `Idle → Preparing` — there is **no Active/Paused → Idle observer arm that emits `PipelineAction.TriggerPipeline`**. The Active/Paused → Idle transition fires for both `StopRecording` (no pipeline) and `StopRecordingAndSend` (pipeline expected), and the cascade observer cannot tell them apart because the state diff is identical.

  Result: the "Send" semantic is lost in this module. The pipeline-trigger must come from a different source (likely the UI resolver path that dispatches a separate `TriggerPipeline` after `StopRecordingAndSend`), but that means **`StopRecordingAndSend` is functionally identical to `StopRecording`** in this module today — the two actions exist with different semantics on paper but identical behavior in code.
- **Why it matters:** Either the cascade observer is missing the trigger logic (a logic gap), or the action collapse is intentional and the action distinction is meaningless (in which case `StopRecordingAndSend` is dead code until B3 wires the UI resolver path differently). Spec ambiguity — needs a definitive call.
- **Suggested fix scope:** small — either:
  - Make the observer aware of the trigger by storing the "send-on-stop" intent in the FSM (e.g., `RecordingState.Active(useBluetooth, audioFile, sendOnStop: Boolean = false)`), checked in the observer to emit `PipelineAction.TriggerPipeline(sessionId, audioFile)`. This makes the action distinction meaningful.
  - Or, remove `StopRecordingAndSend` and have the UI resolver dispatch `StopRecording` followed by `TriggerPipeline` directly (a two-action sequence).
  Choice belongs to the architecture call (B3 surface).

### AUDIT-LOGIC-B2-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt:116-128` (SetContentArea while smallMode=true)
- **Description:** `SetContentArea` is silently rejected (returns `null`) when `state.smallMode && action.area != ContentArea.MAIN_BUTTONS`. This is documented as a structural invariant in the module's KDoc ("small + non-MAIN_BUTTONS is forbidden"). However:
  - The legitimate use-case "user is in small + MAIN_BUTTONS, dispatches SetContentArea(MAIN_BUTTONS)" → first branch `state.smallMode && action.area != MAIN_BUTTONS` is false → falls through to the second branch `if (action.area != state.contentArea) ... else null` → `action.area == state.contentArea (MAIN_BUTTONS)` → returns `null`. Correct no-op.
  - The legitimate use-case "user is NOT in small mode, dispatches SetContentArea(QWERTZ)" → first branch is false → second branch fires the state change. Correct.
  - The forbidden use-case "user is in small + dispatches SetContentArea(QWERTZ)" returns `null` silently. **No log, no diagnostic.** A future bug where the resolver path forgets to check `smallMode` before dispatching would be **silently swallowed**, with the user wondering why their tap on the QWERTZ button did nothing.

  The atomic clamp on `SetSmallMode(true)` and `ToggleSmallMode` already structurally prevents the "small + QWERTZ" state from existing post-dispatch. But the **inbound resolver** could dispatch a non-MAIN_BUTTONS area while small-mode is true — the reducer's silent rejection masks the bug from the resolver author.
- **Why it matters:** Defensive-coding opportunity. A `Log.w` (or even a comment confirming the design intent) would be enough.
- **Suggested fix scope:** small — one log line, or an explicit `// design: silent rejection — resolver MUST gate on state.smallMode` comment.

### AUDIT-LOGIC-B2-8

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:355-400` (cross-module observer's "Pipeline → Recording" no-op branch)
- **Description:** The KDoc at lines 22-27 lists `Pipeline → Recording` cascade as part of the Coupling-Matrix, and the observer body has a long comment at lines 389-398 explaining that "Phase 1 keeps the cascade as a no-op". This is a documented Phase-1 gap, but it leaves the **Coupling-Matrix entry** in the KDoc as misleading (a reader sees the entry and assumes it's implemented).

  Additionally: the observer's `pipelineWasActive && pipelineIsIdle` block at lines 370-387 emits the three Pipeline-Done cascades (`OnPipelineDone`, `MarkLastAudio`, conditional `ChainNext`). The `MarkLastAudio(exists = true)` is hard-coded to `true` — but the comment at line 378-380 acknowledges that "the pipeline-cancel path deleted the file" should mean `exists = false`. The current logic does NOT distinguish Pipeline-Done-success vs Pipeline-Done-cancel — both transitions reach `pipeline = Idle` and both fire `MarkLastAudio(true)`. The cancel path leaves an audio file on disk depending on the spec'd cancel semantics, but the resend button gets armed regardless.
- **Why it matters:** If a user cancels a pipeline and the audio file is deleted (per cancel-cascade semantics), the Resend button is now armed pointing to a non-existent file. ResendModule's reducer (ResendModule.kt:99-105) takes `MarkLastAudio.exists` at face value. ResendModule's failure-arm to clear the marker on file-check failure (mentioned in its KDoc) does NOT exist in the code today — there is no such observer.
- **Suggested fix scope:** medium — needs the cancel-cascade to either delete the file (in which case `MarkLastAudio(false)` should fire) or preserve it (in which case current behavior is correct). The PipelineModule observer should differentiate by checking the prev state — if `prev.pipeline` was being cancelled (carrying the cancel-flag in the FSM), emit `false`. Today the FSM collapses Cancelled directly into Idle without a flag, so the distinction is unrecoverable from the state alone. Likely a Phase-2 polish.

## Coverage

- Files audited (full read): all 14 module files; the orchestrator + registry; the store; PipelinePrefMirror; PipelineRecovery; Action.kt; DictateUiState.kt; DictatePipelineService.kt; PipelineServiceStubSubsystems.kt; DictateOrchestratorTest.kt (cascade-depth spot-check).
- Files skipped (with reason): `TestOnlyModules.kt` — production-side test fixtures only, no runtime semantics. `TransitionResult.kt`, `SideEffect.kt`, `ModuleServices.kt`, `ModuleId.kt`, `InsertionTarget.kt`, `DictateModule.kt` — pure type definitions / DI surfaces; logic audit is N/A.
- Knowledge-skill checkpoints applied:
  - **Sealed-when exhaustivity** (knowledge-typescript discriminated unions): every module's `reduce` is an expression-form `when` over the sealed Action sub-class — Kotlin compiler enforces exhaustivity. No `else`-branches found.
  - **Cross-axis-write check (Mode 3)**: confirmed every module's `reduce` writes only its own sub-state via `global.copy(<axis> = sub)`. No instances of `state.copy(<otherAxis> = ...)`. The reducer-level "Mode-3 violation" pattern is structurally absent. (One borderline case: `lastResultNeedsManualPaste` is a top-level field that conceptually belongs to PipelineModule but is excluded from the module's lens — see B2-1 above.)
  - **Pure-reducer invariant**: spot-checked all 14 `reduce` bodies — no hardware/IO/threading/SP-direct-write inside any. Side-effects flow through `TransitionResult.sideEffects` → `runEffect`. ✓
  - **Self-cascade allowed**: orchestrator step 5 at line 362 does NOT filter by module id; verified the KG-RSB-2-Fix ASCII box at lines 342-356 explicitly preserves the no-self-filter contract. RecordingModule's `Idle → Preparing → ResetSuppressBit` self-cascade depends on it. ✓
  - **EffectFailure routing by `originModuleId`** (ADR-0002): orchestrator step 1a at line 285-298 routes via `moduleById[action.originModuleId]`, not via KClass. ✓
  - **Cascade-depth boundary**: verified the implementation (`depth >= MAX_CASCADE_DEPTH` at line 272) lets depths 0..7 apply (8 reducer applications) then rejects at depth=8. The test (DictateOrchestratorTest.kt line 441-445) asserts `lens.get(A) == 8` — matches. Boundary semantic is "max 8 reducer applications per dispatch chain". Consistent with Spec 1 §15.5 + ADR-0002.
  - **assertCompleteCoverage**: verified at `DictateModuleRegistry.kt` lines 92-111 — enumerates `Action::class.sealedSubclasses`, excludes `Action.EffectFailure`, requires every other direct subtype to be claimed. The 14 production modules cover the 14 direct sealed children of `Action` (RecordingAction, PipelineAction, ViewModeAction, LayoutAction, AudioAction, ResendAction, LivePromptAction, LanguageAction, OverlayAction, FeatureToggleAction, ThemingAction, PendingSessionsAction, KeyboardInputAction, InterruptionAction). ✓
  - **Registry init invariants**: validated `validate()` enforces unique `id`, unique `actionClass`, and no leaf-class overlap across modules — three invariants confirmed against the registry's `Default.all` shape.
  - **Init order in DictatePipelineService.onCreate**: verified Store → Services → PrefMirror + Recovery → Orchestrator → assertCompleteCoverage. The orchestrator's init block calls `prefMirror.attach(store)` synchronously, then `scope.launch { recovery.recover(store) }` async. ✓
  - **Shutdown order**: `orchestrator.shutdown()` calls `prefMirror?.detach()` first (line 254), then iterates modules' `terminate()`. Then `serviceScope.cancel()` runs in `onDestroy` after. ✓
  - **Triangle-FSM T1–T7 transitions** (ViewModeModule + OverlayModule observers): all seven verified — T1 KEYBOARD→WIDGET via ToggleViewModeWidget (permission-gated); T2 WIDGET→KEYBOARD via ToggleViewModeWidget; T3/T4 KEYBOARD/WIDGET→HOVER via `OnImeViewHidden` + `computeViewMode(false, _, pipelineActive=true)`; T5 HOVER→KEYBOARD via `OnImeViewShown` when `userPrefersWidget=false`; T6 HOVER→WIDGET via `OnImeViewShown` when `userPrefersWidget=true`; T7 HOVER→KEYBOARD via `OnPipelineDone` (with derived `imeViewVisible = state != HOVER`). The Permission-Gate on T1 is correctly placed (silent no-op when `!hasPermission`). The cross-module cascade entries in OverlayModule observer (T1 → SetUserPrefersWidget(true), T2 → SetUserPrefersWidget(false), HOVER→KEYBOARD → SuppressAutoOverlay + cancel-cascade, permission-loss → SetViewMode(KEYBOARD)) match Spec 3 §7.3.
  - **Atomic setSmallMode (LayoutModule)**: confirmed both `ToggleSmallMode` and `SetSmallMode(true)` use single `state.copy(smallMode=true, contentArea=MAIN_BUTTONS)` — both fields on the same `LayoutState` axis, NOT Mode 3. ✓

## Out-of-scope observations

- **AUDIT-CONVENTION territory**: `PipelinePrefMirror.companion object` exposes four `OVERLAY_POS_*_KEY` constants that mirror the raw-string keys also written by `OverlayModule.Effect.PersistOverlayPosition` (OverlayModule.kt:158-162). The constants are duplicated as **string literals** in OverlayModule (line 158 uses `"overlay_pos_portrait"` then suffixes `_x`/`_y` at write-time), while PipelinePrefMirror uses the fully-qualified `"overlay_pos_portrait_x"` form. Drift risk: a future rename of the key in one place leaves the other behind. The constants would be canonically referenced by both. Flagging for the convention audit.
- **AUDIT-TEST territory**: No test currently exercises the `lastResultNeedsManualPaste` mutation path (see B2-1) — the test suite would not catch the silent reducer-null behavior because the flag is initialised to `false` and no test dispatches `NotifyResultNeedsManualPaste` with a state-equality assertion afterwards. The B2-C5 report's "T7 with userPrefersWidget=true falls to KEYBOARD" test pins the truth-table edge but doesn't exercise the failure path.
- **AUDIT-PLAN-AND-API territory**: The B2-C7 report's plan-deviation #5 "DictateUiStateObserver.kt Java bridge NOT added in C7" is flagged-for-validate. From the logic side, the binder's `state: StateFlow<DictateUiState>` is correctly typed and the IME (Java) consumer would need a Kotlin-flow bridge. Not a logic issue per se, but B3 may consume this surface across the language boundary — worth confirming the IME-side consumer pattern in the plan-and-api audit.
