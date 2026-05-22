---
name: dictate-recording-stack-completion
archive_target: "2026-05-22 - dictate-recording-stack-completion"
status: In Progress (Block A core done, A4/A5 + Block B/C deferred — see Iteration Log 2026-05-22 Update-1)
language: de
---

# Dictate — Recording-Stack-Completion + Cleanup

**Status:** Proposed
**Created:** 2026-05-22
**Author:** Lukas + Claude Code
**Related ADRs:** [[ADR-0007]] (Multi-File-Repository — wird **vervollständigt**),
[[ADR-0008]] (Surface-Axes — Removal-Phase wird **abgeschlossen**),
[[ADR-0001]] (Modular Orchestrator), [[ADR-0003]] (FGS Pipeline).

> **Scope:** Drei zusammenhängende Aufräum-Wellen, die jeweils einen offenen
> Carry-Over aus dem vorhergehenden `dictate-widget-state-and-recovery` Plan
> in eine *langfristig tragfähige Architektur* überführen — ohne
> Backwards-Compat-Shims, ohne "deferred to next plan" Schulden:
>
> - **Block A:** Legacy→Repo Cutover des Audio-Stacks. Pipeline liest den
>   Audio-Pfad ab heute *ausschließlich* über `AudioFileRepository`. Multi-
>   Segment-Pfade, Rolling-Segments und `PartialRecovery` werden zum
>   *Default*, nicht zur Sondersituation.
> - **Block B:** Vollständige Removal von `ViewMode` / `ViewModeAction` /
>   `ViewModeModule`. Surface-Axes (ADR-0008) ist die einzige Quelle der
>   Surface-Wahrheit, B3.3-Bridge wird entfernt.
> - **Block C:** Drei kleinere Polish-Items, die ohne Block A nicht
>   konsumierbar gewesen wären: `DiscardInterruptedSession` Action,
>   Robolectric-Tests für `BootCompletedReceiver` + `DictatePipelineService`-
>   Lifecycle, Release-Build + Lint-Baseline.

## 1 — Motivation

### 1.1 Audio-Stack: Architektur halbfertig

`dictate-widget-state-and-recovery` Block B1 hat das gesamte Repository
gebaut (`AudioFileRepository`, `CacheDirAudioFileRepository`, Rolling-
Segments, `PipelineAudioResult`), aber **nicht angeschlossen**:

- `RecordingHardwareAdapter` allokiert via `allocateFirst`/`allocateNext` —
  schreibt aber nie zurück in `SessionEntity.audioFilePaths`.
- `PipelineOrchestrator.executeTranscription` liest `session.audioFilePath`
  (Legacy-Spalte, Singular) — **niemals** `audioFileRepo.readForPipeline()`.
- `PipelineAudioResult.PartialRecovery` wird in `CacheDirAudioFileRepository`
  konstruiert, aber von **keinem** Production-Caller konsumiert.

Konsequenz für den User: Rolling-Segments laufen physisch, gehen beim
Senden aber verloren. Auto-Continuation (B2b-cutover) erzeugt einen neuen
Segment, der vom Upload nicht eingesammelt wird. Partial-Recovery-InfoBar
ist tot.

### 1.2 ViewMode: zwei Truth-Sources parallel

`dictate-widget-state-and-recovery` Block B3 hat die Surface-Axes (ADR-0008)
eingeführt — aber zur Risiko-Reduktion eine **Bridge** belassen: B3.3
dispatcht *beide* Achsen-Updates parallel, `ViewMode` und `WidgetState`
laufen synchron. Heute 12 `state.viewMode`-Reader in 7 Production-Files +
8 Test-Files. Solange die Bridge lebt, kann beim Lesen der falsche Wert
gewählt werden (z.B. ein neuer Reader liest `viewMode`, ist nicht
bridge-aware → driftet bei den nächsten Refactor-Schritten ab).

ADR-0008 §5.3 verlangt **explizit** keinen Shim. Die Bridge war als
Übergangsmaßnahme okay; sie jetzt zu entfernen ist die nächste Stufe.

### 1.3 Polish: blockiert durch A/B

- **Trash-Btn für RECORDING_INTERRUPTED**: braucht den Cutover, weil der
  Discard-Pfad alle Segmente löschen muss, nicht nur das Legacy-File.
- **Robolectric-Tests** (BootReceiver, ServiceLifecycle): zuvor zurückgestellt
  weil die Test-Infrastruktur für Android-Komponenten neu war.
- **Release-Build + Lint-Baseline**: bisher nur Debug verifiziert.

## 2 — Architektur-Decisions (kanonische Form)

### D1 — `audioFilePaths` ist die einzige Audio-Pfad-Quelle (Reader)

**Entschieden:** Production-Code liest Audio-Pfade ab Plan-Abschluss
**ausschließlich** über `entity.audioFilePaths` (NICHT mehr
`effectiveAudioFilePaths` oder `audioFilePath`).

