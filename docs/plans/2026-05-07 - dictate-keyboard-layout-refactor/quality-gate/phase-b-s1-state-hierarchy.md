# Phase B — S-1 State-Klassen-Hierarchie Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-1
**Plan-Version vor Edits:** 94b7410401c61cb16c873760e074820447e514fd (`feature/language-chip-curation`)

---

## Summary

Der Migrationspfad für S-1 ist **inhaltlich gesund** — das Ziel-Schema (`DictateUiState` mit 12 Sub-State-Klassen + 1 Top-Level-Bool, `DictateUiStateStore` als alleiniger MutableStateFlow-Owner, `DictateOrchestrator` als Single-Dispatch-Entry, F-11 Module pro Achse) deckt alle heutigen verstreuten Mutations-Sites ab, und das Atomicity-Argument (KSM `setSmallMode` → ein `state.copy()` statt zweier sequenzieller Schreiben) ist konsistent. **Kritisch war jedoch die textuelle Drift im Plan**: viele Sektionen referenzierten noch die pre-F-11-Architektur (monolithischer `PipelineStateManager` + `ViewModeFsm`), die seit 2026-05-10 nicht mehr existiert — d.h. ein Implementer hätte beim Lesen widersprüchliche Anweisungen bekommen (§4.3 sagt "DictateOrchestrator", §7.3 zeigte `PipelineStateManager`-Code). Befund: **2 Critical, 3 Important, 1 Minor**. **21 Plan-Edits in 2 Dateien** angewandt.

---

## Findings + Applied Fixes

### F-1 Sub-State-Pfad-Drift in Spec 2 §13.5 (Cross-Spec-Konsistenz)

- **Severity:** Critical
- **Prüf-Achse:** 6 (Cross-Spec-Konsistenz Sub-State-Pfade)
- **Was:** Spec 2 §13.5 verwendete an zwei Stellen noch flache Pfade — `state.resendCooldown` (Gap 2, Z. 2228) und `state.contentArea` (Gap 3, Z. 2235). Phase-1-Plan-Review (AI-1) hatte ähnliche Drifts in Spec 2 §8.5 / Spec 3 als RESOLVED markiert, aber §13.5 wurde übersehen.
- **Konsequenz:** Ein Implementer, der den Resend-Cooldown-Resolver schreibt, würde nach `DictateUiState.resendCooldown` greifen — Compile-Error, weil das Feld in `ResendState` hierarchisch liegt. Cross-Spec-Drift, der erst beim Build auffällt; im schlimmsten Fall führt es zu einem zusätzlichen flachen Boolean-Feld am Top-Level, das den SRP-Schnitt verletzt.
- **Fix angewandt:**
  - `Spec 2 §13.5 Gap 2`: `state.resendCooldown` → `state.resend.resendCooldown` (mit Hinweis auf F-11 Timer-Effect statt `Handler.postDelayed`).
  - `Spec 2 §13.5 Gap 3`: `state.contentArea` → `state.layout.contentArea`.

### F-2 Service-onCreate Naming-Drift (pre-F-11-Snippets)

- **Severity:** Critical
- **Prüf-Achse:** 1 (Migrations-Vollständigkeit), 5 (Initialisierungs-Reihenfolge)
- **Was:** Sechs Sektionen in Spec 1 zeigten Service-Wiring + Init-Order mit Klassen, die nach F-11 (2026-05-10) nicht mehr existieren:
  - `§4.11.5.1` Sequence-Tabelle: Schritt 5 nennt `stateManager = PipelineStateManager(…)`
  - `§4.11.5.3` Service-Wiring-Snippet: `private lateinit var stateManager: PipelineStateManager`
  - `§7.1` Service-Struktur-Diagramm: Composition-Root-Box zeigt `PipelineStateManager` statt `DictateOrchestrator` + Hilfsklassen
  - `§7.3` onCreate-Snippet: konstruiert `PipelineStateManager(scope, sessionRepo, runner, store, fsm = ViewModeFsm, …)` — `ViewModeFsm` ist nach F-11 ins `ViewModeModule` gewandert (§15.1)
  - `§11.1.4` 5-Sekunden-Timeout-Snippet: `stateManager = PipelineStateManager(scope, db, jobExecutor)`
  - `§11.6.1` recoverFromDb-Beispiel: identische pre-F-11-Konstruktion
  - `§11.2.2` Migrations-Reihenfolge: pre-Block-1a/1b-Split (Block-1 als monolithischer State-Refactor ohne Service-Container)
