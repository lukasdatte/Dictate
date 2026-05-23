# Audit Report: logic (Block 3, scope: full-block)

**Agent-ID:** B3-AUDIT-LOGIC
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-sql (NULL safety + CHECK constraints + cascade semantics)
**Files inspected:** 17 (production: `RecordingHardwareAdapter.kt`, `PipelineCallbackBridge.kt`, `AudioFocusSubsystemAdapter.kt`, `BluetoothScoSubsystemAdapter.kt`, `MigrationTo4.kt`, `SessionStatus.kt`, `SessionEntity.kt`, `SessionDao.kt`, `PipelineRecovery.kt`, `PipelineSessionRepoAdapter.kt`, `PipelineOrphanCleaner.kt`, `CacheDirAudioFileFactory.kt`, `LegacyAudioFileMigration.kt`, `DictatePipelineService.kt`, `PipelineOrchestrator.kt`, `DictateOrchestrator.kt`, `ResendModule.kt`)

## Summary

- Critical: 2
- Important: 5
- Nice-to-have: 3

## Findings

### AUDIT-LOGIC-B3-1 — CASCADE delete of POST_PROCESSING children when cleaning up old COMPLETED parents

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:143-144` and `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:97`
- **Description:** `deleteInsertedOlderThan(cutoff)` executes a plain `DELETE FROM sessions WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff`. The `sessions` table has a self-referential FK `parent_session_id REFERENCES sessions(id) ON DELETE CASCADE` (declared in M2→M3 and preserved verbatim in M3→M4). When a COMPLETED parent session (e.g. a RECORDING the user inserted 7+ days ago) becomes cleanup-eligible, the cascade deletes every child session that points at it via `parent_session_id`. Child sessions of type `POST_PROCESSING` are created when the user opens an old history entry and applies a prompt (see `SessionManager.createSession` validation: "POST_PROCESSING sessions must have a parent_session_id"). A freshly-created POST_PROCESSING child has its own recent `inserted_at`, but the parent's `inserted_at` is what gates the DELETE — the cascade then wipes the fresh child anyway.
- **Why it matters:** Concrete user-visible data loss: user records text on day 1 + inserts it; on day 8 opens that history row + runs a "translate-to-English" post-process prompt; the result is inserted, the new POST_PROCESSING session row gets `inserted_at = day 8`. At the next idle-stop, the cleanup query runs, the day-1 parent matches the 7d cutoff, parent is deleted, CASCADE wipes the day-8 child the user JUST created. The new transcript silently disappears from history. This contradicts the spec's stated intent ("the text has been surfaced to the user and the 7-day grace window has elapsed; the row + its child rows are no longer useful for history" — Spec 1 §6.2 R.17), but only because the spec doesn't consider the post-process-on-old-row scenario.
- **Suggested fix scope:** medium (DB or query change)
- **Suggested fix:** Either (a) add `AND id NOT IN (SELECT parent_session_id FROM sessions WHERE parent_session_id IS NOT NULL)` to the DELETE so parents with extant children are spared, or (b) change the FK to `ON DELETE SET NULL` (breaks the parent-child relationship but preserves child data — but this requires a new schema migration), or (c) recurse into children's `inserted_at`-eligibility before deleting the parent. Needs spec clarification (which option is intended).

### AUDIT-LOGIC-B3-2 — M3→M4 backfill exposes ALL pre-existing COMPLETED rows to the 7-day cleanup at the very first idle-stop after upgrade

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:118-131`
- **Description:** The migration backfills `inserted_at = created_at` for every pre-existing `status = 'COMPLETED' AND final_output_text IS NOT NULL` row. For users with existing history (the `getAll()` query in `HistoryActivity` shows COMPLETED rows are part of the user-visible history surface), this means EVERY historical COMPLETED row whose `created_at` is older than 7 days becomes immediately eligible for `deleteInsertedOlderThan(now - 7d)` at the next idle-stop. The first time the service is destroyed after the upgrade, the user's history older than 7 days is silently wiped (along with the transcripts, processing_steps, etc. via cascade — see also AUDIT-LOGIC-B3-1).
- **Why it matters:** Existing users may have months of history they perceive as a permanent record. The M3→M4 + cleanup combo silently strips it on the first idle-stop after the upgrade. The Spec acknowledges the backfill as "best-effort" but doesn't flag the data-loss consequence of activating the cleanup on rows that have effectively been `inserted_at`-untracked for their entire lifetime. The behavior is also surprising because the pre-M4 history list had no automatic cleanup at all.
- **Suggested fix scope:** medium (migration change or one-shot deferral)
- **Suggested fix:** Several options worth weighing: (a) backfill `inserted_at = max(created_at, now - gracePeriodMs + safetyBuffer)` so existing rows get a fresh-start grace window after upgrade; (b) introduce a separate "amnesty" SharedPreferences flag that suppresses `deleteInsertedOlderThan` for one cleanup cycle after the migration; (c) backfill `inserted_at = NULL` for ALL pre-existing rows and accept that they're never cleaned by the COMPLETED-grace path (they'd be cleaned by a hypothetical "deleteFailedOlderThan" if/when added per Spec 1 §6.3.1 follow-up). The spec needs to commit to a position; today's code silently breaks the implicit "history is forever" contract.

