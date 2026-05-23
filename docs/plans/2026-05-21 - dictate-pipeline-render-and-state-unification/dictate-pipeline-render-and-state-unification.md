---
title: Dictate Pipeline-Render und State-Mgmt-Vereinheitlichung
status: Implementer-ready
genre: Plan
archive_target: 2026-05-21 - dictate-pipeline-render-and-state-unification
created: 2026-05-21
related-plans:
  - 2026-05-21 - dictate-widget-integration (Vorgänger — Widget hat heute eigene Renderer-Bundle)
  - 2026-05-21 - dictate-indirection-cleanup (Schwester — Single-Dispatch-per-Axis Schub)
  - 2026-05-21 - dictate-render-cutover-completion-vol2 (Grundlage — Single-Writer-per-Axis Render)
related-adrs: ADR-0001, ADR-0002, ADR-0004, ADR-0005
---

# Dictate Pipeline-Render und State-Mgmt-Vereinheitlichung

## §1 Ziel + Motivation

Nach dem Inflate-Fix von 2026-05-21 ist der Floating-Widget-Modus
strukturell sichtbar; nach den zwei abgeschlossenen Plans
(`dictate-indirection-cleanup`, 14+5 commits — Action-Reducer-Effect
auf der Input-Seite; `dictate-widget-integration`, 3 commits — Widget
reuses Keyboard-Renderer) ist die Architektur formal "fertig". Auf dem
Gerät (Samsung Galaxy S24 Ultra, Android 16, 2026-05-21) treten
allerdings **fünf konkrete Regressionen** auf, die je entweder die
neue Architektur nicht voll nutzen oder von ihr fallen gelassen wurden.

### §1.1 Die fünf Bugs (User-Verbatim)

#### B-A — Widget-Pipeline-Hang (Critical)

> *"Wenn ich im Widget-Modus aufzeichne und dann auf 'Senden' drücke,
> hängt es einfach und es passiert nichts. Ich habe zwar die
> Möglichkeit, Auto-Enter zu aktivieren oder zu deaktivieren, und wenn
> ich das Widget dann schließe, wandert die Optik in die Hauptanzeige.
> Allerdings passiert inhaltlich nichts."*

Beobachtbar:

- Widget bleibt nach SEND-Press offen.
- Record-Button-Text zeigt nur "sendet" (deutsche `dictate_sending`
  literal, viel weniger als die übliche `N/M ↵ M:SS`-Pipeline-Label).
- Auto-Enter-Toggle funktioniert UI-seitig (Button-State wird gemerkt).
- Widget schließen → Optik wandert in die Hauptanzeige (mirroring
  funktioniert), aber inhaltlich kommt **nichts** in den Editor an.

#### B-B — Pause-Button Icon-Duplikation (nur normale Tastatur)

In Row-2 während `Recording` / `Paused` sind **zwei** Icons sichtbar:
das Pause-Bars-Symbol und ein Rechteck dahinter. Das Widget-Pause-Icon
ist korrekt (nur Pause-Bars).

#### B-C — Backspace Long-Press Regression

Long-Press auf Backspace löschte vor dem Refactor mit
**beschleunigender** Geschwindigkeit (Cascade: 50 ms → 25 ms → 10 ms →
5 ms). Heute: nichts. Single-Tap löscht wie gehabt.

#### B-D — Pipeline-Button-Text broken in BOTH systems

Drei Sub-Probleme im laufenden Pipeline-Phase (sowohl Keyboard als
auch Widget):

- **D-1 Phasenbezeichnung fehlt** — Button zeigt nur statisches "sendet"
  / generic Counter, nicht den aktuellen Step-Namen ("Transkribiert" /
  "Reword — Casual" / "Format" / "Insert" etc.).
- **D-2 Phasen-Counter fehlt** — kein "1/2", "2/2" sichtbar in
  Multi-Step-Pipelines.
- **D-3 Timer tickt nicht** — Timer ändert sich nur bei
  Phase-Übergängen, nicht pro Sekunde.

#### B-E — Prompt-Chips alle tappbar (Regression)

Top-Prompt-Chips sind alle tappbar, auch wenn ihr per-Chip-Rewording
nicht aktiviert ist (oder die Recording läuft). Pre-refactor waren
nicht-anwendbare Chips greyed-out (`alpha=0.5`, `isEnabled=false`).

### §1.2 User-Entscheidung — Widget-Verhalten bei Pipeline-Start

User-Wahl 2026-05-21 (vorgegeben im Auftrag): **"Widget bleibt offen,
Pipeline rendert NUR im Button-Text (kompakter Modus)."**

Konsequenz: keine neue Step-Row-Komponente im Widget. Der
Record-Button-Text trägt die Phase-Information allein (Phase-Name +
Counter + Timer), genau wie der Keyboard-Record-Button.

### §1.3 User-Wunsch — nachhaltige State-Management-Vereinheitlichung

Verbatim:

> *"Optimalerweise ist das State Management für beide identisch
> beziehungsweise angeglichen. Noch besser wäre, wenn beide das gleiche
> State Management nutzen, sodass eine Änderung im Widget durch
> irgendeine User-Interaktion auch direkt Actions in der Tastatur
> verursacht und andersherum genauso: Das Widget sorgt für
> Tastaturänderungen."*

Wichtige Klarstellung (ADR-0005 Triangle-FSM): Keyboard und Widget
sind **nie gleichzeitig sichtbar** (T1–T7-Transitions). "Widget sorgt
für Tastaturänderungen" muss daher als "Action aus Widget verändert
State, der bei nächster Keyboard-Re-Attach gerendert wird" verstanden
werden. Das ist der bestehende Pattern — der Plan muss verifizieren,
dass diese Parität für alle pipeline-relevanten State-Achsen
**tatsächlich** durchgängig ist.

## §2 Acceptance Criteria

Jedes Kriterium ist technisch verifizierbar (JVM-Unit-Test,
Code-Grep, oder reproduzierbarer Device-Test).

### §2.1 Pro Bug

- **AC-A: Widget-SEND führt Pipeline tatsächlich aus.** Im
  WIDGET-Modus mit `state.recording is Active|Paused`: Klick auf
  OVERLAY_RECORD löst die R-1-Snapshot-Aufnahme aus, dispatchet
  `StopRecordingAndSend`, das Pipeline-FSM transitionet `Active →
  Idle.Preparing → Running → Done`, der Text landet via
  `commitTextToInputConnection` im fokussierten Editor. **Test:**
  Instrumented (Espresso-style) oder Manual auf Gerät; JVM:
  `ImePipelineConfigResolver.freshSnapshots` enthält einen Eintrag
  unter dem `sessionId`, BEVOR `PipelineRunnerSubsystemAdapter.submit`
  läuft. Architektur-Invariant-Test: jede `setOnClickListener`-Site,
  die `Action.RecordingAction.StopRecordingAndSend` emittiert,
  feuert vorher die `imeSideAffordance` mit einem ID, das den
  Snapshot-Helper triggert.

- **AC-B: Pause-Button zeigt nur EIN Icon.** In keinem
  RecordingState / Mode überschneiden sich `android:foreground` und
  MaterialButton-Icon auf `pause_btn`. **Test:** Manual + Layout-
  Invariant-Test: `pause_btn` hat KEIN `android:foreground` in
  `activity_dictate_keyboard_view.xml`. Der `iconResolver` ist der
  einzige Schreibpfad (`SlotRenderer.applySlotToView` schreibt
  `MaterialButton.icon`).

- **AC-C: Backspace Long-Press startet den Accelerating-Delete-
  Cascade.** Long-Press auf BACKSPACE: nach ~500 ms beginnt das
  Löschen, alle 50 ms, beschleunigt nach 1.5 s auf 25 ms, nach 3 s
  auf 10 ms, nach 5 s auf 5 ms. Loslassen stoppt den Cascade. Swipe-
  nach-links löst Word-Selection statt aus (BackspaceSwipeHandler
  bleibt funktional). **Test:** Manual + JVM:
  `LogicalButtonId.BACKSPACE` Slot hat einen `longClickResolver` der
  ein `Action.KeyboardInputAction.BackspaceLongPressStart` emittiert
  (oder gleichwertiger Mechanismus); ImeViewBackend's
  `setOnLongClickListener`-Path feuert für BACKSPACE einen
  Affordance-Hook (oder das `KeyboardInputModule` hat einen
  passenden Effekt, der den Cascade startet) und ein
  `MotionEvent.ACTION_UP / CANCEL` triggert die Cancellation.

- **AC-D-1: Phase-Name im Record-Button-Text.** In `Running`-Phase
  zeigt der Record-Button-Text den **aktuellen Step-Namen**
  ("Transkribiert" / "Reword — …" / "Format" / …) — abgeleitet vom
  `currentStepName`-Helper (`state.pipeline.Running.stepHistory`
  lastOrNull RUNNING). **Test:** JVM —
  `resolveRecordButtonTextPipeline(stateWith stepHistory=...)`
  liefert einen String der den Step-Namen enthält.

- **AC-D-2: Phase-Counter sichtbar.** In `Running`-Phase zeigt der
  Record-Button-Text "N/M" — abgeleitet von
  `state.pipeline.Running.completedSteps` /
  `state.pipeline.Running.totalSteps`. **Test:** JVM —
  `formatPipelineLabel(1, 2, false, 500)` enthält "1/2". (Bereits
  erfüllt durch §3 `formatPipelineLabel`; AC-D-2 prüft die
  Sichtbarkeit AUCH wenn der Step-Name dazukommt — beide passen in
  den 2-zeiligen 14sp-Layoutslot.)

- **AC-D-3: Timer tickt pro Sekunde.** In `Running`-Phase aktualisiert
  sich der `elapsedMs`-basierte Timer-Teil mindestens einmal pro
  Sekunde, unabhängig von Step-Boundaries. **Test:** JVM —
  `RecordingActivityTickerObserver` (oder ein neuer
  `PipelineActivityTickerObserver`) emittiert Actions / Renders, die
  `state.pipeline.Running.elapsedMs` pro Sekunde fortschreiben.

