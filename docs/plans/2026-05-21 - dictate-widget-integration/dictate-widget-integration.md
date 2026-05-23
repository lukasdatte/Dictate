---
date: 2026-05-21
author: Lukas + Claude Code (research session)
type: Plan
status: Implementer-ready
context: Floating-Overlay (WIDGET + HOVER) ist nach dem Theme-Wrapper-Fix vom 2026-05-21 inflate-fähig, aber funktional unvollständig. Der RECORD-Button im Overlay zeigt keine Timer-/Amplitude-/State-Texte; SEND löst die Pipeline aus, aber die R-1-`JobRequest`-Snapshot-Affordance fehlt → Pipeline hängt in `Preparing`; TRASH/PAUSE sind korrekt verdrahtet, aber ihre Visuals (PulseLayout, ↵-Icon, Failure-Farbe) sind im Overlay nicht angeschlossen. Ziel: Wiederverwendung der bestehenden Side-Channel-Renderer + Slot-Resolver + `imeSideAffordance`-Hook im Overlay-Pfad, ohne parallele Implementation, mit dem einzigen Unterschied, dass SEND in HOVER strukturell disabled bleibt (keine InputConnection).
related-plans:
  - 2026-05-07 - dictate-keyboard-layout-refactor (Mutterplan — Spec 3 ist die Architektur-SoT)
  - 2026-05-21 - dictate-render-cutover-completion-vol2 (Vorgänger — Single-Writer-per-Axis auf der Render-Seite)
  - 2026-05-21 - dictate-indirection-cleanup (Schwester — Single-Dispatch-per-Axis auf der Input-Seite)
related-adrs: ADR-0004, ADR-0005
archive_target: 2026-05-21 - dictate-widget-integration
---

## §1 Ziel

Den **Floating-Widget-Modus** (`ViewMode.WIDGET` + `ViewMode.HOVER`) auf
das Niveau des Keyboard-Modus heben: derselbe reichhaltige RECORD-Button
(mit PulseLayout, Timer, Amplitude, state-driven Texten "Sending…" /
"Send (de)" / "Record (de)" / "2/3 ↵ 0:08", Auto-Enter-↵-Icon,
Failure-Farbe), gleiche TRASH-/PAUSE-Funktionalität, gleiche
Wiring-Konventionen — **mit dem einzigen Unterschied**, dass SEND im
HOVER-Modus strukturell disabled bleibt, weil keine `InputConnection`
als Ziel existiert. Reuse, nicht Re-Implementation.

## §2 User-Anforderungen (verbatim, 2026-05-21)

> "So hätte ich gerne meinen Aufzeichnen-Button mit Timer und allem
> Drum und Dran, mit den Texten, die darauf angezeigt werden. Natürlich
> muss er um Features erweitert werden.
>
> Das bedeutet: Senden darf nicht möglich sein, während gerade kein
> Tastaturinput verfügbar ist. Das ist eigentlich der einzige große
> Unterschied zwischen den verschiedenen Systemen.
>
> Der Löschen-Button sollte identisch sein und die gleiche
> Funktionalität haben: Aufzeichnung löschen.
>
> Der Pause-Button soll der gleiche Pause-Button sein, mit dem gleichen
> Wiring."

Konsequenz: **ein** Pfad für RECORD/TRASH/PAUSE/SEND, mit
ViewMode-getriebenen Resolver-Branches an genau einer Stelle (= der
HOVER-Send-Gate).

## §3 Soll-vs-Ist-Matrix

Vier User-Anforderungen, je eine Matrix-Zeile. **Soll** zitiert Spec 3 §
und ADR-0005 §; **Ist** zitiert konkrete Datei:Zeile aus dem aktuellen
Stand (2026-05-21, nach Theme-Wrapper-Fix).

### §3.1 RECORD-Optik (Timer / Amplitude / State-Texte / ↵ / Pulse / Failure-Farbe)

| Aspekt | Soll | Ist | Lücke |
|---|---|---|---|
| **State-Texte** ("Record (de)" / "Send (de)" / "Sending…" / "Sending… ↵" / "2/3 ↵ 0:08") | Spec 3 §3.1 OVERLAY_RECORD-Slot soll dieselbe State-driven Logik nutzen wie KEYBOARD_TWO_ROW.RECORD (Spec 2 §8.1). Konkret: `textResolver = resolveRecordButtonText` für Idle/Active/Paused/Preparing **und** `resolveRecordButtonTextPipeline` für Running. | `LayoutCatalog.kt:521-538` definiert OVERLAY_RECORD-Slot **ohne `textResolver`**, nur `iconResolver = R.drawable.ic_baseline_mic_24`. OVERLAY_SEND (`:539-564`) hat `textResolver = { strings.overlaySend }` = statisches `"Send"`. | Kein dynamischer Text. User sieht im Overlay weder Timer noch Phase-Text noch Counter. |
| **Timer-Tick (MM:SS)** | Spec 3 §3.1 KDoc und §11.5 implizieren, dass die Recording-Activity-Ticker-Side-Channel sowohl Keyboard- als auch Overlay-Render-Pfad bedienen. Side-Channel `RecordingAnimationController.onTimerTick(elapsedMs)` formatiert `"%02d:%02d"` (RecordingAnimationController.kt:122-127). | `DictateInputMethodService.java:1497-1526` baut `RecordingActivityTickerObserver` und forwarded **nur** an `imeViewBackend.onTimerTick(...)` + `qwertzRecordingController.onTimerTick(...)`. Der `OverlayBackend` hat keine `onTimerTick`-Methode (`OverlayBackend.kt:138-521` zeigt keine Forwarder-API), und der Observer ruft sie nicht. | Keine Timer-Frames im Overlay. |
| **Amplitude / PulseLayout** | Spec 3 §3.1 OVERLAY_RECORD soll, wenn Recording aktiv ist, dieselbe Border-Glow-Amplitude-Animation + `PulseLayout`-Wrapper anzeigen wie KEYBOARD. Side-Channel-Owner: `RecordingAnimationController` (Spec 2 §11.5). | Der `RecordingAnimationController` wird in `DictateInputMethodService.java:1264-1269` mit `recordPulseLayout` (IME-View-eigene PulseLayout, `activity_dictate_keyboard_view.xml:91`) gebaut. Er wird **nur** an `ImeViewBackend` übergeben (`:1428`); `OverlayBackend.kt:112-136`-Konstruktor hat keinen `RecordingAnimationController`-Parameter. Das Overlay-Layout `overlay_5button_layout.xml:24-32` hat überhaupt keine `PulseLayout`-Wrapper-View. | Keine Pulse-Animation im Overlay. Animationen können auch nicht hinzugefügt werden, weil weder Wrapper-View noch Renderer-Instanz existieren. |
| **Auto-Enter ↵-Icon (Right-Compound-Drawable)** | Spec 3 §3.1 + ADR-0005 §"Required mechanics": OVERLAY_RECORD sollte in `Running` denselben ↵-Right-Drawable zeigen wie KEYBOARD_TWO_ROW_SEND_MODE.RECORD. Side-Channel-Owner: `AutoEnterRenderer` (Phase 3 von dictate-render-cutover-completion-vol2). | `AutoEnterRenderer` wird in `DictateInputMethodService.java:1273-1274` auf `recordButton` (IME-View `record_btn`) konstruiert. Wird nur an `ImeViewBackend` weitergegeben (`:1429`). `OverlayBackend` hat keinen Slot für `AutoEnterRenderer`. | Kein ↵-Icon im Overlay. |
| **Failure-Farbe (rot bei `Running.hasFailure == true`)** | `RecordButtonColorController` ist Single-Writer der `setTextColor`-Achse für `record_btn` (Vol2 Phase 5.A). Soll auch im Overlay-`record_btn`-Äquivalent gelten. | `RecordButtonColorController` wird in `DictateInputMethodService.java:1278-1280` auf IME-View `recordButton` konstruiert, nur an `ImeViewBackend` weitergegeben. Kein Pendant im Overlay-Pfad. | Keine rote Schrift im Overlay bei Pipeline-Failure. |
| **TextSize / TextColor / Layout-Weight** | Der Keyboard-`record_btn` hat `textSize="14sp"`, `maxLines="1"`, `match_parent`-width innerhalb der `PulseLayout` (`activity_dictate_keyboard_view.xml:106-112`). | Das Overlay-Layout (`overlay_5button_layout.xml`) hat OVERLAY_RECORD als reinen 48dp×48dp Icon-Button und OVERLAY_SEND mit fester `minWidth="100dp"` und `weight="1"` (`:41-62`). Kein gemeinsamer Button "RECORD-mit-Timer-und-Text". | Layout-Architektur unterstützt die Optik nicht — wir brauchen entweder Layout-Umbau oder einen anderen Button-Mapping-Vorschlag (siehe §6). |

### §3.2 SEND-Wirkung (Pipeline-Trigger via InputConnection)

