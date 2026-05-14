# Audit Report: logic (Block 1, scope: full-block)

**Agent-ID:** B1-AUDIT-LOGIC
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-typescript (discriminated-union / exhaustive-check patterns applied to Kotlin sealed classes), knowledge-sql (NULL-safety — limited B1 applicability, none triggered)
**Files inspected:** 7
- `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`

## Summary

- Critical: 0
- Important: 3
- Nice-to-have: 4

## Findings

### AUDIT-LOGIC-B1-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt:178-183` (and `:198-203`)
- **Description:** `applyIdleState()` and `applyActiveState(useBluetooth)` call `resolveResendVisibility(...)` with a **hard-coded literal** `pipelineState = PipelineUiState.Idle` (line 182 + 202). The code comment claims this captures an invariant ("at the moment a `RecordingStateController`-driven Idle transition runs, the pipeline FSM is also Idle"), but the invariant is **not enforced** — `RecordingUiController` holds no reference to `KeyboardUiController` or any `PipelineUiStateReader`. Any caller of `recordingStateController.notifyState(...)` from a non-stop pathway (view-recreate `restoreUiState`, language-flip, future cancel-recording path that does not run `onRecordingCompleted`, etc.) can therefore trigger `applyIdleState()` while the pipeline is **actually** in `Preparing`/`Running`/`ReprocessStaging`. In that frame the predicate evaluates `recording=Idle ∧ pipeline=Idle (literal)` → `true` → resend button briefly shows VISIBLE while the pipeline still paints "Sending…" on the record button.
- **Why it matters:** This is the §9.5 race the plan claims the centralisation eliminates ("eliminiert die §9.5-Race"). The pipeline-axis is **owned** by `KeyboardUiController.state` (a real source of truth), so passing the literal — instead of reading the live state — leaves the resend button's pipeline axis unowned by the very controller that owns the recording axis. The "single owner per frame" invariant from Spec 1 §11.2.2 holds for `recordButton` (via the central resolver) but not for `resendButton` in `RecordingUiController`. Block 1a explicitly justifies the literal as "preserved verbatim from previous behaviour" — but the previous behaviour was unconditional `View.GONE` in `applyActiveState` (no pipeline-axis read needed) and `if (audioFile && Pref.ResendButton) VISIBLE else GONE` in `applyIdleState` (which already missed the pipeline axis in the original code too). The new code preserves the same gap behind the helper.
- **Suggested fix scope:** small
- **Suggested fix:** Inject a `pipelineStateProvider: () -> PipelineUiState` into `RecordingUiController` (default `{ PipelineUiState.Idle }` for tests) and replace the two `pipelineState = PipelineUiState.Idle` lines with `pipelineState = pipelineStateProvider()`. The IME-side wires it as `() -> uiController.getState()` — same provider style already used for `isReprocessStaging` (DictateInputMethodService.java:605). Documentation also needs updating: the comment in `applyIdleState` should drop the "invariant" framing.

### AUDIT-LOGIC-B1-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:148`
- **Description:** `startForeground` is invoked unconditionally in `onStartCommand`. On API ≥ 31 it can throw `ForegroundServiceStartNotAllowedException` (background-start restrictions for FGS), and on API ≥ 33 it can throw `SecurityException`/`MissingForegroundServiceTypeException` if `POST_NOTIFICATIONS` is denied + `FOREGROUND_SERVICE_TYPE_MICROPHONE` mismatched. The plan (Spec 1 §11.5.1 + Finding 11 of the S-5 review) explicitly anticipates the POST_NOTIFICATIONS-denied case — but the Block 2 implementation has **no try/catch** around `startForeground` and would crash the service the first time the OS deems the foreground-start illegal. Same applies to `notificationManager.createNotificationChannel` (no catch) and the implicit `Notification` posting from `startForeground` itself.
- **Why it matters:** The ADR-0003 failure-mode "OS-killed service without restart" assumes the service starts cleanly the first time. If a user with `POST_NOTIFICATIONS` denied opens the IME, the first `onStartCommand` crashes the service before binding completes; the IME-side `ServiceConnection.onBindingDied` then re-bind-loops (line 354), and each cycle re-crashes. Spec 1 §11.5.1 Finding 11 plans a banner-based Re-Prompt for this case — but the catch is needed regardless so the service can degrade gracefully.
- **Suggested fix scope:** small
- **Suggested fix:** Wrap `startForegroundCompat(buildInitialNotification())` in a `try { … } catch (ForegroundServiceStartNotAllowedException | SecurityException e) { Log.w(TAG, "FGS start denied", e); stopSelf(); return START_NOT_STICKY; }`. Same defensive `try/catch` around `mgr.createNotificationChannel(channel)` to handle `SecurityException` on locked-down devices. The state-file Test-Strategy already calls out a `pathologisches-Modul-Test` fixture; the same fixture pattern can cover this code path.