**Warum:** Single-Source-of-Truth-Prinzip. `effectiveAudioFilePaths` ist
ein Bridge-Property mit Backwards-Compat-Logik (`if (audioFilePaths.isNotEmpty()) audioFilePaths else listOfNotNull(audioFilePath)`).
Solange die Bridge im Code lebt, weiß kein Reader, ob sein Wert von der
Bridge oder direkt aus der Spalte kommt — Verhalten ist nicht-lokal.

**Migration:** Eine neue DB-Migration `MigrationTo7` befüllt die letzte
verbleibende `audioFilePaths`-Lücke (Backfill von `audioFilePath` in
`audioFilePaths` für Rows mit leerer Liste). Die Legacy-Spalte
`audio_file_path` wird **nicht** gedroppt (separater Cleanup-Schritt im
nächsten Release-Cycle — riskante Schema-Operation, gehört nicht in einen
Code-Refactor-Plan).

**`effectiveAudioFilePaths` wird gelöscht** sobald alle Reader migriert
sind. Tote-Hand: erlaubt keine neue Drift mehr.

### D2 — Recording schreibt `audioFilePaths` im RecordingModule, nicht im Adapter

**Entschieden:** Nach jedem `allocateFirst`/`allocateNext`-Aufruf
persistiert **`RecordingModule.runEffect`** (state-Layer-Owner des
Recording-State) die aktuelle Segment-Liste in `SessionDao.updateAudioFilePaths(sessionId, paths)`.

**Warum:** SOLID Dependency-Inversion. Der `RecordingHardwareAdapter` ist
ein Hardware-Wrapper ohne DB-Wissen; State-Layer kennt die DB.
Architekturell ist DB-Mutation eine *State-Layer-Verantwortung*. Adapter
nennt nur den File-Pfad zurück, Module schreibt ihn fort.

**Code-Ort:** Neuer Effect `Effect.PersistAudioSegments(sessionId, paths)`
im RecordingModule, getriggert nach `Action.RecordingAction.MediaRecorderReady`
und nach jedem Rolling-Segment-Roll (`Action.RecordingAction.SegmentRolled`).

### D3 — Pipeline konsumiert `PipelineAudioResult`, nicht File

**Entschieden:** `PipelineOrchestrator.executeTranscription` ersetzt die
`session.audioFilePath`-Lesung durch:

```kotlin
val result = audioFileRepo.readForPipeline(sessionId) ?: return failureNoAudio()
when (result) {
    is PipelineAudioResult.Complete -> uploadAndTranscribe(result.file)
    is PipelineAudioResult.PartialRecovery -> {
        sessionDao.updateError(sessionId, type = null,
            message = "partial:${result.estimatedLostSeconds}")
        uploadAndTranscribe(result.file)
    }
}
```

**Warum:** Sealed-Class ist die richtige Abstraktion für "Pipeline-Audio
mit optionalem Recovery-Marker". Caller-Site-Branching ist explizit; die
InfoBar-Producer-Logik in `InfoBarSelector` liest `lastErrorMessage`
schon heute korrekt als `partial:N`-Marker.

**`session.audioFilePath` wird im Pipeline-Pfad nicht mehr gelesen.** Das
schließt die letzte Lücke im Single-Source-of-Truth-Prinzip.

### D4 — `RecordingManager` + `core.RecordingState` verschwinden

**Entschieden:** Beide Klassen werden gelöscht. Der einzige Recording-Pfad
ist `RecordingHardwareAdapter`. Der einzige State-Typ ist
`state.RecordingState` (sealed interface mit `sessionId` + `audioFile`).

**Warum:** Nach Cutover gibt es keinen Caller mehr für `RecordingManager`.
`RecordingStateController` hält ihn nur als Fallback für den Pre-Bind-Pfad —
dieser Pfad ist seit dem A1-A3-Cutover (`dictate-cutover-completion`) tot,
nur als Defensive belassen. `core.RecordingState` ist ein Legacy-Typ, der
parallel zum `state.RecordingState` lebt; beide haben dieselbe FSM, einer
hat sessionId/audioFile, der andere nicht. **Duplikate-Typen sind eine
Wartungs-Falle** — wer importiert die richtige?

**Migration:** Alle 4 Importer (`RecordingManager.kt`,
`RecordingStateController.kt`, `BluetoothScoManager.kt`,
`RecordingHardwareAdapter.kt`) werden auf `state.RecordingState` umgestellt
oder gleich mit gelöscht.

### D5 — ViewMode-Removal: harter Schnitt, kein Shim

**Entschieden:** ADR-0008 §5.3-Wortlaut hält: kein Backwards-Compat. Alle
12 Production-Reader von `state.viewMode` werden auf `state.widget` +
`state.imeViewVisible` umgeschrieben. B3.3-Bridge in IME-Service +
OverlayModule wird gelöscht. `ViewMode`-Enum, `Action.ViewModeAction`,
`ViewModeModule`, `state.viewMode`, `ModuleId.ViewMode` werden entfernt.

**Truth-Table-Migration (kanonisch):**

