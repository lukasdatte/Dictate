# Plan: Dictate Reprocess-Refactor — Datenfluss, History, Reprocessing

**Status:** Draft, ready for review
**Scope:** Major architectural refactor touching database schema, pipeline orchestration, keyboard UI, and history UI

---

<!-- EXECUTION-PLAN -->
## Execution Plan

**Erstellt:** 2026-04-16
**Geschätzte Chunks:** 3

### Meine Strategie

Ich folge der "Implementation Order Recommendation" des Plans: Erst die Data-Layer-Grundlagen (DB, VO, Repository, SessionTracker), dann die Runtime-Schicht (Registry, Executor, Orchestrator), zuletzt die UI-Integration. Phase 6 wird bewusst in Chunk 1 vorgezogen — mit Deprecation-Strategie für alte SessionTracker-APIs, damit das Projekt nach jedem Chunk kompiliert.

### Geplante Chunks

| # | Chunk | Plan-Abschnitte | Warum diese Gruppierung? |
|---|-------|-----------------|--------------------------|
| 1 | Data Layer Foundation | Phase 0, 1, 2, 6 + Referenz-Sektionen | DB-Schema, Recording VO, Repository, SessionTracker-Refactor bilden die persistente Grundlage |
| 2 | Pipeline & Registry | Phase 3, 4, 5 | JobState → Executor → Orchestrator; Runtime-Schicht baut auf Chunk 1 auf |
| 3 | UI Integration | Phase 7-13 + Verification | Keyboard-Controller, History-UI, Resend-Button, Tests bauen auf Chunks 1 + 2 auf |

### Abhängigkeiten & Risiken

- **Chunk 1 → 2:** `RecordingRepository`, `SessionDao`, `Recording` VO müssen verfügbar sein
- **Chunk 2 → 3:** `ActiveJobRegistry`, `JobExecutor`, `JobState` müssen verfügbar sein (UI konsumiert StateFlow)
- **Risiko:** Phase 6 wird in Chunk 1 nur mit `@Deprecated`-Markierung für alte APIs integriert — volle Migration der Call-Sites erst in Chunk 3
- **Risiko:** Room-Migration (v2→v3) darf keine Daten verlieren — Migration-Tests sind Teil von Chunk 1

### Zwischenschritte

- **Chunk 1:** Phase 0 zuerst (Schema, Migration), dann VO → Repository → SessionTracker
- **Chunk 2:** JobState zuerst, dann Registry → Token → Executor → Orchestrator
- **Chunk 3:** Resources (Strings, Icons) zuerst, dann Controller → Resend → History → Integration Tests

---
<!-- /EXECUTION-PLAN -->

## Context & Motivation

Die aktuelle Architektur des Recording-zu-Pipeline-Flows hat mehrere zusammenhängende Probleme:

1. **Datenverlust bei Cancel/Error:** Sessions können in einem inkonsistenten Zustand in der DB landen — `audioDurationSeconds = 0` trotz existierender Audio-Datei, keine Transcription, keine ProcessingSteps. Ursache: `PipelineOrchestrator.cancel()` ruft `executor.shutdownNow()` auf, wodurch der asynchrone `onAudioPersisted()`-Callback abgebrochen wird, bevor er die Duration setzen kann.

2. **Fehlendes Re-Transcribe:** Aus der History-Activity gibt es keine Möglichkeit, eine gescheiterte Transkription neu zu starten. Der User muss die Aufnahme komplett wiederholen oder verliert sie.

3. **Resend-Button ist zu schlicht:** Short-Press fügt nur Text ein (aus `sessionTracker.lastOutput`), Long-Press startet eine sofortige Re-Transcription ohne UX für Queue-Anpassung. Der User kann die Post-Processing-Kette nicht nachträglich ändern.

4. **Implicit State Detection:** `lastOutput == null` wird als Indikator für "letzte Session war fehlerhaft" verwendet. Das ist fragil und kann zu falschen Fallbacks führen.

5. **Keine Status-Sichtbarkeit in der History-Liste:** Orphan-Sessions sind von erfolgreichen Sessions visuell ununterscheidbar.

6. **Registry/Lifecycle fehlt:** Es gibt keine zentrale Stelle, die "welche Sessions haben aktuell einen laufenden Job?" beantwortet. Parallele Starts sind möglich, View-State-Synchronisation fehlt.

**Ziel des Refactorings:**

- "Persist first, process later" — Audio und Session sind sofort in persistentem Zustand, bevor die API aufgerufen wird.
- Saubere Trennung zwischen persistentem (DB) und Runtime-State (RAM + StateFlow).
- Einheitliche Job-Registry für alle Session-Operationen (Recording, Re-Transcribe, Reprocess, Post-Process).
- Neuer Pseudo-Recording-Mode im Keyboard für nachträgliche Prompt-Queue-Anpassung.
- Status-Badges in der History-Liste mit Short-Press-Reprocessing für alle Fehlerzustände.
- "Double Enum"-Pattern (Kotlin-Enum + SQL CHECK) für alle Status-/Typ-Spalten.

---

## Design Principles

### 1. Persist first, process later

Jede Aufnahme durchläuft drei strikt getrennte Stufen:

```
CAPTURE  → Audio wird in cache/ aufgezeichnet (MediaRecorder)
   ↓
PERSIST  → Audio wird synchron nach files/recordings/{sessionId}.m4a kopiert.
           Duration wird synchron via MediaMetadataRetriever extrahiert.
           Session wird in DB mit Status RECORDED und korrekter Duration angelegt.
           (Dieser Schritt ist atomar — entweder komplett oder gar nicht.)
   ↓
PROCESS  → Pipeline-Run (Transcription, Auto-Format, Prompts).
           Runtime-State lebt im ActiveJobRegistry (RAM, StateFlow).
           Terminale Status-Updates in DB: COMPLETED oder FAILED.
```

**Konsequenz:** Ein App-Kill während PROCESS hinterlässt eine Session im Status `RECORDED`. Beim nächsten Start sieht der User: Audio ist da, Verarbeitung war nicht erfolgreich. Kein Housekeeping-Job, kein `INTERRUPTED`-Status nötig. Die Session ist aus DB-Sicht bereits im korrekten Zustand.

### 2. Terminale vs. transiente Status

**Persistent (DB):** Nur Zustände mit Bedeutung bei inaktiver App.
- `RECORDED` — Audio liegt vor, nichts oder abgebrochen verarbeitet
- `COMPLETED` — Pipeline erfolgreich durchgelaufen
- `FAILED` — Pipeline mit Error beendet (API-Error, Quota, ...)
- `CANCELLED` — User hat explizit abgebrochen

**Runtime (RAM, StateFlow):** Nur während der App-Session relevant.
- `Running(sessionId, currentStep, totalSteps, startedAt, language)` — aktiver Job

Die UI kombiniert beides:
```
getDisplayState(sessionId):
    val runtime = activeJobRegistry.get(sessionId)
    if (runtime != null) return DisplayState.Running(runtime.progress)
    return DisplayState.fromPersisted(sessionDao.getStatus(sessionId))
```

### 3. Double-Enum-Pattern (see docs/DATABASE-PATTERNS.md)

Alle Status-/Typ-Spalten werden als Kotlin-Enum + SQL `CHECK`-Constraint modelliert. Neue Enum-Werte erzwingen eine Migration. Details, workflow for adding/removing values, and migration test template: [`docs/DATABASE-PATTERNS.md`](../../docs/DATABASE-PATTERNS.md) → section "Double-Enum Pattern". `CLAUDE.md` contains only a one-sentence pointer.

### 4. Ein System für den letzten Keyboard-Job

Der Resend-Button und der Long-Press-Reprocess brauchen Zugriff auf "die letzte im Keyboard durchgeführte Session". Regel:

- **RAM-first, DB-fallback:** Cache wird zuerst befragt (schnell), bei Miss DB-Query mit `origin = 'KEYBOARD'`.
- **Ein Provider:** Einzige Quelle ist `SessionTracker.getLastKeyboardSession()`. Kein zweites parallel gepflegtes Feld.
- **`Pref.LastSessionId` entfällt:** Die DB ist Source of Truth. Der RAM-Cache ist Performance-Optimierung.

### 5. Registry als reaktive State-Source

`ActiveJobRegistry` ist nicht nur ein passiver Set-Tracker — er ist ein `StateFlow`-Provider, den UI-Komponenten (ViewModels, KeyboardUiController) abonnieren. Änderungen propagieren automatisch in alle sichtbaren Views, ohne imperative Aufrufe.

### 6. Kooperative Cancellation (CancellationToken)

> **Finding SEC-1-6:** The existing cancel mechanism is `executor.shutdownNow()` (thread interruption).
> The plan introduces CancellationToken but does not explain the transition path or how
> external I/O (OkHttp calls) is affected. This design principle makes the strategy explicit.

**Strategy:** Two-layer cancellation — cooperative token as primary, thread interrupt as fallback.

1. **Token creation:** `JobExecutor.start()` creates a `CancellationToken` per job and stores it as `activeToken`.
2. **Token check points:** The `PipelineOrchestrator` calls `token.throwIfCancelled()`:
   - Before each pipeline step (transcription, auto-format, each prompt)
   - After each API call returns (before persisting the result)
3. **HTTP call cancellation:** OkHttp calls that are in-flight when the token is set are NOT automatically cancelled by the token. `Thread.interrupt()` (sent after `token.cancel()`) interrupts the blocking `OkHttpCall.execute()`, causing an `InterruptedException` or `IOException`. The catch blocks in the orchestrator treat both as cancellation (see Phase 5).
4. **Transition from old code:** The old `executor.shutdownNow()` call is replaced by `JobExecutor.cancel(sessionId)`, which sets the token AND sends the interrupt. No code path relies solely on interruption anymore.

**Exception hierarchy:**
- `CancellationToken.throwIfCancelled()` throws `java.util.concurrent.CancellationException`
- `Thread.interrupt()` on blocking I/O throws `InterruptedException` or `IOException`
- Both are caught and treated as cancellation in the orchestrator and JobExecutor

### 7. Einheitlicher JobExecutor

Ein Kotlin-Singleton (`object JobExecutor`) führt **alle** Pipeline-Operationen aus: initiales Recording, Re-Transcribe, Step-Regenerate, Post-Process. Damit gibt es einen zentralen Eintrittspunkt, der Registry, Executor und Orchestrator konsistent verbindet. Keine parallelen Code-Pfade.

---

## File Overview

### New files

| Path | Purpose |
|------|---------|
| `core/Recording.kt` | Value Object für eine Audio-Datei + Self-Queries (see Open Decisions for naming discussion: `AudioFile.kt` alternative) |
| `core/RecordingRepository.kt` | Persistence-Bridge zwischen Filesystem + DB (see Open Decisions for naming discussion: `AudioFileRepository.kt` alternative) |
| `core/ActiveJobRegistry.kt` | Reaktive Job-State-Registry (StateFlow) |
| `core/JobExecutor.kt` | Kotlin-Singleton für Pipeline-Ausführung |
| `core/JobState.kt` | Sealed Class für Runtime-Job-Status |
| `core/CancellationToken.kt` | Kooperative Cancellation für Pipeline-Jobs |
| `database/entity/SessionStatus.kt` | Enum: RECORDED / COMPLETED / FAILED / CANCELLED |
| `database/entity/SessionErrorType.kt` | Enum: INVALID_API_KEY / RATE_LIMITED / ... / UNKNOWN (Finding SEC-1-2) |
| `database/entity/SessionOrigin.kt` | Enum: KEYBOARD / HISTORY_REPROCESS / POST_PROCESSING |
| `database/migration/MigrationTo3.kt` | Schema-Migration für alle neuen Spalten |
| `history/HistoryDetailViewModel.kt` | Session-scoped ViewModel mit Registry-Observer |
| `history/PromptChooserBottomSheetV2.kt` | BottomSheet für "Erneut verarbeiten" in Detail |

### Modified files

| Path | Changes |
|------|---------|
| `database/entity/SessionEntity.kt` | +status, +origin, +queuedPromptIds, +lastErrorType, +lastErrorMessage |
| `database/dao/SessionDao.kt` | +findLatestByOrigin, +updateStatus, +updateError, +findWithMissingDuration |
| `database/DictateDatabase.kt` | Version bump + neue Migration + onOpen-Callback für Healing |
| `core/SessionManager.kt` | createSession erweitert um origin + queuedPromptIds; neue finalize-Methoden |
| `core/SessionTracker.kt` | getLastKeyboardSession mit RAM-first/DB-fallback, Prefs-Entfernung |
| `core/PipelineOrchestrator.kt` | reuseSessionId Param, resumePipeline Methode, Persist-first Reihenfolge |
| `core/KeyboardUiController.kt` | PipelineUiState.ReprocessStaging + Render-Logik, PipelineConfig → AutoEnterConfig rename, `isBusy()` method |
| `core/DictateInputMethodService.java` | Resend-Umbau, Pseudo-Recording-Trigger, JobExecutor statt Direct-Call |
| `core/MainButtonsController.kt` | Button-State-Mapping für ReprocessStaging |
| `core/RecordingUiController.kt` | Pause-Button Blind-Schaltung im ReprocessStaging |
| `history/HistoryActivity.java` | Badge-Rendering in Liste |
| `history/HistoryAdapter.java` | Status-Icon + Status-Text pro Row |
| `history/HistoryDetailActivity.java` | Zwei neue Buttons + ViewModel-Integration |
| `history/PipelineStepAdapter.java` | Re-Transcribe-Button auf AUDIO-Row |
| `res/layout/activity_dictate_keyboard_view.xml` | Language-Chip-Container |
| `res/layout/item_history_session.xml` | Status-Icon + Status-Text |
| `res/layout/activity_history_detail.xml` | Zwei neue Action-Buttons |
| `preferences/DictatePrefs.kt` | LastSessionId entfernen |
| `CLAUDE.md` | Double-Enum-Pattern dokumentiert (bereits erledigt) |

---

## Phase 0: Database Migration & Schema

### 0.0 Gradle Dependencies (Prerequisite)

> **Finding SEC-3-1 / SEC-10-1:** The codebase currently uses zero Coroutines or StateFlow.
> The following dependencies must be added to `app/build.gradle.kts` before any
> StateFlow-based code (ActiveJobRegistry, HistoryDetailViewModel) can compile.

```kotlin
// app/build.gradle.kts — add to dependencies block
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
// For Java consumers observing StateFlow via LiveData bridge:
implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
```

