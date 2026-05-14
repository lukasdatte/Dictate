# Plan Review State: dictate-keyboard-layout-refactor

**Plan:** [→ dictate-keyboard-layout-refactor.md](../dictate-keyboard-layout-refactor.md)
**Reviewed plan:** [→ dictate-keyboard-layout-refactor.reviewed.md](../dictate-keyboard-layout-refactor.reviewed.md)
**Mode:** Autonomous (Deep Check + Auto-Apply)
**Started:** 2026-05-10
**Abgeschlossen:** 2026-05-14 (Phase A + B + C inklusive C-State-Wrap-up)
**Plan-Repo:** /home/lukas/WebStorm/Dictate (Branch: feature/language-chip-curation) — Plan + Code im selben Repo seit 2026-05-12
**Code-Repo:** /home/lukas/WebStorm/Dictate (Branch: feature/language-chip-curation)
**Snapshot-Commit (Dictate, später revertiert):** 68d912b
**Migration:**
- 2026-05-10: Plan war initial im Dictate-Repo, wurde ins Docs-Repo migriert (Naming-Konvention Projekt-Präfix `dictate-`)
- 2026-05-12: Plan wurde zurück ins Dictate-Repo migriert (Konsolidierung Plan+Code in einem Repo)
- Docs-Repo-Commit-Audit-Trail: `e1cbfd7` (Import), `f1dfe54` (KGs added), `39ff8eb` (KGs resolved)

---

## Modulare Plan-Struktur

Dieser Plan ist modular. Phase-B- und Phase-C-Agents behandeln ALLE vier Plan-Dateien als
zusammenhängendes Plan-Dokument. Die `.reviewed.md`-Kopien sind die Edit-Targets.

| # | Rolle | Original | Reviewed | Zeilen |
|---|-------|----------|----------|-------:|
| 0 | Hauptplan (Index, Akzeptanz, Iteration-Log) | `dictate-keyboard-layout-refactor.md` | `dictate-keyboard-layout-refactor.reviewed.md` |  450 |
| 1 | Spec: Pipeline / Orchestrator / DictateUiState / Module | `research/1-pipeline-service/1-pipeline-service.md` | …`/1-pipeline-service.reviewed.md` | 2491 |
| 2 | Spec: Keyboard-Layout / Action-Hierarchie / Visibility | `research/2-keyboard-layout/2-keyboard-layout.md` | …`/2-keyboard-layout.reviewed.md` | 2104 |
| 3 | Spec: Floating-Overlay | `research/3-floating-overlay/3-floating-overlay.md` | …`/3-floating-overlay.reviewed.md` | 1948 |

**Gesamt:** 6993 Zeilen (Pre-Review). Nach allen Phase-B + Phase-C-Apply-Pässen ist Spec 1 auf
~3500 Zeilen, Spec 2 + Spec 3 leicht angewachsen — siehe `git log` für die Volume-Trajektorie.

