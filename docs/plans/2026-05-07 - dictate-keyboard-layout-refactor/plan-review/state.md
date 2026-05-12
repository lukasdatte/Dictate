# Plan Review State: dictate-keyboard-layout-refactor

**Plan:** [→ dictate-keyboard-layout-refactor.md](../dictate-keyboard-layout-refactor.md)
**Reviewed plan:** [→ dictate-keyboard-layout-refactor.reviewed.md](../dictate-keyboard-layout-refactor.reviewed.md)
**Mode:** Autonomous (Deep Check + Auto-Apply)
**Started:** 2026-05-10
**Plan-Repo:** /home/lukas/WebStorm/Dictate (Branch: feature/language-chip-curation) — Plan + Code im selben Repo seit 2026-05-12
**Code-Repo:** /home/lukas/WebStorm/Dictate (Branch: feature/language-chip-curation)
**Snapshot-Commit (Dictate, später revertiert):** 68d912b
**Migration:**
- 2026-05-10: Plan war initial im Dictate-Repo, wurde ins Docs-Repo migriert (Naming-Konvention Projekt-Präfix `dictate-`)
- 2026-05-12: Plan wurde zurück ins Dictate-Repo migriert (Konsolidierung Plan+Code in einem Repo)
- Docs-Repo-Commit-Audit-Trail: `e1cbfd7` (Import), `f1dfe54` (KGs added), `39ff8eb` (KGs resolved)

---

## Modulare Plan-Struktur

Dieser Plan ist modular. Die Phase-1- und Phase-2-Agents müssen ALLE vier Plan-Dateien als
zusammenhängendes Plan-Dokument behandeln. Die `.reviewed.md`-Kopien sind die Edit-Targets.

| # | Rolle | Original | Reviewed | Zeilen |
|---|-------|----------|----------|-------:|
| 0 | Hauptplan (Index, Akzeptanz, Iteration-Log) | `dictate-keyboard-layout-refactor.md` | `dictate-keyboard-layout-refactor.reviewed.md` |  450 |
| 1 | Spec: Pipeline / Orchestrator / DictateUiState / Module | `research/1-pipeline-service/1-pipeline-service.md` | …`/1-pipeline-service.reviewed.md` | 2491 |
| 2 | Spec: Keyboard-Layout / Action-Hierarchie / Visibility | `research/2-keyboard-layout/2-keyboard-layout.md` | …`/2-keyboard-layout.reviewed.md` | 2104 |
| 3 | Spec: Floating-Overlay | `research/3-floating-overlay/3-floating-overlay.md` | …`/3-floating-overlay.reviewed.md` | 1948 |

**Gesamt:** 6993 Zeilen.

