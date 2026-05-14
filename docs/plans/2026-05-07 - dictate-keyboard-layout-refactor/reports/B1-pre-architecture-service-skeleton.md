# Block 1: Pre-Architecture + Service-Skeleton

> **This file is the logbook for Block 1.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Pre-Architecture and Service-Skeleton (plan-Block-1a + plan-Block-2)
**Implementation-Chunks:** C1-block1a-quick-wins (S/M, 400 score), C2-block2-pipeline-service-skeleton (M, 850 score)
**Workflow:** Iter-10 5-step workflow with orchestrator-split commits (no resume in this env — IMPL agent does Steps 1-5 internally per chunk, orchestrator splits the diff into Commit 1 (production) + Commit 2 (tests))
**Block-Start-Commit:** `bd8f1e6`
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:**
- Critical: 0
- Important: 1
- Nice-to-have: 0
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| IMPL-1 | B1-C2-IMPL-FULL | Important | delegated-to-orchestrator | JobExecutor-init move deferred to Block 1b (Spec 1 §11.2.2 Block 2 sub-step 7) | C2 IMPL-PLAN-FIX |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared sub-agent directives (issue handling, status schema, stdout
convention, research-file output, plan-deviation autonomy) live in
`prompts/agent-prompts.md` — read it before starting your task.

### Deviation Format

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|

### Issue Format

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|

---

## Implementation Logs

### Chunk C1-block1a-quick-wins — Quick-Wins in today's code

**Agent-IDs:**
- Steps 1-5 (Implementation + Self-fixes + Tests + Test-fix): `B1-C1-IMPL-FULL` (single fresh agent in this env — combines all 5 steps because resume-chain is unavailable; orchestrator splits diff into 2 commits)

**Status:** ✅ done (ready for orchestrator commit-split + AUDIT)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 2 (C1-block1a-quick-wins)
**Implementation-Commit (Commit 1, production):** ⏳ (orchestrator splits)
**Test-Commit (Commit 2, tests):** ⏳ (orchestrator splits)

#### Implementation (B1-C1-IMPL)

**What was done.**
Steps 1-5 executed in a single fresh-agent invocation. Three Block-1a quick-wins implemented end-to-end against today's code (no module architecture):

1. **`predResendVisible` helper** — new top-level Kotlin function in `KeyboardVisibilityPredicates.kt`. Pure boolean over four axes (`lastAudioFileExists`, `resendEnabled`, `recordingState`, `pipelineState`). Companion `resolveResendVisibility(...)` translates to `View.VISIBLE` / `View.GONE`. The signature mirrors the future LayoutCatalog RESEND-slot predicate (Spec 2 §3.2) so Block 5 lifts the body verbatim.

2. **5 of 6 resend-button mutation sites migrated to the helper:**
   - `RecordingUiController.applyIdleState` — was `if (getLastAudioFileExists()) VISIBLE else GONE`; now `resolveResendVisibility(...)`. The combined `getLastAudioFileExists` lambda is split into two independent axes (`getLastAudioFileExists` + `getResendEnabled`) at the constructor boundary so the predicate sees each axis separately.
   - `RecordingUiController.applyActiveState` — was unconditional `GONE`; now `resolveResendVisibility(...)` with `recordingState = Active(useBluetooth)`. The predicate naturally returns false.
   - `DictateInputMethodService.onStartInputView` Idle branch — was an inline `if (...)` with the same 4 axes; now a single `resolveResendVisibility(...)` call.
   - `DictateInputMethodService.runTranscriptionViaOrchestrator` — was unconditional `GONE`; now `resolveResendVisibility(...)` reading `recordingStateController.getState()` + `uiController.getState()` (Preparing at this point → predicate returns false).
   - The 6th site (`DictateInputMethodService.onShowResend`) is kept as explicit `setVisibility(View.VISIBLE)` because the PipelineOrchestrator fires this callback BEFORE `stopPipeline()` runs — the predicate would evaluate to false (pipeline still Running) and the button would never appear. Documented in code with a forward pointer to Block 5 (LayoutCatalog) which folds the predicate into a state-collector and re-orders the pipeline-completion sequence.

