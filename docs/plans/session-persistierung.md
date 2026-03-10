# Feature Plan: Session-Persistierung, Versionierung & Historie UI

## Context

Dictate ist eine Android-Tastatur-App mit einer mehrstufigen AI-Pipeline:
Recording → Transkription → Auto-Formatting → Queued Prompts → Text-Commit.

**Problem**: Alle Zwischenergebnisse gehen verloren. Audio wird bei der nächsten Aufnahme überschrieben, Transkriptionen und AI-Ergebnisse existieren nur im RAM.

**Ziel**: Vollständige Persistierung aller Pipeline-Outputs mit Insert-Only-Versionierung, Audit-Trail, Regenerate-Möglichkeit und Historie-UI.

---

## Phase 1: Datenbank-Schicht (Entities + DAOs + Migration)

### 1.1 Enums (5 neue Dateien in `database/entity/`)

> Abgelegt in `database/entity/` — konsistent mit der bestehenden Verzeichnisstruktur (kein neues `model/`-Verzeichnis).

`database/entity/SessionType.kt`: `RECORDING`, `REWORDING`, `POST_PROCESSING`
`database/entity/StepType.kt`: `AUTO_FORMAT`, `REWORDING`, `QUEUED_PROMPT`
`database/entity/StepStatus.kt`: `SUCCESS`, `ERROR`
`database/entity/InsertionMethod.kt`: `COMMIT`, `PASTE`
`database/entity/InsertionSource.kt`: `TRANSCRIPTION`, `STATIC_PROMPT`, `REWORDING`, `QUEUED_PROMPT`

> **InsertionMethod** = WIE wurde eingefügt (COMMIT via InputConnection, PASTE via Clipboard).
> **InsertionSource** = WAS wurde eingefügt (woher stammt der Text).

Alle als `String` (.name) in DB gespeichert, kein TypeConverter nötig.

### 1.2 Entities (5 neue Dateien in `database/entity/`)

#### SessionEntity

```kotlin
@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["parent_session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("parent_session_id"), Index("type"), Index("created_at")]
)
data class SessionEntity(
    @PrimaryKey val id: String,                          // UUID
    @ColumnInfo(name = "type") val type: String,         // SessionType.name
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "target_app_package") val targetAppPackage: String?,
    @ColumnInfo(name = "language") val language: String?,
    @ColumnInfo(name = "audio_file_path") val audioFilePath: String?,
    @ColumnInfo(name = "audio_duration_seconds") val audioDurationSeconds: Long = 0,
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,

    // Denormalisierte Felder — Cache für schnelle Suche/Anzeige in HistoryActivity
    // Werden nach jedem Pipeline-Schritt aktualisiert
    @ColumnInfo(name = "final_output_text") val finalOutputText: String? = null,
    @ColumnInfo(name = "input_text") val inputText: String? = null           // REWORDING: selektierter Text, POST_PROCESSING: Parent-Output
)
```

3 Session-Typen:
- `RECORDING`: Hat Audio + Transcription. Entsteht bei Recording-Stop.
- `REWORDING`: Kein Audio. Entsteht wenn User Text selektiert + Prompt klickt.
- `POST_PROCESSING`: Kein Audio. Entsteht aus der Historie heraus. `parent_session_id` gesetzt.

#### TranscriptionEntity (Insert-Only, versioniert)

```kotlin
@Entity(
    tableName = "transcriptions",
    foreignKeys = [ForeignKey(entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id"), Index(value = ["session_id", "version"], unique = true)]
)
data class TranscriptionEntity(
    @PrimaryKey val id: String,                           // UUID
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long = 0,        // von CompletionLog hierher verschoben
    @ColumnInfo(name = "completion_tokens") val completionTokens: Long = 0, // (bei Transcription: immer 0)
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

Partial Unique Index (per SQL, nicht Annotation — Room 2.6 unterstützt keine WHERE-Klausel):
```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_transcriptions_current
ON transcriptions(session_id) WHERE is_current = 1;
```

#### ProcessingStepEntity (Insert-Only, versioniert, Linked-List)

```kotlin
@Entity(
    tableName = "processing_steps",
    foreignKeys = [ForeignKey(entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("session_id"),
        Index(value = ["session_id", "chain_index", "version"], unique = true),
        Index("previous_step_id"), Index("previous_transcription_id"), Index("source_session_id")
    ]
)
data class ProcessingStepEntity(
    @PrimaryKey val id: String,                                    // UUID
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "step_type") val stepType: String,          // StepType.name
    @ColumnInfo(name = "chain_index") val chainIndex: Int,         // Position in Kette (0,1,2...)
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    @ColumnInfo(name = "input_text") val inputText: String,
    @ColumnInfo(name = "output_text") val outputText: String?,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "prompt_used") val promptUsed: String?,
    @ColumnInfo(name = "prompt_entity_id") val promptEntityId: Int?,
    @ColumnInfo(name = "previous_step_id") val previousStepId: String?,             // FK → processing_steps.id
    @ColumnInfo(name = "previous_transcription_id") val previousTranscriptionId: String?, // FK → transcriptions.id
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String?,   // Cross-Session bei POST_PROCESSING
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long = 0,        // von CompletionLog hierher verschoben
    @ColumnInfo(name = "completion_tokens") val completionTokens: Long = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "status") val status: String,               // StepStatus.name
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

Partial Unique Index — **auf `(session_id, chain_index)` OHNE step_type**:
```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_processing_steps_current
ON processing_steps(session_id, chain_index) WHERE is_current = 1;
```

Begründung: step_type kann sich bei Regenerate mit anderem Prompt ändern (z.B. AUTO_FORMAT → QUEUED_PROMPT).

#### CompletionLogEntity (Append-Only, nie verändert)

> **DRY-Optimierung**: input_text/output_text/model/provider/duration existieren bereits in ProcessingStepEntity/TranscriptionEntity.
> CompletionLogEntity speichert nur die **Nicht-Redundanten Felder** + FK-Referenzen.
> Token-Counts werden stattdessen direkt in ProcessingStepEntity und TranscriptionEntity gespeichert (siehe dort).

```kotlin
@Entity(tableName = "completion_log",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("session_id"), Index("timestamp"), Index("step_id"), Index("transcription_id")])
data class CompletionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,                        // "TRANSCRIPTION" | "AUTO_FORMAT" | "REWORDING" | "QUEUED_PROMPT"
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "step_id") val stepId: String?,                 // FK → processing_steps.id
    @ColumnInfo(name = "transcription_id") val transcriptionId: String?, // FK → transcriptions.id
    @ColumnInfo(name = "system_prompt") val systemPrompt: String?,     // Nur hier gespeichert (nicht in Steps)
    @ColumnInfo(name = "user_prompt") val userPrompt: String?,         // Nur hier gespeichert
    val success: Boolean,
    @ColumnInfo(name = "error_message") val errorMessage: String?
)
```

