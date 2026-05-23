# Phase 1 — Architecture Scout (Plan-Review)

**Plan:** Keyboard-Layout-Refactor (Service-zentrierte SSOT + 3-Modus-Triangle)
**Modular:** 4 Files, ~6993 Zeilen total (Hauptplan + 3 Specs)
**Reviewer-Modus:** plan-review (heading-based section split, kein `{CHANGED_FILES}`)

---

## Architecture Context

Der Plan refaktoriert die Main-Button-Area der Dictate-IME (Android Input
Method Editor) auf eine MVI-ähnliche Architektur:

- **Aktuelle Codebase:** Java-IME-Service (`DictateInputMethodService.java`)
  + Kotlin-Helper-Klassen unter
  `app/src/main/java/net/devemperor/dictate/{core,keyboard,widget}/`. State
  ist heute hybrid auf 3+ Klassen verteilt
  (`RecordingStateController`, `KeyboardUiController`,
  `KeyboardStateManager`); 6 verteilte `resend_btn`-Mutationen, race-fragiler
  `recordButton.text/isEnabled`-Hybrid.
- **Ziel-Architektur (nach Iteration F-1..F-11):**
  - Foreground Service `DictatePipelineService` als prozess-stabiler State-Owner.
  - `DictateOrchestrator` (Composition Root) + `dispatch(action: Action)`
    als einziger Mutations-Eingang (F-8 Single Dispatch).
  - 13 `DictateModule`-Plugins (sealed interface + `object`-Singletons), eines pro
    fachlicher Domäne; jedes mit eigenem Sub-State, Action-Sealed-Class,
    Reducer, EffectHandler, optionalem Cross-Module-Observer (F-11).
  - Hierarchischer `DictateUiState` mit Sub-State-Klassen (`LayoutPrefs`,
    `AudioState`, `OverlayState`, `ResendState`, `LivePromptState`,
    `LanguageState`, `FeatureToggles`, `ThemingState`,
    `InterruptionState`) — Top-Level hält 14 Sub-Felder (F-10).
  - `LayoutCatalog` mit deklarativen `ButtonSlot`s (predicate + resolver-driven).
  - `RenderBackend`-Interface mit zwei Implementierungen: `ImeViewBackend`
    (MotionLayout) und `OverlayBackend` (WindowManager).
  - 1 neue Library: `kotlinx.collections.immutable` (~50 KB; F-9).

Die **Architektur ist konzeptionell sauber** und folgt einem etablierten
Plugin-System-Pattern (vergleichbar mit den Modul-Patterns aus
`knowledge-reference`). Die Schwachstellen liegen **nicht in der
Ziel-Architektur**, sondern in **textueller Drift zwischen den Iterationen**:
F-8/F-10/F-11 wurden in Spec 1 vollständig propagiert, aber Hauptplan §3,
Spec 2 §6/§8/§9 und Spec 3 §5/§7 enthalten noch pre-F-8/F-10/F-11-Snippets.

---

## SOLID Analysis (System Level)

| Prinzip | Bewertung | Details |
|---------|-----------|---------|
| **SRP** | OK (System-Ebene), 🟡 (1 Modul auffällig) | Module-Aufteilung 13 ist sauber. `LayoutModule` aggregiert aber 4 heterogene Achsen (`contentArea`, `singleRowMode`, `smallMode`, `animationsEnabled`) — siehe AI-3. |
| **OCP** | Sehr gut | Plugin-Pattern erlaubt neue Module via 1 Datei + 4 Mini-Erweiterungen (Spec 1 §15.4). `forKeyboard()` ist die einzige Erweiterungs-Stelle für neue Modi. |
| **DIP** | Gut | F-2 etabliert `PipelineSessionRepo` + `PipelineRunner` als Interfaces, OverlayBackend hängt an `OverlayWindow`/`OverlayPermissionGate`/`OverlayLayoutParamsFactory`-Interfaces. `ModuleServices` ist DI-Container. |
| **LSP** | OK | Sealed-Interface + `object`-Singletons sind polymorph austauschbar; Tests injizieren `FakeModuleServices`. |
| **ISP** | OK | `RenderBackend` (3 Methoden), `DictateModule` (5 + 1 optional), `OverlayWindow` (4) — alle minimal. |

**Wichtigste positive Eigenschaft:** Der DictateOrchestrator kennt **kein**
konkretes Modul — nur das `DictateModule`-Interface und die
`KClass<Action>`-Lookup-Map. Das ist OCP/DIP in seiner reinsten Form: eine
neue State-Achse erfordert NULL Änderungen am Orchestrator.

**Größte Auffälligkeit:** `LayoutModule` ist als Sammler für vier nicht
zusammengehörige Achsen geplant — siehe AI-3.

---

## Integration Analysis

Wie gut fügt sich die Architektur ins bestehende System?

- **Layer-Struktur korrekt:** Plan etabliert ein neues
  `app/src/main/java/net/devemperor/dictate/state/`-Package (mit
  `state/modules/`-Sub-Package) — sauber abgegrenzt vom bestehenden `core/`
  (das schrumpft auf View-/Controller-Concerns) und `keyboard/`
  (QWERTZ-Subview, unverändert).
- **Bestehende Klassen werden migriert/gelöscht:** Spec 1 §9 + Spec 2 §9
  enumerieren konkret welche Klassen verschwinden
  (`KeyboardLayoutModeController`, `MainButtonsController`-Click-Logik,
  Visibility-Mutations in `RecordingUiController`/`KeyboardUiController`/
  `DictateInputMethodService.java`). Migrations-Tabellen referenzieren
  `file:line`-Pointer — das ist sehr gut.
- **Datentyp-Wiederverwendung:** `RecordingState`
  (`RecordingState.kt:10`) und `PipelineUiState` (`PipelineUiState.kt:13`)
  werden **unverändert** weiterverwendet — keine unnötige Doppel-Definition.
- **Foreground-Service-Lifecycle korrekt vorgesehen:**
  AndroidManifest-Diff in §11.1.1, `FOREGROUND_SERVICE_TYPE_MICROPHONE`,
  POST_NOTIFICATIONS-Permission-Pattern (Android 13+) sind alle
  spezifiziert.
- **DB-Migration M3→M4 ist additiv** und rollback-sicher (Spec 1 §6.1).

**Kritische Integrations-Lücke:** Die textuellen Inkonsistenzen zwischen
Specs (siehe AI-1, AI-2) bedeuten konkret: ein Implementierer, der Spec 2
§8.5 wörtlich befolgt, wird Code schreiben, der gegen die in Spec 1 §3
definierte Datenstruktur **nicht kompiliert** (`state.lastAudioExists` vs.
`state.resend.lastAudioExists`). Das ist die größte konkrete Gefahr im
aktuellen Plan-Stand.

---

## Integration Points

