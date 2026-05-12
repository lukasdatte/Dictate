# Keyboard-Layout-Refactor: Service-zentrierte SSOT + 3-Modus-Triangle (KEYBOARD/WIDGET/HOVER)

**Status:** Skeleton — Architektur in Iteration mit User abgeschlossen, Detail-Specs in Arbeit
**Erstellt:** 2026-05-07 — Skeleton fertiggestellt: 2026-05-08
**Branch:** `feature/language-chip-curation` (kein eigener Worktree, vom User entschieden)
**Plan-Skill:** `feature-planning` → `implement-long-plan`
**Komplexität:** Groß (Service-Schicht + UI-Refactor + neuer Window-Typ)

---

## 1. Kontext & Auslöser

### 1.1 Symptom-Geschichte

Die heutige Architektur der Main-Button-Area hat eine systematische Bug-Klasse erzeugt:

<!-- FIX: Issue 3.0.9 – Acceptance-Verifikator-Spalte ergänzt (Bidirectional-Pointer auf Spec/§/Test-ID); Bug #3 in #3a (Send-Mode-Verdecken) + #3b (Resend-Toggle-Verschwinden) gesplittet -->

| # | Datum / Symptom | Beschreibung | Acceptance-Verifikator |
|---|---|---|---|
| 1 | 2026-05-06 — Asymmetrisches Re-Parenting (Single-Row-Toggle) | `trash_btn` / `pause_btn` wurden bei Toggle-On vergessen → unsichtbar nach Mode-Switch | Spec 2 §10 + §14.2 UI-Test 1 (Toggle Single-Row im Idle); strukturell eliminiert via MotionLayout (kein Re-Parent mehr, Spec 2 §7) |
| 2 | 2026-05-07 — Asymmetrisches Re-Parenting (Revert) | `record_pulse_layout` / `backspace_btn` / `resend_btn` wurden alle in `input_row` gestopft → Sofort-Fix mit `originalParents`-Map | Spec 2 §10 + §14.2 UI-Test 7 (Toggle Single-Row während Recording); strukturell eliminiert (L2 flat hierarchy) |
| 3a | Send-Modus + Single-Row (Send-Btn-Verdecken) | Send-Button im Send-Modus + Single-Row teilweise verdeckt | Spec 2 §10 + §14.2 UI-Test 4 ("Send-Button vollständig sichtbar — kritischer Bug-Fix-Verifikator") |
| 3b | Send-Modus + Toggle (Resend-Btn-Verschwinden) | `resend_btn` verschwindet beim Toggle Two-Row ↔ Single-Row in Idle+lastAudio | Spec 2 §14.2 UI-Test 8 (Frame-Capture während Toggle) + UI-Test 9 (Cooldown-Verifikation, Visibility ungebrochen); Spec 1 §10 Block-1: `predResendVisible` reflektiert NICHT `resendCooldown` (Cooldown landet nur im `enabledResolver`, siehe Spec 2 §8.5) |

Diese Bugs sind nicht zufällig. Sie sind Symptome **eines fundamentalen Architektur-Problems**: Layout-Position und State-Visibility werden in mehreren Code-Pfaden parallel verwaltet, die sich gegenseitig überschreiben. Jeder State-Change ist ein Race zwischen Layout-Application und Visibility-Berechnung.

### 1.2 Erweitertes Anforderungs-Set (in Iteration mit User entstanden)

Während der Plan-Iteration sind weitere Anforderungen hinzugekommen:

- **Tastatur-Wechsel-Survival**: Recording/Pipeline soll weiterlaufen, wenn der User auf eine andere Tastatur wechselt (z.B. Gboard für ein Passwort-Feld) und später zurückkommt.
- **WIDGET-Modus (User-Toggle)**: Tastatur kann in einen Floating-Widget verfrachtet werden mit 4 Buttons (Send + Pause + Trash + Schließen). InputConnection bleibt lebendig, Send funktioniert.
- **HOVER-Modus (Auto)**: Wenn die Tastatur während aktiver Aufzeichnung/Pipeline geschlossen wird, erscheint automatisch ein Floating-Window mit dem **gleichen 4-Button-Layout** wie WIDGET — der Send-Button ist hier nur **disabled** (kein InputConnection).
- **Schließen-Button-Differentialverhalten**:
  - In HOVER: Klick → Overlay verschwindet vollständig. User muss Tastatur öffnen + schließen, damit Overlay neu erscheint.
  - In WIDGET: Klick → Tastatur wird klein gemacht, State transitioniert zurück zu KEYBOARD-Modus (mit eventuell aktivem SmallMode).

### 1.3 Was die Recherche bisher ergeben hat

Bestehende Recherchen (Phase 2):

- [research/main-button-area-inventory.md](research/main-button-area-inventory.md) — Capability-Inventur (9 Buttons, 4 State-Achsen, Visibility-Matrix)
- [research/motionlayout-architecture-options.md](research/motionlayout-architecture-options.md) — Bewertung von 5+ Layout-Switching-Patterns. Empfehlung: **MotionLayout + flache MotionScene** mit `VISIBILITY_MODE_IGNORE` pro state-getriebenem Button
- [research/_pending-layout-container-architecture/](research/_pending-layout-container-architecture/_pending-layout-container-architecture.md) — bestätigt MotionLayout-Empfehlung mit konkreten Modifikationen + 2 Spike-Validierungen
- [research/_pending-state-machine-visibility-owners/](research/_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md) — 27 Visibility-Mutationen tabelliert; **5 problematische auf `resend_btn`** in 3 Klassen identifiziert; klare SSOT-Konsolidierungs-Reihenfolge
- [research/_pending-ime-lifecycle-view-recreation/](research/_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md) — IME-Lifecycle-Tiefe; bestätigt: View-Recreate ist First-Class implementiert; KEINE Coroutinen im IME, KEINE WorkManager-Dependency
- [research/_pending-persistence-background-architecture/](research/_pending-persistence-background-architecture/_pending-persistence-background-architecture.md) — Room v3 ist überraschend ausgereift; `RECORDED`-Status passt für A+B-Persistence; **KEIN neuer Table nötig**, nur 1 neue Spalte (`inserted_at`)

---

## 2. Ziele

### 2.1 Architektur-Ziele

| Dimension | Heute | Refactor-Ziel |
|-----------|-------|---------------|
| **Pipeline-Logik-Owner** | im IME-Service-Prozess (stirbt bei Tastatur-Wechsel) | **eigener Foreground-Service** `DictatePipelineService` (überlebt Tastatur-Wechsel) |
| **State-SSOT** | hybrid (KSM + RecordingUiController + Service direkt mutieren) | **`DictateOrchestrator` (Composition Root) + 13 Module im Foreground-Service** als alleinige Mutation-Quelle (Spec 1 §4.3 / §15) |
| **Visibility-Berechnung** | hybrid (5 Mutatoren auf `resend_btn`) | **deklarativ via `LayoutCatalog`-Predicates** im LayoutManager |
| **Layout-Position** | imperativ (ConstraintSet + Re-Parenting) | **deklarativ via MotionScene** (KEYBOARD-Backend) bzw. statisches XML (Overlay-Backend) |
| **Background-Robustheit** | keine (Recording verliert beim Tastatur-Wechsel den Owner) | **Foreground-Service hält den Prozess am Leben**; bei OOM-Death (selten): User-controlled Resume aus DB |
| **Layout-Modi** | 2 (Two-Row, Single-Row) | **3-Modus-Triangle** (KEYBOARD, WIDGET, HOVER) plus 4 KEYBOARD-Sub-Modi |

### 2.2 Erfolgskriterium (vom User formuliert)

> Eine UI-Änderung (neuer Button, neuer Modus, neuer State-Übergang) lässt sich an **einem Ort** beschreiben, und die UI reflektiert das **automatisch korrekt** — ohne dass man drei Klassen koordinieren oder auf Race Conditions testen muss.

### 2.3 Bug-Eliminations-Ziele

- Eliminierung der Bug-Klasse "asymmetrisches Re-Parenting" durch strukturelle Maßnahme (MotionLayout statt Re-Parent).
- Eliminierung der `resend_btn`-Race (5 Mutatoren → 1 Predicate).
- Eliminierung des `recordButton.text/isEnabled`-Hybrid (RecordingUiController + KeyboardUiController überschreiben sich heute).
- Send-Button im Send-Modus + Single-Row korrekt sichtbar, nicht verdeckt.
- "Stale-Running-Session" bei Process-Death: heute zombiehaft, künftig durch Persistence-Recovery + User-Resume gelöst.