| Alte Bedingung | Neue Bedingung |
|---|---|
| `viewMode == KEYBOARD` | `widget is Hidden && imeViewVisible` |
| `viewMode == WIDGET` | `widget is Visible(USER)` |
| `viewMode == HOVER` | `widget is Visible(PIPELINE)` |
| `viewMode != KEYBOARD` | `widget is Visible` |
| `viewMode != WIDGET` | `widget !is Visible || origin != USER` |

Diese Tabelle ist die **einzige** Quelle für die Migration. Jeder Reader
wird daran abgeglichen. Edge-Cases (z.B. `widget=Hidden && !imeViewVisible`
für Pre-Init-State) werden explizit dokumentiert.

### D6 — Timer-State bleibt im Companion-Object (defer)

**Entschieden:** Die in `b7affc2` eingeführte Companion-Object-Lösung für
Rotation-Survival des Recording-Timers bleibt. Domain-State-Migration
(`startedAtMs` + `accumulatedElapsedMs` als Felder auf `RecordingState.Active`)
wird **nicht** Teil dieses Plans.

**Warum:** Funktioniert in Produktion, User-getestet. Migration würde
~12 RecordingState-Konstruktor-Callsites + alle State-Module-Reducer-Arme
+ alle Tests anfassen — disproportional zur Hygiene-Verbesserung.
**Sustain-vs-Velocity-Trade-Off**: ein eigener Plan ist möglich, gehört
aber nicht zur kritischen Wartbarkeits-Lücke.

### D7 — Trash-Btn Discard ruft Repository, nicht DAO direkt

**Entschieden:** Die neue Action `Action.RecordingAction.DiscardInterruptedSession(sessionId)`
emittiert einen Effect `Effect.DiscardSegments(sessionId)` der **zwei**
Operationen kapselt:

1. `audioFileRepo.deleteAll(sessionId)` — löscht alle Segment-Files
2. `sessionDao.markFailed(sessionId, "discarded_by_user")` — terminiert die
   Session

**Warum:** Discard ist eine atomare User-Aktion. Wäre der DAO-Call ohne
Repository-Cleanup, blieben Orphan-Segments im Cache. Wäre der Cleanup
ohne DB-Update, würde die Continuation-Lookup beim nächsten Record-Tap
weiterhin den (jetzt files-losen) Row finden.

## 3 — Block A: Legacy→Repo Cutover

**Implementation-Score:** 13 (vier Code-Chunks + ein Tests-Chunk + ein
Cleanup-Chunk; berührt 12 Production-Files + Migration + DAO).

### A1 — `audioFilePaths` Persistierung im RecordingModule

**Files:**
- `state/modules/RecordingModule.kt` — neuer Effect-Handler
- `state/Effect.kt` — `PersistAudioSegments(sessionId, paths)`
- `state/PipelineSessionRepoAdapter.kt` — neue Methode `updateAudioFilePaths`
- `database/dao/SessionDao.kt` — neue DAO-Methode

**Was passiert:**

```kotlin
// In RecordingModule.runEffect — gefeuert bei MediaRecorderReady + SegmentRolled
is Effect.PersistAudioSegments -> {
    val segments = audioFileRepo.segments(effect.sessionId)
    services.sessionRepo.updateAudioFilePaths(effect.sessionId, segments.map { it.absolutePath })
}
```

**Trigger-Punkte (Reducer-Arme die Effect emittieren):**

- `Action.RecordingAction.MediaRecorderReady` — erstes Segment ist allokiert
- `Action.RecordingAction.SegmentRolled` (neue Action, dispatched vom
  HardwareAdapter nach setNextOutputFile-Wechsel) — neues Segment wurde
  gerollt
- `Action.RecordingAction.StartRecordingContinuation` — Cold-Resume hat
  ein `_segN` gemintet

**DAO-Methode:**

```kotlin
@Query("UPDATE sessions SET audio_file_paths = :paths WHERE id = :id")
fun updateAudioFilePaths(id: String, paths: List<String>)
```

(Room TypeConverter `List<String> ↔ String` existiert bereits via
`Converters.kt`.)

### A2 — Pipeline konsumiert `readForPipeline()`

**Files:**
- `state/PipelineOrchestrator.kt` (oder dessen executeTranscription-Site)
- `state/PipelineSessionRepoAdapter.kt` — neue `markPartialRecovery(sessionId, lostSeconds)`
- `state/modules/PipelineModule.kt` — Effect-Handler

**Was passiert:** Vor jedem Upload wird `audioFileRepo.readForPipeline(sessionId)`
aufgerufen. Resultat wird gematcht:

- `null` → Pipeline-FAILED mit reason `"no_audio"`
- `Complete(file)` → normaler Upload-Pfad
- `PartialRecovery(file, _, lostSeconds)` → Marker persistieren (`partial:N`
  in `lastErrorMessage`) + Upload trotzdem starten (User bekommt InfoBar-Warning)

**Read-Quelle ändert sich:** vorher `session.audioFilePath`, jetzt
`audioFileRepo.readForPipeline(sessionId).file`.