### AUDIT-LOGIC-B1-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:463-468`
- **Description:** `bindService(...)`'s return value is **discarded** ("`bindService(pipelineIntent, pipelineConnection, BIND_AUTO_CREATE)`"). If `bindService` returns `false` (manifest entry missing, permission denied, package-manager-resolve fails), `pipelineServiceBindAttempted` is still flipped to `true`, no `onServiceConnected` will ever fire, `pipelineBinder` stays null forever, and `onDestroy`'s `unbindService(pipelineConnection)` will throw `IllegalArgumentException` (caught — silently — but the binder failure has gone unnoticed in between). Spec 1 §11.3.2 explicitly lists this as an edge case the IME side must handle.
- **Why it matters:** Block 1b's first dispatch via the binder will silently no-op (the `pipelineBinder != null` guard fails) and the user-visible behaviour will be "no recording starts, no error message". With Block 2 the surface is limited (no functional impact until Block 1b lands), but the gap travels into Block 1b unchanged.
- **Suggested fix scope:** small
- **Suggested fix:** Capture the return value: `boolean bound = bindService(...)`. If `!bound`, log + reset `pipelineServiceBindAttempted = false` so the next `onCreateInputView` can retry. The toast-fallback string `dictate_service_not_ready` is already declared in `strings.xml` (line 401) for the pre-bind-action case — same string applies here.

### AUDIT-LOGIC-B1-4

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:164`
- **Description:** `serviceScope.cancel()` runs in `onDestroy` without a `super.onDestroy()`-vs-`cancel()` ordering guarantee for in-flight coroutines. Block 2 has no coroutines launched yet (no orchestrator), so this is a defensive observation, not a current bug. But Block 1b's planned `runBlocking { withTimeout(2000L) { orchestrator.shutdown() } }` (S-5 Finding 6) needs to happen **before** `serviceScope.cancel()` — otherwise `shutdown()`'s suspending `terminate` calls run on an already-cancelled scope and abort instantly. The current code structure (`serviceScope.cancel()` then `super.onDestroy()`) is already in the wrong order for the Block-1b shape — Block 1b would have to invert it.
- **Why it matters:** Hand-off to Block 1b. The current placement reads as "scope teardown is the only step" — Block 1b implementer must remember to insert `runBlocking` BEFORE `serviceScope.cancel()`. A KDoc comment on the `onDestroy` method documenting "Block 1b inserts `runBlocking` here, before `cancel()`" would prevent a Block-1b regression.
- **Suggested fix scope:** small (comment-only)
- **Suggested fix:** Add a `// Block 1b: runBlocking { withTimeout(2000L) { orchestrator.shutdown() } } MUST run before serviceScope.cancel().` comment immediately above `serviceScope.cancel()` to nail the ordering into the seam where the new code lands.

