---
date: 2026-05-21
author: Lukas + Claude Code (planning session)
type: Plan
status: Implementer-ready
context: Vollende den Render-Path-Cutover, den der Vorgänger-Plan `2026-05-15 - dictate-cutover-completion` als A3-Option-a "extract and preserve behaviour" abgelegt hat. Der `PipelineStepRowRenderer` schreibt heute weiterhin alle 100 ms direkt `record_btn.text` aus einer eigenen, legacy-`core.PipelineUiState`-sealed-class — parallel zum reaktiven Catalog/SlotRenderer-Pfad, der dasselbe Feld aus dem Orchestrator-`state.PipelineUiState` schreibt. Diese Doppel-Buchhaltung ist die Wurzel von fünf abgebrochenen Hotfix-Iterationen (R1-Spacing, AE-↵-Visualisierung). Dieser Plan macht den Renderer zum reaktiven Konsumenten, löscht die Legacy-sealed-class und entkoppelt die Constraint-Chain.
related-plan: 2026-05-15 - dictate-cutover-completion (Vorgänger)
related-adrs: ADR-0005
archive_target: 2026-05-21 - dictate-render-cutover-completion-vol2
---

Dieser Plan vollendet den Render-Path-Cutover, den der Vorgänger-Epic
`dictate-cutover-completion` (archiviert 2026-05-17) bewusst aufgeschoben hat.
Die A3-Entscheidung des Vorgänger-Plans war "extrahiere die `BLEIBT`-Teile in
neue kleine Owner (`PipelineStepRowRenderer`, `QwertzRecordingController`)
und preserve byte-identisches Verhalten". Das hat die 4 alten Controller
löschbar gemacht (AC-RR-7 grep-zero erfüllt), aber zwei Wahrheitssquellen
nebeneinander erzeugt: die Legacy-`core.PipelineUiState` (mit eigenem 100 ms
ElapsedTimer + Direkt-View-Write) und die neue `state.PipelineUiState`
(reaktiver Orchestrator-State + Catalog/SlotRenderer).

Post-Closure-Device-Tests auf einem SM-S948B haben dieselbe Wurzel in zwei
sichtbaren Symptomen exponiert (Commit `e7d4b2e`):

- Der ↵-Indikator für Auto-Enter erscheint nie auf `record_btn`, obwohl die
  Funktion technisch durchläuft — der 100 ms-Tick des Legacy-Renderers
  überschreibt das Catalog-Schreiben innerhalb einer Frame-Lücke.
- Die Constraint-Chain `space_btn`/`pause_btn`/`enter_btn` ankert Top **und**
  Bottom auf `trash_btn`, der im Idle/Send GONE ist. ConstraintLayout
  kollabiert den Anker auf 0×0; die wrap_content-Geschwister zentrieren sich
  um die Null-Höhen-Linie und ragen in Row 1 hinein.

Fünf Hotfix-Iterationen (in jenem Commit dokumentiert) haben jeweils den
Catalog-Pfad sauber gemacht. Solange der Legacy-Pfad aber parallel auf
denselben View schreibt, ist jeder Symptom-Fix nur eine Frage von "wer war
zuletzt". Dieser Plan beendet den Wettbewerb.

Es ist ein **strukturierter Refactor auf einer funktionsfähigen App**.
Aufnahme ist das Kern-Feature; §6 (Risiken + Rollback) ist load-bearing —
vor jedem 🔴-Phasen-Start lesen.

## Inhaltsverzeichnis