### AUDIT-LOGIC-B3-3 — `RecordingHardwareSubsystem.start()` is unreachable from any module Effect

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:184-195` (Preparing→Active reducer arm) + `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:94-112` (`start()` method)
- **Description:** The `RecordingHardwareSubsystem` interface declares `start()` with KDoc "Begin recording — must follow a successful `allocate`". The adapter implements it (calls `MediaRecorder.start()` + emits `EffectFailure` on `IllegalStateException`). But there is **no `Effect.StartMediaRecorder`** anywhere in `RecordingModule.Effect`, and the Preparing→Active reducer arm only emits `Effect.StartTimer`, `Effect.StartAmplitudeStream`, `Effect.StartBorderGlow` — never an effect that would call `services.recordingHardware.start()`. The interface contract says start must follow allocate, but the cascade never delivers that call. Currently dormant because the IME-side `RecordingManager` is the actual recording driver in Phase 1 (the chunk-C8 deviation table acknowledges the adapter is a "parallel production-quality path"), but the moment B5/B6 LayoutCatalog routes record-button clicks through `dispatch(Action.RecordingAction.StartRecording)`, MediaRecorder is prepared but never started — silent failure (no audio captured, file is empty when `Stop` arrives).
- **Why it matters:** This is a latent contract violation between the orchestrator-side recording flow and the adapter. The chunk-C8 KDoc explicitly states "RecordingHardwareAdapter is a parallel production-quality implementation that the DictateOrchestrator consumes via RecordingModule.runEffect"; that consumption path is incomplete. The TODO is documented as a Phase-1 limitation in C8's deviation table, but the gap between "interface contract" and "actual effect plumbing" is not captured in any audit-resistant place.
- **Suggested fix scope:** small (add `Effect.StartMediaRecorder` + map it in the Preparing→Active transition + runEffect arm). Out of scope for B3 per the C8 deviation note, but needs to be tracked for B5/B6.
- **Suggested fix:** Add `data object StartMediaRecorder : Effect` to `RecordingModule.Effect`, append it to the `MediaRecorderReady → Active` reducer's side-effect list, add `Effect.StartMediaRecorder -> services.recordingHardware.start()` to `runEffect`.

### AUDIT-LOGIC-B3-4 — onCreate ordering: `PipelineRecovery` launches asynchronously BEFORE `LegacyAudioFileMigration.run`, creating a write-race on legacy-audio rows

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:346-381` (orchestrator construction + Legacy migration order)
- **Description:** `DictateOrchestrator.<init>` calls `scope.launch { recovery.recover(store) }` with `scope = Dispatchers.Main.immediate`. The `launch` returns immediately, but the coroutine body runs **synchronously inline** on Main.immediate up to the first suspension point. That first suspension is `withContext(ioContext) { runStatusPromotion() }` — at which point the IO dispatcher takes over on a different thread to run the DAO queries. Control returns to `onCreate`, which then runs `LegacyAudioFileMigration.run(applicationContext)` synchronously on Main. Both paths now touch the `sessions` table concurrently: recovery's `getSessionsByStatuses` + status-update writes from IO thread, and `LegacyAudioFileMigration.markLegacyAudioSessionsFailed` UPDATE from Main thread. Room serializes via a single SQLite connection, so no data corruption — but the logical ORDER is non-deterministic: a `RECORDING` row referencing `cacheDir/audio.m4a` may be promoted by either path first, ending up with `last_error_message = "recording-interrupted-by-process-death"` (recovery) or `last_error_message = "audio_file_path_legacy_purged"` (LegacyAudioFileMigration). Both end-states have `status = FAILED` (so functionally equivalent), but the diagnostic context differs depending on dispatcher scheduling.
- **Why it matters:** Production debugging of legacy-data-related issues will see varying `last_error_message` values for the same underlying scenario, making support reports inconsistent. More importantly, the ordering rationale in the comment block ("Runs AFTER orchestrator construct (so DB and modules are live) and BEFORE the orphan-cleanup launch (so legacy entries leave the DB before `findAllAudioFilePaths()` consults it)" — DictatePipelineService.kt:372-374) is unfulfilled — the recovery's status-promotion writes start running before LegacyAudioFileMigration finishes.
- **Suggested fix scope:** small (re-order: run LegacyAudioFileMigration BEFORE constructing the orchestrator that triggers recovery, or use a `runBlocking { recovery.runStatusPromotion() }` style barrier).
- **Suggested fix:** Move `LegacyAudioFileMigration.run(applicationContext)` to BEFORE `orchestrator = DictateOrchestrator(...)`. The migration is sub-100ms per its own KDoc; running it synchronously before recovery starts gives deterministic ordering.