### AUDIT-LOGIC-B1-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:312-319`
- **Description:** `LocalBinder.dispatch(action: Any)` has no thread-safety guard. `stubDispatchCount += 1` is a non-atomic read-modify-write on a `var Int`. The binder may be invoked from any client thread (Android does not guarantee main-thread for binder transactions in the same process, and the IME-side has its own `mainHandler` posting boundary). If a test or Block-1b consumer dispatches from a worker thread, `dispatchInvocationCount` may miscount under concurrent access.
- **Why it matters:** Block 2 is a stub, so the counter is test-only. The block-audit test `localBinderDispatch_isNoOp_butCountsInvocations` runs on a single Robolectric thread, so the increment is safe today. But Spec 1 §11.3.4 acknowledges Multi-Bind; if Block 1b adds a second client (Settings/HistoryDetailActivity), concurrent dispatch becomes plausible. A `@Volatile var` + `@Synchronized` method, or an `AtomicInteger`, would be the canonical fix — costs nothing in this hot path.
- **Suggested fix scope:** small
- **Suggested fix:** `private val stubDispatchCount: AtomicInteger = AtomicInteger(0)` + `stubDispatchCount.incrementAndGet()` in `dispatch(...)`. The accessor `dispatchInvocationCount: Int get() = stubDispatchCount.get()` keeps the test contract intact.

### AUDIT-LOGIC-B1-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt:495-498`
- **Description:** `applyRecordButtonForRecording(state)`'s "pipeline owns the button" early-return discards the new `state` argument and calls `refreshRecordButtonFromState()`. This is correct under the documented invariant — but `state` is a `RecordingState` and `refreshRecordButtonFromState()` reads `this.state` (the pipeline state). If a future caller passes a Recording state that should influence the pipeline-painted appearance (e.g. tinting the record-button red while recording is Paused and pipeline is Preparing), there is no way to thread that through. Today this never happens, but the deferred call drops the `state` argument unconditionally, which is a small loss of information.
- **Why it matters:** Pure defensive observation. The current code is correct under the SoT invariant. Documenting "the discard is intentional — pipeline-axis owns the appearance" with a one-line comment makes the design explicit.
- **Suggested fix scope:** small (comment-only)
- **Suggested fix:** Add a one-line comment above `refreshRecordButtonFromState()` in the early-return branch: `// Discarding `state` is intentional — pipeline owns appearance entirely when non-Idle.`

### AUDIT-LOGIC-B1-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt:79-96` (`onStateChanged`)
- **Description:** The new ordering — `onRecordingStateChangedForRecordButton(newState)` BEFORE the `when` dispatch + `stateManager.refresh()` — means the record-button appearance updates **before** `applyIdleState/...` finishes its side-effect work (visualiser reset, prompt button text, etc.) AND before `stateManager.refresh()` rebuilds visibilities. In the brief inter-method window, the record button may show `Idle` styling while the pause-button still shows the previous state's foreground drawable. This is sub-frame so not user-visible, but it inverts the previous mental model ("apply state, THEN tell the world"). The block-report acknowledges this as the §9.5 race resolution, but a stronger structural guarantee — `stateManager.refresh()` AFTER `onRecordingStateChangedForRecordButton` (which already runs first) AND after `applyIdleState/...` (which it does today) — would be worth documenting as the contract.
- **Why it matters:** Block 5 (LayoutCatalog) folds both axes into a single subscriber + `state.collect`. Until then, the contract "all side effects run before `stateManager.refresh()` rebuilds visibility" is what protects users from a torn frame. Documenting this in the `onStateChanged` KDoc cements it as load-bearing.
- **Suggested fix scope:** small (comment-only)
- **Suggested fix:** Add a KDoc to `onStateChanged` capturing the ordering contract: "1. record-button resolver, 2. recording-axis side-effects (pauseButton + animation + prompt buttons + resend), 3. QWERTZ rec-button mirror, 4. stateManager.refresh(). The refresh runs last so all internal mutations are visible to KSM in the same Main-thread task."

## Out-of-scope observations

- **api/convention boundary:** The `companion object` for `NOTIF_ID = 0xD1C7A7E` in `DictatePipelineService` carries an explicit comment that Block 1b moves it into `PipelineNotificationCoordinator.NOTIF_ID`. The Block-2 acceptance `Phase-B S-5 NOTIF_ID-Konsistenz` (Spec 1 §10) is therefore deferred to Block 1b. The handoff is documented — flagging as informational for `AUDIT-PLAN-AND-API`.
- **test gap:** No test asserts `bindService` returns true in Robolectric (`bindService_smokeTest_doesNotThrow` only checks "does not throw" but ignores the return). Flagging for `AUDIT-TEST`.
- **convention:** The `@Suppress("UNUSED_PARAMETER")` on `dispatch(action: Any)` is a Block-2 stub artefact and the `action` parameter is in fact "used" in the documentation comment. A `// Intentionally unused — placeholder for Block 1b.` Kotlin annotation would be tidier. Flagging for `AUDIT-CONVENTION`.