- **Konsequenz:** Ein Implementer hätte beim Folgen der §4.11.5/§7.3-Anweisungen eine `PipelineStateManager`-Klasse erzeugt — dieselbe God-Klasse, deren Vermeidung der Grund für F-11 war. Das Reihenfolge-Argument (`prefMirror.attach(store)` VOR `recovery.recover(store)`) wäre an die Service-onCreate-Sequenz delegiert worden statt im Orchestrator-Konstruktor-`init` zu leben (was es bereits tut, §4.3 Z. 567–570) — fragil bei späterer Implementer-Aenderung. KG-RSB-2-Bug-Klasse (Production-Bug durch architektonische Konfusion).
- **Fix angewandt:** Alle sechs Sektionen auf F-11-Naming umgestellt — `DictateOrchestrator` + `ModuleServicesFactory` + 12 Module + `PipelinePrefMirror`/`PipelineRecovery` als Hilfsklassen (§4.5/§4.6). §4.11.5.1 Sequence-Tabelle expandiert um Schritt 4 (ServicesFactory) und 6.5 (LegacyAudioFileMigration) plus explizite Begründung dass die Init-Order **im Orchestrator-Konstruktor-`init` codiert** ist (nicht extern an die Sequence delegiert). §11.2.2 auf Block-1a/1b-Split (R.7) umgestellt.

### F-3 Service-Field-Migration unvollständig (DictateInputMethodService.java:111–142)

- **Severity:** Important
- **Prüf-Achse:** 1 (Migrations-Vollständigkeit)
- **Was:** §13.2.1 Zeile 16 fasste sechs verschiedene Service-Felder (`livePrompt`, `pendingLivePromptChain`, `vibrationEnabled`, `autoSwitchKeyboard`, `restoreAutoEnter`, `restoreReprocessStaging`) in einer Sammelzeile zusammen mit der Aussage "view-recreate-bridges entfallen". Konkrete Migrations-Ziele waren nur teilweise genannt — für `restoreAutoEnter` und `restoreReprocessStaging` fehlte die explizite "ersatzlos gestrichen weil State überlebt View-Recreate"-Begründung; `autoSwitchKeyboard` (line 121) tauchte gar nicht in der Sub-State-Mapping-Tabelle (§3 Vergleichs-Tabelle) auf.
- **Konsequenz:** Ein Implementer hätte beim "Service-Felder aufräumen"-Schritt entweder die `restore*`-Felder vergessen zu löschen (Bug-Klasse: tote Felder, die niemand mehr setzt) oder eine eigene Bridge-Konstruktion für sie gebaut, weil unklar war, dass sie ersatzlos entfallen. `autoSwitchKeyboard` ist ein Spezialfall (Pre-IME-Switch-Toggle), der bewusst Service-lokal bleibt — aber das war im Plan nicht dokumentiert.
- **Fix angewandt:** §13.2.1 Zeile 16 in 16a–16f aufgespalten — jedes Feld bekommt einen expliziten Migrations-Ziel-Eintrag:
  - 16a `livePrompt` → `DictateUiState.livePrompt.enabled`
  - 16b `pendingLivePromptChain` → `DictateUiState.livePrompt.pendingChain`
  - 16c `vibrationEnabled` → `DictateUiState.audio.vibrationEnabled` (Pref-Mirror)
  - 16d `autoSwitchKeyboard` bleibt **bewusst Service-lokal** (mit Begründung)
  - 16e `restoreAutoEnter` **ersatzlos gestrichen** (mit Begründung: StateFlow überlebt View-Recreate, Spec 1 D1)
  - 16f `restoreReprocessStaging` **ersatzlos gestrichen** (analog, plus Block-1-Akzeptanz-Klausel: `cleanupOldControllers()` darf das Feld nicht mehr capturen)