3. **recordButton.text/isEnabled hybrid centralised** — Spec 1 §11.2.2 step 2. The previously dual ownership (`RecordingUiController.applyIdleState / applyPreparingState / applyActiveState` + `KeyboardUiController.refreshRecordButtonFromState`) collapses into a single `KeyboardUiController.applyRecordButtonForRecording(state: RecordingState)` resolver. `RecordingUiController.onStateChanged` calls this resolver via a new constructor-injected callback before running its auxiliary (pause-button / animation / prompts) work. When the pipeline owns the button (non-Idle pipeline state), the resolver defers to the pipeline-axis branch — guaranteed single owner per frame. `KeyboardUiController` gained a new constructor parameter `dictateButtonTextProvider` for the Idle-label string.

4. **KSM.refresh quick-wins** — Spec 1 §11.2.2 step 3. Added `stateManager.refresh()` calls at the end of `DictateInputMethodService.onSingleRowModeToggled()` (after the ConstraintSet swap + bounce animation) and `onAudioFocusToggled()` (after pref + live-hook + icon refresh).

**Files created (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt` — new helper file (103 lines incl. KDoc).

**Files modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` — split lambda inputs; injected `onRecordingStateChangedForRecordButton` callback; removed recordButton mutations from `applyIdleState` / `applyPreparingState` / `applyActiveState`; migrated 2 resend sites to the helper.
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt` — added `dictateButtonTextProvider` constructor param + `applyRecordButtonForRecording(state)` method.
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — wired the new constructor params; migrated 3 resend sites + 1 explicit-VISIBLE site to the helper / commented; added `stateManager.refresh()` to the two toggle handlers.

**Files created (tests, Commit 2):**
- `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt` — 17 JUnit 4 tests (handwritten, no fakes needed — predicate is pure) covering: happy path, each axis flipped alone, sealed-subclass-not-data semantics, multi-axis fail, `resolveResendVisibility` View-constant translation, consistency-with-predicate.

**Plan deviations.**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| D1 | Spec 1 §11.2.2 step 1 ("alle 6 verstreuten resendButton.visibility-Sites lesen sie") | Site #6 (`onShowResend`) kept as explicit `setVisibility(View.VISIBLE)` instead of routed through the helper. | At the moment the PipelineOrchestrator fires `onShowResend()`, the pipeline state in `KeyboardUiController` is still `Running` (see `PipelineOrchestrator.runStandalonePrompt` ordering: `callback.onPipelineCompleted` → `if (showResendButton) callback.onShowResend()` runs BEFORE the Service's `onPipelineFinished()` → `stopPipeline()` re-posts on the main handler). Predicate would evaluate to false and the resend button would never appear. The behavioural gating (`Pref.ResendButton` + audio-file existence) is preserved upstream in `runTranscriptionViaOrchestrator` (`showResend = ... && Pref.ResendButton`). | None — the deviation isolates one Java site behind a comment that references Spec 1 §9.4; Block 5 (LayoutCatalog) lifts the predicate into a state-collect subscriber and the explicit setter disappears entirely. | inline-fixed (kept explicit, documented in code + here) |
| D2 | Spec 1 §11.2.2 step 2 ("ein zentraler Resolver in KeyboardUiController, der die 8 verstreuten Sites in §13.4.1 ersetzt") | The resolver method `applyRecordButtonForRecording` covers the recording axis only; the pipeline axis stays in the existing `refreshRecordButtonFromState`. The two branches inside `applyRecordButtonForRecording` defer to each other (recording-axis runs when pipeline is Idle; otherwise the pipeline-axis is called). | A single function with two parameters (`recordingState`, `pipelineState`) would have required threading both arguments through every call site that mutates pipeline-state. The deferring approach gives the same "single owner" invariant with a minimum diff. Block 5 collapses both into one slot resolver per LayoutCatalog. | Block 5 collapses; no impact on Block 1b / 2 / 3 / 4. | inline-fixed |
| D3 | Spec 1 §11.2.2 step 3 ("onAudioFocusToggled → KSM.refresh()-Trigger") | The `onAudioFocusToggled` refresh call is added at the end of the existing 1-2-3 sequence (pref / live-hook / icon refresh) — step 4 in the new ordering, not interleaved. | Preserves the original Race-Window ordering invariant (Block-2 Quality-Gate W). The refresh is purely additive. | None. | inline-fixed |

**Issues.**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | — |

**Inline-fixed items.** See Deviations table; all three deviations were resolved inline with rationale + plan-pointer in code comments.

**Overlooked points / known gaps.**
- The race the plan calls out in Spec 1 §9.5 ("Bei rotation/restoreUiState ist die Reihenfolge nicht deterministisch") is mitigated structurally (single owner of recordButton appearance) but a full elimination requires the Block-5 state-driven subscriber. The current code still has a possible micro-window if `onStateChanged(Idle)` fires from `RecordingStateController` AFTER `restoreUiState` paints from the snapshot — but this matches the previous behaviour and any divergence would surface in the AUDIT-LOGIC phase.
- Block 4 (Spec 2 §14.2) extends the predicate to the full 25-case truth-table when LayoutCatalog lands — out of scope here.
- The `RecordingUiController.recordButton` field is now only referenced by `recordingAnimation.prepare(recordButton)` in `init`. Keeping the field for the animation-prepare seam is intentional; the animation hook is still recording-axis only.

#### Plan-Correctness Fix (B1-C1-IMPL-PLAN-FIX)

Combined into the single invocation above (Step 2). Self-review against Spec 1 §11.2.2 + §9.4 + §9.5 confirmed:
- Step 1 (predicate helper) — done; signature mirrors the future LayoutCatalog form.
- Step 2 (recordButton hybrid) — done via central resolver in `KeyboardUiController`; ordering invariant preserved (recording-axis runs only when pipeline is Idle).
- Step 3 (KSM.refresh quick-wins) — added to both toggle handlers without disturbing the existing 1-2-3 ordering.
- All three deviations documented above. None require orchestrator routing.

#### Self-Code Fix (B1-C1-IMPL-CODE-FIX)

Combined into the single invocation above (Step 3). Knowledge-skill grounding consulted:
- `knowledge-doc-format` §"Inline anchors" — `@see` pointers to the plan + spec sections added at the top of `KeyboardVisibilityPredicates.kt` and inside the resolver comment in `KeyboardUiController`.
- Engineering baseline (D7 — sustainable / SOLID / Clean Code): helper is a pure function with a thorough KDoc explaining the why; the dual-axis-deferring resolver in `KeyboardUiController` keeps the single-responsibility principle (each branch owns one axis) while still giving the "one entry point" invariant the spec requires.

#### Tests (B1-C1-IMPL-TEST)

**What was done.**
- New JUnit 4 test class `KeyboardVisibilityPredicatesTest.kt` with 17 tests:
  - Happy path (all 4 axes hold).
  - Each axis flipped alone (5 false cases for recording-state subclasses + pipeline-state subclasses + 2 boolean axes).
  - Bluetooth subclass-vs-data semantics check (the predicate's `is Active` / `is Preparing` ignores the data-class fields).
  - Two- and four-axis simultaneous failures.
  - `resolveResendVisibility` translation (VISIBLE for predicate true, GONE for predicate false).
  - Loop over all non-Idle pipeline states asserting GONE.
  - Consistency wrapper: predicate ↔ resolveResendVisibility never drift.

All 17 tests pass on `./gradlew test` (debug + release variants). Total suite still green (no regression).

**Files created (tests, Commit 2).**
- `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt`

**No code-bugs found while writing tests.** The predicate is pure and the truth-table is straightforward; tests caught no behavioural surprises.

#### Test-Review (B1-C1-IMPL-TEST-FIX)

Combined into the single invocation above (Step 5). Coverage assessment:
- All four predicate axes have at least one passing-true and one failing-false test.
- All three non-Idle pipeline subclasses (`Preparing`, `Running`, `ReprocessStaging`) covered.
- All three non-Idle recording subclasses (`Preparing`, `Active`, `Paused`) covered.
- Both `Active.useBluetooth=true` and `Preparing.useBluetooth=true` exercised to guard against an accidental Bluetooth-only check.
- The `resolveResendVisibility` wrapper is checked against the predicate (consistency-test) so future drift would be caught.

**No code-bugs found during test self-review.**

**Coverage gaps left intentionally:**
- Full 2^4 axis-combo matrix not enumerated — boolean-conjunction semantics is universally tested by "any-false-axis → false" cases. Adding 16 tests would not catch a class of bug the current 17 do not.
- 25-case full truth-matrix from Spec 2 §14.2 (Block 4) is out of scope for Block 1a.

---

### Chunk C2-block2-pipeline-service-skeleton — DictatePipelineService skeleton + FGS

**Agent-IDs:**
- Steps 1-5: `B1-C2-IMPL-FULL` (single fresh agent, orchestrator-split-commits pattern)

**Status:** ✅ done (ready for orchestrator commit-split + AUDIT)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 3 (C2-block2-pipeline-service-skeleton)
**Implementation-Commit (Commit 1, production):** ⏳ (orchestrator splits)
**Test-Commit (Commit 2, tests):** ⏳ (orchestrator splits)

#### Implementation (B1-C2-IMPL)

**What was done.**
Steps 1-5 executed in a single fresh-agent invocation. Block-2 skeleton implemented — Foreground Service container per ADR-0003, with notification channel, FGS-budget-compliant startForeground, LocalBinder single-dispatch surface, and IME-side bind/unbind lifecycle. No orchestrator yet (Block 1b scope).

1. **`DictatePipelineService.kt`** — new Kotlin Service class with:
   - `serviceScope: CoroutineScope` (SupervisorJob + Dispatchers.Main.immediate) — placeholder for Block 1b's `DictateOrchestrator(scope = serviceScope, …)` wiring.
   - `onCreate` calls `ensureNotificationChannel()` synchronously as step 1 (Spec 1 §11.1.4 channel-order invariant — IllegalArgumentException risk on API ≥ 26 if startForeground is called without a channel).
   - `onStartCommand` calls `startForegroundCompat(buildInitialNotification())` synchronously as step 1, returns `START_NOT_STICKY` (ADR-0003 §"Required mechanics" item 2 — FGS 5-second budget; ADR-0003 Failure-Mode "OS-killed service without restart" — recovery is user-triggered via DB-replay in Block 3).
   - `startForegroundCompat` switches between API-34+ explicit-type (`FOREGROUND_SERVICE_TYPE_MICROPHONE`) and pre-API-34 implicit signatures.
   - `onBind` returns a singleton `LocalBinder` — same instance across all bindService callers per Spec 1 §11.3.4 Multi-Bind acceptance.
   - `LocalBinder.dispatch(action: Any)` — Block 2 no-op stub with invocation counter (`dispatchInvocationCount`) so the IME-side dispatch path is exercisable; Block 1b replaces the body with `service.orchestrator.dispatch(action)`. The `Any` placeholder type widens to the future `Action` sealed-class hierarchy without breaking the binder API.
   - `LocalBinder.service` — direct service-instance pointer so Block 1b can layer `state: StateFlow<DictateUiState>` on top without touching the binder's sealed signature.
   - `onDestroy` cancels `serviceScope`. The orchestrator.shutdown() + pre-cancel-dispatch flow from Spec 1 §7.3 + ADR-0003 §"Required mechanics" items 8+9 lands in Block 1b (no orchestrator to shut down in Block 2).
   - `companion object` with `TAG`, `CHANNEL_ID = "dictate_pipeline"`, `NOTIF_ID = 0xD1C7A7E` (NOTIF_ID matches the canonical Spec 1 §7.4 value so the on-device notification id stays stable when Block 1b moves the constant to `PipelineNotificationCoordinator.NOTIF_ID`).

2. **`AndroidManifest.xml`** — additive diff per Spec 1 §11.1.1:
   - 3 service permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`.
   - 1 pre-declared Block-6 permission: `SYSTEM_ALERT_WINDOW` (Spec 3 §5.7 cross-link — declared now to eliminate a second manifest commit between Block 2 and Block 6; no-op until Block 6 wires the overlay).
   - New `<service>` entry for `.core.DictatePipelineService` with `exported="false"`, `foregroundServiceType="microphone"`, `description="@string/dictate_pipeline_service_description"`.

3. **`DictateInputMethodService.java`** — bind/unbind lifecycle per Spec 1 §11.3.1:
   - New fields: `pipelineBinder` (the `DictatePipelineService.LocalBinder` once connected), `pipelineConnection` (a `ServiceConnection` covering all 4 callbacks — onServiceConnected/Disconnected/BindingDied/NullBinding per Spec 1 §11.3.2), `pipelineServiceBindAttempted` (idempotency flag).
   - In `onCreateInputView`: `startForegroundService` + `bindService(BIND_AUTO_CREATE)` once (idempotent across view-recreate cycles via the flag). Bind-site is `onCreateInputView` NOT `onCreate` per ADR-0003 §"Required mechanics" item 4 (IME-onCreate can run before first view inflation).
   - In `onDestroy`: `unbindService` once, with `IllegalArgumentException` catch so a failed bind does not crash teardown. The flag is reset so a re-instantiated IME-Service can re-bind.

4. **Resource additions** in `app/src/main/res/values/strings.xml`:
   - `dictate_pipeline_service_description` — for the manifest `android:description`.
   - `dictate_pipeline_channel_name` / `dictate_pipeline_channel_description` — for the NotificationChannel.
   - `dictate_pipeline_notif_title` / `dictate_pipeline_notif_idle` — for the initial FGS notification.
   - `dictate_service_not_ready` — for the Spec 1 §11.3.2a Pre-Bind-Action toast (not yet wired in Block 2; the string is pre-declared so Block 1b can use it without touching strings.xml a second time).

5. **Test-infrastructure** (Block 2 introduces Robolectric — first user, see Deviation D4):
   - `gradle/libs.versions.toml`: added `robolectric = "4.14.1"` + `androidxTestCore = "1.6.1"` versions + matching `[libraries]` entries.
   - `app/build.gradle`: added `testImplementation libs.robolectric` + `testImplementation libs.androidx.test.core`, and `testOptions.unitTests.includeAndroidResources = true` so Robolectric can resolve string resources from the merged manifest.

**Files created (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (303 lines incl. KDoc).

**Files modified (production, Commit 1):**
- `app/src/main/AndroidManifest.xml` — 4 permissions + new `<service>` entry.
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — 2 imports (ComponentName, ServiceConnection, IBinder); pipeline-binder fields + ServiceConnection (~50 lines); bind in `onCreateInputView`; unbind in `onDestroy`.
- `app/src/main/res/values/strings.xml` — 6 new strings.
- `gradle/libs.versions.toml` — `robolectric` + `androidxTestCore` versions + library entries.
- `app/build.gradle` — `testImplementation` entries for Robolectric + androidx.test.core, plus `unitTests.includeAndroidResources = true`.

**Files created (tests, Commit 2):**
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt` — 10 Robolectric tests with `@RunWith(RobolectricTestRunner)` and `@Config(sdk = [34])`.

**Plan deviations.**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| D4 | State-file §"Test-Strategy" K-4 rule "no Android Context — no Robolectric" + B1 row "Robolectric only for FGS-boot-latency test in chunk C2" | First Robolectric dependency added to the JVM test classpath. Justified inline in `gradle/libs.versions.toml` and at the top of `DictatePipelineServiceTest.kt`. | Spec 1 §10 Phase-B S-5 acceptance "NotificationChannel-vor-startForeground" + "FGS-Boot < 5 s" cannot be expressed against a bare JVM Context — the channel-order assertion needs `NotificationManager.getNotificationChannel`, and `startForeground` needs `Shadow.lastForegroundNotification`. The state-file Test-Strategy explicitly anticipates this row ("Robolectric only for FGS-boot-latency test in chunk C2"). | Block 5 + Block 6 will also need Robolectric (RecordingAnimationController, DefaultOverlayPermissionGate per state-file §"Test-Strategy"). The dep is now in place — no further build-script change required. | inline-fixed |
| D5 | Spec 1 §11.2.2 Block 2 sub-step 7 "JobExecutor-Init wandert vom IME-`onCreate` (Z. 389) in `Service.onCreate` (G7 in §13.5)" | NOT moved in Block 2 — left in `DictateInputMethodService.initLongLivedObjects` Z. 389 as-is. | The move requires constructing `PipelineOrchestrator(aiOrchestrator, autoFormattingService, promptQueueManager, …, this /* PipelineCallback */, …)` inside the service. The constructor has 12 dependencies tied to the IME-service-side architecture (PipelineCallback being the IME-Service itself, RecordingRepository being IME-scoped, etc.). Moving it without the orchestrator-side Composition-Root is a substantial cross-cutting touch that risks creating dual init paths between Block 2 and Block 1b. Spec 1 §7.3's full onCreate-snippet builds the orchestrator + ALL helpers in one composition — Block 1b absorbs JobExecutor.initialize naturally as step 10 of the §4.11.5.1 sequence. Leaving Z. 389 alone in Block 2 keeps the current pipeline working without behavioural regression. | Block 1b absorbs this when it builds the full Composition-Root (Spec 1 §11.2.2 Block 1b sub-step 2 "DictateOrchestrator + ModuleServicesFactory anlegen"). No impact on Block 3/4/5/6 — they consume the orchestrator, not JobExecutor's wiring. | delegated-to-orchestrator (see issue IMPL-1) |

**Issues.**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Important | Spec 1 §11.2.2 Block 2 sub-step 7 calls for `JobExecutor.initialize(pipelineOrchestrator)` to move from `DictateInputMethodService.initLongLivedObjects:389` into `DictatePipelineService.onCreate`. The Block-2 skeleton deliberately keeps JobExecutor wired in the IME service because the move requires constructing PipelineOrchestrator (12-arg constructor with IME-scoped dependencies) and the current PipelineCallback wiring is IME-Service-bound. Block 1b naturally absorbs this when it builds the full Composition-Root per Spec 1 §7.3 (the same snippet that adds DictateOrchestrator + ModuleServicesFactory + PrefMirror). | delegated-to-orchestrator | plan-deviation-resolved-by-deferring-to-block-1b; orchestrator may want to confirm vs Block 1b plan |

**Inline-fixed items.**
- D4 (Robolectric introduction): documented in `gradle/libs.versions.toml` comment block + DictatePipelineServiceTest class-level KDoc. The state-file Test-Strategy row for B1 already anticipates this.

**Overlooked points / known gaps.**
- The `LocalBinder.dispatch(action: Any)` signature uses `Any` as a placeholder. Block 1b widens to `Action` (sealed class) and adds the `state: StateFlow<DictateUiState>` accessor. This is documented in the class-level KDoc and the binder's KDoc — IME-side call sites do not need re-touching since same-process casts already give the binder identity.
- ADR-0003 §"Required mechanics" item 5 ("No WorkManager dependency") is honoured by omission — the build script has no `androidx.work` entry.
- ADR-0003 §"Required mechanics" item 6 (DB-replay recovery on `onCreate`) lands in Block 3 (DB-migration). Block 2 has no DB to read.
- Block-2 Acceptance items in Spec 1 §10 that fall to later blocks: `Phase-B S-5 NOTIF_ID-Konsistenz` (Block 1b — the coordinator-companion replaces the service-companion as SoT), `Phase-B S-5 onDestroy-Timeout` (Block 1b — needs `orchestrator.shutdown()`), `Phase-B S-5 Pre-Bind-Action-Toast` (Block 1b — needs `pipelineBinder.dispatch(action)` to be exercised from a click handler), `Phase-B S-5 POST_NOTIFICATIONS-Prompt` (Onboarding/Settings — separate UI surface, not a service-skeleton concern).
- The IME-side bind-counter (`pipelineServiceBindAttempted`) is a local flag, not a system-level RefCounter. Spec 1 §11.3.4 explicitly forbids a BindRefCounter class — the flag's only job is to avoid spamming `bindService` calls on every view-recreate.

#### Plan-Correctness Fix (B1-C2-IMPL-PLAN-FIX)

Combined into the single invocation above (Step 2). Self-review against ADR-0003 + Spec 1 §5 + §7 + §11.1 + §11.3 + §11.5 confirmed:
- ADR-0003 §"Required mechanics" item 1 (FGS type=microphone) — manifest entry correct.
- ADR-0003 §"Required mechanics" item 2 (startForeground within 5 s) — `onStartCommand` step 1 is `startForegroundCompat`, no blocking work before it.
- ADR-0003 §"Required mechanics" item 3 (Local Binder with `state` + `dispatch`) — binder exposes `service` (Block 1b layers `state` on top) + `dispatch` (Block 2 stub, Block 1b replaces body). Sealed contract preserved.
- ADR-0003 §"Required mechanics" item 4 (bind from `onCreateInputView`) — IME-side binds in `onCreateInputView`, not `onCreate`.
- ADR-0003 §"Required mechanics" item 5 (No WorkManager) — no androidx.work dependency added.
- ADR-0003 §"Required mechanics" items 6-9 (recovery, persistent notification with action buttons, onDestroy ordering, pre-cancel-dispatch) — all Block 1b scope; explicitly documented in class-KDoc and "Overlooked points".
- All 7 Block-2 sub-steps from Spec 1 §11.2.2 covered except sub-step 7 (JobExecutor-move, delegated as D5/IMPL-1).

#### Self-Code Fix (B1-C2-IMPL-CODE-FIX)

Combined into the single invocation above (Step 3). Code-quality fixes applied inline:
- Removed dead `import android.util.Log` after the dispatch-stub got an invocation-counter instead of a log line.
- Renamed/wrapped the test-only fields (`notificationChannelReady`, `stubDispatchCount`) with public read-only getters (`isNotificationChannelReady`, `dispatchInvocationCount`) so the Robolectric test does not poke at private state directly.
- Added inline-anchor `@see` comments on the class-level KDoc (`@see docs/decisions/0003-…` and `@see docs/plans/2026-05-07 - …/research/1-pipeline-service/…`) per knowledge-doc-format §"Inline anchors".
- Channel-order + FGS-budget invariants documented as KDoc blocks inside `onCreate` / `onStartCommand` rather than just inline comments, so the contract is visible from an IDE quick-doc lookup.

Engineering-baseline check (D7 — sustainable / SOLID / Clean Code):
- Single-Responsibility: Service is process-lifecycle-owner only; notification building stays on the service in Block 2 (Block 1b extracts to `PipelineNotificationCoordinator` per Spec 1 §7.4). Documented future-extraction site.
- Open/Closed: the binder's `service` + `dispatch` surfaces are stable; Block 1b widens `dispatch(action: Any)` to `dispatch(action: Action)` without breaking IME-side callers.
- Liskov: no inheritance hierarchy yet (just `Service` and `Binder`); the Block-2 stub respects the framework contracts.
- Interface-Segregation: the binder exposes only what IME-side needs.
- Dependency-Inversion: orchestrator is intentionally absent so Block 1b can inject it cleanly.

#### Tests (B1-C2-IMPL-TEST)

**What was done.**
- New JUnit 4 Robolectric test class `DictatePipelineServiceTest.kt` with 10 tests under `@RunWith(RobolectricTestRunner)` and `@Config(sdk = [34])` (matches the API-34+ FGS type-required code path):
  - **Channel-order (3 tests):** `onCreate_createsNotificationChannel_beforeAnyStartForeground` (Spec 1 §10 Phase-B S-5 acceptance), `notificationChannel_isImportanceLow_andSilent` (channel-config invariant per Spec 1 §11.1.2), `ensureNotificationChannel_isIdempotent_acrossRepeatedOnCreate` (early-return guard).
  - **FGS-5s-budget (2 tests):** `onStartCommand_callsStartForeground_synchronously` (Spec 1 §10 Phase-B S-5 + ADR-0003 Failure-Mode), `onStartCommand_returnsStartNotSticky` (ADR-0003 OOM-recovery contract).
  - **LocalBinder contract (3 tests):** `onBind_returnsLocalBinder_pointingAtTheService` (Spec 1 §5 type contract), `onBind_returnsSameBinderInstance_acrossMultipleCalls` (Spec 1 §11.3.4 Multi-Bind acceptance), `localBinderDispatch_isNoOp_butCountsInvocations` (Block-2 stub contract).
  - **onDestroy (1 test):** `onDestroy_cancelsServiceScope_andSurvivesIdempotently` (cleanup smoke + double-destroy regression guard).
  - **bindService smoke (1 test):** `bindService_smokeTest_doesNotThrow` (manifest-declaration regression guard — pkg manager must resolve the explicit component).

All 10 tests pass on `./gradlew test` (debug + release variants) — `tests="10" skipped="0" failures="0" errors="0"`. The rest of the suite remains green (no cross-test regressions).

**Files created (tests, Commit 2).**
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt`

**No code-bugs found while writing tests.** Block 2 is a skeleton — the channel-order and FGS-budget assertions held on the first run. The Robolectric shadow Service exposes `lastForegroundNotification` + `lastForegroundNotificationId` directly, so no production-code change was needed to make the contract observable.

#### Test-Review (B1-C2-IMPL-TEST-FIX)

Combined into the single invocation above (Step 5). Coverage assessment per Spec 1 §10 Phase-B S-5 acceptance:
- **`Phase-B S-5 NotificationChannel-vor-startForeground`** — covered (`onCreate_createsNotificationChannel_beforeAnyStartForeground`).
- **`Phase-B S-5 FGS-Boot < 5 s`** — covered structurally (`onStartCommand_callsStartForeground_synchronously` asserts the call happens; the 5-second wall-clock budget is a hardware property that this Robolectric test cannot measure, but the structural assertion "step 1 of onStartCommand" is the regression-guard the acceptance asks for — same approach as Spec 1 §10 acceptance "(Robolectric- oder instrumented-Test, p99 < 1 s auf API-34-Test-Device)").
- **`Phase-B S-5 NOTIF_ID-Konsistenz`** — partially covered (`onStartCommand_callsStartForeground_synchronously` asserts the documented `NOTIF_ID` value). Full coverage lands in Block 1b when `PipelineNotificationCoordinator.NOTIF_ID` becomes the SoT and the Service references it.
- **Multi-Bind acceptance (Spec 1 §11.3.4)** — covered (`onBind_returnsSameBinderInstance_acrossMultipleCalls`).
- **`Phase-B S-5 Pre-Bind-Action-Toast`** — explicitly NOT covered (Block 1b — needs the dispatch path to exercise the toast). String resource pre-declared.

The fix to `bindService_smokeTest_doesNotThrow` (Step 5): removed the tautological `assertFalse(..., false)` assertion at the end — JUnit fails the test on any uncaught exception, so reaching the unbind line IS the assertion. Removed the unused `assertFalse` import.

**No code-bugs found during test self-review.**

**Coverage gaps left intentionally:**
- The IME-side bind/unbind lifecycle is not unit-tested here. Robolectric supports InputMethodService but the existing IME-Service is a 2000-line Java class with deep view-side dependencies — a unit test for the bind/unbind code path would require either Mockito (forbidden by Quality-Gate K-1) or a refactor of the IME service that is out of scope for Block 2. The Phase-4.5 E2E runbook (TC-15 keyboard-switch survival) covers the end-to-end IME bind+unbind flow on-device.
- The ServiceConnection's `onBindingDied` rebind code path is not directly tested — Robolectric does not currently fire `onBindingDied` without complex shadow plumbing. The code path is defensive (same-process should not trigger it) and follows Spec 1 §11.3.2 verbatim.

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B1-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B1.md` | — |
| convention | `B1-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B1.md` | — |
| logic | `B1-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B1.md` | — |
| test | `B1-AUDIT-TEST` | ⏳ | `./reports/audit-test-B1.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B1-VAL-SANITY`
**Output file:** `./reports/validated-findings-B1.md`

⏳ to be filled

---

## Block Deviation Summary

⏳ to be consolidated after both chunks + Block-Validate

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step workflow done, both commits per chunk):** ⏳
- **Block-Validate converged (4-topic audit + sanity-pass + repair-waves done):** ⏳
- **AUDIT-TEST: coverage thresholds met for new files, no cross-chunk regressions:** ⏳
- **Build/Lint green at block-end:** ⏳
- **Issue index reconciled (all ids closed/postponed/forwarded):** ⏳
- **Conventions section filled:** ⏳
- **Deviation list propagated to plan/state:** ⏳
- **Cross-block-API consumer info forwarded to Block 2:** ⏳ (B2 reads the DictatePipelineService skeleton API + the predResendVisible helper as foundation for modular orchestrator)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