**Code-Pfade für Cross-Reference (alle relativ zu `/home/lukas/WebStorm/Dictate/`):**
- `app/src/main/java/net/devemperor/dictate/core/` (State-Klassen, Controller, IME-Service)
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml`

---

## Workflow

Der Workflow wurde während der Durchführung gegenüber der ursprünglichen Phase-1/Phase-2-Architektur
umstrukturiert auf eine **Phase-A → Phase-B → Phase-C-Achse**:

- **Phase A** = Subsystem-Inventur (was muss überhaupt reviewed werden)
- **Phase B** = Vertikale Subsystem-Reviews (1 Subsystem pro Agent, Code+Plan-Drift-Achse)
- **Phase C** = Horizontale Plan-Bereichs-Reviews (1 Plan-Bereich pro Agent, reine Plan-Innenlogik-Achse)

Die ursprüngliche Phase-1/Phase-2-Sektion am Ende dieses Files (Pattern-Scout + Architecture-Scout)
ist historischer Kontext — die dort identifizierten Cluster-Themen wurden in Phase B + C strukturiert
abgearbeitet (siehe Cross-Referenz-Tabelle unten).

### Workflow progress

| # | Phase | Status | Completed | Commit |
|---|-------|--------|-----------|--------|
| A | Subsystem-Inventur | ✅ | 2026-05-13 | (im Phase-B-S-1-Commit-Range) |
| B-1 | S-1 State-Hierarchie | ✅ | 2026-05-13 | (Pre-S-5; in History) |
| B-2 | S-2 DB-Schema-Migration | ✅ | 2026-05-13 | (Pre-S-5; in History) |
| B-3 | S-3 Action-Hierarchie | ✅ | 2026-05-13 | (Pre-S-5; in History) |
| B-4 | S-4 Orchestrator | ✅ | 2026-05-13 | (Pre-S-5; in History) |
| B-5 | S-5 Service-Layer + FGS-Lifecycle | ✅ | 2026-05-13 | `f34e484` |
| B-6 | S-6 Keyboard-Renderer | ✅ | 2026-05-13 | `fcc72ec` |
| B-7 | S-7 Audio-File-Management | ✅ | 2026-05-13 | `2b27cf9` |
| B-8 | S-8 Floating-Overlay | ✅ | 2026-05-13 | `dca8cc4` |
| B-9 | S-9 Suppress-Bit-Lifecycle | ✅ | 2026-05-13 | `e418b87` |
| C-1 | State-Modell + Modul-System | ✅ | 2026-05-14 | `2a032e3` |
| C-2 | Service-Layer + Persistence + Lifecycle | ✅ | 2026-05-14 | `abf18e7` |
| C-3 | Action-Hierarchie + Dispatch + EffectFailure | ✅ | 2026-05-14 | `fe65351` |
| C-4 | Layout + View-Rendering (Spec 2) | ✅ | 2026-05-14 | `c9c0273` |
| C-5 | Floating-Overlay (Spec 3) | ✅ | 2026-05-14 | `0f103cb` |
| C-State | Wrap-up + state.md-Update | ✅ | 2026-05-14 | (this commit) |

### Phase-C Findings-Summary

| Phase | Critical | Important | Minor | Plan-Edits |
|-------|---------:|----------:|------:|-----------:|
| C-1 State + Module | 2 | 4 | 3 | 15 |
| C-2 Service + Persistence + Lifecycle | 3 | 4 | 1 | 11 |
| C-3 Action-Hierarchie + Dispatch | 3 | 4 | 1 | 11 |
| C-4 Layout + View-Rendering | 1 | 5 | 0 | (siehe Report) |
| C-5 Floating-Overlay | 3 | 3 | 1 | (siehe Report) |
| **Σ Phase-C** | **12** | **20** | **6** | — |

Alle Phase-C-Reports leben unter `../quality-gate/phase-c{1..5}-*.md`. Der Phase-B-Inventory-Report
liegt unter `../quality-gate/phase-a-subsystem-inventory.md`; die neun S-x-Reports unter
`../quality-gate/phase-b-s{1..9}-*.md`.

---

## Offene Punkte für Block-6-Implementer

Aus den Phase-C-Reports an die Implementer-Phase weitergereichte Themen (keine Plan-Edits mehr nötig,
sondern Block-6-Design-Entscheidungen):

### Aus C-5 F-3

- **`onboardingPending = true`-Trigger-Pfad** (§5.0 OverlayPermissionObserver): der Implementer
  entscheidet, ob das Onboarding-Flag eager (beim ersten WIDGET-Toggle ohne Permission) oder lazy
  (beim ersten HOVER-Auto-Trigger ohne Permission) gesetzt wird. Acceptance-Bullet im §10 erwägen.

### Aus C-1 F-6 + C-5

- **DispatchOutcome-Telemetry-Backlog** (Phase-2): jede `pipeline?.dispatch(...)`-Site im
  Onboarding-/Permission-Flow von Spec 3 §5.0 + §5.2 + IME-View-Lifecycle in Spec 1 §5 muss vor
  Block-2-Acceptance gegen `Unrouted` abgesichert werden. Aktuell als "Phase-2-Backlog" markiert;
  evtl. Lint-Check für `unused-DispatchOutcome` als Acceptance-Klausel ergänzen.

### Aus C-3 F-3 (resolved post-C-5)

- **`Effect.ReleaseMediaRecorder`-Pfad** im `RecordingModule.runEffect`: das `Effect.ReleaseRecording`
  aus §13.5 G6 Pfad A wurde in C-3 auf `Effect.ReleaseMediaRecorder` umgestellt (kanonisches
  Effect-Naming in §15.2). Spec 3 §6.1-T-Tabellen verwenden konsistent `CancelRecording` als
  Action-Variante. Block-6-Implementer muss den finalen `runEffect`-Snippet aus §15.2 als SoT nehmen.

---

## Cross-Spec-Strukturkonsistenz (post-C-State-Verifikation)

| Strukturpunkt | Spec 2 | Spec 3 | Konsistenz |
|---|---|---|---|
| `LayoutCatalog.OVERLAY_5BUTTON` (Catalog-Member, nicht top-level) | §8.6 Skelett-Anker ✅ | §3.1 `object … : LayoutMode(...)` innerhalb `LayoutCatalog`-Object ✅ | ✅ aligned post C-4 F-5 + C-5 F-1 |
| `Action.RecordingAction.CancelRecording` (statt `CancelPipeline` für MediaRecorder-Release) | (kein direkter Ref) | §6.1 + §7.3 T-Tabellen ✅ | ✅ aligned post C-3 F-1 |
| Sub-State-Klassen-Reads via `state.<axis>.<field>` (statt flache Pfade) | §6/§8 ✅ post C-4 | §5/§6/§7 ✅ post C-5 F-2 | ✅ aligned post C-1 AI-1 + C-4 + C-5 |
| `wireStaticOverlayHandlers` Click-Listener `?.let { onAction?.invoke(it) }`-Pattern (DRY mit Spec 2 §6) | §6 SoT ✅ | §4.2 ✅ post C-5 | ✅ aligned post C-3 + C-5 |

---

## Phase 1 Historic Context (initial Pattern-/Architecture-Scout)

> [!NOTE]
> Diese Sektion ist Pre-Phase-B-Snapshot. Die Themen sind in Phase B + C strukturiert abgearbeitet.
> Erhalten als Audit-Trail.

### Pattern-Scout (5 🟡 + 4 🟢)

- **G1 🟡** — Action-Hierarchie nur in Spec 2 §3.3 + Spec 1 §15 propagiert; ~50 Stellen in Spec 2 §6+§8 + ALLEN Spec-3-Action-Refs nutzen alte flache Namen. → **abgearbeitet in C-3 + C-4 + C-5**.
- **G2 🟡** — `PipelineStateManager`-Naming-Drift trotz F-11-Rename: 57 Refs in Spec 1, 10 in Spec 3, 1 im Hauptplan-Diagramm. → **abgearbeitet in Phase-B S-1 + C-1 + C-2 (Echo) + C-4 (LocalBinder-`pipelineService`→`pipeline`-Feld 3. Generation)**.
- **G3 🟡** — Cross-Module-Effect-Modus 3 (Atomic Cross-Axis) deklariert, aber im `dispatch()`-Code §4.3 nicht verdrahtet. → **abgearbeitet in Phase-B S-3 + S-4 (Mode-Tabelle) + C-3 (Cascade-Pfad-Verifikation)**.
- **G4 🟡** — `Action.NoOp` konkurriert mit `null`-Reducer-Return als zwei parallele „ungültig"-Mechanismen. → **abgearbeitet in C-3**.
- **G5 🟡** — Existierende Klassen (`LanguageController`, `RecordingManager`, `BluetoothScoManager`, `JobExecutor`) im Spec-1-§9-Migrations-Inventar nur teilweise gelistet. → **abgearbeitet in Phase-A + Phase-B S-5**.
- **G6 🟢** — Java-Brücke für `DictateUiStateStore` analog `ActiveJobRegistryObserver`. → **abgearbeitet in Phase-B S-1**.
- **G7 🟢** — „FSM-Owner"-Wording zweimal vergeben. → **abgearbeitet in Phase-B S-1 + C-1**.
- **G8 🟢** — `DictateOrchestrator.initialize`-Test-Seam-Symmetrie zu `JobExecutor`. → **abgearbeitet in Phase-B S-4**.
- **G10 🟢** — Hauptplan-§3.2-Diagramm zeigt noch alten `PipelineStateManager`. → **abgearbeitet in C-1**.

### Architecture-Scout (4 Critical + 5 Important)

- **AI-1 ❌Critical** — Spec 2 §8.5 + Spec 3 nutzen flache State-Pfade — Code kompiliert nicht gegen Spec-1-§3-Sub-State-Klassen. → **abgearbeitet in C-4 + C-5 F-2 (ViewModeModule-Reducer-API-Cluster) + Test-Snippets in C-4 F-1**.
- **AI-2 ❌Critical** — Action-API-Drift hierarchisch ↔ flach; `target: InsertionTarget`-Pflicht-Parameter. → **abgearbeitet in C-3**.
- **AI-3 ❌Critical** — Spec 3 §5/§6/§7 zeigen direkte `_state.value.copy(...)`. → **abgearbeitet in Phase-B S-8 + C-5**.
- **AI-4 ❌Critical** — Hauptplan §3.2 / §3.3 Drift. → **abgearbeitet in C-1 F-2 + C-4**.
- **AI-5 🟡** — `LayoutModule` SRP-Smell. → **bewusst akzeptiert (Plan §13.3.12)**.
- **AI-6 🟡** — Block-1-Aufwand-Unterschätzung. → **außerhalb Review-Scope (Aufwands-Schätzung ist Projekt-Management, nicht Plan-Korrektheit)**.
- **AI-7 🟡** — Block-1 1a/1b-Split. → **abgearbeitet in Phase-B S-1 (Block-1b-Sektion separiert)**.
- **AI-8 🟡** — Rekursiver `dispatch()` Loop-Schutz. → **abgearbeitet in Phase-B S-4 (MAX_CASCADE_DEPTH) + C-1 (ASCII-Box-Banner) + C-3**.
- **AI-9 🟡** — `buildContext` ruft Hardware sync im Reducer-Pfad. → **abgearbeitet in Phase-B S-4 + S-7 (audioFile-Vertrag) + C-3**.

### Section-Vorschlag für Phase 2 (5 Sektionen) — historisch

Der Section-Vorschlag aus Phase 1 wurde durch die Phase-A/B/C-Reorganisation ersetzt. Cross-Mapping:

| Phase-1-Sektion | Tatsächlich abgearbeitet in |
|---|---|
| 1. State-Modell + Modul-System | Phase-B S-1 + S-3 + S-4 + **C-1** |
| 2. Service-Layer + Persistence + Lifecycle | Phase-B S-2 + S-5 + S-7 + S-9 + **C-2** |
| 3. Keyboard-Layout-Renderer | Phase-B S-6 + **C-4** |
| 4. Floating-Overlay | Phase-B S-8 + **C-5** |
| 5. Cross-Cutting Konsistenz + §13-Verifikationen + Hauptplan + Acceptance/Tests | über alle Phase-B + Phase-C-Reports verteilt (insb. C-1 F-3/F-4 + C-3 F-7 + C-5 F-3) |

---

## Status: Plan-Review COMPLETE

Phase A + B + C sind abgeschlossen. Der Plan ist für Block-6-Implementer-Phase reif. Verbleibende
Punkte (siehe "Offene Punkte für Block-6-Implementer" oben) sind Design-Entscheidungen, keine
Plan-Korrektheits-Issues.

Nächste Schritte (außerhalb Plan-Review-Scope):
1. Plan-Archivierung (sobald Block-6 startet — `~/.claude/snippets/docs/lifecycle-plan.md`)
2. EN-Übersetzung des Plans (post-Implementierung — siehe Lifecycle-Plan-Snippet)
3. Block-1a-Implementierung kann starten (laut §11.2.1 Reihenfolge)
