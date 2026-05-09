# Recherche: Pending-Transkription-Persistenz + Background-Job-Architektur (A+B)

**Datum:** 2026-05-07
**Branch:** `feature/language-chip-curation`
**Recherche-Scope:** Wie das Dictate-IME einen Background-Send mit Persistenz bauen kann — Variante A (WorkManager + DB-Persistenz) **plus** Variante B (Service-lokale Coroutine, gute UX) kombiniert. Ziel: bei der nächsten IME-Aktivierung zeigt der Restart-Button-Bereich entweder „Backgrounded (in Arbeit)" oder „Background-Verarbeitung fertig" — ein Klick fügt ein.
**Verwandter Plan:** [keyboard-layout-refactor.md](../../keyboard-layout-refactor.md) (Layout-Refactor) — diese Recherche ist Vorbereitung für ein **separates** Folge-Feature, nicht Bestandteil des Layout-Refactors.
**Kein Code geschrieben.** Alle Beispiele in diesem Dokument sind als **Pseudo-Code** markiert.

---

## TL;DR

Dictate hat bereits eine erstaunlich gute Ausgangslage:

- **Room-DB** mit voll ausgebauter Pipeline-Persistenz (`sessions`, `transcriptions`, `processing_steps`, `text_insertions`, `completion_log`). Schema-Version 3, mit Double-Enum-Pattern und CHECK-Constraints.
- **`SessionEntity`** trägt heute schon `status ∈ {RECORDED, COMPLETED, FAILED, CANCELLED}` und `last_error_*`. RECORDED = „Audio persistiert, kein Result da" — exakt der Zustand, den der Background-Send bei Service-Death erzeugt.
- **`SessionStatus.COMPLETED` mit `finalOutputText != null`** ist faktisch schon der „Background fertig, Insertion ausstehend"-Zustand. Der Resend-Button liest ihn heute via `SessionTracker.getLastKeyboardSession()` + `ResendStatusDispatcher`.
- **`JobExecutor`** ist eine prozessweite Singleton-Pipeline mit Cooperative-Cancellation. **`ActiveJobRegistry`** ist ein StateFlow-basiertes Live-Registry für „läuft gerade ein Job" — die Reactive-Pipeline existiert teilweise.
- **WorkManager ist NICHT integriert.** Keine Dependency, keine Worker-Klassen.

Damit ist die A+B-Architektur **kein Greenfield**, sondern **Erweiterung dreier bestehender Schichten**:

1. **Persistenz (Room):** Eine kleine Status-Anpassung (`IN_FLIGHT` als optionaler neuer Status, oder rein über `ActiveJobRegistry`-Flag). Keine neue Tabelle nötig — `sessions` ist bereits eine Pending-Queue.
2. **Job-Layer:** `JobExecutor` (B) bleibt der Hot-Path; **WorkManager (A)** läuft als Backup-Re-Enqueue, idempotent durch Session-ID + Status-Check.
3. **UI:** Der Restart-Button-Bereich liest `SessionEntity.status` + `finalOutputText` — die Logik existiert bereits in `ResendStatusDispatcher.decide(...)`. Erweiterung um `BackgroundedSending` und `BackgroundedReady` ist eine reine State-Matrix-Aufnahme.