---

## 3. Architektur-Vision

### 3.1 Triangle-FSM (KEYBOARD / WIDGET / HOVER)

```
                   ┌──────────────────────────────┐
                   │        KEYBOARD              │
                   │   (volle Tastatur, normal)   │
                   │                              │
                   │   - Two-Row / Single-Row     │
                   │   - Send-Mode-Varianten      │
                   │   - ReprocessStaging         │
                   │   - InputConnection LEBT     │
                   └──────────────────────────────┘
                       │   ▲                  ▲
                       │   │                  │
              User klickt│   │User klickt    │User öffnet
              Widget-    │   │Widget-Close   │Tastatur wieder
              Toggle     │   │(transition    │(View kommt
                         ▼   │ via SmallMode)│ zurück)
                   ┌─────────────────────────┐  │
                   │       WIDGET            │  │
                   │   (User-Wahl, floating) │  │
                   │                         │  │
                   │   - 4 Buttons           │  │
                   │   - Send funktioniert   │  │
                   │   - InputConnection lebt│  │
                   └─────────────────────────┘  │
                       │   ▲                    │
                       │   │                    │
              View hidden│   │View kommt        │
              + Recording│   │zurück (User      │
              läuft      │   │öffnet Tastatur)  │
                         ▼   │                  │
                   ┌─────────────────────────┐  │
                   │      HOVER (Auto)       │──┘
                   │                         │
                   │   - 4 Buttons (gleiches │
                   │     Layout wie WIDGET)  │
                   │   - Send DISABLED       │
                   │   - Schließen → dismiss │
                   │   - InputConnection WEG │
                   └─────────────────────────┘
```

**6 Übergänge**, alle vom `KeyboardLayoutManager` getriggert. Auto-Transitionen basieren auf zwei Inputs: `imeViewSichtbar?` und `pipelineAktiv?`. User-Toggle-Transitionen kommen über Click-Events.

### 3.2 Service-Schicht (neu)

```
╔══════════════════════════════════════════════════════════════════════╗
║                  APP-HAUPTPROZESS (immer derselbe)                   ║
║                                                                      ║
║  ┌────────────────────────────────────────────────────────────────┐ ║
║  │           DictatePipelineService (Foreground)                   │ ║
║  │   — überlebt Tastatur-Wechsel; Persistente Notification         │ ║
║  │                                                                 │ ║
║  │   <!-- FIX: Issue 1.0.1 – §3.2 Diagramm Naming-Update -->       │ ║
║  │   DictateOrchestrator (Composition Root, Single Dispatch)       │ ║
║  │     dispatch(action: Action) → Module-Registry-Routing          │ ║
║  │                                                                 │ ║
║  │   Ko-Aggregate (Hilfsklassen, F-11):                            │ ║
║  │     DictateUiStateStore  (StateFlow-Owner, _state Holder)       │ ║
║  │     PipelinePrefMirror   (SP ↔ Store-Spiegelung)                │ ║
║  │     PipelineRecovery     (DB-Replay)                            │ ║
║  │                                                                 │ ║
║  │   JobExecutor + PipelineOrchestrator (bestehend, bleibt)       │ ║
║  │                                                                 │ ║
║  │   RoomDatabase (sessions + 1 neue Spalte: inserted_at)         │ ║
║  └────────────────────────────────────────────────────────────────┘ ║
║                          ▲                                          ║
║                          │ Local Binder (kein IPC, gleicher Prozess)║
║                          │                                          ║
║  ┌───────────────────────┴────────────────────────────────────────┐ ║
║  │            DictateInputMethodService (IME-Service)              │ ║
║  │  — kommt und geht je nach Tastatur-Auswahl                      │ ║
║  │                                                                 │ ║
║  │   KeyboardLayoutManager (Triangle-FSM, Render-Orchestrator)    │ ║
║  │     subscribe(pipelineService.state) { render(...) }           │ ║
║  │                                                                 │ ║
║  │   ImeViewBackend (KEYBOARD-Modus)                              │ ║
║  │   OverlayBackend (WIDGET + HOVER, beide nutzen es)             │ ║
║  └────────────────────────────────────────────────────────────────┘ ║
╚══════════════════════════════════════════════════════════════════════╝
```

**Schlüsseleigenschaften:**
- **Beide Services im gleichen Prozess** (kein IPC, Local Binder + StateFlow für Kommunikation).
- **Foreground Service hält den Prozess am Leben**, auch wenn IME-Service stirbt (Tastatur-Wechsel-Survival).
- **KEIN WorkManager-Worker** (vom User entschieden). Bei OOM-Death: User-Resume aus DB.
- **Persistente Notification** dient gleichzeitig als Foreground-Service-Pflicht-UI und als Status-Anzeige für User.

### 3.3 LayoutDescriptor-Pattern (Kern des Refactors)

Anstatt verteilten Code für jedes Layout zu haben, lebt jeder Layout-Modus als **Datenstruktur** in einem zentralen `LayoutCatalog`:

```kotlin
<!-- FIX: Issue 1.0.2 – §3.3 LogicalButtonId Liste auf Spec-2-§3.1-Stand -->
enum class LogicalButtonId { RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER, OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE }

data class LayoutMode(
    val id: LayoutModeId,
    val backend: BackendType,
    val rows: List<RowDescriptor>,
)

data class ButtonSlot(
    val logicalId: LogicalButtonId,
    val widthPolicy: WidthPolicy,
    val visibilityPredicate: (DictateUiState) -> Boolean,
    val iconResolver: (DictateUiState) -> Int? = { null },
    val textResolver: (DictateUiState) -> CharSequence? = { null },
    val enabledResolver: (DictateUiState) -> Boolean = { true },
    val actionResolver: (DictateUiState) -> Action,
)

object LayoutCatalog {
    val KEYBOARD_TWO_ROW = LayoutMode(...)
    val KEYBOARD_SINGLE_ROW = LayoutMode(...)
    val KEYBOARD_TWO_ROW_SEND_MODE = LayoutMode(...)
    val KEYBOARD_SINGLE_ROW_SEND_MODE = LayoutMode(...)
    val KEYBOARD_REPROCESS_STAGING = LayoutMode(...)
    val OVERLAY_5BUTTON = LayoutMode(...)  // gemeinsam für WIDGET + HOVER  <!-- FIX: Issue 1.0.2 – OVERLAY_4BUTTON → OVERLAY_5BUTTON -->
}
```

Render-Backends iterieren die Slots, evaluieren die Resolver gegen den aktuellen `DictateUiState` und setzen Visibility/Icon/Text/Action.

---

<!-- FIX: Issue 1.1.6 / R.7 + 3.1.14 – Block 1 in 1a (heutiger Code, kompilier-grün) und 1b (PipelineService-Container) gesplittet -->
## 4. Building Blocks (Implementierungs-Reihenfolge)

| # | Block | Spec | Kurz-Beschreibung | Komplexität |
|---|-------|------|---------------------|-------------|
| **1a** | **Quick-Wins im heutigen Code** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) §11.2.2 + Spec 2 §13.5 Gap 5 | `predResendVisible`-Helper konsolidieren; alle 6 resend_btn-Mutationen auf Helper umstellen; `recordButton.text/isEnabled`-Hybrid auflösen — **im heutigen Code, ohne Modul-Architektur, kompilier-grün** | klein-mittel |
| 2 | **DictatePipelineService-Skelett** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) | Service-Skelett, FGS, ServiceScope, LocalBinder, persistente Notification | mittel |
| **1b** | **DictateUiState + DictateOrchestrator + 13 Module** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) §3 + §4 + §15 | Hierarchischer DictateUiState, DictateOrchestrator, alle 13 Module (Recording, Pipeline, Audio, ViewMode, Overlay, Resend, LivePrompt, Language, Layout, FeatureToggle, Theming, PendingSessions, Interruption-Phase-2), Action-Sealed-Hierarchie — **im PipelineService-Container** | groß |
| 3 | **Subsystem-Adapter-Migration** | [Spec 1](research/1-pipeline-service/1-pipeline-service.md) | LanguageController, BluetoothScoManager, AudioFocus → Module-Migration | mittel |
| 4 | **RecordingHardwareSubsystem** | [Spec 1](research/1-pipeline-service/1-pipeline-service.md) | RecordingManager → RecordingHardwareSubsystem-Adapter, audioFile in State (R.2) | mittel |
| 5 | **LayoutCatalog + ImeViewBackend** | [Spec 2: Keyboard-Layout](research/2-keyboard-layout/2-keyboard-layout.md) | KeyboardLayoutManager, LayoutCatalog, MotionScene, VISIBILITY_MODE_IGNORE, RecordingAnimationController, ContentAreaController + PromptVisibilityController + OverlayResetHandler (R.10) | groß |
| 6 | **OverlayBackend (WIDGET + HOVER)** | [Spec 3: Floating-Overlay](research/3-floating-overlay/3-floating-overlay.md) | Overlay-XML, WindowManager-Integration, Permission-Observer, Schließen-Button-Differential, Mode-Transitionen, Drag-Lifecycle (R.18, R.19) | mittel-groß |

