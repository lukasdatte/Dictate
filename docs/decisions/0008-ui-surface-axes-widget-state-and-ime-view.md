# ADR-0008: UI — Surface-Axes (WidgetState + ImeView)

**Status:** Proposed
**Subsystem:** ui-mode
**Scope:** Project-Wide
**Date:** 2026-05-21
**Supersedes:** ADR-0005 (Triangle-FSM)
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0006, ADR-0007.**
> Diese ADR ersetzt die `ViewMode`-Triangle-FSM aus ADR-0005 durch zwei
> orthogonale Achsen plus eine kleine FSM mit Origin-Tracking.
> ADR-0001 hostet das neue `WidgetModule` als Single-Owner der
> `widget`-Achse; ADR-0002 trägt die Cascade-Mechanik der Transitions
> W4/W6/W7/W8; ADR-0003 erlaubt strukturell den `PIPELINE`-Origin
> (FGS überlebt IME); ADR-0004 rendert beide Surfaces unabhängig;
> ADR-0006 nutzt die neue Achse für Continuation-/Partial-Recovery-
> InfoBar-Producer; ADR-0007 stützt die Pipeline-Continuation, deren
> UX über `widget` läuft.

## Research

Die Surface-Axes-Architektur ist die Synthese aus drei Erkenntnissen,
die sich im Mai 2026 unabhängig voneinander zeigten:

