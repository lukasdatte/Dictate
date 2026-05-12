# Validated Findings – Phase 2 — Batch 1 (Sections 1-3)

**Created:** 2026-05-10
**Mode:** autonomous
**Source:**
- `plan-review/phase2/batch1/section1-structure.md`
- `plan-review/phase2/batch1/section1-logic.md`
- `plan-review/phase2/batch1/section2-structure.md`
- `plan-review/phase2/batch1/section2-logic.md`
- `plan-review/phase2/batch1/section3-structure.md`
- `plan-review/phase2/batch1/section3-logic.md`

---

## Summary

- **Reviewed:** 79 raw issues from 6 reviewer-agents (Sec1-S: 11, Sec1-L: 20, Sec2-S: 10, Sec2-L: 12, Sec3-S: 12, Sec3-L: 14).
- **🟢 Auto-Fix:** 12 issues (Naming-Drift, Numerierungs-/Tabellen-Lücken, fehlende Doku-Sätze, kleine code-snippet-Korrekturen, redundante predicate-Overrides, eindeutige defensive-Lifecycle-Patches).
- **🟡 Needs Decision:** 18 issues (Architektur-Entscheidungen — ReducerContext-Surface, Effect-Failure-Vertrag, Cascade-Snapshot-Semantik, dispatch-Reentrancy-Vertrag, Service-Shutdown-Cleanup, IME-Service-Death-Path, parallele Pipelines, Block-1-Split, Hardware-Subsystem-DIP, KSM-Aufspaltung, Visibility-Owner-D2, MotionScene-firstRender, etc.).
- **❌ Eliminated / Merged:** 49 issues (35 als Verstärkungen in Phase-1-Issues konsolidiert, 8 cross-section-deduped innerhalb Batch 1, 6 over-engineering / non-actionable).
- **Most important findings:**
  Batch 1 verstärkt **alle 8 Phase-1-🟡-Issues** mit konkreten Code-Beispielen — die Phase-1-Befunde sind unverändert offen und durch Detail-Review bestätigt. Drei **neu** entdeckte Critical-Issues kommen hinzu: (a) Spec-1 §7+§9+§11 ist in **Vor-F-11-Vokabular** verfasst (PipelineStateManager + typed Action-Methods statt DictateOrchestrator + dispatch) — eine in sich widersprüchliche Spec; (b) Session-ID-Type-Mismatch (DAO=String vs. Action-Extras=Long) blockt Compile; (c) Spec-2 LayoutCatalog-Slot-Predicates haben einen **stillen Drift-Pfad** (hardcoded `{ false }` ist der eigentliche Bug-Fix, nicht das zentrale Predicate — bei späterem "DRY"-Refactor regrediert der Send-Mode-Bug). Vier weitere wichtige Themen ohne Phase-1-Pendant: IME-Service-Death-ohne-Reattach, parallele Pipelines (Auto-Enter-Race), View-Recreate-Vertrag (viewScope-Cancel), Effect-Failure-Behandlung (`runEffect` ist `Unit`-returning, throw kills cascade).

---

## 🟢 Auto-Fix Issues

### Issue 2.0.1: Spec 1 §3 — "15 State-Achsen" Header vs. 14 Tabellenzeilen Numerierungsdrift
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec1-Structure S-8 (→ `phase2/batch1/section1-structure.md` §S-8)
- **Description:** §3 Z. 207 sagt "**15 State-Achsen**", die folgende Tabelle Z. 209-224 hat 14 nummerierte Zeilen. Sub-State-Felder im `DictateUiState` (Z. 79-105) sind 14 (`recording, pipeline, viewMode, contentArea, layout, overlay, audio, resend, livePrompt, language, features, theming, pendingSessions, interruption`). Der Text ist falsch (vermutlich wurde `BluetoothScoPublicState` versehentlich mitgezählt, obwohl es ein verschachteltes Detail von `audio` ist).
- **Fix:** Spec 1 §3 Z. 207: "15 State-Achsen" → "14 State-Achsen (= Sub-State-Felder im `DictateUiState`)". Verschachtelte Sub-States (BluetoothScoPublicState etc.) als "Detail einer Achse" formulieren.
- **Auto-Fix rationale:** Eindeutige Zähl-Korrektur. Tabelle ist die Quelle der Wahrheit, Header zählt falsch. Keine Architektur-Wahl.
- **Status:** ✅ APPLIED — Spec 1 §3 (Achsen-Übersicht), Header-Zeile umformuliert auf "14 State-Achsen (= Sub-State-Felder im `DictateUiState`)" + Hinweis auf verschachtelte Sub-States.

---

### Issue 2.0.2: Spec 2 §9.4 / §11.5 — restliche `PipelineStateManager`-Treffer auf `DictateOrchestrator` umstellen
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec3-Structure S-8 (→ `phase2/batch1/section3-structure.md` §S-8); ergänzt Phase-1 1.1.1 mit konkreten Spec-2-Treffer-Stellen
- **Description:** Spec 2 referenziert an drei Stellen weiterhin `PipelineStateManager` als Quelle:
  - §9.4: "DictateUiState wird im PipelineStateManager (Spec 1) gehalten"
  - §11.5: "Amplitude/Timer-Hooks: kommen direkt vom `PipelineStateManager` (Spec 1)"
  - §11.5: LocalBinder-Erwähnung ohne Spec-1-§5-Querverweis

  Das ist genau das Naming-Drift-Muster aus Phase-1 1.1.1 — bei der Phase-1-Apply-Pass wurde Spec 2 entweder übersprungen oder die Spec-2-Refs nicht vollständig erfasst.
- **Fix:**
  - §9.4: "DictateUiState wird im DictateOrchestrator (Spec 1 §4.3) verwaltet, gespiegelt vom DictateUiStateStore (§4.4)".
  - §11.5: "kommen direkt vom AudioModule / RecordingModule (Spec 1 §15)".
  - §11.5 LocalBinder-Erwähnung mit Verweis auf Spec 1 §5 ergänzen.
- **Auto-Fix rationale:** Reines Naming-Update auf den in F-11 beschlossenen Namen (analog 1.0.1). Zielnamen existieren in Spec 1 §4.3 / §15 / §5. Keine Architektur-Wahl.
- **Status:** ✅ APPLIED — Spec 2 §9.4 (Tabellen-Zelle) auf `DictateOrchestrator (Spec 1 §4.3)` + `DictateUiStateStore (Spec 1 §4.4)` umgestellt; Spec 2 §11.5 Amplitude/Timer-Hooks-Satz auf `AudioModule / RecordingModule (Spec 1 §15)` + LocalBinder-Spec-1-§5-Querverweis ergänzt.

---

### Issue 2.0.3: Spec 1 §15.5 — Modus 3 (Atomic Cross-Axis) explizit als "Phase-2 / nicht eingebaut" markieren
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec1-Structure S-10 + Sec1-Logic L-8 (→ `phase2/batch1/section1-structure.md` §S-10; `section1-logic.md` §L-8); verstärkt Phase-1 1.1.3 mit Section-1-Code-Beweis
- **Description:** §15.5 listet drei Cross-Module-Effect-Modi, der Orchestrator-Code in §4.3 implementiert nur Modi 1+2 (`result.sideEffects.forEach`, `cascadeActions.forEach { dispatch(it) }`). Modus 3 hat keinen Code-Pfad. §15.6 SOLID-Verifikation ("OCP — Neues Modul = neue Datei") ist mit Mode 3 inkonsistent, weil Mode 3 zentralen Orchestrator-Code anfassen müsste. Phase-1 1.1.3 hat das geflaggt — Empfehlung war Option B ("Phase-2 markieren"). Section-1-Detail-Review bestätigt: §15.5-Eintrag ist als API-Vertrag für Modul-Implementierer formuliert; ein Implementierer, der Mode 3 wählt, merkt das erst zur Run-Time.
- **Fix:**
  - §15.5: Mode-3-Eintrag mit Hinweis "**Phase-2 / nicht eingebaut** — Mode 1+2 sind ausreichend; Mode 3 wird erst bei konkretem Bedarf nachgerüstet (kein Halb-Pattern)."
  - §15.6 SOLID-Verifikation: ein Satz "OCP gilt für Modi 1+2; Mode 3 würde OCP gegen den Orchestrator brechen — daher bewusst nicht eingebaut bis konkreter Bedarf."
- **Auto-Fix rationale:** Phase-1 1.1.3 Option B ist die geklärte Empfehlung; reine Doku-Korrektur, kein Code-Change. Identifiziert die Architektur-Entscheidung als Pattern statt Lücke.
- **Status:** ✅ APPLIED — Spec 1 §15.5 Mode-3-Tabellenzeile auf "**Phase-2 / nicht eingebaut**" + erweiterte Standard-Empfehlung; Spec 1 §15.6 OCP-Zeile mit Mode-3-OCP-Konsistenz-Hinweis ergänzt.

---

### Issue 2.0.4: Spec 1 §5 LocalBinder — `notifyImeViewShown/Hidden`-Wrapper entfernen (Variante A)
- **Category:** [SOLID]
- **Severity:** Nice-to-have
- **Source:** Sec1-Structure S-11 (→ `phase2/batch1/section1-structure.md` §S-11)
- **Description:** F-8-Architektur sagt: "LocalBinder schrumpft auf `state` + `dispatch` + Lifecycle-Hooks." §5 zeigt:
  ```kotlin
  fun dispatch(action: Action) = orchestrator.dispatch(action)
  fun notifyImeViewShown() = dispatch(Action.ViewModeAction.OnImeViewShown)
  fun notifyImeViewHidden() = dispatch(Action.ViewModeAction.OnImeViewHidden)
  ```
  Die Lifecycle-Hooks sind 1:1-Wrapper über `dispatch()` — die alte Drift-Falle (typed Forwarder neben Action-Sealed-Class). F-8 selbst nennt das als zu vermeidendes Pattern.