### F-4 `DictateOrchestrator.shutdown()` rief Module-`terminate()` nicht (Lifecycle-Lücke)

- **Severity:** Important
- **Prüf-Achse:** 7 (Bugs durch Migration — Race Conditions, missed initial value)
- **Was:** §4.3 Z. 641 definierte `fun shutdown() = prefMirror.detach()` — ein Einzeiler, der nur den PrefMirror abmeldet. Das `DictateModule`-Interface (§4.2 Z. 442–448) definiert jedoch eine optionale `terminate(services: ModuleServices)`-Methode (Issue 2.1.12 / D7), die "vom Service-`onDestroy` mit `runBlocking`-Timeout gerufen" werden soll. Niemand ruft `terminate` — die Hardware-Cleanup-Sequenz (RecordingManager.release, BluetoothSco.stop, AudioFocus.release) lag effektiv im Niemandsland.
- **Konsequenz:** Service.onDestroy bei aktiv laufendem Recording würde MediaRecorder im Native-Heap zurücklassen (Bug-Klasse G6 in §13.5.a — der laut Plan zwar adressed ist, aber durch das fehlende `terminate`-Routing **nicht implementiert**). Die §10 Block-2-Acceptance "FIX Issue 3.0.11" wäre nicht erfüllt.
- **Fix angewandt:** `DictateOrchestrator.shutdown()` erweitert auf:
  1. `prefMirror.detach()`
  2. `modules.forEach { it.terminate(services) }` mit try-Catch pro Modul (ein Modul-Failure blockt andere nicht)
  3. KDoc dokumentiert: Reihenfolge, Synchronizität (run-blocking-tauglich), Service.onDestroy ruft anschließend `serviceScope.cancel()`.

### F-5 Block-1-Acceptance referenzierte nicht-mehr-existierende Klasse (post-R.7)

- **Severity:** Important
- **Prüf-Achse:** 1 (Migrations-Vollständigkeit), 3 (Atomicity)
- **Was:** §10 Block-1-Acceptance hatte vier Bullet-Points, die noch "Predicate im PipelineStateManager" referenzierten — also pre-R.7 (Block-1-Split in 1a/1b, 2026-05-10). Außerdem fehlte:
  - Ein expliziter Acceptance-Punkt für **`setSmallMode`-Atomarität** (das genau das Bug-Pattern aus der heutigen `KeyboardStateManager.kt:141-145` ist und der Refactor strukturell eliminieren soll).
  - Ein expliziter Acceptance-Punkt für die **Initialisierungs-Reihenfolge** (`prefMirror.attach` VOR `recovery.recover`).
  - Ein expliziter Acceptance-Punkt für das **PersistentList-Idiom** (Inventur §S-1 Migrations-Schwerpunkt).
- **Konsequenz:** Block-1b könnte technisch grün durchlaufen, ohne dass die Foundation-Eigenschaften strukturell verifiziert wurden — d.h. Block 2–6 würden auf eine ungeprüfte Foundation aufbauen.
- **Fix angewandt:** §10 in zwei Acceptance-Blöcke aufgeteilt (Block 1a Quick-Wins + Block 1b State-Architektur) mit 6 neuen Bullet-Points für Block 1b, inkl. expliziter Test-Klassen-Pointer (`LayoutModuleAtomicityTest.kt`, `DictateOrchestratorInitOrderTest.kt`, `DictateOrchestratorBootRaceTest.kt`).

