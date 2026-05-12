# Validated Findings – Phase 1

**Created:** 2026-05-10
**Mode:** autonomous
**Source:**
- `plan-review/phase1/pattern-scout.md`
- `plan-review/phase1/architecture-scout.md`

---

## Summary

- **Reviewed:** 24 raw issues from 2 agents (G1–G10 + Patterns 5.x from Pattern-Scout, AI-1–AI-14 from Architecture-Scout)
- **🟢 Auto-Fix:** 6 issues (textual drift, scoped wording, additive doc-notes)
- **🟡 Needs Decision:** 7 issues (architectural / SRP / scope / loop-guards)
- **❌ Eliminated:** 4 issues (false positive, deliberate trade-off, or non-actionable)
- **Most important findings:**
  Massive textual drift between Spec 1 §3/§15 (post-F-8/F-10/F-11) and the
  rest of the plan: ~64 flat `Action.X` refs vs. only 25 hierarchical refs;
  ~28 flat `state.X` refs vs. 0 nested refs; 87 `PipelineStateManager`
  occurrences across the four files despite the F-11 rename to
  `DictateOrchestrator`. These auto-fixable string-rewrites are independent
  of the genuine architectural questions, which cluster around: `LayoutModule`
  SRP-Smell, Block-1 size/order break, cascade-loop safety, reducer
  pure-function violation via hardware read, and one deliberate dual mechanism
  (`Action.NoOp` vs. nullable resolver).

---

## 🟢 Auto-Fix Issues

### Issue 1.0.1: Hauptplan §3.2 ASCII-Diagramm zeigt `PipelineStateManager` statt `DictateOrchestrator`
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Architecture-Scout AI-4 + Pattern-Scout G10 (→ `phase1/architecture-scout.md` Issue AI-4; `phase1/pattern-scout.md` G10)
- **Description:** Hauptplan §3.2 (Z. 131) zeigt im Service-Schicht-Diagramm
  noch `PipelineStateManager (SSOT für ALLE State-Achsen)`. F-11 hat das
  in `DictateOrchestrator` umbenannt + 13 Module + Hilfsklassen
  (`DictateUiStateStore`, `PipelinePrefMirror`, `PipelineRecovery`)
  ergänzt. Das Diagramm ist der Erst-Eindruck für Leser des Hauptplans.
- **Fix:** ASCII-Block in §3.2 (Z. 131) ersetzen — `PipelineStateManager`
  → `DictateOrchestrator (Composition Root, Single Dispatch)` und die
  drei Hilfsklassen (`DictateUiStateStore`, `PipelinePrefMirror`,
  `PipelineRecovery`) als Ko-Aggregate ergänzen. 1 Diagramm-Block
  aktualisieren.
- **Auto-Fix rationale:** Reines Naming-Update auf den in F-11
  beschlossenen Namen. Zielname existiert bereits in Spec 1 §4. Keine
  Architektur-Wahl, keine API-Änderung.
- **Status:** ✅ APPLIED

---

### Issue 1.0.2: Hauptplan §3.3 `LogicalButtonId`-Liste + `OVERLAY_4BUTTON` veraltet
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Architecture-Scout AI-4 (→ `phase1/architecture-scout.md` Issue AI-4)
- **Description:** Hauptplan §3.3 listet (Z. 165) `OVERLAY_INDICATOR`
  (existiert nirgends), enthält das obsolete `SEND` als Top-Level
  und vermisst `WIDGET_TOGGLE`, `OVERLAY_RECORD`, `OVERLAY_SEND`,
  `OVERLAY_PAUSE`, `OVERLAY_TRASH`. Z. 189 zeigt `OVERLAY_4BUTTON`,
  obwohl Iteration §1.3 OPEN-2 (Z. 277) und Spec 3 §3 den Mode bereits
  als `OVERLAY_5BUTTON` umbenannt haben.
- **Fix:** §3.3 Z. 165 LogicalButtonId-Liste durch die Spec-2-§3.1-Liste
  ersetzen (`RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE,
  TRASH, SPACE, PAUSE, ENTER, OVERLAY_RECORD, OVERLAY_SEND,
  OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE`). Z. 189
  `OVERLAY_4BUTTON` → `OVERLAY_5BUTTON`.
- **Auto-Fix rationale:** Spec 2 §3.1 + Spec 3 §3 sind die SSoT für die
  Listen; Hauptplan war nur Index-Snapshot. Iter-Log dokumentiert den
  Beschluss bereits. 1:1-Übernahme.
- **Status:** ✅ APPLIED

---

### Issue 1.0.3: Spec 2 §1+§4 — „FSM-Owner" doppelt vergeben (KeyboardLayoutManager vs. ViewModeModule)
- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Source:** Pattern-Scout G7 (→ `phase1/pattern-scout.md` §2.5 + G7)
- **Description:** Spec 2 §1 + §4 nennt `KeyboardLayoutManager` den
  „zentralen Triangle-FSM-Owner". Tatsächlich lebt die FSM-Reduce-Logik
  laut Spec 1 §15.1 im `ViewModeModule`; der Manager subskribiert nur
  den FSM-Output und switcht das RenderBackend. Das Wording suggeriert
  Doppel-Implementierung.
