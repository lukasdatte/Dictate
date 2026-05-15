# Audit Report: plan-and-api (Block 3, scope: full-block)

**Agent-ID:** B3-AUDIT-PLAN-AND-API
**Date:** 2026-05-15
**Knowledge skills used:** none (topic is plan-conformity + stub markings + cross-chunk API contracts; ADR-0001, ADR-0003, and `docs/DATABASE-PATTERNS.md` were read directly as the relevant grounding).
**Files inspected:** 26 production + 17 tests + 5 plan/spec/ADR/pattern docs

Production files inspected:

- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (consumer side — bind + Pre-Dispatch + LanguageController bridge)
- `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (KG-AFF-1 patch sites only)
- `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileFactory.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineCallbackBridge.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/BluetoothScoSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/AudioFocusSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingTimerAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/AmplitudeStreamAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/BorderGlowAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt`
- `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt`
- `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt`
- `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt`
- `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt`
- `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java`
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`

Test files inspected (selectively):

- `app/src/test/java/net/devemperor/dictate/state/PipelineRecoveryFullTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/PipelineOrphanCleanerTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/PipelineSessionRepoAdapterTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/ResendModuleTest.kt`
- `app/src/test/java/net/devemperor/dictate/core/CacheDirAudioFileFactoryTest.kt`
- `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt`
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceCompositionTest.kt`
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceAudioFileFactoryWiringTest.kt`
- (plus 9 adapter tests, FakeSessionDao + SessionStatus/SessionEntity/Migration metadata tests)

Plan / spec / ADR docs read:

- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md` (Building Blocks 3 + 4)
- Spec 1: §4.11 full, §6.1, §6.1.1, §6.2, §6.3, §6.3.1, §6.4, §7.1, §7.3, §9.6, §10 (Block 3 + Block 4 acceptance), §11.2.2 (Block 3 + Block 4), §11.4, §11.6, §11.7, §11.7.0a, §15.x
- ADR-0001, ADR-0003
- `docs/DATABASE-PATTERNS.md` (Double-Enum)

## Summary

- Critical: 0
- Important: 4
- Nice-to-have: 4

The block delivers a complete and well-structured Migration-Persistence-AudioFactory implementation. Plan-treue is high across C8–C11. The IMPL-1 closure is properly implemented (Service.onCreate owns AI infrastructure construction; IME pulls via LocalBinder; JobExecutor.initialize moved as Spec 1 §11.2.2 step 7 requires). SF-4 is closed via PipelineRecovery → `Action.ResendAction.NotifyManualPasteNeeded` → ResendModule reducer (with idempotence + dedicated test). M3→M4 schema migration follows the Double-Enum CHECK-recreate pattern correctly. All five KG-AFF (AudioFileFactory) known-gaps are addressed 1:1 with code patches in C11. KG-SST-2 (orphan cleanup) is wired via `PipelineOrphanCleaner`.

The findings below are forward-compatibility gaps, plan-deviations with thin scope-pointer rationale, and three acceptance-criteria test gaps. None block C12+ wiring; B4+ should pick them up.

## Findings

### AUDIT-PLAN-AND-API-B3-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:501-530`
- **Description:** Service `onDestroy` skips both the `runBlocking + withTimeout(2_000L)` wrapper around `orchestrator.shutdown()` AND the Pre-Cancel-Dispatch step (Spec 1 §7.3 + ADR-0003 "Required mechanics" items 8 + 9 + §10 Block-2 acceptance "MediaRecorder-release-Pfad" + "onDestroy-Timeout"). The block report's C8 deviation table acknowledges this as deferred ("module-terminate is still synchronous in C8; no suspending paths added… C-2/C-3 fix is plan-deferred"), but the deferral is documented inline-fixed and there is no scope-pointer to a future-block issue: the C8-deviation entry has `Resolved? = (Not C8 scope per existing KDoc rationale)` — i.e. no follow-up tracking. ADR-0003 §"Required mechanics" item 9 ("Pre-Cancel-Dispatch on `onDestroy`") and item 8 ("`runBlocking { withTimeout(2_000L) { orchestrator.shutdown() } }`") are both BINDING ("Required mechanics" not "Optional"); §"Failure Modes" explicitly lists them as "What goes wrong if violated."
- **Why it matters:** The two Block-2 acceptance bullets ("MediaRecorder-release-Pfad" + "onDestroy-Timeout") cannot be checked off as long as this deferral stands. The MediaRecorder will leak its native-heap allocation when the service is destroyed during active recording — exactly the failure-mode ADR-0003 documents. The block-validate-pass should either track this as a postponed issue carrying forward to B4/B5 or close the gap inline.
- **Suggested fix scope:** medium (mechanically a 10-line `try { runBlocking { withTimeout(2_000L) { orchestrator.shutdown() } } } catch (TimeoutCancellationException) { … }` wrapper + a 6-line Pre-Cancel-Dispatch state-snapshot read). Spec 1 §7.3 has the literal snippet ready to copy. The block report's reasoning (RecordingModule.terminate is still synchronous; module-terminate has no `suspend`) does not contradict the Pre-Cancel-Dispatch — `Action.RecordingAction.CancelRecording` already flows through the existing reduce → runEffect path.

