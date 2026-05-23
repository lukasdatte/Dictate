# Phase B — S-6 Keyboard-Layout-Renderer: KSM + RecordingUiController + KeyboardLayoutModeController → KeyboardLayoutManager + LayoutCatalog + MotionLayout Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-6 (Subsystem #8 von 9)
**Plan-Version vor Edits:** Stand nach S-9-Apply-Pass (Commit `e418b87`, S-9-Report `phase-b-s9-suppress-bit-lifecycle.md`)

---

## Summary

S-6 ist die UI-Layer für KEYBOARD-Modus und der vorletzte Implementations-Block. Hier eliminiert
der Refactor die 3 Original-Bug-Klassen (asymmetrisches Re-Parenting, resend_btn-Race,
recordButton-Hybrid) durch Predicates + Resolver im LayoutCatalog, MotionLayout statt
ConstraintSet-Manipulation, und ein einheitliches ImeViewBackend. Plus MotionLayout ist das
einzige Subsystem mit echtem Spike-Risiko (PulseLayout-Animation könnte brechen).

**Der dominante Befund:** S-7 hat die `ButtonSlot.actionResolver`-Signatur auf 2-arg
`(state, services) -> Action?` umgestellt, ABER die ~40 Consumer-Sites in §8.1–§8.4 nicht
durchgereicht. Klassischer "Refactor-only-the-API-not-the-callers"-Fall. Plus zwei
Begleit-Bugs: `SendStaging`/`CancelReprocessStaging` als Singleton-Aufruf (sind aber
`data class`) und WIDGET_TOGGLE-Slot komplett fehlend in allen LayoutModes (Spec 3 OPEN-2
wäre stumm broken). Drei Compile-Blocker, die ohne S-6-Audit zu einer konfusen Block-5-Build-
Schleife geführt hätten.

**Drei Critical-Bugs:**

1. **S-7-F-1-Folgepfad nicht vollständig**: ALLE 5 LayoutCatalog-Definitionen (§8.1 TWO_ROW,
   §8.2 SINGLE_ROW, §8.3 SEND_MODE-Varianten, §8.4 REPROCESS_STAGING) hatten Slot-
   `actionResolver`-Lambdas in der alten 0/1-arg-Form. Plus §8.5 `resolveRecordActionPipeline`,
   `resolveTrashAction`, `resolvePauseAction` waren noch 1-arg — als Methodenreferenzen
   `::resolveTrashAction` Compile-Error (Kotlin-Methodenreferenz-Typ matched die Funktions-
   Signatur exakt).

2. **`SendStaging` / `CancelReprocessStaging` als Singleton-Aufruf statt data-class**: §8.4
   REPROCESS_STAGING-Slot rief `{ Action.PipelineAction.SendStaging }` als Object-Referenz.
   Aber Spec 2 §3.3 Z. 205–206 definiert beide als `data class …(val sessionId: String)`
   (post-Phase-1-1.0.5 + R.15). Compile-Error: "Classifier does not have a companion object".

3. **WIDGET_TOGGLE-Slot komplett fehlend in allen 5 KEYBOARD-LayoutModes**: `LogicalButtonId.
   WIDGET_TOGGLE` ist in §3.1 enum + §6 `buttonViews`-Map registriert (Spec 3 OPEN-2 /
   Phase-1-1.0.2-Followup), aber KEINER der LayoutModes definiert einen Slot. Render-Loop
   würde den View nie aktualisieren — Default-XML-Visibility `gone` bleibt sticky. Spec 3
   OPEN-2 wäre stumm broken.

**Vier Important-Findings:**

4. **`predResendVisible`-Cooldown nicht im `enabledResolver` umgesetzt**: §13.5 Gap 2
   dokumentiert die Resolution, aber die RESEND-Slot-Definitionen in §8.1 + §8.2 hatten
   WEDER `enabledResolver` NOCH `alphaResolver` für `resendCooldown`. Test §14.2 UI-Test 9
   würde rot fehlschlagen.

5. **Block-5-Acceptance ohne File-Deletion-Verifikation**: §10 hat KEINE explizite Klausel
   "`KeyboardLayoutModeController.kt` ist gelöscht; grep findet keinen Caller mehr". §9.1
   sagt "entfällt vollständig" + §11.8 Block 5d sagt "Cleanup", aber kein verifizierbares
   Akzeptanz-Kriterium.

6. **R.13 Strict-Mode-Logger-Konkretisierung fehlt**: Acceptance "R.13 Strict-Mode-Logging
   während 5c: `VisibilityWrite from $caller`-Log" — aber **wie** der Logger implementiert
   wird (welcher API-Hook, welche Lebensdauer) nicht spezifiziert.

7. **`onShowResend()` LocalBinder-Drift gegen F-8**: §9.6 Tabelle Z. 1640 sagt
   `pipelineService.markLastAudioExists(true)` als Ziel — aber F-8 verbietet typed Forwarder
   am LocalBinder; muss ein `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(...))`-
   Dispatch werden.

**Zwei Minor-Findings:**

8. **F-4 AudioFocus-Icon-SSoT nicht in den Slots referenziert**: §8.5 definiert
   `resolveAudioFocusIcon(enabled)` als geteilten Helper, aber die AUDIO_FOCUS-Slot-
   Definitionen nutzen ihn nicht — sie haben gar keinen `iconResolver`. Drift-Risiko: Main-
   Button-Area Audio-Focus-Icon bleibt stumm auf dem XML-Default.

9. **`predTrashVisible == predPauseVisible` Code-Duplikation**: §8.5 zwei Top-Level-Funktionen
   mit identischem Body. **Bewusst NICHT konsolidiert** — Begründung im Audit-Trail.