**Java-Consumer Pattern:** `DictateInputMethodService` is Java and cannot collect a `StateFlow` directly. The recommended bridge is `StateFlow.asLiveData()` (from `lifecycle-livedata-ktx`), which the Java code can observe via standard `Observer<T>`:

```java
// In DictateInputMethodService.java — observing ActiveJobRegistry from Java:
LiveData<Map<String, JobState>> jobLiveData =
    FlowLiveDataConversions.asLiveData(ActiveJobRegistry.INSTANCE.getState());
jobLiveData.observeForever(jobs -> {
    // Update keyboard UI based on active job state
});
```

Alternatively, a Kotlin wrapper can expose a callback-based API that hides the Flow:

```kotlin
// In ActiveJobRegistry — callback bridge for Java callers:
fun observeForever(callback: (Map<String, JobState>) -> Unit): Job {
    return CoroutineScope(Dispatchers.Main).launch {
        state.collect { callback(it) }
    }
}
```

The first option (`asLiveData()`) is preferred because it follows standard Android lifecycle patterns and avoids manual scope management.

### 0.1 New enums

**Datei:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionErrorType.kt`

> **Finding SEC-1-2:** Design Principle 3 requires the Double-Enum pattern for ALL
> status/type columns. `last_error_type` originally had only SQL CHECK values but no
> Kotlin enum — this violates the pattern and makes the values non-type-safe in code.

```kotlin
package net.devemperor.dictate.database.entity

/**
 * Error classification for failed sessions.
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md):
 * the SQL column has a CHECK constraint matching these values exactly.
 *
 * Used in [SessionEntity.lastErrorType] when status == FAILED.
 * When status != FAILED, lastErrorType should be null.
 */
enum class SessionErrorType {
    INVALID_API_KEY,
    RATE_LIMITED,
    MODEL_NOT_FOUND,
    BAD_REQUEST,
    SERVER_ERROR,
    NETWORK_ERROR,
    UNKNOWN
}
```

> **Note (SEC-0-4):** `CANCELLED` was removed from the error_type CHECK constraint.
> Cancellation is expressed via `status = CANCELLED` (with `last_error_type = null`),
> not via an error type. This avoids semantic overlap between the two fields.

**Datei:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`

```kotlin
package net.devemperor.dictate.database.entity

/**
 * Terminal persisted state of a [SessionEntity].
 * Runtime state (TRANSCRIBING, PROCESSING) is NOT stored here —
 * it lives in ActiveJobRegistry.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md):
 * the SQL column has a CHECK constraint matching these values exactly.
 */
enum class SessionStatus {
    RECORDED,   // Audio persistent, no processing run (yet) or aborted before DB write of result
    COMPLETED,  // Pipeline finished successfully
    FAILED,     // Pipeline finished with an error (API, quota, network, ...)
    CANCELLED   // User explicitly cancelled
}
```

**Datei:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionOrigin.kt`

```kotlin
package net.devemperor.dictate.database.entity

/**
 * Where a session was initiated from. Used to distinguish keyboard-originated
 * sessions from sessions started out of the history UI.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md).
 */
enum class SessionOrigin {
    KEYBOARD,           // Started from the IME keyboard (normal recording flow)
    HISTORY_REPROCESS,  // Started from HistoryDetailActivity (re-transcribe / re-run)
    POST_PROCESSING     // Post-processing chain (child of another session)
}

// Finding SEC-1-4: Valid type × origin combinations
// ┌─────────────────────┬──────────┬────────────────────┬─────────────────┐
// │ SessionType         │ KEYBOARD │ HISTORY_REPROCESS  │ POST_PROCESSING │
// ├─────────────────────┼──────────┼────────────────────┼─────────────────┤
// │ RECORDING           │ ✅       │ ✅                 │ ❌              │
// │ REWORDING           │ ✅       │ ❌                 │ ❌              │
// │ POST_PROCESSING     │ ❌       │ ❌                 │ ✅              │
// └─────────────────────┴──────────┴────────────────────┴─────────────────┘
// Note: POST_PROCESSING type + POST_PROCESSING origin is intentionally
// redundant — origin confirms provenance, type classifies the session's
// content. This allows queries like "all sessions from history" regardless
// of whether they are recordings or post-processing chains.
```

### 0.2 SessionEntity update

**Datei:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt`

```kotlin
package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["parent_session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("parent_session_id"),
        Index("type"),
        Index("created_at"),
        Index("origin"),  // NEW — for getLastKeyboardSession query
        Index("status")   // NEW — for history list filtering
    ]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "target_app_package") val targetAppPackage: String?,
    @ColumnInfo(name = "language") val language: String?,
    @ColumnInfo(name = "audio_file_path") val audioFilePath: String?,
    @ColumnInfo(name = "audio_duration_seconds") val audioDurationSeconds: Long = 0,
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,

    // NEW — terminal status (Double-Enum, see CLAUDE.md)
    @ColumnInfo(name = "status") val status: String = SessionStatus.RECORDED.name,

    // NEW — where the session was started from (Double-Enum)
    @ColumnInfo(name = "origin") val origin: String = SessionOrigin.KEYBOARD.name,

    // NEW — queued prompts at the time of session creation (comma-separated IDs)
    @ColumnInfo(name = "queued_prompt_ids") val queuedPromptIds: String? = null,

    // NEW — last error context (only for status == FAILED)
    @ColumnInfo(name = "last_error_type") val lastErrorType: String? = null,
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String? = null,

    // Denormalized fields — cache for fast search/display in HistoryActivity
    @ColumnInfo(name = "final_output_text") val finalOutputText: String? = null,
    @ColumnInfo(name = "input_text") val inputText: String? = null
) {
    // Convenience enum accessors (boundary conversion)
    val statusEnum: SessionStatus
        get() = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.RECORDED)

    val originEnum: SessionOrigin
        get() = runCatching { SessionOrigin.valueOf(origin) }.getOrDefault(SessionOrigin.KEYBOARD)

    // Finding SEC-1-2: Double-Enum accessor for lastErrorType
    val errorTypeEnum: SessionErrorType?
        get() = lastErrorType?.let { runCatching { SessionErrorType.valueOf(it) }.getOrNull() }
}
```

### 0.3 Migration SQL

**Datei:** `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt`

```kotlin
package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration for the reprocess refactor. Adds the following columns to `sessions`:
 * - status (TEXT NOT NULL, CHECK constraint)
 * - origin (TEXT NOT NULL, CHECK constraint)
 * - queued_prompt_ids (TEXT)
 * - last_error_type (TEXT, CHECK constraint)
 * - last_error_message (TEXT)
 *
 * Since SQLite cannot add CHECK constraints via ALTER TABLE, we recreate the table.
 *
 * Orphan handling: sessions with type=RECORDING and no audio_duration_seconds
 * but an audio_file_path get status=RECORDED (they need healing, see onOpen callback).
 * All other legacy sessions get status=COMPLETED as a pragmatic default.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the new table with CHECK constraints
        db.execSQL("""
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_duration_seconds INTEGER NOT NULL DEFAULT 0,
                parent_session_id TEXT,
                status TEXT NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('RECORDED', 'COMPLETED', 'FAILED', 'CANCELLED')),
                origin TEXT NOT NULL DEFAULT 'KEYBOARD'
                    CHECK (origin IN ('KEYBOARD', 'HISTORY_REPROCESS', 'POST_PROCESSING')),
                queued_prompt_ids TEXT,
                last_error_type TEXT
                    CHECK (last_error_type IS NULL OR last_error_type IN (
                        'INVALID_API_KEY', 'RATE_LIMITED', 'MODEL_NOT_FOUND',
                        'BAD_REQUEST', 'SERVER_ERROR', 'NETWORK_ERROR',
                        'UNKNOWN'
                    )),
                last_error_message TEXT,
                final_output_text TEXT,
                input_text TEXT,
                FOREIGN KEY (parent_session_id) REFERENCES sessions_new (id) ON DELETE CASCADE
            )
        """.trimIndent())

        // 2. Copy existing rows, inferring status from current state
        //    - If RECORDING type, has audio_file_path, but no transcription → RECORDED (needs healing)
        //    - Otherwise → COMPLETED
        db.execSQL("""
            INSERT INTO sessions_new (
                id, type, created_at, target_app_package, language,
                audio_file_path, audio_duration_seconds, parent_session_id,
                status, origin, queued_prompt_ids,
                last_error_type, last_error_message,
                final_output_text, input_text
            )
            SELECT
                s.id, s.type, s.created_at, s.target_app_package, s.language,
                s.audio_file_path, s.audio_duration_seconds, s.parent_session_id,
                CASE
                    WHEN s.type = 'RECORDING'
                        AND s.audio_file_path IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM transcriptions t WHERE t.session_id = s.id)
                    THEN 'RECORDED'
                    ELSE 'COMPLETED'
                END AS status,
                'KEYBOARD' AS origin,
                NULL AS queued_prompt_ids,
                NULL AS last_error_type,
                NULL AS last_error_message,
                s.final_output_text, s.input_text
            FROM sessions s
        """.trimIndent())

        // 3. Drop old table, rename new
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")

        // 4. Recreate indices
        db.execSQL("CREATE INDEX index_sessions_parent_session_id ON sessions (parent_session_id)")
        db.execSQL("CREATE INDEX index_sessions_type ON sessions (type)")
        db.execSQL("CREATE INDEX index_sessions_created_at ON sessions (created_at)")
        db.execSQL("CREATE INDEX index_sessions_origin ON sessions (origin)")
        db.execSQL("CREATE INDEX index_sessions_status ON sessions (status)")
    }
}
```

### 0.4 DictateDatabase version bump + onOpen hook

**Datei:** `app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt`

```kotlin
// Version bump
@Database(
    entities = [...],
    version = 3,  // was 2
    exportSchema = true
)
abstract class DictateDatabase : RoomDatabase() {
    // ... existing DAOs ...

    companion object {
        @Volatile private var instance: DictateDatabase? = null

        fun getInstance(context: Context): DictateDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                        context.applicationContext,
                        DictateDatabase::class.java,
                        "dictate.db"
                    )
                    .addMigrations(
                        // ... existing migrations ...
                        MIGRATION_2_3
                    )
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // IMPORTANT: Preserve existing partial unique indices
                            // (see SEC-1-3 finding — this call must NOT be lost)
                            createPartialUniqueIndices(db)
                        }
                    })
                    .build()
                    .also { instance = it }
            // IMPORTANT: Healing job must run AFTER getInstance() returns
            // (see SA-2 finding — calling heal() from onOpen causes a
            // circular call back into getInstance(), creating a second
            // Room database instance on the same file).
            // The heal() call is moved to DictateApplication.onCreate()
            // which runs after the DB singleton is fully initialized.
            }
    }
}
```

### 0.5 Duration healing job

**Datei:** `app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt`

```kotlin
package net.devemperor.dictate.database

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import net.devemperor.dictate.database.entity.SessionStatus
import java.io.File

/**
 * One-time healing job that runs on every DB open.
 *
 * Finds legacy RECORDED sessions with `audio_duration_seconds = 0` but a valid
 * `audio_file_path`, extracts the real duration via MediaMetadataRetriever, and
 * updates the DB.
 *
 * Sessions whose audio file no longer exists are promoted to FAILED.
 *
 * The job is idempotent — once a row has a non-zero duration, it is ignored.
 */
object DurationHealingJob {

    private const val TAG = "DurationHealingJob"

    /**
     * Entry point — called from DictateApplication.onCreate() AFTER
     * DictateDatabase.getInstance() has returned (avoiding circular re-entry).
     *
     * NOTE: Do NOT call this from DictateDatabase.onOpen() — that would
     * trigger a second getInstance() call while the first is still inside
     * synchronized{}, causing a deadlock or duplicate Room instance.
     * See SA-2 / CA-2 / SEC-0-2 findings.
     */
    fun heal(dao: SessionDao) {
        val needsHealing = dao.findWithMissingDuration()
        if (needsHealing.isEmpty()) return

        Log.i(TAG, "Healing ${needsHealing.size} session(s) with missing duration")

        for (session in needsHealing) {
            val path = session.audioFilePath ?: continue
            val file = File(path)
            if (!file.exists()) {
                // Audio file is gone (e.g. was in cache/ which Android cleared,
                // or manual delete). Promote to FAILED with a specific error
                // type so the UI can render a meaningful message.
                //
                // Status transition: RECORDED → FAILED
                // Error context: lastErrorType = "UNKNOWN", lastErrorMessage
                // explains the cause. The user sees "Fehlgeschlagen" in the
                // history list and, in the detail view, "Audio file missing".
                // The reprocess buttons are hidden via RecordingRepository.LoadResult.FileMissing.
                dao.updateStatus(session.id, SessionStatus.FAILED.name)
                dao.updateError(
                    session.id,
                    "UNKNOWN",
                    "Audio file not found during healing"
                )
                continue
            }

            val retriever = MediaMetadataRetriever()
            val durationSec: Long = try {
                retriever.setDataSource(path)
                val ms = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                ms / 1000L
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read duration for ${session.id}", e)
                0L
            } finally {
                retriever.release()
            }

            if (durationSec > 0) {
                dao.updateAudioDuration(session.id, durationSec)
            }
        }
    }
}
```

### 0.6 SessionDao additions

**Datei:** `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt`

```kotlin
@Dao
interface SessionDao {
    // ... existing methods ...

    // NEW — RAM-first / DB-fallback support
    @Query("SELECT * FROM sessions WHERE origin = :origin ORDER BY created_at DESC LIMIT 1")
    fun findLatestByOrigin(origin: String): SessionEntity?

    // NEW — healing query
    // NOTE (SEC-0-5): Does NOT filter by status — COMPLETED sessions migrated
    // from legacy data may also have audio_duration_seconds = 0 despite having
    // a valid audio file. The status-agnostic query catches all cases.
    @Query("""
        SELECT * FROM sessions
        WHERE audio_file_path IS NOT NULL
          AND audio_duration_seconds = 0
    """)
    fun findWithMissingDuration(): List<SessionEntity>

    // NEW — terminal status updates
    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    fun updateStatus(id: String, status: String)

    @Query("UPDATE sessions SET last_error_type = :type, last_error_message = :message WHERE id = :id")
    fun updateError(id: String, type: String?, message: String?)

    @Query("UPDATE sessions SET queued_prompt_ids = :ids WHERE id = :id")
    fun updateQueuedPromptIds(id: String, ids: String?)

    @Query("UPDATE sessions SET audio_file_path = NULL WHERE id = :id")
    fun clearAudioFilePath(id: String)

    // NEW — Finding SEC-0-8: Required after file-copy and Opus compression
    // to update the path from cache → persistent storage, or from .m4a → .opus
    @Query("UPDATE sessions SET audio_file_path = :path WHERE id = :id")
    fun updateAudioFilePath(id: String, path: String)
}
```