### F-6 Initial-State-Race-Fence nicht im Acceptance (Bug-Klasse)

- **Severity:** Minor (gefixt zusammen mit F-5)
- **Prüf-Achse:** 7 (Bugs durch Migration — missed initial value)
- **Was:** Subscribers, die unmittelbar nach `bindService` auf `store.state` attached werden, können theoretisch den `DictateUiState.initial()`-Default sehen (keine Pref-Werte, keine pendingSessions), wenn die Init-Sequenz nicht synchron-fertig ist. Der `DictateOrchestrator`-Konstruktor sorgt zwar dafür dass `prefMirror.attach(store)` synchron vor `scope.launch { recovery.recover(store) }` läuft — aber das ist im Plan nirgendwo als Acceptance-Test-Anforderung festgehalten.
- **Konsequenz:** Ein zukünftiger Refactor, der die Init-Sequenz "lazy" macht (z.B. `prefMirror.attach` in `scope.launch` verlagert), würde die Garantie brechen, ohne dass ein Test rot fehlschlägt. Bug-Klasse: missed-initial-value, Subscriber sieht z.B. `audioFocusEnabledPref = true` (Default), obwohl der User in Settings `false` gesetzt hat — erste Audio-Recording-Aktion macht falsche Annahme.
- **Fix angewandt:** Block-1b Acceptance bekommt neuen Punkt: "`DictateOrchestratorBootRaceTest.kt` mit `FakeSharedPreferences`, asserts dass die erste `state.value`-Emission Pref-Werte enthält." §4.11.5.1 Sequence-Tabelle bekommt expliziten "Initial-State-Race"-Absatz, der die Garantie als **Teil des Orchestrator-Konstruktor-Vertrags** verankert.

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 2 | §13.5 Gap 2 | Fix | `state.resendCooldown` → `state.resend.resendCooldown` |
| Spec 2 | §13.5 Gap 3 | Fix | `state.contentArea` → `state.layout.contentArea` |
| Spec 1 | §1 Scope-Aufzählung | Fix | "konsolidierter PipelineStateManager" → "DictateOrchestrator + DictateUiStateStore + 12 Module" |
| Spec 1 | §2 D7 | Fix | "PipelineStateManager als alleinige Mutation-Quelle" → "DictateOrchestrator.dispatch(Action) (F-8 + F-11)" |
| Spec 1 | §4.3 `shutdown()` | Refactor | Erweitert um Modul-`terminate()`-Sequenz mit try-Catch + KDoc |
| Spec 1 | §4.11.5.1 Sequence-Tabelle | Refactor | DictateOrchestrator + ModuleServicesFactory + LegacyAudioFileMigration; Init-Order-Invariante als Orchestrator-Konstruktor-Vertrag verankert; Initial-State-Race-Hinweis |
| Spec 1 | §4.11.5.3 Service-Wiring-Snippet | Refactor | `lateinit var stateManager: PipelineStateManager` → `lateinit var orchestrator: DictateOrchestrator`; vollständiges F-11-Konstrukt mit `ModuleServicesFactory` + `emitAction`-Late-Reference |
| Spec 1 | §6.1 SessionManager-doc-Kommentare | Fix | "Called from PipelineStateManager" → "Called from `RecordingModule.runEffect(Effect.X)`" / `PipelineModule.runEffect(...)` |
| Spec 1 | §6.1 KG-SST-5 Marker | Fix | "PipelineStateManager schreibt zuerst die DB" → "Modul-EffectHandler schreibt zuerst die DB" |
| Spec 1 | §6.1 HistoryAdapter-Kommentar | Fix | "PipelineStateManager.recoverFromDb" → "PipelineRecovery.recover()" |
| Spec 1 | §6.1 SessionDao-doc-Kommentare | Fix | "PipelineStateManager.confirmInsertion / .recoverFromDb" → "PipelineModule.runEffect / PipelineRecovery.recover()" |
| Spec 1 | §6.4 OPEN-3 Schreib-Trigger-Snippet | Refactor | Vom `PipelineStateManager.updateOverlayPosition`-Method zu `OverlayAction.UpdatePosition` + `OverlayModule.reduce` + `Effect.PersistOverlayPosition` |
| Spec 1 | §6.4 SOLID-Konformität-Block | Fix | "Logik bleibt im PipelineStateManager gekapselt" → "Read im PipelinePrefMirror, Write im OverlayModule.runEffect" |
| Spec 1 | §7.1 Service-Struktur-Diagramm | Refactor | DictateOrchestrator + 5 Hilfsklassen + 12 Module statt monolithischer PipelineStateManager |
| Spec 1 | §7.3 onCreate-Snippet | Refactor | Vollständiges F-11-Wiring mit ModuleServicesFactory, Orchestrator-Konstruktor, LegacyAudioFileMigration, JobExecutor.initialize(orchestrator); ViewModeFsm-Parameter entfernt |
| Spec 1 | §7.5 PipelineActionRouter SRP-Block | Fix | "Mock-PipelineStateManager" → "Mock-DictateOrchestrator", dispatch(Action) statt typed Methoden |
| Spec 1 | §8 IME-Migrations-Tabelle | Fix | "wandert in PipelineStateManager" → "wandert in RecordingModule (§15.2) / PipelineModule" |
| Spec 1 | §9.1/§9.2/§9.3 Section-Titel + Migrations-Tabellen | Refactor | Targets auf RecordingModule / PipelineModule / LayoutModule + LayoutCatalog; Atomarität-Klausel für setSmallMode hinzugefügt |
| Spec 1 | §10 Block-1 Acceptance | Refactor | Aufteilung in Block-1a/Block-1b; 6 neue Block-1b-Acceptance-Punkte (SSOT, Single-Dispatch, Init-Order, Atomarität, PersistentList, Boot-Race) |
| Spec 1 | §11.1.4 5-s-Timeout-Snippet | Refactor | DictateOrchestrator-Init; Recovery läuft async vom Konstruktor selbst |
| Spec 1 | §11.2 Block-1/2/3-Implementation | Refactor | Auf R.7 Block-1a/1b-Split umgestellt; 8 detaillierte Sub-Schritte für Block 1b (DictateUiState + Orchestrator + Module + PrefMirror-Wiring + Recovery-Wiring) |
| Spec 1 | §11.2.3 Test-Strategie-Tabelle | Refactor | DictateOrchestratorTest / RecordingModuleTest / PipelineModuleTest / LayoutModuleAtomicityTest / PipelinePrefMirrorTest / PipelineRecoveryTest |
| Spec 1 | §11.6.1 recoverFromDb-Beispiel | Fix | DictateOrchestrator-Init; kein separater scope.launch nötig |
| Spec 1 | §11.7.1/§11.7.3 Test-Tabellen | Refactor | RecordingModuleTest / FakeLocalBinder / FakeModuleServices / FakePipelinePrefMirror / FakePipelineSessionRepo |
| Spec 1 | §13.2.1 Zeile 16 | Refactor | Aufspaltung in 16a–16f mit expliziten Migrations-Zielen pro Service-Field |
| Spec 1 | §13.2.1 Zeilen 1+2 | Fix | RecordingState.audioFile (R.2 Sub-Feld) statt eingekapselt-im-PipelineStateManager |
| Spec 1 | §13.2.2 Verifikations-Block + Spiegelung-Pattern | Fix | F-11-Modul-Reducer + PipelinePrefMirror statt monolithischer Manager-init |
| Spec 1 | §13.2.3 OPEN-3-Mutation-Tabelle | Refactor | OverlayAction.UpdatePosition + OverlayModule.reduce + Effect.PersistOverlayPosition |
| Spec 1 | §13.4.1 DRY-Tabelle | Fix | AudioFocus-Toggle → AudioAction.ToggleAudioFocus + AudioModule.reduce; SmallMode-Apply → PrefMirror.sync; LastAudioExists → ResendModule.Effect.RefreshLastAudioExists (Cross-Module-Cascade) |
| Spec 1 | §13.4.2 Code-Review-Checkliste | Fix | "sp.get nur im PipelineStateManager.init" → "sp.get nur im PipelinePrefMirror.initialMirror/sync" |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-1 Eintrag (2026-05-13) mit 5-Findings-Summary |