**Reihenfolge:** **1a** → 2 → **1b** → 3 → 4 → 5 → 6. Block 1a (Quick-Wins im heutigen Code) **muss** vor allem anderen kommen — er bringt das heutige System auf eine Single-Owner-Visibility-Basis, ohne die Modul-Architektur einzuführen. Block 1b (DictateUiState + Module-Aufbau) kommt **nach** Block 2, weil er den PipelineService-Container braucht; das ist semantisch ehrlicher als die alte "Block 1 hängt von Block 2 ab"-Garantie.

**Risiko (neu):** Block 1a Quick-Wins ↔ Block 1b Module-Aufbau ohne Split würde Race-Condition bei Visibility-Mutationen während der Migration erzeugen. Der Split eliminiert das strukturell.

---

## 5. Spec-Files

Die Architektur ist hier auf High-Level fixiert. Die konkrete Implementierungs-Detail liegt in 3 modularen Spec-Files:

1. **[Spec 1 — Pipeline-Service-Layer](research/1-pipeline-service/1-pipeline-service.md)**: alles, was im `DictatePipelineService` lebt (Foreground Service, `DictateOrchestrator` + 13 Module, Persistence, Bound-Service-API, Lifecycle, State-SSOT-Konsolidierung).

2. **[Spec 2 — KEYBOARD-Layout (IME-View)](research/2-keyboard-layout/2-keyboard-layout.md)**: `KeyboardLayoutManager`, `LayoutCatalog`, `ImeViewBackend`, MotionLayout-Migration, Button-Render-Logik, Migration der bestehenden Layout-Controller.

3. **[Spec 3 — Floating-Overlay (WIDGET + HOVER)](research/3-floating-overlay/3-floating-overlay.md)**: `OverlayBackend`, WindowManager-Integration, Permission-Onboarding, Mode-Transitionen, Touch-Routing.

Jeder Spec ist eigenständig lesbar und definiert seine eigenen Akzeptanzkriterien.

---

## 6. Risiken (Plan-Level, spec-übergreifend)

| Risiko | Mitigation |
|--------|------------|
| **PulseLayout-Animation in MotionLayout-Transition** könnte brechen (kein offizieller Vertrag) | Spike-Validierung am Anfang von Block 5 (Spec 2 §11). Falls Bruch: Fallback zu programmatischen ConstraintSets (Option 4 aus motionlayout-architecture-options.md). |
| **SYSTEM_ALERT_WINDOW-Permission verweigert** durch User | Notification-Fallback (Spec 3 §10). Foreground-Service-Notification ist ohnehin Pflicht — User sieht Status auch ohne Overlay-Permission. |
| **Foreground-Service-Notification-UX** könnte als invasiv empfunden werden | Persistente Notification ist Android-Standard für Background-Audio-Apps. Akzeptanz erprobt. |
| **DB-Migration M3→M4** verliert Daten bei Rollback | M4 ist additiv (`ALTER TABLE … ADD COLUMN inserted_at`); Rollback ist trivial (NULL bleibt NULL). |
| **State-Konsolidierung bricht bestehende Use-Cases** | Block 1 als isolierter Refactor mit vollständigem Manual-Test-Pass vor Block 2 (Spec 1 §10). |
| **MotionLayout-Inflation-Cost** beim ersten `onCreateInputView` zu hoch | Spike-Messung am Anfang von Block 5 (Spec 2 §11). |
| **Keyboard-Switch-Survival** funktioniert nicht wie erwartet | Manual-Test-Plan in Spec 1 §10 deckt dies ab — Recording starten, zu Gboard wechseln, zurück, prüfen ob State noch aktiv ist. |

---

## 7. Verbleibende offene Fragen

| ID | Frage | Antwort |
|----|-------|---------|
| **OPEN-1** | Schließen-Button in WIDGET: transitioniert zu KEYBOARD-Modus mit aktivem `SmallMode` (Tastatur-klein), oder zu KEYBOARD-Modus normal? | **RESOLVED 2026-05-08**: SmallMode-Variante. WIDGET-Schließen aktiviert SmallMode beim KEYBOARD-Modus-Wechsel. |
| **OPEN-2** | WIDGET im Idle (keine Aufnahme aktiv): nur Schließen-Button sichtbar, oder Record-Button ergänzen? | **RESOLVED 2026-05-08**: **Option B — Record-Button ergänzen**. WIDGET wird autark (5 Buttons: Record + Send + Pause + Trash + Schließen). User kann auch im WIDGET-Modus neue Aufnahmen starten. |
| **OPEN-3** | Overlay-Window: feste Position oder draggable? | **RESOLVED 2026-05-08**: **Drag von Anfang an integriert**. Position wird persistiert. Zwei separate Werte: Portrait-Position + Landscape-Position. Speicherung relativ zum Bildschirm (normalisierte 0..1-Koordinaten), damit Position über Device-Wechsel + Auflösungs-Änderungen erhalten bleibt. |
| **OPEN-4** | Auto-Resume nach OOM-Death oder manueller Resume-Button? | **RESOLVED**: manueller Resume (User-Wahl), Spec 1 §7. |
| **OPEN-5** | Notification-Fallback bei Permission-Verweigerung? | **RESOLVED**: implementieren — Foreground-Service-Notification ist ohnehin da, Spec 3 §9. |

<!-- FIX: Issue 3.1.15 (User-Decision Option A) – Plan-Body PENDING-Marker + Out-of-Scope-Sektion -->

### 7.1 Out-of-Scope (Phase 2 — Backlog)

| Bereich | Verschoben weil | Trigger zur Reaktivierung |
|---------|-----------------|---------------------------|
| **Mode 3 — Atomic Cross-Axis-Update** (§15.5 / Spec 1 §14 Open-Q 4) | Mode 1+2 decken den heutigen Bedarf vollständig ab; Mode 3 wäre OCP-Bruch ohne konkreten Use-Case (Issue 1.1.3 Option B). | Echter Use-Case mit Cascade-induziertem Race, der nicht durch Helper-Konsolidierung (R.7 Block 1a) gelöst werden kann. |
| **STANDALONE_OVERLAY-Service** (Spec 1 §14 Open-Q 5+6) | Phase 1 nutzt IME-Service-onDestroy-Cleanup (Issue 3.1.4 Option C Hybrid). Eigener `OverlayWindowService` mit FGS-Notification ist substantieller Footprint ohne klaren Phase-1-Bedarf. | Foldable-Outer-Display-Use-Case oder anderer "Overlay ohne IME-Editor-Field"-Trigger. |
| **WIDGET-autark in HOVER-Auto-Modus** (Issue 3.1.8 Option C) | Heute: WIDGET-autark gilt nur, wenn der User aktiv im WIDGET-Modus ist. HOVER-Auto-Modus zeigt nicht Record (kein InputConnection als Ziel). | Wenn STANDALONE_OVERLAY kommt, kann Record-from-HOVER neu evaluiert werden. |
| **`prefBindings()`-Migration aller 13 Module** | Phase 1: Skelett-API + Migration der Prefs, die heute hartcodiert in `PipelinePrefMirror.sync()` leben. Phase 2: alle Module deklarieren ihre Prefs deklarativ; PrefMirror wird trivial-generisch. | wenn PrefMirror.sync()-Branches erkennbare Code-Smells werden (>20 Zeilen). |

### 7.2 Plan-Body PENDING-Marker