---

## Phase 1: Domain Layer — Recording Value Object

**Datei:** `app/src/main/java/net/devemperor/dictate/core/Recording.kt`

```kotlin
package net.devemperor.dictate.core

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Value object representing a single audio recording on disk.
 * Holds the file reference and exposes self-queries about it.
 *
 * This is NOT a manager — it knows only about itself and does not
 * orchestrate multiple files or talk to the database.
 */
// NOTE (Finding SA-1): This value object imports MediaMetadataRetriever
// (Android framework), which breaks layer-separation and JVM testability.
// Consider moving extractDurationSeconds() to RecordingRepository (infrastructure
// layer) during implementation if JVM tests for Recording are desired.
// DurationHealingJob should then call RecordingRepository.extractDuration()
// instead of duplicating the logic (DRY).
data class Recording(
    val audioFile: File,
    val sessionId: String
) {
    fun exists(): Boolean = audioFile.exists()

    fun sizeBytes(): Long = if (audioFile.exists()) audioFile.length() else 0L

    /**
     * Extracts the duration via MediaMetadataRetriever.
     * Returns 0 on failure (file corrupt, format unreadable, ...).
     *
     * NOTE: Consider moving to RecordingRepository to avoid Android
     * framework dependency in this value object (see Finding SA-1).
     */
    fun extractDurationSeconds(): Long {
        if (!audioFile.exists()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            val ms = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            ms / 1000L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun delete(): Boolean = audioFile.exists() && audioFile.delete()
}
```

---

## Phase 2: Repository Layer — RecordingRepository

**Datei:** `app/src/main/java/net/devemperor/dictate/core/RecordingRepository.kt`

```kotlin
package net.devemperor.dictate.core

import android.content.Context
import android.util.Log
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import java.io.File

/**
 * Persistence bridge for Recordings. Handles the filesystem side
 * (copy from cache to persistent storage, Opus compression) and the DB
 * side (reading session metadata, deleting audio).
 *
 * This is the single place where audio files get promoted from cache
 * to persistent storage. No other code should write to files/recordings/.
 */
class RecordingRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordingRepository"
    }

    private val recordingsDir: File by lazy {
        File(context.filesDir, "recordings").apply { mkdirs() }
    }

    /**
     * Copies the cache file into persistent storage and returns a [Recording].
     * This is SYNCHRONOUS and fast (file copy only, no network).
     *
     * @throws java.io.IOException if the copy fails
     */
    fun persistFromCache(cacheFile: File, sessionId: String): Recording {
        val dest = File(recordingsDir, "$sessionId.m4a")
        cacheFile.copyTo(dest, overwrite = true)
        val recording = Recording(dest, sessionId)

        // Compress to Opus in the background after persist.
        // The uncompressed m4a stays on disk until compression succeeds,
        // so a crash mid-compression leaves a valid fallback file.
        compressToOpus(recording)

        return recording
    }

    /**
     * Compresses the recording from m4a to Opus (~16 KB/s for speech).
     * Expected savings: ~90% storage reduction.
     *
     * The original m4a file is only deleted after successful Opus encoding.
     * If encoding fails, the m4a file remains as fallback.
     *
     * Whisper accepts both m4a and opus natively — no re-encoding is needed
     * when re-transcribing an Opus-compressed recording.
     *
     * Dependency: Requires an Opus encoder library on Android.
     * Options include libopus via NDK (C binding) or a pure-Java wrapper
     * such as `concentus` (Java port of libopus). Evaluate latency vs. APK
     * size trade-off during implementation.
     */
    fun compressToOpus(recording: Recording): Recording {
        val opusFile = File(recording.audioFile.parent, "${recording.sessionId}.opus")
        try {
            // TODO: Call Opus encoder (NDK or Java wrapper)
            // OpusEncoder.encode(recording.audioFile, opusFile, bitrate = 16_000)

            if (opusFile.exists() && opusFile.length() > 0) {
                recording.audioFile.delete()
                return Recording(opusFile, recording.sessionId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opus compression failed for ${recording.sessionId}, keeping m4a", e)
            opusFile.delete() // Clean up partial file
        }
        return recording // Fallback: return original m4a
    }

    /**
     * Loads a Recording given a session. Returns [LoadResult.FileMissing]
     * if the audio file no longer exists on disk.
     */
    fun loadBySessionId(sessionId: String): LoadResult {
        val session = DictateDatabase.getInstance(context).sessionDao().getById(sessionId)
            ?: return LoadResult.SessionNotFound

        val path = session.audioFilePath
            ?: return LoadResult.FileMissing(session)

        val file = File(path)
        if (!file.exists()) return LoadResult.FileMissing(session)

        return LoadResult.Available(Recording(file, sessionId), session)
    }

    /**
     * Deletes the audio file for a session and sets audioFilePath = null in DB.
     * Transcription and processing results are preserved.
     *
     * After deletion, [loadBySessionId] will return [LoadResult.FileMissing],
     * causing the reprocess buttons in the UI to be hidden automatically.
     *
     * @return true if the file was deleted (or didn't exist), false on DB error
     */
    fun deleteBySessionId(sessionId: String): Boolean {
        val dao = DictateDatabase.getInstance(context).sessionDao()
        val session = dao.getById(sessionId) ?: return false

        val path = session.audioFilePath
        if (path != null) {
            val file = File(path)
            if (file.exists()) file.delete()
        }

        dao.clearAudioFilePath(sessionId)
        return true
    }

    sealed class LoadResult {
        data class Available(val recording: Recording, val session: SessionEntity) : LoadResult()
        data class FileMissing(val session: SessionEntity) : LoadResult()
        object SessionNotFound : LoadResult()
    }
}
```

---

## Phase 3: JobState + ActiveJobRegistry

### 3.1 JobState

**Datei:** `app/src/main/java/net/devemperor/dictate/core/JobState.kt`

```kotlin
package net.devemperor.dictate.core

/**
 * Runtime state of a pipeline job for a single session.
 * Lives only in memory — not persisted to the database.
 */
sealed class JobState {
    abstract val sessionId: String

    data class Running(
        override val sessionId: String,
        val currentStepIndex: Int,
        val totalSteps: Int,
        val currentStepName: String,
        val startedAt: Long
    ) : JobState()
}

/** Type classifier for a job — used for analytics and UI decisions. */
enum class JobKind {
    RECORDING,              // Initial recording pipeline
    RESUME,                 // Short-press resend, continue from failure point
    REPROCESS_STAGING,      // Long-press, user-edited queue, full re-transcribe
    HISTORY_REPROCESS,      // From HistoryDetailActivity
    STEP_REGENERATE,        // Regenerate a single processing step
    POST_PROCESS            // Post-processing chain
}
```

### 3.2 ActiveJobRegistry

**Datei:** `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt`

```kotlin
package net.devemperor.dictate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of active jobs. One instance per process (Kotlin object).
 *
 * Exposes a reactive [StateFlow] that UI components can observe to render
 * running-job indicators without imperative state synchronisation.
 *
 * Thread safety: StateFlow is thread-safe; [register] returns false if the
 * sessionId is already active (lock against double-start).
 *
 * Only one active job at a time (by requirement). Calls to [register] while
 * another job is active will return false and the caller must reject.
 */
object ActiveJobRegistry {

    // NOTE (Finding SEC-3-4): The state is Map<String, JobState> even though
    // only 0 or 1 entries are possible (single-job constraint). This is intentional
    // for forward-compatibility — if the single-job constraint is relaxed in the
    // future, the API and all consumers are already Map-ready.
    // Alternative: StateFlow<JobState?> would be simpler but requires a breaking
    // API change when adding multi-job support.
    private val _state = MutableStateFlow<Map<String, JobState>>(emptyMap())

    /** Observable map of sessionId → JobState. */
    val state: StateFlow<Map<String, JobState>> = _state.asStateFlow()

    /**
     * Attempts to register a job. Returns false if another job is already active.
     */
    @Synchronized
    fun register(sessionId: String, initial: JobState.Running): Boolean {
        if (_state.value.isNotEmpty()) return false
        _state.update { it + (sessionId to initial) }
        return true
    }

    /** Updates progress of a running job. No-op if session is not registered. */
    @Synchronized
    fun update(sessionId: String, newState: JobState.Running) {
        _state.update { current ->
            if (current.containsKey(sessionId)) current + (sessionId to newState) else current
        }
    }

    /** Removes a job from the registry (call on completion, failure, or cancel). */
    @Synchronized
    fun unregister(sessionId: String) {
        _state.update { it - sessionId }
    }

    /** Quick check: is any job active? */
    fun isAnyActive(): Boolean = _state.value.isNotEmpty()

    /** Quick check: is this specific session active? */
    fun isActive(sessionId: String): Boolean = _state.value.containsKey(sessionId)

    /** Current state for a session (null if not active). */
    fun get(sessionId: String): JobState? = _state.value[sessionId]
}
```

---

## Phase 4: JobExecutor (Kotlin Singleton) + CancellationToken

### 4.0 CancellationToken

**Datei:** `app/src/main/java/net/devemperor/dictate/core/CancellationToken.kt`

```kotlin
package net.devemperor.dictate.core

import java.util.concurrent.CancellationException

/**
 * Cooperative cancellation token for pipeline jobs.
 *
 * The [PipelineOrchestrator] checks [throwIfCancelled] at defined checkpoints:
 * - Before each pipeline step
 * - After each API call returns
 *
 * [Thread.interrupt] remains as a last-resort fallback for blocking I/O
 * (e.g. OkHttp calls that don't return). The interrupt is sent AFTER
 * [cancel] sets the flag, not INSTEAD of it.
 */
class CancellationToken {
    @Volatile var isCancelled = false
        private set

    fun cancel() { isCancelled = true }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Job cancelled via CancellationToken")
    }
}
```

### 4.1 PipelineConfig (data structure only)

The `PipelineOrchestrator.PipelineConfig` data class is defined here so that `JobRequest.toPipelineConfig()` compiles within Chunk 2. Phase 5 then fills in the consumption side.

```kotlin
// Inside PipelineOrchestrator companion or as inner class:
data class PipelineConfig(
    val audioFile: File? = null,
    val language: String? = null,
    val stylePrompt: String? = null,
    val livePrompt: Boolean = false,
    val recordingsDir: File? = null,
    val targetAppPackage: String? = null,
    val origin: SessionOrigin = SessionOrigin.KEYBOARD,
    val modelOverride: String? = null,   // forward-compat — null = Pref default
    val queuedPromptIds: List<Int> = emptyList()
)
```

### 4.2 JobExecutor

**Datei:** `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt`