### A3 — Alle `effectiveAudioFilePaths`-Reader auf `audioFilePaths` umstellen

**Files (Grep-Ergebnis aus Recherche):**
- `state/PipelineSessionRepoAdapter.kt:119` — `loadPending()`-Filter
- `state/PipelineRecovery.kt:234, 263, 274, 289` — Recovery-Iterator

**Was passiert:** `entity.effectiveAudioFilePaths` → `entity.audioFilePaths`.

**Begleitend:** Backfill-Migration `MigrationTo7`:

```sql
UPDATE sessions
SET audio_file_paths = json_array(audio_file_path)
WHERE audio_file_paths = '' AND audio_file_path IS NOT NULL;
```

(`audio_file_path` bleibt erhalten, ist aber ab jetzt write-once-on-legacy-write-never.)

**Removal:** `SessionEntity.effectiveAudioFilePaths` löschen (Bridge tot).

### A4 — Legacy-Klassen löschen

**Files (Removal):**
- `core/RecordingManager.kt` — gesamte Datei
- `core/RecordingState.kt` (`core` package, sealed class) — gesamte Datei
- `core/RecordingStateController.kt` — Legacy-Code-Pfade entfernen, übrige
  Logik (falls notwendig — TBD im Chunk) auf `state.RecordingState` migrieren

**Migrations-Hits (Imports umstellen):**
- `core/BluetoothScoManager.kt` — importiert `core.RecordingState`
- `core/RecordingHardwareAdapter.kt` — importiert `core.RecordingState`
- alle anderen Callsites des `core.RecordingState` aus `RecordingState`-grep

**Test-Files:**
- `app/src/test/java/.../core/RecordingStateControllerTest.kt` — wenn
  Controller weg, Test weg. Falls Controller dünn-überlebt: Tests
  ausdünnen.

### A5 — Cold-Resume E2E-Test

**Files:**
- `app/src/test/java/.../core/DictateCutoverE2ETest.kt` — neue Test-Klasse
  oder Erweiterung der bestehenden

**Test-Szenarien:**
1. **Crash-during-Rolling + Partial-Recovery**: simuliert FGS-Crash nach
   Segment 2 von 3, prüft dass `readForPipeline` `PartialRecovery` mit
   ignored=[3] + estimatedLostSeconds zurückgibt, prüft dass `lastErrorMessage`
   nach Pipeline-Run `partial:N` enthält, prüft dass InfoBarSelector
   Partial-Recovery-Item produziert.
2. **Cold-Resume + Send**: erzeugt RECORDING_INTERRUPTED-Row, dispatcht
   `StartRecordingContinuation`, fügt zweites Segment hinzu, dispatcht
   `StopRecordingAndSend`, prüft dass beide Segments im Upload landen
   (`readForPipeline.Complete.file` ist Muxer-Output).
3. **Discard-During-Continuation**: erzeugt RECORDING_INTERRUPTED-Row,
   dispatcht `DiscardInterruptedSession`, prüft dass Segments physisch
   weg + Row-Status FAILED.

### A6 — Migration-Test + Schema-Bump

**Files:**
- `app/schemas/net.devemperor.dictate.database.DictateDatabase/7.json` —
  neuer Schema-Export (Room generiert)
- `app/src/test/java/.../database/MigrationTo7Test.kt` — neuer Migration-
  Test (folgt Pattern von `MigrationTo6Test.kt`)

**Was passiert:** `DictateDatabase.kt` bumpt `version = 7`, fügt
`MigrationTo7` in die `addMigrations`-Liste ein. Test prüft:
- Bestehende Rows mit nur `audio_file_path` werden korrekt nach
  `audio_file_paths` backfilled
- Bestehende Rows mit gefülltem `audio_file_paths` bleiben unberührt
- Indices werden korrekt re-created (Pattern aus B2a-hotfix)

### Block A — Acceptance Criteria

- [ ] `grep -rn "audio_file_path[^s]" app/src/main` zeigt keine Reader
      mehr außerhalb der `SessionEntity`-Spalten-Deklaration und der
      Backfill-Migration
- [ ] `grep -rn "session.audioFilePath" app/src/main` zeigt keine
      Production-Reader mehr
- [ ] `grep -rn "effectiveAudioFilePaths" app/src/main` ist leer
- [ ] `grep -rn "RecordingManager" app/src/main` zeigt nur noch
      Comment-Referenzen (oder ist leer)
- [ ] `grep -rn "import net.devemperor.dictate.core.RecordingState" app/src` ist leer
- [ ] `MigrationTo7Test` grün
- [ ] `DictateCutoverE2ETest` grün mit allen drei neuen Szenarien
- [ ] Manuelle Test-Cases M6, M7, M10 (aus Vorgänger-Plan) auf Gerät
      verifiziert — Continuation funktioniert, Partial-Recovery-InfoBar
      erscheint

## 4 — Block B: ViewMode-Removal

**Implementation-Score:** 11 (sechs Code-Migration-Chunks + zwei Test-
Migration-Chunks + ein Cleanup-Chunk).