| Bestehende Schnittstelle | Genutzt? | Empfehlung |
|---|---|---|
| `Service.onBind()` / `Binder` (Android Local Binder) | ja | korrekt eingesetzt; F-8 Single Dispatch hält Binder minimal (`state` + `dispatch` + 2 Lifecycle-Hooks) |
| `MutableStateFlow` / `StateFlow` (kotlinx) | ja | Standard-Reaktiv-Pattern, gut |
| `MotionLayout` (Material) | ja (neu) | Empfohlen durch Phase-2-Recherche; Spike vor Block 5 vorgesehen |
| `WindowManager` / `TYPE_APPLICATION_OVERLAY` | ja (neu) | Wrapper `OverlayWindow` macht das testbar (DIP) |
| `androidx.room.Migration` | ja | M3→M4 in Standard-Migration-Pattern eingebettet |
| `OnSharedPreferenceChangeListener` (`PipelinePrefMirror`) | ja | Standard-Pattern für Pref-Spiegelung |
| `kotlinx.collections.immutable.PersistentList` | ja (neu Library) | Bewusste, begründete Library-Adoption (F-9) |
| `Foreground Service Type Microphone` (Android 14+) | ja | korrekt mit Open-Question §14.2 markiert (Verifikation auf Pixel-API-34 ausstehend) |
| `KSP` (Kotlin Symbol Processing) | nein | als Phase-2-Option dokumentiert — bewusste Nicht-Adoption ist OK |

---

## Global Issues