- **AC-E: Prompt-Chips greyed-out wenn nicht klickbar.**
  - **E-1:** Wenn `Pref.RewordingEnabled == false`: Prompts-Container
    GONE (PromptVisibilityController bereits korrekt).
  - **E-2:** Wenn `state.recording is Active|Paused|Preparing` oder
    `state.pipeline is Preparing|Running`: nicht-Selection-Prompts
    werden mit `alpha=0.5` + `isEnabled=false` gerendert.
  - **E-3:** Wenn ein Prompt `requiresSelection == true` aber es
    keine Selection gibt: dieser Prompt wird mit `alpha=0.5` +
    `isEnabled=false` gerendert (`promptsAdapter.setSelectAllActive`
    + per-Chip-Logik). **Test:** Manual + JVM —
    `PromptsKeyboardAdapter`-State wird aus dem Orchestrator-State
    bezogen, nicht aus dem legacy `recordingStateController.state`.

### §2.2 State-Mgmt-Parität

- **AC-P-1: Single Source of Truth = `DictateUiState`.** Jede
  Render-relevante UI-Achse für Pipeline-Phase, Recording-Phase,
  Auto-Enter-Bit, Step-Counter und Pause-Toggle liest **nur** aus
  `pipelineBinder.getState().value` oder seinem
  `StateFlow<DictateUiState>`. **Test:** Code-Grep —
  `recordingStateController.getState()` / `.isRecordingOrPaused()`
  hat keine Aufrufe in Code-Pfaden mehr, die nach dem Bind
  ausgeführt werden (außer Pre-Bind-Fallback-Tags).

- **AC-P-2: Identische Label-Quelle.** Beide Render-Pfade (Keyboard
  + Widget) konsumieren den **selben** Label-Resolver für die
  Pipeline-Phase (`resolveRecordButtonTextPipeline` direkt oder via
  `resolveOverlayRecordButtonText`-Composition).
  **Test:** Code-Grep — beide Slots
  (`KEYBOARD_TWO_ROW_SEND_MODE.RECORD` /
  `KEYBOARD_SINGLE_ROW_SEND_MODE.RECORD` und
  `OVERLAY_5BUTTON.OVERLAY_RECORD`) referenzieren die selbe Funktion
  (Composition durch `resolveOverlayRecordButtonText` ist explizit).

- **AC-P-3: Symmetrische Side-Channel-Forwards.** Jeder Render-Pfad
  (Keyboard + Widget) konsumiert den selben Side-Channel-Forwarder
  (`onTimerTick` + `onAmplitude` + ein neuer `onPipelineTimerTick`
  für die Pipeline-Phase). Der TickerObserver fan-out auf beide
  Backends ist symmetrisch. **Test:** Beide Backends rendern bei
  identischen `DictateUiState`-Snapshots zu visuell gleichem
  Resultat (modulo Layout-Unterschiede); JVM-Snapshot-Test.

- **AC-P-4: Affordance-Hook ID-Aliasing.** Der IME-Side-Affordance-
  Lambda behandelt sowohl `LogicalButtonId.RECORD` ALS AUCH
  `LogicalButtonId.OVERLAY_RECORD` mit demselben Body (Snapshot-
  Logik). **Test:** Architektur-Invariant-Test — der Lambda-Body
  enthält beide Konstanten in einer ODER-Bedingung.

## §3 Architektur-Analyse

### §3.1 Heutiger State-Flow (Single-Loop-Diagram)

```
                ┌─────────────────────────────────────┐
                │   User-Interaktion (Click/Long/...)  │
                └────────────────┬────────────────────┘
                                 │
            ┌────────────────────┴──────────────────────┐
            │ Click-Site (z.B. ImeViewBackend.click,    │
            │   OverlayBackend.click, IME-side affordance) │
            │   - reads stateRef                          │
            │   - calls slot.actionResolver(state, services)│
            │   - emits Action via onAction(...)          │
            └────────────────────┬──────────────────────┘
                                 │
                       ┌─────────┴──────────┐
                       │  PipelineBinder    │
                       │  .dispatch(Action) │
                       └─────────┬──────────┘
                                 ▼
                ┌──────────────────────────────────┐
                │  DictateOrchestrator             │
                │  .dispatchInternal(action, ctx)  │
                │  - module.reduce(...) per ID     │
                │  - cascade module.onCMS(...)     │
                │  - run side-effects              │
                └────────────────┬─────────────────┘
                                 │
                  ┌──────────────┴───────────────┐
                  │  StateFlow<DictateUiState>   │
                  └──────┬─────────────┬─────────┘
                         │             │
              ┌──────────▼─┐       ┌───▼────────────┐
              │ ImeViewBackend   │ OverlayBackend  │
              │   .render(state) │ .render(state)  │
              │   - applySlots   │ - applySlots    │
              │   - autoEnter    │ - autoEnter     │
              │   - color        │ - color         │
              │   - recording    │ - recording     │
              │   - stepRow      │  (no stepRow:   │
              │                  │   widget compact)│
              └──────────────────┘                 │
                         │             │
                  Side-Channel ticks  Side-Channel ticks
                         │             │
              ┌──────────▼─────────────▼─────────┐
              │ RecordingActivityTickerObserver  │
              │  - state-driven 100ms tick       │
              │  - onTimerTick → both backends   │
              │  - onAmplitude → both backends   │
              │  - **only during Active|Paused** │
              │  - **NOT during Pipeline-Phase** │
              └──────────────────────────────────┘
```

### §3.2 Pipeline-Render-Kette — Keyboard-Pfad

Pro Render-Tick (`ImeViewBackend.render`,
`app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:240-304`):

1. `mode.sceneStateId?.let { motionSurface.jumpToState | transitionToState }`
   — MotionLayout-Scene-Switch.
2. `applySlotToView(slot, view, state, ctx)` für jeden Slot — schreibt
   `visibility`, `isEnabled`, `alpha`, `icon`, `text`.
3. `autoEnterRenderer.onState(state)` — schreibt das ↵-Right-Compound-
   Drawable.
4. `recordButtonColorController.onState(state)` — schreibt
   `setTextColor` (rot bei Failure).
5. `pipelineStepRowRenderer.onState(state)` — diff von `stepHistory`
   gegen den Step-Row-View-Tree (separater Container).
6. `recordingAnimationController.onState(state)` — startet/pausiert
   PulseLayout-Animation + BorderGlow.

Daneben: side-channel Per-Tick-Forwards aus
`RecordingActivityTickerObserver`
(`app/src/main/java/net/devemperor/dictate/core/RecordingActivityTickerObserver.kt:112-230`):

- `onTimerTick(elapsedMs)` → `recordingAnimationController.onTimerTick`
  → `animation.onTimerTick(text)` (schreibt Timer-Text in das
  Animation-Drawable — NICHT in `recButton.setText`).
- `onAmplitude(level)` → `recordingAnimationController.onAmplitude`
  → updates Border-Glow + Amplitude-Bar-Visualizer.

**Wichtig:** Der Ticker tickt NUR bei
`RecordingState.Active` / `Paused`. Für die Pipeline-Phase
(`PipelineUiState.Preparing` / `Running`) gibt es **keinen** Ticker.
Das ist die Wurzel von B-D-3.

Pipeline-Phase Label-Konstruktion: in
`KEYBOARD_TWO_ROW_SEND_MODE.RECORD` (LayoutCatalog.kt:286-316) verwendet
der `textResolver` direkt `resolveRecordButtonTextPipeline(state, strings)`
(TextResolvers.kt:139-154). Dieser ruft `strings.formatPipelineLabel(...)`
(DictatePipelineService.kt:887-900) mit dem Format `"%d/%d ↵ %d:%02d"`
oder `"%d/%d %d:%02d"`. **Step-Name ist NICHT im Format-String** —
das ist die Wurzel von B-D-1.

### §3.3 Pipeline-Render-Kette — Widget-Pfad

Pro Render-Tick (`OverlayBackend.render`,
`app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:273-330`):

1. Permission-Gate (state.overlay.hasPermission).
2. Suppress-Bit-Gate.
3. First-render attach → `inflateAndAttach` → `buildRendererBundle`
   (`OverlayBackend.kt:453-464`) instantiiert die drei Side-Channel-
   Renderer aus den drei Factories.
4. `applySlots(state, mode)` — wie Keyboard.
5. **Forwards** (`OverlayBackend.kt:320-322`):
   `rendererBundle?.autoEnter?.onState(state)`,
   `rendererBundle?.color?.onState(state)`,
   `rendererBundle?.recording?.onState(state)`.
6. `applyPosition(state.overlay)` — Window-Position.

**Step-Row-Renderer fehlt absichtlich** — Widget hat keinen Scroll-
Bereich, kein Platz für eine Step-Row. User-Entscheidung §1.2:
Phase-Information **nur** im Button-Text.

Label-Konstruktion: `OVERLAY_5BUTTON.OVERLAY_RECORD` (LayoutCatalog.kt:519-547)
verwendet `textResolver = { state -> resolveOverlayRecordButtonText(state, strings) }`
(TextResolvers.kt:181-186). Dieser delegiert bei Pipeline-Phase auf
`resolveRecordButtonTextPipeline` — also identisch zum Keyboard.

**Konsequenz für B-D:** Wenn der Keyboard-Label um Step-Name + Timer-
Ticker erweitert wird, profitiert das Widget **automatisch** über die
gemeinsame Composition. Single SoT für den Label wird durch
`resolveRecordButtonTextPipeline` + `formatPipelineLabel` etabliert.

### §3.4 Renderer-Bundle-Inventar

Welche Klasse rendert auf welche View?

| Klasse | Achse | Keyboard-View | Widget-View |
|---|---|---|---|
| `SlotRenderer.applySlotToView` | visibility/enabled/alpha/icon/text | jeder Catalog-Slot (9 Buttons) | jeder Catalog-Slot (4 Buttons) |
| `RecordingAnimationController` | Pulse + Border-Glow + Amplitude + Timer-im-Animation-Drawable | `record_btn` + `record_pulse_layout` | `overlay_record_btn` + `overlay_pulse_layout` |
| `AutoEnterRenderer` | Right-Compound-Drawable (↵-Icon) | `record_btn` | `overlay_record_btn` |
| `RecordButtonColorController` | `setTextColor` (rot bei Failure) | `record_btn` | `overlay_record_btn` |
| `PipelineStepRowRenderer` | Step-Row-Diff | `pipeline_steps_container` | (kein Pendant — bewusst weggelassen) |

**Single-Writer-per-Axis** ist nach den zwei Vorgänger-Plans
gewährleistet. Es gibt keine Cross-Writer für die Pipeline-relevanten
Achsen. **Aber**:

- B-B verletzt Single-Writer für `pause_btn`-Icon: XML
  `android:foreground` schreibt das Pause-Icon einmal (statisch),
  `applySlotToView` schreibt es nochmal über `view.icon = ...`
  (MaterialButton-Icon, dynamisch). Das sind zwei verschiedene Render-
  Slots der View, beide visible.