- [§1 Kontext & Auslöser](#1-kontext--auslöser)
- [§2 Ziele / Acceptance-Kriterien](#2-ziele--acceptance-kriterien)
- [§3 Architektur-Übergang (legacy → reaktiver Konsument)](#3-architektur-übergang-legacy--reaktiver-konsument)
- [§4 Building Blocks (Implementierungs-Phasen)](#4-building-blocks-implementierungs-phasen)
- [§5 Spec-References](#5-spec-references)
- [§6 Risiken & Rollback](#6-risiken--rollback)
- [§7 Verbleibende offene Fragen](#7-verbleibende-offene-fragen)
- [§8 Referenzen](#8-referenzen)
- [§9 Iteration-Log](#9-iteration-log)

## Glossar

### Pipeline-State (die zwei sealed classes)

- **Legacy `core.PipelineUiState`** — `sealed class` in
  `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt:13`.
  Eigentümer: `PipelineStepRowRenderer.state` (`PipelineStepRowRenderer.kt:96`,
  `private set`). Subtypen: `Idle`/`Preparing` (objects) +
  `Running(totalSteps, completedSteps, currentStepName, autoEnterActive,
  hasFailure)` + `ReprocessStaging(...)`. Wird von der IME über
  `pipelineStepRowRenderer.{preparePipeline, startPipeline, addRunningStep,
  completeStep, failStep, stopPipeline, enterReprocessStaging, …}` mutiert.
  Treibt den **Legacy-Schreibpfad** auf `record_btn.text` /
  `setCompoundDrawables` / `setTextColor`.
- **Neu `state.PipelineUiState`** — `sealed interface` in
  `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:239`.
  Eigentümer: Orchestrator (`DictateUiStateStore`, `PipelineModule.reduce`).
  Subtypen: `Idle` (object) + `Preparing(sessionId, autoEnterActive)` +
  `Running(sessionId, target, autoEnterActive, completedSteps, totalSteps,
  startedAtMs, elapsedMs)` + `ReprocessStaging(sessionId, transcript)`.
  Wird über `pipelineBinder.dispatch(Action.PipelineAction.*)` mutiert.
  Treibt den **Catalog/SlotRenderer-Pfad** über die Resolver in
  `TextResolvers.kt:122-133` + `IconResolvers.kt` + die Slot-Definitionen
  in `LayoutCatalog.kt`.

### Render-Pfade (die zwei Writer auf demselben View)

- **Legacy-Pfad:** IME → `pipelineStepRowRenderer.*-Methode` → mutiert
  `core.PipelineUiState` → `refreshRecordButtonFromState`
  (`PipelineStepRowRenderer.kt:400-443`) → schreibt **direkt**
  `views.recordButton.text` / `setCompoundDrawables` / `setTextColor`.
  Getrieben vom 100 ms-`ElapsedTimer` (`:215-222`) **unabhängig** vom
  Orchestrator-State-Emit-Takt.
- **Catalog-Pfad:** Orchestrator-State-Emit →
  `KeyboardLayoutManager.renderTo(state)` →
  `ImeViewBackend.render(state, layoutMode)` → für jeden Slot
  `SlotRenderer.applySlotToView(slot, view, state)`
  (`SlotRenderer.kt:55-85`) → `view.text = slot.textResolver(state)`
  pro State-Emit (per-button-Cache via `R.id.slot_renderer_last_text`).

### Hand-coded Bridges (heute, vor Phase 5)

- `DictateInputMethodService.toggleAutoEnterOverride()`
  (`:4062-4099`): mutiert **beide** State-Seiten — ruft erst
  `pipelineStepRowRenderer.toggleAutoEnter()` und dispatcht dann
  `Action.PipelineAction.ToggleRunningAutoEnter` an den Orchestrator.
  Ist die einzige Synchronisation der `autoEnterActive`-Achse zwischen
  den zwei Welten.
- `DictateInputMethodService.onStepStarted_dispatchOrchestratorSync`
  (`:3771-3805`): liest `pipelineStepRowRenderer.getState()` als
  `core.PipelineUiState.Running`, kopiert `totalSteps`/`autoEnter`
  rüber, dispatcht `Action.PipelineAction.StartPipeline` an den
  Orchestrator. Bridge in Gegenrichtung für den FSM-Lifecycle.

Beide Bridges sind explizit als "D4 hotfix" markiert und verschwinden in
Phase 5/6.

## §1 Kontext & Auslöser

### 1.1 Was der Vorgänger-Plan offen ließ

Der Spec `2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md`
markiert die zwei zentralen Stellen in §7 explizit:

> **RR-5:** `KeyboardUiController`/`RecordingUiController` **are split, not
> deleted.** §9.4/§9.5/§13 keep QWERTZ + step-rows in these classes (`BLEIBT`);
> the C10-C3 kill-list + AC-RR-7 greps assume full deletion. — Mitigation
> **flagged, not chosen here.**

> **Ambiguity A3:** the SPLIT-disposition keeps the BLEIBT halves alive
> behind new owners — option-(a) extract-and-preserve-behaviour was chosen
> as the lowest-risk path for the parent Epic.

Der Vorgänger-Plan war damit voll konsistent mit dem Spec. Er hat die vier
Legacy-Klassen (`MainButtonsController`, `RecordingUiController`,
`KeyboardStateManager`, `KeyboardUiController`) gelöscht (zero-grep
verifiziert) und ihre `BLEIBT`-Teile in `PipelineStepRowRenderer` und
`QwertzRecordingController` extrahiert. **Das war Plan-konform.** Was nicht
geschah: die extrahierten Klassen auf den reaktiven Catalog/State-Pfad
umzubauen. Genau dort lebt jetzt die Schuld, die dieser Vol-2-Plan einlöst.

### 1.2 Was die Post-Closure-Hotfix-Wave gezeigt hat

Commit `e7d4b2e` (fix(post-cutover)) dokumentiert fünf Iterationen pro
Symptom (R1 + AE) — jede hat eine plausible Hypothese gefixt, aber das
Symptom verschoben:

| Iter | R1 — Spacing | AE — ↵ |
|---|---|---|
| 1 | `motion:layout_goneMarginTop` auf trash_btn | Catalog-`ToggleAutoEnter` Action |
| 2 | `record_pulse_layout.marginBottom` in ConstraintSet | Dual-Path-Bridge in `toggleAutoEnterOverride` |
| 3 | Inline `marginBottom` auf PulseLayout | `Preparing.autoEnterActive`-Feld + Merge |
| 4 | `app:currentState` deklarativ | `formatPreparingLabel` für ↵ in Upload |
| 5 | — | `enabledResolver` aus SEND_MODE entfernt (Android schluckt Klicks) |

Die Symptome blieben, weil **die Iterationen den falschen Renderer
gefixt** haben. Catalog ist sauber, aber Legacy überschreibt im 100 ms-Takt.
Drei Audit-Agenten haben das brutal nachgewiesen
(siehe `research/d-audit-r1.md`, `research/d-audit-ae.md`,
`research/d-legacy-map.md`).

### 1.3 Warum das jetzt gemacht werden muss

- **Jedes künftige Feature**, das `record_btn.text` oder eine adjacent
  View berührt (Language-Chip-Curation, Single-Row-Mode), wird denselben
  Half-Migration-Konflikt re-erleben. Single-Row-Mode steht auf einem
  Nachbar-Branch und touched dieselben Slots — wir lösen das jetzt oder
  wir lösen es dort gegen einen instabilen Refactor-Untergrund.
- **`engineering-baseline §1`** ("Prefer the most sustainable solution"):
  zwei parallele State-Spiegel mit hand-coded Bridge sind kein nachhaltiger
  Endzustand. Der reaktive Single-State-Konsument-Pattern ist es.
- **ADR-0005** beschreibt das Triangle-FSM (Keyboard/Widget/Hover) als
  reaktiv-Catalog-getrieben. Die Pipeline-Achse darunter ist die letzte
  imperativ-getriebene; bringt das auf eine Linie mit dem ADR-Konzept.

## §2 Ziele / Acceptance-Kriterien

### 2.1 Funktionale Acceptance

| AC | Beschreibung | Verifikations-Pfad |
|---|---|---|
| **AC-1** | `record_btn.text` wird auf jedem User-sichtbaren Pfad ausschließlich vom Catalog/SlotRenderer geschrieben. Der 100 ms-Tick im Legacy-Renderer schreibt nicht mehr auf diesen View. | Robolectric-Audit-Logger nach `VisibilityWriteAuditLogger`-Muster. |
| **AC-2** | `core.PipelineUiState` ist gelöscht; `core.PipelineUiStateReader` ebenfalls. Suchresultate `grep -r "core\.PipelineUiState" app/src/main/` → 0. | Source-grep + Build. |
| **AC-3** | `PipelineStepRowRenderer` hat keine eigene `state`-Property mehr und keine `preparePipeline`/`startPipeline`/`addRunningStep`/`completeStep`/`failStep`/`stopPipeline`/`enterReprocessStaging`-Methoden. Step-Row-Inflate läuft reaktiv aus `state.pipeline`. | API-grep + Renderer-Test. |
| **AC-4** | `space_btn`/`pause_btn`/`enter_btn` haben keine Top/Bottom-Anker auf `trash_btn` mehr. Beim Idle-Boot (trash_btn GONE) zeigt der Layout-Inspector keinen Overlap zwischen Row 1 und Row 2. | XML-Audit + Robolectric-Pixel-Test. |
| **AC-5** | Auto-Enter-↵-Visualisierung greift sofort beim ersten Tap in der Upload-Window-Phase (Preparing): User-Tap → ↵ im Button binnen einer Frame-Lücke. | Manuelle E2E + Snapshot-Test. |
| **AC-6** | Beide hand-coded Bridges in `DictateInputMethodService.java` (`toggleAutoEnterOverride` Dual-Dispatch und `onStepStarted_dispatchOrchestratorSync`) sind entfernt. Es bleibt ein eindeutiger Dispatch-Pfad. | grep + manuelle Review. |
| **AC-7** | Regressionsliste §6.2 (12 Punkte) sind grün auf Gerät. | Manuelles E2E-Protokoll. |

### 2.2 Architektur-Acceptance

| AC | Beschreibung | Verifikations-Pfad |
|---|---|---|
| **AC-A1** | Single Source of Truth pro Achse. Für jede UI-Eigenschaft des `record_btn` existiert genau eine Stelle im Code, die sie schreibt. | Architecture-Invariant-Test (analog `CutoverArchitectureInvariantTest.kt`). |
| **AC-A2** | Der `PipelineStepRowRenderer` ist ein reiner Konsument: er hat keine Setter-API mehr, die `core.PipelineUiState` mutiert. Er konsumiert `state.pipeline` via `StateFlow.collect` oder synchronen Pull aus `pipelineBinder.getState()`. | API-grep + Code-Review. |
| **AC-A3** | Keine ElapsedTimer-Owner mehr für Pipeline-Gesamt-`elapsedMs`. Die Wahrheit kommt aus `state.PipelineUiState.Running.elapsedMs` (Reducer-Increment via ReducerContext). | Source-grep. |
| **AC-A4** | ADR-0005 Decision-History trägt einen Eintrag dieses Plans (Phase 5 Closure). Trigger / Before / After / Reasoning. | ADR-Datei + Plan-Closure-Step. |

### 2.3 Test-Acceptance

| AC | Beschreibung | Verifikations-Pfad |
|---|---|---|
| **AC-T1** | `./gradlew test` grün nach jeder Phase. | CI-Lokal. |
| **AC-T2** | Neue Snapshot-Tests pro `RecordingState × PipelineUiState`-Kreuzprodukt für `record_btn`-Output (Phase 1 deliverable). | Test-File-Listing. |
| **AC-T3** | `PipelineStepRowRendererTest` wurde in einen reinen View-Inflate-Test umgebaut (statt Renderer-Setter-API). | Test-Code-Review. |

## §3 Architektur-Übergang (legacy → reaktiver Konsument)

### 3.1 Cutover-Seam-Map

| Subsystem | Heute | Cutover-Endzustand |
|---|---|---|
| Pipeline-FSM-State | dual: `core.PipelineUiState` (Renderer-owned) + `state.PipelineUiState` (Orchestrator), via 2 Hand-Bridges synced | nur `state.PipelineUiState` (Orchestrator-owned) |
| `record_btn.text` Schreiber | dual: `refreshRecordButtonFromState` (100 ms) + `SlotRenderer.applySlotToView` | nur `SlotRenderer.applySlotToView` |
| `record_btn` Compound-Drawables | dual: Legacy-Mic/Send/Bluetooth/Auto-Enter + Catalog `iconResolver` | nur `iconResolver` (mit neuem Branch für Auto-Enter-↵) |
| `record_btn.setTextColor` | Legacy-only (rote Failure-Farbe) | neuer Catalog-Mechanismus: `colorResolver` ODER side-channel-Renderer analog zu `BorderGlowAnimation` |
| `record_btn.isEnabled` | dual: Legacy + Catalog `enabledResolver` | nur Catalog (Legacy entfällt mit `refreshRecordButtonFromState`) |
| Auto-Enter-↵-Icon | Legacy via `AutoEnterIconRenderer.get(active)` als Compound-Drawable | reaktiver Side-Channel-Updater (`AutoEnterIconRenderer.onState(state.pipeline)`) im `ImeViewBackend` |
| Step-Row-Inflate (`pipeline_progress_ll`) | imperativ aus IME → `pipelineStepRowRenderer.addRunningStep(name)` | reaktiv: `state.pipeline.Running.stepHistory` (neues Feld) → Renderer observes |
| Pipeline-Gesamt-`elapsedMs` | Legacy: ElapsedTimer im Renderer (100 ms) | `state.PipelineUiState.Running.elapsedMs` (Reducer-Stamp) |
| Step-Row-Per-Step-Duration | Legacy: lokaler ElapsedTimer pro Step-Row-View (`addRunningStep:312-314`) | **bleibt** (View-internal, kein Pipeline-State-Concern) |
| Row-2-Geschwister-Anker | Top **und** Bottom auf `trash_btn` (kollabiert in Idle/Send) | Top auf `record_pulse_layout.bottom`, Bottom self-resolved via wrap_content |

### 3.2 ASCII-Diagramm — Datenfluss Endzustand

```
                ┌───────────────────────────┐
                │  User-Action / Side-Effect│
                └───────────┬───────────────┘
                            │ dispatch(Action.PipelineAction.*)
                            ▼
                ┌───────────────────────────┐
                │  PipelineModule.reduce    │
                │  (pure, single-thread)    │
                └───────────┬───────────────┘
                            │ next DictateUiState
                            ▼
                ┌───────────────────────────┐
                │  DictateUiStateStore      │
                │  StateFlow<DictateUiState>│
                └───────────┬───────────────┘
                            │ collect
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌──────────────┐  ┌──────────────────┐  ┌──────────────────────┐
│ KeyboardLayout│  │ PipelineStepRow │  │ AutoEnterIconRenderer│
│ Manager       │  │ Renderer        │  │ (side-channel)       │
│ → SlotRenderer│  │ (reactive       │  │                      │
│  applySlotTo │  │  consumer:      │  │ on state.pipeline →   │
│  View         │  │  inflate step   │  │ set Compound-Drawable │
│ writes text/  │  │  rows from      │  │ on record_btn         │
│ visibility/   │  │  state.pipeline │  │                      │
│ enabled/icon  │  │ .stepHistory)   │  │                      │
└──────────────┘  └──────────────────┘  └──────────────────────┘
        │                   │                       │
        ▼                   ▼                       ▼
                  ┌─────────────────────┐
                  │     IME view tree   │
                  │  (record_btn etc.)  │
                  └─────────────────────┘
```

Single-direction, Single-Writer pro Achse.

## §4 Building Blocks (Implementierungs-Phasen)

**Leitprinzip:** Niemals zwei Schreiber gleichzeitig auf derselben Achse.
Jede Phase entfernt einen Schreiber, **nachdem** der andere bewiesen
hat, dass er für diese Achse korrekt liefert. Wenn der Catalog-Pfad
einen Branch nicht abdeckt — erst Catalog erweitern, dann Legacy
löschen.

**Risiko-Klassen:** 🟢 niedrig (additiv/Refactor-only), 🟡 mittel
(cross-cutting, eine Achse), 🔴 hoch (Verhalten-flip auf laufendem
System).

### Phase 1 🟢 — Catalog-Surface-Vollständigkeit verifizieren + erweitern

**Pre-Cutover-Verifikationsphase. Kein Verhalten ändert sich. Nur
Tests + ggf. Resolver-Erweiterung.**

**Scope-Anpassung 2026-05-21 nach Sub-Recherche (siehe §7):** Auto-Enter
(Q1) und hasFailure-Color (Q2) bleiben **nicht** in Phase 1 — sie sind
Side-Channel-Renderer-Themen (Q1 → Phase 3, Q2 → Phase 5). Phase 1
verkleinert sich entsprechend auf den text/icon/enabled/alpha-Achsen-Audit.

**Scope:**

1. **Snapshot-Tests pro `RecordingState × PipelineUiState`-Kreuzprodukt**
   für `record_btn` Catalog-Output (text + icon + enabled + alpha).
   Pro Branch dokumentieren — Delta-Befunde zu dem, was
   `PipelineStepRowRenderer.refreshRecordButtonFromState` heute schreibt,
   sind die Phase-3-Akzeptanz-Punkte. **Color-Achse (hasFailure) ist
   außerhalb dieser Phase** (Phase 5).
2. **Bluetooth-Icon-Branch:** `RecordingState.Active(useBluetooth =
   true)` → Compound-Drawable rechts = `ic_baseline_bluetooth_20`.
   Prüfen ob `IconResolvers.kt` das abdeckt. Wenn nicht, ergänzen.
3. **Step-Row-Inflate:** explizit Spec 1 §9.2 **bleibt View-side**.
   Hier passiert keine Catalog-Erweiterung — nur die Trigger-Pfade
   stellen wir in Phase 5 um.

**Files:** `LayoutCatalog.kt`, `IconResolvers.kt`. Tests:
`LayoutCatalogTest.kt`, `IconResolversTest.kt`, neuer
`RecordButtonSnapshotTest.kt`.

**Tests-Pre:** existierende Tests grün.

**Tests-Post:** Bluetooth-Icon-Branch im IconResolvers-Test abgedeckt.
16 Snapshot-Permutationen für `record_btn` (4 RecordingStates × 4
PipelineUiStates) auf den text/icon/enabled/alpha-Achsen — ohne
Color-Achse.

**Roll-back:** trivial — additiv, kein Verhalten geändert.

**Akzeptanz:** Der Catalog produziert für jede 16er-Permutation auf
den text/icon/enabled/alpha-Achsen **byte-äquivalente** Output-Werte
zu dem, was `refreshRecordButtonFromState` heute auf diesen Achsen
schreibt. Color-Branch wird erst in Phase 5 verifiziert.

**Geschätzter Aufwand:** ~1-2 h (reduziert von vorher 2-4 h, weil Q1/Q2
verschoben).

### Phase 2 🟡 — Bridge-Adapter `core.PipelineUiState` ↔ `state.PipelineUiState`

**Bidirektionale Brücke einbauen, damit der IME-Java-Code in einem Atemzug
auf den neuen Typen wechseln kann.**

**Scope:**

1. Adapter-Funktion `state.PipelineUiState.toCoreLegacy(): core.PipelineUiState`
   und umgekehrt. Lebt unter `state/render/PipelineUiStateBridge.kt`.
2. `PipelineStepRowRenderer` bekommt einen zweiten Konsum-Pfad:
   `syncFromOrchestrator(state: state.PipelineUiState)`, intern auf
   das core-Modell mappend. Das ist die Pre-Cutover-Komptabilität.
3. Im IME-Java alle `PipelineUiState.Running`-Casts (`:3771-4050`,
   ca. 8 Stellen) in einen einzigen Helper bündeln (`getPipelinePhase()`)
   — reduziert Cutover-Surface-Area.

**Files:** neuer `PipelineUiStateBridge.kt`, `PipelineStepRowRenderer.kt`
(eine Methode add), `DictateInputMethodService.java` (Helper +
Call-Site-Refactor).

**Tests-Pre:** Phase 1 grün.

**Tests-Post:** Bridge-Roundtrip-Test (`core → state → core` =
Identity-Mapping für jeden Branch).

**Roll-back:** `git revert` des Bridge-Commits. Bridge ist additiv.

**Akzeptanz:** keine Verhaltensänderung. Build grün.

### Phase 3 🔴 — `refreshRecordButtonFromState` STOPPT + AutoEnterRenderer einführen (atomarer Commit)

**Der Punkt, wo der 100 ms-Tick auf `record_btn` aufhört.**

**Q1-Entscheidung 2026-05-21:** Auto-Enter-↵-Icon wird als
Side-Channel-Renderer `AutoEnterRenderer` migriert (Option b — siehe §7
Q1). Catalog-iconResolver-Variante wurde verworfen.

**Scope (alle Stellen in `PipelineStepRowRenderer.kt`):**

1. `refreshRecordButtonFromState` (`:400-443`) → no-op (oder Method-Body
   leeren, KDoc updaten). Alle `views.recordButton.text = …` und
   `setTextColor` und `setCompoundDrawablesRelativeWithIntrinsicBounds`
   raus. **`setTextColor`-Removal vermerken**: Phase 5 (Q2) führt den
   `RecordButtonColorController` als Ersatz ein. **In Phase 3 hat die
   `hasFailure`-Farbe damit übergangsweise keinen Renderer.** Akzeptabel,
   weil die rote Farbe nur ein kurzer Visual-Marker vor `stopPipeline`
   ist (Soak-Window-Beobachtung).
2. `pipelineTotalTimer`-Lambda (`:215-222`) ruft `refreshRecordButtonFromState()`
   nicht mehr. Ruft nur noch die Callback-Listener-Lambda
   (`callbacks.forEach { it.onPipelineTimerTick(...) }`).
3. `applyRecordButtonForRecording` (`:363-398`) → no-op.
4. `restoreRecordButtonIdle` (`:451-455`) → alle Call-Sites aus dem IME
   entfernen (`DictateInputMethodService.java:4019`, `:4905`). Catalog
   ist autoritativ für Idle-Reset.
5. `updateAutoEnterAppearance` (`:459-463`) → **GELÖSCHT.** Ersetzt durch
   den neuen `AutoEnterRenderer` (Q1-Option-b):

   ```kotlin
   // NEU — state/render/AutoEnterRenderer.kt
   class AutoEnterRenderer(
       private val recordButton: MaterialButton,
       rendererFactory: () -> AutoEnterIconRenderer =
           { AutoEnterIconRenderer(recordButton.context) },
   ) {
       private val iconRenderer = rendererFactory()
       private var lastApplied: Pair<Boolean, Boolean>? = null
       fun onState(pipeline: PipelineUiState) {
           val visible = pipeline is PipelineUiState.Running ||
                         pipeline is PipelineUiState.Preparing
           val active = when (pipeline) {
               is PipelineUiState.Running -> pipeline.autoEnterActive
               is PipelineUiState.Preparing -> pipeline.autoEnterActive
               else -> false
           }
           val key = visible to active
           if (lastApplied == key) return
           if (visible) {
               recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                   null, null, iconRenderer.get(active), null,
               )
           } else {
               recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                   0, 0, 0, 0,
               )
               iconRenderer.invalidate()
           }
           lastApplied = key
       }
       fun reset() { lastApplied = null; iconRenderer.invalidate() }
   }
   ```

   Hook im `ImeViewBackend`:
   - `attach()` → neu instanziieren (Context-Cache-Boundary,
     siehe Q1-Hinweis "context-gebunden").
   - `render(state)` → nach `slotRenderer`-fan-out:
     `autoEnterRenderer?.onState(state.pipeline)`.
   - `detach()` → `autoEnterRenderer?.reset(); autoEnterRenderer = null`.

**Konsequenz:** Ab jetzt schreibt **nur** noch der Catalog/SlotRenderer
auf `record_btn.text` und `record_btn.icon`. Das rechte Compound-Drawable
ist der einzige Side-Channel (AutoEnterRenderer). Der 100 ms-Tick
existiert noch (für Step-Row-Timer), schreibt aber nicht mehr `record_btn`.

**Files:** `PipelineStepRowRenderer.kt` (~80 Zeilen Reduktion), `DictateInputMethodService.java`
(Call-Sites entfernen), `ImeViewBackend.kt` (neuer Side-Channel-Hook),
neuer `state/render/AutoEnterRenderer.kt`.

**Tests-Pre:** Phase 2 grün. Phase 1 Snapshots grün. Manuelle E2E
(siehe §6.2 Regressionsliste).

**Tests-Post:**
- AC-NEW: `record_btn.text` wird nie von `PipelineStepRowRenderer`
  geschrieben (`RecordButtonWriteAuditLogger`-Test analog zum
  existierenden `VisibilityWriteAuditLogger`).
- `AutoEnterRendererTest`: idempotente onState-Aufrufe mutieren View
  nur bei Änderung; reset() invalidiert Cache.
- Bluetooth-Branch + Auto-Enter-Branch sichtbar im Manual-E2E.
- hasFailure-Branch (rote Farbe) ist hier **temporär abwesend** —
  Akzeptanz erst in Phase 5.

**Roll-back:** `git revert` dieses Phasen-Commits. **WICHTIG:** Phase 3
MUSS als EIN Commit landen (atomic flip), damit der Revert sauber ist.

**Risiken:** R-A, R-B, R-E, R-F, R-G in §6.1.

**Akzeptanz:** Manuelle E2E grün (ohne hasFailure-Color);
`RecordButtonWriteAuditLogger`-Test grün; `AutoEnterRendererTest` grün.

**Geschätzter Aufwand:** ~3-4 h.

### Phase 4 🟡 — Constraint-Chain Row-2-Geschwister entkoppeln (R1)

**Parallel zu Phase 2/3 startbar (XML-only).**

**Scope:** `motion_scene_keyboard.xml` Z. 166-194 —
`space_btn`/`pause_btn`/`enter_btn` Top- und Bottom-Anker von
`@id/trash_btn` umkonstruieren.

Empfohlene Option (aus dem Migrationsplan-Bericht, §4 Phase 4 (a)):

```
space_btn / pause_btn / enter_btn:
  Top_toBottomOf="@+id/record_pulse_layout"   (statt @+id/trash_btn)
  Bottom: weglassen (wrap_content selbst-resolved)
  Internal-chain: Start/End an Geschwister wie bisher.
```

`record_pulse_layout` ist in jedem State sichtbar (Catalog hat keinen
GONE-Pfad für RECORD) — kein Anker-Kollaps möglich.

`trash_btn` behält seinen eigenen `Top_toBottomOf=record_pulse_layout`,
ist aber nicht mehr Anker für die Geschwister.

**Cleanup:** Die WIP-Hotfix-Iterationen (inline `marginBottom` auf
PulseLayout, `app:currentState`) werden mit dieser Phase **obsolet**
und entfernt — der Plan deckt sie sauber ab.

**Files:** `motion_scene_keyboard.xml`, ggf. `activity_dictate_keyboard_view.xml`
(Cleanup der WIP-marginBottom-Iteration).

**Tests-Pre:** `KeyboardLayoutRenderMirrorTest` grün.

**Tests-Post:** Robolectric-Pixel-Test der `record_btn.bottom` gegen
`space_btn.top` für trash=GONE und trash=VISIBLE.

**Roll-back:** `git revert` des XML-Commits.

**Risiken:**
- Andere `LayoutMode`-States (`single_row_state`, `send_mode_state`,
  `reprocess_staging_state`) können mitkollabieren wenn Anker falsch
  gewählt werden. Mitigation: pro State manuell in Android-Studio-
  Designer-Preview durchklicken + Pixel-Test pro State.

**Akzeptanz:** in jedem der 5 `KEYBOARD_*_STATE`-Modi sind die
Row-2-Geschwister korrekt positioniert.

### Phase 5 🔴 — State-Migrationen + `core.PipelineUiState` löschen (Point of no return)

**Scope-Erweiterung 2026-05-21 nach Sub-Recherche:** Q2 (hasFailure)
und Q3 (stepHistory) sind State-Migrationen, die hier zusätzlich
gemacht werden müssen. Phase 5 wird damit größer (vorher ~4-8 h,
jetzt ~6-10 h) — eventuell in 5a (State-Migrationen + Reducer-Arms)
und 5b (Renderer-Flip + Delete) splitten.

**Scope:**

**5.A — State-Migrationen (additiv, keine Verhaltensänderung solange
der Renderer noch Phase-3-Stand hat):**

1. **`state.PipelineUiState.Running.hasFailure: Boolean = false`** —
   neues additives Feld (Q2). Default sorgt für Source-Kompatibilität
   aller existierenden Construction-Sites.
2. **`state.PipelineUiState.Running.stepHistory: PersistentList<StepRowItem>`** —
   neues additives Feld (Q3). Begleit-Typen in `DictateUiState.kt`:
   ```kotlin
   enum class StepStatus { RUNNING, COMPLETED, FAILED }
   data class StepRowItem(
       val stepName: String,
       val status: StepStatus,
       val startedAtMs: Long,
       val durationMs: Long = 0L,  // 0 solange RUNNING
   )
   ```
   Extension-Property (NICHT zweites State-Feld):
   ```kotlin
   val PipelineUiState.Running.currentStepName: String?
       get() = stepHistory.lastOrNull { it.status == StepStatus.RUNNING }
                   ?.stepName
   ```
3. **Reducer-Arms überarbeiten** in `PipelineModule.kt`:
   - `StartPipeline` Reducer-Arm → setzt `stepHistory =
     persistentListOf()` und `hasFailure = false` (frischer Reset,
     kein Leak).
   - `StepStarted` (`:182-193`) → appendet `StepRowItem(action.stepName,
     RUNNING, ctx.now)` zu `stepHistory`. Restamping `elapsedMs` bleibt.
   - `StepCompleted` (`:208-217`) → finalisiert letzte `RUNNING`-Row
     auf `COMPLETED` + `durationMs = ctx.now - lastRow.startedAtMs`.
     `completedSteps` wird Extension-Property: `stepHistory.count {
     it.status != RUNNING }` (siehe Q3 + Q6).
   - `StepFailed` (`:219-231`) → **Q6-Entscheidung 2026-05-21:
     Option (a) `Running(hasFailure=true)`.** `Running`-Arm:
     finalisiert letzte `RUNNING`-Row auf `FAILED` + setzt
     `hasFailure=true` + restamping `elapsedMs`. `Effect.MarkSessionFailed`
     fliegt; **KEIN** `Effect.DismissNotification` (Pipeline läuft
     visuell weiter, FGS bleibt, weil `executeQueuedPrompts` mit dem
     nächsten queued Prompt weitermacht). `Preparing`-Arm behält die
     heutige `→ Idle + MarkSessionFailed + DismissNotification`-
     Semantik (Upload-Fail vor jeder Row). `PipelineFailed` und
     `PipelineDone` unverändert → `Idle` (wipen den Marker natürlich).
4. **`RecordButtonColorController` (Q2-Side-Channel) implementieren:**
   ```kotlin
   class RecordButtonColorController(
       private val recordButton: MaterialButton,
       private val accentColor: () -> Int,
   ) {
       private var lastFailure: Boolean? = null
       fun onState(state: DictateUiState) {
           val failure = (state.pipeline as? PipelineUiState.Running)
               ?.hasFailure == true
           if (failure == lastFailure) return
           recordButton.setTextColor(
               if (failure) 0xFFF44336.toInt() else Color.WHITE,
           )
           lastFailure = failure
       }
       fun reset() { lastFailure = null }
   }
   ```
   Hook im `ImeViewBackend.render()` nach `AutoEnterRenderer.onState()`.

**5.B — Renderer-zu-Konsument-Flip + Delete:**

5. **`PipelineStepRowRenderer` schrumpft zum reaktiven Konsumenten:**
   - `state`-Property entfällt. Stattdessen Konstruktor-Argument
     `pipelineStateProvider: () -> state.PipelineUiState` oder
     `StateFlow<DictateUiState>`-Subscription.
   - `preparePipeline/startPipeline/stopPipeline/enterReprocessStaging/
     cancelReprocessStaging/updateReprocessQueue/updateReprocessLanguage/
     toggleAutoEnter` (`:183-280`) **alle entfernen**.
   - `addRunningStep/completeStep/failStep` (`:284-353`) — die View-side
     Inflate-Logik **bleibt**, aber der Trigger wechselt: statt
     IME→`pipelineStepRowRenderer.addRunningStep(name)`, dispatch
     `Action.PipelineAction.StepStarted(name)` (existiert bereits) →
     Reducer appendet zu `stepHistory` → `PipelineStepRowView`
     observiert via state-collector und inflated die Row.
   - Per-Step-`ElapsedTimer` (Z. 312-314) **bleibt** (Spec 1 §9.2
     explizit "View-internal").
   - Klasse umbenennen zu `PipelineStepRowView` (kein Renderer-Suffix
     mehr).
6. **`core/PipelineUiState.kt` LÖSCHEN.**
7. **`core/PipelineUiStateReader.kt` LÖSCHEN** (war Bridge-Interface
   für ReprocessStaging-Carrier — heute über state-Pfad gehalten).
8. **`PipelineUiStateBridge.kt` (aus Phase 2) LÖSCHEN** — Bridge
   nicht mehr nötig.
9. **`PipelineUiCallback.kt`** — Interface mit `onPipelineTimerTick`/
   `onPipelineUiStateChanged` etc. entfällt; Subscribe auf
   state-Flow ersetzt das.
10. **`IME servicePipelineCallback` Field (`:268`)** — entfällt.
11. **IME-Java-Casts (`DictateInputMethodService.java:3771-4050`)** —
    auf `state.PipelineUiState` umstellen. Phase-2-Helper-Refactor
    hat das vorbereitet, jetzt aktivieren.
12. **`toggleAutoEnterOverride()` Bridge (`:4062-4099`)** — auf
    einseitigen `pipelineBinder.dispatch(ToggleRunningAutoEnter)`
    reduzieren.
13. **`onStepStarted_dispatchOrchestratorSync` Bridge (`:3771-3805`)** —
    wird obsolet weil StartPipeline direkt aus dem Pipeline-Runner
    dispatcht, ohne über den Legacy-Renderer-State zu lesen.

**Files:** `DictateUiState.kt` (5.A neue Felder + Typen),
`PipelineModule.kt` (5.A Reducer-Arms), neuer
`state/render/RecordButtonColorController.kt` (5.A),
`ImeViewBackend.kt` (Side-Channel-Hook für ColorController),
`PipelineStepRowRenderer.kt` (massiver Schrumpf ~340→~80 LOC, 5.B),
DELETE `core/PipelineUiState.kt`, DELETE `core/PipelineUiStateReader.kt`,
DELETE `PipelineUiStateBridge.kt`, DELETE `PipelineUiCallback.kt`,
`DictateInputMethodService.java` (~30-40 Edit-Stellen).

**Tests-Pre:** Phase 3 + 4 grün. Voller `./gradlew test` grün.

**Tests-Post:**
- `PipelineModuleTest` erweitert um Step-Lifecycle-Events
  (StepStarted/StepCompleted/StepFailed → stepHistory-Mutation).
- `PipelineModuleTest` erweitert um StartPipeline mit
  `stepHistory = persistentListOf()`-Reset.
- `RecordButtonColorControllerTest`: idempotente onState-Aufrufe;
  Failure → rot, sonst weiß.
- `PipelineStepRowViewTest` (umbenannt) — testet View-Inflate-Verhalten
  aus state-Δs.
- Manuelle E2E: hasFailure rote Farbe wieder sichtbar; 3-Prompt-Chain
  inflated 3 Step-Rows; Rotation während Running re-inflated alle
  Steps mit korrektem Status.
- `grep -r "core\.PipelineUiState" app/src/main/` returns 0.
- `grep -r "PipelineUiStateReader" app/src/main/` returns 0.
- `grep -r "PipelineUiCallback" app/src/main/` returns 0.

**Roll-back:** `git revert` der Phasen-Commits. Phase 5 ist groß —
ggf. in 5a (State-Migrationen, Q6-Entscheidung implementiert,
ColorController) und 5b (Renderer-Flip + Delete) splitten. Beide
atomar pro Commit; 5b nach Soak-Window für 5a.

**Risiken:** R-C (jetzt korrekt hier verortet), R-D, R-E, R-H (§6.1).

**Akzeptanz:**
- `core.PipelineUiState` gelöscht (AC-2).
- 100% des Pipeline-FSM-State läuft über Orchestrator.
- hasFailure rote Farbe wieder sichtbar (Q2-Acceptance).
- Step-Lifecycle reaktiv aus State (Q3-Acceptance).
- Manuelle E2E grün (Recording + ReprocessStaging + Rotation während
  Running + Rotation während Staging + Auto-Enter-Toggle + Bluetooth +
  3-Prompt-Chain + Pipeline-Failure mit roter Farbe).

**Geschätzter Aufwand:** ~6-10 h (5.A: 2-4 h, 5.B: 4-6 h).

### Phase 6 🟢 — Cleanup (`AutoEnterConfig`, dead callbacks, ADR-Append)

**Scope:**

1. `PipelineStepRowRenderer.AutoEnterConfig` Data-Class — entfällt;
   Auto-Enter ist im state.Running-Feld.
2. `getAutoEnterConfig()`-Methode — weg.
3. `IME servicePipelineCallback` Field — komplett weg.
4. `Pref.AutoEnter` Pre-Pipeline-Default — verifizieren wo die
   Pre-Pipeline-Wahrheit lebt (heute: sp-key gelesen beim
   `StartPipeline`-Dispatch in IME). **Behält.**
5. **ADR-0005 Decision-History Eintrag:**
   - **Trigger:** Post-cutover Dual-Schreiber-Audits (AE-OPTIK2,
     R1-DEEP3) sowie der `core.PipelineUiState`-Bridge-Kommentar
     im IME (`:4077-4079`).
   - **Before:** A3-option-a "Extract-and-Preserve" — Legacy-Renderer
     `PipelineStepRowRenderer` hält eigenen `core.PipelineUiState` +
     100 ms-Tick + Direkt-View-Write parallel zu Catalog/SlotRenderer.
   - **After:** A3-option-c "Extract-and-Re-Architect" — Renderer als
     reaktiver Konsument von `state.PipelineUiState`; `core.PipelineUiState`
     gelöscht; Single-Writer-pro-Achse via Catalog/SlotRenderer.
   - **Reasoning:** engineering-baseline §1 ("long-term most maintainable"),
     +5 dokumentierte Hotfix-Iterationen die nur Symptome verschoben,
     Re-Litigation-Vermeidung des SoT-Anti-Patterns in zukünftigen
     Features (Single-Row-Mode, Language-Chip-Curation).

**Files:** `PipelineStepRowRenderer.kt`, `PipelineUiCallback.kt`
(DELETE), `DictateInputMethodService.java` (~5-10 Stellen),
`docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`.

**Tests:** trivial — was vorher grün war bleibt grün.

**Roll-back:** `git revert`.

**Akzeptanz:** keine `PipelineUiCallback`-Imports mehr;
`grep -r "PipelineUiCallback" app/src/main/` zeigt 0; ADR-Append
landet.

### 4.1 Phasen-Dependency-Graph

```
Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 5 ──► Phase 6
                  │                        ▲
                  └─► Phase 4 (parallel) ──┘
```

Phase 4 (Constraint-Fix) ist orthogonal zum Render-SoT-Fix und kann
parallel zu Phase 2/3 laufen — sie touchiert nur XML.

### 4.2 Zwischen-Phase-3-und-Phase-5: mandatorischer E2E-Run

**Q4-Entscheidung 2026-05-21: KEIN Tage-Soak-Window.** Phase 3 → Phase 5
laufen direkt nacheinander, ABER zwischen den beiden Commits **muss**
die §6.2-Regressionsliste auf dem Dev-Device (SM-S948B) vollständig
durchlaufen. Reichweite:

- 12 Punkte der §6.2-Liste manuell abarbeiten.
- Nach Phase-3-Commit: APK bauen, installieren, IME force-stoppen,
  Logcat clearen, Liste durchgehen.
- Wenn ein Punkt fehlschlägt → Phase 3 revert, Phase 1-Snapshot
  erweitern um den fehlenden Branch, re-do.
- Erst nach grünem E2E-Run → Phase 5.A starten.

**NICHT in Phase 5 hineinarbeiten**, wenn Phase 3 nicht grün ist —
Bisectability wäre sonst kompromittiert.

**Risiko-Akzeptanz:** Ohne Tage-Soak werden subtile Edge-Cases (Long-Press-
Recording-Pausen während Wochenend-Nutzung, BT-SCO-Reconnect-Loops, etc.)
nicht entdeckt. Der User akzeptiert das Risiko für schnelleres
Durchziehen.

## §5 Spec-References

- **Vorgänger-Plan:** `docs/plans/2026-05-15 - dictate-cutover-completion/`
  - Insbesondere `research/render-path-cutover.md` §7 Ambiguity A3
    + RR-5 (das hier nachzuholende Mitigation).
- **Layout-Refactor-Parent:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/`
- **Ur-Pipeline-UI-Plan:** `docs/plans/archive/pipeline-ui-state-and-auto-enter-refactor.md`
  (etablierte den Legacy-Pfad in Phase 2c/2e).
- **ADR-0005:** UI-Triangle-FSM + Catalog-Click-Affordance-Symmetry-Pattern.
- **Spec 1 §9.2 / §9.6:** Step-Row-Inflate bleibt View-side
  (per definition). Pipeline-Lifecycle-Trigger-Reroute ist Plan-Scope.
- **Spec 2 §9.4 / §9.5 / §13:** `BLEIBT`-Disposition für die
  step-row/QWERTZ-Render-Klassen — wird mit diesem Plan re-evaluiert
  von "split (BLEIBT)" zu "split (reactive consumer)".

## §6 Risiken & Rollback

### 6.1 Bekannte Risiken

| ID | Risiko | Mitigation | Phase |
|---|---|---|---|
| **R-A** | Bluetooth-Icon-Branch verschwindet kommentarlos | Phase-1-Snapshot-Test verifiziert | Phase 1 |
| **R-B** | Auto-Enter-↵-Icon-Toggle bleibt visuell tot wenn (b)-Migration nicht atomar mit refresh-Aus läuft | Phase 3 = EIN Commit | Phase 3 |
| **R-C** | `hasFailure`-rote Text-Farbe verschwindet | Phase 5.A führt `RecordButtonColorController` + `state.PipelineUiState.Running.hasFailure` ein (Q2-Entscheidung 2026-05-21). **Übergangs-Lücke:** zwischen Phase-3-Commit und Phase-5.A-Commit ist die rote Farbe abwesend — akzeptabel, weil rote Farbe nur kurzer Visual-Marker vor `stopPipeline` ist. | Phase 5.A |
| **R-D** | ReprocessStaging-Rotation-Recovery bricht | state-Store persistiert das Feld | Phase 5 |
| **R-E** | Step-Row-Animation-Race (sehr kurze Step-Übergänge < 50 ms) | Reducer-State-Emit ist synchron main-thread per ADR-0001 | Phase 5 |
| **R-F** | Long-Press-Behaviour auf record_btn bricht | Phase 3 touched die Long-Press-Wiring nicht | Phase 3 |
| **R-G** | Bluetooth-Headset-Routing bei BT-SCO-Handshake | Phase 1 Snapshot-Test deckt Variante | Phase 3 |
| **R-H** | Test-Pollution durch Renderer-Tests-Holdovers | Phase 5 löscht/ersetzt den Test | Phase 5 |
| **R-I** | Konflikt mit dem Single-Row-Feature-Branch | Phase 1+2+4 vor Single-Row-Merge; Phase 3+5+6 nach Single-Row-Merge | Sequenz |

### 6.2 Regressionsliste (Pflicht-Grün am Phase-3-Ende UND Phase-5-Ende)

1. Recording-Idle → Active → Stop → Pipeline Running → 1 Step → Insert.
2. Recording mit 3-Prompt-Chain → 3 Step-Rows inflated, Counter zählt.
3. Recording mit Auto-Enter-On → Pipeline endet → Enter wird eingefügt.
4. Auto-Enter-Toggle DURING Running → ↵-Icon updated, Behavior on
   Pipeline-End reflektiert.
5. Bluetooth-Headset connected → Recording → Compound-Drawable rechts =
   Bluetooth-Icon.
6. ReprocessStaging Entry (long-press resend) → state korrekt,
   audio-duration im Button.
7. ReprocessStaging Queue-Edit → Button-Text reflektiert.
8. Rotation während Running → Counter survived, Timer survived.
9. Rotation während ReprocessStaging → Queue + Language survived.
10. **Per-Step-Fail** (z.B. ein queued Rewording-Prompt schlägt fehl): letzte Step-Row mit Cross-Icon (rot) markiert, `record_btn`-Text rot, **Pipeline läuft weiter** mit nächstem queued Prompt (Q6-Klarstellung). Beim Pipeline-Ende (`onPipelineFinished`) wird der Marker natürlich gewipet.
11. **Pipeline-Failure** (z.B. Netzwerk-Komplettausfall) → `PipelineFailed`-Reducer transitioniert zu `Idle`, FGS-Notification verschwindet, Marker mit der Step-Row-Liste gewipet.
12. Keyboard-Switch DURING Recording → Recording bleibt alive
    (Theme-B-Acceptance, sollte nicht regress) + Notification visible.
13. Pause-During-Recording: Timer friert ein (Hotfix-Wave aus
    `e7d4b2e` — bleibt).

### 6.3 Rollback-Strategie

- **Atomic-Commit pro 🔴-Phase.** Phase 3 = ein Commit. Phase 5 = ein
  Commit (oder 5a+5b wenn Größe untragbar).
- **Q4-Entscheidung 2026-05-21: KEIN Tage-Soak-Window.** Zwischen
  Phase 3 und Phase 5 läuft der mandatorische §6.2-E2E-Run (siehe §4.2).
  Wenn der grün ist → Phase 5.A starten. Wenn nicht → Phase 3 revert.
- **Roll-back per `git revert`** auf Phasen-Commit-Ebene; Bisectability
  über `git bisect` möglich für jede Phase.
- **Soft-Roll-back via Bridge-Re-Aktivierung:** Phase 2 lässt die
  Bridge-Adapter; wenn Phase 3 nach Soak ein hartes Problem zeigt, kann
  Phase 3 reverted werden ohne Phase 2 zurückzunehmen.

## §7 Offene Fragen + Entscheidungen

Die Sub-Recherche-Agenten haben am 2026-05-21 drei Architektur-Fragen
durchgearbeitet (siehe `research/q1-auto-enter-migration.md`,
`research/q2-failure-color.md`, `research/q3-step-state-modeling.md`).
Q1-Q3 sind entschieden; Q4-Q6 stehen noch offen.

| Q | Frage | Entscheidung | Phase |
|---|---|---|---|
| **Q1** ✅ | Auto-Enter-↵-Icon-Migration: Catalog-`iconResolver` (a) oder Side-Channel-Renderer (b)? | **(b) Side-Channel `AutoEnterRenderer`.** Catalog (`ButtonSlot.iconResolver: (state) -> Int?`) ist per KDoc-Konvention auf `@DrawableRes Int` festgelegt; `AutoEnterIconRenderer` liefert dynamische `BitmapDrawable`s (PorterDuff-Knockout, density-skaliert). `SlotRenderer.applySlotToView` schreibt auf `MaterialButton.icon` (linke Position), das Compound-Drawable lebt aber rechts. Side-Channel folgt dem etablierten `RecordingAnimationController`-Pattern. **Vor Phase 3 implementieren; Phase 3 ersetzt `updateAutoEnterAppearance`-Call durch `AutoEnterRenderer.onState()`-Hook in `ImeViewBackend.render`.** Lifecycle-Hinweis: `AutoEnterIconRenderer` ist context-gebunden — der `AutoEnterRenderer`-Wrapper muss bei `ImeViewBackend.attach()` neu instanziiert oder per `invalidate()` gerefresht werden (Theme/Density-Cache-Boundary). | Phase 3 |
| **Q2** ✅ | `hasFailure`-Color-Pfad: `colorResolver` (a), Theming-Achse (b), oder side-channel `RecordButtonColorController` (c)? | **(c) Side-Channel `RecordButtonColorController`.** Theming-Achse ist prefs-getrieben (Themen) und passt nicht zum ephemeren `hasFailure`-Flip; Catalog `colorResolver` würde ein erstes Color-Field in das pure-Resolver-Modell einführen (`SlotRenderer` schreibt heute *keine* `setTextColor`-Werte). Side-Channel konsistent mit Q1. **PLAN-LÜCKE entdeckt:** `state.PipelineUiState.Running.hasFailure` existiert **NICHT** im Orchestrator-State — nur in `core.PipelineUiState.Running`. **Phase-Verschiebung von Phase 1 → Phase 5**, weil die State-Migration in Phase 5 stattfindet (siehe Phase 5 Scope-Erweiterung unten und neue Q6). | **Phase 5** (vorher fälschlich Phase 1) |
| **Q3** ✅ | Sollten `currentStepName` + `stepHistory` als zwei Felder in `state.PipelineUiState.Running` modelliert werden, oder als separate state-Achse (z. B. `state.pipelineSteps: PipelineStepsState`), oder view-internal? | **(a) Felder in `Running` — aber NUR `stepHistory: PersistentList<StepRowItem>`, nicht zusätzlich `currentStepName`.** Doppel-Buchhaltung war ein Plan-Fehler (genau das Anti-Pattern, das dieser Plan eliminiert). `currentStepName` wird Extension-Property: `val Running.currentStepName: String? get() = stepHistory.lastOrNull { it.status == StepStatus.RUNNING }?.stepName`. `PersistentList` (kotlinx.collections.immutable) ist im Repo bereits belegt (`PipelineRecovery.kt:5`). Begleit-Typen: `enum class StepStatus { RUNNING, COMPLETED, FAILED }`, `data class StepRowItem(stepName, status, durationMs, startedAtMs)`. Per-Step-`ElapsedTimer` bleibt View-internal (Spec 1 §9.2 explizit). | Phase 5 |
| **Q4** ✅ | Soak-Window-Länge zwischen Phase 3 und Phase 5: 24 h oder 48 h? | **KEIN Soak-Window — direkt Phase 5.** User-Entscheidung 2026-05-21. **Mandatorisch zwischen Phase 3 und Phase 5.A:** die §6.2-Regressionsliste muss auf dem Dev-Gerät vollständig durchlaufen. Wenn ein Test fehlschlägt → Phase 3 reverten, Phase 1 nachschärfen, re-do. **Nicht in Phase 5 hineinarbeiten.** | nach Phase 3 |
| **Q5** ✅ | Timing relativ zum Single-Row-Feature-Branch | **Entfällt.** Der Migrationsplan-Bericht hat dies fälschlich als "Feature-Branch" interpretiert. Single-Row ist eine **bestehende UI-Variante** im aktuellen Code (`LayoutCatalog.KEYBOARD_SINGLE_ROW`, MotionScene-State `single_row_state`) — kein paralleler Branch. Phase 1 deckt Single-Row mit ab (siehe §Phase 1 Acceptance, "5 KEYBOARD_*_STATE-Modi"). Aktueller Branch ist `feature/language-chip-curation` — orthogonal zum Cutover. | — |
| **Q6** ✅ | `StepFailed`-Reducer-Semantik nach Q2/Q3-State-Migrationen | **(a) `Running(hasFailure=true)`** mit gleichzeitiger Markierung der letzten `stepHistory`-Row als `FAILED`. **Wichtige Klarstellung des Q6-Audit:** der Plan-Text war auf falscher Prämisse aufgebaut. `StepFailed` ist **kein** Pipeline-Ende — `executeQueuedPrompts` (Orchestrator) läuft mit dem nächsten queued Prompt weiter. Nur `PipelineFailed` (top-level fatal), `PipelineDone` und `CancelPipeline` enden die Pipeline. Die rote Visual-Markierung lebt heute exakt von `onStepFailed` bis `onPipelineFinished`. Option (b) (neuer Failed-Sub-State + 2-s-Auto-Cleanup) war über-engineered für ein Verhalten, das schlechter wäre als heute (Marker würde während laufender Chain auto-verschwinden). Option (c) verstößt gegen das reaktive Pattern. **Konkret:** `Preparing → StepFailed`-Arm behält die heutige `→ Idle + DismissNotification`-Semantik (Upload-Fail vor jeder Row); `Running → StepFailed`-Arm setzt `stepHistory.last.copy(FAILED) + hasFailure=true` ohne `DismissNotification` (FGS bleibt, Pipeline läuft weiter); `PipelineFailed`/`PipelineDone` unverändert → `Idle` (wipest Marker natürlich). | Phase 5 |

## §8 Referenzen

- **Recherche-Berichte (in `./research/` archivieren):**
  - `d-audit-r1.md` — R1-Audit: Constraint-Chain-Topologie-Diagnose.
  - `d-audit-ae.md` — AE-Audit: Dual-Writer-100-ms-Tick-Diagnose.
  - `d-legacy-map.md` — Klassen-Inventar des Legacy-Render-Systems.
  - `d-migration-plan.md` — Migrationsplan-Bericht (Quelldokument für
    diesen Plan).
- **Code-Pointer (Stand HEAD `e7d4b2e`):**
  - `app/src/main/java/net/devemperor/dictate/state/render/PipelineStepRowRenderer.kt`
    (Phase-3 + Phase-5 Hauptangriffspunkt).
  - `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`
    (Phase-5 DELETE-Ziel).
  - `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`
    (Phase-5 DELETE-Ziel).
  - `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:239`
    (SoT-Target).
  - `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:122-133`
    (Catalog-Resolver für `record_btn`).
  - `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt`
    (neuer Side-Channel-Forward für AutoEnter-Icon, Phase 3).
  - `app/src/main/java/net/devemperor/dictate/core/AutoEnterIconRenderer.kt`
    (zu re-positionieren in Phase 3).
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
    (~30-40 Call-Site-Ersetzungen in Phase 5).
  - `app/src/main/res/xml/motion_scene_keyboard.xml` Z. 166-194
    (R1-Constraint-Fix, Phase 4).
- **ADR-0005:** `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`
  — Decision-History-Append-Ziel für Phase-6-Closure.

## §9 Iteration-Log

| Datum | Iteration | Beschreibung |
|---|---|---|
| 2026-05-21 | Plan-Erstellung | Initialer Plan basierend auf dem Migrationsplan-Bericht (`research/d-migration-plan.md`), den drei Audit-Berichten, und der Hotfix-Wave-Konsolidierung in Commit `e7d4b2e`. |
| 2026-05-21 | Sub-Recherche Q1-Q3 + Plan-Iteration | Drei Sub-Recherche-Agenten haben Q1 (Auto-Enter-Migration), Q2 (hasFailure-Color), Q3 (Step-State-Modell) tief beantwortet. **Entscheidungen:** Q1 → Side-Channel `AutoEnterRenderer`; Q2 → Side-Channel `RecordButtonColorController` **+ State-Migration** (`Running.hasFailure` existiert nicht im Orchestrator-State); Q3 → `stepHistory: PersistentList<StepRowItem>` mit `currentStepName` als Extension-Property (nicht als zweites Feld). **Plan-Lücken entdeckt:** Q2 von Phase 1 → Phase 5 verschoben (State-Migration), R-C entsprechend umverortet, Phase 5 in 5.A (State-Migrationen) + 5.B (Renderer-Flip + Delete) gesplittet, neue Q6 für `StepFailed`-Reducer-Semantik eingeführt. Phase 1 verkleinert. Phase 3 erweitert um `AutoEnterRenderer`-Skizze. Aufwandsschätzung Phase 5 erhöht (4-8 h → 6-10 h). |
| 2026-05-21 | Q4-Q6 final + Plan-Closure für Implementation-Start | **Q4-Entscheidung:** kein Tage-Soak-Window. Phase 3 → Phase 5 direkt nacheinander; zwischen den Phasen läuft mandatorisch der §6.2-E2E-Run. **Q5-Klärung:** entfällt — Single-Row ist eine bestehende UI-Variante, kein Feature-Branch (Missverständnis im Migrationsplan-Bericht). **Q6-Entscheidung (Sub-Recherche-Agent):** `StepFailed` → Option (a) `Running(hasFailure=true)` mit letzter Step-Row als FAILED markiert. Wichtige Klarstellung: `StepFailed` ist **kein** Pipeline-Ende — `executeQueuedPrompts` macht mit dem nächsten queued Prompt weiter. Nur `PipelineFailed`/`PipelineDone`/`CancelPipeline` enden die Pipeline. Plan §4.2 (Soak-Window) durch mandatorischen E2E-Run ersetzt; §6.2 Regressionsliste um "Per-Step-Fail" vs. "Pipeline-Failure" differenziert (Punkt 10 + 11); §6.3 Rollback-Strategie aktualisiert; Phase 5.A StepFailed-Reducer-Arm präzisiert. **Plan ist Implementer-ready.** |