Nicht jeder im Plan vorgesehene Sub-Punkt ist im Phase-1-Apply-Pass abgehakt. Stellen, die noch
explizit Implementierungs-Arbeit verlangen, sind im Plan-Body und in den Specs mit
`<!-- PENDING: ... -->`-Markern annotiert. Beispiele:

- `<!-- PENDING: Issue 2.1.6 / R.4 – sealed-leaves-Indexing braucht Implementer-Konkretisierung der `KClass.sealedSubclasses`-Reflection in `DictateOrchestrator.collectLeaves` -->`
- `<!-- PENDING: Issue 3.1.10 / 3.1.11 – Robolectric-Tests für die drei Drag-Lifecycle-Pfade in Block 6 -->`

Während der Implementierung sind diese Marker zu suchen + abzuarbeiten; nach Block-Ende werden
sie aus dem Plan-Body entfernt (Iter-Log dokumentiert das).

### 7.3 Knowledge-Gap-Index (Stand 2026-05-11)

Drei Research-Apply-Pässe am 2026-05-11 (AudioFileFactory, SessionStatus,
ResetSuppressBit) haben **13 KG-Marker** (`<!-- KNOWLEDGE-GAP: KG-... -->`)
in Spec 1 eingebaut. Marker sind an der konkreten Plan-Stelle verankert
(damit ein Implementer beim Lesen der Sektion sofort sieht, wo Wissens-
Lücken existieren); diese Übersichts-Tabelle erlaubt Schnell-Routing.

**Severity-Klassifizierung:**

- 🔴 **Bug-Risiko** — bei Default-Strategie besteht ein konkretes Risiko,
  dass die Implementierung im Produktivbetrieb bricht.
- 🟡 **Implementer-Decision** — Implementer muss eine Wahl treffen;
  Default ist tolerierbar, aber Auswahl wirkt sich auf Code-Form aus.
- 🟢 **Nice-to-know** — fehlende Detail-Klärung; Default ist sicher,
  Code-Outcome variiert nur marginal.

| KG-ID | Titel | Severity | Heimat (Sektion + Datei) | Klärbar durch |
|---|---|---|---|---|
| KG-AFF-1 | Sofort-Delete des Cache-Files nach Persist | ✅ RESOLVED | Spec 1 §4.11.6.1 (Z. 1345) | Aufgelöst 2026-05-11 — Sofort-Delete in `PipelineOrchestrator.persistNewSession` |
| KG-AFF-2 | Alte `cacheDir/audio.m4a` stranded nach App-Update | ✅ RESOLVED | Spec 1 §4.11.6.2 (Z. 1411) | Aufgelöst 2026-05-11 — `LegacyAudioFileMigration` + DAO-Query |
| KG-AFF-3 | PreferencesFragment "Cache leeren" rekursiv? | ✅ RESOLVED | Spec 1 §4.11.6.3 (Z. 1465) | Aufgelöst 2026-05-11 — `clearCacheRecursively`-Helper in Java |
| KG-AFF-4 | Race `cleanupOrphans` vs. concurrent `allocate` | ✅ RESOLVED | Spec 1 §4.11.10 (Z. 1754) | Aufgelöst 2026-05-11 — 60 s-Cutoff via `lastModified()`-Filter |
| KG-AFF-5 | Defensive `requireNotNull(cacheDir)` im Konstruktor? | ✅ RESOLVED | Spec 1 §4.11.10 (Z. 1776) | Aufgelöst 2026-05-11 — `requireNotNull` im Lazy-Init |
| KG-SST-1 | Vollständige `ActiveJobRegistry`-Konsumentenliste | ✅ RESOLVED | Spec 1 §6.1.1 | Aufgelöst 2026-05-11 — `grep` durchgeführt: 13 unique Sites (9 Logik + 1 Bridge + 3 Doku-Anker); alle bleiben Cache-Reads, kein Refactor |
| KG-SST-2 | Cleanup-Policy für FAILED-Sessions mit ungenutztem Audio | ✅ RESOLVED | Spec 1 §6.3.1 (neu) | Aufgelöst 2026-05-11 — keine bestehende Routine; Block 3 ergänzt `findOrphanedTerminalAudio`-DAO + `cleanupOrphanedTerminalAudio()`-Service-Hook |
| KG-SST-3 | v1→v4 Multi-Step-Migration nicht im automatisierten Test | ✅ RESOLVED | Spec 1 §11.4.2 + §11.7.0 | Aufgelöst 2026-05-11 — konkreter Test-Body `migrate1To4_chain_preservesData()` in §11.4.2; Block 3 muss `androidTest/`-Dir + `room-testing`-Dependency neu anlegen |
| KG-SST-4 | `HistoryAdapter.java`-`switch` ohne `default` (Java-Lint) | ✅ RESOLVED | Spec 1 §6.1.3 + §11.7.0 | Aufgelöst 2026-05-11 — Lint-Setup leer (kein `lint.xml`/`lintOptions`); combined Fix: defensiver `default:`-Branch (Log.wtf + GONE) + `lint { error += "EnumSwitch"; abortOnError true }` |
| KG-SST-5 | Atomarität DB-Persist ↔ `ActiveJobRegistry`-Update | ✅ RESOLVED | Spec 1 §6.1.1 + §6.2 R.17 | Aufgelöst 2026-05-11 — availability-first, DB-first-Reihenfolge; Persistenz-Vertrag (R.17) erweitert; Drift-Toleranz: Cache process-local |
| KG-RSB-1 | Service-Boot-Recovery: Suppress-Bit-Default | ✅ RESOLVED | Spec 1 §15.2 (Z. 4575) | Aufgelöst 2026-05-11 — Status-quo (transient `false`); Doku in Spec 3 §11.9 |
| **KG-RSB-2** | **§4.3 Step-5-Filter blockiert Self-Cascade (RecordingModule sieht sich nicht)** | ✅ RESOLVED | **Spec 1 §15.2 (Z. 4606) + §4.3 (Z. 624)** | **Aufgelöst 2026-05-11: Bug bestätigt. Fix: Self-Filter in §4.3 Step 5 gestrichen (Auflösung A).** |
| KG-RSB-3 | Coupling-Matrix Recording × Overlay: Self-Read-Notation | ✅ RESOLVED | Spec 1 §15.1.x (Z. 4291) | Aufgelöst 2026-05-11 — Konvention oberhalb der Matrix dokumentiert |

**Severity-Heuristik dahinter:**

- 🔴 setzt voraus, dass die Default-Strategie objektiv falsch ist (nicht nur
  suboptimal). KG-RSB-2 war der einzige Marker dieser Klasse (jetzt RESOLVED),
  weil §4.3 Step 5 `it.id != module.id` deterministisch den im RecordingModule
  geschriebenen `onCrossModuleStateChange`-Code blockiert hätte (cross-referenziert
  in §4.3 unten + §15.2). Resultat: ResetSuppressBit-Cascade feuert nie,
  HOVER-Auto-Reopen funktioniert nach erstem User-Close nicht mehr.
  Verifikation siehe §9 (Iter-Log-Eintrag 2026-05-11) und Spec 1 §4.3
  (Hinweis-Block unterhalb der `dispatchInternal`-Implementation).
- 🟡 KG-Marker, deren Default akzeptabel ist, aber wo der Implementer
  aktiv zwischen Optionen wählen muss (Lint-Regel anlegen, neuer DAO-Pfad,
  Konsistenz-Modell). Diese Marker brauchen *keine* User-Entscheidung
  *vor* dem Block-Start, aber müssen während des Blocks adressed werden.
- 🟢 Default-Strategie ist im Marker selbst empfohlen + sicher. Marker
  bleibt als Doku-Anker erhalten, falls später ein Edge-Case auftritt.
- ✅ RESOLVED — Auflösungs-Detail im Marker-Block selbst (Spec-Sektion);
  enthält den konkreten Code-Patch / die Konventions-Entscheidung.

**KG-Auflösungs-Pässe 2026-05-11:**
- **Pass 1:** 7 Marker (KG-AFF-1..5, KG-RSB-1, KG-RSB-3).
- **Pass 2 (Block-3-SessionStatus):** 5 KG-SST-Marker (KG-SST-1..5) — alle
  durch Code-Recherche im Dictate-Repo + konkrete Patches in Spec 1
  aufgelöst (Konsumentenliste verifiziert, Orphan-Audio-Cleanup spezifiziert,
  v1→v4-Test-Body geschrieben, Lint-Setup-Befund + defensiver default-Branch,
  DB-first-Vertrag im R.17 verankert).