- **Fix:** Wrapper-Methoden in §5 entfernen. IME-Service ruft `pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)` direkt. §5-API schrumpft auf `state` + `dispatch`. Hinweis im §5-Header: "Alle UI-Events laufen über `dispatch(action: Action)`; keine typed Forwarder-Methoden — F-8-Geist."
- **Auto-Fix rationale:** F-8 ist der bereits beschlossene Vertrag; Wrapper-Methoden widersprechen ihm. Keine Architektur-Wahl, kleinerer Footprint, konsistent mit F-8.
- **Status:** ✅ APPLIED — Spec 1 §5 LocalBinder schrumpft auf `state` + `dispatch`; `notifyImeViewShown/Hidden`-Wrapper-Methoden entfernt; KDoc auf `dispatch` erklärt Lifecycle-Events via `Action.ViewModeAction.OnImeViewShown/Hidden`; IME-Service-Beispiel ergänzt (`pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)`).

---

### Issue 2.0.5: Spec 1 §3 — `pendingSessions` PersistentList-Mutations-Idiom dokumentieren
- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Source:** Sec1-Logic L-20 (→ `phase2/batch1/section1-logic.md` §L-20)
- **Description:** `state.pendingSessions: PersistentList<PendingSession>` ist structurally shared. Naive Mutationen wie `current.pendingSessions.toMutableList().apply { add(x) }.toPersistentList()` zerstören das Sharing. Plan zeigt das richtige Idiom nicht.
- **Fix:** §3 (oder §15 PendingSessionsModule) Hinweis-Block:
  ```kotlin
  // ✓ structural-share preserved
  pendingSessions = current.pendingSessions.add(newSession)
  // ✗ allocates fresh list
  pendingSessions = (current.pendingSessions + newSession).toPersistentList()
  ```
- **Auto-Fix rationale:** Doku-Ergänzung; das Idiom ist `kotlinx.collections.immutable`-Standard, keine Wahl. Verhindert subtile Performance-Regression im Reducer-Code.
- **Status:** ✅ APPLIED — Spec 1 §3 (DictateUiState-Block, vor Achsen-Übersicht) erhält "PersistentList-Mutations-Idiom"-Hinweis-Block mit ✓/✗ Code-Beispiel.

---

### Issue 2.0.6: Spec 1 §4.2 — Exhaustivity-Konvention für `Effect`/`reduce` `when`-Blöcke dokumentieren
- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Source:** Sec1-Logic L-13 (→ `phase2/batch1/section1-logic.md` §L-13)
- **Description:** `runEffect`/`reduce` `when`-Blöcke sollen exhaustiv sein. Wenn Effect-Interfaces nicht `sealed` deklariert werden, kann der Compiler keine Exhaustivity erzwingen. Plan zeigt das Pattern an Beispielen, formuliert die Konvention aber nicht.
- **Fix:** §4.2 Konventions-Block: "Alle `Effect`-Interfaces sind `sealed interface`. Alle `runEffect`/`reduce` `when`-Blöcke sind expression-form (`= when { ... }`) — Compiler erzwingt Exhaustivity. `else`-Branch ist nur bei explizit nicht-sealed Effekten erlaubt + verlangt Begründung."
- **Auto-Fix rationale:** Etablierte Kotlin-Konvention; Plan zeigt das Pattern bereits an Code-Beispielen. Reines Doku-Update.
- **Status:** ✅ APPLIED — Spec 1 §4.2 (am Ende, nach `ReducerContext`-data-class und vor §4.3) "Exhaustivity-Konvention für `reduce` / `runEffect` `when`-Blöcke"-Block ergänzt (sealed Effect-Interfaces, expression-form `when`, `else`-Branch nur mit Begründung).

---

### Issue 2.0.7: Spec 1 §4.7 — `ModuleServices.scope`/`emitAction` KDoc-Vertrag ergänzen
- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Source:** Sec1-Logic L-17 + Sec2-Structure S-6 + Sec2-Logic L-8 (→ `section1-logic.md` §L-17; `section2-structure.md` §S-6; `section2-logic.md` §L-8)
- **Description:** §4.7 deklariert `val scope: CoroutineScope` und `val emitAction: (Action) -> Unit` ohne Vertrag. Sec1-Logic + Sec2-Structure + Sec2-Logic flaggen die Lücke konsistent: Sync? Async? Welcher Scope? Cancel-Verhalten? Reentrancy? Implementierer wird raten.
- **Fix:** §4.7 KDoc auf `scope` und `emitAction` ergänzen:
  ```kotlin
  /**
   * FGS-`serviceScope` (`SupervisorJob() + Dispatchers.Main.immediate`).
   * Wird in `Service.onDestroy` über `serviceScope.cancel()` beendet —
   * alle in-flight Effects sind danach gecancelt.
   * EffectHandlers MÜSSEN ihre Background-Coroutines in diesem Scope starten;
   * Effects, die das Service-Lifetime überdauern müssen, gehören in einen
   * separaten Worker (heute kein Use-Case).
   */
  val scope: CoroutineScope,
  
  /**
   * Posts eine Action an den Orchestrator. Ausführung **immer asynchron**
   * über `scope.launch { dispatch(action) }` — re-entrant Aufrufe aus
   * `runEffect` heraus sind sicher, weil sie als nächste Main-Looper-Message
   * landen. Zählt als frische Cascade-Tiefe (siehe §4.3 Cascade-Tiefen-Counter).
   */
  val emitAction: (Action) -> Unit,
  ```
  Hinweis: die "async via scope"-Vertrags-Wahl folgt Sec1-Logic L-3 Empfehlung A. Falls 2.1.4 (Orchestrator-Reentrancy-Vertrag) auf Variante B (synchrone Queue) entscheidet, wird dieser KDoc-Block dort übersteuert. Für den Auto-Fix nehmen wir Variante A — sie ist die strukturell konservative Wahl und passt zur Standard-MVI-Konvention.
- **Auto-Fix rationale:** Doku-Lücke wird geschlossen mit der Standard-MVI-Konvention (async via scope). Falls 2.1.4 später anders entscheidet, ist der KDoc-Block gezielt überschreibbar. Verhindert sofortige Implementierungs-Drift.
- **Status:** ✅ APPLIED — Spec 1 §4.7 `ModuleServices`-Konstruktor: KDoc auf `scope` (FGS-`serviceScope`, SupervisorJob, cancel-on-onDestroy) und `emitAction` (async via `scope.launch { dispatch(...) }`, Reentrancy-sicher, Cascade-Tiefe-Counter) ergänzt; Hinweis auf Issue 2.1.4-Override-Pfad enthalten.

---

### Issue 2.0.8: Spec 1 §15.2 — `RecordingState.Paused.Stop/Cancel` `TODO()`-Stubs durch echte Reducer-Arme ersetzen
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec1-Logic L-18 (→ `section1-logic.md` §L-18)
- **Description:** §15.2 Z. 2355-2356 hat:
  ```kotlin
  Action.RecordingAction.StopRecording -> /* analog Active.Stop */ TODO()
  Action.RecordingAction.CancelRecording -> /* analog Active.Cancel */ TODO()
  ```
  `TODO()` wirft zur Run-Time. Als Spec-Beispiel könnte das einkopiert werden. Der "analog Active"-Kommentar misleadet, weil `Paused` keinen `audioFile` hat (Active.Cancel löscht den Audio-File via `effect.recordingAudioFile?.let { Effect.DeleteAudioFile(it) }`).
- **Fix:** Beide Reducer-Arme ausformulieren:
  ```kotlin
  Action.RecordingAction.StopRecording -> TransitionResult(
      newState = RecordingState.Idle,
      effects = listOf(Effect.StopMediaRecorder, Effect.StopTimer, Effect.StopBorderGlow,
                       Effect.StartPipeline(ctx.recordingAudioFile ?: error("audioFile missing in Paused-Stop")))
  )
  Action.RecordingAction.CancelRecording -> TransitionResult(
      newState = RecordingState.Idle,
      effects = listOfNotNull(Effect.StopMediaRecorder, Effect.StopTimer, Effect.StopBorderGlow,
                              ctx.recordingAudioFile?.let { Effect.DeleteAudioFile(it) })
  )
  ```
  Hinweis: setzt voraus, dass `ctx.recordingAudioFile` für Paused weiter verfügbar ist (oder dass `Paused` ein `audioFile`-Feld bekommt — siehe Phase-1 1.1.7 Option A / 2.1.5).
- **Auto-Fix rationale:** Klare Translation der Active-Arme auf Paused. Wenn 2.1.5 entscheidet "audioFile in State spiegeln", wird der `ctx.recordingAudioFile`-Read auf `state.audioFile` geändert — kein neuer Reducer-Branch.
- **Status:** ✅ APPLIED — Spec 1 §15.2 RecordingModule.reduce, `Paused`-Branch: `StopRecording`/`CancelRecording` durch echte `TransitionResult`-Arme ersetzt (analog Active.Stop/Cancel — ohne `StopAmplitudeStream`/`ResumeBorderGlow`, weil Pause die bereits gestoppt hat); Inline-Kommentare verweisen auf Issue 2.1.5-Konditionalität (Hardware-Read aus `ctx` vs. State-Field).

---

### Issue 2.0.9: Spec 1 §9 — Lösch-Tabelle ergänzen (welche heutigen Klassen gelöscht/Adapter/erhalten)
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec2-Logic L-9 + Sec2-Structure S-7 (→ `section2-logic.md` §L-9; `section2-structure.md` §S-7)
- **Description:** §9.1-§9.5 migriert nur 3 Klassen explizit (RecordingStateController, KeyboardUiController, KeyboardStateManager). RecordingUiController, LanguageController, BluetoothScoManager, RecordingManager, JobExecutor — kein Lösch- oder Adapter-Status. Implementer müssen raten. Sec2-Logic L-9 schlägt eine konkrete Tabelle vor.
- **Fix:** §9 (oder §9.6) "Lösch-Tabelle":
  | Heutige Klasse | Final gelöscht in Block | Übergangsweise als Adapter? |
  |---|---|---|
  | RecordingStateController | Block 1 (nach Migration) | nein, direkt gelöscht (Tests umgeschrieben) |
  | KeyboardUiController | Block 1 | partial: state-Teil wandert, View-Teil bleibt bis Spec 2 |
  | RecordingUiController | Block 5 (LayoutCatalog) | bleibt bis dahin, sub-set seiner Methoden wandern |
  | KeyboardStateManager | Block 5 (LayoutCatalog) | bleibt bis dahin, danach Aufspaltung (siehe 2.1.13) |
  | LanguageController | Block 1 (LanguageModule) | wandert in Module-Reducer |
  | RecordingManager | nie gelöscht (Subsystem-Adaptee) | wird hinter Subsystem-Interface gewrapped |
  | BluetoothScoManager | nie gelöscht | Subsystem-Adaptee |
  | JobExecutor | nie gelöscht | implementiert `PipelineRunner`-Interface |
