# Phase 2 / Batch 2 / Section 5 — Structure Review

**Section:** Cross-Cutting Konsistenz + alle §13-Verifikationen + Hauptplan + Acceptance/Tests
**Reviewer-Rolle:** Structure (DRY / SOLID / Architecture-Integration)
**Review-Target:**
- Hauptplan `dictate-keyboard-layout-refactor.md` (450 Zeilen)
- Spec 1 §13.1–§13.5 + §15 (`research/1-pipeline-service/1-pipeline-service.md`)
- Spec 2 §13.1–§13.5 + §10 + §14 (`research/2-keyboard-layout/2-keyboard-layout.md`)
- Spec 3 §13.1–§13.5 + §10 + §14 (`research/3-floating-overlay/3-floating-overlay.md`)
- Acceptance-Sektionen: Spec 1 §10, Spec 2 §10, Spec 3 §10
**Plan-Repo:** `/home/lukas/WebStorm/Docs`
**Code-Cross-Reference-Repo:** `/home/lukas/WebStorm/Dictate`
**Datum:** 2026-05-10
**Sister-Review:** Logic-Reviewer (parallel) deckt Logik-Lücken / Clean Code / Edge-Cases ab — diese Review bleibt strikt strukturell.

---

## Vorgehensweise

Section 5 ist die **Verifikations-Schleife** des Plans: §13-Audits behaupten "alles SSOT, SOLID/DRY erfüllt, alle Mutationen adressiert". Strukturperspektive prüft, ob die Behauptungen tragen, nachdem der Plan mehrere Iterationen (F-1 bis F-11) durchlaufen hat — und ob Hauptplan + Acceptance konsistent zur finalen Architektur sind.

1. **DRY** — Audit-Tabellen-Wiederholung, Action/State-Pfad-Doppelt-Definition, Acceptance-Kriterien-Drift
2. **SOLID** — §13.3-Audits gegen die finale Architektur (post-F-11) auf SRP/OCP/DIP-Stringenz prüfen, Spec 1 §15 als Orchestrator-Pattern-Audit
3. **Cross-Spec-Konsistenz / Architecture-Integration** — passen Spec 2 + Spec 3 strukturell zur finalen SoT in Spec 1 §3-§5 + §15? Hauptplan-Diagramm aktuell? Acceptance verifiziert die richtige Architektur?

Ich vermeide Doppel-Findings mit der Phase-1-Pattern-/Architecture-Scout-Liste — pro globalem Issue gibt's hier nur einen **Section-spezifischen Strukturwinkel**, soweit relevant für §13/Hauptplan/Acceptance. Logic-/Robustness-Findings (z.B. Race-Conditions, async Hardware-Callback-Timing) gehören zum Sister-Logic-Reviewer; ich berühre sie nur, wenn sie strukturelle Konsequenzen für §13 haben.

---

## Findings

### Issue S-1: §13.3-SOLID-Audit zitiert post-F-11 noch das Pre-F-11-Vokabular — Audit ist tonal aktualisiert, semantisch teil-veraltet

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 1 §13.3 (Z. 2065–2181), §13.3.1 / §13.3.2 / §13.3.7 / §13.3.8
- **Description:** §13.3 trägt zwei Iterations-Spuren übereinander. Z. 2067–2072 sagt explizit: "Diese Sektion wurde grundlegend überarbeitet … Modular-Orchestrator-Pattern (F-11) ist die zentrale Klasse jetzt der `DictateOrchestrator`". Direkt darunter §13.3.7/§13.3.8 (Z. 2122–2132) referenziert weiter `PipelineStateManager` als Action-Routing-Ziel:
  - Z. 2130–2132: "**SRP** — pure Mapping `Intent.action → PipelineStateManager-Methode`. … Tests injizieren Mock-Manager und prüfen Methoden-Aufrufe." — Aber post-F-8 (Single Dispatch) gibt es **keine typed Manager-Methoden** mehr; es gibt `dispatch(action: Action)` an einem `DictateOrchestrator`. Das Audit beschreibt das Pre-F-8-Verhalten und claimt dann SRP-Erfüllung.
  - §13.3.9 (Z. 2134–2136): "Siehe §13.3.2b für die aktualisierte F-8-Audit-Sektion" — Forward-Reference, aber §13.3.9 selbst ist leer und stört die Nummern-Sequenz (es gibt §13.3.9 zweimal: einmal als verweisende Stub, einmal in der Abfolge).
  - Eintrag G6 in §13.5 (Z. 2214) ruft `stateManager.cancelPipeline()` als Lösung auf — typed Methode, post-F-8 nicht existent, sollte `dispatch(Action.PipelineAction.CancelPipeline)` sein.

  Konsequenz für die Audit-Funktion: Wenn §13.3 die "interne Selbst-Verifikation" des Plans ist und ihre Beweise auf einer Klasse beruhen, die im finalen Plan einen anderen Namen + andere API hat, ist der Beweis nicht reproduzierbar. Ein Reviewer, der §13.3 gegen Spec 1 §4 (DictateOrchestrator) abgleicht, findet semantische Drift.
- **Affected sections:**
  - Spec 1 §13.3.7 (PipelineNotificationCoordinator) — DIP/SRP-Begründung referenziert StateFlow-Subscription, das ist ok
  - Spec 1 §13.3.8 (PipelineActionRouter) — die Beweis-Begründung "Mapping zu PipelineStateManager-Methoden" ist pre-F-8-Vokabular
  - Spec 1 §13.5 G6 — typed `cancelPipeline()`-Call
- **Suggestion:**
  - §13.3.8 umschreiben: PipelineActionRouter mappt `Intent.action → Action-Sealed-Class-Variante`, dispatched über den injizierten Orchestrator. Tests injizieren Mock-Orchestrator und prüfen, dass `dispatch` mit der korrekten `Action`-Variante gerufen wird.
  - §13.3.9 als Stub entweder löschen oder mit `LocalBinder`-Audit füllen (LocalBinder ist im aktuellen Plan §5 dokumentiert; §13.3.2b deckt es bereits ab — §13.3.9-Stub ist redundant).
  - §13.5 G6: `stateManager.cancelPipeline()` → `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)`. Konsistent mit Spec 2 §3.3 hierarchischer Sealed-Action.

---

