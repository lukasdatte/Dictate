# Research Findings — Plan Review (Autonomous-Mode), 44 🟡-Issues

**Created:** 2026-05-10
**Source:**
- `validated-findings-phase1.md` (8 🟡 — 1.1.1–1.1.8)
- `validated-findings-batch1.md` (21 🟡 — 2.1.1–2.1.21)
- `validated-findings-batch2.md` (15 🟡 — 3.1.1–3.1.15)

**Researched issues:** 44
**🟢 RESEARCH-RESOLVED (auto-applicable):** 21 (R.1–R.21)
**🟡 NEEDS-USER-DECISION (open):** 23
**❌ ELIMINATED:** 0

---

## Executive Summary

Die Recherche bestätigt die Qualität der Validated-Findings: keine eliminierbaren Issues, aber ein erheblicher Anteil (~48 %) lässt sich autonom auflösen, weil entweder

1. die Plan-Empfehlung selbst eindeutig ist und in der Validated-Findings-Datei als „Recommendation" mit klar überlegener Option dokumentiert wird (z.B. 1.1.1 Option B, 2.1.10 Option A),
2. die Recherche zu etablierten Patterns (Excel-EKL Plugin-Module-Augmentation, Standard-MVI/Redux-Toolkit, kotlinx.collections.immutable) eine eindeutige Antwort gibt, oder
3. ein bereits applizierter Fix (z.B. 1.0.5/1.0.6 Action-/State-Pfade) die Voraussetzung für den 🟡-Auflösung schafft.

23 Issues bleiben echte Architektur-Entscheidungen, die User-Input verlangen — sie liegen in den Clustern, die das Validated-Findings-Dokument selbst markiert hat: **Spec-3-Module-Integration**, **HOVER-Lifecycle**, **Block-1-Architektur**, **Reentrancy-Vertrag**, **Failure-Channel**.

### Top-5 Resolutions (priority-sorted by Severity × Footprint)

| # | Issue | Resolution | Auswirkung |
|---|-------|------------|------------|
| R.1 | 1.1.1 PipelineStateManager-Naming | Option B (kontext-sensitives Rename) | 87 Treffer; SSoT-Regel bereits durch Phase-1 1.0.1 + 2.0.2 + 3.0.3 etabliert |
| R.2 | 1.1.7 + 2.1.5 buildContext sync hardware | Option A (audioFile in `RecordingState.Active/Preparing/Paused`) | Beseitigt Pure-Function-Verletzung; löst gleichzeitig 2.1.1 (ReducerContext-Surface) |
| R.3 | 1.1.4 + 2.1.7 Action.NoOp Doppelgleis | Option A + D (nullable resolver + DispatchOutcome) | Eliminiert Log-Spam + Three-indistinguishable-Outcomes; Standard MVI-Idiom |
| R.4 | 1.1.7 Cascade Loop-Guard (Phase 1 1.1.5 in Hauptliste) | Option A + C (depth-counter + DEBUG-assertion) | Standard-MVI-Idiom (Redux-Middleware), low-overhead |
| R.5 | 1.1.5 LayoutModule SRP | Option B (LayoutState-Container) | Konsistenz mit anderen Sub-State-Containern (audio, overlay, …) |

---

## Cluster-Übersicht

| Cluster | Resolved | User-Decision | Bemerkung |
|---------|----------|---------------|-----------|
| Spec-3-Module-Integration (3.1.1, 3.1.2, 3.1.7, 3.1.4, 1.1.2) | — | 5 | Architektur-Cluster, gemeinsam beim User |
| HOVER-Lifecycle (3.1.2-T7, 3.1.3, 3.1.4) | 1 (T7-Cascade) | 2 | T7 als Cascade ist abzuleiten; Permission-Achse + Owner-Architektur sind echte Entscheidungen |
| Block-1-Architektur (1.1.6, 3.1.14) | 2 | — | Phase-1-Recommendation Option A + Sec2-Logic-L-6-Granularität sind kongruent |
| §13-Audit-Konsistenz (1.1.1, 3.1.13) | 2 | — | Reine Doku-Korrektur, mechanisch ableitbar |
| NoOp / Cascade / Effect-Vertrag (1.1.4, 1.1.5/1.1.7-Loop, 2.1.3, 2.1.4, 2.1.6, 2.1.7) | 4 | 2 | MVI-Standard-Patterns geben klare Richtung; nur Reentrancy-Async (2.1.4) und Effect-Failure (2.1.3) bleiben User-Decision wegen API-Tiefe |
| Reducer-Pure-Function (1.1.7, 2.1.1, 2.1.5) | 3 | — | Cluster löst sich gemeinsam mit Option A |
| Drag-Lifecycle (3.1.5, 3.1.6, 3.1.10, 3.1.11) | 2 | 2 | L-3/L-7/L-5 sind klar; Multi-Display + Pattern-Konsistenz Closure-vs-stateRef bleiben offen |
| Plan-Hygiene (3.1.15) | 1 | — | Pragmatische Option A erstreckt sich auf alle Specs |
| Sonstige (2.1.8, 2.1.9, 2.1.10, 2.1.11, 2.1.12, 2.1.13, 2.1.14, 2.1.15, 2.1.16, 2.1.17, 2.1.18, 2.1.19, 2.1.20, 2.1.21, 3.1.8, 3.1.9, 3.1.12) | 6 | 11 | Mix aus klar-resolvable (z.B. 2.1.19 String-IDs, 2.1.10 sessionId-Multi-Job) und User-Architektur-Decisions |

---

## ✅ Resolved Issues (R.1–R.21)

### R.1 — Issue 1.1.1: `PipelineStateManager` vs. `DictateOrchestrator`

- **Origin:** `validated-findings-phase1.md`
- **Category:** [INTEGRATION], **Severity:** Critical
- **Chosen option:** **Option B (kontext-sensitives Rename)** — wie in der Validated-Findings-Datei selbst empfohlen
- **Justification:**
  - Phase-1 1.0.1 + Batch-1 2.0.2 + Batch-2 3.0.3 haben den Großteil der 87 Treffer bereits per Apply abgearbeitet (Spec 1 §3.2-Diagramm, Spec 2 §9.4/§11.5, Spec 3 §5–§7 + §10/§11/§13).
  - Die noch verbleibenden Stellen sind **fast ausschließlich Iteration-Log + Migrations-Begründungen** (z.B. „der pre-F-1-`PipelineStateManager` war die God-Klasse" in §9-Iter-Log) — diese müssen historisch korrekt bleiben.
  - Recherche im Spec 1 §13.5 + §9-Iter-Log bestätigt: alle nicht-applied Treffer sind in Iter-Log/„heutige Klasse"-Kontext, kein einziger in „Zielarchitektur"-Kontext.
- **Implementation instruction:**
  - Reine Verifikation per Grep über alle vier Plan-Files (`research/{1,2,3}-*/*.reviewed.md` + `dictate-keyboard-layout-refactor.reviewed.md`):
    1. `grep -n "PipelineStateManager"` pro File listen.
    2. Pro Treffer prüfen: ist Kontext „Iter-Log"/„heutige Klasse"/„pre-F-1" → BLEIBT (mit Klarstellungs-Suffix wenn fehlt: „(pre-F-1, abgelöst durch DictateOrchestrator)").
    3. Sonst → Replace mit `DictateOrchestrator (Spec 1 §4.3)` oder `dem jeweiligen Modul (Spec 1 §15)`.
  - Iter-Log §9 in jeder Spec um den Apply-Eintrag „R.1 — Naming-Drift kontextsensitiv aufgelöst" ergänzen.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.2 — Issue 1.1.7 + 2.1.5: `buildContext` sync hardware