- **Auto-Fix rationale:** Reine Tabelle-Ergänzung; existierende Migration-Aussagen + Code-Realität diktieren die Einträge. Pro Block "End-of-Block-Cleanup-Check" via grep ergibt sich daraus.
- **Status:** ✅ APPLIED — Spec 1 §9.6 (neue Subsektion zwischen §9.5 und §10) "Lösch-/Adapter-/Erhalt-Tabelle (heutige Klassen → künftiger Status)" hinzugefügt mit den 8 Klassen aus dem Issue-Vorschlag + End-of-Block-Cleanup-Check-Hinweis.

---

### Issue 2.0.10: Spec 1 §6.3 — Pref-Mirror-Bypass-Block aus `recoverFromDb` entfernen
- **Category:** [DRY]
- **Severity:** Important
- **Source:** Sec2-Structure S-4 (→ `section2-structure.md` §S-4)
- **Description:** §6.3 `recoverFromDb` liest Overlay-Position direkt aus `PreferenceManager.getDefaultSharedPreferences(ctx)` und schreibt sie ins State per `_state.value.copy(overlayPositionPortraitX = …)`. §4.5 `PipelinePrefMirror` macht denselben Read kanonisch. Doppel-Read, doppelter Write-Path. Zudem nutzt §6.3 die **flachen** Felder (`overlayPositionPortraitX`), die F-10 in `state.overlay.positionPortraitX` umgestellt hat — also auch noch eine F-10-Inkonsistenz.

  `PipelinePrefMirror.attach(store)` läuft in `DictateOrchestrator.init` (§4.3) **vor** `recovery.recover(store)` — Overlay-Position ist beim Recovery-Start bereits im Store.
- **Fix:** §6.3 — den Pref-Read-Block (Z. 911-919) löschen. `recoverFromDb` mutiert nur noch `pendingSessions`. Damit löst sich gleichzeitig die F-10-Mismatch-Frage in §6.3.
- **Auto-Fix rationale:** Reine Code-Skizzen-Korrektur; PrefMirror ist die SSoT, der Bypass ist ein Drift-Artefakt aus einer früheren Iteration. Keine Architektur-Wahl.
- **Status:** ✅ APPLIED — Spec 1 §6.3 `recoverFromDb`: Pref-Mirror-Bypass-Block (Overlay-Position-Read aus `PreferenceManager`) gelöscht; Funktion mutiert nur noch `pendingSessions`. Begründungs-Block ergänzt (PrefMirror.attach läuft vor recovery.recover; F-10-Mismatch-Pfad eliminiert).

---

### Issue 2.0.11: Spec 2 §8.3 — Inline-Doku an `KEYBOARD_TWO_ROW_SEND_MODE.TRASH/PAUSE` (warum hardcoded `{ false }`)
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec3-Logic L-1 (→ `section3-logic.md` §L-1)
- **Description:** Send-Mode hat hardcoded `visibilityPredicate = { false }` für TRASH/PAUSE. Idle-Mode (TWO_ROW/SINGLE_ROW) nutzt `predTrashVisible`/`predPauseVisible`. Das ist die strukturelle Bug-Fix-Mechanik des User-Bugs §1.1 #3 — der Catalog-Switch eliminiert den Bug, **nicht** die zentralen Predicates. Wenn ein zukünftiger "DRY-Refactor" die hardcoded `{ false }` durch `predTrashVisible` ersetzt (weil "warum verschieden"), regrediert der Bug. Plan dokumentiert das nicht.
- **Fix:** Inline-Doku-Block an `KEYBOARD_TWO_ROW_SEND_MODE` und `KEYBOARD_SINGLE_ROW_SEND_MODE` (TRASH + PAUSE-Slots):
  ```kotlin
  // Hardcoded { false } statt predTrashVisible / predPauseVisible.
  // Begründung: bekannter User-Bug (Plan §1.1 #3 — "Send-Btn verdeckt im Send-Modus").
  // Der Catalog-Switch via forKeyboard(state) ist der Bug-Eliminator —
  // die zentrale Predicate predTrashVisible(state) liefert während des
  // Active → Pipeline.Preparing-Tick-Übergangs noch true (recording.isActive),
  // weil die Reducer-Reihenfolge nicht atomar ist.
  // NICHT auf predTrashVisible umstellen ohne Plan-Iter — siehe Test §14.2 UI-Test 4.
  ```
  Analog für PAUSE.
- **Auto-Fix rationale:** Reine Doku-Anker an load-bearing Code (Inline-Doc-SSoT-Anker §3 "Constraint/Gotcha-Comment"). Verhindert klassischen Drift-Pfad. Test-Empfehlung folgt in 2.0.12.
- **Status:** ✅ APPLIED — Spec 2 §8.3: Architektur-Notiz-Blockquote vor dem `KEYBOARD_TWO_ROW_SEND_MODE`-Block + Inline-Kommentare an allen vier `visibilityPredicate = { false }`-Stellen (TRASH/PAUSE in TWO_ROW + SINGLE_ROW SEND_MODE). Verweis auf Bug §1.1 #3 + Test §14.2 UI-Test 4.

---

### Issue 2.0.12: Spec 2 §8.5 — Inline-Doku an `predResendVisible` (warum cooldown nicht in visibility-predicate)
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec3-Logic L-2 (→ `section3-logic.md` §L-2)
- **Description:** Bug §1.1 #3 "Resend verschwindet beim Toggle" wird strukturell durch das `predResendVisible`-Schema gefixt: der 500ms-Cooldown landet im `enabledResolver` (disabled+alpha), **nicht** im `visibilityPredicate`. Damit kann kein Re-Parent + kein Cooldown mehr die Visibility kippen. Plan dokumentiert das nicht. Wenn ein zukünftiger Refactor "die Visibility soll auch den Cooldown beachten" zieht, reaktiviert sich der Bug.
- **Fix:** Inline-Doku an `predResendVisible(state)` (§8.5):
  ```kotlin
  // Visibility-Predicate für RESEND-Button.
  // Wichtig: resendCooldown ist NICHT Teil der Predicate — Cooldown landet
  // ausschließlich im enabledResolver (disabled+alpha 0.4f, siehe LayoutMode-Slots).
  // Begründung: bekannter User-Bug (Plan §1.1 #3 — "Resend verschwindet beim Toggle").
  // Der heutige Bug entstand durch transient visibility-Mutations bei Re-Parent.
  // Im neuen System kein Re-Parent (L2 flat hierarchy) + keine Cooldown-im-Visibility-Pfad
  // = strukturell ausgeschlossen. NICHT in den Visibility-Pfad ziehen ohne Plan-Iter.
  ```
- **Auto-Fix rationale:** Reine Doku-Anker an load-bearing Code (Inline-Doc-SSoT). Klassisches Drift-Verhinderungs-Comment.
- **Status:** ✅ APPLIED — Spec 2 §8.5: KDoc auf `predResendVisible` erweitert mit "WICHTIG (FIX 2.0.12): `resendCooldown` ist NICHT Teil dieser Predicate"-Block (Cooldown-Trennung, L2-flat-hierarchy, Bug §1.1 #3, Test-Anker §14.2 UI-Test 4).

---

## 🟡 Needs Decision Issues

### Issue 2.1.1: `ReducerContext` Surface-Design — `audio + recordingAudioFile + now` vs. `global: DictateUiState + now` vs. per-Modul-Context
- **Category:** [SOLID]
- **Severity:** Important
- **Source:** Sec1-Structure S-2 (→ `section1-structure.md` §S-2)
- **Description:** `ReducerContext(audio, recordingAudioFile, now)` ist auf den heutigen Recording-Module-Bedarf zugeschnitten. Andere Module brauchen `language` (Pipeline für Reprocess-Override-Auflösung), `pipeline` (Resend für Done-State-Trigger), `pipeline.ReprocessStaging` (LivePromptModule). Jede neue cross-axis-Bedingung erfordert eine `ReducerContext`-Erweiterung — verletzt OCP des Modul-Patterns + ISP. Hängt eng mit Phase-1 1.1.7 (Hardware-Read) zusammen.
- **Options:**
  A) `ReducerContext(global: DictateUiState, now: Long)` — Reducer ist pure relativ zu `(subState, action, globalSnapshot)`; darf querlesen, kein Wachstum der Surface. Konsistent mit `onCrossModuleStateChange(prev, next)`-Signatur. Risiko: Module könnten zu viel lesen (kein ISP-Schutz).
  B) `ReducerContext` pro Modul typisiert — jedes Modul deklariert seine Erwartungen. ISP-strikt, 13× eigener Type, Boilerplate.
  C) Status quo + Surface-Wachstum bei Bedarf — pragmatisch, OCP-Drift bei jedem neuen Modul.