- **Triangle-FSM Truth-Table-Konflikt (2026-05-21):** Beim Fix des
  "Widget verschwindet bei IME-Close ohne Recording"-Bugs (#121) wurde
  eine Row 3 zur `computeViewMode`-Truth-Table hinzugefügt
  (`!imeView && userPrefersWidget → WIDGET`). Diese kollidiert
  semantisch mit Row 4 (`!imeView && pipelineActive → HOVER`) — die
  Row-Priorität "löst" den Konflikt, verliert aber die Origin-Information.
- **Bidirectional-Render-Fix (2026-05-21, A3-Phase):**
  `KeyboardLayoutManager.modeForBackend` führt zwei voneinander
  unabhängige Render-Ketten ein (Keyboard-Surface + Widget-Surface
  können simultan live sein). Das macht die "exklusive ViewMode-Wahl"
  zur Lüge: die FSM hat den 1-aus-3-Charakter strukturell verloren,
  ohne dass das State-Modell nachgezogen wurde.
- **Crash-Recovery-Requirement (2026-05-21):** Damit die User-Vision
  des nahtlosen Wiederaufnehmens nach Crashes umsetzbar ist, muss das
  System unterscheiden, ob das Widget "User-gewollt" oder
  "Pipeline-getrieben" sichtbar ist — sonst ist nicht entscheidbar,
  ob nach Pipeline-Ende KEYBOARD oder WIDGET die Ziel-Surface ist.

Die heutige Lösung (Row-Priorität + Patch-Tests) ist semantisch fragil
und macht zukünftige Modi (z.B. Picture-in-Picture, Standalone-Overlay)
durch Aufblähen der Enum-Werte teurer.

## Context

Die `ViewMode`-Enum (KEYBOARD | WIDGET | HOVER) kollabiert drei
orthogonale Konzepte in eine einzige Variable:

1. **Welche Surface ist gerendert** — Keyboard-Layout vs. Floating-Overlay
2. **Wer hat die Surface ausgelöst** — User-Aktion vs. Pipeline-Lifecycle
3. **Ist die Tastatur sichtbar** — `imeViewVisible`

Die Surfaces sind aber strukturell unabhängig (Bidirectional-Render-Fix):
beide können gleichzeitig live sein. Und die Origin-Information ist
nach Pipeline-Ende essentiell für die Rückkehr-Entscheidung.

Das Problem ist nicht "die FSM ist falsch" — sie löst die Probleme von
2026-05-14 sauber. Das Problem ist, dass die FSM **gewachsen** ist:
T1-T6 → T7 (Geist-Widget-Fix) → Row 3 (Sticky-Widget-Fix) → und
Crash-Recovery braucht jetzt eine vierte Dimension (Origin), die in
die Enum nicht mehr sauber reinpasst.

## Decision

Die `ViewMode`-Enum wird **entfernt** und durch zwei orthogonale Achsen
im `DictateUiState` ersetzt:

```kotlin
data class DictateUiState(
    val widget: WidgetState,          // Achse 1: Floating-Element-State
    val imeViewVisible: Boolean,       // Achse 2: Tastatur-Sichtbarkeit
    val recording: RecordingState,     // unverändert
    val pipeline: PipelineUiState,     // unverändert
    val overlay: OverlayState,         // unverändert (position, suppress-bit)
    // ...
)

sealed class WidgetState {
    object Hidden : WidgetState()
    data class Visible(val origin: WidgetOrigin) : WidgetState()
}

enum class WidgetOrigin {
    /** User-Toggle des Widget-Buttons — sticky, überlebt IME-View-Wechsel. */
    USER,
    /** Auto-Trigger bei IME-Hide während Recording/Pipeline aktiv — transient. */
    PIPELINE,
}
```

**Surface-Rendering** ergibt sich aus den Achsen:

```
keyboardSurface rendered  ⇔  imeViewVisible == true
widgetSurface   rendered  ⇔  widget is Visible
```

Beide Surfaces können gleichzeitig sichtbar sein (Bidirectional-Render
bleibt strukturell intakt).

**Transitions** ersetzen ADR-0005 T1-T7:

| ID | Trigger | Pre | Resultat |
|---|---|---|---|
| W1 | `WidgetAction.ToggleWidget` | `widget=Hidden` | `widget=Visible(USER)` |
| W2 | `WidgetAction.CloseWidget` | `widget is Visible` | `widget=Hidden` + suppressBit=true + recording.Active→Paused (Pipeline läuft weiter) |
| W3 | `WidgetAction.OnImeViewHidden` | `widget=Hidden && !suppressBit && (recording.active ∨ pipeline.running)` | `widget=Visible(PIPELINE)` |
| W4 | `WidgetAction.OnImeViewShown` | `widget=Visible(PIPELINE)` | `widget=Hidden` |
| W5 | `WidgetAction.OnImeViewShown` | `widget=Visible(USER)` | bleibt (sticky) |
| W6 | recording=Idle ∧ pipeline=Idle (Cascade) | `widget=Visible(PIPELINE)` | `widget=Hidden` |
| W7 | recording.Idle→Preparing (Cascade) | `suppressBit==true` | `suppressBit=false` (heute schon so) |
| W8 | recording.Paused→Active (Cascade) | `suppressBit==true` | `suppressBit=false` (NEU) |

**Module-Ownership** (per ADR-0001):
- `WidgetModule` ersetzt `ViewModeModule` — Single-Owner der
  `widget`-Achse, owns Reducer-Arme für W1, W2, W3, W4, W5.
- W6 ist eine Cascade aus dem Cross-Module-Coupling
  (RecordingModule + PipelineModule beobachten ihre eigene
  Idle-Boundary und feuern `WidgetAction.OnPipelineQuiescent`).
- W7 + W8 sind RecordingModule-Self-Cascades, die den `suppressBit`
  in `OverlayModule` resetten (heute schon so für W7, neu W8).

**Action-Familie:** `Action.WidgetAction.*` ersetzt
`Action.ViewModeAction.*`. Die alten Actions werden gelöscht — kein
Backwards-Compat-Shim (Repo hat keine externen Konsumenten).

**Host-Commit-Guard:** `commitTextToInputConnection` returnt `false`
wenn `widget is Visible` — das ersetzt die heute fehlende WIDGET-Mode-
Absicherung. Im HOVER-äquivalent (jetzt: `widget=Visible(PIPELINE)`)
ist Senden ohnehin durch den Action-Resolver geblockt.

## Alternatives

### Alt-1: Flat Enum mit Origin in der Enum-Variante

```kotlin
enum class SurfaceMode {
    KEYBOARD,
    WIDGET_USER,
    WIDGET_PIPELINE,
}
```

**Verworfen, weil:**
- Erweiterungen sperrig: für zukünftige Modi (PIP, Standalone-Overlay)
  müsste pro Origin-Kombination ein neuer Enum-Wert dazu — Enum-Wert-
  Explosion.
- Bidirectional-Render passt nicht (Enum suggeriert exklusive Wahl).
- Origin ist trotz Codierung im Namen nicht typsicher zugreifbar
  (Strings parsen).

### Alt-2: Zwei separate Boolean-Felder

```kotlin
data class State(
    val widgetUserRequested: Boolean,
    val widgetPipelineTriggered: Boolean,
    val imeViewVisible: Boolean,
)
```

**Verworfen, weil:**
- Strukturell sind ungültige Kombinationen möglich (beide true),
  müssten per Convention-Check verhindert werden.
- USER-Dominanz über PIPELINE wäre nicht typsicher: Resolver müssten
  prüfen `if (userRequested) USER else if (pipelineTriggered) PIPELINE
  else null` — dieselbe Logik, nur unsicherer.
- Sealed-Class macht den Origin als Compile-Time-Invariante sichtbar.

### Alt-3: Triangle-FSM beibehalten + vierte Mode "WIDGET_RECOVERY"

**Verworfen, weil:**
- Das macht das Truth-Table-Problem nur akuter — vier Modi statt drei,
  mehr Rows, mehr Row-Priority-Konflikte.
- Origin-Information bleibt implizit (HOVER-vs-WIDGET-vs-RECOVERY hat
  Origin-Bedeutung, aber kein explizites Tracking).
- Bidirectional-Render passt weiterhin nicht.

### Alt-4: Origin als Sub-Field auf ViewMode mit `data class WIDGET(origin)`

```kotlin
sealed class ViewMode {
    object KEYBOARD : ViewMode()
    data class WIDGET(val origin: WidgetOrigin) : ViewMode()
    object HOVER : ViewMode()
}
```

**Verworfen, weil:**
- HOVER bleibt redundant — `HOVER` ist exakt `WIDGET(origin=PIPELINE)
  + !imeViewVisible`.
- Bidirectional-Render passt weiterhin nicht (KEYBOARD exklusiv zu
  WIDGET).
- Konsistenz: Wenn schon Sealed-Class, dann konsequent ohne
  redundante Variante.

Die gewählte Lösung (zwei orthogonale Achsen) ist die einzige, die
**alle** semantischen Konflikte strukturell löst: Bidirectional-Render
ist strukturell sichtbar (beide Achsen-Werte gleichzeitig wahr),
Origin ist explizit + typsicher, ungültige Zustände sind compile-time
ausgeschlossen.

## Consequences

### Positive

- **Origin-Erhaltung über Lifecycle hinweg:** USER-Origin überlebt
  jeden Auto-Trigger. Pipeline-getriebene Aktivierung ist explizit
  von User-Pref unterscheidbar. Die "Wo will der User nach Pipeline-
  Ende hin?"-Frage hat eine deterministische Antwort.
- **Strukturelles Sticky-Widget** durch W5 (Row 3 vom 2026-05-21 wird
  obsolet). Sticky ist nicht mehr ein Patch in Truth-Table-Reihenfolge.
- **Bidirectional-Render-Konsistenz:** State-Modell und Renderer-Logik
  passen jetzt strukturell zusammen. Beide Surfaces können
  unabhängig live sein.
- **Erweiterbarkeit:** Neue Modi (PIP, Standalone-Overlay) brauchen
  keine `ViewMode`-Enum-Erweiterung. Sie können als neue Achse
  hinzukommen (z.B. `pipMode: PipState`) oder als Origin-Variante
  (`enum WidgetOrigin { USER, PIPELINE, PIP_FALLBACK }`).
- **Host-Block ist strukturell**: `widget is Visible` ist die einzige
  Condition für commitText-Block — kein Mode-Switch nötig.

### Negative

- **Breaking Change ohne Shim:** ~183 ViewMode-Referenzen werden in
  einem Schritt migriert. Keine inkrementelle Rollout-Möglichkeit.
- **Mehr State-Fields:** Aus einer Enum werden eine Sealed-Class +
  ein Bool. Mehr Code, leicht mehr Memory pro State-Snapshot.
- **Cascade-Komplexität:** W6 ist eine Cross-Module-Cascade
  (RecordingModule UND PipelineModule müssen ihre Idle-Boundary
  beobachten + `WidgetAction.OnPipelineQuiescent` feuern). Das ist
  mehr Verflechtung als die heutige T7-Single-Boundary in
  ViewModeModule.
- **Test-Migration ist substantiell:** Komplette Test-Suite für
  `ViewModeModuleTest` muss umgeschrieben werden. Einige
  Test-Szenarien sind nicht 1:1 portierbar (HOVER-Tests müssen zu
  `widget=Visible(PIPELINE)`-Tests werden).

### Failure Modes

- **F1: Race zwischen W2 (CloseWidget) und W3 (OnImeViewHidden)** —
  User klickt Close-Btn, gleichzeitig switcht er die App. Sequenz
  unklar.
  **Mitigation:** W2 setzt `suppressBit=true` *bevor* `widget=Hidden`
  wird. W3 prüft `!suppressBit`. Wenn W2 zuerst läuft: W3 wird
  geblockt (korrekt). Wenn W3 zuerst läuft: W3 setzt
  `widget=Visible(PIPELINE)`, W2 macht `widget=Hidden + suppressBit`
  — Endzustand ist korrekt.
- **F2: Verlorene Origin-Information bei W3-Cascade** — wenn `widget`
  parallel von USER auf PIPELINE wechselt (theoretisch: USER schließt
  via Close, im selben Tick startet Pipeline + IME-Hide).
  **Mitigation:** Pre-Condition `widget == Hidden` in W3 verhindert
  das strukturell. Im genannten Szenario ist nach W2 widget=Hidden,
  also kann W3 feuern und korrekt zu PIPELINE wechseln.
- **F3: `pendingSessions`-Hydration vs. Continuation-Auto-Reactivate**
  — Recovery setzt RECORDING_INTERRUPTED, gleichzeitig klickt User
  schnell Record (vor Recovery-Async-Fertigstellung).
  **Mitigation:** ActionResolvers.resolveRecordAction prüft auf
  `pendingSessions`-Inhalt (über `state.pendingSessions`); wenn
  `pendingSessions` noch leer ist (Recovery nicht fertig), startet
  neue Session (kein Continuation). Edge-Case ist akzeptabel —
  User-Verhalten "Record direkt nach App-Start" ist selten + Verlust
  ist eine Aufnahme, nicht Datenkorruption.
- **F4: Migration von Spec 3** — Spec 3 (Floating-Overlay) §7.1,
  §7.3, §6.1, §11.9, §4.8 dokumentieren die Triangle-FSM detailliert.
  Sie wird nach ADR-0008 obsolet.
  **Mitigation:** Spec 3 bekommt im Rahmen von B5 ein Decision-
  History-Entry "Superseded by ADR-0008" (oder wird komplett durch
  neue Spec im plan-co-located `research/` ersetzt). Plan-Phase 5b
  Translation-Pass kümmert sich um EN-Sidecar.
- **F5: Bidirectional-Render-Tests** — `KeyboardLayoutManagerTest`
  hat Tests gegen die alte ViewMode-Logik. Bei der Migration könnten
  Race-Edge-Cases versehentlich wegfallen.
  **Mitigation:** B5 Acceptance-Criterion fordert explizit, dass die
  Bidirectional-Render-Tests strukturell intakt bleiben (mindestens
  äquivalente Coverage). Code-Review-Gate.

## References

### Related Plan

- [`docs/plans/2026-05-21 - dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md`](../plans/2026-05-21%20-%20dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md)
  — Plan, der diese ADR umsetzt (Blocks B0-B5).

### Related ADRs (Cooperation)

- ADR-0001 (Modular Orchestrator) — `WidgetModule` ist der Single-Owner
  der `widget`-Achse.
- ADR-0002 (Cross-Module Cascade) — W6 ist eine Mode-2 Cascade aus
  Recording+Pipeline gemeinsam, W7/W8 sind RecordingModule-Self-Cascades.
- ADR-0003 (Foreground-Service) — macht den `PIPELINE`-Origin
  strukturell möglich (FGS überlebt IME, Pipeline läuft durch).
- ADR-0004 (LayoutCatalog) — Render-Backends sind pro Surface, nicht
  pro ViewMode. `KeyboardLayoutManager.modeForBackend` migriert von
  ViewMode-Param auf `(widget, recording, pipeline)`-Tupel.
- ADR-0006 (InfoBar State-Derived) — **Partial-Recovery-Producer**
  (Audio-Verlust-Warnung) hängt am `pendingSessions`/`lastErrorMessage`-
  Zustand. **KEIN** Continuation-Hint-Producer: Recovery ist silent —
  erfolgreiches Wiederaufnehmen ist für den User nicht unterscheidbar
  von einer normalen Aufnahme (UX-Entscheidung, 2026-05-21).
- ADR-0007 (Audio Multi-File Repository) — Crash-Continuation
  (RECORDING_INTERRUPTED + allocateNext) ist die UX-Motivation für
  Origin-Tracking.

### Superseded

- **ADR-0005 (Triangle-FSM, Accepted 2026-05-14)** — diese ADR
  ersetzt sie vollständig. ADR-0005 erhält im Rahmen von B0 ein
  Decision-History-Entry "Superseded by ADR-0008" und `Status:
  Superseded`.

### Specs (impacted)

- Spec 3 (Floating-Overlay) §7.1, §7.3, §6.1, §11.9, §4.8 — werden
  obsolet, B5 erzeugt entweder Decision-History-Entry oder neue Spec.

## Supersede Triggers (Forward-Looking Notes)

Eine zukünftige ADR-MMMM würde diese ADR ablösen, wenn:

- Ein **vierter Surface-Modus** eingeführt wird, der nicht als
  WidgetOrigin-Variante darstellbar ist (z.B. ein nicht-floating
  PIP-Modus, der den IME-Service ersetzt statt überlagert).
- Die **Bidirectional-Render-Architektur** rückgängig gemacht werden
  muss (z.B. wegen MotionLayout-Performance-Issues bei Doppel-Render).
  In dem Fall wäre wieder eine exklusive Surface-Wahl nötig.
- Die **Origin-Achse** sich als unzureichend erweist und durch eine
  vollständige Stack-Disziplin (z.B. eine Liste von Push-Reasons)
  ersetzt werden müsste.

## Decision History

### 2026-05-21 — Initial proposal

**Trigger:** User-Anforderung (2026-05-21): Pause-Button-Refactor im
Widget-Modus, kombiniert mit Recovery-System für FGS-Crash-Pipeline-
Outputs. Bei der Architektur-Erarbeitung wurde der Triangle-FSM-
Truth-Table-Konflikt (Row 3 vs Row 4) als grundlegendes semantisches
Problem identifiziert; gleichzeitig deckte die Bidirectional-Render-
Migration (2026-05-21 A3-Phase) auf, dass die "exklusive Mode-Wahl"
strukturell nicht mehr stimmt.

**Before:** ADR-0005 Triangle-FSM mit `ViewMode.{KEYBOARD,WIDGET,
HOVER}`. `computeViewMode`-Truth-Table mit 5 Rows + Row-Priorität
zur Konflikt-Auflösung. `userPrefersWidget` als transient Field auf
`overlay.userPrefersWidget`. T1-T7 Transitions.

**After:** `WidgetState` (Hidden | Visible(origin)) +
`imeViewVisible: Boolean` als orthogonale Achsen. W1-W8
Transitions. `WidgetOrigin` (USER | PIPELINE) explizit. Bidirectional-
Render strukturell sichtbar (beide Achsen können true sein).

**Reasoning:** Drei orthogonale Konzepte (Surface, Origin, IME-
Sichtbarkeit) in einer Enum kollabieren ist die Wurzel der
Truth-Table-Probleme. Die Origin-Information ist außerdem für die
Crash-Recovery-Continuation-UX essentiell — sie kann nicht weiterhin
implizit über die Enum-Variante codiert werden. Die zwei-Achsen-
Form ist die einzige Alternative, die alle semantischen Konflikte
strukturell (compile-time) löst.