**Gesamt:** ~30 Edits in 3 Dateien (Spec 1: 27, Spec 2: 2, Hauptplan: 1).

---

## Offene Fragen für nachfolgende Agents

### Für S-2 (DB-Schema-Migration)
- `PipelineRecovery.recover(store)` ist im aktuellen Plan als asynchrone Operation im `scope.launch { ... }` definiert (§4.3 init Z. 569). Das ist konsistent mit dem Plan, aber für Phase-B S-2 sollte verifiziert werden, ob die Recovery-DAO-Reads (insbesondere `markRecordingAsFailed` + `clearAudioFilePath` für RECORDING-Sessions) im Multi-Job-Modell (R.8) korrekt mit dem JobExecutor-Re-Init interagieren — die Initial-State-Race-Fence (Block-1b Acceptance neu) testet nur Pref-Mirror, nicht Recovery.

### Für S-4 (Pipeline-Orchestrierung)
- Der `dispatchInternal`-Self-Cascade-Fix (KG-RSB-2, 2026-05-11) ist im Plan korrekt eingearbeitet. **Aber:** der Cascade-Snapshot wird in §4.3 Step 5 als `nextGlobal = store.snapshot` gelesen — *nach* `runEffect`-Aufrufen in Step 4. Wenn ein `runEffect` synchron einen weiteren `dispatch` triggert (was es laut Reentrancy-Vertrag NICHT darf — `emitAction` async-via-scope ist Pflicht), wäre der Cascade-Snapshot stale. Phase-B S-4 sollte einen Test schreiben, der einen Reentrancy-Verstoß deterministisch detektiert.

