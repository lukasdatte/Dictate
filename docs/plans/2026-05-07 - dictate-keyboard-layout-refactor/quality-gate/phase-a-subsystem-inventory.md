# Phase A — Subsystem-Inventur (Migrations-Sicht)

**Erstellt:** 2026-05-13
**Plan-Version (commit):** 04db5f711924433dec29cf984a5d2cad1548c4b9 (`feature/language-chip-curation`)
**Reviewer:** Phase-A-Agent

---

## Summary

Der Refactor "Dictate Keyboard Layout Refactor" umfasst **neun klar abgrenzbare
Migrationspfade**, die zusammen die heutige verteilte State-/View-Architektur in
ein Modular-Orchestrator-Pattern (DictateOrchestrator + 12 aktive Module +
hierarchischer DictateUiState) überführen. Der Refactor ist überdurchschnittlich
groß für eine kleine Android-IME-App: er kombiniert eine neue **Foreground-
Service-Schicht**, eine **DB-Schema-Migration mit CHECK-Constraint-Erweiterung**,
eine **MotionLayout-getriebene UI-Refactor** und ein **komplett neues
Floating-Overlay-Subsystem mit eigenem Window-Lifecycle und Permission-Onboarding**
in einem einzigen Plan.

Was ihn von einem reinen UI-Refactor unterscheidet: jedes Subsystem führt **neue
Cross-Module-Invarianten** ein (Pure-Reducer-Rule, MAX_CASCADE_DEPTH, F-8
Single-Dispatch-Ownership, Self-Cascade-Erlaubnis nach KG-RSB-2-Fix), die nur dann
korrekt zusammenspielen, wenn sie gemeinsam betrachtet werden — das ist auch die
zentrale Empfehlung dieser Inventur. Auffällig sind drei Cluster: (a) der
Module-Plugin-Pattern als architektonisches Fundament (Spec 1 §4 + §15), (b)
die Cascade-/Action-Hierarchie als verteilter Vertrag über alle drei Specs
hinweg, (c) das Floating-Overlay-Subsystem mit komplett neuer
Lifecycle-Surface (WindowManager + Permission-Observer + Drag).

**Top-3-Migrations-Risiken aus Inventur-Sicht:**

1. **DB-Schema-Migration M3 → M4 mit CHECK-Constraint-Erweiterung** — nicht mehr
   rein additiv, erzwingt `CREATE TABLE … _new + INSERT SELECT + DROP + RENAME`-
   Strategie. Backfill-Pfad für vier Konsumenten-Sites (siehe S-2),
   `androidTest/`-Verzeichnis muss erstmals angelegt werden (Plan-KG bestätigt).
2. **Block-1a vs. Block-1b-Split (Quick-Wins im heutigen Code → Module-
   Architektur im Foreground-Service-Container)** — die Plan-Iteration hat
   diesen Split spät eingeführt (R.7 Block-1a). Subsystem S-3 (ImeViewBackend +
   LayoutCatalog) hängt strukturell davon ab, dass S-1 (State + Module) bereits
   im PipelineService lebt, und gleichzeitig muss der heutige Code in Block 1a
   visibility-clean sein, bevor der Service-Container eingezogen wird.
3. **Cross-Module-Cascade-Korrektheit (KG-RSB-2 Production-Bug-Klasse)** — der
   Self-Filter-Bug in §4.3 Step 5 ist nur DURCH die Cross-Spec-Lese-Tiefe der
   Plan-Review aufgefallen. Solche Cascade-Probleme sind in §15.1.x
   (Coupling-Matrix) noch nicht vollständig gegen die `R(…) C(…)`-Notation
   formal verifiziert; jede neue Cross-Module-Cascade ist ein potenzieller
   Self-Filter-Wiedergänger. Subsystem S-9 (ResetSuppressBit-Lifecycle)
   ist exemplarisch für diese Risiko-Klasse.

---

## Subsysteme (Migrations-Sicht)

### [S-1] State-Klassen-Hierarchie: Flach + 3-Owner → Hierarchisch + Sealed + DictateUiStateStore

- **Scope:** Konsolidierung der heute auf drei Klassen verteilten State-Felder
  in eine einzige immutable `DictateUiState`-Top-Level-Klasse mit 13 Sub-State-
  Achsen, gehalten in `DictateUiStateStore` (`MutableStateFlow`). Ersetzt
  `RecordingStateController.state`, `KeyboardUiController.state`,
  `KeyboardStateManager.contentArea/isSmallMode` durch eine einzige SSoT
  (siehe Spec 1 §3, §4.4; Plan-Hauptdok §3.2).
- **Alt-Zustand:** Drei unabhängige State-Halter mit eigenen Callbacks; flache
  Felder (`var contentArea`, `var isSmallMode`, `var audioFocusEnabled` etc.);
  Pref-Reads on-demand verteilt über `DictateInputMethodService.java`
  (`:580, :664, :1025, :1402, :2632, …`); sealed Klassen `RecordingState` +
  `PipelineUiState` existieren, sind aber nur lokale Owner-Felder.
- **Neu-Zustand:** Eine immutable `data class DictateUiState` mit Sub-State-
  Klassen (`AudioState`, `LayoutState`, `OverlayState`, `ResendState`,
  `LivePromptState`, `LanguageState`, `FeatureToggles`, `ThemingState`,
  `InterruptionState`, `PipelineUiState`, `RecordingState`,
  `pendingSessions: PersistentList<PendingSession>`, plus 1 Top-Level-Boolean
  `lastResultNeedsManualPaste`). Mutation nur über `store.update(reducer)` —
  alle Subscriber lesen reaktiv via `StateFlow.collect`.
- **Plan-Refs:** Spec 1 §3 (Daten-Modell + Achsen-Übersicht + Vergleichs-
  Tabelle Z. 282–301); Spec 1 §4.4 (`DictateUiStateStore`); Spec 1 §13.2.1
  (State-Mutation-Audit); F-10-Iter-Log-Eintrag (2026-05-09 Sub-State-Klassen);
  Hauptplan §3.2 (Architektur-Diagramm).
- **Code-Refs:**
  `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt:106-107`
  (`var state: RecordingState`),
  `core/KeyboardUiController.kt:63-65` (`var state: PipelineUiState`),
  `core/KeyboardStateManager.kt:100,102` (`contentArea`, `isSmallMode`),
  `core/DictateInputMethodService.java:111-122` (verstreute Service-Felder),
  `core/PipelineUiState.kt`, `core/RecordingState.kt` (sealed Datentypen
  bleiben, werden Sub-State).
- **Migrations-Schwerpunkt:**
  - **Atomicity:** `KeyboardStateManager.setSmallMode` mutiert heute
    `isSmallMode + contentArea` sequenziell (`:141-145`) — der Refactor macht
    das atomar in einem `store.update`-Reducer. Subscriber sehen nie einen
    Stale-Zustand zwischen den zwei alten Schritten.
  - **PersistentList-Idiom (Spec 1 §3, Z. 240–254):** `pendingSessions`
    benutzt `kotlinx.collections.immutable.PersistentList`. Reducer MÜSSEN
    `.add` / `.removeAll` (structurally shared) verwenden, nicht
    `toMutableList()`-Round-Trip — Drift-Quelle, weil nicht-typisierte
    Mutationen zur Compile-Zeit unsichtbar sind.
  - **Pref-Mirror-Initialisierung (S-1 vs. S-2 / S-9 Reihenfolge-Risiko):**
    `PipelinePrefMirror.attach(store)` muss vor `recovery.recover(store)`
    laufen, sonst sieht Recovery initial keine Overlay-Positions-Defaults
    (eingearbeitet in Issue 2.0.10, aber subtil).
  - **Cross-Spec-Drift:** alle drei Specs schreiben State-Pfade
    (`state.recording.X`, `state.layout.singleRowMode`, `state.overlay.X`)
    direkt in Code-Snippets. Drift zwischen Sub-State-Feldnamen und
    Predicates ist ein häufiges Quality-Risk (vgl. R.5 LayoutState-Container,
    R.21 Cross-Spec-DRY-Tabelle).
- **Abhängigkeiten:** keine — Foundation-Layer. S-2 bis S-9 lesen oder
  schreiben Sub-State-Achsen, die hier definiert werden. Reihenfolge-
  vorgängig zu allen anderen Subsystemen.