### AUDIT-LOGIC-B3-5 — `Action.ResendAction.NotifyManualPasteNeeded` carries a sessionId field the reducer ignores

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt:119-125` + `app/src/main/java/net/devemperor/dictate/state/Action.kt:274`
- **Description:** `data class NotifyManualPasteNeeded(val sessionId: String)` declares a `sessionId` payload. The reducer arm in `ResendModule` only reads it for the idempotence guard (`if (!state.lastResultNeedsManualPaste)`) — the `sessionId` value itself never enters `ResendState`. The state has a single `lastResultNeedsManualPaste: Boolean`. When `PipelineRecovery` dispatches one `NotifyManualPasteNeeded(id)` per pending-COMPLETED row (`PipelineRecovery.kt:153-155`), each call after the first is a no-op (idempotent), and the user has no way to know WHICH session's result needs pasting. If the user pastes once and dismisses the flag (`ClearManualPasteFlag`), the remaining N-1 sessions' results are silently never resurfaced.
- **Why it matters:** SF-4's intent is to recover from "IME process died after pipeline completed but before commitText landed" — the user needs to know there's a result waiting to be pasted. If multiple sessions reach this state (e.g. multiple OOM-deaths across a session-burst), only one is signaled. The other results sit in the DB indefinitely until the cleanup grace window passes.
- **Suggested fix scope:** medium (change ResendState shape: `lastResultNeedsManualPaste: Boolean` → `pendingPasteIds: List<String>` or `pendingPasteSessionId: String?`, plus UI consumer changes).
- **Suggested fix:** Replace the boolean with a queue/set of session-ids. The reducer adds-on-Notify, removes-on-ClearForId. Once the IME consumer (deferred to B5/B6 per the B3 report) wires up, it iterates the queue. Out of scope for B3 unless explicitly required; flag for B5+.

### AUDIT-LOGIC-B3-6 — Recovery `findPendingInsertion()` is queried twice (once via `sessionRepo.loadPending()`, once directly)

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:135 + 150-152`
- **Description:** `recover()` first calls `sessionRepo.loadPending()` which (via `PipelineSessionRepoAdapter.loadPending`) executes `sessionDao.findPendingInsertion()`. Then a few lines later it calls `sessionDao.findPendingInsertion()` directly for SF-4 dispatch. Two SELECT queries against the same data set in the same recovery pass.
- **Why it matters:** Mild inefficiency. The post-status-promotion DB state doesn't change between the two reads, so the data is identical. Cleaner would be to read once and use the result for both pending-hydration and SF-4 dispatch (since the COMPLETED+pending subset is exactly the second part of `loadPending`'s union).
- **Suggested fix scope:** small (restructure to read once, derive both pending-list and SF-4 dispatch list from the single result).
- **Suggested fix:** Move the `findPendingInsertion()` call to before Phase 2-3, capture the list, hydrate pendingSessions from it (combined with RECORDED-with-audio), and iterate it again for SF-4 dispatch.