### §3.5 State-Achsen-Inventar — Pipeline-Render

Welche `DictateUiState`-Achsen füttern welchen Renderer?

| Achse | Verwendet von | Konsumiert in beiden Pfaden? |
|---|---|---|
| `state.recording` | `RecordingAnimationController.onState` (Pulse), `SlotRenderer` (alpha/enabled/icon), `resolveRecordButtonText` (Text) | ✓ ja |
| `state.pipeline` | `PipelineStepRowRenderer` (Keyboard only), `resolveRecordButtonTextPipeline`, `RecordButtonColorController` (Failure-Color), `AutoEnterRenderer` (autoEnter), `KeyboardLayoutManager.forKeyboard` (Mode-Selection) | ✓ ja (Widget hat keinen StepRow, aber rest ja) |
| `state.pipeline.Running.elapsedMs` | `formatPipelineLabel` | ✓ ja — **aber nicht per-Sekunde aktualisiert** (B-D-3) |
| `state.pipeline.Running.stepHistory` | `PipelineStepRowRenderer` (Keyboard) — **NICHT** `formatPipelineLabel` (B-D-1) | — |
| `state.pipeline.Running.completedSteps/totalSteps` | `formatPipelineLabel` (N/M) | ✓ ja (D-2 funktioniert) |
| `state.viewMode` | `KeyboardLayoutManager`-mode selection, `OverlayBackend`-attach-toggle, `resolveOverlayRecordEnabled` (HOVER-gate) | ✓ ja |
| `state.layout.smallMode` | `forKeyboard` mode selection | nicht relevant für Widget |
| `state.features.rewordingEnabled` | `PromptVisibilityController` (visibility), prompt-chip enable | ✓ ja |

**Side-Channel-Ticker** ist die Lücke: er fan-outed an beide Backends
(Widget integriert), aber **er tickt nur bei `state.recording`-
Phasen, nicht bei `state.pipeline`-Phasen**. Damit fehlt Per-Sekunde-
Timer-Updates im Running.

### §3.6 Triangle-FSM-Konsequenzen für Render

ADR-0005 (Triangle-FSM): KEYBOARD ↔ WIDGET ↔ HOVER, nie gleichzeitig.

- WIDGET ↔ KEYBOARD User-toggelt: Render-State bleibt
  `DictateUiState` (Single SoT) → bei Re-Attach übernimmt das andere
  Backend nahtlos die Optik. **Voraussetzung:** beide Backends
  rendern aus exakt den gleichen Achsen — was Renderer-Bundle-Inventar
  §3.4 zeigt.
- HOVER: Widget mit `enabledResolver = (state.viewMode == WIDGET)` =
  false → Send-Button greyed out, kein Klick durchschlägt
  (defensive Doppel-Gate in `resolveOverlayRecordAction` —
  ActionResolvers.kt:294).
- T7 (Pipeline-Done → HOVER-Dismiss): `OverlayBackend.render` reagiert
  auf `state.overlay.suppressAutoOverlayUntilNextSession` und tearen-
  down.

**Für den Plan-Scope:** der State-Mgmt-Wunsch §1.3 ist bereits
erfüllt — beide Backends konsumieren den selben StateFlow. Die
**effektive Divergenz** entsteht durch:

1. Affordance-Hook ID-Mismatch (B-A): Widget sendet
   `OVERLAY_RECORD`, IME-Lambda matched nur `RECORD`.
2. Ticker-Coverage-Lücke (B-D-3): keine per-Sekunde-Updates während
   Pipeline.
3. Legacy-Controller-Reste (B-E): `recordingStateController.getState()`
   wird gelesen, ist aber auf dem neuen Pfad nicht-driven.
4. Format-String-Lücke (B-D-1): `formatPipelineLabel` enthält keinen
   Step-Name-Slot.
5. XML-statisches Foreground (B-B): zweistufige Icon-Schreibung.
6. Long-Press-Handler-Verlust (B-C): keine Catalog-Entsprechung für
   den Accelerating-Delete-Cascade.

## §4 Root-Cause-Mapping

> **Confidence-Legende:** **high** = von Code-Pfad bis Symptom traced;
> **medium** = wahrscheinliche Kette, aber unbestätigte Annahmen
> bleiben; **low** = Hypothese, mehrere Causes plausibel.

### §4.1 B-A — Widget-Pipeline-Hang

**Symptom:** Widget-SEND führt Pipeline nicht aus; Button zeigt
permanent "sendet"; Auto-Enter-Toggle funktioniert; Widget schließen
spiegelt State in Keyboard, aber Text kommt nicht im Editor an.

**Code-Evidence:**

- `OverlayBackend.wireStaticOverlayHandlers`
  (`app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:529-549`):
  ```kotlin
  if (id == LogicalButtonId.OVERLAY_RECORD) {
      imeSideAffordance(id, false)
  }
  slot.actionResolver(state, services)?.let { action ->
      onAction?.invoke(action)
  }
  ```
  → IME-Affordance-Hook bekommt `id = OVERLAY_RECORD`.

- `DictatePipelineService.imeSideAffordance` Konstruktor-Arg
  (`app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:739-741`):
  ```kotlin
  imeSideAffordance = { id, isLongPress ->
      binder.delegateImeSideAffordance?.invoke(id, isLongPress)
  },
  ```
  → Forwarder, gibt `id = OVERLAY_RECORD` weiter.

- `DictateInputMethodService.imeSideAffordance` Lambda
  (`app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1415-1462`):
  ```java
  imeSideAffordance = (id, isLongPress) -> {
      if (id == LogicalButtonId.RECORD && isLongPress) {           // ← NO MATCH
          onRecordLongClicked();
      } else if (id == LogicalButtonId.RESEND && isLongPress) {    // ← NO MATCH
          onResendLongClicked();
      } else if (id == LogicalButtonId.RESEND) {                   // ← NO MATCH
          ...
      } else if (id == LogicalButtonId.RECORD) {                   // ← NO MATCH
          prepareCatalogStopRecordingIfActive();
      }
      return kotlin.Unit.INSTANCE;
  };
  ```
  → Bei `id = OVERLAY_RECORD` matcht **keine** Bedingung →
  `prepareCatalogStopRecordingIfActive()` läuft nie → R-1-Snapshot
  wird nicht gemacht.

- `ImePipelineConfigResolver.resolveFresh`
  (`app/src/main/java/net/devemperor/dictate/core/ImePipelineConfigResolver.kt:146-153`):
  ```kotlin
  override fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline {
      val cfg = freshSnapshots.remove(sessionId)
          ?: throw UnsupportedOperationException(
              "ImePipelineConfigResolver: no fresh-recording snapshot for sessionId=" +
                  "$sessionId. ..."
          )
      ...
  }
  ```
  → async resolveFresh wirft → EffectFailure-Arm fängt → Pipeline-FSM
  bleibt in `Preparing` stehen → "sendet" rendert weiter.

- `LogicalButtonId.kt:35 / :81`: `RECORD` und `OVERLAY_RECORD` sind
  distinkte enum-Konstanten.

**Wahrscheinliche Ursache:** Beim Verdrahten des Affordance-Hooks im
`dictate-widget-integration`-Plan (§8.3 Chunk 3.2) wurde die
Lambda-Body-Erweiterung NICHT mitgezogen. Der KDoc in
DictateInputMethodService.java:1495-1508 sagt korrekt: "share the
exact same IME-side affordance lambda" — aber der Lambda-Body
verzweigt explizit auf `RECORD` / `RESEND`-IDs ohne ein
`OVERLAY_RECORD`-Branch. Der Plan dokumentierte die Architektur, die
Implementierung des Hook-Body wurde übersprungen.

Es gibt auch eine Architektur-Frage: soll `OVERLAY_RECORD` als
eigener Hook-ID behandelt werden (eigene Branch), oder soll das
`OVERLAY_RECORD`-Klick *am Hook-Punkt* auf `RECORD` aliassen
(symmetrisch zur Resolver-Composition, die `resolveOverlayRecordAction`
intern `resolveRecordAction` / `resolveRecordActionPipeline` aufruft)?
Variante 2 ist konsistenter mit dem Plan-Wortlaut "exakt der selbe
Button". Beide sind plausibel; siehe §5 Empfehlung.

**Confidence:** **high.** Direkter Code-Pfad bis Symptom geschlossen.

### §4.2 B-B — Pause-Button Icon-Duplikation

**Symptom:** Während Recording/Paused zeigt `pause_btn` zwei
Drawables: Pause-Bars-Icon zentriert + ein dahinter sichtbares
Rechteck.

**Code-Evidence:**

- `app/src/main/res/layout/activity_dictate_keyboard_view.xml:176-184`:
  ```xml
  <com.google.android.material.button.MaterialButton
      android:id="@+id/pause_btn"
      ...
      android:foreground="@drawable/ic_baseline_pause_24"
      android:foregroundGravity="center"
      ...
  ```
  → Statisches `android:foreground` ist immer gerendert.

- `LayoutCatalog.kt:141-148` (KEYBOARD_TWO_ROW PAUSE) + analoge
  Slots:147 / :198 / :320 / :370 / :472:
  ```kotlin
  ButtonSlot(
      logicalId = LogicalButtonId.PAUSE,
      ...
      iconResolver = ::resolvePauseIcon,
      ...
  )
  ```
  → `iconResolver` liefert `R.drawable.ic_baseline_mic_24` oder
  `ic_baseline_pause_24` je nach `state.recording is Paused`.

- `SlotRenderer.applySlotToView`
  (`app/src/main/java/net/devemperor/dictate/state/render/SlotRenderer.kt:65-76`):
  ```kotlin
  if (view is MaterialButton) {
      slot.iconResolver(state)?.let { iconRes ->
          ...
          view.icon = ContextCompat.getDrawable(ctx, iconRes)
          ...
      }
      ...
  }
  ```
  → Schreibt `MaterialButton.icon` (interne Icon-Property der
  MaterialButton-Klasse).

- `app/src/main/res/layout/overlay_5button_layout.xml:101-109`:
  ```xml
  <MaterialButton
      android:id="@+id/overlay_pause_btn"
      ...
      app:icon="@drawable/ic_baseline_pause_24"
      ...
  ```
  → Verwendet `app:icon`, nicht `android:foreground` → kein Duplikat.

Android-Renderhintergrund: MaterialButton zeichnet `icon` als seinen
internen Icon-Drawable; `android:foreground` ist eine separate View-
Property die *über* dem Content gezeichnet wird. Beide sind sichtbar.