---

### [S-2] DB-Schema-Migration: SessionStatus v3 (4 Stati) → v4 (6 Stati + inserted_at + CHECK-Recreate)

- **Scope:** SQLite-Schema-Migration `MIGRATION_3_4` plus Konsumenten-Audit für
  die neue `SessionStatus`-Enum-Variante. Diese Migration ist zentral für den
  OOM-Death-Recovery-Pfad und für die Cleanup-Policy.
- **Alt-Zustand:** `SessionStatus.kt` mit 4 Werten (`RECORDED`, `COMPLETED`,
  `FAILED`, `CANCELLED`) und CHECK-Constraint, der nur diese vier akzeptiert
  (`MigrationTo3.kt:43-75`); `sessions`-Tabelle ohne `inserted_at`-Spalte;
  Status-Konsumenten in `ResendStatusDispatcher.kt:57-71`,
  `HistoryAdapter.java:130-159`, `HistoryDetailActivity.java:287-299, :454, :590`.
- **Neu-Zustand:** Enum erweitert um `RECORDING` + `TRANSCRIBING`; neue Spalte
  `inserted_at INTEGER NULL` mit COMPLETED-Backfill via
  `CASE WHEN status='COMPLETED' AND final_output_text IS NOT NULL THEN created_at`;
  CHECK-Constraint umfasst alle 6 Werte. Migration ist **kein** reines `ALTER
  TABLE`, sondern table-recreate (`CREATE … _new + INSERT SELECT + DROP +
  RENAME + Index-Recreate`) — atomar in Room-Transaktion.
- **Plan-Refs:** Spec 1 §6.1 (Migration-Code Z. 2284–2386), §6.1.1
  (ActiveJobRegistry-Strategie nach M4 + 13 Konsumenten), §6.1.2 (Schema-
  Version-Wiring), §6.1.3 (Konsumenten-Update-Audit), §11.4.2 (MigrationTo4Test),
  §11.7.0 (Lint-Setup KG-SST-4); Plan-Hauptdok §7.3 KG-SST-1..5
  (alle RESOLVED); D8 in §2.
- **Code-Refs:**
  `database/migration/MigrationTo3.kt:43-75` (Pattern-Vorlage für recreate),
  `database/entity/SessionStatus.kt:11-16` (Enum),
  `database/DictateDatabase.kt:38,73` (`version = 3`, `addMigrations(...)`),
  `database/entity/SessionEntity.kt:51` (Insertions-Punkt für `insertedAt`-Feld),
  `database/dao/SessionDao.kt:68-85, :96` (Insertions-Punkt für neue Queries),
  `core/SessionManager.kt:97-111` (Vorlage für `transition*`-Methoden),
  `history/HistoryAdapter.java:122,130-159`,
  `history/HistoryDetailActivity.java:208,285,287-299,454,590,148`,
  `core/ResendStatusDispatcher.kt:57-71`,
  `core/ActiveJobRegistry.kt:20-65`,
  `core/JobExecutor.kt:96,164,294`,
  `core/PipelineOrchestrator.kt:124,204,837-855`.
- **Migrations-Schwerpunkt:**
  - **Schema-Migration Atomicity:** Table-recreate ist eine SQLite-Pattern-
    Falle — wenn Indices nach `RENAME` nicht recreated werden, sind sie weg.
    `INSERT SELECT` mit fehlender Spalte würde silent default-werten. Plan
    hat das adressiert (`CREATE INDEX IF NOT EXISTS` ×5 Z. 2380–2384), aber
    Test-Coverage muss alle 4 alten Stati + alle Child-Tabellen
    (`processing_steps`, `transcriptions`) verifizieren (Spec 1 §11.4.2 Z.
    3774 explizit gefordert).
  - **`androidTest/`-Verzeichnis existiert NICHT** (Plan-KG bestätigt, eigene
    `Glob`-Verifikation hat es ebenfalls bestätigt: nur `app/src/test/` und
    `app/src/main/` vorhanden) — Block 3 muss das Verzeichnis erstmals
    anlegen + `room-testing`-Dependency in `build.gradle` einführen.
    Migrations-Test-Suite läuft als Instrumented-Test, nicht JVM.
  - **DB-vs-Cache-Reihenfolge (KG-SST-5):** Reducer schreibt **erst** DB,
    **dann** `ActiveJobRegistry` — Drift bei Crash ist akzeptiert
    (process-local Cache). Diese Reihenfolge wurde in der Plan-Review-Iter
    umgedreht (vorher: erst Registry, dann DB) — Implementer-Falle, wenn
    jemand die alte Tabellen-Zeile in §6.1.1 ohne den darunter stehenden
    R.17-Vertrag liest.
  - **Konsumenten-Audit:** 4 `when/switch`-Sites (siehe §6.1.3-Tabelle)
    müssen die neuen Enum-Werte abdecken. Java-Site `HistoryAdapter:130-159`
    ist `switch` ohne `default` — KG-SST-4 (RESOLVED) hat das mit Lint-Regel
    `EnumSwitch = error` und defensivem `default: Log.wtf + GONE` adressiert.
- **Abhängigkeiten:** S-1 (DictateUiState muss existieren, weil `pendingSessions`
  vom `recoverFromDb` befüllt wird). Sollte als zweites Subsystem implementiert
  werden, sofort nach S-1. S-7 (Audio-File-Lifecycle) konsumiert die
  `audio_file_path`-Spalte für `cleanupOrphanedTerminalAudio` (§6.3.1) und
  `LegacyAudioFileMigration` (§4.11.6.2 KG-AFF-2). S-5 (Foreground-Service)
  ist Aufruf-Site für `recoverFromDb` + Cleanup-Policy.

---

### [S-3] Action-Hierarchie: Flach + LocalBinder-Forwarder → Hierarchisch sealed + Single-Dispatch

- **Scope:** Konsolidierung der ~25 typed LocalBinder-Forwarder-Methoden zu
  einem einzigen `dispatch(action: Action)`-Eingang über eine hierarchische
  `Action`-sealed-class-Struktur (eine innere sealed class pro Modul).
  Eliminiert F-8-Doppel-Definition (Forwarder + Action-Klasse parallel).
- **Alt-Zustand:** Heute (Pre-Refactor) gibt es **keinen** zentralen Action-
  Bus. Mutations laufen direkt durch Controller-Methoden
  (`RecordingStateController.startRecording(audioFile, useBluetooth, audioFocusEnabled)`,
  `KeyboardUiController.preparePipeline()` etc.). Spec-Versionen vor F-8 hatten
  einen `LocalBinder` mit ~25 Forwarder-Methoden parallel zu einer flachen
  `Action`-sealed-class.
- **Neu-Zustand:** `Action` ist eine flache `sealed class` mit einer inneren
  sealed class pro Modul (`RecordingAction`, `PipelineAction`, `ViewModeAction`,
  `LayoutAction`, `AudioAction`, `ResendAction`, `LivePromptAction`,
  `LanguageAction`, `OverlayAction`, `FeatureToggleAction`,
  `PendingSessionsAction`, `KeyboardInputAction`, `InterruptionAction`).
  Pluss eine Top-Level `EffectFailure(effect, reason)` für Failure-Channel
  (Option D). `LocalBinder` exposed `state` (read) + `dispatch(action)` +
  2 Lifecycle-Hooks (`OnImeViewShown`/`OnImeViewHidden` als ViewModeAction).
- **Plan-Refs:** Spec 2 §3.3 (vollständige Action-Hierarchie, Z. 119–306);
  Spec 1 §4.3 (`dispatch` + `dispatchInternal` mit Sealed-Leaves-Indexing,
  Z. 519–646); Spec 1 §5 (LocalBinder-API, Z. 2138–2219); Iter-Log F-8
  (2026-05-09); R.3/Issue 1.1.4 (nullable Resolver statt NoOp, Z. 132–133);
  R.4/Issue 2.1.6 (Sealed-Leaves-Indexing).