- **Origin:** `validated-findings-phase1.md` (1.1.7) + `validated-findings-batch1.md` (2.1.5)
- **Category:** [SOLID] / [LOGIC], **Severity:** Important (cluster-Critical)
- **Chosen option:** **Option A** (in beiden Issues identisch empfohlen)
- **Justification:**
  - **Pattern in Codebase:** `RecordingManager.kt` Z. 46 hält `currentAudioFile: File?` als Owner-Field; Z. 58 setzt es bei `start()`, Z. 119 liest es bei `stop()`. Im Refactor wandert dieses Field natürlich in `RecordingState.Active/Preparing/Paused` als Sub-State-Feld (Field-of-State, statt Field-of-Hardware-Adapter).
  - **MVI-Standard:** Reducer pure relativ zu `(state, action, ctx)`. Hardware-Reads gehören in `runEffect()` per Definition.
  - **Side-Benefit:** `ReducerContext` schrumpft — eliminiert 2.1.1 (Surface-Wachstum) ohne separaten Fix.
  - **Test-Vorteil:** State-Tests brauchen keinen `ModuleServicesFactory`-Stub mehr (heute Pflicht für reine Reducer-Tests).
  - **Bug-Eliminierung (Sec1-Logic L-5):** Cancel während Active liest stale `currentAudioFile()` → Orphan-File-Leak. Fix beseitigt das strukturell.
- **Implementation instruction:**
  - **Spec 1 §3 `RecordingState`-Sealed-Class anpassen:**
    ```kotlin
    sealed interface RecordingState {
        object Idle : RecordingState
        data class Preparing(val useBluetooth: Boolean, val audioFile: File) : RecordingState
        data class Active(val useBluetooth: Boolean, val audioFile: File) : RecordingState
        data class Paused(val useBluetooth: Boolean, val audioFile: File) : RecordingState
    }
    ```
  - **Action `StartRecording` erweitern um `audioFile` (oder im Reducer aus `services.recordingHardware.allocateAudioFile()`-Effect-Result wiedergewinnen — Pattern via `MediaRecorderReady(audioFile: File)`-Action).**
  - **Reducer-Path Spec 1 §15.2 anpassen:**
    - `is Active.CancelRecording` → `ctx.recordingAudioFile?.let { … }` ersetzen durch `state.audioFile.let { Effect.DeleteAudioFile(it) }` (immer non-null).
    - Analog `Paused.Cancel` (siehe 2.0.8).
  - **`ReducerContext` schrumpfen** (Spec 1 §4.2):
    ```kotlin
    data class ReducerContext(val audio: AudioState, val now: Long = System.currentTimeMillis())
    ```
    `recordingAudioFile`-Feld entfernen; `buildContext(global)` in §4.3 vereinfachen.
  - **Spec 1 §15.2 Inline-Doku ergänzen:** „audioFile lebt im State (Pure-Function-Garantie); Hardware-Read entfällt."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.3 — Issue 1.1.4 + 2.1.7: `Action.NoOp` + Reducer-`null` + DispatchOutcome

- **Origin:** `validated-findings-phase1.md` (1.1.4) + `validated-findings-batch1.md` (2.1.7)
- **Category:** [CLEAN], **Severity:** Important
- **Chosen option:** **Option A + D kombiniert** (nullable resolver + DispatchOutcome) — wie in 2.1.7 als bevorzugte Empfehlung formuliert
- **Justification:**
  - **MVI-Standard:** „Action did nothing" hat in Mavericks/Orbit/Compose-MVI **immer** den nullable-Resolver-Pfad. `NoOp` als Top-Level-Action ist Anti-Pattern — der Slot bindet im aktuellen State auf nichts; das ist eine **Resolver-Output-Eigenschaft**, keine Action-Class-Eigenschaft.
  - **Three-indistinguishable-Outcomes (Sec1-Logic L-7):** mit nullable-Resolver verschwindet einer der drei Pfade strukturell (kein dispatch-Call → kein Outcome). Der DispatchOutcome-Type macht die verbliebenen zwei (`Rejected` vs. `NoOpReducerNull`) unterscheidbar — Tests können assertieren.
  - **Eliminiert Log-Spam:** „Keine Modul-Zuordnung für NoOp" (§4.3 Z. 446-448) entfällt strukturell.
  - **Konsistent mit 2.1.6 sealed-leaves-Indexing (R.4):** wenn `Action.NoOp` weg ist, ist die `Action`-Hierarchie kanonisch in `RecordingAction|PipelineAction|...` aufgespalten — Indexer kann strikter werden.
- **Implementation instruction:**
  - **Spec 1 §3 / §4.x:** `Action.NoOp` aus dem Action-Sealed-Class-Inventar entfernen.
  - **Spec 2 §3.1 LayoutCatalog `actionResolver`-Type:**
    ```kotlin
    val actionResolver: (DictateUiState) -> Action?
    // Backend-Click-Handler:
    view.setOnClickListener { onAction?.invoke(slot.actionResolver(state) ?: return@setOnClickListener) }
    ```
  - **Spec 3 §3.1 OVERLAY_CLOSE-Slot `actionResolver` umstellen:**
    ```kotlin
    actionResolver = { state ->
        when (state.viewMode) {
            ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget
            ViewMode.HOVER -> Action.ViewModeAction.CloseOverlay
            else -> null  // statt Action.NoOp
        }
    }
    ```
  - **Spec 1 §4.3 `dispatch()` returned `DispatchOutcome`:**
    ```kotlin
    sealed interface DispatchOutcome {
        object Applied : DispatchOutcome
        data class Rejected(val action: Action, val reason: String) : DispatchOutcome
        data class Unrouted(val action: Action) : DispatchOutcome
    }
    fun dispatch(action: Action): DispatchOutcome { ... }
    ```
    IME-Service ignoriert das Result; Tests können `assertIs<Applied>(orchestrator.dispatch(…))`.
  - Spec 1 §4.3 Log-Branch „Keine Modul-Zuordnung" entfernen (Unrouted ist nun ein typisierter Pfad).
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.4 — Issue 2.1.6: `findModule` Routing — sealed-leaves-Indexing

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A** (sealed-leaves-Indexing)
- **Justification:**
  - **Voraussetzung erfüllt:** alle Action-Hierarchien sind `sealed` (Spec 1 §15 + Spec 2 §3.3 — kanonisch).
  - **Eliminiert beide Footguns** (Ambiguity unentdeckt + Inner-sealed-Sub-Sub-Class).
  - **O(1)-Lookup statt Linear-Scan** im Hot-Path.
  - **Init-Time-Failure** ist Standard für DI-Container-Pattern (Hilt, Dagger, Koin).
  - **Konsistent mit R.3 (NoOp entfernt):** keine Top-Level-Top-Outliers, jedes Leaf ist eindeutig einem Modul zugeordnet.
- **Implementation instruction:**
  - **Spec 1 §4.3 `findModule` ersetzen durch sealed-leaves-Walker:**
    ```kotlin
    private val moduleByLeafClass: Map<KClass<out Action>, DictateModule<*, *, *>> = run {
        val map = mutableMapOf<KClass<out Action>, DictateModule<*, *, *>>()
        modules.forEach { module ->
            collectLeaves(module.actionClass).forEach { leaf ->
                require(map.put(leaf, module) == null) {
                    "Action $leaf is routed to multiple modules — ambiguity detected at init"
                }
            }
        }
        // Init-time check: jeder Leaf muss zugeordnet sein
        require(allActionLeaves().all { it in map }) {
            "Unrouted action leaves: ${allActionLeaves() - map.keys}"
        }
        map
    }
    private fun collectLeaves(c: KClass<out Action>): List<KClass<out Action>> =
        if (c.sealedSubclasses.isEmpty()) listOf(c)
        else c.sealedSubclasses.flatMap { collectLeaves(it as KClass<out Action>) }
    ```
  - **Linear-Fallback in `findModule` entfernen** — Lookup ist immer eindeutig.
  - **Spec 1 §4.3 Inline-Doku:** „Sealed-leaves-Indexing — jede konkrete Action-Class ist genau einem Modul zugeordnet; Verstoß ist Init-Time-Error."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.5 — Issue 1.1.5 (Phase-1) / Issue 1.1.5: `LayoutModule` SRP-Smell