- **Fix:** Spec 2 §1 + §4 (jeweils 1 Zeile) — „FSM-Owner" durch
  „FSM-Renderer" oder „FSM-Subscriber" ersetzen, mit kurzem Verweis,
  dass die Reduce-Logik im `ViewModeModule` liegt.
- **Auto-Fix rationale:** Klassisches Wording-Update; existierende
  Verantwortungs-Trennung (Modul reduziert, Manager rendert) ist im Plan
  bereits klar — nur das Etikett widerspricht ihr.
- **Status:** ✅ APPLIED

---

### Issue 1.0.4: Spec 1 §4.4 — `DictateUiStateStore` braucht Java-Brücken-Notiz analog `ActiveJobRegistryObserver`
- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Source:** Pattern-Scout G6 (→ `phase1/pattern-scout.md` §1 Tabelle Zeile 3 + G6)
- **Description:** `DictateInputMethodService.java` ist Java; ohne
  Brücken-Klasse muss jede Java-Stelle den `state.collect`-Lifecycle
  selbst basteln. Im Code existiert das Pattern bereits
  (`core/ActiveJobRegistryObserver.kt` mit `repeatOnLifecycle` +
  `fun interface Listener`). Plan §4.4 (Spec 1) erwähnt es nicht
  explizit — die Lücke fällt erst im Implementation-Block 8
  (IME-Service-Integration) auf.
- **Fix:** Spec 1 §4.4 um eine 2-Zeilen-Notiz erweitern: „Java-Brücke
  analog `ActiveJobRegistryObserver` vorgesehen — `DictateUiStateObserver`
  wickelt den `state.collect`-Lifecycle für `DictateInputMethodService.java`
  ab. Konkrete Implementierung im Block 2."
- **Auto-Fix rationale:** Ergänzungs-Notiz, keine architektonische Wahl;
  das referenzierte Pattern existiert bereits unverändert im Repo.
- **Status:** ✅ APPLIED

---

### Issue 1.0.5: Spec 1 §15 + Spec 2/3 — Hierarchische Action-Hierarchie nicht durchpropagiert (~64 flache Refs)
- **Category:** [INTEGRATION]
- **Severity:** **Critical**
- **Source:** Pattern-Scout G1 + Architecture-Scout AI-2 (DEDUPED) (→ `phase1/pattern-scout.md` §2.1 + G1; `phase1/architecture-scout.md` Issue AI-2)
- **Description:** F-8 + F-11 etablieren `Action` als hierarchische
  sealed-class (`Action.RecordingAction.StartRecording`,
  `Action.AudioAction.ToggleAudioFocusPref`, …). Spec 1 §15 + Spec 2 §3.3
  definieren die Hierarchie kanonisch (25 hierarchische Refs gesamt).
  Die konkreten Verwendungen sind aber durchgängig flach: 10 in Spec 1
  (außerhalb §15), 32 in Spec 2 (vor allem §6/§8.5/§8.6), 22 in Spec 3
  (§3.1, §6, §7.3) — gesamt **~64 flache Refs**. Beispiele:
  `Action.StartRecording` (statt `Action.RecordingAction.StartRecording`),
  `Action.ToggleAudioFocus` (existiert nicht — heißt
  `Action.AudioAction.ToggleAudioFocusPref`), `Action.Backspace`
  (statt `Action.KeyboardInputAction.Backspace`),
  `Action.ResendLastAudio` (statt `Action.ResendAction.ResendLastAudio`),
  `Action.CloseOverlay` (statt `Action.ViewModeAction.CloseOverlay`).
  Zusätzlich verliert `StartRecording` seinen Pflicht-Parameter
  (`target: InsertionTarget`) in den Snippets.
  **Konsequenz:** Code, der wörtlich aus Spec 2 §8.5 oder Spec 3 §3.1
  abgeschrieben wird, kompiliert nicht.
- **Fix:** Globaler String-Rewrite über alle drei Specs + Hauptplan
  außerhalb der kanonischen Definitions-Stellen (Spec 1 §15 + Spec 2 §3.3):

  | Pre-F-8/F-11 (heute) | Post-F-8/F-11 (kanonisch) |
  |---|---|
  | `Action.StartRecording` | `Action.RecordingAction.StartRecording(target = InsertionTarget.MainInputConnection)` |
  | `Action.StopRecordingAndSend` | `Action.RecordingAction.StopRecordingAndSend` |
  | `Action.PauseRecording` | `Action.RecordingAction.PauseRecording` |
  | `Action.ResumeRecording` | `Action.RecordingAction.ResumeRecording` |
  | `Action.CancelRecording` | `Action.RecordingAction.CancelRecording` |
  | `Action.Backspace` | `Action.KeyboardInputAction.Backspace` |
  | `Action.EnterKey` | `Action.KeyboardInputAction.EnterKey` |
  | `Action.ToggleAudioFocus` | `Action.AudioAction.ToggleAudioFocusPref` |
  | `Action.ResendLastAudio` | `Action.ResendAction.ResendLastAudio` |
  | `Action.ResendLastAudioLong` | `Action.ResendAction.ResendLastAudioLong` |
  | `Action.ToggleViewModeWidget` | `Action.ViewModeAction.ToggleViewModeWidget` |
  | `Action.CloseOverlay` | `Action.ViewModeAction.CloseOverlay` |
  | `Action.CancelReprocessStaging` | `Action.PipelineAction.CancelReprocessStaging` |

  `Action.NoOp` bleibt Top-Level (so in §3.3 definiert) — separates Issue
  1.1.4 dazu (NoOp-Doppelgleis).

  Hot-Spots: Spec 2 Z. 920, 923, 926, 944, 980-989, 1012-1095,
  1172-1192; Spec 3 Z. 67, 77, 87, 95, 99-101, 907, 936, 1017, 1022,
  1051-1060.