> **FK-Entscheidung**: `session_id` hat FK-Constraint mit CASCADE. `step_id` und `transcription_id` haben bewusst KEINE FK-Constraints,
> weil Completion-Logs auch für fehlgeschlagene Calls existieren können wo noch kein Step/Transcription erstellt wurde.
> Die referenzielle Integrität wird über CASCADE von session_id gewährleistet (Session-Delete → Log-Delete).

#### TextInsertionEntity (Undo-Buffer + Paste-Log)

```kotlin
@Entity(tableName = "text_insertions",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("session_id"), Index("timestamp"),
               Index("source_step_id"), Index("source_transcription_id")])
data class TextInsertionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    val timestamp: Long,
    @ColumnInfo(name = "inserted_text") val insertedText: String,
    @ColumnInfo(name = "replaced_text") val replacedText: String?,
    @ColumnInfo(name = "target_app_package") val targetAppPackage: String?,
    @ColumnInfo(name = "cursor_position") val cursorPosition: Int?,
    @ColumnInfo(name = "source_step_id") val sourceStepId: String?,             // exakte Step-Version
    @ColumnInfo(name = "source_transcription_id") val sourceTranscriptionId: String?, // exakte Transcription-Version
    @ColumnInfo(name = "insertion_method") val insertionMethod: String           // "COMMIT" | "PASTE"
)
```

### 1.3 DAOs (5 neue Interface-DAOs in `database/dao/`)

> **Wichtig**: Room-Interface-DAOs können KEINE Methoden mit Body haben.
> DAOs bieten nur atomare Operationen (`@Insert`, `@Query`).
> Transaktionale Geschäftslogik liegt im `SessionManager` via `db.runInTransaction {}`.

**TranscriptionDao** — Atomare Operationen:
```kotlin
@Dao
interface TranscriptionDao {
    @Insert
    fun insert(entity: TranscriptionEntity)

    @Query("SELECT COALESCE(MAX(version), 0) FROM transcriptions WHERE session_id = :sessionId")
    fun getMaxVersion(sessionId: String): Int

    @Query("UPDATE transcriptions SET is_current = 0 WHERE session_id = :sessionId AND is_current = 1")
    fun clearCurrent(sessionId: String)

    @Query("SELECT * FROM transcriptions WHERE session_id = :sessionId AND is_current = 1")
    fun getCurrent(sessionId: String): TranscriptionEntity?

    @Query("SELECT * FROM transcriptions WHERE session_id = :sessionId ORDER BY version")
    fun getAllVersions(sessionId: String): List<TranscriptionEntity>
}
```

**ProcessingStepDao** — Atomare Operationen:
```kotlin
@Dao
interface ProcessingStepDao {
    @Insert
    fun insert(entity: ProcessingStepEntity)

    @Query("SELECT COALESCE(MAX(chain_index), -1) FROM processing_steps WHERE session_id = :sessionId AND is_current = 1")
    fun getMaxChainIndex(sessionId: String): Int

    @Query("SELECT COALESCE(MAX(version), 0) FROM processing_steps WHERE session_id = :sessionId AND chain_index = :chainIndex")
    fun getMaxVersion(sessionId: String, chainIndex: Int): Int

    @Query("UPDATE processing_steps SET is_current = 0 WHERE session_id = :sessionId AND chain_index = :chainIndex AND is_current = 1")
    fun clearCurrentAtIndex(sessionId: String, chainIndex: Int)

    @Query("UPDATE processing_steps SET is_current = 0 WHERE session_id = :sessionId AND chain_index > :chainIndex AND is_current = 1")
    fun invalidateDownstream(sessionId: String, chainIndex: Int)

    @Query("SELECT * FROM processing_steps WHERE session_id = :sessionId AND is_current = 1 ORDER BY chain_index")
    fun getCurrentChain(sessionId: String): List<ProcessingStepEntity>

    @Query("SELECT * FROM processing_steps WHERE session_id = :sessionId AND chain_index = :chainIndex ORDER BY version")
    fun getVersionsAtIndex(sessionId: String, chainIndex: Int): List<ProcessingStepEntity>
}
```

Die transaktionale Orchestrierung (clearCurrent + insert, invalidateDownstream + insert) erfolgt im `SessionManager` via `db.runInTransaction {}`.

### 1.4 Migration v1→v2

In `database/migration/Migrations.kt`: 5× `CREATE TABLE` + alle Indices.
Partial Unique Indices werden zusätzlich im `onOpen`-Callback angelegt (`CREATE ... IF NOT EXISTS`).

### 1.5 DictateDatabase aktualisieren

- `version = 2`, `entities` um 5 neue Klassen erweitern
- 5 neue abstract DAO-Funktionen
- `.addMigrations(MIGRATION_1_2)` in `buildDatabase()`
- `onOpen`-Callback für Partial Unique Indices

### 1.6 SessionManager

> Benannt als `SessionManager` (nicht "SessionRepository") — die Klasse enthält Business-Logik
> (require()-Checks, Versionierungs-Logik, transaktionale Orchestrierung), nicht nur DB-Zugriff.
> Platzierung in `core/` statt `database/repository/`, analog zu RecordingManager/PromptQueueManager.

Neue Datei `core/SessionManager.kt`. Braucht `DictateDatabase` (für `runInTransaction`) + alle DAOs:

```kotlin
class SessionManager(private val db: DictateDatabase) {
    private val sessionDao = db.sessionDao()
    private val transcriptionDao = db.transcriptionDao()
    private val stepDao = db.processingStepDao()
    private val completionLogDao = db.completionLogDao()
    private val textInsertionDao = db.textInsertionDao()
```

Methoden:
- `createSession(type, targetApp, language, audio, parent)` — mit `require()`-Checks
- `addTranscriptionVersion(sessionId, text, model, ...)` — via `db.runInTransaction { clearCurrent + insert }`
- `appendProcessingStep(...)` / `regenerateProcessingStep(...)` — Ketten-Management via `db.runInTransaction {}`
- `logCompletion(type, sessionId, stepId, transcriptionId, ...)` — AI-Call-Logging
- `logTextInsertion(sessionId, text, replaced, sourceStepId, sourceTranscriptionId, method)`
- `logPasteFromHistory(sessionId, sourceStepId, sourceTranscriptionId, text)` — Convenience
- `getFinalOutput(sessionId)` / `getFinalOutputSource(sessionId)` — `FinalOutputInfo(text, stepId?, transcriptionId?)`
- `updateFinalOutputText(sessionId, text)` — denormalisiert
- `updateAudioDuration(sessionId, durationSeconds)` — nach Audio-Persistierung

