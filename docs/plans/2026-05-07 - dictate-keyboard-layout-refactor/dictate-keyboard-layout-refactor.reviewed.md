# Keyboard-Layout-Refactor: Service-zentrierte SSOT + 3-Modus-Triangle (KEYBOARD/WIDGET/HOVER)

**Status:** Skeleton — Architektur in Iteration mit User abgeschlossen, Detail-Specs in Arbeit
**Erstellt:** 2026-05-07 — Skeleton fertiggestellt: 2026-05-08
**Branch:** `feature/dictate-keyboard-layout-refactor` (Worktree, Phase-0-skill-mandated)
**Plan-Skill:** `feature-planning` → `implement-long-plan-v2` (active 2026-05-14)
**Komplexität:** Groß (Service-Schicht + UI-Refactor + neuer Window-Typ)

<!-- EXECUTION-PLAN -->
**Implement-Long-Plan-v2 execution strategy (Phase 1a + 1b — 2026-05-14):**

- **Chunking:** Plan-Reader-Mode (plan 1699 lines + 3 specs 6984/2601/2857 lines, modular-plan-pattern D21 active). 4 chunks.json files (1 plan-level, 3 spec-level), all validated. 19 chunks total, aggregate Implementation-Score ~16,200, ~11,200 LOC est.
- **Block grouping (6 blocks):**
  - **B0** — Architecture-Foundation (C0): 5 ADRs + state-architecture docs as binding pre-code anchor. Docs-only, no code. XL D12-atomic Foundation-Pack.
  - **B1** — Pre-Architecture + Service-Skeleton (C1, C2): Block-1a quick-wins + DictatePipelineService skeleton with FGS.
  - **B2** — Modular-Orchestrator (C3 → C4 → C5 → C6 → C7): state-types → orchestrator+registry → core modules → aux modules → wiring. Heaviest block (4350 score).
  - **B3** — Migration-Persistence-AudioFactory (C8 → C9 → C10 → C11): subsystem-adapter migration → DB schema M3→M4 → recovery+cleanup → AudioFileFactory. Introduces androidTest infrastructure (C9 Step 0).
  - **B4** — Keyboard-Layout-Catalog (C12 → C13 → C14 → C15): LayoutCatalog → MotionScene XML → ImeViewBackend → service wiring + cleanup.
  - **B5** — Floating-Overlay (C16 → C17 → C18): OverlayBackend + window → permission flow → mode-transitions T1-T7 + drag.
- **Test approach:** JVM unit tests with handwritten fakes (K-1, no Mockito/MockK) + no Android Context (K-4, no Robolectric) per Quality-Gate decision. First-time androidTest introduction in B3 (Room migration). Espresso UI-Tests 1-10 in B4. 5-step chunk workflow with 2 commits per chunk (production + tests).
- **Block-validation:** Multi-agent audit per block (PLAN-AND-API / CONVENTION / LOGIC / TEST) + consolidator + repair-sub-phase (iter cap 3). Test-agent count per block: 1-3 (state-file `Test-Agents` column).
- **E2E (Phase 4.5):** 24 manual TCs (Android-IME E2E is device-attached, no headless runner). Knowledge-skill gap flagged (`test-knowledge-android` / `test-knowledge-mobile` / `test-knowledge-ime` missing) — runbook hand-rolled.
- **User mode:** "ohne Walkthrough" — orchestrator runs Phase 0 → 5 autonomously without pause-questions. Phase-2 user-approval is briefing-only (informational). Phase-4.5 3 blocking-user-questions defaulted in state-file; user can override before Phase 4.5 starts.
- **Worktree:** `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor` (branch `feature/dictate-keyboard-layout-refactor`).
- **State-file:** `./dictate-keyboard-layout-refactor.state.md` (alongside this plan).
<!-- /EXECUTION-PLAN -->

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
| **0** | **Architektur-Doku + ADR** | §4.0 (dieses Dokument) | ADR `docs/decisions/NNNN-modular-orchestrator-architecture.md` + Architektur-Doku `docs/architecture/state-architecture/` anlegen. Verankert das Modular-Orchestrator-Pattern (Composition Root + 13 Module + Single-Dispatch + Pure-Reducer-Invariante + Cross-Module-Cascade) projektweit. Pflicht-Lese-Quelle für alle folgenden Blöcke. **Bindender Vertrag, nicht Beschreibung.** | klein |
| **1a** | **Quick-Wins im heutigen Code** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) §11.2.2 + Spec 2 §13.5 Gap 5 | `predResendVisible`-Helper konsolidieren; alle 6 resend_btn-Mutationen auf Helper umstellen; `recordButton.text/isEnabled`-Hybrid auflösen — **im heutigen Code, ohne Modul-Architektur, kompilier-grün** | klein-mittel |
| 2 | **DictatePipelineService-Skelett** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) | Service-Skelett, FGS, ServiceScope, LocalBinder, persistente Notification | mittel |
| **1b** | **DictateUiState + DictateOrchestrator + 13 Module** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) §3 + §4 + §15 | Hierarchischer DictateUiState, DictateOrchestrator, alle 13 Module (Recording, Pipeline, Audio, ViewMode, Overlay, Resend, LivePrompt, Language, Layout, FeatureToggle, Theming, PendingSessions, Interruption-Phase-2), Action-Sealed-Hierarchie — **im PipelineService-Container** | groß |
| 3 | **Subsystem-Adapter-Migration** | [Spec 1](research/1-pipeline-service/1-pipeline-service.md) | LanguageController, BluetoothScoManager, AudioFocus → Module-Migration | mittel |
| 4 | **RecordingHardwareSubsystem** | [Spec 1](research/1-pipeline-service/1-pipeline-service.md) | RecordingManager → RecordingHardwareSubsystem-Adapter, audioFile in State (R.2) | mittel |
| 5 | **LayoutCatalog + ImeViewBackend** | [Spec 2: Keyboard-Layout](research/2-keyboard-layout/2-keyboard-layout.md) | KeyboardLayoutManager, LayoutCatalog, MotionScene, VISIBILITY_MODE_IGNORE, RecordingAnimationController, ContentAreaController + PromptVisibilityController + OverlayResetHandler (R.10) | groß |
| 6 | **OverlayBackend (WIDGET + HOVER)** | [Spec 3: Floating-Overlay](research/3-floating-overlay/3-floating-overlay.md) | Overlay-XML, WindowManager-Integration, Permission-Observer, Schließen-Button-Differential, Mode-Transitionen, Drag-Lifecycle (R.18, R.19) | mittel-groß |

**Reihenfolge:** **0** → **1a** → 2 → **1b** → 3 → 4 → 5 → 6. Block 0 ist die schriftliche Verankerung der Architektur als bindender Vertrag — ADR + Architektur-Doku werden VOR der Code-Migration angelegt, damit alle folgenden Blöcke einen verbindlichen Lese-Anker haben (und Block-1b-Implementer nicht aus dem Bauch entscheiden, was "Pure-Reducer" oder "Cross-Module-Cascade" bedeutet). Block 1a (Quick-Wins im heutigen Code) bleibt nach Block 0, weil 1a die Architektur noch nicht braucht — aber der ADR-Kontext hilft, falls in 1a eine Architektur-Frage hochkommt. Block 1b (DictateUiState + Module-Aufbau) kommt **nach** Block 2, weil er den PipelineService-Container braucht.

**Risiko (neu):** Block 1a Quick-Wins ↔ Block 1b Module-Aufbau ohne Split würde Race-Condition bei Visibility-Mutationen während der Migration erzeugen. Der Split eliminiert das strukturell.

### 4.0 Block 0 — Architektur-Doku + ADR (Pflicht-Lese-Anker)

Block 0 produziert zwei Artefakte, die VOR allen Code-Blöcken existieren müssen:

#### 4.0.1 ADRs (5 Stück) in `docs/decisions/`

**Zweck:** Bindender Architektur-Vertrag, **aufgeteilt in fünf eigenständige ADRs**, damit
spätere Phase-2-Erweiterungen einzelne Entscheidungen via Superseding ändern können, ohne
die Gesamtarchitektur in Frage zu stellen (z.B. Mode 3 zulassen, vierter ViewMode,
WorkManager-Adoption).

##### 4.0.1.0 ADR-Übersicht

| # | ADR-Slug | Subsystem | Decision-Kernsatz (geht in §2 "Decision") |
|---|---|---|---|
| 1 | `state-modular-orchestrator-pattern` | `state` | "Wir adoptieren das `DictateOrchestrator + 13 DictateModule`-Pattern mit Single-Dispatch, Pure-Reducer-Invariante und Lens-basierter Sub-State-Abkapselung. Mutation läuft ausschließlich über `dispatch(action: Action)` (Main-Thread-confined). Jede Sub-State-Achse hat genau einen Modul-Owner; Module kommunizieren nur über globalen State + Action-Pipe, niemals direkt." |
| 2 | `state-cross-module-cascade` | `state` | "Cross-Module-Effekte laufen über **Mode 1** (eigene SideEffect aus dem Reducer) oder **Mode 2** (Action-Cascade via `onCrossModuleStateChange(prev, next): List<Action>`). **Mode 3** (Atomic Cross-Axis-Update im Reducer) ist verboten. Self-Cascade ist erlaubt (KG-RSB-2-Fix). `MAX_CASCADE_DEPTH = 8` ist der einzige Endlos-Schutz. `EffectFailure` wird über `originModuleId` an das emittierende Modul geroutet, nicht über KClass-Lookup." |
| 3 | `service-foreground-pipeline-architecture` | `service` | "Pipeline-State lebt in einem dedizierten `DictatePipelineService` (Foreground Service, type=microphone) im selben App-Prozess wie der IME-Service. Kommunikation via LocalBinder + StateFlow (kein IPC). **Kein WorkManager** — bei OOM-Death erfolgt Recovery via DB-Replay + manueller User-Resume. Persistente Notification ist Pflicht (FGS) und dient gleichzeitig als Status-UI." |
| 4 | `ui-layout-catalog-motionlayout` | `ui-rendering` | "UI-Rendering ist deklarativ: `LayoutCatalog` (Predicates + Resolver pro `ButtonSlot`) + MotionScene-XML statt programmatisches Re-Parenting. `RenderBackend`-Interface mit Multi-Backend-Pattern (`ImeViewBackend` + `ContentAreaController` + `OverlayBackend` parallel). Click-Listener einmal beim `attach()` verdrahtet, lesen Backend-Felder `stateRef`/`modeRef`. `motion:visibilityMode=\"ignore\"` ist Pflicht auf allen state-getriebenen Buttons." |
| 5 | `ui-triangle-fsm-keyboard-widget-hover` | `ui-mode` | "ViewMode ist eine deterministisch berechnete Funktion `computeViewMode(imeViewVisible, userPrefersWidget, pipelineActive) → ViewMode` mit drei Werten KEYBOARD/WIDGET/HOVER und sieben Übergängen T1–T7. WIDGET ist User-getriggert (Permission-gated), HOVER ist automatisch bei `!imeViewVisible && pipelineActive`. `userPrefersWidget` ist transient (in-memory). T7 (HOVER→KEYBOARD bei Pipeline-Done) ist struktureller Schutz gegen den 'Geist-Widget'-Bug." |

**Subsystem-Header pro ADR:** ADR-1+2 = `Subsystem: state`. ADR-3 = `Subsystem: service`.
ADR-4 = `Subsystem: ui-rendering`. ADR-5 = `Subsystem: ui-mode`. Alle fünf gleichzeitig
mit `Scope: Project-Wide`-Hinweis im Header, weil sie auch andere Subsysteme binden.

##### 4.0.1.0.1 Mapping: Plan-Body-Sub-Sektion → ADR

Die hart definierten Regeln aus §4.0.1.1–§4.0.1.7 + §4.0.5 + §4.0.6 verteilen sich
auf die fünf ADRs wie folgt — beim Schreiben der ADRs werden die genannten Sektionen
1:1 übernommen (oder per Cross-Reference verlinkt).

| Plan-Body-Sektion | ADR-1 | ADR-2 | ADR-3 | ADR-4 | ADR-5 |
|---|:---:|:---:|:---:|:---:|:---:|
| §4.0.1.1 Reducer-Regeln | **§3** | — | — | — | — |
| §4.0.1.2 Action-Regeln | **§4** | (§4) | — | — | — |
| §4.0.1.3 Effect-Regeln | **§5** | (§5) | — | — | — |
| §4.0.1.4 Cross-Module-Regeln | (Ref) | **§3** | — | — | — |
| §4.0.1.5 Verbotene Patterns | (a,b,c,e,h,i,m,n) | (f,g) | — | (d,j,k,l) | — |
| §4.0.1.6 Wer hält State (Diagramm) | **§6** | — | — | — | — |
| §4.0.1.7 Wer hält UI-Wiring | — | — | — | **§3** | — |
| §4.0.5 Modul-Isolation (Lens, Diagramm, Kanäle, Registry) | **§7** | (Ref) | — | — | — |
| §4.0.6.1 Walkthrough Button | **§Cons. Pos.** | — | — | (§Cons. Pos.) | — |
| §4.0.6.2 Walkthrough Sub-Tastatur | — | — | — | **§Cons. Pos.** | — |
| §4.0.6.3 Walkthrough Modul | **§Cons. Pos.** | (§Cons. Pos.) | — | — | — |
| Hauptplan §3.1 Triangle-FSM-Diagramm | — | — | — | — | **§3** |
| Spec 3 §7.1 computeViewMode-Truth-Table | — | — | — | — | **§4** |
| Spec 3 §7.3 T1–T7 Übergänge | — | — | — | — | **§5** |
| Spec 1 §7 Foreground-Service-Lifecycle | — | — | **§3** | — | — |
| Spec 1 §11.1 FGS-Details (5s-Timeout, Permission, Bind) | — | — | **§4** | — | — |
| Spec 2 §3–§8 LayoutCatalog + RenderBackend | — | — | — | **§4** | — |
| Spec 2 §7 MotionScene-XML | — | — | — | **§5** | — |

Zeichen-Konvention: **fett** = Haupt-Heimat der Regel (1:1-Übernahme). `(Ref)` =
Cross-Reference per `@see ADR-X §Y`. `(§N)` = Sub-Sektions-Erwähnung, nicht 1:1.

##### 4.0.1.0.2 Querverweis-Graph zwischen den ADRs

```
ADR-1 (Modular-Orchestrator)
  ├── ist Voraussetzung für ─► ADR-2 (Cross-Module-Cascade)
  ├── lebt im Container von ─► ADR-3 (Foreground-Service)
  └── wird konsumiert von   ─► ADR-4 (LayoutCatalog)

ADR-2 (Cross-Module-Cascade)
  ├── erweitert             ─► ADR-1
  └── relevant für T7-Pfad  ─► ADR-5

ADR-3 (Foreground-Service)
  ├── hostet                ─► ADR-1 (Composition Root)
  └── motiviert             ─► ADR-5 (HOVER-Modus möglich, weil FGS überlebt)

ADR-4 (LayoutCatalog + MotionLayout)
  ├── konsumiert            ─► ADR-1 (Sub-State über RenderBackend)
  └── implementiert         ─► ADR-5 (Triangle-FSM via computeLayoutMode)

ADR-5 (Triangle-FSM)
  ├── implementiert in      ─► ADR-1 (ViewModeModule)
  ├── benötigt              ─► ADR-3 (FGS für HOVER-Persistenz)
  └── wird gerendert von    ─► ADR-4 (RenderBackend-Switching)
```

Jede ADR §References trägt die anderen vier ADRs als Cross-Reference (bidirektional).

##### 4.0.1.0.3 ADR-Standard-Struktur (verbindlich pro ADR, 14 Sektionen)

Alle fünf ADRs folgen derselben Sektions-Struktur (gemäß `knowledge-adr-format` skill):

| § | Sektion | Inhalt |
|---|---|---|
| 1 | **Kontext** | Symptom/Auslöser aus diesem Plan + Bezug zu §1.1 Bug-Klassen / §2.1 Architektur-Zielen |
| 2 | **Decision (Status: Proposed → Accepted)** | Der Decision-Kernsatz aus §4.0.1.0 (1:1) |
| 3..7 | **Hart definierte Regeln** | je ADR-spezifisch (siehe Mapping-Tabelle §4.0.1.0.1) |
| 8 | **Consequences (Positiv)** | konkret aus §2.1+2.3 + Walkthrough-Verweise |
| 9 | **Consequences (Negativ + Failure Modes)** | bekannte Risiken aus Plan §6 + Phase-A-Inventur Top-3 |
| 10 | **Subsystem-Header** | `Subsystem: <state/service/ui-rendering/ui-mode>` + optionaler `Scope: Project-Wide`-Hinweis |
| 11 | **References** | Plan-Datei · Specs 1+2+3 § · Phase-A-Inventur · Phase-B/C-Reports · die anderen 4 ADRs · `~/.claude/skills/knowledge-adr-format` |
| 12 | **Decision History** | Initial-Entry `2026-MM-DD — Created (Proposed)` |

**Skill + Template:** Beim Schreiben jeder ADR **MUSS** das `knowledge-adr-format`-Skill
und `~/.claude/templates/adr.md` geladen werden (mandatory load aus
`~/.claude/snippets/docs/docs.md`).

**Lifecycle pro ADR:** Status `Proposed` während Block 0; nach Plan-Approval `Accepted`.
Body danach append-only — Reversal nur via neuer ADR, die diese supersedet.

**Phase-2-Erwartungen** (potenzielle Superseding-Kandidaten):

- ADR-2 könnte superseded werden, wenn ein Use-Case Mode 3 (Atomic Cross-Axis) zwingend
  macht (Plan §7.1 Out-of-Scope).
- ADR-3 könnte superseded werden, wenn STANDALONE_OVERLAY-Service kommt (Plan §7.1
  Out-of-Scope) oder WorkManager nötig wird.
- ADR-5 könnte superseded werden, wenn ein vierter ViewMode (z.B. PIP-Modus) oder ein
  anderes Schließen-Verhalten ein Phase-2-Wunsch wird.
- ADR-1 + ADR-4 sind die stabilsten — Superseding hier würde einen größeren Architektur-
  Pivot bedeuten.

##### 4.0.1.1 Was macht ein Reducer (ADR §3)

**Positiv-Definition (was er DARF und SOLL):**

- Pure Function-Signatur: `(state: S, action: A, ctx: ReducerContext) → TransitionResult<S, E>?`
- Deterministisch — gleiche Inputs ergeben gleiche Outputs
- Liest Cross-Module-State NUR über `ctx.global: DictateUiState` (Read-only-View)
- Mutiert AUSSCHLIESSLICH den eigenen Sub-State `S` (Single-Owner-per-Sub-State-Invariante)
- Emittiert Side-Effects als Liste in `TransitionResult.sideEffects` (Plan, nicht Ausführung)
- Return `null` = "Action in diesem State unbedeutend" — der Orchestrator gibt
  `DispatchOutcome.Rejected("reducer-null")` zurück, KEIN Bug
- `when`-Block ist expression-form über sealed Actions (Compiler erzwingt Exhaustivity)
- Verfügbarer Read-Kontext: `ctx.global` (kompletter UiState-Snapshot) + `ctx.now`
  (monoton, für Timer-Vergleiche)

**Negativ-Definition (was er NICHT darf):**

- Hardware/IO/FS-Lesen (kein `audioFile.exists()`, kein `MediaRecorder.prepare()`)
- Threading (Coroutinen, Threads, Locks)
- Andere Sub-States schreiben (kein `state.copy(audio = ...)` aus
  `RecordingModule.reduce`)
- Direkten Store-Zugriff (`_state.value = …`)
- Synchron `dispatch(...)` aufrufen
- System-Time-Reads (außer `ctx.now`)
- `else`-Branch in `when` über sealed Action (außer dokumentierter OEM-extensible Effect)
- Logging mit Side-Effect (`Log.d` ist OK; `Toast.makeText` aus Reducer ist verboten)

##### 4.0.1.2 Was machen Actions (ADR §4)

**Was sie SIND:**

- Reine Daten-Container (`data class` / `data object`)
- Sealed-Class-Hierarchie mit einer inneren sealed pro Modul (`Action.RecordingAction`,
  `Action.OverlayAction`, …)
- Tragen alle Inputs, die der Reducer braucht (z.B. `audioFile: File` als
  Pre-Dispatch-Allocation aus dem Resolver)
- Einziger Eingang in die State-Mutation: `orchestrator.dispatch(action)`
- Plus eine Top-Level `Action.EffectFailure(originModuleId, effect, reason)` als
  Failure-Channel mit `originModuleId`-Routing (NICHT KClass-Routing)

**Was sie NICHT sind:**

- Träger von Logik (keine Methoden außer Daten-Properties)
- Träger von Hardware-Referenzen (kein `MediaRecorder`-Instance darin)
- Direkt ausführbar — Actions werden geplant, nicht "gerufen"
- Parallel-Definition zu LocalBinder-Forwarder-Methoden (F-8: Doppel-API verboten)

**Wie sie ausgelöst werden (fünf Quellen):**

1. **UI-Click:** `slot.actionResolver(state, services) → Action?` → `onAction(action)` →
   `binder.dispatch(action)`. Nullable-Resolver-Return ist stiller No-Op
   (`?.let { onAction(it) }`), kein Log-Spam.
2. **Android-Lifecycle:** z.B. `onFinishInputView` →
   `service.dispatch(Action.ViewModeAction.OnImeViewHidden)`.
3. **Cross-Module-Cascade:** ein anderes Modul returnt eine Action aus
   `onCrossModuleStateChange(prev, next)`, die der Orchestrator rekursiv dispatcht
   (depth+1).
4. **Effect-Completion:** `services.emitAction(action)` aus dem `runEffect`-Coroutine
   (immer asynchron, Main-Thread re-post über `Main.dispatcher.launch`).
5. **Effect-Failure (automatisch):** Orchestrator emittiert
   `Action.EffectFailure(originModuleId, effect.toString(), reason)`, wenn ein
   Effect wirft. Geroutet zurück an Origin-Modul über `reduceFailure`-Hook.

##### 4.0.1.3 Was sind Side-Effects (ADR §5)

**Was sie SIND:**

- Pro Modul ein eigenes `sealed interface Effect : SideEffect`
  (z.B. `RecordingModule.Effect.AllocateMediaRecorder(target, useBluetooth, audioFile)`)
- Asynchrone Hardware/IO-Aktionen
- Vom Reducer als **Plan** in `TransitionResult.sideEffects` emittiert, nicht ausgeführt
- Vom Orchestrator nach State-Emit in `services.scope.launch { module.runEffect(effect, services) }` ausgeführt
- Hardware-Schnittstelle ist exklusiv `runEffect` — kein anderer Code-Pfad darf
  `services.recordingHardware.*` etc. rufen

**Was sie NICHT dürfen:**

- Synchron `dispatch(...)` aufrufen (würde Frozen-Cascade-Snapshot verletzen)
- State direkt schreiben (`_state.value = …`)
- Mehrfach für dasselbe Reducer-Output ausgeführt werden (idempotent oder Reducer-Filter)
- Außerhalb des `services.scope` gestartet werden (würde beim
  `serviceScope.cancel()` leaken)

**Wie sie auf Throws reagieren:**

```kotlin
services.scope.launch {
    runCatching { module.runEffect(effect, services) }
        .onFailure { e ->
            services.emitAction(Action.EffectFailure(
                originModuleId = module.id,
                effect = effect.toString(),
                reason = e.message ?: "unknown"
            ))
        }
}
```

- `EffectFailure` wird **zurück** an Origin-Modul geroutet (NICHT KClass-Lookup) →
  `reduceFailure(state, failure, ctx)` Hook
- Default-Hook returnt `null` = "kein Failure-Pfad definiert"
  (`DispatchOutcome.Rejected("reducer-null")` ist semantisch korrekt)
- `effect.toString()` für `object`-Effects ergibt den Simple-Name; für `data class`-Effects
  enthält er die Args-Repräsentation — Module mit `data class`-Effects MÜSSEN
  `failure.effect.startsWith("AllocateMediaRecorder(")` verwenden (siehe Spec 1 §15.2)

##### 4.0.1.4 Was Cross-Module macht (ADR §6)

**Erlaubt:**

- **Mode 1 (eigene SideEffect):** Modul A reagiert auf eigene State-Transition mit
  einem Effect, der Hardware in einem anderen Subsystem anstößt. Keine State-Mutation
  in fremder Achse.
- **Mode 2 (Action-Cascade):** Modul B hört in
  `onCrossModuleStateChange(prev, next): List<Action>` auf eine State-Transition
  im globalen State und returnt Actions, die der Orchestrator rekursiv dispatcht
  (depth+1).
- **Self-Cascade:** Modul B darf seine eigene State-Transition cross-cascaden
  (Self-Filter `it.id != module.id` in `dispatchInternal` Step 5 ist **gestrichen**,
  KG-RSB-2-Fix 2026-05-11).
- **Frozen-Snapshot:** `prevGlobal` + `nextGlobal` werden vor der Cascade gesnapshottet
  — Observer sehen einen konsistenten Vor-/Nach-Vergleich, auch wenn die rekursive
  Dispatch-Sequenz den State weiter mutiert.

**Verboten:**

- **Mode 3 (Atomic Cross-Axis-Update):** ein Reducer mutiert mehrere Sub-State-Achsen
  gleichzeitig. Phase-2-Backlog ohne expliziten Use-Case.
- Synchroner Re-Dispatch aus `onCrossModuleStateChange` (immer über die rekursive
  `dispatchInternal`-Schleife, nicht durch Code-Duplikation).
- Kreuz-Mutation auf fremder Achse via `state.copy(otherAxis = …)` — gehört in eine
  Action des Owner-Moduls.

**Endlos-Schutz:** `MAX_CASCADE_DEPTH = 8` — DEBUG-`error()`, Release-Log-`error`.
Einziger Schutz nach KG-RSB-2-Fix.

##### 4.0.1.5 Hart verbotene Patterns (ADR §7)