### AUDIT-LOGIC-B3-7 — `PipelineOrphanCleaner.cleanupOrphanedTerminalAudio` increments `deletedFiles` for files that were already gone

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt:134-145`
- **Description:** The condition `val ok = !file.exists() || file.delete()` evaluates to `true` if either (a) the file is already gone (`!file.exists()` = true, no delete attempted) or (b) `file.delete()` returns true. Both cases increment `deletedFiles`. The DAO row clear list `cleared` is also added to in both cases. The KDoc explicitly accepts this ("the file already missing — both are 'success' for cleanup purposes"). The `CleanupResult.deletedAudioFiles` field as documented in `Aggregate diagnostic` is mildly misleading — the count reflects "rows whose audio_file_path was cleared", not "files actually deleted from disk".
- **Why it matters:** The metric is exposed in `Log.i(TAG, "orphan-cleanup: deletedAudioFiles=…")` (DictatePipelineService.kt:439-441). A future telemetry consumer or a developer trying to diagnose disk-usage might be misled. Acceptable per documented design, but worth tightening for clarity.
- **Suggested fix scope:** small (rename field or split into two counters).
- **Suggested fix:** Rename to `processedOrphanRows` or split into `filesActuallyDeleted` (only `delete()` returned true) + `rowsCleared` (existing semantic).

### AUDIT-LOGIC-B3-8 — `PipelineCallbackBridge.dispatch` swallows `Throwable` including JVM `Error` subclasses

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/PipelineCallbackBridge.kt:71-77`
- **Description:** The catch is `catch (t: Throwable)` — this catches `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath`, `LinkageError`, etc. The KDoc rationale ("A misbehaving IME-side callback must NOT abort the pipeline thread") justifies catching `Exception`, but catching `Error` is more aggressive than needed and can mask serious VM-level problems.
- **Why it matters:** Defensive-coding hygiene. Swallowing `OutOfMemoryError` makes the pipeline appear to continue while the JVM is in an unrecoverable state; the next allocation will throw too. Catching only `Exception` would still prevent normal NPE-on-View etc. from crashing the pipeline thread, while letting Error subclasses propagate and trigger Crashlytics.
- **Suggested fix scope:** small (replace `Throwable` with `Exception`).
- **Suggested fix:** Change `catch (t: Throwable)` to `catch (t: Exception)`. `RuntimeException`, `IOException`, `NullPointerException` etc. still get caught; `OutOfMemoryError` and friends propagate.

### AUDIT-LOGIC-B3-9 — Recovery's `EmptySessionDao` legacy-constructor leaves a footgun in production code

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:117-118 + 269-301`
- **Description:** The convenience constructor `PipelineRecovery(sessionRepo)` is described as "kept for backward compatibility with C7-era tests" but lives in production code (not test-only sources). It wires `EmptySessionDao` which silently returns empty/no-op for every query. If a developer wires this constructor in production (forgetting to thread through the real `sessionDao`), recovery silently does nothing for the §6.3 algorithm — no RECORDING-promotion, no TRANSCRIBING-downgrade, no ghost-cleanup. The data degrades quietly; no error surfaces.
- **Why it matters:** API design risk. The single-arg constructor is indistinguishable in usage from the primary constructor; the silent no-op semantics violate the principle of failure-loud-failure-soft. Test-only constructors should live in `src/test/` (or be marked `@TestOnly` / annotated such that production wiring can't accidentally pick them).
- **Suggested fix scope:** small (move the convenience constructor + EmptySessionDao to `src/test/java/.../testutil/`, or annotate with a marker that ProGuard/lint can enforce).
- **Suggested fix:** Move `EmptySessionDao` and the secondary constructor to a test-fixture file under `app/src/test/java/net/devemperor/dictate/testutil/EmptySessionDao.kt` and update C7-era tests to import it explicitly. The primary constructor becomes the only production surface.

### AUDIT-LOGIC-B3-10 — `Effect.AllocateMediaRecorder` failure path doesn't reset suppress-bit; FSM left in `Preparing` if `MediaRecorder.start()` would fail

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:73-86` (prepare-fail) and `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:343+` (`reduceFailure`)
- **Description:** When `MediaRecorder.prepare()` throws `IOException`, the adapter releases the recorder and emits `Action.EffectFailure(originModuleId = ModuleId.Recording, effect = "AllocateMediaRecorder(...)", reason = ...)`. The orchestrator routes this to `RecordingModule.reduceFailure`. Let me check that the reducer arm exists and transitions Preparing → Idle. Looking at the reduceFailure method on line 343, only AllocateMediaRecorder + StopMediaRecorder are handled (per the chunk-C8 deviation note "Failure recovery for Effect.AllocateMediaRecorder (cache-wipe, MIC-permission revoked mid-prepare, etc.) and Effect.StopMediaRecorder (too-short stream throw)"). However, the adapter's `start()` method ALSO emits `EffectFailure(effect = "StartMediaRecorder")` for IllegalStateException — but since (per AUDIT-LOGIC-B3-3) there's no Effect that calls `start()` in the orchestrator-side flow today, this is unreachable. However, if AUDIT-LOGIC-B3-3 is fixed and a `Effect.StartMediaRecorder` is added without a corresponding `reduceFailure` arm, the FSM would stay in Active while the hardware is actually broken.
- **Why it matters:** Tightly coupled to AUDIT-LOGIC-B3-3 — the fix for that finding needs a `reduceFailure` arm that handles `StartMediaRecorder` failure (transition Active → Idle + release hardware). Today the gap is dormant; when B5/B6 closes B3-3, this needs to be closed too.
- **Suggested fix scope:** small (add reduceFailure arm for the new effect).
- **Suggested fix:** When `Effect.StartMediaRecorder` is added, also add the matching `reduceFailure` arm so a start-failure rolls the FSM back to Idle and emits `Effect.ReleaseMediaRecorder`.