> `getFinalOutputSource` gibt eine eigene Data-Class `FinalOutputInfo` zurück statt `Pair<String, String>` — bessere Java-Interop.

---

## Phase 2: Integration in DictateInputMethodService

### 2.1 SessionTracker + ProcessingContext

> **Architektur-Entscheidung**: Statt 7+ neue Instanzvariablen direkt in die 2121-Zeilen God-Class
> DictateInputMethodService zu packen, wird die Session-Logik in eine dedizierte `SessionTracker`-Klasse
> extrahiert — analog zum bestehenden Pattern (RecordingManager, PromptQueueManager, BluetoothScoManager).

#### SessionTracker (neue Datei `core/SessionTracker.kt`)

```kotlin
class SessionTracker(private val sessionManager: SessionManager) {
    // Session-Tracking (volatile für cross-thread Sichtbarkeit)
    @Volatile var currentSessionId: String? = null
        private set
    @Volatile var currentStepId: String? = null
        private set
    @Volatile var currentTranscriptionId: String? = null
        private set
    @Volatile var lastSessionId: String? = null
        private set
    @Volatile var lastOutput: String? = null       // Cache für Resend-Tap (kein DB-Zugriff auf Main-Thread)
        private set

    fun startSession(type: SessionType, targetApp: String?, language: String?, audioPath: String?, parentId: String?) {
        if (currentSessionId != null) return  // Guard: bereits aktiv
        val session = sessionManager.createSession(type, targetApp, language, audioPath, 0, parentId)
        currentSessionId = session.id
    }

    fun setTranscription(id: String) { currentTranscriptionId = id; currentStepId = null }
    fun setStep(id: String) { currentStepId = id; currentTranscriptionId = null }

    fun resetSession() {
        if (currentSessionId != null) {
            lastSessionId = currentSessionId
            lastOutput = sessionManager.getFinalOutput(currentSessionId!!)
        }
        currentSessionId = null
        currentStepId = null
        currentTranscriptionId = null
    }

    fun restoreFromPrefs(sp: SharedPreferences) {
        lastSessionId = sp.getString("net.devemperor.dictate.last_session_id", null)
    }
    fun persistToPrefs(sp: SharedPreferences) {
        sp.edit().putString("net.devemperor.dictate.last_session_id", lastSessionId).apply()
    }
    fun reuseLastSession() { currentSessionId = lastSessionId }
}
```

#### ProcessingContext (neue Datei `core/ProcessingContext.kt`)

> **Statt pending\*-Instanzvariablen**: Expliziter Kontext als Parameter durchreichen.
> Thread-sicher (immutable), kein Seiteneffekt, kein Copy-Paste-Risiko.

```kotlin
data class ProcessingContext(
    val stepType: StepType,           // AUTO_FORMAT, REWORDING, QUEUED_PROMPT
    val promptUsed: String?,          // tatsächlicher Prompt-Text
    val promptEntityId: Int?          // FK → prompts.id (null bei Auto-Format/Freitext)
)
```

#### Instanzvariablen in DictateInputMethodService

```java
// Nur noch 2 neue Instanzvariablen statt 8:
private SessionTracker sessionTracker;
private SessionManager sessionManager;
```

Initialisierung in `onCreateInputView()` nach DB-Setup:
```java
sessionManager = new SessionManager(DictateDatabase.getInstance(this));
sessionTracker = new SessionTracker(sessionManager);
sessionTracker.restoreFromPrefs(sp);
```

### 2.2 Session-Erstellung (via SessionTracker)

**RECORDING** — in `startWhisperApiRequest()` (Zeile ~1506), vor dem `execute()`:
```java
EditorInfo info = getCurrentInputEditorInfo();
sessionTracker.startSession(SessionType.RECORDING,
    info != null ? info.packageName : null,
    currentInputLanguageValue, audioFile.getAbsolutePath(), null);
```
> `startSession()` hat intern den Guard (`if currentSessionId != null return`).

**REWORDING** — in `startGPTApiRequest(PromptEntity model, ...)` (Zeile ~1608), nach `buildRewording()`:
```java
if (model.getId() != -1) {
    sessionTracker.startSession(SessionType.REWORDING,
        editorInfo.packageName, null, null, null);
}
```

Nicht bei: Static Prompts (`[text]`), Live Prompts (gehören zur RECORDING-Session).

### 2.3 Transkription persistieren + Completion-Log

In `startWhisperApiRequest()`, innerhalb des `speechApiThread.execute()` (Zeile ~1507):
```java
// Zeitmessung starten
long transcriptionStart = System.nanoTime();

String language = currentInputLanguageValue != null && !currentInputLanguageValue.equals("detect")
    ? currentInputLanguageValue : null;
TranscriptionResult result = aiOrchestrator.transcribe(audioFile, language, stylePrompt);
String resultText = result.getText().strip();

long transcriptionDurationMs = (System.nanoTime() - transcriptionStart) / 1_000_000;
String transcriptionProvider = aiOrchestrator.getProvider(AIFunction.TRANSCRIPTION).name();

// Transkription versioniert persistieren
TranscriptionEntity t = sessionManager.addTranscriptionVersion(
    sessionTracker.getCurrentSessionId(), resultText, result.getModelName(),
    transcriptionProvider, transcriptionDurationMs);
sessionTracker.setTranscription(t.getId());

// Completion-Log für Transkription
sessionManager.logCompletion("TRANSCRIPTION", sessionTracker.getCurrentSessionId(),
    null, t.getId(),
    null, null,
    result.getModelName(), transcriptionProvider,
    transcriptionDurationMs, true, null);
```

> **Completion-Log Signatur vereinfacht**: input_text/output_text entfernt (redundant mit TranscriptionEntity).
> Nur noch: type, sessionId, stepId, transcriptionId, systemPrompt, userPrompt, modelName, provider, durationMs, success, errorMessage.

### 2.4 AutoFormattingService — Breaking Change

`formatIfEnabled()` gibt `FormatResult(text, completionResult?, error?)` statt `String` zurück:
```kotlin
data class FormatResult(
    val text: String,
    val completionResult: CompletionResult?,
    val error: Exception? = null           // Unterscheidet "disabled" (null+null) von "failed" (null+error)
)
```

AutoFormattingService erstellt **keinen** Step selbst (hat keinen Zugang zu SessionManager).
Der **Caller** in `startWhisperApiRequest()` erstellt den Step:

