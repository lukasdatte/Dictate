# Research: Pending-Transcription Persistence + Background-Job Architecture (A+B)

**Date:** 2026-05-07
**Branch:** `feature/language-chip-curation`
**Research scope:** How the Dictate IME can build a background send with persistence — variant A (WorkManager + DB persistence) **plus** variant B (service-local coroutine, good UX) combined. Goal: on the next IME activation the restart-button area shows either "Backgrounded (in progress)" or "Background processing done" — one click inserts.
**Related plan:** [keyboard-layout-refactor.md](../../keyboard-layout-refactor.md) (layout refactor) — this research is preparation for a **separate** follow-up feature, not part of the layout refactor.
**No code written.** All examples in this document are marked as **pseudo-code**.

---

## TL;DR

Dictate already has a surprisingly good starting position:

- **Room DB** with fully built pipeline persistence (`sessions`, `transcriptions`, `processing_steps`, `text_insertions`, `completion_log`). Schema version 3, with the Double-Enum pattern and CHECK constraints.
- **`SessionEntity`** already carries `status ∈ {RECORDED, COMPLETED, FAILED, CANCELLED}` and `last_error_*`. RECORDED = "audio persisted, no result yet" — exactly the state the background send produces on service death.
- **`SessionStatus.COMPLETED` with `finalOutputText != null`** is effectively already the "background done, insertion pending" state. The resend button reads it today via `SessionTracker.getLastKeyboardSession()` + `ResendStatusDispatcher`.
- **`JobExecutor`** is a process-wide singleton pipeline with cooperative cancellation. **`ActiveJobRegistry`** is a StateFlow-based live registry for "a job is running right now" — the reactive pipeline exists in part.
- **WorkManager is NOT integrated.** No dependency, no worker classes.

So the A+B architecture is **not greenfield** but an **extension of three existing layers**:

1. **Persistence (Room):** a small status adjustment (`IN_FLIGHT` as an optional new status, or purely via the `ActiveJobRegistry` flag). No new table needed — `sessions` is already a pending queue.
2. **Job layer:** `JobExecutor` (B) stays the hot path; **WorkManager (A)** runs as a backup re-enqueue, idempotent via session ID + status check.
3. **UI:** the restart-button area reads `SessionEntity.status` + `finalOutputText` — the logic already exists in `ResendStatusDispatcher.decide(...)`. The extension by `BackgroundedSending` and `BackgroundedReady` is a pure state-matrix addition.