## Coverage

- Files audited:
  - `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt`
  - `app/src/main/java/net/devemperor/dictate/core/PipelineCallbackBridge.kt`
  - `app/src/main/java/net/devemperor/dictate/core/AudioFocusSubsystemAdapter.kt`
  - `app/src/main/java/net/devemperor/dictate/core/BluetoothScoSubsystemAdapter.kt`
  - `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileFactory.kt`
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
  - `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (KG-AFF-1 patch)
  - `app/src/main/java/net/devemperor/dictate/core/ResendStatusDispatcher.kt`
  - `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt`
  - `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt`
  - `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`
  - `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`
  - `app/src/main/java/net/devemperor/dictate/history/HistoryAdapter.java`
  - `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt`
  - `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (SessionCleanupGracePeriodMs)
  - `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
  - `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt`
  - `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt`
  - `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt` (NotifyManualPasteNeeded reducer)
  - `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt` (cross-check StartMediaRecorder absence)
  - `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt` (init order, scope.launch timing)
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (Pre-Dispatch allocate call site)
- Files skipped (with reason): test files — handled by AUDIT-TEST topic.
- Knowledge-skill checkpoints applied:
  - knowledge-sql: NULL safety (`audio_file_path?.let { File(it).exists() } == true` is correct null-handling), CASCADE semantics (drove finding B3-1 + B3-2), CHECK constraint behavior (verified `SessionStatus` enum vs SQL CHECK consistency — all 6 variants in both — passes), strict-vs-inclusive cutoff (`< :cutoff` consistently strict — passes).

## Out-of-scope observations

- **(Convention)** `LegacyAudioFileMigration` uses `PreferenceManager.getDefaultSharedPreferences(context)` for its idempotence flag, while the rest of the app uses the named `getSharedPreferences("net.devemperor.dictate", ...)` prefs file (CLAUDE.md: "Preferences are always accessed through DictatePrefs.kt sealed class — never use raw string keys"). Functional correctness OK (both are persistent), but breaks the project convention. Flag for `convention` agent.
- **(Convention)** `LegacyAudioFileMigration.FLAG_PREF = "legacy_audio_purged_v4"` is a raw string key, not a `Pref` sealed-class entry. Same convention concern. Flag for `convention` agent.
- **(plan-and-api)** The B3 report's C9 deviation table notes that `markLegacyAudioSessionsFailed` takes `failedStatus: String` (not `SessionStatus`) per project convention. The DAO signature confirms this. Consistent with the deviation rationale ("Room has no built-in converter for SessionStatus, and the existing updateStatus(id, status: String) uses the same boundary"). No logic issue.
- **(Test)** No JVM unit test exercises the `PipelineOrchestrator.persistNewSession` KG-AFF-1 sofort-delete (`runCatching { audioFile.delete() }`) — the C11 report explicitly notes "PipelineOrchestrator KG-AFF-1 patch has no unit-test". Flag for AUDIT-TEST.
- **(Test)** Pure JVM test coverage of the M3→M4 migration backfill ordering (when concurrent with recovery's status-promotion) does not exist; the migration is exercised only in instrumented `MigrationTo4Test`. The race documented in AUDIT-LOGIC-B3-4 has no regression test. Flag for AUDIT-TEST.