```java
long formatStart = System.nanoTime();
FormatResult fr = autoFormattingService.formatIfEnabled(resultText, currentInputLanguageValue);
long formatDurationMs = (System.nanoTime() - formatStart) / 1_000_000;
String sid = sessionTracker.getCurrentSessionId();

if (sid != null) {
    if (fr.getCompletionResult() != null) {
        // SUCCESS: Auto-Format hat funktioniert
        String provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name();
        ProcessingContext ctx = new ProcessingContext(StepType.AUTO_FORMAT, null, null);
        ProcessingStepEntity step = sessionManager.appendProcessingStep(
            sid, ctx.getStepType().name(), resultText, fr.getText(),
            fr.getCompletionResult().getModelName(), provider,
            null, null, // promptUsed, promptEntityId (Auto-Format hat keinen User-Prompt)
            null, sessionTracker.getCurrentTranscriptionId(), // previousTranscriptionId
            null, fr.getCompletionResult().getPromptTokens(),
            fr.getCompletionResult().getCompletionTokens(),
            formatDurationMs, StepStatus.SUCCESS.name(), null);
        sessionTracker.setStep(step.getId());

        sessionManager.logCompletion("AUTO_FORMAT", sid,
            step.getId(), null, null, null,
            fr.getCompletionResult().getModelName(), provider,
            formatDurationMs, true, null);

    } else if (fr.getError() != null) {
        // ERROR: Auto-Format fehlgeschlagen — Error-Step persistieren für Audit-Trail
        String provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name();
        String model = aiOrchestrator.getModelName(AIFunction.COMPLETION);
        ProcessingStepEntity step = sessionManager.appendProcessingStep(
            sid, StepType.AUTO_FORMAT.name(), resultText, null,
            model, provider, null, null,
            null, sessionTracker.getCurrentTranscriptionId(),
            null, 0, 0, formatDurationMs,
            StepStatus.ERROR.name(), fr.getError().getMessage());
        // Kein setStep() — Output bleibt bei der Transcription
        sessionManager.logCompletion("AUTO_FORMAT", sid,
            step.getId(), null, null, null,
            model, provider, formatDurationMs, false, fr.getError().getMessage());
    }
    // else: disabled → kein Step, kein Log
}
resultText = fr.getText();
```

AutoFormattingService intern:
```kotlin
fun formatIfEnabled(transcript: String, languageHint: String?): FormatResult {
    if (!enabled) return FormatResult(transcript, null, null)  // disabled
    return try {
        val result = aiOrchestrator.complete(userPrompt, systemPrompt)
        FormatResult(result.text.trim().ifEmpty { transcript }, result, null)  // success
    } catch (e: Exception) {
        Log.w("AutoFormattingService", "Auto-formatting failed", e)
        FormatResult(transcript, null, e)  // failed — error mitgeben für Step-Erstellung
    }
}
```

### 2.5 `requestRewordingFromApi()` — Signatur-Änderung

**Problem**: `requestRewordingFromApi()` (Zeile 1683) gibt nur `result.getText()` zurück, aber wir brauchen das volle `CompletionResult` (modelName, promptTokens, completionTokens) für Step-Erstellung und Completion-Logging.

**Änderung**: Rückgabetyp von `String` auf `CompletionResult` ändern:
```java
// VORHER (Zeile 1683):
private String requestRewordingFromApi(String prompt, String systemPrompt) {
    CompletionResult result = aiOrchestrator.complete(prompt, systemPrompt);
    return result.getText();
}

// NACHHER:
private CompletionResult requestRewordingFromApi(String prompt, String systemPrompt) {
    return aiOrchestrator.complete(prompt, systemPrompt);
}
```

Aufrufstelle in `startGPTApiRequestInternal()` (Zeile 1631) anpassen:
```java
CompletionResult result = requestRewordingFromApi(pp.getUserPrompt(), pp.getSystemPrompt());
String rewordedText = result.getText();
```

### 2.6 ProcessingContext durchreichen statt pending*-Instanzvariablen

> **Kernänderung**: `startGPTApiRequestInternal()` bekommt `ProcessingContext` als neuen Parameter.
> Kein impliziter Seiteneffekt, thread-sicher (immutable Data-Class), kein Copy-Paste-Risiko.

**Signatur-Änderung** für `startGPTApiRequestInternal()`:
```java
// VORHER:
private void startGPTApiRequestInternal(PromptPair pp, String displayName,
    PromptResultCallback callback, boolean restorePromptsOnFinish)

// NACHHER:
private void startGPTApiRequestInternal(PromptPair pp, String displayName,
    ProcessingContext ctx, PromptResultCallback callback, boolean restorePromptsOnFinish)
```

**Caller 1** — `startGPTApiRequest(PromptEntity model, ...)` (Zeile 1575):
```java
ProcessingContext ctx = new ProcessingContext(
    StepType.REWORDING,
    model.getPrompt(),
    model.getId() >= 0 ? model.getId() : null);
startGPTApiRequestInternal(pp, displayName, ctx, callback, restoreFlag);
```

**Caller 2** — `applyQueuedPromptAtIndex()` (Zeile 1748):
```java
ProcessingContext ctx = new ProcessingContext(
    StepType.QUEUED_PROMPT,
    prompt.getPrompt(),
    prompt.getId());
PromptService.PromptPair pp = promptService.buildQueuedPrompt(prompt.getPrompt(), textForPrompt);
startGPTApiRequestInternal(pp, prompt.getName(), ctx, callback, restoreUiAfter);
```

Alle Overloads von `startGPTApiRequest` müssen `ProcessingContext` durchreichen.

### 2.7 Processing-Steps + Completion-Log in `startGPTApiRequestInternal()`