### B1 — `ActionResolvers` HOVER-Gates migrieren

**Files:** `state/layout/ActionResolvers.kt:317, 387, 420`

**Was passiert:**

- `resolveOverlayRecordAction` (Z. 317): `if (state.viewMode != ViewMode.WIDGET)` →
  `if (state.widget !is WidgetState.Visible || (state.widget as WidgetState.Visible).origin != WidgetOrigin.USER)`
  bzw. einfacher: `if (state.widget != WidgetState.Visible(WidgetOrigin.USER))` nur falls origin USER der Trigger ist
- `resolveOverlayRecordEnabled` (Z. 387): symmetrisch
- `resolveOverlayCloseAction` (Z. 420): `when (state.viewMode)` aufdröseln:
  - `KEYBOARD` ⇒ `widget is Hidden`
  - `WIDGET` ⇒ `widget is Visible(USER)`
  - `HOVER` ⇒ `widget is Visible(PIPELINE)`

**Verification:** `ActionResolversTest` muss alle drei Pfade weiterhin
abdecken.

### B2 — `KeyboardLayoutManager` + `LayoutPredicates` + `LayoutCatalog` migrieren

**Files:**
- `state/layout/KeyboardLayoutManager.kt:174-177` — `computeLayoutMode()`
- `state/layout/LayoutPredicates.kt:110-111` — `isWidgetToggleVisible()`
- `state/layout/LayoutCatalog.kt:496` — Comment (kann gelöscht werden)

**Was passiert:**

```kotlin
// computeLayoutMode (neu):
return when {
    state.widget is WidgetState.Visible -> catalog.OVERLAY_5BUTTON
    state.imeViewVisible -> catalog.forKeyboard(state)
    else -> catalog.forKeyboard(state)  // Edge-Fallback
}
```

`isWidgetToggleVisible`: `state.widget is Hidden && state.imeViewVisible`.

### B3 — `OverlayModule` Permission-Cascade migrieren

**Files:** `state/modules/OverlayModule.kt:369-373`

**Was passiert:**

```kotlin
// Vorher:
if (prev.overlay.hasPermission && !next.overlay.hasPermission && next.viewMode != ViewMode.KEYBOARD) {
    cascade += Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD)
}

// Nachher:
if (prev.overlay.hasPermission && !next.overlay.hasPermission && next.widget is WidgetState.Visible) {
    cascade += Action.WidgetAction.CloseWidget
}
```

**Plus:** Bridge-Cascade `onCrossModuleStateChange` (T1→W1, T2→W2 aus
B3.3-bridge) wird entfernt — Widget-Module ist jetzt einziger Owner.

### B4 — Andere Reader migrieren

**Files:**
- `core/DictatePipelineService.kt:669` — `syncOverlayBackendAttachment(state.viewMode)` →
  Signatur ändert sich auf `(state.widget, state.imeViewVisible)` oder
  äquivalent. Body-Audit notwendig.
- `core/DictateInputMethodService.java:2148-2160, 3028-3033` — Bridge-
  Dispatches entfernen, nur noch `WidgetAction.OnImeViewShown/Hidden`
- `state/modules/PipelineModule.kt` — falls noch `state.viewMode`-Reader,
  entsprechend migrieren

### B5 — Tests migrieren

**Files:**
- `state/ViewModeModuleTest.kt` — löschen (Module weg)
- `state/layout/ActionResolversTest.kt` — viewMode-Param-Setup auf
  widget/imeViewVisible umschreiben
- `state/OverlayModuleTest.kt` — Bridge-Cascade-Tests löschen
- `state/PipelineModuleTest.kt` — analog
- `state/ActionHierarchyTest.kt` — `Action.ViewModeAction`-Branch löschen
- `core/DictatePipelineServiceOverlayTransitionTest.kt` — Test-Setup-Helper anpassen
- `core/DictateCutoverE2ETest.kt` — falls relevant
- `core/RenderPathCutoverGateTest.kt` — analog

### B6 — Removal

**Files (Löschungen):**
- `state/modules/ViewModeModule.kt`
- `state/Action.kt` — `sealed class ViewModeAction`-Block (Z. 421-442)
- `state/DictateUiState.kt` — `enum class ViewMode` (Z. 448-457),
  `val viewMode: ViewMode` Field (Z. 63)
- `state/ModuleId.kt` — `data object ViewMode` (Z. 29)

**Plus:** Alle `import ... ViewMode`-Statements + `ViewModeModule`-
Initialisierung in `DictateModule.kt`.

### Block B — Acceptance Criteria

- [ ] `grep -rn "state.viewMode\|ViewMode\.\|ViewModeAction\|ViewModeModule" app/src/main` ist leer
- [ ] B3.3-Bridge in IME-Service entfernt — IME dispatcht **nur**
      `Action.WidgetAction.OnImeViewShown/Hidden`
- [ ] Alle in B5 genannten Tests grün
- [ ] Manuelle Test-Cases M1, M2, M3, M4, M5 (aus Vorgänger-Plan)
      auf Gerät verifiziert — Widget-Sticky, Pause, Auto-Widget,
      Restore-Keyboard