| # | Kategorie | Severity | Issue | Beschreibung |
|---|-----------|----------|-------|--------------|
| AI-1 | [CONSISTENCY] | **Critical** | Flat-vs-nested State-Field-Drift zwischen Specs | Spec 2 §8.5 + Spec 3 §3/§7 referenzieren `state.lastAudioExists`, `state.singleRowMode`, `state.audioFocusEnabled`, `state.userPrefersWidget`, `state.smallMode`, `state.overlayOnboardingPending`, `state.animationsEnabled` (flach), aber Spec 1 §3 (post-F-10) definiert sie verschachtelt: `state.resend.lastAudioExists`, `state.layout.singleRowMode`, `state.audio.audioFocusEnabledPref`, `state.overlay.userPrefersWidget`, `state.layout.smallMode`, `state.overlay.onboardingPending`, `state.layout.animationsEnabled`. **Code, der wörtlich aus Spec 2/3 abgeschrieben wird, kompiliert nicht.** |
| AI-2 | [CONSISTENCY] | **Critical** | Action-API-Drift zwischen F-8/F-11 (hierarchisch) und Implementierungs-Snippets (flach) | Spec 2 §3.3 definiert Actions hierarchisch: `Action.RecordingAction.StartRecording`, `Action.AudioAction.ToggleAudioFocusPref`, `Action.PipelineAction.CancelReprocessStaging`, `Action.KeyboardInputAction.Backspace`, `Action.KeyboardInputAction.EnterKey`, `Action.ResendAction.ResendLastAudio`, `Action.ViewModeAction.ToggleViewModeWidget`, `Action.ViewModeAction.CloseOverlay`. Aber LayoutCatalog-Beispiele (Spec 2 §8.x: Z. 920, 923, 926, 944, 980, 983, 986, 989, 1015, 1018, 1029, 1032, 1051-1059, 1084-1106, 1172-1174) und Spec 3 §3 (Z. 67, 77, 95-101) verwenden **flache** Namen: `Action.StartRecording`, `Action.Backspace`, `Action.ToggleAudioFocus`, `Action.EnterKey`, `Action.ResendLastAudio`, `Action.ToggleViewModeWidget`, `Action.CloseOverlay`. `Action.ToggleAudioFocus` existiert in §3.3 gar nicht (heißt `ToggleAudioFocusPref`). |
| AI-3 | [CONSISTENCY] | **Critical** | Mutations-Pattern-Drift: Spec 3 §7 nutzt direkte `_state.value.copy(...)` statt `dispatch(action)` | Spec 3 §5.3 (Z. 846, 854, 862, 867), §6.1 (Z. 916, 923, 945), §7.1 (Z. 968), §7.3 (Z. 1025-1031, 1064-1068, 1102) zeigen Code-Skizzen, die direkt `_state.value = _state.value.copy(viewMode = …, smallMode = …, userPrefersWidget = …)` mutieren — also den `PipelineStateManager`-Pre-F-8-Pfad. Die Single-Dispatch-Architektur (F-8) verbietet das genau: alle Mutationen MÜSSEN über `dispatch(action: Action)` an einen Module-Reducer laufen. Das ist nicht nur Style — es bricht die Architektur-Invariante "Reducer ist Pure Function" und umgeht Cross-Module-Observer (F-11). |
| AI-4 | [CONSISTENCY] | **Critical** | Hauptplan §3.2-Skelett pre-F-1/F-8/F-11; §3.3 listet `OVERLAY_4BUTTON` statt `OVERLAY_5BUTTON` und nicht-existente `LogicalButtonId.OVERLAY_INDICATOR` | Hauptplan ist der "Index" — wenn jemand zuerst nur den Hauptplan liest, sieht er ein veraltetes Bild. §3.2 enumeriert `PipelineStateManager (SSOT für ALLE State-Achsen)` — das ist der F-1-Vor-Stand. F-11 hat das in `DictateOrchestrator` umbenannt + 13 Module hinzugefügt; davon steht nichts im Skelett. §3.3 erwähnt `OVERLAY_4BUTTON` (in Iter-Log "Resolved 2026-05-08 — heißt OVERLAY_5BUTTON"). LogicalButtonId-Liste enthält `OVERLAY_INDICATOR` (kommt nirgends vor) und vermisst `WIDGET_TOGGLE` + `OVERLAY_RECORD` (beide in Spec 2/3 verwendet). |
| AI-5 | [SOLID] | Important | `LayoutModule` aggregiert vier semantisch unzusammenhängende Achsen → SRP-Smell | `LayoutPrefs` (singleRowMode, smallMode, animationsEnabled) ist UI-Mode. `ContentArea` (MAIN_BUTTONS/QWERTZ/EMOJI_PICKER) ist View-Selection. Beide werden vom selben `LayoutModule` verwaltet (Spec 1 §15.1 Tabelle Zeile 5 + §3 Achsen-Übersicht Zeile 4+5). Sub-State-Klasse `LayoutPrefs` ist sinnvoll; `contentArea` als Enum-Direktfeld in DictateUiState neben `layout: LayoutPrefs` ist inkonsistent (eines hat Container-Typ, das andere nicht). Empfehlung: entweder zwei Module (`LayoutPrefsModule` + `ContentAreaModule`) — oder eine `LayoutState` Sub-Klasse, die `contentArea` einschließt (`state.layoutState.contentArea` und `state.layoutState.prefs.singleRowMode`). |
| AI-6 | [INTEGRATION] | Important | `Block 1: State-SSOT-Konsolidierung` ist im Hauptplan §4 "klein-mittel" geschätzt — entspricht aber nach F-10/F-11 dem Aufwand: 13 Module, hierarchischer DictateUiState, kotlinx.collections.immutable, Action-Sealed-Hierarchie | Block 1 hat sich zwischen Iteration "Quick-Win-Fixes" (alter Stand laut Tabelle: "`resend_btn`-Visibility zentralisieren; Quick-Win-Fixes (KSM.refresh in Toggle-Callbacks)") und der F-11-Realität (komplettes Modul-System aufbauen) **massiv vergrößert**. Die Kurz-Beschreibung in Hauptplan §4 stimmt nicht mehr; die Größenangabe "klein-mittel" ist falsch (eher "groß" allein für Block 1). Das ist relevant für Aufwandsschätzung + Implementations-Planung. |
| AI-7 | [INTEGRATION] | Important | F-11-Trennung Block 1 vor Block 2 funktioniert nicht mehr | Hauptplan §4 sagt: "Block 1 (State-SSOT) muss vor allem anderen kommen — sonst werden neue Bug-Klassen auf einer noch-fragilen State-Quelle aufgebaut." Aber Block 1 ist im Hauptplan beschrieben als Quick-Wins **innerhalb des bestehenden Codes** ("KSM.refresh in Toggle-Callbacks"); der Modular-Orchestrator (F-11) braucht aber den Foreground-Service als Container (Block 2). Resultat: Block 1 ist heute nicht mehr klar abgegrenzt von Block 2. Spec 2 §13.5 Gap 5 erwähnt das implizit — die Mitigation ("Block 1 implementiert die `predResendVisible`-Konsolidierung **bereits** vor dem Refactor — eliminiert die 6-Mutator-Race **innerhalb des heutigen Codes**, ohne MotionLayout") deutet an, dass Block 1 zwei Inkarnationen hat: (a) Quick-Win im heutigen Code, (b) State-Refactor mit DictateOrchestrator. Das sollte explizit in Hauptplan §4 als Block 1a + 1b auftauchen oder Block 1 sollte umbenannt werden. |
| AI-8 | [SOLID] | Important | Cross-Module-Cascade-Mechanik via rekursiver `dispatch()` — kein Schutz gegen Cascade-Schleifen | DictateOrchestrator §4.3 dispatcht in Schritt 6: `cascadeActions.forEach { dispatch(it) }` — rekursiv. Wenn Modul A bei State-Change von B reagiert mit Action X, und Modul B bei State-Change durch X wieder mit Action Y reagiert, gibt es keine Schutz-Schicht (Tiefe-Counter, Cycle-Detection). Spec 1 §15.5 erwähnt das gar nicht. Ein "Atomic Cross-Axis-Update"-Modus ist als Modus 3 dokumentiert, aber der Default (Modus 2 / Action-Cascade) hat keine Sicherheit gegen versehentliche Loops. Empfehlung: max. Cascade-Tiefe + DEBUG-Log; Stackoverflow soll als Logger-Warning kommen, nicht als Crash. |
| AI-9 | [DEPENDENCY] | Important | `DictateOrchestrator` ruft im Reduce-Schritt `servicesFactory.get().recordingHardware.currentAudioFile()` synchron für jeden Action-Dispatch — das ist eine Hardware-Read im "Pure Reducer" | Spec 1 §4.3 Z. 487-490 definiert `buildContext(global)` → `ReducerContext(audio = ..., recordingAudioFile = servicesFactory.get().recordingHardware.currentAudioFile())`. Ein Reducer ist per F1+F2-Definition pure (deterministisch, keine I/O). `currentAudioFile()` ist aber ein Hardware-Subsystem-Read. Vorschlag: `recordingAudioFile` muss ein Feld im `RecordingState.Active`/`Preparing`/`Paused` werden (nicht im Context); nur dann ist der Reducer wirklich pure und testbar ohne Hardware-Stub. |
| AI-10 | [CONSISTENCY] | Nice-to-have | Spec 1 §6.4 (`updateOverlayPosition`) verwendet pre-F-10 flache State-Felder | Z. 960-964: `_state.value.copy(overlayPositionPortraitX = x, overlayPositionPortraitY = y)`. Nach F-10 muss das `overlay = state.overlay.copy(positionPortraitX = x, positionPortraitY = y)` sein. Ähnlich §9 (alle Migrations-Tabellen-Zellen "Mutiert in DictateUiState"-Spalte: `audioFocusEnabled` statt `audio.audioFocusEnabledPref`, `lastAudioExists` statt `resend.lastAudioExists`). |
| AI-11 | [CONSISTENCY] | Nice-to-have | Spec 1 §9 Migrations-Tabelle (`startRecording`, `stopRecording`, `pauseRecording`, …) ist pre-F-8 | Die typed Methods (`startRecording(target)`, `pauseRecording()`, `cancelPipeline()` etc.) wurden in F-8 zugunsten `dispatch(Action.RecordingAction.StartRecording(target))` etc. eliminiert. Die Migrations-Tabelle in §9.1 — §9.5 listet aber weiterhin "private interne Methoden" auf dem `PipelineStateManager`. Das ist konsistent mit dem alten Plan-Stand, widerspricht aber F-8. |
| AI-12 | [CONSISTENCY] | Nice-to-have | Spec 1 §13.4.1 enthält weiterhin `state.audioFocusEnabled`, `state.lastAudioExists` (flach) im DRY-Beweis | DRY-Tabelle in §13.4.1 zeigt heute `state.audioFocusEnabled` als künftiges Feld, sollte `state.audio.audioFocusEnabledPref` heißen (F-10). Ähnlich die anderen Beispiele. Die Beweis-Logik bleibt korrekt; nur das Beispiel-Code ist nicht aktualisiert. |
| AI-13 | [INTEGRATION] | Nice-to-have | Der `EditBarController` (Edit-Bar-Audio-Focus-Btn) sollte explizit aus dem Plan-Scope ausgeschlossen oder integriert werden | Spec 2 §8.5 (`resolveAudioFocusIcon`-Helper, F-4) sagt: "Beide Konsumenten lesen denselben StateFlow UND mappen über dieselbe Funktion". Die Edit-Bar ist aber heute eine separate Klasse, die heute `state.audioFocusEnabled` direkt liest. Im Refactor wird sie erweitert um `state.collect { state -> editBarController.refreshAudioFocusIcon(state.audio.audioFocusEnabledPref) }`. Das ist NICHT in den 6 Blöcken explizit aufgeführt. Empfehlung: kleine Block-7-Notiz "Edit-Bar Auf-State-Refactor" oder explizit "out-of-scope" in §1 Hauptplan. |
| AI-14 | [SOLID] | Nice-to-have | `AudioFocusGate` heißt heute schon so (`core/AudioFocusGate.kt`); der Plan benennt das Subsystem `AudioFocusSubsystem` (`ModuleServices`) — Naming-Konflikt | Bestehender Code: `app/src/main/java/net/devemperor/dictate/core/AudioFocusGate.kt`. Spec 1 §4.7 nennt es `AudioFocusSubsystem`. Empfehlung: bestehenden Klassen-Namen behalten oder explizit Rename als Migrations-Schritt vermerken. |

---

### Issue AI-1: Flat-vs-nested State-Field-Drift

- **Category:** [CONSISTENCY]
- **Severity:** Critical
- **Location:**
  - Spec 2 §8.5 (Z. 1124-1213, "Zentrale Predicate- und Resolver-Helfer")
  - Spec 2 §8.6 (Z. 1220-1232, `forKeyboard`)
  - Spec 2 §6 Z. 418, 1308 (`state.animationsEnabled`)
  - Spec 3 §3.1 Z. 64-101 (`OVERLAY_5BUTTON` LayoutMode)
  - Spec 3 §5/§7 (Z. 820-867, 1025-1102) — alle `_state.value.copy(...)`-Snippets
  - Spec 1 §6.4 Z. 960-964 (`updateOverlayPosition`)
  - Spec 1 §9 Migration-Tabellen (alle "Mutiert in DictateUiState"-Spalten)
  - Spec 1 §13.4.1 Beispiel-Code in der DRY-Tabelle