```java
private void startGPTApiRequestInternal(PromptService.PromptPair pp, String displayName,
        ProcessingContext ctx, PromptResultCallback callback, boolean restorePromptsOnFinish) {
    // ... UI-Updates (bestehend) ...

    rewordingApiThread = Executors.newSingleThreadExecutor();
    rewordingApiThread.execute(() -> {
        String sid = sessionTracker.getCurrentSessionId();
        long startTime = System.nanoTime();
        try {
            CompletionResult result = requestRewordingFromApi(pp.getUserPrompt(), pp.getSystemPrompt());
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            String rewordedText = result.getText();

            // SUCCESS: Step + Completion-Log persistieren
            if (sid != null) {
                String provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name();
                ProcessingStepEntity step = sessionManager.appendProcessingStep(
                    sid, ctx.getStepType().name(),
                    pp.getUserPrompt(), rewordedText,
                    result.getModelName(), provider,
                    ctx.getPromptUsed(), ctx.getPromptEntityId(),
                    sessionTracker.getCurrentStepId(),       // previousStepId
                    sessionTracker.getCurrentTranscriptionId(), // previousTranscriptionId
                    null, result.getPromptTokens(), result.getCompletionTokens(),
                    durationMs, StepStatus.SUCCESS.name(), null);

                sessionManager.logCompletion(ctx.getStepType().name(), sid,
                    step.getId(), null,
                    pp.getSystemPrompt(), pp.getUserPrompt(),
                    result.getModelName(), provider,
                    durationMs, true, null);

                sessionTracker.setStep(step.getId());
            }

            if (callback != null) {
                callback.onSuccess(rewordedText);
            } else {
                commitTextToInputConnection(rewordedText, InsertionSource.REWORDING);
            }
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            // ERROR: Step mit ERROR-Status persistieren für Audit-Trail
            if (sid != null) {
                String provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name();
                String model = aiOrchestrator.getModelName(AIFunction.COMPLETION);
                sessionManager.appendProcessingStep(
                    sid, ctx.getStepType().name(),
                    pp.getUserPrompt(), null,
                    model, provider,
                    ctx.getPromptUsed(), ctx.getPromptEntityId(),
                    sessionTracker.getCurrentStepId(),
                    sessionTracker.getCurrentTranscriptionId(),
                    null, 0, 0, durationMs,
                    StepStatus.ERROR.name(), e.getMessage());

                sessionManager.logCompletion(ctx.getStepType().name(), sid,
                    null, null,
                    pp.getSystemPrompt(), pp.getUserPrompt(),
                    model, provider,
                    durationMs, false, e.getMessage());
            }

            // ... bestehende Error-Handling-Logik (UI-Feedback, Toast, etc.) ...
        }
    });
}
```

> **Error-Path**: Fehlgeschlagene API-Calls erstellen jetzt einen Step mit `StepStatus.ERROR` + Completion-Log mit `success=false`.
> Das gewährleistet den vollständigen Audit-Trail auch bei Fehlern.

### 2.8 commitTextToInputConnection — Erweiterung

Neuer Parameter `InsertionSource` (nullable — `null` = keine Persistierung):
```java
private void commitTextToInputConnection(String text, InsertionSource source) {
    InputConnection ic = getCurrentInputConnection();
    if (ic == null) return;

    // VOR commitText: selektierten Text erfassen (Undo-Buffer)
    String replacedText = null;
    if (source != null) {
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) replacedText = sel.toString();
    }

    String output = text == null ? "" : text;
    // ... Bestehende Output-Logik (instant/slow/fallback) unverändert ...

    // NACH commitText: Persistieren + Session finalOutputText aktualisieren
    if (source != null && output.length() > 0) {
        final String fReplacedText = replacedText;
        final String fSessionId = sessionTracker.getCurrentSessionId();
        final String fStepId = sessionTracker.getCurrentStepId();
        final String fTranscriptionId = sessionTracker.getCurrentTranscriptionId();
        final String pkg = getCurrentInputEditorInfo() != null
            ? getCurrentInputEditorInfo().packageName : null;

        dbExecutor.execute(() -> {  // Shared single-thread executor (siehe unten)
            sessionManager.logTextInsertion(fSessionId, output, fReplacedText, pkg,
                null, fStepId, fTranscriptionId, InsertionMethod.COMMIT);
            if (fSessionId != null) {
                sessionManager.updateFinalOutputText(fSessionId, output);
            }
        });
    }
}
```

> **Shared Executor**: `private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();`
> als Instanzvariable statt `Executors.newSingleThreadExecutor()` pro Aufruf.

SessionDao:
```kotlin
@Query("UPDATE sessions SET final_output_text = :text WHERE id = :sessionId")
fun updateFinalOutputText(sessionId: String, text: String)
```

6 Aufrufstellen anpassen (InsertionSource mitgeben):
| Zeile | Kontext | InsertionSource |
|-------|---------|-----------------|
| 1525 | Direkt-Commit nach Whisper | TRANSCRIPTION |
| 1584 | Statischer Prompt | STATIC_PROMPT |
| 1636 | Rewording-Ergebnis | REWORDING |
| 1725 | Queued-Prompts leer | TRANSCRIPTION |
| 1733 | Queued-Prompt-Kette Ende | QUEUED_PROMPT |
| 1760 | Queued-Prompt Failure | QUEUED_PROMPT |

### 2.9 Session-Reset via SessionTracker

```java
private void restorePromptUi() {
    // ZUERST: Session-State zurücksetzen (noch auf dem Background-Thread)
    // resetSession() cached lastOutput + lastSessionId intern
    sessionTracker.resetSession();
    sessionTracker.persistToPrefs(sp);

    // DANN: UI-Update auf Main-Thread
    if (mainHandler == null) return;
    mainHandler.post(() -> { ... });
}
```

> **Threading-Invariante**: `restorePromptUi()` wird IMMER vom Background-Thread aufgerufen
> (speechApiThread oder rewordingApiThread). `commitTextToInputConnection` läuft auf dem gleichen
> Thread VORHER. Die Reihenfolge ist garantiert: erst commit (snapshots Werte in finals), dann reset.
> `@Volatile` auf den SessionTracker-Feldern stellt die Cross-Thread-Sichtbarkeit sicher.

### 2.10 Audio-Persistierung

Audio von `getCacheDir()/audio.m4a` → `getFilesDir()/recordings/{sessionId}.m4a` kopieren.
Neue Hilfsmethode `persistAudioFile(cacheFile, sessionId)`.

Nach dem Kopieren: Audio-Dauer ermitteln und in Session aktualisieren:
```java
private void persistAudioFile(File cacheFile, String sessionId) {
    File destDir = new File(getFilesDir(), "recordings");
    destDir.mkdirs();
    File dest = new File(destDir, sessionId + ".m4a");
    Files.copy(cacheFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);

    // Audio-Dauer ermitteln
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
        retriever.setDataSource(dest.getAbsolutePath());
        String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        long durationSeconds = Long.parseLong(durationStr) / 1000;
        sessionManager.updateAudioDuration(sessionId, durationSeconds);
    } finally {
        retriever.release();
    }
}
```

---

## Phase 3: Historie-UI

### 3.1 Neue Dateien