| Aspekt | Soll | Ist | Lücke |
|---|---|---|---|
| **InputConnection-Verfügbarkeit** | Spec 3 §3.1 (`enabledResolver = state.viewMode == WIDGET && state.recording.isActiveOrPaused`) + ADR-0005 §"Alternatives" Pkt. 5: SEND ist nur dann strukturell sinnvoll, wenn ein gültiger `InputConnection` als Ziel existiert. WIDGET = IME-View visible = `getCurrentInputConnection()` valid. HOVER = IME-View hidden = `getCurrentInputConnection()` null. | `LayoutCatalog.kt:546-549` realisiert genau diesen `enabledResolver`. **Soll-konform.** | **Keine Lücke** beim Gate selbst. |
| **R-1 `JobRequest`-Snapshot vor Dispatch** | Die `prepareCatalogStopRecordingIfActive()`-Helper-Affordance (DictateInputMethodService.java:3503-3539) muss VOR der `Action.RecordingAction.StopRecordingAndSend`-Dispatch laufen, damit `imePipelineConfigResolver.snapshotFresh(...)` den fresh-snapshot in seine Map legt, bevor `PipelineRunnerSubsystemAdapter.resolveFresh()` async ihn liest. Ohne Snapshot → loud `UnsupportedOperationException` (R-1 silent-data-loss tripwire) → Pipeline-FSM bleibt in `Preparing` → endloses "Sending…". | `ImeViewBackend.kt:439-441` ruft `imeSideAffordance(RESEND/RECORD, false)` für die zwei Keyboard-RECORD-/RESEND-Click-Sites. `OverlayBackend.kt:355-366` (`wireStaticOverlayHandlers`) ruft **keinen** `imeSideAffordance`-Hook — der `OverlayBackend`-Konstruktor hat überhaupt keinen entsprechenden Parameter (`:112-136`). | **Smoking-Gun für "SEND tut nichts".** Catalog dispatcht `StopRecordingAndSend`, Orchestrator weiß nicht, welche `audioFile`/welche IME-Runtime-Felder gelten, Pipeline crasht silent in `EffectFailure`-Arm, State friert in `Preparing` ein, Overlay-Button-Text bleibt "Sending…", nichts wird in die App geschrieben. |
| **Wo wird Transcript in InputConnection committed?** | `DictateInputMethodService.java:4003` `commitTextToInputConnection(text, source)` wird vom `onPipelineDone`-Callback gerufen. Das nutzt `getCurrentInputConnection()` (Zeile 4168), das nur in `KEYBOARD`/`WIDGET`-Modi ein gültiges Objekt liefert (IME-View sichtbar). | Wäre korrekt verdrahtet, **sobald** Pipeline tatsächlich `Done` erreicht (was sie heute aus Overlay nicht tut wegen R-1-Bug oben). | Folgt aus dem R-1-Bug — keine separate Lücke. |
| **HOVER-Send (defensives Gate)** | Sollte unmöglich sein — `enabledResolver` macht den Button alpha=0.4 + `isEnabled=false`, Android schluckt Clicks auf disabled Views. | `LayoutCatalog.kt:546-549` setzt das. **Doppelt-Defensive im Resolver:** falls Click trotzdem ankommt (Race), `actionResolver = { _, _ -> Action.RecordingAction.StopRecordingAndSend }` (`:561-563`) feuert blind. Im HOVER-Modus würde `commitTextToInputConnection` dann auf `getCurrentInputConnection() == null` laufen und null-safe returnen. | **Lücke (defensive):** der actionResolver sollte selbst `state.viewMode != WIDGET ⇒ null` returnen, symmetrisch zu `resolveOverlayRecordAction` (`ActionResolvers.kt:264`). |

### §3.3 TRASH (Aufzeichnung verwerfen)

| Aspekt | Soll | Ist | Lücke |
|---|---|---|---|
| **Action** | Spec 3 §3.1 + User-Wunsch: identisches Verhalten zum Keyboard-TRASH. Keyboard-Resolver: `resolveTrashAction` (`ActionResolvers.kt:187-199`) — branched ReprocessStaging vs Idle vs CancelRecording. | `LayoutCatalog.kt:593-595` setzt OVERLAY_TRASH `actionResolver = { _, _ -> Action.RecordingAction.CancelRecording }` direkt — **ohne** ReprocessStaging-Branch. | Im Overlay wird im ReprocessStaging-Pipeline-Sub-State (was hier aber nicht erreichbar ist, weil Spec 3 §10 ReprocessStaging als KEYBOARD-only definiert) der falsche Action emittiert. **Reduzierte Lücke** — strukturell unerreichbar, aber DRY-Verletzung gegen die Single-Source. |
| **Visibility** | Spec 3 §3.1 + §10: TRASH sichtbar **wenn** Recording aktiv/paused **oder** Pipeline nicht idle. | `LayoutCatalog.kt:590-593` setzt genau diesen `visibilityPredicate`. Soll-konform. | **Keine Lücke.** |
| **Side-Channel** | Kein Side-Channel-Renderer für TRASH (Icon ist statisch via XML, kein Pulse/Timer/Color). | Korrekt — kein zusätzlicher Renderer nötig. | **Keine Lücke.** |

### §3.4 PAUSE (Aufnahme pausieren oder fortsetzen)

| Aspekt | Soll | Ist | Lücke |
|---|---|---|---|
| **Action** | Keyboard-Resolver: `resolvePauseAction` (`ActionResolvers.kt:209-217`). Overlay-Resolver: `resolveOverlayPauseAction` (`ActionResolvers.kt:286-293`). **Beide haben identischen Body**. | `LayoutCatalog.kt:580` ruft `resolveOverlayPauseAction`, der byte-identisch zu `resolvePauseAction` ist. | **Reine DRY-Verletzung** — zwei Funktionen mit identischem Body. User-Wunsch "gleiches Wiring" = ein Resolver. |
| **Icon-Resolver** | `resolvePauseIcon` (Spec 2 §8.5; existiert) swaps `ic_baseline_mic_24` vs `ic_baseline_pause_24`. | `LayoutCatalog.kt:573-579` ruft `resolvePauseIcon(state)` direkt — **Soll-konform**, ein Resolver, beide Layouts. | **Keine Lücke.** |
| **enabled / alpha** | `enabled = state.recording.isActiveOrPaused`, `alpha = 1f if active else 0.4f`. | `LayoutCatalog.kt:569-572` setzt das. Soll-konform. | **Keine Lücke.** |
| **Side-Channel** | Kein Side-Channel-Renderer für PAUSE (Icon ist via `iconResolver` ausreichend ausgedrückt). | Korrekt. | **Keine Lücke.** |

### §3.5 Zusammenfassung der Lücken

| # | Lücke | Severity | Wo |
|---|-------|----------|-----|
| L-1 | RECORD-Optik: kein `textResolver` für Overlay-Record-Button | 🔴 (User-bemerkt) | OVERLAY_5BUTTON OVERLAY_RECORD-Slot |
| L-2 | RECORD-Optik: kein Timer-Side-Channel-Forwarder im Overlay-Pfad | 🔴 (User-bemerkt) | `DictateInputMethodService.java:1497-1526` + `OverlayBackend`-API |
| L-3 | RECORD-Optik: keine PulseLayout-Wrapper-View im Overlay-XML, kein `RecordingAnimationController`-Slot im OverlayBackend | 🔴 (User-bemerkt) | `overlay_5button_layout.xml` + `OverlayBackend`-Konstruktor |
| L-4 | RECORD-Optik: kein `AutoEnterRenderer`-Slot im OverlayBackend | 🔴 (User-bemerkt) | `OverlayBackend`-Konstruktor |
| L-5 | RECORD-Optik: kein `RecordButtonColorController`-Slot im OverlayBackend | 🔴 (User-bemerkt) | `OverlayBackend`-Konstruktor |
| L-6 | SEND-Wirkung: kein `imeSideAffordance(RECORD, false)`-Hook im Overlay-Click-Pfad | 🔴 (User-bemerkt, latenter Daten-Verlust) | `OverlayBackend.wireStaticOverlayHandlers` |
| L-7 | SEND-Wirkung: HOVER-Send `actionResolver` ist nicht defensiv | 🟡 (Race-Window) | `LayoutCatalog.kt:561` |
| L-8 | TRASH: ReprocessStaging-Branch im OVERLAY_TRASH-Resolver fehlt | 🟢 (strukturell unerreichbar) | `LayoutCatalog.kt:594` |
| L-9 | PAUSE: doppelte Funktion `resolveOverlayPauseAction` vs `resolvePauseAction` | 🟢 (DRY) | `ActionResolvers.kt:286-293` |

## §4 State-Kopplung-Analyse

### §4.1 Heutige Side-Channel-Topologie

```
                  ┌──────────────────────────────────────────────────────┐
                  │ DictatePipelineService (process-survivor; Spec 1 §7) │
                  │   - StateFlow<DictateUiState>                         │
                  │   - KeyboardLayoutManager.attachBackend(b)            │
                  │   - OverlayBackend (built in Step 8, line 674)        │
                  └──────────────────────────────────────────────────────┘
                                  │                            │
                                  │ state-emits                │ state-emits
                                  │                            │
                                  ▼                            ▼
       ┌──────────────────────────────────┐    ┌──────────────────────────────────┐
       │ ImeViewBackend (per onCreateInput│    │ OverlayBackend (process-survivor)│
       │  -View; line 1209+)               │    │  - render(state, OVERLAY_5BUTTON)│
       │                                   │    │  - 5 button-views (overlay XML) │
       │ Owns: 9 button views (IME XML)    │    │                                  │
       │ Owns: 4 side-channel renderers ▼  │    │ Owns: 0 side-channel renderers   │
       └──┬────────────────────────────────┘    └──────────────────────────────────┘
          │
   ┌──────┴───────────────────────────────────────────┐
   │ side-channel-renderers — bound to IME-View VIEWS │
   │  - RecordingAnimationController(recordBtn, pulse)│
   │  - AutoEnterRenderer(recordBtn)                  │
   │  - RecordButtonColorController(recordBtn)        │
   │  - PipelineStepRowRenderer(stepsContainer, …)    │
   └──────────────────────────────────────────────────┘

side-tick (timer + amplitude) — NOT in StateFlow:
   RecordingActivityTickerObserver (line 1501)
       ▼
   imeViewBackend.onTimerTick(elapsedMs)     ← forward-1 (line 1504)
   imeViewBackend.onAmplitude(level)         ← forward-2 (line 1515)
   qwertzRecordingController.onTimerTick(…)  ← forward-3 (line 1506)
   ✗ NO forward to OverlayBackend            ← this is the gap
```

### §4.2 Warum hat das Overlay heute keinen Anschluss?

Drei strukturelle Gründe, alle aus dem Implementierungs-Sequencing 2026-05-07 → 2026-05-21:

1. **Service-vs-View-Lifecycle-Mismatch.** Die Side-Channel-Renderer
   sind **View-bound** — `RecordingAnimationController` hält
   `recordPulseLayout: PulseLayout?` (Konstruktor-Arg,
   `RecordingAnimationController.kt:54`), `AutoEnterRenderer` hält
   `recordButton: MaterialButton`
   (`AutoEnterRenderer.kt:75`), genauso `RecordButtonColorController`.
   Diese Views leben im IME-View-Layout
   (`activity_dictate_keyboard_view.xml`), dessen Lebenszyklus
   `onCreateInputView` → `cleanupOldControllers` ist. Der OverlayBackend
   hingegen wird **einmal** in
   `DictatePipelineService.onCreate` (Zeile 674) gebaut und überlebt
   die ganze Service-Lebenszeit (= IME-Re-Inflates). Eine einzelne
   Renderer-Instanz pro Side-Channel kann also nicht beide Backends
   bedienen — die View-Refs würden dangling werden.