**Alle 12 KG-Marker sind jetzt ✅ RESOLVED.** Verbleibend offen: keine.

**Routing pro Block:**

- **Block 1 (Module-Skelett):** keine KGs.
- **Block 2 (DictatePipelineService):** keine KGs.
- **Block 3 (PipelineStateManager + DB v4):** KG-SST-1, KG-SST-2,
  KG-SST-3, KG-SST-4, KG-SST-5 (alle ✅ RESOLVED — Code-Patches und
  Test-Bodies in Spec 1 §6.1.1, §6.1.3, §6.2, §6.3.1, §11.4.2, §11.7.0).
  *(Zusätzlich: DAO-Query `markLegacyAudioSessionsFailed` für KG-AFF-2
  lebt im Block-3-Schema-Block.)*
- **Block 4 (AudioFileFactory + Reducer-Pure-Audio):** KG-AFF-1
  bis KG-AFF-5 (alle ✅ RESOLVED — Code-Patches in Marker-Blöcken);
  KG-RSB-2 (Bug-Fix) bereits in §4.3 implementiert (Filter gestrichen).
- **Block 5 (KEYBOARD-Layout / MotionLayout):** keine KGs.
- **Block 6 (Floating-Overlay):** KG-RSB-1, KG-RSB-3 (beide ✅ RESOLVED).

---

## 8. Referenzen

- Aktuelle Codebase-Pointer: alle Spec-Files referenzieren konkrete Files mit `file:line`.
- Phase-2-Recherche-Outputs (oben in §1.3 verlinkt) — Quelle für SSOT-Verletzungen, Lifecycle-Garantien, MotionLayout-Empfehlungen, Persistence-Stand.
- Sofort-Fix vom 2026-05-07: `KeyboardLayoutModeController.kt:60-74,183-191` (originalParents-Map). Wird durch MotionLayout-Refactor (Block 5) obsolet, kann dort entfernt werden.

---

## 9. Iteration-Log

### 2026-05-07 — Initial-Entwurf
Vorgängerversion vom Phase-2-Agent direkt geschrieben (ohne User-Phase-1-Klärung). Architektur damals: 6 Decision-Questions (D1-D6), MotionLayout vs. flat ConstraintLayout offen, alles im IME-Service-Prozess.

### 2026-05-08 — User-Iteration: Triangle-FSM, Foreground-Service, no-WorkManager
- User-Anforderung: Tastatur-Wechsel-Survival → Foreground-Service-Pattern eingeführt.
- User-Anforderung: 3-Modus-Triangle (KEYBOARD/WIDGET/HOVER) mit Auto-Transitionen.
- User-Anforderung: gemeinsames 4-Button-Overlay-Layout (Send disabled in HOVER).
- User-Entscheidung: KEIN WorkManager-Worker. Recovery via DB + manueller User-Resume.
- User-Entscheidung: KEIN Worktree, direkt im aktuellen Branch.
- User-Anforderung: Schließen-Button-Differential (HOVER dismiss, WIDGET → KEYBOARD).
- Plan in 3 modulare Specs aufgeteilt (Pipeline-Service, KEYBOARD-Layout, Floating-Overlay).
- Detail-Recherche der 3 Specs an Recherche-Agenten delegiert.

### 2026-05-08 — User-Entscheidungen zu Open Questions
- **OPEN-1**: SmallMode-Variante nach WIDGET-Schließen.
- **OPEN-2**: 5-Button-Layout im Overlay (Record + Send + Pause + Trash + Schließen) — WIDGET wird autark; auch HOVER zeigt 5 Buttons (mit Send disabled, kein InputConnection). Anstelle von OVERLAY_4BUTTON heißt der LayoutMode jetzt **OVERLAY_5BUTTON**.
- **OPEN-3**: Drag-Funktionalität von Anfang an integriert. Position wird persistiert. Zwei Werte (Portrait/Landscape), normalisierte 0..1-Koordinaten relativ zum Bildschirm. Drag-Detection via OnTouchListener mit Klick-Differenzierung (Threshold-basiert). Snap-to-Edge optional (TBD im Implementations-Detail).

Diese Entscheidungen werden via Follow-up-Agent in Spec 1, 2, 3 propagiert.

### 2026-05-08 — Cross-Spec-Konsolidierung nach Recherche-Agenten-Abschluss
Die drei Detail-Recherche-Agenten haben in §13.5 ihrer jeweiligen Specs Cross-Spec-Gaps identifiziert. Konsolidierung:

**Gefixt (in den Specs eingearbeitet):**
- `DictateUiState` (Spec 1 §3) erweitert um `resendCooldown`, `userPrefersWidget`, `overlayOnboardingPending` (alle `Boolean = false`).
- `LogicalButtonId` (Spec 2 §3.1) erweitert um `WIDGET_TOGGLE`.
- `Action`-Sealed-Klasse (Spec 2 §3.3) erweitert um `MarkOverlayOnboardingShown` und `DismissOverlayOnboarding`.
- `ImeViewBackend.render` (Spec 2 §6) nutzt jetzt `view.icon = …` statt `view.foreground = …` — konsistent mit OverlayBackend (Spec 3 §4.2).

**Bewusst akzeptierte Gaps:**
- Spec 1 G6 (MediaRecorder-Leak bei Process-Death) — Android-Cleanup greift, dokumentiert.
- Spec 3 GAP-5 (HOVER-Schließen-Edge-Case mit `userPrefersWidget=true`) — bewusste Persistenz-Eigenschaft.
- Spec 3 GAP-6 (Permission-Revoke ohne Broadcast) — selten, Polling overengineered.

**Spec-Status nach Konsolidierung:**
- Hauptplan: 273 Zeilen (final).
- Spec 1: 1298 Zeilen.
- Spec 2: 1910 Zeilen.
- Spec 3: 1441 Zeilen.
- Total Plan-Material: ~4900 Zeilen, alle Decisions begründet, alle Mutations adressed, SOLID/DRY/SSOT verifiziert in jeder Spec §13.

### 2026-05-08 — Architektur-Review-Pass: SOLID/DRY-Konsolidierung (F-1 bis F-7)

Nach einer Architektur-Review im Chat (Fokus: DRY, SOLID, langfristige Erweiterbarkeit) wurden sieben gefundene Schwächen in den Plan eingearbeitet:

**F-1 (kritisch) — `PipelineStateManager` von God-Klasse zu Composition Root.**
Frühere Spec-Versionen hatten den Manager mit fünf Verantwortungen entworfen
(State-Mutation + Pref-Sync + FSM + Recovery + JobExecutor-Init). Substruktur
in vier Hilfsklassen ist jetzt explizit (Spec 1 §4.1):
- `DictateUiStateStore` (StateFlow-Owner, pure Daten)
- `ViewModeFsm` (Pure Function: Triangle-FSM)
- `PipelinePrefMirror` (SP ↔ Store-Spiegelung)
- `PipelineRecovery` (DB-Replay)

Der Manager ist jetzt Composition Root — orchestriert Action-Methoden + Hardware,
delegiert Detail-Logik an die Hilfsklassen.

**F-2 (mittel) — DIP via `PipelineSessionRepo` + `PipelineRunner`-Interfaces.**
Frühere Konstruktor-Dependencies an `AppDatabase` (Room) und statisches
`JobExecutor`-object sind durch Interfaces abstrahiert. Vollständig testbar mit
Fakes ohne Android-Stack (Spec 1 §4.2 + §13.3.11).

**F-3 (mittel) — `DictatePipelineService` Aufteilung.**
Notification-Building und Action-PendingIntent-Routing sind in zwei dedizierte
Helper-Klassen extrahiert (Spec 1 §7.1):
- `PipelineNotificationCoordinator` (State → Notification, throttled)
- `PipelineActionRouter` (PendingIntent → Manager-Methode)

Service-Klasse selbst ist jetzt einzig Process-Lifecycle-Owner.

**F-4 (klein) — `resolveAudioFocusIcon(enabled)` als geteilter Top-Level-Helper.**
Eliminiert die letzte Drift-Quelle zwischen LayoutCatalog AUDIO_FOCUS-Slot und
EditBarController. Beide Sites lesen nicht nur denselben StateFlow, sondern
mappen auch über dieselbe Funktion (Spec 2 §8.5). Spec 2 Gap 1 RESOLVED.