- **Code-Refs:** Heute kein zentraler Eingangspunkt — Migrations-Anker sind
  alle `RecordingStateController`-Aufrufe (`DictateInputMethodService.java`
  ruft `recordingStateController.startRecording(...)` etc.) und alle
  `KeyboardUiController`-Aufrufe (`preparePipeline`, `startPipeline`, …).
  Künftige Datei: `state/Action.kt` (neu).
- **Migrations-Schwerpunkt:**
  - **Sealed-Leaves-Indexing (R.4):** Doppel-Routing einer Action zu zwei
    Modulen ist Init-Time-Error (`DictateOrchestrator.moduleByLeafClass` Z.
    551–561). Setup-Zeit-Audit, KEIN Runtime-Bug.
  - **Reentrancy-Vertrag (Issue 2.1.4 Option A):** `dispatch()` ist
    Main-Thread-confined; Effects müssen über `emitAction()` (scope.launch)
    re-dispatchen, nicht synchron. Falsche Nutzung bricht das frozen-cascade-
    Invariant.
  - **Nullable Resolver-Idiom (R.3):** alle `actionResolver: (state) -> Action?`
    in `ButtonSlot`-Definitionen (Spec 2 §3.2). Click-Listener `?.let { onAction
    (it) }` (Spec 2 §6, Spec 3 §4.2). Pre-R.3-Spec hatte `Action.NoOp` —
    Drift-Quelle, wenn eine Slot-Definition NoOp statt null behält.
  - **Exhaustivity-Konvention (Issue 2.0.6, Spec 1 §4.2 Z. 502–514):**
    `when (action)` MUSS expression-form über die innere sealed class sein,
    kein `else`-Branch erlaubt (außer dokumentiertem OEM-extensible-Effect).
    Code-Review-Pflicht.
  - **Java-Brücke:** Spec 1 §4.4 (Z. 683-684) nennt eine geplante
    `DictateUiStateObserver`-Brücke analog zu `core/ActiveJobRegistryObserver.kt`
    für Java-Site-Konsumenten. Heute existiert die Brücke noch nicht;
    Block 2 muss sie anlegen.
- **Abhängigkeiten:** S-1 (Sub-State-Felder = Action-Argumente leben in
  `DictateUiState`). S-3 ist Voraussetzung für S-4 (Orchestrator routet
  Actions via `KClass`-Lookup) und für S-6/S-8 (Button-Slots emittieren
  Actions). Implementierungsreihenfolge: nach S-1, parallel zu S-4.

---

### [S-4] Pipeline-Orchestrierung: Verteilte Controller → DictateOrchestrator + DictateModule-Plugin-Pattern

- **Scope:** Einführung des Modular-Orchestrator-Patterns (Composition Root +
  13 Module à la Excel-EKL Module-Augmentation). Ersetzt den frühren
  god-class-Entwurf `PipelineStateManager` durch einen schlanken
  `DictateOrchestrator`, der nur das `DictateModule`-Interface kennt.
- **Alt-Zustand:** Heute ist die Pipeline-Logik in drei Klassen verteilt:
  `RecordingStateController` (Recording-Lifecycle), `KeyboardUiController`
  (PipelineUiState-Mutation), und der `DictateInputMethodService` selbst
  (kombiniert beide + Service-Lifecycle-Wissen). Keine SSoT für
  Cross-Achsen-Logik; Cross-Achsen-Effekte sind verstreute `callback`-Lambdas
  (z.B. Audio-Focus-Loss → Recording.Pause läuft heute über
  `RecordingStateController.Callback`, nicht über zentralen Bus).
- **Neu-Zustand:** `DictateOrchestrator` ist Composition Root + Action-Routing
  + Cross-Module-Cascade-Dispatch. `DictateModule<S, A, E>` ist ein
  `sealed interface` mit `id`, `actionClass`, `read`/`write`/`initialState`,
  `reduce`, `runEffect`, `onCrossModuleStateChange`, `prefBindings`,
  `terminate`. 12 aktive Module + 1 Phase-2-Modul in einer zentralen
  `DictateModuleRegistry.all`-Liste mit Init-Sanity-Check.
- **Plan-Refs:** Spec 1 §4 (gesamtes Kapitel — Architektur, Interface, Helper,
  Z. 305–913); Spec 1 §15 (Modul-Inventar + Coupling-Matrix +
  RecordingModule-Vollbeispiel + AudioModule-Beispiel, Z. 4877–5460); F-11-
  Iter-Log (2026-05-09 Modular Orchestrator); R.4 Sealed-Leaves-Indexing;
  R.6 Cascade-Tiefe-Counter (MAX_CASCADE_DEPTH = 8).
- **Code-Refs:** Heute komplett neu (`state/DictateOrchestrator.kt`,
  `state/DictateModule.kt`, `state/DictateUiStateStore.kt`,
  `state/modules/*.kt` — alle neu). Heutige Anker sind die zu ersetzenden
  god-Klassen:
  `core/RecordingStateController.kt:78-99,128-321` (Public-API + Callbacks),
  `core/KeyboardUiController.kt:63-165,213-353,464-509` (State-Mutator +
  Refresh-Funktion),
  `core/DictateInputMethodService.java:329-396` (Composition Root heute).
- **Migrations-Schwerpunkt:**
  - **Pure-Reducer-Invariante:** Reducer dürfen KEINE Hardware/IO-Reads
    machen (R.2: `audioFile` lebt im State, nicht im Context). Verstoß ist
    nur durch Code-Review erkennbar — kein Compile-Check.
  - **Cross-Module-Cascade-Mechanik (Mode 1+2 in §15.5):** Action-Cascade
    via `onCrossModuleStateChange(prev, next): List<Action>` plus
    `MAX_CASCADE_DEPTH = 8`-Counter. Race-Frei durch frozen-snapshot
    (`prevGlobal` + `nextGlobal` werden vor Cascade gesnapshottet).
    Mode 3 (Atomic Cross-Axis-Update) ist Out-of-Scope (Phase-2-Backlog).
  - **Self-Cascade-Erlaubnis (KG-RSB-2-Fix, 2026-05-11):** Der Self-Filter
    `it.id != module.id` in §4.3 Step 5 wurde **gestrichen** — ein Modul
    darf seine eigene State-Transition cross-cascaden. Production-Bug,
    wenn jemand den Filter aus Reflex-Defensive wieder einbaut. Regression-
    Test `recordingModule_idleToPreparing_emitsResetSuppressBit` ist
    Pflicht (Plan §10 Acceptance R.RSB-FIX-A).
  - **Reflection vs. manuelle Registry (Open Question, Hauptdok §9 F-11):**
    Aktuell manuelle Liste in `DictateModuleRegistry.all`. R8/ProGuard-
    Robustheit-Risk bei `sealedSubclasses`-Variante. Implementer-Decision.
  - **`ModuleServices`-DI-Container (§4.7, Z. 788–845):** Lazy-Provider-
    Pattern. EffectHandlers MÜSSEN ihre Coroutines im injizierten
    `services.scope` (FGS-`serviceScope`) starten; `services.emitAction`
    ist immer asynchron (frischer Cascade-Snapshot). Falsche Scope-Wahl
    leakt Coroutines beim `serviceScope.cancel()`.
- **Abhängigkeiten:** S-1 (DictateUiState als Sub-State-Container), S-3
  (Action-Hierarchie). S-4 muss laufen, **bevor** S-6 (LayoutCatalog) oder
  S-8 (OverlayBackend) Actions emittieren können. Block-1b-Implementierung
  hängt am PipelineService-Container (S-5).

---

### [S-5] Service-Schicht: IME-only → DictatePipelineService (Foreground) + LocalBinder + Lifecycle-Recovery

- **Scope:** Einführung eines neuen Foreground-Services im selben App-Prozess,
  der die State-Pipeline überlebt, wenn der IME-Service stirbt (Tastatur-
  Wechsel-Survival). Plus persistente Notification, Action-Routing über
  PendingIntents, Service-Lifecycle (`onCreate`, `onStartCommand`,
  `onDestroy`).
- **Alt-Zustand:** Pipeline-State lebt im `DictateInputMethodService`-Prozess
  (IME-Service). Bei Tastatur-Wechsel stirbt der IME-Service und damit die
  Pipeline. Keine Notification, kein OOM-Death-Recovery.