- **Description:** F-10 hat `DictateUiState` von flat (~30 Felder) auf
  hierarchisch (14 Sub-State-Klassen) umgestellt. Spec 1 §3 ist
  post-F-10 (verschachtelt: `state.resend.lastAudioExists`,
  `state.layout.singleRowMode`, etc.). Aber alle Code-Beispiele in Spec 2 +
  Spec 3 + Spec 1 §6.4/§9/§13.4 sind **pre-F-10** (flach:
  `state.lastAudioExists`, `state.singleRowMode`, `state.audioFocusEnabled`).
  Wer Spec 2 §8.5 wörtlich abschreibt: kompiliert nicht.
- **Suggestion:** Konsolidierungs-Pass: globaler Rewrite aller Predicate-/
  Resolver-Snippets in Spec 2 + Spec 3 + Spec 1 §6.4/§9/§13.4 auf die
  hierarchische Struktur. Konkret-Tabelle:

  | Pre-F-10 (heute im Plan) | Post-F-10 (gemäß Spec 1 §3) |
  |---|---|
  | `state.lastAudioExists` | `state.resend.lastAudioExists` |
  | `state.resendEnabled` | `state.resend.resendEnabled` |
  | `state.singleRowMode` | `state.layout.singleRowMode` |
  | `state.smallMode` | `state.layout.smallMode` |
  | `state.animationsEnabled` | `state.layout.animationsEnabled` |
  | `state.audioFocusEnabled` | `state.audio.audioFocusEnabledPref` |
  | `state.userPrefersWidget` | `state.overlay.userPrefersWidget` |
  | `state.overlayOnboardingPending` | `state.overlay.onboardingPending` |
  | `state.overlayPositionPortraitX` | `state.overlay.positionPortraitX` |
  | `state.contentArea` | `state.contentArea` (bleibt flach laut Spec 1 §3) |

  Phase-2-Reviewer prüft **eine** Spec wörtlich gegen Spec 1 §3.

---

### Issue AI-2: Action-API-Drift zwischen hierarchisch (F-8) und flat (Snippets)

- **Category:** [CONSISTENCY]
- **Severity:** Critical
- **Location:**
  - Spec 2 §6 Z. 455, 458, 461 (`Action.NoOp`, `Action.ResendLastAudioLong`)
  - Spec 2 §8 Z. 920, 923, 926, 944, 980-989, 1012-1095 (alle LayoutMode-Definitionen)
  - Spec 2 §8.5 Z. 1172-1187 (`resolveRecordAction`, `resolveTrashAction`, `resolvePauseAction`)
  - Spec 3 §3.1 Z. 67, 77, 87, 95, 99-101
  - Spec 3 §7.3 Z. 1017, 1022, 1051, 1060
- **Description:** Spec 2 §3.3 (post-F-8/F-11) definiert Actions
  hierarchisch: `Action.RecordingAction.StartRecording`,
  `Action.AudioAction.ToggleAudioFocusPref`, etc. Aber die LayoutCatalog-
  und actionResolver-Snippets im selben Dokument (§8) und in Spec 3
  verwenden flache Namen. Konkret falsch:
  - `Action.ToggleAudioFocus` existiert nicht in §3.3 (heißt
    `Action.AudioAction.ToggleAudioFocusPref`)
  - `Action.StartRecording` ist `Action.RecordingAction.StartRecording(target = …)` — `target: InsertionTarget` ist sogar Pflicht-Parameter in §3.3 Z. 130, das ist aus den Snippets verloren gegangen
  - `Action.Backspace` ist `Action.KeyboardInputAction.Backspace`
  - `Action.EnterKey` ist `Action.KeyboardInputAction.EnterKey`
  - `Action.ResendLastAudio` ist `Action.ResendAction.ResendLastAudio`
  - `Action.ResendLastAudioLong` ist `Action.ResendAction.ResendLastAudioLong`
  - `Action.ToggleViewModeWidget` ist `Action.ViewModeAction.ToggleViewModeWidget`
  - `Action.CloseOverlay` ist `Action.ViewModeAction.CloseOverlay`
  - `Action.CancelReprocessStaging` ist `Action.PipelineAction.CancelReprocessStaging`
  - `Action.NoOp` ist OK (in §3.3 als Top-Level definiert)
- **Suggestion:** Globaler Rewrite-Pass über alle Action-Referenzen außerhalb
  Spec 2 §3.3. Phase-2-Reviewer prüft mit `grep -E
  "Action\.(StartRecording|StopRecording|PauseRecording|...)" `, dass diese
  Form **nicht** mehr vorkommt. Zusätzlich: `Action.StartRecording` hat
  einen Pflicht-Parameter (`target: InsertionTarget`) — der muss in den
  Resolver-Snippets ergänzt werden (z.B. `resolveRecordAction` Z. 1172):
  `is RecordingState.Idle -> Action.RecordingAction.StartRecording(InsertionTarget.MainInputConnection)`.

---

### Issue AI-3: Mutations-Pattern-Drift — direkte `_state.value.copy(...)` umgeht Single Dispatch

- **Category:** [CONSISTENCY]
- **Severity:** Critical
- **Location:**
  - Spec 3 §5.3 Z. 846, 854, 862, 867 (Onboarding-State-Mutations)
  - Spec 3 §6.1 Z. 916, 923, 945 (`closeOverlay`, `confirmCloseOverlay`)
  - Spec 3 §7.1 Z. 967-968 (`notifyImeViewVisibilityChanged`)
  - Spec 3 §7.3 Z. 1025-1031, 1064-1068, 1101-1102 (alle 6 Triangle-FSM-Übergänge)
- **Description:** F-8 etabliert `dispatch(action: Action)` als **einzigen**
  Mutations-Eingang. F-11 verlagert die Mutation-Logik in 13 Module-Reducer.
  Aber Spec 3 §5/§6/§7 zeigen Code-Skizzen, die direkt `_state.value =
  _state.value.copy(viewMode = …, smallMode = …)` mutieren — also den
  pre-F-8-Pfad mit dem alten `PipelineStateManager`-Direktzugriff. Das ist
  drei Architektur-Brüche in einem:
  1. Bypass des `dispatch`-Eintrags → Cross-Module-Observer (F-11) feuert nicht
  2. Bypass des `ViewModeModule`-Reducers → Triangle-FSM-Logik dupliziert sich
  3. Atomare Multi-Achsen-Mutation (`copy(viewMode = KEYBOARD, smallMode = true, userPrefersWidget = false)`) ist Modus 3 (atomic Cross-Axis-Update, Spec 1 §15.5) — der "in begründeten Ausnahmen" sein soll, aber hier als Default genutzt wird