**Befund:** **9 Findings (3 Critical, 4 Important, 2 Minor) — ~14 Plan-Edit-Operationen in
2 Dateien (Spec 2: 13, Hauptplan: 1).**

**Hauptlücke:** Die Resolver-Signatur-Inkonsistenz (F-1) ist ein "Refactor-only-the-API-not-
the-callers"-Fall. S-7 hat den Schnittstellen-Typ + zentrale Helper migriert, aber die
40 Lambda-Consumer-Sites in §8.1–§8.4 nicht. Ohne S-6-Audit wäre der Block-5-Implementer in
eine 30-60-Min-Debug-Schleife geraten — und mit den Begleit-Findings (data-class-Singleton-
Aufruf-Bugs + komplett fehlender WIDGET_TOGGLE-Slot) wäre Spec 3 OPEN-2 stumm broken in den
Build gelandet, niemand hätte gemerkt, dass der `widget_toggle_btn` nie sichtbar wird.

---

## Findings + Applied Fixes

### F-1 S-7-F-1-Folgepfad nicht vollständig — alle Slot-Lambdas in §8.1–§8.4 sind alt

- **Severity:** Critical
- **Prüf-Achse:** 1 (ButtonSlot.actionResolver 2-arg-Migration, S-7-F-1-Folgepfad)
- **Was:** S-7 hat in `phase-b-s7-audio-file-management.md` F-1 den `ButtonSlot.actionResolver`-
  Typ auf `(DictateUiState, ModuleServices) -> Action?` (2-arg) umgestellt, den
  `ImeViewBackend`-Konstruktor um `services: ModuleServices` erweitert, und die zentrale
  `resolveRecordAction` auf 2-arg migriert. ABER die 5 LayoutCatalog-Definitionen in §8.1
  KEYBOARD_TWO_ROW, §8.2 KEYBOARD_SINGLE_ROW, §8.3 KEYBOARD_TWO_ROW_SEND_MODE /
  KEYBOARD_SINGLE_ROW_SEND_MODE, §8.4 KEYBOARD_REPROCESS_STAGING hatten alle Slot-
  `actionResolver`-Lambdas in der alten Form:
    - `actionResolver = { Action.X }` — 0-arg-Lambda, Kotlin inferiert `() -> Action`. Kein Match.
    - `actionResolver = { state -> ... }` — 1-arg-Lambda, Kotlin inferiert `(DictateUiState) -> Action`. Kein Match.
    - `actionResolver = { null }` — 0-arg-Lambda. Kein Match.

  Methodenreferenzen wie `::resolveRecordAction` (zentrale Helper, S-7 schon auf 2-arg) sind ok.
  Aber **andere** Methodenreferenzen — `::resolveRecordActionPipeline`, `::resolveTrashAction`,
  `::resolvePauseAction` — sind in §8.5 noch 1-arg definiert. Kotlin-Methodenreferenz-Typ
  matched die Funktions-Signatur exakt: `KFunction1<DictateUiState, Action?>` ist nicht
  zuweisbar zu `(DictateUiState, ModuleServices) -> Action?`. Compile-Error in 3 weiteren
  Slots.

  Insgesamt ~40 Lambda-Sites + 3 Methodenreferenz-Sites — alle würden beim ersten
  `./gradlew assembleDebug` rot fehlschlagen.
- **Konsequenz:** Block-5-Build-Blocker für alle KEYBOARD-LayoutModes. Bug-Klasse identisch
  zu S-7 F-1 + F-2, aber an ~40 Stellen statt 2. Compile-Fehlermeldung:
  > Type mismatch: inferred type is `(DictateUiState) -> Action.KeyboardInputAction.Backspace`
  > but `(DictateUiState, ModuleServices) -> Action?` was expected

  Implementer-Reflex: entweder den `services`-Parameter "rausziehen" (zerstört R.2 Pre-Dispatch-
  Allokation für `resolveRecordAction`) ODER alle ~40 Stellen manuell migrieren — was ohne
  Plan-Konvention zu Inkonsistenzen führt (welcher Argument-Name: `_, _` oder `state, services`?).
- **Fix angewandt:**
  - **Spec 2 §8.1 KEYBOARD_TWO_ROW:** alle 8 Slot-Lambdas auf 2-arg-Form (`{ _, _ -> Action.X }`)
    migriert.
  - **Spec 2 §8.2 KEYBOARD_SINGLE_ROW:** alle 9 Slot-Lambdas migriert.
  - **Spec 2 §8.3 KEYBOARD_TWO_ROW_SEND_MODE + KEYBOARD_SINGLE_ROW_SEND_MODE:** alle
    Slot-Lambdas migriert.
  - **Spec 2 §8.4 KEYBOARD_REPROCESS_STAGING:** alle Slot-Lambdas migriert.
  - **Spec 2 §8.5:** `resolveRecordActionPipeline`, `resolveTrashAction`, `resolvePauseAction`
    auf 2-arg `(state: DictateUiState, services: ModuleServices)` mit Migrations-Hinweis-Block
    umgestellt (services im Body ignoriert — Pure-Function-Garantie bleibt).
  - **Spec 2 §10:** neue Acceptance-Klausel "ButtonSlot.actionResolver 2-arg-Signatur konsistent
    — Build-Smoke verifiziert Compile-Time-Garantie".

### F-2 `SendStaging` / `CancelReprocessStaging` als Singleton-Aufruf statt data-class-Konstruktor

