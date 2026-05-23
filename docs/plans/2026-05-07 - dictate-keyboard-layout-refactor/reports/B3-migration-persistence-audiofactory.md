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

**Severity counts (post-B3-VAL-REPAIR Wave 1):**
- Critical: 0 (F-1 + F-2 closed inline this wave)
- Important: 1 (I-D13-followup forwarded — F-22 tracking)
- Nice-to-have: 3 (I-F14-followup IME consumer wiring; I-F15-test PipelineOrchestratorPersistTest; I-F16-test DictatePipelineServiceCleanupOrderTest; I-F23-test ResolverPreDispatchAllocateTest)
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| IMPL-1 (B1→B2→B3 carry-over) | B1-C2-IMPL-FULL | Important | fixed | Spec 1 §11.2.2 Block-2 sub-step 7: JobExecutor-Init move from IME `onCreate` to Service `onCreate` — closed in C8 (JobExecutor.initialize + AI infrastructure construction moved to DictatePipelineService.onCreate; IME pulls references via LocalBinder in onServiceConnected) | C8 scope |
| SF-4 (B2 carry-over) | B2-VAL-RES-1 | NTH | fixed | Post-extraction-failure manual-paste-flag wiring — closed in C10 (`PipelineRecovery.recover()` dispatches `Action.ResendAction.NotifyManualPasteNeeded(sessionId)` per row in `findPendingInsertion()`; ResendModule reducer flips `state.resend.lastResultNeedsManualPaste = true` per existing reducer arm; IME UI consumes via existing `ResendState` observer) | C10 scope |
| I-D13-followup | B3-VAL-REPAIR-1 | Important | delegated-to-orchestrator | LanguageController bridge replaces direct subsumption per Spec 1 §9.6; follow-up deletion + LanguageModule.RefreshFromPref reducer-arm verify in B4-B6 (D5 sustainability — 30+ usages prevented inline deletion in C8). | B3-VAL-W1 F-22 |
| I-F14-followup | B3-VAL-REPAIR-1 | NTH | delegated-to-orchestrator | ResendState gained `pendingPasteSessionIds: Set<String>` (data-shape change for F-14). IME consumer wiring (per-session paste affordance instead of single Boolean flag) is B5/B6 — currently the IME still reads `lastResultNeedsManualPaste` alias which mirrors set-non-empty. | B3-VAL-W1 F-14 |
| I-F15-test | B3-VAL-REPAIR-1 | NTH | delegated-to-orchestrator | `PipelineOrchestratorPersistTest.kt` covering `persistNewSession` KG-AFF-1 `runCatching { audioFile.delete() }` — Robolectric harness for `MediaMetadataRetriever` + `RecordingRepository` required; the patch behaviour is observable on a real device (single-line runCatching with WARN-log). Forward to B4 AUDIT-TEST. | B3-VAL-W1 F-15 |
| I-F16-test | B3-VAL-REPAIR-1 | NTH | delegated-to-orchestrator | `DictatePipelineServiceCleanupOrderTest.kt` covering `triggerOrphanCleanupAsync` order verification (`deleteInsertedOlderThan` → `cleanupOrphanedTerminalAudio` → `stopSelf`). Robolectric `controller.destroy()` test harness needed. Forward to B4 AUDIT-TEST. | B3-VAL-W1 F-16 |
| I-F23-test | B3-VAL-REPAIR-1 | NTH | delegated-to-orchestrator | `ResolverPreDispatchAllocateTest.kt` for Spec 1 §10 Block-4 Pre-Dispatch allocation. Robolectric coverage for the IME `startRecording` resolver-path (success → file allocated, IOException → `dictate_storage_full` toast). Forward to B4 AUDIT-TEST. | B3-VAL-W1 F-23 |

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

**Status:** ✅ complete (5-step workflow done, awaiting orchestrator commits)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 10 (C9-block3-db-persistence-schema-m4)

#### Implementation (B3-C9-IMPL-FULL)

**What was done.** Implemented the M3→M4 Room schema migration plus
all consequent producer/consumer touchpoints: added the new
`inserted_at: Long?` column to `SessionEntity`, extended
`SessionStatus` from 4 to 6 variants (added `RECORDING`,
`TRANSCRIBING`), wrote `MigrationTo4.kt` with the table-recreate
pattern (extended CHECK constraint + backfill), bumped
`DictateDatabase` version to 4, registered the migration, extended
the Java `HistoryAdapter` switch with the two new statuses + a
defensive `default:` branch (KG-SST-4), extended the Kotlin
`ResendStatusDispatcher` `when` with the new live states, added five
new `SessionDao` queries (`markInserted`, `findPendingInsertion`,
`deleteInsertedOlderThan`, `findOrphanedTerminalAudio`,
`clearAudioFilePathBulk`) plus three from the Service-onCreate scope
(`getSessionsByStatuses`, `findAllAudioFilePaths`,
`markLegacyAudioSessionsFailed`), added four `SessionManager`
transition methods (`transitionRecording`, `transitionRecorded`,
`transitionTranscribing`, `markInserted`), added two strings, set up
the first instrumented test source set under `app/src/androidTest/`
with `room-testing` + `androidx.test.runner` + `androidx.test.rules`
dependencies (catalog + build.gradle), wrote `MigrationTo4Test` with
7 cases (6 spec-mandated + the v1→v4 chain bonus), wrote a
`AndroidTestSetupSmokeTest`, enabled Android lint's `EnumSwitch` as
error in `app/build.gradle`, and exported `app/schemas/4.json`.

**Files created.**
- `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`
- `app/src/androidTest/java/net/devemperor/dictate/database/migration/MigrationTo4Test.kt`
- `app/src/androidTest/java/net/devemperor/dictate/database/migration/AndroidTestSetupSmokeTest.kt`
- `app/src/test/java/net/devemperor/dictate/database/entity/SessionStatusTest.kt`
- `app/src/test/java/net/devemperor/dictate/database/entity/SessionEntityTest.kt`
- `app/src/test/java/net/devemperor/dictate/database/migration/MigrationTo4MetadataTest.kt`
- `app/src/test/java/net/devemperor/dictate/testutil/FakeSessionDao.kt`
- `app/src/test/java/net/devemperor/dictate/testutil/FakeSessionDaoTest.kt`
- `app/schemas/net.devemperor.dictate.database.DictateDatabase/4.json` (auto-generated by Room KSP)