- **Suggestion:**
  - WIDGET-Toggle-Pfad: `Action.ViewModeAction.ToggleViewModeWidget` →
    Reducer im `ViewModeModule` setzt `viewMode`. Cross-Module-Observer auf
    `LayoutModule` setzt bei WIDGET→KEYBOARD-Transition `smallMode = true`
    (das ist eine saubere F-11-Action-Cascade).
  - View-Visibility-Lifecycle: bereits in Spec 1 §5 als
    `Action.ViewModeAction.OnImeViewShown/Hidden` gefasst — Spec 3 §7
    sollte das verwenden, nicht eigene `notifyImeViewVisibilityChanged`-Methode.
  - Onboarding: `Action.OverlayAction.MarkOverlayOnboardingShown` (in §3.3
    bereits definiert) statt `_state.value.copy(overlayOnboardingPending = false)`.
  - Permission-Gate-Check: Aktuell in der Code-Skizze direkt im Mutations-
    Code; sauberer ist ein eigenes `Action.OverlayAction.RequestWidgetMode`
    (Reducer prüft `permissions.hasOverlayPermission()`, mutiert entweder
    `viewMode` oder setzt `onboardingPending`).

---

### Issue AI-4: Hauptplan §3.2/§3.3-Skelett pre-F-1/F-8/F-11

- **Category:** [CONSISTENCY]
- **Severity:** Critical
- **Location:** Hauptplan §3.2 (Z. 121-152), §3.3 (Z. 160-194), §4 (Z. 197-208)
- **Description:** Hauptplan ist der Index — wer den Plan zum ersten Mal
  liest, sieht zuerst §3. Aktuelle Probleme:
  - §3.2 ASCII-Diagramm: zeigt `PipelineStateManager (SSOT für ALLE
    State-Achsen)` — der F-1-Vor-Zustand. F-11 hat das in
    `DictateOrchestrator` umbenannt, ergänzt durch 13 Module +
    `DictateUiStateStore` + `PipelinePrefMirror` + `PipelineRecovery`. Davon
    steht nichts.
  - §3.3 LogicalButtonId-Liste: `RECORD, SEND, RESEND, BACKSPACE, TRASH,
    SPACE, PAUSE, ENTER, AUDIO_FOCUS, OVERLAY_INDICATOR, OVERLAY_CLOSE`. Die
    Spec-2-§3.1-Liste lautet aber: `RECORD, RESEND, BACKSPACE, AUDIO_FOCUS,
    WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER, OVERLAY_RECORD, OVERLAY_SEND,
    OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE`. `OVERLAY_INDICATOR`
    existiert nicht; `WIDGET_TOGGLE` + `OVERLAY_RECORD` + `OVERLAY_SEND` +
    `OVERLAY_PAUSE` + `OVERLAY_TRASH` fehlen; `SEND` (top-level) ist obsolet
    (Send ist `RECORD` mit `actionResolver = StopRecordingAndSend`).
  - §3.3 LayoutCatalog-Konstanten-Liste: `OVERLAY_4BUTTON` — laut
    Iter-Log "Resolved 2026-05-08" jetzt `OVERLAY_5BUTTON`.
  - §4 Block-Tabelle Spalte "Kurz-Beschreibung" für Block 1: `resend_btn-
    Visibility zentralisieren; recordButton.text/isEnabled-Hybrid auflösen;
    Quick-Win-Fixes (KSM.refresh in Toggle-Callbacks)` — entspricht NICHT
    dem heutigen F-11-Block-1-Inhalt (13 Module, hierarchischer
    DictateUiState, kotlinx.collections.immutable, Action-Hierarchie). Block
    1 ist heute deutlich größer.
- **Suggestion:** Hauptplan §3.2-§3.3-§4-Konsolidierungs-Pass:
  - §3.2: ASCII-Diagramm um die `DictateOrchestrator`-Composition Root +
    13-Module-Box + Hilfsklassen (Store/PrefMirror/Recovery) erweitern.
  - §3.3: LogicalButtonId-Skelett aus Spec 2 §3.1 übernehmen.
  - §3.3: `OVERLAY_4BUTTON` → `OVERLAY_5BUTTON`.
  - §4: Block-1-Beschreibung aktualisieren auf F-11-Realität (oder Block 1
    in 1a/1b aufteilen — siehe AI-7); Komplexität "klein-mittel" → "groß".

---

### Issue AI-5: `LayoutModule` aggregiert vier disjunkte Achsen (SRP)

- **Category:** [SOLID]
- **Severity:** Important
- **Location:** Spec 1 §15.1 Tabelle Zeile 5, §3 Achsen-Übersicht Zeile 4+5
- **Description:** Spec 1 §15.1 listet `LayoutModule` als Owner für
  `contentArea, layout`-Achsen. Detail:
  - `contentArea: ContentArea` (enum: MAIN_BUTTONS/QWERTZ/EMOJI_PICKER) — View-Selection
  - `layout: LayoutPrefs(singleRowMode, smallMode, animationsEnabled)` — UI-Mode-Prefs

  Diese Achsen haben unterschiedliche Quellen (ContentArea wird vom
  IME-Service via Action-Trigger gesetzt; LayoutPrefs von SharedPreferences
  via PrefMirror), unterschiedliche Konsumenten (ContentArea steuert
  Container-Visibility — `mainButtonsClTyped`/`qwertzContainer`/
  `emojiPickerCl`; LayoutPrefs steuert MotionLayout-State + Slot-Predicates),
  und unterschiedliche Lifecycle-Charakteristika (ContentArea ist transient;
  LayoutPrefs ist persistent). SRP-Smell.

  Zusätzlich strukturelle Inkonsistenz: `state.contentArea` ist Direkt-Feld
  (Enum), `state.layout` ist Sub-State-Container (Data-Class). Wer eine neue
  Layout-Achse hinzufügt, weiß nicht: gehört das ins `LayoutPrefs` oder
  flat?
- **Suggestion:** Zwei Optionen:
  1. **Zwei Module** (`ContentAreaModule`, `LayoutPrefsModule`) — saubere
     SRP, aber 14 Module statt 13 (Phase-2-Modul rückt höher).
  2. **Ein `LayoutState`-Container**, der beides aufnimmt:
     `data class LayoutState(val contentArea: ContentArea, val prefs:
     LayoutPrefs)`. State-Pfad: `state.layoutState.contentArea`,
     `state.layoutState.prefs.singleRowMode`. Fühlt sich konsistenter an
     mit den anderen Sub-State-Containern.

  Empfehlung: Option 2 (kleiner Footprint, klarere Hierarchie). Phase 2
  diskutiert das.

---

### Issue AI-6: Block-1-Aufwand massiv unterschätzt

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Hauptplan §4 Tabelle Zeile 1, §1.3 Iter-Log F-11
- **Description:** Block 1 hat zwei Realitäten in einem Plan:
  - Hauptplan §4 sagt: `State-SSOT-Konsolidierung — resend_btn-Visibility
    zentralisieren; recordButton.text/isEnabled-Hybrid auflösen; Quick-Win-
    Fixes; Komplexität: klein-mittel`
  - Spec 1 (post-F-11) sagt: 13 Module à ~150-300 Zeilen, hierarchischer
    DictateUiState mit 14 Sub-State-Klassen, Action-Sealed-Hierarchie mit
    ~12 Sub-Sealed-Klassen, kotlinx.collections.immutable-Adoption,
    Cross-Module-Observer, ModuleServices-DI-Container.

  Für ein Implement-Long-Plan-Tool (das den Hauptplan als Index nutzt)
  bedeutet das: Block 1 wird mit "klein-mittel" geschätzt, frisst aber
  realistisch 60% des Refactor-Aufwands.