- **Auto-Fix rationale:** Pro Treffer ist die Mapping-Tabelle eindeutig
  (1 Quelle → 1 Ziel); die kanonischen Hierarchie-Pfade existieren
  bereits in Spec 1 §15 + Spec 2 §3.3; keine Architektur-Wahl. Der
  einzige nicht-rein-mechanische Rewrite ist `StartRecording`-mit-
  Pflicht-Parameter — das ist explizit in der Tabelle (`target =
  InsertionTarget.MainInputConnection` als Default in Resolver-
  Snippets) gelöst.
- **Status:** ✅ APPLIED

---

### Issue 1.0.6: Spec 1 §6.4/§9/§13.4 + Spec 2 + Spec 3 — Hierarchische State-Pfade nicht durchpropagiert (~28 flache Refs)
- **Category:** [INTEGRATION]
- **Severity:** **Critical**
- **Source:** Architecture-Scout AI-1 + AI-10 + AI-11 + AI-12 (DEDUPED) (→ `phase1/architecture-scout.md` Issue AI-1, AI-10–AI-12)
- **Description:** F-10 stellt `DictateUiState` von flach (~30 Felder)
  auf hierarchisch (14 Sub-State-Klassen) um. Spec 1 §3 ist die
  kanonische Definition (post-F-10, hierarchisch). Die Code-Snippets in
  Spec 1 §6.4/§9/§13.4, Spec 2 §6/§8.5/§8.6 und Spec 3 §3.1/§5/§7
  verwenden aber durchgängig die flachen Felder (Verifizierung:
  28 flache Refs vs. 0 nested Refs in den vier Plan-Files).
- **Fix:** Globaler String-Rewrite gemäß Konkret-Tabelle:

  | Pre-F-10 (heute im Plan) | Post-F-10 (Spec 1 §3) |
  |---|---|
  | `state.lastAudioExists` | `state.resend.lastAudioExists` |
  | `state.resendEnabled` | `state.resend.resendEnabled` |
  | `state.singleRowMode` | `state.layout.singleRowMode` |
  | `state.smallMode` | `state.layout.smallMode` |
  | `state.animationsEnabled` | `state.layout.animationsEnabled` |
  | `state.audioFocusEnabled` | `state.audio.audioFocusEnabledPref` |
  | `state.userPrefersWidget` | `state.overlay.userPrefersWidget` |
  | `state.overlayOnboardingPending` | `state.overlay.onboardingPending` |
  | `state.overlayPositionPortraitX/Y` | `state.overlay.positionPortraitX/Y` |
  | `state.contentArea` | bleibt flach (so in §3 definiert) |
  | `state.viewMode` | bleibt flach (so in §3 definiert) |

  Hot-Spots: Spec 1 Z. 960-964 (`updateOverlayPosition`), Z. 1190-1354
  (Migrations-Tabellen), Z. 1946+ (DRY-Beweis); Spec 2 Z. 418, 1124-1213,
  1220-1232, 1308; Spec 3 Z. 64-101, 820-867, 916-1102.

  Auch Spec 1 §9 Migrations-Tabellen-Spalten "Mutiert in DictateUiState"
  müssen angepasst werden (heute: `audioFocusEnabled`, `lastAudioExists`,
  …).
- **Auto-Fix rationale:** Eindeutige 1:1-Mapping-Tabelle; kanonische
  Pfade existieren in Spec 1 §3; keine Architektur-Wahl. `state.viewMode`
  und `state.contentArea` bleiben bewusst flach (so in §3 deklariert) —
  Sonderfall ist im Mapping berücksichtigt.
- **Status:** ✅ APPLIED

---

## 🟡 Needs Decision Issues