```
history/
  HistoryActivity.java          — Session-Liste mit Filter-Chips + Suche
  HistoryAdapter.java           — RecyclerView-Adapter (typ-abhängige Icons)
  HistoryDetailActivity.java    — Pipeline-Detail-View
  PipelineStepAdapter.java      — Step-Items mit Versions-Chips
  PromptChooserBottomSheet.java — Prompt-Auswahl (BottomSheetDialogFragment)
  PromptChooserAdapter.java     — Einfacher Prompt-Listen-Adapter

layout/
  activity_history.xml          — Filter-Chips + SearchView + RecyclerView + Delete-All
  item_history_session.xml      — Typ-Icon + Datum + Subtitle + Preview + Steps-Summary
  activity_history_detail.xml   — Header + ScrollView(Pipeline-RV) + Action-Buttons
  item_pipeline_step.xml        — Connector + Card(Icon,Title,Version,Actions,Output,Meta,Chips)
  dialog_prompt_chooser.xml     — Drag-Handle + Freitext + Divider + Prompt-Liste
  item_prompt_chooser.xml       — Name + Prompt-Preview
```

### 3.2 HistoryActivity — Session-Liste

- Filter-Chips: "Alle" | "Aufnahmen" | "Rewording" | "Nachbearbeitung"
- SearchView: Sucht in `final_output_text` und `input_text` (denormalisierte Felder auf SessionEntity)
- SessionDao braucht: `search(query)` mit `LIKE '%' || :query || '%'` auf beide Felder
- Typ-abhängige Items:
  - RECORDING: 🎤 + Dauer
  - REWORDING: ✏️ + Prompt-Name
  - POST_PROCESSING: 🔄 + "Basiert auf [Datum]" (klickbar → Parent)
- Swipe-to-Delete mit MaterialAlertDialogBuilder
- **Empty-State**: Analog zu UsageActivity/PromptsOverviewActivity — zentrierter Text wenn keine Sessions vorhanden

### 3.3 HistoryDetailActivity — Pipeline-View

Pipeline je nach Session-Typ:

**RECORDING:**
```
🎤 Audio (2.3s) [▶ Play]          ← Audio-Playback (siehe 3.3a)
📝 Transkription v2 [Regenerate] [Versionen ▾]
✨ Auto-Format v1 [Regenerate] [Anderer Prompt] [Versionen ▾]
🔄 Übersetzer v1 [Regenerate] [Anderer Prompt] [>>]
📋 Final Output [Kopieren] [Teilen]  ← Clipboard/Share (siehe 3.3b)
```

**REWORDING:**
```
📥 Eingabe (selektierter Text)
✏️ Rewording v1 [Regenerate] [Anderer Prompt] [>>]
📋 Final Output [Kopieren] [Teilen]
```

**POST_PROCESSING:**
```
🔗 Input von Session [Datum] [→ Öffnen]
🔄 Prompt-Name v1 [Regenerate] [Anderer Prompt] [>>]
📋 Final Output [Kopieren] [Teilen]
```

### 3.3a Audio-Playback

Einfacher `MediaPlayer` für die Audio-Wiedergabe in HistoryDetailActivity:
```java
private MediaPlayer mediaPlayer;

private void playAudio(String audioFilePath) {
    if (mediaPlayer != null) { mediaPlayer.release(); }
    mediaPlayer = new MediaPlayer();
    mediaPlayer.setDataSource(audioFilePath);
    mediaPlayer.prepare();
    mediaPlayer.start();
    // Play-Button → Pause-Button wechseln
    mediaPlayer.setOnCompletionListener(mp -> resetPlayButton());
}

@Override protected void onDestroy() {
    if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
    super.onDestroy();
}
```

> Kein ExoPlayer nötig — nur lokale M4A-Dateien, kein Streaming. Einfacher MediaPlayer reicht.
> Audio-Focus wird nicht angefordert (kurze Clips, kein Musik-Player).

### 3.3b Clipboard + Share

**"Kopieren"**: `ClipboardManager` + Toast:
```java
ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", outputText));
Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
```

**"Teilen"**: Standard Share-Intent:
```java
Intent shareIntent = new Intent(Intent.ACTION_SEND);
shareIntent.setType("text/plain");
shareIntent.putExtra(Intent.EXTRA_TEXT, outputText);
startActivity(Intent.createChooser(shareIntent, null));
```

> **Kein "Einfügen"-Button** in HistoryDetailActivity — das Einfügen via InputConnection funktioniert
> nur im IME-Kontext. Stattdessen: "Kopieren" (Clipboard) + Toast "In Zwischenablage kopiert".
> Der User kann dann in der Ziel-App einfügen.

### 3.4 Versions-Switcher

- Inline ChipGroup im Step-Item (kein Dialog)
- Chips: `v1 (14:23) | v2 (14:25) ✓`
- Bei Step-Type-Wechsel zwischen Versionen: Typ-Name im Chip anzeigen
- Warnung bei downstream Steps: "Nachfolgende Schritte basieren auf anderer Version"

### 3.5 AIOrchestrator-Zugang + Regenerate-Threading

**Problem**: Regeneration braucht `aiOrchestrator.transcribe()` und `.complete()`.
AIOrchestrator wird aktuell nur in `DictateInputMethodService` konstruiert.

**Lösung**: AIOrchestrator als Factory-Methode verfügbar machen — identischer Constructur-Aufruf
wie in DictateInputMethodService (Zeile ~318):
```java
// In HistoryDetailActivity.onCreate():
SharedPreferences sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
AIOrchestrator orchestrator = new AIOrchestrator(sp, DictateDatabase.getInstance(this).usageDao());
```

Kein Singleton nötig — AIOrchestrator ist stateless (nur SharedPreferences + UsageDao).

**Regenerate-Threading-Pattern**:
```java
// AIOrchestrator ist blockierend → Background-Thread nötig
private final ExecutorService regenerateExecutor = Executors.newSingleThreadExecutor();

private void regenerateStep(ProcessingStepEntity step) {
    setUiState(UiState.LOADING);  // ProgressBar anzeigen
    regenerateExecutor.execute(() -> {
        try {
            CompletionResult result = orchestrator.complete(step.getInputText(), ...);
            if (!isFinishing()) {  // Activity-Lifecycle prüfen
                runOnUiThread(() -> {
                    // UI aktualisieren, Version-Chips refreshen
                    setUiState(UiState.IDLE);
                });
            }
        } catch (Exception e) {
            if (!isFinishing()) {
                runOnUiThread(() -> {
                    setUiState(UiState.ERROR);
                    showError(e.getMessage());
                });
            }
        }
    });
}
```

UI-State-Machine: `IDLE` → `LOADING` → `IDLE` | `ERROR`.

### 3.6 Regenerate + Prompt-Auswahl

- "Regenerate": Gleicher Prompt, neuer API-Call → neue Version
- "Mit anderem Prompt": PromptChooserBottomSheet → Freitext oder gespeicherter Prompt
- "Weiterverarbeiten": Erstellt POST_PROCESSING-Session mit `parent_session_id`