```kotlin
package net.devemperor.dictate.core

import android.content.Context
import android.util.Log
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide executor for all pipeline operations.
 *
 * Lifecycle: Kotlin object (singleton). Lazy-initialised on first access.
 * No explicit shutdown — dies with the app process.
 *
 * Responsibilities:
 * - Holds the ExecutorService for background pipeline work.
 * - Provides a single entry point for starting any kind of job.
 * - Creates a [CancellationToken] per job for cooperative cancellation.
 * - Updates [ActiveJobRegistry] throughout the lifecycle.
 * - Finalizes session state in DB on completion/failure/cancel.
 */
object JobExecutor {

    private const val TAG = "JobExecutor"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The cancellation token for the currently active job, or null if idle. */
    @Volatile
    private var activeToken: CancellationToken? = null

    /** The thread running the current job — used for last-resort interrupt. */
    @Volatile
    private var activeThread: Thread? = null

    /**
     * Initializes the executor with a PipelineOrchestrator instance.
     * Must be called once from DictateInputMethodService.onCreate().
     *
     * Finding SEC-10-2: The orchestrator is held internally so that both
     * the IME service and HistoryDetailActivity can start jobs without
     * needing their own orchestrator instance.
     */
    @Volatile
    private var orchestrator: PipelineOrchestrator? = null

    fun initialize(orchestrator: PipelineOrchestrator) {
        this.orchestrator = orchestrator
    }

    /**
     * Starts a new job. Returns false if another job is already active.
     *
     * @param request the job description
     */
    fun start(
        context: Context,
        request: JobRequest
    ): Boolean {
        val orchestrator = this.orchestrator
            ?: throw IllegalStateException("JobExecutor not initialized — call initialize() first")
        val initial = JobState.Running(
            sessionId = request.sessionId,
            currentStepIndex = 0,
            totalSteps = request.totalSteps,
            currentStepName = "",
            startedAt = System.currentTimeMillis()
        )

        if (!ActiveJobRegistry.register(request.sessionId, initial)) {
            Log.w(TAG, "Cannot start job — another job is already active")
            return false
        }

        val token = CancellationToken()
        activeToken = token

        executor.submit {
            activeThread = Thread.currentThread()
            try {
                // Finding CA-4: Sealed-class dispatch — no force-unwraps needed
                when (request) {
                    is JobRequest.TranscriptionPipeline -> orchestrator.runTranscriptionPipeline(
                        request.toPipelineConfig(),
                        reuseSessionId = request.sessionId,
                        cancellationToken = token
                    )
                    is JobRequest.Resume -> orchestrator.resumePipeline(request.sessionId, token)
                    is JobRequest.StepRegenerate -> orchestrator.regenerateStep(
                        request.sessionId,
                        request.stepChainIndex,
                        token
                    )
                    is JobRequest.PostProcess -> orchestrator.runPostProcessing(
                        request.sessionId,
                        request.inputText,
                        request.promptText,
                        request.promptId
                    )
                }
                // Orchestrator writes terminal COMPLETED itself
            } catch (e: java.util.concurrent.CancellationException) {
                // Finding CA-1 + SEC-5-1: CancellationException means the
                // cooperative token was triggered. Check if the cancel path
                // already wrote CANCELLED (from onPipelineCancelClicked).
                // If not, write it here as a safety net.
                if (token.isCancelled) {
                    Log.i(TAG, "Job cancelled: ${request.sessionId}")
                    // onPipelineCancelClicked already wrote CANCELLED — skip finalizeFailed
                } else {
                    finalizeFailed(context, request.sessionId, e)
                }
            } catch (e: InterruptedException) {
                // Thread.interrupt() fallback — treat as cancel
                Log.i(TAG, "Job interrupted (cancel fallback): ${request.sessionId}")
            } catch (e: Exception) {
                // Finding CA-1: Check if this is a race with user cancel.
                // If the token was cancelled, don't overwrite CANCELLED with FAILED.
                if (token.isCancelled) {
                    Log.i(TAG, "Job failed after cancel — ignoring: ${request.sessionId}", e)
                } else {
                    Log.e(TAG, "Job failed: ${request.sessionId}", e)
                    finalizeFailed(context, request.sessionId, e)
                }
            } finally {
                activeToken = null
                activeThread = null
                ActiveJobRegistry.unregister(request.sessionId)
            }
        }
        return true
    }

    /**
     * Cancels the currently active job via cooperative token + last-resort interrupt.
     *
     * The cancellation flow:
     * 1. Set the CancellationToken flag — the orchestrator checks this at
     *    defined checkpoints (before each step, after each API call).
     * 2. Send Thread.interrupt() as fallback — catches blocking OkHttp calls
     *    that don't check the token.
     *
     * The caller (e.g. DictateInputMethodService.onPipelineCancelClicked) is
     * responsible for writing the terminal CANCELLED status to the DB and
     * unregistering from ActiveJobRegistry.
     */
    fun cancel(sessionId: String) {
        activeToken?.cancel()
        activeThread?.interrupt()  // Last-resort for blocking I/O
    }

    private fun finalizeFailed(context: Context, sessionId: String, error: Throwable) {
        val dao = DictateDatabase.getInstance(context).sessionDao()
        val (errorType, errorMessage) = classifyError(error)
        dao.updateStatus(sessionId, SessionStatus.FAILED.name)
        dao.updateError(sessionId, errorType, errorMessage)
    }

    private fun classifyError(error: Throwable): Pair<String, String> {
        // Extract AIProviderException.ErrorType if possible, otherwise UNKNOWN
        // Implementation detail — see Phase 5 for how PipelineOrchestrator surfaces errors
        return when (error) {
            is net.devemperor.dictate.ai.AIProviderException -> error.errorType.name to (error.message ?: "unknown")
            else -> "UNKNOWN" to (error.message ?: error.javaClass.simpleName)
        }
    }
}

/**
 * Unified request descriptor for JobExecutor.start().
 *
 * Finding CA-4: Modeled as a sealed class with type-safe variants per JobKind.
 * This eliminates force-unwraps (stepChainIndex!!, postProcessInputText!!) and
 * makes it impossible to construct an invalid request (e.g., a STEP_REGENERATE
 * without a stepChainIndex). The existing codebase uses sealed classes for
 * similar constructs (PipelineUiState, LoadResult).
 */
sealed class JobRequest {
    abstract val sessionId: String
    abstract val totalSteps: Int
    abstract fun toPipelineConfig(): PipelineOrchestrator.PipelineConfig

    /** Initial recording pipeline or full reprocess (re-transcribe + all steps). */
    data class TranscriptionPipeline(
        override val sessionId: String,
        override val totalSteps: Int,
        val kind: TranscriptionKind,
        val audioFilePath: String? = null,
        val language: String? = null,
        val modelOverride: String? = null,
        val queuedPromptIds: List<Int> = emptyList(),
        val targetAppPackage: String? = null
    ) : JobRequest() {
        override fun toPipelineConfig() = PipelineOrchestrator.PipelineConfig(
            audioFile = audioFilePath?.let { java.io.File(it) },
            language = language,
            targetAppPackage = targetAppPackage,
            modelOverride = modelOverride,
            queuedPromptIds = queuedPromptIds
        )
    }

    enum class TranscriptionKind { RECORDING, REPROCESS_STAGING, HISTORY_REPROCESS }

    /** Short-press resend — continue from failure point. */
    data class Resume(
        override val sessionId: String,
        override val totalSteps: Int
    ) : JobRequest() {
        override fun toPipelineConfig() = PipelineOrchestrator.PipelineConfig()
    }

    /** Regenerate a single processing step. */
    data class StepRegenerate(
        override val sessionId: String,
        override val totalSteps: Int,
        val stepChainIndex: Int  // Non-nullable — always required
    ) : JobRequest() {
        override fun toPipelineConfig() = PipelineOrchestrator.PipelineConfig()
    }

    /** Post-processing chain. */
    data class PostProcess(
        override val sessionId: String,
        override val totalSteps: Int,
        val inputText: String,    // Non-nullable — always required
        val promptText: String,   // Non-nullable — always required
        val promptId: Int? = null
    ) : JobRequest() {
        override fun toPipelineConfig() = PipelineOrchestrator.PipelineConfig()
    }
}
```

---

## Phase 5: PipelineOrchestrator Refactor

This is the largest phase — touches the core pipeline logic.

### 5.1 Changes to `PipelineOrchestrator.kt`

**Key changes:**

1. **New parameter `reuseSessionId: String? = null`** on `runTranscriptionPipeline()`. If set, skip `sessionTracker.startSession()` and operate on the existing session. A `cancellationToken: CancellationToken` parameter is passed through from `JobExecutor`; the orchestrator checks `token.throwIfCancelled()` before each step and after each API call.

2. **`persistAudioFile()` moves to the beginning of the flow.** Audio is copied to `files/recordings/` BEFORE the transcription API call. Duration is extracted SYNCHRONOUSLY via `Recording.extractDurationSeconds()`. Session is written to DB with correct `audioDurationSeconds` and `status = RECORDED` as part of the same atomic step.

3. **`sessionManager.createSession()` receives new parameters** — `origin`, `queuedPromptIds`, initial `status`.

4. **Terminal status writes:** On success, `sessionManager.finalizeCompleted(sessionId)`. On failure, the exception propagates to `JobExecutor`, which writes `FAILED`. On cancel, the cancel path writes `CANCELLED`.

5. **New method `resumePipeline(sessionId: String)`** implements the "short press resume" flow — examines existing processing steps and starts from the first non-successful step.

6. **New optional parameter `modelOverride: String? = null`** on the transcription and completion calls. When null (the normal case), the provider's default model from Prefs is used. When set, it overrides the default for this specific pipeline run. This is the forward-compatibility hook for the `ReprocessStaging.selectedModel` field — wired end-to-end but not yet exposed in the UI.

**PipelineConfig** is defined in Phase 4.1 as a pure data structure. Phase 5 fills in how the orchestrator consumes it (reading `origin`, `modelOverride`, `queuedPromptIds` etc.).

The orchestrator reads `modelOverride` and passes it to the AI runner layer. If null, the runner falls back to the existing `Pref.WhisperModel` / `Pref.CompletionModel` logic. This keeps the normal code path unchanged.

**Pseudocode of the refactored `runTranscriptionPipeline`:**

```kotlin
fun runTranscriptionPipeline(
    config: PipelineConfig,
    reuseSessionId: String? = null,
    cancellationToken: CancellationToken = CancellationToken()
) {
    // ── Stage PERSIST (synchronous, atomic) ──
    val sessionId: String
    if (reuseSessionId != null) {
        sessionId = reuseSessionId
        // Ensure session exists and is in a valid state for re-run
        val existing = sessionManager.getSessionById(sessionId)
            ?: throw IllegalStateException("Session $sessionId not found")
    } else {
        // New session — persist audio first
        val audioFile = config.audioFile
            ?: throw IllegalStateException("Audio file required for new session")

        val tempSessionId = java.util.UUID.randomUUID().toString()
        val recording = recordingRepository.persistFromCache(audioFile, tempSessionId)
        val durationSec = recording.extractDurationSeconds()

        sessionId = sessionManager.createSession(
            id = tempSessionId,
            type = SessionType.RECORDING,
            targetApp = config.targetAppPackage,
            language = config.language,
            audioFilePath = recording.audioFile.absolutePath,
            audioDurationSeconds = durationSec,
            parentId = null,
            origin = config.origin,
            queuedPromptIds = promptQueueManager.getQueuedIds().joinToString(","),
            initialStatus = SessionStatus.RECORDED
        )
        // Finding SEC-5-3: notifySessionCreated was called but never defined
        // on SessionTracker. Replaced by setting the tracker's currentSessionId
        // directly and updating the keyboard cache if origin is KEYBOARD.
        sessionTracker.currentSessionId = sessionId
        if (config.origin == SessionOrigin.KEYBOARD) {
            sessionTracker.notifyKeyboardSessionCompleted(
                sessionManager.getSessionById(sessionId)!!
            )
        }
    }

    // ── Stage PROCESS ──
    try {
        // Step 1: Transcription
        cancellationToken.throwIfCancelled()  // Checkpoint: before step
        callback.onPipelineStepStarted("Transcription", 1, computedTotalSteps)
        val transcription = executeTranscription(sessionId, ...)
        cancellationToken.throwIfCancelled()  // Checkpoint: after API call

        // Step 2: Auto-format (optional)
        if (autoFormatEnabled) {
            cancellationToken.throwIfCancelled()
            callback.onPipelineStepStarted("Auto-Format", 2, computedTotalSteps)
            executeAutoFormat(sessionId, transcription.text)
            cancellationToken.throwIfCancelled()
        }

        // Step 3..N: Queued prompts (each step checks token before + after)
        if (!config.livePrompt) {
            executeQueuedPrompts(sessionId, ..., cancellationToken)
        }

        // Terminal success
        sessionManager.finalizeCompleted(sessionId)
        callback.onPipelineCompleted(sessionId)
    } catch (cancelled: java.util.concurrent.CancellationException) {
        // Finding SEC-5-1: CancellationToken.throwIfCancelled() throws
        // CancellationException, NOT InterruptedException.
        sessionManager.finalizeCancelled(sessionId)
        throw cancelled
    } catch (interrupted: InterruptedException) {
        // Fallback: Thread.interrupt() from JobExecutor.cancel() can cause
        // InterruptedException in blocking I/O (e.g. OkHttp). Treat as cancel.
        sessionManager.finalizeCancelled(sessionId)
        throw interrupted
    } catch (e: Exception) {
        // JobExecutor will write FAILED + error context
        callback.onPipelineError(sessionId, e)
        throw e
    }
}
```

### 5.1.1 Versioning behaviour on reprocess

When `runTranscriptionPipeline` is called with `reuseSessionId != null` (the reprocess path), the pipeline must produce **new versions** of `TranscriptionEntity` and `ProcessingStepEntity` rows rather than overwriting existing ones. The schema already supports this via `version` (Int) and `is_current` (Boolean) columns — this section makes the behaviour explicit.

**Transcription versioning:**

Before writing the new transcription, the orchestrator runs an atomic transaction:

```kotlin
db.runInTransaction {
    // 1. Demote all existing transcription rows for this session
    transcriptionDao.clearCurrentForSession(sessionId)
    // 2. Compute next version number
    val nextVersion = (transcriptionDao.getMaxVersionForSession(sessionId) ?: 0) + 1
    // 3. Insert the new transcription with version=nextVersion, is_current=true
    transcriptionDao.insert(TranscriptionEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        version = nextVersion,
        isCurrent = true,
        text = newTranscriptionText,
        // ... model, provider, tokens, duration ...
    ))
}
```

**ProcessingStep versioning:**

The same pattern applies for each step in the chain. Per-`chainIndex`, a new version is created:

```kotlin
db.runInTransaction {
    // 1. Find the highest version at this chainIndex
    val nextVersion = (stepDao.getMaxVersionAtIndex(sessionId, chainIndex) ?: 0) + 1
    // 2. Demote any current row at this chainIndex
    stepDao.clearCurrentAtIndex(sessionId, chainIndex)
    // 3. Insert the new step row
    stepDao.insert(ProcessingStepEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        chainIndex = chainIndex,
        version = nextVersion,
        isCurrent = true,
        // ... input/output/model/duration/status ...
    ))
}
```

Note that `clearCurrentAtIndex` already exists on `StepDao` (used by `regenerateProcessingStep`). The new method `clearCurrentForSession` on `TranscriptionDao` mirrors that pattern at the session level.