### AUDIT-PLAN-AND-API-B3-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java:286-298`
- **Description:** C11's KG-AFF-3 recursive Cache-Clear is implemented, but the Phase-B S-7 race-protect for "active recording during Cache leeren" is MISSING. Spec 1 §4.11.6.3 mandates an `isRecordingActive()` guard with a dedicated `dictate_cache_clear_blocked_recording` toast string (§10 Block-4 acceptance: "Race-Schutz gegen aktive Recording (Phase-B S-7 F-10): Click ist disabled + Toast bei `state.recording !is Idle`"). The current implementation goes straight from confirm dialog → `clearCacheRecursively(cacheDir)` with no recording-state check; `dictate_cache_clear_blocked_recording` string does not exist in any resource file.
- **Why it matters:** A user who clicks "Cache leeren" mid-recording will `unlink()` the open MediaRecorder FD. MediaRecorder keeps writing to the unlinked inode; recording stops successfully on disk but the file is gone (no dirent), and `persistFromCache` then fails — Ghost-Session FAILED — with no clear user-facing explanation. The race window is small (settings activity vs. IME service typically not visible at the same time) but the failure mode is exactly the silent-corruption case the spec calls out as the reason for the guard.
- **Suggested fix scope:** small-medium (one bound-service-state read in PreferencesFragment + one string resource in `values/strings.xml` + `values-de/strings.xml`). The state read needs the same Java-bridge mechanism the IME uses (`DictateUiStateObserver` or a snapshot read via `pipelineBinder`). Block 6+ may need this for the overlay flow anyway.

### AUDIT-PLAN-AND-API-B3-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1617-1620`
- **Description:** Plan §4.11.5.4 migration step 1 ("Entfernen: Field `private File audioFile;` in `DictateInputMethodService.java:208`") is acknowledged as a D-14 deviation in the C11 block-report deviations ("`audioField` not yet removed, full removal requires the orchestrator-side path to be primary, which lands in B5/B6"). That deferral is reasonable. **However**, the residual legacy path at line 1617 (`audioFile = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.TranscriptionAudioFile.INSTANCE));`) bypasses the new `CacheDirAudioFileFactory` entirely — the import-an-audio-file flow constructs a `File` in the cache root (the legacy fixed-name layout), not in `cacheDir/audio/`. This file will fall OUTSIDE the scope of `CacheDirAudioFileFactory.cleanupOrphans` (whose `audioCacheDir` is `cacheDir/audio/`), so it leaks indefinitely unless the user manually clears the cache.
- **Why it matters:** Two related drift artefacts: (a) the import-audio-file feature does not benefit from KG-AFF-4's 60s freshness cutoff (collision-free naming, multi-job safety), and (b) the imported file is invisible to the new orphan-cleanup pass. Spec 1 §11.2.2 Block-4 step 13 calls for "`audioFile`-Field Z. 208 + Allokations-Zeile Z. 1612" to be deleted; the block report cites step 1612 but step 1617 (import-audio-file) is the second allocation site and isn't addressed.
- **Suggested fix scope:** small-medium. Either (1) route the import flow through `pipelineBinder.audioFileFactory.allocate()` like the regular recording path (with an IOException fallback), or (2) document the import flow as out-of-scope for AudioFileFactory in the C11 deviations table with a clear B5/B6 follow-up pointer. The current state has neither — the legacy line is untouched by the C11 patch and the block report does not mention it.

