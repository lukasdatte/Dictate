# Phase 2 — Section 2 Structure Review

**Section:** Service-Layer + Persistence + Lifecycle
**Plan file (modular):** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.md`
**Spec file:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.md`
**Review target:** Spec 1 §6 (Persistence), §7 (Foreground Service Lifecycle), §8 (IME-Integration), §9 (Migration), §11 (Background-Architektur / Research-TODOs)
**Reviewer role:** Structure (DRY / SOLID / Architecture-Integration). Logic + Clean Code lives with the parallel logic reviewer.
**Worktree:** `/home/lukas/WebStorm/Docs` (review), Code-Cross-Reference `/home/lukas/WebStorm/Dictate`

---

## Step 1 — DRY Check

The single biggest DRY issue in this section is that **§7 + §11 have not been re-aligned with the F-8/F-11 corrections in §4**. §4 explicitly renamed `PipelineStateManager` → `DictateOrchestrator`, replaced typed Action-methods with a single `dispatch(Action)` and split state-mutation into 13 modules. §7.3 + §11 still operate on the old vocabulary, which produces multiple drift-style duplicates (an old API "alongside" the new one). Each is listed as a concrete finding below.

Additional DRY findings:

- **Hardware-Adapter naming drift** — `ModuleServices` (§4.7) declares `BluetoothScoSubsystem`, `AudioFocusSubsystem`, `RecordingHardwareSubsystem`. The current code already ships `BluetoothScoControl` (interface, `BluetoothScoManager.kt:27`), `AudioFocusGate` (interface, `AudioFocusGate.kt:17`) and `BluetoothScoManager` (concrete impl). A new `*Subsystem` family duplicates an established DIP pattern — this is exactly the pattern the user catalog flags as "BluetoothScoControl-DIP-Pattern als Vorbild für Hardware-Adapter".
- **Two ServiceConnection variants** — §7.2 sketches a single-line `bindService(...)` block; §11.3.1 + §11.3.2 give a Java implementation of the same connection with full edge-case handling (`onBindingDied`, `onNullBinding`). They are not contradictory but the §7.2 fragment will reach implementation as a separate copy of the same logic if not merged into §11.3.
- **Two `recoverFromDb` definitions** — §6.3 shows a Kotlin implementation that reads from `db.sessionDao()` directly **and** simultaneously reads SharedPreferences ("Pref-Mirror lesen"). §4.6 (`PipelineRecovery.recover`) reads only via the `PipelineSessionRepo` interface. The §6.3 version violates the Repo abstraction (DAO leak) **and** re-implements pref-mirror logic that already lives in `PipelinePrefMirror` (§4.5). See Issue S-3 + S-4.
- **`ACTION_DISCARD` vs `ACTION_DISMISS`** — §11.1.2 introduces `ACTION_DISCARD`; §7.5 uses `ACTION_DISMISS` for the same semantic ("verwerfen"). Two action-string constants for one user intent — DRY-violation at the contract layer.
- **`stopRecording` / `stopRecordingAndSend`** — §7.5 maps both `ACTION_STOP` and `ACTION_SEND` to `stateManager.stopRecording()` ("semantisch identisch"); §11.1.2 maps `ACTION_SEND` to a separate `stopRecordingAndSend()`. Pick one — either the methods are identical (then §11.1.2 is dead code) or they differ (then §7.5 is wrong). Both descriptions cannot coexist.
- **§9 inline-resend stand-in vs LayoutCatalog-Predicate** — §11.2.2 Block-1 step 5 prescribes a transitional `resendButton.visibility = ...`-Subscriber inside `PipelineStateManager` that duplicates exactly the predicate that §9.4 / Spec 2 §9.5 will own. This is intentional (called out as transitional), but the spec needs to also state which spec section in Block 5 deletes the transitional subscriber, otherwise the duplicate solidifies.

Internal repetition that does **not** rise to a finding: `PipelineActionRouter.dispatch` (§7.5) and `onStartCommand` (§11.1.2) have similar `when (intent.action)`-blocks — but §7.5 explicitly extracts §11.1.2's logic into the router. After the F-3 correction §11.1.2 should be deleted; see Issue S-1.

---

## Step 2 — SOLID Check

### SRP