- **Origin:** `validated-findings-phase1.md` (1.1.5)
- **Category:** [SOLID], **Severity:** Important
- **Chosen option:** **Option B** (LayoutState-Container)
- **Justification:**
  - **Pattern-Konsistenz:** alle anderen 13 Module nutzen Sub-State-Container (`AudioState`, `OverlayState`, `ResendState`, …). `state.contentArea` als Direkt-Feld ist die einzige Inkonsistenz.
  - **Recommendation der Validated-Findings:** Option B ist explizit als „kleiner Footprint, klarere Hierarchie, konsistent mit anderen Sub-State-Klassen" empfohlen.
  - **Option A überspezifiziert** auf 14 Module ohne fachlichen Gewinn (ContentArea ist 1 Enum, keine eigene Achse mit Reducer-Logik).
  - **Option C** lässt strukturelle Inkonsistenz stehen — gegen die SSoT-Regel.
- **Implementation instruction:**
  - **Spec 1 §3 `DictateUiState`:**
    ```kotlin
    val layout: LayoutState
    // wo:
    data class LayoutState(
        val contentArea: ContentArea = ContentArea.MAIN_BUTTONS,
        val singleRowMode: Boolean = false,
        val smallMode: Boolean = false,
        val animationsEnabled: Boolean = true,
    )
    ```
  - **State-Pfade global ersetzen:**
    - `state.contentArea` → `state.layout.contentArea`
    - `state.layout.singleRowMode` bleibt (war schon nested via R.1.0.6-Apply).
  - **Hot-Spots:**
    - Spec 1 §3 Achsen-Tabelle (Z. 207ff)
    - Spec 1 §6.3, §9, §13.4
    - Spec 2 §6 / §8.5 / §13 (alle `state.contentArea`-Reads)
    - Spec 3 §11.6 (User-Bug-Pfad)
  - **§15.1 LayoutModule-Eintrag** auf neue 4-Field-LayoutState-Achse aktualisieren.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.6 — Issue 1.1.7 (Phase-1) / 1.1.5 in Cluster: Cascade Loop-Guard (Phase-1 Issue 1.1.7 misnummeriert; Phase-1-Reentry zu Issue 1.1.7 = depth-counter)

- **Origin:** `validated-findings-phase1.md` (1.1.7 — Cross-Module-Cascade rekursiv ohne Loop-Guard)
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A + Option C kombiniert** (Tiefe-Counter + DEBUG-Assertion)
- **Justification:**
  - **Standard-Pattern:** Redux-Toolkit + Orbit-MVI nutzen Tiefe-Counter (z.B. Redux's `redux-thunk` mit `getState()` + cap, Recoil's depth limit). Zyklen sind selten, depth-counter ist „good-enough".
  - **Low-Overhead:** ein `Int`-Parameter pro `dispatch`-Call, eine `if`-Prüfung. Praktisch keine Performance-Auswirkung.
  - **DEBUG-Assertion:** entdeckt Loops in Tests sofort; in Production graceful degradation (Logger-Warning + Cascade-Abbruch — IME crashed nicht).
  - **Option B (Cycle-Detection per Action-Klasse)** wurde geprüft und ist zu teuer/komplex für den realen Bedarf — Set-Allocation pro dispatch ist GC-Pressure auf einem heißen Pfad.
- **Implementation instruction:**
  - **Spec 1 §4.3 `dispatch` mit depth-Parameter:**
    ```kotlin
    fun dispatch(action: Action) = dispatchInternal(action, depth = 0)

    private fun dispatchInternal(action: Action, depth: Int): DispatchOutcome {
        if (depth >= MAX_CASCADE_DEPTH) {
            val msg = "Cascade loop detected at depth=$depth, action=$action"
            if (BuildConfig.DEBUG) error(msg)
            else { android.util.Log.e(TAG, msg); return DispatchOutcome.Rejected(action, "cascade-loop") }
        }
        // ... bisherige Logik ...
        cascadeActions.forEach { dispatchInternal(it, depth + 1) }
        return DispatchOutcome.Applied
    }

    companion object { private const val MAX_CASCADE_DEPTH = 8 }
    ```
  - **Spec 1 §15.5 Dokumentation ergänzen:** „Cascade-Tiefen-Counter (Cap 8). Ein Loop wird in DEBUG via `error()` und in Release via Logger-Error abgebrochen — IME crashed niemals."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.7 — Issue 1.1.6: Block-1-Split (1a + 1b)

- **Origin:** `validated-findings-phase1.md` (1.1.6) + `validated-findings-batch2.md` (3.1.14)
- **Category:** [INTEGRATION], **Severity:** Critical
- **Chosen option:** **Option A** (Split + Granularität via Sec2-Logic-L-6 1a/1b/1c/1d/1e)
- **Justification:**
  - **Recommendation in beiden Issues konsistent.** Phase-1 1.1.6 + Batch-2 3.1.14 empfehlen identisch Option A.
  - **Sec2-Logic L-6 (Batch 1)** schlägt sogar feinere Granularität vor (1a–1e mit kompilier-grünen Etappen).
  - **„Vor allem"-Garantie** wird durch 1a (im heutigen Code) eingehalten — semantisch ehrlicher als „Block 1 hängt von Block 2 ab".
  - **Implement-Long-Plan-Skill-Kompatibilität:** kleinere Blöcke = weniger Risk pro Chunk; Block-Mode-Validate kann pro Sub-Block laufen.
  - **Spec 2 §13.5 Gap 5** beschreibt das `predResendVisible`-Konsolidierungs-Pattern bereits implizit — Block 1a ist also schon teil-spezifiziert.
- **Implementation instruction:**
  - **Hauptplan §4 Block-Tabelle erweitern:**
    | Block | Inhalt | Komplexität | Reihenfolge |
    |-------|--------|-------------|-------------|
    | 1a | `predResendVisible`-Helper konsolidieren; alle 6 resend_btn-Mutationen auf Helper umstellen; `recordButton.text/isEnabled`-Hybrid auflösen — **im heutigen Code, ohne Modul-Architektur** | klein-mittel | **vor allem (heutiger Code)** |
    | 2 | PipelineService-Skelett (FGS, ServiceScope, LocalBinder) | mittel | nach 1a |
    | 1b | DictateUiState (hierarchisch), DictateOrchestrator, 13 Module, Action-Sealed-Hierarchie — **im PipelineService-Container** | groß | nach 2 |
    | 3 | LanguageController, BluetoothScoManager, AudioFocus → Module-Migration | mittel | nach 1b |
    | 4 | RecordingManager → RecordingHardwareSubsystem-Adapter | mittel | nach 3 |
    | 5 | LayoutCatalog (Spec 2) — KeyboardLayoutManager + ImeViewBackend | groß | nach 4 |
    | 6 | OverlayBackend (Spec 3) | mittel | nach 5 |
  - **Hauptplan §4 Z. 208 „Block 1 muss vor allem"-Garantie** umschreiben auf „Block 1a muss vor allem (heutiger Code, kompilier-grün)".
  - **Hauptplan §6 Risiken** — neuer Eintrag „Block-1-Quick-Wins ↔ Block-1b-Module-Aufbau ohne Split → Race-Condition bei Visibility-Mutationen während Migration".
  - **Spec 1 §10 Block-1-Acceptance** in 1a/1b granular aufteilen (siehe 3.1.14-Empfehlung):
    - 1a: 6 resend_btn-Mutationen konsolidiert; recordButton-Hybrid weg.
    - 1b: 13-Module-Inventar-Check; Pref-Mirror-Durchschlag-Check; Action-KClass-Routing-Test.
  - **Spec 1 §11.2.2 (Migrations-Tabellen)** in 1a/1b umstrukturieren mit „kompilier-grün vor nächstem Sub-Block"-Vertrag.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.8 — Issue 2.1.10: Concurrency — parallele Pipelines

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A** (Multi-Job-Modell mit sessionId-Tracking)
- **Justification:**
  - **Use-Case zwingend:** Auto-Enter (LivePromptModule) chained Pipelines — **ohne** parallele Pipelines wäre das Feature funktionslos (zweite Recording-Aktion müsste auf die erste warten).
  - **Existierender Pattern:** `JobExecutor` + `ActiveJobRegistry` (siehe `core/JobExecutor.kt` + `core/ActiveJobRegistryObserver.kt`) sind bereits Multi-Job-fähig — die Pipeline kann ihre `sessionId` auf den existierenden Slot mappen.
  - **Recommendation in Validated-Findings explizit Option A.**
  - **DB-Tabellen-Schema** trägt sessionId schon (DAO `getByStatus("RECORDED")` in §6.3 zeigt das).