### Issue 1.1.1: `PipelineStateManager` vs. `DictateOrchestrator` — Naming-Drift (87 Refs)
- **Category:** [INTEGRATION]
- **Severity:** **Critical**
- **Source:** Pattern-Scout G2 + Architecture-Scout AI-4 (DEDUPED) (→ `phase1/pattern-scout.md` §2.2 + G2; AI-4 §3.2-Diagramm-Anteil ist als Issue 1.0.1 separat)
- **Description:** F-11 (2026-05-09) hat `PipelineStateManager`
  (god-class) zu `DictateOrchestrator` (Composition Root + Action-Router,
  ohne fachliche Logik) umbenannt. Hilfsklassen (`DictateUiStateStore`,
  `PipelinePrefMirror`, `PipelineRecovery`) behalten den `Pipeline*`-Prefix.
  Verifikation: 87 Treffer für `PipelineStateManager` — Spec 1: 57,
  Spec 3: 19, Hauptplan: 9, Spec 2: 2. Der reale Stand: `DictateOrchestrator`
  ersetzt `PipelineStateManager` vollständig.
- **Options:**
  A) **Globales Rename** in allen vier Plan-Files (87 Treffer).
     Vorteil: SSoT-Regel respektiert (ein Name pro Konzept). Risiko:
     Kontextverlust an Stellen, wo der historische Name in Begründungen
     bewusst stehen bleiben muss (z.B. „der pre-F-1-`PipelineStateManager`
     war die God-Klasse" → soll erhalten bleiben).
  B) **Kontext-sensitives Rename:** alle „in der Zielarchitektur"-Stellen
     auf `DictateOrchestrator`; alle „Iteration-Log"-Verweise und
     „Migration-vom-Pre-F-1-Zustand"-Stellen behalten den alten Namen
     mit Klarstellung.
- **Recommendation:** **Option B** — Naming-SSoT für Zielarchitektur
  durchziehen, aber Iteration-Log + Migrations-Begründungen historisch
  korrekt halten. Kein Auto-Fix, weil das Klassifizierungsurteil pro
  Treffer einen menschlichen Sanity-Check braucht.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.2: Spec 3 §5/§6/§7 — direkte `_state.value.copy(...)` umgeht F-8 Single Dispatch
- **Category:** [LOGIC]
- **Severity:** **Critical**
- **Source:** Architecture-Scout AI-3 (→ `phase1/architecture-scout.md` Issue AI-3)
- **Description:** F-8 etabliert `dispatch(action: Action)` als
  **einzigen** Mutations-Eingang. F-11 verlagert die Mutations-Logik in
  13 Module-Reducer. Spec 3 §5.3 (Z. 846, 854, 862, 867), §6.1
  (Z. 916, 923, 945), §7.1 (Z. 968), §7.3 (Z. 1025-1102) zeigen Snippets,
  die **direkt** `_state.value = _state.value.copy(viewMode = …,
  smallMode = …, userPrefersWidget = …)` mutieren. Drei Architektur-
  Brüche in einem Snippet: (a) Bypass `dispatch()` → Cross-Module-
  Observer feuert nicht; (b) Bypass `ViewModeModule`-Reducer → FSM-Logik
  dupliziert sich; (c) atomare Multi-Achsen-Mutation fällt in den
  „Atomic Cross-Axis"-Modus 3 (laut §15.5 „nur in begründeten
  Ausnahmen") — wird hier als Default benutzt.
- **Options:**
  A) **Action-Dispatch:** Snippets umschreiben auf
     `dispatch(Action.OverlayAction.MarkOverlayOnboardingShown)`,
     `dispatch(Action.ViewModeAction.ToggleViewModeWidget)` etc. Cross-
     Achsen-Effekte (z.B. WIDGET→KEYBOARD setzt `smallMode = true`)
     laufen via `LayoutModule.onCrossModuleStateChange` (Action-Cascade,
     Modus 2).
  B) **Action-Cascade explizit verdrahten:** zusätzlich zu Option A
     einen sauberen Cross-Module-Observer-Pfad in §15.5 dokumentieren,
     der pro relevantem Übergang die Folge-Action emittiert.
  C) **Atomic Cross-Axis (Modus 3) verbindlich skizzieren:** wenn die
     Multi-Achsen-Mutation gewollt ist, dann muss Modus 3 als
     `composeAtomic(prev, next, action): DictateUiState?`-Hook auf
     `DictateModule` (oder analog im Orchestrator) skizziert werden —
     Option A reicht nicht.
- **Recommendation:** **Option A + B kombiniert.** Spec-3-Snippets auf
  `dispatch()` umschreiben; Cross-Achsen-Logik ins `LayoutModule`
  +`ViewModeModule` heben (Action-Cascade per `onCrossModuleStateChange`).
  Modus 3 (Atomic Cross-Axis) bleibt als „Phase-2-Pattern" markiert oder
  wird in Issue 1.1.3 separat geklärt. Kein Auto-Fix, weil die richtige
  Verteilung der Verantwortung (welches Modul beobachtet wen?) eine
  Architektur-Entscheidung ist.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.3: Cross-Module-Effect-Modus 3 (Atomic Cross-Axis) — deklariert, aber nicht verdrahtet
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Pattern-Scout G3 (→ `phase1/pattern-scout.md` §2.3 + G3)
- **Description:** Spec 1 §15.5 listet drei Cross-Module-Effect-Modi:
  (1) Eigene SideEffect, (2) Action-Cascade, (3) Atomic Cross-Axis-Update
  (mehrere Achsen in einem `store.update`). Der Orchestrator-Code in
  §4.3 implementiert nur Modi 1 und 2 (`result.sideEffects.forEach`,
  `cascadeActions.forEach { dispatch(it) }`). Modus 3 fehlt komplett —
  weder als optionaler Hook auf `DictateModule` noch als separate
  Reducer-Liste im Orchestrator. Der Plan markiert das in §F-11 selbst
  als „Open Question Implementierungsphase", ohne aber Modus 3 aus §15.5
  zu entfernen. **Halb-Pattern ist die schlechteste Option.**