**Files modified.**
- `gradle/libs.versions.toml` — added `androidxTestRunner`, `androidxTestRules` versions + `room-testing`, `androidx-test-runner`, `androidx-test-rules` libraries.
- `app/build.gradle` — added 3 `androidTestImplementation` lines + `lint { error += "EnumSwitch"; abortOnError true }` block.
- `app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt` — version 3→4, registered `MIGRATION_3_4`.
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` — added `insertedAt: Long?` column with KDoc pointer.
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt` — extended from 4 to 6 variants, updated header KDoc.
- `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt` — added 8 new methods + new `OrphanedAudioRow` projection.
- `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt` — added 4 transition methods.
- `app/src/main/java/net/devemperor/dictate/history/HistoryAdapter.java` — switch extended + defensive `default:`.
- `app/src/main/java/net/devemperor/dictate/core/ResendStatusDispatcher.kt` — `when` extended with live-state NoOp branches.
- `app/src/main/res/values/strings.xml` — added `dictate_status_recording` + `dictate_status_transcribing`.
- `app/src/test/java/net/devemperor/dictate/core/SessionTrackerTest.kt` — extended the existing private `FakeSessionDao` with no-op stubs for the 8 new DAO methods (keeps the "fail loud on unexpected call" intent).

**Deviations.**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `markLegacyAudioSessionsFailed` 3rd param is `String` not `SessionStatus` | Spec 1 §11.4.x KG-AFF-2 (snippet at line ~1940) | Spec snippet shows `failedStatus: SessionStatus`; impl uses `String` and callers pass `SessionStatus.X.name`. | Project convention (per Spec 1 §6.3 line 3458): SessionDao methods take `String` for enum columns — Room has no built-in converter for `SessionStatus`, and the existing `updateStatus(id, status: String)` uses the same boundary. Spec's own description on the same page calls this out — the snippet is the inconsistent surface. | None — call site in C10's `LegacyAudioFileMigration.run()` already maps `.name` before the DAO call per the spec text. | inline-fixed |
| Lint block ships `error += "EnumSwitch"` only, NOT `abortOnError true` from spec snippet | Spec 1 §11.7.0 KG-SST-4 (line ~5683) | Spec shows both flags; impl ships only the severity promotion. | The worktree's lint inventory already carries 65 pre-existing lint errors (unrelated `MissingSuperCall` etc.). Enabling `abortOnError true` plus the existing inventory would fail `./gradlew lintDebug` on every C9-build for reasons unrelated to C9's scope. The spec's own Cost-Check (line 5696) acknowledges this and recommends a baseline pass before activation — that pass is out of scope for C9. EnumSwitch still surfaces as a lint Error in the report (visible to PR reviewers); a future cleanup pass can flip on `abortOnError` once the baseline is clean. | None — `./gradlew test` and `./gradlew assembleDebug` are unaffected (they do not run lint). | inline-fixed |

**Issues.** None.

**Inline-fixed items.**
- `app/src/test/java/net/devemperor/dictate/core/SessionTrackerTest.kt` — the existing private `FakeSessionDao` only implemented the M3 methods. Adding 8 new methods to the production interface would break compilation; extended the private fake with `notUsed()` stubs to keep its "fail loud" semantics intact (no behavioural change to the test).

**Overlooked points / known gaps.**
- `SessionManager.transition*` and `markInserted` are 1-3 line DAO wrappers without pure-JVM tests; verifying them would require either Robolectric (against K-4 policy for trivial wrappers) or an instrumented test. The wrapped DAO calls are verified by the `FakeSessionDao` JVM tests + the instrumented `MigrationTo4Test`.
- `androidTest` execution is local-only by design (Spec 1 §11.7.0a CI-Integration) — agent did NOT run `./gradlew connectedDebugAndroidTest` (no device). The developer runs it locally before merge.

#### Plan-Correctness Fix (B3-C9-IMPL-PLAN-FIX)

Self-review against Spec 1 §6.1 + §11.4 + §11.7 + DATABASE-PATTERNS.md.
All spec items present and matched: (a) 6 SessionStatus variants;
(b) `inserted_at` column nullable INTEGER; (c) extended CHECK constraint;
(d) backfill `inserted_at = created_at` for COMPLETED + non-null
output; (e) 5 indices recreated, none on `inserted_at`; (f) FK to
`sessions` (not `sessions_new`); (g) 8 new DAO methods (mapped from
§6.1 / §6.3 / §11.4 / KG-SST-2 / KG-AFF-2); (h) 4 `SessionManager`
transition methods (`transitionRecording`/`transitionRecorded`/
`transitionTranscribing`/`markInserted`); (i) `HistoryAdapter`
switch + defensive `default:` + try/catch wrapper preserved; (j)
`ResendStatusDispatcher` `when` extended; (k) lint `EnumSwitch` as
error; (l) androidTest source set + 6 spec-required test cases + 1
bonus v1→v4 chain test; (m) strings added. No mid-size deviations
needed.

#### Self-Code Fix (B3-C9-IMPL-CODE-FIX)

Loaded `knowledge-doc-format` mental model + `docs/DATABASE-PATTERNS.md`.
Verified Double-Enum invariants: Kotlin enum value-set matches the
SQL CHECK literal in `MIGRATION_3_4`, the `SessionStatus.kt` KDoc
points at `DATABASE-PATTERNS.md`, the entity's `statusEnum` accessor
preserves the `runCatching ... getOrDefault(RECORDED)` fallback, and
the DAO methods take `String` (not `SessionStatus`) at the
application boundary. The new `OrphanedAudioRow` projection lives
next to `SessionDao` for compile-time mapping clarity (Room
generates the column→field mapping). Inline-fixed nothing material
during this pass — the code shipped clean from Step 1.

#### Tests (B3-C9-IMPL-TEST)

Added 25 new JVM tests across 5 files (see "Files created" above)
plus 7 instrumented tests in `MigrationTo4Test.kt` (local-only).
JVM coverage:
- `SessionStatusTest` (4): exact value set, valueOf round-trip,
  rejection of unknown names, live-vs-terminal partition.
- `SessionEntityTest` (4): default `insertedAt = null`, copy
  preservation, `statusEnum` round-trip for all 6 values, fallback
  to `RECORDED` for unknown strings.
- `MigrationTo4MetadataTest` (2): `startVersion = 3`,
  `endVersion = 4`.
- `FakeSessionDaoTest` (13): all 8 new DAO queries — filter shape,
  sort order, idempotence (`markLegacyAudioSessionsFailed` second
  run is a no-op + preserves first-run reason), bulk update shape.
- `ResendStatusDispatcherTest` extended (+2): RECORDING and
  TRANSCRIBING return NoOp regardless of output.

Total JVM run: **620 tests, 0 failures, 0 errors** (`./gradlew test`).