**F-5 (klein) — Naming `PipelineState` → `DictateUiState`.**
Top-Level-Daten-Container heißt jetzt `DictateUiState`, eliminiert den
verwirrenden Konflikt mit der Sub-Achse `PipelineUiState`. Renaming durch alle
vier Plan-Dateien propagiert; `PipelineStateManager` (Composition Root für
Pipeline-Service-Subsystem) behält seinen Namen.

**F-6 (klein) — `view.post {}` für GAP-7 (View-Size-0 beim ersten Render).**
Statt nur defensiver `measuredWidth`-Fallback triggert `OverlayBackend.inflateAndAttach`
jetzt einen `view.post { applyPosition(stateRef) }`-Hook nach `dragHandler.attach`.
Der Callback feuert nach dem ersten Layout-Pass mit dann korrekten View-
Dimensionen und re-applied die Position (Spec 3 §4.2). Spec 3 GAP-7 RESOLVED.

**F-7 (klein) — Geteilter Slot-Apply-Helper für beide Backends.**
`ImeViewBackend.applySlotProperties` und `OverlayBackend.applySlots` waren als
separate Methoden mit identischer Sieben-Zeilen-Logik dupliziert. Beide rufen
jetzt die Top-Level-Funktion `applySlotToView(slot, view, state, ctx)` auf
(Spec 2 §5.1, neue Datei `keyboard/render/SlotRenderer.kt`). Spec 3 GAP-1
(`.foreground` vs `.icon`-Inkonsistenz) RESOLVED, weil beide Backends durch
denselben Helper laufen, der konsistent `.icon` verwendet.

**Effekt auf SOLID-Audit:** Spec 1 §13.3 ist von 4 Klassen auf 11 Klassen
erweitert; jede neue Klasse hat ihre eigene SRP/OCP/DIP-Begründung.
**Effekt auf DRY-Audit:** Spec 2 §13.4 hat zwei neue Sektionen (F-4 + F-7);
zwei Drift-Quellen sind strukturell eliminiert.
**Effekt auf Erweiterbarkeit:** Eine neue Slot-Property (z.B. `contentDescription`)
wird an genau einer Stelle (`applySlotToView`) ergänzt; eine neue State-Achse
braucht keine Manager-Reorganisation; eine vierte ViewMode (z.B. PIP) erfordert
nur einen `when`-Branch in `ViewModeFsm` und ein neues Backend.

### 2026-05-09 / 2026-05-10 — Architektur-Konsolidierung Pass 2: Modular Orchestrator (F-8 bis F-11)

Im Anschluss an einen weitergehenden Architektur-Review wurden vier weitere Korrekturen eingearbeitet. Sie adressieren das langfristige Skalierungs-Problem zentralisierter Reducer/EffectRunner-Strukturen + die State-Inventur aus Block 3.5 (15 Achsen, 8 fehlende Pref-Mirrors, neue Subsystem-Achsen für BluetoothSco/Audio/Language/LivePrompt/Reprocess).

**F-8 (mittel) — Single Dispatch über `Action`-sealed-class.**
Frühere Spec-Versionen hatten den `LocalBinder` mit ~25 typed Forwarder-Methoden,
parallel zu einer `Action`-sealed-class mit identischen Varianten — Doppel-
Definition, DRY-Verletzung. Korrektur: LocalBinder schrumpft auf
`state` + `dispatch(action: Action)` + 2 Lifecycle-Hooks (View-Shown/Hidden,
die intern als `ViewModeAction` gefeuert werden). Kotlin-Compiler erzwingt im
Reducer-`when` Exhaustivität — keine Action wird vergessen. Spec 1 §5 angepasst.

**F-9 (mittel) — Library-Entscheidung: kein MVI-Framework + kotlinx.collections.immutable.**
Nach Library-Vergleich (Orbit-MVI, MVIKotlin, Decompose, Mavericks, Tinder
StateMachine) Entscheidung: **keine MVI-Library adoptieren**. Begründung:
- Wir sind mit StateFlow + sealed Action + Reducer + Composition Root bereits
  bei MVI; eine Library spart Boilerplate, bringt aber kein Architektur-Plus
- Plugin-Pattern (F-11) ist nicht eingebaut in keiner Library — wir bauen es ohnehin selbst
- IME-APK-Footprint relevant — Library-Adoption verschlechtert ihn
- MVIKotlin Bus-Faktor 1 (Arkady Ivanov), Mavericks Android-only/Fragment-zentriert,
  Tinder StateMachine stagniert seit 2021

**Eine** neue Library-Adoption: `kotlinx.collections.immutable` (~50 KB APK-Impact)
für echte Listen-Immutabilität (`PersistentList<PendingSession>` statt `List<PendingSession>`).
JetBrains-pflegt, garantierte Langlebigkeit. Spec 1 §3 angepasst.

**F-10 (kritisch) — Sub-State-Klassen im DictateUiState (15 Achsen).**
State-Inventur aus Block 3.5 hat 15 State-Achsen identifiziert (3 Hot-Path-FSMs,
7 Subsystem-Achsen, 8 Pref-Mirror-Achsen). Das hätte zu einer 30+-Felder-Daten-
Klasse geführt — selber SRP-Antipattern wie F-1. Korrektur: hierarchische
**Sub-State-Klassen** pro semantischer Achse (`AudioState`, `LayoutPrefs`,
`OverlayState`, `ResendState`, `LivePromptState`, `LanguageState`, `FeatureToggles`,
`ThemingState`, `InterruptionState`). Jede Sub-State-Klasse ist immutable, wird
vom jeweiligen Modul (F-11) verwaltet, hat klare Zuständigkeit.

Plus: Pref-Mirror erweitert um 9 zusätzliche UI-State-relevante Prefs
(`RewordingEnabled`, `AutoFormattingEnabled`, `InstantOutput`, `Vibration`,
`Theme`, `AccentColor`, `OverlayCharacters`, `OutputSpeed`, `UseBluetoothMic`).
Spec 1 §3 + §4.5 angepasst.

**F-11 (kritisch) — Modular Orchestrator + DictateModule-Plugin-Pattern.**
Frühere Designs hatten zentralisierte Reducer + EffectRunner mit großen `when`-
Blöcken über alle Achsen — skaliert nicht: bei 13 Modulen × 5-10 Effekten = 65-130
`when`-Branches in zwei Dateien. Korrektur: **Modular Orchestrator Pattern**,
inspiriert vom Excel-EKL Module-Augmentation-Pattern (TS `declare module`),
in Kotlin abgebildet via `sealed interface DictateModule` + `object`-Singletons.

- Jedes Modul kapselt **eigenen Sub-State + Actions + Reducer + SideEffects +
  EffectHandler + Cross-Module-Observer** in einer Datei
- `DictateOrchestrator` löst den ehemaligen `PipelineStateManager` ab; kennt nur
  das `DictateModule`-Interface, routet Actions type-safe via `KClass<A>`-Lookup
- `DictateModuleRegistry` listet alle 13 Module (12 aktiv + 1 Phase-2) zentral
- Cross-Module-Effekte: drei Modi (eigene SideEffect, Action-Cascade über
  `onCrossModuleStateChange`, atomarer Cross-Axis-Reducer für seltene Fälle)

Compile-Time-Garantien: sealed interface erzwingt Modul-Vollständigkeit,
generics erzwingen Type-Konsistenz pro Modul, KClass-Lookup garantiert eindeutiges
Action-Routing. Spec 1 §4 + §15 (NEU) angepasst.

**Effekt auf File-Struktur:** neue Verzeichnisse `state/` und `state/modules/`
mit 13 Modul-Files. Jedes Modul-File ist ~150-300 Zeilen, in sich kohärent.
Hinzufügen eines neuen Moduls = 1 neue Datei + 4 kleine Erweiterungen (ModuleId,
Action.XxxAction, DictateUiState.subState, DictateModuleRegistry.all).