- **Neu-Zustand:** `DictatePipelineService` (Foreground Service mit
  `FOREGROUND_SERVICE_TYPE_MICROPHONE`), Composition Root im `onCreate`
  (instanziiert Store, Orchestrator, PrefMirror, Recovery, ModuleServices,
  AudioFileFactory, NotificationCoordinator, ActionRouter). LocalBinder mit
  `state` + `dispatch` für IME-Service-Anbindung. `stopSelf()` bei
  Terminal-State. Persistente Notification (Channel `dictate_pipeline`,
  Priority LOW, kein Sound, kein Heads-Up).
- **Plan-Refs:** Spec 1 §7 (Lifecycle-Struktur, Z. 3063–3264 — §7.1–§7.7);
  Spec 1 §11.1 (FGS-Implementierungs-Details, AndroidManifest-Diff,
  Notification-Builder, 5s-Timeout, Z. 3527–3705); Spec 1 §11.3 (Bound-
  Service-Setup); F-3-Iter-Log; D1–D6 in §2; Plan-Hauptdok §3.2 (Diagramm).
- **Code-Refs:** Heute neu (`core/DictatePipelineService.kt`,
  `core/PipelineNotificationCoordinator.kt`, `core/PipelineActionRouter.kt`).
  Heutige Anker:
  `app/src/main/AndroidManifest.xml:5-9,29-40` (Permissions + IME-Service-
  Eintrag — additiv erweitert);
  `core/DictateInputMethodService.java:329-396` (Composition-Root-Logik
  wandert in den neuen Service);
  `core/JobExecutor.kt:39,389` (Initialize-Hook wandert in Service-onCreate).
- **Migrations-Schwerpunkt:**
  - **5s-Timeout-Fenster (§11.1.4):** `startForeground()` MUSS synchron in
    `onCreate` laufen, BEVOR irgendeine Coroutine startet — sonst
    `ForegroundServiceDidNotStartInTimeException`. DB-Reads + Cleanup
    laufen async danach.
  - **Service-Type "microphone" (§11.1.1):** API 34+ braucht
    `FOREGROUND_SERVICE_TYPE_MICROPHONE`-Permission + `startForeground(...,
    type)`-Variante. Wir bleiben mit Type=microphone über die gesamte
    Service-Lifetime (Option 2, einfacher).
  - **Permission-POST_NOTIFICATIONS (Android 13+):** Runtime-Permission;
    Plan verweist auf §11.5.1. Wenn der User die Notification ablehnt,
    läuft der FGS trotzdem — Notification ist dann unsichtbar.
  - **Bind-Lifecycle (§11.3.1):** Service-Start + Bind in
    `IME.onCreateInputView` (nicht beim ersten Recording-Click — Latenz-
    Argument, ~50–200ms). Unbind in `IME.onDestroy`. `BadTokenException`
    + `IllegalArgumentException` müssen gefangen werden (Permission-
    Revoke zur Laufzeit).
  - **G7 (Spec 1 §13.5.a):** `JobExecutor.initialize(orchestrator)` wandert
    aus dem IME-Service-onCreate in den Service-onCreate. Defensiv-
    `null`-Check für JobExecutor empfohlen, falls die Bind-Sequenz Race
    hat — Plan akzeptiert das als Niedrig-Risk.
  - **`onDestroy`-Cleanup-Sequenz (§4.11.5.1):** `serviceScope.cancel()`
    cancelt In-Flight-Effects. Modul `terminate()`-Methode (Issue 2.1.12)
    feuert finale SideEffects (Hardware-Release). `runBlocking`-Timeout
    auf onDestroy ist akzeptiert (Service-Death-Cleanup).
- **Abhängigkeiten:** S-1 (Store), S-3 (LocalBinder.dispatch nimmt Action),
  S-4 (Orchestrator wird im Service-onCreate konstruiert), S-7
  (AudioFileFactory wird im Service-onCreate gewired). S-5 ist die
  Aufruf-Site für Recovery-Read aus S-2 (`recoverFromDb`). Block 2 in
  der Implementierungs-Reihenfolge des Plans (Hauptdok §4 Tabelle).

---

### [S-6] Keyboard-Layout-Renderer: KSM + RecordingUiController + KeyboardLayoutModeController → KeyboardLayoutManager + LayoutCatalog + MotionLayout

- **Scope:** Komplette UI-Layer-Refactor für den KEYBOARD-Modus. Eliminiert die
  3 Bug-Klassen (asymmetrisches Re-Parenting, resend_btn-Race, recordButton-
  Hybrid) durch Predicates + Resolver im `LayoutCatalog`, MotionLayout statt
  ConstraintSet-Manipulation, und ein einheitliches `ImeViewBackend`.
- **Alt-Zustand:** `KeyboardLayoutModeController.kt:60-272` mit
  `originalParents`-Map, Re-Parenting bei Single-Row-Toggle, programmatischen
  ConstraintSets (`csTwoRowAction`, `csTwoRowInput`, `csSingleRow`).
  `KeyboardStateManager.applyVisibility()` mutiert 8+ View-Properties direkt.
  `RecordingUiController` und `KeyboardUiController.refreshRecordButtonFromState`
  schreiben beide auf `recordButton.text/isEnabled` (Race-fragil).
  `MainButtonsController.registerMainButtonListeners` (`:155-260`) registriert
  9 Click-Listener mit Service-Method-Callbacks.
- **Neu-Zustand:** `KeyboardLayoutManager` (Triangle-FSM-Renderer, subscribt
  `state.collect`); `LayoutCatalog.forKeyboard(state)` wählt deterministisch
  einen der 5 KEYBOARD-Modi (`TWO_ROW`, `SINGLE_ROW`, `TWO_ROW_SEND_MODE`,
  `SINGLE_ROW_SEND_MODE`, `REPROCESS_STAGING`); `ImeViewBackend` rendert über
  MotionLayout mit `motion_scene_keyboard.xml` (4 Scene-States,
  `VISIBILITY_MODE_IGNORE` auf allen 9 Buttons); Click-Listener einmalig
  verdrahtet, lesen `stateRef`/`modeRef`-Felder (L8). KSM wird in drei
  Owner-Klassen aufgespalten (R.10: `ContentAreaController` +
  `PromptVisibilityController` + `OverlayResetHandler`).
- **Plan-Refs:** Spec 2 §3–§8 (vollständige Datentyp + LayoutCatalog +
  Backend-Code); Spec 2 §7 (MotionScene-XML, Z. 605–1022); Spec 2 §9 (Migration
  vorhandener Klassen — `KeyboardLayoutModeController` entfällt komplett,
  ~273 Zeilen gelöscht; `MainButtonsController` → ImeViewBackend);
  Spec 2 §11.1–§11.7 (XML-Diff, Inflation-Cost, BorderGlow-Migration, Click-
  Listener-Leak-Analyse, Special-Touch-Handler); Spec 2 §13.1 (Visibility-
  Mutation-Audit — 23 Mutations-Sites klassifiziert); F-7 (geteilter Slot-
  Apply-Helper); R.10 (KSM-Aufspaltung); R.11 (VISIBILITY_MODE_IGNORE auf
  allen 9 Buttons); R.13 (Strict-Mode-Logging während 5c); R.14 (firstRender-
  Flag); R.12 (sceneStateId direkt am LayoutMode).