- **PipelineStateManager (§7.3 + §11)** is described as the owner of recording-hardware-construction, pref-mirror, recovery, JobExecutor-init, AudioFocusGate consumption (§11.7.3) and `state` mutation. §4.3 explicitly removes that role from one class and splits it across `DictateOrchestrator` + `PipelinePrefMirror` + `PipelineRecovery` + 13 Modules. §7/§11 leaving the old name + old monolithic responsibility intact is an SRP regression and a self-contradiction inside the spec (§4 says "is no longer; §7 still wires it as Composition Root). See Issue S-1.
- **DictatePipelineService.onCreate (§7.3 / §11.1.4)** does five things synchronously: notification-channel-create, store-construct, sessionRepo-construct, runner+prefMirror+recovery construct, NotificationCoordinator + ActionRouter construct, `JobExecutor.initialize`. The §13.3.1 self-audit claims the service is a *Process-Lifecycle-Owner* — but the code in §7.3 keeps wiring in `onCreate` itself rather than in a dedicated `ServiceComponent`/Composition-Root helper. With ~12 collaborators that is a SRP smell ("Service is also DI-Container"). Recommendation: a `PipelineServiceComponent.create(service)` factory that returns a tuple `(orchestrator, notifCoordinator, actionRouter)` — the service's `onCreate` then has six lines.
- **PipelineSessionRepo / SessionDao (§6.1 + §11.6.2)** — §11.6.2's `recoverFromDb` calls `db.sessionDao().getByStatus("RECORDED")` directly + writes `dao.updateStatus(...)` + `dao.updateError(...)` from inside the StateManager. That mixes Repository-layer concerns into the orchestrator. Either the Repo grows the missing methods (`findOrphanedRecorded`, `markGhostFailed`) or this code lives in `PipelineRecovery`. Today's §4.6 `PipelineRecovery` only does `loadPending` — it should own the ghost-cleanup too. See Issue S-3.
- **PipelineNotificationCoordinator (§7.4)** — clean SRP. State→Notification + throttled subscription. One small SRP overlap: §7.4 owns the terminal-detection logic (`onTerminal` callback fires `stopSelf`); strictly that is a service-lifecycle decision, not a notification-render decision. Refactor: `PipelineTerminalDetector` (one method `isAllTerminal(state)`) so the same check is reusable for cleanup-policy in §11.6.2. Nice-to-have only.
- **PipelineActionRouter (§7.5)** — clean SRP, except `pendingIntentFor` is exposed for the `NotificationCoordinator` to use (§7.4 builds notifications, §7.5 builds pendingIntents — coupled). That is fine but the spec should explicitly state the wiring (`coordinator = PipelineNotificationCoordinator(service, state, scope, actionRouter)`) instead of leaving the dependency implicit.

### DIP

- **§4.9 declares `PipelineSessionRepo` + `PipelineRunner` as the abstractions**. §6.3, §11.6.2 and §11.2.3 all bypass `PipelineSessionRepo` and reach directly into `db.sessionDao()`. The DIP claim in §13.3.6 / §13.3.11 is therefore **structurally not held** by the section. See Issue S-3.
- **`recordingHardware = RecordingHardware(audioManager, ...)` (§7.3 line 1040)** instantiates a concrete class inline. There is no `RecordingHardwareSubsystem`-interface declaration anywhere in the spec — only the *type-name* in `ModuleServices` (§4.7). For DIP to actually hold, the spec must declare the interface (one of the existing `BluetoothScoControl` / `AudioFocusGate` patterns is the proven model). Today the StateManager will still depend on `RecordingHardware`-concrete. See Issue S-2.
- **`PipelineActionRouter` (§7.5)** depends on `PipelineStateManager` directly (concrete). With F-8/F-11, the router must depend on either the `LocalBinder` or the `DictateOrchestrator`-interface — otherwise the router cannot be tested without instantiating the full orchestrator. Suggestion: depend on `(action: Action) -> Unit` (a function reference). The §13.3.8 self-audit still names "PipelineStateManager" — the audit was not updated for F-8/F-11 either.

### LSP

- **Module-substitution claim (§13.3.13)** is structurally fine because of `sealed interface DictateModule`. No Liskov issues at the interface level.
- **`PipelineSessionRepo` test-doubles (§11.7.3)** — `FakeSessionDao` is listed but `FakePipelineSessionRepo` is the *abstraction* a Repo-DIP-clean spec would test against. Today both are listed (`FakePipelineSessionRepo` referenced in §13.3.6 + §13.3.11 audit; `FakeSessionDao` listed in §11.7.3). Substitutability is OK; but the *direction* of the test boundary contradicts itself. See Issue S-3 (related).

### ISP / OCP

- **`PipelineSessionRepo` (§4.9)** is minimal (4 methods). ISP-clean.
- **§9.4-actionResolver** has `actionResolver = { /* short-press: re-run pipeline; long-press: enter staging */ }` — a single resolver lambda taking two semantically different actions. That is borderline ISP/OCP for the slot interface (Spec 2 owns the slot definition; not in scope here, but flagged as a cross-section concern).
- **§7.5 ACTION_*-constants** — adding a new action string requires touching: AndroidManifest? (no), the action-string companion (yes), the `dispatch`-when (yes), the `pendingIntentFor` callers (yes). Reasonable OCP — extension points are localized.

---

## Step 3 — Architecture Integration

### Layer / Module Structure

- **Class-renaming inconsistency** — the spec is internally split between two architectures:
  - §4 + §13.3.x: `DictateOrchestrator`, `dispatch(Action)`, modular plugin
  - §7.3 + §9.1 + §11: `PipelineStateManager`, typed action-methods (`pauseRecording()`, `cancelPipeline()`, `confirmInsertion()`, `toggleAudioFocus()`, …)
  
  These cannot both be true. Either §7+§11 are stale (most likely — F-11 is dated 2026-05-09, after the original §7/§11 drafts) or the orchestrator has a typed-method facade not described in §4. A future implementer will copy §7.3's `onCreate` verbatim and end up wiring a class that no longer exists. **Critical**. See Issue S-1.

- **Hardware-Subsystem location** — Subsystem classes (RecordingHardware, BluetoothScoSubsystem, AudioFocusSubsystem) are not assigned a package in the spec. Existing convention puts them in `app/src/main/java/net/devemperor/dictate/core/`. The spec needs an explicit "Subsystem-Verzeichnis" line, otherwise hardware adapters end up scattered.

- **`recoverFromDb` location** — §6.3 places `recoverFromDb` on `PipelineStateManager` (suspend method). §4.6 places `recover(store)` on `PipelineRecovery`. §11.6.1's `onCreate`-snippet calls `stateManager.recoverFromDb()`, **not** `recovery.recover(store)`. With F-11 the orchestrator delegates to recovery — §11.6.1 has not been updated. See Issue S-1.

### Coroutine-Scope Ownership (Lifecycle-Konsistenz)

The plan defines exactly two scopes — `serviceScope` (lives with the FGS, §7.3) and `viewScope` (lives with the IME view, §5/§11.3). That is correct in principle and §13.4.2 acknowledges "two scopes by design".

Open structural issues with scope-ownership:

- **Recovery + Pref-Mirror initial-read run on `serviceScope` (Main-immediate, §7.3 line 1021)** — §11.1.4 explicitly warns about the 5-second-FGS-deadline. `PipelineRecovery.recover` is a `suspend` IO call. §11.6.1 dispatches it on `Dispatchers.IO` inside the launch — but §4.3's `init { scope.launch { recovery.recover(store) } }` does **not** specify `Dispatchers.IO`. With `Dispatchers.Main.immediate` as the scope context, this is a Main-thread DB-IO read. **Important**. See Issue S-5.

- **Effect-Handler scope ownership undefined** — §4.7 `ModuleServices` carries a `scope` field that EffectHandlers can use. The spec never says **which** scope. If it is `serviceScope`, then long-running effects (e.g. PipelineRunner.submit) will keep the service alive past `stopSelf`-eligibility. If it is a fresh per-action scope, cancellation is unclear. Recommendation: explicit "all module-effects run on `serviceScope`; effects that must survive service-stop run on a `processLifecycleScope`-equivalent". Without this rule, lifecycle correctness is implementation-discretion. **Important**. See Issue S-6.

- **`PipelineNotificationCoordinator.startReactiveUpdates` (§7.4)** runs `state.collect` on `serviceScope`. Throttled `distinctUntilChanged` is correct, but the coroutine never terminates of its own accord — the spec must state that `serviceScope.cancel()` in `onDestroy` (§7.3 line 1065) is the only termination. This is implicit but should be a documented invariant; otherwise a careless `notifCoordinator.shutdown()` could be added later and double-cancel.

- **Cross-process-death scope** — when the FGS is killed and re-started, the new `serviceScope` has no replay knowledge of in-flight `PipelineRunner.submit` jobs from the previous incarnation. §11.6 covers DB-recovery; it does **not** cover "what happens to the JobExecutor.activeToken from the dead process". `JobExecutor` is a process-singleton (§11.7.3 + comment in §13.3.11) — when the process dies, both die together. That is fine, but the spec should state it (current §11.6.3 only says "User must click Restart"). **Nice-to-have** documentation gap.

### Migration §9 — Reihenfolge

The ordering Block-1 → Block-2 → Block-3 (§11.2.2) is sensible: state-SSOT first (without service / DB-schema), then service hosting, then DB-schema. Two structural concerns:

- **§9.1 RecordingStateController-Migration is described as "wandert komplett in `PipelineStateManager`"**. Given F-11, it actually wanders into `RecordingModule.reduce` + `RecordingModule.runEffect`. The migration table at §9.1 is therefore **wrong against §4 / §15.2**. Implementer reading §9.1 will produce a fat `PipelineStateManager` even though §4 already replaced it. **Critical**. See Issue S-1.

- **§9.2 references `KeyboardUiController.refreshRecordButtonFromState` migration to "RECORD-Slot-textResolver" in Spec 2 §9.5** — that is a cross-spec dependency. The spec should explicitly mark this step as "blocked by Spec 2 implementation", otherwise Block-1 cannot complete the migration alone. Today §9.5 says "ein einziger textResolver in Spec 2 §9.5" but Block-1 acceptance (§10) says "in Block 5 dann final in LayoutCatalog" — meaning: Block-1 needs a temporary resolver inside `KeyboardUiController`. That is a transitional duplicate that should be called out as such with a deletion-target. **Important**.

- **§9 is silent about RecordingUiController** — §9.4 mentions "5 verstreute resend_btn-Mutationen" in `RecordingUiController.kt` and §9.5 mentions it again, but there is no dedicated "§9.X RecordingUiController" entry. RecordingUiController is heavily mutated today (`applyIdleState`, `applyActiveState`, `applyPreparingState`); the spec should state explicitly: "RecordingUiController is replaced wholesale by LayoutCatalog-resolvers in Spec 2; in Block 1 it is reduced to a thin pass-through". Without this, the migration table has a silent gap. See Issue S-7.

### Pattern Catalog Compliance

- **`StateFlow.update`** — used correctly throughout (§3.0, §4.4, §6.3, §6.4, §15.2 examples). DRY-clean.
- **`BluetoothScoControl`-DIP-Pattern** — *not* used as the model for `BluetoothScoSubsystem`. See Issue S-2.
- **`JobExecutor`-Singleton** — §4.9 says "JobExecutor (statisches object adaptiert das Interface)". The init now moves to Service-onCreate (§7.3 line 1045 / G7). Structural concern: a `static object` that has process-lifetime is being initialized on a service-lifetime trigger. If the service is killed and restarted in the same process (FGS-relaunch without process-death), `JobExecutor.initialize` will be called twice. The spec must specify whether `initialize` is idempotent. **Important**. See Issue S-8.
- **`ActiveJobRegistryObserver` Java-Brücke** — not mentioned in §6-§9 / §11 at all. If the observer ever needs to receive a process-restart event, the spec is silent on whether it survives across `serviceScope.cancel()`. Probably fine because it lives on `JobExecutor` (process-lifetime), but a sentence in §11.6 would close the loop.

### File / Manifest Integration

- **AndroidManifest §11.1.1** — additive, follows existing convention (compare to existing `<service>` block at `:29-40`). Clean.
- **DB schema §6.1 / MigrationTo4.kt** — file-naming follows existing pattern (`MigrationTo3.kt` precedent at `database/migration/`). Clean.
- **`SessionDao`-additions §6.1** — mixes `id` types: existing DAO uses `String id` (per `markInserted(id: String, …)`); §11.6.2 ghost-cleanup uses `it.id` directly without specifying the type but `markInserted(it.id, …)` would have to match. §7.5 / §11.5 use `Long sessionId` for action-extras (`putExtra(EXTRA_SESSION_ID, …)` is `Long`). **Type inconsistency**: ID is `String` in DAO, `Long` in action-payload. **Critical**. See Issue S-9.

### Global-Issues Cross-Check

- **🟡 1.1.4 LayoutModule SRP-Smell** — `LayoutModule` is in §15.1 (axis: `singleRowMode, smallMode, animationsEnabled, contentArea`). It does **not** appear in §6-§9/§11 (this section's review-target). Not relevant for Section 2, except indirectly via §9.3 (`KeyboardStateManager.contentArea` → `DictateUiState`). No impact on this section's structure.
- **🟡 1.1.6 Block-1-Aufwand massiv unterschätzt** — confirmed: §9.1 + §9.2 + §9.3 + §9.4 + §9.5 each describe non-trivial migrations. With F-11 (each migration also reroutes through a Module), the work doubled. The spec should add an explicit complexity note. Out of structure-scope, flagged for the orchestrator-section reviewer.
- **🟡 1.1.7 buildContext synchroner Hardware-Call** — §4.3 line 489 is `recordingAudioFile = servicesFactory.get().recordingHardware.currentAudioFile()`. That is a hardware-call in the synchronous `dispatch`-path. Relevant for §11 background-arch: the dispatch chain must remain non-blocking. **Important**. See Issue S-10.

---

## Findings

### Issue S-1: §7 + §11 not aligned with F-8/F-11 architecture (PipelineStateManager vs DictateOrchestrator)

- **Category:** [INTEGRATION] (with knock-on [SOLID-SRP] + [DRY])
- **Severity:** Critical
- **Location:** Spec 1 §7.3 (lines 1018-1067), §9.1 (lines 1207-1233), §9.2 (lines 1234-1257), §11.1.4 (lines 1530-1547), §11.2.2 Block-1 (lines 1559-1581), §11.6.1 (lines 1831-1845), §13.3.8 (line 2130)
- **Description:** §4 (Iteration 2026-05-08/09) replaced `PipelineStateManager` with `DictateOrchestrator` and replaced typed-action-methods with `dispatch(action: Action)`. §7 + §11 still document the old class with its old typed API (`stateManager.pauseRecording()`, `stateManager.cancelPipeline()`, `stateManager.recoverFromDb()`, `stateManager = PipelineStateManager(...)`). An implementer following §7.3's `onCreate` will instantiate a class that §4 says no longer exists, and will write typed forwarder-methods that §4 explicitly removed. This is an internal contradiction, the most damaging kind of plan-defect because both halves are detailed code-skeletons.
- **Affected codebase files:** none yet (greenfield); but the §9.1 + §9.2 migration tables are guides for moving `RecordingStateController.kt`, `KeyboardUiController.kt`, `KeyboardStateManager.kt` — they will guide the work toward the wrong target.
- **Suggestion:** Re-write §7.3 to construct `DictateOrchestrator` instead of `PipelineStateManager`. Re-write §7.5 to dispatch typed Actions (`stateManager.pauseRecording()` → `orchestrator.dispatch(Action.RecordingAction.Pause)`). Re-write §9.1 / §9.2 / §9.3 migration-targets to "→ RecordingModule.reduce + RecordingModule.runEffect" / "→ PipelineModule" / "→ LayoutModule + ViewModeModule". Re-write §11.6.1 to call `recovery.recover(store)`. Add a single "§7.0 Klassennamen" cross-reference table that pins the §4-vocabulary as authoritative. Update §13.3.8 to reflect router→orchestrator (not router→stateManager).

### Issue S-2: Hardware-Subsystem-Adapter duplicate the existing BluetoothScoControl/AudioFocusGate DIP pattern

- **Category:** [DRY] + [SOLID-DIP]
- **Severity:** Important
- **Location:** Spec 1 §4.7 (lines 628-643), §7.3 line 1040 (`recordingHardware = RecordingHardware(audioManager, ...)`)
- **Description:** Pattern-Catalog (Phase 1) names `BluetoothScoControl`-DIP-Pattern as the vorbild. The codebase ships `BluetoothScoControl` (`BluetoothScoManager.kt:27`) and `AudioFocusGate` (`AudioFocusGate.kt:17`) as interfaces with concrete `BluetoothScoManager`/production-impl + test-fakes (`FakeAudioFocusGate.kt`). The spec introduces three new `*Subsystem` types (`BluetoothScoSubsystem`, `AudioFocusSubsystem`, `RecordingHardwareSubsystem`) without reusing the existing interface names — and §7.3 line 1040 instantiates `RecordingHardware(audioManager, ...)` as a concrete class with no interface declared anywhere. Result: the section's "DIP via interfaces" claim (§13.3.15) is structurally not realised; new type-names duplicate the existing DIP-pattern.
- **Affected codebase files:**
  - `app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt:27` (existing `BluetoothScoControl` interface)
  - `app/src/main/java/net/devemperor/dictate/core/AudioFocusGate.kt:17` (existing `AudioFocusGate` interface)
  - `app/src/test/java/net/devemperor/dictate/core/FakeAudioFocusGate.kt` (existing test-fake)
- **Suggestion:** Either (a) rename `BluetoothScoSubsystem` → `BluetoothScoControl` (re-use existing interface) and `AudioFocusSubsystem` → `AudioFocusGate`, deleting the new names; or (b) keep new names but explicitly state that they *are* the existing interfaces re-imported. For `RecordingHardwareSubsystem`, declare the interface in §4.9 alongside `PipelineSessionRepo`/`PipelineRunner`, with the existing `RecordingManager` as the concretisation (or define a thin facade that wraps it). Without an interface, §7.3 line 1040 is a DIP-violation.

### Issue S-3: PipelineSessionRepo abstraction bypassed in §6.3 + §11.6.2

- **Category:** [SOLID-DIP] + [SOLID-SRP] + [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 1 §6.3 (lines 906-922), §11.6.2 (lines 1853-1873)
- **Description:** §4.9 declares `PipelineSessionRepo` as the abstraction. §13.3.6 + §13.3.11 audit-self-claim that `PipelineRecovery` "hängt am `PipelineSessionRepo`-Interface". But §6.3's `recoverFromDb` reads via `db.sessionDao().getSessionsByStatuses(...)` directly. §11.6.2 reads `db.sessionDao().findPendingInsertion()`, `getByStatus("RECORDED")`, `updateStatus`, `updateError` — all DAO-direct. The Repo-abstraction is silently bypassed for everything except the happy-path `loadPending`. This both violates DIP (the orchestrator now depends on Room DAOs again) and violates SRP (state-manager / orchestrator pulls IO + business-policy logic — "ghost-session-cleanup" is repository-policy, not state-policy).
- **Affected codebase files:** `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt`
- **Suggestion:** Grow the `PipelineSessionRepo` interface to cover every DB call this section wants:
  ```kotlin
  interface PipelineSessionRepo {
      suspend fun loadPending(): List<PendingSession>
      suspend fun loadOrphanedRecorded(): List<PendingSession>          // NEW (§11.6.2)
      suspend fun markGhostFailed(sessionId: String, reason: String)    // NEW (§11.6.2)
      suspend fun markInserted(sessionId: String, at: Long)
      suspend fun markFailed(sessionId: String, reason: String)
      suspend fun deleteInsertedOlderThan(cutoffMs: Long): Int          // NEW (§11.7 cleanup-policy)
      fun pendingFlow(): Flow<List<PendingSession>>
  }
  ```
  Move the §11.6.2 ghost-cleanup-loop into `PipelineRecovery.recover()` (or a sibling `PipelineCleanup.runOnStart()`). The orchestrator never sees the DAO.

### Issue S-4: §6.3 Pref-Mirror bypass duplicates PipelinePrefMirror

- **Category:** [DRY] + [SOLID-SRP]
- **Severity:** Important
- **Location:** Spec 1 §6.3 (lines 911-919) — "Pref-Mirror lesen (siehe §6.4): Overlay-Position pro Orientation … `prefs.getFloat(Pref.OverlayPositionPortraitX, 1.0f)` …"
- **Description:** §4.5 `PipelinePrefMirror` already holds the canonical pref-→-state mapping (overlay positions among them: §4.5 lines 568-572). §6.3's `recoverFromDb` re-reads the same prefs directly from `PreferenceManager.getDefaultSharedPreferences(ctx)` and writes them into `_state.value.copy(overlayPositionPortraitX = …)`. Two readers, two writers, two write-paths into the store for the same data. With F-10 the overlay is `state.overlay.positionPortraitX` (sub-state), not the flat `overlayPositionPortraitX` shown in §6.3 — the snippet is also out-of-date with the F-10 state model.
- **Affected codebase files:** none yet
- **Suggestion:** Delete the pref-read block in §6.3. `PipelinePrefMirror.attach(store)` runs in `DictateOrchestrator.init` (§4.3) before `recovery.recover(store)`, so the overlay-position is already in the store when recovery starts. `recoverFromDb` only needs to do `pendingSessions =` mutation. As a side effect this removes the F-10-mismatch (flat field vs. sub-state).

### Issue S-5: scope-ownership for recovery-IO undefined; risk of Main-thread DB-call

- **Category:** [INTEGRATION] (Lifecycle-Konsistenz)
- **Severity:** Important
- **Location:** Spec 1 §4.3 line 439 (`scope.launch { recovery.recover(store) }`), §7.3 line 1021 (`serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`), §11.6.1 (lines 1841-1843)
- **Description:** `serviceScope` is `Dispatchers.Main.immediate`. §4.3's recovery launch does NOT switch to `Dispatchers.IO`. §11.6.1 explicitly switches to `Dispatchers.IO` inside the launch. The two snippets disagree — and §4.3 (the Composition Root) is the one an implementer will copy. Result: DB-IO on Main-thread on every service start, blocking everything for 100-500ms (per §11.6.1's own estimate).
- **Affected codebase files:** none yet
- **Suggestion:** Either declare `recovery.recover(store)` as `suspend fun recover(store) = withContext(Dispatchers.IO) { ... }` (the §11.6.2 sketch already does this — make it canonical in §4.6), or change §4.3 to `scope.launch(Dispatchers.IO) { recovery.recover(store) }`. Pick one and remove the other. Same rule applies to all "DB-write Checkpoint-Hooks" in §6.2 — §11.2.2 step 5 says "in einem `scope.launch(Dispatchers.IO)`" but §6.2 itself shows no scope at all.

### Issue S-6: Effect-Handler scope ownership undefined in ModuleServices

- **Category:** [INTEGRATION] (Lifecycle-Konsistenz) + [SOLID-DIP]
- **Severity:** Important
- **Location:** Spec 1 §4.7 line 641 (`val scope: kotlinx.coroutines.CoroutineScope`), §11 silent on the question
- **Description:** `ModuleServices` carries one `scope` field. The spec never says which scope this is or what its cancellation semantics are. EffectHandlers (`runEffect(effect, services)` per §4.2) will reach for `services.scope` to launch background work. Three problems:
  1. If it is `serviceScope`, then any effect that out-lives `state.isAllTerminal()` (e.g. a network upload still running when `stopSelf` is triggered) will be cancelled mid-flight. May or may not be the intent.
  2. If it is a fresh per-effect scope, cancellation on service-stop is impossible.
  3. The §4.7 declaration uses *concrete* `CoroutineScope`, not an interface — DIP-leak (the production scope is `serviceScope` but tests want a `TestScope`; trivially satisfied because `TestScope` is a `CoroutineScope`, but the spec should call this out).
- **Affected codebase files:** none yet
- **Suggestion:** Add §4.7 contract clause: "`scope` is the FGS `serviceScope`. EffectHandlers MUST NOT launch work outside this scope. Effects whose user-visible lifecycle exceeds the service (none currently expected) must be re-modelled as a separate Worker." Document the `serviceScope.cancel()` happens in `Service.onDestroy` (§7.3 line 1065) — that section should have an explicit "all in-flight effects are cancelled" sentence so the implementer does not add a `try { ... } finally { fireOneMoreThing() }` pattern.

### Issue S-7: §9 has no entry for RecordingUiController; migration table silently incomplete

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 1 §9.1-§9.5 (lines 1207-1338) — RecordingUiController appears only as a duplication-source in §9.4 / §9.5, never as a migration-target.
- **Description:** Today `RecordingUiController.kt` owns `applyIdleState`, `applyActiveState`, `applyPreparingState` — heavy view-mutation logic that cannot survive the refactor unchanged. §9.1-§9.5 explicitly migrates `RecordingStateController` (§9.1), `KeyboardUiController` (§9.2), `KeyboardStateManager` (§9.3), but the equivalent §9.X for `RecordingUiController` is missing. §9.4 + §9.5 *partially* cover its surface but there is no statement what *the rest* of the file becomes. Implementer following §9 sequentially will either keep `RecordingUiController` alive (hybrid pattern survives) or delete it without replacement (loses Idle/Active/Preparing-render code that is nowhere else specified).
- **Affected codebase files:**
  - `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` (lines 115-184)
- **Suggestion:** Add §9.6 "RecordingUiController → LayoutCatalog-Resolvers (Spec 2)". Map each method to its replacement: `applyIdleState`/`applyActiveState`/`applyPreparingState` → state-driven `slot.apply(view, state)` in Spec 2 LayoutCatalog. State explicitly "in Block 1, RecordingUiController is reduced to a transitional wrapper that subscribes to `state.collect`; in Block 5 it is deleted entirely". Without this section the migration has a 100-line silent gap.

### Issue S-8: JobExecutor.initialize idempotency unspecified across service-restart

- **Category:** [INTEGRATION] (Lifecycle-Konsistenz)
- **Severity:** Important
- **Location:** Spec 1 §7.3 line 1045 (`runner.initialize(orchestrator)   // G7: JobExecutor-init wandert hierher (vom IME-onCreate)`), §13.5 G7 (lines 2215)
- **Description:** `JobExecutor` is a `static object` (process-singleton). The init is moved from IME-onCreate (called once per IME-restart) to Service-onCreate (called once per FGS-start). FGS can restart within the same process (e.g. after `stopSelf` followed by a new `startForegroundService`) — the same `object JobExecutor` will see `initialize(...)` called twice. The §13.5 G7 mitigation says "defensiv-`null`-Check in JobExecutor + lazy-init beim ersten Job-Start" — which is the *opposite* mitigation (covers under-init, not double-init). Today `JobExecutor.initialize` is not in the spec, so its body is unknown — but if it allocates threads or registers listeners, the second call will leak.
- **Affected codebase files:** `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` (line 332 referenced by spec, current `initialize` body)
- **Suggestion:** Either (a) make `JobExecutor.initialize` idempotent (no-op on second call) and document it in §11.7.3, or (b) move the call site to a guard: `if (!runner.isInitialized()) runner.initialize(orchestrator)`. Update §13.5 G7 mitigation to address the *over-init* case explicitly. As a structural alternative, drop the static-object pattern entirely and inject a `JobExecutor` instance via `ModuleServices` — that is the cleaner DIP solution but is out of scope for this section's review.

### Issue S-9: Session-ID type inconsistency (String in DAO, Long in Action-extras)

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 1 §6.1 line 869 (`fun markInserted(id: String, timestamp: Long)`), §7.5 line 1132 (`intent.getLongExtra(EXTRA_SESSION_ID, -1L)`), §7.5 line 1135 (same), §11.4.2 line 1757 (`'s1'` — String), §11.5 (Long throughout actions)
- **Description:** §6.1 specifies `markInserted(id: String, timestamp: Long)` because today's `SessionEntity.id` is a String. §7.5 dispatches `confirmInsertion(it)` where `it` comes from `getLongExtra(EXTRA_SESSION_ID, -1L)` — Long. The two cannot connect: `stateManager.confirmInsertion(it: Long)` cannot call `dao.markInserted(it: String)` without a conversion the spec does not show. §11.4.2 cements String IDs ('s1'/'s2') in the migration test. Either the action-extra must be `putExtra(... String)` + `getStringExtra`, or the DAO must take Long. The internal-stub for action-router-Insert at §7.5 line 1130-1134 shows the type-mismatch bug at compile time.
- **Affected codebase files:**
  - `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` (today: String id)
  - `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt`
- **Suggestion:** Decide the canonical type. Options:
  - (a) Use `String` throughout — change all `getLongExtra` to `getStringExtra`, change `EXTRA_SESSION_ID` payload-type to String.
  - (b) Use `Long` (UUID-rowid mapping) — requires a `SessionEntity.numericId` column added in M3→M4 migration alongside `inserted_at`, and `markInserted(numericId: Long)`.
  - Document the choice in §6.1 with a one-line justification ("session-IDs are String-UUIDs because they are generated client-side before DB-insert" or similar). Until done, Block-2 cannot compile.

### Issue S-10: ReducerContext.buildContext does sync hardware-call inside dispatch

- **Category:** [SOLID-SRP] + [INTEGRATION]
- **Severity:** Nice-to-have (already flagged Phase 1 1.1.7 — section-relevance noted here)
- **Location:** Spec 1 §4.3 lines 487-490 (`buildContext = ... recordingAudioFile = servicesFactory.get().recordingHardware.currentAudioFile()`)
- **Description:** Phase 1 1.1.7 flagged "buildContext synchroner Hardware-Call". For Section 2 the relevant question is: does the Service-Layer make this Main-thread blocking? `currentAudioFile()` is presumably a `File`-getter on the recording subsystem, almost certainly cheap. But the principle of a *pure* reducer requires a context that is *captured* before reduce, not *computed during dispatch via hardware*. If `currentAudioFile()` ever grows (e.g. file-stat for size), the dispatch loop on `serviceScope.Main.immediate` blocks. Structural concern: the seam between "hardware-state" and "reducer-state" is not enforced by the type-system — `RecordingHardware.currentAudioFile()` is reachable from inside reducer-context-building.
- **Affected codebase files:** none yet
- **Suggestion:** Lift `recordingAudioFile` into `DictateUiState` (sub-state of `recording`) — the file path is already known when recording starts. `ReducerContext` then carries only the data already in the store. Removes the sync-hardware-call-on-dispatch pattern. Section-2 impact: spec §3 (the data-model section, not in this review-target) needs the sub-state field.

---

## Summary Table

| # | Category | Severity | Issue | Description |
|---|----------|----------|-------|-------------|
| S-1 | [INTEGRATION] | Critical | §7+§11 not aligned with F-8/F-11 | `PipelineStateManager` vocabulary still in §7.3, §9.1-§9.3, §11.x; §4 has replaced it with `DictateOrchestrator` + 13 modules. Internal contradiction; implementer cannot follow both. |
| S-2 | [DRY] + [DIP] | Important | Hardware-Subsystem-Adapter duplicate existing DIP pattern | New `*Subsystem`-types ignore existing `BluetoothScoControl`/`AudioFocusGate` interfaces; `RecordingHardware` instantiated as concrete with no interface declared. |
| S-3 | [DIP] + [SRP] + [INTEGRATION] | Important | PipelineSessionRepo bypassed in §6.3 + §11.6.2 | DAO calls direct from orchestrator; ghost-cleanup is repo-policy bleeding into state-policy. |
| S-4 | [DRY] + [SRP] | Important | §6.3 pref-mirror bypass duplicates PipelinePrefMirror | Overlay-positions read twice (mirror + recovery), with F-10 sub-state mismatch. |
| S-5 | [INTEGRATION] | Important | Recovery-IO scope ambiguity (Main vs IO) | §4.3 launches recovery without Dispatchers.IO; §11.6.1 with — implementer copies §4.3 → blocks Main. |
| S-6 | [INTEGRATION] + [DIP] | Important | Effect-Handler scope ownership undefined | `ModuleServices.scope` not pinned; lifecycle / cancellation semantics open. |
| S-7 | [INTEGRATION] | Important | §9 silent on RecordingUiController migration | Three view-render methods have no migration-target; gap between §9.4 + §9.5 + Spec 2. |
| S-8 | [INTEGRATION] | Important | JobExecutor.initialize idempotency | Service-restart calls init twice on process-singleton; §13.5 G7 mitigation addresses wrong direction. |
| S-9 | [INTEGRATION] | Critical | Session-ID type inconsistency String vs Long | §6.1 DAO takes String; §7.5 / §11.5 action-extras are Long. Compile-time bug for Insert-action. |
| S-10 | [SRP] + [INTEGRATION] | Nice-to-have | ReducerContext sync-hardware-call | `buildContext` reaches into RecordingHardware on every dispatch; section-relevance of Phase-1 1.1.7. |

---

## Reviewer Notes

- **Out of scope** (not in §6-§9 / §11): Spec 1 §3 data-model, §4 (Orchestrator + Modules) — those belong to Section 1's reviewer. References made above are read-only context to identify drift in *this* section's documents.
- **Logic-track items intentionally left out** of this review (forwarded to logic-reviewer): action-name semantics (`stopRecording` vs `stopRecordingAndSend`), terminal-state correctness, `notifSubtitleFor` content correctness, recoverFromDb-User-Communication.
- **Severity bias:** Two Criticals (S-1, S-9) reflect concrete contradictions with type-/name-mismatches that block a compile. Other Importants are SRP/DIP defects that ship working code but rot fast. S-10 is Nice-to-have because the symptom is small today; the principle is architectural.