| # | Verbotenes Pattern | Begründung |
|---|---|---|
| (a) | Direkter `_state.value = …` außerhalb des Stores | bricht Single-Dispatch-Ownership (F-8) |
| (b) | Hardware/IO im Reducer | bricht Pure-Reducer-Invariante (F1+F2), Test wird non-deterministisch |
| (c) | `else`-Branch in `reduce`-`when` über sealed Actions | verliert Exhaustivity-Garantie, neue Action-Variante wird silent geschluckt |
| (d) | Re-Parenting beim Layout-Switch (ConstraintSet-Rewriting) | reaktiviert Bug §1.1 #1+#2 (asymmetrisches Re-Parenting). MotionLayout ist verbindlich |
| (e) | `toMutableList()`-Round-Trip auf `PersistentList` | zerstört structural-sharing, Performance-Regression |
| (f) | Self-Filter (`it.id != module.id`) in `dispatchInternal` Step 5 | KG-RSB-2-Reaktivierung, HOVER-Auto-Reopen kaputt |
| (g) | Cross-Axis-Mutation im Reducer (Mode 3) | Phase-2-Backlog, kein Use-Case |
| (h) | Synchroner Re-Dispatch aus EffectHandler | bricht Frozen-Cascade-Snapshot; nur via `services.emitAction(...)` async |
| (i) | Action-Forwarder-Methoden im LocalBinder parallel zur Action-Hierarchie | F-8: Doppel-Definition verboten |
| (j) | `pred*Visible`-Predicate enthält Cooldown-Logic | reaktiviert Bug §1.1 #3b; Cooldown gehört in `enabledResolver` |
| (k) | View-Visibility-getriebene Buttons ohne `motion:visibilityMode="ignore"` im MotionScene-XML | MotionScene + LayoutCatalog kollidieren, sichtbarer Sprung |
| (l) | Click-Listener pro Render-Tick neu verdrahten | Memory-Leak (Lambda pro Button pro Tick) |
| (m) | `actionResolver`-Return `Action.NoOp` (statt `null`) | `Action.NoOp` ist gestrichen (R.3), Click-Filter ist `?.let`-Aussortierung |
| (n) | Direkter Modul-zu-Modul-Aufruf (`recordingModule.foo()` aus OverlayModule) | bricht Modul-Entkopplung; Inter-Modul-Kommunikation NUR über globalen State + Action-Pipe |

##### 4.0.1.6 Wer hält State (ADR §8)

```
┌──────────────────────────────────────────────────────────────┐
│  DictateUiStateStore                                          │
│                                                              │
│    private val _state = MutableStateFlow(initial)            │
│    val state: StateFlow<DictateUiState> = _state.asStateFlow()│
│                                                              │
│    fun update(reducer: (DictateUiState) -> DictateUiState)   │
└──────────────────────────────────────────────────────────────┘
       ▲                                            │
       │ EINZIGE Mutations-                         │ Read-only via collect{}
       │ Schnittstelle                              │
       ▼                                            ▼
┌──────────────────────────┐         ┌─────────────────────────────┐
│  DictateOrchestrator     │         │  Subscriber                 │
│  .dispatch(action)       │         │  - KeyboardLayoutManager    │
│                          │         │  - PipelineNotificationCoord│
│  Main-Thread-confined    │         │  - PrefMirror               │
└──────────────────────────┘         │  - DB-Subscriber            │
                                     └─────────────────────────────┘
```

**Drei Hauptregeln:**

1. **State ist read-only nach außen** — nur via `state.collect { ... }`.
2. **Mutation einzig über `dispatch(action: Action)`** — die Hand-Mutation
   `_state.value = ...` ist privat im Store. Verstoß ist Code-Review-Verletzung, kein
   Compile-Check.
3. **`dispatch()` ist Main-Thread-confined**
   (`require(Looper.myLooper() == Looper.getMainLooper())`).

##### 4.0.1.7 Wer hält UI-Wiring (ADR §10)

- `RenderBackend`-Interface ist die einzige UI-Mutator-Schnittstelle.
- Pro Render-Surface ein Backend: `ImeViewBackend` (KEYBOARD), `OverlayBackend`
  (WIDGET + HOVER), `ContentAreaController` (Container-Visibility, zweites Backend
  parallel zu ImeView, R.10).
- Click-Listener werden EINMAL beim `attach()` in `wireStaticHandlers()` verdrahtet.
- Lambdas referenzieren `stateRef`/`modeRef`-Felder, NICHT die `render`-Argumente —
  damit lebt genau ein Lambda pro Button für die gesamte Backend-Lebenszeit (L8).
- Resolver-Result ist `Action?`. `null` ist stiller No-Op (kein Log-Spam).
- Visibility/Enabled NICHT im Click-Listener prüfen — `visibilityPredicate` +
  `enabledResolver` managen das im Render-Loop.

#### 4.0.2 Architektur-Doku: `docs/architecture/state-architecture/`

**Zweck:** Ausführliche, lehrbuchartige Erläuterung mit ASCII-Diagrammen, Code-Schnipseln,
Walkthroughs für neue Module/Buttons/Backends. Komplementär zur ADR — die ADR ist
**Vertrag (knapp + bindend)**, die Architektur-Doku ist **Lehrmaterial (lang + erklärend)**.

**Mindest-Inhalt (verbindliche Topics):**

| Datei | Inhalt |
|---|---|
| `state-architecture/README.md` | Architektur-Übersicht: Triangle-FSM (3-Modus, 7 Übergänge, computeViewMode-Truth-Table) · Service-Schicht-Diagramm (FGS + IME, LocalBinder) · Modular Orchestrator (Composition Root + Module-Inventar) · Lese-Reihenfolge der Sub-Files · Pointer auf die ADR als bindenden Vertrag |
| `state-architecture/state-and-actions.md` | `DictateUiState` (immutable, 13 Sub-State-Achsen + 1 Top-Level-Flag) · PersistentList-Idiom (add/remove, KEIN `toMutableList()`-Round-Trip) · Sub-State-Klassen-Liste mit Felder + Owner-Modul · Action-Hierarchie (Sealed-Hierarchie mit inneren sealed pro Modul) · `dispatch`-Loop in 5 Steps (Cascade-Limit → Routing → Reducer → State-Write → Effects async → Cross-Module-Cascade) · `DispatchOutcome.{Applied, Rejected, Unrouted}`-Trichotomie · ReducerContext (global + now) |
| `state-architecture/modules.md` | `DictateModule<S, A, E>`-Interface komplett (`id`, `actionClass`, `read`/`write`/`initialState`, `reduce`, `reduceFailure`, `runEffect`, `onCrossModuleStateChange`, `prefBindings`, `terminate`) · `ModuleId`-Aufzählung (14 IDs) · Lens-Pattern-Mechanik · `DictateModuleRegistry.all` + Init-Sanity-Check (Doppel-Routing-Detektion) · `ModuleServices`-DI-Container · `PrefBinding<S, T>` (Phase 2) · Modul-Inventar-Übersicht mit Sub-State-Typ + Verantwortlichkeit |
| `state-architecture/effects-and-failures.md` | `SideEffect`-Sealed pro Modul (Beispiel: `RecordingModule.Effect.{AllocateMediaRecorder, ReleaseMediaRecorder, StartTimer, …}`) · `runEffect(effect, services)` im `services.scope` · `services.emitAction` (Main-Thread-Re-Post) · `EffectFailure`-Routing über `originModuleId` (NICHT KClass-Lookup) · `effect.toString()`-Konvention für `object` vs `data class` · `reduceFailure(state, failure, ctx)`-Hook · Default-Return `null` = "kein Failure-Pfad" · Failure-Recovery-Patterns (State-Rollback, Error-Marker, ToastSink) |
| `state-architecture/cross-module-cascade.md` | Mode 1 (eigene SideEffect) vs Mode 2 (Action-Cascade) vs Mode 3 (verboten) · `onCrossModuleStateChange(prev, next): List<Action>`-Konvention · frozen-snapshot (`prevGlobal` + `nextGlobal` werden vor Cascade gefangen) · Self-Cascade-Erlaubnis (KG-RSB-2-Fix mit Vor-/Nach-Code-Diff) · `MAX_CASCADE_DEPTH=8` (DEBUG-error, Release-Log-error) · Coupling-Matrix-Notation `R(state.x.y) C(Action.Y.Z)` mit Self-Read-Konvention (KG-RSB-3) · Beispiel-Cascade-Sequenz (ResetSuppressBit) |
| `state-architecture/rendering.md` | `RenderBackend`-Interface (`attach`, `detach`, `render`) · Multi-Backend-Pattern (ImeViewBackend + ContentAreaController + OverlayBackend parallel, R.10) · `LayoutCatalog`-Konzept (Predicates + Resolver pro `ButtonSlot`) · `LayoutMode` mit `sceneStateId` (R.12) · `LogicalButtonId`-Enum-Liste · MotionScene + `motion:visibilityMode="ignore"`-Verbindlichkeit (R.11) · `firstRender`-Flag (R.14) · geteilter `SlotRenderer.applySlotToView`-Helper (F-7) · `computeLayoutMode(state)`-Truth-Table |
| `state-architecture/wiring-ui.md` | Click-Listener-Wiring komplett: `wireStaticHandlers()` einmal beim `attach`, NIE pro Render-Tick (L8) · `stateRef` + `modeRef`-Felder als single source für Lambda-Reads · Nullable-Resolver-Idiom (`slot.actionResolver(s, services)?.let { onAction(it) }`) · Special-Touch-Handler (CursorSwipe, Backspace-Swipe, Enter-Overlay — state-frei) · Long-Click pro Button · Memory-Leak-Strukturschutz mit Code-Vergleich (per-Tick-Wiring vs Once-Wiring) · Daten-Fluss-Diagramm Click→Reducer→State-Emit→Render |
| `state-architecture/triangle-fsm.md` | Drei ViewModes (KEYBOARD/WIDGET/HOVER) · `computeViewMode(imeViewVisible, userPrefersWidget, pipelineActive)`-Truth-Table · 7 Übergänge T1–T7 mit Bedingungen + Cascade-Sequenz · `userPrefersWidget`-Persistenz (transient, in-memory) · Geist-Widget-Bug-Strukturschutz (T7) · Permission-Gate vor T1 |
| `state-architecture/adding-a-module.md` | §4.0.6.3 (Walkthrough Modul) — kompletter Schritt-Plan: Sub-State definieren · Action-Subklasse anlegen · Modul-Singleton implementieren (alle 9 Hooks) · `ModuleId`-Eintrag · `DictateModuleRegistry.all`-Eintrag · ggf. System-Subscription · Coupling-Matrix-Update · Tests · Inline-Anker setzen |
| `state-architecture/adding-a-button.md` | §4.0.6.1 (Walkthrough Button) — kompletter Schritt-Plan: `LogicalButtonId`-Eintrag · XML-View-ID · Backend-`buttonViews`-Mapping · ggf. neue Action-Variante · ggf. Reducer-Erweiterung im Owner-Modul · `ButtonSlot` im LayoutCatalog · Tests |
| `state-architecture/adding-a-sub-keyboard.md` | §4.0.6.2 (Walkthrough Sub-Tastatur) — zwei Varianten: (A) neue ContentArea (Container-Visibility) · (B) neue Render-Surface (eigener Backend mit `BackendType`-Erweiterung + LayoutMode + ggf. WindowManager) |
| `state-architecture/forbidden-patterns.md` | Negativ-Beispiele mit Code-Snippets: alle 14 Punkte aus ADR §7 (§4.0.1.5) je mit (a) verbotenem Code-Snippet (b) warum es bricht (c) korrekter Alternative |

**Skill:** `knowledge-doc-format` + `~/.claude/templates/universal.md` (mandatory load aus
`~/.claude/snippets/docs/docs.md`). Doc-Genre: **Architecture (Subsystem)** — §2-Heading
ist "Properties this Architecture Guarantees".

**Sprache:** Englisch (Konvention aus `~/.claude/snippets/docs/language-conventions.md` —
Architektur-Doku ist Produkt-Dokumentation).

**Inline-Anker-Konvention:** Code, das die ADR-Regeln implementiert, trägt
`@see docs/decisions/NNNN-modular-orchestrator-architecture.md §X` als Kommentar
(siehe `knowledge-doc-format` skill §"Inline anchors"). Mindestens an folgenden Stellen:

- `DictateModule`-Interface-Datei → ADR §3+4+5
- `DictateOrchestrator.dispatchInternal` → ADR §6+§7(f)
- `DictateUiStateStore` → ADR §8
- `RenderBackend`-Interface → ADR §10
- `wireStaticHandlers`-Funktion → ADR §10 + §7(l)

#### 4.0.3 Block-0-Acceptance

- [ ] **Fünf ADRs** existieren unter `docs/decisions/`, jeweils Status `Accepted`,
      mit allen 12 ADR-Sektionen aus §4.0.1.0.3, korrektem Subsystem-Header (state /
      service / ui-rendering / ui-mode), Decision-History-Initial-Entry:
  - [ ] `NNNN-state-modular-orchestrator-pattern.md`
  - [ ] `NNNN-state-cross-module-cascade.md`
  - [ ] `NNNN-service-foreground-pipeline-architecture.md`
  - [ ] `NNNN-ui-layout-catalog-motionlayout.md`
  - [ ] `NNNN-ui-triangle-fsm-keyboard-widget-hover.md`
- [ ] Inter-ADR-Querverweise gemäß §4.0.1.0.2 (alle fünf ADRs verweisen aufeinander
      in §References, bidirektional).
- [ ] `docs/decisions/README.md` (ADR-Index) existiert mit allen fünf Einträgen.
- [ ] `docs/architecture/state-architecture/README.md` + alle 12 Sub-Files (§4.0.2 Tabelle)
      existieren, alle mit "Properties this Architecture Guarantees"-Sektion (UDOC-Skeleton).
- [ ] Bidirektionale Plan-↔-ADR-Referenz: Hauptplan §8.1 "Referenzen" verlinkt alle fünf
      ADRs, jede ADR §References verlinkt den Plan.
- [ ] Bidirektionale Spec-↔-Architektur-Doku-Referenz: Spec 1/2/3 §12 "Referenzen" verlinken
      die relevanten ADRs + Architektur-Doku-Dateien (Spec 1 → ADR-1+2+3 · Spec 2 → ADR-1+4 ·
      Spec 3 → ADR-3+4+5).
- [ ] Sanity-Check: ein nicht im Plan-Review beteiligter Reader kann ADRs + Architektur-Doku
      lesen und das Modular-Orchestrator-Pattern + Triangle-FSM + FGS-Architektur reproduzieren
      (ohne in die Specs zu schauen).

#### 4.0.4 Bindender-Vertrag-Charakter

Nach Block-0-Abschluss gilt:

- Block 1a..6 dürfen die in ADR §3..§7 hart definierten Regeln **nicht** brechen.
- Beim Implementieren werden ADR + Architektur-Doku zitiert (Inline-Anker per `@see docs/decisions/NNNN-modular-orchestrator-architecture.md §3` etc., gemäß `knowledge-doc-format` skill §"Inline anchors").
- Ein Implementer, der eine Architektur-Regel anders machen will, muss Block 0 re-öffnen (ADR-Body editieren ist nur `Proposed` möglich; nach `Accepted` ist Reversal nur via neuer Superseding-ADR).

#### 4.0.5 Modul-Isolation — wie mehrere Module nebeneinander laufen

(Diese Sektion ist die SoT für die Architektur-Doku `state-architecture/modules.md` +
`cross-module-cascade.md`. Code-Implementation siehe Spec 1 §4.2 + §15.)

##### 4.0.5.1 Lens-Pattern (read/write)

Jedes Modul kennt **seinen eigenen Sub-State-Typ `S`** (z.B. `RecordingState`,
`OverlayState`). Aus dem globalen `DictateUiState` wird der Sub-State über zwei
Lens-Methoden ausgeschnitten:

```kotlin
interface DictateModule<S, A : Action, E : SideEffect> {
    fun read(global: DictateUiState): S                  // ⊂ "raus"-Lens
    fun write(global: DictateUiState, sub: S): DictateUiState  // ⊂ "rein"-Lens
    fun initialState(): S
    // ...
}

// RecordingModule:
object RecordingModule : DictateModule<RecordingState, Action.RecordingAction, RecordingModule.Effect> {
    override fun read(global: DictateUiState) = global.recording
    override fun write(global: DictateUiState, sub: RecordingState) = global.copy(recording = sub)
    override fun initialState() = RecordingState.Idle
    // ...
}
```

**Konsequenz:** der Reducer arbeitet auf `S` (z.B. `RecordingState`) und kennt das
Top-Level-`DictateUiState` **NUR** durch den `ReducerContext.global` für **Reads**.
Er kann sein eigenes `S` ändern, aber **niemals** `global.audio` oder `global.layout`
schreiben.

##### 4.0.5.2 Drei Module nebeneinander (ASCII-Diagramm)

```
                    ┌──────────────────────────────────────────────┐
                    │            DictateUiState (global)           │
                    │                                              │
                    │   recording   pipeline   layout   overlay    │
                    │      ▲           ▲          ▲        ▲       │
                    │      │           │          │        │       │
                    └──────┼───────────┼──────────┼────────┼───────┘
                           │           │          │        │
              read/write   │           │          │        │
              eigene Achse │           │          │        │
                           │           │          │        │
                   ┌───────┴────┐ ┌────┴────┐ ┌───┴────┐ ┌─┴──────┐
                   │ Recording  │ │Pipeline │ │ Layout │ │Overlay │
                   │  Module    │ │ Module  │ │ Module │ │ Module │
                   │            │ │         │ │        │ │        │
                   │ Sub-State: │ │   ...   │ │   ...  │ │   ...  │
                   │ RecordingState                                │
                   │ Actions:                                      │
                   │ RecordingAction.{Start,Stop,Pause,...}        │
                   │ Effects:                                      │
                   │ Effect.{AllocateMediaRecorder,StartTimer,...} │
                   │                                               │
                   │ ─── Read ──► ctx.global.audio.useBluetoothMic │
                   │              ctx.global.layout.singleRowMode  │
                   │              (Read-only, in eigener           │
                   │               Reducer-Logik konsumiert)       │
                   │                                               │
                   │ ─── Write ─► nur state.copy(... eigenes ...)  │
                   │              NIEMALS state.copy(audio=...)    │
                   │                                               │
                   │ ─── Cascade ► returnt List<Action> aus        │
                   │               onCrossModuleStateChange,       │
                   │               die der Orchestrator dispatcht  │
                   └───────────────────────────────────────────────┘
```

##### 4.0.5.3 Drei Kommunikations-Kanäle zwischen Modulen

| Kanal | Mechanismus | Wann |
|---|---|---|
| **Read** (Polling beim Reduce) | `ctx.global.<otherAxis>` im Reducer | Synchron, jedes Mal beim eigenen Dispatch |
| **Cascade** (Mode 2) | `onCrossModuleStateChange(prev, next): List<Action>` | Nach jeder erfolgreichen State-Mutation iteriert der Orchestrator ALLE Module |
| **Effect-emit** (Mode 1 → fremde Action) | `services.emitAction(Action.OtherModule.Foo)` aus `runEffect` | Asynchron, frischer Cascade-Snapshot |

**Wichtig (verbotene Pattern (n)):** Modul A kennt Modul B **nicht direkt** — kein
`recordingModule.overlayModule.foo()`. Alle Inter-Modul-Kommunikation läuft durch den
**globalen State** + die **Action-Pipe**. Module sind dadurch **untereinander
entkoppelt** und einzeln testbar.

##### 4.0.5.4 Innerer Sub-State vs. globaler State — Abkapselung

| Eigenschaft | Innerer Sub-State `S` | Globaler State `DictateUiState` |
|---|---|---|
| Wer schreibt? | NUR der Owner-Reducer | NUR der Store über `module.write(global, newSub)` |
| Wer liest? | Owner-Modul: über `read(global)` · Andere Module: über `ctx.global.<axis>` | Subscriber über `state.collect{}` · andere Module über `ctx.global` |
| Typ | Modul-spezifisch (`RecordingState`, `OverlayState`, ...) | Top-Level `data class DictateUiState` |
| Immutability | Sealed/`data class`, `copy()`-basiert | `data class` mit allen Sub-States als Properties |
| Identität | Hat keine außerhalb des Moduls — andere Module sehen nur den Snapshot via `ctx.global.recording` | Global einzigartig, monoton durch StateFlow emittiert |

**Abkapselung erfolgt struktural:**

1. **Compile-Time-Type-Safety:** Reducer signiert auf `S`, nicht auf `DictateUiState`.
   Versuch, `state.audio = ...` direkt zu schreiben, ist ein Compile-Error.
2. **Lens-Vertrag:** `write(global, newSub)` wird vom Orchestrator gerufen, NICHT vom
   Reducer. Reducer returnt nur das neue `S`.
3. **`ctx.global` ist `val`** — Reducer hat Read-only-Zugriff auf den globalen State,
   kein Mutator.
4. **`ModuleServices`-Container** ist read-only von außen; Hardware wird über
   Interfaces (`RecordingHardware`, `AudioFileFactory` etc.) angesprochen, nicht
   über Instanzen-Felder anderer Module.

##### 4.0.5.5 Verdrahtung: `DictateModuleRegistry.all`

Es gibt **eine** zentrale Liste aller Module:

```kotlin
object DictateModuleRegistry {
    val all: List<DictateModule<*, *, *>> = listOf(
        RecordingModule, PipelineModule, AudioModule, ViewModeModule, OverlayModule,
        ResendModule, LivePromptModule, LanguageModule, LayoutModule,
        FeatureToggleModule, ThemingModule, PendingSessionsModule, KeyboardInputModule,
        // InterruptionModule (Phase 2)
    )

    init {
        // Sanity-Check: keine doppelte Action-Klasse, keine doppelte ModuleId
        val byActionClass = all.groupBy { it.actionClass }
        require(byActionClass.values.all { it.size == 1 }) {
            "Doppel-Routing: ${byActionClass.filter { it.value.size > 1 }}"
        }
    }
}
```

Diese Liste wird vom `DictateOrchestrator` einmal beim Service-`onCreate` eingelesen.
Daraus baut der Orchestrator:

```kotlin
private val moduleByLeafClass: Map<KClass<*>, DictateModule<*, *, *>> =
    DictateModuleRegistry.all.flatMap { module ->
        module.actionClass.sealedSubclasses.map { it to module }
    }.toMap()
```

Der `KClass`-Lookup ist Init-Time-konstant. Doppel-Routing einer Action zu zwei Modulen
ist **Init-Time-Error**, kein Runtime-Bug. ProGuard-Keep-Regel für `sealedSubclasses`
ist Pflicht (sonst strippped R8 die Reflection).

#### 4.0.6 Walkthroughs — wie neue Komponenten hinzugefügt werden

(Diese Sektion ist die SoT für die Architektur-Doku-Dateien `adding-a-button.md`,
`adding-a-sub-keyboard.md`, `adding-a-module.md`.)

##### 4.0.6.1 Neuer Button (Beispiel: `INSERT_COMMA`)

Ziel: ein neuer Button im KEYBOARD_TWO_ROW-Modus, der ein Komma in den InputConnection
einfügt.

**Schritt 1 — neuer `LogicalButtonId`-Eintrag (Spec 2 §3.1):**

```kotlin
enum class LogicalButtonId {
    RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER,
    OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE,
    INSERT_COMMA,   // ← NEU
}
```

**Schritt 2 — XML-View-ID:**

```xml
<!-- res/layout/activity_dictate_keyboard_view.xml -->
<ImageButton android:id="@+id/insert_comma_btn"
             android:visibilityMode="ignore"
             ... />
```

**Schritt 3 — Button-View-Mapping im Backend (Spec 2 §6 `ImeViewBackend.buttonViews`):**

```kotlin
private val buttonViews: Map<LogicalButtonId, View> = mapOf(
    // ... bestehende
    LogicalButtonId.INSERT_COMMA to rootView.findViewById(R.id.insert_comma_btn),
)
```

**Schritt 4 — Action-Variante (`KeyboardInputAction` ist bereits da):**

```kotlin
sealed class KeyboardInputAction : Action() {
    data object Backspace : KeyboardInputAction()
    data object Enter : KeyboardInputAction()
    data object Space : KeyboardInputAction()
    data object InsertComma : KeyboardInputAction()      // ← NEU
}
```

**Schritt 5 — Reducer-Erweiterung in `KeyboardInputModule` (§15.6, Unit-State):**

```kotlin
override fun reduce(state: Unit, action: Action.KeyboardInputAction, ctx: ReducerContext)
    : TransitionResult<Unit, Effect>? = when (action) {
    Action.KeyboardInputAction.Backspace   -> TransitionResult(Unit, listOf(Effect.SendBackspace))
    Action.KeyboardInputAction.Enter       -> TransitionResult(Unit, listOf(Effect.SendEnter))
    Action.KeyboardInputAction.Space       -> TransitionResult(Unit, listOf(Effect.SendSpace))
    Action.KeyboardInputAction.InsertComma -> TransitionResult(Unit, listOf(Effect.SendText(",")))
}

sealed interface Effect : SideEffect {
    object SendBackspace : Effect
    object SendEnter : Effect
    object SendSpace : Effect
    data class SendText(val text: String) : Effect   // ← NEU, generisch
}

override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    Effect.SendBackspace  -> services.inputConnection().sendKeyEvent(KEYCODE_DEL)
    Effect.SendEnter      -> services.inputConnection().sendKeyEvent(KEYCODE_ENTER)
    Effect.SendSpace      -> services.inputConnection().commitText(" ", 1)
    is Effect.SendText    -> services.inputConnection().commitText(effect.text, 1).let { Unit }
}
```

**Schritt 6 — `ButtonSlot` im LayoutCatalog (Spec 2 §8.1):**