- **Options:**
  A) **Modus 3 jetzt skizzieren:** `composeAtomic(prev, next, action):
     DictateUiState?`-Hook auf `DictateModule` (default `null`).
     Orchestrator iteriert nach normalem Reduce + Cascade einmal alle
     Module mit non-null-Hook und faltet ihre Outputs via `store.update`
     atomar zusammen.
  B) **Modus 3 als „Phase-2 / nicht eingebaut" markieren:** §15.5 auf
     zwei Modi reduzieren; Modus 3 in §14 (Open Questions) als „erst bei
     konkretem Bedarf nachrüsten" parken. Issue 1.1.2 Option A reicht für
     die Spec-3-Übergänge.
- **Recommendation:** **Option B** — bis ein konkreter Use-Case Modus 3
  unausweichlich macht (Issue 1.1.2 Option C), Modus 2 (Action-Cascade)
  ist ausreichend. Bewusst auf Halb-Pattern verzichten. Diese
  Entscheidung hängt eng mit Issue 1.1.2 zusammen — gemeinsam klären.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.4: `Action.NoOp` vs. Reducer-`null`-Return — Zwei „Action war nicht aktiv"-Mechanismen
- **Category:** [CLEAN]
- **Severity:** Important
- **Source:** Pattern-Scout G4 (→ `phase1/pattern-scout.md` §2.4 + G4)
- **Description:** Pattern A: `Action.NoOp` als Top-Level-Default für
  nicht-bindende Slots (Spec 2 §3.3 Z. 126; Spec 2 §8.5
  `resolveRecordActionPipeline`; Spec 3 §3.1 Schließen-Button).
  Pattern B: Reducer geben `null` zurück, wenn die Action im aktuellen
  State nicht erlaubt ist (Spec 1 §4.2 Z. 360-362; verwendet in §15.2).
  Beide bedeuten „Action soll nichts tun", aber der Codepfad ist
  unterschiedlich: `NoOp` läuft durch `dispatch()`, findet kein Modul,
  loggt `"Keine Modul-Zuordnung für NoOp"` (§4.3 Z. 446-448) und endet —
  das ist nicht intendiert (würde False-Positive-Warnings produzieren).
- **Options:**
  A) **`Action.NoOp` entfernen + `actionResolver` auf `(DictateUiState)
     -> Action?` (nullable) typisieren.** Backend ruft
     `slot.actionResolver(state)?.let { onAction(it) }`. Nicht-bindende
     Slots feuern keine `dispatch`-Calls; das Logging-Rauschen
     verschwindet.
  B) **`Action.NoOp` behalten, im Orchestrator früh aussortieren:**
     `if (action == Action.NoOp) return` als erste Zeile von `dispatch`.
     Resolver bleibt `(DictateUiState) -> Action`. Einfache Erweiterung,
     aber laesst die Doppelgleisigkeit konzeptionell stehen.
  C) **Status quo akzeptieren:** beide Mechanismen behalten, Logging-
     Level für „Keine Modul-Zuordnung für NoOp" auf DEBUG senken. Nicht
     empfohlen — verstößt gegen SSoT-Intent.
- **Recommendation:** **Option A** — der nullable-Resolver-Typ ist
  semantisch genau, was hier modelliert wird („dieser Slot bindet im
  aktuellen State auf nichts"). Reducer-`null` bleibt für „Action war
  fachlich nicht erlaubt" reserviert (zwei klar getrennte Konzepte mit
  unterschiedlicher Ursache). Architektur-Entscheidung, daher 🟡.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.5: `LayoutModule` aggregiert vier disjunkte Achsen — SRP-Smell
- **Category:** [SOLID]
- **Severity:** Important
- **Source:** Architecture-Scout AI-5 (→ `phase1/architecture-scout.md` Issue AI-5)
- **Description:** Spec 1 §15.1 listet `LayoutModule` als Owner für
  `contentArea, layout`-Achsen. Detail:
  - `contentArea: ContentArea` (MAIN_BUTTONS/QWERTZ/EMOJI_PICKER) —
    View-Selection, transient, vom IME-Service via Action-Trigger gesetzt.
  - `layout: LayoutPrefs(singleRowMode, smallMode, animationsEnabled)`
    — UI-Mode-Prefs, persistent, von SharedPreferences via PrefMirror.

  Unterschiedliche Quellen, Konsumenten, Lifecycle-Charakteristika.
  SRP-Smell. Zusätzlich strukturelle Inkonsistenz: `state.contentArea`
  ist Direkt-Feld (Enum), `state.layout` ist Sub-State-Container
  (Data-Class). Wer eine neue Layout-Achse hinzufügt, weiß nicht: gehört
  das ins `LayoutPrefs` oder flat?