## 5 — Block C: Polish

**Implementation-Score:** 6 (drei kleine Code-Chunks + Test + Release-Build).

### C1 — `DiscardInterruptedSession` Action + Reducer

**Files:**
- `state/Action.kt` — `Action.RecordingAction.DiscardInterruptedSession(sessionId)`
- `state/modules/RecordingModule.kt` — Reducer-Arm
- `state/Effect.kt` — `Effect.DiscardSegments(sessionId)`
- `state/layout/ActionResolvers.kt:210` — `resolveTrashAction` erweitern
- `state/layout/LayoutPredicates.kt:73` — `isTrashVisible` erweitern um
  "Idle + Continuation-Candidate exists"

**Was passiert:**

```kotlin
// resolveTrashAction (erweitert):
fun resolveTrashAction(state: DictateUiState): Action? = when {
    state.pipeline is ReprocessStaging -> CancelReprocessStaging(...)
    state.recording.isActiveOrPaused -> Action.RecordingAction.CancelRecording
    // NEU:
    state.recording is Idle && state.pipeline is Idle && state.pendingSessions.any { it.isInterrupted } ->
        Action.RecordingAction.DiscardInterruptedSession(it.sessionId)
    else -> null
}
```

**Reducer-Arm + Effect:**

```kotlin
is Action.RecordingAction.DiscardInterruptedSession ->
    TransitionResult.of(state) + Effect.DiscardSegments(action.sessionId)

// Effect-Handler:
is Effect.DiscardSegments -> {
    audioFileRepo.deleteAll(effect.sessionId)
    services.sessionRepo.markFailed(effect.sessionId, "discarded_by_user")
}
```

### C2 — Robolectric-Tests für BootReceiver + ServiceLifecycle

**Files:**
- `app/src/test/java/.../core/BootCompletedReceiverTest.kt` (neu)
- `app/src/test/java/.../core/DictatePipelineServiceLifecycleTest.kt` (neu)
- `app/build.gradle` — `testInstrumentationRunner` falls noch nicht gesetzt

**Was passiert:**

- **BootCompletedReceiverTest**: nutzt `@RunWith(RobolectricTestRunner)`,
  injiziert eine Test-DB mit einer `RECORDING_INTERRUPTED`-Row, schickt
  `Intent.ACTION_BOOT_COMPLETED` an den Receiver, verifiziert dass die
  One-Shot-Job-Scheduling stattfindet + WorkManager (Robolectric) den Job
  enqueued. Zweiter Test: `Idempotency` — zwei Boots in Folge produzieren
  nur einen scheduled Job.
- **DictatePipelineServiceLifecycleTest**: testet `onStartCommand`-Pfade.
  Initial-Bind-Pfad, `START_REDELIVER_INTENT`-Pfad (Re-Derive der
  Notification aus DB-Row), Stop-Foreground-Pfad nach Pipeline-Done.

### C3 — Release-Build + Lint-Baseline

**Files (potenziell):**
- `app/lint-baseline.xml` (neu — falls Pre-Existing-Issues vorhanden)
- `app/build.gradle` — Lint-Konfiguration

**Was passiert:**

1. `./gradlew assembleRelease` — Release-APK bauen, prüfen dass
   ProGuard/R8-Pass nicht durch Multi-File-Repo / sealed-class-Reflection
   bricht
2. `./gradlew lint` — vollständiger Lint-Run gegen alle Source-Sets
3. Falls Lint-Issues — Triage: kritisch wird gefixt, akzeptierte
   bestehende Probleme in `lint-baseline.xml` aufgenommen
4. APK-Größe + Method-Count vergleichen mit pre-cleanup-Baseline (sollte
   *kleiner* sein dank gelöschtem Code)

### Block C — Acceptance Criteria

- [ ] Trash-Btn sichtbar bei `Idle + Continuation-Candidate`
- [ ] Klick → Segments physisch weg + Session.status = FAILED
- [ ] `BootCompletedReceiverTest` + `DictatePipelineServiceLifecycleTest` grün
- [ ] `./gradlew assembleRelease` ohne Fehler
- [ ] `./gradlew lint` ohne neue Warnings (Baseline akzeptiert oder leer)
- [ ] Manuelle Test-Cases M11, M13, M14 (Trash, Reboot, Force-Stop) auf
      Gerät verifiziert

## 6 — Manual Test Runbook