### 3.7 Clipboard-Logging

"Kopieren"-Button in HistoryDetailActivity loggt den Clipboard-Zugriff:
```java
// Nach clipboard.setPrimaryClip():
sessionManager.logTextInsertion(sessionId, outputText, null, null,
    null, lastStep.getId(), null, InsertionMethod.PASTE);
```

> `InsertionMethod.PASTE` = Clipboard-Copy aus der Historie. `COMMIT` = Direkt-Einfügung via InputConnection.

### 3.8 Navigation

- Neuer Preference-Eintrag in `fragment_preferences.xml` (neben "Usage")
- Neuer History-Button in der Tastatur (siehe Phase 5.1)
- Neue Activities in `AndroidManifest.xml`

---

## Phase 4: Pipeline-Progress im Keyboard

### 4.1 Bestehender Bereich ersetzen

Der Spinner (`prompts_keyboard_running_prompt_tv` + `prompts_keyboard_running_pb`) sitzt im
`prompts_keyboard_cl`-Container (72dp hoch, layout Zeile 307-358). Aktuell zeigt er nur
den Prompt-Namen + indeterminate ProgressBar.

**Änderung**: Statt Spinner wird eine kompakte Step-Liste angezeigt — im **gleichen Container**,
keine Höhenänderung:

```
┌──────────────────────────────────────────────┐
│  ✅ Transkription  1.8s                      │  Zeile 1 (oben)
│  🔄 Formatierung   0.4s    [Abbrechen]       │  Zeile 2 (unten)
└──────────────────────────────────────────────┘
  72dp — gleiche Höhe wie prompts_keyboard_cl
```

**Regeln**:
- Maximal 2 Zeilen sichtbar (in 72dp passen 2 × 28dp + Padding)
- Zeigt immer: den letzten abgeschlossenen Step + den aktuell laufenden Step
- Scrollt automatisch mit wenn die Pipeline fortschreitet
- Kein Retry-Button (User-Entscheidung) — nur Abbrechen
- Step-Status-Icons: ✅ done, 🔄 running (oder kleiner ProgressBar), ⏳ pending, ❌ error

### 4.1a Integration in `startWhisperApiRequest()`

> Phase 4.1 beschreibt das Layout. Hier die **Code-Integration** für den Whisper-Flow:

```java
// In startWhisperApiRequest(), vor dem execute():
mainHandler.post(() -> showPipelineProgress());  // PipelineProgressView anzeigen

speechApiThread.execute(() -> {
    // Step 1: Transkription
    mainHandler.post(() -> updatePipelineStep("Transkription", StepState.RUNNING));
    long t0 = System.nanoTime();
    TranscriptionResult result = aiOrchestrator.transcribe(...);
    long tDuration = (System.nanoTime() - t0) / 1_000_000;
    mainHandler.post(() -> updatePipelineStep("Transkription", StepState.DONE, tDuration));

    // Step 2: Auto-Formatierung (optional)
    if (autoFormattingEnabled) {
        mainHandler.post(() -> updatePipelineStep("Formatierung", StepState.RUNNING));
        long f0 = System.nanoTime();
        FormatResult fr = autoFormattingService.formatIfEnabled(...);
        long fDuration = (System.nanoTime() - f0) / 1_000_000;
        mainHandler.post(() -> updatePipelineStep("Formatierung", StepState.DONE, fDuration));
    }

    // Step 3+: Queued Prompts
    // processQueuedPrompts() ruft intern updatePipelineStep() auf
    ...
});
```

### 4.2 Abbrechen-Button + vollständiger Abbrechen-Flow

- Sichtbar nur während Pipeline läuft
- Positioniert rechts im 2-Zeilen-Container

**Vollständiger Abbrechen-Flow:**
```java
cancelButton.setOnClickListener(v -> {
    // 1. Flag setzen (für Queued-Prompt-Ketten)
    pipelineCancelled = true;

    // 2. Aktuellen API-Call abbrechen
    if (speechApiThread != null) speechApiThread.shutdownNow();
    if (rewordingApiThread != null) rewordingApiThread.shutdownNow();

    // 3. Letzten erfolgreichen Output committen
    String lastSuccessfulOutput = sessionTracker.getCurrentStepId() != null
        ? sessionManager.getStepOutput(sessionTracker.getCurrentStepId())
        : (sessionTracker.getCurrentTranscriptionId() != null
            ? sessionManager.getTranscriptionText(sessionTracker.getCurrentTranscriptionId())
            : null);
    if (lastSuccessfulOutput != null) {
        commitTextToInputConnection(lastSuccessfulOutput, InsertionSource.TRANSCRIPTION);
    }

    // 4. UI-State zurücksetzen
    hidePipelineProgress();
    restorePromptUi();
});
```

> **Queued-Prompt-Ketten**: `pipelineCancelled`-Flag wird in `applyQueuedPromptAtIndex()` geprüft.
> Wenn gesetzt → Kette abbrechen, letzten Output committen. Flag wird in `restorePromptUi()` zurückgesetzt.

### 4.3 Kein Streaming in Phase 4

Streaming (Token-für-Token-Anzeige) wird **nicht** in diesem PR umgesetzt. Die Runner-Interfaces
müssten dafür erweitert werden (`StreamingCompletionRunner`). Das ist ein separates Feature.
Die Progress-Anzeige zeigt nur Step-Status (pending/running/done), keinen Live-Text.

### 4.4 PipelineProgressView

Neues Custom-View oder Layout-Include das in `prompts_keyboard_cl` eingebettet wird:
- `pipeline_progress_view.xml` — LinearLayout mit 2 TextViews (Step-Icon + Name + Duration)
- Wird bei Pipeline-Start `VISIBLE`, ersetzt `prompts_keyboard_rv` + `running_prompt_tv` + `running_pb`
- Bei Pipeline-Ende wird auf `promptsRv` zurückgewechselt (bestehende `restorePromptUi()`-Logik)

## Phase 5: Tastatur-Buttons (History + Resend-Logik)

### 5.1 History-Button in der Tastatur

Neuer `MaterialButton` in `activity_dictate_keyboard_view.xml`:
- Icon: `ic_baseline_history_24` (neues Drawable erstellen — Material Icons "history")
- Tap → öffnet `HistoryActivity` als neue Activity (`FLAG_ACTIVITY_NEW_TASK`)
- **Platzierung**: In der Button-Reihe neben dem Settings-Button. Konkrete ConstraintLayout-Constraints
  müssen beim Implementieren aus dem bestehenden Layout ermittelt werden (Chain-Anpassung nötig)

### 5.2 Resend-Button Logik ändern