- **Options:**
  A) **Zwei Module:** `ContentAreaModule` + `LayoutPrefsModule` —
     saubere SRP, 14 Module statt 13.
  B) **Ein `LayoutState`-Container:** `data class LayoutState(val
     contentArea: ContentArea, val prefs: LayoutPrefs)`. State-Pfade:
     `state.layoutState.contentArea`, `state.layoutState.prefs.singleRowMode`.
     Konsistent mit anderen Sub-State-Containern.
  C) **Status quo:** Plan verteidigen — `contentArea` als Direkt-Feld
     ist bewusste Entscheidung, weil es ein Enum ist (1 Feld pro Achse).
- **Recommendation:** **Option B** (kleiner Footprint, klarere
  Hierarchie, konsistent mit anderen Sub-State-Klassen). Option A
  überspezifiziert die Modul-Achse (auf 14). Option C lässt die
  strukturelle Inkonsistenz stehen. Architektur-Entscheidung, daher 🟡.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.6: Block 1 = Quick-Wins UND F-11-Modul-System — Aufwand massiv unterschätzt + Block-Reihenfolge bricht
- **Category:** [INTEGRATION]
- **Severity:** **Critical**
- **Source:** Architecture-Scout AI-6 + AI-7 (DEDUPED — beide referenzieren
  denselben Block-1-Zwiespalt) (→ `phase1/architecture-scout.md` Issue AI-6, AI-7)
- **Description:** Hauptplan §4 Tabelle Zeile 1 beschreibt Block 1 als
  „resend_btn-Visibility zentralisieren; recordButton.text/isEnabled-
  Hybrid auflösen; Quick-Win-Fixes (KSM.refresh in Toggle-Callbacks);
  Komplexität: klein-mittel". Das ist der Vor-F-11-Stand. Mit F-11 ist
  Block 1 inhaltlich: 13 Module à 150-300 Zeilen, hierarchischer
  DictateUiState mit 14 Sub-State-Klassen, Action-Sealed-Hierarchie
  (~12 Sub-Sealed-Klassen), kotlinx.collections.immutable, Cross-Module-
  Observer, ModuleServices-DI. „klein-mittel" ist falsch (eher „groß").

  Zusätzlich bricht die Block-Reihenfolge-Garantie: Hauptplan §4 Z. 208
  sagt „Block 1 muss vor allem anderen kommen". Mit dem F-11-Block-1-
  Inhalt braucht aber das Modul-System den Foreground-Service als
  Container — und der ist Block 2. Resultat: Block 1 hängt von Block 2 ab.

  Spec 2 §13.5 Gap 5 stellt eine Hilfs-Mitigation in Aussicht
  („`predResendVisible`-Konsolidierung **bereits** vor dem Refactor —
  eliminiert die 6-Mutator-Race **innerhalb des heutigen Codes**, ohne
  MotionLayout"). Das deutet auf zwei Block-1-Phasen hin, die im
  Hauptplan nicht so dokumentiert sind.
- **Options:**
  A) **Block 1 in 1a + 1b splitten:**
     - Block 1a — Quick-Win-Konsolidierung (im heutigen Code):
       `predResendVisible` als Helper, alle 6 resend_btn-Mutationen auf
       den Helper umstellen, `recordButton.text/isEnabled`-Hybrid
       auflösen. Ohne Modul-Architektur. Komplexität: klein-mittel.
     - Block 1b — State-Architektur (im PipelineService-Container):
       DictateUiState (hierarchisch), DictateOrchestrator, 13 Module,
       Action-Sealed-Hierarchie. Komplexität: groß. Setzt Block 2
       (Service-Skelett) voraus.
     Reihenfolge: 1a → 2 → 1b → 3 → 4 → 5 → 6. „Block 1 vor allem"-
     Garantie wird durch 1a (im heutigen Code) eingehalten.
  B) **Block-1-Beschreibung schlicht umformulieren ohne Split:**
     Hauptplan §4 + §10 explizit auf F-11-Realität aktualisieren
     (Komplexität → groß; Reihenfolge-Garantie streichen oder neu
     formulieren). Block 1 hängt damit explizit von Block 2 ab.
  C) **Block 1 nach Block 2 verschieben:** Block 2 (Service-Skelett)
     wird Block 1, der Modul-Aufbau wird Block 2. Quick-Win-
     Konsolidierungen entfallen oder werden Pre-Block-0.
