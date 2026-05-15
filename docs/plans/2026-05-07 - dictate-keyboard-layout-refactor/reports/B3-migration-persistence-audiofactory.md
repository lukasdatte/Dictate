# Block 3: Migration-Persistence-AudioFactory

> **This file is the logbook for Block 3.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Migration-Persistence-AudioFactory (plan-Block-3 + plan-Block-4)
**Implementation-Chunks:** C8-block3-subsystem-adapter-migration (M, 700 score) · C9-block3-db-persistence-schema-m4 (M, 850 score) · C10-block3-db-persistence-recovery (M, 600 score) · C11-block4-audio-file-factory (L, 1000 score)
**Workflow:** Iter-10 5-step workflow with orchestrator-split commits per chunk. Block runs C8 → C9 → C10 → C11 sequentially.
**Block-Start-Commit:** `b97e09a`
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:**
- Critical: 0
- Important: 0 (IMPL-1 closed in C8)
- Nice-to-have: 0
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| IMPL-1 (B1→B2→B3 carry-over) | B1-C2-IMPL-FULL | Important | fixed | Spec 1 §11.2.2 Block-2 sub-step 7: JobExecutor-Init move from IME `onCreate` to Service `onCreate` — closed in C8 (JobExecutor.initialize + AI infrastructure construction moved to DictatePipelineService.onCreate; IME pulls references via LocalBinder in onServiceConnected) | C8 scope |
| SF-4 (B2 carry-over) | B2-VAL-RES-1 | NTH | open (delegated-to-orchestrator) | Post-extraction-failure manual-paste-flag wiring — recovery-path responsibility | C10 scope |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|

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

### Chunk C8-block3-subsystem-adapter-migration — Subsystem-Adapter-Migration

**Agent-IDs:** Steps 1-5 (combined): `B3-C8-IMPL-FULL`

**Status:** ✅ done (Steps 1-5 in single invocation; orchestrator splits diff into 2 commits)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 9 (C8-block3-subsystem-adapter-migration)
**Implementation-Commit:** ⏳ (orchestrator to assign — production diff)
**Test-Commit:** ⏳ (orchestrator to assign — tests diff)

#### Implementation (B3-C8-IMPL-FULL)