- **Suggestion:** Hauptplan §4 + §10 (falls vorhanden) explizit aktualisieren:
  - Block 1 ist groß. Das ist OK, aber transparent machen.
  - Optional: Block 1 in 1a (Quick-Win im heutigen Code, siehe AI-7) und 1b
    (DictateUiState/DictateOrchestrator/13 Module aufbauen) splitten.

---

### Issue AI-7: F-11 löst Block-1-vor-Block-2-Garantie auf

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Hauptplan §4 Z. 208 ("Reihenfolge: 1 → 2 → 3 → 4 → 5 → 6"),
  Spec 2 §13.5 Gap 5
- **Description:** Hauptplan §4: "Block 1 (State-SSOT) muss vor allem
  anderen kommen, sonst werden neue Bug-Klassen auf einer noch-fragilen
  State-Quelle aufgebaut." Diese Garantie galt mit dem alten Block-1-Inhalt
  (Quick-Win-Konsolidierung im heutigen Code). Mit dem F-11-Block-1-Inhalt
  (DictateOrchestrator + 13 Module) braucht Block 1 aber den
  Foreground-Service als Container — der ist erst Block 2. Resultat: Block
  1 hängt von Block 2 ab.

  Spec 2 §13.5 Gap 5 stellt eine Hilfs-Mitigation in Aussicht: "Block 1
  implementiert die `predResendVisible`-Konsolidierung **bereits** vor dem
  Refactor — eliminiert die 6-Mutator-Race **innerhalb des heutigen
  Codes**, ohne MotionLayout." Das deutet auf zwei Block-1-Phasen hin, die
  aber im Hauptplan nicht so dokumentiert sind.
- **Suggestion:** Hauptplan §4 explizit aufsplitten:
  - **Block 1a — Quick-Win-Konsolidierung (im heutigen Code):**
    `predResendVisible` als Helper-Funktion, alle 6 resend_btn-Mutationen
    auf den Helper umstellen, `recordButton.text/isEnabled`-Hybrid auflösen.
    Macht den Code stabiler, ohne die Modul-Architektur einzuführen.
    Eigenes Subset von Spec 1 §9.4/§9.5.
  - **Block 1b — Modul-System aufbauen (im PipelineService-Container):**
    DictateUiState (hierarchisch), DictateOrchestrator, 13 Module, Action-
    Sealed-Hierarchie. Setzt Block 2 (Service-Skelett) voraus oder läuft
    parallel.

  Alternativ: Block 1b umbenennen in "State-Architektur" und nach Block 2
  einreihen, sodass die Reihenfolge ist: 1a (Quick-Wins) → 2
  (Service-Skelett) → 1b (Modul-Architektur) → 3 (DB) → 4 → 5 → 6.

---

### Issue AI-8: Cross-Module-Cascade — kein Schutz gegen Loop

- **Category:** [SOLID]
- **Severity:** Important
- **Location:** Spec 1 §4.3 Z. 471-477 (`cascadeActions.forEach { dispatch(it) }`),
  §15.5 (Cross-Module-Effect-Modi)
- **Description:** DictateOrchestrator dispatcht in Schritt 6 alle
  Cascade-Actions rekursiv via `cascadeActions.forEach { dispatch(it) }`.
  Wenn:
  - Modul A beobachtet Cross-Module-State-Change und emittiert Action X
  - Modul B's Reducer auf X mutiert seinen State
  - Modul A's Cross-Module-Observer feuert wieder, sieht den neuen B-State,
    emittiert wieder X'
  - … StackOverflowError

  Spec 1 §15.5 erwähnt drei Cross-Module-Modi, ohne Loop-Sicherheit zu
  diskutieren. Real-World: AudioFocus-Loss → Recording.Pause → State-Change
  → Audio-Modul-Observer reagiert wieder?
- **Suggestion:** Eine der zwei Mitigations:
  1. **Cascade-Tiefe-Counter:** `dispatch(action, depth = 0)`, ab depth ≥ 8
     Logger.error + Cascade abbrechen. Pragmatisch.
  2. **Cycle-Detection:** Track Set<KClass<Action>> während eines Top-Level-
     dispatch; Re-Emission derselben Action in derselben Cascade-Tiefe ist
     Bug.

  Empfehlung: Cascade-Tiefe-Counter (lower-overhead, debug-freundlich) +
  Logger-Warning + DEBUG-Build-Assertion. In Spec 1 §15.5 ergänzen.

---

### Issue AI-9: Reducer-Context-Hardware-Read verletzt Pure-Function-Invariante

- **Category:** [DEPENDENCY]
- **Severity:** Important
- **Location:** Spec 1 §4.3 Z. 487-490 (`buildContext`)
- **Description:** `buildContext(global)` ruft
  `servicesFactory.get().recordingHardware.currentAudioFile()` synchron auf
  und schreibt das Ergebnis in den `ReducerContext`. Damit ist der Reducer
  technisch **nicht pure**, weil `currentAudioFile()` ein Subsystem-Read
  ist (sein Rückgabewert kann sich zwischen zwei Reducer-Calls ändern,
  ohne dass eine Action ihn auslöst).

  Konsequenz: Reducer-Tests müssen `ModuleServicesFactory` mocken (auch für
  pure-State-Tests), und der "deterministische" Reducer ist es nur unter
  Voraussetzung, dass das Subsystem zwischen Reducer-Aufrufen nicht
  mutiert — keine Compile-Zeit-Garantie.
- **Suggestion:**
  - `recordingAudioFile` als Feld in `RecordingState.Active /
    Preparing / Paused` aufnehmen (Action `MediaRecorderReady` füllt es).
  - `ReducerContext` schrumpft auf nur noch State-Snapshots (`audio:
    AudioState`, ggf. `now: Long`). Damit ist der Reducer wirklich pure.
  - Hardware-Reads bleiben — wie F-11 vorsieht — in `runEffect()`, das per
    Definition nicht-pure ist.

---

### Issues AI-10 bis AI-14

(Nice-to-have / Important, oben in der Übersichtstabelle adressiert. Keine
weiteren Detail-Sektionen nötig — die Suggestions sind explizit.)

---

## Section Proposal (heading-based, Plan-Review)

Phase 2 sollte den Plan in **5 Sektionen** aufteilen — nach Architektur-
Schicht und Komplexitäts-Cluster, nicht nach Spec-File. Die Inkonsistenz-
Findings (AI-1 bis AI-4) verlangen, dass jede Sektion **gegen Spec 1 §3
(SSoT für DictateUiState) + Spec 2 §3.3 (SSoT für Action-Hierarchie)**
referenz-prüft.