**Code-Pfade für Cross-Reference (alle relativ zu `/home/lukas/WebStorm/Dictate/`):**
- `app/src/main/java/net/devemperor/dictate/core/` (State-Klassen, Controller, IME-Service)
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml`

---

## Workflow progress

| # | Step | Status | Completed |
|---|------|--------|-----------|
| 1 | Input validation + Setup + Migration ins Docs-Repo | ✅ | 2026-05-10 |
| 2 | Phase 1: Agents (Pattern + Architecture Scout) | ✅ | 2026-05-10 |
| 3 | Phase 1: Sanity Check | ⏳ | – |
| 4 | Phase 1: Apply 🟢 (auto-fix) + Verify | ⏳ | – |
| 5 | Phase 2: Section identification + batch split | ⏳ | – |
| 6 | Phase 2: Batches (Structure + Logic Reviewer) | ⏳ | – |
| 7 | Phase 2: Sanity Check + Apply pro Batch | ⏳ | – |
| 8 | Research Step (🟡-Issues autonom auflösen) | ⏳ | – |
| 9 | Final Report + User Checkpoint | ⏳ | – |
| 10 | Apply Remaining + Final Summary | ⏳ | – |

## Phase 1 Results (Summary)

### Pattern-Scout (5 🟡 + 4 🟢)

- **G1 🟡** — Action-Hierarchie nur in Spec 2 §3.3 + Spec 1 §15 propagiert; ~50 Stellen in Spec 2 §6+§8 + ALLEN Spec-3-Action-Refs nutzen alte flache Namen.
- **G2 🟡** — `PipelineStateManager`-Naming-Drift trotz F-11-Rename: 57 Refs in Spec 1, 10 in Spec 3, 1 im Hauptplan-Diagramm.
- **G3 🟡** — Cross-Module-Effect-Modus 3 (Atomic Cross-Axis) deklariert, aber im `dispatch()`-Code §4.3 nicht verdrahtet.
- **G4 🟡** — `Action.NoOp` konkurriert mit `null`-Reducer-Return als zwei parallele „ungültig"-Mechanismen.
- **G5 🟡** — Existierende Klassen (`LanguageController`, `RecordingManager`, `BluetoothScoManager`, `JobExecutor`) im Spec-1-§9-Migrations-Inventar nur teilweise gelistet.
- **G6 🟢** — Java-Brücke für `DictateUiStateStore` analog `ActiveJobRegistryObserver` fehlt.
- **G7 🟢** — „FSM-Owner"-Wording zweimal vergeben.
- **G8 🟢** — `DictateOrchestrator.initialize`-Test-Seam-Symmetrie zu `JobExecutor` fehlt.
- **G10 🟢** — Hauptplan-§3.2-Diagramm zeigt noch alten `PipelineStateManager`.

### Architecture-Scout (4 Critical + 5 Important)

- **AI-1 ❌Critical** — Spec 2 §8.5 + Spec 3 nutzen flache State-Pfade (`state.lastAudioExists`, …) — Code kompiliert nicht gegen Spec-1-§3-Sub-State-Klassen.
- **AI-2 ❌Critical** — Action-API-Drift hierarchisch ↔ flach in Snippets; `target: InsertionTarget`-Pflicht-Parameter fehlt.
- **AI-3 ❌Critical** — Spec 3 §5/§6/§7 zeigen direkte `_state.value.copy(...)` — bricht F-8 Single Dispatch + F-11 Modul-Reducer.
- **AI-4 ❌Critical** — Hauptplan §3.2: alter `PipelineStateManager`; §3.3: `OVERLAY_4BUTTON` (sollte `_5BUTTON`) + nicht-existente `LogicalButtonId`-Enums.
- **AI-5 🟡** — `LayoutModule` aggregiert vier disjunkte Achsen — SRP-Smell.
- **AI-6 🟡** — Block-1-Aufwand massiv unterschätzt (post-F-11).
- **AI-7 🟡** — F-11 löst „Block 1 vor Block 2"-Garantie auf — Block 1 sollte 1a/1b gesplittet werden.
- **AI-8 🟡** — Cross-Module-Cascade via rekursivem `dispatch()` — kein Loop-Schutz.
- **AI-9 🟡** — `buildContext` ruft `recordingHardware.currentAudioFile()` synchron im Reducer-Pfad — verletzt Pure-Function-Invariante.

### Section-Vorschlag für Phase 2 (5 Sektionen)

1. **State-Modell + Modul-System** (Spec 1 §3-§5 + §15)
2. **Service-Layer + Persistence + Lifecycle** (Spec 1 §6-§9 + §11)
3. **Keyboard-Layout-Renderer** (Spec 2 vollständig, ohne §13)
4. **Floating-Overlay** (Spec 3 vollständig, ohne §13)
5. **Cross-Cutting Konsistenz + alle §13-Verifikationen + Hauptplan + Acceptance/Tests**

Split priorisiert Cross-Spec-Konsistenz: UI-Sektionen [3]+[4] prüfen wörtlich gegen State+Action-SoT in [1], [5] schließt die Verifikations-Schleife.

## Validated Findings

| Datei | Phase | 🟢 | 🟡 | ❌ |
|-------|-------|----|----|----|
| validated-findings-phase1.md | Phase 1 | – | – | – |

## Phase 2 Batches

*(Wird nach Section-Identifizierung befüllt — voraussichtlich 2 Batches: Batch 1 = Sections 1-3 (6 Agents), Batch 2 = Sections 4-5 (4 Agents))*