- **Recommendation:** **Option A** — beseitigt OCP-Drift mit niedrigem Footprint, ist symmetrisch zu `onCrossModuleStateChange(prev, next)`. ISP-Schutz wird durch Konvention (Modul-Kommentar "liest nur diese Achsen") erreicht, nicht durch Type-Engineering. Löst gleichzeitig Phase-1 1.1.7 (Hardware-Read entfällt aus Context, wenn `recordingAudioFile` ins State wandert — siehe 2.1.5).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.2: `PipelinePrefMirror.sync()` — 19-Branch-`when` durch deklarative Pref-Bindings ersetzen (Modul-OCP)
- **Category:** [DRY]
- **Severity:** Important
- **Source:** Sec1-Structure S-1 (→ `section1-structure.md` §S-1)
- **Description:** §4.5 `sync()` enthält 19 fast identische `when`-Branches (`Pref.X.key -> current.copy(<sub> = current.<sub>.copy(<feld> = sp.get(...)))`). `initialMirror()` (Z. 541-573) wiederholt dieselbe Zuordnung. Pref-Mapping-Wissen lebt zweimal. Neue Pref hinzufügen = zwei Stellen ändern (= wird vergessen).
- **Options:**
  A) Pref-Bindings pro Modul deklarativ, PrefMirror als Aggregator:
     ```kotlin
     fun prefBindings(): List<PrefBinding<S, *>> = emptyList()
     data class PrefBinding<S, T>(val pref: Pref<T>, val apply: (sub: S, value: T) -> S)
     ```
     `PipelinePrefMirror` baut Map `pref.key -> (module, binding)` aus `DictateModuleRegistry.all.flatMap { it.prefBindings() }`. Eine Quelle pro (Modul, Feld). Konsistent mit OCP-Modul-Geist.
  B) `prefMap: Map<String, (DictateUiState, SharedPreferences) -> DictateUiState>` als Klassen-Konstante; `sync()` reduziert sich auf Map-Lookup. Pragmatischer, weniger architektonisch.
  C) Status quo + Konvention "neue Pref → 2 Stellen ändern" + Test, der Drift erkennt.
- **Recommendation:** **Option A** — passt zum Modul-Pattern (jedes Modul ist autonom), ist OCP-konform, eliminiert beide Doppel-Quellen. Footprint moderat (eine zusätzliche Methode am `DictateModule`-Interface, default `emptyList`).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.3: `runEffect` Failure-Vertrag — `Unit`-returning + throw kills cascade
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec1-Logic L-2 + Sec1-Logic L-14 (→ `section1-logic.md` §L-2, §L-14)
- **Description:** `fun runEffect(effect, services): Unit` ohne `try/catch` im `dispatch()`-Pfad. Eine throwende Effekt-Implementation:
  - lässt State **post-Reducer** mutiert (Z.B. `Recording.Preparing` ohne MediaRecorder)
  - tötet Cascade-Section (Schritt 5+6 in `dispatch`) → Cross-Module-Observer feuern nicht
  - bubbles via `LocalBinder.dispatch` zum IME-Service hoch → Tastatur stürzt ab (L-14)

  Subsysteme haben keinen typisierten Failure-Channel.
- **Options:**
  A) Per-Effect `try/catch`, Failure-Action `EffectFailure(moduleId, effect, throwable)` cross-cutting; Module können opt-in observen.
  B) `runEffect` returnt `EffectResult` (sealed: `Success | Failure(throwable)`) — typisierter Failure-Channel ohne Exceptions.
  C) Vertrag definieren: "Effects MUST NOT throw; Subsystems wrappen ihre eigenen Exceptions und emittieren explizite Failure-Actions via `services.emitAction`." (Doku-Vertrag, kein Code-Change am Orchestrator.)
  D) Kombiniert: A + Mindest-`try/catch` im Orchestrator als Safety-Net + `LocalBinder.dispatch` umschließt alles in `try/catch` damit IME niemals crashed.
- **Recommendation:** **Option D** — `try/catch` im Orchestrator pro Effect (`handleEffectFailure(...)`), `LocalBinder.dispatch` mit Top-Level-Schutz, `EffectFailure`-Action als Standard-Channel. Verhindert Tastatur-Crash + erlaubt Module reagieren. Architektur-Entscheidung wegen API-Auswirkung (neuer Action-Typ + Pattern für Failure-Recovery).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.4: Orchestrator-Reentrancy-Vertrag — sync `emitAction`-Reentrancy + Cascade-Snapshot-Semantik
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec1-Logic L-1 + L-3 + Sec2-Logic L-2 (→ `section1-logic.md` §L-1, §L-3; `section2-logic.md` §L-2); verstärkt Phase-1 1.1.5 (Cascade-Loop-Guard) mit weiteren Failure-Modes
- **Description:** Drei Reentrancy-/Snapshot-Themen, die verschränkt sind:
  1. **`emitAction` aus EffectHandler** — Sync vs. Async undefiniert; Sync-Re-Entry bricht Cascade-Snapshot-Semantik (outer-step-4-Inner-dispatch sieht prev/next von outer's pre-Cascade).
  2. **Cascade-Snapshot-Drift** — `prevGlobal/nextGlobal` werden vor der Cascade gefangen; jeder Cascade-Step mutiert Store. Observer-Call N+1 sieht "current" (drifted), nicht ursprüngliches `next`. Spec entscheidet das nicht.
  3. **Hardware-Callback-Threading** — RecordingManager-Callbacks laufen auf MediaRecorder-Thread, BluetoothScoManager-BroadcastReceiver auf Main. `dispatch` mutiert `MutableStateFlow` aus zwei Threads → Race.

  Phase-1 1.1.5 (Loop-Guard) hängt direkt damit zusammen — ohne Reentrancy-Vertrag ist Loop-Guard-Implementation mehrdeutig.
- **Options:**
  A) **`emitAction` immer async via scope, `dispatch` not-reentrant + Main-Thread-confined:**
     - `services.emitAction(x)` = `scope.launch { dispatch(x) }`
     - `dispatch` startet mit `require(Looper.myLooper() == Looper.getMainLooper())`
     - Cascade-Snapshot ist `prev = pre-trigger`, `next = post-trigger` (eingefroren, kein Re-Snapshot)
     - Cascade-Tiefen-Counter (depth=0..8) als Loop-Guard
  B) **`emitAction` synchron mit Queue:** outer-dispatch drained Queue vor Return; cascade snapshot wird re-evaluated per cascade step.
  C) **`emitAction` synchron, Cascade-Snapshot fest:** sync-Re-Entry erlaubt, aber cascade-prev/next bleiben ihren outer-Werten — Race-Risiko bei mid-cascade.
- **Recommendation:** **Option A** — Standard-MVI-Konvention (Compose-MVI / Redux-Toolkit), Test-freundlich, eliminiert Threading-Race ohne expliziten Lock. Loop-Guard ist Tiefen-Counter (depth>=8 → log.error + abort). Cascade-Snapshot-Doku in §15.5 ergänzen ("observers see prev=pre-trigger, next=post-trigger; not the moving state"). Konsequenz: 2.0.7 KDoc bleibt gültig.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.5: Phase-1 1.1.7 Auflösung — `recordingAudioFile` ins State spiegeln vs. lazy ctx vs. status quo
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec1-Logic L-5 + Sec2-Structure S-10 + Sec2-Logic L-2.3 (→ Phase-1 1.1.7 verstärkt mit konkreten Bug-Pfaden)
- **Description:** Phase-1 1.1.7 ist weiterhin offen. Sec1-Logic L-5 zeigt konkretes Bug-Szenario (Cancel während Active liest stale `currentAudioFile()` → Orphan-File-Leak). Sec2-Logic L-2.3 + Sec2-Structure S-10 bestätigen: Hardware-Read im Reducer-Pfad bricht Pure-Function-Vertrag.
- **Options:** (identisch mit Phase-1 1.1.7)
  A) `recordingAudioFile` in `RecordingState.Active`/`Preparing`/`Paused` als Field; `MediaRecorderReady`-Action füllt es; Reducer liest aus State.
  B) `buildContext` lazy: `ctx.recordingAudioFile()` als Funktion statt Wert.
  C) Status quo + Hardware-Read als akzeptiertes Trade-off.
- **Recommendation:** **Option A** — saubere Pure-Function-Garantie, eliminiert das Reducer-Test-Setup-Problem (kein Hardware-Mock für State-Tests), löst gleichzeitig 2.1.1 (`ReducerContext` schrumpft). Architektur-Entscheidung mit API-Auswirkung (`RecordingState`-Sub-Klassen-Felder ändern sich).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.6: `findModule` Routing — Map + isAssignableFrom-Fallback vs. sealed-leaves-Indexing
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec1-Logic L-4 + Sec1-Structure S-5 + Sec1-Logic L-11 (→ `section1-logic.md` §L-4, §L-11; `section1-structure.md` §S-5)
- **Description:** `findModule` macht Map-Lookup auf Concrete + Linear-Scan-Fallback mit `isAssignableFrom`. Drei Probleme:
  1. Ambiguity unentdeckt — Module mit überlappenden Action-Hierarchien routen zum erst-registrierten ohne Error.
  2. Inner sealed-Sub-Sub-Class-Footgun — neuer Sub-Sub-Class erbt unfreiwillig die Parent-Modul-Route.
  3. `Action.NoOp` fällt durch und produziert WARN-Spam (Phase-1 1.1.4).
- **Options:**
  A) **Sealed-leaves-Indexing:** beim Init `Action::class.sealedSubclasses` rekursiv walken; Map-Keys sind alle konkreten Leaf-Action-Klassen. O(1)-Lookup, kein Fallback. Init-Time-Error bei Ambiguity oder Unrouted-Leaf.
  B) **Linear-Fallback behalten + Init-Time-Check:** beim Init testen, dass kein Leaf zwei Module matched. Fallback wird nicht entfernt — Footgun bleibt.
  C) Status quo + Unit-Test, der Modul-Hierarchie scannt.
- **Recommendation:** **Option A** — eliminiert beide Footguns + Performance-Mikro-Issue (Linear-Scan im hot Path). `Action.NoOp`-Branch wird separat geklärt in 2.1.7. Setzt voraus: alle Action-Hierarchien sind `sealed`-deklariert (Plan §3.3 macht das bereits).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.7: Phase-1 1.1.4 Auflösung — `Action.NoOp` + Reducer-`null` + Three-indistinguishable-Outcomes
- **Category:** [CLEAN]
- **Severity:** Important
- **Source:** Sec1-Logic L-7 + Sec1-Structure S-3 (→ `section1-logic.md` §L-7; `section1-structure.md` §S-3); verstärkt Phase-1 1.1.4
- **Description:** Phase-1 1.1.4 weiterhin offen. Sec1-Logic L-7 ergänzt eine **dritte** "no-op"-Variante: `TransitionResult(state, emptyList())` mit `nextState == subState` — funktional gleich wie 1+2, aber kein Log. Drei nicht-unterscheidbare "Action did nothing"-Pfade. Tests können nicht via Output diskriminieren.
- **Options:** (kombiniert Phase-1 1.1.4 Optionen + neue Sec1-L7-Idee)
  A) Phase-1 Option A — `Action.NoOp` entfernen, `actionResolver: (DictateUiState) -> Action?` (nullable). Kombiniert mit Option D unten.
  B) Phase-1 Option B — `Action.NoOp` early-return im `dispatch`. Kombiniert mit Option D.
  C) Phase-1 Option C — Status quo + Log-Level-Anpassung.
  D) **Sec1-L7-Erweiterung:** `dispatch` returnt `DispatchOutcome` (sealed: `Applied | Rejected(reason) | Unrouted | NoOp`). IME ignoriert es; Tests können assertieren. Ortho zu A/B/C.