### AUDIT-PLAN-AND-API-B3-4

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:322` + `324`
- **Description:** Three subsystems in `ModuleServices` still come from `PipelineServiceStubSubsystems` in production:
  - `pipelineRunner = PipelineServiceStubSubsystems.pipelineRunner` — `submit`/`cancel` are log-only no-ops; `isRunning` always returns `false`; `activeJobCount` always returns 0.
  - `notificationCoordinator = PipelineServiceStubSubsystems.notificationCoordinator` — `show()` and `dismiss()` log only; no real notification updates from state changes.
  - `bluetoothSco` falls back to the stub when `audioManager == null` (production path defensive only; not a real concern outside test setups).
  The block report's C8 narrative claims the migration "covers all subsystems per Spec 1 §11.2.2 Block-3" and lists "RecordingHardware/BluetoothSco/AudioFocus/Timer/Amplitude/BorderGlow/PipelineCallbackBridge" — but `PipelineRunnerSubsystem` and `PipelineNotificationCoordinatorSubsystem` are part of `ModuleServices` (Spec 1 §4.7 lists them with the other 12 subsystems) and remain stubbed.
- **Why it matters:** Two consequences:
  1. **`PipelineRunnerSubsystem` is not yet wired** — the orchestrator-side `Effect.SubmitPipelineJob` path goes into a log-only stub. The legacy IME-side `JobExecutor.start()` is still the only real pipeline-runner. This is consistent with the phased plan (orchestrator-side recording wiring lands when LayoutCatalog drives recordings end-to-end, B5/B6) but is NOT documented in the C8 deviations table or block-report. A reader who follows §4.7 expecting all 14 subsystems to be live will be surprised.
  2. **`PipelineNotificationCoordinatorSubsystem` is stubbed** — the persistent FGS notification has only the initial title built by `buildInitialNotification()` and never updates from state changes (Recording-Active → "Aufnahme läuft" etc.). The §10 Block-2 acceptance "Beim Recording: persistente Notification sichtbar, zeigt korrekte Action-Buttons" is therefore not met. `PipelineNotificationCoordinator` (Spec 1 §7.4) and `PipelineActionRouter` (Spec 1 §7.5) are referenced extensively in the spec but neither real class exists in the codebase.
- **Suggested fix scope:** medium. Either (a) document the two stubs explicitly in the block-report's Deviations table with B5/B6 follow-up scope pointers, or (b) note them under "Overlooked points / known gaps" so the block-validate-pass tracks them. The plan-orchestrator can decide whether to defer the real implementations or block-3 should close at least the notification coordinator (the spec sections §7.4-§7.5 give code that can be copied without modular-orchestrator dependencies).

### AUDIT-PLAN-AND-API-B3-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:117-118`
- **Description:** The convenience constructor `PipelineRecovery(sessionRepo)` wires an `EmptySessionDao` (a private singleton with ~20 no-op overrides) and `EmptyCoroutineContext`. Its rationale is documented (back-compat with C7-era tests, e.g. `DictateOrchestratorInitOrderTest`), but the C7 test that drove the original signature should be updated to use the primary constructor with a fake DAO — the legacy path adds ~30 lines of no-op overrides without buying long-term flexibility. The status-promotion call path becomes silently inert if the legacy constructor is accidentally used in a non-test context.
- **Why it matters:** Spec 1 §4.6 + §6.3 names `PipelineRecovery.recover()` as the ONLY production path to load pending sessions + run §6.3 promotion. A legacy constructor that runs the recover() body but always sees an empty candidate list is a subtle footgun. Production wiring at `DictatePipelineService.kt:340-344` correctly uses the primary constructor, but a future implementer who copies the legacy signature loses §6.3 promotion silently.
- **Suggested fix scope:** small. Either (a) migrate C7-era tests to use the primary constructor with `FakeSessionDao` and remove the legacy constructor + `EmptySessionDao`, or (b) tighten the KDoc to say "test-only" and consider `@VisibleForTesting`.