**Wahrscheinliche Ursache:** Das XML-`android:foreground="…ic_baseline_pause_24"`
ist ein Relikt aus der Vor-Catalog-Ära (als die Pause-Icon-Achse noch
direkt im XML stand). Beim Übergang auf `iconResolver` wurde das
statische Foreground nicht entfernt. In KEYBOARD_TWO_ROW war der
`iconResolver` neu — es wurde gedacht "der iconResolver schreibt
das" und übersehen, dass die XML-Property statisch bleibt. Im
Overlay-Layout (das in `dictate-widget-integration` neu geschrieben
wurde) wurde es bereits richtig gemacht (`app:icon` von Anfang an).

**Confidence:** **high.** Klare statische vs. dynamische
Render-Pfad-Trennung erklärt das Doppelbild perfekt.

### §4.3 B-C — Backspace Long-Press-Cascade weg

**Symptom:** Long-Press auf Backspace löscht nicht; pre-refactor
löschte er mit beschleunigender Geschwindigkeit.

**Code-Evidence:**

- `DictateInputMethodService.java:4873-4898` `onBackspaceLongClicked()`:
  ```java
  public void onBackspaceLongClicked() {
      isDeleting = true;
      startDeleteTime = System.currentTimeMillis();
      currentDeleteDelay = 50;
      deleteRunnable = new Runnable() { ... };
      deleteHandler.post(deleteRunnable);
  }
  ```
  → Existiert noch, KDoc sagt "owned by SpecialTouchHandlerInstaller's
  BackspaceSwipeHandler".

- `grep -rn "onBackspaceLongClicked"` im gesamten src/main-Tree liefert
  NUR die Definition + den KDoc-Verweis im
  `SpecialTouchHandlerInstaller.kt:280` (im Kommentar). **Kein
  Aufrufer.**

- `SpecialTouchHandlerInstaller.buildBackspaceSwipeHandler`
  (`SpecialTouchHandlerInstaller.kt:286-292`):
  ```kotlin
  private fun buildBackspaceSwipeHandler(): View.OnTouchListener =
      BackspaceSwipeHandler(
          inputConnectionProvider = inputConnectionProvider,
          vibrate = onVibrate,
          onDeleteCancelled = onBackspaceDeleteCancelled,
          ...
      )
  ```
  → BackspaceSwipeHandler verarbeitet nur Swipe-Geste, NICHT
  Long-Press.

- `BackspaceSwipeHandler.onTouch`
  (`app/src/main/java/net/devemperor/dictate/keyboard/BackspaceSwipeHandler.kt:53-59`):
  ```kotlin
  MotionEvent.ACTION_DOWN -> {
      ...
      return false // allow click/long-press detection
  }
  ```
  → Lässt ACTION_DOWN durch, damit die ViewSystem-Long-Press-Detection
  läuft.

- `LayoutCatalog.kt:97-102` (KEYBOARD_TWO_ROW BACKSPACE):
  ```kotlin
  ButtonSlot(
      logicalId = LogicalButtonId.BACKSPACE,
      ...
      actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
  )
  ```
  → **Kein `longClickResolver`!** Default = `{ _, _ -> null }`.

- `ImeViewBackend.wireStaticHandlers` Long-Click-Branch
  (`ImeViewBackend.kt:488-510`):
  ```kotlin
  view.setOnLongClickListener {
      onVibrate()
      if (id == LogicalButtonId.RECORD || id == LogicalButtonId.RESEND) {
          imeSideAffordance(id, true)
      }
      // ... slot.longClickResolver(s, services) returns null for BACKSPACE
      true
  }
  ```
  → Für BACKSPACE: vibriert, kein dispatch, kein Affordance-Hook,
  consumed. Lang gedrückt wird verschluckt.

- `AcceleratingRepeatHandler` existiert
  (`app/src/main/java/net/devemperor/dictate/keyboard/AcceleratingRepeatHandler.kt`)
  und wird in `QwertzKeyboardController` für die QWERTZ-Backspace
  verwendet (`QwertzKeyboardController.kt:47`). Aber: das ist die
  QWERTZ-Backspace (im QWERTZ-Layout), nicht die Haupt-Tastatur-
  Backspace.