## Coverage

- Files audited:
  - `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt` (pure predicate — verified all 4 axes covered; sealed-subclass `is` checks correct against `RecordingState.Idle/Preparing/Active/Paused` and `PipelineUiState.Idle/Preparing/Running/ReprocessStaging`; null-safety n/a because all parameters are non-null reference types or primitives)
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (FGS lifecycle: channel-before-startForeground OK; FGS 5-second budget OK by code-structure; `serviceScope.cancel` ordering flagged as B1-4; `startForeground` error-path flagged as B1-2; binder thread-safety flagged as B1-5)
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — bind/unbind code path: bind in `onCreateInputView` is correct per Spec 1 §11.3.1 (verified the §7.2 vs §11.3.1 drift was resolved in the plan); unbind in `onDestroy` symmetric; `onFinishInput` is **not** a separate unbind site (per Spec 1 §11.3.1 the bind survives keyboard switches deliberately — so the asymmetric "no unbind in onFinishInput" is correct, not a bug); `ServiceConnection.onServiceDisconnected/onBindingDied/onNullBinding` all four covered; `bindService` return-value discard flagged as B1-3.
  - `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt` (hybrid resolution `applyRecordButtonForRecording` — pipeline-axis-owns-when-non-Idle invariant correctly implemented; minor comment gap flagged as B1-6)
  - `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` (lambda-split correct; `onStateChanged` ordering correct under the documented invariant; literal `PipelineUiState.Idle` flagged as B1-1; ordering contract flagged as B1-7)
- KSM.refresh ordering in `onAudioFocusToggled` — verified against `bd8f1e6:DictateInputMethodService.java` baseline (pre-edit order was 1-SP-write / 2-live-hook / 3-icon-refresh). The new code appends `stateManager.refresh()` as step 4 at the end, preserving the existing 1-2-3 Race-Window ordering invariant from Block-2 Quality-Gate W. Agent's claim verified.
- Hybrid resolution `KeyboardUiController.applyRecordButtonForRecording` — pipeline-axis-when-non-Idle branch (lines 495-498) correctly defers to `refreshRecordButtonFromState()`, preserving the "single owner per frame" invariant from Spec 1 §11.2.2.
- 6th site (`onShowResend`) keeping explicit `setVisibility(View.VISIBLE)` — verified against `PipelineOrchestrator.kt` lines 951-955 (callback `onPipelineCompleted` → `onShowResend` runs while pipeline-state is still `Running`) and `DictateInputMethodService.onPipelineFinished` (line 2007) which fires `uiController.stopPipeline()` **after** the orchestrator's run-step-5 returns. Agent's timing rationale is correct: running `predResendVisible` at `onShowResend()` would return `false` because `uiController.getState()` is still `Running`. The deferred Block-5 LayoutCatalog state-driven subscriber resolves this structurally.
- Files skipped (with reason): `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `gradle/libs.versions.toml`, `app/build.gradle` — declarative / build config, not logic-bearing.
- Knowledge-skill checkpoints applied:
  - `knowledge-typescript` (discriminated unions / exhaustive checks): predicate uses `is RecordingState.Idle` + `is PipelineUiState.Idle` — both are `object` sealed-subclasses in their hierarchies. `is` against an `object` is the canonical "exhaustive match" form in Kotlin (equivalent to `===` identity check), so the predicate is correct. Non-Idle variants (`Preparing`/`Active`/`Paused` for recording; `Preparing`/`Running`/`ReprocessStaging` for pipeline) all fall through to `false` via `&&` short-circuit. Confirmed against `RecordingState.kt:10-15` and `PipelineUiState.kt:13-54`.
  - `knowledge-sql` (NULL safety): no SQL or DB code in B1 diff — N/A.