- **Recommendation:** **Option A** — Split macht den Übergang
  ehrlich, hält die „vor allem"-Garantie für 1a (Quick-Win) ein, und 1b
  hat saubere Voraussetzung in Block 2. Das ist auch näher an dem, was
  Spec 2 §13.5 Gap 5 implizit beschreibt. Architektur-Entscheidung, da
  Block-Struktur den Implement-Long-Plan-Ablauf direkt prägt.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.7: Cross-Module-Cascade rekursiv ohne Loop-Guard
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Architecture-Scout AI-8 (→ `phase1/architecture-scout.md` Issue AI-8)
- **Description:** `DictateOrchestrator.dispatch` Z. 477 macht
  `cascadeActions.forEach { dispatch(it) }` — rekursiv. Wenn Modul A
  beim State-Change von B mit Action X reagiert und Modul B's Reducer auf
  X seinen State so mutiert, dass A's Cross-Module-Observer wieder feuert
  (X' emittiert), entsteht endlose Rekursion → StackOverflowError.
  §15.5 erwähnt das nicht; es gibt kein Tiefe-Counter und keine
  Cycle-Detection. Real-World-Szenario: AudioFocus-Loss → Recording.Pause
  → State-Change → Audio-Modul-Observer reagiert wieder → …
- **Options:**
  A) **Cascade-Tiefe-Counter:** `dispatch(action, depth = 0)`, ab
     `depth >= 8` Logger.error + Cascade abbrechen. Pragmatisch,
     low-overhead.
  B) **Cycle-Detection per Action-Klasse:** Track
     `Set<KClass<Action>>` während eines Top-Level-`dispatch`; Re-Emission
     derselben Action in derselben Cascade-Kette → Logger-Warning +
     Abbruch. Genauer als (A), aber teurer.
  C) **DEBUG-Build-Assertion:** zusätzlich zu (A) oder (B): in
     DEBUG-Builds Stackoverflow als `error()` (App-Crash, früh
     bemerkt); in Release als Logger-Warning + Cascade-Abbruch.
- **Recommendation:** **Option A + Option C kombiniert** — Tiefe-Counter
  als Default-Schutz, DEBUG-Assertion damit Loops in Tests sofort auffallen.
  Architektur-Entscheidung, weil die Wahl der Mitigation Auswirkungen
  auf Test-Strategie und Production-Logging hat.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

### Issue 1.1.8: Reducer ist nicht pure — `buildContext` macht synchronen Hardware-Read pro `dispatch`
- **Category:** [SOLID]
- **Severity:** Important
- **Source:** Architecture-Scout AI-9 (→ `phase1/architecture-scout.md` Issue AI-9)
- **Description:** Spec 1 §4.3 Z. 487-490 definiert
  `buildContext(global)` → `ReducerContext(audio = global.audio,
  recordingAudioFile = servicesFactory.get().recordingHardware
  .currentAudioFile())`. Die Funktion wird im `dispatch()`-Pfad VOR
  jedem Reducer-Aufruf evaluiert (Z. 455). `currentAudioFile()` ist ein
  Subsystem-Read (Hardware-/Filesystem-Zustand) und kann sich zwischen
  zwei Reducer-Calls ändern, ohne dass eine Action ihn auslöst. Damit
  ist der Reducer **nicht pure** — die F1+F2-Garantie ist verletzt.
  Konsequenz: Reducer-Tests müssen `ModuleServicesFactory` stubben
  (auch für reine State-Übergangs-Tests). Determinismus ist nur unter
  „Subsystem mutiert nicht zwischen Reducer-Calls"-Annahme gegeben.
- **Options:**
  A) **`recordingAudioFile` als Feld in `RecordingState.Active /
     Preparing / Paused`:** Action `MediaRecorderReady` füllt es bei
     Allokation; Reducer liest nur aus dem State, nicht aus der
     Hardware. `ReducerContext` schrumpft auf reine State-Snapshots
     (`audio: AudioState`, ggf. `now: Long`). Hardware-Reads bleiben in
     `runEffect()` (per Definition nicht-pure).
  B) **`buildContext` ein-mal-pro-`dispatch` evaluieren ist akzeptabel:**
     Reducer ist trotzdem pure relativ zu `(state, action, context)`;
     der Hardware-Read passiert vor dem Reducer, nicht im Reducer.
     Tests stubben `servicesFactory`. Kein Code-Refactor nötig.
  C) **`buildContext` lazy machen:** Hardware-Read erst dann, wenn
     ein Reducer ihn anfordert (`ctx.recordingAudioFile()`-Funktion
     statt -Wert). Reducer wird per definiert nicht-pure, weil ein
     Funktion-Call im Reducer-Body Side-Effekte hat — semantisch
     schlechter als (A) und (B).
- **Recommendation:** **Option A** — saubere Pure-Function-Garantie,
  einfacher zu testen, Hardware-Reads bleiben physisch in `runEffect`
  isoliert. Architektur-Entscheidung mit API-Auswirkung
  (`RecordingState`-Sub-Klassen-Felder ändern sich), daher 🟡.
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md)

---

## ❌ Eliminated Issues