**Was sich gegenüber dem Block-3.7-Design geändert hat:**
- **`PipelineStateManager` → `DictateOrchestrator`** (umbenannt, schlanker)
- **`ViewModeFsm` → `ViewModeModule`** (wandert ins Modul-System)
- **Zentraler `EffectRunner` entfällt** — pro Modul eigener `runEffect`
- **Zentraler `CrossAxisReducer` entfällt zum Großteil** — Cross-Module-Logik im
  jeweiligen Modul via `onCrossModuleStateChange`. Atomar-Cross-Axis nur für
  seltene Fälle (z.B. Pipeline-Done betrifft Resend + LivePrompt + PendingSessions
  in einem Update — kann optional via `Composed-Update`-Hook erfolgen)

**Open Questions, die in der Implementierungsphase noch zu klären sind:**
1. **Reflection vs. manuelle Registry**: aktuell manuelle Liste in `DictateModuleRegistry.all`. Alternative: `DictateModule::class.sealedSubclasses.map { it.objectInstance }`. Trade-off: Compile-Sicherheit vs. R8/ProGuard-Robustheit. Aktuelle Empfehlung: manuelle Liste mit init-Check, Reflection als optionales Upgrade.
2. **KSP-basierte Auto-Discovery**: KSP (Kotlin Symbol Processing) könnte Module via Annotation auto-registrieren. Heute überdimensioniert — als Phase-2-Option dokumentiert.
3. **Atomic Cross-Axis-Update-Hook**: ob wir einen optionalen `composeAtomic(prev, next, currentAction)`-Hook brauchen, der nach normalem Reduce + Cross-Module-Cascade einen finalen atomaren Mergung erlaubt — heute via dispatch-Rekursion gelöst, aber bei sichtbaren Zwischenzuständen evtl. zu spät.

**Spec-Status nach diesem Pass:**
- Hauptplan: ~430 Zeilen (final).
- Spec 1: ~2200 Zeilen.
- Spec 2: ~2100 Zeilen.
- Spec 3: ~1950 Zeilen.
- Total Plan-Material: ~6700 Zeilen, alle 11 Korrekturen begründet, SOLID/DRY/SSOT in jeder Spec §13 verifiziert.
- 15 State-Achsen identifiziert, 13 Module geplant (12 aktiv + 1 Phase-2).

### 2026-05-10 — Plan-Review Phase 1 + Phase 2 Apply-Pässe (1.0.x, 2.0.x, 3.0.x)

Drei aufeinanderfolgende Apply-Pässe der Plan-Review-Iteration:

**Phase 1 (1.0.1–1.0.6):** Hauptplan §3.2 (PipelineStateManager → DictateOrchestrator-Diagramm), §3.3 (LogicalButtonId-Liste + OVERLAY_4BUTTON → OVERLAY_5BUTTON), Spec 1+2-Naming-Drift-Cleanup, Action-Hierarchie auf `Action.<Modul>Action.<X>` umgestellt, hierarchische State-Pfade `state.<sub>.X` durchpropagiert.

**Phase 2 Batch 1 (2.0.1–2.0.12):** Spec-2-spezifische SOLID/DRY-Konsolidierungen + Resend-Cooldown-Inline-Doku.

**Phase 2 Batch 2 (3.0.1–3.0.12):** Verifikation Phase-1-Apply (3.0.1 + 3.0.2: §3.2 + §3.3 erneut geprüft, alle Boxen + Konstanten korrekt). Spec-3 + verbleibende Spec-2-Sites mit Phase-1-Mappings nachgepflegt (3.0.3 PipelineStateManager-Naming in Spec 3, 3.0.4 hierarchische State-Pfade in §13-Audits, 3.0.5 Action-Hierarchie an Resolver-Helpers + GAP-2). §13-Audit-Cleanups (3.0.6 §13.3 PipelineActionRouter + G6, 3.0.7 §13.5-Tabellen-Trennung Open/Cross-Spec/Resolved, 3.0.8 §13.1-Cross-Spec-Konflikt KSM:162 + EnterOverlayHandler). Acceptance-Test-Lücken geschlossen (3.0.9 Bug-Symptom-Bidi-Pointer + Resend-Toggle-Tests, 3.0.10 Cross-Module-Cascade-Acceptance, 3.0.11 MediaRecorder-Leak-Test). 3.0.12 WIDGET_TOGGLE in Spec 2 §13.1 + §13.2 + §6 buttonViews-Map.

🟡 Architektur-Decisions (Phase 1 1.1.x, Phase 2 2.1.x, Phase 2 3.1.x) bleiben offen und sind in `plan-review/validated-findings-*.md` als PENDING markiert. Sie werden im Research-Step + User-Decision-Pass adressiert.

<!-- FIX: Issue 3.1.15 (User-Decision Option A) – Iter-Log um Phase-1+2-Apply-Pässe -->
### 2026-05-10 — Phase-2-Apply-Pässe: 21 Research-Resolved + 23 User-Decisions

**Research-Resolved (R.1–R.21):** 21 🟡-Issues mit eindeutiger Recherche-Auflösung wurden mechanisch appliziert.

- **State-Foundational** (R.5 LayoutState-Container; R.2 audioFile in RecordingState; R.8 sessionId-Multi-Job-Modell mit String-IDs; R.3 NoOp-Removal + DispatchOutcome; R.4 sealed-leaves-Indexing; R.6 Cascade-Tiefe-Counter Cap 8 + DEBUG-Assertion; R.15 sessionId String durchgängig).
- **Block-1-Split** (R.7 + 3.1.14): Block 1 in 1a (heutiger Code, Quick-Wins) + 1b (Module-Architektur im PipelineService-Container) gesplittet. Reihenfolge: 1a → 2 → 1b → 3 → 4 → 5 → 6.
- **Spec-2-Konsolidierung** (R.9 View-Recreate-Vertrag in Spec 1 §8.x; R.10 KSM-Aufspaltung in ContentAreaController + PromptVisibilityController + OverlayResetHandler; R.11 visibilityMode="ignore" auf alle 9 Buttons; R.12 sceneStateId direkt am LayoutMode; R.13 KSM-Übergangs-State mit leeren Bodies in 5c; R.14 firstRender-Flag in ImeViewBackend).
- **Persistenz-Cluster** (R.16 vollständige Recovery-Logik mit Status-Branches; R.17 Idempotenz + State-First + PersistenceError-Action).
- **Spec-3-Drag-Cluster** (R.18 Drag-Hoheit + Persist-bei-Detach + Threshold-Abstimmung; R.19 Anchor TOP|START + view.effectiveSize-Helper).
- **Audit-Erweiterungen** (R.20 Cross-Module-Coupling-Matrix in §15.1.x; R.21 Cross-Spec-DRY-Tabelle + predIsIdle-Helper in `state/Predicates.kt`).
- **Naming-Drift-Cleanup** (R.1): Verifikations-Pass über alle vier Plan-Files; restliche `PipelineStateManager`-Treffer sind Iter-Log-/Kontext-Stellen und bleiben.

**User-Decisions (23 🟡 → ✅ APPLIED nach Research-Tendenz):**

- Cluster 1 — Spec-3-Module-Integration: 1.1.2 Option A+B kombiniert (dispatch + Cross-Module-Observer); 1.1.3 Option B (Mode 3 Phase-2-Backlog); 3.1.1 Option A (OverlayModule-Spec-Heimat in Spec 3 §4.8); 3.1.2 Option A (Code = Spec 1 ViewModeModule, Doku = Spec 3 §7.1; T7 als Cross-Module-Cascade); 3.1.4 Option C Hybrid (IME-Service-onDestroy → detachAllBackends; STANDALONE_OVERLAY Phase-2); 3.1.7 Option A (closeOverlay-Cascade + Suppress-Bit + Audio-File-Cleanup-Vertrag).
- Cluster 2 — HOVER-Lifecycle: 3.1.3 Option A (Permission als State-Achse + Observer + Settings-Deep-Link).
- Cluster 3 — Reentrancy: 2.1.3 Option D (try/catch im Orchestrator + EffectFailure-Action); 2.1.4 Option A (emitAction async-via-scope, dispatch Main-Thread-confined).
- Cluster 4 — Cross-Module-Invariants: 2.1.8 Option C (Paused.useBluetooth-Field) + Option A als Pattern (Invariants-Subsection).
- Cluster 5 — IME-Service-Death: 2.1.9 Option C (Clipboard + persistenter pending-Marker; `lastResultNeedsManualPaste`-State-Flag).
- Cluster 6 — Service-Cleanup: 2.1.12 Option A+B (terminale Cleanup-Sequenz + onDestroy mit runBlocking-Timeout; Modul-API erweitert um `terminate()`).
- Cluster 7 — ReducerContext + PrefBindings: 2.1.1 Option A (`global: DictateUiState`); 2.1.2 Option A (deklarative Pref-Bindings pro Modul; neue API `prefBindings()`).
- Cluster 8 — Spec-2 LayoutModule-Integration: 2.1.15 Option A+B (Beziehungs-Section + ContentAreaController als zweites RenderBackend).
- Cluster 9 — Spec-3-Sonstige: 3.1.6 Option A (early-return + Aspect-Bucket-Persist); 3.1.8 Option A+C (WIDGET-autark gilt nur in WIDGET-Modus + Acceptance-Test; STANDALONE_OVERLAY Phase-2); 3.1.9 Option A (userPrefersWidget-Persistenz als bewusste Eigenschaft + Acceptance); 3.1.10 Option A (Spec-2-Pattern: stateRef-driven, einmaliger Click-Listener).
- Cluster 10 — Plan-Hygiene: 3.1.15 Option A (Plan-Body-PENDING-Marker, neue §7.1 Out-of-Scope-Sektion, §7.2 PENDING-Marker-Konvention).