```
[1] Section: "State-Modell + Modul-System (DictateUiState + DictateOrchestrator + 13 Module)"
    Heading:
      - Spec 1 §3 (Datenmodell DictateUiState, hierarchisch)
      - Spec 1 §4 (DictateOrchestrator + Modular Plugin-Pattern)
      - Spec 1 §5 (Local-Binder API, Single Dispatch)
      - Spec 1 §15 (Modul-Inventar, alle 13 Module)
    Layer: Domain (State-Definitionen, pure Reducer)
    Justification: Das ist die architektonische Kernschicht (post-F-8/F-10/F-11).
        Jede andere Sektion referenziert diese Bausteine. Phase-2-Reviewer prüft
        hier insbesondere: Modul-Pattern-Vollständigkeit (alle 13 Module gegen
        §15.1-Tabelle, jedes mit Reducer + EffectHandler + optionalem
        Cross-Module-Observer), Cascade-Loop-Sicherheit (AI-8),
        Pure-Reducer-Garantie (AI-9), `LayoutModule`-SRP (AI-5).
    Contains:
      - Hierarchische DictateUiState mit 14 Sub-State-Klassen
      - DictateModule-Interface + ModuleId + TransitionResult
      - DictateOrchestrator + Cross-Module-Cascade-Mechanik
      - Action-Sealed-Hierarchie (12 Sub-Sealed-Klassen)
      - DictateModuleRegistry + ModuleServices + ModuleServicesFactory
      - PipelinePrefMirror + PipelineRecovery + DictateUiStateStore (F-1-Hilfsklassen)
      - 13 Modul-Implementierungen (Recording, Pipeline, Audio, ViewMode, …)

[2] Section: "Service-Layer + Persistence + Lifecycle"
    Heading:
      - Spec 1 §6 (Persistence-Erweiterung M3→M4)
      - Spec 1 §7 (Lifecycle Foreground Service)
      - Spec 1 §8 (IME-Service-Integration)
      - Spec 1 §9 (Migration vorhandener Klassen)
      - Spec 1 §11 (Research-TODOs / Implementations-Details)
    Layer: Application (Service-Lifecycle) + Infrastructure (Room/Notification)
    Justification: Die Service-Außenwelt (FGS, Notification-Coordinator,
        ActionRouter, DB-Migration, Bound-Connection-Setup, IME-Service-Slim-Down).
        Logisch von [1] getrennt: hier geht es um Container + Lifecycle, nicht
        um State-Logik. Phase-2-Reviewer prüft konkret: Action-Methods in §9
        gegen Action-Hierarchie aus [1] (AI-11), `state.X`-Pfade in §6.4 + §9
        (AI-10), AndroidManifest-Korrektheit (FGS-Type, POST_NOTIFICATIONS).
    Contains:
      - DictatePipelineService-Skelett, FGS-Lifecycle, stopSelf-Bedingung
      - PipelineNotificationCoordinator + PipelineActionRouter
      - DB-Schema-Migration M3→M4 (inserted_at)
      - Recovery-Read + Pref-Mirror-Init
      - Migrations-Tabellen für RecordingStateController/KeyboardUiController/
        KeyboardStateManager
      - Bound-Service-Edge-Cases (Race IME-onCreate vs. Service-onCreate)

[3] Section: "Keyboard-Layout-Renderer (KeyboardLayoutManager + LayoutCatalog + ImeViewBackend + MotionLayout)"
    Heading:
      - Spec 2 §3 (LogicalButtonId, ButtonSlot, Action-Hierarchie)
      - Spec 2 §4 + §5 + §5.1 (KeyboardLayoutManager + RenderBackend + applySlotToView)
      - Spec 2 §6 (ImeViewBackend mit MotionLayout)
      - Spec 2 §7 (MotionScene-XML)
      - Spec 2 §8 (LayoutCatalog: 5 KEYBOARD-Modi + Predicates + Resolver)
      - Spec 2 §9 (Migration: KeyboardLayoutModeController/MainButtonsController/
                   KeyboardStateManager/RecordingUiController)
      - Spec 2 §11 (Research-TODOs: PulseLayout-Spike, BorderGlow-Migration,
                    Click-Listener-Lifecycle, Special-Touch-Handler)
    Layer: Application (Layout-Selection) + Frontend (View-Rendering)
    Justification: Keyboard-Layout-Engine als Einheit. Phase-2-Reviewer prüft
        wörtlich: alle `state.X`-Lesungen in §8.5/§8.6 gegen Spec 1 §3 (AI-1),
        alle `Action.Y`-Konstruktionen gegen Spec 2 §3.3 (AI-2),
        Visibility-Matrix gegen `predTrashVisible/predPauseVisible/predResendVisible`
        (§8.7), MotionScene-Vollständigkeit (5 Sub-Modi vs. 4 LayoutMode-Konstanten +
        REPROCESS_STAGING).
    Contains:
      - LogicalButtonId (14 Werte) + ButtonSlot + RowDescriptor + LayoutMode
      - KeyboardLayoutManager mit `forKeyboard(state)`-Selektor
      - applySlotToView (F-7 / DRY-Helper)
      - ImeViewBackend mit wireStaticHandlers + Special-Touch-Handlers
      - MotionScene-XML (5 KEYBOARD-States)
      - LayoutCatalog mit 5 LayoutModes + Predicate-/Resolver-Helpers
      - Migration aller 5 alten Klassen (Code-Pointer)
      - PulseLayout-Spike + Inflation-Cost-Messung
      - RecordingAnimationController + BorderGlow-Migration

[4] Section: "Floating-Overlay (OverlayBackend + Window-Lifecycle + Permission + Triangle-FSM)"
    Heading:
      - Spec 3 §3 (OVERLAY_5BUTTON-LayoutMode + XML)
      - Spec 3 §4 (OverlayBackend + LayoutParamsFactory + DragHandler + PositionMapper)
      - Spec 3 §5 (Permission-Onboarding-Flow)
      - Spec 3 §6 (Schließen-Button-Differential)
      - Spec 3 §7 (Mode-Transitionen / 6 Triangle-FSM-Übergänge)
      - Spec 3 §8 + §9 (Touch-Routing + Notification-Fallback)
      - Spec 3 §11 (Drag-Detail-Research, OPEN-3-Antworten)
    Layer: Frontend (Window-Rendering) + Application (Permission-Gate, FSM-Übergänge)
    Justification: Overlay-Subsystem als kohärente Einheit. Phase-2-Reviewer
        prüft hier insbesondere: alle `_state.value.copy(...)`-Mutationen gegen
        F-8 Single Dispatch (AI-3), Triangle-FSM-Übergänge T1-T6 gegen
        ViewModeModule-Reducer (AI-3), `state.X`-Lesungen gegen Spec 1 §3
        (AI-1), Drag-Edge-Cases (View-Size-0 / GAP-7 / F-6-Mitigation),
        Permission-Edge-Cases (Revoke / GAP-6).
    Contains:
      - OVERLAY_5BUTTON-LayoutMode + Overlay-XML (5 Buttons)
      - OverlayBackend + AndroidOverlayWindow-Wrapper
      - DefaultOverlayDragHandler + DefaultOverlayPositionMapper (OPEN-3)
      - OverlayLayoutParamsFactory + Flag-Tabelle
      - OverlayPermissionGate + Onboarding-UI im IME-View
      - 6 Triangle-FSM-Übergänge T1-T6 (KEYBOARD↔WIDGET↔HOVER)
      - Schließen-Button-Differential (WIDGET → KEYBOARD+SmallMode vs. HOVER → dismiss)
      - Notification-Fallback bei Permission-Verweigerung

[5] Section: "Cross-Cutting Konsistenz + Architektur-Integrität (Hauptplan + alle §13-Verifikationen)"
    Heading:
      - Hauptplan §1-§9 (Kontext, Ziele, Architektur-Vision, Building Blocks, Specs, Risiken, Open Questions, Iteration-Log)
      - Spec 1 §13 (Vollständigkeits-Verifikation: Visibility-Audit, State-Mutation-Audit, SOLID, DRY, Gaps)
      - Spec 2 §13 (Visibility-Audit, Click-Listener-Audit, SOLID, DRY, Gaps)
      - Spec 3 §13 (SSOT, SOLID, DRY, Cross-Spec-Konsistenz, Gaps)
      - Spec 1 §10 + Spec 2 §10 + Spec 3 §10 (Acceptance-Kriterien)
      - Test-Strategien (Spec 1 §11, Spec 2 §14, Spec 3 §14)
    Layer: Cross-Cutting / Meta
    Justification: Diese Sektion ist die **Konsistenz-Verifikations-Sektion**.
        Sie ist absichtlich quer zu den Layer-Sektionen [1]-[4]: Phase-2-Reviewer
        prüft hier, ob die §13-Audits (DRY/SSOT/SOLID-Beweise) noch stimmen,
        nachdem [1]-[4] mit konkretem Code-Vorschlägen referenz-geprüft wurden.
        Speziell: stimmt das Hauptplan-Architektur-Skelett §3.2/§3.3 mit
        F-11-Realität überein (AI-4)? Stimmt Block-Reihenfolge §4 noch (AI-7)?
        Sind Acceptance-Kriterien gegen die F-8/F-10/F-11-Architektur formuliert
        oder noch gegen die alte? Sind die §13-DRY-Beispiele post-F-10
        (AI-12)? Sind alle in §13.5-Gaps referenzierten Mitigations tatsächlich
        in [1]-[4] implementiert (z.B. F-6-Mitigation für GAP-7, F-4-Mitigation
        für resolveAudioFocusIcon, F-7-Mitigation für applySlotToView)?
    Contains:
      - Hauptplan-Skelett-Konsistenz (AI-4)
      - Block-Reihenfolge + Block-1-Aufwand-Audit (AI-6, AI-7)
      - Iteration-Log F-1..F-11 — vollständig propagiert?
      - §13.5-Gaps in jeder Spec — alle Mitigations referenziert?
      - Acceptance-Kriterien-Konsistenz mit Single-Dispatch-Architektur
      - Test-Strategie (Unit-Tests pro Modul, Integration-Tests, Spike-Validierungen)
      - Open Questions (Auto-Discovery, Atomic Cross-Axis-Hook,
        FOREGROUND_SERVICE_TYPE_MICROPHONE-Verifikation, Notification-Throttling)
      - Edit-Bar-Audio-Focus-Btn-Migration (AI-13)
```