- **Code-Refs:**
  `core/KeyboardLayoutModeController.kt:47-272` (komplett gelöscht),
  `core/KeyboardStateManager.kt:36-240` (aufgespalten),
  `core/MainButtonsController.kt:76-477` (Migration in ImeViewBackend),
  `core/RecordingUiController.kt:115-277` (Main-Button-Mutationen entfallen),
  `core/KeyboardUiController.kt:241,464-509` (record_btn-Resolver-Migration),
  `core/InfoBarController.kt:25-46` (bleibt, view-lokaler Owner),
  `app/src/main/res/layout/activity_dictate_keyboard_view.xml:12-172` (XML-
  Refactor zu MotionLayout — siehe Spec 2 §11.1 Diff),
  neu: `app/src/main/res/xml/motion_scene_keyboard.xml`,
  `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (überarbeitet,
  Spec 2 §7.2 zeigt das Ziel-XML),
  neu: `keyboard/render/SlotRenderer.kt` (`applySlotToView`-Helper),
  `keyboard/KeyboardLayoutManager.kt`,
  `keyboard/ImeViewBackend.kt`,
  `keyboard/LayoutCatalog.kt`,
  `keyboard/RecordingAnimationController.kt`,
  `keyboard/ContentAreaController.kt`,
  `keyboard/PromptVisibilityController.kt`,
  `keyboard/OverlayResetHandler.kt`.
- **Migrations-Schwerpunkt:**
  - **PulseLayout-Animation-Spike (Spec 2 §11.3, Risiko-Hauptdok §6):**
    `PulseLayout.onDetachedFromWindow` ruft `animator.cancel()` — MotionLayout
    macht während Transition partielle View-Detach/Re-Attach, könnte die
    Pulse-Animation killen. **Spike-Validierung VOR Block 5** (Z. 1699–1751)
    — wenn Bruch: Fallback auf programmatische ConstraintSets (Option 4).
  - **Hardcoded `{ false }` für TRASH/PAUSE im SEND_MODE (Issue 2.0.11, Z.
    1121–1131):** Inline-Doku-Anker explizit; ein gut gemeinter DRY-Refactor
    würde Bug §1.1 #3 reaktivieren. Code-Review-Falle.
  - **`predResendVisible` ohne `resendCooldown` (Issue 3.0.9, Z. 1264–1278):**
    Cooldown landet im `enabledResolver`, nicht im `visibilityPredicate`.
    Falsche Migration würde Bug §1.1 #3b reaktivieren. Block-1-Acceptance
    hat dedizierten Test-Punkt.
  - **R.13 / Strict-Mode-Logging in 5c (Spec 2 §10):** in der Übergangs-
    Phase 5c sind `KeyboardStateManager`-Methoden leere Bodies; ein
    `VisibilityWrite from $caller`-Log verifiziert, dass keine zwei
    Subsysteme parallel auf einer Visibility-Achse schreiben. Diese
    Schmerzphase ist genau die Stelle, an der typische SSOT-Migrations-
    Bugs entstehen.
  - **R.14 / firstRender-Flag:** Beim Re-Inflate (Rotation, Theme) muss der
    erste Render `jumpToState` rufen (nicht `transitionToState`), sonst
    sieht der User eine 250ms-Slide-In-Animation. View-Recreate-spezifisch.
  - **R.11 / `visibilityMode="ignore"` auf allen 9 Buttons:** MotionScene
    managt Position, LayoutCatalog managt Visibility — Trennung kollisions-
    frei nur, wenn das Attribut auf jedem Button steht. XML-Lint-Check.
  - **EditBarController (heute `MainButtonsController.kt:500`):**
    `edit_audio_focus_btn` lebt orthogonal zur Main-Button-Area;
    `resolveAudioFocusIcon`-Helper (F-4, Spec 2 §8.5 Z. 1397–1399) ist die
    SSoT für beide. Drift-Risk, wenn der EditBar-Code direkt ein anderes
    Icon mapped.
- **Abhängigkeiten:** S-1 (`state.layout.singleRowMode` etc.), S-3 (Action-
  Hierarchie), S-4 (Orchestrator), S-5 (PipelineService-LocalBinder).
  Implementiert in Block 5 nach Block 1a/1b/2/3/4 — also als vorletzter
  Block. Konsumiert AudioFileFactory aus S-7 (Resolver `resolveRecordAction`
  ruft `services.audioFileFactory.allocate()`).

---

### [S-7] Audio-File-Management: `cacheDir/audio.m4a`-Fixpfad → AudioFileFactory + `cacheDir/audio/`-Subdir + Cleanup-Routinen

- **Scope:** Einführung einer Pre-Dispatch-File-Allocation für Recording-
  Audio (Multi-Job-Modell R.8 verlangt collision-freie Pfade). Plus
  Orphan-Cleanup beim Service-Boot, Legacy-Migration vom alten Fixpfad,
  rekursiver "Cache leeren"-Helper.
- **Alt-Zustand:** `DictateInputMethodService.java:1612` ist ein fester Pfad
  `new File(getCacheDir(), "audio.m4a")` — jede neue Aufnahme überschreibt die
  alte. Inkompatibel mit Multi-Job. `PreferencesFragment.java:272-289`
  iteriert nur Top-Level-Cache, ist nicht rekursiv. Keine Orphan-Cleanup-
  Routine.
- **Neu-Zustand:** `interface AudioFileFactory` mit `allocate()` (frischer
  File-Pfad mit UUID-Suffix in `cacheDir/audio/`) und `cleanupOrphans
  (referencedPaths)`. Default-Impl `CacheDirAudioFileFactory` mit
  60s-Cutoff-Filter (KG-AFF-4) gegen Race mit concurrent allocate.
  `LegacyAudioFileMigration` (KG-AFF-2) räumt die alte `audio.m4a` beim
  ersten Boot mit Pref-Flag. `PreferencesFragment.clearCacheRecursively`-
  Helper (KG-AFF-3). Sofort-Delete des Cache-Files nach
  `PipelineOrchestrator.persistFromCache` (KG-AFF-1).
- **Plan-Refs:** Spec 1 §4.11 (kanonische Sektion, Z. 916–2134 — Motivation,
  Interface, Default-Impl, Pre-Dispatch-Usage, Lifecycle, Service-Wiring,
  Recovery-Coupling, Edge-Cases, Failure-Modi, Constants, Tests); Spec 1
  §6.3.1 (Orphan-FAILED-Audio-Cleanup, KG-SST-2 RESOLVED); Spec 2 §8.5 Z.
  1340–1360 (Resolver `resolveRecordAction` mit `services.audioFileFactory.
  allocate()` + IOException-Handling); Plan-Hauptdok §7.3 KG-AFF-1..5 (alle
  RESOLVED); R.2 (audioFile im RecordingState).
- **Code-Refs:**
  `core/DictateInputMethodService.java:208,1612,1407,936,1706` (heutige
  `audioFile`-Sites — werden entfernt),
  `core/RecordingManager.kt:61-62` (MediaRecorder-Container `.m4a` + MPEG_4 +
  AAC),
  `core/RecordingRepository.kt:45-47` (`persistFromCache` mit `copyTo
  (overwrite=true)`),
  `core/PipelineOrchestrator.kt:837-855` (Persist-Stage, KG-AFF-1 Cache-
  Cleanup-Hook),
  `settings/PreferencesFragment.java:272-289` (Cache leeren — KG-AFF-3),
  neu: `core/AudioFileFactory.kt`, `core/CacheDirAudioFileFactory.kt`,
  `migration/LegacyAudioFileMigration.kt`,
  `database/dao/SessionDao.kt` (neue Queries `findAllAudioFilePaths`,
  `markLegacyAudioSessionsFailed`, `findOrphanedTerminalAudio`,
  `clearAudioFilePathBulk`).
- **Migrations-Schwerpunkt:**
  - **Race-Window cleanupOrphans vs. allocate (KG-AFF-4 RESOLVED):**
    60s-Cutoff via `lastModified()`-Filter schließt das Race-Fenster.
    Implementer-Falle: ohne den Cutoff würde sich ein neu allozierter File
    in eine cleanup-Iteration mid-boot verlieren.
  - **Reducer-Pure-Garantie (R.2):** `audioFile` wird vor `dispatch()`
    vom Resolver alloziert (`resolveRecordAction` in Spec 2 §10/§8.5) und
    als Action-Argument durch den Reducer geschoben. Reducer macht keinen
    FS-Zugriff. `IOException` aus `mkdirs()` wird vom Resolver in einen
    `ToastSink`-Aufruf übersetzt, BEVOR dispatch — sonst landet eine
    I/O-Exception im Reducer-Pfad.
  - **App-Update-Migration (KG-AFF-2):** Alte DB-Sessions referenzieren
    `cacheDir/audio.m4a` (alle dieselbe Datei, weil Pfad nicht UUID-
    suffixiert). `LegacyAudioFileMigration` markiert sie als FAILED mit
    `audio_file_path_legacy_purged`-Reason. Idempotent via Pref-Flag
    `legacy_audio_purged_v4`.
  - **Cache-Subdir-Scope vs. PreferencesFragment (KG-AFF-3):**
    `File.delete()` auf nicht-leeres Verzeichnis ist no-op. Recursive-
    Helper wird in Java implementiert (kein Kotlin-Interop-Overhead).
  - **Recovery-Coupling (§4.11.6):** `cacheDir` kann vom OS unter
    Storage-Druck gewiped werden. Recovery-Pfad (§11.6.2) markiert
    referenzierende Sessions als Ghost-FAILED. Akzeptiertes Trade-off,
    weil `persistFromCache` (heute schon existierend) in `filesDir/
    recordings/` kopiert.
  - **Orphan-FAILED-Audio-Cleanup (§6.3.1 / KG-SST-2):** Neue DAO-Methode
    `findOrphanedTerminalAudio(cutoff)` + Service-Idle-Stop-Hook
    `cleanupOrphanedTerminalAudio()`. Layer-Trennung DAO/File-IO.
- **Abhängigkeiten:** S-1 (`RecordingState` trägt `audioFile`), S-2 (DAO-
  Queries auf `audio_file_path`; Legacy-Migration nach M4), S-3 (Action
  `StartRecording` trägt `audioFile`), S-4 (RecordingModule.runEffect
  ruft `recordingHardware.allocate(file)`), S-5 (Service-onCreate-Wiring
  + Idle-Stop-Cleanup). Block 4 in Plan-Hauptdok §4.

---

### [S-8] Floating-Overlay-Subsystem: Nicht-existent → OverlayBackend + WindowManager-Lifecycle + WIDGET/HOVER-Differenzierung

- **Scope:** Komplett neues UI-Subsystem für floating-over-other-apps Overlay.
  Umfasst Window-Lifecycle, Permission-Onboarding, Mode-Transitionen
  (Triangle-FSM KEYBOARD/WIDGET/HOVER), Schließen-Button-Differential,
  Touch-Routing, Drag-Funktionalität mit per-Orientation-Persistierung.
- **Alt-Zustand:** Heute nicht-existent. Keine Floating-Overlay-Klassen, keine
  `SYSTEM_ALERT_WINDOW`-Permission im Manifest, kein WindowManager-Usage.
- **Neu-Zustand:** `OverlayBackend` (implementiert `RenderBackend`-Interface
  identisch zum `ImeViewBackend`); `AndroidOverlayWindow`-Wrapper für
  `WindowManager.addView/removeView/updateViewLayout` (DIP, testbar mit
  Fake); `OverlayPermissionGate` (Permission-Check + Onboarding-Persistenz);
  `OverlayPermissionObserver` (synchronisiert `state.overlay.hasPermission`
  via Lifecycle-Trigger, kein Polling); `DefaultOverlayLayoutParamsFactory`
  (TYPE_APPLICATION_OVERLAY + Flags); `DefaultOverlayDragHandler` (Drag-vs-
  Click-Differenzierung via 8dp-Threshold, gravity TOP|START); `Default
  OverlayPositionMapper` (0..1 ↔ Pixel-Konversion mit `view.effectiveSize`).
  Overlay-XML `overlay_5button_layout.xml` mit 5 Buttons (Record + Send +
  Pause + Trash + Close). Permission-Onboarding-InfoBar im IME-View.
- **Plan-Refs:** Spec 3 (gesamt, alle 14 Sektionen); Spec 3 §3 (Layout-Mode +
  XML); Spec 3 §4 (OverlayBackend + Wrapper + LayoutParams + DragHandler +
  PositionMapper + OverlayModule); Spec 3 §5 (Permission-Flow + Observer +
  Gate + Settings-Intent + Onboarding-UI); Spec 3 §6 (Schließen-Button-
  Differential, WIDGET-Pfad vs. HOVER-Pfad); Spec 3 §7 (Triangle-FSM mit
  6 Übergängen T1–T7); Spec 3 §11.5 (Drag-Detail, OPEN-3); Spec 3 §13
  (Vollständigkeits-Verifikation SSOT/SOLID/DRY/Cross-Spec); R.18 (Drag-
  Hoheit); R.19 (Anchor TOP|START + effectiveSize); R.20 (Cross-Module-
  Coupling-Matrix in Spec 1 §15.1.x); Issue 3.1.3 (Permission-Achse als
  State); Issue 3.1.6 (Aspect-Bucket-Persist Option A); Plan-Hauptdok §7.1
  Out-of-Scope (STANDALONE_OVERLAY = Phase-2).
- **Code-Refs:** Heute keine Anker — komplett neues Subsystem.
  Künftige Dateien:
  `overlay/OverlayBackend.kt`,
  `overlay/AndroidOverlayWindow.kt`,
  `overlay/OverlayPermissionGate.kt`,
  `overlay/OverlayPermissionObserver.kt`,
  `overlay/DefaultOverlayLayoutParamsFactory.kt`,
  `overlay/DefaultOverlayDragHandler.kt`,
  `overlay/DefaultOverlayPositionMapper.kt`,
  `state/modules/OverlayModule.kt`,
  `app/src/main/res/layout/overlay_5button_layout.xml`,
  `app/src/main/res/layout/overlay_permission_infobar.xml`,
  `app/src/main/res/drawable/overlay_background.xml`,
  `app/src/main/res/values/styles_overlay.xml`,
  `app/src/main/res/values/strings.xml` (neue Strings ergänzen).
  Manifest-Diff: `app/src/main/AndroidManifest.xml` —
  `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`.
- **Migrations-Schwerpunkt:**
  - **Window-Lifecycle-Edge-Cases (§11.6):** `BadTokenException` bei
    Permission-Revoke zur Laufzeit; `IllegalArgumentException` bei doppeltem
    `removeView`. Idempotenz im Wrapper.
  - **Permission ohne Broadcast (§5.5):** Android sendet KEIN
    `OverlayPermissionChanged`-Broadcast. State-Refresh läuft via
    `OverlayPermissionObserver.refresh()` an Lifecycle-Triggern
    (`onCreateInputView` / `onStartInputView`). Polling wäre Anti-Pattern.
    GAP-6 in §13.5.c als bewusste Akzept-Eigenschaft.
  - **Drag-Hoheit vs. State-Read-Konflikt (R.18):** `applyPosition()`
    early-returnt während aktivem Drag — sonst würde ein konkurrenter
    State-Update die Drag-Position überschreiben. Atomicity-Detail.
  - **View-Size 0 beim ersten Render (F-6 / GAP-7):** `view.post { applyPosition
    (stateRef) }`-Hook nach `dragHandler.attach` re-appliziert die Position
    nach dem ersten Layout-Pass. Sonst springt das Widget einmal.
  - **Mode-Transition T7 (Cluster mit 3.1.2):** "Geist-Widget"-Bug
    strukturell ausgeschlossen — `PipelineDone` in HOVER triggert
    `ViewMode.KEYBOARD` via Cross-Module-Cascade. Test-Plicht in
    Hauptdok §10 Acceptance Block 1.
  - **`userPrefersWidget`-Persistenz (§11.9):** Bewusst transient
    (in-Memory). Nach OOM-Recovery `false`. Begründet weil
    Session-Boundary durch Process-Restart überschritten ist.
  - **5-Button-Layout (OPEN-2):** WIDGET ist autark — Record-Button
    sichtbar in Idle, in HOVER disabled (kein InputConnection-Target).
    Out-of-Scope: WIDGET-autark in HOVER (Phase-2 STANDALONE_OVERLAY).
  - **Manifest-Permission `SYSTEM_ALERT_WINDOW`:** Special-Permission
    auf API 23+, kein Runtime-Prompt — User MUSS in System-Settings
    explizit umschalten. Settings-Deep-Link über
    `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.