| Original Issue | Source | Reason for Elimination |
|----------------|--------|------------------------|
| Pattern-Scout G5 — `LanguageController.kt` / `RecordingManager.kt` / `BluetoothScoManager.kt` / `KeyboardUiController.kt` Migrations-Lücke | `phase1/pattern-scout.md` §2.6 + G5 | **Nicht eliminiert, sondern dedupliziert in Issue 1.1.6** — Block-1-Inhalt-Update muss die Migrations-Liste eh schließen; G5 ist eine Ausprägung desselben Block-1-Inhalt-Drift-Problems. (Wenn beim Apply-Step herauskommt, dass die Klassen-Migrations-Liste tatsächlich separat behandelt werden muss, kann aus 1.1.6 ein Sub-Issue entstehen.) |
| Pattern-Scout G8 — `JobExecutor.initialize(orchestrator)` als Test-Seam-Vorbild | `phase1/pattern-scout.md` §1 Tabelle Zeile 11 + G8 | **Over-Engineering / nicht aktuell.** Plan nutzt heute Konstruktor-Default-Parameter für die Modul-Liste, was ein etabliertes Kotlin-Pattern ist und Test-Seam-Symmetrie via Konstruktor-Injection bereits erfüllt. Eine zusätzliche `initialize`-Methode würde zwei Init-Pfade (Default-Konstruktor + `initialize`) parallel halten — höhere Komplexität ohne realen Test-Seam-Gewinn. Pattern-Scout selbst klassifiziert das als „lohnt sich, wenn das gewünscht ist" (also: nur falls Bedarf entsteht). Kein PENDING-Issue. |
| Pattern-Scout G9 — F-7 backend-spezifische Click-Listener (kein Issue) | `phase1/pattern-scout.md` §1 + G9 | **False positive — bewusste Differenzierung, klar begründet.** Pattern-Scout markiert das selbst als „kein Issue". Nur zur Vollständigkeit gelistet. |
| Architecture-Scout AI-13 — EditBarController-Auf-State-Refactor explizit in Block-Liste aufnehmen | `phase1/architecture-scout.md` Issue AI-13 | **Nicht eliminiert, sondern dedupliziert in Issue 1.1.6** — gehört zur Block-Inhalt-Aktualisierung, die im Block-1-Split-Issue mitfällt. Wenn Phase 2 das nicht abdeckt, kann ein eigenes Sub-Issue entstehen. |
| Architecture-Scout AI-14 — `AudioFocusGate` (existing) vs. `AudioFocusSubsystem` (Plan) Naming-Konflikt | `phase1/architecture-scout.md` Issue AI-14 | **Nicht eliminiert, dedupliziert in Issue 1.1.1** — derselbe Naming-SSoT-Patternsspielraum (Bestands-Klasse vs. Plan-Wording). Kann im selben kontext-sensitiven Rename-Pass mitgehoben werden. |
| Pattern-Scout §3 (Module-Augmentation → Kotlin Übersetzungs-Audit) — alle Punkte | `phase1/pattern-scout.md` §3 | **False positive / kein Issue.** Pattern-Scout selbst kommt zum Schluss „Übersetzung ist idiomatisch korrekt für Kotlin. Keine Klassifikation als Issue." Der Punkt ist nur zur Plan-Validierung in der Pattern-Scout-Output-Datei aufgeführt. |
| Pattern-Scout §5 (Pattern-Erweiterungs-Vorschläge: `DictateUiStateObserver`, `ImmutableActions`-Test-DSL, `@Synchronized`-Annotation) | `phase1/pattern-scout.md` §5 | **Reuse-Vorschläge, keine Findings.** `DictateUiStateObserver` ist als Issue 1.0.4 (additive Notiz) gekennzeichnet. `ImmutableActions`-Test-DSL und `@Synchronized` sind nicht aktuell — beide sind „nice to have", aber nicht im Plan-Scope. Kann später als eigene ADR/Plan auftauchen. |

---

## Notes for Apply-Step (Resume Context)

- **Apply-able 🟢-Issues:** 1.0.1, 1.0.2, 1.0.3, 1.0.4, 1.0.5, 1.0.6.
  Davon sind **1.0.5 + 1.0.6 die mengenmäßig größten** (~64 Action-Refs +
  ~28 State-Path-Refs über alle vier Plan-Files). Apply-Agent muss die
  beiden Mapping-Tabellen wörtlich anwenden.
- **🟡-Issues bleiben unangetastet** für den späteren Research-/Final-
  Report-Step. User-Entscheidung pro Issue.
- **Dedup-Hinweise:**
  - 1.0.5 vereint Pattern-Scout G1 + Architecture-Scout AI-2.
  - 1.0.6 vereint Architecture-Scout AI-1 + AI-10 + AI-11 + AI-12.
  - 1.1.1 vereint Pattern-Scout G2 + den Naming-Anteil von AI-4 (das
    Diagramm-§3.2-Update ist davon separiert als 1.0.1, der LogicalButtonId-
    §3.3-Update als 1.0.2).
  - 1.1.6 vereint Architecture-Scout AI-6 + AI-7.
- **Kein PREVIOUS_FINDINGS-Dedup nötig** — Phase 1 ist erste Phase.