**No deletion of old versions.** Even after multiple reprocesses, all historical versions remain in the DB, accessible via the version selector in the History detail UI. This satisfies the "no history of attempts" requirement (we don't track failed attempts as separate entities) while still preserving multiple successful versions.

**New DAO methods needed:**

```kotlin
// TranscriptionDao
@Query("UPDATE transcriptions SET is_current = 0 WHERE session_id = :sessionId")
fun clearCurrentForSession(sessionId: String)

@Query("SELECT MAX(version) FROM transcriptions WHERE session_id = :sessionId")
fun getMaxVersionForSession(sessionId: String): Int?

// ProcessingStepDao (new methods alongside existing clearCurrentAtIndex)
@Query("SELECT MAX(version) FROM processing_steps WHERE session_id = :sessionId AND chain_index = :chainIndex")
fun getMaxVersionAtIndex(sessionId: String, chainIndex: Int): Int?
```

**Edge case — partial reprocess via `resumePipeline`:**

When `resumePipeline` is called (short-press resend on a FAILED session), it only re-runs steps from the failure point. The steps **before** the failure point are not touched — they keep their existing version. The new steps from the failure point onward get a fresh version. So a session can end up with mixed versions per chainIndex, e.g.:

- chainIndex 0: version 1, is_current=true (transcription succeeded first time)
- chainIndex 1: version 1, is_current=false (auto-format succeeded first time, but reprocess made a new version)
- chainIndex 1: version 2, is_current=true (reprocess re-ran auto-format)
- chainIndex 2: version 1, is_current=true (prompt step failed first time, succeeded on resume — version stays 1)

This is correct: each `chainIndex` has its own version stream, independent of the others.

### 5.1.2 Version-selector UI for transcription rows

The existing `PipelineStepAdapter` already supports version selection for `ProcessingStep` rows (via the `versions: List<ProcessingStepEntity>` builder field). After this refactor, the same mechanism must be extended to `TranscriptionEntity` rows in the History detail view.

**Changes to `PipelineStepAdapter`:**

- New builder field: `transcriptionVersions: List<TranscriptionEntity>` for the TRANSCRIPTION row type
- New callback: `StepActionCallback.onTranscriptionVersionSelected(selectedVersion: TranscriptionEntity)`
- The version-chip rendering is shared with the ProcessingStep variant — the adapter knows from the row type which collection to use

**Changes to `HistoryDetailActivity.buildRecordingPipeline()`:**

When constructing the TRANSCRIPTION row, load all transcription versions for the session:

```java
List<TranscriptionEntity> transcriptionVersions =
    transcriptionDao.getAllVersionsForSession(sessionId);
// ...
.transcriptionVersions(transcriptionVersions)
```

**New DAO method:**

```kotlin
@Query("SELECT * FROM transcriptions WHERE session_id = :sessionId ORDER BY version ASC")
fun getAllVersionsForSession(sessionId: String): List<TranscriptionEntity>
```

When the user selects a different transcription version, the activity calls a new method analogous to `switchVersion` for processing steps:

```java
private void switchTranscriptionVersion(TranscriptionEntity selected) {
    db.runInTransaction(() -> {
        transcriptionDao.clearCurrentForSession(sessionId);
        transcriptionDao.setCurrentById(selected.getId());
    });
    // Update final output text — derived from the new current transcription
    sessionManager.updateFinalOutputText(sessionId, sessionManager.getFinalOutput(sessionId));
    loadSession();
}
```

### 5.2 New method: `resumePipeline()`

```kotlin
/**
 * Resumes a pipeline for a non-completed session. Used by short-press resend
 * and by the "Direkt ausführen" button in history.
 *
 * Algorithm: inspect existing processing steps and start from the first
 * non-successful step. If no transcription exists, start from scratch.
 */
fun resumePipeline(sessionId: String, cancellationToken: CancellationToken = CancellationToken()) {
    val session = sessionManager.getSessionById(sessionId)
        ?: throw IllegalStateException("Session $sessionId not found")

    val transcription = transcriptionDao.getCurrent(sessionId)

    if (transcription == null) {
        // Start from transcription
        val audioFile = File(session.audioFilePath!!)
        val config = PipelineConfig(
            audioFile = audioFile,
            language = session.language,
            stylePrompt = null,
            livePrompt = false,
            recordingsDir = File(context.filesDir, "recordings"),
            targetAppPackage = session.targetAppPackage,
            origin = SessionOrigin.HISTORY_REPROCESS
        )
        runTranscriptionPipeline(config, reuseSessionId = sessionId)
        return
    }

    // Transcription exists — start from auto-format or first prompt
    val existingSteps = stepDao.getCurrentChain(sessionId)
    val lastSuccessIndex = existingSteps
        .filter { it.statusEnum == StepStatus.SUCCESS }
        .maxOfOrNull { it.chainIndex } ?: -1

    val resumeFromIndex = lastSuccessIndex + 1
    val inputText = if (lastSuccessIndex == -1) {
        transcription.text
    } else {
        existingSteps.first { it.chainIndex == lastSuccessIndex }.outputText ?: transcription.text
    }

    // Finding SEC-5-1: resumePipeline also needs try/catch for
    // CancellationException and InterruptedException — same pattern as
    // runTranscriptionPipeline (see Phase 5.1).
    try {
        cancellationToken.throwIfCancelled()
        executeStepsFrom(sessionId, resumeFromIndex, inputText, cancellationToken)
        sessionManager.finalizeCompleted(sessionId)
    } catch (cancelled: java.util.concurrent.CancellationException) {
        sessionManager.finalizeCancelled(sessionId)
        throw cancelled
    } catch (interrupted: InterruptedException) {
        sessionManager.finalizeCancelled(sessionId)
        throw interrupted
    }
    // Note: other exceptions propagate to JobExecutor which writes FAILED
}
```

### 5.3 SessionManager new methods

```kotlin
/**
 * Atomic session creation with all fields set at once.
 *
 * REPLACES the old signature that took fewer fields and wrote denormalized
 * values later. After this refactor, every session starts life with correct
 * audioDurationSeconds, origin, queuedPromptIds, and initialStatus — no
 * more partial rows that need healing.
 *
 * Callers must pass the appropriate [SessionOrigin]:
 * - [PipelineOrchestrator.runTranscriptionPipeline] (keyboard flow) → KEYBOARD
 * - [HistoryDetailActivity] re-transcribe button → HISTORY_REPROCESS
 *   (Note: history reprocess does NOT create a new session — it reuses the
 *   existing sessionId via [PipelineOrchestrator.runTranscriptionPipeline.reuseSessionId].
 *   Origin of the existing session is NOT overwritten.)
 * - [HistoryDetailActivity.createPostProcessingSession] → POST_PROCESSING
 * - Rewording flow (starting a rewording from clipboard text) → KEYBOARD
 *   (audioFilePath=null, audioDurationSeconds=0, queuedPromptIds=null)
 */
fun createSession(
    id: String,
    type: SessionType,
    targetApp: String?,
    language: String?,
    audioFilePath: String?,
    audioDurationSeconds: Long,
    parentId: String?,
    origin: SessionOrigin,
    queuedPromptIds: String?,   // comma-separated prompt entity IDs, null for non-RECORDING
    initialStatus: SessionStatus
): String

// NEW — terminal status writes. Each is a single UPDATE statement,
// and each also touches the SessionTracker RAM cache if applicable.
fun finalizeCompleted(sessionId: String)  // sets status = COMPLETED
fun finalizeCancelled(sessionId: String)  // sets status = CANCELLED + clears last_error_type/message (Finding CA-1)
fun finalizeFailed(sessionId: String, errorType: String, errorMessage: String)

/**
 * Reads the historical queued prompt IDs for a session.
 *
 * IMPORTANT: This method reads from [SessionEntity.queuedPromptIds] (the
 * comma-separated TEXT column populated at session creation time). It does
 * NOT reconstruct the queue from ProcessingStep rows — that would be
 * ambiguous for sessions that died before processing started.
 *
 * Returns an empty list if the session has no stored queue (e.g., legacy
 * sessions from before the migration, or rewording sessions).
 *
 * Used by:
 * - [DictateInputMethodService.onResendLongClicked] to populate the
 *   initial queue in [PipelineUiState.ReprocessStaging].
 * - [HistoryDetailActivity] "Direkt ausführen" button to replay with the
 *   same queue as the original run.
 */
fun getHistoricalQueuedPromptIds(sessionId: String): List<Int>
```

**Call-site migration — updating existing callers of `createSession`:**

Before the refactor, `SessionManager.createSession` has a shorter signature. After the refactor, every call site must pass the new fields. The following call sites need to be updated as part of Phase 5:

| Call site | File | New origin | New queuedPromptIds | New initialStatus |
|-----------|------|-----------|---------------------|-------------------|
| `PipelineOrchestrator.runTranscriptionPipeline` (RECORDING) | `core/PipelineOrchestrator.kt` | `KEYBOARD` (or `HISTORY_REPROCESS` if origin override) | `promptQueueManager.getQueuedIds().joinToString(",")` | `RECORDED` |
| `runReworkingPipeline` or similar (REWORDING) | `core/PipelineOrchestrator.kt` | `KEYBOARD` | `null` | `RECORDED` (transient; immediately transitions to COMPLETED if no audio) |
| `HistoryDetailActivity.createPostProcessingSession` | `history/HistoryDetailActivity.java` | `POST_PROCESSING` | `null` (or the queue if used) | `RECORDED` |
| `PipelineOrchestrator.runStandalonePrompt` (REWORDING) | `core/PipelineOrchestrator.kt` | `KEYBOARD` | `null` | `RECORDED` |
| `SessionTracker.startSession` | `core/SessionTracker.kt` | passed through from caller | passed through | passed through |

**Refactoring note:** If a caller currently calls `sessionTracker.startSession(...)` which internally calls `sessionManager.createSession(...)`, the `startSession` wrapper itself needs to accept the new parameters and forward them. This is a mechanical but repo-wide change — grep for `createSession(` before merging Phase 5.

---

## Phase 6: SessionTracker with RAM-first / DB-fallback

**Datei:** `app/src/main/java/net/devemperor/dictate/core/SessionTracker.kt`

```kotlin
package net.devemperor.dictate.core

import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionType

/**
 * Tracks the current session and caches the last keyboard-initiated session.
 *
 * Strategy for getLastKeyboardSession:
 * 1. RAM cache hit → return immediately
 * 2. DB query for the latest session with origin = KEYBOARD → populate cache
 * 3. Still null → return null
 *
 * The cache is invalidated on session deletion and repopulated lazily on next access.
 */
class SessionTracker(
    private val sessionManager: SessionManager,
    private val sessionDao: SessionDao
) {
    @Volatile var currentSessionId: String? = null
        private set
    @Volatile var currentStepId: String? = null
        private set
    @Volatile var currentTranscriptionId: String? = null
        private set

    @Volatile private var cachedLastKeyboardSession: SessionEntity? = null

    fun startSession(...) { /* unchanged except for removing Pref.LastSessionId */ }

    /**
     * Returns the last session that was initiated from the keyboard,
     * or null if no such session exists.
     *
     * RAM-first, DB-fallback, with read-through caching.
     */
    fun getLastKeyboardSession(): SessionEntity? {
        cachedLastKeyboardSession?.let { return it }

        val fromDb = sessionDao.findLatestByOrigin(SessionOrigin.KEYBOARD.name)
        cachedLastKeyboardSession = fromDb
        return fromDb
    }

    /**
     * Called by the pipeline after a new keyboard session finishes.
     * Updates the RAM cache — the DB was already written by the pipeline.
     */
    fun notifyKeyboardSessionCompleted(session: SessionEntity) {
        cachedLastKeyboardSession = session
    }

    /**
     * Invalidates the cache — call after a session is deleted from history.
     */
    fun invalidateLastKeyboardCache() {
        cachedLastKeyboardSession = null
    }

    fun resetSession() {
        currentSessionId = null
        currentStepId = null
        currentTranscriptionId = null
        // Note: no more Pref.LastSessionId write — DB is source of truth
    }

    // ── Removed methods ──
    // restoreLastSessionIdFromPrefs(sp)  ← replaced by getLastKeyboardSession() DB fallback
    // restoreLastOutputFromDb()          ← no longer needed (no lastOutput field)
    // persistToPrefs(sp)                 ← Pref.LastSessionId is removed entirely
    // reuseLastSession()                 ← callers use getLastKeyboardSession().id instead
}
```

### 6.1 Deprecated APIs (Phase 6) and call-site migration (Phase 9 / Chunk 3)

**Deprecation-first strategy:** In Phase 6 (Chunk 1), old API methods are marked as `@Deprecated` but **not deleted**. This ensures Chunk 1 (Phases 0, 1, 2, 6) compiles independently without requiring changes to `DictateInputMethodService`. The actual call-site migration and method removal happen in Phase 9 / Chunk 3.

**In `SessionTracker.kt` (Phase 6 — deprecate only):**

| Deprecated | Replacement | Removed in |
|------------|-------------|------------|
| `lastSessionId` (volatile field) | `getLastKeyboardSession()?.id` | Phase 9 |
| `lastOutput` (volatile field) | `getLastKeyboardSession()?.finalOutputText` | Phase 9 |
| `restoreLastSessionIdFromPrefs(sp)` | No-op — DB is source of truth | Phase 9 |
| `restoreLastOutputFromDb()` | No-op — `getLastKeyboardSession()` reads it lazily | Phase 9 |
| `persistToPrefs(sp)` | No-op — no Pref write needed | Phase 9 |
| `reuseLastSession()` | `currentSessionId = getLastKeyboardSession()?.id` at call site | Phase 9 |

```kotlin
// Example deprecation annotation (Phase 6):
@Deprecated(
    "Use getLastKeyboardSession()?.id instead",
    ReplaceWith("getLastKeyboardSession()?.id")
)
fun getLastSessionId(): String? = lastSessionId
```

**In `DictatePrefs.kt` (Phase 6 — deprecate only):**

| Deprecated | Replacement | Removed in |
|------------|-------------|------------|
| `object LastSessionId : Pref<String>(...)` | (none — delete entirely) | Phase 9 |

**In `DictateInputMethodService.java` (Phase 9 — actual migration):**

Grep for the following and remove/replace:
- `sessionTracker.restoreLastSessionIdFromPrefs(sp);` → remove
- `sessionTracker.restoreLastOutputFromDb();` → remove
- `sessionTracker.persistToPrefs(sp);` → remove
- `sessionTracker.reuseLastSession();` → replaced by direct use of `getLastKeyboardSession()`
- `sessionTracker.getLastOutput()` → `sessionTracker.getLastKeyboardSession() != null ? ... : null`

After all call sites are migrated in Phase 9, the deprecated methods and `Pref.LastSessionId` are deleted in the same PR.

**Migration note:** The `Pref.LastSessionId` entry in existing user installations will simply be ignored after the update. No cleanup migration needed — SharedPreferences values for obsolete keys do not hurt, and removing the class is enough.

**Datei:** `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`

```kotlin
// Phase 6: DEPRECATE (keep the object, add @Deprecated)
// Phase 9: REMOVE entirely
// object LastSessionId : Pref<String>("net.devemperor.dictate.last_session_id", "")
```

---

## Phase 7: KeyboardUiController — ReprocessStaging State

**Naming note:** `KeyboardUiController.PipelineConfig` (already implemented in code, Commit 05c7278+) has been renamed to `KeyboardUiController.AutoEnterConfig` to avoid confusion with `PipelineOrchestrator.PipelineConfig`. The two types serve different purposes:
- `AutoEnterConfig` (UI): Holds the `autoEnterActive` flag for the keyboard record button rendering.
- `PipelineOrchestrator.PipelineConfig` (Orchestrator): Holds audio file, language, origin, model override, etc. for pipeline execution.

### 7.1 Extended PipelineUiState

**Datei:** `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`

```kotlin
package net.devemperor.dictate.core

/**
 * UI state for the record button and prompt area.
 *
 * Follows the sealed-class state pattern documented in KeyboardUiController.
 * Additions to this class must update [KeyboardUiController.refreshRecordButtonFromState].
 */
sealed class PipelineUiState {
    object Idle : PipelineUiState()
    object Preparing : PipelineUiState()

    data class Running(
        val totalSteps: Int,
        val completedSteps: Int,
        val currentStepName: String,
        val autoEnterActive: Boolean,
        val hasFailure: Boolean = false  // Already implemented (Commit 8273403). Used in refreshRecordButtonFromState() for red text color on failures.
    ) : PipelineUiState()

    /**
     * NEW — The user has triggered long-press on resend, a previous keyboard
     * session has been loaded, and the user is now editing the prompt queue
     * and/or language before starting a reprocess.
     *
     * In this state:
     * - The large record button shows "Audio X:YY · Senden" and acts as a send trigger
     * - The pause button in the input row is disabled ("blind")
     * - The trash button in the input row cancels the reprocess and returns to Idle
     * - The prompt bar shows the editable queue with a language chip
     *
     * Forward-compatibility note: [selectedModel] is persisted in the state but
     * not currently editable in the UI. The data path (state → JobRequest →
     * PipelineConfig → PipelineOrchestrator) is prepared so a future UI can
     * expose a model-selector chip without touching any plumbing. Default is
     * null, which means "use the provider's default from Prefs".
     */
    data class ReprocessStaging(
        val targetSessionId: String,
        val audioDurationSeconds: Long,
        val editableQueue: List<Int>,       // prompt entity IDs
        val selectedLanguage: String?,
        val selectedModel: String? = null,  // future-proofing — not editable in UI yet
        val isStarting: Boolean = false     // becomes true after Send pressed, before Registry-Running
    ) : PipelineUiState()
}
```

### 7.2 KeyboardUiController render logic

**KeyboardStateManager integration (Finding SEC-7-1):**

`KeyboardStateManager.refresh()` drives visibility via lambdas (`isPipelineRunning`, `isRecording`, `isPipelineProgressVisible`, etc.). None covers `ReprocessStaging`. Without a new lambda, `refresh()` resets the visibility that `applyReprocessStagingState()` just set.

**Required change in `KeyboardStateManager` constructor — add new lambda:**

```kotlin
class KeyboardStateManager(
    // ... existing lambdas ...
    private val isPipelineRunning: () -> Boolean,
    private val isPipelineProgressVisible: () -> Boolean,
    private val isReprocessStaging: () -> Boolean,  // NEW (Finding SEC-7-1)
    // ...
)
```

**Required changes in `KeyboardStateManager` visibility methods:**

```kotlin
// In applyRecordingControlsVisibility():
private fun applyRecordingControlsVisibility() {
    val isActive = isRecording() || isPaused()
    val isStaging = isReprocessStaging()
    // Pause button: visible during recording, DISABLED during ReprocessStaging
    views.pauseButton.visibility = if (isActive || isStaging) View.VISIBLE else View.GONE
    views.pauseButton.isEnabled = isActive  // disabled (blind) in ReprocessStaging
    views.pauseButton.alpha = if (isActive) 1.0f else 0.4f
    // Trash button: visible during recording AND ReprocessStaging (cancel action)
    views.trashButton.visibility = if (isActive || isStaging) View.VISIBLE else View.GONE
}

// In applyPromptsVisibility() — add ReprocessStaging to the showPrompts condition:
val showPrompts = when {
    isSmallMode -> false
    contentArea == ContentArea.EMOJI_PICKER -> false
    isActive || isPipelineRunning() || isReprocessStaging() -> true  // CHANGED: added isReprocessStaging()
    else -> isRewordingEnabled()
}
// Pipeline progress vs prompts RecyclerView:
// ReprocessStaging shows the RecyclerView (queue editing), NOT pipeline progress
val isPipelineProgress = isPipelineProgressVisible() && !isReprocessStaging()
```

**Lambda wiring in `DictateInputMethodService` (where KeyboardStateManager is created):**

```java
// Add to the KeyboardStateManager constructor call:
/* isReprocessStaging */ () -> uiController != null
    && uiController.getState() instanceof PipelineUiState.ReprocessStaging,
```

This ensures that `refresh()` — which is called on rotation, content-area switches, and layout rebuilds — preserves the correct visibility for ReprocessStaging without being overwritten

Add a new branch in `refreshRecordButtonFromState()`:

```kotlin
is PipelineUiState.ReprocessStaging -> {
    views.recordButton.isEnabled = true
    views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
        R.drawable.ic_baseline_play_arrow_24,  // left: play icon (audio exists)
        0,
        R.drawable.ic_baseline_send_24,         // right: send icon (action hint)
        0
    )
    val durationStr = formatDurationMinSec(s.audioDurationSeconds)
    views.recordButton.text = "Audio $durationStr · Senden"
    views.recordButton.setTextColor(Color.WHITE)
}
```

### 7.3 New state-mutation methods

```kotlin
/**
 * Enters ReprocessStaging mode with the given session as reference.
 * Called by DictateInputMethodService.onResendLongClicked().
 */
fun enterReprocessStaging(
    targetSessionId: String,
    audioDurationSeconds: Long,
    initialQueue: List<Int>,
    language: String?
) {
    updatePipelineState(PipelineUiState.ReprocessStaging(
        targetSessionId = targetSessionId,
        audioDurationSeconds = audioDurationSeconds,
        editableQueue = initialQueue,
        selectedLanguage = language
    ))
}

/**
 * Cancels the ReprocessStaging (trash button pressed). Returns to Idle.
 */
fun cancelReprocessStaging() {
    if (state is PipelineUiState.ReprocessStaging) {
        updatePipelineState(PipelineUiState.Idle)
    }
}

/**
 * Updates the editable queue (when user toggles a prompt chip).
 */
fun updateReprocessQueue(queue: List<Int>) {
    val s = state
    if (s is PipelineUiState.ReprocessStaging) {
        updatePipelineState(s.copy(editableQueue = queue))
    }
}

/**
 * Updates the selected language (when user picks from the language chip dropdown).
 */
fun updateReprocessLanguage(language: String) {
    val s = state
    if (s is PipelineUiState.ReprocessStaging) {
        updatePipelineState(s.copy(selectedLanguage = language))
    }
}
```

### 7.3.1 Convenience query methods

```kotlin
/**
 * Returns true if the controller is in any non-Idle state.
 *
 * Comparison with related methods:
 * - [isPipelineRunning]: true only for [PipelineUiState.Running]
 * - [isPipelineActive]: true for [Running] + [Preparing]
 * - [isBusy]: true for everything except [Idle] (includes [ReprocessStaging])
 *
 * Use [isBusy] when guarding against any user interaction that requires
 * an idle keyboard (e.g. starting a new recording, entering ReprocessStaging).
 */
fun isBusy(): Boolean = state !is PipelineUiState.Idle
```

### 7.4 MainButtonsController — ReprocessStaging button state

**Datei:** `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt`

The `MainButtonsController` observes `uiController.state` via `onPipelineUiStateChanged` callback. When `newState is ReprocessStaging`, it calls `applyReprocessStagingState()`:

```kotlin
/**
 * Configures button states for [PipelineUiState.ReprocessStaging].
 *
 * Triggered by: [onPipelineUiStateChanged] in the service when the state
 * transitions to ReprocessStaging.
 */
fun applyReprocessStagingState() {
    // Pause-Button: disabled/invisible (no recording to pause)
    views.pauseButton.isEnabled = false
    views.pauseButton.alpha = 0.4f  // visual "blind" indication

    // Trash-Button: visible, clickable → cancels ReprocessStaging
    views.trashButton.visibility = View.VISIBLE
    views.trashButton.isEnabled = true

    // QWERTZ-Button: remains in Idle-look (no ReprocessStaging indicator)
}

/**
 * Resets all buttons to their normal (Idle) state.
 *
 * Called when:
 * - [cancelReprocessStaging] returns to Idle
 * - [stopPipeline] completes a running job
 */
fun resetToNormalState() {
    views.pauseButton.isEnabled = true
    views.pauseButton.alpha = 1.0f
    views.trashButton.visibility = View.GONE
    // ... restore existing button state logic
}

/**
 * General state dispatcher. Called by the service on every state transition.
 */
fun applyStateToButtons(state: PipelineUiState) {
    when (state) {
        is PipelineUiState.ReprocessStaging -> applyReprocessStagingState()
        is PipelineUiState.Idle -> resetToNormalState()
        else -> {
            views.pauseButton.isEnabled = true
            views.pauseButton.alpha = 1.0f
            // ... existing button state logic for Running/Preparing
        }
    }
}
```

---

## Phase 8: Language Chip in Prompt Bar

### 8.1 Layout changes

**Datei:** `app/src/main/res/layout/activity_dictate_keyboard_view.xml`

Add a language chip container as the first scroll item inside `prompts_keyboard_rv`. Since `prompts_keyboard_rv` is a RecyclerView, this is done by making the language chip the item at position 0 with a special item-type in the adapter.

**Strategy (chosen: NF7 Option B — part of the scroll):**

Modify `PromptsKeyboardAdapter` to support a header item type `VIEW_TYPE_LANGUAGE_CHIP` at position 0, and regular prompts from position 1 onward. The language chip is only rendered when the controller is in `ReprocessStaging` state.

### 8.2 PromptsKeyboardAdapter changes

**Datei:** `app/src/main/java/net/devemperor/dictate/rewording/PromptsKeyboardAdapter.java`

```java
private static final int VIEW_TYPE_LANGUAGE_CHIP = 0;
private static final int VIEW_TYPE_PROMPT = 1;

private boolean showLanguageChip = false;
private String currentLanguage = null;
private LanguageChipClickListener languageChipListener = null;

public void setLanguageChipVisible(boolean visible, String language) {
    boolean changed = (this.showLanguageChip != visible);
    this.showLanguageChip = visible;
    this.currentLanguage = language;
    if (changed) notifyDataSetChanged();
}

@Override
public int getItemViewType(int position) {
    if (showLanguageChip && position == 0) return VIEW_TYPE_LANGUAGE_CHIP;
    return VIEW_TYPE_PROMPT;
}

@Override
public int getItemCount() {
    return (showLanguageChip ? 1 : 0) + prompts.size();
}

// Finding SEC-7-5: When the language chip is at position 0, all subsequent
// data.get(position) calls are off-by-one. A helper method centralizes the
// offset calculation so no call site forgets to adjust.
private int toDataIndex(int adapterPosition) {
    return adapterPosition - (showLanguageChip ? 1 : 0);
}

// ...onCreateViewHolder and onBindViewHolder branch on viewType
// All data access in onBindViewHolder MUST use toDataIndex(position):
//   Prompt prompt = data.get(toDataIndex(position));
// The same applies to setQueuedPromptOrder() and all click callbacks.

// StaggeredGridLayout: The language chip must use FullSpan so it spans
// the full width rather than occupying one grid column:
//   StaggeredGridLayoutManager.LayoutParams lp = ...;
//   lp.setFullSpan(viewType == VIEW_TYPE_LANGUAGE_CHIP);
```

The language chip opens a `MaterialAlertDialog` or `BottomSheet` showing all available languages from `R.array.dictate_input_languages`. Selecting one triggers `uiController.updateReprocessLanguage(lang)`.

---

## Phase 9: Resend Button Rewrite

**Datei:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`

### 9.1 onResendClicked (short press) — status-based dispatch

```java
@Override
public void onResendClicked() {
    SessionEntity lastSession = sessionTracker.getLastKeyboardSession();
    if (lastSession == null) {
        // No previous keyboard session — nothing to do
        return;
    }

    SessionStatus status = lastSession.getStatusEnum();
    switch (status) {
        case COMPLETED:
            // Happy path — re-insert the existing final output text
            String output = lastSession.getFinalOutputText();
            if (output != null && !output.isEmpty()) {
                commitTextToInputConnection(output, InsertionSource.TRANSCRIPTION);
            }
            break;

        case RECORDED:
        case FAILED:
            // Error recovery — resume the pipeline from the failure point
            startResumeJob(lastSession.getId());
            break;

        case CANCELLED:
            // Finding SEC-7-8: For CANCELLED sessions, short-press inserts
            // the last available output (if any) rather than resuming.
            // This prevents accidental costly API calls from a quick tap
            // (long-press is the intentional reprocess gesture).
            String cancelledOutput = lastSession.getFinalOutputText();
            if (cancelledOutput != null && !cancelledOutput.isEmpty()) {
                commitTextToInputConnection(cancelledOutput, InsertionSource.TRANSCRIPTION);
            } else {
                // No output available — fall back to resume
                startResumeJob(lastSession.getId());
            }
            break;
    }
}

/**
 * Job-Blocking UX (Keyboard): When JobExecutor.start() would return false
 * (because a job is already active), show an InfoBar toast via InfoBarController.
 * This is the keyboard's strategy for communicating job-blocking to the user.
 */
private void startResumeJob(String sessionId) {
    if (ActiveJobRegistry.INSTANCE.isAnyActive()) {
        showInfoBar(R.string.dictate_job_already_active);
        return;
    }

    // Finding CA-4: Type-safe sealed class — no nullable fields needed
    JobRequest.Resume request = new JobRequest.Resume(
        sessionId,
        /* totalSteps */ computeRemainingSteps(sessionId)
    );
    JobExecutor.INSTANCE.start(this, request);
}
```

### 9.2 onResendLongClicked — open ReprocessStaging

```java
@Override
public void onResendLongClicked() {
    // Guard: isBusy() covers Running, Preparing, AND ReprocessStaging
    if (uiController.isBusy()) {
        return;
    }

    SessionEntity lastSession = sessionTracker.getLastKeyboardSession();
    if (lastSession == null) {
        return;  // no audio to reprocess
    }

    // Verify audio file still exists
    RecordingRepository.LoadResult result = recordingRepository.loadBySessionId(lastSession.getId());
    if (!(result instanceof RecordingRepository.LoadResult.Available)) {
        showInfoBar(R.string.dictate_audio_file_missing);
        return;
    }

    // Load historical queue
    List<Integer> historicalQueue = sessionManager.getHistoricalQueuedPromptIds(lastSession.getId());

    // Enter ReprocessStaging state
    uiController.enterReprocessStaging(
        lastSession.getId(),
        lastSession.getAudioDurationSeconds(),
        historicalQueue,
        lastSession.getLanguage()
    );
}
```

### 9.3 Send button handler in ReprocessStaging

A new handler for when the big record button is pressed while in `ReprocessStaging`:

```java
public void onRecordClicked() {
    PipelineUiState state = uiController.getState();

    if (state instanceof PipelineUiState.ReprocessStaging) {
        handleReprocessSend((PipelineUiState.ReprocessStaging) state);
        return;
    }

    // ... existing logic for Idle/Running
}

private void handleReprocessSend(PipelineUiState.ReprocessStaging staging) {
    // Finding CA-4: Type-safe sealed class variant
    JobRequest.TranscriptionPipeline request = new JobRequest.TranscriptionPipeline(
        staging.getTargetSessionId(),
        computeTotalStepsForQueue(staging.getEditableQueue()),
        JobRequest.TranscriptionKind.REPROCESS_STAGING,
        /* audioFilePath */ sessionManager.getAudioFilePath(staging.getTargetSessionId()),
        staging.getSelectedLanguage(),
        /* modelOverride */ staging.getSelectedModel(),
        staging.getEditableQueue(),
        /* targetAppPackage */ getCurrentTargetApp()
    );
    boolean started = JobExecutor.INSTANCE.start(this, request);
    if (!started) {
        showInfoBar(R.string.dictate_job_already_active);
    }
    // Finding SEC-7-6: The transition from ReprocessStaging → Running
    // cannot rely on a non-existent StateFlow observer in the existing
    // imperative architecture. Instead, call preparePipeline() explicitly
    // (consistent with the existing pattern in the recording flow).
    if (started) {
        uiController.preparePipeline();
    }
}
```

### 9.4 Trash button in ReprocessStaging

```java
@Override
public void onTrashClicked() {
    if (uiController.getState() instanceof PipelineUiState.ReprocessStaging) {
        uiController.cancelReprocessStaging();
        return;
    }

    // ... existing cancel-recording logic
}
```

### 9.5 Cancel during a running job (any kind)

The existing `onPipelineCancelClicked()` (called from the cancel button shown in the prompt area during a running pipeline) must be updated to integrate with the new architecture. The handler:

```java
public void onPipelineCancelClicked() {
    String sessionId = sessionTracker.getCurrentSessionId();
    if (sessionId == null) return;

    // 1. Cancel via cooperative token + last-resort interrupt
    JobExecutor.INSTANCE.cancel(sessionId);

    // 2. Mark the session as CANCELLED in the DB
    // Finding CA-1: finalizeCancelled also clears last_error_type and
    // last_error_message to prevent inconsistent state if finalizeFailed
    // was called first in a race condition.
    sessionManager.finalizeCancelled(sessionId);

    // 3. Finding CA-1: Do NOT call ActiveJobRegistry.unregister() here.
    // The JobExecutor's finally block handles unregistration. Double
    // unregistration is harmless but unnecessary and misleading.

    // 4. Reset UI state to Idle
    uiController.stopPipeline();
}
```

**Cancellation strategy:** `JobExecutor.cancel()` first sets the cooperative `CancellationToken` flag, then sends `Thread.interrupt()` as a last-resort for blocking OkHttp calls. The orchestrator checks `token.throwIfCancelled()` at defined checkpoints (before each step, after each API call), so cancellation is typically handled cooperatively without thread interruption.

**Race-condition note (Finding CA-1):** The cancel flow is:

1. `cancel()` sets the cooperative token + sends interrupt
2. `finalizeCancelled()` writes CANCELLED status + clears `last_error_type` and `last_error_message` (prevents inconsistent state if `finalizeFailed` was called first by the worker thread)
3. `unregister()` is NOT called here — the `JobExecutor`'s `finally` block handles it, preventing double-unregistration
4. In the `JobExecutor.start()` catch block, `token.isCancelled` is checked before calling `finalizeFailed()` — if the token was cancelled, the worker thread skips `finalizeFailed()` entirely

There is a small window where `JobExecutor.start()` checks `ActiveJobRegistry.isAnyActive()` and finds the just-cancelled session still registered. The user-facing impact: the user must wait a few hundred milliseconds before starting a new job. This is acceptable.

### 9.5.1 Removed APIs in DictateInputMethodService

Alongside the new resend logic, the following obsolete code paths in `DictateInputMethodService.java` must be removed in the same PR:

| Removed | Reason |
|---------|--------|
| Old `onResendClicked()` body with `sessionTracker.getLastOutput()` | Replaced by status-based dispatching |
| Old `onResendLongClicked()` direct-call to `runTranscriptionViaOrchestrator()` | Replaced by `uiController.enterReprocessStaging(...)` |
| Field `audioFile = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE))` inside `onResendLongClicked` | Replaced by `RecordingRepository.loadBySessionId()` — the cache file no longer exists after "Persist first" |
| `sessionTracker.reuseLastSession()` | Reprocess now goes through `JobExecutor.start(...)` with the existing `sessionId`, no "reuse" concept needed |
| References to `Pref.LastFileName` outside the recording flow | `Pref.LastFileName` stays (it tracks the currently active recording cache file), but it's no longer the canonical reference for "last session" |

**In `MainButtonsController.kt`:**

| Removed | Reason |
|---------|--------|
| `reRegisterRecordButtonListener()` | The record button listener is set once in `onCreateInputView` and never swapped. The old code swapped it when entering/leaving the auto-enter-toggle state, but the new State-Dispatcher pattern (Phase 7.2 + Phase 9.3) dispatches by state without listener swapping. |

If `reRegisterRecordButtonListener()` is referenced anywhere else in the codebase, those references must also be removed.

### 9.6 Auto-format setting on resume

When `resumePipeline` runs, it must decide which auto-format setting to honour: the **historical** one (what was set when the session was originally recorded) or the **current** one (what is set in Prefs right now).

**Decision: current setting always wins.** Justification:

- The Prefs are not snapshotted per-session — there is no way to know historically what the user had set.
- If the user disabled auto-format since the original run, they probably don't want it on the retry either.
- Reading Prefs at execution time is the existing behaviour of `AutoFormattingService.formatIfEnabled()` — `resumePipeline` does not need to do anything special, it just calls the same service.

**Implementation note:** This means `resumePipeline` does NOT need a special parameter for auto-format. It runs the same code path as a normal pipeline run, which already reads `Pref.AutoFormatting` lazily.

**Edge case — auto-format was on, ran successfully, user later disables it, then resumes from a later step:**

- Step 0 (transcription): version 1, current
- Step 1 (auto-format): version 1, current — succeeded with old setting
- Step 2 (prompt): version 1 — failed
- User flips Prefs.AutoFormatting to false
- User presses short-press resend → `resumePipeline`
- Resume sees: transcription is current, step 1 (auto-format) is current, step 2 failed
- Resume starts from chainIndex=2 — does NOT re-run auto-format
- Step 2 is run on the input from step 1 (the formatted text)

This is the correct behaviour: we resume from where we left off, not from where the current settings would have started us. The user's old auto-formatted text is preserved as the input for step 2.

**Counter-example — fresh run after the setting change:**

If the user starts a brand new recording after disabling auto-format, the new pipeline correctly skips auto-format from the start. Only `resumePipeline` ignores the change for already-completed steps.

---

## Phase 10: History UI — Status Badges & Detail Buttons

### 10.1 HistoryAdapter — status badges

**Datei:** `app/src/main/java/net/devemperor/dictate/history/HistoryAdapter.java`

```java
@Override
public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    SessionEntity session = data.get(position);

    // ... existing icon, date, preview logic ...

    // NEW: status badge
    SessionStatus status = session.getStatusEnum();
    switch (status) {
        case COMPLETED:
            holder.statusIcon.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.GONE);
            break;
        case RECORDED:
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_baseline_pending_24);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setText(R.string.dictate_status_recorded);
            break;
        case FAILED:
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_baseline_error_outline_24);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setText(R.string.dictate_status_failed);
            break;
        case CANCELLED:
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_baseline_cancel_24);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setText(R.string.dictate_status_cancelled);
            break;
    }

    // NEW: runtime overlay from ActiveJobRegistry
    if (ActiveJobRegistry.INSTANCE.isActive(session.getId())) {
        holder.statusIcon.setImageResource(R.drawable.ic_baseline_sync_24);
        holder.statusText.setText(R.string.dictate_status_running);
    }
}
```

### 10.2 item_history_session.xml

Add an `ImageView` (24dp) and a `TextView` next to the existing subtitle, inside a horizontal `LinearLayout`:

```xml
<LinearLayout orientation="horizontal" ...>
    <TextView id="@+id/item_history_subtitle_tv" ... />
    <View android:layout_width="8dp" android:layout_height="1dp" />
    <ImageView id="@+id/item_history_status_icon"
               android:layout_width="16dp" android:layout_height="16dp"
               android:visibility="gone" />
    <View android:layout_width="4dp" android:layout_height="1dp" />
    <TextView id="@+id/item_history_status_tv"
              android:textSize="12sp"
              android:visibility="gone" />