### AUDIT-PLAN-AND-API-B3-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:60` (header KDoc) + entire file
- **Description:** The file's header KDoc (line 10-59) is still phrased as if Block 3 was upcoming work ("the real Android-backed implementations land in Block 3 (subsystem-adapter migration, chunk C8) — pre-existing legacy classes (`RecordingManager`, `BluetoothScoManager`, …) get re-fronted by adapter shims at that point"). After B3 lands, the file has shrunk in production usage from "14 stubs" to "2 stubs + 1 fallback" — but the docs still claim Block 3 is the replacement window. The `audioFileFactory` block (line 184-195) is correctly marked deprecated; the `sessionRepo` block (line 129-150) is correctly noted as superseded by C10; but the file-level KDoc + the per-property KDocs for `recordingHardware`, `bluetoothSco`, `audioFocus`, `recordingTimer`, `amplitudeStream`, `borderGlow` still say "B3 fills this — module emitted an effect that the stub absorbs" (the const `MESSAGE = "B3 fills this …"`). After C8 lands, those stubs are NOT used in production wiring — only test code reaches them.
- **Why it matters:** Documentation drift. A reader investigating "why does my module emit a log-only call" who lands on the stub file will see the "B3 fills this" message and assume B3 hasn't run yet, when in fact the wiring at `DictatePipelineService.kt:239-286` already swapped to real adapters. The block-report's C8 narrative mentions the file was "Updated KDoc on `stubSessionRepo()` — marked 'deprecated (kept for compile-compat)'" but the rest of the file-level KDoc and `MESSAGE` constant weren't touched.
- **Suggested fix scope:** small (mechanical KDoc rewrite + flip `MESSAGE` to "Test-only stub — real subsystem lives in the *Adapter classes in core/").