2. **Single-Writer-per-Axis-Invariante.** Die Vol2-Plan-Closure
   (ADR-0005 Decision-History 2026-05-21) hat als zentrale Regel
   etabliert: **für jede UI-Property des `record_btn` (text / icon-left
   / icon-right / enabled / alpha / setTextColor) genau ein
   Schreibpfad.** Wenn wir den `RecordingAnimationController` "wieder­ver­wenden",
   müssen wir sicherstellen, dass NICHT beide Backends gleichzeitig
   denselben Renderer halten und auf unterschiedliche `record_btn`-Views
   schreiben — das wäre ein neuer Cross-Window-Dual-Writer. Lösung:
   pro Backend eine *eigene* Renderer-Instanz auf jeweils
   eigene Views (kein dangling), aber dieselbe Renderer-**Klasse**
   (kein Code-Dup).

3. **Overlay-XML hat keine PulseLayout-Wrapper-View.** Das
   `overlay_5button_layout.xml` (siehe Datei) hat einen 48dp×48dp
   reinen Icon-Button für OVERLAY_RECORD und einen separaten Text-Button
   für OVERLAY_SEND. Es gibt keinen einzelnen "Record-Mega-Button" mit
   `match_parent`-Breite, `textSize="14sp"`, drumherum eine
   PulseLayout-View. Das ist eine **Layout-Architektur-Entscheidung**, die
   das User-Wunsch-Visual heute nicht ausdrücken kann. Layout-Umbau ist
   nötig (siehe §6 Option-Bewertung).

### §4.3 State-Achsen, die im Overlay heute landen

Was der `OverlayBackend.render` **bereits** konsumiert:

| State-Achse | Konsumiert durch | Quelle |
|---|---|---|
| `state.overlay.hasPermission` | Permission-Gate in `render` | `OverlayBackend.kt:207-210` |
| `state.overlay.suppressAutoOverlayUntilNextSession` | Suppress-Gate in `render` | `OverlayBackend.kt:214-217` |
| `state.overlay.positionPortrait{X,Y}` / `positionLandscape{X,Y}` | `applyPosition` | `OverlayBackend.kt:404-408` |
| `state.viewMode` | Indirekt über `LayoutCatalog`-Resolver (z.B. OVERLAY_SEND.enabledResolver) | Resolver-Side |
| `state.recording.isActiveOrPaused` | Indirekt über Resolver | Resolver-Side |
| `state.pipeline` (für Trash-Visibility, Pause-Enabled) | Indirekt über Resolver | Resolver-Side |

Was **fehlt** — die per-Tick-Side-Channels (Timer / Amplitude) und die
post-state-emit-Side-Channels (PulseLayout-State-Klasse-Transition,
AutoEnter-Drawable-Diff, Failure-Color-Flip):