- **Recommendation:** **Option A + D kombiniert** — semantisch sauberster nullable-Resolver-Typ + typed Outcome für Tests. Eliminiert WARN-Spam, eliminiert Bug-vs-NoOp-Verwechslung, gibt Tests einen typisierten Hebel. Höchster Footprint von allen Optionen, höchster Nutzen. Architektur-Entscheidung.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.8: Cross-Module-State-Invarianten — keine zentrale Enforcement-Schicht
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec1-Logic L-6 + Sec1-Logic L-10 (→ `section1-logic.md` §L-6, §L-10)
- **Description:** Module reduzieren ihre Achsen unabhängig. Der Plan definiert nicht, welche cross-axis-Kombinationen verboten sind. Beispiele:
  - `recording=Active(useBluetooth=true)` + `audio.bluetoothSco.phase=Disconnected` → Recording mit BT-Mic ohne SCO-Link
  - `recording=Active` + `pipeline=ReprocessStaging` → Recording während Reprocess (anderswo "exklusiv" angenommen)
  - Pause→Resume rekonstruiert `Active(useBluetooth = ctx.audio.useBluetoothMic)` — wenn der User die Pref während der Pause ändert, kippt der Mic mid-Session (L-10).
  - `viewMode=HOVER` + `recording=Idle` — symmetrische Regel (Recording→Idle verlässt HOVER) nicht spezifiziert.
- **Options:**
  A) **Invariants-Subsection in §3** + final invariant-check step in `DictateUiStateStore.update` (throws/logs bei Verletzung). Single Point of Truth.
  B) **Sanity-Test-Suite** mit Fuzzing über State-Permutationen + manuelle Acceptance-Tests in §10.
  C) **`Paused`-Datentyp ergänzt um `useBluetooth: Boolean`** (gezielt 1 Invariant-Korrektur, nicht generisch — adressiert L-10 isoliert).
- **Recommendation:** **C als Sofort-Fix + A als längerfristig** — `Paused(useBluetooth: Boolean)` löst die konkrete Pref-Mid-Pause-Bug-Klasse. Invariants-Subsection + Store-Level-Check als Pattern für künftige Cross-Axis-Regeln. Architektur-Entscheidung wegen Pattern-Etablierung.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.9: IME-Service-Death während aktiver Pipeline — Insert-Pfad ohne InputConnection
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec2-Logic L-1 (→ `section2-logic.md` §L-1)
- **Description:** Plan adressiert Tastatur-Wechsel ausgiebig. Nicht modelliert: User wechselt **dauerhaft** auf andere IME (Gboard) während Pipeline läuft. `Pipeline-Done`-Notification-Klick ruft `confirmInsertion(id)`, aber `InputConnection` lebt im IME-Service, nicht im Pipeline-Service.
  - `inputConnectionProvider: () -> InputConnection?` (§4.7) — Quelle nicht spezifiziert
  - kein No-IC-Branch in Insert-Effect-Logik
  - DB hält `RECORDED/COMPLETED`-Session ohne klaren Owner
- **Options:**
  A) **No-IC-Branch in Insert-Effect:** `inputConnectionProvider() == null` → Notification-Action "Einfügen" disabled bzw. "Copy to Clipboard + Toast"; State-Achse `lastResultNeedsManualPaste: Boolean`; bei nächstem IME-Show als "Bereit zum Einfügen" anbieten.
  B) **DB-only:** Session bleibt mit `status=COMPLETED + inserted_at IS NULL` in DB; User muss History-UI aufrufen, um manuell zu inserten.
  C) **Kombiniert A+B:** No-IC → Clipboard + persistenter pending-Marker, der bei nächster IME-Show angeboten wird.
- **Recommendation:** **Option C** — User-Erfahrung optimal (sofortige Clipboard-Verfügbarkeit + automatisches Wiederaufnehmen). Architektur-Entscheidung wegen neuer State-Achse und neuer Notification-Logik.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.10: Concurrency — parallele Pipelines (Auto-Enter + zweites Recording)
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec2-Logic L-5 (→ `section2-logic.md` §L-5)
- **Description:** LivePromptModule's `Pipeline-Done → ChainNext` impliziert Folge-Pipeline-Start. Wenn der User parallel ein neues Recording startet, gibt es zwei Pipelines, die um `PipelineRunner.submit(...)` konkurrieren. Probleme:
  1. `PipelineRunner.submit` ist nicht reentrant-spezifiziert (Queue? Reject? Replace?)
  2. `PipelineUiState` sealed mit 4 States, kein Multi-Slot — A's Done-Action wird auf B's Slot projiziert
  3. DB-Write-Konkurrenz beim parallelen `markCompleted`
- **Options:**
  A) **Multi-Job-Modell:** PipelineUiState repräsentiert nur den UI-fokussierten Job; mehrere Background-Jobs erlaubt mit eigener `sessionId`. Done-Action enthält sessionId; Reducer matched gegen aktiven Slot — wenn ID nicht matched → Background-Insertion (DB + Notification, kein UI-Wechsel).
  B) **Single-Pipeline-Constraint:** Action.StartRecording im Zustand `pipeline=Running(_)` wird ignoriert oder triggert User-sichtbare Toast-Meldung "Vorherige Pipeline noch nicht fertig".
- **Recommendation:** **Option A** — Auto-Enter-Use-Case verlangt parallele Pipelines (sonst sinnlos). Architektur-Entscheidung wegen sessionId-Tracking-Surface.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.11: View-Recreate-Vertrag — viewScope-Cancel + State-Subscriber-Reattach
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec2-Logic L-3 (→ `section2-logic.md` §L-3)
- **Description:** Heute hat die Codebase 3 dedizierte view-recreate-Mechaniken (`cleanupOldControllers`, `rewireCallbacks`, `restoreUiState`). Im Refactor wandern viele Achsen zum Pipeline-Service — gut. Aber:
  - `viewScope` Cancel-Punkt nicht festgelegt (`onCreateInputView` vor Inflate? `onFinishInputView`?)
  - OverlayBackend + KeyboardLayoutManager haben view-bezogene Subscriber, die bei view-recreate detach + reattach brauchen
  - WindowManager.removeView (Overlay) ist kein StateFlow-Subscriber → cancel reicht nicht
- **Options:**
  A) **Explizite §8.x-Section "View-Recreate-Vertrag":**
     - viewScope-Erzeugung in `onCreateInputView` (vor Subscriber-Wiring)
     - viewScope.cancel() in `cleanupOldControllers`-Pendant (z.B. `onFinishInputView`)
     - Migrations-Tabelle "Detach-Calls heute → cancel automatisch in Refactor" (welche entfallen, welche bleiben)
     - Robolectric-Test "rotation while pipeline running"
  B) Status quo + Code-Review als Schutz.
- **Recommendation:** **Option A** — explizite Section (Doku) + Robolectric-Test. Architektur-Entscheidung wegen Lifecycle-Vertrag.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.12: Service-Shutdown-Cleanup — `DictateOrchestrator.shutdown()` released keine Hardware
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec2-Logic L-8 (→ `section2-logic.md` §L-8)
- **Description:** §7.3 `onDestroy` ruft `stateManager.shutdown()` → `prefMirror.detach()`. Es ruft NICHT `Effect.ReleaseMediaRecorder`, NICHT `cancelActive` auf PipelineRunner, NICHT `bluetoothScoManager.release()`. Bei System-Driven-Service-Stop (kein Process-Death) bleibt MediaRecorder Mikrofon-Lock + BT-SCO connected.
- **Options:**
  A) **Terminale Cleanup-Sequenz in `shutdown()`:** für jedes Modul `runEffect(<terminate-Effect>)`. Reihenfolge wichtig: Recording → AudioFocus → BT.
  B) **`onDestroy` mit `runBlocking` + Timeout:**
     ```kotlin
     override fun onDestroy() {
         super.onDestroy()
         runBlocking { stateManager.shutdownAndRelease(timeout = 1.seconds) }
         serviceScope.cancel()
     }
     ```
  C) Status quo + Doku "Process-Death-only release-Garantie".
- **Recommendation:** **Option A + B kombiniert** — terminale Cleanup-Sequenz pro Modul + Service-onDestroy mit Timeout. Architektur-Entscheidung wegen neuer Module-API (`terminate-Effect`).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.13: KeyboardStateManager-Aufspaltung nach Refactor (SRP-Restschwäche)
- **Category:** [SOLID]
- **Severity:** Important
- **Source:** Sec3-Structure S-7 (→ `section3-structure.md` §S-7)
- **Description:** Nach §9.3-Migration verbleibt KSM mit 4 Achsen: ContentArea-Visibility, Recording-Controls (gelöscht), Prompts-Visibility, Overlay-Reset. Plan sagt "wird zu einem orthogonalen ContentAreaController" — ohne weitere Details. Naming-Drift: Klasse heißt weiter `KeyboardStateManager`, deren ursprüngliche Rolle ("deterministischer Visibility-Owner") jetzt dem Catalog gehört.
- **Options:**
  A) **Aufspalten:** `ContentAreaController` (mainButtonsCl/qwertz/emojiPicker-Container-Toggle) + `PromptVisibilityController` (promptsCl + Sub-Views) + Overlay-Reset zum OverlayBackend. Drei kleinere Klassen, jede mit klarer Achse.
  B) **Umbenennen:** `KeyboardStateManager` → `ContentAreaController`, Prompts-Logik in eigene Klasse extrahieren.