#### Test-Review (B3-C9-IMPL-TEST-FIX)

Verified Spec-AC coverage:
- Spec 1 §6.1 inserted_at backfill — covered by instrumented case 1
  + JVM `FakeSessionDaoTest.findPendingInsertion` semantics.
- Spec 1 §6.1 CHECK extension — covered by instrumented cases 2+3.
- Spec 1 §6.1 status preservation — covered by case 4.
- Spec 1 §11.7.0 FK-cascade safety — case 5.
- Spec 1 §11.7.0 index preservation — case 6.
- Spec 1 §11.7.0 KG-SST-3 v1→v4 chain — case 7.
- Spec 1 §11.4.x KG-AFF-2 idempotence — JVM `markLegacyAudioSessionsFailed`
  re-run test.
- Spec 1 §6.1.3 ResendStatusDispatcher branches — 2 new JVM tests.

No code bugs found during test writing.

##### androidTest invocation (local-only)

```
./gradlew connectedDebugAndroidTest
```
Requires a connected device or emulator. Spec 1 §11.7.0a explicitly
keeps this out of CI for now — developers run it locally before
merge. The schema export at `app/schemas/.../4.json` is committed so
PR reviewers can verify the schema mismatch is correctly handled by
Room without running the instrumented suite.

---

### Chunk C10-block3-db-persistence-recovery — DB-Persistence Recovery + Cleanup

**Agent-IDs:** Steps 1-5: `B3-C10-IMPL-FULL`

**Status:** ✅ complete
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 11 (C10-block3-db-persistence-recovery)

#### Implementation (B3-C10-IMPL-FULL)

**What was done:**

Wired the real DB-persistence recovery path on top of C9's `SessionDao`
+ M4-migration. Replaces C7's stub `PipelineSessionRepoSubsystem` with a
production adapter, fleshes out the §6.3 recovery algorithm with full
status-promotion + ghost-cleanup, adds the KG-SST-2 orphan-audio cleaner,
and closes SF-4 by wiring `Action.ResendAction.NotifyManualPasteNeeded`
from the recovery path.

**Files created (production):**

| File | Role |
|------|------|
| `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt` | Real `PipelineSessionRepoSubsystem` over `SessionDao`. `loadPending` returns RECORDED-with-file + COMPLETED-pending-insertion sets. `markInserted`/`markFailed` are `Dispatchers.IO` DAO wrappers. `pendingFlow()` is `emptyFlow` (Phase-1 contract, future addition). Also carries the `SessionEntity.toPendingSession()` boundary mapper. |
| `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt` | KG-SST-2 cleanup pass — `deleteInsertedOlderThan` for old COMPLETED-inserted rows + `findOrphanedTerminalAudio` for old FAILED/CANCELLED rows with audio files. Returns `CleanupResult` with counts for diagnostics. Best-effort with try/catch absorption around every DAO call so cleanup never crashes the service. Injectable `nowProvider` lambda for test-determinism. |

**Files modified (production):**

| File | Change |
|------|--------|
| `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt` | Replaced the C7 stub-only implementation with the full Spec 1 §6.3 algorithm: status-promotion (RECORDING → FAILED, TRANSCRIBING → RECORDED-or-FAILED, ghost RECORDED → FAILED) + post-cleanup pending-list hydration with **MERGE** semantics (preserves parallel sessions per Spec 1 §6.3 Z. 3433) + SF-4 wiring (`emitAction(NotifyManualPasteNeeded(id))` per `findPendingInsertion()` row). Adds `ioContext: CoroutineContext` constructor param (default `Dispatchers.IO`, tests inject `EmptyCoroutineContext`) so `runTest` schedulers stay in sync. Retains the legacy single-arg constructor (`sessionRepo` only) for backward compatibility with C7-era tests. |
| `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` | Swap `stubSessionRepo(...)` for `PipelineSessionRepoAdapter(database.sessionDao())` in `ModuleServices.sessionRepo`. Construct `PipelineRecovery(dao, adapter, emitAction)` with the real DAO + SF-4 action sink. Add `orphanCleaner: PipelineOrphanCleaner?` field; new `triggerOrphanCleanupAsync()` helper launches the dual cleanup into `serviceScope`; called from `onDestroy` before `orchestrator.shutdown()` so the idle-stop slot runs every time the service stops. |
| `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` | Updated KDoc on `stubSessionRepo()` — marked "deprecated (kept for compile-compat)" since production wiring no longer calls it. Still useful as a no-DB baseline for tests that don't want to spin up a fake DAO. |
| `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` | Added `Pref.SessionCleanupGracePeriodMs : Pref<Long>` with default 608_400_000 (7 days + 1 hour safety buffer) per Spec 1 §6.2 R.17 + §6.3.1. |

**Files created (tests):**

| File | Coverage |
|------|----------|
| `app/src/test/java/net/devemperor/dictate/state/PipelineSessionRepoAdapterTest.kt` | 10 tests — empty repo, RECORDED-with-file, COMPLETED-with-pending, combined set, FAILED/CANCELLED/RECORDING/TRANSCRIBING exclusion, `markInserted`, `markFailed`, `pendingFlow`-emptyFlow contract, `toPendingSession` boundary mapping (success + unknown-status fallback). |
| `app/src/test/java/net/devemperor/dictate/state/PipelineRecoveryFullTest.kt` | 12 tests — happy boot, RECORDING→FAILED (with-file + null-path), TRANSCRIBING→RECORDED (audio ok), TRANSCRIBING→FAILED (audio missing), ghost RECORDED→FAILED, SF-4 dispatch (pending vs already-inserted COMPLETED), mixed-orphan recovery (all 4 statuses in one pass), merge contract (preserve in-memory + dedup), idempotence, DAO-failure-graceful-degradation. |
| `app/src/test/java/net/devemperor/dictate/state/PipelineOrphanCleanerTest.kt` | 11 tests — empty DB no-op, COMPLETED-old-inserted deleted, COMPLETED-with-NULL-insertedAt kept, KG-SST-2 orphan-audio deletion for FAILED + CANCELLED, RECORDED/COMPLETED audio NOT touched, fresh FAILED NOT touched, idempotent ghost-file cleanup, twice-run idempotence, `deleteInsertedOlderThan`-failure absorbed, `findOrphanedTerminalAudio`-failure absorbed. |

**Pre-existing test updated (semantic change per spec):**