- **Severity:** Critical
- **Prüf-Achse:** 1 (Action-Hierarchie-Konsistenz, S-3-Folgepfad)
- **Was:** §8.4 KEYBOARD_REPROCESS_STAGING-Slot-Definitionen:
  ```kotlin
  ButtonSlot(LogicalButtonId.RECORD, FillRemaining, ...,
      actionResolver = { Action.PipelineAction.SendStaging }),    // VOR FIX
  ButtonSlot(LogicalButtonId.TRASH, WrapContent, ...,
      actionResolver = { Action.PipelineAction.CancelReprocessStaging }),    // VOR FIX
  ```
  Aber Spec 2 §3.3 Z. 205–206:
  ```kotlin
  data class SendStaging(val sessionId: String) : PipelineAction()
  data class CancelReprocessStaging(val sessionId: String) : PipelineAction()
  ```
  Compile-Error: "Classifier 'SendStaging' does not have a companion object, and thus must
  be initialized here". Plus: ohne `sessionId` wäre die Pipeline nicht in der Lage, die
  korrekte Reprocess-Staging-Session zu adressieren — semantischer Bug ON TOP des Compile-
  Bugs.
- **Konsequenz:** Block-5-Build-Blocker für REPROCESS_STAGING-Mode. Implementer-Reflex:
  entweder die Actions als `object` redeklarieren (Plan-Drift gegen S-3 + R.15 sessionId-
  String-Konvention) ODER ad-hoc `sessionId` aus dem State holen ohne Plan-Konvention.
- **Fix angewandt:**
  - **Spec 2 §8.4 RECORD-Slot:** `actionResolver = { state, _ -> (state.pipeline as?
    PipelineUiState.ReprocessStaging)?.let { Action.PipelineAction.SendStaging(it.sessionId) } }`.
    Safe-cast-Pattern analog zu §8.5 `resolveTrashAction` (Z. 1430).
  - **Spec 2 §8.4 TRASH-Slot:** analog für `CancelReprocessStaging(it.sessionId)`.

### F-3 WIDGET_TOGGLE-Slot komplett fehlend in allen 5 KEYBOARD-LayoutModes

- **Severity:** Critical
- **Prüf-Achse:** 2 (LayoutCatalog vs. WIDGET_TOGGLE-Slot-Vollständigkeit)
- **Was:** `LogicalButtonId.WIDGET_TOGGLE` ist verankert in:
  - **Spec 2 §3.1 enum LogicalButtonId** (Z. 57): `WIDGET_TOGGLE, // (Spec 3 GAP-4): Toggle-Btn KEYBOARD → WIDGET`.
  - **Spec 2 §6 `buttonViews`-Map** (Z. 542): `LogicalButtonId.WIDGET_TOGGLE to rootView.findViewById(R.id.widget_toggle_btn)`.
  - **Spec 2 §13.1 Visibility-Mutation-Audit Zeile 22b**: "NEU — LayoutCatalog `WIDGET_TOGGLE`-Slot, Predicate `{ state.viewMode == ViewMode.KEYBOARD }`".
  - **Spec 2 §13.2 Click-Listener-Audit**: "WIDGET_TOGGLE — NEU (Spec 3 OPEN-2)".

  ABER: KEINER der 5 LayoutModes in §8.1–§8.4 hatte einen `ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, ...)`
  definiert. Der Render-Loop in §6 (Z. 601):
  ```kotlin
  mode.rows.flatMap { it.slots }.forEach { slot -> ... applySlotToView(slot, view, state, ctx) }
  ```
  iteriert nur über definierte Slots — der `widget_toggle_btn`-View bleibt nie aktualisiert.
  Default-XML-Visibility `gone` bleibt sticky.

  Der Silent-Skip-Schutz in §6 (Z. 603 `error("No view registered for ${slot.logicalId}")`)
  greift in die ANDERE Richtung: er fängt einen Slot ohne View. Ein View OHNE Slot ist nicht
  geschützt — der View bleibt einfach in seinem Initial-XML-State.
- **Konsequenz:** Spec 3 OPEN-2 / Phase-1-1.0.2 wäre stumm broken: der widget_toggle_btn
  würde nie sichtbar werden, kein State-Pfad könnte den WIDGET-Mode aktivieren. Nutzer-
  Feature "Toggle KEYBOARD ↔ WIDGET" wäre tot. Niemand bemerkt es bis Spec 3 OverlayBackend
  in Block 6 implementiert wird und der QA fragt: "wo ist der Toggle-Button?". Auch §13.5.b
  Cross-Spec-Patches-Pending erwähnt die offene Slot-Position, aber nicht die Tatsache,
  dass der Slot **gar nicht** existiert.
- **Fix angewandt:**
  - **Spec 2 §8.1 TWO_ROW** + **§8.2 SINGLE_ROW:** WIDGET_TOGGLE-Slot mit
    `visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD }` +
    `actionResolver = { _, _ -> Action.ViewModeAction.ToggleViewModeWidget }` eingefügt.
    Position: am Ende der action_row (TWO_ROW) bzw. Single-Row-Chain.
  - **Spec 2 §8.3 SEND_MODE-Varianten** + **§8.4 REPROCESS_STAGING:** WIDGET_TOGGLE-Slot mit
    `visibilityPredicate = { false }` (kein Mode-Wechsel mid-Pipeline / mid-Staging) +
    `actionResolver = { _, _ -> null }` eingefügt. Begründung dokumentiert (User-Decision:
    WIDGET-Toggle deaktiviert während Send-Mode, damit Sender Pipeline nicht versehentlich
    durch Mode-Toggle abreißt).
  - **Spec 2 §10:** neue Acceptance-Klausel "widget_toggle_btn wird in TWO_ROW + SINGLE_ROW
    gerendert (Predicate `viewMode == KEYBOARD`); in SEND_MODE + REPROCESS_STAGING GONE".
  - **Spec 2 §13.5.b:** Cross-Spec-Patches-Pending-Block aktualisiert — WIDGET_TOGGLE-Slot
    ist jetzt explizit in allen 5 LayoutModes verankert.