- **Recommendation:** **Option A** — sauberer SRP, jede Achse ein Owner. Architektur-Entscheidung wegen Klassen-Aufspaltung.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.14: Visibility-Owner D2 — MotionScene vs. `applySlotToView` für statische Buttons
- **Category:** [SOLID]
- **Severity:** Critical
- **Source:** Sec3-Structure S-1 (→ `section3-structure.md` §S-1)
- **Description:** Plan verspricht "MotionScene managt Position, LayoutManager managt Visibility — kollisionsfrei (L3)". `applySlotToView` setzt `view.visibility` für **alle** Slots in der Render-Schleife. §7.3 setzt `visibilityMode="ignore"` nur auf Buttons mit nicht-konstantem Predicate — andere Buttons (record_pulse, backspace, space, enter) bekommen `visibilityMode` NICHT, ihre Visibility kommt aus der MotionScene. Doppel-Mutation für statische Buttons → exakt der Konflikt, den L3 verhindern wollte.
- **Options:**
  A) **`visibilityMode="ignore"` auf alle 9 Buttons:** `applySlotToView` ist einziger Visibility-Owner. §7.3-Tabelle wird trivial.
  B) **Render-Loop filtert:** `slots.filter { it.visibilityPredicateIsDynamic() }.forEach { ... }`. Predicates haben dafür ein `isDynamic`-Flag.
- **Recommendation:** **Option A** — SRP-stärker, Catalog ist die einzige Visibility-Quelle, MotionScene macht ausschließlich Position. Eindeutiger Vertrag, keine Sonderfälle. Architektur-Entscheidung wegen Visibility-Vertrags-Festlegung.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.15: Spec 2 — Beziehung KeyboardLayoutManager ↔ Spec-1-LayoutModule + ContentArea-Renderer
- **Category:** [SOLID]
- **Severity:** Critical
- **Source:** Sec3-Structure S-2 (→ `section3-structure.md` §S-2)
- **Description:** Spec 1 etabliert `LayoutModule` als dedizierte Modul-Achse. Spec 2 §4/§6 referenziert davon **nichts** — Manager liest direkt vom Top-Level `DictateUiState`, ContentArea-Toggle in §9.3 mutiert direkt `views.mainButtonsClTyped.visibility` über `ContentAreaController` (dritte Visibility-Achse außerhalb Catalog UND MotionScene). Verstärkt Phase-1 1.1.5 (LayoutModule SRP) — Spec 2 spielt das Modul-Pattern nicht mit.
- **Options:**
  A) **Explizite "§4.x Beziehung zu LayoutModule"-Section:**
     - LayoutModule.LayoutState → liefert `singleRowMode/contentArea` an `KeyboardLayoutManager.computeLayoutMode`
     - `Action.LayoutAction.*` werden vom Manager NICHT emittiert (nur Read-Subscriber)
     - ContentAreaController wird zweites RenderBackend (`MainContainerBackend`) ODER im `ImeViewBackend.render` über eigenen Slot/Helper integriert
  B) **ContentAreaController als zweites RenderBackend modellieren** (ohne Beziehungs-Section).
  C) Status quo (mit Phase-1 1.1.5 als verbundene Frage).
- **Recommendation:** **Option A + B kombiniert** — Beziehungs-Section macht das Pattern explizit, ContentAreaController als zweites Backend ist dann konsequent. Hängt eng mit 2.1.13 (KSM-Aufspaltung) zusammen — gemeinsam klären. Architektur-Entscheidung.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.16: Spec 2 LayoutMode `sceneStateId`-OCP + Erweiterungs-Checklist
- **Category:** [SOLID]
- **Severity:** Important
- **Source:** Sec3-Structure S-4 (→ `section3-structure.md` §S-4)
- **Description:** Neuer Layout-Modus erfordert Edits an 5 Stellen (LayoutModeId-Enum, toSceneStateId()-when, Scene-XML, Catalog-Konstante, forKeyboard()-Branch). `LayoutModeId.toSceneStateId()` ist exhaustive `when` als top-level extension — bricht OCP (jeder neue Mode editiert die zentrale Funktion).
- **Options:**
  A) **`sceneStateId` direkt am `LayoutMode`:** `data class LayoutMode(... val sceneStateId: Int? = null)`. `ImeViewBackend.render` liest `mode.sceneStateId`. `toSceneStateId()` entfällt. Plus "§8.x Erweiterungs-Pattern: neuer Layout-Modus"-Section mit 5-Punkt-Checklist.
  B) Status quo + Erweiterungs-Checklist.
- **Recommendation:** **Option A** — saubere OCP, neuer Mode = neue Konstante mit eigener sceneStateId. Hat moderaten Footprint.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.17: Spec 2 §11.8 Migration — KSM-`applyRecordingControlsVisibility` Übergangs-State
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec3-Logic L-7 (→ `section3-logic.md` §L-7)
- **Description:** §11.8 sagt "5d ist destruktiv — strikt am Ende". Aber innerhalb von 5c (Manager-Wiring) lebt `KSM.applyRecordingControlsVisibility` weiter, Manager schreibt korrekte Visibility, KSM überschreibt bei jedem `KSM.refresh`. Doppelmutation für die PR-Lücke zwischen 5c und 5d. Bug-Report-Risiko während Lücke.
- **Options:**
  A) **5c-Tail-Step:** KSM-Methoden durch leere Implementation **ersetzen** (nicht löschen). KSM.refresh ruft sie weiter auf — no-op. 5d entfernt dann die leere Methode + alle Aufrufer. Risiko-frei.
  B) **5c+5d zusammenführen:** ein PR statt zwei.
  C) **Strict-Mode-Logging während 5c:** `VisibilityWrite from $caller`-Log + Acceptance-Kriterium "keine zwei Subsysteme schreiben gleichzeitig".
- **Recommendation:** **Option A + C kombiniert** — leere Body in 5c eliminiert die Doppelmutation, Strict-Mode-Log macht Acceptance verifizierbar. Architektur-Entscheidung wegen Migration-Vertrag.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.18: Spec 2 §6 — `firstRender`-Flag für jumpToState-vs-transitionToState
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec3-Logic L-3 (→ `section3-logic.md` §L-3)
- **Description:** §10 Acceptance Block 5: "Re-Inflate (Rotation, Theme-Wechsel): erster Frame zeigt korrekten LayoutMode ohne Animation-Snap (`jumpToState` statt `transitionToState` beim ersten Render)." Code-Skizze in §6 liest `state.animationsEnabled` als Predicate — nicht "ist erster Render". Bei Re-Inflate ist `animationsEnabled=true` (User-Pref), also `transitionToState` → User sieht 250ms-Animation von MotionLayout-Initial-State (`two_row_state`) zum eigentlichen Mode → Animation-Snap. Acceptance ist durch Code nicht erfüllt.
- **Options:**
  A) **`firstRender: Boolean = true`-Field am Backend:**
     ```kotlin
     if (firstRender || !state.animationsEnabled) motionLayout.jumpToState(...)
     else motionLayout.transitionToState(...)
     firstRender = false
     ```
     + Inline-Doku, warum (MotionLayout-Initial-State immer erster ConstraintSet).
  B) **`detach()` setzt `firstRender = true` zurück** für korrekte Wiederverwendung über view-recreate.
- **Recommendation:** **Option A + B** — beides nötig für korrekte view-recreate-Semantik. Code-Snippet-Korrektur ist mechanisch, aber die Architektur-Entscheidung "wann ist 'first'?" ist Design-Frage (single attach? oder "nach jedem detach"?).
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

## Phase-1 → Batch-1 Verstärkungs-Tabelle

| Phase-1-Issue | Status nach Batch 1 | Verstärkungen aus Batch 1 |
|---|---|---|
| **1.1.1** PipelineStateManager-Naming-Drift | weiterhin offen — Spec-2-Resttreffer in 2.0.2 als 🟢 ausgekoppelt | Sec3-Structure S-8 (Spec-2 §9.4 + §11.5), Sec2-Structure S-1 (Spec-1 §7+§9+§11 ist insgesamt in Vor-F-11-Vokabular — der **größte** Resttreffer-Block, separat als 🟡 in Sec2-S-1 sichtbar; bleibt für Final-Report) |
| **1.1.2** Spec 3 direkte `_state.value.copy` | nicht in Batch 1 (Spec 3 = Batch 2) | — |
| **1.1.3** Modus 3 phantom | weiterhin offen — als 🟢 in 2.0.3 ausgekoppelt (Doku-Markierung "Phase 2"), Implementation bleibt 🟡 | Sec1-Structure S-10 (§15.6 SOLID-Inkonsistenz mit Mode 3), Sec1-Logic L-8 (Pipeline-Done-Beispiel mit Auto-Enter), Sec3-Logic L-12 implizit |
| **1.1.4** NoOp vs Reducer-null | weiterhin offen — verstärkt in 2.1.7 als 🟡 | Sec1-Logic L-7 (3 indistinguishable no-op outcomes + DispatchOutcome-Vorschlag), Sec1-Structure S-3 (Logging-Drift im Orchestrator) |
| **1.1.5** Cascade-Loop-Guard | weiterhin offen — verstärkt in 2.1.4 als 🟡 (Reentrancy-Vertrag insgesamt) | Sec1-Logic L-1 (Snapshot-Drift, kein Tiefen-Cap, Phase-1 1.1.5 unmitigated), Sec2-Logic L-2 (Hardware-Callback-Threading + Re-entrant-dispatch) |
| **1.1.6** Block-1 unterschätzt | weiterhin offen — verstärkt mit konkretem 1a/1b/1c-Vorschlag (Sec2-Logic L-6) | Sec2-Logic L-6 (1a/1b/1c/1d/1e-Split mit kompilier-grünen Etappen), Sec2-Structure S-1 (verbunden mit dem F-11-Vokabular-Drift) |
| **1.1.7** buildContext sync hardware | weiterhin offen — als 🟡 in 2.1.5 mit konkretem Bug-Beispiel | Sec1-Logic L-5 (Cancel orphan-File-Leak), Sec2-Logic L-2.3 (memory-Field aber un-pure), Sec2-Structure S-10 (Section-2-Relevanz) |
| **1.1.8** LayoutModule SRP | nicht direkt in Batch 1 (Phase-1 hatte falsche Nummer — eigentlich 1.1.5 in Phase 1) | Sec1-Logic L-9, Sec1-Structure S-4, Sec3-Logic L-12 (mit Cross-Achsen-Constraint-Argument), Sec2-Logic L-7 |