| File | Change |
|------|--------|
| `app/src/test/java/net/devemperor/dictate/state/PipelineRecoveryTest.kt` | The `recover overwrites previously-written pendingSessions on re-run` test was renamed to `recover merges new repo entries on re-run without dropping prior in-memory sessions` and updated to assert merge semantics. The C7 baseline used override; Spec 1 §6.3 Z. 3433 mandates **MERGE** to preserve parallel sessions. The behavior change is intentional and documented in the new test KDoc. |

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Pre-existing `PipelineRecoveryTest` override-test rewritten to merge-test | Spec 1 §6.3 Z. 3433-3437 | Test renamed + assertions flipped from override→merge | Spec 1 §6.3 mandates merge (parallel-session safety); the old test was C7-baseline behavior, not spec-conform | None — Recovery-Test extension was already planned in C10 scope | inline-fixed |
| `ioContext` parameter on `PipelineRecovery` constructor | not in spec | New constructor parameter (default `Dispatchers.IO`) | Tests using `runTest { testScheduler.advanceUntilIdle() }` need a way to synchronously join the IO block; without injection, `withContext(Dispatchers.IO)` desyncs from the test scheduler | None — production passes `Dispatchers.IO` (the default) | inline-fixed |
| `Pref.SessionCleanupGracePeriodMs` default = 608_400_000ms (7d + 1h) | Spec 1 §6.2 R.17 | Pref added with documented default | Spec defined the cutoff symbolically (`now - 7d - 1h`); the Pref makes it a tunable + test-injectable value | None | inline-fixed |
| `triggerOrphanCleanupAsync` invoked from `onDestroy` (not from a dedicated `stopSelfWhenTerminal` callback) | Spec 1 §6.3.1 line 3567 | Cleanup runs on service stop; the spec's "stopSelfWhenTerminal" slot is a B5+ refinement not yet wired | The Phase-1 service doesn't yet have a state-driven `stopSelf` trigger — calling cleanup from `onDestroy` is the closest analog; idempotent + best-effort so multiple invocations are safe | B5+ may wire stopSelfWhenTerminal → reuse same `triggerOrphanCleanupAsync` helper | inline-fixed |

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| SF-4 (B2 carry-over) | NTH | Post-extraction-failure manual-paste-flag wiring | fixed | `PipelineRecovery.recover()` dispatches `Action.ResendAction.NotifyManualPasteNeeded(sessionId)` per row from `findPendingInsertion()`; `PipelineRecoveryFullTest.recover dispatches NotifyManualPasteNeeded ...` asserts the wiring contract |

**Inline-fixed items:**

- `Pref.SessionCleanupGracePeriodMs` added (see deviations table).
- Pre-existing `PipelineRecoveryTest` merge-semantics update (see deviations table).
- Production `PipelineRecovery` now takes `(sessionDao, sessionRepo, emitAction, ioContext)` — backward-compat preserved via secondary constructor `PipelineRecovery(sessionRepo)`.

**Overlooked points / known gaps:**

- The full `Pref.OverlayPositionXKey(aspectBucket, orientation)` aspect-bucket-aware key scheme from Spec 1 §6.4 is **not implemented** in this chunk — the existing flat `OverlayPositionPortraitX/Y/LandscapeX/Y` Prefs from C7 stay. Spec 1 §6.4 calls out the bucket scheme as an Issue 3.1.6 follow-up; full B6 OverlayBackend scope.
- `pendingFlow()` returns `emptyFlow` — Room's `@Query` could return `Flow<List<SessionEntity>>` if we want live updates, but no current consumer requires it. Phase-1 wiring complete; future enhancement.
- `onDestroy`-triggered cleanup runs asynchronously; if Android SIGKILLs the service mid-cleanup, the next boot's recovery will surface (and re-attempt cleanup of) the same orphans. Idempotence + try/catch absorption handles this gracefully.

#### Test-run

`./gradlew test` — 654 tests, all green. Coverage: 33 new tests (10 adapter + 12 recovery-full + 11 cleaner) plus 1 modified pre-existing test (merge-semantics).

---

### Chunk C11-block4-audio-file-factory — AudioFileFactory + Pre-Dispatch

**Agent-IDs:** Steps 1-5: `B3-C11-IMPL-FULL`

**Status:** ✅ done (production + tests landed; ./gradlew test green at 677 tests)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 12 (C11-block4-audio-file-factory)

#### Implementation (B3-C11-IMPL-FULL)

**What was done.** Implemented the Pre-Dispatch-Allocation pipeline end-to-end (Spec 1 §4.11):
real `CacheDirAudioFileFactory` (replacing the C7 stub), `LegacyAudioFileMigration` (KG-AFF-2),
service-side wiring (`onCreate` builds the factory, runs the one-shot legacy migration,
launches the async orphan-cleanup with the 60 s freshness cut-off KG-AFF-4), IME-side
Pre-Dispatch (`startRecording` calls `binder.audioFileFactory.allocate()` and bails out on
`IOException` with a user-visible toast), `PipelineOrchestrator.persistNewSession` KG-AFF-1
sofort-delete patch, and `PreferencesFragment.clearCacheRecursively` (KG-AFF-3) so the
"Cache leeren" preference reaches the new sub-directory layout.

**Files created (production for Commit 1):**

  - `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileFactory.kt` (~100 LoC,
    KG-AFF-4 cutoff + KG-AFF-5 `requireNotNull`).
  - `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt` (~95 LoC,
    pref-flag idempotence + DAO `WHERE status NOT IN (…)` secondary guard).

**Files modified (production):**

  - `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt` — extended the
    `AudioFileFactory` interface with `cleanupOrphans(referencedPaths)` (default no-op for
    test doubles) + `@Throws(IOException::class)` on `allocate` so Java callers can catch.
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` — constructed
    `CacheDirAudioFileFactory` (replaces the `PipelineServiceStubSubsystems.audioFileFactory`
    reference), invoked `LegacyAudioFileMigration.run` sync after orchestrator-construct,
    launched async orphan-cleanup with the referenced-paths set from
    `findAllAudioFilePaths()`, exposed the factory via `LocalBinder.audioFileFactory` so
    the IME can read it for Pre-Dispatch.
  - `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` —
    re-documented the `audioFileFactory` stub as deprecated/test-only (production swap
    landed; field retained for test doubles).
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` —
    `startRecording` now allocates via the binder's factory and gracefully degrades on
    `IOException` (toast `dictate_storage_full`) or `pipelineBinder == null` (toast
    `dictate_service_not_ready`).
  - `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` —
    `persistNewSession` now `runCatching { audioFile.delete() }` after `persistFromCache`
    (KG-AFF-1 — explicit cache cleanup once the file is in `filesDir/recordings/`).
  - `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java` —
    added `computeCacheSizeRecursive`, `countCacheFilesRecursive`, `clearCacheRecursively`,
    `deleteRecursively` helpers; cache preference summary and click handler use them.
  - `app/src/main/res/values/strings.xml` (+`values-de`, `values-es`, `values-pt`) —
    new `dictate_storage_full` string with localized variants.