Empfehlung: **kein neuer `pending_transcription`-Table.** Der existierende `sessions`-Eintrag IST die Pending-Row. Stattdessen ein dünner WorkManager-Worker als Watchdog auf bestehenden `RECORDED`/`FAILED`-Sessions. Begründung in [Abschnitt C](#c-datenmodell-vorschlag).

---

## A. Aktueller Persistenz-Stand

### A.1 DB-Lösung im Einsatz

**Room (androidx.room 2.6.1)** als Single-Source-of-Truth, plus SharedPreferences nur für User-Settings.

| Layer | Datei | Anmerkung |
|-------|-------|-----------|
| Entry-Point | `app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt:42-104` | Singleton `getInstance(context)`, KSP-Schema-Export `app/schemas/`, `allowMainThreadQueries()` (Achtung: heute toleriert, sollte mittelfristig weg) |
| Migrations | `app/src/main/java/net/devemperor/dictate/database/migration/Migrations.kt`, `MigrationTo3.kt` | Recreate-Table-Pattern mit CHECK-Rebuild |
| Konfiguration | `app/build.gradle:55-59` | `room.schemaLocation = $projectDir/schemas` |

**SharedPreferences** (`DictatePrefs.kt`) wird nur für User-Settings genutzt (Provider-Keys, Sprachen-Liste, Pref.SingleRowMode, etc.). **Keine** Pipeline-State-Persistenz mehr — die zwischenzeitliche `lastSessionId`/`lastOutput`-Pref wurde explizit entfernt (siehe `SessionTracker.kt:17-22`, „Phase 9 removed the legacy …"). Das ist relevant: das Projekt hat schon einmal die Reise „Prefs → DB als SoT" gemacht und das ist bewusst der Ziel-Zustand.

**Annahme — zu validieren:** DataStore (Proto/Preferences) wird nicht eingesetzt. Grep `androidx.datastore` ist leer.

### A.2 Existierende Tabellen / Entities / DAOs

Stand Schema-Version 3:

| Tabelle | Entity | DAO | Zweck |
|---------|--------|-----|-------|
| `sessions` | `SessionEntity` (`database/entity/SessionEntity.kt:11-69`) | `SessionDao` (`database/dao/SessionDao.kt`) | **Master-Row pro Pipeline-Run.** Trägt `status`, `origin`, `audio_file_path`, `audio_duration_seconds`, `last_error_*`, `final_output_text`, `input_text`, `queued_prompt_ids`. **Das ist die Pending-Row.** |
| `transcriptions` | `TranscriptionEntity` | `TranscriptionDao` | Versionierte Transcription-Outputs (mehrere Versionen pro Session möglich, `is_current` markiert die aktuelle). |
| `processing_steps` | `ProcessingStepEntity` | `ProcessingStepDao` | Versionierte Pipeline-Steps (auto-format, queued prompts, post-processing). `chain_index` + `version` + `is_current`. |
| `completion_log` | `CompletionLogEntity` | `CompletionLogDao` | Append-only Audit-Trail für jeden API-Call (success + error). |
| `text_insertions` | `TextInsertionEntity` | `TextInsertionDao` | Append-only Log jeder Insertion ins Edit-Feld (auch wer, wann, wohin, replaced_text). **Wichtig** für „wurde schon eingefügt"-Erkennung. |
| `usage` | `UsageEntity` | `UsageDao` | Token-/Kostentracking (legacy). |
| `prompts` | `PromptEntity` | `PromptDao` | User-Prompts. |

Sechs Double-Enums sind bereits in Verwendung oder dokumentiert:
- `SessionStatus`: `RECORDED`, `COMPLETED`, `FAILED`, `CANCELLED` (`database/entity/SessionStatus.kt`)
- `SessionOrigin`: `KEYBOARD`, `HISTORY_REPROCESS`, `POST_PROCESSING` (`database/entity/SessionOrigin.kt`)
- `SessionType`: `RECORDING`, `REWORDING`, `POST_PROCESSING` (`database/entity/SessionType.kt`)
- `StepStatus`: `SUCCESS`, `ERROR`
- `StepType`: `AUTO_FORMAT`, `REWORDING`, `QUEUED_PROMPT`
- `AIProviderException.ErrorType` (Wiederverwendung in `last_error_type`)

### A.3 Migrations-Mechanismus

Versionierung läuft über das Standard-Room-Migration-Pattern (`Migration(N, N+1)`):

- Schema-Bump in `DictateDatabase.kt` → `version = N`.
- Neue Migration in `database/migration/Migrations.kt` (oder eigenes File wie `MigrationTo3.kt`).
- Registrierung in `buildDatabase()` via `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.
- **CHECK-Constraints** für Double-Enum-Spalten erfordern Recreate-Table-Pattern (SQLite kann CHECK nicht via `ALTER TABLE` ändern). `MigrationTo3.kt` zeigt das idiomatisch: `CREATE TABLE sessions_new (...) CHECK (...)`, `INSERT … SELECT … FROM sessions`, `DROP`, `RENAME`, Indizes neu.
- **Test-Pattern:** `MigrationTestHelper` (siehe `docs/DATABASE-PATTERNS.md`, Zeilen 148–183, Template).
- Schema-Snapshots werden via KSP nach `app/schemas/net.devemperor.dictate.database.DictateDatabase/{N}.json` exportiert.

Konsequenz für diese Recherche: **wenn wir einen neuen Status (z.B. `IN_FLIGHT`) brauchen, ist das eine M3→M4-Migration mit CHECK-Rebuild.** Das ist nicht gratis, aber auch kein Show-Stopper. [Abschnitt C](#c-datenmodell-vorschlag) diskutiert, ob es überhaupt nötig ist.

---

## B. Send-Pipeline aktueller Stand

### B.1 Audio-Aufnahme

**Wo aufgenommen:** `RecordingManager.kt:55-83` (`MediaRecorder` mit MPEG_4/AAC, 64 kbps, 44.1 kHz, `.m4a`).

**Wohin geschrieben — Lifecycle in 2 Stufen:**

1. **Cache (transient).** `DictateInputMethodService.startRecording()` (`core/DictateInputMethodService.java:1612-1619`) erzeugt die Datei als `getCacheDir() + "audio.m4a"`. Cache-Dir ist Android-managed — kann jederzeit aufgeräumt werden, deshalb ist sie **nicht** für längere Persistenz geeignet.
2. **Persistent (durable).** Nach Stop wird die Cache-Datei via `RecordingRepository.persistFromCache(cacheFile, sessionId)` (`core/RecordingRepository.kt:45-49`) nach `getFilesDir()/recordings/{sessionId}.m4a` umkopiert. Dann (gleicher synchroner Aufruf) `extractDurationSeconds(...)` (`RecordingRepository.kt:65-80`) und `SessionManager.createSession(..., audioFilePath = recording.audioFile.absolutePath, audioDurationSeconds = ..., initialStatus = RECORDED)` (`PipelineOrchestrator.kt:837-898`).

Diese Sequenz ist **schon das „Persist-First"-Pattern**, das die A+B-Architektur braucht: bevor die API angefasst wird, liegt die Session-Row mit `status = RECORDED` und gültigem Audio-Pfad in der DB. Implementiert wurde das in der Reprocess-Refactor-Iteration (siehe `PipelineOrchestrator.kt:43-52`, „After the reprocess-refactor (Chunk 2)").

**In-Memory vs Disk:** ausschließlich Disk. Es gibt keinen In-Memory-Audio-Blob im Pipeline-Pfad.

### B.2 Transkription

**Provider-Abstraktion:** `ai/AIOrchestrator.kt` (Entry-Point) → `ai/factory/RunnerFactory.kt` → `ai/runner/{OpenAICompatibleRunner, AnthropicCompletionRunner}`. Das ist die Stelle, an der der HTTP-Call eigentlich passiert.

**Async-Pattern:** **Synchron + ExecutorService**, KEINE Coroutines im API-Pfad:

- `AIOrchestrator.transcribe(...)` ist synchron blockierend (siehe Aufruf in `PipelineOrchestrator.kt:1023`).
- Die Pipeline läuft auf einem **dedizierten Single-Thread-`ExecutorService`** in `JobExecutor` (`core/JobExecutor.kt:33`, `Executors.newSingleThreadExecutor()`). Die orchestrator-eigenen `executor`-Felder (`PipelineOrchestrator.kt:145`) sind Legacy und werden für JobExecutor-routierte Jobs **nicht** benutzt — JobExecutor ruft die `*Blocking`-Varianten direkt auf seinem Executor auf.
- Cooperative Cancellation via `CancellationToken` (`core/CancellationToken.kt`) plus `Thread.interrupt()`-Fallback aus `JobExecutor.cancel()` (`JobExecutor.kt:184-187`).

**Konsequenz für A+B:** Coroutines existieren in der UI-Schicht (`ActiveJobRegistry` ist `StateFlow`-basiert) und in `ActiveJobRegistryObserver`, aber **nicht in der Pipeline selbst**. Ein WorkManager-`CoroutineWorker` wäre möglich; einfacher und konsistent ist ein klassischer `Worker` mit `doWork()` blocking.

### B.3 Insertion (Text → Edit-Feld)

Mehrere Pfade, aber alle gehen letztlich durch:

- `DictateInputMethodService.commitTextToInputConnection(...)` (`core/DictateInputMethodService.java:1907-2000`). Das ist die Insertion-Engine: ruft `ic.commitText(output, 1)` und loggt via `SessionManager.logTextInsertion(...)` mit `InsertionMethod.COMMIT` oder `PASTE`.
- Resend/Recovery-Pfad: `ResendInsertStrategy.execute(...)` (`core/ResendInsertStrategy.kt:85-118`) — 3-Stage-Strategie (Live IC → Captured IC → Toast + Resume-Job).
- Long-Press auf den Resend-Button → `ReprocessStaging` (`onResendLongClicked` → `KeyboardUiController.enterReprocessStaging(...)`).

**„Wurde schon eingefügt?"** — heute implizit beantwortet durch User-Verhalten + `text_insertions`-Log. Es gibt **keine** Spalte „is_inserted" auf der Session. Das ist relevant für [Edge Case F.1 + F.2](#f-edge-cases-und-deren-behandlung).

### B.4 Result-Lifecycle (Wo lebt der Result zwischen Transkription und Insertion?)

Drei parallele Quellen:

1. **`processing_steps.output_text`** des `is_current` Steps — kanonisch.
2. **`transcriptions.text`** der `is_current` Version — Fallback wenn keine Steps.
3. **`sessions.final_output_text`** — denormalisierter Cache, von `SessionManager.updateFinalOutputText(...)` synchron mit (1)/(2) gehalten.

`SessionManager.getFinalOutput(sessionId)` (`core/SessionManager.kt:372-385`) implementiert genau diese Fallback-Kette und ist **die kanonische Lese-Operation** für „Result-Text dieser Session".

**Datenfluss in der heutigen Pipeline:**

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

Heute ist der Pfad **synchron-blocking-but-non-UI** und die Insertion passiert **direkt nach** der Pipeline. Das Window für Service-Death (Killed-by-OS, App-Switch) ist offen — die Pipeline überlebt zwar bis zum Ende des `JobExecutor`-Threads, aber wenn der Prozess vorher stirbt, ist der `final_output_text` zwar in der DB, aber die Insertion ist nicht passiert (und das `text_insertions`-Log ist leer). Das ist genau der Zustand, den die A+B-Architektur als „BackgroundedReady" anzeigen will.

---

## C. Datenmodell-Vorschlag

### C.1 Empfehlung: KEIN neuer `pending_transcription`-Table

Begründung — die User-Anforderung „Variante A+B kombiniert" lässt sich vollständig auf die existierende `sessions`-Tabelle abbilden:

| User-Wunsch | Bereits vorhanden | Erweiterung nötig |
|-------------|-------------------|-------------------|
| Audio + Metadata persistieren | `sessions.audio_file_path`, `sessions.audio_duration_seconds`, `sessions.target_app_package`, `sessions.queued_prompt_ids`, `sessions.language` | — |
| Result speichern, bis User einfügt | `transcriptions.text`, `processing_steps.output_text`, `sessions.final_output_text` | — |
| Status-Tracking (PENDING / IN_FLIGHT / COMPLETED / INSERTED / DELETED) | `SessionStatus.{RECORDED, COMPLETED, FAILED, CANCELLED}` | siehe C.2 — entweder `IN_FLIGHT` neuer Status **oder** rein über `ActiveJobRegistry` |
| „Insertion ausstehend" markieren | implicit: `COMPLETED` ohne korrespondierenden `text_insertions`-Eintrag | siehe C.3 |
| User-Identity / IME-Service-ID | `sessions.target_app_package` (für „in welche App gehörte das") | siehe Edge-Cases [F.3](#f3-gleiches-audio-ime-switch-dazwischen-wem-gehoert-die-pending-session) |
| Lifecycle-Cleanup | `RecordingRepository.deleteBySessionId()`, `DurationHealingJob` (Idempotenz-Heilung) | siehe C.4 |

Eine neue Tabelle `pending_transcription` wäre fast **eine Kopie der `sessions`**-Tabelle. Das verletzt SOLID/SRP nicht, aber es führt zu Doppel-Pflege:
- Migrations doppelt: jede Schema-Änderung an Audio-Path / Language / queued_prompt_ids muss in beiden Tabellen.
- Source-of-Truth-Konflikt: was ist „die Session"? Die in `sessions` oder die in `pending_transcription`? Wenn der User später aus History eine alte Session re-prozessiert (`SessionOrigin.HISTORY_REPROCESS`), würde sie in beide Tabellen geschrieben → DRY-Verletzung.
- `JobExecutor`, `PipelineOrchestrator`, `ResendStatusDispatcher`, `SessionTracker.getLastKeyboardSession()` würden alle zwei Quellen abfragen müssen.

**Trade-off, falls explizit gewünscht:** Eine separate Tabelle wäre architektonisch sauberer, wenn „Pending" semantisch fundamental anders wäre als „Session" (z.B. wenn Pendings nur kurz leben und Sessions History sind). Da Sessions aber **schon heute** alle Lebenslagen abbilden (RECORDED = Audio da, kein Result; COMPLETED = Result da; FAILED = Error; CANCELLED = abgebrochen), ist die Eins-zu-Eins-Abbildung gegeben. Empfehlung: **eine Tabelle**.

### C.2 Status-Modell — zwei Optionen

**Option A — Minimalismus (empfohlen):** Kein neuer Status. Stattdessen wird „IN_FLIGHT" durch die Live-Existenz im `ActiveJobRegistry` (StateFlow, Kotlin-`object`, prozessweit) repräsentiert. DB-Status-Übergänge bleiben:

```
RECORDED ──(JobExecutor läuft)──→ COMPLETED
                              │
                              ├──→ FAILED
                              │
                              └──→ CANCELLED
```

**Mapping zu User-States:**

| User-Wunsch | DB-Status | Live-Signal |
|-------------|-----------|-------------|
| `PENDING` | `RECORDED` (= Audio da, Pipeline noch nicht durch) | `ActiveJobRegistry.isActive(id) == false` |
| `IN_FLIGHT` | `RECORDED` | `ActiveJobRegistry.isActive(id) == true` (Service-Coroutine ODER WorkManager läuft) |
| `COMPLETED` | `COMPLETED` mit `final_output_text != null` | — |
| `INSERTED` | `COMPLETED` mit zugehörigem `text_insertions`-Eintrag (siehe C.3) | — |
| `DELETED` | Row weg (`onDelete = CASCADE` räumt audio + transcriptions + steps mit) | — |
| `FAILED` | `FAILED` mit `last_error_*` | — |

Vorteil: keine Migration, keine Kombinatorik-Explosion, alle bestehenden Queries bleiben gültig.

**Option B — Explizit (alternative):** `IN_FLIGHT` als neuer DB-Status. Erfordert:
- M3→M4-Migration mit Recreate-Table (`CHECK (status IN ('RECORDED', 'IN_FLIGHT', 'COMPLETED', 'FAILED', 'CANCELLED'))`).
- Bump in `database/entity/SessionStatus.kt` + Doku in `DATABASE-PATTERNS.md`.
- Doppelter-Schreib-Punkt: WorkManager UND Service-Coroutine müssen `IN_FLIGHT` setzen, beide müssen es wieder zurücksetzen.

Nachteil: WorkManager-Job-Crashs (Process-Death) hinterlassen `IN_FLIGHT` Stale-Rows, die ein Healing-Job (analog `DurationHealingJob`) auf `RECORDED` zurücksetzen muss. Das fügt eine weitere konsistenz-kritische Schreiboperation ein.

**Empfehlung:** **Option A.** Begründung:
- `ActiveJobRegistry` existiert bereits und erfüllt die Funktion in-process exakt.
- Cross-process-Sicht („läuft jetzt ein Job?") ist im IME-Kontext nicht relevant — der IME-Service-Prozess ist der einzige, der Jobs startet. Der WorkManager-Worker läuft im selben Prozess (siehe [D](#d-workmanager-integration)).
- Die kombinatorische Klarheit (Sessionsstatus + Live-Flag = 2 Achsen → wenige State-Tupel) ist einfacher zu testen als 5 DB-Status mit Übergangs-Matrix.

### C.3 „Wurde schon eingefügt?" — Detection-Mechanismus

Heute kann man das aus `text_insertions` ableiten: existiert ein `text_insertions`-Eintrag mit `session_id = X` UND `inserted_text` matcht `sessions.final_output_text`, dann ist eingefügt.

**Vorschlag — Helper-Query** (Pseudo-Code DAO):

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

Alternativ schlanker: ein Spalte `inserted_at: Long?` auf `sessions` (denormalisiert, M3→M4-Migration). Vorteil: o(1)-Lookup, kein Text-Vergleich. Nachteil: noch eine Schreib-Stelle, die mit `text_insertions` synchron gehalten werden muss.

**Empfehlung:** denormalisiertes Feld `sessions.inserted_at: Long?`. Begründung:
- Liegt in der etablierten "Denormalized Cache Columns"-Familie (siehe `DATABASE-PATTERNS.md`, „Denormalized Cache Columns" Abschnitt).
- O(1)-Query — wichtig für die Liste der Pending-Sessions auf dem Restart-Button (potentiell mehrere Pendings).
- Update-Site ist genau eine: `commitTextToInputConnection` (oder via `SessionManager.logTextInsertion`, das bereits zentral ist).

### C.4 Audio-File-Lifecycle: Disk vs. Blob

**Empfehlung: File-Path** (so wie heute, `sessions.audio_file_path` zeigt auf `getFilesDir()/recordings/{sessionId}.m4a`).

| Disk-File | DB-Blob |
|-----------|---------|
| **+** Streaming-Upload möglich (Whisper akzeptiert m4a + opus nativ) | – Whole-Blob in Memory beim Upload |
| **+** Schon implementiert, getestet, robust (`RecordingRepository`, `DurationHealingJob`) | – Migration der vorhandenen Audio-Files nötig |
| **+** Schemata vergleichbar (kein BLOB-Mega-Row) | – Room-Performance bei großen BLOBs (>1 MB) ist mäßig |
| **+** Cleanup orthogonal: Datei kann gelöscht werden (z.B. nach Insertion + Cooldown), ohne die Session zu verlieren | – Foreign-Backup-Tools sehen die Audios mit |
| – Inkonsistenz möglich (DB-Row sagt „Datei da", Datei ist weg → `DurationHealingJob` heilt das) | **+** Atomarer DB-Transaktions-Scope |

**Cleanup-Policy (schon implementiert + erweiterungsfähig):**
- `RecordingRepository.deleteBySessionId()` löscht die Datei und setzt `audio_file_path = NULL` (`RecordingRepository.kt:136-148`).
- `onDelete = CASCADE` auf `parent_session_id` räumt Child-Sessions automatisch.
- `DurationHealingJob` (`database/DurationHealingJob.kt`) heilt Inkonsistenzen beim App-Start (Datei weg → Status auf FAILED + UNKNOWN-ErrorType).

**Zu validieren / Annahme:** Heute werden Audio-Files **nie automatisch gelöscht** — sie sammeln sich in `recordings/`. Für die Pending-Lifecycle-Logik schlägt diese Recherche eine Auto-Cleanup-Policy vor: Audio-File löschen, **sobald** `sessions.inserted_at != null` UND `now() - inserted_at > X Tage` (X-Vorschlag: 7). Die Session-Row bleibt für History-Zwecke erhalten, der `final_output_text` ist denormalisiert in der Row.

### C.5 Vorschlags-Tabelle (Erweiterung von `sessions`, KEIN neuer Table)

Tabelle als **Migrations-Plan M3→M4**. Eckig markiert, was wirklich neu ist:

| Spalte | Typ | Default | Constraints | Status | Bedeutung |
|--------|-----|---------|-------------|--------|-----------|
| `id` | TEXT NOT NULL PRIMARY KEY | — | — | ✅ vorhanden | UUID, vom Caller pre-allocated (`PipelineConfig.preAllocatedSessionId`) |
| `type` | TEXT NOT NULL | — | (Double-Enum: SessionType) | ✅ vorhanden | RECORDING / REWORDING / POST_PROCESSING |
| `created_at` | INTEGER NOT NULL | — | — | ✅ vorhanden | `System.currentTimeMillis()` |
| `target_app_package` | TEXT | NULL | — | ✅ vorhanden | aus `EditorInfo.packageName` |
| `language` | TEXT | NULL | — | ✅ vorhanden | aus `LanguageController.getEffectiveLanguage()` |
| `audio_file_path` | TEXT | NULL | — | ✅ vorhanden | absolut, `getFilesDir()/recordings/{id}.m4a` |
| `audio_duration_seconds` | INTEGER NOT NULL | — | — | ✅ vorhanden | aus `MediaMetadataRetriever` synchron beim Persist |
| `parent_session_id` | TEXT | NULL | FK CASCADE | ✅ vorhanden | für POST_PROCESSING-Children |
| `status` | TEXT NOT NULL | `'COMPLETED'` (Migrations-Default) | CHECK in 4-elementigem Set | ✅ vorhanden | RECORDED / COMPLETED / FAILED / CANCELLED |
| `origin` | TEXT NOT NULL | `'KEYBOARD'` | CHECK | ✅ vorhanden | KEYBOARD / HISTORY_REPROCESS / POST_PROCESSING |
| `queued_prompt_ids` | TEXT | NULL | — | ✅ vorhanden | comma-separated prompt-IDs |
| `last_error_type` | TEXT | NULL | CHECK in `AIProviderException.ErrorType` ohne CANCELLED | ✅ vorhanden | nur bei status=FAILED |
| `last_error_message` | TEXT | NULL | — | ✅ vorhanden | freier Text |
| `final_output_text` | TEXT | NULL | — | ✅ vorhanden | denormalisierter Output für UI |
| `input_text` | TEXT | NULL | — | ✅ vorhanden | denormalisierter Input |
| **`inserted_at`** | **INTEGER** | **NULL** | **—** | **🆕 M4-Migration** | **Wallclock der ersten Insertion (für „BackgroundedReady" vs „eingefügt" Detection); NULL solange kein Insert passierte. Update-Site: `SessionManager.logTextInsertion()`** |
| **`sticky_until`** | **INTEGER** | **NULL** | **—** | **🆕 (optional, M4)** | **Wallclock-Zeit, bis zu der die Session als „BackgroundedReady" auf dem Restart-Button beworben werden soll. Default: `inserted_at + 24h`. Nach Ablauf zeigt der Restart-Button die Session nicht mehr aktiv an, sie bleibt aber in der History. Diese Spalte ist optional; ohne sie wird die User-Stickiness rein über `inserted_at IS NULL` ausgedrückt. Trade-off: sticky_until verkompliziert das Modell, aber gibt UX-seitig mehr Kontrolle.** |

Indizes: bestehende bleiben (`status`, `origin`, `created_at`, `parent_session_id`, `type`). Neuer Index empfohlen:

| Index | Begründung |
|-------|------------|
| `index_sessions_pending` auf `(origin, status, inserted_at)` | für „die Liste der Pending-Sessions, die der User noch einfügen kann" — Query: `WHERE origin = 'KEYBOARD' AND status IN ('RECORDED', 'COMPLETED') AND inserted_at IS NULL ORDER BY created_at DESC`. Ohne Index O(N), mit Index O(log N + K). |

**Lifecycle-Policy zusammengefasst:**

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

## D. WorkManager-Integration: A+B-Architektur

### D.1 Job-Start-Flow (kombiniert)

Der heutige Pfad (B nur) ist:

```
runTranscriptionViaOrchestrator()
  → JobExecutor.start(TranscriptionPipeline) [in Service-Process, registriert in ActiveJobRegistry]
       → executor.submit { orchestrator.runTranscriptionPipelineBlocking(...) }
            → status: RECORDED → COMPLETED
            → callback.onPipelineCompleted(text)
                 → commitTextToInputConnection(...)  // Insert sofort
```

Vorschlag (A+B kombiniert):

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

**Schlüssel-Idee „Backup-mit-Initial-Delay":** Der WorkManager-Job ist **kein paralleler Konkurrent**, sondern ein **Watchdog**. Er ist mit Initial-Delay konfiguriert (z.B. 60 Sekunden), sodass die Service-Coroutine in 95% der Fälle schon fertig ist, bevor der Worker überhaupt startet. Der Worker prüft dann (siehe D.3):

1. Ist die Session schon `COMPLETED` und `final_output_text` gesetzt? → No-Op, fertig.
2. Ist sie noch `RECORDED`? → Service ist gestorben → Worker übernimmt die Pipeline.
3. Ist `ActiveJobRegistry.isActive(sessionId)` = true? → Kein Re-Run, Worker reschedulet sich (Retry mit Backoff).

Damit ist der Worker **idempotent über DB-State** — der einzig konsistente Synchronisationspunkt über Process-Boundaries hinweg.

### D.2 WorkManager-Konfiguration im Detail

| Setting | Wert | Begründung |
|---------|------|-----------|
| `Worker`-Klasse | `PipelineWatchdogWorker : Worker` (kein `CoroutineWorker`) | Pipeline ist heute synchron; ein klassischer Worker passt besser zur bestehenden ExecutorService-Architektur. |
| Network Constraint | `NetworkType.CONNECTED` | Pipeline braucht Internet (Whisper / GPT). Ohne Connectivity macht ein Re-Run keinen Sinn — WorkManager re-tried automatisch wenn Network kommt. |
| Battery Constraint | KEINE (nicht `setRequiresBatteryNotLow`) | User-Wunsch ist „Recording ist passiert, jetzt soll es fertig werden". Battery-Constraint würde bei Low-Battery den Worker verzögern. |
| Storage Constraint | KEINE | Audio liegt schon auf Disk, kein Risiko. |
| Backoff-Policy | `BackoffPolicy.EXPONENTIAL`, `initialBackoff = 30s` | Bei Transient-Error (z.B. Rate-Limited) macht Linear-Retry-mit-30s wenig Sinn — Exponential gibt der API Atemzeit. |
| Initial-Delay | `60s` | siehe D.1 — gibt Service-Coroutine Vorrang. **Trade-off zu validieren:** 30s könnte schon reichen. |
| Unique-Tag | `"pipeline-${sessionId}"` | Ein Worker pro Session, garantiert. |
| `ExistingWorkPolicy` | `KEEP` | wenn beim Re-Trigger bereits ein Worker für diese Session enqueued ist, nichts tun. |
| `Worker.Result` | `success()` bei DB-Status COMPLETED, `retry()` bei NETWORK_ERROR / RATE_LIMITED, `failure()` bei INVALID_API_KEY / BAD_REQUEST | Maps auf `AIProviderException.ErrorType`. |
| Foreground-Service-Notification | Optional, NICHT empfohlen für MVP | Würde Battery-Whitelist + Notification-Permission (API 33+) erfordern. Für ein IME-Use-Case ist das overkill. |

Dependency: `androidx.work:work-runtime-ktx:2.10.x` (zu validieren — es gibt aktuell kein WorkManager-Eintrag in `gradle/libs.versions.toml`). Die Lib bringt ihren eigenen Init-Provider (Manifest-Merger), kein expliziter `WorkManager.initialize(...)` nötig.

### D.3 Idempotenz: wie verhindern wir Doppel-Send?

Drei Schichten:

**Schicht 1 — `ActiveJobRegistry` (in-process Lock):**
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

**Schicht 2 — DB-Status-Check.** Der `RECORDED → COMPLETED`-Transition ist atomar (eine UPDATE-Anweisung), also gibt es keinen Mid-Transition-State, in den der Worker hineinfallen könnte. Wenn der Worker das `RECORDED` liest, ist die Pipeline **definitiv noch nicht durch**. Wenn er `COMPLETED` liest, ist sie **definitiv durch**.

**Schicht 3 — `enqueueUniqueWork` mit `KEEP`.** Selbst wenn der User mehrfach `runTranscriptionViaOrchestrator` aufruft (unmöglich heute, da `JobExecutor.start` `false` zurückgibt bei aktivem Job), würde der WorkManager keinen zweiten Worker für dieselbe Session-ID anlegen. Single-source per Tag.

**Race-Edge:** WorkManager startet seinen Worker MS-genau gleichzeitig wie der Service-Process aufwacht und JobExecutor seine Resume-Routine startet. Lösung: `JobExecutor.start()` returniert `false` wenn ein Job bereits aktiv ist (`ActiveJobRegistry.register(...)` schlägt fehl), und der Worker macht `Result.retry()`. Beim nächsten Backoff-Slot (30s+) ist der Race aufgelöst.

### D.4 Pseudo-Code: Service-Coroutine-Pfad (Variante B)

Hier ist die Coroutine-Erweiterung minimal, weil JobExecutor heute schon der Hot-Path ist. Trade-off: heute läuft der Job auf einem `ExecutorService`-Thread; bei Service-Death wird er via `executor.shutdownNow()` interrupted. Wenn der Service-Process komplett killed wird (OOM, ANR), stirbt der Thread mit. **Genau das ist der Fall, in dem WorkManager übernimmt.**

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

**Was passiert, wenn der Service-Coroutine-Pfad (B) erfolgreich ist?**
- Status wird auf `COMPLETED` gesetzt → 60 Sekunden später wacht der Worker auf → liest `COMPLETED` → `Result.success()` → fertig. Kein Side-Effect, keine Kosten.

**Was passiert, wenn der Service stirbt mid-flight?**
- Die ExecutorService-Thread wird interrupted, aber `sessions.status` bleibt auf `RECORDED` (kein `finalize*()` rief `updateStatus`) ODER es passiert ein `finalizeFailed` mit NETWORK_ERROR (wenn Pipeline schon einen API-Call abgesetzt hatte und InterruptedException vom OkHttp-Stack kam).
- In beiden Fällen wacht WorkManager 60s später auf, liest die DB, übernimmt.

**Was passiert, wenn das Netz weg ist und der Service-Coroutine-Pfad scheitert mit NETWORK_ERROR?**
- Status: `FAILED` mit `last_error_type = NETWORK_ERROR`.
- Worker wacht auf, liest `FAILED` → returnt `Result.success()` (der User entscheidet selbst via Retry-UI).
- **Alternative — strittig:** Worker könnte auch bei `FAILED + NETWORK_ERROR` einen Retry triggern (Result.retry()), weil das nicht-terminal ist. Trade-off:
   - Pro: User-Wunsch „auto-retry" wenn Netz wiederkommt.
   - Contra: User hat in dem Moment evtl. bereits eine andere Recording gestartet — Re-Run dieser ist verwirrend.
   - Empfehlung: **MVP — nicht auto-retry**. User muss explizit den Retry-Button drücken. Auto-Retry bei Network-Error kann V2 sein.

---

## E. UI-Kommunikation: DB → View

### E.1 Reactive Pipeline (Status quo + Erweiterung)

**Status quo:**
- DAOs sind synchron (`fun getById(...): SessionEntity?`, `fun findLatestByOrigin(...): SessionEntity?`). KEIN `Flow<>`, KEIN LiveData.
- Live-State läuft heute über `ActiveJobRegistry.state: StateFlow<Map<sessionId, JobState>>` und wird via `ActiveJobRegistryObserver.observe(owner, listener)` (`core/ActiveJobRegistryObserver.kt:23-47`) Java-freundlich exponiert (`repeatOnLifecycle(STARTED)`).
- DB-State wird **on-demand** geladen — entweder beim View-Recreate (`onCreateInputView`) oder beim User-Klick (`SessionTracker.getLastKeyboardSession()` → DB-Query, RAM-cached).

**Vorschlag — minimaler, konsistenter Ausbau:**

1. **DAO-Erweiterung — neue Flow-Methoden in `SessionDao`** (Pseudo-Code):
```
[Pseudo-Code, DAO-Erweiterung]
@Query("""SELECT * FROM sessions
   WHERE origin = 'KEYBOARD'
     AND status IN ('RECORDED', 'COMPLETED')
     AND inserted_at IS NULL
   ORDER BY created_at DESC""")
fun observePendingKeyboardSessions(): Flow<List<SessionEntity>>
```
   Room generiert daraus automatisch einen Flow, der bei DB-Mutationen re-emittiert. Voraussetzung: das Modul hat `androidx.room:room-ktx` (ist schon in `app/build.gradle:84`).

2. **Repository / Composite-Reader.** `PendingTranscriptionReader` (neue Kotlin-Klasse), die:
   - Den DB-Flow oben mit dem `ActiveJobRegistry.state`-StateFlow **kombiniert** (via `kotlinx.coroutines.flow.combine`),
   - daraus eine `StateFlow<List<PendingViewState>>` baut, in der jede Session als `PendingViewState.{Sending, Ready, Failed}` (Sealed Class, siehe E.3) zugeordnet wird.

3. **UI-Konsumer.** Der Restart-Button-Bereich (heute single-Resend-Button mit `ResendStatusDispatcher`) wird zu einem Render-Konsumer dieses State-Flow. Patterns:
   - Im IME-Service via `lifecycleScope.launch { reader.state.collect { ... } }` analog `ActiveJobRegistryObserver`.
   - Oder, falls IME-Service kein `lifecycleScope` hat, ein eigener `CoroutineScope(SupervisorJob() + Dispatchers.Main)` mit Scope-Tear-Down in `onDestroy`.

### E.2 View-Recreate-Verhalten

**Was passiert beim Re-Inflate (`onCreateInputView`)?**

Heute reicht ein synchroner Pull aus `SessionTracker.getLastKeyboardSession()` + Auswertung via `ResendStatusDispatcher`. Mit der Reactive-Pipeline (E.1) wird das zum Cold-Start des Flow-Subscriptions:

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

**Lifecycle-Disziplin:** der `serviceScope` MUSS in `onDestroy` geschlossen werden. Sonst leaken die Coroutines über View-Lifecycles hinweg.

### E.3 Restart-Button-States

Erweiterung von `ResendAction` um die Background-States. Sealed-class-Erweiterung:

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

`BackgroundedReady` ist **funktional identisch** mit `Insert` — die Unterscheidung ist rein für UI-Beschriftung. Beide triggern beim Klick `commitTextToInputConnection(...)` und `SessionManager.logTextInsertion(...)` (was `inserted_at` setzt → State wird zu `Empty`).

Dispatcher-Erweiterung (Pseudo-Code):

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

Diese Logik subsumiert das heutige `ResendStatusDispatcher`-Verhalten 1:1, ergänzt aber die Background-States. Migration: `ResendStatusDispatcher` wird zu `RestartButtonStateMapper` erweitert, der bestehende Dispatcher kann eine schmale Adapter-Schicht bleiben oder ganz ersetzt werden (Refactor-Risiko: niedrig, gut testbar — heutiger Dispatcher hat schon Unit-Tests).

---

## F. Edge-Cases und deren Behandlung

### F.1 User hat 3 Pending Transcriptions, alle fertig — Queue oder Stack?

**Problem:** Heute gibt es **eine** Resend-Action. Mit der A+B-Architektur kann es mehrere `BackgroundedReady`-Sessions gleichzeitig geben (User recorded zweimal hintereinander, beide werden background-completed, aber keine eingefügt).

**Vorschlag:**

| Variante | UX | Kosten |
|----------|----|---------|
| **Stack (LIFO, neuestes zuerst)** | Restart-Button zeigt das **neueste** ungenutzte Pending. User klickt → eingefügt → nächstältestes wird angezeigt. Wenn der User das ältere nicht will, kann er via Long-Press das Stack-Detail aufklappen. | Niedrig. Erfordert nur einen sortierten DB-Query (ORDER BY created_at DESC) und das Mapping auf den Restart-Button. |
| **Queue (FIFO, ältestes zuerst)** | Restart-Button zeigt das **älteste** Pending. | Verwirrender — der User hat gerade die *neue* Recording gemacht und erwartet, dass die nächste ist, was er einfügt. |
| **Liste / Sheet** | Long-Press zeigt Liste, kurzer Klick ist immer das neueste. | Hybrid; ist UX-mäßig vermutlich am robustesten. |

**Empfehlung:** **Stack (LIFO) für Default-Klick, Long-Press öffnet Liste/BottomSheet** — analog dem heutigen `onResendLongClicked` → `ReprocessStaging`-Pattern. Damit ist die Default-UX schnell, und Power-User bekommen Detailkontrolle.

**Annahme — zu validieren:** Der „typische" User-Flow ist sequenziell (eine Recording, einfügen, nächste Recording). Multi-Pending tritt nur auf, wenn der User in eine andere App switcht ohne einzufügen, dann zurückkommt — Multi-Pending ist also Edge-Case, nicht Hot-Path.

### F.2 User hat 1 Pending, Failed — UI?

State: `BackgroundedFailed`. Auf dem Restart-Button:
- Beschriftung: „Letzte Aufnahme fehlgeschlagen" + Icon (z.B. Warning).
- Default-Klick: Retry, falls `canRetry = true` (siehe `isRetryable`-Logik unten).
- Long-Press: Detail-Sheet mit Error-Message + manueller Retry-Button + Discard-Button.

**`isRetryable` Heuristik:**
```
[Pseudo-Code]
fun isRetryable(errorType: AIProviderException.ErrorType): Boolean = when (errorType) {
    NETWORK_ERROR, RATE_LIMITED, SERVER_ERROR, UNKNOWN -> true
    INVALID_API_KEY, MODEL_NOT_FOUND, BAD_REQUEST -> false   // User-Action nötig
    CANCELLED -> false                                        // sollte hier nicht landen (CHECK)
}
```

Wenn `!canRetry`: Long-Press-Sheet zeigt Erklärung („Ungültiger API-Key") + Settings-Shortcut. Der heutige `InfoBarController` (`core/DictateInputMethodService.java:219`) zeigt diese Errors bereits transient — neu ist nur die persistente Anzeige am Restart-Button.

### F.3 Gleiches Audio, IME-Switch dazwischen — wem gehört die Pending-Session?

**Problem:** Der User startet die Recording im IME, switcht zwischendurch zur Standard-Tastatur (oder einer anderen IME), und die Pipeline läuft im Background weiter. Beim nächsten Aufrufen von Dictate — gehört die Pending zu „diesem User" oder „dieser App-Instance"?

**Heutige Antwort der Architektur:**

- Die `sessions`-Tabelle ist app-weit (eine `dictate.db` pro Installation). Ein „User" gibt es nicht — Dictate ist single-user pro Device.
- `target_app_package` (`SessionEntity.targetAppPackage`) ist gespeichert — wir wissen, in welche App die Output gehören sollte.
- `origin = KEYBOARD` markiert IME-Originated.

**Implikationen:**

| Szenario | Antwort |
|----------|---------|
| Nutzer wechselt vom Dictate-IME zur Gboard, dictate-Pipeline läuft weiter | Service stirbt evtl., WorkManager übernimmt. Pending bleibt als `RECORDED`/`COMPLETED` in der DB. |
| Nutzer wechselt zurück zu Dictate, in der **gleichen App** wie ursprünglich | Restart-Button zeigt `BackgroundedReady`. `target_app_package` matcht aktuelles `EditorInfo.packageName` → kein Konflikt. |
| Nutzer wechselt zurück zu Dictate, aber jetzt in einer **anderen App** | Restart-Button zeigt trotzdem `BackgroundedReady` (Default-Verhalten). User muss entscheiden, ob er den alten Output hier einfügen will. |

**Empfehlung — UX-Vorschlag:**

- **Default:** Pending wird angezeigt unabhängig von der aktuellen App. Der User trägt die Verantwortung.
- **Optional Tooltip / Long-Press-Detail:** „Ursprünglich aufgenommen für com.example.foo. Trotzdem hier einfügen?"
- **Hard-Filter (nicht empfohlen, aber zu diskutieren):** Pending nur anzeigen, wenn `target_app_package == currentEditorPackage`. Verstecke ältere Pendings für andere Apps in der Long-Press-Liste. — Trade-off: zu restriktiv, User verlieren ihre Output.

**Identity:** Es gibt **keine User-Identity** auf Device-Level (Multi-User-Android wäre ein anderes Profil → andere App-Daten). `IME-Service-ID` ist die App-Installation. Wir brauchen also nichts Neues.

### F.4 App neu installiert während Pending offen

**Was passiert:**
- App-Uninstall löscht `dictate.db` und `getFilesDir()/recordings/*` (Android-managed).
- Reinstall = leere DB. Keine alten Pendings mehr.
- **Audio-Files in fremden Verzeichnissen** (z.B. wenn User manuell Backup gemacht hat) bleiben unsichtbar — keine Foreign-Insertion.

**Cleanup-Bedarf:** Keiner. Android räumt App-Daten beim Uninstall mit.

**Foreign-Insertion-Frage:** Soll der User „seine" alten Audios von einem Backup-Restore zurückspielen können? — Nein, das ist Out-of-Scope. Audio-Files gehören zu `sessions`-Rows; ohne die Row können wir die Datei nicht zuordnen (UUID-Filename ist zwar deterministisch, aber alle Metadata sind in der Row).

---

## G. Bestehende WorkManager-Quellen im Repo

**Ergebnis:** **Keine.** Der Repo enthält weder eine `androidx.work`-Dependency in `gradle/libs.versions.toml` noch eine Worker-Klasse oder einen `enqueueUniqueWork`-Aufruf in `app/src/main`.

Greps verifiziert:
- `grep -rn "androidx.work" /home/lukas/WebStorm/Dictate/app/src` → leer
- `grep -n "WorkManager\|androidx.work" /home/lukas/WebStorm/Dictate/gradle/libs.versions.toml` → leer
- `grep -rn "WorkManager\|enqueueUniqueWork\|OneTimeWorkRequest" /home/lukas/WebStorm/Dictate/app` → leer

**Konsequenz:**
- WorkManager-Library + Worker-Klasse müssen neu hinzugefügt werden.
- Manifest-Provider wird von der `work-runtime-ktx` Lib automatisch eingebracht (Manifest-Merger), keine manuelle `<provider>` Einträge nötig.
- Initialisierung: kein `WorkManager.initialize(...)` Call nötig (Default-Provider). Falls Custom-Configuration (z.B. Custom-Logger) gewünscht ist, würde eine `Configuration.Provider`-Implementierung in `DictateApplication` reichen — ist für MVP aber nicht erforderlich.

**Was bereits existiert und re-nutzbar ist:**
- `DurationHealingJob` als Inspiration für „One-Shot-Job auf App-Start, idempotent". Der Watchdog-Worker folgt dem gleichen Idempotenz-Stil (DB-Check → No-Op falls fertig).
- `JobExecutor.start()` als Single-Entry-Point für die Pipeline. Der WorkManager-Worker MUSS durch JobExecutor routen, nicht direkt `PipelineOrchestrator` aufrufen — sonst wäre der `ActiveJobRegistry`-Lock umgangen.
- `ActiveJobRegistry.isActive(sessionId)` als Cross-Caller Lock (Service-Coroutine + Worker beide sehen denselben Wert, da sie im selben Process laufen).

---

## H. Risiken & offene Fragen

| Risiko | Impact | Mitigation / offene Frage |
|--------|--------|---------------------------|
| Worker läuft im **selben Process** wie der IME-Service — Process-Death betrifft beide | Hoch — limitiert die Robustheit von Variante A | Verifizieren via Doku: `WorkManager` startet Workers im Default-Process der App, nicht in einem separaten. Wenn der IME-Process killed wird, kann der Worker erst beim nächsten Process-Wakeup laufen (z.B. Broadcast, AlarmManager-Trigger). **Annahme — zu validieren:** WorkManager wakelt den Process selbst auf, wenn Constraints erfüllt sind (Network connect Broadcast). Praktische Konsequenz: nach Process-Death dauert es bis zur nächsten User-Interaktion oder bis WorkManager sich selbst wakelt. |
| `allowMainThreadQueries()` in `DictateDatabase` führt zu möglichen Main-Thread-DB-Zugriffen | Mittel | Bestehender Trade-off; UI-Konsumer für `BackgroundedReady`/`BackgroundedSending` MÜSSEN über Coroutines (StateFlow.collect) gehen, nicht synchron. |
| Kombinatorische UI-State-Explosion durch `BackgroundedReady`/`BackgroundedSending`/`BackgroundedFailed` × Multi-Pending × bestehende `RestartButton`-Variants | Mittel | RestartButton zeigt **immer nur die neueste** Pending (LIFO-Stack). Multi-Pending ist via Long-Press-Sheet sichtbar. Damit ist die State-Matrix linear in der Anzahl Status, nicht kreuz-multiplikativ mit Anzahl Pendings. |
| Wenn der WorkManager-Worker beim Re-Run dieselbe Audio-Datei hochlädt, die Service-Coroutine aber inzwischen schon einen Step-Result geschrieben hat, gibt es Inkonsistenz im Step-Chain | Hoch | `JobExecutor.start()` mit `JobRequest.Resume` läuft durch `PipelineOrchestrator.resumePipelineBlocking`, das von der **letzten erfolgreichen Stelle** weitermacht (siehe `PipelineOrchestrator.kt:359-488`, `lastSuccessChainIndex`). Idempotenz auf Step-Level ist also schon implementiert. |
| Datenbankvolumen: bei 100 Pendings/Tag explodiert `recordings/` schnell (Audio-Files ~50 KB/sec) | Mittel | Auto-Cleanup-Policy implementieren: nach `inserted_at + 7 Tagen` löscht ein periodischer Job (auch ein WorkManager-`PeriodicWorkRequest`) das Audio-File via `RecordingRepository.deleteBySessionId()`. Session-Row + Output bleiben in History. |
| `Pref.LastFileName` (`audio.m4a` im Cache) würde mit Pre-Allocated-Session-IDs konfligieren wenn mehrere Recordings parallel laufen | Niedrig (heute kein Problem, single-job lock) | Wegen `ActiveJobRegistry.register()` kann nur eine Recording aktiv sein. Fix in V2 wenn Multi-Recording-Support gewünscht: Cache-Filenamen pro Session-ID (`cache/audio_{id}.m4a`). |
| Backward-Compat: Schema-Migration M3→M4 für `inserted_at` + `sticky_until` betrifft alle existierenden DBs | Niedrig | Recreate-Table-Pattern wie `MigrationTo3.kt`, mit Default-Fallback `inserted_at = NULL` für alte Rows (keine Annahmen über Insertion-History — alte Sessions werden als „nie inserted" markiert, was UX-mäßig harmlos ist da `getLastKeyboardSession()` ohnehin nur die neueste Session anzeigt). |

---

## I. Vorgeschlagene Implementierungs-Reihenfolge (für späteren Plan)

Diese Recherche bereitet einen separaten Plan vor. Vorschlags-Reihenfolge:

1. **Phase 0:** Dependency-Add `androidx.work:work-runtime-ktx`, Smoke-Test-Worker, der nichts tut außer einen Log schreibt — verifiziert, dass die Lib korrekt initialisiert ist.
2. **Phase 1:** `inserted_at`-Feld via M3→M4-Migration. Update-Site in `SessionManager.logTextInsertion()`. Tests via `MigrationTestHelper`.
3. **Phase 2:** `PendingTranscriptionReader` (Flow-Combiner) + Erweiterung von `ResendStatusDispatcher` zu `RestartButtonStateMapper`. Tests pure JVM (ResendStatusDispatcher-Pattern).
4. **Phase 3:** `PipelineWatchdogWorker` mit Idempotenz-Check. Tests via Robolectric oder echtem `androidx.work:work-testing`.
5. **Phase 4:** UI-Integration in den Restart-Button (Renderer + Long-Press-Sheet für Multi-Pending). Manuelle Verifikation auf Device.
6. **Phase 5:** Auto-Cleanup-Policy (`PeriodicWorkRequest`, „delete audio files where `inserted_at < now - 7d`"). Optional, kann V2 sein.

Phasen 1–4 sind blocking für Feature-Vollständigkeit; Phase 5 ist Hygiene.

---

## J. Verweise (file:line)

| Thema | Datei | Zeile / Funktion |
|-------|-------|-----------------|
| Persist-First-Pattern | `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` | `persistNewSession`, `runTranscriptionPipelineBlocking:215-325` |
| RecordingRepository (File-Lifecycle) | `app/src/main/java/net/devemperor/dictate/core/RecordingRepository.kt` | `persistFromCache:45-49`, `extractDurationSeconds:65-80`, `loadBySessionId:107-118`, `deleteBySessionId:136-148` |
| SessionManager (Boundary für DB-Writes) | `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt` | `createSession:44-87`, `finalizeCompleted:97`, `logTextInsertion:320-342`, `getFinalOutput:372-385` |
| SessionEntity (heutiges Datenmodell) | `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` | gesamte Datei |
| SessionStatus (Double-Enum) | `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt` | gesamte Datei |
| MigrationTo3 (CHECK-Rebuild-Pattern) | `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt` | `MIGRATION_2_3:20-205` |
| DurationHealingJob (Inspiration für Watchdog-Worker) | `app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt` | `heal:37-71` |
| JobExecutor (zentraler Job-Entry) | `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` | `start:82-168`, `cancel:184-187` |
| ActiveJobRegistry (StateFlow-Live-Lock) | `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` | `register:37-41`, `isActive:61` |
| ActiveJobRegistryObserver (Java-freundlicher Subscribe-Helper) | `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt` | `observe:30-42` |
| ResendStatusDispatcher (heutige Decision-Matrix) | `app/src/main/java/net/devemperor/dictate/core/ResendStatusDispatcher.kt` | `decide:56-72` |
| ResendInsertStrategy (3-Stage-Insertion) | `app/src/main/java/net/devemperor/dictate/core/ResendInsertStrategy.kt` | `execute:85-118` |
| Insertion in Edit-Feld | `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | `commitTextToInputConnection:1907-2000`, `onResendClicked:2249-2300` |
| Recording-Stop → Pipeline-Start | `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | `runTranscriptionViaOrchestrator:1665-1736` |
| AIProviderException.ErrorType | `app/src/main/java/net/devemperor/dictate/ai/AIProviderException.kt` | `ErrorType:18-39` |
| DB-Patterns (Double-Enum, Denormalized Cache) | `docs/DATABASE-PATTERNS.md` | gesamte Datei, insb. „Double-Enum Pattern" + „Denormalized Cache Columns" |

---

**Status:** Recherche abgeschlossen. Architektur-Empfehlung steht. Ein Folge-Plan kann auf dieser Basis Phase-Aufteilung + ADR (über die Wahl Option A vs B in [Abschnitt C.2](#c2-status-modell--zwei-optionen)) generieren.