Details + per-Issue-Status: `plan-review/research-findings.md` + `plan-review/validated-findings-{phase1,batch1,batch2}.md`.

<!-- FIX: Konsolidiert 2026-05-11 – Research-Step Detail-Vertiefung (3 PENDING-Auflösungen + 13 KG-Marker) -->
### 2026-05-11 — Research-Step Detail-Vertiefung der 3 PENDING-Auflösungen

Drei parallele Research-Apply-Pässe haben die drei letzten PENDING-Marker
auf Detail-Tiefe ausgebaut und insgesamt **13 KG-Marker** (Knowledge-Gap)
in Spec 1 verankert. Konsolidierte Übersicht in §7.3 (KG-Index).

**Aufgelöste Bereiche:**

- **AudioFileFactory** (Spec 1 §4.11): von 254 auf 947 Zeilen vertieft.
  5 KG-Marker (KG-AFF-1 bis KG-AFF-5) — alle Default-Strategie 🟢 (Status-quo
  akzeptiert) bis 🟡 (Code-Erweiterung empfohlen).
- **SessionStatus** (Spec 1 §6.1 + §6.3 + §11.7.0): Konsumentenliste,
  Migration-Risiken, FAILED-Audio-Cleanup, Java-`switch`-Default-Lint,
  DB ↔ Registry-Atomarität konkretisiert. 5 KG-Marker (KG-SST-1 bis
  KG-SST-5) — **alle ✅ RESOLVED 2026-05-11** (dedizierter Auflösungs-Pass,
  Code-Recherche im Dictate-Repo). Konkrete Patches in §6.1.1 (Konsumenten-
  Tabelle + DB-first-Vertrag), §6.1.3 (defensiver `default:`-Branch),
  §6.2 R.17 (DB→Cache-Reihenfolge), §6.3.1 (Orphan-Audio-Cleanup-Routine
  — neu), §11.4.2 (`migrate1To4_chain_preservesData`-Test-Body), §11.7.0
  (Lint-Aktivierung + Risiko-Tabelle-Update).
- **ResetSuppressBit** (Spec 1 §15.2 + Spec 3 §14.1): Cross-Module-Action
  als single-reducer-owned. 3 KG-Marker (KG-RSB-1 bis KG-RSB-3).

**Wichtigster Fund: KG-RSB-2 — Production-Bug bestätigt + Fix angewendet.**

Beim Lesen des `dispatchInternal`-Snippets in Spec 1 §4.3 verifiziert:
der Self-Filter `modules.filter { it.id != module.id }` (Z. 624 vor Fix)
hätte den `RecordingModule.onCrossModuleStateChange`-Block bei der eigenen
`StartRecording`-Action deterministisch blockiert. Damit wäre die
`ResetSuppressBit`-Cascade niemals gefeuert; das Suppress-Bit wäre nach
erstem User-Overlay-Close permanent `true` geblieben; HOVER-Auto-Reopen
hätte nach diesem Klick für den Rest der Session-Lifecycle nicht mehr
funktioniert. Production-Bug bestätigt → Auflösung (A) angewendet:
Self-Filter in §4.3 Step 5 ist **gestrichen** (siehe FIX-Kommentar dort);
MAX_CASCADE_DEPTH (R.6, Cap 8) ist die alleinige Endlos-Cascade-Sicherung,
was im Plan-Body schon dokumentiert war. KG-RSB-2 ist als RESOLVED markiert;
Regression-Test (`recordingModule_idleToPreparing_emitsResetSuppressBit`)
geht in Block-4-Acceptance.

**Vollständige KG-Liste mit Severity-Klassifizierung:** siehe §7.3.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Iter-Log-Eintrag (ResetSuppressBit-Detail) -->
### 2026-05-11 — Research-Step PENDING-3: ResetSuppressBit als dedizierte Cross-Module-Action

**Auslöser:** Spec 3 §4.8 OverlayModule.onCrossModuleStateChange hatte zwei
implizite Bit-Mutations-Pfade — `SuppressAutoOverlayUntilNextSession`-Cascade
(HOVER → KEYBOARD-Boundary, setzt das Bit auf `true`) und einen impliziten
Reset, der über `SetUserPrefersWidget`-Cascade nebenbei das Bit auf `false`
gezogen hat. Doppel-Eigentum + leise Semantik-Drift, kein grep-bares Reset-
Trigger.

**Auflösung:** Reset-Pfad zentralisiert in `RecordingModule.onCrossModuleStateChange`
(Spec 1 §15.2). Pseudo-Cascade in `OverlayModule.onCrossModuleStateChange`
gestrichen (Spec 3 §4.8 — durch Erklär-Kommentar ersetzt). `OverlayAction.ResetSuppressBit`
als neue, idempotente Action eingeführt (Spec 2 §3.3 — `object`, kein Payload).
Coupling-Matrix-Zelle `Recording × Overlay` bleibt strikt-minimal auf
`C(OverlayAction.ResetSuppressBit)` ohne neuen `R(state.recording)`-Eintrag
im OverlayModule (SRP-Guardrail).

**Mehrwert:**
- **Single-Reducer-Ownership** des Suppress-Bits — alle Mutations laufen über
  einen benannten Reducer-Arm in `OverlayModule.reduce` (`SuppressAutoOverlay...`
  setzt true, `ResetSuppressBit` setzt false). Greppbar.
- **SRP-konform** — Reset-Trigger lebt im Modul, das den auslösenden State-
  Übergang besitzt (`state.recording`).
- **Idempotenz** dokumentiert (Reducer returnt `TransitionResult` auch wenn
  Bit bereits `false` → kein `DispatchOutcome.Rejected("reducer-null")`).

**Knowledge-Gaps offen** (siehe KG-Marker in den Specs):
- **KG-RSB-1** (Spec 1 §15.2): Boot-Default des Bits — empfohlen: transient
  (Default `false`, kein Pref-Mirror), Spiegel-Eintrag in Spec 3 §11.9 für
  expliziten Vertrag.
- **KG-RSB-2** (Spec 1 §15.2): §4.3 Step 5 filtert das emittierende Modul
  (`it.id != module.id`) — RecordingModule.onCrossModuleStateChange sieht
  seinen eigenen `Idle → Preparing`-Übergang nicht. Empfohlen: Self-Filter
  streichen (Auflösung A); Cascade-Depth-Counter R.6 schützt vor Endlos-Loops.
  Production-Bug-Risiko, wenn ungeklärt.
- **KG-RSB-3** (Spec 1 §15.1.x Coupling-Matrix): Recording × Overlay-Zelle
  hat keinen `R(state.recording)`-Eintrag, obwohl die Cascade-Bedingung
  Self-Read auf `state.recording` ist. Notations-Konvention ("Self-Reads
  implizit durch Diagonale `—`") sollte explizit dokumentiert werden.

Spec-Eingriffe: Spec 1 §10 (Acceptance-Klausel), §15.1.x (KG-Marker), §15.2
(Cascade-Sequence-Diagramm + KG-Marker + Logging-Empfehlung); Spec 2 §3.3
(object-Modellierung explizit); Spec 3 §10 (Suppress-Bit-Lifecycle-Acceptance),
§14.1 (Test-Skelette für Reducer + Cross-Module-Cascade + Integration).