### Issue S-2: §13-Audit-Tabellen referenzieren Sub-State-Pfade flach (`state.lastAudioExists`, `state.audioFocusEnabled`), obwohl Spec 1 §3 hierarchische Sub-State-Klassen vorschreibt (post-F-10)

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 1 §13.2.2 (Z. 2017), §13.4.1 (Z. 2194–2195); Spec 2 §8.5 + §13.x; Spec 3 §13.4
- **Description:** F-10 (Hauptplan §0 / Iter-Log Z. 392–404) hat `DictateUiState` von 30+ flachen Feldern auf hierarchische Sub-State-Klassen umgestellt:
  - `LayoutPrefs` { singleRowMode, smallMode, animationsEnabled }
  - `ResendState` { lastAudioExists, resendEnabled, resendCooldown }
  - `AudioState` { audioFocusEnabledPref, audioFocusGranted, bluetoothSco, useBluetoothMic, vibrationEnabled }
  - `OverlayState` { positionPortraitX/Y, positionLandscapeX/Y, userPrefersWidget, onboardingPending }
  - `LivePromptState`, `LanguageState`, `FeatureToggles`, `ThemingState`, …

  Die §13-Audits beschreiben das Refactor jedoch noch durchgängig mit flachen Pfaden:
  - Spec 1 §13.2.2 Z. 2017: "`Pref.LastFileName` … `DictateUiState.lastAudioExists` spiegelt File-Existence." → korrekt wäre `DictateUiState.resend.lastAudioExists`.
  - Spec 1 §13.4.1 Z. 2194–2195: "`state.collect { state -> mainButtonsController.refreshAudioFocusIcon(state.audioFocusEnabled) }`" → korrekt `state.audio.audioFocusEnabledPref`. Plus zweimal `state.lastAudioExists` (Z. 2195) — sollte `state.resend.lastAudioExists` sein.
  - Spec 2 §8.5 Z. 1125–1127 (`predResendVisible`): liest `state.lastAudioExists`, `state.resendEnabled`, `state.recording`, `state.pipeline`. Erste zwei sind flach — sollten `state.resend.lastAudioExists` / `state.resend.resendEnabled` sein.
  - Spec 2 §13.4.1 Z. 2041: Test-Snippet `predResendVisible(base.copy(lastAudioExists = false))` — kompiliert nicht gegen Spec-1-§3-Sub-State (`DictateUiState.copy(lastAudioExists = …)` existiert nicht).
  - Spec 3 §3.1 (OVERLAY_5BUTTON-Definition Z. 60–101): `state.recording`, `state.pipeline`, `state.viewMode` sind korrekt (Top-Level-Hot-Path). Aber Z. 366–368 (`applyPosition` in §4.2 / §4.7-Vorlauf): `state.overlayPositionPortraitX/Y` — sollte `state.overlay.positionPortraitX/Y`.
  - Spec 3 §5.3 (`state.overlayOnboardingPending`, Z. 755 / 820 / 821) — sollte `state.overlay.onboardingPending`.
  - Spec 3 §11.5 Z. 1408 / 1410: `state.overlayPosition{Portrait,Landscape}{X,Y}` — sollte `state.overlay.position…`.

  Strukturkonsequenz: Die zentrale **F-10-Korrektur (Hauptplan-Iter-Log) wurde nicht durch alle Audit- und Spec-Pfade nachgezogen.** Die §13.4-DRY-Tabellen behaupten "ein Predicate, eine Quelle" — aber das im Code-Snippet zitierte Predicate liest aus einem nicht-existenten flachen Feld. Wenn der Implementer §13.4 als Vorlage nimmt, schreibt er Code, der gegen das §3-Datenmodell nicht kompiliert.

  Pattern-Catalog zur Einordnung: Excel-EKL Module-Augmentation hat denselben Gain (lokal beim Modul deklarieren) — verlöre den Gain, wenn Konsumenten weiter flache Pfade lesen, die der Augmentor strukturierte. F-10 ist die Kotlin-Spiegelung dieses Gains.
- **Affected sections:**
  - Spec 1 §13.2.2 (Pref-Mirror-Tabelle), §13.4.1 (DRY-Tabelle), §11.2.2 (Block-1-Schritte-Snippets)
  - Spec 2 §8.5 (Predicate/Resolver-Definitionen, Z. 1112–1216), §13.4.1 (DRY-Vergleich), §14.2 (Test-Snippets)
  - Spec 3 §4.2 / §4.7 (`applyPosition`-Pfad), §5.3 (`overlayOnboardingPending`-Pfad), §11.5 (Persistierung), §13.x (alle Beweis-Tabellen, die State-Pfade zitieren)
- **Suggestion:** Cross-Spec-Suche-und-Ersetze (manuell/skript-gesteuert) post-F-10:
  - `state.lastAudioExists` → `state.resend.lastAudioExists`
  - `state.resendEnabled` (im Visibility-Kontext) → `state.resend.resendEnabled`
  - `state.audioFocusEnabled` → `state.audio.audioFocusEnabledPref`
  - `state.singleRowMode` / `state.smallMode` / `state.animationsEnabled` → `state.layout.*`
  - `state.overlayPosition*` / `state.overlayOnboardingPending` / `state.userPrefersWidget` → `state.overlay.*`
  - Verifikation: `LayoutCatalog.forKeyboard(state)`-Branches und `predResendVisible / predTrashVisible / predPauseVisible / resolveRecordButtonText*`-Funktionen müssen nach Substitution kompilieren.

  Zusatz: Eine **Top-Level-`it.<axis>`-Helper-Convention** für besonders häufige Pfade dokumentieren (z.B. `val DictateUiState.isResendEligible: Boolean get() = recording is RecordingState.Idle && pipeline is PipelineUiState.Idle && resend.lastAudioExists && resend.resendEnabled && !resend.resendCooldown`). Damit ist der Slot-Code lesbar **und** typsicher gegen Sub-State-Pfade. SSoT-konform: Property in §3 des Sub-States definieren, in §8.5 als Convenience referenzieren, keine Redefinition.

---

### Issue S-3: Spec 2 §3.3 + Spec 1 §15 deklarieren hierarchische Action-Sealed-Class — Spec 2 §6/§8 + ALLE Spec-3-Action-Refs nutzen flache Namen (post-F-8 nicht propagiert)

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 2 §3.3 (Z. 113–241) deklariert hierarchische Struktur mit `Action.RecordingAction.StartRecording`, `Action.PipelineAction.TriggerPipeline`, `Action.ViewModeAction.ToggleViewModeWidget`, `Action.OverlayAction.UpdateOverlayPosition`, `Action.ResendAction.ResendLastAudio`, …; aber:
  - Spec 2 §8.5 (Resolver-Helfer Z. 1171–1198): `Action.StartRecording`, `Action.StopRecordingAndSend`, `Action.PauseRecording`, `Action.ResumeRecording`, `Action.CancelRecording`, `Action.CancelReprocessStaging`, `Action.SendStaging` — flach.
  - Spec 2 §11.7 (Touch-Handler-Referenzen): `Action.Backspace`, `Action.EnterKey` — flach (sollte `Action.KeyboardInputAction.*`).
  - Spec 3 §3.1 (OVERLAY_5BUTTON-Slots Z. 67/77/86/95/100/101): `Action.StartRecording`, `Action.StopRecordingAndSend`, `Action.ResumeRecording`/`PauseRecording`, `Action.CancelRecording`, `Action.ToggleViewModeWidget`, `Action.CloseOverlay` — alle flach.
  - Spec 3 §4.2/§5.3/§7.3/§11.5 (mehrere Stellen): `Action.UpdateOverlayPosition`, `Action.MarkOverlayOnboardingShown`, `Action.DismissOverlayOnboarding` — flach.
- **Description:** F-8 hat `Action` als hierarchische Sealed-Class definiert (eine Klasse pro Modul-Achse) — Compile-Garantie für KClass-Routing (Spec 1 §4.2 / §15.2). Post-F-8 referenziert ein Reducer im RecordingModule den Action-Typ als `Action.RecordingAction.StartRecording`, denn nur so ist der Type-Parameter `Action.RecordingAction` der `actionClass: KClass<A>`-Eintrag eindeutig.

  Die Slot-Definitionen in Spec 2 §8.5 + Spec 3 §3.1 nutzen aber durchgängig die Pre-F-8-Form ohne Modul-Suffix. Konsequenzen:
  1. **Compiles-not.** `Action.StartRecording` ist nach §3.3 keine gültige Reference; die kanonische Form ist `Action.RecordingAction.StartRecording`.
  2. **Audit §13.3.x SOLID-Beweise verlieren ihre Basis.** §13.3.13 (Modul-Implementierungen) sagt "Neue Recording-Action = neue Variante in `Action.RecordingAction`" — gilt nur, wenn Slots die hierarchische Form benutzen. Wenn Slots die flache Form benutzen, gibt es keine compile-time-Verifikation, dass eine Action zum richtigen Modul geht.
  3. **DRY-Audit §13.4 verliert ein Argument.** §13.4 behauptet, "Action ist Single-Source-of-Truth" — die Slots sind stille Schatten-Definition, weil sie auf nicht-existente Action-Symbole zeigen.
  4. **Cross-Spec-Drift gegen Spec 1.** Spec 1 §15.2 RecordingModule.reduce nutzt `Action.RecordingAction.StartRecording`, `…MediaRecorderReady`, `…CancelRecording` — wenn Slot in Spec 3 `Action.StartRecording` invoked, kommt eine inkompatible Action am Reducer an.

  Phase 1 Pattern-Scout hat das als G1 + AI-2 markiert; hier strukturperspektivisch verstärkt: Section 5 ist die §13-Verifikations-Schleife, und sowohl §13.4 (DRY-Audit) als auch §13.3.13 (SOLID-Audit der Module) hängen daran, dass Action-Routing kompiliert.