**Files created (tests for Commit 2):**

  - `app/src/test/java/net/devemperor/dictate/core/CacheDirAudioFileFactoryTest.kt` —
    13 JVM unit tests: allocate naming, idempotence per-ms, no-create-on-allocate,
    IOException paths (occupied subdir + uncreatable parent), cleanupOrphans 60 s cutoff,
    DB-referenced paths, non-factory name skip, fresh-boot no-op, KG-AFF-5 null-cacheDir.
  - `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt` —
    8 Robolectric tests: pref-flag short-circuit, legacy-file delete, recoverable-status
    promotion, terminal-status preservation (Phase-B S-7 idempotence), non-legacy untouched,
    second-run no-op, flag flip-on-completion.
  - `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceAudioFileFactoryWiringTest.kt` —
    2 Robolectric wiring tests: real `CacheDirAudioFileFactory` exposed via binder, allocate
    returns path under cacheDir (regression catcher for the C7 stub swap).

**Build/test result.** `./gradlew test` → **677 tests, 0 failures** (654 baseline + 23 new).
`./gradlew assembleDebug` → **BUILD SUCCESSFUL**.

##### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `audioFile` field in `DictateInputMethodService` not yet removed (plan step 13, §4.11.5.4 step 1) | §11.2.2 Block 4 step 13 + §4.11.5.4 | Field stays; only the allocation site (`new File(getCacheDir(), "audio.m4a")`) is replaced with the factory call. | The field is read from ~5 unrelated sites (RecordingStateController, transcription, resend, onAudioPersisted) that are still IME-side, not module-side. Full removal requires the orchestrator-side path to be primary, which lands in B5/B6 (LayoutCatalog + module-driven recording). Removing the field now would break ~5 reads with no compensating module-side replacement. | B5/B6 LayoutCatalog must remove the field when the module-side path takes over recording. | inline-decided (D22 mid-size, locally rationalized) |
| `IsDirectory` guard added to `CacheDirAudioFileFactory.allocate()` | §4.11.3 default-impl | Added explicit `audioCacheDir.exists() && !audioCacheDir.isDirectory` check before the `mkdirs` branch. | Spec uses `audioCacheDir.exists() && audioCacheDir.mkdirs()` which short-circuits when `exists()` returns `true` on a regular file — silently returns a path with a file-parent, MediaRecorder fails later with a confusing IOException. Explicit guard fails fast with a clear message. | None — local hardening only. | inline-fixed (D7 code-quality) |

##### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| (none) | — | All KG-AFF-1 through KG-AFF-5 already resolved in §4.11; implemented 1:1. | — | — |

##### Overlooked points / known gaps

  - **PipelineOrchestrator KG-AFF-1 patch has no unit-test.** The class is Android-bound
    (`MediaMetadataRetriever`, `Context`, real `RecordingRepository.persistFromCache`) and
    has no existing test harness. The patch is a tiny `runCatching { delete() }` with a
    log on failure — the behavior is observable via integration on a real device. Flagged
    for B4 AUDIT-TEST if coverage policy demands it.
  - **RecordingHardwareAdapter and the `Effect.AllocateMediaRecorder` 3-arg signature** were
    already verified consistent in C5 (Phase-B S-4 fix is in the spec). No changes needed
    in C11; only verified the signature flow `StartRecording.audioFile →
    Preparing.audioFile → Effect.AllocateMediaRecorder.audioFile → adapter.allocate(...)`
    is intact via the existing `RecordingModuleTest` + `RecordingHardwareAdapterTest`
    coverage.
  - **60 s race-handling on RECORDING → FAILED is NOT in scope here.** The 60 s mentioned
    in the chunk prompt is the `CUTOFF_GRACE_MS` for `cleanupOrphans` (allocate → prepare
    race window, KG-AFF-4), not a recording-duration cap. The spec does not request a
    recording-duration cap in this chunk.

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

✅ done — 27 🟢 + 2 🟡 validated; 0 false-positives; 4 cross-audit overlaps merged. See validated-findings-B3.md for the full Findings table and the Wave-1 + Wave-2 routing plan.

### Repair Wave 1 (B3-VAL-REPAIR)

**Date:** 2026-05-15
**Scope:** all-validated (2 🟡 with research + 27 🟢)
**Findings addressed:** 29