- **Abhängigkeiten:** S-1 (`OverlayState` Sub-Achse), S-3 (`OverlayAction`-
  Hierarchie + `ViewModeAction`), S-4 (OverlayModule + ViewModeModule),
  S-9 (`OverlayState.suppressAutoOverlayUntilNextSession`-Bit-Lifecycle),
  S-6 (LayoutCatalog.OVERLAY_5BUTTON-Definition lebt cross-spec, Spec 2
  §3.2). Block 6 in der Plan-Hauptdok-Reihenfolge — der vorletzte Block,
  nach S-6 (Block 5).

---

### [S-9] ResetSuppressBit-Lifecycle: PENDING-3 Pseudo-Cascade → Cross-Module-Action mit Single-Reducer-Ownership

- **Scope:** Single-Reducer-Ownership des Bits
  `state.overlay.suppressAutoOverlayUntilNextSession` (verhindert HOVER-
  Auto-Reopen nach User-Klick auf den Overlay-Close-Button). Greifbar als
  eigenes Subsystem, weil es zwei Cross-Module-Cascades involviert
  (OverlayModule → SET via CloseOverlay; RecordingModule → RESET via
  StartRecording-Session-Start-Boundary).
- **Alt-Zustand:** Plan-Versionen vor PENDING-3 hatten zwei implizite
  Mutations-Pfade — `SuppressAutoOverlayUntilNextSession`-Cascade (HOVER →
  KEYBOARD-Boundary, setzt `true`) UND einen impliziten Reset, der über
  `SetUserPrefersWidget`-Cascade im OverlayModule nebenbei das Bit auf
  `false` zog. Doppel-Eigentum, leise Semantik-Drift, kein grep-bares
  Reset-Trigger. KG-RSB-2: `dispatchInternal`-Self-Filter
  `it.id != module.id` (Spec 1 §4.3 vor Fix) hätte den Reset blockiert —
  bestätigter Production-Bug.