- **Affected sections:**
  - Spec 2 §8.5 Z. 1112–1216 (Resolver-Helpers + die in Slots referenzierten Actions)
  - Spec 2 §11.7 (CursorSwipe / Backspace / EnterOverlay Action-Emissionen)
  - Spec 2 §13.2 Click-Listener-Audit-Tabelle Z. 1849–1877 (alle "Action.X" Einträge)
  - Spec 3 §3.1, §4.2, §5.3, §7.3, §11.5 (alle Action-Referenzen)
  - Spec 3 §13 Beweis-Sektionen, die Action-Snippets zitieren
- **Suggestion:** Action-Substitution post-F-8 wie folgt:
  - Recording: `Action.StartRecording / StopRecording / StopRecordingAndSend / PauseRecording / ResumeRecording / CancelRecording` → `Action.RecordingAction.<X>` (Spec 1 §15.2 RecordingAction.StartRecording braucht zudem Pflicht-Parameter `target: InsertionTarget` — siehe Phase-1-AI-2)
  - Pipeline: `Action.TriggerPipeline / StartPipeline / CancelPipeline / SendStaging / CancelReprocessStaging / ConfirmInsertion` → `Action.PipelineAction.<X>`
  - Resend: `Action.ResendLastAudio / ResendLastAudioLong` → `Action.ResendAction.<X>`
  - ViewMode: `Action.ToggleViewModeWidget / CloseOverlay` → `Action.ViewModeAction.<X>`
  - Overlay: `Action.UpdateOverlayPosition / MarkOverlayOnboardingShown / DismissOverlayOnboarding` → `Action.OverlayAction.<X>`
  - Audio: `Action.ToggleAudioFocus` → `Action.AudioAction.ToggleAudioFocusPref`
  - Layout: `Action.ToggleSingleRowMode / ToggleSmallMode` → `Action.LayoutAction.<X>`
  - Tastatur-Input: `Action.Backspace / EnterKey` → `Action.KeyboardInputAction.<X>`

  Nach Substitution: §13.4 (DRY) behält ihr "Single-Source-of-Truth"-Argument, weil Action-Symbole jetzt strukturell zum Modul gehören (compile-time verifizierbar). §13.3.13 SOLID-Beweis ist haltbar.

---

### Issue S-4: Spec 3 §13.1 SSOT-Behauptung wird durch §5/§6/§7 Code-Skizzen widerlegt — direkte `_state.value.copy(...)`-Mutationen umgehen Reducer + dispatch