**VORHER** (Zeile 454-459):
- Tap = `startWhisperApiRequest()` (neuer API-Call mit gecachter Audio-Datei)

**NACHHER**:
- **Tap** = Zwischengespeichertes Ergebnis einfügen (kein API-Call)
  - Liest den `finalOutput` der letzten Session aus der DB
  - `commitTextToInputConnection(lastOutput, InsertionSource.TRANSCRIPTION)`
  - Schnell, kein Netzwerk nötig
- **Long-Press** = Regenerieren (neuer API-Call)
  - Bisheriges Verhalten: `startWhisperApiRequest()` mit gecachter Audio-Datei
  - Erstellt neue Transcription-Version (v2, v3, ...) in der bestehenden Session

```java
resendButton.setOnClickListener(v -> {
    vibrate();
    // Tap: Letztes Ergebnis direkt einfügen — KEIN DB-Zugriff auf Main-Thread!
    // lastOutput ist in SessionTracker gecacht (gesetzt in resetSession())
    String lastOutput = sessionTracker.getLastOutput();
    if (lastOutput != null) {
        commitTextToInputConnection(lastOutput, InsertionSource.TRANSCRIPTION);
    }
});

resendButton.setOnLongClickListener(v -> {
    vibrate();
    // Long-Press: Neu transkribieren (gleiche Session, neue Version)
    if (audioFile == null) audioFile = new File(getCacheDir(), sp.getString(...));
    sessionTracker.reuseLastSession(); // currentSessionId = lastSessionId
    startWhisperApiRequest();
    return true;
});
```

> **Kein DB-Zugriff auf Main-Thread**: `lastOutput` wird beim Session-Reset in `SessionTracker.resetSession()`
> gecacht (Background-Thread). Der Resend-Tap liest nur die gecachte Instanzvariable.
> `lastSessionId` wird über SharedPreferences persistiert (überlebt Keyboard-Restart).
> `lastOutput` wird beim nächsten Start aus der DB geladen (in `restoreFromPrefs()` erweitern).

---

## Kritische Dateien

| Datei | Änderung |
|-------|----------|
| `database/DictateDatabase.kt` | version=2, 5 Entities, 5 DAOs, Migration, onOpen-Callback |
| `core/DictateInputMethodService.java` | 2 neue Instanzvariablen (sessionTracker, sessionManager) + dbExecutor, commitText-Erweiterung mit InsertionSource, 6 Aufrufstellen, `requestRewordingFromApi` → CompletionResult, `startGPTApiRequestInternal` + ProcessingContext-Parameter, Resend-Button Tap/LongPress, History-Button, Pipeline-Progress-View, Abbrechen-Button |
| `core/SessionTracker.kt` | **NEU** — Session-Lifecycle-Management (analog RecordingManager) |
| `core/ProcessingContext.kt` | **NEU** — Immutable Data-Class als Parameter statt pending*-Instanzvariablen |
| `core/SessionManager.kt` | **NEU** — Orchestriert DB-Operationen via `db.runInTransaction {}` |
| `res/layout/activity_dictate_keyboard_view.xml` | History-Button einfügen, Pipeline-Progress-View im `prompts_keyboard_cl`-Container |
| `core/AutoFormattingService.kt` | FormatResult(text, completionResult?, error?) statt String |
| `ai/AIOrchestrator.kt` | Unverändert |
| `ai/runner/CompletionResult.kt` | Unverändert — wird direkt von `requestRewordingFromApi` zurückgegeben |

## Bestehende Patterns wiederverwenden

- Entity-Konventionen: `@ColumnInfo(name = "...")`, `data class` — siehe `UsageEntity.kt`
- DAO-Konventionen: `@Query`, `@Insert`, `@Transaction` — siehe `UsageDao.kt`, `PromptDao.kt`
- Activity-Pattern: `EdgeToEdge.enable()`, `ViewCompat.setOnApplyWindowInsetsListener`, ActionBar — siehe `UsageActivity.java`, `PromptsOverviewActivity.java`
- Adapter-Pattern: `AdapterCallback` Interface — siehe `PromptsOverviewAdapter.java`
- Database Singleton: `getInstance()` mit `synchronized` — siehe `DictateDatabase.kt`

## Verifikation

1. **DB-Migration**: App installieren → Upgrade → prüfen dass bestehende Prompts/Usage erhalten bleiben
2. **Recording-Session**: Aufnahme machen → in DB prüfen: Session + Transcription + optionale Steps vorhanden
3. **Rewording-Session**: Text selektieren → Prompt klicken → prüfen: Session(type=REWORDING) + Step + TextInsertion
4. **Regenerate**: In Historie → Transcription regenerieren → prüfen: v2 mit is_current=1, v1 mit is_current=0
5. **Versions-Switcher**: Version wechseln → prüfen: is_current Flags korrekt umgesetzt
6. **Post-Processing**: "Weiterverarbeiten" klicken → prüfen: neue Session mit parent_session_id
7. **Clipboard-Logging**: "Kopieren" in Historie klicken → prüfen: TextInsertion mit method=PASTE
8. **Partial Unique Index**: Versuch zwei is_current=1 Rows einzufügen → muss SQLiteConstraintException werfen
9. **Pipeline-Progress**: Recording starten → prüfen: Spinner ersetzt durch 2-Zeilen-Step-Anzeige → nach Commit zurück zu Prompt-Liste
10. **Abbrechen**: Während Pipeline läuft → Abbrechen drücken → prüfen: API-Call abgebrochen, letzter erfolgreicher Output committed, UI zurückgesetzt
11. **Abbrechen Queued-Prompts**: Während Queued-Prompt-Kette → Abbrechen → Kette stoppt, letzter Output committed
12. **History-Button**: In Tastatur sichtbar → Tap öffnet HistoryActivity
13. **Resend Tap**: Nach Recording → Resend drücken → letztes Ergebnis sofort eingefügt (kein API-Call, kein DB-Zugriff)
14. **Resend Long-Press**: → neuer Whisper-Call → neue Transcription-Version (v2) in gleicher Session
15. **lastSessionId Persistenz**: Keyboard-Service killen → neu starten → Resend funktioniert noch
16. **Error-Audit-Trail**: API-Call fehlschlagen lassen → in DB prüfen: Step mit status=ERROR + errorMessage + CompletionLog mit success=false
17. **Audio-Playback**: In Historie → RECORDING-Session öffnen → Play-Button → Audio-Wiedergabe
18. **Audio-Dauer**: Nach Recording prüfen: audioDurationSeconds > 0 in SessionEntity
19. **Empty-State**: Alle Sessions löschen → HistoryActivity zeigt Empty-State