Recommendation: **no new `pending_transcription` table.** The existing `sessions` entry IS the pending row. Instead, a thin WorkManager worker as a watchdog over existing `RECORDED`/`FAILED` sessions. Reasoning in [section C](#c-datenmodell-vorschlag).

---

## A. Current Persistence State

### A.1 The DB solution in use

**Room (androidx.room 2.6.1)** as the single source of truth, plus SharedPreferences only for user settings.

| Layer | File | Note |
|-------|-------|-----------|
| Entry point | `app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt:42-104` | Singleton `getInstance(context)`, KSP schema export `app/schemas/`, `allowMainThreadQueries()` (caution: tolerated today, should go medium-term) |
| Migrations | `app/src/main/java/net/devemperor/dictate/database/migration/Migrations.kt`, `MigrationTo3.kt` | Recreate-table pattern with CHECK rebuild |
| Configuration | `app/build.gradle:55-59` | `room.schemaLocation = $projectDir/schemas` |

**SharedPreferences** (`DictatePrefs.kt`) is used only for user settings (provider keys, language list, Pref.SingleRowMode, etc.). **No** pipeline-state persistence any more — the interim `lastSessionId`/`lastOutput` pref was explicitly removed (see `SessionTracker.kt:17-22`, "Phase 9 removed the legacy …"). That is relevant: the project has already made the "prefs → DB as SoT" journey once and that is deliberately the target state.

**Assumption — to be validated:** DataStore (Proto/Preferences) is not used. Grep `androidx.datastore` is empty.

### A.2 Existing tables / entities / DAOs

As of schema version 3:

| Table | Entity | DAO | Purpose |
|---------|--------|-----|-------|
| `sessions` | `SessionEntity` (`database/entity/SessionEntity.kt:11-69`) | `SessionDao` (`database/dao/SessionDao.kt`) | **The master row per pipeline run.** Carries `status`, `origin`, `audio_file_path`, `audio_duration_seconds`, `last_error_*`, `final_output_text`, `input_text`, `queued_prompt_ids`. **This is the pending row.** |
| `transcriptions` | `TranscriptionEntity` | `TranscriptionDao` | Versioned transcription outputs (multiple versions per session possible, `is_current` marks the current one). |
| `processing_steps` | `ProcessingStepEntity` | `ProcessingStepDao` | Versioned pipeline steps (auto-format, queued prompts, post-processing). `chain_index` + `version` + `is_current`. |
| `completion_log` | `CompletionLogEntity` | `CompletionLogDao` | Append-only audit trail for every API call (success + error). |
| `text_insertions` | `TextInsertionEntity` | `TextInsertionDao` | Append-only log of every insertion into the edit field (also who, when, where, replaced_text). **Important** for "was it already inserted" detection. |
| `usage` | `UsageEntity` | `UsageDao` | Token/cost tracking (legacy). |
| `prompts` | `PromptEntity` | `PromptDao` | User prompts. |

Six Double-Enums are already in use or documented:
- `SessionStatus`: `RECORDED`, `COMPLETED`, `FAILED`, `CANCELLED` (`database/entity/SessionStatus.kt`)
- `SessionOrigin`: `KEYBOARD`, `HISTORY_REPROCESS`, `POST_PROCESSING` (`database/entity/SessionOrigin.kt`)
- `SessionType`: `RECORDING`, `REWORDING`, `POST_PROCESSING` (`database/entity/SessionType.kt`)
- `StepStatus`: `SUCCESS`, `ERROR`
- `StepType`: `AUTO_FORMAT`, `REWORDING`, `QUEUED_PROMPT`
- `AIProviderException.ErrorType` (reused in `last_error_type`)

### A.3 Migration mechanism

Versioning runs via the standard Room migration pattern (`Migration(N, N+1)`):

- Schema bump in `DictateDatabase.kt` → `version = N`.
- New migration in `database/migration/Migrations.kt` (or its own file like `MigrationTo3.kt`).
- Registration in `buildDatabase()` via `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.
- **CHECK constraints** for Double-Enum columns require the recreate-table pattern (SQLite cannot change CHECK via `ALTER TABLE`). `MigrationTo3.kt` shows that idiomatically: `CREATE TABLE sessions_new (...) CHECK (...)`, `INSERT … SELECT … FROM sessions`, `DROP`, `RENAME`, indices new.
- **Test pattern:** `MigrationTestHelper` (see `docs/DATABASE-PATTERNS.md`, lines 148–183, template).
- Schema snapshots are exported via KSP to `app/schemas/net.devemperor.dictate.database.DictateDatabase/{N}.json`.

Consequence for this research: **if we need a new status (e.g. `IN_FLIGHT`), that is an M3→M4 migration with a CHECK rebuild.** That is not free but also no show-stopper. [Section C](#c-datenmodell-vorschlag) discusses whether it is needed at all.

---

## B. Send Pipeline — Current State

### B.1 Audio recording

**Where recorded:** `RecordingManager.kt:55-83` (`MediaRecorder` with MPEG_4/AAC, 64 kbps, 44.1 kHz, `.m4a`).

**Where written — lifecycle in 2 stages:**

1. **Cache (transient).** `DictateInputMethodService.startRecording()` (`core/DictateInputMethodService.java:1612-1619`) creates the file as `getCacheDir() + "audio.m4a"`. The cache dir is Android-managed — it can be cleaned up at any time, which is why it is **not** suitable for longer persistence.
2. **Persistent (durable).** After stop the cache file is copied via `RecordingRepository.persistFromCache(cacheFile, sessionId)` (`core/RecordingRepository.kt:45-49`) to `getFilesDir()/recordings/{sessionId}.m4a`. Then (the same synchronous call) `extractDurationSeconds(...)` (`RecordingRepository.kt:65-80`) and `SessionManager.createSession(..., audioFilePath = recording.audioFile.absolutePath, audioDurationSeconds = ..., initialStatus = RECORDED)` (`PipelineOrchestrator.kt:837-898`).

This sequence is **already the "persist-first" pattern** the A+B architecture needs: before the API is touched, the session row with `status = RECORDED` and a valid audio path lies in the DB. This was implemented in the reprocess-refactor iteration (see `PipelineOrchestrator.kt:43-52`, "After the reprocess-refactor (Chunk 2)").

**In-memory vs disk:** disk only. There is no in-memory audio blob in the pipeline path.

### B.2 Transcription

**Provider abstraction:** `ai/AIOrchestrator.kt` (entry point) → `ai/factory/RunnerFactory.kt` → `ai/runner/{OpenAICompatibleRunner, AnthropicCompletionRunner}`. That is the place where the HTTP call actually happens.

**Async pattern:** **Synchronous + ExecutorService**, NO coroutines in the API path:

- `AIOrchestrator.transcribe(...)` is synchronously blocking (see the call in `PipelineOrchestrator.kt:1023`).
- The pipeline runs on a **dedicated single-thread `ExecutorService`** in `JobExecutor` (`core/JobExecutor.kt:33`, `Executors.newSingleThreadExecutor()`). The orchestrator's own `executor` fields (`PipelineOrchestrator.kt:145`) are legacy and are **not** used for JobExecutor-routed jobs — JobExecutor calls the `*Blocking` variants directly on its executor.
- Cooperative cancellation via `CancellationToken` (`core/CancellationToken.kt`) plus a `Thread.interrupt()` fallback from `JobExecutor.cancel()` (`JobExecutor.kt:184-187`).

**Consequence for A+B:** coroutines exist in the UI layer (`ActiveJobRegistry` is StateFlow-based) and in `ActiveJobRegistryObserver`, but **not in the pipeline itself**. A WorkManager `CoroutineWorker` would be possible; simpler and consistent is a classic `Worker` with `doWork()` blocking.

### B.3 Insertion (text → edit field)

Several paths, but all ultimately go through:

- `DictateInputMethodService.commitTextToInputConnection(...)` (`core/DictateInputMethodService.java:1907-2000`). That is the insertion engine: calls `ic.commitText(output, 1)` and logs via `SessionManager.logTextInsertion(...)` with `InsertionMethod.COMMIT` or `PASTE`.
- Resend/recovery path: `ResendInsertStrategy.execute(...)` (`core/ResendInsertStrategy.kt:85-118`) — a 3-stage strategy (live IC → captured IC → toast + resume job).
- Long-press on the resend button → `ReprocessStaging` (`onResendLongClicked` → `KeyboardUiController.enterReprocessStaging(...)`).

**"Was it already inserted?"** — answered implicitly today via user behaviour + the `text_insertions` log. There is **no** "is_inserted" column on the session. That is relevant for [edge case F.1 + F.2](#f-edge-cases-und-deren-behandlung).

### B.4 Result lifecycle (where does the result live between transcription and insertion?)

Three parallel sources:

1. **`processing_steps.output_text`** of the `is_current` step — canonical.
2. **`transcriptions.text`** of the `is_current` version — fallback if there are no steps.
3. **`sessions.final_output_text`** — a denormalised cache, kept synchronously with (1)/(2) by `SessionManager.updateFinalOutputText(...)`.

`SessionManager.getFinalOutput(sessionId)` (`core/SessionManager.kt:372-385`) implements exactly this fallback chain and is **the canonical read operation** for "the result text of this session".

**Data flow in today's pipeline:**

```
[Pseudo-Code, Datenfluss]
RecordingManager.stop()
  → onRecordingStopped(audioFile)              # Cache-File
  → DictateInputMethodService.runTranscriptionViaOrchestrator()
       → JobExecutor.start(TranscriptionPipeline)    # WorkManager-Slot heute leer
            → executor.submit { orchestrator.runTranscriptionPipelineBlocking(...) }
                 → persistNewSession()                 # Cache → recordings/{id}.m4a, status=RECORDED
                 → executeTranscription()              # API-Call
                 → addTranscriptionVersion()           # transcriptions row
                 → executeAutoFormat() / executeQueuedPrompts()
                 → finalizeCompleted()                 # status=COMPLETED
                 → callback.onPipelineCompleted(text)  # liefert an Service zurück
                      → commitTextToInputConnection()  # SOFORT einfügen (synchron, kein Pending)
```

Today the path is **synchronous-blocking-but-non-UI** and the insertion happens **directly after** the pipeline. The window for service death (killed-by-OS, app switch) is open — the pipeline does survive until the end of the `JobExecutor` thread, but if the process dies before that, `final_output_text` is in the DB but the insertion has not happened (and the `text_insertions` log is empty). That is exactly the state the A+B architecture wants to display as "BackgroundedReady".

---

## C. Data-Model Proposal

### C.1 Recommendation: NO new `pending_transcription` table

Reasoning — the user requirement "variant A+B combined" maps completely onto the existing `sessions` table:

| User wish | Already present | Extension needed |
|-------------|-------------------|-------------------|
| Persist audio + metadata | `sessions.audio_file_path`, `sessions.audio_duration_seconds`, `sessions.target_app_package`, `sessions.queued_prompt_ids`, `sessions.language` | — |
| Store the result until the user inserts | `transcriptions.text`, `processing_steps.output_text`, `sessions.final_output_text` | — |
| Status tracking (PENDING / IN_FLIGHT / COMPLETED / INSERTED / DELETED) | `SessionStatus.{RECORDED, COMPLETED, FAILED, CANCELLED}` | see C.2 — either `IN_FLIGHT` a new status **or** purely via `ActiveJobRegistry` |
| Mark "insertion pending" | implicit: `COMPLETED` without a corresponding `text_insertions` entry | see C.3 |
| User identity / IME-service ID | `sessions.target_app_package` (for "which app did this belong to") | see edge cases [F.3](#f3-gleiches-audio-ime-switch-dazwischen-wem-gehoert-die-pending-session) |
| Lifecycle cleanup | `RecordingRepository.deleteBySessionId()`, `DurationHealingJob` (idempotency healing) | see C.4 |

A new table `pending_transcription` would be almost **a copy of the `sessions`** table. That does not violate SOLID/SRP, but it leads to double maintenance:
- Migrations doubled: every schema change to audio path / language / queued_prompt_ids must go into both tables.
- A source-of-truth conflict: what is "the session"? The one in `sessions` or the one in `pending_transcription`? If the user later re-processes an old session from history (`SessionOrigin.HISTORY_REPROCESS`), it would be written into both tables → a DRY violation.
- `JobExecutor`, `PipelineOrchestrator`, `ResendStatusDispatcher`, `SessionTracker.getLastKeyboardSession()` would all have to query two sources.

**Trade-off, if explicitly desired:** A separate table would be architecturally cleaner if "pending" were semantically fundamentally different from "session" (e.g. if pendings only live briefly and sessions are history). But since sessions **already today** map all life situations (RECORDED = audio there, no result; COMPLETED = result there; FAILED = error; CANCELLED = aborted), the one-to-one mapping is given. Recommendation: **one table**.

### C.2 Status model — two options

**Option A — minimalism (recommended):** No new status. Instead, "IN_FLIGHT" is represented by the live existence in the `ActiveJobRegistry` (StateFlow, Kotlin `object`, process-wide). DB-status transitions stay:

```
RECORDED ──(JobExecutor läuft)──→ COMPLETED
                              │
                              ├──→ FAILED
                              │
                              └──→ CANCELLED
```

**Mapping to user states:**

| User wish | DB status | Live signal |
|-------------|-----------|-------------|
| `PENDING` | `RECORDED` (= audio there, pipeline not yet through) | `ActiveJobRegistry.isActive(id) == false` |
| `IN_FLIGHT` | `RECORDED` | `ActiveJobRegistry.isActive(id) == true` (service coroutine OR WorkManager running) |
| `COMPLETED` | `COMPLETED` with `final_output_text != null` | — |
| `INSERTED` | `COMPLETED` with an associated `text_insertions` entry (see C.3) | — |
| `DELETED` | row gone (`onDelete = CASCADE` clears audio + transcriptions + steps with it) | — |
| `FAILED` | `FAILED` with `last_error_*` | — |

Advantage: no migration, no combinatorial explosion, all existing queries stay valid.

**Option B — explicit (alternative):** `IN_FLIGHT` as a new DB status. Requires:
- An M3→M4 migration with recreate-table (`CHECK (status IN ('RECORDED', 'IN_FLIGHT', 'COMPLETED', 'FAILED', 'CANCELLED'))`).
- A bump in `database/entity/SessionStatus.kt` + docs in `DATABASE-PATTERNS.md`.
- A double-write point: WorkManager AND the service coroutine must set `IN_FLIGHT`, both must reset it again.

Disadvantage: WorkManager-job crashes (process death) leave `IN_FLIGHT` stale rows that a healing job (analogous to `DurationHealingJob`) must reset to `RECORDED`. That adds another consistency-critical write operation.

**Recommendation:** **Option A.** Reasoning:
- `ActiveJobRegistry` already exists and fulfils the function in-process exactly.
- The cross-process view ("is a job running now?") is not relevant in the IME context — the IME-service process is the only one that starts jobs. The WorkManager worker runs in the same process (see [D](#d-workmanager-integration)).
- The combinatorial clarity (session status + live flag = 2 axes → few state tuples) is easier to test than 5 DB statuses with a transition matrix.

### C.3 "Was it already inserted?" — detection mechanism

Today this can be derived from `text_insertions`: if a `text_insertions` entry with `session_id = X` AND `inserted_text` matches `sessions.final_output_text` exists, then it has been inserted.

**Proposal — helper query** (pseudo-code DAO):

```kotlin
// [Pseudo-Code, kein echter DAO-Code]
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM text_insertions
         WHERE session_id = :sessionId
           AND inserted_text = :expectedOutput
    )