- **Implementation instruction:**
  - **Spec 1 §3 `PipelineUiState`:** sealed-class um `sessionId` erweitern (jede Sub-Class trägt die ID):
    ```kotlin
    sealed interface PipelineUiState {
        object Idle : PipelineUiState
        data class Preparing(val sessionId: String) : PipelineUiState
        data class Running(val sessionId: String, val target: InsertionTarget) : PipelineUiState
        data class ReprocessStaging(val sessionId: String, val transcript: String) : PipelineUiState
    }
    ```
  - **Spec 1 §15 PipelineModule:** Done-Action enthält `sessionId`. Reducer matched gegen aktiven UI-Slot:
    ```kotlin
    is Action.PipelineAction.Done -> {
        if (state is Running && state.sessionId == action.sessionId) {
            // UI-Wechsel zu Idle/Done
        } else {
            // Background-Insertion: DB markCompleted + Notification, kein UI-Wechsel
            TransitionResult(nextState = state, sideEffects = listOf(Effect.NotifyBackgroundDone(action.sessionId)))
        }
    }
    ```
  - **Spec 1 §4.7 PipelineRunner-Interface:** `submit(sessionId: String, …)` — falls Job mit derselben `sessionId` läuft, ablehnen (Idempotenz). Sonst Job in eigener Coroutine starten.
  - **Spec 1 §10 Block-1-Acceptance** ergänzen: „Auto-Enter mit parallelem Recording: zwei sessionIds, beide laufen, nur eine ist aktiv im UI-Slot, andere liefert Notification".
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.9 — Issue 2.1.11: View-Recreate-Vertrag (viewScope-Cancel)

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A** (explizite §8.x „View-Recreate-Vertrag"-Section)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Existierende heutige Mechaniken** (`cleanupOldControllers`, `rewireCallbacks`, `restoreUiState` — siehe `DictateInputMethodService.java`) sind Quelle der Wahrheit, was tatsächlich passiert. Refactor erbt das Verhalten 1:1, nur über `viewScope.cancel()` zentralisiert.
  - **Robolectric-Test** ist Standard für IME-Lifecycle (existiert bereits im Projekt für andere Komponenten).
  - **Option B (Status quo + Code-Review)** ist zu schwach für eine Lifecycle-Vertrag-Architektur — nicht nachvollziehbar in 6 Monaten.
- **Implementation instruction:**
  - **Spec 2 (oder Spec 1 §5 LocalBinder-Section) neue §8.x „View-Recreate-Vertrag":**
    ```
    1. viewScope-Erzeugung in DictateInputMethodService.onCreateInputView() VOR Subscriber-Wiring.
    2. viewScope.cancel() in DictateInputMethodService.onFinishInputView() (analog cleanupOldControllers).
    3. WindowManager.removeView (Overlay) wird in OverlayBackend.detach() gerufen (kein StateFlow-Cancel,
       sondern expliziter Call — siehe §11.6 / Spec 3 §4.3).
    ```
  - **Migrations-Tabelle pro Spec 2 §11.X** ergänzen: „Detach-Calls heute → cancel automatisch in Refactor"
    | Heutiger Call | Refactor-Replacement |
    |---|---|
    | `cleanupOldControllers()` | `viewScope.cancel()` (in `onFinishInputView`) |
    | `rewireCallbacks()` | entfällt (StateFlow-Subscriber + new viewScope) |
    | `restoreUiState()` | entfällt (StateFlow holds state, neuer viewScope subscribed → erste Emission auto-restored) |
  - **Spec 2 §10 Block-5-Acceptance** ergänzen: „Robolectric-Test rotation while pipeline running — nach `onFinishInputView` + `onCreateInputView` ist der Subscriber neu attached und der Pipeline-State ist korrekt gerendert."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.10 — Issue 2.1.13: KeyboardStateManager-Aufspaltung

- **Origin:** `validated-findings-batch1.md`
- **Category:** [SOLID], **Severity:** Important
- **Chosen option:** **Option A** (Aufspalten in 3 kleine Klassen)
- **Justification:**
  - **Pattern-Konsistenz:** alle anderen Refactor-Targets (RecordingManager, BluetoothScoManager) bleiben SRP-rein nach Refactor — KSM darf nicht die Ausnahme sein.
  - **Recommendation in Validated-Findings explizit Option A.**
  - **Bestehende Klassen-Namen** (`MainButtonsController.kt`, `KeyboardLayoutModeController.kt`) zeigen, dass Single-Responsibility-Controller bereits etabliertes Pattern in der Codebase sind.
  - **Spec 2 §11.X Lösch-Tabelle** (2.0.9) erlaubt Aufspaltung als „Klasse wird ersetzt durch drei kleinere".
- **Implementation instruction:**
  - **Spec 2 §11.X Lösch-/Adapter-Tabelle anpassen:**
    | Heutige Klasse | Final gelöscht in Block | Ersatz |
    |---|---|---|
    | KeyboardStateManager | Block 5d | aufgespalten in: ContentAreaController, PromptVisibilityController, OverlayResetHandler |
  - **Spec 2 §6 / §9.3 ContentAreaController erstmalig spezifizieren:**
    ```kotlin
    // Owner: mainButtonsCl/qwertz/emojiPicker-Container-Visibility (state.layout.contentArea-Achse)
    class ContentAreaController(views: KeyboardViews) : RenderBackend  // optional als RenderBackend
    ```
  - **Spec 2 §9.3 PromptVisibilityController** als neue Klasse: prompts-Container + Sub-Views.
  - **Spec 2 §13.1 / §13.2 Audit-Tabellen** reflektieren die drei neuen Owner.
  - **Spec 2 §11.8 Migration:** in 5d wird KSM gelöscht; vorher (5c) werden die drei Methoden in Stub-Bodies + neue Owner-Klassen migriert (vermeidet Doppelmutations-Fenster — siehe R.13).
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.11 — Issue 2.1.14: Visibility-Owner D2 — `visibilityMode="ignore"` auf alle 9 Buttons

- **Origin:** `validated-findings-batch1.md`
- **Category:** [SOLID], **Severity:** Critical
- **Chosen option:** **Option A** (alle 9 Buttons mit `visibilityMode="ignore"`)
- **Justification:**
  - **Recommendation in Validated-Findings explizit Option A.**
  - **SRP-stärker:** Catalog ist die einzige Visibility-Quelle; MotionScene macht ausschließlich Position. Eindeutiger Vertrag, keine Sonderfälle.
  - **Lint-Regel-Vereinfachung (Sec3-Logic L-4 dedupliziert):** wenn alle Buttons `ignore` haben, ist die Frage hinfällig.
  - **Diff-Volumen niedrig:** 9 Buttons × 1 Attribut = 9 XML-Edits.
- **Implementation instruction:**
  - **Spec 2 XML-Layout-Files** (`*.xml` für Two-Row, Single-Row, Send-Modes): bei jedem der 9 Buttons (record_btn, resend_btn, backspace, audio_focus, widget_toggle, trash, space, pause, enter):
    ```xml
    motion:visibilityMode="ignore"
    ```
  - **Spec 2 §7.3 Tabelle** trivialisieren: "alle Buttons unter Catalog-Visibility-Ownership".
  - **Spec 2 §6 (`applySlotToView`)** Inline-Doku ergänzen: „Catalog ist alleiniger Visibility-Owner — `visibilityMode='ignore'` auf allen Buttons garantiert kollisionsfreien Render."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.12 — Issue 2.1.16: LayoutMode `sceneStateId`-OCP

- **Origin:** `validated-findings-batch1.md`
- **Category:** [SOLID], **Severity:** Important
- **Chosen option:** **Option A** (`sceneStateId` direkt am `LayoutMode`)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **OCP-Standard:** „neuer Mode = neue Konstante, keine zentrale Funktion editieren". Konsistent mit Plugin-Module-Pattern in Excel-EKL (siehe `knowledge-reference`).
  - **Footprint moderat:** ein Field, keine top-level extension function mehr.
- **Implementation instruction:**
  - **Spec 2 §3 LayoutMode-Datenklasse:**
    ```kotlin
    data class LayoutMode(
        val id: LayoutModeId,
        val backend: BackendType,
        val rows: List<RowDescriptor>,
        val sceneStateId: Int? = null,  // NEU — null für Backends ohne MotionLayout (Overlay)
    )
    ```
  - **Spec 2 §6 ImeViewBackend.render** liest `mode.sceneStateId`:
    ```kotlin
    mode.sceneStateId?.let { motionLayout.transitionToState(it) }  // oder jumpToState — siehe R.13
    ```
  - **Spec 2 §X (irgendwo nach §6) `toSceneStateId()`-Extension entfernen.**
  - **Spec 2 neue §8.x „Erweiterungs-Pattern: neuer Layout-Modus":** 5-Punkt-Checklist (LayoutModeId-Enum, Catalog-Konstante, Scene-XML, sceneStateId, forKeyboard()-Branch).
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.13 — Issue 2.1.17: §11.8 Migration — KSM-Übergangs-State

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Critical
- **Chosen option:** **Option A + C kombiniert** (leerer Body in 5c + Strict-Mode-Logging)
- **Justification:**
  - **Recommendation in Validated-Findings explizit kombinierte A+C.**
  - **Risiko-frei:** leerer Body in 5c eliminiert die Doppelmutation strukturell; Strict-Mode-Log macht Acceptance verifizierbar.
  - **Option B (Zusammenführen 5c+5d)** vergrößert PR-Risiko (= ein riesiger PR mit Bug-Klassen).
- **Implementation instruction:**
  - **Spec 2 §11.8 Block 5c Beschreibung:**
    > In 5c werden die drei KSM-Methoden (`applyRecordingControlsVisibility`, `applyContentAreaVisibility`, `applyPromptsVisibility`) durch **leere Implementierung** ersetzt (no-op). KSM.refresh ruft sie weiter auf, ohne dass etwas passiert. Manager (R.10-Owner-Klassen) übernimmt die Visibility-Mutationen.
  - **Spec 2 §11.8 Block 5d Beschreibung:**
    > 5d entfernt die leeren Methoden + alle Aufrufer (`KSM.refresh` + Sites in MainButtonsController etc.).
  - **Spec 2 §10 Block-5-Acceptance** ergänzen: „Strict-Mode-Logging während 5c — `VisibilityWrite from $caller`-Log; Acceptance-Kriterium 'keine zwei Subsysteme schreiben gleichzeitig auf einer Visibility-Achse'."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.14 — Issue 2.1.18: §6 firstRender-Flag

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Critical
- **Chosen option:** **Option A + B kombiniert** (firstRender-Flag + detach()-Reset)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Acceptance §10 verlangt das verbindlich** („`jumpToState` statt `transitionToState` beim ersten Render").
  - **MotionLayout-API-Verhalten:** `transitionToState` von Initial-State ergibt 250ms-Animation; `jumpToState` ist atomar. Acceptance kann nur durch Flag erfüllt werden — Code-Skizze in §6 hat den Bug.
  - **detach()-Reset (Option B)** ist nötig für korrekte view-recreate-Semantik (gleiche Backend-Instanz wird wiederverwendet nach Rotation).
- **Implementation instruction:**
  - **Spec 2 §6 ImeViewBackend.render anpassen:**
    ```kotlin
    private var firstRender: Boolean = true

    override fun render(state: DictateUiState, mode: LayoutMode) {
        // ... bestehende Logik ...
        mode.sceneStateId?.let { sceneId ->
            if (firstRender || !state.layout.animationsEnabled) {
                motionLayout.jumpToState(sceneId)
            } else {
                motionLayout.transitionToState(sceneId)
            }
        }
        firstRender = false
    }

    override fun detach() {
        // ... bestehende Logik ...
        firstRender = true   // Reset für view-recreate-Semantik
    }
    ```
  - **Spec 2 §6 Inline-Doku ergänzen:** „MotionLayout-Initial-State ist immer der erste ConstraintSet — beim Re-Inflate (Rotation, Theme-Wechsel) muss der erste Render `jumpToState` rufen, sonst sieht der User eine 250ms-Animation vom Initial-State zum eigentlichen Mode."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.15 — Issue 2.1.19: Session-ID Type-Mismatch

- **Origin:** `validated-findings-batch1.md`
- **Category:** [INTEGRATION], **Severity:** Critical
- **Chosen option:** **Option A** (String durchgängig)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Minimaler Footprint:** heutige IDs sind bereits String (UUID), kein DB-Schema-Change.
  - **Java-Compat:** `getStringExtra` ist trivial in Java (IME-Service ist Java).
- **Implementation instruction:**
  - **Spec 1 §7.5 (Notification-Click-Handler):**
    ```kotlin
    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
    pipeline?.dispatch(Action.PipelineAction.ConfirmInsertion(sessionId))
    ```
  - **Spec 1 §6.1 markInserted-Signatur** bleibt `(id: String, ...)`.
  - **Spec 1 §11.4.2 Migration-Test-Tabelle** bleibt String-IDs.
  - **Spec 1 §11.5 Action-Extras-Signatur** auf String konsistent: `EXTRA_SESSION_ID: String` durchgängig.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.16 — Issue 2.1.20: Recovery deckt RECORDING/TRANSCRIBING nicht

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A** (vollständige Recovery-Logik)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **DB-Status-Lifecycle ist explizit:** Sessions in `RECORDING/TRANSCRIBING` müssen post-Process-Restart als `failed` markiert werden, sonst ewig stuck.
  - **Merge-Operation** beseitigt Race mit neuem Recording während Recovery.
- **Implementation instruction:**
  - **Spec 1 §6.3 recoverFromDb komplett:**
    ```kotlin
    suspend fun recoverFromDb() = withContext(Dispatchers.IO) {
        val all = sessionDao.getSessionsByStatuses(listOf(
            SessionStatus.RECORDING, SessionStatus.TRANSCRIBING,
            SessionStatus.RECORDED, SessionStatus.TRANSCRIBED,
            SessionStatus.COMPLETED,
        ))
        val (stuckRecording, _) = all.partition { it.status in listOf(RECORDING, TRANSCRIBING) }
        // Stuck-Sessions als failed markieren
        stuckRecording.forEach { sessionDao.markFailed(it.id, reason = "process-restart-during-${it.status}") }
        // Audio-Files prüfen (dead-files entsorgen, andere als pending behalten)
        val recovered = all.filter { it.status == RECORDED && it.audioFile.exists() }
                          + all.filter { it.status == COMPLETED && it.insertedAt == null }
        // MERGE — kein Override
        store.update { current ->
            current.copy(pendingSessions = current.pendingSessions.addAll(recovered))
        }
    }
    ```
  - **Spec 1 §10 Block-2-Acceptance** ergänzen: „Process killed during RECORDING: Session ist post-Recovery `status=failed`, Audio-File aufgeräumt." + „Race-Test: parallel-Recording während Recovery führt nicht zu pendingSessions-Override."
  - **Spec 1 §6.1 SessionDao** muss `getSessionsByStatuses(...)` + `markFailed(id, reason)` exposen.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.17 — Issue 2.1.21: Checkpoint-Hooks Idempotenz

- **Origin:** `validated-findings-batch1.md`
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A** (vollständiger Vertrag)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Standard-Pattern:** Room/SQLite-Convention `@Insert(onConflict = REPLACE)`; State-First-Reihenfolge ist MVI-Standard (Reducer mutiert State, Effect schreibt DB asynchron).
  - **`PersistenceError`-Action** ist konsistent mit dem Failure-Channel-Pattern (siehe verwandtes Issue 2.1.3 — bleibt User-Decision, aber wenn 2.1.3 Option D entscheidet, ist das hier vorgreifend).
- **Implementation instruction:**
  - **Spec 1 §6.2 erweitern um Vertrag-Block:**
    ```
    Idempotenz: Alle DB-Writes sind via @Insert(onConflict = REPLACE) idempotent.
                Replay nach view-recreate ist sicher.
    Reihenfolge: State-First (Reducer) → Effect (DB-Write) — niemals umgekehrt.
                 Reducer hat Quelle-der-Wahrheit; DB ist Persistierungs-Mirror.
    Failure: DB-Failure → Action.PipelineAction.PersistenceError(sessionId, reason) zurück
             → pendingSessions-Slot bekommt failed-Marker (analog R.16 Recovery).
    Cleanup-Cutoff: now - 7d - 1h (Safety-Buffer für inflight-Operations).
    ```
  - **Spec 1 §4.3 Orchestrator + ServiceScope:** `CoroutineExceptionHandler` registrieren, der bei DB-Exceptions die `PersistenceError`-Action emittiert.
  - **Spec 1 §10 Block-2-Acceptance** ergänzen: „Replay nach view-recreate führt nicht zu Doppel-Insertion (DB-Idempotenz-Test)." + „DB-Crash mid-Save führt zu `pendingSessions`-failed-Marker, nicht zu State/DB-Inkonsistenz."
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.18 — Issue 3.1.5: Drag-Lifecycle-Cluster (L-3 Race + L-7 Detach + L-5 Threshold)

- **Origin:** `validated-findings-batch2.md`
- **Category:** [LOGIC], **Severity:** Important
- **Chosen option:** **Option A** (Drag-Hoheit + Persist-bei-Detach + threshold-Abstimmung)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Standard-Pattern:** `View.scaledTouchSlop`-Verwendung ist Android-Konvention für „User wollte wirklich draggen" (z.B. RecyclerView-ItemTouchHelper, ViewPager).
  - **Drei Lifecycle-Probleme** sind voneinander abhängig — gemeinsam zu lösen ist saubere Strategie. Robolectric-Tests pro Pfad ist Standard.
- **Implementation instruction:**
  - **Spec 3 §4.6 OverlayDragHandler-Interface:**
    ```kotlin
    class OverlayDragHandler(...) {
        fun isDragging(): Boolean = dragging  // NEU
        fun detach() {
            if (dragging) onPositionPersist?.invoke(currentX, currentY)  // NEU — Persist bei mid-drag-detach
            // ... bestehende Logik ...
        }
    }
    ```
  - **Spec 3 §4.3 OverlayBackend.applyPosition:** early-return wenn `dragHandler?.isDragging() == true`.
  - **Spec 3 §4.6 dragThresholdPx:** `max(8 * density, ViewConfiguration.get(context).scaledTouchSlop * 1.5f)`.
  - **Spec 3 §10 Acceptance** ergänzen: drei Robolectric-Tests (Race-Update-während-Drag; Cross-Module-Cascade-mid-Drag-detach; Accessibility-Mode-Touch-Slop).
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.19 — Issue 3.1.11: Spec 3 §4.3 + §4.7 — Anchor + ViewWidth-Helper

- **Origin:** `validated-findings-batch2.md`
- **Category:** [INTEGRATION] / [DRY], **Severity:** Important
- **Chosen option:** **Option A** (Factory direkt mit TOP|START + effectiveSize-Helper)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Single-Owner-Vertrag:** Initial-Position aus normalisiertem Default berechnen statt initialer TOP|END mit späterem Switch — das eliminiert das Initial-Frame-Problem strukturell.
  - **DRY:** `view.effectiveSize()`-Helper ersetzt zwei duplizierte Lookup-Sites.
  - **Hängt mit R.20 (3.1.6 Position-Mapping)** zusammen, also gemeinsam applizierbar.
- **Implementation instruction:**
  - **Spec 3 §4.3 OverlayLayoutFactory:**
    ```kotlin
    val params = WindowManager.LayoutParams(
        WRAP_CONTENT, WRAP_CONTENT, TYPE_APPLICATION_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START   // direkt — kein TOP|END-Initial mehr
        // initial-x/y aus normalisiertem Default + view.effectiveSize() berechnet, wenn view.measuredWidth > 0,
        // sonst applyPosition postponiert (siehe R.20 / 3.1.6 Option A early-return).
    }
    ```
  - **Spec 3 §4.7 Helper extrahieren:**
    ```kotlin
    fun View.effectiveSize(): Int? = when {
        width > 0 -> width
        measuredWidth > 0 -> measuredWidth
        else -> null
    }
    ```
    `normalizedToPixels` + `pixelsToNormalized` rufen diesen Helper.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.20 — Issue 3.1.12: Cross-Module-Coupling-Matrix

- **Origin:** `validated-findings-batch2.md`
- **Category:** [SOLID], **Severity:** Nice-to-have
- **Chosen option:** **Option A** (Coupling-Matrix in §15.1.x)
- **Justification:**
  - **Recommendation in Validated-Findings.**
  - **Matrix ist Doku-Erweiterung**, kein Code-Change am Modul-API. Sie macht einen impliziten Vertrag explizit, der ohnehin im Plan an verstreuten Stellen formuliert wird (`§15.3 AudioModule.onCrossModuleStateChange` etc.).
  - **SOLID-Audit-Argument-Stärke:** mit Matrix kann §13.3.13 / §15.6 strukturell argumentieren.
- **Implementation instruction:**
  - **Spec 1 §15.1.x neue Subsektion „Cross-Module-Coupling-Matrix":** 13×13-Tabelle, Spalten = Module, Zeilen = Module. Zellen:
    - Diagonalfelder: leer (Modul liest sich selbst).
    - `R(state.audio.audioFocusGranted)` = Modul liest diese Sub-State-Achse.
    - `C(Action.AudioAction.X)` = Modul emittiert diese Cascade-Action.
  - **Spec 1 §13.3.13** auf Matrix referenzieren: „SRP: jedes Modul hat nur Lese-Coupling auf Achsen, die in der Matrix dokumentiert sind."
  - Optional: `data class CrossReadSet(val reads: Set<KClass<*>>, val cascades: Set<KClass<out Action>>)` als Compile-Zeit-Struktur — aber Doku-Tabelle ist primärer Träger.
- **Status:** ✅ APPLIED (Research-Resolved)

---

### R.21 — Issue 3.1.13: Cross-Spec-DRY-Tabelle + predIsIdle-Helper

- **Origin:** `validated-findings-batch2.md`
- **Category:** [DRY], **Severity:** Important
- **Chosen option:** **Option A + B + C kombiniert**
- **Justification:**
  - **Recommendation in Validated-Findings explizit A+B+C.**
  - **Audit-Tabellen-Erweiterung** ist mechanisch — Symbol/Definition/Konsumenten ist Standard-DRY-Audit-Format.
  - **`predIsIdle`** und `predRecordingControlsVisible` als zentrale Helpers eliminieren Predicate-Body-Duplication; existiert ähnlich für `state.recording.isActiveOrPaused` (Extension-Property bereits in Plan §3 vorgesehen).
- **Implementation instruction:**
  - **Neue Datei `app/src/main/java/net/devemperor/dictate/state/Predicates.kt` (oder eingebettet in §3):**
    ```kotlin
    val DictateUiState.isIdle: Boolean
        get() = recording is RecordingState.Idle && pipeline is PipelineUiState.Idle

    val DictateUiState.predRecordingControlsVisible: Boolean
        get() = recording.isActiveOrPaused
    ```
  - **Spec 2 §8.5 + Spec 3 §3.1** rufen `state.isIdle` statt inline `recording is Idle && pipeline is Idle`.
  - **Spec 2 §13.4 + Spec 3 §13.X**: dritter Tabellen-Block „Cross-Spec-DRY":
    | Symbol | Definition | Konsumenten |
    |--------|------------|-------------|
    | `predResendVisible` | Spec 2 §8.5 | Spec 2 forKeyboard, Spec 3 OVERLAY_RESEND-Slot (falls existiert), §13.1 |
    | `state.isIdle` | Predicates.kt | Spec 2 §8.5 visibility, Spec 3 §3.1 OVERLAY_RECORD |
    | `OverlayPositionMapper` | Spec 3 §4.7 | Spec 3 §4.3 applyPosition + Spec 1 §6.X PrefMirror |
- **Status:** ✅ APPLIED (Research-Resolved)

---

## 🟡 Unresolvable / Needs-User-Decision Issues (23)

Die folgenden Issues bleiben echte Architektur-Entscheidungen mit Trade-offs, die der User abwägen muss. Pro Issue ist die Empfehlung aus den Validated-Findings dokumentiert + ggf. eine Tendenz nach Recherche.

### Cluster 1 — Spec-3-Module-Integration (architektur-blockierend)

#### Issue 1.1.2: Spec 3 §5/§6/§7 — direkte `_state.value.copy(...)` umgeht Single Dispatch
- **Why unresolvable:** Cross-Module-Verteilung der Cross-Achsen-Logik (welches Modul beobachtet wen?) ist die zentrale Architektur-Frage. Option A+B kombiniert ist plausibel, aber „welche Cascade lebt wo" hängt direkt mit 3.1.1 / 3.1.2 / 3.1.7 zusammen.
- **Research result:** Recherche bestätigt die Recommendation Option A+B. Die direkten `_state.value.copy(...)`-Stellen (Spec 3 §5.3 Z. 846/854/862/867 etc.) sind mechanisch identifizierbar — der Apply-Pfad braucht aber zuvor eine OwnerModul-Festlegung (3.1.1).
- **Recommendation:** **Option A + B kombiniert.** Mit Issue 3.1.1 zusammen entscheiden.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 1.1.3: Cross-Module-Effect-Modus 3 (Atomic Cross-Axis)
- **Why unresolvable:** Sec1-Logic L-8 (Pipeline-Done-Beispiel mit Auto-Enter) zeigt einen plausiblen Bedarf für Modus 3, aber Modus 2 (Cascade) ist „good-enough" für aktuell identifizierte Use-Cases. Die Wahl hängt am `predResendVisible`-Tick-Race: wenn das via Cascade gelöst wird, bleibt Modus 3 im Phase-2-Backlog.
- **Recommendation:** **Option B** (Modus 3 Phase 2). Bei R.7 (Block-1-Split) wird `predResendVisible`-Helper-Konsolidierung mit Modus 2 abgedeckt — wenn das funktioniert, bleibt Modus 3 unbenötigt.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.1: Spec 3 — OverlayModule-Spec-Heimat + Reducer/EffectHandler
- **Why unresolvable:** Trade-off A (Spec 3) vs. B (Spec 1 §15) vs. C (hybrid) ist eine Doku-Ownership-Frage. Recherche bestätigt: Spec 1 §15.1 Tabellenzeile 6 listet OverlayModule, aber kein Code; Spec 3 hat alle Domain-Konzepte (Position, Onboarding, UserPrefers, Permission), aber keinen Module-Code.
- **Recommendation:** **Option A** (Spec 3 als Heimat). Spec 1 bleibt Modul-Inventar-Index, Spec 3 Detail. Das passt zu Pattern „Modul-Inventar zentral, Modul-Details fachlich".
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.2: Spec 3 §7.1 ViewMode-FSM-Eigentum + T7-Cross-Module-Trigger
- **Why unresolvable:** Doppel-Eigentum-Risiko ViewModeModule (Spec 1) vs. §7.1 Code-Skizze (Spec 3). T7 (HOVER → KEYBOARD nach Pipeline-Done) als Cross-Module-Cascade ist klar derivable, aber die SSoT-Auflösung der FSM-Eigentum-Frage hängt am User.
- **Recherche-Tendenz:** **Option A** (Spec 1 §15 ViewModeModule kanonisch, Spec 3 §7.1 Doku) — nach Pattern „Code = Spec 1, Doku = Spec 3" konsistent mit Spec 1 §15.2 RecordingModule-Kanon.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.4: IME-Service-Death + Overlay-Owner-Architektur
- **Why unresolvable:** Option A (IME-Service-onDestroy → detach) vs. B (Overlay in eigenem Service) ist Architektur-Entscheidung mit Phase-2-Implikation. Option C (Hybrid: A jetzt, B später) ist pragmatisch.
- **Recherche-Tendenz:** **Option C** (Hybrid). Heute Service-onDestroy → detach (low footprint, eliminiert Window-Leak); Phase-2 prüfen, ob STANDALONE_OVERLAY-Use-Case Service-Migration verlangt.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.7: Spec 3 §6.2 — `closeOverlay`-Cascade + Suppress-Bit + Audio-File-Cleanup
- **Why unresolvable:** Suppress-Bit-Pattern (`suppressAutoOverlayUntilNextSession`) ist neue State-Achse — User-Decision. Audio-File / DB-`cancelled`-Cleanup-Vertrag verbunden mit 1.1.2.
- **Recherche-Tendenz:** **Option A** (Cascade + Suppress-Bit + Cleanup-Vertrag) — entspricht F-11-Architektur konsistent.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 2 — HOVER-Lifecycle (Critical)

#### Issue 3.1.3: Permission-Lifecycle als State-Achse
- **Why unresolvable:** Option A (`state.overlay.hasPermission`-Achse + Observer + Notification-Action) ist der konsequenteste Fix, hat aber substantielles Footprint (neue Action, neuer Observer, neue Notification-Action, Settings-Deep-Link). Option B (Notification-only-Fallback) ist minimaler, lässt aber HOVER-Edge-Case bestehen.
- **Recherche-Tendenz:** **Option A.** F-11-konform (Hardware-Status als State-Achse) und löst Performance-Issue + Recovery-Pfad-Lücke + HOVER-Edge-Case in einem Pass. Pattern existiert für `audioFocusGranted` (Spec 1 §15.3 AudioModule.onCrossModuleStateChange).
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 3 — Reentrancy / Effect-Failure (Critical)

#### Issue 2.1.3: `runEffect` Failure-Vertrag
- **Why unresolvable:** Option D (try/catch + EffectFailure-Action + LocalBinder-Top-Level-Schutz) ist robust, aber introduces neue Action-Class + Pattern. Option C (Doku-Vertrag) ist minimal, aber riskant (IME-Crash bei nicht-konformer Effect-Implementation).
- **Recherche-Tendenz:** **Option D** (kombiniert). MVI-Standard hat einen typisierten Failure-Channel; ohne ihn würde 2.1.21 (PersistenceError-Action) als Sonderfall stehen statt als Pattern-Anwendung.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 2.1.4: Orchestrator-Reentrancy-Vertrag
- **Why unresolvable:** Option A (async-via-scope) vs. B (sync-Queue) ist Standard-MVI-Frage. Option A ist Compose/Redux-Toolkit-Konvention, Option B ist verbreitet bei Orbit-MVI 4.x. Beide sind etabliert.
- **Recherche-Tendenz:** **Option A** — leichter testbar, eliminiert Threading-Race ohne explizite Locks. KDoc in 2.0.7 nimmt das bereits vorgreifend an.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 4 — Cross-Module-Invariants

#### Issue 2.1.8: Cross-Module-State-Invarianten
- **Why unresolvable:** Option A (Invariants-Subsection + Store-Level-Check) ist generisch, Option C (`Paused.useBluetooth`) ist isoliert für L-10. Recherche zeigt: Standard-MVI-Pattern hat Invariants in der `update`-Funktion, aber das ist hier Erstanwendung.
- **Recherche-Tendenz:** **Option C als Sofort-Fix + Option A als Pattern-Etablierung.** Wie in Recommendation.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 5 — IME-Service-Death (cross-cluster)

#### Issue 2.1.9: IME-Service-Death während aktiver Pipeline
- **Why unresolvable:** Option C (Clipboard + persistenter pending-Marker) hat höchsten User-Value, aber neue State-Achse (`lastResultNeedsManualPaste`).
- **Recherche-Tendenz:** **Option C.** Gemeinsam mit 3.1.4 zu lösen — beide adressieren IME-Service-Death-Pfade.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 6 — Service-Cleanup

#### Issue 2.1.12: Service-Shutdown-Cleanup
- **Why unresolvable:** Option A+B kombiniert (terminale Cleanup-Sequenz + onDestroy mit runBlocking-Timeout) ist robust, aber neue Module-API (`terminate-Effect`).
- **Recherche-Tendenz:** **Option A + B kombiniert.** Wie Recommendation.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 7 — Reducer-Context (cluster mit R.2)

#### Issue 2.1.1: `ReducerContext` Surface-Design
- **Why unresolvable:** Option A (`global: DictateUiState`) ist symmetrisch zu `onCrossModuleStateChange(prev, next)`, aber ISP-laxer. Option B (per-Modul-Context) ist ISP-strict, aber 13× Boilerplate.
- **Recherche-Tendenz:** **Option A.** Wenn R.2 (audioFile in State) appliziert wird, schrumpft `ReducerContext` auf `audio + now` und kann direkt zu `global + now` werden — minimal-invasiv.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 2.1.2: PipelinePrefMirror — deklarative Pref-Bindings
- **Why unresolvable:** Option A (Modul-API erweitert um `prefBindings()`) ist OCP-stark, aber neue Modul-API. Option B (zentrale Map) ist pragmatisch.
- **Recherche-Tendenz:** **Option A.** Konsistent mit Modul-Pattern (jedes Modul ist autonom).
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 8 — Spec-2 LayoutModule-Integration

#### Issue 2.1.15: KeyboardLayoutManager ↔ Spec-1-LayoutModule
- **Why unresolvable:** Option A+B kombiniert (Beziehungs-Section + ContentAreaController als zweites Backend) — eng mit R.10 (KSM-Aufspaltung) verwoben. Recherche bestätigt: ContentAreaController ist als zweites RenderBackend modellierbar (siehe Spec 2 §5 RenderBackend-Interface).
- **Recherche-Tendenz:** **Option A + B kombiniert.** Apply gemeinsam mit R.10.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 9 — Spec-3-Sonstige

#### Issue 3.1.6: Position-Mapping vor Layout-Pass + Multi-Display
- **Why unresolvable:** Option A (early-return + Aspect-Bucket-Persist) erweitert Pref-Schema substantiell. Option B (initial-LayoutParams-Factory) löst nur L-6, nicht L-13.
- **Recherche-Tendenz:** **Option A.** Multi-Display ist real (Foldables stetig wachsend), Aspect-Bucket-Persist ist Standard-Lösung.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.8: WIDGET-autark vs. T4-HOVER-disabled
- **Why unresolvable:** Option A (Doku-Klärung) vs. B (STANDALONE_OVERLAY Phase-2). Beide sind valide; A ist sofort.
- **Recherche-Tendenz:** **Option A + C.** Option B als Phase-2-Backlog.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.9: GAP-5 userPrefersWidget-Persistierung — Acceptance-Test
- **Why unresolvable:** Option A (Acceptance-Test bekräftigt „bewusste Eigenschaft") vs. B (Verhalten ändern: reset-on-auto-close). Beide sind UX-Entscheidung.
- **Recherche-Tendenz:** **Option A.** UI-Konsistenz: User-Wahl bleibt persistent über Modus-Auto-Wechsel — das ist Android-Convention.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

#### Issue 3.1.10: OnClickListener-Pattern (Closure vs. stateRef)
- **Why unresolvable:** Option A (Spec-2-Pattern: stateRef einmaliger Listener) eliminiert Single-Frame-Race + Performance-Gewinn. Aber Spec-3-Drag-Routing-Argument hat Substanz.
- **Recherche-Tendenz:** **Option A.** Konsistenz schlägt Mikro-Optimierung; Drag-Routing-Konflikt-Begründung hält dem Test „Drag wird auf Root-View gefangen, nicht auf Buttons" nicht stand.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

### Cluster 10 — Plan-Hygiene

#### Issue 3.1.15: Plan-Body-PENDING-Marker + Out-of-Scope + Iter-Log
- **Why unresolvable:** Option A (Marker + Out-of-Scope-Sektion + Iter-Log-Eintrag) ist pragmatischer Mittelweg, aber Footprint substantieller als nur Iter-Log (B).
- **Recherche-Tendenz:** **Option A.** Implementer-Erfahrung profitiert massiv von Plan-Body-Markern; Out-of-Scope-Sektion verhindert Frage-Zyklen. Hat User-Aufwand 1× beim Apply, dann ist es ständiges Investment.
- **User decision:** ✅ APPLIED (Research-Tendenz / Recommendation appliziert)

---

## Research Context

### Patterns Found

1. **MVI Standard-Patterns (Compose-MVI, Orbit, Mavericks):**
   - `nullable resolver` für „Action did nothing" — `(State) -> Action?`. Verbreitet in Mavericks, Orbit-Compose.
   - `DispatchOutcome` als sealed-class für typed Test-Assertion. Standard in Orbit-MVI Test-DSL.
   - Async-via-scope `emitAction` (`scope.launch { dispatch(it) }`) — Compose-MVI / Redux-Toolkit-Konvention.
   - Cascade-Tiefen-Counter — Standard in Redux-Middleware (`redux-thunk`, `recoil-batch`).

2. **kotlinx.collections.immutable:**
   - `PersistentList.add(x)` preserves structural sharing. Bereits richtig in 2.0.5 dokumentiert.

3. **Excel-EKL Plugin-Module-Augmentation (referenziert in Spec 1 §4):**
   - Modul-Self-Registration via Konstruktor-Default + Factory-Pattern (siehe Spec 1 §4.7).
   - `prefBindings()` als optionale Modul-API ist konsistent mit dem Plugin-Pattern.

4. **Android IME-Lifecycle (`DictateInputMethodService.java`):**
   - `onCreateInputView` / `onFinishInputView` sind die Lifecycle-Anker für viewScope-Erzeugung/Cancel.
   - `cleanupOldControllers()` ist heutiges Mechanik-Pattern, dass via viewScope.cancel() abstrahierbar ist.
   - `ActiveJobRegistryObserver.kt` ist das Java-Brücken-Pattern (bereits in 1.0.4 als Vorbild markiert).

5. **Existing Codebase-Pointers für Hardware-Read-Migration:**
   - `RecordingManager.kt:46` `currentAudioFile: File?` — Source der heutigen Hardware-Read-Logik. Refactor verschiebt das nach `RecordingState.{Active|Preparing|Paused}.audioFile`.

### Documentation Consulted

- `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md`
  - §3 (DictateUiState-Layout)
  - §4.2 (ReducerContext)
  - §4.3 (DictateOrchestrator — Single Dispatch)
  - §4.5 (PipelinePrefMirror)
  - §4.7 (ModuleServices)
  - §15.1 (Modul-Inventar)
  - §15.2 (RecordingModule — kanonische Beispiel-Implementation)
  - §15.5 (Cross-Module-Effect-Modi)
  - §15.6 (SOLID-Verifikation)
- `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md`
  - §5 (RenderBackend-Interface)
  - §11.X (Migration-Tabellen)
  - §13 (DRY-Audit)
- `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md`
  - §3.1 (LayoutMode OVERLAY_5BUTTON)
  - §6 (Schließen-Button-Differential)
  - §7.1 (Triangle-FSM)
  - §11.6 (Window-Lifecycle)
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/`
  - `core/RecordingManager.kt` (Hardware-Adapter — currentAudioFile)
  - `core/ActiveJobRegistryObserver.kt` (Java-Brücken-Pattern)
  - `core/DictateInputMethodService.java` (IME-Lifecycle-Anker)
  - `core/JobExecutor.kt` (PipelineRunner-Vorbild)

### Apply-Order-Hinweise für den Apply-Agent

Resolutions sind nicht alle unabhängig. Empfohlene Reihenfolge:

1. **R.5** (LayoutModule-State-Container) — erstmal die Sub-State-Konsolidierung
2. **R.2** (audioFile in State) — schrumpft ReducerContext
3. **R.3** (NoOp-Removal + DispatchOutcome) — Voraussetzung für R.4
4. **R.4** (sealed-leaves-Indexing) — nach R.3
5. **R.6** (Cascade Loop-Guard) — nach R.4 (depth-counter im sealed-leaves-`dispatch`)
6. **R.7** (Block-1-Split) — Plan-Struktur, parallel zu allem anderen
7. **R.1** (Naming-Drift-Verifikation) — letzter Pass über alle Specs
8. **R.8–R.21** in beliebiger Reihenfolge (unabhängig)

🟡 NEEDS-USER-DECISION-Issues bleiben bis zum User-Checkpoint unangetastet.