**Hinweis zur Phase-1-Nummerierung:** Phase-1 hat Issues 1.0.1-1.0.6 + 1.1.1-1.1.8 vergeben. Die Batch-1-Output-Headers verwenden teilweise leicht abweichende Bezugsnummern (1.1.4 vs. 1.1.5 für LayoutModule SRP). Die obenstehende Tabelle alignt zur kanonischen Phase-1-Nummerierung in `validated-findings-phase1.md`.

---

## Cross-Section-Dedup innerhalb Batch 1

| Konsolidiertes Issue | Quellen |
|---|---|
| 2.1.4 (Reentrancy-Vertrag) | Sec1-Logic L-1 + L-3, Sec2-Logic L-2 |
| 2.1.5 (recordingAudioFile in State) | Sec1-Logic L-5, Sec2-Structure S-10, Sec2-Logic L-2.3 |
| 2.1.6 (findModule-Routing) | Sec1-Logic L-4, Sec1-Logic L-11, Sec1-Structure S-5 |
| 2.1.7 (NoOp + null-Reducer + DispatchOutcome) | Sec1-Logic L-7, Sec1-Structure S-3, Phase-1 1.1.4 |
| 2.0.7 (`emitAction`/`scope` KDoc) | Sec1-Logic L-17, Sec2-Structure S-6, Sec2-Logic L-8 |
| 2.0.9 (Lösch-Tabelle) | Sec2-Logic L-9, Sec2-Structure S-7 |

---

## ❌ Eliminated Issues

| Original Issue | Source | Reason for Elimination |
|----------------|--------|------------------------|
| Sec1-Structure S-6 (`runEffect`/`emitAction`/cross-module 3 Stilarten) | section1-structure.md §S-6 | **Dedup mit 2.1.3 (Effect-Failure) + 2.0.7 (`emitAction`-KDoc)** — die Stilrichtlinie wird durch den Effect-Vertrag (2.1.3) + `emitAction`-Vertrag (2.0.7) eindeutig; separater Issue redundant. |
| Sec1-Structure S-7 (`ModuleServices` 14-Feld-Container ISP) | section1-structure.md §S-7 | **Over-engineering / Reviewer akzeptiert Status quo.** S-7 selbst empfiehlt "Status quo + ein Doku-Satz". Der Doku-Satz wird in 2.0.7 (KDoc-Pass) mitgenommen — kein eigener Issue. |
| Sec1-Structure S-9 (Datei-Layout `state/` zentral vs. modules/) | section1-structure.md §S-9 | **Reviewer-Empfehlung Variante B (Status quo) bestätigt aktuellen Stand** — Plan-Inkonsistenz war nur im §15-Header-Satz; das ist Teil von 2.0.6 (Convention-Block) bzw. fällt im EN-Translation-Pass beim Archivieren auf. |
| Sec1-Logic L-12 (PrefMirror Pref-Storms + default-Duplikat) | section1-logic.md §L-12 | **Out-of-scope (PERFORMANCE)** + Default-Duplikat fällt mit 2.1.2 (Pref-Bindings deklarativ) automatisch. |
| Sec1-Logic L-15 (`OnImeViewShown`-Naming gehört zu Lifecycle-Achse) | section1-logic.md §L-15 | **Nice-to-have, low-priority Naming.** Wenn überhaupt, fällt das mit 2.0.4 (LocalBinder-Wrapper entfernen — direkter `dispatch`) zusammen. Separater Issue overkill. |
| Sec1-Logic L-16 (`onCrossModuleStateChange` → `cascadeFrom`) | section1-logic.md §L-16 | **Nice-to-have Naming-Vorschlag**, low-priority. Die Plan-API ist konsistent verwendet; Umbenennung würde mehr Diff verursachen als Nutzen. |
| Sec1-Logic L-19 (PendingSessionsModule Reducer vs. Backdoor) | section1-logic.md §L-19 | **Dedup mit 2.0.6 (Convention-Block) + Inline-Doku** — Sec2-Structure S-3 (Repo-Bypass) + 2.0.6 enthalten die Anwort: alle DB-Reads gehen durch Repo, alle Mutationen durch dispatch. PendingSessionsModule braucht eine `OnDbUpdate(...)`-Action, die ist trivial und kann beim Apply-Step in 2.0.6 als ein Beispiel mit reingenommen werden. Kein eigener Issue. |
| Sec2-Structure S-2 (Hardware-Subsystem-Adapter duplicate `BluetoothScoControl`/`AudioFocusGate`) | section2-structure.md §S-2 | **Dedup mit Spec-1-Vor-F-11-Vokabular-Block** (Sec2-Structure S-1) — wenn §7+§9+§11 auf F-11-Vokabular umgeschrieben werden (eigenes 🟡-Issue im Final-Report wegen Größe), fließt automatisch das richtige Naming für Subsystem-Interfaces ein. Separater Issue redundant. *Hinweis: Sec2-Structure S-1 selbst (Vor-F-11-Block) ist ein **eigenes 🟡** im Final-Report-Backlog; hier nicht separat aufgenommen, weil es als Resttreffer der Phase-1-1.1.1-Familie erfasst ist.* |
| Sec2-Structure S-3 (PipelineSessionRepo bypass §6.3 + §11.6.2) | section2-structure.md §S-3 | **Dedup mit 2.0.10 (Pref-Mirror-Bypass entfernen)** für §6.3 und mit Sec2-Logic L-4 (Recovery-DAO-Reads) für §11.6.2. §11.6.2-Anteil wird mit 2.1.6 (sealed-leaves-Indexing) entkoppelt — die DAO-Methoden müssen ins Repo-Interface, das ist Teil des `recoverFromDb`-Refactors in §11.6.2 (Sec2-Logic L-4). Kein eigener Issue. |
| Sec2-Structure S-5 (Recovery-IO Main-Thread vs. IO) | section2-structure.md §S-5 | **Dedup mit 2.0.7 (`scope`-KDoc)** — der KDoc-Block legt fest, dass alle Effects in `serviceScope` laufen. Recovery-Spezifik fällt unter den allgemeinen Scope-Vertrag. Wenn 2.1.4 (Reentrancy-Vertrag) Async-Standard wählt, ist Recovery automatisch async. |
| Sec2-Structure S-8 (JobExecutor.initialize-Idempotenz) | section2-structure.md §S-8 | **Dedup mit 2.0.9 (Lösch-Tabelle)** + bei der Service-Cleanup-Sequenz (2.1.12) auf JobExecutor-Idempotenz hinweisen. Der konkrete Codefix (`if (!runner.isInitialized()) runner.initialize(...)`) ist eine triviale Mechanik, die im Spec-1 §7.3-Update mitfließt. |
| Sec2-Structure S-9 (Session-ID String vs. Long) | section2-structure.md §S-9 | **Reklassifiziert als eigenes 🟡** — dies ist ein **Critical**, der nicht durch andere Issues abgedeckt wird. *(Korrektur: aufgenommen als 2.1.X — siehe unten)* | 

(2.1.19 below)