""")
fun isOutputInserted(sessionId: String, expectedOutput: String): Boolean
```

Alternatively leaner: a column `inserted_at: Long?` on `sessions` (denormalised, M3→M4 migration). Advantage: an O(1) lookup, no text comparison. Disadvantage: another write site that must be kept in sync with `text_insertions`.

**Recommendation:** the denormalised field `sessions.inserted_at: Long?`. Reasoning:
- It is in the established "Denormalized Cache Columns" family (see `DATABASE-PATTERNS.md`, "Denormalized Cache Columns" section).
- An O(1) query — important for the list of pending sessions on the restart button (potentially several pendings).
- The update site is exactly one: `commitTextToInputConnection` (or via `SessionManager.logTextInsertion`, which is already central).

### C.4 Audio-file lifecycle: disk vs. blob

**Recommendation: file path** (as today, `sessions.audio_file_path` points to `getFilesDir()/recordings/{sessionId}.m4a`).

| Disk file | DB blob |
|-----------|---------|
| **+** Streaming upload possible (Whisper accepts m4a + opus natively) | – Whole blob in memory at upload |
| **+** Already implemented, tested, robust (`RecordingRepository`, `DurationHealingJob`) | – Migration of the existing audio files needed |
| **+** Schemas comparable (no BLOB mega-row) | – Room performance with large BLOBs (>1 MB) is mediocre |
| **+** Cleanup orthogonal: the file can be deleted (e.g. after insertion + cooldown) without losing the session | – Foreign backup tools see the audios too |
| – Inconsistency possible (DB row says "file there", file is gone → `DurationHealingJob` heals that) | **+** Atomic DB-transaction scope |

**Cleanup policy (already implemented + extensible):**
- `RecordingRepository.deleteBySessionId()` deletes the file and sets `audio_file_path = NULL` (`RecordingRepository.kt:136-148`).
- `onDelete = CASCADE` on `parent_session_id` clears child sessions automatically.
- `DurationHealingJob` (`database/DurationHealingJob.kt`) heals inconsistencies on app start (file gone → status to FAILED + UNKNOWN error type).

**To be validated / assumption:** Today audio files are **never deleted automatically** — they accumulate in `recordings/`. For the pending-lifecycle logic this research proposes an auto-cleanup policy: delete the audio file **as soon as** `sessions.inserted_at != null` AND `now() - inserted_at > X days` (X suggestion: 7). The session row stays for history purposes, the `final_output_text` is denormalised in the row.

### C.5 Proposal table (extension of `sessions`, NO new table)

The table as an **M3→M4 migration plan**. Marked in square brackets is what is really new:

| Column | Type | Default | Constraints | Status | Meaning |
|--------|-----|---------|-------------|--------|-----------|
| `id` | TEXT NOT NULL PRIMARY KEY | — | — | ✅ present | UUID, pre-allocated by the caller (`PipelineConfig.preAllocatedSessionId`) |
| `type` | TEXT NOT NULL | — | (Double-Enum: SessionType) | ✅ present | RECORDING / REWORDING / POST_PROCESSING |
| `created_at` | INTEGER NOT NULL | — | — | ✅ present | `System.currentTimeMillis()` |
| `target_app_package` | TEXT | NULL | — | ✅ present | from `EditorInfo.packageName` |
| `language` | TEXT | NULL | — | ✅ present | from `LanguageController.getEffectiveLanguage()` |
| `audio_file_path` | TEXT | NULL | — | ✅ present | absolute, `getFilesDir()/recordings/{id}.m4a` |
| `audio_duration_seconds` | INTEGER NOT NULL | — | — | ✅ present | from `MediaMetadataRetriever` synchronously at persist |
| `parent_session_id` | TEXT | NULL | FK CASCADE | ✅ present | for POST_PROCESSING children |
| `status` | TEXT NOT NULL | `'COMPLETED'` (migration default) | CHECK in a 4-element set | ✅ present | RECORDED / COMPLETED / FAILED / CANCELLED |
| `origin` | TEXT NOT NULL | `'KEYBOARD'` | CHECK | ✅ present | KEYBOARD / HISTORY_REPROCESS / POST_PROCESSING |
| `queued_prompt_ids` | TEXT | NULL | — | ✅ present | comma-separated prompt IDs |
| `last_error_type` | TEXT | NULL | CHECK in `AIProviderException.ErrorType` without CANCELLED | ✅ present | only when status=FAILED |
| `last_error_message` | TEXT | NULL | — | ✅ present | free text |
| `final_output_text` | TEXT | NULL | — | ✅ present | denormalised output for UI |
| `input_text` | TEXT | NULL | — | ✅ present | denormalised input |
| **`inserted_at`** | **INTEGER** | **NULL** | **—** | **🆕 M4 migration** | **Wallclock of the first insertion (for "BackgroundedReady" vs "inserted" detection); NULL as long as no insert happened. Update site: `SessionManager.logTextInsertion()`** |
| **`sticky_until`** | **INTEGER** | **NULL** | **—** | **🆕 (optional, M4)** | **Wallclock time up to which the session should be advertised as "BackgroundedReady" on the restart button. Default: `inserted_at + 24h`. After expiry the restart button no longer actively shows the session, but it stays in history. This column is optional; without it the user stickiness is expressed purely via `inserted_at IS NULL`. Trade-off: sticky_until complicates the model but gives more UX-side control.** |

Indices: existing ones stay (`status`, `origin`, `created_at`, `parent_session_id`, `type`). A new index recommended:

| Index | Reason |
|-------|------------|
| `index_sessions_pending` on `(origin, status, inserted_at)` | for "the list of pending sessions the user can still insert" — query: `WHERE origin = 'KEYBOARD' AND status IN ('RECORDED', 'COMPLETED') AND inserted_at IS NULL ORDER BY created_at DESC`. Without an index O(N), with an index O(log N + K). |

**Lifecycle policy summarised:**

```
[Pseudo-Code, Lifecycle-Diagramm]

  Recording stoppt
        │
        ▼
  status=RECORDED, audio_file_path gesetzt, inserted_at=NULL
        │
        ├── (B) JobExecutor läuft sofort ──► COMPLETED + final_output_text gesetzt
        │                                       │
        │                                       ▼
        │                                 User klickt Resend ──► inserted_at = now()
        │                                                       (Audio-File-Cleanup nach +7 Tagen)
        │
        └── (Service stirbt vor Ende) ──► WorkManager picks up
                                              │
                                              ▼
                                          Re-run Pipeline (idempotent, siehe D.3)
                                              │
                                              ▼
                                          status=COMPLETED, inserted_at=NULL
                                              │
                                              ▼
                                          Bei nächster IME-Aktivierung: Restart-Button zeigt "BackgroundedReady"

  Bei FAILED: bleibt sichtbar mit Retry-Button (UI-Pattern: "BackgroundedFailed")
```

---

## D. WorkManager Integration: the A+B Architecture

### D.1 Job-start flow (combined)

Today's path (B only) is:

```
runTranscriptionViaOrchestrator()
  → JobExecutor.start(TranscriptionPipeline) [in Service-Process, registriert in ActiveJobRegistry]
       → executor.submit { orchestrator.runTranscriptionPipelineBlocking(...) }
            → status: RECORDED → COMPLETED
            → callback.onPipelineCompleted(text)
                 → commitTextToInputConnection(...)  // Insert sofort
```

Proposal (A+B combined):

```
[Pseudo-Code, Erweiterter Flow]

runTranscriptionViaOrchestrator()
  → preAllocatedId = UUID()
  → // PERSIST-FIRST passiert IM PipelineOrchestrator als erstes:
  → // (existing) sessions row mit status=RECORDED, audio_file_path, queued_prompt_ids
  →
  → // (B) Service-Coroutine — primärer Pfad, sofort
  → JobExecutor.start(this, TranscriptionPipeline(preAllocatedId, ...))
  →
  → // (A) WorkManager — Backup, idempotent
  → val workRequest = OneTimeWorkRequestBuilder<PipelineWatchdogWorker>()
  →   .setInputData(workDataOf("session_id" to preAllocatedId))
  →   .setConstraints(Constraints.Builder()
  →     .setRequiredNetworkType(NetworkType.CONNECTED)
  →     .build())
  →   .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30s, TimeUnit.SECONDS)
  →   .setInitialDelay(60s, TimeUnit.SECONDS)   // ← Trick: erst nach 60s aktiv,
  →   .build()                                  //   gibt der Service-Coroutine
  →                                             //   Vorrang.
  → WorkManager.getInstance(context).enqueueUniqueWork(
  →   "pipeline-${preAllocatedId}",             // Unique-Tag = sessionId
  →   ExistingWorkPolicy.KEEP,                  // KEEP — niemals doppelt enqueuen
  →   workRequest
  → )

// PipelineWatchdogWorker.doWork() — siehe D.3
```

**Key idea "backup-with-initial-delay":** The WorkManager job is **not a parallel competitor** but a **watchdog**. It is configured with an initial delay (e.g. 60 seconds), so that the service coroutine is already done in 95% of cases before the worker even starts. The worker then checks (see D.3):

1. Is the session already `COMPLETED` and `final_output_text` set? → no-op, done.
2. Is it still `RECORDED`? → the service has died → the worker takes over the pipeline.
3. Is `ActiveJobRegistry.isActive(sessionId)` = true? → no re-run, the worker reschedules itself (retry with backoff).

So the worker is **idempotent over the DB state** — the only consistent synchronisation point across process boundaries.

### D.2 WorkManager configuration in detail

| Setting | Value | Reason |
|---------|------|-----------|
| `Worker` class | `PipelineWatchdogWorker : Worker` (no `CoroutineWorker`) | The pipeline is synchronous today; a classic worker fits the existing ExecutorService architecture better. |
| Network constraint | `NetworkType.CONNECTED` | The pipeline needs internet (Whisper / GPT). Without connectivity a re-run makes no sense — WorkManager re-tries automatically when network comes. |
| Battery constraint | NONE (not `setRequiresBatteryNotLow`) | The user wish is "recording happened, now it should finish". A battery constraint would delay the worker on low battery. |
| Storage constraint | NONE | The audio is already on disk, no risk. |
| Backoff policy | `BackoffPolicy.EXPONENTIAL`, `initialBackoff = 30s` | On a transient error (e.g. rate-limited) a linear-retry-with-30s makes little sense — exponential gives the API breathing room. |
| Initial delay | `60s` | see D.1 — gives the service coroutine priority. **Trade-off to validate:** 30s might already be enough. |
| Unique tag | `"pipeline-${sessionId}"` | One worker per session, guaranteed. |
| `ExistingWorkPolicy` | `KEEP` | if at the re-trigger a worker for this session is already enqueued, do nothing. |
| `Worker.Result` | `success()` on DB status COMPLETED, `retry()` on NETWORK_ERROR / RATE_LIMITED, `failure()` on INVALID_API_KEY / BAD_REQUEST | Maps onto `AIProviderException.ErrorType`. |
| Foreground-service notification | Optional, NOT recommended for the MVP | Would require a battery whitelist + notification permission (API 33+). For an IME use-case that is overkill. |

Dependency: `androidx.work:work-runtime-ktx:2.10.x` (to validate — there is currently no WorkManager entry in `gradle/libs.versions.toml`). The lib brings its own init provider (manifest merger), no explicit `WorkManager.initialize(...)` needed.

### D.3 Idempotency: how do we prevent a double send?

Three layers:

**Layer 1 — `ActiveJobRegistry` (in-process lock):**
```
[Pseudo-Code, Worker-Body]

class PipelineWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val sessionId = inputData.getString("session_id") ?: return Result.failure()
        val db = DictateDatabase.getInstance(applicationContext)
        val session = db.sessionDao().getById(sessionId) ?: return Result.success()
        //  ↑ Session weg = User hat in History gelöscht. Nichts zu tun.

        // Schicht 1: ist gerade ein Job aktiv? → reschedule
        if (ActiveJobRegistry.isActive(sessionId)) {
            return Result.retry()
            //  ↑ Service-Coroutine läuft noch. Worker macht Pause, prüft beim
            //    nächsten Backoff-Slot wieder.
        }

        // Schicht 2: schon fertig? → no-op
        if (session.statusEnum == SessionStatus.COMPLETED &&
            session.finalOutputText != null) {
            return Result.success()
        }

        // Schicht 3: terminal-fail? → no-op (User entscheidet via Retry-UI)
        if (session.statusEnum == SessionStatus.FAILED ||
            session.statusEnum == SessionStatus.CANCELLED) {
            return Result.success()
        }

        // Übrig: status == RECORDED, kein in-flight Job → übernehmen.
        // Wir routen DURCH JobExecutor, damit ActiveJobRegistry-Konsistenz
        // erhalten bleibt (eine andere App-Activity könnte parallel observen).
        val started = JobExecutor.start(applicationContext,
            JobRequest.Resume(sessionId, totalSteps = computeRemaining(session)))
        if (!started) return Result.retry()
        //  ↑ JobExecutor sagt "schon ein anderer Job aktiv" — backoff.

        // Worker wartet hier auf den Job-Abschluss?
        // → NEIN. JobExecutor startet auf seinem ExecutorService und kehrt
        //   sofort zurück. Der Worker beendet doWork() mit Result.success() —
        //   das eigentliche Pipeline-Ergebnis schreibt JobExecutor in die DB.
        //   Der Worker hat seine Aufgabe getan: "anstoßen, dass es passiert".
        return Result.success()
    }
}
```

**Layer 2 — DB-status check.** The `RECORDED → COMPLETED` transition is atomic (a single UPDATE statement), so there is no mid-transition state the worker could fall into. If the worker reads `RECORDED`, the pipeline is **definitely not through yet**. If it reads `COMPLETED`, it is **definitely through**.

**Layer 3 — `enqueueUniqueWork` with `KEEP`.** Even if the user calls `runTranscriptionViaOrchestrator` multiple times (impossible today, since `JobExecutor.start` returns `false` on an active job), WorkManager would not create a second worker for the same session ID. Single-source per tag.

**Race edge:** WorkManager starts its worker MS-precisely at the same time as the service process wakes up and JobExecutor starts its resume routine. Solution: `JobExecutor.start()` returns `false` if a job is already active (`ActiveJobRegistry.register(...)` fails), and the worker does `Result.retry()`. At the next backoff slot (30s+) the race is resolved.

### D.4 Pseudo-code: service-coroutine path (variant B)

Here the coroutine extension is minimal, because JobExecutor is already the hot path today. Trade-off: today the job runs on an `ExecutorService` thread; on service death it is interrupted via `executor.shutdownNow()`. If the service process is killed completely (OOM, ANR), the thread dies with it. **That is exactly the case where WorkManager takes over.**

```
[Pseudo-Code, IME Service-Side]

class DictateInputMethodService : InputMethodService {

    fun onAudioRecorded(audioFile: File) {
        val sessionId = UUID.randomUUID().toString()

        // ── Variante B (heute schon so) ──
        val request = JobRequest.TranscriptionPipeline(
            preAllocatedSessionId = sessionId,
            audioFilePath = audioFile.absolutePath,
            // ... weitere Felder
        )
        JobExecutor.start(this, request)
        // Persistenz passiert IM PipelineOrchestrator (persistNewSession)
        // bevor der erste API-Call rausgeht. Ergebnis schreibt der Orchestrator.

        // ── Variante A (NEU) ──
        val backupWorker = OneTimeWorkRequestBuilder<PipelineWatchdogWorker>()
            .setInputData(workDataOf("session_id" to sessionId))
            .setInitialDelay(60, TimeUnit.SECONDS)
            .setConstraints(/* NetworkType.CONNECTED */)
            .setBackoffCriteria(EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "pipeline-$sessionId",
            ExistingWorkPolicy.KEEP,
            backupWorker
        )
    }
}
```

**What happens if the service-coroutine path (B) succeeds?**
- The status is set to `COMPLETED` → 60 seconds later the worker wakes up → reads `COMPLETED` → `Result.success()` → done. No side effect, no cost.

**What happens if the service dies mid-flight?**
- The ExecutorService thread is interrupted, but `sessions.status` stays `RECORDED` (no `finalize*()` called `updateStatus`) OR a `finalizeFailed` with NETWORK_ERROR happens (if the pipeline had already issued an API call and an InterruptedException came from the OkHttp stack).
- In both cases WorkManager wakes up 60s later, reads the DB, takes over.

**What happens if the network is gone and the service-coroutine path fails with NETWORK_ERROR?**
- Status: `FAILED` with `last_error_type = NETWORK_ERROR`.
- The worker wakes up, reads `FAILED` → returns `Result.success()` (the user decides via the retry UI).
- **Alternative — contentious:** the worker could also trigger a retry on `FAILED + NETWORK_ERROR` (Result.retry()), because that is non-terminal. Trade-off:
   - Pro: the user wish "auto-retry" when the network comes back.
   - Contra: the user may by then have already started another recording — re-running this one is confusing.
   - Recommendation: **MVP — no auto-retry**. The user must explicitly press the retry button. Auto-retry on a network error can be V2.

---

## E. UI Communication: DB → View

### E.1 Reactive pipeline (status quo + extension)

**Status quo:**
- The DAOs are synchronous (`fun getById(...): SessionEntity?`, `fun findLatestByOrigin(...): SessionEntity?`). NO `Flow<>`, NO LiveData.
- Live state runs today via `ActiveJobRegistry.state: StateFlow<Map<sessionId, JobState>>` and is exposed Java-friendly via `ActiveJobRegistryObserver.observe(owner, listener)` (`core/ActiveJobRegistryObserver.kt:23-47`) (`repeatOnLifecycle(STARTED)`).
- DB state is loaded **on-demand** — either at view recreate (`onCreateInputView`) or on a user click (`SessionTracker.getLastKeyboardSession()` → DB query, RAM-cached).

**Proposal — a minimal, consistent build-out:**

1. **DAO extension — new flow methods in `SessionDao`** (pseudo-code):
```
[Pseudo-Code, DAO-Erweiterung]
@Query("""SELECT * FROM sessions
   WHERE origin = 'KEYBOARD'
     AND status IN ('RECORDED', 'COMPLETED')
     AND inserted_at IS NULL
   ORDER BY created_at DESC""")
fun observePendingKeyboardSessions(): Flow<List<SessionEntity>>
```
   Room automatically generates a flow from this that re-emits on DB mutations. Prerequisite: the module has `androidx.room:room-ktx` (already in `app/build.gradle:84`).

2. **Repository / composite reader.** `PendingTranscriptionReader` (a new Kotlin class) that:
   - **combines** the DB flow above with the `ActiveJobRegistry.state` StateFlow (via `kotlinx.coroutines.flow.combine`),
   - builds from it a `StateFlow<List<PendingViewState>>` in which each session is mapped as `PendingViewState.{Sending, Ready, Failed}` (a sealed class, see E.3).

3. **UI consumer.** The restart-button area (today a single resend button with `ResendStatusDispatcher`) becomes a render consumer of this state flow. Patterns:
   - In the IME service via `lifecycleScope.launch { reader.state.collect { ... } }` analogous to `ActiveJobRegistryObserver`.
   - Or, if the IME service has no `lifecycleScope`, a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Main)` with scope tear-down in `onDestroy`.

### E.2 View-recreate behaviour

**What happens on a re-inflate (`onCreateInputView`)?**

Today a synchronous pull from `SessionTracker.getLastKeyboardSession()` + evaluation via `ResendStatusDispatcher` suffices. With the reactive pipeline (E.1) that becomes the cold start of the flow subscriptions:

```
[Pseudo-Code, View-Recreate]

override fun onCreateInputView(): View {
    val view = inflateLayout()
    // ...

    // Cold-Start: Flow-Collection, erste Emission liefert sofort den aktuellen DB-Stand
    serviceScope.launch {
        pendingTranscriptionReader.state.collect { pendings ->
            // pendings: List<PendingViewState>
            mainButtonsController.renderPendingState(pendings.firstOrNull())
            // (ggf. Liste statt nur firstOrNull, siehe F.1)
        }
    }

    return view
}
```

**Lifecycle discipline:** the `serviceScope` MUST be closed in `onDestroy`. Otherwise the coroutines leak across view lifecycles.

### E.3 Restart-button states

An extension of `ResendAction` by the background states. Sealed-class extension:

```
[Pseudo-Code, ResendAction-Erweiterung]
sealed class RestartButtonState {
    object Empty : RestartButtonState()                   // keine Pending — Button hidden
    data class Insert(                                    // status=COMPLETED, output != null, NICHT inserted
        val sessionId: String,
        val output: String,
        val source: InsertionSource
    ) : RestartButtonState()
    data class Resume(val sessionId: String) : RestartButtonState()  // RECORDED, kein Job aktiv
    data class BackgroundedSending(                       // RECORDED + Job aktiv (Service oder WorkManager)
        val sessionId: String,
        val progress: JobState.Running                    // existing
    ) : RestartButtonState()
    data class BackgroundedReady(                         // COMPLETED + nicht inserted (= INSERT-Variante)
        val sessionId: String,
        val output: String
    ) : RestartButtonState()
    data class BackgroundedFailed(                        // FAILED + Retry möglich
        val sessionId: String,
        val errorType: AIProviderException.ErrorType,
        val canRetry: Boolean
    ) : RestartButtonState()
}
```

`BackgroundedReady` is **functionally identical** to `Insert` — the distinction is purely for the UI label. Both trigger `commitTextToInputConnection(...)` and `SessionManager.logTextInsertion(...)` on a click (which sets `inserted_at` → the state becomes `Empty`).

Dispatcher extension (pseudo-code):

```
[Pseudo-Code, RestartButtonStateMapper]
fun map(session: SessionEntity?, isJobActive: Boolean): RestartButtonState {
    if (session == null) return Empty
    return when (session.statusEnum) {
        SessionStatus.RECORDED ->
            if (isJobActive) BackgroundedSending(session.id, ActiveJobRegistry.get(session.id) as Running)
            else Resume(session.id)
        SessionStatus.COMPLETED ->
            if (session.insertedAt != null) Empty
            else if (session.finalOutputText.isNullOrEmpty()) Empty // defensive
            else if (isJobActive) BackgroundedSending(...)  // re-running, sehr selten
            else BackgroundedReady(session.id, session.finalOutputText)
        SessionStatus.FAILED ->
            BackgroundedFailed(session.id, session.errorTypeEnum ?: UNKNOWN, canRetry = isRetryable(...))
        SessionStatus.CANCELLED ->
            if (session.finalOutputText.isNullOrEmpty()) Empty
            else BackgroundedReady(session.id, session.finalOutputText)
            // (Existing pattern: CANCELLED kann Output haben wenn user mid-pipeline canceled — dann Insert-pfad)
    }
}
```

This logic subsumes today's `ResendStatusDispatcher` behaviour 1:1 but adds the background states. Migration: `ResendStatusDispatcher` is extended to `RestartButtonStateMapper`, the existing dispatcher can stay a thin adapter layer or be replaced entirely (refactor risk: low, well testable — today's dispatcher already has unit tests).

---

## F. Edge Cases and Their Handling

### F.1 The user has 3 pending transcriptions, all done — queue or stack?

**Problem:** Today there is **one** resend action. With the A+B architecture there can be several `BackgroundedReady` sessions simultaneously (the user recorded twice in a row, both are background-completed, but neither inserted).

**Proposal:**

| Variant | UX | Cost |
|----------|----|---------|
| **Stack (LIFO, newest first)** | The restart button shows the **newest** unused pending. The user clicks → inserted → the next-oldest is shown. If the user does not want the older one, they can open the stack detail via long-press. | Low. Requires only a sorted DB query (ORDER BY created_at DESC) and the mapping onto the restart button. |
| **Queue (FIFO, oldest first)** | The restart button shows the **oldest** pending. | More confusing — the user just made the *new* recording and expects the next to be what they insert. |
| **List / sheet** | Long-press shows a list, a short click is always the newest. | Hybrid; probably the most robust UX-wise. |

**Recommendation:** **Stack (LIFO) for the default click, long-press opens the list/bottom sheet** — analogous to today's `onResendLongClicked` → `ReprocessStaging` pattern. So the default UX is fast and power users get detail control.

**Assumption — to be validated:** The "typical" user flow is sequential (one recording, insert, next recording). Multi-pending only occurs if the user switches to another app without inserting, then comes back — so multi-pending is an edge case, not a hot path.

### F.2 The user has 1 pending, failed — UI?

State: `BackgroundedFailed`. On the restart button:
- Label: "Last recording failed" + icon (e.g. warning).
- Default click: retry, if `canRetry = true` (see the `isRetryable` logic below).
- Long-press: a detail sheet with the error message + a manual retry button + a discard button.

**`isRetryable` heuristic:**
```
[Pseudo-Code]
fun isRetryable(errorType: AIProviderException.ErrorType): Boolean = when (errorType) {
    NETWORK_ERROR, RATE_LIMITED, SERVER_ERROR, UNKNOWN -> true
    INVALID_API_KEY, MODEL_NOT_FOUND, BAD_REQUEST -> false   // User-Action nötig
    CANCELLED -> false                                        // sollte hier nicht landen (CHECK)
}
```

If `!canRetry`: the long-press sheet shows an explanation ("Invalid API key") + a settings shortcut. Today's `InfoBarController` (`core/DictateInputMethodService.java:219`) already shows these errors transiently — new is only the persistent display on the restart button.

### F.3 Same audio, IME switch in between — who owns the pending session?

**Problem:** The user starts the recording in the IME, switches in between to the default keyboard (or another IME), and the pipeline continues in the background. On the next call of Dictate — does the pending belong to "this user" or "this app instance"?

**The architecture's answer today:**

- The `sessions` table is app-wide (one `dictate.db` per installation). There is no "user" — Dictate is single-user per device.
- `target_app_package` (`SessionEntity.targetAppPackage`) is stored — we know which app the output should belong to.
- `origin = KEYBOARD` marks IME-originated.

**Implications:**

| Scenario | Answer |
|----------|---------|
| The user switches from the Dictate IME to Gboard, the dictate pipeline continues | The service may die, WorkManager takes over. The pending stays as `RECORDED`/`COMPLETED` in the DB. |
| The user switches back to Dictate, in the **same app** as originally | The restart button shows `BackgroundedReady`. `target_app_package` matches the current `EditorInfo.packageName` → no conflict. |
| The user switches back to Dictate, but now in a **different app** | The restart button still shows `BackgroundedReady` (default behaviour). The user must decide whether to insert the old output here. |

**Recommendation — UX proposal:**

- **Default:** the pending is shown regardless of the current app. The user bears the responsibility.
- **Optional tooltip / long-press detail:** "Originally recorded for com.example.foo. Insert here anyway?"
- **Hard filter (not recommended, but to be discussed):** show the pending only if `target_app_package == currentEditorPackage`. Hide older pendings for other apps in the long-press list. — Trade-off: too restrictive, users lose their output.

**Identity:** There is **no user identity** at device level (multi-user Android would be a different profile → different app data). The `IME-Service-ID` is the app installation. So we need nothing new.

### F.4 App reinstalled while a pending is open

**What happens:**
- An app uninstall deletes `dictate.db` and `getFilesDir()/recordings/*` (Android-managed).
- Reinstall = empty DB. No old pendings any more.
- **Audio files in foreign directories** (e.g. if the user made a manual backup) stay invisible — no foreign insertion.

**Cleanup need:** None. Android clears app data on uninstall.

**Foreign-insertion question:** Should the user be able to restore "their" old audios from a backup restore? — No, that is out of scope. Audio files belong to `sessions` rows; without the row we cannot associate the file (the UUID filename is deterministic, but all metadata is in the row).

---

## G. Existing WorkManager Sources in the Repo

**Result:** **None.** The repo contains neither an `androidx.work` dependency in `gradle/libs.versions.toml` nor a worker class nor an `enqueueUniqueWork` call in `app/src/main`.

Greps verified:
- `grep -rn "androidx.work" /home/lukas/WebStorm/Dictate/app/src` → empty
- `grep -n "WorkManager\|androidx.work" /home/lukas/WebStorm/Dictate/gradle/libs.versions.toml` → empty
- `grep -rn "WorkManager\|enqueueUniqueWork\|OneTimeWorkRequest" /home/lukas/WebStorm/Dictate/app` → empty

**Consequence:**
- The WorkManager library + worker class must be newly added.
- The manifest provider is brought in automatically by the `work-runtime-ktx` lib (manifest merger), no manual `<provider>` entries needed.
- Initialisation: no `WorkManager.initialize(...)` call needed (default provider). If custom configuration (e.g. a custom logger) is desired, a `Configuration.Provider` implementation in `DictateApplication` would suffice — but not required for the MVP.

**What already exists and is reusable:**
- `DurationHealingJob` as inspiration for "one-shot job on app start, idempotent". The watchdog worker follows the same idempotency style (DB check → no-op if done).
- `JobExecutor.start()` as the single entry point for the pipeline. The WorkManager worker MUST route through JobExecutor, not call `PipelineOrchestrator` directly — otherwise the `ActiveJobRegistry` lock would be bypassed.
- `ActiveJobRegistry.isActive(sessionId)` as a cross-caller lock (service coroutine + worker both see the same value, since they run in the same process).

---

## H. Risks & Open Questions

| Risk | Impact | Mitigation / open question |
|--------|--------|---------------------------|
| The worker runs in the **same process** as the IME service — process death affects both | High — limits the robustness of variant A | Verify via docs: `WorkManager` starts workers in the app's default process, not in a separate one. If the IME process is killed, the worker can only run at the next process wakeup (e.g. broadcast, AlarmManager trigger). **Assumption — to be validated:** WorkManager wakes the process itself when constraints are met (network-connect broadcast). Practical consequence: after process death it takes until the next user interaction or until WorkManager wakes itself. |
| `allowMainThreadQueries()` in `DictateDatabase` leads to possible main-thread DB accesses | Medium | An existing trade-off; the UI consumer for `BackgroundedReady`/`BackgroundedSending` MUST go via coroutines (StateFlow.collect), not synchronously. |
| A combinatorial UI-state explosion via `BackgroundedReady`/`BackgroundedSending`/`BackgroundedFailed` × multi-pending × the existing `RestartButton` variants | Medium | The restart button **always shows only the newest** pending (LIFO stack). Multi-pending is visible via the long-press sheet. So the state matrix is linear in the number of statuses, not cross-multiplicative with the number of pendings. |
| If the WorkManager worker uploads the same audio file on the re-run, but the service coroutine has meanwhile already written a step result, there is an inconsistency in the step chain | High | `JobExecutor.start()` with `JobRequest.Resume` runs through `PipelineOrchestrator.resumePipelineBlocking`, which continues from the **last successful point** (see `PipelineOrchestrator.kt:359-488`, `lastSuccessChainIndex`). Idempotency at the step level is therefore already implemented. |
| Database volume: with 100 pendings/day `recordings/` explodes quickly (audio files ~50 KB/sec) | Medium | Implement an auto-cleanup policy: after `inserted_at + 7 days` a periodic job (also a WorkManager `PeriodicWorkRequest`) deletes the audio file via `RecordingRepository.deleteBySessionId()`. The session row + output stay in history. |
| `Pref.LastFileName` (`audio.m4a` in the cache) would conflict with pre-allocated session IDs if several recordings ran in parallel | Low (no problem today, single-job lock) | Because of `ActiveJobRegistry.register()` only one recording can be active. Fix in V2 if multi-recording support is desired: cache filenames per session ID (`cache/audio_{id}.m4a`). |
| Backward compat: the schema migration M3→M4 for `inserted_at` + `sticky_until` affects all existing DBs | Low | Recreate-table pattern like `MigrationTo3.kt`, with a default fallback `inserted_at = NULL` for old rows (no assumptions about the insertion history — old sessions are marked as "never inserted", which is harmless UX-wise since `getLastKeyboardSession()` only shows the newest session anyway). |

---

## I. Proposed Implementation Order (for a Later Plan)

This research prepares a separate plan. Proposed order:

1. **Phase 0:** dependency add `androidx.work:work-runtime-ktx`, a smoke-test worker that does nothing but write a log — verifies that the lib is correctly initialised.
2. **Phase 1:** the `inserted_at` field via an M3→M4 migration. The update site in `SessionManager.logTextInsertion()`. Tests via `MigrationTestHelper`.
3. **Phase 2:** `PendingTranscriptionReader` (a flow combiner) + the extension of `ResendStatusDispatcher` to `RestartButtonStateMapper`. Tests pure JVM (the ResendStatusDispatcher pattern).
4. **Phase 3:** `PipelineWatchdogWorker` with an idempotency check. Tests via Robolectric or the real `androidx.work:work-testing`.
5. **Phase 4:** UI integration into the restart button (renderer + long-press sheet for multi-pending). Manual verification on device.
6. **Phase 5:** the auto-cleanup policy (`PeriodicWorkRequest`, "delete audio files where `inserted_at < now - 7d`"). Optional, can be V2.

Phases 1–4 are blocking for feature completeness; phase 5 is hygiene.

---

## J. References (file:line)

| Topic | File | Line / function |
|-------|-------|-----------------|
| Persist-first pattern | `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` | `persistNewSession`, `runTranscriptionPipelineBlocking:215-325` |
| RecordingRepository (file lifecycle) | `app/src/main/java/net/devemperor/dictate/core/RecordingRepository.kt` | `persistFromCache:45-49`, `extractDurationSeconds:65-80`, `loadBySessionId:107-118`, `deleteBySessionId:136-148` |
| SessionManager (boundary for DB writes) | `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt` | `createSession:44-87`, `finalizeCompleted:97`, `logTextInsertion:320-342`, `getFinalOutput:372-385` |
| SessionEntity (today's data model) | `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` | the whole file |
| SessionStatus (Double-Enum) | `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt` | the whole file |
| MigrationTo3 (CHECK rebuild pattern) | `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt` | `MIGRATION_2_3:20-205` |
| DurationHealingJob (inspiration for the watchdog worker) | `app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt` | `heal:37-71` |
| JobExecutor (central job entry) | `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` | `start:82-168`, `cancel:184-187` |
| ActiveJobRegistry (StateFlow live lock) | `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` | `register:37-41`, `isActive:61` |
| ActiveJobRegistryObserver (Java-friendly subscribe helper) | `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt` | `observe:30-42` |
| ResendStatusDispatcher (today's decision matrix) | `app/src/main/java/net/devemperor/dictate/core/ResendStatusDispatcher.kt` | `decide:56-72` |
| ResendInsertStrategy (3-stage insertion) | `app/src/main/java/net/devemperor/dictate/core/ResendInsertStrategy.kt` | `execute:85-118` |
| Insertion into the edit field | `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | `commitTextToInputConnection:1907-2000`, `onResendClicked:2249-2300` |
| Recording-stop → pipeline-start | `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | `runTranscriptionViaOrchestrator:1665-1736` |
| AIProviderException.ErrorType | `app/src/main/java/net/devemperor/dictate/ai/AIProviderException.kt` | `ErrorType:18-39` |
| DB patterns (Double-Enum, Denormalized Cache) | `docs/DATABASE-PATTERNS.md` | the whole file, esp. "Double-Enum Pattern" + "Denormalized Cache Columns" |

---
