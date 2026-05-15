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