</LinearLayout>
```

### 10.3 HistoryDetailActivity — three new buttons

**Datei:** `app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java`

Add three new buttons to the AUDIO row of the pipeline adapter:

- **"Direkt ausführen"** (`ic_baseline_play_arrow_24`) — triggers a `JobKind.RESUME` or full `HISTORY_REPROCESS` with the historical queue (no user interaction).
- **"Erneut verarbeiten"** (`ic_baseline_edit_note_24`) — opens a `PromptChooserBottomSheetV2` where the user can edit the queue before starting.
- **"Audio löschen"** (`ic_baseline_delete_24`) — deletes the audio file permanently while keeping session, transcription, and processing results. After deletion, all reprocess buttons are hidden (because `LoadResult.FileMissing`).

```java
@Override
public void onPlayAudio(String audioFilePath) { /* existing */ }

// NEW
// Finding SEC-10-2: PipelineOrchestrator is not available in HistoryDetailActivity
// (it's currently only instantiated in DictateInputMethodService).
// Solution: JobExecutor holds a lazy-initialized PipelineOrchestrator internally.
// See Phase 4.2 — JobExecutor.start() no longer takes an orchestrator parameter.
@Override
public void onDirectReprocess(String sessionId) {
    JobRequest request = buildHistoryReprocessRequest(sessionId, /* editedQueue */ null);
    JobExecutor.INSTANCE.start(this, request);
}