```kotlin
val KEYBOARD_TWO_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW,
    backend = BackendType.IME_VIEW,
    sceneStateId = R.id.scene_two_row,
    rows = listOf(
        RowDescriptor(slots = listOf(
            // ... bestehende Slots
            ButtonSlot(
                logicalId = LogicalButtonId.INSERT_COMMA,
                widthPolicy = WidthPolicy.WrapContent,
                visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD },
                iconResolver = { R.drawable.ic_comma },
                enabledResolver = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.InsertComma },
            ),
        ))
    ),
)
```

**Schritt 7 — Tests (JVM, Reducer pure):**

```kotlin
@Test
fun insertCommaAction_emitsSendTextEffect() {
    val result = KeyboardInputModule.reduce(
        state = Unit,
        action = Action.KeyboardInputAction.InsertComma,
        ctx = ReducerContext(DictateUiState.initial())
    )
    assertNotNull(result)
    assertEquals(listOf(KeyboardInputModule.Effect.SendText(",")), result!!.sideEffects)
}
```

**Was NICHT zu tun ist:**
- Keine MotionScene-XML-Änderung (Button im selben Layout-Modus, nur neuer Slot)
- Kein Cross-Module-Observer (Button hat keine Cross-Achse-Wirkung)
- Keine DB-Migration
- Keine Modul-Datei neu anlegen

**Komplexität:** ~20 LoC verteilt über 4-5 Dateien.

##### 4.0.6.2 Neue Sub-Tastatur — zwei Varianten

###### Variante A — neue ContentArea (z.B. Numerik-Pad neben QWERTZ/Emoji)

Wenn die "Sub-Tastatur" eine **andere Sicht im selben Render-Backend** ist:

**Schritt 1 — `ContentArea`-Enum erweitern:**

```kotlin
enum class ContentArea { MAIN_BUTTONS, QWERTZ, EMOJI_PICKER, NUMERIC_PAD }   // ← NEU
```

**Schritt 2 — XML-Container hinzufügen + IDs:**

```xml
<FrameLayout android:id="@+id/numeric_pad_container" ... >
    <!-- Numerik-Buttons -->
</FrameLayout>
```

**Schritt 3 — `ContentAreaController` (R.10, Spec 2 §4.1) erweitern:**

```kotlin
class ContentAreaController(views: KeyboardViews) : RenderBackend {
    override fun render(state: DictateUiState, mode: LayoutMode) {
        views.mainButtonsCl.visibility       = if (state.layout.contentArea == ContentArea.MAIN_BUTTONS) VISIBLE else GONE
        views.qwertzContainer.visibility     = if (state.layout.contentArea == ContentArea.QWERTZ) VISIBLE else GONE
        views.emojiPickerContainer.visibility = if (state.layout.contentArea == ContentArea.EMOJI_PICKER) VISIBLE else GONE
        views.numericPadContainer.visibility = if (state.layout.contentArea == ContentArea.NUMERIC_PAD) VISIBLE else GONE  // ← NEU
    }
}
```

**Schritt 4 — Trigger-Button-Slot:**

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.NUMERIC_TOGGLE,
    actionResolver = { _, _ -> Action.LayoutAction.SetContentArea(ContentArea.NUMERIC_PAD) },
    // ...
)
```

**Komplexität:** mittel.

###### Variante B — neue Render-Surface (z.B. eigenes Window via WindowManager)

Wenn die "Sub-Tastatur" ein **eigenes Window** ist (so wie WIDGET/HOVER heute über
`OverlayBackend`):

**Schritt 1 — neuer `RenderBackend` (Spec 2 §5):**

```kotlin
class NotificationPanelBackend(
    private val ctx: Context,
    private val services: ModuleServices,
    private val window: OverlayWindow,
    // ...
) : RenderBackend {
    override fun attach(onAction: (Action) -> Unit) { /* wireStaticHandlers etc. */ }
    override fun detach() { /* */ }
    override fun render(state: DictateUiState, mode: LayoutMode) { /* applySlotToView pro Button */ }
}
```

**Schritt 2 — neuer `BackendType`-Enum-Wert:**

```kotlin
enum class BackendType { IME_VIEW, OVERLAY_WINDOW, NOTIFICATION_PANEL }
```

**Schritt 3 — neuer `LayoutMode` im LayoutCatalog + ggf. neuer ViewMode-Wert in
Triangle-FSM.**

**Schritt 4 — Backend-Switching im `KeyboardLayoutManager`** (Multi-Backend-Liste,
R.10, erlaubt parallele Backends).

**Komplexität:** groß. Neue WindowManager-Window-Konfiguration, ggf. eigene
Permission-Logik, neue Touch-Routing-Strategie.

##### 4.0.6.3 Neues Modul (Beispiel: `BatterySaverModule`)

Ziel: ein Modul, das den Battery-Saver-Status beobachtet und die Pipeline pausiert,
wenn Battery-Saver aktiv ist.

**Schritt 1 — Sub-State definieren:**

```kotlin
// In DictateUiState.kt
data class BatterySaverState(val isActive: Boolean = false)

data class DictateUiState(
    // ... bestehende
    val batterySaver: BatterySaverState = BatterySaverState(),
)
```

**Schritt 2 — Actions definieren:**

```kotlin
sealed class BatterySaverAction : Action() {
    data class SetActive(val active: Boolean) : BatterySaverAction()
}
```

**Schritt 3 — Modul implementieren:**

```kotlin
// app/src/main/java/net/devemperor/dictate/state/modules/BatterySaverModule.kt
object BatterySaverModule : DictateModule<BatterySaverState, Action.BatterySaverAction, BatterySaverModule.Effect> {
    override val id = ModuleId.BatterySaver
    override val actionClass = Action.BatterySaverAction::class

    override fun read(global: DictateUiState) = global.batterySaver
    override fun write(global: DictateUiState, sub: BatterySaverState) = global.copy(batterySaver = sub)
    override fun initialState() = BatterySaverState()

    sealed interface Effect : SideEffect