- **Category:** [SOLID] / [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 3 §13.1 (Z. 1540–1563) vs. §5.3 (Z. 846, 854, 862, 867), §6 (Z. 916, 923, 945), §7 (Z. 968, 1025, 1029)
- **Description:** §13.1 behauptet als zentrale SSOT-Eigenschaft (Z. 1554–1563):
  > "Wer mutiert `viewMode`? Ausschließlich der `PipelineStateManager`. … Konsequenz: keine Mutation auf irgendeinem Pfad **am StateManager vorbei**."

  Aber die Code-Skizzen in §5–§7 enthalten 11+ Stellen mit `_state.value = _state.value.copy(...)`-Mutationen. Beispiele:
  - §5.3 Z. 846: `_state.value = _state.value.copy(overlayOnboardingPending = true)` (Onboarding-Trigger)
  - §6.1 Z. 916/923: `_state.value = _state.value.copy(...)` (closeOverlay-Logik mit smallMode-Aktivierung)
  - §7.1 Z. 968: `_state.value = _state.value.copy(viewMode = newViewMode)` (Triangle-FSM-Apply)
  - §7.3 T3 (HOVER-Trigger) Z. 1025/1029: `_state.value = _state.value.copy(overlayOnboardingPending = …)`

  Strukturkonsequenz:
  1. **Bricht F-8 (Single Dispatch).** Spec 1 §5 sagt: LocalBinder schrumpft auf `state` + `dispatch(action)` + 2 Lifecycle-Hooks; ALLE Mutationen laufen durch dispatch. Ein `_state.value = …` umgeht das.
  2. **Bricht F-11 (Modular Reducer).** Module haben einen reinen `reduce(state, action, ctx) → TransitionResult` plus optionalen `runEffect`. Ein direktes `_state.value =` ist ein Effect, der KEINE Action emittiert — der Reducer-Vertrag ist nicht-deterministisch testbar.
  3. **Bricht §13.1 SSOT-Beweis.** Der Beweis-Zitat-Anker (Z. 1546–1552) lautet "render(state, mode) liest `state` (read-only)" — aber daneben (in §5–§7) finden direkte Mutationen statt, die NICHT als Action laufen. Der Audit ist nicht synchron mit den Code-Skizzen.
  4. **Bricht Plan-Behauptung Hauptplan §3.3 / Spec 1 §13.4.** "Keine Pref-Writes am Manager vorbei", "Mutationen sind in atomic dispatch-Schritte gegliedert" — wenn `closeOverlay()` (§6.2 in Spec 3) die Pref-Synchronisation und die State-Mutation in zwei verschiedenen Reducer-Aufrufen oder gar direkt am Store ausführt, ist das Pre-F-8-Architektur.

  §13.1-Audit-Tabelle (Z. 1546–1552) zitiert "Click-Listener invokt onAction(slot.actionResolver(state))" als Beweis — das ist korrekt für die Slot-Click-Pfade. Aber §5.3 / §6 / §7 sind die **State-Manager-internen Methoden** (Triangle-FSM in `closeOverlay` / `toggleViewMode` / `notifyImeViewVisibilityChanged`); diese sind nicht Slot-getriggert, sondern Service-/IME-Callback-getriggert. Genau diese müssen post-F-8 ebenfalls über `dispatch(Action.ViewModeAction.X)` laufen.
- **Affected sections:**
  - Spec 3 §5.3 (Onboarding-State-Mutation), §6.1 (closeOverlay aus WIDGET), §6.2 (closeOverlay aus HOVER), §7.1 (Triangle-FSM-Apply), §7.3 T1–T6 (alle 6 Übergänge)
  - Spec 3 §13.1-Beweis-Tabelle: ist nur richtig für die Click-Pfade, NICHT für die Service-internen State-Manager-Methoden
- **Suggestion:**
  - Alle `_state.value = _state.value.copy(...)`-Stellen in Spec 3 §5/§6/§7 in `orchestrator.dispatch(Action.<modul>Action.<X>)` umschreiben. Der Reducer im jeweiligen Modul (ViewModeModule / OverlayModule / LayoutModule) bewegt den State.
  - Cross-Module-Cascade-Pattern (Spec 1 §15.5 Modus 2 "Action-Cascade") nutzen: z.B. WIDGET-Schließen löst `Action.ViewModeAction.CloseOverlay` aus, der ViewModeModule-Reducer setzt `viewMode = KEYBOARD`, in `onCrossModuleStateChange` emittiert das ViewModeModule (oder LayoutModule) `Action.LayoutAction.SetSmallMode(true)` als Cascade-Action.
  - §13.1-Beweis-Tabelle erweitern um die Service-internen Pfade. Entweder mit echtem `dispatch`-Pfad-Beweis ("closeOverlay ruft dispatch(Action.ViewModeAction.CloseOverlay), dessen Reducer …") oder explizit als _Open Question_ markieren, falls nicht entschieden.
  - §15.1-Tabelle (Spec 1) listet ViewModeModule mit "F4-Subset (ehemals ViewModeFsm)" — hier präzisieren, dass alle 6 Triangle-FSM-Übergänge im ViewModeModule.reduce als `when (action)` exhaustiv abgedeckt sind. Damit ist der §13.1-SSOT-Anker compile-time-erzwungen.

---

### Issue S-5: Hauptplan §3.2 (Service-Schicht-Diagramm) + §3.3 (LayoutDescriptor-Pattern) zeigen Pre-F-8/Pre-F-11-Vokabular — Top-Level-Architektur-Bild ist nicht synchron zur finalen Architektur

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Hauptplan §3.2 (Z. 121–158), §3.3 (Z. 160–193)
- **Description:** §3.2 ist das ASCII-Diagramm der Service-Schicht und nennt explizit "**PipelineStateManager** (SSOT für ALLE State-Achsen)" (Z. 131) — das ist der F-1-Stand. Post-F-11 (Z. 406–443 Iter-Log) heißt die zentrale Klasse `DictateOrchestrator`, der `PipelineStateManager` existiert nicht mehr als Klasse (er ist umbenannt worden, sein State-Halten ist in `DictateUiStateStore` ausgelagert, sein FSM-Verhalten in `ViewModeModule`, etc.).

  §3.3 (Z. 160–193) zeigt das LayoutCatalog-Skelett:
  - Z. 165: `enum class LogicalButtonId { … }` listet `OVERLAY_INDICATOR, OVERLAY_CLOSE, …` — `OVERLAY_INDICATOR` ist nicht in Spec 2 §3.1 aufgelistet (dort: `OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE` — also 5 statt OVERLAY_INDICATOR).
  - Z. 189: `val OVERLAY_4BUTTON = LayoutMode(...)  // gemeinsam für WIDGET + HOVER` — aber der Hauptplan-Iteration-Log Z. 277 sagt explizit: "Anstelle von OVERLAY_4BUTTON heißt der LayoutMode jetzt **OVERLAY_5BUTTON**". Spec 2 §3.2 Z. 109 hat `OVERLAY_5BUTTON`, Spec 3 §3.1 ebenfalls. Hauptplan-§3.3 ist nicht angepasst.

  Konsequenz: Wer ins Hauptplan-Bild schaut, baut auf falschen Symbolen auf. Der §3.2-Diagramm-Block ist die erste Stelle, an der ein Implementer "die Architektur" begreift — das Bild zeigt eine Architektur-Generation zurück.

  Bezug zu Issue S-1: §13.3 ist tonal post-F-11, semantisch teilweise pre-F-11. §3.2/§3.3 ist tonal pre-F-11 (die Diagramme erwähnen die F-Iterationen nicht, sind also nie aktualisiert worden). Das Iter-Log behauptet richtig, dass die Iterationen in den Specs propagiert wurden — aber das **eigene Top-Level-Bild des Hauptplans** ist nicht propagiert.
- **Affected sections:**
  - Hauptplan §3.2 (Service-Schicht-Diagramm, "PipelineStateManager"-Box)
  - Hauptplan §3.3 (LayoutDescriptor-Pattern-Code-Skelett, OVERLAY_4BUTTON, OVERLAY_INDICATOR)
- **Suggestion:**
  - §3.2-Diagramm: "PipelineStateManager"-Box umbenennen in "DictateOrchestrator (Composition Root, Modular)". Sub-Boxen darunter: "DictateUiStateStore (StateFlow-Container)", "ModuleRegistry (13 Module, Spec 1 §15)", "PipelinePrefMirror", "PipelineRecovery". Damit ist §3.2 mit Spec 1 §4.1 strukturell deckungsgleich.
  - §3.3-Code-Skelett: `OVERLAY_INDICATOR` entfernen (nicht in §3.1 von Spec 2 vorgesehen), `OVERLAY_4BUTTON` → `OVERLAY_5BUTTON`. LogicalButtonId-Liste mit Spec 2 §3.1 abgleichen (Z. 50–68). Optional: Pointer-Marker "Spec 2 §3.1 ist die kanonische Quelle, dieser Auszug ist eine Übersicht" als SSoT-Schutz hinzufügen.
  - Iter-Log-Eintrag F-11 (Z. 430–432) bereits sagt "**`PipelineStateManager` → `DictateOrchestrator`** (umbenannt, schlanker)" — der Hauptplan §3.2 sollte die Konsequenz daraus selbst tragen.

---

### Issue S-6: §13.5 "Identified Gaps" referenziert RESOLVED-Status für F-Iter-Anker, aber das Gap-Tabellen-Format vermischt offene Gaps + post-mortem-Resolutionen

- **Category:** [DRY] (Audit-Tabellen-Schema)
- **Severity:** Important
- **Location:** Spec 1 §13.5 (Z. 2205–2215), Spec 2 §13.5 (Z. 1979–2011), Spec 3 §13.5 (Z. 1754–1765)
- **Description:** §13.5 in allen drei Specs ist das "Open Issues / Mitigations"-Register. Es enthält drei Kategorien Einträge gemischt:
  1. Aktuelle, **offen verbleibende** Gaps (z.B. Spec 1 G6 MediaRecorder-Leak, Spec 3 GAP-5 HOVER-Schließen-Edge-Case, GAP-6 Permission-Revoke).
  2. Post-mortem **RESOLVED-Anker**, die historisch dokumentieren, dass ein F-Iter den Gap geschlossen hat (Spec 2 Gap 1 "RESOLVED via F-4", Spec 3 GAP-1 "RESOLVED via F-7", GAP-7 "RESOLVED via F-6").
  3. **Cross-Spec-Drift-Hinweise** als "wird in Spec X ergänzt, sobald implementiert" (Spec 3 GAP-2/GAP-3/GAP-4 — listet, dass `Action.MarkOverlayOnboardingShown`, `state.overlayOnboardingPending`, `LogicalButtonId.WIDGET_TOGGLE` in Spec 1/Spec 2 ergänzt werden müssen).

  Strukturproblem:
  1. Kategorie 3 sollte **kein Eintrag in §13.5 sein** — entweder ist der Cross-Spec-Patch eingearbeitet (dann gehört die Notiz ins Iter-Log) oder noch offen (dann gehört sie in die jeweilige Spec selbst, nicht in eine §13.5 der drittten Spec). Der Plan ist im Skeleton-Status, aber mehrere F-Iter haben behauptet, "in den Specs eingearbeitet" zu haben (Hauptplan-Z. 285–290). GAP-2/3/4 in Spec 3 widersprechen dem.
  2. Kategorie 2 (RESOLVED) bläht §13.5 als _aktuelle Gap-Liste_ auf — "Gap" und "ehemals Gap, gefixt" sollten visuell trennbar sein, sonst muss jeder Reviewer pro Zeile parsen, ob der Eintrag aktiv ist.
  3. Spec 1 §13.5 G3/G4/G5 (Z. 2211–2213) sind eigentlich erledigt durch F-1 (Composition Root) bzw. durch F-10 (Pref-Mirror als zentraler State-Achse-Spiegel) — aber sie stehen als "Niedrig" markiert in der Tabelle, ohne RESOLVED-Annotation. Das ist die umgekehrte Drift: Gaps, die nicht-mehr-Gap sind, hängen weiter im "offen"-Bereich.

  Konsequenz für die Audit-Funktion: §13.5 ist nicht mehr ein zuverlässiger "Gap-Inbox", sondern ein gemischter Log. Ein Reviewer muss alles per Hand abgleichen, was in den Specs steht und wo das gilt.
- **Affected sections:**
  - Spec 1 §13.5 (alle 7 Einträge G1–G7)
  - Spec 2 §13.5 (Gap 1–5, davon 1 mit RESOLVED-Annotation, 4 ohne)
  - Spec 3 §13.5 (GAP-1 bis GAP-8)
- **Suggestion:** §13.5 in **drei Bereiche** trennen, in jeder Spec gleich strukturiert:
  - **§13.5.a Open Gaps (aktuelle offene Punkte)** — nur Kategorie 1
  - **§13.5.b Cross-Spec Patches Pending** — nur Kategorie 3, mit klarem "→ in Spec X §Y eintragen, Verantwortlicher: Block-Z"
  - **§13.5.c Resolved (Iter-History)** — Kategorie 2, mit Status-Ankern und F-Iter-Pointer

  Damit ist auf einen Blick klar, was offen ist (§13.5.a) vs. was Cross-Spec-Schuld ist (§13.5.b) vs. was bereits gefixt ist (§13.5.c). Spec 1 G3/G4/G5 wandern nach §13.5.c (RESOLVED via F-1/F-10). Spec 3 GAP-2/GAP-3/GAP-4 wandern nach §13.5.b — und müssen tatsächlich in Spec 1/Spec 2 eingearbeitet werden, sonst ist der "F-11-Iter alle Cross-Spec-Patches eingearbeitet"-Anker (Hauptplan-Iter-Log Z. 285–290) falsch.

---

### Issue S-7: Hauptplan §6 Block-Plan unterschätzt Block 1 strukturell — Block-Größe spiegelt nicht die durch F-10/F-11 entstandenen 13 Module + Sub-State-Klassen wider

- **Category:** [SOLID] (Block-Komposition / SRP) / [INTEGRATION]
- **Severity:** Important
- **Location:** Hauptplan §4 (Z. 197–208 — Building-Blocks-Tabelle), Spec 1 §11.2.2 (Z. 1555–1599 — Block-1-Schritte)
- **Description:** §4 listet Block 1 als "klein-mittel" (Z. 201) — **State-SSOT-Konsolidierung**: "`resend_btn`-Visibility zentralisieren; `recordButton.text/isEnabled`-Hybrid auflösen; Quick-Win-Fixes (KSM.refresh in Toggle-Callbacks)". Diese Beschreibung stammt aus der Pre-F-11-Iter (vor F-10 / F-11) und reflektiert den damaligen Plan: ein flacher `DictateUiState` + ein `PipelineStateManager`, der etwas State zentralisiert.

  Post-F-10/F-11 ist Block 1 strukturell ein anderes Tier:
  - 13 Modul-Files (`state/modules/RecordingModule.kt`, `PipelineModule.kt`, `AudioModule.kt`, `ViewModeModule.kt`, …) zu erstellen — auch wenn 12 davon trivialen Reducer + Pref-Sync haben, ist das _13 neue Files_, davon `RecordingModule` ein 130-Zeilen-File mit Hardware-Effect-Mapping (Spec 1 §15.2).
  - 8 Sub-State-Klassen mit Pref-Mirror (`LayoutPrefs`, `OverlayState`, `AudioState`, `ResendState`, `LivePromptState`, `LanguageState`, `FeatureToggles`, `ThemingState`) — jede mit eigenem `prefMirror` / Initial-Default / Migrations-Pfad aus heutigen verstreuten Pref-Reads.
  - `DictateOrchestrator` Composition Root + `DictateUiStateStore` + `DictateModuleRegistry` + `ModuleServices` + `ModuleServicesFactory` (alle Spec 1 §4) — 5 neue Klassen-Dateien.
  - 4 Hilfsklassen in Spec 1 §4.5–§4.6 (`PipelinePrefMirror`, `PipelineRecovery`, …).
  - PLUS die im Block-1-Beschrieb adressierten Mutations-Konsolidierungen (resend_btn, recordButton-Hybrid, KSM.refresh).

  Phase 1 hat das als AI-6 / 1.1.6 markiert (Block-1-Aufwand massiv unterschätzt). Strukturperspektive ergänzt: **Spec 1 §11.2.2 listet Block-1 als 7 sequenzielle Schritte**, die nicht zwischen "Skelett anlegen" und "alte Klassen ablösen" unterscheiden. Logik-Reviewer (Sec2-Logic L-6 Z. 137–150) hat zudem aufgezeigt, dass die Schritte nicht kompilier-grün sind — strukturell heißt das: Block 1 ist ein einziger riesiger atomarer Refactor-Block ohne saubere Sub-Block-Grenzen.

  Konsequenz für das §10-Acceptance-Audit: Block-1-Acceptance (Spec 1 §10 Z. 1353–1358) listet 5 Kriterien, alle auf der Verhaltens-Ebene (resend-Visibility, recordButton-Resolver, KSM.refresh-Trigger, UC-Tests). Es gibt kein Acceptance-Kriterium "13 Module sind angelegt", "DictateOrchestrator routet alle 25+ Action-Varianten", "Pref-Mirror spiegelt alle 9 Sub-State-Pref-Achsen". Damit kann Block 1 als "done" markiert werden, ohne dass die F-10/F-11-Strukturarbeit fertig ist.
- **Affected sections:**
  - Hauptplan §4 Block-Tabelle (Z. 199–207, Komplexitätsspalte für Block 1)
  - Hauptplan §4 Reihenfolge-Sektion (Z. 208 "1 → 2 → 3 → 4 → 5 → 6", Block-1-Pflicht-vor-Block-2-Argument)
  - Spec 1 §10 (Block-1-Acceptance)
  - Spec 1 §11.2.2 (Block-1-Schritte-Liste)
- **Suggestion:**
  - Hauptplan §4: Block 1 in Block 1a/1b/1c splitten (Sec2-Logic L-6 schlägt 1a-e vor):
    - **1a — Skelett:** Sub-State-Klassen + DictateUiState-Top-Level + DictateOrchestrator-Stub + DictateModuleRegistry. Apps läuft unverändert (Manager wird konstruiert, aber nicht genutzt).
    - **1b — Recording-Achse + RecordingModule:** Hardware-Adapter (`RecordingHardwareSubsystem` als Wrapper um `RecordingManager`), RecordingModule.reduce + runEffect, Migration RecordingStateController → RecordingModule + Adapter-Stub.
    - **1c — Pipeline-Achse + PipelineModule:** Analog für KeyboardUiController-State-Anteil. PipelineModule + LivePromptModule + ResendModule.
    - **1d — Layout/View/Audio/Overlay-Module:** restliche 9 Module (trivial-Reducer + Pref-Mirror), inkl. Pref-Mirror-Wiring.
    - **1e — Mutations-Konsolidierungen:** resend_btn / recordButton-Hybrid / KSM.refresh — die ursprüngliche Block-1-Beschreibung.
  - Hauptplan §4 Komplexitätsspalte: "klein-mittel" → "groß" (1a–1e). Hauptplan §6 / §7 Tabellen entsprechend auf Block 1a–1e granular machen.
  - Spec 1 §10 Block-1-Acceptance pro 1a–1e Sub-Acceptance ergänzen: 13-Module-Inventar-Check, Pref-Mirror-Durchschlag-Check, Action-KClass-Routing-Test (alle Action-Varianten enden im richtigen Reducer).
  - Spec 1 §11.2.2 in 1a–1e umstrukturieren — pro Sub-Block ein eigener Schritt-Set, mit "kompilier-grün vor nächstem Sub-Block"-Vertrag.

---

### Issue S-8: Acceptance-Kriterien §10 (Specs 1+2+3) decken die User-Bug-Fixes nicht End-to-End ab — die zwei dokumentierten Bug-Klassen sind in keiner Acceptance-Liste pro-Bug verifiziert

- **Category:** [INTEGRATION] (Acceptance vs. Architektur-Ziele)
- **Severity:** Important
- **Location:** Hauptplan §1.1 (Z. 13–22, drei Bug-Symptome), §2.3 (Z. 64–70, Bug-Eliminations-Ziele); Spec 1 §10, Spec 2 §10, Spec 3 §10
- **Description:** Hauptplan §1.1 listet die Auslöser-Bugs:
  1. Asymmetrisches Re-Parenting beim Single-Row-Toggle (trash_btn/pause_btn vergessen)
  2. Asymmetrisches Re-Parenting beim Revert (record_pulse_layout/backspace_btn/resend_btn alle in input_row gestopft)
  3. **Send-Modus + Single-Row: Send-Button verdeckt, resend-Button verschwindet beim Toggle**

  §2.3 listet Bug-Eliminations-Ziele:
  - "Eliminierung der Bug-Klasse 'asymmetrisches Re-Parenting' durch strukturelle Maßnahme (MotionLayout statt Re-Parent)" — adressiert (1) + (2)
  - "Eliminierung der `resend_btn`-Race (5 Mutatoren → 1 Predicate)" — adressiert Teil von (3)
  - "Send-Button im Send-Modus + Single-Row korrekt sichtbar, nicht verdeckt" — adressiert anderer Teil von (3)

  Acceptance-Inventur:
  - **Spec 2 §10** (Block 5 Acceptance Z. 1389–1396): erfasst "Send-Mode + Single-Row: Send-Button vollständig sichtbar, kein Verdecken (Bug-Eliminierung)" ✓ — aber **NICHT** "resend-Button verschwindet beim Toggle" (Phase 1 logic-reviewer Sec3-Logic L-1, L-14 hat das markiert).
  - **Spec 1 §10** (Block 1 Acceptance Z. 1353–1358): "resend_btn-Visibility wird nur an EINER Stelle berechnet" ✓ — aber das ist eine Code-Eigenschaft, kein Verhaltens-Verifikator. End-to-End: "User toggelt Single-Row während Idle+lastAudio mit Resend-Btn an, Resend-Btn bleibt sichtbar"-Test fehlt.
  - **Spec 3 §10** (Block 6 Acceptance Z. 1183–1210): adressiert WIDGET / HOVER / Permission, aber nicht den ursprünglichen Bug-Set (der ist in KEYBOARD-Modus passiert).

  Strukturproblem: §13.x in den Specs **behauptet** "User-Bug-Fixes adressiert" (Spec 2 §13.1 Z. 1846: "Die 5+ problematischen `resend_btn`-Mutationen sind alle ENTFERNT zugunsten **eines** Predicates"). Aber Acceptance hat keinen Test, der den Bug-Pfad reproduziert und verifiziert, dass er tot ist. **Strukturelle Garantie ohne Verhaltens-Verifikation ist Drift-anfällig** — eine spätere Iteration kann die Predicate-Logik ändern und die Strukturgarantie behalten, während der Bug zurückkommt (z.B. eine Cooldown-Achse, die mit Visibility verschnitten wird).

  Sec3-Logic L-1, L-14 haben das aus Logik-Sicht aufgezeigt; Strukturperspektive ergänzt: §10 sollte pro Bug-Symptom mindestens **ein Acceptance-Kriterium** haben, das auf das Bug-Symptom direkt verweist (z.B. "Bug §1.1 #3 'Send-Button verdeckt': verifiziert in Spec 2 §10 #3 + Test §14.2 UI-Test 4"). Bidirectional: §1.1 verlinkt auf §10/§14, §10/§14 verlinken zurück auf §1.1. Damit kann ein Reviewer in einem Schritt feststellen, ob alle 3 Bug-Symptome durch _benannte_ Tests gedeckt sind.
- **Affected sections:**
  - Hauptplan §1.1 (Bug-Symptom-Liste)
  - Hauptplan §2.3 (Bug-Eliminations-Ziele)
  - Spec 1 §10, Spec 2 §10, Spec 3 §10
  - Spec 2 §14 (Test-Strategie)
- **Suggestion:**
  - Bug-Symptom-Tabelle im Hauptplan §1.1 erweitern um Spalte "Acceptance-Verifikator" mit Pointer auf Spec/§/Test-ID. Z.B.:
    | # | Symptom | Acceptance | Test-ID |
    |---|---|---|---|
    | 1 | Re-Parenting Single-Row-Toggle | Spec 2 §10 #2,5; §14.2 UI-Test 1,2 | T-2 |
    | 2 | Re-Parenting Revert | strukturell durch MotionLayout (Spec 2 §10 #1) | T-1 |
    | 3a | Send-Btn verdeckt | Spec 2 §10 #3; §14.2 UI-Test 4 | T-4 |
    | 3b | Resend-Btn verschwindet beim Toggle | **fehlt** — neu hinzufügen | **fehlt** |
  - Acceptance-Eintrag in Spec 2 §10 für 3b ergänzen: "Resend-Btn ist während Single-Row-Toggle in Idle+lastAudio sichtbar (visibility=VISIBLE) durchgängig — verifiziert via Frame-Capture / Espresso während aktivem Toggle." (übernommen aus Sec3-Logic L-14 Vorschlag)
  - Spec 2 §14.2 zusätzliche UI-Tests:
    - "**UI-Test 8:** Toggle Single-Row während Idle+lastAudio: Resend-Btn bleibt sichtbar (kein Frame ohne)."
    - "**UI-Test 9:** Active → Pipeline-Preparing-Übergang: kein Frame zeigt trash/pause über record_btn (Bug §1.1 #3)."
  - §14.2-Tabelle in §10-Tabelle als Reverse-Pointer ("decken Bug-Symptom #X") — bidirectional.

---

### Issue S-9: §13.4 DRY-Audit-Schema "Heutige Duplikation → Künftige Ein-Stellen-Quelle" verzichtet auf "Cross-Spec-Quelle" — Resolver/Predicate-DRY-Verifikation in Spec 2 ignoriert Spec 3

- **Category:** [DRY]
- **Severity:** Important
- **Location:** Spec 2 §13.4 (Z. 1916–1977), Spec 3 §13.3 (Z. 1617–1722)
- **Description:** Spec 2 §13.4 vergleicht heutige Duplikation gegen die "Künftige Ein-Stellen-Quelle" (z.B. `predResendVisible` als zentrale Quelle für die 6 heutigen Mutations-Sites). Spec 3 §13.3 macht analog Cross-Backend-DRY-Argument für `applySlotToView` (F-7). Beide §13.4/§13.3-Audits verifizieren DRY pro Spec **isoliert**.

  Was nicht audited ist:
  1. **Cross-Spec-Resolver-DRY:** Spec 3 §3.1 OVERLAY_5BUTTON.RECORD-Slot definiert `actionResolver = { Action.StartRecording }`. Spec 2 §3.x KEYBOARD_TWO_ROW.RECORD-Slot würde via `resolveRecordAction(state)` (Spec 2 §8.5 Z. 1171) bei Idle ebenfalls `Action.StartRecording` returnen. Wenn die Conventionen inkompatibel sind (Spec 1 §15.2 verlangt `Action.RecordingAction.StartRecording(target: InsertionTarget)` mit Pflicht-Parameter — Phase 1 AI-2), ist der Cross-Spec-Resolver-Ansatz divergiert. §13.4 / §13.3 hat keinen Eintrag dafür.
  2. **Cross-Spec-Predicate-DRY:** `state.recording.isActiveOrPaused` (extension, Spec 1 §13.4.1 Z. 2189) wird in Spec 2 §8.5 + Spec 3 §3.1 verwendet. Es ist nicht klar, ob Spec 1 die kanonische Definition hat oder ob jede Spec eine eigene Version pflegt — wenn beide eine eigene haben, ist das stille Cross-Spec-Duplikation, die §13.4 nicht erfasst.
  3. **`OverlayPositionMapper.normalizedToPixels` / `pixelsToNormalized` (Spec 3 §13.3 Z. 1687–1698):** Die Konversion lebt in `OverlayPositionMapper`. Spec 1 §6.4 (SharedPreferences-Erweiterung — Overlay-Position) hat allerdings einen eigenen Pfad für die Persistierung, der ggf. eigene Normierung implementiert. Das §13-Audit prüft nicht, ob beide Pfade auf dieselbe Math hängen.

  Strukturperspektive: Plan ist modular, das ist gut — aber §13.4-DRY-Audit ist nicht modular-aware. Das Audit-Format sollte explizit zwischen "intra-Spec-DRY" (innerhalb dieser Spec) und "cross-Spec-DRY" (gegen die anderen Specs) unterscheiden.
- **Affected sections:**
  - Spec 2 §13.4 (alle Subsektionen)
  - Spec 3 §13.3 (alle Subsektionen)
  - Spec 1 §13.4.1 (insbesondere `state.recording.isActiveOrPaused`-Extension-Site)
- **Suggestion:**
  - Pro §13.4 (Spec 2) und §13.3 (Spec 3) eine neue Sub-Sektion "Cross-Spec-DRY":
    - Tabellen-Spalten "Symbol", "Definitions-Site (Spec/§/Z)", "Konsumenten (Spec/§/Z)"
    - Beispiel-Eintrag: `state.recording.isActiveOrPaused` | Spec 1 §3 (Sub-State extension) | Spec 2 §8.5 predTrashVisible/predPauseVisible Z. 1132/1136; Spec 3 §3.1 OVERLAY_5BUTTON Z. 70/80/93 |
  - Zentrale "Resolver/Predicate-Library" in Spec 1 §3 oder in einem neuen `state/Predicates.kt` (Top-Level-Funktionen) konsolidieren. Beide Backends + alle Module referenzieren von dort.
  - Spec 3 §13.3 Sub-Sektion "Position-Konversion" um Pointer auf Spec 1 §6.4 / SharedPreferences-Pfad erweitern und beide Pfade auf eine einzige Mapper-Klasse (`OverlayPositionMapper` aus Spec 3 §4.7) zurückführen — nicht zwei parallele Math-Pfade.

---

### Issue S-10: §15 (Spec 1) Modul-Inventar enthält keine Dependency-Matrix — Cross-Module-Cascade-Begrenzung ist nicht strukturell ausgewiesen

- **Category:** [SOLID] (DIP / Cross-Module-Coupling)
- **Severity:** Nice-to-have (verstärkt sich in Block 1d-Implementierung)
- **Location:** Spec 1 §15.1 (Modul-Übersicht Z. 2238–2254), §15.5 (Cross-Module-Effect-Modi)
- **Description:** §15.1 listet pro Modul "Cross-Module-Observer? ja/nein" und nennt Beispiele (z.B. AudioModule reagiert auf Recording-Active+Preparing → AudioFocus; LivePromptModule reagiert auf Pipeline-Done → ChainNext). Der §13.3.13-SOLID-Audit (Z. 2162–2168) und §15.6-SOLID-Audit (Z. 2482–2490) **behaupten** SRP/OCP-Erfüllung pro Modul — aber zeigen nicht systematisch, **welches Modul welche Sub-State-Pfade anderer Module liest** (Cross-Module-Read-Coupling) und **welche Action-Klassen welches Modul anderer Module emittieren darf** (Cross-Module-Cascade-Coupling).

  Konkrete Drift-Risiken ohne Matrix:
  1. AudioModule.onCrossModuleStateChange (Spec 1 §15.3 Z. 2433–2453) liest `prev.recording`, `next.recording`, `prev.audio.audioFocusGranted`, `next.audio.audioFocusGranted`. Das ist konsistent mit AudioState/RecordingState. Aber: wenn ein zukünftiger Reviewer eine Optimierung plant ("audioFocus-Loss → Recording.Pause **NUR** wenn nicht-blutooth"), muss er BluetoothScoState mitlesen — das wird einfach hinzufügbar sein, aber niemand weiß, dass jetzt AudioModule auch BluetoothSco liest. Cross-Module-Read-Coupling wächst still.
  2. Cascade-Action-Emission: AudioModule.onCrossModuleStateChange (Z. 2449) emittiert `Action.RecordingAction.PauseRecording` als Cascade. Das ist Cross-Module-Action-Emission. Spec 1 §15.1 sagt "Cross-Module-Observer? ja", aber nicht, **welche Action-Klasse(n) das Modul fremd-emittieren darf**. Wenn AudioModule plötzlich auch `Action.PipelineAction.CancelPipeline` cascadet, ist niemand strukturell darauf vorbereitet.

  Strukturelle Konsequenz: Cross-Module-Coupling wächst implizit; SRP-Audit (§13.3 / §15.6) hängt am "ein Modul = eine Achse"-Argument, das aber nicht durch eine Matrix verifiziert wird. Sec3-Logic L-12 hat als Logic-Risiko ähnliches markiert (LayoutModule SRP — 4 disjunkte Achsen in einem Modul). Strukturperspektive: Plan braucht eine **Cross-Module-Matrix**, damit das SOLID-Argument trägt.
- **Affected sections:**
  - Spec 1 §15.1 (Modul-Übersicht-Tabelle)
  - Spec 1 §15.6 (SOLID-Verifikation des Modul-Patterns)
  - Spec 1 §13.3.13 (Modul-Implementierungen-SOLID)
- **Suggestion:**
  - Neue §15.1.x "Cross-Module-Coupling-Matrix":
    - Zeilen: 13 Module
    - Spalten 1-13: jedes Modul liest Sub-State von anderem Modul? (Bool oder Liste der gelesenen Achsen)
    - Spalten 14-26: jedes Modul cascadet Actions an anderes Modul? (Liste der emittierten Action-Klassen)
  - Pro Cell: maximale Read-Liste / Action-Klasse-Liste, damit der SRP-Audit konkret wird ("AudioModule liest `recording` + `audio` — nichts anderes; cascadet `Action.RecordingAction.PauseRecording` — nichts anderes").
  - Compile-Zeit-Helfer: pro Modul eine `read(global)`-Methode, die den lokalen Sub-State zurückgibt — die ist bereits da. Plus ein optionaler `crossReads(global): CrossReadSet` für die Cross-Module-Sub-States. Module ohne Cross-Reads return leer; AudioModule hat `recording` + `audio` als CrossReadSet.
  - §13.3.13-Audit dann basiert auf der Matrix: SRP = "jedes Modul liest nur sein lokales Sub-State + die in CrossReadSet deklarierten" — strukturell verifizierbar.

---

## Summary Table

| #     | Category                      | Severity      | Issue (Kurz)                                                                  | Description |
|-------|-------------------------------|---------------|-------------------------------------------------------------------------------|-------------|
| S-1   | [INTEGRATION]                 | Critical      | §13.3-SOLID-Audit zitiert pre-F-11/F-8-Vokabular trotz Iter-Header           | PipelineActionRouter / G6 cancelPipeline / §13.3.9-Stub: typed Methoden post-F-8 obsolet, Audit-Beweis bricht. |
| S-2   | [INTEGRATION]                 | Critical      | Sub-State-Pfad-Drift post-F-10 in §13-Tabellen + §8.5 + Spec 3                | flache `state.lastAudioExists`/`state.audioFocusEnabled`/`state.overlayPosition*` statt `state.resend.*` / `state.audio.*` / `state.overlay.*`. |
| S-3   | [INTEGRATION]                 | Critical      | Action-Hierarchie post-F-8 nicht in Slot/Resolver-Sites propagiert            | Spec 2 §8.5 + Spec 2 §11.7 + alle Spec-3-Action-Refs nutzen flache Names; widersprechen Spec 2 §3.3 + Spec 1 §15.2. |
| S-4   | [SOLID] / [INTEGRATION]       | Critical      | Spec 3 §5–§7 mutiert direkt `_state.value.copy(...)` — bricht F-8 + §13.1     | 11+ direkte Mutationen umgehen dispatch + Reducer; §13.1-SSOT-Beweis ist nicht haltbar. |
| S-5   | [INTEGRATION]                 | Critical      | Hauptplan §3.2 + §3.3 zeigt Pre-F-11/F-8-Architekturbild                      | "PipelineStateManager"-Box, "OVERLAY_4BUTTON", "OVERLAY_INDICATOR" — Top-Level-Bild ist nicht synchron zur finalen Architektur. |
| S-6   | [DRY]                         | Important     | §13.5 mischt offene Gaps + RESOLVED + Cross-Spec-Patches                      | Audit-Tabelle ist nicht mehr eindeutig "Open Inbox"; Cross-Spec-Patches GAP-2/3/4 (Spec 3) widersprechen "in Specs eingearbeitet"-Iter-Anker. |
| S-7   | [SOLID] / [INTEGRATION]       | Important     | Hauptplan §4 Block 1 unterschätzt nach F-10/F-11 — strukturell zu groß        | 13 Module + 8 Sub-State-Klassen + Composition-Root + Adapter-Stubs in einem Block; Acceptance §10 verifiziert nur Verhaltens-Konsolidierung, nicht Strukturarbeit. |
| S-8   | [INTEGRATION]                 | Important     | Acceptance §10 deckt User-Bug-Fixes nicht End-to-End                          | Bug-Symptom #3b ("Resend-Btn verschwindet beim Toggle") in keinem §10-Eintrag; Bidirectional-Pointer §1.1 ↔ §10/§14 fehlen. |
| S-9   | [DRY]                         | Important     | §13.4-DRY-Audit ist intra-Spec, nicht cross-Spec                              | Cross-Spec-DRY-Tabelle (Symbol / Definition / Konsumenten) fehlt; `state.recording.isActiveOrPaused`, Position-Mapper, Resolver werden cross-Spec unkontrolliert dupliziert. |
| S-10  | [SOLID]                       | Nice-to-have  | §15 Modul-Inventar ohne Cross-Module-Coupling-Matrix                          | SRP/OCP-Audit der 13 Module hängt am "eine Achse pro Modul" — Cross-Read + Cross-Cascade-Coupling implizit, kann still wachsen. |

---

## Bezug zu GLOBAL_ISSUES (Section 5)

| GLOBAL                                                          | Structure-Review-Verdikt                                       | Findings              |
|-----------------------------------------------------------------|----------------------------------------------------------------|-----------------------|
| **1.1.1** PipelineStateManager-Naming-Drift (Phase 1 G2 + S-8)  | bestätigt + verstärkt — auch in §13.3.7/8 + G6 + §3.2-Diagramm | S-1, S-5              |
| **1.1.6** Block-1-Aufwand massiv unterschätzt (post-F-11)       | strukturell bestätigt — 13 Module + Sub-State + Adapter        | S-7                   |
| **Cross-Spec-Konsistenz-Drift** (Pre-F-11-Vokabular)            | verstärkt — flache Action-Hierarchie + flache State-Pfade      | S-2, S-3, S-4         |
| **Sec3-Logic L-14** Acceptance fehlt End-to-End-Bug-Tests       | bestätigt + erweitert — Bidirectional-Pointer-Schema           | S-8                   |
| **Phase-1 AI-1/AI-2/AI-3** (Sub-State / Action-API / direkter Mutation) | bestätigt aus Audit-Sicht — §13-Beweise nicht haltbar  | S-2, S-3, S-4         |
| **Phase-1 G3** Cross-Module-Effect-Modus 3 nicht verdrahtet     | überschneidet S-10 (Matrix-Vorschlag deckt Modus 3 ab)         | S-10                  |
| **Phase-1 G10** Hauptplan-§3.2-Diagramm zeigt alten StateManager| bestätigt; auch §3.3 betroffen                                 | S-5                   |

---

## Notes for Reviewer

- **Schwerpunkt:** S-1 bis S-5 sind die **Critical**-Cluster — sie verschärfen das eine zentrale Strukturproblem: F-1…F-11 sind im Iter-Log dokumentiert und in Spec 1 §3-§5 + §15 + Spec 2 §3.3 propagiert worden, aber **nicht durchgehend** in §13-Audit-Tabellen, in den Slot-Definitionen Spec 2 §8.5 / Spec 3 §3.1, in den Triangle-FSM-Code-Skizzen Spec 3 §5–§7, und im Hauptplan-Top-Level-Bild §3.2/§3.3. Das bedeutet: die §13-"interne Audit-Schleife" ist nicht haltbar — ein Implementer, der §13 als Vorlage nimmt, baut Code, der gegen das aktualisierte Datenmodell + Action-Modell nicht kompiliert.
- **Empfohlene Apply-Reihenfolge:**
  1. **S-2 + S-3** zuerst (mechanisch durch Suchen-Ersetzen über alle vier Plan-Files; semi-automatisch). Damit kompiliert die Slot-Code-Skizze + Beweis-Tabelle.
  2. **S-4** danach (Spec 3 §5/§6/§7 in dispatch-basierte Action-Cascade umschreiben). Strukturelle Konsequenz aus S-3.
  3. **S-1 + S-5** parallel (Audit + Hauptplan-Diagramm aktualisieren auf DictateOrchestrator + OVERLAY_5BUTTON).
  4. **S-7** mittel-priorisiert (Block-1-Split). Adressiert Sec2-Logic L-6.
  5. **S-8 + S-9** als Acceptance/Audit-Erweiterungen (Bug-Bidi-Pointer, Cross-Spec-DRY-Tabelle).
  6. **S-6 + S-10** als Audit-Format-Erweiterungen (§13.5-Splitting, Cross-Module-Matrix).
- **Nicht-Doppel-Findings mit Phase 1:** Die obigen Issues kondensieren Phase-1-Findings in Section-5-Strukturperspektive (siehe Bezugs-Tabelle). Section-5-Spezifika (z.B. §13.5-Audit-Format-Drift in S-6, Acceptance-Bidirectional-Pointer in S-8, Cross-Module-Coupling-Matrix in S-10) wären in Phase 1 nicht aufgetaucht.
- **Cross-cutting Theme:** "Iter-Log claimt eingearbeitet, Audit-Sektion + Slot-Sites zeigen das Gegenteil." Der Plan hat sich strukturell verbessert (F-1 bis F-11 sind echte Architekturarbeit), aber die Verifikations-Schleife (§13) ist nicht gleichzeitig mitgewachsen. Das ist genau die Drift, die §13-Audits verhindern sollten — ein Audit-System, das selbst der Drift unterliegt, kann seine Aufgabe nicht erfüllen.