// NEW
@Override
public void onReprocessWithEdit(String sessionId) {
    PromptChooserBottomSheetV2.newInstance(sessionId)
        .show(getSupportFragmentManager(), "reprocess_chooser");
}

// NEW
@Override
public void onDeleteAudio(String sessionId) {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dictate_delete_audio_title)
        .setMessage(R.string.dictate_delete_audio_message)
        .setPositiveButton(R.string.dictate_delete_audio_confirm, (dialog, which) -> {
            boolean deleted = recordingRepository.deleteBySessionId(sessionId);
            if (deleted) {
                loadSession();  // Refresh — reprocess buttons will hide via FileMissing
            }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
}

public void onReprocessQueueConfirmed(String sessionId, List<Integer> editedQueue) {
    JobRequest request = buildHistoryReprocessRequest(sessionId, editedQueue);
    JobExecutor.INSTANCE.start(this, request);
}
```

### 10.4 PipelineStepAdapter — AUDIO row extensions

Add `showDirectReprocess`, `showReprocessWithEdit`, and `showDeleteAudio` builder fields, and render button visibility on the AUDIO row accordingly. Visibility logic:

- `showDirectReprocess = true` if session status in (`RECORDED`, `FAILED`, `CANCELLED`)
- `showReprocessWithEdit = true` always for RECORDING-type sessions (also `COMPLETED` — user can experiment)
- `showDeleteAudio = true` if audio file exists (i.e. `LoadResult.Available`)
- Hide all three if audio file is missing (`RecordingRepository.LoadResult.FileMissing`)
- Hide reprocess buttons (but not delete) if `ActiveJobRegistry.isActive(sessionId)`

### 10.5 HistoryDetailViewModel

**Datei:** `app/src/main/java/net/devemperor/dictate/history/HistoryDetailViewModel.kt`

**Job-Blocking UX (History):** The ViewModel observes `ActiveJobRegistry.state` and exposes it as part of `DisplayState`. When a job is active, the `HistoryDetailActivity` automatically disables all reprocess buttons (see 10.4 visibility logic: "Hide reprocess buttons if `ActiveJobRegistry.isActive(sessionId)`"). This is reactive -- no polling or manual enable/disable calls needed.

```kotlin
package net.devemperor.dictate.history

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import net.devemperor.dictate.core.ActiveJobRegistry
import net.devemperor.dictate.core.JobState
import net.devemperor.dictate.database.entity.SessionEntity

/**
 * Session-scoped ViewModel for HistoryDetailActivity.
 * Observes ActiveJobRegistry to show live progress updates during reprocessing.
 */
class HistoryDetailViewModel(val sessionId: String) : ViewModel() {

    /**
     * Finding SA-7/SEC-10-5: Factory is required because the constructor takes
     * a sessionId parameter. This is the first ViewModel in the project and
     * sets the pattern for future ViewModels.
     *
     * Usage in HistoryDetailActivity:
     *   val vm = ViewModelProvider(this, HistoryDetailViewModel.Factory(sessionId))
     *       .get(HistoryDetailViewModel::class.java)
     */
    class Factory(private val sessionId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryDetailViewModel(sessionId) as T
        }
    }

    private val _session = MutableStateFlow<SessionEntity?>(null)
    val session: StateFlow<SessionEntity?> = _session.asStateFlow()

    /** Combined state: DB session + runtime job state (if active). */
    val displayState: StateFlow<DisplayState> = combine(
        session,
        ActiveJobRegistry.state
    ) { s, jobs ->
        val runtime = jobs[sessionId] as? JobState.Running
        when {
            s == null -> DisplayState.Loading
            runtime != null -> DisplayState.Running(s, runtime)
            else -> DisplayState.Static(s)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DisplayState.Loading)

    fun loadSession(dao: SessionDao) {
        _session.value = dao.getById(sessionId)
    }

    sealed class DisplayState {
        object Loading : DisplayState()
        data class Static(val session: SessionEntity) : DisplayState()
        data class Running(val session: SessionEntity, val job: JobState.Running) : DisplayState()
    }
}
```

### 10.6 PromptChooserBottomSheetV2

**Datei:** `app/src/main/java/net/devemperor/dictate/history/PromptChooserBottomSheetV2.kt`

**Purpose:** Queue-editor for reprocessing from the History detail view. Unlike V1 (selection-only with checkboxes), V2 is a full queue editor with ordering, add, and remove capabilities.

**UI Elements:**

- `RecyclerView` with drag-and-drop reordering (via `ItemTouchHelper` with `UP`/`DOWN` directions)
- Each prompt item shows: prompt name, a drag handle (left), and a remove button (right, `ic_baseline_close_24`)
- **"Prompt hinzufugen"** button at the bottom — opens a prompt selection popup (filtered to prompts not yet in the queue)
- **"Verarbeiten"** confirmation button — commits the edited queue and triggers reprocessing

**Data source:**

- Initial queue is populated from `session.queued_prompt_ids` (the prompt IDs used during the last run)
- If `queued_prompt_ids` is null (legacy session), the queue starts empty and the user adds prompts manually

**Callback:**

```kotlin
interface ReprocessQueueCallback {
    fun onReprocessQueueConfirmed(sessionId: String, editedQueue: List<Int>)
}
```

The host `HistoryDetailActivity` implements this callback. On confirmation, it builds a `JobRequest(kind = HISTORY_REPROCESS, ...)` with the edited queue and passes it to `JobExecutor.start()`.

**Difference to V1:** `PromptChooserBottomSheet` (V1) is a simple checkbox-based multi-select used during normal recording flow. V2 adds: (1) ordered queue semantics, (2) drag-to-reorder, (3) individual remove, (4) add-from-remaining. V1 remains unchanged for its existing use case.

---

## Phase 11: String Resources

**Datei:** `app/src/main/res/values/strings.xml`

```xml
<!-- Status badges -->
<string name="dictate_status_recorded">Nicht verarbeitet</string>
<string name="dictate_status_failed">Fehlgeschlagen</string>
<string name="dictate_status_cancelled">Abgebrochen</string>
<string name="dictate_status_running">Wird verarbeitet…</string>

<!-- History detail actions -->
<string name="dictate_history_direct_reprocess">Direkt ausführen</string>
<string name="dictate_history_reprocess_edit">Erneut verarbeiten</string>

<!-- Reprocess staging -->
<string name="dictate_reprocess_audio_available">Audio %1$s · Senden</string>
<string name="dictate_job_already_active">Verarbeitung läuft bereits</string>
<string name="dictate_audio_file_missing">Audio-Datei nicht mehr verfügbar</string>

<!-- Delete audio -->
<string name="dictate_delete_audio_title">Audio löschen</string>
<string name="dictate_delete_audio_message">Audio wird unwiderruflich gelöscht. Transkription und Ergebnisse bleiben erhalten.</string>
<string name="dictate_delete_audio_confirm">Löschen</string>
```

Translations for `values-de`, `values-es`, `values-pt` analog.

---

## Phase 12: New Material Icons

Add the following Material icon drawables under `app/src/main/res/drawable/`:

- `ic_baseline_pending_24.xml` — RECORDED badge
- `ic_baseline_error_outline_24.xml` — FAILED badge
- `ic_baseline_cancel_24.xml` — CANCELLED badge
- `ic_baseline_sync_24.xml` — Running badge (can be animated via `AnimatedVectorDrawable` if desired)
- `ic_baseline_edit_note_24.xml` — "Erneut verarbeiten" button
- `ic_baseline_delete_24.xml` — "Audio löschen" button

Use the standard Material Icons set from [fonts.google.com/icons](https://fonts.google.com/icons) and export as Android Vector Drawables.

---

## Phase 13: Wire-up & Integration Tests

### 13.1 DictateInputMethodService initialization

The service needs to:
- Instantiate `RecordingRepository` and pass it into `PipelineOrchestrator`
- Observe `ActiveJobRegistry.state` to reflect running-job status in the keyboard UI (if desired for parallel-job-blocking feedback)
- Initialise `SessionTracker` with the new `SessionDao` dependency
- Remove all references to `Pref.LastSessionId` and `restoreLastSessionIdFromPrefs`

### 13.2 Tests

| Test | File | What it verifies |
|------|------|------------------|
| Migration 2→3 | `MigrationTest.kt` | Schema upgrade preserves data, sets status correctly |
| Double-Enum CHECK constraint | `SessionStatusCheckTest.kt` | Inserting an invalid status string throws |
| Double-Enum CHECK constraint | `SessionErrorTypeCheckTest.kt` | Inserting an invalid error_type string throws (Finding SEC-1-2) |
| Orphan healing | `DurationHealingJobTest.kt` | Rows with duration=0 get fixed from real audio files (including COMPLETED sessions — Finding SEC-0-5) |
| JobExecutor single-job lock | `JobExecutorTest.kt` | Second start() call returns false while first is running |
| Resume from mid-chain | `PipelineResumeTest.kt` | After failed step 2, resume starts at step 2 with correct input |
| SessionTracker fallback | `SessionTrackerTest.kt` | RAM-miss triggers DB query, hit caches result |
| Transcription versioning | `TranscriptionVersioningTest.kt` | Session v1 → Re-Transcribe → v2. Both versions in DB. session.final_output_text = v2. DAO returns both versions. |

---

## Verification Checklist

- [ ] Schema migration 2→3 applies cleanly on a clean DB
- [ ] Schema migration 2→3 applies cleanly on a DB with legacy orphan sessions
- [ ] Legacy orphan sessions get `status = 'RECORDED'` after migration
- [ ] Duration healing job runs on app start and fixes `audio_duration_seconds = 0`
- [ ] CHECK constraint rejects invalid status strings (integration test)
- [ ] `Pref.LastSessionId` is fully removed from codebase
- [ ] `getLastKeyboardSession()` returns null for fresh installs
- [ ] `getLastKeyboardSession()` returns correct session after DB query + caches
- [ ] A completed normal recording has `status = COMPLETED` after pipeline
- [ ] A cancelled recording has `status = CANCELLED` after user trash press
- [ ] An API error during transcription leaves session at `status = FAILED` with error context
- [ ] Short-press resend on a COMPLETED session re-inserts the text
- [ ] Short-press resend on a FAILED session resumes from the failed step
- [ ] Long-press resend opens ReprocessStaging with historical queue pre-selected
- [ ] In ReprocessStaging, the large record button shows "Audio X:YY · Senden"
- [ ] In ReprocessStaging, the pause button is disabled (blind)
- [ ] In ReprocessStaging, the trash button cancels back to Idle
- [ ] Language chip in prompt bar opens a language selection dialog
- [ ] Selecting a different language updates `UiState.ReprocessStaging.selectedLanguage`
- [ ] Pressing Send in ReprocessStaging starts a JobKind.REPROCESS_STAGING job
- [ ] Sessions marked FAILED/RECORDED/CANCELLED show the correct badge in HistoryAdapter
- [ ] Runtime-running session shows the sync badge that overrides the persisted badge
- [ ] "Direkt ausführen" button in history detail starts a RESUME job
- [ ] "Erneut verarbeiten" button opens the edit bottom-sheet
- [ ] Only one job can be active at a time (second start attempt blocked)
- [ ] Active job survives Activity destroy (HistoryDetailActivity back press)
- [ ] Active job survives IME view recreation
- [ ] Active job does NOT survive app process kill (expected behaviour)
- [ ] Audio compression: m4a is compressed to opus after persist, original deleted on success
- [ ] Audio compression failure: m4a fallback is preserved, no data loss
- [ ] "Audio loschen" button shows confirmation dialog
- [ ] After audio deletion: reprocess buttons are hidden, transcription preserved
- [ ] CancellationToken: cooperative cancel stops pipeline at next checkpoint
- [ ] CancellationToken + Thread.interrupt: blocking OkHttp call is interrupted as fallback
- [ ] `isBusy()` returns true during ReprocessStaging (not just Running/Preparing)
- [ ] Deprecated SessionTracker methods still compile in Chunk 1 (no call-site changes)
- [ ] Deprecated SessionTracker methods are fully removed after Chunk 3
- [ ] Gradle dependencies for Coroutines/StateFlow/ViewModel added and compile (Finding SEC-3-1)
- [ ] Java consumer can observe ActiveJobRegistry via asLiveData() bridge (Finding SEC-3-1)
- [ ] DurationHealingJob runs from DictateApplication.onCreate(), NOT from onOpen (Finding SA-2)
- [ ] DurationHealingJob finds COMPLETED sessions with duration=0 too (Finding SEC-0-5)
- [ ] onOpen callback preserves createPartialUniqueIndices call (Finding SEC-1-3)
- [ ] SessionErrorType Kotlin enum matches SQL CHECK constraint values (Finding SEC-1-2)
- [ ] JobExecutor.initialize() is called from DictateInputMethodService.onCreate() (Finding SEC-10-2)
- [ ] HistoryDetailViewModel.Factory used in HistoryDetailActivity (Finding SA-7)
- [ ] KeyboardStateManager.refresh() handles ReprocessStaging axis (Finding SEC-7-1)
- [ ] PromptsKeyboardAdapter uses toDataIndex() for all data access (Finding SEC-7-5)
- [ ] PromptChooserBottomSheetV2 supports drag-to-reorder
- [ ] PromptChooserBottomSheetV2 "Verarbeiten" button triggers reprocess with edited queue
- [ ] TranscriptionVersioningTest: re-transcribe creates version 2, both versions in DB
- [ ] Reprocess buttons in History disabled while a job is active (reactive via ViewModel)
- [ ] Keyboard shows InfoBar toast when job is blocked by active job

---

## Migration Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Migration failure on existing user DBs | Thorough MigrationTest covering pre-migration states observed in production DB (`duration=0`, orphans, parent_session_id chains) |
| CHECK constraint rejects rows due to unexpected legacy values | Migration UPDATE step coerces all non-standard values to the safest default (`COMPLETED` or `UNKNOWN`) before adding the CHECK |
| JobExecutor deadlock with existing ExecutorService in DictateInputMethodService | JobExecutor owns its own ExecutorService, separate from IME service executor — no shared state |
| User confusion about new buttons in history | German strings are concise and match existing tone; tooltips can be added later if feedback warrants |
| Language chip doesn't fit on narrow screens | Chip is part of the horizontally scrolling RecyclerView — no layout overflow |
| `Pref.LastSessionId` removal breaks existing users mid-flight | One-shot migration reads old pref (if present) into the RAM cache on first run, then ignores it. Prefs entry itself is deleted on next write cycle. |

---

## Open Decisions (for later, not blocking this plan)

- ~~Should the `RecordingRepository` eventually also handle audio compression / format conversion (m4a → opus) for storage efficiency?~~ **Stub in Chunk 1; Encoder-Wiring deferred.** `RecordingRepository.compressToOpus()` exists as a no-op stub and is NO LONGER called unconditionally from `persistFromCache()`. Once an Opus encoder (NDK libopus or `concentus`) is wired in, call-sites opt in explicitly. Silent-no-op under the name `compressToOpus` was rejected as misleading (Chunk-1 finding W2).
- Should the language chip be promoted to the normal (non-reprocess) recording UI? Not in scope now, but the architecture allows it trivially.
- ~~Should there be a "delete audio but keep session" option for privacy-conscious users?~~ **Now in scope** — see Phase 10.3, "Audio loschen" button with confirmation dialog.
- ~~Should the JobExecutor expose cancellation that actually survives `shutdownNow()` (e.g., checking a cancellation flag instead of interrupting the thread)?~~ **Now in scope** — see Phase 4, `CancellationToken`. Cooperative cancellation via volatile flag, with `Thread.interrupt()` as last-resort fallback for blocking OkHttp calls.
- **(Finding SA-1/SEC-0-6) Should `Recording.extractDurationSeconds()` move to `RecordingRepository`?** ✅ **Resolved: Moved to `RecordingRepository` (Chunk 1).** `Recording` is now free of Android framework imports (JVM-testable), and `RecordingRepository.extractDurationSeconds(File)` is the single source of duration extraction for both the pipeline and `DurationHealingJob` (DRY). `DurationHealingJob.heal()` now takes the repository as a second parameter; `DictateApplication.onCreate()` wires both.
- **(Finding SEC-1-5) Should `Recording.kt` and `RecordingRepository.kt` be renamed to `AudioFile.kt` / `AudioFileRepository.kt`?** ⏸ **Deferred.** Chunk 1 implemented as `Recording`/`RecordingRepository` (Option b). A future rename is mechanical (IDE refactor) and can be done as a follow-up if the six `Recording*` files in `core/` cause confusion during Chunk 3 UI work.

---

## Implementation Order Recommendation

When implementing via the `implement-long-plan` skill, group the phases into three chunks:

**Chunk 1 — Data layer foundation:**
- Phase 0.0 (Gradle dependencies for Coroutines/StateFlow/ViewModel)
- Phase 0 (Migration + Schema)
- Phase 1 (Recording value object)
- Phase 2 (RecordingRepository, including Opus compression + deleteBySessionId)
- Phase 6 (SessionTracker: add `getLastKeyboardSession()`, **deprecate** old APIs — but do NOT delete them yet)

Note: Phase 6 uses `@Deprecated` annotations on old methods so that Chunk 1 compiles without touching `DictateInputMethodService`. The deprecated methods are thin wrappers that delegate to the new API internally.

Note: DurationHealingJob must be called from `DictateApplication.onCreate()` (not from `DictateDatabase.onOpen()`) to avoid the circular re-entry issue (Finding SA-2). The job takes the repository as a second parameter (Open Decision SA-1 resolved — `extractDurationSeconds` lives on the repository). Example:
```kotlin
// In DictateApplication.onCreate():
val db = DictateDatabase.getInstance(this)
val recordingRepository = RecordingRepository(this)
val executor = Executors.newSingleThreadExecutor()
executor.execute {
    DurationHealingJob.heal(db.sessionDao(), recordingRepository)
}
executor.shutdown()  // prevents non-daemon thread leak (Finding W3)
```

**Chunk 2 — Pipeline & Registry:**
- Phase 3 (JobState + ActiveJobRegistry)
- Phase 4 (CancellationToken + PipelineConfig data structure + JobExecutor)
- Phase 5 (PipelineOrchestrator refactor including resumePipeline, cancellation checkpoints)

Note: `PipelineOrchestrator.PipelineConfig` is defined as a data structure in Phase 4.1, so `JobRequest.toPipelineConfig()` compiles. Phase 5 then fills in how the orchestrator consumes the config.

Note: `JobExecutor.initialize(orchestrator)` must be called from `DictateInputMethodService.onCreate()` so that both the IME and HistoryDetailActivity can start jobs (Finding SEC-10-2).

**Chunk 3 — UI integration:**
- Phase 7 (KeyboardUiController: ReprocessStaging, AutoEnterConfig rename, `isBusy()`)
- Phase 8 (Language chip)
- Phase 9 (Resend button rewrite + **call-site migration** from deprecated SessionTracker APIs + deletion of deprecated methods)
- Phase 10 (History UI updates: badges, detail buttons incl. audio delete, PromptChooserBottomSheetV2)
- Phase 11 (String resources)
- Phase 12 (Material icons)
- Phase 13 (Integration wiring + tests incl. TranscriptionVersioningTest)

Each chunk should compile and build independently. Between chunks, the app should still run (possibly with partial functionality).
