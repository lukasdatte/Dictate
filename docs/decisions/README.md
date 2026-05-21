# Architecture Decision Records (ADRs)

This directory holds the project's Architecture Decision Records — the
durable record of non-trivial technical decisions and their reasoning.
ADRs are read by future maintainers who need to know **why** the code
is shaped the way it is, without access to the conversation that
produced the decision.

## When to consult ADRs

Before designing a feature, refactor, or migration that touches a
subsystem listed below, **read the relevant ADRs end-to-end**. The
decisions in this directory are binding constraints — a plan that
silently re-litigates a settled decision will be rejected at review,
and an implementation that contradicts an Accepted ADR is a
correctness defect.

Minimum check:

1. Scan the index table below.
2. For every subsystem your plan touches, read all ADRs with a
   matching `Subsystem:` header plus every `Scope: Project-Wide`
   ADR.
3. If your plan **contradicts** an Accepted ADR, either align or
   write a new ADR with `Status: Supersedes ADR-NNNN`. Do not
   deviate silently.
4. If your plan **implements** an Accepted ADR's decision, reference
   the ADR in the plan's `## References`. The link is bidirectional.

## When to write a new ADR

Create an ADR when a plan or working session produces:

- A **technology or library choice** that other code will have to live with.
- An **architectural pattern adopted** (e.g. modular orchestrator,
  Triangle-FSM).
- A **trade-off resolved by measurement, testing, or design iteration**.
- A **cross-cutting convention** that applies project-wide.
- A **reversal** of a previous decision (creates a new ADR that
  supersedes the old one).

Do not write an ADR for routine configuration changes or bug fixes
unless they encode a new constraint.

**Format:** ADRs follow the structure in
`~/.claude/skills/knowledge-adr-format/SKILL.md` — Research →
Context → Decision → Alternatives → Consequences (Positive /
Negative / Failure Modes) → References → Decision History.
Use the template at `~/.claude/templates/adr.md`.

## Lifecycle

`Proposed` → `Accepted` → optionally `Superseded by ADR-MMMM`.

- **Proposed** — body may be freely edited as the design iterates;
  each non-trivial edit gets a Decision-History entry.
- **Accepted** — body is append-only. Substantive reversal happens
  via a new ADR (`Status: Supersedes ADR-NNNN`), not by editing
  the body.

Editing rules are spelled out in
`~/.claude/skills/knowledge-adr-format/SKILL.md` §"Lifecycle and
editing rules".

## Index

| ID | Title | Subsystem / Scope | Status | Date |
|---|---|---|---|---|
| [0001](0001-state-modular-orchestrator-pattern.md) | State — Modular Orchestrator Pattern | state · *Project-Wide* | Accepted | 2026-05-14 |
| [0002](0002-state-cross-module-cascade.md) | State — Cross-Module Cascade | state · *Project-Wide* | Accepted | 2026-05-14 |
| [0003](0003-service-foreground-pipeline-architecture.md) | Service — Foreground Pipeline Architecture | service · *Project-Wide* | Accepted | 2026-05-14 |
| [0004](0004-ui-layout-catalog-motionlayout.md) | UI — LayoutCatalog + MotionLayout | ui-rendering · *Project-Wide* | Accepted | 2026-05-14 |
| [0005](0005-ui-triangle-fsm-keyboard-widget-hover.md) | UI — Triangle-FSM (KEYBOARD / WIDGET / HOVER) | ui-mode · *Project-Wide* | Accepted | 2026-05-14 |
| [0006](0006-ui-info-bar-state-derived-items.md) | UI — Info-Bar State-Derived Items with Cross-Module Producers | ui-architecture, state-management | Proposed | 2026-05-21 |
| [0007](0007-audio-multi-file-repository.md) | Audio — Multi-File Recording Repository (Resume-after-Cold-Start) | audio-pipeline, database | Proposed | 2026-05-21 |

## Relationship graph

The five Phase-1 ADRs reference each other as follows
(plan §4.0.1.0.2):

```
ADR-0001 (Modular Orchestrator)
  ├── prerequisite for  ─► ADR-0002 (Cross-Module Cascade)
  ├── hosted by         ─► ADR-0003 (Foreground Service)
  └── consumed by       ─► ADR-0004 (LayoutCatalog)

ADR-0002 (Cross-Module Cascade)
  ├── extends           ─► ADR-0001
  └── enables T7 path   ─► ADR-0005

ADR-0003 (Foreground Service)
  ├── hosts             ─► ADR-0001 (Composition Root)
  └── makes possible    ─► ADR-0005 (HOVER mode, because FGS outlives IME)

ADR-0004 (LayoutCatalog + MotionLayout)
  ├── consumes          ─► ADR-0001 (RenderBackend reads StateFlow)
  └── implements        ─► ADR-0005 (RenderBackend switching for ViewMode)

ADR-0005 (Triangle-FSM)
  ├── implemented in    ─► ADR-0001 (ViewModeModule)
  ├── needs             ─► ADR-0003 (FGS for HOVER persistence)
  ├── needs             ─► ADR-0002 (T7 = Pipeline-Done cascade)
  ├── rendered by       ─► ADR-0004 (RenderBackend switching)
  └── extended by       ─► ADR-0006 (Overlay-Block-Hint mode)

ADR-0006 (Info-Bar State-Derived Items)
  ├── plugs into        ─► ADR-0001 (new module on modular store)
  ├── extends           ─► ADR-0005 (OVERLAY_BLOCK_HINT mode)
  └── consumes          ─► ADR-0007 (Pending-Recording dismiss/append)

ADR-0007 (Multi-File Audio Repository)
  ├── hosted by         ─► ADR-0003 (FGS owns MediaRecorder instances for Live-Resume)
  └── consumed by       ─► ADR-0006 (Pending-Recording Fortsetzen action)
```

Every ADR's `## References` section lists the other four ADRs as
cross-references (bidirectional).

## Process pointers

- **Skill (mandatory load when writing or substantially editing an ADR):**
  `~/.claude/skills/knowledge-adr-format/SKILL.md`
- **Template:** `~/.claude/templates/adr.md`
- **Lifecycle snippet:** `~/.claude/snippets/docs/lifecycle-adr.md`
- **Doc index:** `~/.claude/snippets/docs/docs.md`