    override fun reduce(state: BatterySaverState, action: Action.BatterySaverAction, ctx: ReducerContext)
        : TransitionResult<BatterySaverState, Effect>? = when (action) {
        is Action.BatterySaverAction.SetActive ->
            if (action.active != state.isActive)
                TransitionResult(state.copy(isActive = action.active), emptyList())
            else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = Unit

    // Cross-Module-Cascade: bei Battery-Saver-On → Recording pausieren
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
        if (!prev.batterySaver.isActive && next.batterySaver.isActive
            && next.recording is RecordingState.Active)
            listOf(Action.RecordingAction.PauseRecording)
        else emptyList()
}
```

**Schritt 4 — `ModuleId`-Eintrag:**

```kotlin
sealed interface ModuleId {
    // ... bestehende
    data object BatterySaver : ModuleId
}
```

**Schritt 5 — Registry-Eintrag:**

```kotlin
object DictateModuleRegistry {
    val all = listOf(
        // ... bestehende
        BatterySaverModule,
    )
}
```

**Schritt 6 — System-Subscription** für Battery-Saver-Status: ein externer Observer
(im `DictatePipelineService.onCreate`) hört auf `BatteryManager.ACTION_POWER_SAVE_MODE_CHANGED`
und ruft `binder.dispatch(Action.BatterySaverAction.SetActive(isOn))`.

**Schritt 7 — Coupling-Matrix-Update (Spec 1 §15.1.x):**

```
BatterySaver × Recording = C(RecordingAction.PauseRecording)
```

**Schritt 8 — Tests:**

```kotlin
@Test
fun batterySaver_activeWhileRecording_cascadesToPause() {
    val cascadeActions = BatterySaverModule.onCrossModuleStateChange(
        prev = DictateUiState.initial().copy(
            batterySaver = BatterySaverState(isActive = false),
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
        ),
        next = DictateUiState.initial().copy(
            batterySaver = BatterySaverState(isActive = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
        ),
    )
    assertEquals(listOf(Action.RecordingAction.PauseRecording), cascadeActions)
}
```

**Was NICHT zu tun ist:**
- Keine UI-Änderung (Modul beeinflusst Verhalten, nicht Rendering)
- Keine Spec-Datei neu schreiben
- Keine DB-Migration

**Komplexität:** mittel. 1 neue Datei (~80 LoC), 4 Edits in bestehenden Dateien,
1 Test-Datei.

##### 4.0.6.4 Allgemeines Walkthrough-Pattern

```
1. WO LEBT DAS NEUE FEATURE?
   ├── neuer Button im bestehenden Modus    → §4.0.6.1 (~20 LoC)
   ├── neue Render-Surface / Sub-Tastatur   → §4.0.6.2 (~mittel-groß)
   └── neue Verhaltens-Achse                → §4.0.6.3 (~80 LoC + Tests)

2. WAS BERÜHRT ES?
   State?         → Sub-State erweitern oder neuen anlegen
   Actions?       → neue Action-Variante in passender innerer sealed
   Effects?       → neue Effect-Variante in passender Effect-Sealed
   UI?            → ButtonSlot + ggf. View-ID
   Cross-Module?  → onCrossModuleStateChange-Hook im neuen Modul (oder Owner-Modul)

3. WAS MUSS GETESTET WERDEN?
   Reducer-Test       (JVM, pure, kein Android)
   Cascade-Test       (Cross-Module-Observer auf Vor-/Nach-Snapshot)
   Resolver-Test      (Click → Action über ButtonSlot)
   UI-Smoke-Test      (Espresso, optional)

4. WO WERDEN INLINE-ANKER GESETZT?
   Modul-Datei: @see docs/decisions/NNNN-modular-orchestrator-architecture.md
   ButtonSlot:  @see docs/architecture/state-architecture/adding-a-button.md
   Coupling-Matrix-Update: in Spec 1 §15.1.x
```

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
<!-- FIX: Phase-B S-4 (2026-05-13) – Umbenennung des alten PipelineOrchestrator als Phase-2-Backlog. -->
| **Umbenennung des alten `PipelineOrchestrator`** | Phase 1 Scope ist State-Refactor, nicht Audio-Pipeline-Refactor. Der neue `state.DictateOrchestrator` und der alte `core.PipelineOrchestrator` (Audio-Pipeline-Runner, 1383 Z.) koexistieren — Naming-Konflikt bewusst akzeptiert (Spec 1 §1.x Naming-Konvention dokumentiert die Disambiguierung). Umbenennung erfordert Konsumenten-Site-Updates (~10 Stellen, verifiziert via Spec 1 §12 Code-Pointer + Tests). | Phase 2 evaluiert eine `PipelineRunner`/`PipelineExecutor`-Umbenennung ODER eine Auflösung des Orchestrators in den `PipelineModule.runEffect`-Pfad (eliminiert den Naming-Konflikt strukturell). |

### 7.2 Plan-Body PENDING-Marker

Nicht jeder im Plan vorgesehene Sub-Punkt ist im Phase-1-Apply-Pass abgehakt. Stellen, die noch
explizit Implementierungs-Arbeit verlangen, sind im Plan-Body und in den Specs mit
`<!-- PENDING: ... -->`-Markern annotiert. Beispiele:

- `<!-- RESOLVED Phase-B S-4 (2026-05-13): Issue 2.1.6 / R.4 – sealed-leaves-Indexing-Reflection ist konkretisiert (Spec 1 §4.3 `collectLeaves`) und braucht zwingend eine ProGuard-Keep-Regel (Spec 1 §4.3 Hinweis-Block "ProGuard/R8-Keep-Regel ist Pflicht"); Vollständigkeits-Check in §4.8 init verwendet ebenfalls `Action::class.sealedSubclasses`. Block-1b-Acceptance "Phase-B S-4 ProGuard-Robustheit" verifiziert via Release-Smoke-Test. -->`
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

### 8.1 Block-0-Artefakte (Pflicht-Lese-Anker, in Block 0 angelegt)

**Fünf ADRs** unter `docs/decisions/` (Subsystem-Aufteilung siehe §4.0.1.0):

1. [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md) — `Subsystem: state` —
   Composition Root + 13 DictateModule + Single-Dispatch + Pure-Reducer + Lens-Pattern.
2. [ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md) — `Subsystem: state` —
   Mode 1+2 erlaubt, Mode 3 verboten, Self-Cascade-Erlaubnis, MAX_CASCADE_DEPTH=8,
   EffectFailure-Routing über originModuleId.
3. [ADR-0003 — service-foreground-pipeline-architecture](../../decisions/0003-service-foreground-pipeline-architecture.md) — `Subsystem: service` —
   DictatePipelineService als FGS, LocalBinder + StateFlow (kein IPC), kein WorkManager,
   persistente Notification.
4. [ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md) — `Subsystem: ui-rendering` —
   LayoutCatalog (Predicates + Resolver) + MotionScene-XML, RenderBackend-Multi-Backend,
   Click-Listener-Once-Wiring.
5. [ADR-0005 — ui-triangle-fsm-keyboard-widget-hover](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md) — `Subsystem: ui-mode` —
   3-Modus-Triangle KEYBOARD/WIDGET/HOVER + computeViewMode-Truth-Table + 7 Übergänge T1–T7.

Jede ADR hat alle 12 Sektionen aus §4.0.1.0.3. Cross-Reference-Graph: §4.0.1.0.2.

**ADR-Index** [`docs/decisions/README.md`](../../decisions/README.md) — erstmals in Block 0 angelegt;
listet alle fünf ADRs.

**Architektur-Doku** [`docs/architecture/state-architecture/`](../../architecture/state-architecture/README.md) mit 12 Sub-Files
(siehe §4.0.2 dieses Plans). Ausführliches Lehrmaterial mit ASCII-Diagrammen,
Code-Schnipseln, Walkthroughs. Auch Code-Inline-Anker zeigen hierhin
(`@see docs/architecture/state-architecture/...`).

**Bidirektionale Referenz-Pflicht:**
- Jede der fünf ADRs §References verlinkt diesen Plan + die anderen vier ADRs.
- Dieser Plan-Block §4.0 + §8.1 verlinken alle fünf ADRs.
- Specs 1/2/3 §12 verlinken die je relevanten ADRs (Spec 1 → ADR-0001+0002+0003 · Spec 2 →
  ADR-0001+0004 · Spec 3 → ADR-0003+0004+0005) sowie die Architektur-Doku-Dateien.

---

## 9. Iteration-Log

### 2026-05-14 — Block 0 (Architektur-Doku + 5 ADRs) als bindender Vor-Code-Anker verankert

- **Trigger:** User-Anforderung "ADR + Architektur-Doku als Block in den Plan einarbeiten,
  ADR jetzt noch nicht formulieren, aber Regeln festlegen, was rein muss." Follow-up-
  User-Entscheidung: **fünf ADRs** statt einer (Triangle-FSM als eigene 5. ADR), damit
  Phase-2-Erweiterungen einzelne Entscheidungen via Superseding ändern können.
- **Eingearbeitet:**
  - Neuer **Block 0** ganz oben in §4-Block-Tabelle (vor 1a). Reihenfolge **0** → **1a**
    → 2 → **1b** → 3 → 4 → 5 → 6.
  - **§4.0.1** — Fünf-ADR-Definition. §4.0.1.0 listet alle fünf ADRs mit Decision-Kernsätzen
    und Subsystem-Headern. §4.0.1.0.1 mappt Plan-Body-Sub-Sektionen → ADR (welche Regel
    landet in welcher ADR). §4.0.1.0.2 visualisiert den Inter-ADR-Querverweis-Graph.
    §4.0.1.0.3 fixiert die 12-Sektionen-Standard-Struktur pro ADR + Phase-2-Superseding-
    Erwartungen. Sub-Sektionen §4.0.1.1..§4.0.1.7 enthalten die hart definierten Regeln
    für Reducer / Actions / Effects / Cross-Module + verbotene Patterns + Wer-hält-State
    + Wer-hält-UI-Wiring — vollständig im Plan-Body, NICHT ausgelagert. Das ist die SoT,
    gegen die die ADRs in Block 0 dann nur noch ausformuliert werden.
  - **§4.0.2** — Architektur-Doku-Definition mit 12 Sub-File-Topics in
    `docs/architecture/state-architecture/`. Pro Datei konkreter Mindest-Inhalt
    ausformuliert (nicht nur Stichworte).
  - **§4.0.3** — Block-0-Acceptance-Kriterien (6 Bullets, bidirektionale Referenzen,
    Sanity-Check durch externen Reader).
  - **§4.0.4** — Bindender-Vertrag-Charakter: Code-Blöcke 1a..6 dürfen ADR-Regeln nicht
    brechen; Reversal nur via Superseding-ADR.
  - **§4.0.5** — Modul-Isolation: Lens-Pattern (`read`/`write`), ASCII-Diagramm,
    3 Kommunikations-Kanäle (Read/Cascade/Effect-emit), innerer-vs-globaler State,
    `DictateModuleRegistry.all`-Verdrahtung. Verboten: direkter Modul-zu-Modul-Aufruf
    (neuer Verbots-Punkt (n) in §4.0.1.5).
  - **§4.0.6** — Walkthroughs für 3 Hinzufüge-Szenarien: neuer Button (§4.0.6.1, ~20
    LoC, am Beispiel INSERT_COMMA), neue Sub-Tastatur (§4.0.6.2, Variante A
    ContentArea + Variante B eigenes Window), neues Modul (§4.0.6.3, am Beispiel
    BatterySaverModule) + Allgemein-Pattern (§4.0.6.4).
  - **§8.1** Block-0-Artefakte-Liste in Referenzen ergänzt (ADR-Pfad, ADR-Index,
    Architektur-Doku-Pfad, bidirektionale-Referenz-Pflicht).
- **Rationale:** Die Architektur-Iterations-Pässe F-1..F-11 + die 14 Phase-B/C-Reviews
  haben harte Konventionen herausgearbeitet (Pure-Reducer, Single-Dispatch,
  Mode-1+2-Cross-Module, Self-Cascade-Erlaubnis nach KG-RSB-2, MAX_CASCADE_DEPTH=8,
  Lens-Pattern, RenderBackend-Multi-Backend, Click-Listener-Once-Wiring …) — ohne
  formalen ADR-Anker würde ein zukünftiger Implementer aus dem Bauch entscheiden,
  was z.B. "Pure-Reducer" bedeutet, und potenziell verbotene Patterns (b/h/n)
  einführen. Block 0 macht die Architektur zum bindenden Vertrag, nicht zur Empfehlung.
- **Implikation für Block-1b-Implementer:** Block 1b muss ADR-Inline-Anker setzen
  (mindestens an `DictateModule`-Interface, `DictateOrchestrator.dispatchInternal`,
  `DictateUiStateStore`, `RenderBackend`, `wireStaticHandlers`). Code-Review-Pflicht.
- **Implikation für Plan-Lifecycle:** Beim Plan-Archive-Pass (Phase 5 implement-long-plan)
  bleiben die Block-0-Artefakte (ADR + Architektur-Doku) NICHT im Plan-Archiv-Ordner,
  sondern in ihren projektweiten Heimaten (`docs/decisions/` + `docs/architecture/`).
  Das Plan-Archive-Verzeichnis verlinkt sie nur referenzierend (per-Plan-README).

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

### 2026-05-13 — Phase-B Quality-Gate S-1: State-Klassen-Hierarchie Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-1 (Flach + 3-Owner → Hierarchisch + Sealed + DictateUiStateStore) gegen Phase-A-Inventur. **5 Findings (2 Critical, 3 Important):** (1) Cross-Spec sub-state path drift in Spec 2 §13.5 (`state.resendCooldown`, `state.contentArea` → `state.resend.resendCooldown`, `state.layout.contentArea`) gefixt; (2) Service-onCreate naming drift in Spec 1 §4.11.5.1/§4.11.5.3/§7.1/§7.3/§11.1.4/§11.2/§11.6.1 — pre-F-11-Snippets zeigten `PipelineStateManager`/`ViewModeFsm`, die nach 2026-05-10 nicht mehr existieren — auf `DictateOrchestrator` + `ModuleServicesFactory` + Modul-Pattern umgestellt; (3) Service-Field-Migration-Tabelle (§13.2.1 Zeile 16) in 16a–16f aufgespalten — `restoreAutoEnter` + `restoreReprocessStaging` als ersatzlos-gestrichen markiert (Begründung: StateFlow überlebt View-Recreate), `livePrompt`/`pendingLivePromptChain`/`vibrationEnabled` mit hierarchischen Zielen verankert; (4) `DictateOrchestrator.shutdown()` ruft jetzt Modul-`terminate(services)` (D7 Issue 2.1.12) — frühere `prefMirror.detach()`-only-Implementation hätte Module-Cleanup übersprungen; (5) Block-1-Acceptance gegen Block-1a/1b-Split aktualisiert + zwei neue Acceptance-Klauseln (Atomarität `setSmallMode`, Initial-State-Race-Fence-Test). Plan-Edits: 19 Sektionen in Spec 1, 2 Sektionen in Spec 2.

### 2026-05-13 — Phase-B Quality-Gate S-2: DB-Schema-Migration SessionStatus v3→v4 Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-2 (4 Stati → 6 Stati + `inserted_at` + CHECK-Recreate) gegen Phase-A-Inventur + S-1-Vorgänger-Report. **8 Findings (2 Critical, 4 Important, 2 Minor):** (1) HistoryAdapter-Doppel-Sicherung-Konfusion: der bestehende `try/catch (IllegalArgumentException)`-Wrapper Z. 131-135 mit RECORDED-Fallback (Downgrade-Verträglichkeit) war im Plan-Patch nicht dokumentiert — Plan implizierte versehentlich, dass `default: Log.wtf + GONE` der einzige Catch-All ist; (2) RECORDING-Recovery-Lücke: partial-written Audio-File in `cacheDir/audio.m4a` wird nicht vom RECORDING-Recovery-Branch geräumt (DB-Row hat typischerweise `audio_file_path = NULL`, weil Path erst beim Stop geschrieben wird) — Cleanup läuft via `AudioFileFactory.cleanupOrphans` (Block 4) bzw. `cacheDir`-OS-Cleanup; jetzt explizit dokumentiert; (3) `inserted_at`-Index-Begründung: kein zusätzlicher Index trotz aktiver `WHERE inserted_at`-Queries — Begründung (kleine Tabelle, kein Hot-Path, Telemetrie-gesteuerte Nachrüstung) ergänzt; (4) androidTest-Setup als eigener Block-3-Schritt 0 herausgezogen (Surprise-Finding #4 der Inventur): konkrete Version-Catalog-Erweiterung, build.gradle-Snippet, Verifikations-Smoke-Test vor MigrationTo4-Implementation — substanziell genug, um nicht als Sub-Aufgabe von "Migration anlegen" versteckt zu sein; (5) HistoryDetailActivity:287-299 Whitelist-Logik (`canReprocess = (RECORDED || FAILED || CANCELLED || COMPLETED)`) ist bereits defensiv gegen neue Status-Werte — Plan-Anweisung "explizit ausschließen mit `status != RECORDING && status != TRANSCRIBING`" wäre redundanter Code-Lärm und wurde gestrichen; (6) FAILED-DB-Row-Lifecycle dokumentiert: `cleanupOrphanedTerminalAudio` räumt nur Audio-Files, NICHT DB-Rows — Begründung (User-Wert, DB-Größe-irrelevant) explizit hinterlegt; (7) Verzahnung `JobExecutor.register` (Lock-Producer, bleibt) vs. `Effect.PersistStatus(TRANSCRIBING)` (Status-Producer, neuer Modul-Hook): DB-first-Regel betrifft nur den Modul-Effect-Pfad, nicht den JobExecutor — Sequence-Diagramm + Implementer-Anker ergänzt, damit nicht jemand `SessionDao.updateStatus` versehentlich in JobExecutor.start einbaut (SRP-Bruch); (8) Downgrade-Strategie (v4→v3) explizit als "kein Pfad implementieren, App crasht beim ersten DB-Zugriff, User-Daten bleiben intakt, Re-Install der v4-App ist Recovery" in §11.7.0-Risiko-Tabelle dokumentiert. Block-3-Sub-Schritte von 9 auf 14 erweitert (eigener Schritt 0 androidTest + Schritte 10–14 für Konsumenten/Lint/Strings). Block-3-Acceptance um 4 neue S-2-Klauseln ergänzt (androidTest-Smoke, Doppel-Sicherung, Cleanup-Reihenfolge, SessionStatus-KDoc-Update). Plan-Edits: 9 Sektionen in Spec 1 (Spec 2/3 unverändert, weil S-2 reiner Spec-1-Scope ist).

### 2026-05-13 — Phase-B Quality-Gate S-3: Action-Hierarchie + Sealed-Leaves-Indexing + Single-Dispatch Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-3 (Flach + LocalBinder-Forwarder → Hierarchisch sealed + Single-Dispatch + Sealed-Leaves-Indexing) gegen Phase-A-Inventur + S-1/S-2-Vorgänger-Reports. **7 Findings (2 Critical, 4 Important, 1 Minor):** (1) **CRITICAL — `Action.EffectFailure` ohne Modul-Routing**: Top-Level-Action wurde vom Orchestrator `Unrouted` abgewiesen (kein Modul beanspruchte `actionClass = Action.EffectFailure::class`); Failure-Channel war effektiv tot. Korrektur: `EffectFailure` trägt jetzt `originModuleId`, der Orchestrator routet sie über `moduleById[originModuleId]` zurück ans emittierende Modul; `DictateModule`-Interface bekommt einen neuen `reduceFailure(state, failure, ctx)`-Hook mit Default `null` (Module ohne Failure-Pfad → semantisch korrekter `Rejected("reducer-null")`); Spec 1 §4.3 dispatchInternal-Pfad um EffectFailure-Special-Case erweitert; (2) **CRITICAL — `KeyboardInputAction` ohne Modul-Routing**: Spec 2 §3.3-Kommentar "kein eigenes Modul — direkt im IME-Service ausgeführt" war architektonisch inkonsistent — Resolver dispatchen an `orchestrator.dispatch`, aber kein Modul claimt `actionClass = KeyboardInputAction::class` → `Unrouted` → Backspace/Enter/Space-Buttons tot. Korrektur: neues `KeyboardInputModule` (§15.6 neu) mit `Unit`-State + Effect-Pipeline (InputConnection-Operations), in `DictateModuleRegistry.all` registriert; `ModuleId.KeyboardInput` ergänzt; `ModuleServices.clipboard` als optional Field für `CopyToClipboard`-Effect; (3) **IMPORTANT — `ToggleAudioFocus` vs. `ToggleAudioFocusPref` Naming-Drift**: Spec 1 §9.1 Migration-Tabelle + §13.4 DRY-Tabelle nutzten `ToggleAudioFocus` (ohne `Pref`-Suffix), Spec 2 §3.3 SoT nutzt `ToggleAudioFocusPref`. Auf SoT-Name umgestellt; (4) **IMPORTANT — Spec 2 §11.6 Click-Listener-Empfehlung-Snippet verletzte R.3-nullable-Resolver-Idiom**: `onAction?.invoke(slot.actionResolver(s))` hätte NPE/Compile-Fehler bei `null`-Resolver erzeugt — auf `slot.actionResolver(s)?.let { onAction?.invoke(it) }` umgestellt; (5) **IMPORTANT — `ResendAction`-Naming-Kollision** zwischen `core.ResendAction` (heutiger Status-Dispatcher, behalten) und `state.Action.ResendAction` (neuer Orchestrator-Action) dokumentiert mit Hinweis-Block in §6.1.3, damit Implementer die Typen nicht versehentlich vermischen (Compiler weist Cross-Use als Type-Mismatch ab, aber Code-Review-Klarheit ist load-bearing); (6) **IMPORTANT — Java-Brücke `DictateUiStateObserver` Spec war zu dünn**: §4.4 Z. 711 nannte sie nur als "vorgesehen für Block 2" ohne konkretes Code-Snippet, Consumer-Liste oder Acceptance — vollständige Implementation (analog zu `ActiveJobRegistryObserver.kt`) jetzt im Plan-Body, Consumer-Tabelle (DictateInputMethodService, HistoryAdapter, HistoryDetailActivity), Block-2-Acceptance-Klausel ergänzt; (7) **MINOR — Spec 2 §11.6 Amplitude/Timer-Hooks-Vorschlag**: erwähnte noch "simple Callback-Methode am LocalBinder" als Option, was F-8 (LocalBinder schrumpft auf `state` + `dispatch`) widerspricht — auf "zusätzlicher `StateFlow<AmplitudeTick>` am LocalBinder" umgestellt. Block-2-Acceptance um 3 neue S-3-Klauseln ergänzt (Java-Brücke, KeyboardInputModule, EffectFailure-Origin-Routing). Plan-Edits: ~14 Operations in 2 Dateien (Spec 1: 11, Spec 2: 3). Spec 3 unverändert — S-3-Action-Hierarchie ist Spec-2-SoT, Konsumenten in Spec 3 §4.2/§4.8 sind unverändert konsistent.

### 2026-05-13 — Phase-B Quality-Gate S-4: Pipeline-Orchestrierung (DictateOrchestrator + DictateModule-Plugin-Pattern) Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-4 (Verteilte Controller → DictateOrchestrator + 13 Module + Cascade-Mechanik) gegen Phase-A-Inventur + S-1/S-2/S-3-Vorgänger-Reports. **9 Findings (3 Critical, 5 Important, 1 Minor):** (1) **CRITICAL — `KClass.sealedSubclasses`-Reflection ohne ProGuard-Keep-Regel**: Spec 1 §4.3 `collectLeaves` verwendet `c.sealedSubclasses.flatMap` (Reflection auf Action-Hierarchie); R8-Default strippt sealed-Hierarchie weg → `moduleByLeafClass` leer → ALLE Actions `Unrouted` im Release-Build. Bug-Klasse identisch zu S-3 F-1/F-2 aber kataklysmischer (alle 14 Action-Subtypen betroffen). Korrektur: konkreter ProGuard-Patch (`-keep,allowobfuscation class * extends Action { *; }`) in §4.3 als Hinweis-Block; Block-1b-Acceptance "Phase-B S-4 ProGuard-Robustheit" mit instrumented Release-Smoke-Test; Hauptplan §7.2 PENDING-R.4 auf RESOLVED gestellt. (2) **CRITICAL — Init-Sanity-Check fängt nur Doppel-Routing, nicht Fehlende Routing** (S-3 Follow-Up F-7): `actionClasses.toSet().size`-Check (§4.8) hätte S-3-F-1/F-2 nicht gefangen; ein neuer Top-Level-Action-Subtyp (z.B. zukünftige Interruption-Aktivierung) wäre wieder still-dropped. Korrektur: dritter Init-Check via `Action::class.sealedSubclasses` + Excludelist für Special-Cases (EffectFailure) — Init-Time-Failure bei fehlendem Modul-Owner; Block-1b-Acceptance "Phase-B S-4 Vollständigkeits-Check" mit gezieltem Negativ-Test. (3) **CRITICAL — `Effect.AllocateMediaRecorder`-Signatur-Drift in §15.2**: Definition Z. ~5456 hatte 2 Args (target, useBluetooth), Reducer-Use Z. ~5492 rief mit 3 Args (audioFile), EffectHandler Z. ~5565 rief mit 2 Args — drei-fache Inkonsistenz, Compile-Fehler beim ersten Build. Korrektur: Definition + EffectHandler auf 3 Args; §4.7 RecordingHardwareSubsystem-KDoc explizit auf 3-Arg-Signatur; audioFile-Vertrag-Block in §15.2 erweitert um "Konsistenz der drei AllocateMediaRecorder-Sites". (4) **IMPORTANT — AudioModule.onCrossModuleStateChange Dead-Code-Block** (§15.3): leerer `if (Idle → Preparing) { ... }`-Block mit nur Kommentaren + falscher Kommentar-Hinweis "Direct-Hardware-Calls hier alternativ" hätte Implementer in Mode-2-Verstoß verleitet. Korrektur: Block ersatzlos gelöscht (AudioFocus-Request läuft als Effect im RecordingModule); Top-of-Block-KDoc um Pure-Function-Vertrag erweitert. (5) **IMPORTANT — Cascade-Reihenfolge der 13 Module nicht spezifiziert** (§4.3 Step 5-6): `modules.flatMap` iteriert in `DictateModuleRegistry.all`-Reihenfolge; rekursive `dispatchInternal`-Calls erfassen frische Snapshots → zweite Cascade sieht erste Mutation; ein versehentliches Reorder (z.B. alphabetisch sortieren beim Code-Cleanup) ändert observable Semantik. Korrektur: Cascade-Order-Vertrag-Block in §4.3 (Reihenfolge deterministisch + Disjunkt-Konvention + Reorder-ist-Plan-relevant); `DictateModuleRegistry.all`-Liste mit KDoc "Reihenfolge: deterministisch + Code-Review-relevant"; Block-1b-Acceptance "Phase-B S-4 Cascade-Order-Determinism" mit 2-Mock-Module-Test. (6) **IMPORTANT — `prefBindings()`-Interface-API ohne Konsumenten** (§4.2 vs. §4.5): Interface deklariert `prefBindings()`-Hook ("PipelinePrefMirror verwendet diese Liste"), aber §4.5 hat 19 hardcodierte Pref-Mappings — Phase-1-Dead-Code, Phase-2-Backlog-Markierung im Plan-Body unklar. Korrektur: §4.2 KDoc um Phase-1/Phase-2-Vermerk; §4.5 oben KDoc mit "Phase 1 hardcoded, Phase 2 prefBindings()"; §13.4.2 Code-Review-Regel "prefBindings()-Override nur Default emptyList() in Phase 1". (7) **IMPORTANT — `PipelineOrchestrator` vs. `DictateOrchestrator` Naming-Konflikt** (Surprise-Finding #2): zwei Klassen mit "Orchestrator" im Namen (alter Audio-Pipeline-Runner 1383Z. bleibt; neuer State-Action-Router kommt hinzu); Plan dokumentierte das nur in Tabellen-Zeile §8. Korrektur: neuer §1.x Naming-Konvention-Block mit Disambiguier-Tabelle + Lese-Konvention + Phase-2-Backlog-Hinweis; §13.5 G7-Block cross-linkt; Hauptplan §7.1 Out-of-Scope-Eintrag "Umbenennung des alten PipelineOrchestrator" mit Phase-2-Trigger. (8) **IMPORTANT — `shutdown()`-vs-`serviceScope.cancel()`-Reihenfolge nicht acceptance-getestet**: §4.3 KDoc dokumentiert die Sequenz, §7.3 onDestroy implementiert sie korrekt — aber kein Test gegen Reorder. Bei vertauschter Reihenfolge würden async-Cleanup-Schritte (Notification-cancel, DB-Flush) silent-no-op laufen. Korrektur: `shutdown()`-KDoc um Aufrufer-Vertrag-Absatz; Block-2-Acceptance "Phase-B S-4 shutdown-Order" mit FakeModule-`terminate`-Scope-Assert. (Minor: §11.6.2 Recovery-Snippet verwendete `_state.update` statt `store.update` — Insider-Syntax-Drift, gefixt.) Block-1b-Acceptance um 4 neue S-4-Klauseln ergänzt. Plan-Edits: 18 Operations in 2 Dateien (Spec 1: 15, Hauptplan: 3). Spec 2 + Spec 3 unverändert — S-4 ist Spec-1-Scope (DictateOrchestrator + DictateModule + DictateModuleRegistry leben dort kanonisch).

### 2026-05-13 — Phase-B Quality-Gate S-5: Service-Schicht (IME-only → DictatePipelineService Foreground + LocalBinder + Lifecycle-Recovery) Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-5 (IME-only → DictatePipelineService FGS + LocalBinder + onCreate/onDestroy + Notification-Coordinator + Action-Router) gegen Phase-A-Inventur + S-1/S-2/S-3/S-4/S-7-Vorgänger-Reports. **13 Findings (3 Critical, 7 Important, 3 Minor):** (1) **CRITICAL — §11.1.2 onStartCommand-Snippet pre-F-11-Drift**: `stateManager.pauseRecording()` / `stateManager.resumeRecording()` etc. — `PipelineStateManager` existiert nach 2026-05-10 nicht mehr; §7.3 (post-S-1-F-2) ruft `actionRouter.dispatch(intent)` via `PipelineActionRouter` → `DictateOrchestrator.dispatch(Action.X)`. Doppel-Snippet mit zwei verschiedenen Steuerungspfaden hätte Implementer dazu verleitet, einen direkten Controller-Aufruf-Pfad neben dem Orchestrator-Dispatch zu bauen (F-8 Single-Dispatch-Bruch). Korrektur: §11.1.2 onStartCommand-Block komplett neu geschrieben — pure Forward an `actionRouter` + `startForegroundCompat(notifCoordinator.buildInitial())` + `startReactiveUpdates(...)`; konsistent mit §7.3-SoT. (2) **CRITICAL — NOTIF_ID-Doppel-Definition (1001 in §11.1.2 vs 0xD1C7A7E in §7.4)**: zwei verschiedene `NOTIF_ID`-Konstanten in zwei Sektionen — `PipelineNotificationCoordinator.NOTIF_ID = 0xD1C7A7E` (§7.4 companion, post-F-3) vs `DictatePipelineService.NOTIF_ID = 1001` (§11.1.2 companion). Bei Verwendung der falschen Konstante hätte der Service via `startForeground(1001, …)` eine FGS-Notification angelegt, während der Coordinator via `nm.notify(0xD1C7A7E, …)` separate Update-Notifications gefeuert hätte → zwei sichtbare Notifications gleichzeitig (eine sticky-FGS, eine reguläre), `cancel(0xD1C7A7E)` würde die FGS-Notification nicht entfernen. Korrektur: NOTIF_ID-Konstante lebt nur im `PipelineNotificationCoordinator` (§7.4 SoT); §11.1.2-Doppel-Definition gestrichen; expliziter SoT-Hinweis-Block; Architektur-Test `Phase-B S-5 NOTIF_ID-Konsistenz` als Block-2-Acceptance. (3) **CRITICAL — `ensureNotificationChannel()` nicht in §7.3 onCreate**: §11.1.4 sagt "synchron als allererste Aktion vor jeglicher Coroutine-Initialisierung", aber §7.3 onCreate-Snippet (post-S-1-F-2) hatte den Call NICHT. Auf API ≥ 26 wirft `startForeground` mit nicht-existierendem Channel `IllegalArgumentException: Bad notification posted from package`. Bei Fresh-Install (kein Channel) → erstes `startForeground` crasht → Service-Death → ANR-Klasse im Play-Store. Korrektur: `ensureNotificationChannel()` als Schritt 1.5 in §4.11.5.1 Sequence-Tabelle + im §7.3 onCreate-Snippet als erste Aktion nach `super.onCreate()`; Block-2-Acceptance `Phase-B S-5 NotificationChannel-vor-startForeground` mit Fresh-Install-Fixture-Test. (4) **IMPORTANT — §7.2 vs §11.3.1 Bind-Site-Drift**: §7.2 sagte "IME-Service onCreate", §11.3.1 sagt "in `onCreateInputView`" (mit Latenz-Begründung). IME-Service-onCreate ist ein ANDERER Hook als onCreateInputView — IME-onCreate kann VOR erstem View-Inflate laufen (manche OEM-IME-Settings rufen es), und bindService dort hätte den Service unnötig früh hochgezogen mit potentiellem Race gegen den View-Lifecycle. Korrektur: §7.2 auf `onCreateInputView` umgestellt, mit Cross-Link auf §11.3.1; §11.2.2 Block 2 Sub-Schritt 3 explizit auf onCreateInputView verankert. (5) **IMPORTANT — `JobExecutor.initialize`-Typ-Konflikt**: §7.3 onCreate-Snippet rief `runner.initialize(orchestrator)` — mit `orchestrator: DictateOrchestrator` (dem NEUEN State-Action-Router). Aber `JobExecutor.initialize(orchestrator: PipelineOrchestrator)` erwartet den ALTEN PipelineOrchestrator (Audio-Pipeline-Runner, verifiziert via Code-Read `JobExecutor.kt:56`). Type-Mismatch → Compile-Error beim ersten `assembleDebug` von Block 2. Bug-Klasse: identisch zu S-4 F-7 Naming-Konflikt, aber im Snippet-Code nicht nur in der Doku. Korrektur: §7.3 onCreate-Snippet konstruiert `pipelineOrchestrator: PipelineOrchestrator` explizit; `JobExecutor.initialize(pipelineOrchestrator)`-Aufruf mit FIX-Kommentar; §4.11.5.1 Sequence-Tabelle Schritt 10 explizit mit dem Naming-Konvention-Hinweis. (6) **IMPORTANT — Service-onDestroy ohne `runBlocking`-Timeout-Wrapper**: §4.3 `shutdown()`-KDoc behauptete "Module-`terminate`-Calls dürfen blockieren (max. 1–2 s), weil sie unter `runBlocking`-Timeout des Service.onDestroy laufen sollen". Aber §7.3 onDestroy-Snippet rief `orchestrator.shutdown()` SYNCHRON ohne `runBlocking { withTimeout(2000L) { … } }`. Wenn ein Modul-`terminate` einen Coroutine-Suspend macht (z.B. zukünftiger NotificationCoordinator-Cleanup mit DB-Flush) oder pathologisch lange braucht, hängt onDestroy bis OS-seitig SIGKILL bei ~20 s — User sieht "Dictate hat aufgehört zu reagieren"-Dialog. Korrektur: §7.3 onDestroy-Snippet mit explizitem `runBlocking { withTimeout(2000L) { orchestrator.shutdown() } } catch TimeoutCancellationException`-Wrapper + Notification-Cancel als Step 3 ergänzt; `Phase-B S-5 onDestroy-Timeout`-Acceptance mit pathologischem-Modul-Test. (7) **IMPORTANT — Pre-Bind-Action-Pfad ohne User-Feedback**: §11.3.2 ServiceConnection-Edge-Cases deckten nur den Bind-Lifecycle, nicht den Pfad "User klickt im IME einen Button BEVOR `onServiceConnected` gefeuert hat". §11.3.3 behauptet "kein UI-Race möglich" — gilt aber nur für Touch-Events innerhalb desselben Main-Looper-Tasks; bei sehr schnellen Multi-Tap-Sequenzen oder Crash-Recovery (Service neu gebindet, Click vor onServiceConnected) ist `pipelineBinder == null`. Korrektur: neuer §11.3.2a "Pre-Bind-Action-Pfad" mit defensiv-Check + Toast-Fallback (`R.string.dictate_service_not_ready`); neue String-Resource (DE+EN); Block-2-Acceptance `Phase-B S-5 Pre-Bind-Action-Toast`. Außerdem: Defensive-Check für `onStartCommand` falls vor `onCreate`-Complete dispatchet wird (sollte nicht passieren, aber Logging statt Crash). (8) **IMPORTANT — Tastatur-Wechsel-Survival User-Visibility unklar**: Block-2-Acceptance hatte "Recording starten, Tastatur zur Gboard wechseln, 30s warten, zurück → Recording läuft noch" — aber keine Klausel "User sieht Mic-Indikator im System-Tray + kann via Notification cancellen". Auf API ≥ 31 zeigt das OS automatisch einen Mic-Indikator bei aktivem `FOREGROUND_SERVICE_TYPE_MICROPHONE`-Service — das ist ein zentrales User-Vertrauen-Feature ("warum hat meine App noch das Mic?"), und der Plan dokumentierte es nirgendwo. Korrektur: explizite Block-2-Acceptance `Phase-B S-5 Mic-Indikator beim Tastatur-Wechsel`. (9) **IMPORTANT — FGS-Killed-by-System (low memory) Recovery-Visibility**: §7.3 returnt `START_NOT_STICKY` — Service wird bei OS-OOM-Kill NICHT automatisch neu gestartet. Recovery-Pfad RECORDING→FAILED (§6.3 + Block-3-Acceptance R.16a) läuft erst beim NÄCHSTEN User-Bind. Plan dokumentierte die User-Sichtbarkeits-Lücke nicht: was sieht der User, wenn er die Tastatur nach OOM-Kill öffnet? Korrektur: Block-2-Acceptance `Phase-B S-5 FGS-Killed-by-System (Low-Memory)` mit `adb shell am kill`-Simulation + Assert dass `state.pendingSessions` die FAILED-Session enthält. (10) **MINOR — `audioFileFactory` Field-Position in Block 2 vs Block 4**: §7.3 onCreate-Snippet zeigt `audioFileFactory = CacheDirAudioFileFactory(applicationContext)` als Composition-Root-Schritt — aber `audioFileFactory` ist laut §11.2.2 erst Block 4. Block-2-Implementer hätte einen Compile-Error gehabt ("`AudioFileFactory` not found"). Korrektur: `audioFileFactory` als `lateinit var`-Field im Service deklariert (Phase-1-Stub), §11.2.2 Block 2 Sub-Schritt 1 explizit mit "Stub-Composition-Root + audioFileFactory-Stub"-Hinweis. (11) **MINOR — POST_NOTIFICATIONS-Prompt-Flow lückenhaft**: §11.5.1 sagte "in Settings/Onboarding promptem" — aber kein konkreter Sub-Schritt in §11.2.2 Block 2, keine String-Resource für Prompt-Text, kein Block-2-Acceptance-Bullet, kein User-Friction-Signaling im IME bei Decline. Korrektur: §11.5.1 erweitert um (a) ActivityResultLauncher-Code-Snippet für Onboarding, (b) Settings-Activity-onResume-Re-Prompt für Update-Users (Pref-Flag-idempotent), (c) Banner-Hinweis im IME-View bei aktivem Recording + Decline (Settings-Intent öffnen); §11.2.2 Block 2 Sub-Schritt 6 ergänzt; Block-2-Acceptance `Phase-B S-5 POST_NOTIFICATIONS-Prompt`. (12) **MINOR — Multi-Bind-Klärung fehlte**: Plan dokumentierte nicht, ob mehrere Clients (IME + zukünftige Settings/HistoryDetailActivity) parallel binden dürfen. Ohne Klärung hätte Block-2-Implementer entweder eine `BindRefCounter`-Premature-Optimization gebaut oder uncoordinierten Multi-Bind erlaubt. Korrektur: neuer §11.3.4 "Multi-Bind-Klärung" mit erlaubt-Klausel + Client-Tabelle + `stopSelf()`-Interaktion + Multi-Bind-Acceptance-Test. Zusätzlich: SYSTEM_ALERT_WINDOW im §11.1.1 Manifest-Diff vorab-deklariert (Cross-Link auf Spec 3 §5.7) — eliminiert einen zweiten Manifest-Commit zwischen Block 2 und Block 6. (13) **MINOR — FGS-5s-Boot-Latenz nicht acceptance-getestet**: §11.1.4 dokumentierte die 5-s-Frist als Mitigation, aber kein reproduzierbarer Test gegen Regression (z.B. wenn jemand einen sync-DB-Read in `onCreate` einbaut, der die Frist verbraucht). Korrektur: Block-2-Acceptance `Phase-B S-5 FGS-Boot < 5 s` mit Robolectric- oder instrumented-Test, p99 < 1 s auf API-34-Test-Device. Block-2-Acceptance um 8 neue S-5-Klauseln ergänzt (Mic-Indikator, FGS-Kill-Restart, NotificationChannel-Order, FGS-Boot-Latenz, NOTIF_ID-Konsistenz, onDestroy-Timeout, Multi-Bind, Pre-Bind-Toast, POST_NOTIFICATIONS-Prompt). Plan-Edits: ~16 Operations in 1 Datei (Spec 1: 16, Hauptplan: 1). Spec 2 + Spec 3 unverändert — S-5 ist Spec-1-Scope (Service-Lifecycle + Bind-Connection leben dort kanonisch). **Top-Insight:** die §11.1.2 vs §7.3-Doppel-Snippet-Drift (Finding 1+2+3) hätte den Block-2-Build sofort blockiert ODER, schlimmer, einen schleichenden Bug erzeugt, bei dem zwei separate Notifications angezeigt werden und der Service-Stop nicht sauber funktioniert — die ANR-Klasse beim ersten Fresh-Install-User wäre ein Play-Store-Crash-Burst gewesen.

### 2026-05-13 — Phase-B Quality-Gate S-7: Audio-File-Management (AudioFileFactory + Pre-Dispatch-Allocation + Cleanup-Routinen) Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-7 (Fixpfad `cacheDir/audio.m4a` → AudioFileFactory + `cacheDir/audio/`-Subdir + Cleanup-Routinen) gegen Phase-A-Inventur + S-1/S-2/S-3/S-4-Vorgänger-Reports. **11 Findings (3 Critical, 6 Important, 2 Minor):** (1) **CRITICAL — `ButtonSlot.actionResolver`-Signatur-Drift gegen `resolveRecordAction`**: `ButtonSlot.actionResolver: (DictateUiState) -> Action?` (Spec 2 §3.2, 1-arg) vs. `resolveRecordAction(state, services)` (Spec 2 §8.5, 2-arg post-PENDING-1). Compile-Fehler beim ersten `assembleDebug` — `::resolveRecordAction`-Methodenreferenz scheitert. Korrektur: `actionResolver` auf 2-arg `(DictateUiState, ModuleServices) -> Action?` erweitert; `wireStaticHandlers` (Spec 2 §6) + `wireStaticOverlayHandlers` (Spec 3 §4.2) rufen 2-arg-Variante; `ImeViewBackend`-Konstruktor + `OverlayBackend`-Konstruktor um `services: ModuleServices`-Field erweitert. (2) **CRITICAL — Spec 3 `OVERLAY_RECORD`-actionResolver fehlt `audioFile`-Argument**: Z. 69 von Spec 3 ruft `Action.RecordingAction.StartRecording(target = …)` ohne `audioFile` — `data class StartRecording(target, audioFile)` verlangt beide Felder. Korrektur: neuer Helper `resolveOverlayRecordAction(state, services)` in Spec 3 §3.1 mit Pre-Dispatch-Allocate analog zu Spec 2 §8.5; IOException-Toast-Fallback identisch. (3) **CRITICAL — `LegacyAudioFileMigration` ist NICHT idempotent gegen Re-Marking**: DAO-Query `markLegacyAudioSessionsFailed` setzt unkonditional `status = FAILED` + überschreibt `last_error_message` — würde bei zweitem Lauf (z.B. nach Pref-Wipe + Re-Upgrade) bereits FAILED-Sessions ihre originale Fehler-Information verlieren (z.B. "transcription_timeout"); im worst case sogar COMPLETED-Sessions auf FAILED downgraden + Daten verlieren. Korrektur: DAO-Query um `AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')`-Filter erweitert + Idempotenz-Erklärung als Hinweis-Block. (4) **IMPORTANT — String-Resource `dictate_storage_full` fehlt + nicht in Block-4-Sub-Schritten**: `resolveRecordAction` ruft `R.string.dictate_storage_full`, aber die Resource existiert heute nicht in `strings.xml` (verifiziert per `grep`); Plan-§4.11.10 F1 erwähnt "Neue String-Resource nötig" als Hinweis, aber kein konkreter EN/DE-Wert + kein Schritt in §11.2.2. Korrektur: neuer Block-4-Sub-Schritt 7 "String-Resource ergänzen" mit EN/DE-Vorlage. (5) **IMPORTANT — Recovery-Coupling-Tabelle (§4.11.6) deckt nur RECORDED, nicht v4-Stati RECORDING/TRANSCRIBING**: S-2-Migration bringt zwei neue Stati; §11.6.2 + Block-3-Acceptance R.16a/b/c dokumentiert die korrekten Recovery-Pfade, aber §4.11.6 referenziert nur den RECORDED-Pfad. Korrektur: Tabelle um 3 neue Zeilen (RECORDING-Crash, TRANSCRIBING-File-ok, TRANSCRIBING-File-weg) erweitert mit Querverweis auf R.16a/b/c. (6) **IMPORTANT — `getByStatus("RECORDED")` vs. `getSessionsByStatuses(List<String>)`-DAO-Drift in §11.6.2**: §6.3 DAO-Definition listet `getSessionsByStatuses(statuses: List<String>)` (Plural); §11.6.2 Recovery-Snippet rief `dao.getByStatus("RECORDED")` (Singular, nicht existent). S-2-F-2 hat andere Recovery-Lücken adressiert, aber diese Signatur-Drift nicht. Korrektur: Aufrufe auf `getSessionsByStatuses(listOf("RECORDED"))` umgestellt. (7) **IMPORTANT — `RecordingModule.reduceFailure` für AllocateMediaRecorder-Failure fehlt** (S-3-Follow-Up + S-4-Übersehen): ohne `reduceFailure`-Arm würde `EffectFailure(originModuleId = Recording, …)` an Default `null` enden → `Rejected("reducer-null")` → State hängt im `Preparing` für immer. Korrektur: `RecordingModule.reduceFailure`-Override in §15.2 mit Rollback `Preparing → Idle` + `ReleaseMediaRecorder` + `DeleteAudioFile`-Effects; auch StopMediaRecorder-Failure-Arm ergänzt. (8) **IMPORTANT — `cleanupOrphanedTerminalAudio` Concurrency-Vertrag unklar**: §6.3.1 trigger-slot, Dispatcher-Disziplin und Double-Delete-Race mit `RecordingRepository.deleteBySessionId` waren nicht explizit dokumentiert. Korrektur: Concurrency-Vertrag-Block mit Trigger-Slot-Präzision (`stopSelfWhenTerminal`-Callback), Dispatcher-Disziplin (Dispatchers.IO), Concurrent-Allocate-Verhalten und Double-Delete-Idempotenz. (9) **IMPORTANT — `LegacyAudioFileMigration` läuft synchron auf Main-Thread**: drei IO-Operationen (Pref-Read + File-Delete + DAO-UPDATE) in Service.onCreate vor FGS-5s-Frist. Worst-case ~200 ms bei großen DBs. Korrektur: Threading-Block mit Telemetrie-Trigger-Hinweis (Phase-2-Pfad: async, weil Idempotenz safe). (10) **MINOR — `PreferencesFragment.clearCacheRecursively` Race mit aktiver Recording**: `entry.delete()` auf offener MediaRecorder-FD verursacht `unlink()` — beim Stop ist die Audio-Datei verschwunden (Ghost-Session). Korrektur: Defensive Vorbedingung "wenn `state.recording !is Idle` → Toast 'Aufnahme läuft' + return"; neue String-Resource `dictate_cache_clear_blocked_recording`. (11) **MINOR — Block 4 (AudioFileFactory) hatte keine explizite Sub-Schritt-Liste in §11.2.2** — Implementer hätte aus §4.11 KG-Markern rekonstruieren müssen. Korrektur: explizite 14-Schritt-Sequenz für Block 4 in §11.2.2 ergänzt. Block-4-Acceptance neu eingeführt (12 Klauseln). Plan-Edits: ~16 Operations in 4 Dateien (Spec 1: 11, Spec 2: 3, Spec 3: 3, Hauptplan: 1). Hauptlücke: die Resolver-Signatur-Inkonsistenz (Finding 1+2) hätte den Block-4-Build sofort blockiert; ohne S-7-Audit wäre der Implementer in eine konfuse Compile-Error-Schleife geraten ("`(state) -> Action?` vs. `(state, services) -> Action?` — wo lebt `services`?"). (2) **CRITICAL — Init-Sanity-Check fängt nur Doppel-Routing, nicht Fehlende Routing** (S-3 Follow-Up F-7): `actionClasses.toSet().size`-Check (§4.8) hätte S-3-F-1/F-2 nicht gefangen; ein neuer Top-Level-Action-Subtyp (z.B. zukünftige Interruption-Aktivierung) wäre wieder still-dropped. Korrektur: dritter Init-Check via `Action::class.sealedSubclasses` + Excludelist für Special-Cases (EffectFailure) — Init-Time-Failure bei fehlendem Modul-Owner; Block-1b-Acceptance "Phase-B S-4 Vollständigkeits-Check" mit gezieltem Negativ-Test. (3) **CRITICAL — `Effect.AllocateMediaRecorder`-Signatur-Drift in §15.2**: Definition Z. ~5456 hatte 2 Args (target, useBluetooth), Reducer-Use Z. ~5492 rief mit 3 Args (audioFile), EffectHandler Z. ~5565 rief mit 2 Args — drei-fache Inkonsistenz, Compile-Fehler beim ersten Build. Korrektur: Definition + EffectHandler auf 3 Args; §4.7 RecordingHardwareSubsystem-KDoc explizit auf 3-Arg-Signatur; audioFile-Vertrag-Block in §15.2 erweitert um "Konsistenz der drei AllocateMediaRecorder-Sites". (4) **IMPORTANT — AudioModule.onCrossModuleStateChange Dead-Code-Block** (§15.3): leerer `if (Idle → Preparing) { ... }`-Block mit nur Kommentaren + falscher Kommentar-Hinweis "Direct-Hardware-Calls hier alternativ" hätte Implementer in Mode-2-Verstoß verleitet. Korrektur: Block ersatzlos gelöscht (AudioFocus-Request läuft als Effect im RecordingModule); Top-of-Block-KDoc um Pure-Function-Vertrag erweitert. (5) **IMPORTANT — Cascade-Reihenfolge der 13 Module nicht spezifiziert** (§4.3 Step 5-6): `modules.flatMap` iteriert in `DictateModuleRegistry.all`-Reihenfolge; rekursive `dispatchInternal`-Calls erfassen frische Snapshots → zweite Cascade sieht erste Mutation; ein versehentliches Reorder (z.B. alphabetisch sortieren beim Code-Cleanup) ändert observable Semantik. Korrektur: Cascade-Order-Vertrag-Block in §4.3 (Reihenfolge deterministisch + Disjunkt-Konvention + Reorder-ist-Plan-relevant); `DictateModuleRegistry.all`-Liste mit KDoc "Reihenfolge: deterministisch + Code-Review-relevant"; Block-1b-Acceptance "Phase-B S-4 Cascade-Order-Determinism" mit 2-Mock-Module-Test. (6) **IMPORTANT — `prefBindings()`-Interface-API ohne Konsumenten** (§4.2 vs. §4.5): Interface deklariert `prefBindings()`-Hook ("PipelinePrefMirror verwendet diese Liste"), aber §4.5 hat 19 hardcodierte Pref-Mappings — Phase-1-Dead-Code, Phase-2-Backlog-Markierung im Plan-Body unklar. Korrektur: §4.2 KDoc um Phase-1/Phase-2-Vermerk; §4.5 oben KDoc mit "Phase 1 hardcoded, Phase 2 prefBindings()"; §13.4.2 Code-Review-Regel "prefBindings()-Override nur Default emptyList() in Phase 1". (7) **IMPORTANT — `PipelineOrchestrator` vs. `DictateOrchestrator` Naming-Konflikt** (Surprise-Finding #2): zwei Klassen mit "Orchestrator" im Namen (alter Audio-Pipeline-Runner 1383Z. bleibt; neuer State-Action-Router kommt hinzu); Plan dokumentierte das nur in Tabellen-Zeile §8. Korrektur: neuer §1.x Naming-Konvention-Block mit Disambiguier-Tabelle + Lese-Konvention + Phase-2-Backlog-Hinweis; §13.5 G7-Block cross-linkt; Hauptplan §7.1 Out-of-Scope-Eintrag "Umbenennung des alten PipelineOrchestrator" mit Phase-2-Trigger. (8) **IMPORTANT — `shutdown()`-vs-`serviceScope.cancel()`-Reihenfolge nicht acceptance-getestet**: §4.3 KDoc dokumentiert die Sequenz, §7.3 onDestroy implementiert sie korrekt — aber kein Test gegen Reorder. Bei vertauschter Reihenfolge würden async-Cleanup-Schritte (Notification-cancel, DB-Flush) silent-no-op laufen. Korrektur: `shutdown()`-KDoc um Aufrufer-Vertrag-Absatz; Block-2-Acceptance "Phase-B S-4 shutdown-Order" mit FakeModule-`terminate`-Scope-Assert. (Minor: §11.6.2 Recovery-Snippet verwendete `_state.update` statt `store.update` — Insider-Syntax-Drift, gefixt.) Block-1b-Acceptance um 4 neue S-4-Klauseln ergänzt. Plan-Edits: 18 Operations in 2 Dateien (Spec 1: 15, Hauptplan: 3). Spec 2 + Spec 3 unverändert — S-4 ist Spec-1-Scope (DictateOrchestrator + DictateModule + DictateModuleRegistry leben dort kanonisch).

### 2026-05-13 — Phase-B Quality-Gate S-6: Keyboard-Layout-Renderer (KSM + RecordingUiController + KeyboardLayoutModeController → KeyboardLayoutManager + LayoutCatalog + MotionLayout) Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-6 (UI-Layer für KEYBOARD-Modus — eliminiert die 3 Original-Bug-Klassen: asymmetrisches Re-Parenting, resend_btn-Race, recordButton-Hybrid; plus MotionLayout-Migration mit PulseLayout-Animations-Spike-Risiko) gegen Phase-A-Inventur + S-1/S-3/S-7-Vorgänger-Reports. S-6 ist der vorletzte Implementations-Block (Block 5 im Hauptplan §4) und konsumiert die ButtonSlot-2-arg-actionResolver-Signatur aus S-7 F-1. **9 Findings (3 Critical, 4 Important, 2 Minor):** (1) **CRITICAL — S-7-F-1-Folgepfad nicht vollständig**: ButtonSlot-Typ + ImeViewBackend + zentrale `resolveRecordAction` waren auf 2-arg umgestellt, aber ALLE 5 LayoutCatalog-Definitionen (§8.1 TWO_ROW, §8.2 SINGLE_ROW, §8.3 SEND_MODE-Varianten, §8.4 REPROCESS_STAGING) hatten Slot-`actionResolver`-Lambdas in der alten 0/1-arg-Form (`{ Action.X }`, `{ state -> ... }`, `{ null }`) — alle wären beim ersten `assembleDebug` ein Compile-Error gewesen (Kotlin inferiert 0-arg-Lambda als `() -> Action`, 1-arg als `(DictateUiState) -> Action?`; weder typ-kompatibel zu `(DictateUiState, ModuleServices) -> Action?`). Zusätzlich waren `resolveRecordActionPipeline`, `resolveTrashAction`, `resolvePauseAction` in §8.5 noch 1-arg definiert — als Methodenreferenzen `::resolveTrashAction` Compile-Error (Kotlin-Methodenreferenz-Typ matched die Funktions-Signatur exakt). Bug-Klasse: ohne S-6-Audit wäre der Block-5-Implementer in eine konfuse Compile-Schleife geraten ("die Methodenreferenzen funktionieren, die Lambdas nicht — was ist los?"). Korrektur: alle Lambdas auf `{ _, _ -> Action.X }` / `{ state, _ -> ... }` / `{ _, _ -> null }` migriert (5 LayoutModes × ~8 Slots = ~40 Edits); §8.5 Resolver `resolveRecordActionPipeline`, `resolveTrashAction`, `resolvePauseAction` auf 2-arg `(state, services)` mit Migrations-Hinweis-Block umgestellt. (2) **CRITICAL — `SendStaging` / `CancelReprocessStaging` als Singleton-Aufruf statt data-class**: §8.4 REPROCESS_STAGING-Slot rief `{ Action.PipelineAction.SendStaging }` und `{ Action.PipelineAction.CancelReprocessStaging }` als Object-Referenzen. Aber Spec 2 §3.3 Z. 205–206 definiert beide als `data class SendStaging(val sessionId: String)` bzw. `data class CancelReprocessStaging(val sessionId: String)` (post-Phase-1-1.0.5 + R.15). Compile-Error: "Classifier 'SendStaging' does not have a companion object, and thus must be initialized here". Implementer-Reflex hätte entweder `sessionId` aus dem aktiven Pipeline-State holen müssen ODER ein `Phase 2`-Stub gebaut. Korrektur: beide Actions lesen den `sessionId` aus dem `ReprocessStaging`-State über safe-cast `state.pipeline as? PipelineUiState.ReprocessStaging` — analog zu §8.5 `resolveTrashAction`-Pattern. (3) **CRITICAL — WIDGET_TOGGLE-Slot komplett fehlend in allen 5 KEYBOARD-LayoutModes**: `LogicalButtonId.WIDGET_TOGGLE` ist in §3.1 enum + §6 `buttonViews`-Map registriert (Spec 3 OPEN-2 / Phase-1-1.0.2-Followup mit Silent-Skip-Schutz `error(...)` Z. 603), aber KEINER der LayoutModes definiert einen Slot für ihn. **Konsequenz:** der Render-Loop iteriert `mode.rows.flatMap{it.slots}`; wenn WIDGET_TOGGLE nicht enthalten ist, wird der View nie aktualisiert — Default-XML-Visibility `gone` bleibt sticky. Spec 3 OPEN-2 (Toggle KEYBOARD ↔ WIDGET) wäre damit kaputt: der widget_toggle_btn würde nie sichtbar werden, kein State-Pfad könnte den WIDGET-Mode aktivieren. (Hinweis: §6 Silent-Skip-Schutz greift in die andere Richtung — Slot ohne View, nicht View ohne Slot.) Korrektur: WIDGET_TOGGLE-Slot in alle 5 LayoutModes (§8.1–§8.4) eingefügt: TWO_ROW + SINGLE_ROW mit `visibilityPredicate = { viewMode == ViewMode.KEYBOARD }`; SEND_MODE-Varianten + REPROCESS_STAGING mit `{ false }` (kein Mode-Wechsel während aktiver Pipeline). (4) **IMPORTANT — `predResendVisible`-Cooldown nicht im `enabledResolver` umgesetzt**: §13.5 Gap 2 dokumentiert die Resolution ("Cooldown im `enabledResolver`, nicht `visibilityPredicate`"), aber die RESEND-Slot-Definitionen in §8.1 + §8.2 hatten WEDER `enabledResolver` NOCH `alphaResolver` für `resendCooldown`. Bug-Klasse: Plan §1.1 #3b ("Resend verschwindet beim Toggle") wäre durch das Predicate-Pattern eliminiert, aber das Cooldown-Verhalten (500ms disabled+alpha 0.4 nach Klick) wäre stumm verschwunden — das Spec-Versprechen aus §13.5 wäre nicht erfüllt, Test §14.2 UI-Test 9 (Cooldown-Visibility-Frame-Check) würde rot fehlschlagen. Korrektur: RESEND-Slots in §8.1 + §8.2 um `enabledResolver = { !it.resend.resendCooldown }` + `alphaResolver = { if (it.resend.resendCooldown) 0.4f else 1f }` ergänzt. (5) **IMPORTANT — Block-5-Acceptance ohne File-Deletion-Verifikation**: §10 hat Acceptance-Bullets für R.11/R.13/R.14 + Bug-Eliminations-Tests, aber KEINE explizite Klausel "`KeyboardLayoutModeController.kt` ist gelöscht; grep findet keinen Caller mehr". §9.1 sagt "entfällt vollständig" + §11.8 Block 5d sagt "Cleanup", aber kein verifizierbares Akzeptanz-Kriterium. Implementer-Reflex: 5d kann durch eine "in Phase 2 löschen wir es"-Bequemlichkeit umgangen werden, was den Plan-Spec-Versprechen (273 Zeilen weg) verfehlt UND einen versteckten Caller im Service-Wiring (Block 5c) übersehen würde. Korrektur: 3 neue Acceptance-Klauseln in §10: (a) Datei-Gelöscht-Check per `find`, (b) Caller-Grep-Negativ-Check, (c) ButtonSlot 2-arg-Konsistenz-Build-Smoke. (6) **IMPORTANT — R.13 Strict-Mode-Logger-Konkretisierung fehlt**: Acceptance Z. 1664 "R.13 Strict-Mode-Logging während 5c: `VisibilityWrite from $caller`-Log" — aber **wie** der Logger implementiert wird (welcher API-Hook, welches Filter, welche Lebensdauer) ist nicht spezifiziert. Implementer hätte freie Hand: könnte als `Log.d`-Wrapper enden (kein strukturiertes Logging), oder als Stack-Trace-Reflection-Heavy-Tool (Performance-Bremse mid-render), oder als never-removed Production-Bloat. Korrektur: Acceptance um explizite Implementation-Spec ergänzt — `VisibilityWriteAuditLogger`-Klasse mit `BuildConfig.DEBUG`-Guard, in 5c aktiv, in 5d ersatzlos gelöscht; API + Akzeptanz-Kriterium (60-s-Soak-Test, 0 doppelte Writes). (7) **IMPORTANT — `onShowResend()` LocalBinder-Drift gegen F-8**: §9.6 Tabelle Z. 1640 sagt `pipelineService.markLastAudioExists(true)` als Ziel für die letzte resend_btn-Mutation in `DictateInputMethodService.java:1839`. Aber F-8 (Single-Dispatch + LocalBinder-Schrumpfung auf `state` + `dispatch`) verbietet typed Forwarder am LocalBinder — `markLastAudioExists` würde wieder einen Forwarder einbauen, S-3 hat das schon allgemein für `KeyboardInputModule` etc. korrigiert. Korrektur: §9.6 auf `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` umgestellt (Action existiert bereits in §3.3 Z. 250). (8) **MINOR — F-4 AudioFocus-Icon-SSoT nicht in den Slots referenziert**: §8.5 definiert `resolveAudioFocusIcon(enabled: Boolean): Int` als geteilten Helper (Main-Button-Area-Slot + EditBar), aber die AUDIO_FOCUS-Slot-Definitionen in §8.1–§8.4 nutzen ihn nicht — sie haben gar keinen `iconResolver`. Drift-Risiko: ohne expliziten iconResolver fällt das Icon auf den XML-Default (`ic_baseline_volume_off_24`) zurück und wechselt nie zu `ic_baseline_volume_up_24`, wenn `audioFocusEnabledPref == true`. EditBar nutzt den Helper über `refreshAudioFocusIcon`, Main-Button-Area wäre stumm broken. Korrektur: AUDIO_FOCUS-Slot in §8.2 SINGLE_ROW + §8.3 SINGLE_ROW_SEND_MODE (die einzigen, wo Predicate `true` ist) bekommt `iconResolver = { resolveAudioFocusIcon(it.audio.audioFocusEnabledPref) }`. (9) **MINOR — `predTrashVisible == predPauseVisible` Code-Duplikation**: §8.5 zwei Top-Level-Funktionen mit identischem Body (`recording.isActiveOrPaused || pipeline is ReprocessStaging`). Mögliche Konsolidierung zu einer Funktion, oder bewusste Trennung mit Begründungs-Kommentar (zukünftige Divergenz erwartet — z.B. trash sichtbar während Reprocess, pause disabled+alpha 0.4 vs. trash enabled). NICHT als Plan-Edit eingearbeitet — bewusst stehen gelassen, weil §8.4 REPROCESS_STAGING die beiden Slots bereits divergierend behandelt (pause disabled+alpha 0.4 mit hardcoded `enabledResolver = { false }`, trash enabled mit `actionResolver` → CancelReprocessStaging); eine Konsolidierung würde die zukünftige Divergenz-Inspiration nehmen. Acceptance nicht erweitert. Plan-Edits: ~14 Operations in 2 Dateien (Spec 2: 13 — 5 LayoutMode-Definitionen umgeschrieben, §8.5 Resolver-Signaturen, §9.6 LocalBinder-Drift-Fix, §10 Acceptance +5 Klauseln, §13.5.b WIDGET_TOGGLE-Klarstellung; Hauptplan: 1 — diesen Iter-Log-Eintrag). Spec 1 + Spec 3 unverändert — S-6 ist Spec-2-Scope (LayoutCatalog + ImeViewBackend leben dort kanonisch); S-7-Vorgänger hat die Spec-1-Resolver-Signatur + Spec-3-OverlayBackend-Folgepfad bereits abgedeckt. **Top-Insight:** S-7 hat die Schnittstelle (ButtonSlot-Typ + zentrale Resolver) auf 2-arg migriert, ABER die ~40 Consumer-Sites in §8.1–§8.4 nicht durchgereicht — ein klassischer "Refactor-only-the-API-not-the-callers"-Fall. Ohne S-6-Audit hätte der Block-5-Implementer die 40 Lambda-Stellen einzeln debuggen müssen, mit dem zusätzlichen Risiko der `data class`-Singleton-Aufruf-Bugs (Finding 2) und der vollständig fehlenden WIDGET_TOGGLE-Verankerung (Finding 3) — Spec 3 OPEN-2-Feature wäre stumm broken in den Build gelandet.

### 2026-05-13 — Phase-B Quality-Gate S-9: ResetSuppressBit-Lifecycle (PENDING-3 Pseudo-Cascade → Cross-Module-Action mit Single-Reducer-Ownership) Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-9 (Doppel-Eigentum-Pseudo-Cascade → dedizierte `OverlayAction.ResetSuppressBit` mit Single-Reducer-Ownership in OverlayModule + Cross-Module-Cascade aus RecordingModule.onCrossModuleStateChange) gegen Phase-A-Inventur + S-1/S-3/S-4/S-7-Vorgänger-Reports. S-9 ist Cross-Cutting (klein im Scope, kritisch für Cascade-Korrektheit) und die exemplarische Test-Case für KG-RSB-2 (Self-Filter-Bug-Klasse) sowie für die Pure-Cross-Module-Cascade-Mechanik (Mode 2 in §15.5). **5 Findings (1 Critical, 3 Important, 1 Minor):** (1) **CRITICAL — Spec 3 §7.3 T1+T2 Cross-Axis-Mutation widerspricht §6.1 + §15.5 Mode-2-Konvention (Phase-A Surprise-Finding #3)**: Spec 3 §7.3 T1 (Z. 1369–1383) zeigte einen ViewModeModule.reduce-Snippet, der GLEICHZEITIG `viewMode` und `overlay.onboardingPending` mutiert; T2 (Z. 1405–1419) mutierte GLEICHZEITIG `viewMode + layout.smallMode + overlay.userPrefersWidget` in einem Reducer-Schritt. Das ist Cross-Axis-Mutation (Mode 3 / Atomic Cross-Axis-Update), die laut Spec 1 §15.5 explizit Phase-2-Backlog ist. Spec 3 §6.1 (Z. 1226–1257) zeigte schon die korrekte Mode-2-Form (ViewModeModule mutiert NUR viewMode; LayoutModule + OverlayModule reagieren via onCrossModuleStateChange auf den Übergang). §7.3 war eine inkonsistente Doppel-Truth-Quelle — zwei Spec-3-Sektionen zeigten zwei verschiedene Reducer-Formen für DIE GLEICHE Action (`ToggleViewModeWidget`). Bug-Klasse: ein Implementer hätte abhängig davon, welche Sektion er zuerst liest, entweder die SRP-konforme oder die SRP-verletzende Form implementiert; im SRP-Verletzenden Fall wäre ViewModeModule reducer-pflichtig auf 3 verschiedenen Sub-State-Achsen — und die korrespondierenden Cascade-Hooks in LayoutModule + OverlayModule (Spec 3 §6.1 + §4.8 + Spec 1 §15.2-Pattern) wären als zusätzliche No-Ops eingebaut, was Dead-Code wäre ODER Double-Mutation (zwei Pfade setzen denselben State). Korrektur: §7.3 T1+T2 auf §6.1-konsistente Mode-2-Cascade-Form umgestellt — ViewModeModule mutiert NUR `viewMode`; LayoutModule.onCrossModuleStateChange cascadiert `LayoutAction.SetSmallMode(true)`; OverlayModule.onCrossModuleStateChange cascadiert `OverlayAction.SetUserPrefersWidget(true/false)`. T1 zusätzlich: Permission-Gate gibt `null` zurück (Reducer-Vertrag "Action nicht relevant"), Onboarding-Trigger lebt im Resolver/Effect-Pfad. Cross-Reference §6.1 ↔ §7.3 explizit als "Spec-3-internal SSoT"-Hinweis-Block. (2) **IMPORTANT — Self-Filter-Re-Einführung-Schutz nur via Regression-Test, keine prominente Inline-Banner**: Der KG-RSB-2-FIX-Kommentar in Spec 1 §4.3 Step 5 (Z. 718–723) war als 6-Zeilen-`// FIX:`-Kommentar formatiert — visuell ähnlich zu den ~80 anderen FIX-Kommentaren in der Datei. Bei einem späteren Code-Refactoring ("looks like an infinite-loop guard; let me re-add the filter") wäre der Schutz **nur** der Regression-Test `recordingModule_idleToPreparing_emitsResetSuppressBit_viaSelfCascade()` (§10 R.RSB-FIX-A) — keine Compile-Time-Sicherung, keine Lint-Regel, keine prominente Code-Banner. Bug-Klasse: ein Reviewer sieht den FIX-Kommentar nicht (Augenermüdung in 6700-Zeilen-Spec), pusht den Re-Add-Filter, Test fängt ihn — aber NUR wenn der Test mitläuft (lokale Workflow-Disziplin). Production-Bug-Risiko bei test-skip oder Selektiv-Build. Korrektur: §4.3 Step 5 FIX-Kommentar in einen prominenten ASCII-Box-Banner mit `⚠ DO NOT RE-ADD SELF-FILTER`-Heading + expliziter Verlinkung des R.RSB-FIX-A-Tests + 1-Satz-Beschreibung des Production-Bugs umgewandelt — schwer zu übersehen, schwer versehentlich zu entfernen. (3) **IMPORTANT — Cross-Module-Modi-Disambiguation fehlte explizite Anti-Beispiel-Tabelle**: §15.5 listete Mode 1 + Mode 2 als Tabelle und Mode 3 als Phase-2-Backlog, aber kein Anti-Beispiel-Block, der die Modi gegen *konkrete* Patterns aus dem Plan abgrenzt. Bei einem zukünftigen Spec-Eingriff (z.B. eine neue Cascade hinzufügen) hätte ein Maintainer ohne ausreichende Disambiguation Mode 3 versehentlich eingebaut (genau das, was in Spec 3 §7.3 passiert war — Finding 1). Self-Read-Konvention (KG-RSB-3) war zwar in §15.1.x dokumentiert, aber NICHT mit den Mode-Definitionen cross-verlinkt. Korrektur: §15.5 um eine 4-Zeilen-Anti-Beispiel-Tabelle ergänzt, die Mode 1 / Mode 2 / Mode 3-Backlog / Mode 2 (Self-Read) anhand konkreter Plan-Beispiele (`AllocateMediaRecorder`, `ViewMode → SmallMode-Cascade`, T2 vorher-Form, `ResetSuppressBit`-Cascade) abgrenzt; Code-Review-Pflicht ("Reducer mutiert zwei verschiedene Sub-State-Achsen → Mode-3-Verstoß") + Cross-Link auf §15.1.x Coupling-Matrix-Konvention. (4) **IMPORTANT — Idempotenz-Subscriber-Verhalten war nicht explizit dokumentiert**: Spec 3 §4.8 OverlayModule.reduce-Snippet (Z. 890–898) erklärte die Idempotenz semantisch (TransitionResult statt null → Applied statt Rejected), aber dokumentierte nicht, was mit StateFlow-Subscribern passiert, wenn `state.copy(suppressAutoOverlayUntilNextSession = false)` mit bereits `false`-Wert ein neues `OverlayState`-Objekt mit identischer struktureller Gleichheit erzeugt. Kotlin `MutableStateFlow.update` vergleicht via `equals` (data class structural equality) und unterdrückt die Emission bei gleichem Wert — aber das war im Plan implizit. Bug-Klasse: ein Subscriber-Implementer in Block 6 könnte fragen "warum kommt mein OverlayBackend.render() bei Re-Reset nicht doppelt?" und sich auf ein nicht-dokumentiertes Verhalten verlassen, das durch ein späteres MutableStateFlow-Wrapping (z.B. Manual-Emit-Override) gebrochen würde. Korrektur: Reducer-Snippet-Kommentar um Subscriber-Verhalten-Absatz erweitert ("StateFlow-Subscriber-Verhalten: MutableStateFlow.update unterdrückt Emission bei strukturell gleicher data class — kein Re-Render-Overhead, keine doppelte Telemetrie"). (5) **MINOR — `OverlayAction.ResetSuppressBit` als `object`-Singleton + ProGuard-Cross-Link nicht explizit**: Spec 2 §3.3 (Z. 293–301) dokumentiert ausführlich, *warum* `ResetSuppressBit` ein `object` ist (Naming-Konsistenz, Sealed-Leaves-Routing, Test-Assertions ohne equals/hashCode). Aber **nicht** explizit: wie verhält sich die `object`-Subtype unter der S-4-F-1-ProGuard-Keep-Regel `-keep,allowobfuscation class * extends Action`? Antwort (kein neuer Bug): die Pattern matched jeden Subtyp inkl. `object`-Singletons; ProGuard `allowobfuscation` erlaubt Namens-Verkürzung, aber `KClass`-Reference bleibt intakt, weil `sealedSubclasses`-Reflection auf der Class-Hierarchie operiert, nicht auf Namen. Korrektur: kein Spec-Eingriff nötig — die existierende ProGuard-Regel in §4.3 (Z. 813–828) deckt `object`-Subtypen ab; MARK-as-NOTED ohne Plan-Edit (für künftige S-Reviews dokumentiert in S-9-Report-Datei). Plan-Edits: 4 Operations in 3 Dateien (Spec 1: 2 — §4.3 Banner + §15.5 Anti-Beispiel-Tabelle; Spec 3: 2 — §4.8 Reducer-Kommentar + §7.3 T1/T2-Cascade-Refactor; Hauptplan: 1 — diesen Iter-Log-Eintrag). Acceptance unverändert (R.RSB-FIX-A + Suppress-Bit-Lifecycle-Bullets in Spec 1 §10 + Spec 3 §10 reichen für S-9; §7.3-Refactor ist Notation/Doku-Konsistenz, nicht neue Funktionalität). **Top-Insight:** der Surprise-Finding-#3-Fall ist ein lehrreiches Beispiel für **interne Notations-Drift in einer einzelnen Spec-Datei** — §6.1 und §7.3 hatten denselben State-Übergang (`WIDGET → KEYBOARD via ToggleViewModeWidget`) in zwei verschiedenen Reducer-Formen kodifiziert; ein Implementer hätte ohne S-9-Audit eine 50%-Chance gehabt, die SRP-verletzende Form zu wählen. KG-RSB-2 selbst (Self-Filter-Bug) bleibt durch den existierenden Regression-Test und den neuen prominenten Banner robust — beide Mechanismen zusammen sind belt-and-suspenders, was bei einem Bug dieser Schwere (HOVER-Auto-Reopen permanent kaputt nach erstem Close-Klick) angemessen ist. Spec 2 unverändert — S-9-Action-Definition lebt in §3.3, die ist bereits SoT und konsistent.

### 2026-05-13 — Phase-B Quality-Gate S-8: Floating-Overlay-Subsystem (Nicht-existent → OverlayBackend + WindowManager-Lifecycle + WIDGET/HOVER-Differenzierung) Migrations-Pfad-Review
Vollständiger Review des Migrationspfads für Subsystem S-8 (komplett neues UI-Subsystem: OverlayBackend + AndroidOverlayWindow-Wrapper + OverlayPermissionGate + OverlayPermissionObserver + DefaultOverlayLayoutParamsFactory + DefaultOverlayDragHandler + DefaultOverlayPositionMapper + OverlayModule + 5-Button-XML/Strings + Manifest-Permission) gegen Phase-A-Inventur + alle 8 Vorgänger-Reports (S-1/S-2/S-3/S-4/S-7/S-5/S-9/S-6). S-8 ist der **letzte** Phase-B-Subsystem-Audit und das einzige Subsystem ohne Code-Anker — jede Klasse + jedes XML wird neu erschaffen. Daher Hauptaufgabe: Plan-Innenlogik + Cross-Spec-Konsistenz + Architektur-Korrektheit, nicht Code-vs-Plan-Drift. **7 Findings (2 Critical, 3 Important, 2 Minor):** (1) **CRITICAL — `AndroidOverlayWindow.update()` ohne IllegalArgumentException-Catch (SRP-Verstoß + Crash-Pfad)**: Der Wrapper (§4.1, Z. 335–337 vor Fix) ruft `windowManager.updateViewLayout` ohne try/catch. Bei Permission-Revoke zur Laufzeit (User toggelt in Settings ab, während Overlay sichtbar ist) detached Android die View OS-seitig, aber unser `attached`-Bit bleibt `true` (Android sendet kein Broadcast). Der nächste `applyPosition()`-Call (z.B. State-Drag-Update oder render()-Re-apply) ruft `updateViewLayout` auf einer nicht-mehr-attached View → `IllegalArgumentException` → unhandled-Exception-Crash. Plus SRP-Verstoß: der Wrapper sollte Lifecycle-Idempotenz garantieren, aber das galt nur für `detach()`. Bug-Klasse: stiller Daten-Bug bis zum Crash; identische Bug-Klasse hätte beim Update existiert wie beim Remove. Korrektur: try/catch in `update()` ergänzt, `attached = false` + Log; Lifecycle-Idempotenz-Vertrag als prominenter Block dokumentiert ("Wrapper ist alleinige SRP-Heimat für WindowManager-Exception-Behandlung; Backend kennt keinen WindowManager-Exception-Typ — DIP-konform"). (2) **CRITICAL — `AndroidOverlayWindow.attach()` BadTokenException-Catch lebt im Backend (SRP-Verstoß)**: Der Wrapper (§4.1, Z. 330–334 vor Fix) ruft `windowManager.addView` ohne try/catch; stattdessen lebt der Catch im `OverlayBackend.inflateAndAttach()` (§4.2, Z. 522 vor Fix). Damit muss das Backend einen WindowManager-Exception-Typ kennen (DIP-Verstoß). Konsequenter Fix mit Finding 1: Catch wandert in `attach()`, Backend prüft nur `overlayWindow.isAttached() == false` und bricht ab; Backend braucht **keinen** Import für `WindowManager.BadTokenException` mehr. Konsistent mit dem `update()`/`detach()`-Idempotenz-Pattern. (3) **IMPORTANT — §13.4 Tabelle Click-Listener-Spalte widerspricht §4.2-Code (post-Issue-3.1.10 Doppel-Truth)**: §13.4 Tabelle (Z. 2209 vor Fix) sagt Overlay nutzt "pro Render"-Listener (`view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }`), aber §4.2 (Z. 433–443) zeigt `wireStaticOverlayHandlers` einmal-pro-inflate mit `stateRef`/`modeRef`-Field-Pattern — identisch zum IME. Doppel-Truth-Quelle: zwei Spec-3-Sektionen zeigen zwei verschiedene Click-Listener-Setups für den gleichen Backend. Vermutlicher Hintergrund: §13.4 war vor Issue 3.1.10 (User-Decision Option A) geschrieben, das den Spec-2-Pattern auf Overlay übertragen hat; §4.2 wurde dafür refaktoriert, aber §13.4 nicht synchron gezogen. Bug-Klasse: ein Implementer hätte abhängig von der Lesreihenfolge entweder das Backend-spezifische "pro Render"-Pattern oder das geteilte stateRef-Pattern implementiert; das stateRef-Pattern ist das aktuelle Soll (begründet in §4.2-FIX-Kommentar). Korrektur: §13.4 Tabelle auf "identisch" umgestellt, Erklärung um Drag-Routing-Konflikt-Auflösung über Touch-Listener-Hierarchie ergänzt. (4) **IMPORTANT — T7 (HOVER → KEYBOARD via PipelineDone-Cascade) als Übergang in §7 fehlt (Geist-Widget-Bug-Strukturschutz nicht aus FSM-Sektion ableitbar)**: Phase-A Subsystem-Inventur (Z. 588–591) listet T7 als kritischen Test-Pflicht-Mode-Transition ("PipelineDone in HOVER triggert ViewMode.KEYBOARD via Cross-Module-Cascade — Geist-Widget-Bug strukturell ausgeschlossen"). Aber Spec 3 §7.3 zeigt nur T1–T6; T7 ist nur indirekt über §15.1 Coupling-Matrix (`Pipeline × ViewMode = R(state.pipeline) C(ViewModeAction.OnPipelineDone)`) + §10 Acceptance Block 1 erreichbar. Bug-Klasse: ein Implementer, der nur §7 liest, würde die Pipeline-Done-Cascade als implementations-pflichtiges Verhalten übersehen — der "Geist-Widget"-Bug (Overlay-Window bleibt sichtbar nach Pipeline-Done in HOVER) wäre ein verzögerter Bug-Report aus QA, nicht ein verhinderter strukturschutz. Korrektur: T7-Block in §7.3 verankert mit Auslöser (PipelineDone-Cascade aus PipelineModule.onCrossModuleStateChange) + reduce-Snippet im ViewModeModule (Re-Compute via computeViewMode mit pipelineActive=false) + WIDGET-Variante (auch wenn userPrefersWidget=true geht es auf KEYBOARD, weil das Widget eine sichtbare IME oder eine aktive Pipeline braucht). §10 Acceptance um T7-Klausel ergänzt; §14.3 Manual-Test-Plan um T7-Zeile erweitert. (5) **IMPORTANT — Spec 1 §15 hat keine eigene `ViewModeModule`-Implementation; Spec 3 §7.1 SSoT-Note verweist auf "Spec 1 §15 kanonisch" was nicht stimmt**: §7.1 SSoT-Note (Z. 1300–1302 vor Fix) sagt "ViewMode-FSM ist im ViewModeModule (Spec 1 §15) kanonisch implementiert", aber Spec 1 §15.2/§15.3/§15.6 enthalten nur die kanonischen Implementationen für RecordingModule, AudioModule, KeyboardInputModule. ViewModeModule wird in §15.1 Modul-Inventar als Zeile #4 gelistet + in der Coupling-Matrix verankert, aber **kein vollständiger Code-Block**. Spec 3 §6.1 + §7.3 enthalten die `reduce`-Skelette + `computeViewMode`-Truth-Table — das ist faktisch die einzige Quelle. Bug-Klasse: ein Implementer sucht Spec 1 §15.x ViewModeModule und findet keinen Code; folgt der SSoT-Note ins Leere; geht nach §15.2 RecordingModule-Pattern selbst auflegen, oder pingt den Plan-Owner. Korrektur: SSoT-Note erweitert mit expliziter "Implementations-Heimat-Klarstellung" — Spec 1 §15 hat Beispiel-Modul-Implementationen; übrige Module folgen dem Pattern, aber sind nicht vollständig abgedruckt; für ViewModeModule liefert Spec 3 §7.1 + §6.1 + §7.3 (T1–T7) den konkreten Implementations-Anchor; Spec 1 §15.1 verankert den Modul-Inventar-Eintrag + die Cross-Module-Coupling-Matrix-Zeilen. (6) **MINOR — Permission-Boot-Default-Race-Window nicht dokumentiert**: `OverlayState.hasPermission` ist im `DictateUiState.initial()` per default `false` (Spec 1 §3 Z. 183). Zwischen Service-Start und dem ersten `OverlayPermissionObserver.init()`-Dispatch (vom IME-onCreate) sieht jeder State-Subscriber `hasPermission = false` — falls in diesem Fenster ein `render(state, mode)` mit `state.viewMode in (WIDGET, HOVER)` triggert, fällt der Code in den Fallback-Pfad (`teardownOverlay()`). In der Praxis nicht erreichbar (HOVER-Auto-Trigger setzt aktives Recording voraus → Recording startet immer aus dem IME-View, der vorher `init()` durchgelaufen ist; WIDGET-Toggle wird vom User explizit angeklickt), aber bewusste Akzept-Eigenschaft sollte dokumentiert sein. Korrektur: §5.0 OverlayPermissionObserver-Sektion um Boot-Default-Race-Window-Block erweitert. (7) **MINOR — `dragHandler?.isDragging() == true`-null-Verhalten in §4.2 applyPosition undokumentiert**: `dragHandler == null` (zwischen `detach()` und nächstem `inflateAndAttach()`) lässt `?.isDragging()` zu null evaluieren → `== true` ist false → early-return triggert NICHT. Das ist korrekt (ohne aktiven Drag-Handler gibt es keine Drag-Hoheit, die zu schützen wäre), aber subtil und ohne Kommentar. Korrektur: 4-Zeilen-Kommentar an der Stelle ergänzt. Plan-Edits: ~8 Operations in 2 Dateien (Spec 3: 7 — §4.1 Wrapper mit Exception-Hygiene + Idempotenz-Vertrag-Block, §4.2 inflateAndAttach ohne Backend-Catch + applyPosition-null-Kommentar, §5.0 Boot-Default-Race-Block, §7.1 SSoT-Note-Klarstellung, §7.3 T7-Block neu, §10 Acceptance T7-Klausel, §11.6 Edge-Case-Tabelle update, §13.4 Tabelle Click-Listener-Spalte; §14.3 Manual-Test-Plan T7; Hauptplan: 1 — diesen Iter-Log-Eintrag). Spec 1 + Spec 2 unverändert — S-8-Findings sind strikt Spec-3-internal (Wrapper-Idempotenz + Doku-Konsistenz + T7-FSM-Vollständigkeit). **Top-Insight:** Spec 3 ist das einzige Subsystem ohne bestehende Code-Anker — alle Klassen werden neu erschaffen. Daher konnten klassische "Refactor-only-the-API"-Bugs (wie S-7-F-1, S-6-F-1) nicht entstehen. Dafür gab es eine andere Klasse: **Lifecycle-Idempotenz im Wrapper unvollständig** (Findings 1+2). Der `detach()`-Catch war da, aber `update()` und `attach()` hatten denselben Race-Pfad mit IllegalArgumentException/BadTokenException — bei `detach()` wurde der Risiko erkannt, bei `update()` und `attach()` nicht. Lesson: wenn ein Wrapper für eine Resource-Lifecycle existiert, müssen ALLE Lifecycle-Methoden idempotent gegen den OS-seitigen Detach-Race sein, nicht nur eine. Plus die Doku-Drift in §13.4 + §7.1 zeigt einen anderen Verschleißpfad: **Verifikations-Sektionen werden bei späteren Refactors leicht stale** — §13.4 war ein "Konsistenz-Beweis"-Block, der nach Issue 3.1.10 nicht synchron gezogen wurde, obwohl §4.2 (der Code-SoT) korrekt refaktoriert war. T7-Lücke ist die FSM-Spiegelung: §7.3 zeigte 6 von 7 Übergängen, der 7. lebte nur in der Coupling-Matrix.

### 2026-05-14 — Phase-C Quality-Gate C-5: Floating-Overlay Plan-Innenkohärenz-Review
Vollständiger Innenkohärenz-Review der Spec 3 (Floating-Overlay — OverlayBackend + AndroidOverlayWindow-Wrapper + Permission-Observer + Mode-Transitionen T1–T7 + Drag-Lifecycle + 5-Button-Layout + Acceptance + Tests) — §1–§14 — plus Cross-Spec-Verifikation gegen Spec 1 §3 (OverlayState + ViewMode-Sub-State), §15.1 (Modul-Inventar OverlayModule + ViewModeModule + Coupling-Matrix), §15.5 (Cross-Module-Effect-Modi / Mode-3-Verbot), Spec 2 §4 + §8.6 (LayoutCatalog.OVERLAY_5BUTTON-Property-Skelett — C-4 F-5-Cross-Reference) und Hauptplan §3.3 (OVERLAY_5BUTTON-Acceptance-Anker). C-5 baut auf C-1+C-2+C-3+C-4-Edits auf und löst zwei explizite Cross-References auf: (a) C-4 F-5-Cross-Spec-Korrektur-Pflicht (OVERLAY_5BUTTON-Catalog-Embedding) und (b) C-3-Disambiguation `CancelRecording` vs `CancelPipeline` für CloseOverlay-in-HOVER. **7 Findings (3 Critical, 3 Important, 1 Minor); 16 Plan-Edits.** (1) **CRITICAL — Cross-Spec-Compile-Error: `LayoutCatalog.OVERLAY_5BUTTON` als top-level `object` deklariert (C-4 F-5-Vererbung)**: Spec 3 §3.1 deklarierte `OVERLAY_5BUTTON` als top-level Kotlin-`object` außerhalb von `LayoutCatalog`; aber 9+ Konsumenten in Spec 2 + Spec 3 referenzieren den qualifizierten Member `LayoutCatalog.OVERLAY_5BUTTON`. C-4 F-5 hat diese Doppel-Truth-Quelle entdeckt + den SoT-Strukturplatz in Spec 2 §8.6 als Property-Skelett verankert. C-5-Auflösung: Spec 3 §3.1 zeigt jetzt die Einbettung als nested object inside `LayoutCatalog` (`object LayoutCatalog { ... object OVERLAY_5BUTTON : LayoutMode(...) }`). Damit ist `LayoutCatalog.OVERLAY_5BUTTON` ein gültiger qualifizierter Member-Zugriff (Kotlin nested-object). Spec 2 §8.6 Property-Skelett ist jetzt mit dem Inhalt aus Spec 3 §3.1 strukturell befüllbar; Block-6-Implementer kombiniert die zwei Spec-Sites zu einem `object LayoutCatalog`-Body. (2) **CRITICAL — ViewModeModule-Reducer-API-Cluster: 6 Snippets mit Compile-Fehlern (`state.copy(viewMode = ...)`, `state.viewMode`, `state.overlay.X`, fiktives `state.imeViewVisible`)**: ViewModeModule operiert auf der `ViewMode`-Enum-Sub-State (Spec 1 §3 `viewMode: ViewMode`; §15.1 Modul-Inventar-Zeile #4 "viewMode (enum)"). Reducer-Signatur analog Spec 1 §15.2 RecordingModule: `reduce(state: ViewMode, action, ctx): TransitionResult<ViewMode, Effect>?`. Cross-Module-Reads gehen über `ctx.global.x.y`, nicht `state.x.y`. Aber Spec 3 hatte 6 Reducer-Snippets mit globalem-DictateUiState-API-Form: §7.1 OnImeViewShown/Hidden, §7.3 T1 Permission-Gate, §7.3 T2 WIDGET→KEYBOARD, §7.3 T3 OnImeViewHidden, §7.3 T7 OnPipelineDone (mit fiktivem `state.imeViewVisible`), §6.1 ToggleViewModeWidget — alle nutzten `state.copy(viewMode = ...)` (Enum hat kein `.copy()`) oder `state.viewMode` (Enum-Instanz hat keine `.viewMode`-Property). Plus `state.imeViewVisible` ist **fiktives Feld** — existiert weder in DictateUiState noch in ViewMode-Enum. Bug-Klasse: Compile-Error in 6 Snippets, vervielfacht sich bei jedem Mode-Trigger-Copy-Paste; plus Fata-Morgana-Feld würde Implementer zu Mode-3-Verstößen verleiten. Hintergrund: ViewModeModule hat **keinen Code-Block in Spec 1 §15.x** (S-8 F-5 hat das als Implementations-Heimat-Klarstellung dokumentiert) — die Snippets wurden damit nicht gegen ein Spec-1-Modul-Pattern validiert. Korrektur: alle 6 Snippets auf Sub-State-Form umgestellt (`state` als ViewMode-Enum direkt; Cross-Module-Reads über `ctx.global`; `nextState = newViewMode` statt `state.copy(viewMode = ...)`; `state.imeViewVisible` → `state != ViewMode.HOVER` als abgeleiteter Wert); expliziter Signatur-Kommentar in jeder Sektion. (3) **CRITICAL — §5.4 Pseudo-Code-Flow: Mode-3-Verstoß + Pure-Reducer-Violation**: Permission-da-Pfad mutierte gleichzeitig `viewMode + overlay.onboardingPending` (Mode-3-Verstoß, Spec 1 §15.5 Anti-Beispiel-Tabelle Zeile 3 explizit Phase-2-Backlog); plus `permissions.markPermanentlyDenied()` + `permissions.markOnboardingShown()` wurden synchron im Reducer aufgerufen (R.2-Verstoß, Spec 1 §4.2 Reducer-Pure-Contract); plus zwei Module (OverlayModule + ViewModeModule) gemischt in einem when-Block. Bug-Klasse: identisch zu Phase-B S-9 F-1 (§7.3 T1/T2 vor S-9-Fix), aber in §5.4 als "Pseudo-Code-Flow"-Pseudocode mental abgegrenzt und damit unentdeckt. Korrektur: §5.4 vollständig refaktoriert zu zwei getrennten when-Blöcken pro Modul mit korrekter Sub-State-Signatur + Side-Effects über `Effect`-Objekte + Cascade in `OverlayModule.onCrossModuleStateChange` für onboardingPending-Cleanup (Mode-2-Form analog §7.3 T1 post-S-9); plus Hinweis-Block zum onboardingPending=true-Setter-Auslöser-Pfad. (4) **IMPORTANT — §6.2 CloseOverlay-in-HOVER: Pre-C-3-`CancelPipeline` + fehlende Cancel-Cascade in §4.8**: §6.2 referenzierte `Action.PipelineAction.CancelPipeline` als Cascade-Action (Pre-C-3-Form). C-3 F-1 hat festgelegt: für Recording-Hardware-Release ist `Action.RecordingAction.CancelRecording` korrekt; bei HOVER ist `state.recording.isActiveOrPaused` per Definition wahr (HOVER = IME hidden + Pipeline aktiv) — also Recording priorisiert. Zusätzlich: §4.8 OverlayModule.onCrossModuleStateChange emittierte bei HOVER → KEYBOARD nur `SuppressAutoOverlayUntilNextSession` — die Cancel-Cascade **fehlte komplett**. Folge: Bei `Action.ViewModeAction.CloseOverlay` in HOVER würde der ViewMode auf KEYBOARD wechseln + Suppress-Bit setzen, aber das Recording weiterlaufen (kein Cancel-Effect emittiert) — User-Wahrnehmung: "Overlay weg, aber Notification zeigt noch Recording". Symptomatisch identisch zum MediaRecorder-Leak-Bug aus C-3 F-1 (§7.3 onDestroy). Korrektur: §6.2 ViewModeModule.reduce auf Sub-State-Signatur + Doku-Heimat-Kommentar (Cancel-Cascade lebt in §4.8); §6.2 Folge-Block zeigt die Cancel-Cascade explizit (active recording → CancelRecording priorisiert, sonst pipeline !is Idle → CancelPipeline); §4.8 HOVER → KEYBOARD-Block um die Cancel-Cascade ergänzt + Kommentar verweist auf C-3 F-1 + Spec 1 §7.3 onDestroy als symmetrisches Pattern. (5) **IMPORTANT — §13.3 Permissions-Logik-SoT Pre-Issue-3.1.3-Beschreibung**: §13.3 DRY-Block sagte "Permission-Check existiert nur an einer Stelle: `OverlayPermissionGate.hasOverlayPermission()` ... aufgerufen in ViewModeModule.reduce + OverlayBackend.render" — aber post-Issue-3.1.3 ist Permission eine State-Achse (`state.overlay.hasPermission`), nicht ein Live-Read; der Observer (`OverlayPermissionObserver`, §5.0) ist die einzige Live-Quelle, Reducer dürfen keinen synchronen `Settings.canDrawOverlays()`-Aufruf machen (R.2). Korrektur: §13.3 Permissions-Logik-Block refaktoriert auf Post-Issue-3.1.3-Form (State-Achse als SoT, Observer als einzige Live-Quelle, Gate für nicht-Reducer-Konsumenten). (6) **IMPORTANT — OverlayModule `reduceFailure`-Absenz als bewusste Design-Entscheidung nicht dokumentiert (C-3-Cross-Reference)**: §4.8 OverlayModule hat keinen `reduceFailure`-Override. Das ist konsistent mit Default-`null` (Spec 1 §4.2), aber ohne explizite Block-Doku entsteht ein zukünftiger False-Positive-Finding "OverlayModule fehlt reduceFailure". C-3 hat das explizit an C-5 weitergegeben. Korrektur: §4.8 vor dem Reducer-Code-Block um EffectFailure-Konvention-Block erweitert (bewusste Design-Entscheidung: alle Overlay-Effects sind idempotente Pref-Writes / UI-Side-Effects, kein Rollback-Bedarf; Cross-Ref auf Spec 2 §3.3 EffectFailure-KDoc post-C-3). (7) **MINOR — Z.-Refs in §5.0 + §10 nicht auf Section-Anchor umgestellt (F-5-Pattern aus C-1)**: `Spec 1 §3 Z. 183` (§5.0) und `§4.2 Zeile 341` (§10) sind fragil gegen jede Phase-D-Erweiterung. Korrektur: beide auf Section-Anchor + Methoden-/Block-Name umgestellt. Plan-Edits: 16 Operations in 2 Dateien (Spec 3: 15 — §3.1 LayoutCatalog-Einbettung, §4.2 wireStaticOverlayHandlers DRY-Cross-Ref, §4.8 EffectFailure-Design-Block + Cancel-Cascade-Update, §5.0 Z.-Ref-Anchor, §5.4 Mode-3 + Pure-Reducer-Refactor, §6.1 when-Korrektur, §6.2 Cancel-Disambiguation + Reducer-Signatur, §7.1 + §7.3 T1/T2/T3/T7 Reducer-Signatur-Korrekturen, §10 Z.-Ref-Anchor, §13.3 Permissions-Logik-Refactor; Hauptplan: 1 — diesen Iter-Log-Eintrag). Spec 1 + Spec 2 unverändert in C-5 — der Strukturplatz-Anker für OVERLAY_5BUTTON ist bereits in Spec 2 §8.6 von C-4 gesetzt; C-5 hat nur den Inhalts-SoT in Spec 3 §3.1 umgestellt. **Top-Insight:** C-5 hat **drei neue Achsen** gegenüber C-1/C-2/C-3/C-4 erweitert: (a) **Reducer-API-Cluster** (F-2) — ViewModeModule hat keinen Code-Block in Spec 1 §15.x (Implementations-Heimat ist Spec 3), und damit fehlt ein kanonischer Reading-Anchor für die Sub-State-Reducer-Form. Die anderen Module (RecordingModule, AudioModule, KeyboardInputModule) haben Spec-1-Code-Blöcke, die als Validations-Quelle dienen — ViewModeModule fehlt diese Anker-Quelle. Lesson für Phase-D: wenn ein Modul **keinen kanonischen Code-Block in Spec 1 §15** hat ("Implementations-Heimat in Spec 3"), MUSS der Snippet-Reviewer den Sub-State-Reducer-Signatur-Vertrag aus Spec 1 §15.2 (RecordingModule-Beispiel) **manuell anlegen** — `grep` auf "state.copy(" alleine findet die globale-vs-sub-Form-Inkonsistenz nicht, weil es die nicht-existente Methode `Enum.copy()` als Token nicht unterscheidet. (b) **Fiktives-Feld-Sub-Cluster** (F-2 Sub) — `state.imeViewVisible` (in §7.3 T7) ist eine Fata Morgana, die nur durch Cross-Spec-Trace (Spec 3 Reference → Spec 1 DictateUiState-Felder) findbar ist. Saubere Auflösung — IME-Visibility aus aktuellem ViewMode ableiten — ist semantisch einfacher als ein neues State-Feld + zwei Sync-Pfade. (c) **Pseudo-Code-Snippet-Drift** (F-3) — die S-9-Refactor-Welle hat §7.3 T1/T2 auf Mode-2-Cascade umgestellt, aber §5.4 (als "Pseudo-Code-Flow" mental abgegrenzt) blieb mit dem Mode-3-Verstoß stehen. Lesson: Pseudo-Code-Snippets in Plan-Sektionen, die Reducer-Logik zeigen, müssen denselben Sub-State-Reducer-Vertrag durchlaufen wie "echte" Code-Snippets — sie sind Lese-Vorlage für Implementer, und ein Pseudo-Code-Snippet mit Mode-3-Verstoß ist nicht weniger schädlich als ein Code-Snippet mit Mode-3-Verstoß. Plus: **F-4-Cancel-Cascade-Lücke** (CloseOverlay-in-HOVER ohne Recording-Cancel) ist die Spiegelung der C-3 F-1-Disambiguation in einem zweiten Pfad — beide haben dieselbe Struktur (active recording → CancelRecording priorisiert, sonst Pipeline → CancelPipeline). Wiederkehrendes Pattern für Service-/Lifecycle-Cancel-Pfade; ein Phase-D-Audit sollte alle Cancel-Trigger-Sites (Hard-Service-Death, User-Close-Overlay, App-Background-Cancel etc.) systematisch gegen dieses Pattern prüfen.

### 2026-05-14 — Phase-C Quality-Gate C-4: Layout / View-Rendering Plan-Innenkohärenz-Review
Vollständiger Innenkohärenz-Review der Spec 2 (KEYBOARD-Layout-Renderer + LayoutCatalog + ImeViewBackend + MotionScene + Migrations-Tabellen + Acceptance + Tests) — §1–§14 — plus Cross-Spec-Verifikation gegen Spec 1 §4.1 + §15.1 (LayoutModule + ViewModeModule Modul-Inventar), §15.5 (Cross-Module-Effect-Modi), §11.2.2 Schritt 6 (LayoutModule-Atomar-Vertrag), §5 (LocalBinder.state-Naming, post-F-8) und Spec 3 §3.1 (`OVERLAY_5BUTTON`-Deklaration). C-4 baut auf C-1+C-2+C-3-Edits auf und löst die explizite C-1-Offene-Frage zur Atomar-Vertrags-Cross-Verlinkung in Spec 2 §4.1. **6 Findings (1 Critical, 5 Important, 0 Minor); 10 Plan-Edits.** (1) **CRITICAL — §14.2 Test-Snippet `predResendVisible`: vier Bugs in einem 6-Zeilen-Block** (klassisches AI-1-flat-state-paths-Drift-Echo, das in Plan-Body-Sektionen homogenisiert war, aber in Test-Snippets stehen blieb): (a) `RecordingState.Active(false)` — Active braucht 2 Args (`useBluetooth, audioFile`); (b) `PipelineUiState.Preparing` — ist `data class Preparing(val sessionId: String)`, nicht Singleton; (c) `base.copy(lastAudioExists = false)` — `lastAudioExists` lebt in `ResendState` (Spec 1 §3), korrekt ist `base.copy(resend = base.resend.copy(lastAudioExists = false))`; (d) analog für `resendEnabled`. Bug-Klasse: das Snippet ist Lese-Vorlage für die parametrisierte `VisibilityMatrixTest`-Suite (25+ Cases) — die vier Bugs hätten sich vervielfacht; plus Implementer hätte das falsche mentale Modell (flat-state-paths) für Production-Code übernommen. Korrektur: alle vier Stellen auf hierarchische Sub-State-Pfade + korrekte 2-arg-Konstruktoren umgestellt + prominenter FIX-Kommentar mit Cross-Ref auf Spec 1 §3-Sub-State-Klassen. (2) **IMPORTANT — F-11/G2-Naming-Drift `pipelineService` → `pipeline` (LocalBinder) in drei Sites** (Spec 2 §2 L6 Architektur-Entscheidung, §10 Block-4-Acceptance, §11.8 Block-5c-Migration-Snippet): Spec 1 §5 IME-Side-Snippet ist SoT (`pipeline!!.state.collect`), aber die drei Spec-2-Sites blieben in Pre-F-11-Form. Hintergrund: F-11 hat drei verschiedene Naming-Achsen — Class (`PipelineStateManager` → `DictateOrchestrator`, von Phase-B + C-1 homogenisiert), Methoden (von C-1 homogenisiert), und **Feld-Naming im IME** (`pipelineService` → `pipeline`); die Feld-Achse wurde in keiner früheren Welle synchron gezogen. Drift gegen ~20 andere Spec-2-Sites, die bereits `pipeline.dispatch(Action.X)` nutzen. Korrektur: alle drei Sites auf `pipeline.state` + Cross-Ref auf Spec 1 §5 LocalBinder-API. (3) **IMPORTANT — §13.1 Zeile 28 widerspricht §9.6: `pipelineService.markLastAudioExists` ist Pre-F-8-Form**: Die Visibility-Mutation-Audit-Tabelle Zeile 28 (für `DictateInputMethodService.java:1839`) sagt "ENTFERNT — wird zu `pipelineService.markLastAudioExists(true)` State-Update". Aber §9.6 (dieselbe Migration, andere Tabelle) sagt für dieselbe Source-Line "wird zu Action-Dispatch: `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))`" mit FIX-Kommentar: "Drift gegen F-8 (LocalBinder hat NUR `state` + `dispatch`, kein `markLastAudioExists`-Forwarder)". §13.1 ist also intern inkonsistent gegen §9.6. Bug-Klasse: Implementer, der §13.1 als Lese-Anchor nutzt, würde einen nicht-existenten typed Forwarder aufrufen → Compile-Error. Korrektur: §13.1 Zeile 28 auf die §9.6-konforme Action-Dispatch-Form umgestellt + Cross-Ref auf F-8 LocalBinder-API. (4) **IMPORTANT — Atomar-Vertrag setSmallMode in Spec 2 §4.1 nicht cross-verlinkt (C-1-Offene-Frage aufgelöst)**: C-1 hatte explizit gefragt, ob der LayoutModule-Atomar-Vertrag aus Spec 1 §11.2.2 Schritt 6 + Block-1b-Acceptance "Atomarität setSmallMode" in der Spec-2-LayoutModule-Beziehungs-Section gespiegelt ist. Antwort: war nicht. Das ist load-bearing, weil die Atomarität die explizite Mode-3-Grenze ist: `LayoutState.copy(smallMode = enabled, contentArea = MAIN_BUTTONS)` mutiert ZWEI Felder, aber sie leben in DERSELBEN Sub-State-Klasse `LayoutState` — kein Mode-3-Verstoß (Spec 1 §15.5). Ohne Cross-Link würde ein Implementer ggf. die Mutation in zwei separate Actions splitten und damit das KSM-Bug-Verhalten (sequenzielle Schritte, Stale-Zwischen-Zustand) re-introducen. Korrektur: Atomar-Vertrag-Cross-Link-Blockquote in §4.1 nach dem "Vertrag"-Absatz verankert + Cross-Ref auf `LayoutModuleAtomicityTest.kt`. (5) **IMPORTANT — Cross-Spec-Compile-Error: `LayoutCatalog.OVERLAY_5BUTTON`-Member existiert nicht**: Spec 2 §4 + §8.6 (implizit) und Spec 3 §11/§14 (5+ Stellen) referenzieren `LayoutCatalog.OVERLAY_5BUTTON` als qualifizierten Catalog-Member, aber Spec 3 §3.1 deklariert `OVERLAY_5BUTTON` als top-level `object OVERLAY_5BUTTON : LayoutMode(...)` außerhalb des Catalog-Objects. Compile-Error in der jetzigen Form. C-4-Entscheidung: die Catalog-Member-Form ist die korrekte (`LayoutCatalog` ist SoT für ALLE LayoutModes inkl. OVERLAY; Spec-2- und Spec-3-Konsumenten erwarten den qualifizierten Member). Cross-Spec-Korrektur-Pflicht an C-5 weitergegeben; Spec 2 §4 mit FIX-Kommentar versehen (Implementer weiß, dass der Ref jetzt Compile-Error ist, aber post-C-5 grün wird); Spec 2 §8.6 mit Property-Skelett-Anker ergänzt (`// val OVERLAY_5BUTTON: LayoutMode = ... // SoT: Spec 3 §3.1; C-5 ergänzt den Property-Body hier`). (6) **IMPORTANT — §4 Code-Snippet vs. §4.1 Prosa: Single-Backend vs. List-of-Backends**: §4 zeigt `private var activeBackend: RenderBackend?` (Single-Field), aber §4.1 sagt "der KeyboardLayoutManager hält **eine Liste** aktiver Backends statt eines einzigen `activeBackend`-Felds" (für ContentAreaController parallel zu ImeViewBackend, R.10 / Issue 2.1.15 Option B). Doppel-Truth-Quelle in derselben Section. Bug-Klasse: identisch zu Phase-B S-8 F-3 (Spec-3-Click-Listener-Pattern-Doppel-Truth) — ein Implementer würde abhängig von der Reading-Reihenfolge entweder Single-Backend implementieren und beim ContentAreaController-Wiring stutzen, ODER den ContentAreaController in den ImeViewBackend.render-Body inlinen (SRP-Verstoß gegen §4.1). Korrektur: §4 Code-Snippet bleibt unverändert als pädagogisches Single-Backend-Skelett, aber ein prominenter Implementer-Anker-Header VOR dem Snippet dokumentiert die SoT-Hierarchie (§4 = Skelett, §4.1 = Production-Vertrag). Plan-Edits: 10 Operations in 2 Dateien (Spec 2: 9 — §2 L6 Architektur-Entscheidung, §4 Implementer-Anker-Header, §4 LayoutCatalog.OVERLAY_5BUTTON-FIX-Kommentar, §4.1 Atomar-Vertrag-Blockquote, §8.6 LayoutCatalog-Property-Skelett, §10 Block-4-Acceptance-Klausel, §11.8 Block-5c-Migration-Snippet, §13.1 Zeile 28, §14.2 Test-Snippet-Cluster; Hauptplan: 1 — diesen Iter-Log-Eintrag). Spec 1 + Spec 3 unverändert in C-4 — Spec-1-Side ist durch C-1+C-2+C-3 stabil; Spec-3-Side hat die `OVERLAY_5BUTTON`-Korrektur-Pflicht von C-5 zu adressieren (Cross-Reference dokumentiert). **Top-Insight:** C-4 hat **drei Achsen** gegenüber C-1/C-2/C-3 erweitert: (a) **Test-Snippet-Drift-Cluster** (F-1) — klassische AI-1-flat-state-paths-Drift, die in Plan-Body homogenisiert war, aber in Test-Vorlagen stehen blieb. Lesson: Test-Snippets sind häufig **das letzte Refactoring-Target** in einem Plan-Edit-Pass, weil sie als "Beispiel" mental abgekapselt werden — aber sie sind Lese-Vorlage für Implementer und vervielfachen sich in der echten Test-Suite. Bei jeder State-/Action-Sub-Klassen-Umstrukturierung MUSS ein separater Pass über Test-Snippets laufen. (b) **F-11-Drift-Echo dritter Generation** (F-2/F-3) — Phase-B hat den Class-Naming-Echo gezogen, C-1 den Methoden-Echo, C-4 den Feld-Echo (`pipelineService` → `pipeline`). Drei Naming-Achsen, drei Pass-Wellen. Lesson für Phase-D: Naming-Refactors brauchen **separate Grep-Pässe** für jede Achse (Class / Methode / Feld). (c) **Cross-Spec-Struktur-Drift** (F-5) — Spec 2 erwartet `LayoutCatalog.OVERLAY_5BUTTON`, Spec 3 deklariert top-level. Beide Specs sind für sich kohärent, aber die Cross-Spec-Strukturplatz-Konvention ist nicht synchron — nur durch End-to-End-Trace (Spec-2-Konsument → Spec-3-Deklaration über Spec-2-SoT-Object) findbar. Klassischer C-Achsen-Wert.

### 2026-05-14 — Phase-C Quality-Gate C-3: Action-Hierarchie + Dispatch + EffectFailure Plan-Innenkohärenz-Review
Vollständiger Innenkohärenz-Review der Bereiche Spec 1 §4.2 (DictateModule-Interface: `actionClass`, `reduce`, `runEffect`, `reduceFailure`, `onCrossModuleStateChange`), §4.3 (DictateOrchestrator.dispatch + dispatchInternal: EffectFailure-Routing, Reducer-vs-reduceFailure-Dispatch, MAX_CASCADE_DEPTH), §4.7/§4.8 (DictateModuleRegistry + `moduleByLeafClass`-Routing + Vollständigkeits-Check), §4.10 (Kontrakt), §5 (LocalBinder.dispatch), §10 Block-2-Acceptance (MediaRecorder-release-Pfad), §13.5.a G6 (Service-Death während aktivem Recording), §15.2 (RecordingModule + reduceFailure-Hook) + §15.3 (AudioModule.onCrossModuleStateChange) + §15.5 (Cross-Module-Effect-Modi) + §15.6 (KeyboardInputModule) — plus Cross-Spec-Verifikation gegen Spec 2 §3.2 (ButtonSlot.actionResolver-Signatur), §3.3 (Action-Sealed-Hierarchie inkl. EffectFailure-KDoc), §6 (ImeViewBackend.wireStaticHandlers), §8.4 (KEYBOARD_REPROCESS_STAGING-Slot-Definitionen), §9.6 (resend-Mutation-Migration) und Spec 3 (Action-Refs in OverlayBackend + OverlayModule — kein Inhalt-Drift entdeckt). C-3 baut auf C-1 + C-2-Edits auf und löst die explizite Cross-Reference aus C-2 F-3 auf (Action-Naming `CancelPipeline` vs `CancelRecording` für MediaRecorder-Release). **8 Findings (3 Critical, 4 Important, 1 Minor); 11 Plan-Edits.** (1) **CRITICAL — C-2 F-3 Cross-Reference: Action-Naming `CancelPipeline` → `CancelRecording` für MediaRecorder-Release-Pfad**: §10 Acceptance Block-2 + §13.5 G6 Pfad A referenzierten `Action.PipelineAction.CancelPipeline`, das aber via `moduleByLeafClass` an **PipelineModule** routet — Recording-Hardware-Lifecycle gehört per SRP zu RecordingModule (§15.2), das `Effect.ReleaseMediaRecorder` synchron emittiert. Disambiguation-Entscheidung: `Action.RecordingAction.CancelRecording` ist die korrekte Action. Begründung: RecordingModule hat drei Reducer-Arme für CancelRecording (Preparing/Active/Paused + CancelRecording), die alle synchron `Effect.ReleaseMediaRecorder` (+ `Effect.DeleteAudioFile`) emittieren — der Pfad ist bereits implementiert, kein neuer Code nötig. Korrektur: §10 Acceptance + §13.5 G6 + §7.3-onDestroy-FIX-Kommentar alle auf `CancelRecording` umgestellt; komplementäre Pipeline-Cancel-Klausel ergänzt (bei `state.pipeline !is Idle` + kein aktives Recording → `CancelPipeline` an PipelineModule). Domain-Trennung explizit dokumentiert. (2) **CRITICAL — §13.5 G6 referenziert fiktiven `Effect.ReleaseRecording` (existiert in keiner Modul-Effect-Liste)**: §13.5 G6 Pfad A sagt wörtlich "PipelineModule-Reducer emittiert `Effect.ReleaseRecording`" — aber dieser Effect existiert nirgendwo im Plan. RecordingModule hat `Effect.ReleaseMediaRecorder`, PipelineModule hat KEINEN Recording-Hardware-Effect (PipelineModule.Effect-Liste umfasst nur DB-/Job-Effects). Ein Implementer würde improvisieren (Effect anlegen oder direkt `RecordingManager` rufen) — Drift gegen die Plan-Architektur. Korrektur: §13.5 G6 Mitigation auf `Effect.ReleaseMediaRecorder` (RecordingModule.Effect) umgestellt, konsistent mit F-1. (3) **CRITICAL — RecordingModule.reduceFailure: `failure.effect == "AllocateMediaRecorder"` matched NIE (data-class-`toString()`-Bug)**: Der Orchestrator (§4.3 Step 4) füllt `Action.EffectFailure.effect` per `effect.toString()`. Kotlin-`data class.toString()` enthält die Property-Werte (`"AllocateMediaRecorder(target=…, useBluetooth=…, audioFile=…)"`), während `object.toString()` den Simple-Name liefert (`"StopMediaRecorder"`). Der exakte String-Match `== "AllocateMediaRecorder"` ergibt für die data-class-Variante **immer `false`** — der Preparing-Rollback-Arm ist silent-toter Code, jeder AllocateMediaRecorder-Failure würde über Default-`null` als `Rejected("reducer-null")` abgewiesen, Recording bliebe für immer in Preparing hängen. Tests, die den Reducer-Arm direkt aufrufen (ohne `effect.toString()`-Roundtrip), wären grün — der Bug ist nur am Ende eines End-to-End-Pfads sichtbar. Korrektur: `failure.effect.startsWith("AllocateMediaRecorder(")` (Prefix-Match für data-class-toString()-Format); StopMediaRecorder-Arm bleibt exakter Match (object-Typ). Convention-FIX-Kommentar dokumentiert das Pattern; Cross-Doku in Spec 2 §3.3 EffectFailure-KDoc ergänzt. (4) **IMPORTANT — Spec 2 §3.3 EffectFailure-KDoc: stale `Spec 1 §4.3 Z. 617`-Ref (F-5-Pattern aus C-1)**: Phase-B-Apply-Pässe haben §4.3 mehrfach erweitert (Cascade-Order-Block, ProGuard-Block, KeyboardInput-Routing, reduceFailure-Hook); Z. 617 zeigt nicht mehr auf den EffectFailure-Routing-Block. Korrektur: Z. 617 → Section-Anchor "§4.3, EffectFailure-Pfad `dispatchInternal` Step 1a + 2". (5) **IMPORTANT — Spec 2 §3.3 EffectFailure-KDoc: stale Reducer-Arm-Prosa (vor Phase-B S-3 reduceFailure-Hook)**: Die KDoc behauptet "Modul reagiert in seinem eigenen Reducer-Arm `is Action.EffectFailure -> …`" — aber Phase-B S-3 hat den separaten Hook `reduceFailure(state, failure, ctx)` auf dem DictateModule-Interface eingeführt (Spec 1 §4.2). §4.3 Step 2 ruft ihn explizit getrennt von `reduce(...)`. Ein Implementer, der die §3.3-KDoc als Quelle für "wie reagiere ich auf EffectFailure" liest, baut den Failure-Arm in `reduce(...)` ein — entweder Compile-Error (Type-Mismatch gegen modul-spezifischen Action-Typ `A`) oder silent-no-op (Type-Cast-Workaround). Korrektur: KDoc auf den separaten `reduceFailure`-Hook umgestellt + ISP-Begründung dokumentiert + Cross-Ref auf Spec 1 §4.2. (6) **IMPORTANT — Spec 2 §3.3 EffectFailure-KDoc: Effect-Identifier-Konvention für `object` vs. `data class` fehlt**: Die KDoc beschreibt die Routing-Konvention, aber dokumentiert nicht, dass `effect: String` per `effect.toString()` befüllt wird und dass Kotlin-`data class.toString()` die Property-Werte enthält. Ein zukünftiger Modul-Autor mit einem data-class-Effect würde denselben naiven String-Match-Bug wie F-3 reproduzieren. Korrektur: Convention-Block in der KDoc ergänzt (`object`-Effects → exakter Match, `data class`-Effects → Prefix-Match `startsWith("EffectName(")`). (7) **IMPORTANT — Slot-Resolver-`null`-Semantik als strukturelle Verhinderung von `DispatchOutcome.Unrouted` nicht dokumentiert (C-1 F-6-Offene-Frage)**: Spec 2 §3.2 `ButtonSlot.actionResolver`-KDoc dokumentiert "`null` bedeutet: Click ist im aktuellen State unbedeutend", aber nicht wo das `null` aussortiert wird (im Click-Handler per `?.let`) und dass damit die Action den Orchestrator nie erreicht → kein `DispatchOutcome.Unrouted`/`Rejected`-Log-Pfad. Ein Implementer könnte ein Modul ohne Reducer-Arm für eine Resolver-erzeugbare Action schreiben und auf Telemetry-Logs warten, die aber nie kommen. Korrektur: §3.2 KDoc explizit erweitert (Resolver-`null` ist strukturelle Verhinderung, erste Validierungs-Schicht, Reducer ist zweite); §6 wireStaticHandlers-FIX-Kommentar dokumentiert die `?.let`-Filter-Site mit Cross-Ref. (8) **MINOR — Spec 2 §8.4 + §9.6: stale `Z. 205/206/250` Intra-Spec-Refs (F-5-Pattern fortgesetzt)**: Drei inline-Kommentare referenzieren Action-Definitionen per Zeilennummer auf §3.3. Zwar zeigen die Refs aktuell noch auf die korrekten Definitionen, aber sie sind fragil gegen jede zukünftige Erweiterung der Action-Sealed-Class. Korrektur: alle drei Refs auf Action-Name-Anchor umgestellt (Pattern aus C-1 F-5): `"Z. 205"` → `"PipelineAction.SendStaging"`, `"Z. 206"` → `"PipelineAction.CancelReprocessStaging"`, `"Z. 250"` → `"ResendAction.MarkLastAudio"`. Plan-Edits: 11 Operations in 3 Dateien (Spec 1: 4 — §7.3 onDestroy-FIX-Kommentar finalisiert, §10 MediaRecorder-Pfad + Pipeline-Pfad, §13.5 G6 Mitigation, §15.2 RecordingModule.reduceFailure startsWith-Pattern; Spec 2: 6 — §3.2 ButtonSlot.actionResolver-KDoc, §3.3 EffectFailure-KDoc (drei Korrekturen in einem Block: Section-Anchor + reduceFailure-Hook + Effect-Identifier-Konvention), §6 wireStaticHandlers FIX-Kommentar, §8.4 SendStaging + CancelReprocessStaging Kommentare, §9.6 MarkLastAudio Kommentar; Hauptplan: 1 — diesen Iter-Log-Eintrag). Spec 3 unverändert — Action-Refs in Spec 3 verwenden bereits durchgängig die hierarchische Form und enthalten keine stalen Z.-Refs. **Top-Insight:** C-3 hat eine **neue Achse** gegenüber C-1/C-2: nicht nur Drift-Echo (stale Counter, stale Vertrags-Layer), sondern einen **Cross-Spec-Reducer-Logik-Bug** (F-3) entdeckt. Der Effect-Identifier-Match-Bug in `RecordingModule.reduceFailure` entsteht aus der Wechselwirkung zwischen Spec 2 §3.3 (`EffectFailure.effect: String`-Definition + Orchestrator-`toString()`-Encoding) und Spec 1 §15.2 (RecordingModule-Reducer-String-Match). Solche Cross-Spec-Reducer-Bugs sind **nicht durch Drift-grep findbar** — sie brauchen einen End-to-End-Trace durch zwei Specs hindurch. Das ist der Wert des C-Achsen-Mandats: einzelne Spec-internal-Reviews (Phase B) hätten den Bug nicht gefangen, weil jede Spec für sich kohärent war. Plus: die C-2-Cross-Reference (F-3 von C-2) war im Spec gut markiert — der TODO-Marker im §7.3-onDestroy-Snippet hat genau die Disambiguation-Pflicht an C-3 weitergereicht, die jetzt aufgelöst ist. Der Plan-Review-Workflow (C-2 → C-3 → C-4/C-5) funktioniert: explizite Cross-References (statt "vergessen") sind die Mechanik, die das architektonisch korrekte Ergebnis erzwingt. Lesson für Phase-C-4/C-5: **Cross-Spec-Reducer-Logik gezielt prüfen, nicht nur Anchor-Drift.** Beispiel: Spec 2 §8.5 `resolveRecordAction` allokiert ein File und packt es in `Action.RecordingAction.StartRecording(audioFile=…)`; Spec 1 §15.2 RecordingModule liest `action.audioFile` und schreibt es in `RecordingState.Preparing(audioFile=…)`. Diese drei Touch-Points (Resolver → Action → Reducer-State) müssen am selben Type und an derselben Null-Konvention hängen.

### 2026-05-14 — Phase-C Quality-Gate C-2: Service-Layer + Persistence + Lifecycle Plan-Innenkohärenz-Review
Vollständiger Innenkohärenz-Review der Bereiche Spec 1 §6 (Persistence-Erweiterung: Schema-Migration M3→M4, ActiveJobRegistry-Strategie, Checkpoint-Hooks R.17, Recovery-Read, Orphan-FAILED-Audio-Cleanup), §7 (Lifecycle: Foreground Service Composition Root + Notification-Coordinator + Action-Router + onDestroy-Cleanup) und §11 (Implementation-Details: AndroidManifest-Diff, Block-1a/1b/2/3/4-Migrations-Reihenfolge, DB-Migration-Tests inkl. v1→v4-Chain, POST_NOTIFICATIONS Runtime-Permission, OOM-Death-Recovery, Lint-Setup, androidTest-Setup). C-2 baut auf C-1-Edits auf (KeyboardInputModule-Counter homogenisiert, §15.2 Fence-Bug, §13.3.12 DictateModule-Interface 7+4). **8 Findings (3 Critical, 4 Important, 1 Minor); 10 Plan-Edits.** (1) **CRITICAL — §4.11.5.1 `onDestroy`-Snippet zeigt `stateManager.shutdown()` (Pre-F-11-Drift)**: Phase-B S-1 hat den monolithischen `PipelineStateManager` durch `DictateOrchestrator` ersetzt; das §4.11.5.1-onDestroy-Mini-Snippet (Cleanup-Job-Interaktion) wurde dabei nicht synchron gezogen. Bug-Klasse: Compile-Error, weil `stateManager` als Field im Service nicht existiert; ein Implementer würde stutzen, aber wenn er den §7.3-Voll-Snippet (mit `runBlocking`-Timeout) als SoT nutzt, ist das Problem nur kosmetisch. Trotzdem: Lese-Anchor-Drift zwischen §4.11 und §7.3. Korrektur: `stateManager.shutdown()` → `orchestrator.shutdown()`; FIX-Kommentar verweist auf §7.3 als Voll-Snippet-SoT. (2) **CRITICAL — §6.3 Recovery-Snippet übergibt `SessionStatus`-Enum-Werte (Z. 3395) an `getSessionsByStatuses(List<String>)`-DAO**: Innerhalb derselben Funktion `recoverFromDb()` ruft der Top-Block (Z. 3331-3336) das DAO korrekt mit `.name`-Strings auf; der Bottom-Block (Z. 3395) übergibt die Enum-Werte direkt. Bug-Klasse: Kotlin-Type-Mismatch — Compile-Error, NICHT silent. Aber dass es **innerhalb derselben Funktion** zwei verschiedene Calling-Conventions gibt, ist Drift aus iterativer Plan-Editing-Geschichte. Korrektur: Bottom-Block auf `.name`-Strings umgestellt + FIX-Kommentar dokumentiert die Konvention (DAO-Signatur ist `List<String>`, kein TypeConverter). (3) **CRITICAL — §7.3 `onDestroy` fehlt Pre-Cancel-Dispatch für MediaRecorder-Release**: §10 Acceptance Block-2 "MediaRecorder-release-Pfad (FIX Issue 3.0.11)" und §13.5 G6 Pfad A verlangen explizit, dass `Service.onDestroy` bei aktivem Recording zuerst `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` ruft → `RecordingModule.runEffect(Effect.ReleaseMediaRecorder)` → `recordingManager.release()`. Der aktuelle §7.3-onDestroy-Snippet ruft jedoch nur `orchestrator.shutdown()` — was über `module.terminate(services)` cleanup macht. Aber: `DictateModule.terminate(services)` hat im §4.2-Interface einen Default-Body `Unit`, und `RecordingModule` (§15.2) hat KEIN `terminate`-Override, das `Effect.ReleaseMediaRecorder` synchron emittieren würde. → MediaRecorder leakt im Native-Heap bei IME-Schließen während Recording. Drift zwischen §7.3-Code-Snippet und §10/§13.5-Acceptance. Zusätzliche Action-Naming-Frage: `CancelPipeline` ist `Action.PipelineAction`-Variante; Recording-Hardware wird von `RecordingModule` gehalten (Action sollte `Action.RecordingAction.CancelRecording` sein). Cross-Spec-Klärung gehört nach C-3 (Action-Hierarchie). Korrektur: §7.3-onDestroy-Snippet um Schritt-0-Block ergänzt (auskommentiert mit State-Switch zwischen Recording-/Pipeline-Cancel) + prominenten FIX-Kommentar, der die Implementer-Pflicht zur Disambiguierung dokumentiert; semantisch korrekte Action-Variante wird vor Block-2-Acceptance-Test entschieden (C-3-Cross-Reference). (4) **IMPORTANT — §6.2 R.17 Persistenz-Vertrag hat zwei "Reihenfolge"-Klauseln ohne Layer-Disambiguierung**: Bulletpoint 2 ("Reihenfolge State-First": State zuerst, dann DB) und Bulletpoint 5 ("Reihenfolge DB → Cache": DB zuerst, dann ActiveJobRegistry) wirken auf den ersten Blick widersprüchlich, sind es aber nicht — sie sprechen über ZWEI verschiedene Layer-Übergänge (State ↔ DB ist die erste Stufe, DB ↔ Performance-Cache die zweite). Ohne Disambiguierung-Block ist das ein Lese-Anchor-Drift-Risiko: ein Implementer könnte den State zuerst, dann ActiveJobRegistry, dann DB schreiben (= State-First, aber DB-last) — was die DB-first-Garantie für OOM-Recovery aufweicht. Korrektur: Vorab-Disambiguierung als prominenter Blockquote ergänzt (zwei Reihenfolge-Klauseln, zwei Layer; Gesamt-Reihenfolge: State → DB → ActiveJobRegistry); beide Bulletpoints im Vertrag explizit mit Layer-Tag annotiert ("(State ↔ DB)" und "(DB ↔ ActiveJobRegistry)"). (5) **IMPORTANT — §11.6.2 Recovery-Snippet ist veraltete Pre-S-2-Variante, widerspricht §6.3 SoT**: Das §11.6.2-Snippet zeigt eine vereinfachte `recoverFromDb()`-Logik OHNE RECORDING/TRANSCRIBING-Branches; nur ein RECORDED-Subpfad + Ghost-Detection. §6.3 (SoT post-S-2) hat dagegen die volle 6-Stati-Recovery-Logik (RECORDING→FAILED+cleanup, TRANSCRIBING→RECORDED-Downgrade-oder-FAILED, etc.). Bug-Klasse: ein Implementer, der nur §11.6.2 liest, würde die Recovery-Logik unvollständig implementieren — die R.16a/b/c-Tests in §10 Acceptance würden rot fehlschlagen. Korrektur: §11.6.2-Snippet als "vereinfachte Pre-S-2-Variante OHNE RECORDING/TRANSCRIBING-Branches" + "Implementer-Anker: SoT ist §6.3" explizit annotiert + Funktion zu `recoverFromDb_recordedSubPath` umbenannt, damit klar ist, dass es nur den RECORDED-Sub-Pfad illustriert. (6) **IMPORTANT — §11.1.4 Snippet-Prosa-Drift: `stateManager.state.value` (Pre-F-11)**: Mitigation-Text Z. 4554 referenziert `stateManager.state.value` als State-Quelle für den synchronen Notification-Build. Phase-B S-1 hat das auf `DictateOrchestrator.state` umgestellt; Prosa-Drift seit Phase-B nicht synchron gezogen. Korrektur: `stateManager.state.value` → `orchestrator.state.value` + `onCreate` → `onStartCommand` (korrekte Phase, weil `startForeground` in `onStartCommand` lebt, nicht `onCreate` — siehe §4.11.5.1 Sequence-Tabelle Schritt 9). (7) **IMPORTANT — NOTIF_ID-Referenzen in §7.3-onStartCommand + §11.1.2-startForegroundCompat unqualifiziert**: Phase-B S-5 hat die NOTIF_ID-SoT auf `PipelineNotificationCoordinator.NOTIF_ID` konsolidiert (kein `private const val NOTIF_ID` mehr im Service-companion); §10 Acceptance "Phase-B S-5 NOTIF_ID-Konsistenz" verlangt das. Aber §7.3-`onStartCommand` (Z. 3849) und §11.1.2-`startForegroundCompat` (Z. 4584/4586) referenzieren `NOTIF_ID` unqualifiziert — kompiliert nicht, weil im Service-companion (`companion object { private const val TAG = ... }` Z. 3868) keine NOTIF_ID-Const existiert. §11.1.2 Companion-Snippet (Z. 4420-4424) hat genau diese Doppel-Definition explizit gestrichen mit Hinweis "Service referenziert `PipelineNotificationCoordinator.NOTIF_ID` direkt". Drift zwischen Konsolidierungs-Block und Code-Snippet. Korrektur: alle drei Sites (§7.3 onStartCommand, §11.1.2 startForegroundCompat, §4.11.5.1 Sequence-Tabelle Schritt 9) auf `PipelineNotificationCoordinator.NOTIF_ID` qualifiziert. (8) **MINOR — §11.2.3 Test-Strategie Tabelle Pref-Zähler stale (15 → 19)**: Phase-C-1 hat §11.2.2 Schritt 7 von "15 Prefs" auf "19 Prefs" korrigiert (basierend auf §4.5 `initialMirror`-Block: 3 layout + 3 audio + 1 resend + 4 features + 4 theming + 4 overlay = 19). §11.2.3 Test-Strategie-Tabelle (`PipelinePrefMirrorTest`-Zeile) zeigte aber noch "15 Prefs" — Folge-Konsistenz-Drift. Korrektur: Test-Zeile auf "19 Prefs" + explizite Aufzählung der Pref-Buckets aktualisiert. **Plus**: §11.1.1 Block-2-Manifest-Diff-Caption "alle drei Permission-Gruppen kombiniert" zählt vier Permission-Einträge (drei Service + SYSTEM_ALERT_WINDOW); Caption auf "vier Permission-Einträge — drei Service-Permissions + die vorab deklarierte Overlay-Permission" umformuliert (Off-by-One-Counter durch SYSTEM_ALERT_WINDOW-Ergänzung in Phase-B S-5 F-12). Plan-Edits: 10 Operations in 1 Datei (Spec 1: 9 — §4.11.5.1 onDestroy stateManager→orchestrator, §6.2 R.17 Reihenfolge-Disambiguierung-Blockquote + zwei Layer-Tags, §6.3 Recovery-Snippet Enum→.name, §7.3 onDestroy Pre-Cancel-Dispatch-Marker, §7.3 onStartCommand NOTIF_ID-Qualifier, §11.1.1 Block-2-Manifest-Diff-Caption, §11.1.2 startForegroundCompat NOTIF_ID-Qualifier, §11.1.4 stateManager→orchestrator + onCreate→onStartCommand, §11.2.3 Test-Strategie-Tabelle 15→19 Prefs; §11.6.2 Snippet-Annotation als Pre-S-2-Variante; §4.11.5.1 Sequence-Tabelle Schritt 9 NOTIF_ID-Qualifier; Hauptplan: 1 — diesen Iter-Log-Eintrag). Spec 2 + Spec 3 unverändert — C-2-Scope ist Spec-1-zentral (§6 + §7 + §11 sind ausschließlich Service-Lifecycle + Persistence + Block-Implementation, alle Spec-1-internal). **Top-Insight:** Das C-2-Finding-Cluster ist ein **Drift-Echo-Muster** ähnlich C-1: jede Phase-B-Iteration hat einen primären Konsolidierungs-Block angelegt (z.B. F-11-DictateOrchestrator, NOTIF_ID-SoT, R.17-Persistenz-Vertrag), aber Folge-Sites mit Cross-Refs blieben mehrfach stale. Konkret: NOTIF_ID-Konsolidierung in §11.1.2 (Block der Doppel-Definition entfernt) zog die §7.3-Code-Snippet-Sites + §4.11.5.1-Sequence-Tabelle nicht synchron auf qualifizierte Refs; F-11-DictateOrchestrator-Rename zog §4.11.5.1-onDestroy + §11.1.4-Mitigation-Prosa nicht synchron; R.17-DB-first-Erweiterung lebte als 5. Bulletpoint neben dem State-First-Bulletpoint ohne Disambiguierung — drei verschiedene Sub-Drift-Pfade aus drei verschiedenen Phase-B-Iterationen. Lesson: jeder Phase-B-Edit, der eine **Naming-Konvention oder einen Vertrags-Layer** ändert, MUSS einen Plan-weiten `grep` über den ALTEN Naming-Token (z.B. `stateManager\.`, `NOTIF_ID\b`-unqualifiziert) auslösen. C-2 hat alle 10 Echo-Sites in §6 + §7 + §11 homogenisiert. Plus: die §6.3 Recovery-Snippet-Enum-Drift war ein **innerhalb-Funktion**-Drift (Top-Block korrekt, Bottom-Block falsch) — solche Sites sind nicht durch grep findbar (Token ist identisch, nur Aufruf-Argument-Form unterscheidet sich) und brauchen Code-Snippet-Read-Through.

### 2026-05-14 — Phase-C Quality-Gate C-1: State-Modell + Modul-System Plan-Innenkohärenz-Review
Vollständiger Innenkohärenz-Review der Bereiche Spec 1 §3 (DictateUiState + Sub-State-Klassen + PersistentList-Idiom), §4 (DictateOrchestrator + DictateModule + Helpers + AudioFileFactory), §5 (LocalBinder API) und §15 (Modul-Inventar + Coupling-Matrix + RecordingModule/AudioModule/KeyboardInputModule-Beispiele + Cross-Module-Modi). Andere Achse als Phase-B: nicht Code-vs-Plan-Drift, sondern Plan-Innenlogik nach allen 9 Phase-B-Edits. **9 Findings (2 Critical, 4 Important, 3 Minor):** (1) **CRITICAL — §15.2 RecordingModule Markdown-Prosa inmitten des Kotlin-Code-Blocks**: Phase-B S-4 hat den Erklärungstext "audioFile-Vertrag" + "Konsistenz der drei AllocateMediaRecorder-Sites" zwischen Reducer-Body und `runEffect`-Block eingefügt, OHNE den umschließenden ` ``` `-Fence zu schließen. Markdown rendert `**...**` und `1. ...` als Literal innerhalb der Kotlin-Syntax-Hervorhebung; Lesefluss ist gebrochen, Kotlin-Listing sieht aus wie ungültiger Code. Korrektur: Prosa-Block aus dem Code ausgelagert hinter den schließenden Fence (zwischen `}` von RecordingModule und der nachfolgenden Cascade-Reihenfolge-Doku); Inhalt unverändert, Position semantisch erhalten. (2) **CRITICAL — KeyboardInputModule fehlt in §4.1 Architektur-Übersicht-Tree**: §15.1-Modul-Tabelle listet KeyboardInputModule als 13. aktives Modul (Phase-B S-3-Ergänzung); §4.8 DictateModuleRegistry.all listet es ebenfalls; §15.6 hat die kanonische Implementierung. Aber §4.1 Architektur-Übersicht (Tree-Diagramm der Module unter `DictateOrchestrator`) zeigt nur 12 aktive Module + 1 Phase-2-Stub — KeyboardInputModule fehlt. Bug-Klasse: ein Implementer, der sich am §4.1-Tree orientiert ("welche Module muss ich anlegen?"), würde KeyboardInput-Slot ohne Modul-Owner haben → silent-no-op für Backspace/Enter/Space-Klicks (identische Bug-Klasse wie Phase-B S-3 F-2 vor Fix). Korrektur: KeyboardInputModule in §4.1-Tree als 13. aktives Modul ergänzt + Cross-Link auf §15.6; Tree-Caption auf "14 Module (13 aktiv + 1 Phase-2-Stub)" umgestellt. (3) **IMPORTANT — Modul-Zähler-Drift in 5 weiteren Stellen**: nach KeyboardInputModule-Ergänzung sind diverse Plan-Stellen mit dem alten "12 aktive Module"-Zähler nicht synchron: §1 Scope-Aufzählung, §4.2 Interface-Intro, §7.1 Service-Struktur-Tree, §11.2.2 Block-1b-Header + Schritte, §11 Block-1b-Acceptance-Header, §13.3.12 + §13.3.13 Audit-Texte, §15 Modul-Inventar-Intro. Konsistenz-Pflicht: jeder dieser Sites referenziert die "Anzahl Module" als Lese-Anchor — Drift verwirrt Reviewer und produziert Inkonsistenz-Findings in zukünftigen Audits. Korrektur: alle Sites auf "13 aktiv (+1 Phase-2-Stub)" umgestellt; §11.2.2 Schritt 1 zusätzlich auf präzises Sub-State-Feld-Zähl-Verhältnis (12 Sub-State-Typen + `pendingSessions: PersistentList<>`-Feld + Top-Level-Bool) konkretisiert; §11.2.2 Schritt 7 PrefMirror-Zähler 15 → 19 (§4.5 hardcoded-Liste ist 19). (4) **IMPORTANT — DictateModule-Interface-Methoden-Zähler in §13.3.12 + §15.7 stale**: §13.3.12 + §15.7 sagen "5 Pflicht-Methoden + 1 optional". Phase-B S-3 hat `reduceFailure` (default null) als 6. optionale Methode ergänzt; Issue 2.1.2 + 2.1.12 (User-Decision Apply 2026-05-10) haben zusätzlich `prefBindings()` + `terminate()` als optionale Methoden hinzugefügt. Tatsächlicher Stand: 7 Pflicht-Methoden (`id, actionClass, read, write, initialState, reduce, runEffect`) + 4 optionale Hooks mit Default-Body (`reduceFailure, onCrossModuleStateChange, prefBindings, terminate`). Korrektur: §13.3.12 ISP-Block + §15.7 ISP-Zeile auf 7+4 umgestellt; Methoden-Namen explizit aufgezählt, damit ein Reviewer das Interface-Surface auf einen Blick erfasst. (5) **IMPORTANT — Phase-B-Cross-Links via Zeilennummer brechen nach späteren Edits**: Phase-B hat in mehreren FIX-Kommentaren Cross-Links der Form "`§4.3 Z. 617`", "`§4.8 Z. 1017–1033`", "`§4.2 Z. 462`", "`§4.3 Z. 567–570`", "`§4.3 Z. 587–589`" gesetzt. Nach Phase-B-S-4-Apply (Cascade-Order-Block + ProGuard-Block + KeyboardInput-Ergänzungen) sind alle diese Line-Numbers verschoben — typischer Versatz +60 bis +130 Zeilen. Bug-Klasse: ein Reviewer folgt der Z.-Referenz und landet im falschen Code-Snippet, schließt auf "Plan ist inkonsistent". Korrektur: Cross-Links auf Section-Anchor-Form umgestellt ("§4.3 `dispatchInternal` Step 1a + 2", "§4.8 `modules`-Liste", "§4.2 `prefBindings()`-Hook", "§4.3 `DictateOrchestrator`-Konstruktor", "§4.3 ProGuard-Hinweis-Block"). Anchor-Refs überleben spätere Refactorings; Z.-Refs nicht. (6) **IMPORTANT — §5 LocalBinder.dispatch-Return-Type `DispatchOutcome` nicht im KDoc**: `fun dispatch(action: Action) = orchestrator.dispatch(action)` inferiert den Return-Type aus dem Orchestrator (= `DispatchOutcome`), aber das KDoc beschreibt nur die Eingabe-Semantik. Ein IME-Implementer, der `pipeline?.dispatch(Action.X)` ruft, sieht weder im Methoden-Header noch im KDoc, dass ein Outcome zurückkommt — typische Quelle für stille Rejected/Unrouted-Bugs (Action wird gedropped, aber niemand merkt es). Korrektur: Return-Type explizit als `: DispatchOutcome` im LocalBinder ergänzt + KDoc-Absatz dokumentiert, dass IME-Konsumenten den Wert ignorieren dürfen (Rejected/Unrouted sind Phase-1-Telemetry-only, vom Orchestrator bereits geloggt). (7) **MINOR — §15.1.x Coupling-Matrix hat 13×13 Spalten/Zeilen + keinen Hinweis auf KeyboardInputModule-Absenz**: KeyboardInputModule ist Unit-State + kein Observer + kein Inbound-Coupling — die Matrix korrekt zu erstellen wäre eine zusätzliche leere Zeile + leere Spalte ohne Inhalt, also Noise. Aber ohne Caption-Hinweis sucht ein Reviewer "warum fehlt KeyboardInput?" und schreibt einen False-Positive-Finding. Korrektur: Caption-Block direkt unter der Matrix dokumentiert die bewusste Auslassung + verweist auf §15.6-letzter-Absatz (KeyboardInput hat keine Coupling-Beziehung). (8) **MINOR — §3 Tabelle Z. 304 "13 Achsen" vs. §15 Intro "14 Module (13 aktiv + 1 Phase-2)" wirkt widersprüchlich, ist es nicht**: §3 zählt Sub-State-FELDER (Recording, Pipeline, ViewMode, Layout, Overlay, Audio, Resend, LivePrompt, Language, Features, Theming, PendingSessions, Interruption = 13); §15 zählt MODULE (dieselben 13 + KeyboardInput = 14, wobei KeyboardInputModule keine eigene State-Achse hat = `Unit`-State). Beide Zahlen sind korrekt für ihr jeweiliges Counting-Schema. Ohne Klarstellung wirkt das wie "13 vs. 14 — was stimmt jetzt?". Korrektur: §15-Intro um eine Klausel "KeyboardInputModule hat keine eigene State-Achse (`Unit`-State, siehe §15.6); daher 14 Module, aber nur 13 State-Achsen (§3)" — adressiert direkt das Off-by-One-Reading. (9) **MINOR — §11.2.2 Sub-Schritt 1 "12 Sub-State-Klassen + 1 Top-Level-Bool" lässt `pendingSessions` ungenannt**: Mathematisch zählt das ohne `pendingSessions: PersistentList<PendingSession>` (das ist eine PersistentList, kein eigener Wrapper-Typ). §3-Tabelle listet `pendingSessions` aber als 12. State-Achse. Bug-Klasse: ein Implementer denkt "12 Klassen anlegen" und übersieht das `pendingSessions`-Feld, das im DictateUiState als drittletztes Feld erscheint. Korrektur: Schritt 1 auf "12 Sub-State-Typen (Recording/Pipeline sealed + 10 data classes) + pendingSessions als 13. Achse + lastResultNeedsManualPaste-Bool" präzisiert. Plan-Edits: 14 Operations in 1 Datei (Spec 1: 14; Hauptplan: 1 — dieser Iter-Log-Eintrag; Spec 2 + Spec 3 unverändert). **Top-Insight:** der KeyboardInputModule-Drift war strukturell ein Phase-B-Lücken-Symptom: S-3 hat das Modul in §4.8 + §15.6 verankert, aber den §4.1-Tree (= Lese-Anchor für Implementer) nicht synchron gezogen. Folge: die "Anzahl Module"-Zähler-Sites haben sich auf zwei verschiedene Stand-Anker fragmentiert (3 Sites mit alter "12 aktiv"-Zahl, 4 Sites mit neuer "13 aktiv"-Zahl, 2 Sites mit der Hybrid-Mischung "13 Module (12 aktiv + 1 Phase-2)"). Phase-C-1 hat alle 9+ Sites homogenisiert auf "13 aktive Module (+1 Phase-2-Stub)". Lesson: bei Plan-Edits, die einen Zähler ändern (Module, Achsen, Prefs, Methoden), MUSS ein Plan-weiter `grep` über den Zahlen-Token erfolgen — sonst entstehen genau diese fragmentierten Drift-Cluster. Plus die §15.2 Markdown-Inside-Kotlin-Block-Bug zeigt: wenn Phase-B-Editing Prosa zwischen Code-Snippets einfügt, MUSS ein Fence-Check folgen.