- **Neu-Zustand:** Dedizierte `Action.OverlayAction.ResetSuppressBit`
  (`object`, kein Payload, idempotenter Reducer in OverlayModule.reduce —
  setzt `false` auch wenn bereits `false`, kein null-Return → kein
  `DispatchOutcome.Rejected("reducer-null")`). Emittiert von
  `RecordingModule.onCrossModuleStateChange` bei Boundary
  `prev.recording is Idle && next.recording is Preparing`. Self-Cascade
  ist erlaubt (Self-Filter in §4.3 Step 5 gestrichen, KG-RSB-2 Auflösung A,
  2026-05-11). MAX_CASCADE_DEPTH (R.6) bleibt einziger Endlos-Cascade-
  Schutz.
- **Plan-Refs:** Plan-Hauptdok §9 (Iter-Log 2026-05-11 PENDING-3); Plan-
  Hauptdok §7.3 KG-RSB-1..3 (alle RESOLVED); Spec 1 §4.3 (FIX-Kommentar in
  `dispatchInternal` Z. 619–627 — Self-Filter gestrichen); Spec 1 §15.2
  (`RecordingModule.onCrossModuleStateChange` mit ResetSuppressBit-Cascade,
  Z. 5127–5163; ausführliche Cascade-Sequenz-Tabelle Z. 5168–5213); Spec 1
  §15.1.x (Coupling-Matrix `Recording × Overlay = C(OverlayAction.
  ResetSuppressBit)` mit Self-Read-Konvention KG-RSB-3 RESOLVED); Spec 1
  §10 Acceptance R.RSB-FIX-A Regression-Test; Spec 2 §3.3 (Action-Definition
  als `object`, Z. 241–263); Spec 3 §4.8 (Spiegel-Eintrag im OverlayModule —
  Pseudo-Cascade gestrichen, durch Kommentar ersetzt); Spec 3 §11.9
  (Persistenz-Bit Boot-Default-Semantik KG-RSB-1 RESOLVED).
- **Code-Refs:** Heute komplett neu (das gesamte Subsystem entsteht erst
  mit dem Refactor). Anker-Sites in der neuen Architektur:
  `state/modules/RecordingModule.kt` (`onCrossModuleStateChange` Z. 5154–
  5160 Spec 1),
  `state/modules/OverlayModule.kt` (`reduce(ResetSuppressBit)` Z. 829–832
  Spec 3),
  `state/DictateOrchestrator.kt` (`dispatchInternal` Step 5 — KEIN Self-
  Filter).
- **Migrations-Schwerpunkt:**
  - **Self-Cascade-Erlaubnis (KG-RSB-2):** Production-Bug-Klasse — wenn
    irgendwer aus Reflex den Self-Filter wieder einbaut, ist
    HOVER-Auto-Reopen permanent kaputt. Regression-Test
    `recordingModule_idleToPreparing_emitsResetSuppressBit_viaSelfCascade()`
    ist Pflicht.
  - **Idempotenter Reducer:** Action returnt `TransitionResult` auch wenn
    Bit bereits `false`. Reducer-null würde `DispatchOutcome.Rejected
    ("reducer-null")` triggern — semantisch falsch für eine Session-Start-
    Markierung. Test-Coverage muss "Reset bei bereits-false" als
    `Applied` erwarten, nicht `Rejected`.
  - **Cancel-in-Preparing-Verhalten (Spec 1 §15.2 Z. 5146–5152):**
    Wenn User in `Preparing` canceled (`Preparing → Idle`), passiert
    KEIN Reset — Boundary-Test deckt nur `Idle → Preparing`. Eine im
    selben Lifecycle wieder gestartete Session wird beim erneuten
    `StartRecording` neu ge-reset. Edge-Case-Test in Acceptance R.RSB-
    FIX-A.
  - **Coupling-Matrix-Konvention (KG-RSB-3):** Self-Reads (Modul liest
    seine eigene Achse) werden NICHT in die Matrix-Zelle eingetragen.
    `Recording × Overlay = C(OverlayAction.ResetSuppressBit)` ohne
    `R(state.recording)`. Notations-Drift-Risk, wenn ein späterer
    Maintainer die Konvention nicht kennt.
  - **Bewusst transient (KG-RSB-1):** Boot-Default `false`, kein
    Pref-Mirror. Konsistent mit `userPrefersWidget` — Session-Boundary
    durch Process-Restart überschritten.
- **Abhängigkeiten:** S-1 (`OverlayState.suppressAutoOverlayUntilNextSession`),
  S-3 (`OverlayAction.ResetSuppressBit`, `OverlayAction.SuppressAutoOverlay
  UntilNextSession`), S-4 (Orchestrator-`dispatchInternal` ohne Self-Filter),
  S-8 (OverlayModule.reduce). Subsystem ist **Cross-Cutting** durch alle
  drei Specs. Implementiert teils in Block 4 (RecordingModule), teils in
  Block 6 (OverlayModule).

---

## Cross-Cutting Concerns (Subsystem-übergreifend)

Eigenschaften, die nicht zu einem einzelnen Subsystem gehören, sondern als
Querschnitt geprüft werden müssen:

- **F-8 Single-Dispatch-Ownership** — alle Mutationen laufen über
  `DictateOrchestrator.dispatch(action: Action)`. Kein direkter `_state.update`-
  Call außerhalb von Module-`reduce`. View-Properties werden nur über
  Backend-Resolver gesetzt, nie direkt. Audit-Pflicht über alle drei Specs
  (Spec 1 §13.2, Spec 2 §13.1, Spec 3 §13.1).
- **Pure-Reducer-Invariante (F1+F2)** — Reducer dürfen keine Hardware/IO-Reads
  machen. `audioFile` lebt im State (R.2), nicht im `ReducerContext`. Verstoß
  nur via Code-Review erkennbar.
- **MAX_CASCADE_DEPTH = 8** (R.6) — alleinige Endlos-Cascade-Sicherung nach
  KG-RSB-2-Fix. DEBUG-`error()`, Release-Log-`error`. Test-Coverage
  `cascade_loop_triggers_DispatchOutcome_Rejected_at_depth_8`.
- **Cross-Module-Effect-Modi 1+2 (Spec 1 §15.5)** — Mode 1: eigene SideEffect;
  Mode 2: Action-Cascade via `onCrossModuleStateChange`. Mode 3 (Atomic
  Cross-Axis-Update) ist Out-of-Scope, Phase-2-Backlog.
- **`onCrossModuleStateChange`-Konvention** — frozen-snapshot via `prevGlobal`
  + `nextGlobal`; Cascade-Actions werden rekursiv dispatcht mit depth+1;
  Self-Cascade ist erlaubt (KG-RSB-2-Fix).
- **Coupling-Matrix (Spec 1 §15.1.x)** — explizite Notation
  `R(state.x.y) C(Action.Y.Z)`. Self-Reads (Diagonale) implizit, KEIN
  separater Eintrag. Neue Cross-Module-Reads ohne Matrix-Update sind
  Code-Review-Verstoß.