| Side-Channel | Quelle | Wer ruft heute auf | Wer müsste ins Overlay rufen |
|---|---|---|---|
| Timer-Tick (`MM:SS`) | `RecordingActivityTickerObserver` (process-side) | `imeViewBackend.onTimerTick(elapsedMs)` (line 1504) | `overlayBackend.onTimerTick(elapsedMs)` (gibt's nicht — API-Erweiterung nötig) |
| Amplitude-Tick (0..1) | `RecordingActivityTickerObserver` | `imeViewBackend.onAmplitude(level)` (line 1515) | `overlayBackend.onAmplitude(level)` (gibt's nicht) |
| RecordingState-Klassen-Transition (Idle→Active→Paused) | Reactive: `recordingAnimationController.onState(state)` aus `ImeViewBackend.render` (line 285) | `RecordingAnimationController` (IME-View) | Eigene Instanz in `OverlayBackend.render` |
| AutoEnter-Compound-Drawable-Diff | Reactive: `autoEnterRenderer.onState(state)` aus `ImeViewBackend.render` (line 267) | `AutoEnterRenderer` (IME-View) | Eigene Instanz in `OverlayBackend.render` |
| Failure-Color-Flip | Reactive: `recordButtonColorController.onState(state)` aus `ImeViewBackend.render` (line 273) | `RecordButtonColorController` (IME-View) | Eigene Instanz in `OverlayBackend.render` |

`PipelineStepRowRenderer` ist bewusst NICHT in dieser Liste — der
Stepper-View lebt im IME-Content-Area
(`pipelineStepsContainer`/`pipelineScrollView` aus
`activity_dictate_keyboard_view.xml`) und gehört konzeptionell zum
Keyboard-Render-Pfad. Im Overlay hätte er keinen Anbringungsort und keine
sinnvolle UX (das Widget ist 270×110dp, hat keinen Scrollbereich).

## §5 SEND-Wirkung — vertiefte Code-Trace

Ziel: **wo bricht es ab, wenn ich SEND im Overlay klicke**?

```
[User klickt overlay_send_btn]
    ↓
OverlayBackend.wireStaticOverlayHandlers (line 355-366)
    ↓ catalog look-up
LayoutCatalog.OVERLAY_5BUTTON OVERLAY_SEND.actionResolver (line 561-563)
    ↓ returns Action.RecordingAction.StopRecordingAndSend (unconditional)
onAction.invoke(StopRecordingAndSend)
    ↓
PipelineBinder.dispatch(StopRecordingAndSend)
    ↓
RecordingModule.reduce(StopRecordingAndSend, ctx)
    ↓ FSM Active|Paused → Idle + sideEffect: SubmitPipeline(sessionId, …)
PipelineModule.reduce(SubmitPipeline)
    ↓ state.pipeline := Preparing(sessionId, …)
    ↓ sideEffect: ImePipelineSubmitSubsystemAdapter.submit(JobRequest)
PipelineRunnerSubsystemAdapter.submit
    ↓ async coroutine
ImePipelineConfigResolver.resolveFresh(sessionId)         ← reads freshSnapshots map
    ↓
    freshSnapshots[sessionId]  →  null  (NEVER POPULATED FROM OVERLAY)
    ↓
ImePipelineConfigResolver.assertFreshSnapshotPresent
    ↓
    throw UnsupportedOperationException("R-1 silent-data-loss tripwire …")
    ↓
DictateOrchestrator.dispatchInternal catches via EffectFailure-arm
    ↓ state.pipeline stays in Preparing  ← user sees endless "Sending…"
    ↓ no PipelineDone, no commitTextToInputConnection, no UI feedback
```

### §5.1 Wo der R-1-Snapshot fehlt

Im Keyboard-Pfad wird `prepareCatalogStopRecordingIfActive()` durch den
`imeSideAffordance`-Hook gefeuert, der in
`ImeViewBackend.wireStaticHandlers` (line 439-441) für RECORD/RESEND
explizit gesetzt ist:

```java
if (id == LogicalButtonId.RESEND || id == LogicalButtonId.RECORD) {
    imeSideAffordance(id, false);
}
```

Das ruft den Lambda aus
`DictateInputMethodService.java:1399-1419`, der bei `RECORD && !isLongPress`
den `prepareCatalogStopRecordingIfActive()`-Helper aufruft — der packt
den `audioFile`-Snapshot + die R-1-Felder in
`imePipelineConfigResolver` BEVOR der Catalog `dispatch` macht. Im
Overlay-Pfad fehlt dieser Hook komplett:

- `OverlayBackend.kt:355-366` `wireStaticOverlayHandlers` macht **nur**
  `actionResolver(state, services)?.let { onAction?.invoke(it) }`.
- Der OverlayBackend-Konstruktor (`:112-136`) hat keinen
  `imeSideAffordance`-Parameter.
- Der Hook ist auch nicht im `ModuleServices`-Interface verfügbar.

### §5.2 ViewMode-Gate-Asymmetrie

Eine zweite, subtile Asymmetrie: das Keyboard-RECORD bei Active|Paused
emittiert über `resolveRecordAction` (`ActionResolvers.kt:106-107`)
einen `StopRecordingAndSend` **ohne** Insertion-Target-Check, weil der
Keyboard-Pfad strukturell garantiert, dass die IME-View visible ist
(sonst wäre der Klick gar nicht möglich). Das Overlay-SEND
emittiert das gleiche, aber im HOVER-Modus wäre der Target dead.

`enabledResolver` (`LayoutCatalog.kt:546-549`) macht das defensiv,
indem die View `isEnabled=false` ist. Android schluckt Clicks auf
disabled MaterialButton. **Aber:** wenn der `enabledResolver`
zwischen Tap-Down und Tap-Up das Bit flippt (Race-Window), könnte
ein Klick durchschlagen. Korrekt wäre `actionResolver`-doppelt-defensiv:
`if (state.viewMode != WIDGET) return null` (symmetrisch zu
`resolveOverlayRecordAction:264`).

### §5.3 InputConnection-Bind-Vertrag im WIDGET-Modus

Im WIDGET ist die IME-View visible (sonst kann der User den Toggle gar
nicht klicken — Spec 3 §7.1 computeViewMode-Truth-Table). Damit ist
`getCurrentInputConnection()` valid. Pipeline-Done-Callback
(`DictateInputMethodService.java:3989-4010`) ruft
`commitTextToInputConnection(text, source)` → `getCurrentInputConnection().commitText(...)`
→ Text landet im fokussierten Editor.

**Wichtig:** das funktioniert nur, solange die IME-View während der
gesamten Pipeline-Laufzeit visible bleibt. Wenn der User mid-Pipeline
den Overlay-Close klickt (T2), wird die IME-View klein (SmallMode), die
Pipeline läuft weiter, am Ende landet der Text im Editor. Wenn der User
mid-Pipeline die App wechselt, geht IME-View weg, Pipeline läuft auf dem
FGS weiter (ADR-0003), bei `PipelineDone` ist
`getCurrentInputConnection()` aber null → `commitText` schlägt fehl. Das
ist die HOVER-Phase, und für diese existiert die `Notification`-Fallback-
UI (Spec 3 §9). Außerhalb des Scopes dieses Plans.

## §6 Architektur-Optionen

Drei Optionen zur Implementierung der RECORD-Optik-Wiederverwendung;
SEND/TRASH/PAUSE folgen jeweils analog.

### §6.1 Option 1: Overlay zeigt embedded IME-View

**Idee:** Der OverlayBackend rendert die komplette IME-`MotionLayout`-View
in einer Card im Floating-Window. Alle bestehenden Renderer und Slots
funktionieren ohne Anpassung — sie sehen dieselben Views.

**Bewertung:**

- ✗ **Android-Hard-Constraint:** Eine View kann nur in einem Window
  leben (Spec 3 §2 O5). Die IME-View ist im IME-Window; sie kann nicht
  parallel im Overlay-Window sein. Ein zweiter Inflate ist möglich, aber
  das ist dann eine eigene View-Instanz — kein "embedded".
- ✗ **Lifecycle-Mismatch:** Die IME-View lebt im
  `onCreateInputView`-Cycle; der OverlayBackend lebt im
  Service-Cycle. View-Recreate bei Rotation würde das Overlay
  invalidieren.
- ✗ **Implementations-Aufwand:** WindowManager-Embedded-Views sind eine
  experimentelle Android-API (`SurfaceView`/`SurfaceControlViewHost`),
  nicht im Use-Case.

**Verdikt: nicht machbar.**

### §6.2 Option 2: Separate Overlay-Views + 2× Side-Channel-Instanzen, gleiche Klassen

**Idee:**

1. **Overlay-Layout umbauen** (`overlay_5button_layout.xml`): ein
   reicher `record_btn`-Äquivalent — `match_parent`-width im
   `PulseLayout`-Wrapper, `textSize`-passend, `textColor`-default white,
   `compound-drawables`-fähig. Reihe 1: `[record_btn (mit Pulse)
   FillRemaining]` (kombiniert RECORD + SEND in einem Button — der Text
   ist state-driven, die Action ist state-driven). Reihe 2 bleibt:
   `[trash_btn] (spacer) [pause_btn] (spacer) [close_btn]` (drei
   48dp-Icons). Oder Variante 2a: drei Reihen, mit separatem SEND-Button
   unterhalb — siehe §6.5.
2. **OverlayBackend-Konstruktor erweitert um vier optionale Side-Channel-
   Renderer-Slots**: `recordingAnimationController`, `autoEnterRenderer`,
   `recordButtonColorController`, `imeSideAffordance` (Function2). Die
   ersten drei sind dieselben Klassen, aber neue Instanzen, gebaut mit
   den Overlay-View-Refs (`overlayRecordButton`, `overlayPulseLayout`).
3. **DictatePipelineService.onCreate baut die Overlay-Instanzen** der
   Side-Channel-Renderer. Aber: Service hat keine View-Refs (die
   leben im OverlayBackend nach `inflateAndAttach`). Variante: der
   OverlayBackend baut die Instanzen selbst in `inflateAndAttach` (line
   277-312) — analog zu `wireStaticOverlayHandlers` —, weil dann
   die Views verfügbar sind.
4. **OverlayBackend bekommt zwei neue Side-Channel-API-Methoden:**
   `onTimerTick(elapsedMs)` und `onAmplitude(level)`, die einfach an
   den eigenen `recordingAnimationController` durchreichen (analog
   `ImeViewBackend.onTimerTick:302-304`).
5. **RecordingActivityTickerObserver** in
   `DictateInputMethodService.java:1497-1526` bekommt einen dritten
   Forwarder: `overlayBackend.onTimerTick(elapsedMs)`. Da der
   `overlayBackend` allerdings auf dem `PipelineService` lebt und nicht
   im IME-Service, müssen wir die Forward-Path via Service-Binder
   herstellen oder die Ticker-Observer-Owner umparken — siehe §7
   Implementations-Sequencing.
6. **`imeSideAffordance`-Hook ans OverlayBackend übergeben** und im
   `wireStaticOverlayHandlers` für OVERLAY_SEND/OVERLAY_RECORD VOR dem
   Catalog-Dispatch feuern — symmetrisch zu
   `ImeViewBackend.kt:439-441`.

**Bewertung:**

- ✓ **Reuse der Klassen:** `RecordingAnimationController`,
  `AutoEnterRenderer`, `RecordButtonColorController` werden mit
  Overlay-View-Refs instantiiert — kein neuer Renderer-Code, nur neue
  Instanzen.
- ✓ **Reuse der Resolver:** OVERLAY_RECORD-Slot bekommt
  `textResolver = resolveRecordButtonText` (ohne Pipeline-Live-Variante,
  weil das Overlay sich nicht zwischen TWO_ROW und TWO_ROW_SEND_MODE
  unterscheidet — sondern in einem Slot je nach state.pipeline
  branched). Konkret: ein neuer Top-Level-Helper
  `resolveOverlayRecordButtonText(state, strings)` wäre der saubere Weg,
  der intern `resolveRecordButtonText` vs `resolveRecordButtonTextPipeline`
  branched.
- ✓ **Reuse des `imeSideAffordance`-Hooks:** dieselbe
  `prepareCatalogStopRecordingIfActive()`-Helper, jetzt auch vom Overlay
  gefeuert. Single-Source der R-1-Snapshot-Logik.
- ✓ **Single-Writer-per-Axis bleibt erhalten:** die zwei Instanzen
  schreiben auf zwei verschiedene Views (IME-`record_btn` vs
  Overlay-`record_btn`), keine Konflikte.
- ✓ **SOLID:** SRP intakt (jeder Renderer bedient eine Achse), OCP
  intakt (neue Backends bekommen ihre eigenen Renderer-Instanzen via
  Konstruktor-Injection), DIP intakt (Renderer-Klassen kennen keine
  Backend-Spezifika).
- ✓ **Sustainable:** ein zukünftiger Reader sieht den symmetrischen
  Patten "jeder Backend hat sein eigenes Set von Side-Channel-Renderer-
  Instanzen, alle dieselbe Klasse" — wartbar.
- ⚠ **Layout-Umbau:** Nicht-trivial. Das Overlay-XML hat heute keinen
  PulseLayout-Wrapper. Wir brauchen eine neue XML-Variante. Schritt-
  weise machbar (siehe §7 Block 2).
- ⚠ **Doppelte Renderer-Instanzen:** Memory-Footprint
  ≈ +30KB (vier kleine Klassen mit je 1-2 Refs + Cache-Felder). Akzeptabel.

**Verdikt: empfohlen.**

### §6.3 Option 3: Slot-System wird vollständiger Renderer

**Idee:** Alle Side-Channel-Logik (PulseLayout, Timer-Format,
Amplitude-Visualizer, AutoEnter-Icon-Diff, Failure-Color) wandert in
neue `ButtonSlot`-Resolver-Felder (`pulseResolver`, `amplitudeResolver`,
`timerResolver`, `failureColorResolver`, `compoundDrawableResolver` ×
2). Die `applySlotToView`-Helper-Logik wird massiv erweitert. Side-
Channel-Renderer-Klassen werden eliminiert. Beide Backends fahren über
dasselbe einheitliche Slot-Apply.

**Bewertung:**

- ✓ **Konzeptionelle Einheit:** EIN Apply-Pfad pro Render-Tick, keine
  Side-Channels mehr.
- ✗ **Sprengt den Plan-Rahmen:** Vol2-Plan-Closure hat **ausdrücklich**
  entschieden (Q1-Decision §7 Q1 Cutover-vol2): Side-Channels sind die
  korrekte Heimat für stateful Animationen (PulseLayout startet/pausiert/
  stoppt), Per-Tick-Forwards (Timer/Amplitude) und das dynamische
  AutoEnter-`BitmapDrawable` (kein `@DrawableRes`-Resolver-Idiom).
  Diese Entscheidung umkehren wäre ein Architektur-Reset, kein
  Bug-Fix.
- ✗ **R.2 Pure-Reducer-Vertrag:** Stateful Animation kann nicht aus
  einem reinen `(state) -> ViewProperty`-Resolver fallen. Wir würden
  entweder die R.2-Garantie brechen oder den Slot-Renderer mit
  Mutable-State versehen — beides ist ein Schritt zurück.
- ✗ **Klassen-Elimination unerwünscht:** `RecordingAnimationController`
  & Co. sind als bewusste Abstraktion entworfen
  (`RecordingAnimationController.kt:18-26` KDoc: "Animations sind
  stateful — that lifecycle cannot be expressed by the
  `ButtonSlot` pure-resolver model").
- ⚠ **Effort:** L (>2h pro Side-Channel), gesamt ~6-10h, plus Test-
  Suite-Umbau.

**Verdikt: nicht in diesem Plan-Rahmen.** Falls je gewollt: eigener
Folge-Plan `dictate-side-channel-elimination` — Architektur-Reform-
Diskussion mit ADR-Update.

### §6.4 Engineering-Baseline-Bewertung

| Kriterium | Option 1 | Option 2 | Option 3 |
|---|---|---|---|
| Maintainability (6-Monats-Reader) | n/a | ✓ symmetrisches Pattern, ein Glance | ⚠ neuer Apply-Pfad mit non-trivial Stateful-Slot-Resolvern |
| Serviceability (Debug/Logging) | n/a | ✓ jeder Renderer hat eigene Cache, isolierte Tests | ⚠ globale Slot-Apply ist Single-Point-of-Failure |
| Extensibility (neue Backend / neue Achse) | n/a | ✓ konstruktoriell aufnehmbar | ⚠ jede neue Achse erweitert das gemeinsame Resolver-Set |
| Engineering-Baseline §1 ("most sustainable") | unmöglich | **empfohlen** | nicht in diesem Scope |

### §6.5 Layout-Variante 2a — "ein Button"-vs-"zwei Buttons" im Overlay

**Variante 2a (empfohlen):** OVERLAY_RECORD und OVERLAY_SEND
**zusammengeführt** zu einem einzigen reichen Button (`overlay_record_btn`),
analog zum Keyboard-`record_btn`. Der Button-Text ist state-driven:
- Idle: "Record (de)"
- Active: "Send" (im WIDGET enabled, im HOVER disabled)
- Paused: "Send" (gleiche Regel)
- Preparing: "Sending…"
- Running: `"2/3 ↵ 0:08"`

Vorteil: 1:1-Symmetrie zum Keyboard, weniger Klassen-Auseinanderfallen.
Eine Slot-Definition, eine Click-Action (`resolveRecordAction`
wiederverwendbar im Overlay-Slot), eine `enabledResolver`-Logik
(`state.recording !is Preparing` für Active/Paused, aber zusätzlich
`state.viewMode == WIDGET` im Active/Paused-Branch).

**Variante 2b (alternativ):** OVERLAY_RECORD bleibt 48dp Icon-Button
für Idle-Start, OVERLAY_SEND ist der reiche Button (mit Timer / Texten)
für Active/Paused/Preparing/Running, sichtbar nur dann. Vorteil:
weniger Layout-Umbau, näher am aktuellen Visual. Nachteil:
Slot-Definition-Komplexität — zwei Buttons mit `visibilityPredicate`-
Verschwenkung statt einem state-driven Button.

**Empfehlung: Variante 2a** — User-Wunsch "_genau der Aufzeichnen-Button
mit Timer und allem Drum und Dran_" liest sich eindeutig nach
1:1-Match zur Keyboard-Optik. Variante 2b ist Kompromiss; 2a ist
Reuse-pur.

→ Konsequenz für die Implementation: Layout-XML neu zeichnen, OVERLAY_RECORD
und OVERLAY_SEND mergen, Slot-Resolver-Set neu zusammensetzen. Die
Layout-Resultate beider Modi:
- WIDGET in Idle: ein zentraler "Dictate (de)"-Button, daneben kleine
  Trash (gone in Idle), Pause (gone in Idle), Close.
- WIDGET in Active: ein zentraler "Send"-Button mit PulseLayout um sich,
  daneben Trash (sichtbar), Pause (sichtbar), Close.
- HOVER in Active: derselbe Button als "Send" (disabled, alpha 0.4,
  weil keine InputConnection), daneben Trash + Pause + Close.

## §7 Akzeptanzkriterien

Jedes Kriterium ist technisch verifizierbar — Code-Greps, JVM-Unit-Tests
oder reproduzierbare Device-Steps.

- **AC-1: RECORD-Optik im Overlay zeigt state-driven Text.** Im WIDGET-
  Modus in `state.recording == Idle && state.pipeline == Idle` zeigt
  der Overlay-RECORD-Button "Dictate (de)" (oder dem effektiven
  Sprach-Code entsprechend). In `Active/Paused` zeigt er "Send". In
  `Preparing` zeigt er "Sending…". In `Running` zeigt er den Counter +
  ↵-Format. Verifizierbar via JVM-Test, der `OverlayBackend.render`
  mit verschiedenen State-Snapshots aufruft und den Button-Text
  inspiziert.

- **AC-2: Timer + Amplitude laufen im Overlay-Record-Button** während
  `Active`. Nach Druck von OVERLAY_RECORD (Idle → Active) sieht der
  User die Border-Glow-Animation, die Amplitude-Bars und einen
  MM:SS-Timer im Button-Text-Bereich (Spec 2 §11.5 Verhalten). Manual-
  Verify (Device-Test); JVM-Verify: `RecordingActivityTickerObserver`
  ruft `overlayBackend.onTimerTick(elapsedMs)` mindestens 1× bei
  `RecordingState.Active`-Emit.

- **AC-3: SEND im Overlay löst tatsächlich die Pipeline aus** (R-1-
  Snapshot wird gemacht). Nach SEND-Click landet der `freshSnapshot`
  in `imePipelineConfigResolver` BEVOR `PipelineRunnerSubsystemAdapter.resolveFresh`
  asynchron läuft. Pipeline transitionet `Preparing → Running →
  Done`, `commitTextToInputConnection` schreibt in den fokussierten
  Editor. Verifizierbar via Integration-Test (Espresso-style) oder
  Device-Manual-Test "Aufnahme starten → Overlay öffnen via Toggle →
  SEND klicken → Text muss in Editor erscheinen".

- **AC-4: SEND ist im HOVER strukturell unmöglich.** Im HOVER-Modus
  (IME-View hidden + Pipeline aktiv) ist der Overlay-Send-Button
  visuell alpha=0.4, `isEnabled=false`. Doppelt-Defensive: selbst wenn
  ein Click durchschlägt, returnt der `actionResolver` `null`. JVM-Test:
  `OverlayBackend.render(stateWithViewModeHover, OVERLAY_5BUTTON)`,
  dann simulierter Click-Event auf `overlay_send_btn` → Verifikation
  dass `onAction` NICHT mit `StopRecordingAndSend` gerufen wird.

- **AC-5: TRASH im Overlay verwendet `resolveTrashAction`** (Keyboard-
  Resolver). Nach Klick auf OVERLAY_TRASH in `RecordingState.Active|Paused`
  emittiert der Catalog `Action.RecordingAction.CancelRecording` —
  identisches Verhalten zum Keyboard-TRASH. JVM-Test parametrisiert
  über alle (recording, pipeline)-Kombinationen.

- **AC-6: PAUSE im Overlay verwendet `resolvePauseAction`** (Keyboard-
  Resolver, kein dedicated OverlayResolver mehr).
  `resolveOverlayPauseAction` ist als Symbol entfernt; `LayoutCatalog.OVERLAY_5BUTTON.OVERLAY_PAUSE.actionResolver`
  zeigt `::resolvePauseAction`. JVM-Test:
  `OverlayBackend`-Klick im `Active` ⇒ `PauseRecording`,
  Klick im `Paused` ⇒ `ResumeRecording`.

- **AC-7: AutoEnter-↵-Icon erscheint im Overlay-Record-Button** im
  `Running` mit `autoEnterActive == true`. Manual-Verify (Pipeline
  starten + Doppel-Tap auf SEND → ↵-Icon erscheint rechts vom
  Counter-Text). JVM-Verify: `AutoEnterRenderer.onState` wird vom
  `OverlayBackend.render` aufgerufen, schreibt den `RightCompoundDrawable`
  auf `overlay_record_btn`.

- **AC-8: Failure-Farbe (rot) erscheint im Overlay-Record-Button** bei
  `Running.hasFailure == true`. Symmetrisch zum Keyboard
  (Vol2 Phase 5.A). JVM-Verify: `RecordButtonColorController.onState`
  forwarded an `overlay_record_btn.setTextColor(red)` bei der
  entsprechenden State-Klasse.

- **AC-9: Single-Writer-per-Axis-Invariante bleibt erhalten.** Jede
  Schreibpfad-Axe des `overlay_record_btn` hat genau einen Schreiber:
  - `text` → `SlotRenderer.applySlotToView`
  - `isEnabled` → `SlotRenderer.applySlotToView`
  - `alpha` → `SlotRenderer.applySlotToView`
  - `compound-drawables left/right` → `AutoEnterRenderer` (Overlay-Instanz)
  - `setTextColor` → `RecordButtonColorController` (Overlay-Instanz)
  - Border-Glow + Pulse → `RecordingAnimationController` (Overlay-Instanz)
  Verifizierbar via `CutoverArchitectureInvariantTest`-Erweiterung
  (grep auf `overlay_record_btn.setForeground` / `.setText` /
  `.setTextColor` außerhalb der erlaubten Owner).

- **AC-10: `imeSideAffordance`-Hook ist symmetrisch zu Keyboard.**
  `OverlayBackend.wireStaticOverlayHandlers` ruft den Hook **vor** dem
  Catalog-Dispatch für OVERLAY_RECORD (und für OVERLAY_SEND, falls
  separat im Variante-2b-Layout). Statische `CutoverArchitectureInvariantTest`-
  Assertion: das Click-Branching in `OverlayBackend.wireStaticOverlayHandlers`
  muss `imeSideAffordance(LogicalButtonId.OVERLAY_RECORD, false)`
  als String enthalten (innerhalb 200-Char-Window).

- **AC-11: SOLID-Konformität.** Keine neue Klasse wird eingeführt, die
  bestehende SRP-Grenzen verletzt. Die Side-Channel-Renderer-Klassen
  bleiben unverändert (nur neue Instanzen). Der `OverlayBackend`-
  Konstruktor wächst um 4 Felder (3 Renderer + 1 Affordance-Lambda) —
  alle optional mit Defaults für JVM-Tests, identisch zum
  `ImeViewBackend`-Pattern (Konstruktor-Argumente
  `recordingAnimationController` / `autoEnterRenderer` / etc. sind
  bereits `null`-able dort).

- **AC-12: PAUSE / TRASH-DRY-Cleanup.** `resolveOverlayPauseAction` und
  `resolveOverlayCloseAction` (sofern PAUSE+CLOSE-Counterparts gleich
  bleiben können) als getrennte Funktionen ENTfernt. Für CLOSE
  ist der Branch ViewMode-spezifisch (`when(state.viewMode)`) — der
  bleibt als eigener Resolver `resolveOverlayCloseAction` bestehen
  (kein Keyboard-Counterpart). Für PAUSE: die Overlay-Slot referenziert
  direkt `::resolvePauseAction`.

## §8 Implementations-Blöcke / -Chunks

Vier Blöcke, sequentiell. Block 1 ist Foundation (Layout-XML + Side-
Channel-Forwarder-API). Block 2 wirelt die Renderer-Instanzen. Block 3
implementiert den `imeSideAffordance`-Hook. Block 4 räumt DRY-Sünden auf.

### §8.1 Block 1 — Layout + OverlayBackend-API-Erweiterung

**Ziel:** Strukturelle Voraussetzungen schaffen — neues Layout-XML mit
PulseLayout-Wrapper, OverlayBackend-Konstruktor um Renderer-Slots
erweitern, `onTimerTick`/`onAmplitude`-Forwarder-API ergänzen. Keine
Verhaltensänderung — Renderer-Instanzen werden in Block 2 verdrahtet,
bis dahin bleibt OVERLAY_5BUTTON unverändert.

- **Chunk 1.1 — Layout-XML neu zeichnen** (Variante 2a, §6.5).
  Datei: `app/src/main/res/layout/overlay_5button_layout.xml`.
  Neue Struktur:
  ```
  Reihe 1: [PulseLayout > MaterialButton(record_btn, match_parent)]
  Reihe 2: [Trash 48dp] [Spacer w=1] [Pause 48dp] [Spacer 6dp] [Close 48dp]
  ```
  Das einzelne `overlay_send_btn`-Element entfernen — der `record_btn`
  übernimmt SEND-Funktion via state-driven Text/Action.
  `MaterialButton` mit `textSize="14sp"`, `maxLines="1"`,
  `iconGravity="textStart"`. Die `PulseLayout`-Wrapper analog zu
  `activity_dictate_keyboard_view.xml:91-114`. ID-Kontrakt: weiterhin
  `overlay_record_btn` (jetzt auch SEND-Slot), `overlay_trash_btn`,
  `overlay_pause_btn`, `overlay_close_btn` + neu `overlay_pulse_layout`.
  OVERLAY_SEND-ID wird **gestrichen**. **Effort: S.**

- **Chunk 1.2 — `LogicalButtonId`-Cleanup**.
  Datei: `app/src/main/java/net/devemperor/dictate/state/layout/LogicalButtonId.kt`.
  `OVERLAY_SEND` als Enum-Member entfernen, weil der Slot strukturell
  weggefallen ist. Cross-Spec-Verifikation: alle `LayoutCatalog`-Stellen,
  die `OVERLAY_SEND` referenzieren, müssen mitfallen
  (`LayoutCatalog.kt:540`). **Effort: S.**

- **Chunk 1.3 — OverlayBackend-Konstruktor erweitern um 4 Slots.**
  Datei: `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt`.
  Neue optionale Parameter (Defaults: `null` / `{ _, _ -> }`):
  - `recordingAnimationController: RecordingAnimationController? = null`
  - `autoEnterRenderer: AutoEnterRenderer? = null`
  - `recordButtonColorController: RecordButtonColorController? = null`
  - `imeSideAffordance: (LogicalButtonId, Boolean) -> Unit = { _, _ -> }`

  **Wichtig:** die Side-Channel-Renderer können **nicht** im Konstruktor
  injiziert werden (Service hat noch keine View-Refs). Vorschlag: stattdessen
  ein **Factory-Lambda** `recordingAnimationControllerFactory:
  (recordButton: MaterialButton, pulseLayout: PulseLayout) ->
  RecordingAnimationController = ::DefaultRecordingAnimationController`
  (analog für die anderen). Die Factories werden dann in `inflateAndAttach`
  (line 277-312) gerufen, sobald die Views da sind. Alternativ: ein
  einziges `OverlayRendererBundle`-Interface mit drei `init(views)`-
  Methoden — diskutieren in Code-Review.

  Plus zwei neue Forwarder-Methoden:
  - `fun onTimerTick(elapsedMs: Long) { recordingAnimationController?.onTimerTick(elapsedMs) }`
  - `fun onAmplitude(level: Float) { recordingAnimationController?.onAmplitude(level) }`

  Plus `updateAccentColor(color)` (analog ImeViewBackend.kt:310-312).
  **Effort: M.**

- **Chunk 1.4 — OverlayBackend `inflateAndAttach` baut Renderer-
  Instanzen** und verdrahtet sie im `render`-Tick (analog
  `ImeViewBackend.render:267-285`). Konkret nach `wireDragController(view)`
  (Zeile 311) ein neuer Block:
  ```
  val recordBtn = buttonViews[OVERLAY_RECORD] as MaterialButton
  val pulseLayout = view.findViewById<PulseLayout>(R.id.overlay_pulse_layout)
  rendererBundle = OverlayRendererBundle(
      recording = RecordingAnimationController(animationFactory(recordBtn), pulseLayout, animationsEnabled),
      autoEnter = AutoEnterRenderer(recordBtn),
      color = RecordButtonColorController(recordBtn, …),
  )
  ```
  In `render` nach `applySlots(state, mode)` (Zeile 237) drei Forwards:
  `rendererBundle?.autoEnter.onState(state)`,
  `rendererBundle?.color.onState(state)`,
  `rendererBundle?.recording.onState(state)`.
  In `teardownOverlay` (Zeile 476): `rendererBundle?.reset(); rendererBundle = null`.
  **Effort: M.**

**Block-1-AC:** OverlayBackend kompiliert mit neuen Slots; bestehende
Tests bleiben grün (Side-Channels noch nicht in Production verdrahtet);
Layout-XML rendert visuell korrekt (manueller Inflate-Test). Der RECORD-
Button im Overlay zeigt **noch** keinen state-driven Text — das macht
Block 2.

### §8.2 Block 2 — Slot-Resolver-Wiederverwendung im OVERLAY_5BUTTON-Catalog

**Ziel:** OVERLAY_5BUTTON.OVERLAY_RECORD bekommt `textResolver`,
`actionResolver`, `enabledResolver` aus dem Keyboard-Pfad. SEND-Slot
strukturell weggefallen (Block 1). PAUSE / TRASH referenzieren direkt
Keyboard-Resolver.

- **Chunk 2.1 — Neuer Helper `resolveOverlayRecordButtonText(state,
  strings)`** in `TextResolvers.kt`.
  Body:
  ```
  fun resolveOverlayRecordButtonText(state: DictateUiState, strings: LayoutStrings): CharSequence =
      when (state.pipeline) {
          is PipelineUiState.Preparing,
          is PipelineUiState.Running -> resolveRecordButtonTextPipeline(state, strings)
          else -> resolveRecordButtonText(state, strings)
      }
  ```
  Idee: das Overlay hat einen Button für alle Pipeline-Sub-States, daher
  branchen wir hier statt im Catalog-Layout-Mode (wie es Keyboard mit
  TWO_ROW vs TWO_ROW_SEND_MODE macht). **Effort: S.**

- **Chunk 2.2 — Neuer Helper `resolveOverlayRecordAction(state,
  services)`** in `ActionResolvers.kt`. **Erweiterung** des bestehenden
  `resolveOverlayRecordAction` (Zeile 263-278): zusätzliche Branches für
  `Active|Paused → StopRecordingAndSend` (nur in WIDGET, in HOVER null).
  Pipeline-Sub-Branches: `Preparing|Running → ToggleRunningAutoEnter`
  (analog `resolveRecordActionPipeline`, line 156-176, dort werden Idle-
  Pipeline-Sub-States aufgelöst). **Effort: M.**

- **Chunk 2.3 — OVERLAY_RECORD-Slot in LayoutCatalog umbauen**.
  Datei: `LayoutCatalog.kt:521-538`. Neuer Body:
  ```
  ButtonSlot(
      logicalId = LogicalButtonId.OVERLAY_RECORD,
      widthPolicy = WidthPolicy.FillRemaining,
      visibilityPredicate = { true },  // Always visible (state branches via resolvers)
      textResolver = { state -> resolveOverlayRecordButtonText(state, strings) },
      enabledResolver = { state -> resolveOverlayRecordEnabled(state) },
      alphaResolver = { state -> if (resolveOverlayRecordEnabled(state)) 1f else 0.4f },
      iconResolver = { state -> resolveOverlayRecordIcon(state) },  // optional; mic in Idle
      actionResolver = ::resolveOverlayRecordAction,
  )
  ```
  Plus die Helper `resolveOverlayRecordEnabled(state)` (Idle: WIDGET;
  Active|Paused: WIDGET; Preparing|Running: WIDGET; alle in HOVER:
  false). **Effort: M.**

- **Chunk 2.4 — OVERLAY_SEND-Slot aus LayoutCatalog löschen** (Zeile
  539-564). Strukturell weggefallen. **Effort: S.**

- **Chunk 2.5 — OVERLAY_PAUSE-Slot referenziert `::resolvePauseAction`**.
  Datei: `LayoutCatalog.kt:580`. Cross-Spec-Verifikation: `resolvePauseAction`
  und `resolveOverlayPauseAction` haben identischen Body, daher reine
  Symbol-Konsolidierung. **Effort: S.**

- **Chunk 2.6 — `resolveOverlayPauseAction` aus `ActionResolvers.kt:286-293`
  entfernen** (DRY). **Effort: S.**

- **Chunk 2.7 — OVERLAY_TRASH-Slot referenziert `::resolveTrashAction`**.
  Datei: `LayoutCatalog.kt:593-595`. Trotz strukturell-unerreichbarem
  ReprocessStaging-Branch — DRY-Konsistenz. **Effort: S.**

**Block-2-AC:** OVERLAY_RECORD-Button zeigt im Overlay den korrekten
state-driven Text + AutoEnter-↵-Icon im Running + rote Schrift im
Failure + PulseLayout + Timer. SEND als separater Button entfällt.
Manual-Verify mit Pipeline-Run von Idle → Active → Send → Running →
Done.

### §8.3 Block 3 — `imeSideAffordance`-Hook im Overlay-Pfad

**Ziel:** Den R-1-Snapshot vor jedem catalog-getriebenen SEND-Click
ausführen, symmetrisch zum Keyboard-Pfad.

- **Chunk 3.1 — `imeSideAffordance`-Forward im OverlayBackend ergänzen**.
  Datei: `OverlayBackend.kt:355-366` `wireStaticOverlayHandlers`.
  Neue Logik vor dem Catalog-Dispatch:
  ```
  view.setOnClickListener {
      val state = stateRef ?: return@setOnClickListener
      val slot = currentSlot(id) ?: return@setOnClickListener
      if (id == LogicalButtonId.OVERLAY_RECORD) {
          imeSideAffordance(id, false)  // R-1 snapshot before dispatch
      }
      slot.actionResolver(state, services)?.let { onAction?.invoke(it) }
  }
  ```
  **Effort: S.**

- **Chunk 3.2 — `OverlayBackend`-Konstruktor-Slot für `imeSideAffordance`
  im `DictatePipelineService` verdrahten.** Aber: `imeSideAffordance`
  lebt in `DictateInputMethodService`, nicht im Service. Wir brauchen
  einen Brücken-Mechanismus.

  Variante A: das `imeSideAffordance` wird über `ModuleServices` exponiert
  (z.B. neues Field `imeSideAffordanceHook: (LogicalButtonId, Boolean)
  -> Unit`, vom IME bei Bind gesetzt). Pro: Konsistent mit dem bestehenden
  `inputConnectionProvider`-Pattern (ModuleServices.kt:97). Con:
  ModuleServices ist primär ein DI-Container für Module-Effects, nicht
  ein UI-Affordance-Bus.

  Variante B: ein neuer separater Bus `ImeSideAffordanceBus` im Service,
  IME registriert seinen Hook bei Bind, OverlayBackend liest den Hook
  aus dem Bus. Pro: Sauber getrennt. Con: Eine Klasse mehr.

  Variante C: der `OverlayBackend` bekommt ein Lambda im Konstruktor,
  der von `DictatePipelineService.onCreate` mit einer Funktion gefüllt
  wird, die zur Bind-Zeit auf den IME zugreift (späte Bind via
  `lateinit`-Style). Pro: Minimaler Diff. Con: Pre-Bind-Window-Race.

  **Empfehlung: Variante A** — `ModuleServices.imeSideAffordanceHook`
  als neues Field, default `{ _, _ -> }`. Im `DictateInputMethodService`
  wird der Hook bei `attachImeViewBackendIfReady` (line 1374-1421) auf
  `ModuleServices`-Instanz geschrieben. Der OverlayBackend liest
  `services.imeSideAffordanceHook` im Click-Handler. **Effort: M.**

- **Chunk 3.3 — `prepareCatalogStopRecordingIfActive` ist self-gating**
  (DictateInputMethodService.java:3503-3539, prüft `state.recording is
  Active|Paused`). Bei Klick aus dem Overlay in Idle (= Start-Recording)
  ist die Helper-Funktion ein No-Op — sie returnt früh. **Kein Code-
  Change nötig**, nur Verifikation. **Effort: S (Verify-only).**

**Block-3-AC:** Im WIDGET-Modus, mit aktiver Aufnahme, Klick auf
OVERLAY_RECORD (jetzt SEND) → R-1-Snapshot wird in
`imePipelineConfigResolver` geschrieben → Pipeline läuft korrekt
`Preparing → Running → Done` → Text landet im Editor. Manual-Verify
und JVM-Integration-Test.

### §8.4 Block 4 — DRY-Aufräumen + Defense-in-Depth

**Ziel:** Verbleibende Symbol-Duplikate und Race-Window-Defensiven
schließen.

- **Chunk 4.1 — `resolveOverlayPauseAction` löschen** (siehe Chunk 2.6).
  Schon in Block 2 ausgeführt. **Effort: 0.**

- **Chunk 4.2 — `resolveOverlayCloseAction` bleibt als eigener Resolver**.
  Begründung: das ViewMode-Branching (WIDGET → ToggleViewModeWidget,
  HOVER → CloseOverlay) ist Overlay-spezifisch ohne Keyboard-Pendant.
  Cross-Verify im Code-Review: hat keinen Keyboard-Counterpart, der
  ihn ersetzen könnte. **Effort: S (Verify-only).**

- **Chunk 4.3 — Defensive HOVER-Send-Gate im `resolveOverlayRecordAction`**.
  Bereits im Block 2 Chunk 2.2 enthalten — der Active/Paused-Branch
  returnt `null` bei `state.viewMode != WIDGET`. **Effort: 0.**

- **Chunk 4.4 — Architektur-Invariant-Test erweitern**.
  Datei: `app/src/test/java/.../CutoverArchitectureInvariantTest.kt`.
  Neue Assertion: `(g) overlayBackendClickBranchFiresAffordanceForRecord`
  — sucht in `OverlayBackend.kt` nach
  `imeSideAffordance(LogicalButtonId.OVERLAY_RECORD, false)` innerhalb
  des Click-Lambda-Range. Locks the symmetric R-1-protection invariant.
  **Effort: M.**

**Block-4-AC:** Code-Grep für duplizierte Resolver findet keine
Treffer mehr; Architektur-Invariant-Test grünt; Manual-Race-Test
"Schnell-Tap OVERLAY_RECORD im HOVER während ViewMode-Transition"
zeigt kein Double-Dispatch.

### §8.5 Effort-Summary

| Block | Chunks | Total Effort |
|---|---|---|
| Block 1 — Foundation | 1.1 + 1.2 + 1.3 + 1.4 | S+S+M+M ≈ 2h |
| Block 2 — Slot-Wiederverwendung | 2.1 + 2.2 + 2.3 + 2.4 + 2.5 + 2.6 + 2.7 | S+M+M+S+S+S+S ≈ 1.5h |
| Block 3 — Affordance-Hook | 3.1 + 3.2 + 3.3 | S+M+S ≈ 1h |
| Block 4 — Cleanup | 4.1 + 4.2 + 4.3 + 4.4 | 0+S+0+M ≈ 0.5h |
| **Gesamt** | — | **~5h** |

Plus Test-Schreibung (Side-Channel-Renderer-Wiring-Test, Catalog-
Resolver-Test, Architektur-Invariant-Test) — Realistisch 6-8h Implementation.

## §9 Wiederverwendungs-Map

Tabelle: welcher bestehende Code wird wie wiederverwendet?

| Code-Element | Heutige Heimat | Im Keyboard-Pfad genutzt | Im Overlay-Pfad neu genutzt? | Wie? |
|---|---|---|---|---|
| **Klassen (Renderer)** | | | | |
| `RecordingAnimationController` | `state/render/RecordingAnimationController.kt` | ja | ja | eigene Overlay-Instanz, `recordButton`/`pulseLayout` aus Overlay-XML |
| `AutoEnterRenderer` | `state/render/AutoEnterRenderer.kt` | ja | ja | eigene Overlay-Instanz, `recordButton` aus Overlay-XML |
| `RecordButtonColorController` | `state/render/RecordButtonColorController.kt` | ja | ja | eigene Overlay-Instanz, `recordButton` aus Overlay-XML |
| `PipelineStepRowRenderer` | `state/render/PipelineStepRowRenderer.kt` | ja | **nein** (Step-Row ist Content-Area-Feature, nicht Widget-tauglich) | — |
| `PulseLayout` (View-Klasse) | `widget/PulseLayout.kt` | ja (`record_pulse_layout` in main XML) | ja (`overlay_pulse_layout` in overlay XML) | neue View-Instanz im Layout |
| **Resolver (Funktionen)** | | | | |
| `resolveRecordButtonText` | `state/layout/TextResolvers.kt` | ja (KEYBOARD_TWO_ROW / SINGLE_ROW) | ja (indirekt via `resolveOverlayRecordButtonText`) | Composition |
| `resolveRecordButtonTextPipeline` | `state/layout/TextResolvers.kt` | ja (TWO_ROW_SEND_MODE / SINGLE_ROW_SEND_MODE) | ja (indirekt via `resolveOverlayRecordButtonText`) | Composition |
| `resolveRecordAction` | `state/layout/ActionResolvers.kt` | ja | partiell wiederverwendet | `resolveOverlayRecordAction` neu definiert mit gleichem Body + ViewMode-Gate |
| `resolveRecordActionPipeline` | `state/layout/ActionResolvers.kt` | ja | partiell | im Overlay-Action-Resolver für Preparing/Running-Branch genutzt |
| `resolvePauseAction` | `state/layout/ActionResolvers.kt` | ja | **direkt referenziert** | `LayoutCatalog.OVERLAY_5BUTTON.OVERLAY_PAUSE.actionResolver = ::resolvePauseAction` |
| `resolvePauseIcon` | `state/layout/...` | ja | **schon direkt referenziert** (`LayoutCatalog.kt:578`) | bleibt |
| `resolveTrashAction` | `state/layout/ActionResolvers.kt` | ja | **direkt referenziert** | `LayoutCatalog.OVERLAY_5BUTTON.OVERLAY_TRASH.actionResolver = ::resolveTrashAction` |
| `resolveOverlayCloseAction` | `state/layout/ActionResolvers.kt` | nein | ja (eigener Resolver, weil ViewMode-Branch ohne Keyboard-Pendant) | bleibt erhalten |
| `resolveOverlayRecordAction` | `state/layout/ActionResolvers.kt` | nein | **erweitert** um Pipeline-Sub-State-Branches | siehe Chunk 2.2 |
| `resolveOverlayPauseAction` | `state/layout/ActionResolvers.kt` | nein | **gelöscht** (DRY mit `resolvePauseAction`) | siehe Chunk 2.6 |
| `resolveOverlayRecordButtonText` (neu) | `state/layout/TextResolvers.kt` | nein | ja (neu) | siehe Chunk 2.1 |
| **Side-Channel-Forwarder** | | | | |
| `RecordingActivityTickerObserver` | `core/RecordingActivityTickerObserver.kt` | ja (`imeViewBackend.onTimerTick/onAmplitude`) | ja, **erweitert** um dritten Forward `overlayBackend.onTimerTick/onAmplitude` | siehe Chunk 1.3 |
| **Hooks** | | | | |
| `imeSideAffordance` (Function2) | `DictateInputMethodService.java:1374-1421` (Lambda) | ja (im IME-Backend-Konstruktor) | ja, **gleicher Lambda neu durchgereicht** via `ModuleServices.imeSideAffordanceHook` | siehe Chunk 3.2 |
| `prepareCatalogStopRecordingIfActive` | `DictateInputMethodService.java:3503-3539` | ja (vom Lambda gefeuert) | ja, **selbe Funktion** vom Lambda gefeuert | unverändert (self-gating) |
| **Catalog-Layout** | | | | |
| `LayoutCatalog.OVERLAY_5BUTTON` | `state/layout/LayoutCatalog.kt:513-605` | nein | ja, **bestehender LayoutMode**, nur Slot-Bodies umgebaut | Single-Source-of-Slot-Truth |
| **Layout-XML** | | | | |
| `overlay_5button_layout.xml` | `res/layout/` | nein | **umgebaut** zu 1 record-mit-pulse + 3 Icons | siehe Chunk 1.1 |
| `activity_dictate_keyboard_view.xml` | `res/layout/` | ja | **unverändert** | — |

## §10 Risiken / Open Questions

### §10.1 Risiken

- **R-1: Doppelte Renderer-Instanzen vs Process-Memory.** Der Overlay-
  Backend hält jetzt 3 Side-Channel-Renderer + 1 PulseLayout-Drawable-
  Cache. Plus die IME-View-Seite hat dieselben Instanzen auf eigenen
  Views. Schätzung: +30-50KB Heap im aktiven Overlay-Modus. Akzeptabel,
  weil das Overlay nur in WIDGET/HOVER-Modi attached ist
  (Service-side Toggling per `syncOverlayBackendAttachment`,
  `DictatePipelineService.kt:745-783`). **Mitigation:** `teardownOverlay`
  räumt alles auf — Memory-Leak-Test mit Allocate/Free-Cycles im Test-
  Suite.
- **R-2: Side-Channel-Renderer-Lifecycle vs Overlay-Detach.** Beim
  Overlay-Detach (T2 / T7) muss `rendererBundle.reset()` laufen, **bevor**
  `overlayWindow.detach()` die View-Refs killt. Reihenfolge wichtig.
  **Mitigation:** Defensive Try-Catch + Test-Case "Detach während aktiver
  Animation".
- **R-3: PulseLayout-Animation-State über Overlay-Reattach.** Bei T4
  (WIDGET → HOVER) wechselt der ViewMode, aber der OverlayBackend bleibt
  attached. Die Reattach-Logik existiert nicht — der Pulse-Cycle muss
  durchlaufen. Lifecycle-Garantie aus `RecordingAnimationController`:
  `lastRecordingState`-Cache wird erst bei `reset()` invalidiert.
  Verifikation: T4-Test ohne Animation-Glitch.
- **R-4: `imeSideAffordance`-Hook-Setter-Race im pre-Bind-Window.**
  Wenn der Overlay vor `attachImeViewBackendIfReady` (Bind-Zeit)
  inflated wird (theoretisch: HOVER-Auto-Trigger bei kalt-gestartetem
  Service), ist `ModuleServices.imeSideAffordanceHook` noch
  `{ _, _ -> }`-Default. Klick auf OVERLAY_RECORD im HOVER wäre dann
  ein silent no-op. **Mitigation:** Pre-Bind-Toast bei Click + Service-
  side Lifecycle-Audit. Im Prinzip ist HOVER-im-kalt-Start strukturell
  schwierig erreichbar (Pipeline muss laufen, also muss IME mindestens
  einmal visible gewesen sein).
- **R-5: ID-Removal `OVERLAY_SEND`.** Wenn andere Plan-Dateien
  (Spec 3) `OVERLAY_SEND` referenzieren, müssen die mitfallen oder als
  historisch dokumentiert werden. Spec 3 §3.1 / §3.2 / §11.4 alle
  referenzieren `overlay_send_btn` / `OVERLAY_SEND`. **Mitigation:**
  Plan-Annotation in Spec 3 als Update; Decision-History-Eintrag in
  ADR-0005 "Send-Button mit Record-Button gemerged für visuelle Reuse".
- **R-6: Bestehende Tests verwenden `OVERLAY_SEND`.**
  `OverlayBackend`-Tests + `LayoutCatalog`-Tests. **Mitigation:** Tests
  als Teil von Block 1/2 mit umbauen.

### §10.2 Open Questions

- **OQ-1 (Layout-Variante 2a vs 2b — User-UX-Wahl) — ENTSCHIEDEN
  2026-05-21: Variante 2a.** User-Plan-Review in der selben
  Pair-Programming-Session: *"Ich hätte hier tatsächlich gerne den
  gleichen Button verwendet, wie wir ihn auch in der normalen Ansicht
  nutzen, also in der normalen Tastaturansicht, und zwar exakt den
  gleichen Button. … Ich hätte gerne einen reichen Button. Der soll
  bitte wiederverwendbar sein."* OVERLAY_RECORD und OVERLAY_SEND
  werden zu einem Slot gemergt, mit state-driven Text-/Enabled-/
  Alpha-Resolvern und gemeinsamem `actionResolver`. Layout-XML wird
  entsprechend neu gezeichnet (§8.1 Chunk 1.1). Spec 3 §3.1/§3.2/§11.4
  werden als historisch annotiert; ADR-0005 bekommt einen
  Decision-History-Eintrag (siehe §10.1 R-5).

- **OQ-2 (RECORD-Button im Idle-WIDGET startet Aufnahme — UX-Auswirkung)
  — ENTSCHIEDEN 2026-05-21: Variante 2a beibehalten.**
  Spec 3 §11.9 stellte ursprünglich klar: OVERLAY_RECORD ist sichtbar in
  Idle, mit autonomer Start-Recording-Funktion. Wenn der Button jetzt
  identisch zum Keyboard-RECORD-Button ist (mit "Dictate (de)"-Label
  in Idle), wirkt er weniger wie ein "Floating-Start-Button" und mehr
  wie ein "Mini-Keyboard". User-Bestätigung deckt das ab — der Button
  bleibt 1:1 zum Keyboard, inklusive Idle-Label "Dictate (de)".

- **OQ-3 (HOVER-im-Idle ist strukturell unmöglich).** Per
  `computeViewMode`-Truth-Table (ADR-0005) ist HOVER nur bei `pipelineActive
  == true`. Im Idle gibt's kein HOVER. Damit muss die OVERLAY_RECORD-
  Button-Idle-Variante nur für WIDGET-Idle gerendert werden. Das
  `enabledResolver` ist also `viewMode == WIDGET && pipeline == Idle`
  vs `viewMode == WIDGET && recording in [Active, Paused]` vs
  `viewMode == WIDGET && pipeline in [Preparing, Running]`. Diese Logik
  ist im neuen Resolver sauber aufbaubar. **Keine Frage offen** —
  Implementation-Detail.

- **OQ-4 (Notification-Action-Buttons als Alternative für HOVER-Send?).**
  Spec 3 §9 + ADR-0005 §"Required mechanics" #4: in HOVER ist die
  Foreground-Service-Notification mit [Pause][Cancel][Send]-Action-Buttons
  die korrekte Send-UX. Send im Overlay wäre also strukturell
  redundant, der Button kann ruhig disabled bleiben.
  **Empfehlung:** Notification-Implementation ist separater Plan
  (`dictate-notification-actions`); für jetzt: HOVER-Send disabled,
  User soll Notification verwenden. **Keine Frage offen** — Scope-Cut.

- **OQ-5 (Side-Channel-Ticker-Owner-Migration).** Der
  `RecordingActivityTickerObserver` wird aktuell im IME-Service
  (`DictateInputMethodService.java:1497-1526`) instantiiert. Damit
  zeigt er nur, wenn IME-View visible ist (WIDGET, nicht HOVER). Im
  HOVER laufen Timer + Amplitude nicht — Overlay zeigt keinen Timer,
  obwohl Recording weiterläuft. **Lösungsoption:** Ticker-Observer in
  den `DictatePipelineService` migrieren (Service-side, überlebt
  IME-View-Tod). Dann läuft er auch im HOVER. **Out-of-Scope** dieses
  Plans, aber als Folge-Plan
  `dictate-pipeline-ticker-service-side` empfohlen. Erkenntlich
  als Mid-Term-Roadmap-Item.

## §11 Referenzen

- **Vorgänger-Plans:**
  - [`2026-05-07 - dictate-keyboard-layout-refactor`](../2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md)
    — Mutterplan; Spec 3 ist die binding-pre-code-Architektur-SoT für
    Floating-Overlay.
  - [`2026-05-21 - dictate-render-cutover-completion-vol2`](../2026-05-21%20-%20dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md)
    — Single-Writer-per-Axis-Etablierung, Side-Channel-Renderer-
    Klassen (AutoEnter / Color / Animation) als Pattern.
  - [`2026-05-21 - dictate-indirection-cleanup`](../2026-05-21%20-%20dictate-indirection-cleanup/dictate-indirection-cleanup.md)
    — Single-Dispatch-per-Axis-Etablierung; Struktur-Referenz dieses
    Plans.
- **ADRs:**
  - [ADR-0004 — UI Layout-Catalog + MotionLayout](../../decisions/0004-ui-layout-catalog-motionlayout.md)
    — `RenderBackend`-Architektur, `LayoutCatalog` als Single-Source,
    Slot-Resolver-Model.
  - [ADR-0005 — UI Triangle-FSM (KEYBOARD / WIDGET / HOVER)](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)
    — `computeViewMode`, T1-T7-Transitions, Send-Button-Differential-
    Verhalten WIDGET vs HOVER (Alternative #5).
- **Spec 3 — Floating-Overlay:**
  [research/3-floating-overlay/3-floating-overlay.reviewed.md](../2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
  §3.1 (OVERLAY_5BUTTON-Layout), §3.2 (XML), §4.2 (OverlayBackend),
  §10 (Acceptance), §11.5 (Drag), §11.9 (`userPrefersWidget`).
- **Code-Pointers (Soll-Belege):**
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:112-521`
  - `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:134-552`
  - `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt:52-144`
  - `app/src/main/java/net/devemperor/dictate/state/render/AutoEnterRenderer.kt:74-142`
  - `app/src/main/java/net/devemperor/dictate/state/render/RecordButtonColorController.kt:50-77`
  - `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:513-605` (OVERLAY_5BUTTON)
  - `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:64-158` (KEYBOARD_TWO_ROW als Vorlage)
  - `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:87-346`
  - `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:109-161`
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1209-1438` (IME-Backend-Wiring + Affordance-Lambda)
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1497-1526` (Ticker-Observer)
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3503-3539` (`prepareCatalogStopRecordingIfActive`)
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:639-696` (OverlayBackend-Konstruktion)
  - `app/src/main/res/layout/overlay_5button_layout.xml` (heutiges Layout)
  - `app/src/main/res/layout/activity_dictate_keyboard_view.xml:91-114` (PulseLayout-Vorlage)

## §12 Change History

### 2026-05-21 — Initial draft

- **Trigger:** User-Beobachtung am Gerät nach Inflate-Fix vom selben
  Tag: Overlay öffnet sich visuell, aber RECORD-Optik abweichend,
  SEND-Click ohne Wirkung, TRASH/PAUSE-Verhalten unklar. User-Auftrag
  "tiefgreifende Soll-vs-Ist-Recherche + Implementations-Plan".
- **What changed:** Plan initial verfasst. Vier-Aspekt-Soll-vs-Ist-
  Matrix (RECORD-Optik / SEND-Wirkung / TRASH / PAUSE), drei
  Architektur-Optionen (Embedded-View / 2×-Instanzen / Slot-Reform),
  Empfehlung Option 2 mit Variante 2a (RECORD-+-SEND-Merge), Vier-
  Block-Implementations-Sequenz, AC-1 bis AC-12. 9 identifizierte
  Lücken (L-1 bis L-9). 6 Risiken (R-1 bis R-6), 5 Open Questions
  (OQ-1 bis OQ-5).
- **Status:** Implementer-ready — alle Soll-Behauptungen mit Spec-3-
  Sektion-Referenz belegt, alle Ist-Behauptungen mit Datei:Zeile
  belegt, drei Optionen vollständig durchgespielt mit Engineering-
  Baseline-Bewertung. Pflicht-Lektüre: Spec 3 §3 + §4 + §6 + §7 + §10
  + §11.5 + §11.9; ADR-0005 Decision + Required-Mechanics. Optional:
  Vol2-Plan §7 Q1 (Side-Channel-Q1-Decision-Begründung).

### 2026-05-21 — Plan-Review und User-Entscheidungen

- **Trigger:** User-Plan-Review-Session am selben Tag. User hat (a)
  die Architektur-Erklärung der 6 Owner-Klassen für den
  RECORD-Button explizit angefordert (vor Implementations-Freigabe)
  und (b) die kritische Variante-2a/2b-Frage entschieden.
- **What changed:**
  - §10.2 OQ-1 — von "Verifikation erforderlich" auf "Variante 2a
    entschieden". User-Zitat verbatim eingefügt als Belegfaden
    ("exakt den gleichen Button … reichen Button … wiederverwendbar").
  - §10.2 OQ-2 — Bestätigung "Variante 2a beibehalten" zusammen mit
    OQ-1 vom User abgehakt.
  - §10.2 OQ-3 + OQ-4 + OQ-5 — keine Änderung
    (OQ-3 strukturell entschieden, OQ-4 Scope-Cut auf Folge-Plan,
    OQ-5 Folge-Plan-Mid-Term).
- **Status:** Implementer-ready mit eingetragener User-Entscheidung.
  Implementation kann mit §8.1 Chunk 1.1 (Layout-XML-Umbau) starten;
  keine offenen UX-/Architektur-Fragen mehr blockierend.