### AUDIT-PLAN-AND-API-B3-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:799-823`
- **Description:** The D-13 deviation (LanguageController NOT fully replaced by LanguageModule) is documented in the C8 block report with sound reasoning (D5 sustainability: LanguageController has 30+ usages across IME + Settings activity; deletion is invasive). The implementation uses a one-line bridge dispatching `Action.LanguageAction.RefreshFromPref` into the orchestrator. The block report says: "deletion comes in B4-B6" / "B5/B6 LayoutCatalog routes through orchestrator". **Gap:** there is no `Action.LanguageAction.RefreshFromPref` reducer arm verification mentioned in the audit chain, and the deviation table cell ("Impact on later chunks: None") understates the impact — `state.language` may be stale w.r.t. `LanguageController.lastEffective` between init and the first dispatch (LanguageController constructor reads from prefs in its own init order, and the bridge fires only on subsequent prefs change). Spec 1 §9.6 explicitly says "wandert direkt in den Modul-Reducer; keine Adapter-Phase nötig" — Block 1 (which in the chunks-json mapping is B2's LanguageModule) was supposed to subsume LanguageController. The bridge is the adapter-phase the spec said was unnecessary.
- **Why it matters:** Forward-compat — future-block authors who read §9.6 ("no adapter phase needed for LanguageController") will be surprised to find one. The deferral is appropriate but the documentation footprint is thin: no follow-up issue is created in the block report's Issue Index, only the deviation table entry. Adding an explicit "B4-B6 follow-up: delete LanguageController + bridge" issue with `delegated-to-orchestrator` would close the loop.
- **Suggested fix scope:** small (orchestrator-only — add an Important follow-up issue to the block-report's Issue Index with `delegated-to-orchestrator` status + a clear cross-link to B4/B5/B6 scope; verify LanguageModule has a `RefreshFromPref` reducer arm).

### AUDIT-PLAN-AND-API-B3-8

- **Severity:** Nice-to-have
- **File:** Plan §10 Block-3 + Block-4 acceptance criteria — three named test files do NOT exist
- **Description:** Three plan-acceptance test files for Block 3 + Block 4 are NOT in the codebase:
  - `DictatePipelineServiceCleanupOrderTest.kt` (Spec 1 §10 Block-3 Phase-B S-2 acceptance: "im `stopSelf`-Pfad läuft `dao.deleteInsertedOlderThan(cutoff)` VOR `cleanupOrphanedTerminalAudio()` VOR `stopSelf()`")
  - `DictatePipelineServiceBootOrphanCleanupTest.kt` (Spec 1 §10 Block-4 acceptance: "Boot-Cleanup-Hook" verification)
  - `PipelineOrchestratorPersistTest.kt` (Spec 1 §10 Block-4 acceptance: KG-AFF-1 `audioFile.delete()` after `persistFromCache`)
  - `ResolverPreDispatchAllocateTest.kt` (Spec 1 §10 Block-4 acceptance: pre-dispatch allocation in resolvers)
  The C11 block-report acknowledges that `PipelineOrchestratorPersistTest` is missing ("PipelineOrchestrator KG-AFF-1 patch has no unit-test… flagged for B4 AUDIT-TEST if coverage policy demands it"). The other two are not flagged.
- **Why it matters:** Some of the same behaviour is covered by other tests (e.g., `PipelineOrphanCleanerTest` covers the dual-cleanup-order at the cleaner-class level; the boot-orphan-cleanup is partly covered by `DictatePipelineServiceAudioFileFactoryWiringTest`). The plan named explicit test files for acceptance verification; the block-validate-pass should either confirm the alternate-coverage chain or add the named tests. ResolverPreDispatchAllocateTest is genuinely missing — no test currently asserts the IME's `startRecording` calls `audioFileFactory.allocate()` and translates `IOException` to the `dictate_storage_full` toast (it's mocked / not Robolectric-tested).
- **Suggested fix scope:** medium. Either (a) confirm alternate-coverage explicitly in the block-report (linking the existing tests that cover each acceptance bullet), or (b) add the named tests. The first option is cheaper; the audit just needs evidence the coverage exists.

## Deviation-Resolutions (plan-gaps filled or stubs implemented as deviations)

The following plan-prescribed items were filled correctly per the C8–C11 deviation tables and are NOT findings:

- **C9 — `markLegacyAudioSessionsFailed` takes `String` not `SessionStatus`.** Correct per Double-Enum boundary convention (`docs/DATABASE-PATTERNS.md` §"DAO methods take `String`, not the enum type"). Spec snippet §4.11.6.2 was inconsistent with its own §6.3 text — implementation correctly follows the project convention.
- **C9 — Lint block omits `abortOnError true`.** Correct deferral: the worktree carries 65 pre-existing lint errors unrelated to C9; enabling abortOnError would break C9-build. Spec §11.7.0 Cost-Check explicitly recommends baseline-pass first. C9 ships `error += "EnumSwitch"` (the severity promotion) without the abort; the deviation table entry is clear.
- **C10 — `PipelineRecovery` constructor takes `ioContext: CoroutineContext`.** Production passes `Dispatchers.IO` (matches spec); tests inject `EmptyCoroutineContext` for `runTest` scheduler sync. Addition is local-only, no spec-conflict.
- **C10 — `Pref.SessionCleanupGracePeriodMs` default = `608_400_000` ms (7d + 1h).** Correct per Spec 1 §6.2 R.17 — spec defines the cutoff symbolically (`now − 7d − 1h`), the Pref makes it tunable. Default constant matches the formula `7 * 24 * 3600 * 1000 + 3600 * 1000`.
- **C10 — `triggerOrphanCleanupAsync` runs from `onDestroy` (not from a state-driven `stopSelfWhenTerminal` callback).** Correct deferral: the state-driven trigger requires the modular orchestrator to drive `stopSelf` via state observation; that's B5+ scope. The onDestroy slot is idempotent + best-effort, so calling cleanup there is safe; reuse from a future stopSelfWhenTerminal callback is straightforward.
- **C11 — `IsDirectory` guard added to `CacheDirAudioFileFactory.allocate()`.** Local hardening — spec's `audioCacheDir.exists() && audioCacheDir.mkdirs()` would short-circuit on a regular file at that path; the explicit guard fails fast with a clearer message. Code-quality fix, not a deviation in intent.
- **C11 — All five KG-AFF (1-5) resolutions implemented 1:1.** Verified: KG-AFF-1 patch at `PipelineOrchestrator.kt:867-874`; KG-AFF-2 `LegacyAudioFileMigration.kt` with idempotence pref flag + DAO `WHERE status NOT IN (...)` filter; KG-AFF-3 `clearCacheRecursively` + `computeCacheSizeRecursive` + `countCacheFilesRecursive` in `PreferencesFragment.java`; KG-AFF-4 60s cutoff via `lastModified()` filter in `CacheDirAudioFileFactory.cleanupOrphans`; KG-AFF-5 `requireNotNull(cacheDirProvider())` in the lazy `audioCacheDir` init. KG-AFF-3 race-protect is NOT implemented (see Finding B3-2 above).
- **IMPL-1 closure verified.** Service.onCreate now constructs AIOrchestrator + AutoFormattingService + PromptQueueManager + SessionManager + SessionTracker + PromptService + RecordingRepository + legacy PipelineOrchestrator + PipelineCallbackBridge, and calls `JobExecutor.initialize(pipelineOrchestratorImpl)` at line 227. IME's `initLongLivedObjects` (line 399-469) explicitly does NOT construct these — comment at line 413-421 documents the migration: "AI infrastructure ownership transferred to DictatePipelineService". IME's `bindAiInfrastructureFromService` (line 485-501) pulls references from LocalBinder + registers callbacks. Verified by `DictatePipelineServiceCompositionTest.kt`.
- **SF-4 closure verified.** `PipelineRecovery.recover()` at line 150-155 calls `sessionDao.findPendingInsertion()` and dispatches `Action.ResendAction.NotifyManualPasteNeeded(entity.id)` per row. `ResendModule.reduce` at line 119-125 flips `state.copy(lastResultNeedsManualPaste = true)` idempotently. Tests at `PipelineRecoveryFullTest.kt:196-241` cover (a) dispatch happens for pending-insertion COMPLETED rows, (b) does NOT dispatch for already-inserted COMPLETED rows, (c) the per-id dispatch count matches the row count. End-to-end wiring verified: dispatch site → reducer arm → state flag → ResendModule reducer tests in `ResendModuleTest.kt:105-135`.
- **Double-Enum invariants verified for SessionStatus (6 variants).** Kotlin `enum class SessionStatus` at `SessionStatus.kt:25-69` matches the SQL `CHECK` literal at `MigrationTo4.kt:76-79` exactly: `'RECORDING', 'RECORDED', 'TRANSCRIBING', 'COMPLETED', 'FAILED', 'CANCELLED'`. KDoc points at `docs/DATABASE-PATTERNS.md`. `SessionEntity.statusEnum` preserves `runCatching { SessionStatus.valueOf(...) }.getOrDefault(SessionStatus.RECORDED)` fallback (per pattern). DAO methods take `String` not enum at the application boundary. AndroidTest `MigrationTo4Test` (7 cases) verifies CHECK acceptance + invalid rejection + status preservation.

## API consumer match (cross-chunk)

- **C8's `PipelineCallbackBridge` consumed by IME via LocalBinder?** ✅ — `LocalBinder.registerPipelineCallback(this)` called at `DictateInputMethodService.java:498` from `bindAiInfrastructureFromService`; `LocalBinder.registerPipelineCallback` delegates to `pipelineCallbackBridgeImpl.setDelegate(callback)`.
- **C9's `SessionDao` consumed by C10's `PipelineSessionRepoAdapter`?** ✅ — `PipelineSessionRepoAdapter` constructor at line 69-71 takes `SessionDao`; `loadPending` uses `sessionDao.getSessionsByStatuses(...)` + `sessionDao.findPendingInsertion()`. Both DAO methods exist in C9's SessionDao at lines 122-131 + 189-190.
- **C10's `PipelineRecovery` wired into Service.onCreate before first dispatch?** ✅ — `DictatePipelineService.kt:340-353` constructs `PipelineRecovery(sessionDao, sessionRepoAdapterImpl, emitAction)` then passes it to `DictateOrchestrator` constructor. ADR-0003 + Spec 1 §4.3 contract: `prefMirror.attach` before `recovery.recover` is the orchestrator's `init` responsibility (verified at Spec 1 §4.10 + §11.2.2 Block 1b step 8; B2 audit reports already confirmed this contract holds).
- **C11's `AudioFileFactory` consumed by IME's `startRecording` (Pre-Dispatch)?** ✅ — `DictateInputMethodService.java:1834-1851` reads `pipelineBinder.getAudioFileFactory().allocate()`. Both the binder-null defensive path (toast `dictate_service_not_ready`) and the IOException path (toast `dictate_storage_full`) are wired. The same `audioFileFactoryImpl` instance is exposed via both `binder.audioFileFactory` AND `ModuleServices.audioFileFactory` (line 329 of DictatePipelineService.kt) — single source of truth confirmed.
- **C11's `LegacyAudioFileMigration` runs at the right Service lifecycle moment?** ✅ — Called at `DictatePipelineService.kt:376` synchronously after orchestrator-construct (step 5 of Spec 1 §4.11.5.1) and BEFORE the async `audioFileFactoryImpl.cleanupOrphans` launch (step 6). Sync runtime is bounded (<200ms worst-case per spec §4.11.6.2 threading note) — FGS 5s budget preserved. Try/catch wrapper present.

## Coverage

- **Files audited:** all 26 production files modified or added by B3 (per git-diff against `b97e09a`), plus 17 representative test files, plus 5 plan/spec/ADR/pattern reference documents.
- **Files skipped (with reason):** none. Spec §11.4 was sampled (§11.4.2 androidTest scaffolding); other §11.4 subsections cover material orthogonal to plan-and-api scope (e.g. §11.4.3 RecordingManager FK-test methodology — that's CONVENTION/LOGIC scope).
- **Knowledge-skill checkpoints applied:** Double-Enum pattern (`docs/DATABASE-PATTERNS.md`) against `SessionStatus.kt` + `MigrationTo4.kt` + `SessionDao.kt` + `SessionEntity.kt`. ADR-0001 + ADR-0003 cross-referenced against `DictatePipelineService.onCreate` composition root + `PipelineRecovery` recovery contract + `onDestroy` ordering. Plan §9.6 deletion table cross-referenced against `LanguageController` deletion deferral.

## Out-of-scope observations

(Flagged briefly per the prompt's note — picked up by other audit topics or by the consolidator.)

- **LOGIC topic candidate:** `PipelineRecovery.runStatusPromotion()` filters candidates by status enum AFTER the `getSessionsByStatuses` call — three independent loops over the same list. Not incorrect, but a `groupBy { it.statusEnum }` could be cleaner. CONVENTION-style polish.
- **TEST topic candidate:** The C11 wiring test `DictatePipelineServiceAudioFileFactoryWiringTest.kt` only has 2 tests (binder + cacheDir scope). The audit-trail in the C8 block report claims 14 Robolectric tests for "IMPL-1 closure"; cross-check whether `DictatePipelineServiceCompositionTest.kt` actually has 14 tests + whether `triggerOrphanCleanupAsync` (internal) is tested.
- **CONVENTION topic candidate:** `DictatePipelineService.kt` mixes import styles — fully qualified `kotlinx.coroutines.runBlocking` references are absent because they're not used, but `android.view.inputmethod.InputConnection` is fully qualified at line 688 vs. imported elsewhere. Style nit, not plan-and-api.