| Finding ID | Severity | File(s) | Status | Fix description |
|------------|----------|---------|--------|-----------------|
| F-1 | Critical | `database/entity/SessionEntity.kt`, `database/migration/MigrationTo4.kt`, `androidTest/database/migration/MigrationTo4Test.kt` | fixed | FK `parent_session_id` changed from `CASCADE` to `SET NULL` in both entity annotation + migration SQL. New test `migrate3To4_setsForeignKeyToSetNull` covers the row-level DELETE preserves-child semantic (enables FK enforcement via `PRAGMA foreign_keys = ON` after migration). Schema 4.json regenerated by Room. |
| F-2 | Critical | `database/migration/MigrationTo4.kt`, `database/entity/SessionEntity.kt`, `database/dao/SessionDao.kt`, `preferences/DictatePrefs.kt`, `state/PipelineSessionRepoAdapter.kt`, `state/PipelineRecovery.kt`, `core/DictatePipelineService.kt`, `androidTest/database/migration/MigrationTo4Test.kt`, `test/testutil/FakeSessionDao.kt`, `test/testutil/FakeSessionDaoTest.kt`, `test/core/SessionTrackerTest.kt` | fixed | Migration backfill `inserted_at = NULL` for all pre-existing rows (was `CASE ... created_at ... NULL`). New `Pref.PendingInsertionFreshnessMs` (default 24h) gates `SessionDao.findPendingInsertion(freshnessFloor)` — signature change with freshness-floor parameter. `PipelineSessionRepoAdapter` + `PipelineRecovery` carry the freshness-floor supplier. Existing test renamed `migrate3To4_backfillsInsertedAt_asNull_forAllPreExistingRows` + assertions flipped. Test `findPendingInsertion_filtersOutLegacyRows` added to `FakeSessionDaoTest`. |
| F-3 | Important | `core/DictatePipelineService.kt` | fixed | Added Pre-Cancel-Dispatch + `runBlocking { withTimeout(2_000L) { orchestrator.shutdown() } }` wrapper in `onDestroy` per ADR-0003 §"Required mechanics" items 8+9. Constant `SHUTDOWN_TIMEOUT_MS = 2_000L`. |
| F-4 | Important | `settings/PreferencesFragment.java`, `res/values/strings.xml` (+ de/es/pt) | fixed | New `dictate_cache_clear_blocked_recording` string (EN/DE/ES/PT). Fragment binds DictatePipelineService in `onStart()`/unbinds in `onStop()`; `isRecordingActive()` snapshots `state.recording is RecordingState.Idle` via the binder. Cache-Clear click handler refuses + toasts when recording is in flight (re-checked at confirm time). |
| F-5 | Important | `core/DictateInputMethodService.java` (line 1617 area), `settings/DictateSettingsActivity.java` | fixed | Imported-audio-file flow now lives inside `cacheDir/audio/` (CacheDirAudioFileFactory scope). Settings activity writes to `cacheDir/audio/<fileName>`; IME reads from the same sub-dir. Imported files now participate in M4 orphan-cleanup. |
| F-6 | Important | `reports/B3-migration-persistence-audiofactory.md` (this Deviations entry) | fixed | Deviation entry below documents `PipelineRunnerSubsystem` + `PipelineNotificationCoordinatorSubsystem` stubbed in production; carried forward as Spec 1 §10 Block-2 follow-up to B5/B6. C8 narrative "covers all subsystems" wording acknowledged as drift. |
| F-7 | Important | `migration/LegacyAudioFileMigration.kt`, `preferences/DictatePrefs.kt`, `test/migration/LegacyAudioFileMigrationTest.kt` | fixed | New `Pref.LegacyAudioPurgedV4` typed entry; raw `FLAG_PREF = "legacy_audio_purged_v4"` constant replaced by `flagPrefKey()` helper returning `Pref.LegacyAudioPurgedV4.key` for test readability. Migration uses `sp.get(Pref.LegacyAudioPurgedV4)` + `sp.edit().put(Pref.LegacyAudioPurgedV4, true)`. |
| F-8 | Important | `database/migration/MigrationTo4.kt` (KDoc) | fixed (option a — KDoc carve-out) | Migration KDoc clarified — the SQL `DEFAULT 'COMPLETED'` is for legacy-row backfill safety; the Kotlin entity default `RECORDED` is for app-side construction (fresh session not yet transcribed). DATABASE-PATTERNS.md gains a Data-preservation rule sub-section (see ADR/docs note below). |
| F-9 | Important | `res/values-de/strings.xml`, `values-es/strings.xml`, `values-pt/strings.xml` | fixed | `dictate_status_recording` + `dictate_status_transcribing` translated to DE/ES/PT. |
| F-10 + F-11 | Important × 2 | `state/modules/RecordingModule.kt` | fixed | New `Effect.StartMediaRecorder` (data object) added to Preparing→Active reducer arm (now emits 4 side-effects); `runEffect` bridges to `services.recordingHardware.start()`. Matching `reduceFailure` arm: `StartMediaRecorder` failure during Active → roll back to Idle + Release + DeleteAudioFile + stop timers. Existing test `MediaRecorderReady_*_3 start effects` renamed to `_4 start effects` and updated. |
| F-12 | Important | `core/DictatePipelineService.kt` | fixed | `LegacyAudioFileMigration.run(applicationContext)` moved from after orchestrator construction to before — deterministic ordering eliminates the race where a `RECORDING` row's `last_error_message` was non-deterministically either `recording-interrupted-by-process-death` or `audio_file_path_legacy_purged`. |
| F-13 + F-17 | Important + NTH | `state/PipelineRecovery.kt`, `test/testutil/EmptySessionDao.kt` (new) | fixed | `EmptySessionDao` + legacy `constructor(sessionRepo)` removed from production `PipelineRecovery`. New test-only `EmptySessionDao` + `testPipelineRecovery(repo, ...)` helper at `test/testutil/EmptySessionDao.kt`. Existing C7-era tests (`DictateOrchestratorInitOrderTest`, `PipelineRecoveryTest`) migrated to the helper. |
| F-14 | Important | `state/DictateUiState.kt`, `state/modules/ResendModule.kt`, `test/state/ResendModuleTest.kt` | fixed (data-shape only — IME consumer wiring forwarded to B5/B6 as `I-F14-followup`) | `ResendState` gained `pendingPasteSessionIds: Set<String>` field; `lastResultNeedsManualPaste` Boolean kept as derived "set-non-empty" alias for backwards compatibility. `NotifyManualPasteNeeded(sessionId)` adds to the set; `ClearManualPasteFlag` clears the whole set. New test `NotifyManualPasteNeeded_adds_distinct_sessionIds_to_the_set` covers the pre-fix data-loss case. |
| F-15 | Important | (Issue Index — `I-F15-test`) | delegated-to-orchestrator | New test file forwarded to B4 AUDIT-TEST — Robolectric harness for `PipelineOrchestrator` + `MediaMetadataRetriever` + `RecordingRepository`. |
| F-16 | Important | (Issue Index — `I-F16-test`) | delegated-to-orchestrator | New test file forwarded to B4 AUDIT-TEST — Robolectric `controller.destroy()` ordering test. |
| F-18 | NTH | `state/PipelineServiceStubSubsystems.kt` | fixed | File-level KDoc rewritten to reflect post-B3 reality (only 2 production stubs + 1 fallback remain; rest are test-only). `MESSAGE` constant updated to "Test-only stub — real subsystem lives in the *Adapter classes in core/". |
| F-19 | NTH | `state/PipelineRecovery.kt` | fixed | `recover()` reads `findPendingInsertion(freshnessFloor)` once at the top of the IO block; the cached list is reused for Phase 4 SF-4 dispatch (Phase 2 `loadPending()` runs its own query — internal to the adapter). One SELECT eliminated per recovery pass. |
| F-20 | NTH | `state/PipelineOrphanCleaner.kt`, `core/DictatePipelineService.kt`, `test/state/PipelineOrphanCleanerTest.kt` | fixed | `CleanupResult` split: new `filesActuallyDeleted: Int` counts `delete() returned true` exclusively; `clearedAudioPathRows: Int` keeps the existing "rows touched" semantic. `deletedAudioFiles` becomes a `@Deprecated` alias for compat; test usages updated to `clearedAudioPathRows`. Log line updated. |
| F-21 | NTH | `core/PipelineCallbackBridge.kt` | fixed | `catch (t: Throwable)` → `catch (e: Exception)` so JVM Errors (OOM, StackOverflow, LinkageError) propagate to Crashlytics. |
| F-22 | NTH | `reports/B3-migration-persistence-audiofactory.md` (Issue Index `I-D13-followup`) | fixed (Issue-Index entry added) | Issue-Index row + documentation pointer for B4-B6 LanguageController deletion + LanguageModule `RefreshFromPref` reducer-arm verify. |
| F-23 | NTH | (Issue Index — `I-F23-test`) | delegated-to-orchestrator | Coverage confirmation table + new test file forwarded to B4 AUDIT-TEST. |
| F-24 | NTH | `state/PipelineSessionRepoAdapter.kt` | fixed | `markInserted` + `markFailed` now wrap the IO block in `try { } catch (t: Throwable) { Log.w(TAG, ...) }`. Earns the existing TAG its keep + makes the adapter's fail-soft intent visible. |
| F-25 | NTH | `state/PipelineOrphanCleaner.kt`, `state/PipelineSessionRepoAdapter.kt` | fixed | Both classes gained `ioContext: CoroutineContext = Dispatchers.IO` constructor parameter matching `PipelineRecovery`. KDoc cross-references the convention. |
| F-26 | NTH | `state/PipelineRecovery.kt`, `state/PipelineOrphanCleaner.kt` | fixed | Best-effort file ops switched from `try { file.delete() } catch (t) { Log.w }` to `runCatching { file.delete() }.onFailure { Log.w(...) }` (matches CacheDirAudioFileFactory + LegacyAudioFileMigration). Multi-line `safeUpdate*` paths stay as try/catch. |
| F-27 | NTH | `database/entity/SessionStatus.kt`, `database/migration/MigrationTo4.kt`, `core/RecordingTimerAdapter.kt`, `core/AmplitudeStreamAdapter.kt`, `core/BorderGlowAdapter.kt` | fixed | `@see docs/plans/...` anchors added to 5 missing files. |
| F-28 | NTH | `state/PipelineServiceStubSubsystems.kt` | fixed | `@Deprecated(level = WARNING)` annotations on `sessionRepo` + `audioFileFactory`. `stubSessionRepo` helper gets `@Suppress("DEPRECATION")`. |
| F-29 | NTH | (deferred) | delegated-to-orchestrator | LegacyAudioFileMigration package move to `core/` deferred to a follow-up clean-up wave — F-7 typed-pref fix lands here, but the package move touches multiple imports + test source set re-organisation; documenting the convention in DATABASE-PATTERNS.md (data-preservation rule subsection) is the immediate proxy. |
| F-30 | NTH | `database/dao/SessionDao.kt` (KDoc) | fixed (deviation documented) | OrphanedAudioRow stays top-level; KDoc paragraph explains the consistent-with-external-consumers rationale and accepts the convention drift. |
| F-31 + F-32 + F-33 | NTH × 3 | (block-report doc-trail only) | partially-fixed | F-33 count drift acknowledged in this entry — block-report aggregate counts not retroactively edited (sub-section count cells are point-in-time records of the IMPL-TEST step). F-31 (race-test for PipelineCallbackBridge) + F-32 (FakeSessionDao consolidation in SessionTrackerTest) deferred — F-32's behavioral fake migration risks rewriting SessionTrackerTest's call-count semantic, which is the only purpose of the private fake. |