### F-4 `predResendVisible`-Cooldown nicht im `enabledResolver` der RESEND-Slots umgesetzt

- **Severity:** Important
- **Prüf-Achse:** 6 (`predResendVisible` ohne `resendCooldown` — Issue 3.0.9)
- **Was:** Spec 2 §13.5 Gap 2 (RESOLVED) dokumentiert: "Cooldown landet ausschließlich im
  `enabledResolver` des RESEND-Slots (disabled+alpha 0.4f, siehe LayoutMode-Slot-Definitionen
  in §8.1/§8.2)". Plus §8.5 `predResendVisible`-KDoc Z. 1327:
  > WICHTIG (FIX 2.0.12): `resendCooldown` ist NICHT Teil dieser Predicate. Cooldown landet
  > ausschließlich im `enabledResolver` des RESEND-Slots (disabled+alpha 0.4f, siehe
  > LayoutMode-Slot-Definitionen in §8.1/§8.2).

  ABER die RESEND-Slot-Definitionen in §8.1 + §8.2:
  ```kotlin
  ButtonSlot(LogicalButtonId.RESEND, WrapContent,
      visibilityPredicate = ::predResendVisible,
      actionResolver = { Action.ResendAction.ResendLastAudio }),   // VOR FIX
  ```
  Weder `enabledResolver = { !it.resend.resendCooldown }` noch
  `alphaResolver = { if (it.resend.resendCooldown) 0.4f else 1f }` — das Cooldown-Verhalten
  ist im Spec-Versprechen verankert, aber nirgends im Code-Snippet umgesetzt.