| Sec2-Logic L-4 (Recovery deckt RECORDING/TRANSCRIBING nicht) | section2-logic.md §L-4 | **Reklassifiziert als eigenes 🟡** — ist nicht durch andere Issues abgedeckt. *(Korrektur: 2.1.20 unten)* |
| Sec2-Logic L-10 (Checkpoint-Hooks ohne Idempotenz) | section2-logic.md §L-10 | **Reklassifiziert als eigenes 🟡** — eigenständig. *(2.1.21 unten)* |
| Sec2-Logic L-11 (recoverFromDb nutzt nicht-Repo-Methoden) | section2-logic.md §L-11 | **Dedup mit Sec2-Logic L-4 (= 2.1.20)** — neue Methoden im Repo-Interface sind Teil des `recoverFromDb`-Refactors. |
| Sec2-Logic L-12 (POST_NOTIFICATIONS-Verweigerung) | section2-logic.md §L-12 | **Out-of-scope für State-/Module-Refactor.** Privacy-Use-Case ist legitim, aber nicht durch diesen Plan adressiert; als separater Plan/Issue tracken. |
| Sec3-Structure S-3 (D1-MotionLayout-Begründung im Spec) | section3-structure.md §S-3 | **Doku-Lücke, Nice-to-have** — wird beim EN-Translation-Pass / Doc-Format-Pass beim Plan-Archive automatisch aufgegriffen (UDOC verlangt eine Begründungs-Spur). Kein dedizierter Issue. |
| Sec3-Structure S-5 (DRY Slot-Skeleton) | section3-structure.md §S-5 | **Empfehlung "Konstanten-Extraktion" ist Code-Optimierung** — gehört zur Implementation-Phase (Block 5), nicht zum Plan. Kein Plan-Issue. |
| Sec3-Structure S-6 (`applySlotToView` MaterialButton-Type-Check) | section3-structure.md §S-6 | **Reviewer-Empfehlung "TODO-Kommentar setzen, sauber wenn Spec 3 (Overlay) zweiten Backend braucht"** — wird mit Spec-3-Implementation natürlich aufgegriffen. Kein Plan-Issue. |
| Sec3-Structure S-9 (`predResendVisible`-Helper schließt Pipeline ein → Overrides redundant) | section3-structure.md §S-9 | **Subtile Optimierung — eliminieren der `{ false }`-Overrides** würde aber die Inline-Doku 2.0.11/2.0.12 schwächen (der Override IST die Bug-Fix-Mechanik). Lasten ist die strukturelle Sicherheit höher als DRY-Gewinn. |
| Sec3-Structure S-10 (Touch-Handler-Lifecycle in `detach()`) | section3-structure.md §S-10 | **Dedup mit 2.1.11 (View-Recreate-Vertrag)** — wenn §8.x View-Recreate-Vertrag dokumentiert wird, fließt Touch-Handler-Lifecycle automatisch ein. |
| Sec3-Structure S-11 (LayoutMode/LayoutModule/LayoutAction Naming-Kollision) | section3-structure.md §S-11 | **Nice-to-have Naming-Vorschlag**, würde Diff-Volumen massiv erhöhen. Plan-Iteration sollte hier konservativ bleiben. |
| Sec3-Structure S-12 (`render()`-Idempotenz) | section3-structure.md §S-12 | **Reviewer-Empfehlung "Trade-off explizit dokumentieren, statt offen lassen"** — fällt mit 2.0.11/2.0.12 (Inline-Doku-Pass) zusammen. |
| Sec3-Logic L-4 (visibilityMode-silent-contract) | section3-logic.md §L-4 | **Dedup mit 2.1.14 (Visibility-Owner Option A)** — wenn alle 9 Buttons `visibilityMode="ignore"` bekommen, ist die Lint-Regel-Frage hinfällig. |
| Sec3-Logic L-5 (predTrashVisible == predPauseVisible) | section3-logic.md §L-5 | **Dedup mit 2.0.11 (Inline-Doku Send-Mode-Hardcoded)** — Inline-Doku erklärt bereits, dass die zentralen Predicates und die Catalog-Hardcoded-Werte unterschiedlich sind (= load-bearing Drift-Schutz). |
| Sec3-Logic L-6 (forKeyboard ignoriert singleRowMode in ReprocessStaging) | section3-logic.md §L-6 | **UX-Frage, Acceptance-Lücke** — sollte in §10 als Acceptance-Eintrag geklärt werden, fällt mit 2.0.9 (Lösch-Tabelle / Acceptance-Updates) zusammen. |
| Sec3-Logic L-8 (detach() löscht stateRef/modeRef nicht) | section3-logic.md §L-8 | **Dedup mit 2.1.11 (View-Recreate-Vertrag)** — Pattern für detach-State-Reset. |
| Sec3-Logic L-9 (`?: return@forEach` silent skip) | section3-logic.md §L-9 | **Trivialer Code-Fix** — zur Implementation-Phase. Plan-Beispiel sollte korrigiert werden, fällt aber unter Code-Quality der Snippets, nicht unter Plan-Issue. |
| Sec3-Logic L-10 (`prev::class == curr::class` schluckt Sub-Properties) | section3-logic.md §L-10 | **Trivialer Code-Fix** — `prev == curr` statt `prev::class == curr::class`. Einzeiler, gehört zur Implementation. |
| Sec3-Logic L-11 (EnterOverlayHandler defensive depth widerspricht SSOT) | section3-logic.md §L-11 | **Dedup mit 2.1.13 (KSM-Aufspaltung)** — wenn KSM aufgespalten wird, gehört Overlay-Reset zum OverlayBackend. EnterOverlayHandler-Mutation bleibt; KSM-Reset entfällt. |
| Sec3-Logic L-13 (currentSlot flatMap-Allocation) | section3-logic.md §L-13 | **Out-of-scope (PERFORMANCE)** — Reviewer markiert selbst als "marginal". |
| Sec3-Logic L-14 (§10 Acceptance-Lücke) | section3-logic.md §L-14 | **Dedup mit 2.0.11 + 2.0.12 (Inline-Doku-Pass) + 2.1.18 (firstRender-Flag)** — die fehlenden Acceptance-Tests werden bei jenen Issues miterstellt. |

---

## Korrektur-Issues (oben fälschlich in ❌ verschoben — gehören zu 🟡)

### Issue 2.1.19: Session-ID-Type-Mismatch (DAO=String vs. Action-Extras=Long)
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec2-Structure S-9 (→ `section2-structure.md` §S-9)
- **Description:** §6.1 spezifiziert `markInserted(id: String, ...)` (heutiges `SessionEntity.id` ist String). §7.5 dispatcht `confirmInsertion(it)` mit `it = intent.getLongExtra(EXTRA_SESSION_ID, -1L)` — Long. §11.4.2 zementiert String-IDs ('s1'/'s2') in der Migration-Test-Tabelle. §11.5 zeigt Long-Action-Extras. Block-2 würde nicht kompilieren.
- **Options:**
  A) **String durchgängig:** alle `getLongExtra` → `getStringExtra`, EXTRA_SESSION_ID-Payload-Type ist String. Begründung: "session-IDs sind String-UUIDs, client-side generiert".
  B) **Long durchgängig:** SessionEntity bekommt `numericId: Long` in M3→M4-Migration; `markInserted(numericId: Long)`.
- **Recommendation:** **Option A** — minimaler Footprint (heutige IDs sind bereits String); kein DB-Schema-Change. Architektur-Entscheidung wegen API-Type-Festlegung.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.20: Recovery deckt RECORDING/TRANSCRIBING-Stuck-Sessions nicht
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec2-Logic L-4 (→ `section2-logic.md` §L-4)
- **Description:** §11.6.2 reduziert Recovery auf `getByStatus("RECORDED")`. Sessions in `RECORDING` (Service starb mid-recording, audio-File teil-geschrieben) oder `TRANSCRIBING` (Service starb mid-Whisper-Call) fallen durch und bleiben für immer in der DB stuck. Doppel-DB-Read mit Race-Window. Recovery-vs-neuer-Recording-Race (Recovery überschreibt pendingSessions ohne Merge).
- **Options:**
  A) **Vollständige Recovery-Logik in §6.3 ausformulieren** mit allen Status-Branches:
     ```kotlin
     suspend fun recoverFromDb() = withContext(Dispatchers.IO) {
         val stuck = sessionDao.getSessionsByStatuses(listOf(
             RECORDING, TRANSCRIBING, RECORDED, TRANSCRIBED, COMPLETED))
         // ... partition + markFailed für RECORDING/TRANSCRIBING/dead-files
         // ... pending = COMPLETED-without-inserted_at + RECORDED-with-file
         store.update { it.copy(pendingSessions = it.pendingSessions + recovered) }  // MERGE!
     }
     ```
  B) Status quo + Acceptance-Test "process killed during RECORDING/TRANSCRIBING".
- **Recommendation:** **Option A** — saubere Recovery-Garantie + Acceptance-Test. Architektur-Entscheidung wegen DB-Status-Lifecycle.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 2.1.21: Checkpoint-Hooks Idempotenz / Atomarität / Failure-Strategie
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec2-Logic L-10 (→ `section2-logic.md` §L-10)
- **Description:** §6.2 listet 6 State→DB-Schreibungen ohne Idempotenz-, Reihenfolge- oder Failure-Vertrag. Konsequenzen: Replay nach view-recreate könnte INSERT zweimal fahren; Service-mid-Schreibung-Crash hinterlässt State/DB-Inkonsistenz; Cleanup-Race bei `deleteInsertedOlderThan`.
- **Options:**
  A) **§6.2 erweitern um Idempotenz + State-First-Reihenfolge + Failure-Action:**
     - `@Insert(onConflict = REPLACE)` oder explizit `INSERT OR IGNORE`
     - Reihenfolge-Vertrag: State-First, dann Effect → DB-Schreibung
     - DB-Failure → `Action.PersistenceError(sessionId, reason)` zurück → `pendingSessions`-Slot bekommt failed-Marker
     - `CoroutineExceptionHandler` im `serviceScope`
     - Cleanup-Cutoff mit Safety-Buffer (now - 7d - 1h)
- **Recommendation:** **Option A** — vollständiger Vertrag. Architektur-Entscheidung wegen neuer `PersistenceError`-Action + Exception-Handler-Pattern.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

## Notes for Apply-Step (Resume Context)

- **Apply-able 🟢-Issues (12):** 2.0.1 (Header-Zähler), 2.0.2 (Spec-2 Naming-Drift), 2.0.3 (Mode-3 Phase-2-Markierung), 2.0.4 (LocalBinder-Wrapper entfernen), 2.0.5 (PersistentList-Idiom), 2.0.6 (Exhaustivity-Konvention), 2.0.7 (`emitAction`/`scope`-KDoc), 2.0.8 (`Paused.Stop/Cancel`-Reducer-Arme), 2.0.9 (Lösch-Tabelle), 2.0.10 (Pref-Mirror-Bypass §6.3), 2.0.11 (Inline-Doku Send-Mode-Hardcoded), 2.0.12 (Inline-Doku `predResendVisible`-Cooldown-Trennung).
- **🟡-Issues bleiben unangetastet (21):** 2.1.1 bis 2.1.21. Architektur-Entscheidungen, gehen in den Research-Step + Final-Report. User-Entscheidung pro Issue.
- **Cluster-Hinweis für Final-Report:** 
  - 2.1.1 (`ReducerContext`-Surface) ↔ 2.1.5 (audioFile in State) ↔ Phase-1 1.1.7 — gemeinsam klären
  - 2.1.4 (Reentrancy-Vertrag) ↔ 2.1.3 (Effect-Failure) ↔ Phase-1 1.1.5 — gemeinsam klären
  - 2.1.6 (sealed-leaves-Indexing) ↔ 2.1.7 (NoOp + DispatchOutcome) ↔ Phase-1 1.1.4 — gemeinsam klären
  - 2.1.13 (KSM-Aufspaltung) ↔ 2.1.14 (Visibility-Owner D2) ↔ 2.1.15 (Manager↔LayoutModule-Beziehung) ↔ Phase-1 1.1.5 (LayoutModule-SRP) — gemeinsam klären
  - 2.1.20 (Recovery-Stuck-Sessions) ↔ 2.1.21 (Checkpoint-Hooks-Vertrag) — gemeinsam klären
- **Größtes nicht-erfasstes Resttreffer-Thema (aus Sec2-Structure S-1):** Die Spec-1 §7+§9+§11-Sektionen sind in Vor-F-11-Vokabular verfasst (PipelineStateManager statt DictateOrchestrator, typed Methods statt dispatch). Das ist mengenmäßig groß, aber strukturell identisch zu Phase-1 1.1.1 — wird im Final-Report gemeinsam mit 1.1.1 geklärt (kontext-sensitives Rename-Pass nach Phase-1-Empfehlung Option B).