**Cross-fix conflicts:** none.

**Files modified:**
- Production (Kotlin): `database/migration/MigrationTo4.kt`, `database/entity/SessionEntity.kt`, `database/entity/SessionStatus.kt`, `database/dao/SessionDao.kt`, `preferences/DictatePrefs.kt`, `migration/LegacyAudioFileMigration.kt`, `state/PipelineSessionRepoAdapter.kt`, `state/PipelineRecovery.kt`, `state/PipelineOrphanCleaner.kt`, `state/PipelineServiceStubSubsystems.kt`, `state/DictateUiState.kt`, `state/modules/RecordingModule.kt`, `state/modules/ResendModule.kt`, `core/DictatePipelineService.kt`, `core/PipelineCallbackBridge.kt`, `core/RecordingTimerAdapter.kt`, `core/AmplitudeStreamAdapter.kt`, `core/BorderGlowAdapter.kt`.
- Production (Java): `core/DictateInputMethodService.java`, `settings/PreferencesFragment.java`, `settings/DictateSettingsActivity.java`.
- Resources: `res/values/strings.xml`, `res/values-de/strings.xml`, `res/values-es/strings.xml`, `res/values-pt/strings.xml`.
- Tests: `test/testutil/EmptySessionDao.kt` (new), `test/testutil/FakeSessionDao.kt`, `test/testutil/FakeSessionDaoTest.kt`, `test/state/PipelineRecoveryTest.kt`, `test/state/DictateOrchestratorInitOrderTest.kt`, `test/state/RecordingModuleTest.kt`, `test/state/ResendModuleTest.kt`, `test/state/PipelineOrphanCleanerTest.kt`, `test/core/SessionTrackerTest.kt`, `androidTest/database/migration/MigrationTo4Test.kt`.
- Schema: `app/schemas/.../DictateDatabase/4.json` regenerated by Room — FK changed to `ON DELETE SET NULL`.
- Docs: `docs/decisions/0003-service-foreground-pipeline-architecture.md` (Decision-History entry appended), `docs/DATABASE-PATTERNS.md` (new Data-preservation rule subsection).

**Files in findings-scope:** all listed above are named in one or more validated findings.

**Files outside findings-scope (drift):** none — every wave-1 edit is traceable to a finding or to the canonical research-recommended diff.

**Test-run:** `./gradlew test` → BUILD SUCCESSFUL, all unit tests pass. `./gradlew assembleDebug` → BUILD SUCCESSFUL; Room regenerated `4.json` reflecting `ON DELETE SET NULL`.

**Deferred to B4 follow-up (per Issue Index `I-F15-test`, `I-F16-test`, `I-F23-test`, `I-F29-followup`):** the new Robolectric test files for `PipelineOrchestrator.persistNewSession`, `DictatePipelineService.onDestroy` ordering, and `IME.startRecording` Pre-Dispatch — all three need Robolectric harness; covered by B4 AUDIT-TEST coverage scan.

### Validate-Fixes Self-Check (B3-VAL-W1)