- **PersistentList-Mutations-Idiom (Spec 1 §3 Z. 240–254)** — Reducer
  verwenden `add`/`removeAll` direkt, kein `toMutableList()`-Round-Trip
  (würde structural-sharing zerstören).
- **Acceptance-Test-Coverage** — Plan-Hauptdok §10 listet pro Block einen
  Acceptance-Block. R.RSB-FIX-A Regression-Test, R.16a/b/c Recovery-Tests,
  R.17 Idempotenz-Test, R.9 View-Recreate-Test, R.11/R.13/R.14 für
  MotionLayout. Test-Verzeichnis-Strategie: JVM-Tests
  (`app/src/test/`) für Reducer/Resolver, Instrumented-Tests
  (`app/src/androidTest/` — muss erstmals angelegt werden) für DB-Migration
  und Espresso-UI-Tests.
- **EN-Übersetzung der archivierten Plan-Files** — wird nach Phase-5 in
  einem separaten Pass erzeugt (siehe `~/.claude/snippets/docs/plan-archive-process.md`).
  Nicht Teil der Refactor-Inventur.

---

## Empfohlene Phase-B-Reihenfolge

Auf Basis der `Abhängigkeiten`-Felder oben — Foundation-Schichten zuerst,
Cross-Spec-übergreifende Querschnitts-Pfade später:

1. **[S-1] State-Klassen-Hierarchie** — Foundation. Ohne `DictateUiState` +
   Store haben keine andere Subsystem-Migration einen Ziel-Datentyp; alles
   andere baut auf Sub-State-Pfaden auf.
2. **[S-2] DB-Schema-Migration** — Schema kommt vor Logik. Die DB-Migrations-
   Test-Suite muss isoliert verifizierbar sein, bevor Module ihre Checkpoint-
   Hooks anbauen (S-4). Außerdem ist `androidTest/`-Verzeichnis-Neuaufbau
   ein eigener Schritt.
3. **[S-3] Action-Hierarchie** — Aktion-Vertrag muss stehen, bevor Module
   ihn implementieren (S-4) oder Backend-Resolver ihn emittieren (S-6, S-8).
4. **[S-4] Pipeline-Orchestrierung** — Modul-System mit allen 12 aktiven
   Modulen. Konsumiert S-1/S-2/S-3 als Foundation. Phase-B muss hier die
   Cascade-Mechanik (frozen-snapshot, Self-Cascade, MAX_CASCADE_DEPTH)
   besonders prüfen.
5. **[S-7] Audio-File-Management** — Logischer "kleiner Block" zwischen
   Foundation und Service-Wiring. AudioFileFactory wird in S-5 (Service-
   onCreate) gewired und in S-6 (Resolver) konsumiert.
6. **[S-5] Service-Schicht** — Foreground-Service umschließt das gesamte
   State-System aus S-1/S-2/S-3/S-4/S-7. Manifest-Diff, FGS-Timing,
   Bind-Lifecycle.
7. **[S-9] ResetSuppressBit-Lifecycle** — Cross-Cutting durch S-4 + S-8.
   Sollte VOR S-8 geprüft werden, weil die Self-Cascade-Erlaubnis aus
   §4.3 Step 5 ein architektonisches Detail ist, das in S-4 lebt, aber
   nur in S-8/S-9 sichtbare Konsequenzen hat.
8. **[S-6] Keyboard-Layout-Renderer** — UI-Layer für KEYBOARD-Modus.
   Konsumiert S-1 (State-Pfade), S-3 (Actions), S-7 (AudioFileFactory).
   Block 5 in der Plan-Implementierungs-Reihenfolge.
9. **[S-8] Floating-Overlay-Subsystem** — UI-Layer für WIDGET + HOVER.
   Komplett neues Subsystem; konsumiert S-1, S-3, S-4, S-6 (geteiltes
   `RenderBackend`-Interface + `applySlotToView`-Helper) und S-9
   (Suppress-Bit-Lifecycle). Block 6 in der Plan-Implementierungs-
   Reihenfolge.

Die Reihenfolge ist **kompatibel** mit der Plan-Hauptdok §4 Block-Reihenfolge
(1a → 2 → 1b → 3 → 4 → 5 → 6), spiegelt sie aber neu aus Migrations-Sicht
(Subsystem-Abhängigkeitsgraph statt Block-Implementations-Reihenfolge). Plan-
Blocks und Subsysteme stehen orthogonal zueinander — S-4 wird teilweise in
Block 1a (Quick-Wins, Helper-Konsolidierung) und teilweise in Block 1b
(Module-Architektur) gebaut.

---

## Surprise-Findings

Vier Beobachtungen, die nicht ins Standard-Format passen und die Phase B/C
prüfen sollte (KEINE Bewertung hier — nur Notierung):

1. **`F-3` Service-Aufteilung deckt nur 3 Klassen** — Spec 1 §7.1 nennt drei
   Helper (`PipelineStateManager` [umbenannt zu `DictateOrchestrator`],
   `PipelineNotificationCoordinator`, `PipelineActionRouter`). Aber im
   `DictatePipelineService.onCreate` (§7.3, Z. 3107–3130) wird **zusätzlich**
   `recovery`, `prefMirror`, `runner.initialize(orchestrator)` etc.
   konstruiert — der Service ist nicht ganz so schlank wie F-3 ihn beschreibt.
   Phase B/C könnte prüfen, ob §7.1-Tabelle und §7.3-Snippet konsistent sind.

2. **JobExecutor + PipelineOrchestrator bleiben unverändert (Spec 1 §9.6 +
   §13.2.1 Z. 4577–4578)** — die heutigen Pipeline-Klassen `JobExecutor.kt`
   und `PipelineOrchestrator.kt` (nicht zu verwechseln mit dem NEUEN
   `DictateOrchestrator`) sind als "nie gelöscht" markiert. Aber Spec 1
   §15.5 nennt zwei Modi für Cross-Module-Effekte und sagt "JobExecutor
   wird zu `PipelineRunner`-Interface-Konkretisierung". Es ist nicht ganz
   klar, ob der heutige `PipelineOrchestrator` (mit seinem eigenen
   Persist-Pfad in §4.11.6.1) zu einem Modul wird oder ein paralleles
   Subsystem bleibt. Phase B könnte dieses Naming-Konflikt-Risiko klären
   (zwei "Orchestratoren" im Codebase).

3. **`computeViewMode`-Pfad in Spec 3 §7.1 vs. `ViewModeFsm` in Spec 1 §15.1** —
   Spec 3 §7.1 (Z. 1238–1262) zeigt `computeViewMode` als Helper innerhalb
   des ViewModeModule. Spec 1 §15.1 Tabelle nennt ViewModeModule mit
   "F4-Subset (ehemals ViewModeFsm)". §13.3.4 (Spec 1) sagt "ViewModeFsm
   verschoben → §15.1 (ViewModeModule)". Aber `T2: WIDGET → KEYBOARD`
   in Spec 3 §7.3 (Z. 1339–1353) zeigt einen Reducer, der GLEICHZEITIG
   `viewMode`, `layout.smallMode` und `overlay.userPrefersWidget` mutiert
   — das wäre Cross-Axis-Mutation (Mode 3, eigentlich Phase-2-Backlog) im
   ViewModeModule-Reducer. Issue 1.1.2 Option A+B sagt aber: ViewModeModule
   mutiert NUR `viewMode`; LayoutModule + OverlayModule machen den Rest
   via Cascade. Die §7.3-T2-Code-Snippet könnte missverstanden werden.

4. **`androidTest/`-Verzeichnis fehlt im Repo** — eigene Verifikation
   (`Bash ls app/src/androidTest/`) bestätigt das Plan-KG (KG-SST-3,
   Spec 1 §11.4.2 Z. 3774). Block 3 muss `androidTest/`-Verzeichnis +
   `room-testing`-Dependency neu anlegen. Das ist ein **substanzielles
   neues Test-Setup** (Instrumented-Test-Runner, Gradle-Config), keine
   triviale Add-Migration. Phase B/C könnte prüfen, ob das im Implementer-
   Aufwand vom Block-3 ausreichend reflektiert ist.

---