**Wahrscheinliche Ursache:** Beim CR-DEL-Schritt der Render-Cutover
(Deletion des `MainButtonsController` — siehe Code-Kommentar
`DictateInputMethodService.java:4870-4872` "was MainButtonsController.Callback
(deleted)") wurde der Aufruf von `onBackspaceLongClicked()` mit
entfernt. Die Body-Definition blieb stehen, die Wiring wurde nicht
auf das Catalog-Long-Press-System (`longClickResolver` +
`imeSideAffordance`) migriert. Der KDoc-Hinweis
"owned by BackspaceSwipeHandler" ist faktisch falsch — der Swipe-
Handler kümmert sich nur um Swipe-Word-Selection.

**Confidence:** **high.** Lambda-Body existiert, kein Caller, kein
Catalog-Slot — klare Hängenbleib-Migration.

### §4.4 B-D-1 — Phase-Name fehlt im Pipeline-Label

**Symptom:** Während Pipeline-Phase zeigt der Record-Button kein
Step-Name ("Transkribiert" / "Reword — …" / "Format" / "Insert").

**Code-Evidence:**

- `DictatePipelineService.buildLayoutStrings`
  (`app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:887-900`):
  ```kotlin
  formatPipelineLabel = { completedSteps, totalSteps, autoEnterActive, elapsedMs ->
      val seconds = (elapsedMs / 1000L).toInt()
      val mm = seconds / 60
      val ss = seconds % 60
      if (autoEnterActive) {
          String.format(Locale.US, "%d/%d ↵ %d:%02d", completedSteps, totalSteps, mm, ss)
      } else {
          String.format(Locale.US, "%d/%d %d:%02d", completedSteps, totalSteps, mm, ss)
      }
  },
  ```
  → Format hat 4 Slots: `completedSteps`, `totalSteps`, `autoEnterActive`,
  `elapsedMs`. **Step-Name ist nicht im Funktions-Argument.**

- `resolveRecordButtonTextPipeline`
  (`app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:139-154`):
  ```kotlin
  fun resolveRecordButtonTextPipeline(state: DictateUiState, strings: LayoutStrings): CharSequence =
      when (val pipe = state.pipeline) {
          is PipelineUiState.Preparing -> strings.formatPreparingLabel(pipe.autoEnterActive)
          is PipelineUiState.Running ->
              strings.formatPipelineLabel(
                  pipe.completedSteps,
                  pipe.totalSteps,
                  pipe.autoEnterActive,
                  pipe.elapsedMs,
              )
          else -> strings.record
      }
  ```
  → Liest `completedSteps`, `totalSteps`, `autoEnterActive`,
  `elapsedMs`. **`stepHistory` / `currentStepName` wird nicht gelesen.**

- `state.pipeline.Running.currentStepName` extension property
  (`app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:413-414`):
  ```kotlin
  val PipelineUiState.Running.currentStepName: String?
      get() = stepHistory.lastOrNull { it.status == StepStatus.RUNNING }?.stepName
  ```
  → Existiert, wird aber nirgendwo gelesen, außer im
  `PipelineStepRowRenderer` (separater View-Tree).

**Wahrscheinliche Ursache:** Beim Design des `formatPipelineLabel`-
Formats wurde der Step-Name bewusst aus dem Button-Label ausgespart,
weil der `PipelineStepRowRenderer` die Step-Namen-Information im
separaten Step-Row-Container zeigt. Im Widget-Modus existiert dieser
Container aber nicht (User-Entscheidung §1.2 — Phase-Info nur im
Button-Text), darum fehlt der Phase-Name dort komplett. Im Keyboard
ist es ein Feature-Gap: der Step-Name steht zwar im Step-Row, aber
auch dort kann der User ihn gern AUCH im Button-Text haben (= näher
am Focus).

**Confidence:** **high.** Format-String und Resolver-Signatur sind
direkt einsehbar; das Feature ist schlicht nicht implementiert.

### §4.5 B-D-2 — Phase-Counter fehlt

**Symptom:** "N/M"-Counter wird nicht angezeigt.

**Code-Evidence:** `formatPipelineLabel` enthält `"%d/%d"` (s.o.). Der
Counter ist also formal im Format-String.

**Wahrscheinliche Ursache:** B-D-2 hängt vermutlich von B-A ab — wenn
die Pipeline in `Preparing` hängen bleibt, wird nie `Running` erreicht,
nie ein `StartPipeline` mit `totalSteps`-Payload ausgewertet, also nie
`formatPipelineLabel` mit echten Werten gefüttert; stattdessen rendert
`formatPreparingLabel` → "sendet". Sobald B-A gefixt ist und die
Pipeline `Running` erreicht, sollte der Counter erscheinen — er ist
struktur-fertig.

**Confidence:** **medium.** Hypothese (B-D-2 ist Folge-Symptom von B-A);
bestätigbar nach B-A-Fix durch ein Device-Re-Test ohne weiteren Code-
Change.

### §4.6 B-D-3 — Timer tickt nicht pro Sekunde

**Symptom:** Während Pipeline-Phase aktualisiert sich der Timer nur an
Phase-Boundaries (StepStarted / StepCompleted), nicht pro Sekunde.

**Code-Evidence:**

- `PipelineModule.reduce(StepStarted)`
  (`app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:209`):
  ```kotlin
  nextState = state.copy(
      elapsedMs = elapsedSince(state.startedAtMs, ctx.now),
      stepHistory = nextHistory,
  ),
  ```
  → `elapsedMs` wird **nur** bei `StepStarted` / `StepCompleted` /
  `StepFailed` aktualisiert.

- `RecordingActivityTickerObserver.handleRecordingStateChange`
  (`RecordingActivityTickerObserver.kt:163-173`):
  ```kotlin
  when (rs) {
      is RecordingState.Idle -> stopTicker()
      is RecordingState.Active -> startOrContinueTicker(...)
      is RecordingState.Paused -> freezeTicker(...)
      is RecordingState.Preparing -> { /* wait for Active */ }
  }
  ```
  → Ticker reagiert nur auf `state.recording`. Während Pipeline-
  Phase ist `state.recording == Idle` (gekoppelt durch
  `StopRecordingAndSend` reducer arm, RecordingModule). Ticker stoppt.

- Es gibt **keinen** `PipelineActivityTickerObserver` — der Pipeline-
  Phase-Timer hat keinen Ticker-Owner.

**Wahrscheinliche Ursache:** Beim Bau des `RecordingActivityTickerObserver`
in der `dictate-render-cutover-completion-vol2`-Phase wurde
`state.recording` als Trigger gewählt, weil die Hauptsymptome
(BorderGlow + Amplitude-Bars) während aktiver Recording wichtig sind.
Die Pipeline-Phase hat ihren eigenen Timer-Slot
(`Running.elapsedMs`), aber **keinen Per-Sekunde-Schreiber** — die
elapsedMs-Restamps an Step-Boundaries waren wahrscheinlich als
"genau genug" gedacht (jeder Step dauert mehrere Sekunden).
Auf langen Steps (Transkription mit 30 s Audio = ~5 s Step,
Reword-Step = mehrere Sekunden, etc.) sieht der User dann lange Pausen
ohne Timer-Tick.

**Confidence:** **high.** Reducer- und Observer-Code lesen sich beide
direkt.

### §4.7 B-E — Prompt-Chips alle tappbar

**Symptom:** Prompt-Chips (außer Selection-only Chips) sind
tappable/aktiv, auch wenn aktive Recording / Pipeline läuft.
Pre-Refactor waren sie greyed-out mit alpha=0.5.

**Code-Evidence:**

- `DictateInputMethodService.updatePromptButtonsEnabledState`
  (`app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4478-4491`):
  ```java
  private void updatePromptButtonsEnabledState() {
      RecordingState state = recordingStateController != null
          ? recordingStateController.getState() : RecordingState.Idle.INSTANCE;
      disableNonSelectionPrompts = state.isRecordingOrPaused() || state instanceof RecordingState.Preparing;
      ...
      promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
      ...
  }
  ```
  → Liest `recordingStateController.getState()` (legacy).

- `RecordingStateController` wird auf dem neuen Pfad **nie**
  driven — Beweis im KDoc des `RecordingActivityTickerObserver`
  (`RecordingActivityTickerObserver.kt:32-44`):
  > *"`RecordingStateController.startRecording()` is never called on
  > the new path (the orchestrator's [RecordingHardwareAdapter] now
  > owns MediaRecorder directly, without polling)."*

  → `recordingStateController.state` bleibt permanent `Idle` auf
  dem neuen Bound-Pfad.

- `disableNonSelectionPrompts` ist also immer `false` →
  `promptsAdapter.setDisableNonSelectionPrompts(false)` →
  `PromptsKeyboardAdapter.kt:256-258`:
  ```kotlin
  boolean shouldDisable = disableNonSelectionPrompts && model.getId() >= 0 && !model.getRequiresSelection();
  holder.promptBtn.setEnabled(!shouldDisable);
  holder.promptBtn.setAlpha(shouldDisable ? 0.5f : 1f);
  ```
  → Chips bleiben permanent enabled + alpha=1.

**Wahrscheinliche Ursache:** Bei der Render-Cutover-vol2-Migration
wurde der `updatePromptButtonsEnabledState`-Aufrufer-Pfad nicht
mit-migriert. Die Funktion lebt noch im IME, aber ihre Quelle
(`recordingStateController.getState()`) ist post-Cutover ein Stub.
Der korrekte Pfad wäre: Observer auf `pipelineBinder.state` →
auf `state.recording` (oder besser: ein `isPipelineOrRecordingBusy`
derived predicate) zugreifen.

**Confidence:** **high.** Direkter Code-Pfad.

## §5 Lösungs-Architektur

### §5.1 Single SoT für Pipeline-Label

**Vorschlag:** Erweitere `formatPipelineLabel`-Signatur um den
Step-Name:

```kotlin
// in LayoutStrings (TextResolvers.kt)
val formatPipelineLabel: (
    stepName: String?,         // NEU
    completedSteps: Int,
    totalSteps: Int,
    autoEnterActive: Boolean,
    elapsedMs: Long,
) -> CharSequence,
```

Implementation in `DictatePipelineService.buildLayoutStrings`:

```kotlin
formatPipelineLabel = { stepName, completedSteps, totalSteps, autoEnterActive, elapsedMs ->
    val mm = ((elapsedMs / 1000L) / 60).toInt()
    val ss = ((elapsedMs / 1000L) % 60).toInt()
    val arrow = if (autoEnterActive) " ↵" else ""
    val phase = stepName ?: ""
    // Layout: zweizeilig "phase / counter↵\nM:SS" — passt in 14sp, 2 Zeilen.
    // (Detaillierte Layout-Decision: §9 OQ-1.)
    if (phase.isNotEmpty()) {
        String.format(Locale.US, "%s%s\n%d/%d  %d:%02d", phase, arrow, completedSteps, totalSteps, mm, ss)
    } else {
        String.format(Locale.US, "%d/%d%s  %d:%02d", completedSteps, totalSteps, arrow, mm, ss)
    }
},
```

`resolveRecordButtonTextPipeline`:

```kotlin
is PipelineUiState.Running ->
    strings.formatPipelineLabel(
        pipe.currentStepName,
        pipe.completedSteps,
        pipe.totalSteps,
        pipe.autoEnterActive,
        pipe.elapsedMs,
    )
```

Beide Render-Pfade (Keyboard via SEND_MODE-Mode-Selektion, Widget via
`resolveOverlayRecordButtonText`-Composition) ziehen automatisch die
neue Signatur durch — **Single SoT** ist die `formatPipelineLabel`-
Lambda.

**Trade-off:** `MaterialButton.text` mit `\n` macht zwei Zeilen. Das
14sp-Layout im Keyboard hat genug Höhe. Im Widget ist der Button
ebenfalls `wrap_content`-Höhe — passt. Alternativ: einzeilige Form
`"Transkribiert 1/2 ↵ 0:08"` (kompakter, aber lange Step-Namen
brechen vielleicht). Empfehlung: zweizeilig, weil Step-Namen variabel-
lang werden ("Reword — Casual" hat 16 Zeichen).

### §5.2 Per-Sekunde-Timer-Ticker für Pipeline

Zwei Optionen:

**Variante A — neuer `PipelineActivityTickerObserver`.** Eigener
Observer analog zum `RecordingActivityTickerObserver`. Tickt während
`state.pipeline is Running`, dispatcht jede Sekunde eine Action wie
`Action.PipelineAction.TickPipelineTimer` die im PipelineModule
`elapsedMs = elapsedSince(state.startedAtMs, ctx.now)` re-stamps.
Pro:

- Reines Orchestrator-Pattern; Single SoT für `elapsedMs` bleibt der
  Reducer.
- Beide Render-Pfade profitieren automatisch (sie konsumieren ja
  `state.pipeline.Running.elapsedMs`).
- Sauber testbar (JVM).

Con:

- 1 Action/s × N Sekunden = bissl Dispatch-Last. Bei 30 s
  Pipeline = 30 Reduces. Vernachlässigbar.
- Reducer wird "lebendig" — bisher gilt das nur für Step-Boundaries
  und User-Aktionen.

**Variante B — Side-Channel-Tick auf den Backend-Renderern (analog
`RecordingActivityTickerObserver` mit `onTimerTick`).** Ein neuer
`onPipelineTimerTick(elapsedMs)` an beide Backends; die Backends
formatieren und schreiben den Button-Text direkt aus dem Tick (statt
aus dem Reducer-State).

Pro:

- Kein extra Reducer-Traffic.

Con:

- Bricht Single-Writer-per-Axis: Der Button-Text wird dann von
  ZWEI Pfaden geschrieben — `applySlotToView` (state-driven, an
  Step-Boundaries) und der neue `onPipelineTimerTick` (per-Sekunde).
- Falls die zwei Pfade einen winzigen Zeit-Offset haben, kann es
  Flicker geben.
- Schwer mit dem `ButtonSlot.textResolver`-Modell zu vereinen.

**Empfehlung: Variante A.** Hält Single SoT, geringer Cost.
Implementierung-Detail: `Action.PipelineAction.TickPipelineTimer`
(neue Action, idempotent — wenn `state.pipeline !is Running`, no-op),
PipelineModule-Arm einfach `state.copy(elapsedMs =
elapsedSince(state.startedAtMs, ctx.now))`.

### §5.3 Renderer-Bundle-Parität Keyboard ↔ Widget

Heute (post `dictate-widget-integration`) sind die drei Side-Channel-
Renderer (`autoEnter` / `color` / `recording`) in beiden Backends
instanziiert (Faktoren in `DictatePipelineService:693-724`). Das
funktioniert bereits.

**Fehlt:** `PipelineStepRowRenderer` ist Keyboard-only, weil das
Widget keinen Step-Row-Container hat. Per User-Entscheidung §1.2
korrekt — Phase-Info kommt **nur** in den Button-Text. Dieser Plan
ändert das nicht.

**Symmetrische Verifikation:** ein neuer
`CutoverArchitectureInvariantTest`-Eintrag prüft, dass die
Side-Channel-Forward-Listen in `RecordingActivityTickerObserver`
und im neuen `PipelineActivityTickerObserver` jeweils **beide
Backends** treffen.

### §5.4 Affordance-Hook-Symmetrie (B-A-Fix)

Der IME-Side-Affordance-Lambda muss `OVERLAY_RECORD` UND `RECORD` mit
derselben Body behandeln. **Empfehlung:**

```java
imeSideAffordance = (id, isLongPress) -> {
    if ((id == LogicalButtonId.RECORD || id == LogicalButtonId.OVERLAY_RECORD) && isLongPress) {
        // OVERLAY_RECORD long-press: aktuell keine UX-Bedeutung
        // (Catalog hat keinen longClickResolver am Overlay); der
        // RECORD-Long-Press-Body bleibt RECORD-only.
        onRecordLongClicked();
    } else if (id == LogicalButtonId.RESEND && isLongPress) {
        onResendLongClicked();
    } else if (id == LogicalButtonId.RESEND) {
        // ... unchanged
    } else if (id == LogicalButtonId.RECORD || id == LogicalButtonId.OVERLAY_RECORD) {
        // Snapshot-Helper ist self-gating: kein Body wenn Idle/Preparing.
        prepareCatalogStopRecordingIfActive();
    }
    return kotlin.Unit.INSTANCE;
};
```

**Architektur-Locker:** ein neuer Architektur-Invariant-Test
(`CutoverArchitectureInvariantTest.affordanceHookHandlesBothRecordIds`)
prüft per AST-Grep, dass jede `prepareCatalogStopRecordingIfActive()`-
Call-Site sowohl `RECORD` als auch `OVERLAY_RECORD` als Trigger-IDs
hat. Closes the structural symmetry.

**Alternative-Considered:** ein Alias-Mechanismus
(`LogicalButtonId.OVERLAY_RECORD.aliasFor() == RECORD`) im Hook-
Forwarder. Verworfen weil zu viel Indirektion für 2 IDs.

### §5.5 Backspace Long-Press wieder-aktivieren

Drei Optionen:

**Variante A — `KeyboardInputModule.BackspaceLongPressStart` Action.**
Action emittiert über `longClickResolver` der BACKSPACE-Slots; ein
`KeyboardInputModule`-Effect startet den Accelerating-Repeat-Handler.
ACTION_UP/CANCEL emittiert `BackspaceLongPressEnd`. Pro: durchgängig
Action-Reducer-Effect. Con: Wechsel von User-Touch-Geste auf
Action-Dispatch ist ungewöhnlich — der Cascade läuft im
Effect-Coroutine-Land statt im View-Touch-Land.

**Variante B — IME-Side-Affordance für BACKSPACE.** Long-Press auf
BACKSPACE feuert `imeSideAffordance(BACKSPACE, true)`; die IME-Lambda
ruft `onBackspaceLongClicked()` direkt. ACTION_UP/CANCEL: weiterer
Affordance `imeSideAffordance(BACKSPACE, false)` (oder ein neuer
`onBackspaceUp`-Hook). Pro: minimaler Code-Change; bestehende
`onBackspaceLongClicked` / `onBackspaceDeleteCancelled` Methoden bleiben
unverändert; der ImeViewBackend bekommt nur eine erweiterte
Long-Click-Branch-Bedingung. Con: `imeSideAffordance` wird zur
"Catch-All"-Brücke für legacy IME-Behavior — pragmatisch, nicht
architektonisch sauber. ABER: das ist exakt der KDoc-dokumentierte
Zweck des Hook ("legacy MainButtonsController.Callback button
behaviours that have NO FSM/dispatch representation").

**Variante C — Catalog-Level Touch-Resolver.** Ein neues
`ButtonSlot.touchResolver`-Feld, der ACTION_DOWN/MOVE/UP fängt. Pro:
einheitlich. Con: massive Catalog-API-Erweiterung für genau einen
Use-Case (Backspace-Long-Press) — Overkill.

**Empfehlung: Variante B.** Pragmatisch, minimaler Code-Diff, nutzt
den bereits existierenden Affordance-Mechanismus, behält
`onBackspaceLongClicked` / `onBackspaceDeleteCancelled` als IME-side
implementations. Die ACTION_UP/CANCEL-Cancellation muss separat
gewahrt werden — sie ist heute durch `BackspaceSwipeHandler.onTouch`
ACTION_UP/CANCEL → `onDeleteCancelled()` bereits intakt (das funktioniert
unabhängig vom Long-Press-Start).

Konkret: füge in `ImeViewBackend.wireStaticHandlers` für
`setOnLongClickListener` einen Branch hinzu:

```kotlin
if (id == LogicalButtonId.RECORD ||
    id == LogicalButtonId.RESEND ||
    id == LogicalButtonId.BACKSPACE) {
    imeSideAffordance(id, true)
}
```

Und im IME-Affordance-Lambda:

```java
} else if (id == LogicalButtonId.BACKSPACE && isLongPress) {
    onBackspaceLongClicked();
}
```

### §5.6 Pause-Button-Icon-Duplikation-Fix

Entferne in `activity_dictate_keyboard_view.xml:180-181` die zwei
Zeilen `android:foreground="@drawable/ic_baseline_pause_24"` +
`android:foregroundGravity="center"` am `pause_btn`. Der
`iconResolver = ::resolvePauseIcon` rendert das Pause-Icon dynamisch
über `MaterialButton.icon` — das ist der korrekte Single Writer.

**Sanity-Check:** symmetrisch zum `prompt_pause_btn` (Zeile 516-520),
der ebenfalls `android:foreground="@drawable/ic_baseline_pause_24"`
verwendet. Aber `prompt_pause_btn` wird NICHT vom Catalog gerendert
(er ist im prompts-area XML statisch) — die statische Foreground ist
dort korrekt. Nur `pause_btn` hat den Konflikt.

**Architektur-Locker:** ein Lint-style XML-Check (oder ein
`CutoverArchitectureInvariantTest`-Eintrag) prüft, dass
`pause_btn` kein `android:foreground` mehr hat.

### §5.7 Prompt-Chips-Disable-Logik state-driven

Ersetze in `updatePromptButtonsEnabledState`:

```java
// before
RecordingState state = recordingStateController != null
    ? recordingStateController.getState() : RecordingState.Idle.INSTANCE;
disableNonSelectionPrompts = state.isRecordingOrPaused() || state instanceof RecordingState.Preparing;

// after
DictateUiState ui = pipelineBinder != null ? pipelineBinder.getState().getValue() : null;
boolean recordingBusy = ui != null && (
    ui.getRecording().isActiveOrPaused() ||
    ui.getRecording() instanceof RecordingState.Preparing
);
boolean pipelineBusy = ui != null && (
    ui.getPipeline() instanceof PipelineUiState.Preparing ||
    ui.getPipeline() instanceof PipelineUiState.Running
);
disableNonSelectionPrompts = recordingBusy || pipelineBusy;
```

Plus: register einen Observer auf `pipelineBinder.state` der bei
Änderungen dieser zwei Achsen `updatePromptButtonsEnabledState()`
ruft. Analog zum `PipelineUiStateObserver`-Pattern
(DictateInputMethodService.java:1157-1198).

**Architektur-Locker:** `CutoverArchitectureInvariantTest`-Eintrag
sucht jede `recordingStateController.getState()`-Call-Site in
`DictateInputMethodService.java` und vergleicht gegen eine Whitelist.
Idealerweise wandern alle Reads auf `pipelineBinder.getState().value`,
und `recordingStateController` selbst wird als Klassen-Reste deklariert
(post-cutover dead-code — ein zukünftiger Folge-Plan kann ihn entfernen,
out-of-scope hier).

### §5.8 State-Mgmt-Vereinheitlichung — Verifikation

Nach §5.1–§5.7 sind die State-Mgmt-Differenzen gewichtsmäßig minimiert:

- Pipeline-Label: ein Resolver, ein Format-Lambda — beide Backends teilen.
- Side-Channel-Tick: ein Observer pro Tick-Quelle (Recording, Pipeline),
  jeder fan-out an beide Backends.
- Affordance-Hook: ein Lambda-Body, beide IDs.
- Prompt-Chip-Disable: ein Predicate, einer State-Quelle.
- Pause-Icon: ein Schreibpfad.

**Behoben** = die fünf Bugs. **Verbessert** = die Architektur-
Konsistenz: einmal über die Plan-Lebenszeit gibt es einen
strukturellen Audit-Test, der die Symmetrie locked.

**Verifizierende Tests:**

- `RenderParityTest.kt` — Für eine Reihe von State-Snapshots (Idle,
  Active, Preparing, Running mit Step "Transcribe", Running mit Step
  "Reword", Done): rendere beide Backends gegen Spy-Views; assert dass
  Record-Button-Text-State (Resolver-Output) **byte-identisch** zwischen
  Keyboard-Mode und Widget-Mode ist (modulo der HOVER-disabled Branch).
- `CutoverArchitectureInvariantTest.affordanceHookHandlesBothRecordIds`
  — AST-Grep.
- `CutoverArchitectureInvariantTest.recordingStateControllerNotReadAfterBind`
  — Whitelist-Lock.
- `PipelineTimerTickerTest.kt` — JVM-Test, dass
  `PipelineActivityTickerObserver` mindestens 1 Tick/s während Running
  emittiert.

## §6 Implementations-Blöcke

Fünf Blöcke, sortiert nach Abhängigkeit + Risk-Wert. Quick-Wins zuerst,
Architektur-Touches danach.

### §6.1 Block 1 — Quick-Wins (zwei kosmetische Bugs, low-risk)

**Ziel:** Die zwei Bugs lösen die mit minimalem Code-Diff erreicht
werden — Pause-Icon-Doppellage und Backspace-Long-Press.

- **Chunk 1.1 — Entferne `android:foreground` an `pause_btn`** in
  `app/src/main/res/layout/activity_dictate_keyboard_view.xml:180-181`.
  Manual-Verify auf Gerät; Unit-Test:
  `CutoverArchitectureInvariantTest`-Eintrag der XML-Foreground gegen
  Whitelist prüft. **Effort: S.** (~15 min, betroffen 1 Datei)

- **Chunk 1.2 — Backspace-Long-Press Affordance-Branch.**
  - `ImeViewBackend.wireStaticHandlers` Long-Click-Branch:
    Erweitere die Affordance-Bedingung um `LogicalButtonId.BACKSPACE`
    (`ImeViewBackend.kt:490`).
  - `DictateInputMethodService.imeSideAffordance` Lambda: füge Branch
    `if (id == LogicalButtonId.BACKSPACE && isLongPress)
    onBackspaceLongClicked()` (DictateInputMethodService.java:1417 Bereich).
  - Verify: `onBackspaceDeleteCancelled()` wird von
    `BackspaceSwipeHandler` ACTION_UP weiterhin gerufen — bereits intakt.
  - **Effort: S.** (~30 min, 2 Dateien)

**Block-1-AC:** Pause-Icon zeigt nur Pause-Bars; Backspace-Long-Press
löscht beschleunigend.

### §6.2 Block 2 — Affordance-Hook-Symmetrie (B-A Critical-Fix)

**Ziel:** B-A schließen — `OVERLAY_RECORD` triggert Snapshot.

- **Chunk 2.1 — Erweitere IME-Affordance-Lambda** um
  `OVERLAY_RECORD`-Branches symmetrisch zu `RECORD`. Datei:
  `DictateInputMethodService.java:1415-1462`. Konkrete Edits in §5.4.
  Plus KDoc-Update: "Hook is invoked from both keyboard `record_btn`
  click AND overlay `overlay_record_btn` click; ID-symmetry is the
  R-1-snapshot symmetry for the merged RECORD+SEND slot." **Effort: S.**

- **Chunk 2.2 — Erweitere `prepareCatalogStopRecordingIfActive` KDoc**
  um Hinweis auf OVERLAY_RECORD-Call-Site
  (`DictateInputMethodService.java:3615-3651`). **Effort: S.** (Doku
  only)

- **Chunk 2.3 — `CutoverArchitectureInvariantTest`-Eintrag**:
  `affordanceHookHandlesBothRecordIds`. Sucht im Source-File nach
  `RECORD` und `OVERLAY_RECORD` innerhalb des Affordance-Lambda-Range
  und assertet, dass beide vorkommen. Locks the symmetry. **Effort: M.**

**Block-2-AC:** Widget-SEND führt Pipeline tatsächlich aus; Text landet
im Editor. JVM-Integration-Test mit Fake-Backends + Spy-PipelineRunner.

### §6.3 Block 3 — Prompt-Chips state-driven (B-E)

**Ziel:** B-E schließen — Chips greyed-out bei Recording/Pipeline.

- **Chunk 3.1 — `updatePromptButtonsEnabledState` auf
  `pipelineBinder.state` migrieren.** Datei:
  `DictateInputMethodService.java:4478-4491`. Code per §5.7. **Effort: S.**

- **Chunk 3.2 — Observer registrieren.** In
  `attachImeViewBackendIfReady` (oder im `PipelineUiStateObserver`
  selbst): bei `state.recording` oder `state.pipeline` Änderung
  (distinctUntilChanged), rufe `updatePromptButtonsEnabledState()`.
  Datei: `DictateInputMethodService.java:1157`. **Effort: M.**

- **Chunk 3.3 — Architektur-Invariant-Test:**
  `CutoverArchitectureInvariantTest.recordingStateControllerNotReadOnBoundPath`
  — gleich-grep wie indirection-cleanup AC-7. Whitelist-Lock. **Effort: M.**

**Block-3-AC:** Chips greyed-out bei aktiver Recording + bei aktiver
Pipeline; Re-Click auf gespriegeltem State triggert keine erneute
Action (Race-Test).

### §6.4 Block 4 — Pipeline-Label-Erweiterung (B-D-1)

**Ziel:** B-D-1 schließen — Step-Name im Button-Text. (B-D-2 folgt
strukturell mit nach B-A — keine separate Action nötig, nur Verify.)

- **Chunk 4.1 — `LayoutStrings.formatPipelineLabel`-Signatur
  erweitern** um `stepName: String?` (TextResolvers.kt:81-100).
  Implementierung in `DictatePipelineService.buildLayoutStrings`
  (DictatePipelineService.kt:887-900) — §5.1. **Effort: M.**

- **Chunk 4.2 — `resolveRecordButtonTextPipeline` passt sich an.**
  Datei: TextResolvers.kt:139-154. Lese `pipe.currentStepName` aus
  `state.pipeline as Running`. **Effort: S.**

- **Chunk 4.3 — Update aller Test-Wiring-Sites** für `LayoutStrings`
  (Test-Fixtures) — neue `stepName`-Argument hinzufügen. **Effort: M.**

**Block-4-AC:** Während Running zeigt der Button "Transkribiert\n1/2
0:08" (oder gleichwertiges Format). Beide Backends profitieren.

### §6.5 Block 5 — Pipeline-Timer-Ticker (B-D-3)

**Ziel:** B-D-3 schließen — Per-Sekunde-Timer-Update.

- **Chunk 5.1 — Neue Action `Action.PipelineAction.TickPipelineTimer`**
  (Action.kt). Idempotent — kein payload außer impliziter `ctx.now`.
  **Effort: S.**

- **Chunk 5.2 — `PipelineModule.reduce`-Arm** für `TickPipelineTimer`:
  wenn `state is Running`, `state.copy(elapsedMs = elapsedSince(state.startedAtMs, ctx.now))`;
  sonst null (no-op). **Effort: S.**

- **Chunk 5.3 — `PipelineActivityTickerObserver`** analog zu
  `RecordingActivityTickerObserver` aber state-flow auf `state.pipeline`.
  Subscriben sobald `is Running`, dispatch `TickPipelineTimer` jede 1000
  ms. Stop wenn `!is Running`. **Effort: M.**

- **Chunk 5.4 — Observer-Wiring** in
  `DictateInputMethodService.attachImeViewBackendIfReady` (analog zur
  RecordingActivityTickerObserver-Setup-Stelle ab :1591). **Effort: S.**

- **Chunk 5.5 — Unit-Test** `PipelineTimerTickerTest`. **Effort: M.**

**Block-5-AC:** Während Running: pro Sekunde +1 `elapsedMs`-Sekunden-
Schritt sichtbar im Button-Text. Beide Backends profitieren.

### §6.6 Effort-Summary

| Block | Chunks | Effort | Risiko |
|---|---|---|---|
| Block 1 — Quick-Wins | 1.1, 1.2 | ~45 min | ⚪ low |
| Block 2 — Affordance-Symmetrie (B-A!) | 2.1, 2.2, 2.3 | ~1 h | 🟡 medium |
| Block 3 — Prompt-Chips state-driven | 3.1, 3.2, 3.3 | ~1.5 h | 🟡 medium |
| Block 4 — Label-Erweiterung | 4.1, 4.2, 4.3 | ~1.5 h | ⚪ low |
| Block 5 — Pipeline-Ticker | 5.1–5.5 | ~2 h | 🟡 medium |
| **Gesamt (Code)** | — | **~7 h** | — |
| Plus Tests | — | **~3 h** | — |

## §7 Acceptance-Criteria-Tests

### §7.1 Pro Bug

- **AC-A Test:** `OverlayPipelineHangIntegrationTest.kt` — Fake-IME
  registriert die Affordance, Fake-OverlayBackend simuliert Click,
  assert dass `ImePipelineConfigResolver.freshSnapshots[sessionId]`
  nach dem Click belegt ist UND vor dem Pipeline-Runner-Submit
  konsumiert wird. JVM-Test ohne Android-Context.

- **AC-B Test:** XML-Lint via
  `CutoverArchitectureInvariantTest.pauseBtnHasNoForeground` — grep
  in `activity_dictate_keyboard_view.xml` auf `android:foreground`
  in der `pause_btn` Definition. Plus Manual-Verify.

- **AC-C Test:**
  `BackspaceLongPressIntegrationTest.kt` — Fake-View dispatch
  long-click, assert dass `imeSideAffordance(BACKSPACE, true)` gefeuert
  wird, dass `deleteHandler.postDelayed(deleteRunnable, 50ms)` läuft,
  dass nach 1.5 s der `currentDeleteDelay` auf 25 ms flippt.

- **AC-D-1 Test:** `ResolverTest.kt` — `resolveRecordButtonTextPipeline`
  mit `state.pipeline = Running(stepHistory = [running("Transcribe")])`
  liefert einen String der `"Transcribe"` enthält.

- **AC-D-2 Test:** strukturell durch AC-A erfüllt; Manual auf Gerät.

- **AC-D-3 Test:** `PipelineTimerTickerTest.kt` — Fake-Time-Source,
  dispatch `TickPipelineTimer` nach 1 s, assert `state.pipeline.Running.elapsedMs ≥ 1000`.

- **AC-E Test:** `PromptChipDisableTest.kt` — Fake-State mit
  `recording = Active`, assert `disableNonSelectionPrompts == true`
  ohne `recordingStateController` zu touchen.

### §7.2 State-Mgmt-Parität

- **AC-P-1 Test:**
  `CutoverArchitectureInvariantTest.noLegacyRecordingStateControllerReadsAfterBind`.

- **AC-P-2 Test:** `RenderParityTest.keyboardAndOverlayShareSameLabelResolver`
  — JVM, beide LayoutCatalog-Sites für Pipeline-Running referenzieren
  `resolveRecordButtonTextPipeline` direkt oder via
  `resolveOverlayRecordButtonText`-Composition.

- **AC-P-3 Test:** `TickerFanOutTest.recordingTickerHitsBothBackends`
  + `pipelineTickerHitsBothBackends`.

- **AC-P-4 Test:** `CutoverArchitectureInvariantTest.affordanceHookHandlesBothRecordIds`.

## §8 Risks

### §8.1 Implementations-Risiken

- **R-1: Layout-Breaking durch zweizeiliges Label.** Der erweiterte
  `formatPipelineLabel` mit `\n`-Separator könnte zu breit / zu hoch
  für die `record_btn`-Constraint in `activity_dictate_keyboard_view.xml:91-112`
  sein. Mitigation: Manual auf Gerät; ggf. `maxLines="2"` + `singleLine=false`
  in der XML; Layout-Test mit Espresso. **Severity: medium.**

- **R-2: Per-Sekunde-Dispatch erzeugt UI-Glitches.** Wenn der
  TickPipelineTimer-Reducer State-Updates erzeugt, die andere
  Renderer (z.B. `PipelineStepRowRenderer`) als "Change" interpretieren,
  könnten unnötige Diff-Passes laufen. Mitigation:
  `distinctUntilChanged` auf den relevanten Achsen (elapsedMs ist
  in keinem anderen Renderer relevant außer dem Label); `PipelineStepRowRenderer`
  hat einen `appliedKey`-Cache (siehe `PipelineStepRowRenderer.kt:117`)
  der den Diff überspringt. **Severity: low.**

- **R-3: Affordance-Hook im Pre-Bind-Window.** Wenn der User im WIDGET
  klickt, BEVOR der IME `registerImeSideAffordance` aufgerufen hat
  (e.g. App startet, Widget öffnet sich, IME hat noch nicht bound),
  ist `delegateImeSideAffordance == null` und der Click ist ein
  silent no-op (kein Snapshot, kein Dispatch — der Lambda im
  OverlayBackend ist `null`-safe). Strukturell: das WIDGET ist nur
  via User-Toggle aus KEYBOARD erreichbar (T1) → IME war vorher
  bound, also Affordance ist registered. Mitigation: defensive
  Pre-Bind-Log + manueller Stresstest. **Severity: low** (strukturell
  unerreichbar).

- **R-4: Backspace-Long-Press Race mit Swipe.** ACTION_DOWN startet
  beide: System-Long-Press-Detection UND BackspaceSwipeHandler. Wenn
  der User auf Backspace ACTION_DOWN macht, dann nach 100 ms swiped:
  Long-Press hat noch nicht gefeuert (Default 500 ms), Swipe-State
  aktiviert sich, BackspaceSwipeHandler ruft `v.cancelLongPress()` →
  Long-Press wird abgebrochen → kein Affordance-Trigger. Korrekt.
  Wenn der User ACTION_DOWN macht, dann nach 600 ms steht still:
  Long-Press feuert → Affordance → Delete-Cascade startet.
  ACTION_UP: BackspaceSwipeHandler ruft `onDeleteCancelled()` →
  Cascade stoppt. Korrekt. Mitigation: Espresso-Test mit drei
  Sequenzen (kurze Press, lange Press, lange Press + Swipe).
  **Severity: low.**

- **R-5: `PipelineStepRowRenderer` ist Keyboard-only.** Wenn der User
  im Widget eine lange Pipeline läuft und mid-pipeline zu Keyboard
  schließt (T2): der Step-Row-Renderer im Keyboard sieht plötzlich
  die volle `stepHistory` und muss alle Rows nachholen. Heute
  funktioniert das (der Renderer ist Diff-basiert); Manual-Verify auf
  Gerät. **Severity: low.**

### §8.2 Architektur-Risiken

- **R-6: User-Wunsch §1.3 ist eigentlich schon erfüllt.** Der Plan
  formalisiert was bereits gilt (gemeinsame StateFlow). Die "Magie"
  des "Widget triggers Keyboard-Änderungen" ist die Triangle-FSM —
  ADR-0005-konformer ViewMode-Wechsel. Mitigation: §10 Change-History
  dokumentiert den Wunsch explizit und referenziert ADR-0005 als
  Grund. **Severity: kosmetisch.**

## §9 Open Questions

> Diese Fragen müssen vor Implementation geklärt werden. Pro Frage:
> Empfehlung + Alternative. Wenn keine User-Entscheidung in der
> Plan-Genehmigung, gilt die Empfehlung.

### §9.0 Status — ALLE ENTSCHIEDEN (2026-05-21)

User-Review-Session (2026-05-21) hat alle fünf OQs aufgelöst. Drei via
AskUserQuestion-Dialog, zwei (OQ-3, OQ-4) durch implizite Übernahme der
Empfehlung im selben Dialog:

| OQ | Entscheidung | Variante |
|---|---|---|
| OQ-1 | Pipeline-Label zweizeilig | A |
| OQ-2 | Step-Name 1:1 durchreichen (keine i18n) | A |
| OQ-3 | `recordingStateController` als `@Deprecated` lassen, Removal-Folge-Plan | A |
| OQ-4 | Pipeline-Ticker-Intervall 1000 ms | (Empfehlung) |
| OQ-5 | Widget-Long-Press = no-op | A |

Implementer kann ohne weitere Rücksprache mit Block 1 starten.
Details zu den Optionen + Begründung in den §9.1–9.5 Sub-Sektionen.

### §9.1 OQ-1 — Pipeline-Label-Layout zweizeilig oder einzeilig?

**Variante A — zweizeilig:** `"Transkribiert\n1/2 ↵ 0:08"` (Step-
Name oben, Counter+Timer unten). Pro: Step-Name lesbar (kein
Mid-String). Con: Button-Höhe wächst.

**Variante B — einzeilig:** `"Transkribiert · 1/2 ↵ 0:08"`. Pro:
weniger Layout-Disturbanz. Con: lange Step-Namen brechen, Texts
shrink.

**Empfehlung:** **Variante A.** Heute hat der `record_btn` bereits
`textSize="14sp"` mit 2-Zeilen-Höhe-Budget; QwertzRecordingController
(`QwertzRecordingController.kt:229`) verwendet schon zweizeilig
(`"$counter$enterIndicator\n$timer"`). **Konsistenz** mit der QWERTZ-
Mini-Anzeige.

### §9.2 OQ-2 — Welcher Step-Name-String soll dem User angezeigt werden?

Heute wird der Step-Name in `Action.PipelineAction.StepStarted` vom
Pipeline-Runner geliefert
(`PipelineRunnerSubsystemAdapter` → orchestrator dispatch).
Pipeline-Runner liefert Strings wie `"Transcription"` /
`"Reword: Casual"` / `"Format"` / `"Insert"`. Sollen diese 1:1
durchgereicht werden, oder sollen sie via `R.string.*`-Indirektion
internationalisiert werden?

**Variante A — 1:1 durchreichen:** keine i18n; was der Runner sendet,
zeigt der Button. Pro: einfach; bereits implementiert in
`PipelineStepRowRenderer.kt:227` mit `nameTv.text = row.stepName`.

**Variante B — `R.string.*`-Lookup:** der Runner sendet keys wie
`"step_transcription"`; das Backend-Label-Code holt
`getString(R.string.step_transcription)`. Pro: i18n. Con:
zusätzliche Indirektion, weitere String-Resourcen.

**Empfehlung: Variante A.** Konsistent mit dem heutigen
`PipelineStepRowRenderer`-Verhalten. i18n der Step-Namen ist ein
separater Folge-Plan (`dictate-pipeline-step-name-i18n`).

### §9.3 OQ-3 — Soll `recordingStateController` post-cutover gelöscht werden?

Nach Block 3 ist `recordingStateController.getState()` nicht mehr
gelesen auf dem Bound-Pfad. Aber: er wird in
`DictateInputMethodService:724` immer noch instanziiert (für
Pre-Bind-Fallback?). Sein Verbleib ist Dead-Code post-Bind.

**Variante A — lassen.** Pre-Bind-Fallback bleibt funktional;
out-of-scope hier; deletion in einem Folge-Plan
(`dictate-recording-state-controller-removal`).

**Variante B — sofort löschen.** Riskanter; pre-Bind-Reads ohne Fallback
würden NullPointerException werfen; eigener Plan-Scope.

**Empfehlung: Variante A.** Markiere die Klasse mit
`@Deprecated("Post-cutover dead code; remove in follow-up plan")`.
In-Scope dieses Plans: nur die Reads aus `updatePromptButtonsEnabledState`
ersetzen.

### §9.4 OQ-4 — Pipeline-Ticker-Intervall: 1 s, 100 ms oder adaptiv?

Recording-Ticker tickt mit 100 ms. Pipeline-Timer ist sekundengranular
(`%d:%02d`). 100 ms-Pipeline-Tick wäre Verschwendung
(10 Dispatches/s ohne sichtbare Änderung).

**Empfehlung:** **1000 ms** (= 1 Sekunde). Sichtbar-Granular,
minimaler Reducer-Traffic.

### §9.5 OQ-5 — Soll der OVERLAY_RECORD-Long-Press ein eigenes Behavior bekommen?

Heute: der OVERLAY_RECORD hat kein `longClickResolver`. Der Affordance-
Erweiterung-Vorschlag in §5.4 zieht den `RECORD`-Long-Press-Body auf
`OVERLAY_RECORD` mit (Idle → Settings + file-picker launch +
autoSwitchKeyboard). Im WIDGET-Modus aber: macht ein
"open Settings"-Launch Sinn? Der User hat das Widget vor sich, nicht
das Keyboard. Der Settings-Launch öffnet eine Activity, die das
Widget verdecken würde (oder Activity-launch aus Service-Context ist
ein UX-Bruch).

**Variante A — Long-Press im Widget no-op.** Lambda checkt
`if (id == LogicalButtonId.OVERLAY_RECORD && isLongPress) return;`.

**Variante B — Long-Press im Widget = `onRecordLongClicked()`** (gleich
wie Keyboard). User kann Settings vom Widget aus öffnen.

**Empfehlung: Variante A.** UX-Aware-Konservativ. Long-Press im
Widget ist ungewöhnlich; wenn User Settings öffnen will, ist
KEYBOARD-Modus + Long-Press der intuitive Weg.

## §10 Change History

### 2026-05-21 — Initial draft

- **Trigger:** User-Beobachtung am Gerät (Samsung S24 Ultra, Android
  16) nach Live-Demo der zwei abgeschlossenen Plans (`dictate-indirection-cleanup`,
  `dictate-widget-integration`). Fünf konkrete Regressionen gemeldet
  mit verbatim Quotes (B-A bis B-E).
- **What:** Comprehensive Research + Plan. Fünf Root-Cause-Maps mit
  Confidence-Scoring; Architektur-Analyse Keyboard- vs. Widget-Render-
  Kette; State-Achsen-Inventar; fünf Implementations-Blöcke
  geordnet nach Risk + Dependency; AC-Tests pro Bug + AC-P-Parität-
  Tests; fünf Open Questions.
- **Key findings:**
  - **B-A Critical Root-Cause:** IME-Affordance-Lambda branched auf
    `LogicalButtonId.RECORD`, OverlayBackend feuert `OVERLAY_RECORD`
    → keine R-1-Snapshot → Pipeline hängt forever in `Preparing`.
    Confidence: **high**.
  - **B-B Pause-Icon-Doppellage:** XML `android:foreground` + Catalog
    `iconResolver` schreiben BEIDE; das XML-Foreground ist Relikt aus
    Pre-Catalog-Ära. Confidence: **high**.
  - **B-C Backspace-Long-Press weg:** `onBackspaceLongClicked()`
    existiert noch, hat aber keinen Caller. CR-DEL hat den Aufrufer
    entfernt, ohne den Catalog-Long-Press-Hook anzubinden.
    Confidence: **high**.
  - **B-D-1 Phase-Name fehlt:** `formatPipelineLabel`-Signatur hat
    keinen `stepName`-Parameter. Confidence: **high**.
  - **B-D-2 Counter fehlt:** strukturelles Folge-Symptom von B-A
    (Pipeline erreicht `Running` nie). Confidence: **medium** (= klärt
    sich nach B-A-Fix).
  - **B-D-3 Timer tickt nicht:** Kein
    `PipelineActivityTickerObserver` existiert; `elapsedMs` wird nur
    an Step-Boundaries re-stamped. Confidence: **high**.
  - **B-E Prompt-Chips tappable:** `updatePromptButtonsEnabledState`
    liest `recordingStateController.getState()` (legacy, post-cutover
    nicht driven). Confidence: **high**.
- **Status:** Spec — programmer-ready (pending OQ-1 ... OQ-5
  resolution by user-review). Implementer kann nach OQ-Klärung mit
  Block 1 (Quick-Wins) starten. Block 2 (B-A Critical-Fix) ist der
  höchste-Wert-Block.

### 2026-05-21 — OQ-Resolution + Implementer-ready

- **Trigger:** User-Review der fünf Open Questions in einer
  AskUserQuestion-Session.
- **What:** Alle fünf OQs entschieden (siehe §9.0). Frontmatter
  `status` von `Spec — programmer-ready (pending OQ resolution)` auf
  `Implementer-ready` umgestellt.
- **Outcome:** Plan ist freigegeben für die Implementation-Wave.
  Block-Reihenfolge: 1 (Quick-Wins B-B/B-C) → 2 (B-A Critical) →
  3 (B-E) → 4 (B-D-1) → 5 (B-D-3).