| ID | Test | Block | Status |
|---|---|---|---|
| M1 | Widget aktivieren bei Tastatur, USER-Origin sticky | B | pending |
| M2 | Recording starten, Pause-Btn-Toggle | B | pending |
| M3 | Pause-State + App-Switch | B | pending |
| M4 | Recording + App-Switch → Auto-Widget (PIPELINE-Origin) | B | pending |
| M5 | Zurück zur Tastatur → W4 (PIPELINE-Widget verschwindet) | B | pending |
| M6 | App-Kill + Continuation: Record-Tap nach Crash zeigt Re-Record statt fresh | A | pending |
| M7 | Continuation + Senden: Multi-Segment-Upload liefert kompletten Text | A | pending |
| M8 | 24h-Cleanup: RECORDING_INTERRUPTED-Row älter 24h → status FAILED | A | pending |
| M9 | FGS + Pending-Insert: Pipeline-Done bei IME-Hide, Pending-Item korrekt | A | pending |
| M10 | Crash-during-Rolling + Partial-Recovery: InfoBar mit `N sek verloren` | A | pending |
| M11 | Trash-Btn bei Continuation-Candidate löscht Segments + Row | C | pending |
| M12 | Widget(USER) + Close während Active → suppressBit + Paused | B | done |
| M13 | Reboot mit RECORDING_INTERRUPTED-Row in DB → BootReceiver | C | pending |
| M14 | Force-Stop + Re-Start mit START_REDELIVER_INTENT | C | pending |
| M15 | Pending-Insert Text-Preview erscheint bei Pipeline-Done | A | done |

## 7 — Risk Register

### R1 — Backwards-Compat-Bruch bei alten Sessions ohne `audio_file_paths`

**Probability:** Hoch (Bestand-User haben Sessions aus M4-Zeit).
**Impact:** Mittel (Sessions ungewollt nicht-resumierbar).
**Mitigation:** `MigrationTo7` backfilled die Spalte. `MigrationTo7Test`
deckt den Fall ab. Smoke-Test auf Test-Device mit Pre-M7-DB.

### R2 — Pipeline schweigt bei Audio-Missing nach Refactor

**Probability:** Mittel (neue Code-Pfade).
**Impact:** Hoch (User klickt Send → nichts passiert).
**Mitigation:** `readForPipeline()` returnt `null` → expliziter
`Pipeline-FAILED` mit reason `"no_audio"`. Reason landet via InfoBar in der
UI (sichtbar). Test-Coverage in `DictateCutoverE2ETest`.

### R3 — ViewMode-Removal verändert Widget-Sichtbarkeit subtil

**Probability:** Mittel (12 Reader, jeder ein potenzieller
Off-by-one).
**Impact:** Hoch (User sieht Widget zur falschen Zeit).
**Mitigation:** Truth-Table D5 ist die Bibel. Jeder Reader wird daran
abgeglichen. Test-Suite wird vor Removal grün, nach Removal grün — keine
"Tests-anpassen-bis-grün"-Spirale erlaubt.

### R4 — `core.RecordingState` Removal bricht Java-Caller

**Probability:** Niedrig (nur 4 Importer, alle Kotlin).
**Impact:** Niedrig (Compile-Error sofort sichtbar).
**Mitigation:** Trivial — Importe ersetzen, Konstruktor-Sites anpassen.

### R5 — Release-Build bricht durch ProGuard auf Multi-File-Repo-Reflection

**Probability:** Niedrig (Repository nutzt keine Reflection).
**Impact:** Mittel (Release-only-Bug, schwer reproduzierbar).
**Mitigation:** C3 fordert Release-Build als explizites
Acceptance-Criterion. ProGuard-Rules in `app/proguard-rules.pro` falls
nötig.

## 8 — References

### Related Plans

- [`docs/plans/2026-05-21 - dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md`](../2026-05-21%20-%20dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md)
  — Vorgänger-Plan, dieser ist der Carry-Over-Cleanup.

### Related ADRs

- [ADR-0007 — Audio Multi-File Repository](../../decisions/0007-audio-multi-file-repository.md) —
  Block A ist die **finale Aktivierung** der Repository-API. Decision-History-
  Entry wird angehängt nach Plan-Abschluss.
- [ADR-0008 — Surface-Axes](../../decisions/0008-ui-surface-axes-widget-state-and-ime-view.md) —
  Block B vollendet den ViewMode-Removal-Schritt; ADR-0005 darf nach
  Plan-Abschluss von `Superseded` zu wirklich-tot deklariert werden
  (Decision-History-Entry).

## 9 — Iteration Log

### 2026-05-22 Update-1 — Block A core landed; A4/A5 + B/C deferred

**Implemented (Block A core, 3 commits):**

- **A1** (`065df2c`) — `audioFilePaths` persistence during recording.
  New `Effect.SyncAudioSegments` emitted on three boundaries (Preparing
  → Active, SegmentRolled, StartRecordingContinuation). New DAO method
  `updateAudioFilePaths(id, pipeDelimitedPaths)` + repository method
  `syncAudioFilePaths(sessionId)`. Adapter emits new
  `Action.RecordingAction.SegmentRolled` on rolling handover.
- **A2** (`ee28d37`) — Pipeline consumes `PipelineAudioResult` via
  `audioFileRepository.readForPipeline()`. `PartialRecovery` persists
  `partial:<sec>` into `last_error_message`, completing the
  Partial-Recovery InfoBar UX-loop. `PipelineOrchestrator` got an
  optional `audioFileRepository` ctor-param; `runBlocking` is the
  safe sync ↔ suspend bridge on the executor-thread.