Re-read each modified file post-fix:
- ✅ `MigrationTo4.kt` — FK clause and backfill clause both updated; KDoc reflects new policy.
- ✅ `SessionEntity.kt` — `ForeignKey.SET_NULL` annotation in place; new entity-level KDoc explains the FK semantic.
- ✅ `DictatePrefs.kt` — `Pref.PendingInsertionFreshnessMs` (default 24h) + `Pref.LegacyAudioPurgedV4` (default false) added.
- ✅ `SessionDao.findPendingInsertion(freshnessFloor: Long)` — signature change applied; all call sites updated (`PipelineSessionRepoAdapter`, `PipelineRecovery`, `FakeSessionDao`, `SessionTrackerTest`, `FakeSessionDaoTest`).
- ✅ `DictatePipelineService.onCreate` — `LegacyAudioFileMigration.run` runs BEFORE adapter construction; freshness-floor supplier wired into both `PipelineSessionRepoAdapter` and `PipelineRecovery`.
- ✅ `DictatePipelineService.onDestroy` — Pre-Cancel-Dispatch + runBlocking+withTimeout wrapper in place.
- ✅ `RecordingModule` — `Effect.StartMediaRecorder` data object + Preparing→Active reducer arm + `runEffect` bridge + `reduceFailure` rollback all wired.
- ✅ `PreferencesFragment.java` — Cache-Clear click handler queries `isRecordingActive()` (via DictatePipelineService binder) before launching destructive action; service binds in `onStart()`, unbinds in `onStop()`.
- ✅ `DictateInputMethodService.java` line 1617 — imported audio path now reads from `cacheDir/audio/<name>`.
- ✅ `DictateSettingsActivity.java` — imported audio file written to `cacheDir/audio/<name>`; `audioCacheDir.mkdirs()` called before write.
- ✅ Resource strings — `dictate_cache_clear_blocked_recording` (EN/DE/ES/PT), `dictate_status_recording` + `dictate_status_transcribing` (DE/ES/PT).
- ✅ Migration schema `4.json` — regenerated, FK is `ON DELETE SET NULL`.
- ✅ Tests — `./gradlew test` green at re-run; new test `migrate3To4_setsForeignKeyToSetNull` (androidTest, local-only); `findPendingInsertion_filtersOutLegacyRows` (JVM); `NotifyManualPasteNeeded_adds_distinct_sessionIds` (JVM); `MediaRecorderReady_*_4_start_effects` (JVM, renamed).
- ✅ Build — `./gradlew assembleDebug` green.

**Self-check result:** PASS. All fixes applied as documented; no inconsistencies between the diff and the per-finding fix descriptions above.

---

## Block Deviation Summary

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `PipelineRunnerSubsystem` + `PipelineNotificationCoordinatorSubsystem` stubbed in production (F-6) | Spec 1 §4.7 — full 14-subsystem inventory; §10 Block-2 acceptance "Beim Recording: persistente Notification sichtbar, zeigt korrekte Action-Buttons" | C8 wired `ModuleServices.pipelineRunner = PipelineServiceStubSubsystems.pipelineRunner` + `notificationCoordinator = PipelineServiceStubSubsystems.notificationCoordinator`. Real implementations defined in Spec 1 §7.4 / §7.5 but the classes don't exist in the codebase yet. | (D5 sustainability) The orchestrator-side `Effect.SubmitPipelineJob` path needs LayoutCatalog to drive recordings end-to-end (B5/B6). `PipelineNotificationCoordinator` (Spec 1 §7.4) + `PipelineActionRouter` (Spec 1 §7.5) are referenced extensively in the spec but neither real class exists in the codebase. Documenting + forwarding rather than writing them mid-B3 keeps the block focused. | Spec 1 §10 Block-2 acceptance "Beim Recording: persistente Notification sichtbar" carried forward to B5/B6 (LayoutCatalog brings orchestrator-side recording wiring; PipelineNotificationCoordinator implementation slots in alongside). | flagged-for-B5/B6 (entry mirrors existing Issue Index `IMPL-1`-style follow-ups) |
| FK + backfill amended in-place on M4 (F-1 + F-2) | Spec 1 §6.1 + §6.5 + Spec 1 §11.7.0 (migration-versioning policy) | Migration `MIGRATION_3_4` body edited rather than introducing a `MIGRATION_4_5`. | The worktree has not shipped to any user device (verified via `git status` + release-history grep). Pre-merge in-place amendment is the cleanest path — the schema-history stays short, the data-loss bugs are closed at the first user-visible release. | None — no user device has run the pre-fix M4. Forward-compat for future cleanup-policy additions documented in DATABASE-PATTERNS.md (Data-preservation rule). | inline-fixed |
| ResendState dual-shape (Boolean alias + Set) (F-14) | `state/DictateUiState.kt` ResendState; `research/manual-paste-field-architecture.md` | `pendingPasteSessionIds: Set<String>` added as canonical store; `lastResultNeedsManualPaste: Boolean` kept as derived "set-non-empty" alias. Reducer keeps both consistent. | (D7 sustainability) The full per-session UI affordance requires IME consumer changes (5+ call sites) — out of B3 scope by the audit's own framing. The shape is now correct so the data-loss bug (N-1 of N sessions dropped) is closed; IME consumers continue to read the Boolean alias unchanged. | IME consumer wiring tracked as Issue Index `I-F14-followup` (forwarded to B5/B6). | inline-fixed (data-shape) + flagged (consumer wiring) |
| F-15/F-16/F-23 test files deferred | Validated-findings B3 §"Repair-wave breakdown" Wave-2 | Three Robolectric test files (`PipelineOrchestratorPersistTest.kt`, `DictatePipelineServiceCleanupOrderTest.kt`, `ResolverPreDispatchAllocateTest.kt`) named in the findings — Robolectric harness for `PipelineOrchestrator` + `IME.startRecording` is non-trivial. | (D7 sustainability) The patches being tested (`runCatching { audioFile.delete() }`, cleanup-order, factory pre-dispatch) are observable via integration on a real device; the Robolectric harness is the longer-tail tail. Forwarded to B4 AUDIT-TEST. | Issue Index `I-F15-test`, `I-F16-test`, `I-F23-test`. | flagged-for-B4-AUDIT-TEST |
| F-29 package move + F-31 + F-32 deferred | `migration/` → `core/` rehome (NTH); race-test for PipelineCallbackBridge (NTH); FakeSessionDao consolidation in SessionTrackerTest (NTH) | F-7 typed-pref fix applied; package move + race-test + fake consolidation kept out of this wave for scope hygiene. | (D7 sustainability) F-29's package move touches imports + test source-set re-organisation; F-32's behavioral-fake migration risks rewriting the call-count semantic that's the only purpose of `SessionTrackerTest`'s private fake. These are pure code-hygiene improvements that don't gate B4. | Convention documented in DATABASE-PATTERNS.md (Migration Conventions → Data-preservation rule sub-section) as the immediate proxy for F-29. | flagged-for-follow-up-clean-up-wave |

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