- **Konsequenz:** Plan-§1.1 Bug-Symptom #3b ("Resend verschwindet beim Toggle") wäre durch
  das Predicate-Pattern strukturell eliminiert. Aber das Cooldown-Verhalten (500ms disabled+
  alpha 0.4 nach Klick — bekannt aus `MainButtonsController.kt:331-333`) wäre stumm
  verschwunden. Test §14.2 UI-Test 9 (Cooldown-Visibility-Frame-Check: "nach Click bleibt
  visibility=VISIBLE, enabled=false, alpha=0.4") würde rot fehlschlagen. Hat User-Impact:
  Doppelklicks auf Resend werden NICHT verhindert → 500ms-Race im Service → potenzielle
  Pipeline-Doppel-Trigger.
- **Fix angewandt:**
  - **Spec 2 §8.1 RESEND-Slot:** `enabledResolver = { !it.resend.resendCooldown }` +
    `alphaResolver = { if (it.resend.resendCooldown) 0.4f else 1f }` ergänzt.
  - **Spec 2 §8.2 RESEND-Slot:** analog.

### F-5 Block-5-Acceptance ohne File-Deletion-Verifikation + Caller-Grep-Check

- **Severity:** Important
- **Prüf-Achse:** 3 (KeyboardLayoutModeController-Deletion + Caller-Verification)
- **Was:** §10 Acceptance hat:
  - Bug-Eliminierungs-Tests (UI-Test 4/8/9/10)
  - R.11/R.13/R.14-Klauseln
  - Performance-Tests (Inflation-Cost < 50ms)
  - Re-Inflate-Tests

  Aber KEINE explizite Klausel:
  - "`KeyboardLayoutModeController.kt` ist gelöscht (find findet die Datei nicht mehr)".
  - "Kein produktiver Code referenziert `KeyboardLayoutModeController` / `setSingleRowMode`
    / `csTwoRowAction` / `csTwoRowInput` / `csSingleRow` / `applyContentAreaVisibility` /
    `applyPromptsVisibility` / `applyRecordingControlsVisibility` (grep negative)".
  - "ButtonSlot.actionResolver 2-arg-Signatur konsistent in allen 5 LayoutModes (Build-Smoke)".

  §9.1 sagt "entfällt vollständig" + §11.8 Block 5d sagt "Cleanup: KeyboardLayoutModeController…
  löschen", aber kein verifizierbares Akzeptanz-Kriterium. Implementer-Reflex: 5d kann durch
  eine "in Phase 2 löschen wir es"-Bequemlichkeit umgangen werden. Bug-Risiko: ein
  versteckter Caller im Service-Wiring (Block 5c) bleibt aktiv und triggert die alten KSM-
  Methoden mit leeren Bodies → Strict-Mode-Logger schweigt → niemand merkt's.
- **Konsequenz:** Plan-Spec-Versprechen "273 Zeilen weg" wird verfehlt. Phase-1-Iteration
  des Refactors trägt ungenutzte Klassen-Last weiter; Phase-2 muss ein expliziter Cleanup-
  Pass eingelegt werden.
- **Fix angewandt:**
  - **Spec 2 §10:** drei neue Acceptance-Klauseln:
    - "Phase-B S-6 — `KeyboardLayoutModeController.kt`-Datei gelöscht": `find` liefert leeres
      Ergebnis nach Block-5d-Cleanup. CI- oder Plan-Review-Check.
    - "Phase-B S-6 — Keine verwaisten Caller": `grep -rn 'KeyboardLayoutModeController\|
      setSingleRowMode\|csSingleRow\|csTwoRowAction\|csTwoRowInput\|applyContentAreaVisibility\|
      applyPromptsVisibility\|applyRecordingControlsVisibility' app/src/main/` liefert NUR
      Treffer in `docs/` und Test-Dateien.
    - "Phase-B S-6 — ButtonSlot.actionResolver 2-arg-Signatur konsistent": alle Slot-
      `actionResolver`-Lambdas in §8.1–§8.4 sind 2-arg. Build-Smoke verifiziert.

### F-6 R.13 Strict-Mode-Logger-Konkretisierung fehlt

- **Severity:** Important
- **Prüf-Achse:** 4 (R.10 KSM-Aufspaltung + R.13 Strict-Mode-Logging)
- **Was:** §10 Acceptance Z. 1664 (vor Fix):
  > **R.13 Strict-Mode-Logging während 5c:** `VisibilityWrite from $caller`-Log; Acceptance-
  > Kriterium "keine zwei Subsysteme schreiben gleichzeitig auf einer Visibility-Achse" wird
  > verifiziert.

  Aber **wie** der Logger implementiert wird, ist nicht spezifiziert:
  - Welcher API-Hook? `View.setVisibility`-Override via Subclass? Reflection auf
    `setVisibility`? Strict-Mode-Wrapper über `applyVisibility`?
  - Welche Lebensdauer? In 5c aktiv, in 5d gelöscht? Production-permanent (Bloat)?
  - Welcher Caller-Mechanismus? `Throwable.stackTrace`? Logging-Tags?
  - Welcher Threshold? "0 doppelte Writes" via wie viele Test-Iterationen?

  Implementer hätte freie Hand → potentielle Inkonsistenzen.
- **Konsequenz:** Acceptance-Test wäre unter-spezifiziert. Drei Failure-Modi:
  1. Implementer baut einen `Log.d`-Wrapper (kein strukturiertes Logging) → niemand sieht
     die Drift-Warnings.
  2. Implementer baut einen Stack-Trace-Reflection-Heavy-Tool → Performance-Bremse mid-render
     (jede Visibility-Mutation = 50µs Stack-Walking).
  3. Implementer vergisst, den Logger in 5d zu löschen → Production-Bloat + log spam.
- **Fix angewandt:**
  - **Spec 2 §10:** neue Acceptance-Klausel:
    > Phase-B S-6 — R.13 Strict-Mode-Logger konkret: `VisibilityWriteAuditLogger` ist eine
    > eigene Klasse (`core/audit/VisibilityWriteAuditLogger.kt`), die in 5c über
    > `BuildConfig.DEBUG`-Guard aktiv ist und in 5d ersatzlos gelöscht wird. API:
    > `fun logWrite(viewId: Int, caller: String, target: Int)` — `caller` aus
    > `Thread.currentThread().stackTrace[2].className` extrahiert. Akzeptanz: 0 Logs nach
    > Phase-5c-Soak-Test über 60 s (alle 5 LayoutModes durchgewechselt) — kein zweites
    > Subsystem schreibt parallel.

### F-7 `onShowResend()` LocalBinder-Drift gegen F-8

- **Severity:** Important
- **Prüf-Achse:** 4 (DictateInputMethodService resend-Mutationen → Dispatch-Pfad)
- **Was:** §9.6 Tabelle Z. 1640 (vor Fix):
  > `DictateInputMethodService.java:1839` (`resendButton.setVisibility(View.VISIBLE)` in
  > `onShowResend()`) | wird zu State-Update: `pipelineService.markLastAudioExists(true)`
  > → State emittiert → Predicate evaluiert → resend wird sichtbar

  Aber F-8 (Single-Dispatch + LocalBinder-Schrumpfung auf `state` + `dispatch`) verbietet
  typed Forwarder am LocalBinder. `markLastAudioExists(...)` wäre genau so ein Forwarder.
  S-3 (Phase-B-Quality-Gate-Report) hat dieses Anti-Pattern bereits für `KeyboardInputModule`
  etc. allgemein korrigiert — Plan-Drift, dass es hier wieder auftaucht.

  Spec 2 §3.3 Z. 250 hat die korrekte Action bereits definiert:
  ```kotlin
  data class MarkLastAudio(val exists: Boolean) : ResendAction()
  ```
- **Konsequenz:** Implementer würde einen ungewünschten LocalBinder-Forwarder einbauen, der
  F-8 verletzt. Compile-grün, aber Architektur-Drift; kommt erst in der nächsten S-Review
  oder Code-Review auf. Klein, aber Plan-Konsistenz-relevant.
- **Fix angewandt:**
  - **Spec 2 §9.6 Tabelle Z. 1640:** auf `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))`
    umgestellt mit Phase-B-S-6-FIX-Kommentar + Cross-Link auf F-8 + §3.3 Z. 250.

### F-8 F-4 AudioFocus-Icon-SSoT nicht in den Slots referenziert

- **Severity:** Minor
- **Prüf-Achse:** 9 (EditBarController + `edit_audio_focus_btn` SSoT)
- **Was:** §8.5 Z. 1458–1460 definiert `resolveAudioFocusIcon(enabled: Boolean): Int` als
  geteilten Helper für Main-Button-Area-Slot UND EditBar-Variante (F-4 / DRY). §13.4
  AudioFocus-Icon-Resolver-Tabelle Z. 2259–2263 dokumentiert die SSoT-Garantie.

  ABER die AUDIO_FOCUS-Slot-Definitionen in §8.1–§8.4 nutzen den Helper NICHT:
  ```kotlin
  ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
      visibilityPredicate = { true },
      actionResolver = { Action.AudioAction.ToggleAudioFocusPref }),   // VOR FIX (kein iconResolver)
  ```
  Kein `iconResolver` → der `applySlotToView`-Helper in §5.1 (Z. 494) macht
  `slot.iconResolver(state)?.let { view.icon = ContextCompat.getDrawable(ctx, it) }` —
  bei null-iconResolver bleibt der Icon-Slot leer / fällt auf XML-Default zurück
  (`ic_baseline_volume_off_24`).
- **Konsequenz:** AUDIO_FOCUS-Button bleibt stumm auf `volume_off`-Icon, auch wenn
  `state.audio.audioFocusEnabledPref == true`. EditBar nutzt den Helper korrekt (per
  `EditBarController.refreshAudioFocusIcon`), Main-Button-Area wäre stumm broken. F-4-DRY-
  Beweis (§13.4 Z. 2265) wäre semantisch leer — der gemeinsame Helper existiert, aber nur
  ein Konsument nutzt ihn.
- **Fix angewandt:**
  - **Spec 2 §8.2 SINGLE_ROW AUDIO_FOCUS-Slot:** `iconResolver = { resolveAudioFocusIcon(it.audio.audioFocusEnabledPref) }` ergänzt.
  - **Spec 2 §8.3 SINGLE_ROW_SEND_MODE AUDIO_FOCUS-Slot:** analog.
  - (TWO_ROW + SEND_MODE-TWO_ROW + REPROCESS_STAGING haben Predicate `false` — Slot ist
    GONE, iconResolver wäre Dead-Code. Bewusst nicht hinzugefügt.)

### F-9 `predTrashVisible == predPauseVisible` Code-Duplikation — bewusst NICHT konsolidiert

- **Severity:** Minor
- **Prüf-Achse:** 4 (DRY)
- **Was:** §8.5 hat zwei Top-Level-Funktionen mit identischem Body:
  ```kotlin
  fun predTrashVisible(state: DictateUiState): Boolean =
      state.recording.isActiveOrPaused || state.pipeline is PipelineUiState.ReprocessStaging

  fun predPauseVisible(state: DictateUiState): Boolean =
      state.recording.isActiveOrPaused || state.pipeline is PipelineUiState.ReprocessStaging
  ```
  Reine Code-Duplikation. DRY würde Konsolidierung empfehlen (z.B. `predRecordingControlsVisible`).
- **Analyse:** **Bewusst NICHT konsolidiert.** Begründung:
  - §8.4 REPROCESS_STAGING behandelt die beiden Slots bereits divergierend: PAUSE ist
    `visible+disabled+alpha 0.4` (hardcoded `enabledResolver = { false }`), TRASH ist
    `visible+enabled` mit `actionResolver` → CancelReprocessStaging.
  - Predicate-Logik ist gleich, aber Slot-Properties divergieren — die Predicate-Trennung
    macht zukünftige Divergenz (z.B. "in einem zukünftigen Mode soll trash sichtbar, pause
    nicht sichtbar sein") billig umsetzbar.
  - Konsolidierung würde zukünftige Divergenz-Inspiration nehmen und ein Slot-Property-
    Reshuffle erzwingen.

  Trade-off: Code-Duplikation (DRY-Verstoß) vs. zukünftige Erweiterbarkeit (OCP-Vorteil).
  Sustainable-Solution-Prinzip aus den Engineering-Baselines: lieber zwei klare Funktionen
  mit klarer Semantik als eine Funktion mit unklarer Zukunft.
- **Fix angewandt:** Kein Plan-Edit (Decision-Trail in diesem Report dokumentiert).

---

## Verifikationen (gegen Plan-Snippets, kein direkter Code-Read nötig)

| Plan-Aussage | Verifiziert per | Ergebnis |
|---|---|---|
| `ButtonSlot.actionResolver: (DictateUiState, ModuleServices) -> Action?` (2-arg) in §3.2 | Read Spec 2 §3.2 Z. 104 | ✅ post-S-7-F-1 |
| §6 ImeViewBackend.wireStaticHandlers ruft `slot.actionResolver(s, services)` | Read Spec 2 §6 Z. 626 | ✅ post-S-7-F-1 |
| §8.5 `resolveRecordAction(state, services)` ist 2-arg | Read Spec 2 §8.5 Z. 1401–1421 | ✅ post-S-7-F-1 |
| §8.1 TWO_ROW Slot-Lambdas waren alle 0/1-arg vor Fix | Read Spec 2 §8.1 Z. 1090–1130 vor Fix | ✅ Bug bestätigt |
| §8.2 SINGLE_ROW Slot-Lambdas waren alle 0/1-arg vor Fix | Read Spec 2 §8.2 Z. 1138–1175 vor Fix | ✅ Bug bestätigt |
| §8.3 SEND_MODE-Lambdas waren alle 0/1-arg vor Fix | Read Spec 2 §8.3 Z. 1194–1262 vor Fix | ✅ Bug bestätigt |
| §8.4 REPROCESS_STAGING-Lambdas waren alle 0/1-arg vor Fix | Read Spec 2 §8.4 Z. 1270–1309 vor Fix | ✅ Bug bestätigt |
| §8.5 `resolveRecordActionPipeline` war 1-arg vor Fix | Read Spec 2 §8.5 Z. 1424–1427 vor Fix | ✅ Bug bestätigt |
| §8.5 `resolveTrashAction` war 1-arg vor Fix | Read Spec 2 §8.5 Z. 1429–1433 vor Fix | ✅ Bug bestätigt |
| §8.5 `resolvePauseAction` war 1-arg vor Fix | Read Spec 2 §8.5 Z. 1435–1439 vor Fix | ✅ Bug bestätigt |
| `Action.PipelineAction.SendStaging` ist `data class …(val sessionId: String)` | Read Spec 2 §3.3 Z. 205 | ✅ post-Phase-1-1.0.5 + R.15 |
| `Action.PipelineAction.CancelReprocessStaging` ist `data class …(val sessionId: String)` | Read Spec 2 §3.3 Z. 206 | ✅ post-R.15 |
| §8.4 REPROCESS_STAGING-Slot rief `{ Action.PipelineAction.SendStaging }` als Singleton | Read Spec 2 §8.4 Z. 1282 vor Fix | ✅ Bug bestätigt |
| §8.4 REPROCESS_STAGING-Slot rief `{ Action.PipelineAction.CancelReprocessStaging }` als Singleton | Read Spec 2 §8.4 Z. 1296 vor Fix | ✅ Bug bestätigt |
| `LogicalButtonId.WIDGET_TOGGLE` in §3.1 enum | Read Spec 2 §3.1 Z. 57 | ✅ verifiziert |
| `LogicalButtonId.WIDGET_TOGGLE` in §6 `buttonViews`-Map | Read Spec 2 §6 Z. 542 | ✅ verifiziert |
| §8.1 TWO_ROW hatte keinen WIDGET_TOGGLE-Slot vor Fix | Read Spec 2 §8.1 Z. 1093–1128 vor Fix | ✅ Bug bestätigt |
| §8.2 SINGLE_ROW hatte keinen WIDGET_TOGGLE-Slot vor Fix | Read Spec 2 §8.2 Z. 1141–1173 vor Fix | ✅ Bug bestätigt |
| §8.3 SEND_MODE hatte keinen WIDGET_TOGGLE-Slot vor Fix | Read Spec 2 §8.3 Z. 1196–1261 vor Fix | ✅ Bug bestätigt |
| §8.4 REPROCESS_STAGING hatte keinen WIDGET_TOGGLE-Slot vor Fix | Read Spec 2 §8.4 Z. 1273–1308 vor Fix | ✅ Bug bestätigt |
| §8.5 `predResendVisible`-KDoc dokumentiert "Cooldown im enabledResolver" | Read Spec 2 §8.5 Z. 1327–1339 | ✅ verifiziert |
| §13.5 Gap 2 RESOLVED dokumentiert: "Cooldown ist in `state.resend.resendCooldown` + enabledResolver" | Read Spec 2 §13.5 Z. 2293–2298 | ✅ verifiziert |
| §8.1 + §8.2 RESEND-Slot hatte WEDER enabledResolver NOCH alphaResolver vor Fix | Read Spec 2 §8.1 Z. 1102–1103 + §8.2 Z. 1167–1169 vor Fix | ✅ Bug bestätigt |
| §10 hat KEINE File-Deletion-Acceptance-Klausel für KLMC vor Fix | Read Spec 2 §10 vor Fix | ✅ Bug bestätigt |
| §10 R.13 Strict-Mode-Logger-Klausel ohne Implementation-Spec | Read Spec 2 §10 Z. 1663 | ✅ Bug bestätigt |
| §9.6 sagt `pipelineService.markLastAudioExists(true)` vor Fix | Read Spec 2 §9.6 Z. 1640 vor Fix | ✅ Bug bestätigt |
| Action `Action.ResendAction.MarkLastAudio(val exists: Boolean)` in §3.3 | Read Spec 2 §3.3 Z. 250 | ✅ verifiziert |
| §8.1–§8.4 AUDIO_FOCUS-Slots haben keinen iconResolver | Read Spec 2 §8.1 Z. 1108 + §8.2 Z. 1171 + §8.3 Z. 1213 + §8.4 Z. 1291 vor Fix | ✅ Bug bestätigt |
| §8.5 `resolveAudioFocusIcon(enabled)` als geteilter Helper | Read Spec 2 §8.5 Z. 1458–1460 | ✅ verifiziert |

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 2 | §8.1 KEYBOARD_TWO_ROW | Refactor | Alle 8 Slot-Lambdas auf 2-arg `{ _, _ -> Action.X }` (F-1); RESEND-Slot um enabledResolver + alphaResolver für resendCooldown ergänzt (F-4); WIDGET_TOGGLE-Slot neu eingefügt mit Predicate `viewMode == KEYBOARD` (F-3) |
| Spec 2 | §8.2 KEYBOARD_SINGLE_ROW | Refactor | Alle 9 Slot-Lambdas auf 2-arg (F-1); RESEND um Cooldown (F-4); WIDGET_TOGGLE-Slot neu (F-3); AUDIO_FOCUS um iconResolver mit resolveAudioFocusIcon-Helper (F-8) |
| Spec 2 | §8.3 KEYBOARD_TWO_ROW_SEND_MODE + KEYBOARD_SINGLE_ROW_SEND_MODE | Refactor | Alle Slot-Lambdas auf 2-arg (F-1); WIDGET_TOGGLE-Slot mit Predicate `{ false }` neu (F-3); SINGLE_ROW_SEND_MODE AUDIO_FOCUS um iconResolver (F-8) |
| Spec 2 | §8.4 KEYBOARD_REPROCESS_STAGING | Refactor | Alle Slot-Lambdas auf 2-arg (F-1); SendStaging + CancelReprocessStaging mit sessionId aus state.pipeline (F-2); WIDGET_TOGGLE-Slot mit Predicate `{ false }` neu (F-3) |
| Spec 2 | §8.5 `resolveRecordActionPipeline` | Refactor | 1-arg → 2-arg `(state, services)` mit Migrations-Hinweis-Block (F-1) |
| Spec 2 | §8.5 `resolveTrashAction` | Refactor | 1-arg → 2-arg (F-1) |
| Spec 2 | §8.5 `resolvePauseAction` | Refactor | 1-arg → 2-arg (F-1) |
| Spec 2 | §9.6 DictateInputMethodService-Tabelle | Fix | `pipelineService.markLastAudioExists(true)` → `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` mit F-8-Cross-Link (F-7) |
| Spec 2 | §10 Block-5-Acceptance | Add | 5 neue Phase-B-S-6-Klauseln: File-Deletion-Check (F-5), Caller-Grep-Negative (F-5), R.13-Logger-Implementation-Spec (F-6), WIDGET_TOGGLE-Render-Verifikation (F-3), ButtonSlot 2-arg-Build-Smoke (F-1) |
| Spec 2 | §13.5.b Cross-Spec Patches Pending | Refactor | WIDGET_TOGGLE-Slot-Status-Update: "jetzt in allen 5 KEYBOARD-LayoutModes explizit verankert" (F-3) |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-6 Eintrag (2026-05-13) — 9-Findings-Summary, chronologisch zwischen S-7 und S-9 platziert |

**Gesamt:** ~14 Edit-Operationen in 2 Dateien (Spec 2: 13, Hauptplan: 1).

---

## Offene Fragen für nachfolgende Agents

### Für S-8 (OverlayBackend / Spec 3)

- **Spec 3 §3.1 OVERLAY_5BUTTON LayoutMode**: S-7-F-1 hat `OVERLAY_RECORD` über
  `::resolveOverlayRecordAction` (2-arg) verankert. Aber die anderen 4 Overlay-Slots
  (`OVERLAY_SEND`, `OVERLAY_PAUSE`, `OVERLAY_TRASH`, `OVERLAY_CLOSE`) müssen analog zur S-6-
  F-1-Welle nach 2-arg migriert werden, falls sie 0/1-arg-Lambdas haben. S-8 muss das
  prüfen — analog zum hier verankerten F-1-Pattern in §8.1–§8.4.
- **Spec 3 OverlayBackend hat keinen WIDGET_TOGGLE-Slot — bewusst**: WIDGET_TOGGLE ist
  ein KEYBOARD-Mode-Element (LayoutCatalog), nicht im OVERLAY_5BUTTON. Konsistent mit
  Spec 2 §3.1 Comment "WIDGET_TOGGLE — KEYBOARD → WIDGET-Toggle".
- **`MarkLastAudio`-Dispatch-Pfad in DictateInputMethodService.java:1839**: F-7 hat den
  Plan-Tabellen-Eintrag korrigiert. Der konkrete Code-Pfad in `onShowResend()` ist im
  S-3-Java-Brücken-Konsumenten-Pattern (S-3-F-6) zu implementieren — keine direkte
  S-6-Bindung, aber S-8 sollte cross-checken, dass `onShowResend()` analog für den Overlay-
  Send-Pfad existiert oder nicht relevant ist.

### Für S-2 / Cross-Spec

- **KSM-Aufspaltung in 3 Owner (R.10)** — `ContentAreaController` + `PromptVisibilityController`
  + `OverlayResetHandler`: keine Plan-Edits in S-6 nötig (§9.3 dokumentiert das ausreichend);
  aber **S-2-Touchpunkt:** §13.1 Visibility-Mutation-Audit Zeile 1–4 sagt
  `mainButtonsClTyped`/`editButtonsLl`/`qwertzContainer`/`emojiPickerCl` "BLEIBT — wird in
  einen ContentAreaController extrahiert". Die DB-Schema-Migration (S-2) berührt das nicht
  direkt; aber wenn `ContentAreaController` während des Refactors als zweites RenderBackend
  implementiert wird (§13.1 Issue 2.1.15 / Option B), muss der `attach(onAction)`-Pattern
  konsistent sein.

### Cross-Cutting

- **R.13 Strict-Mode-Logger als Konvention für andere Migrations-Spike-Phasen**: F-6 hat
  den `VisibilityWriteAuditLogger` als 5c-Spezifik dokumentiert. Eine analoge Telemetrie-
  Klasse könnte für andere Doppel-Owner-Migrationen interessant sein (z.B. wenn ein
  zukünftiger Plan einen `ContentAreaController` ↔ `KSM`-Übergang macht). Empfehlung: dieses
  Pattern in `docs/architecture/audit-logging.md` festhalten, falls ein analoger Bedarf
  in Phase 2 entsteht.
- **WIDGET_TOGGLE-Konsistenz mit Spec 3 GAP-4**: F-3 hat den Slot in allen 5 KEYBOARD-
  LayoutModes verankert. Spec 3 §13.5 GAP-4 (Mitigation) sollte gegen den finalen Slot-
  Position-Vorschlag (TWO_ROW: rechts neben AUDIO_FOCUS; SINGLE_ROW: am Ende der Chain)
  validiert werden — der Block-6-OverlayBackend-Implementer muss konsistent dispatchen.
- **F-9 (predTrashVisible == predPauseVisible)**: bewusst NICHT konsolidiert. Wenn ein
  zukünftiger Plan-Iter eine echte Divergenz einführt (Modes, in denen trash sichtbar aber
  pause nicht sichtbar ist), bleibt die Trennung. Wenn nach 6 Monaten klar wird, dass die
  Logik dauerhaft identisch bleibt, kann eine spätere Iteration konsolidieren — kein
  technical-debt-Marker, weil der Code-Aufwand minimal ist.

---