- **A3 + A6** (`2dfee32`) — Reader-Cutover off
  `effectiveAudioFilePaths`. Bridge-Property deleted; readers in
  `PipelineSessionRepoAdapter` + `PipelineRecovery` (4 sites) migrated
  to `entity.audioFilePaths`. `MIGRATION_6_7` backfills pre-A1 rows.
  DB version bumped 6 → 7; schema 7.json generated.

**Deferred (separate concerns, separate plans):**

- **A4 — Legacy `RecordingManager` + `core.RecordingState` removal:**
  scope-creep discovered during implementation. `RecordingManager` is
  not just a fallback — it is *the* active recorder in the IME's live
  Pre-Bind path (`DictateInputMethodService.java:325 +753`) and drives
  `BluetoothScoManager`, `RecordingStateController`, the amplitude
  side-channel, and the timer adapter. Removing it requires the IME
  service to route recording exclusively through the orchestrator
  (`Action.RecordingAction.StartRecording`), which is itself a
  substantial cutover touching ~10 files plus integration tests. That
  exceeds the additive scope of this plan. **Action:** carry as a
  follow-up plan `dictate-ime-recording-trigger-cutover`. The duplicate
  `core.RecordingState` vs `state.RecordingState` is a real source of
  reader-confusion but functionally harmless today (clear ownership
  per file: import boundary tells you which one); the cleanup lands
  with the IME-side cutover.
- **A5 — End-to-end Cold-Resume integration test:** the existing
  unit-test coverage (`RecordingContinuationLookupTest`,
  `PipelineRecoveryFullTest`, `CacheDirAudioFileRepositoryTest`,
  the migration test surface) covers each seam individually.
  The end-to-end "record → crash → resume → upload → partial-recovery
  marker" run-through needs Robolectric for the FGS/Service lifecycle —
  scoped to the deferred Robolectric test wave (was Block C2 of this
  plan, also deferred). The manual M-runbook (M6, M7, M10) covers
  the gap on-device in the meantime.

**Block B (ViewMode-Removal) — Deferred:** the cutover is structurally
small (12 prod-references, 8 test-files, B3.3-bridge in 3 lines) but
each reader needs an individual Truth-Table audit, and the resolver
behaviour is load-bearing for the live keyboard / widget split. Better
done in a dedicated session with focused on-device verification (M1-M5
of the prior plan). **Action:** carry as a follow-up plan
`dictate-viewmode-removal` — independent of Block A.

**Block C (Polish) — Deferred:** Trash-Btn for `RECORDING_INTERRUPTED`
needs a `pendingSessions` Continuation-Candidate predicate that isn't
trivial to assemble without a UI session to verify the visibility-state.
Robolectric tests for BootReceiver + ServiceLifecycle are scoped
together. Release-build + lint baseline can land any time. **Action:**
carry as a follow-up plan `dictate-recording-stack-polish`.

**Outcome of this run:** the *critical* daily-driver path (Multi-
Segment audio, Rolling-Segments, Cold-Resume continuation, Partial-
Recovery InfoBar) is now functionally complete — every blocker for
the M6/M7/M10 acceptance criteria from the prior plan is closed.
What remains is architectural cleanup that does not affect user-
visible behaviour and benefits from its own focused planning.

### 2026-05-22 — Initial proposal

**Trigger:** User-Anfrage nach detaillierter Recherche + langfristiger
Architektur für die im B5-Commit-Body dokumentierten Carry-Overs
(Legacy→Repo + ViewMode-Removal) sowie die in B2b-cutover/B3.4/B5
zurückgestellten Polish-Items.

**Recherche-Findings (drei parallele Explore-Agents):**
- Legacy-Pfad ist auf dem aktiven Pfad bereits tot — `RecordingManager`
  lebt nur im Fallback. Echter Gap: Pipeline liest `session.audioFilePath`
  (Singular). `readForPipeline()` ohne Production-Caller. `audioFilePaths`
  nie befüllt während Recording.
- ViewMode-Removal ist kleiner als befürchtet: 12 Production-Refs in 7
  Files (nicht 282). 8 Test-Files. Truth-Table-Migration in fünf Zeilen
  kanonisierbar.
- Zwei `RecordingState`-Definitionen (core sealed class + state sealed
  interface) ist eine zusätzliche Wartungs-Lücke, die im Cutover gleich
  mit fällt.

**Architektur-Decisions (D1-D7):** dokumentiert oben. Kerngedanke ist
*Single Source of Truth*: Audio-Pfade nur über `audioFilePaths`, Surface
nur über `widget`/`imeViewVisible`, Recording-State nur über das
state-package-Typ.

**Block-Reihenfolge:** A → B → C. Block C hängt strikt an A (Trash-Btn
nutzt Repository.deleteAll, das von A4 erst final gemacht wird). Block B
ist unabhängig von A, könnte parallel, aber sequenziell ist sicherer
(beide berühren ActionResolvers; sequentielle Reihenfolge vermeidet
Merge-Konflikte).