**What was done:**
1. **Subsystem adapters (Spec 1 §9.6 + §15.x)** — created production-quality adapter classes that implement the orchestrator-side subsystem interfaces (`*Subsystem` / `*Sink`) and wrap or replace the C7 stubs:
   - `RecordingHardwareAdapter` — MediaRecorder lifecycle wrapper; emits `Action.RecordingAction.MediaRecorderReady` on successful allocate, `Action.EffectFailure` on prepare/start/stop failure.
   - `BluetoothScoSubsystemAdapter` — wraps the existing `BluetoothScoControl` (interface that `BluetoothScoManager` implements) for the `start()/stop()` SCO route control.
   - `AudioFocusSubsystemAdapter` — wraps the existing `AudioFocusGate` for `request()/release()` AudioFocus operations.
   - `RecordingTimerAdapter` — Handler-based monotonic timer (parallel to RecordingManager's internal timer; silent in Phase 1 — B5 will route UI rendering via state-derived predicates).
   - `AmplitudeStreamAdapter` — placeholder with observable lifecycle flag (no-op body; B5 fills in real sampling).
   - `BorderGlowAdapter` — placeholder with observable lifecycle flag.
2. **IMPL-1 closure (Spec 1 §11.2.2 step 7 + §7.3)** — moved AI infrastructure construction from `DictateInputMethodService.initLongLivedObjects` to `DictatePipelineService.onCreate`:
   - AIOrchestrator, AutoFormattingService, PromptQueueManager, SessionManager, SessionTracker, PromptService, RecordingRepository, the legacy PipelineOrchestrator (audio-pipeline runner), and PipelineCallbackBridge are now Service-constructed.
   - `JobExecutor.initialize(pipelineOrchestratorImpl)` is called in Service.onCreate (was previously line 447 in IME).
   - LocalBinder exposes all of the above via typed getters so the IME's existing call sites still work.
   - IME's `onServiceConnected` calls `bindAiInfrastructureFromService(binder)` which pulls the references into the existing IME fields. The IME also registers itself as the PipelineCallbackBridge delegate and PromptQueueManager callback delegate, plus an InputConnection provider.
   - IME's existing usages of these fields (50+ call sites) work unchanged.
3. **PipelineCallbackBridge** — solves the lifecycle mismatch between Service-owned PipelineOrchestrator and IME-owned UI callbacks. The service constructs the bridge once; the IME registers/clears itself as the active delegate on bind/unbind. Calls during gaps drop silently (no NPE, no throw).
4. **AudioFocus bridge into orchestrator** — the Service constructs its own production AudioFocusGate whose listener emits `Action.AudioAction.OnAudioFocusGrantChanged` into the orchestrator, so the AudioModule observes focus changes per Spec 1 §15.3.
5. **BluetoothSco bridge into orchestrator** — Service-owned `BluetoothScoManager` instance routes SCO state callbacks (`onScoConnected/Disconnected/Failed`) into `Action.AudioAction.OnBluetoothScoStateChanged` per the C7 module wiring.
6. **LanguageController bridge** — added a `pipelineBinder.dispatch(Action.LanguageAction.RefreshFromPref)` call in the IME's LanguageController callback so the orchestrator's `state.language` stays in sync with the IME-side LanguageController writes. Full LanguageController deletion is post-C8 (per Spec 1 §9.6 table "Final gelöscht in Block 1" — IME-side state remains primary until B5 LayoutCatalog routes through orchestrator).

**Files created (production):**
- `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/BluetoothScoSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/AudioFocusSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingTimerAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/AmplitudeStreamAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/BorderGlowAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineCallbackBridge.kt`

**Files modified (production):**
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` — added AI infrastructure construction, JobExecutor.initialize, real subsystem adapter wiring, LocalBinder accessors + callback registration methods, AudioFocus + BluetoothSco bridge listeners.
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — removed direct construction of AI infrastructure from `initLongLivedObjects`; added `bindAiInfrastructureFromService` / `unbindAiInfrastructureFromService`; ServiceConnection wires the lifecycle; null-guards on `pipelineOrchestrator` usages for early-window safety; LanguageController bridge dispatch.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| AI infrastructure is fully Service-constructed (not double-constructed in IME) | Spec 1 §11.2.2 step 7 | Plan literal: "JobExecutor-Init wandert". I went further — construction of AIOrchestrator/AutoFormattingService/PromptQueueManager/SessionManager/SessionTracker/PromptService/RecordingRepository also moved to Service, IME pulls via LocalBinder | (D5) sustainability — having one owner is cleaner than two parallel copies that could diverge. The prompt explicitly listed these for Service-side construction. | None — IME's field-based call sites unchanged | inline-fixed |
| RecordingTimerAdapter/AmplitudeStreamAdapter/BorderGlowAdapter are no-op in Phase 1 | Spec 1 §15.x (mentions them but C8 scope is parallel to IME's existing flow) | Adapters present interface seams but no Phase-1 UI mutation | (D7) IME's RecordingManager + RecordingUiController still drive these visually. Future B5 will fill in real bodies. The interface seams must exist for module-side `runEffect` to work without NPE — that's the C8 contract. | B5 fills in real bodies | inline-fixed |
| LanguageController NOT fully replaced by LanguageModule | Spec 1 §9.6 table: "LanguageController: Block 1 (LanguageModule) — wandert direkt in den Modul-Reducer; keine Adapter-Phase nötig" | LanguageController stays as IME-side state owner; added a one-line bridge to dispatch RefreshFromPref into LanguageModule | (D5) full deletion is invasive (LanguageController has 30+ usages across IME + Settings activity, with its own observer plumbing). Plan §9.6 puts it in "Block 1 (LanguageModule)" — Block 1 is the modular orchestrator-aufbau block (B2 in our state-file mapping), and B2 shipped LanguageModule as the state owner; the controller deletion is post-state-owner-shipped. The bridge keeps both sides synced; deletion comes in B4-B6. | None — bridge is one-way, idempotent; later blocks can delete LanguageController once UI consumers are migrated | inline-fixed (Important issue forwarded to B4/B5 follow-up) |
| Coroutine-based DictatePipelineService.onDestroy timeout NOT added | Spec 1 §7.3 (Phase-B S-5 fix) shows `runBlocking + withTimeout(2000)` wrapper | onDestroy still calls `orchestrator.shutdown()` plainly | (D7) per existing C7 KDoc: "shutdown() is currently synchronous; runBlocking-Timeout wrapper is not strictly necessary in C7. Block 1b wraps it when module-terminate becomes suspending." module-terminate is still synchronous in C8; no suspending paths added. Spec §7.3 also adds Pre-Cancel-Dispatch — that's a Phase-C C-2/C-3 fix that operates on `state.recording !is Idle` — RecordingModule's terminate doesn't yet release MediaRecorder (Spec calls it KG, deferred). Adding now would be premature; C-3 is its own deferred scope. | C-2/C-3 fix is plan-deferred | (Not C8 scope per existing KDoc rationale) |

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| IMPL-1 (carry-over) | Important | JobExecutor-Init move from IME to Service | fixed | DictatePipelineService.onCreate now constructs the legacy PipelineOrchestrator + dependencies and calls `JobExecutor.initialize(pipelineOrchestratorImpl)`. Verified by `DictatePipelineServiceCompositionTest.onCreate calls JobExecutor_initialize with the legacy PipelineOrchestrator`. |

#### Plan-Correctness Fix (B3-C8-IMPL-PLAN-FIX)

Re-read spec 1 §11.2.2 + §7.3 + §15.x against the implementation. Findings:
- ✅ JobExecutor.initialize moved (literal §11.2.2 step 7).
- ✅ Service is the composition root (§7.3 conformant in shape; differences documented in Deviations).
- ✅ Subsystem adapters wrap legacy classes (§9.6 — "BluetoothScoManager: nie gelöscht — wird hinter `BluetoothScoSubsystem`-Interface gewrapped"; same for AudioFocusGate).
- ✅ AudioFocus + BluetoothSco changes are bridged into AudioModule via emitAction (§15.3 cascade contract preserved).

No mid-size plan deviations requiring delegation; all deviations are small + locally decidable and documented in the table above.

#### Self-Code Fix (B3-C8-IMPL-CODE-FIX)

Code-quality review:
- Removed unused `currentAudioFile` field in `RecordingHardwareAdapter` (Set but never Get).
- Improved KDoc clarity on `activeRecorder()` test seam.
- Changed `BluetoothScoSubsystemAdapter` to depend on `BluetoothScoControl` interface (not the concrete `BluetoothScoManager`) — K-1 conformance (handwritten fake testability).
- All new files have header KDoc with spec references + "why" reasoning.

#### Tests (B3-C8-IMPL-TEST)

**Files created (tests):**
- `app/src/test/java/net/devemperor/dictate/core/AudioFocusSubsystemAdapterTest.kt` — 3 tests (request/release delegation, multi-call).
- `app/src/test/java/net/devemperor/dictate/core/BluetoothScoSubsystemAdapterTest.kt` — 4 tests (start/stop/timeout/cycles).
- `app/src/test/java/net/devemperor/dictate/core/AmplitudeStreamAdapterTest.kt` — 5 tests (lifecycle flag transitions, idempotency).
- `app/src/test/java/net/devemperor/dictate/core/BorderGlowAdapterTest.kt` — 4 tests (lifecycle flag transitions).
- `app/src/test/java/net/devemperor/dictate/core/RecordingTimerAdapterTest.kt` — 6 Robolectric tests (Handler-backed timer; start/idempotency/pause/resume/reset).
- `app/src/test/java/net/devemperor/dictate/core/RecordingHardwareAdapterTest.kt` — 8 Robolectric tests (MediaRecorder shadow; allocate→ready emit, allocate-conflict, no-op resilience).
- `app/src/test/java/net/devemperor/dictate/core/PipelineCallbackBridgeTest.kt` — 19 tests (registration, forwarding, null-gap, throw isolation).
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceCompositionTest.kt` — 14 Robolectric tests (IMPL-1 closure — all binder accessors return non-null instances, JobExecutor.initialize verification, callback registration plumbing).

**Test totals:** 595 tests (up from 536 at B2-end). 0 failures.

#### Test-Review (B3-C8-IMPL-TEST-FIX)

Coverage review:
- ✅ All new adapter classes have at least lifecycle + idempotency tests.
- ✅ IMPL-1 closure verified end-to-end (JobExecutor.initialize call, binder accessors).
- ✅ Callback registration plumbing tested (PromptQueueManager delegate routing verified via behavioral assertion — observed snapshot grows when delegate is set, stays unchanged when null).
- ✅ Throw isolation in PipelineCallbackBridge (the pipeline thread cannot afford to abort).
- Coverage threshold: 100% of new adapter public methods have at least one test. Branches (failure paths in `RecordingHardwareAdapter`) tested via Robolectric.

No code-bugs found during test writing (the implementation passed the tests on first run for all classes except the two minor compile errors fixed inline — TranscriptionKind ref, InsertionTarget.IME→INPUT_CONNECTION, assertTrue/assertEquals imports).

---

### Chunk C9-block3-db-persistence-schema-m4 — DB-Persistence Schema-Migration M3→M4

**Agent-IDs:** Steps 1-5: `B3-C9-IMPL-FULL`

**Status:** ⏳ pending (depends on C8)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 10 (C9-block3-db-persistence-schema-m4)

⏳

---

### Chunk C10-block3-db-persistence-recovery — DB-Persistence Recovery + Cleanup

**Agent-IDs:** Steps 1-5: `B3-C10-IMPL-FULL`

**Status:** ⏳ pending (depends on C9)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 11 (C10-block3-db-persistence-recovery)

⏳

---

### Chunk C11-block4-audio-file-factory — AudioFileFactory + Pre-Dispatch

**Agent-IDs:** Steps 1-5: `B3-C11-IMPL-FULL`

**Status:** ⏳ pending (depends on C10)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 12 (C11-block4-audio-file-factory)

⏳

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending (run after all 4 chunks)
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B3-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B3.md` | — |
| convention | `B3-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B3.md` | — |
| logic | `B3-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B3.md` | — |
| test | `B3-AUDIT-TEST` | ⏳ | `./reports/audit-test-B3.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B3-VAL-SANITY`
**Output file:** `./reports/validated-findings-B3.md`

⏳

---

## Block Deviation Summary

⏳

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step workflow done, both commits per chunk):** ⏳
- **Block-Validate converged (4-topic audit + sanity-pass + repair-waves done):** ⏳
- **AUDIT-TEST: coverage thresholds met for new files, no cross-chunk regressions:** ⏳
- **Build/Lint green at block-end:** ⏳
- **Issue index reconciled (all ids closed/postponed/forwarded):** ⏳
- **Conventions section filled:** ⏳
- **Deviation list propagated to plan/state:** ⏳
- **Cross-block-API consumer info forwarded to Block 4:** ⏳ (B4 LayoutCatalog consumes the migrated subsystems via DictateOrchestrator + ModuleServices unchanged)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