**Begründung des 5er-Splits:**

- **Anzahl 5** ≤ 6 (User-Anforderung). Eine sechste Sektion ("Test-Strategie")
  habe ich bewusst in [5] aufgenommen statt eigenständig — Tests sind in den
  Specs selbst sehr verstreut (§11 in Spec 1, §14 in Spec 2 + Spec 3) und
  Test-Findings hängen unmittelbar mit Architektur-Findings zusammen.
- **Logische Schichtung:** [1] State+Module = Domain; [2] Service+Persistence+
  Migration = Application/Infrastructure; [3] KEYBOARD-Renderer = UI; [4]
  Overlay-Renderer = UI; [5] = Cross-Cutting. Recommended-Order
  (Shared Types → Domain → Application → Infrastructure → API → Frontend) ist
  hier zu Domain → Application+Infra → Frontend(Keyboard) → Frontend(Overlay)
  → Cross-Cutting kollabiert.
- **Spec-Trennung respektiert, aber nicht erzwungen:** [3] und [4] folgen den
  Spec-Grenzen Spec 2/Spec 3, weil das die Frontend-Aufteilung KEYBOARD vs.
  WIDGET/HOVER ist. [1]+[2] zerlegen Spec 1 in zwei (Domain vs. Service-
  Container). [5] wandelt Spec 1 §13/Spec 2 §13/Spec 3 §13 in eine
  Cross-Cutting-Audit-Sektion.
- **Cross-Spec-Konsistenz priorisiert:** Phase-2-Reviewer in [3] und [4]
  prüfen explizit gegen [1]-Definitionen (Single SoT für State-Pfade und
  Action-Hierarchie). Das adressiert AI-1 + AI-2 + AI-3 systemisch.
- **Cross-Cutting-Sektion [5]** ist nicht nur Sammelstelle, sondern hat
  eigene Audit-Aufgabe: prüft, ob alle §13-Verifikationen (in den Specs
  selbst aufgestellt) nach den [1]-[4]-Korrekturen noch korrekt sind.

---

## Zusammenfassung

Die Architektur ist **konzeptionell solide** — F-11 hat ein nachweislich gutes
Plugin-System etabliert, F-1/F-3/F-4/F-7 haben sauber God-Klassen aufgespalten,
F-2 etabliert DIP, F-9 begründet Library-Adoption sorgfältig. Die Schwachstellen
sind **textuelle Drift zwischen den Iterationen** (AI-1, AI-2, AI-3, AI-4),
nicht architektonische Fehler.

**4 Critical-Findings** (AI-1, AI-2, AI-3, AI-4) sind alle vom selben Typ:
F-8/F-10/F-11 wurden in Spec 1 §3-§5 + §15 vollständig propagiert, aber
**nicht** in Spec 1 §6.4/§9/§13.4, Spec 2 §6/§8/§9, Spec 3 §3/§5/§6/§7 und
Hauptplan §3.2/§3.3/§4. Eine **Konsolidierungs-Welle** vor der Implementierung
(globaler Rewrite-Pass über Specs) ist nötig — sonst kompilieren die wörtlich
abgeschriebenen Code-Beispiele nicht.

**3 Important-Findings** (AI-5, AI-6, AI-7, AI-8, AI-9) sind echte
Architektur-Fragen: SRP-Smell `LayoutModule`, Block-1-Aufwand-Drift,
Block-Reihenfolge-Bruch durch F-11, Cross-Module-Cascade-Loop-Schutz,
Reducer-Pure-Function-Verletzung. Die brauchen User-Entscheidungen.

**5 Nice-to-have-Findings** (AI-10, AI-11, AI-12, AI-13, AI-14) sind
sekundäre Konsistenz-Probleme; Phase 2 deckt sie automatisch ab.

Der Section-Vorschlag (5 Sektionen) optimiert für **Cross-Spec-Konsistenz-
Audits**: jede UI-Sektion ([3], [4]) prüft wörtlich gegen die State+Action-
Single-Source-of-Truth in [1]; [5] schließt die Verifikations-Schleife.
