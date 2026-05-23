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

1. **2026-05-06**: Asymmetrisches Re-Parenting beim Single-Row-Toggle — `trash_btn`/`pause_btn` wurden bei Toggle-On vergessen.
2. **2026-05-07**: Asymmetrisches Re-Parenting beim Revert — `record_pulse_layout`/`backspace_btn`/`resend_btn` wurden alle in `input_row` gestopft → Sofort-Fix mit `originalParents`-Map (heute live).
3. **Send-Modus + Single-Row**: Send-Button verdeckt, resend-Button verschwindet beim Toggle.

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
| **State-SSOT** | hybrid (KSM + RecordingUiController + Service direkt mutieren) | **`PipelineStateManager` im Foreground-Service** als alleinige Mutation-Quelle |
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
║  │   PipelineStateManager (SSOT für ALLE State-Achsen)            │ ║
║  │     _state: MutableStateFlow<DictateUiState>                    │ ║
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
enum class LogicalButtonId { RECORD, SEND, RESEND, BACKSPACE, TRASH, SPACE, PAUSE, ENTER, AUDIO_FOCUS, OVERLAY_INDICATOR, OVERLAY_CLOSE, ... }

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
    val OVERLAY_4BUTTON = LayoutMode(...)  // gemeinsam für WIDGET + HOVER
}
```

Render-Backends iterieren die Slots, evaluieren die Resolver gegen den aktuellen `DictateUiState` und setzen Visibility/Icon/Text/Action.

---

## 4. Building Blocks (Implementierungs-Reihenfolge)

| # | Block | Spec | Kurz-Beschreibung | Komplexität |
|---|-------|------|---------------------|-------------|
| 1 | **State-SSOT-Konsolidierung** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) | `resend_btn`-Visibility zentralisieren; `recordButton.text/isEnabled`-Hybrid auflösen; Quick-Win-Fixes (KSM.refresh in Toggle-Callbacks) | klein-mittel |
| 2 | **DictatePipelineService (Foreground)** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) | Service-Skelett, persistente Notification, Local Binder, PipelineStateManager-Migration aus IME-Service | mittel-groß |
| 3 | **DB-Persistence-Erweiterung** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) | `inserted_at`-Spalte, M3→M4-Migration, Checkpoint-Hooks im PipelineStateManager, Recovery-Read | klein |
| 4 | **KeyboardLayoutManager + LayoutCatalog** | [Spec 2: Keyboard-Layout](research/2-keyboard-layout/2-keyboard-layout.md) | Triangle-FSM, Catalog-Struktur, RenderBackend-Interface, Subscription auf DictateUiState | mittel |
| 5 | **ImeViewBackend (MotionLayout-Refactor)** | [Spec 2: Keyboard-Layout](research/2-keyboard-layout/2-keyboard-layout.md) | XML-Restructuring zu MotionLayout, MotionScene mit allen KEYBOARD-Modi, VISIBILITY_MODE_IGNORE für state-getriebene Buttons, PulseLayout-Integration, Migration KeyboardLayoutModeController weg | groß |
| 6 | **OverlayBackend (WIDGET + HOVER)** | [Spec 3: Floating-Overlay](research/3-floating-overlay/3-floating-overlay.md) | Overlay-XML, WindowManager-Integration, Permission-Flow, Schließen-Button-Differential, Mode-Transitionen | mittel-groß |

**Reihenfolge:** 1 → 2 → 3 → 4 → 5 → 6. Block 1 (State-SSOT) **muss** vor allem anderen kommen, sonst werden neue Bug-Klassen auf einer noch-fragilen State-Quelle aufgebaut.

---

## 5. Spec-Files

Die Architektur ist hier auf High-Level fixiert. Die konkrete Implementierungs-Detail liegt in 3 modularen Spec-Files:

1. **[Spec 1 — Pipeline-Service-Layer](research/1-pipeline-service/1-pipeline-service.md)**: alles, was im `DictatePipelineService` lebt (Foreground Service, `PipelineStateManager`, Persistence, Bound-Service-API, Lifecycle, State-SSOT-Konsolidierung).

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