### Für S-8 (Floating-Overlay-Subsystem)
- `OverlayState.hasPermission` wird vom `OverlayPermissionObserver` (Spec 3 §5.0) synchron gehalten, aber die **initiale Permission-Abfrage** ist nicht im `PipelinePrefMirror.initialMirror` enthalten (richtig, weil es kein Pref ist) und nicht im Orchestrator-Konstruktor synchron getriggert. Ergebnis: zwischen `bindService` und der ersten Observer-Emission zeigt der State `hasPermission = false`, selbst wenn die Permission tatsächlich gegeben ist. Phase-B S-8 sollte prüfen, ob ein synchroner Initial-Read in `OverlayPermissionObserver.attach(store)` (analog zu `PipelinePrefMirror.attach`) im Orchestrator-`init` aufgerufen wird. Wenn nicht: gleiche Bug-Klasse wie F-6, nur für die Permission-Achse.

### Für S-9 (ResetSuppressBit-Lifecycle)
- §15.2 RecordingModule's `onCrossModuleStateChange` feuert `OverlayAction.ResetSuppressBit` beim `Idle → Preparing`-Übergang. Der Block-Mapping (Plan §4) verortet S-9 in Block 4 + Block 6. Phase-B S-9 sollte verifizieren, dass der `R.RSB-FIX-A`-Regression-Test (Block-4-Acceptance, §10) wirklich rot fehlschlägt, wenn der Self-Filter in §4.3 Step 5 versehentlich wieder eingebaut wird — der Test ist im Plan beschrieben, aber das Test-File `DictateOrchestratorTest.kt` ist nicht angelegt (PENDING).

---
