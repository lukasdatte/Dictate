# Audit Report: plan-and-api (Block 0, scope: full-block)

**Agent-ID:** B0-AUDIT-PLAN-AND-API
**Date:** 2026-05-14
**Knowledge skills used:** knowledge-adr-format (referenced by plan-§4.0.1.0.3 + ADRs themselves; not loaded as live skill since topic is docs-only plan-treue), knowledge-doc-format (likewise referenced)
**Files inspected:** 19 (5 ADRs + ADR-index + 12 architecture-doc files + 1 cross-check against plan §4.0)

- `docs/decisions/README.md`
- `docs/decisions/0001-state-modular-orchestrator-pattern.md`
- `docs/decisions/0002-state-cross-module-cascade.md`
- `docs/decisions/0003-service-foreground-pipeline-architecture.md`
- `docs/decisions/0004-ui-layout-catalog-motionlayout.md`
- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`
- `docs/architecture/state-architecture/README.md`
- `docs/architecture/state-architecture/state-and-actions.md`
- `docs/architecture/state-architecture/modules.md`
- `docs/architecture/state-architecture/effects-and-failures.md`
- `docs/architecture/state-architecture/cross-module-cascade.md`
- `docs/architecture/state-architecture/rendering.md`
- `docs/architecture/state-architecture/wiring-ui.md`
- `docs/architecture/state-architecture/triangle-fsm.md`
- `docs/architecture/state-architecture/adding-a-module.md`
- `docs/architecture/state-architecture/adding-a-button.md`
- `docs/architecture/state-architecture/adding-a-sub-keyboard.md`
- `docs/architecture/state-architecture/forbidden-patterns.md`

Cross-checked back-references (in modified files):
- Plan §8.1 (line 1272-1304) — ADR-0001..0005 + ADR-Index links present, bidirektionale-Referenz-Pflicht statement present.
- Spec 1 §12 (line 5793-5801) — "ADRs (Block-0-Artefakte, bidirektional)" lists ADR-0001/0002/0003 + Architektur-Doku pointer.
- Spec 2 §12 (line 2265-2275) — lists ADR-0001/0004 + 4 architecture-doc files.
- Spec 3 §12 (line 2258-2267) — lists ADR-0003/0004/0005 + 2 architecture-doc files.

## Summary

- Critical: 0
- Important: 1
- Nice-to-have: 6

The 18-doc set is substantively complete and faithful to plan §4.0. All
five ADRs exist, all 12 architecture-doc files exist, all five plan
decision-kernsätze are present in the §Decision section of the
corresponding ADR, all 14 forbidden patterns are catalogued, all three
walkthroughs (button + module + sub-keyboard with both variants) are
worked examples, the cross-module-cascade rules + Triangle-FSM T1–T7 +
RenderBackend pattern are all materialised. The single
**Important** finding is an ambiguity-driven gap in the inter-ADR
cross-reference graph: plan §4.0.1.0.2 demands "Jede ADR §References
trägt die anderen vier ADRs als Cross-Reference (bidirektional)", but
four of the five ADRs list only their direct-edge neighbours from the
graph. The **Nice-to-have** findings are mostly format/coverage
polish — surface details that don't undermine the binding-contract
nature of the artefacts.

## Findings

### AUDIT-PLAN-AND-API-B0-1

- **Severity:** Important
- **File:** `docs/decisions/0001-state-modular-orchestrator-pattern.md:293-296` · `docs/decisions/0002-state-cross-module-cascade.md:287-289` · `docs/decisions/0003-service-foreground-pipeline-architecture.md:292-294` · `docs/decisions/0004-ui-layout-catalog-motionlayout.md:337-339`
- **Description:** Plan §4.0.1.0.2 closes with "Jede ADR §References trägt die anderen vier ADRs als Cross-Reference (bidirektional)" — a universal-cross-reference rule. The implementation only honours the **direct-edge** subset of the graph:
  - ADR-0001 §References lists ADR-0002, ADR-0003, ADR-0004 — **ADR-0005 missing** (graph has edge ADR-0005 → ADR-0001 "implemented in", which is bidirectional per the closing sentence).
  - ADR-0002 §References lists ADR-0001, ADR-0005 — **ADR-0003 and ADR-0004 missing**.
  - ADR-0003 §References lists ADR-0001, ADR-0005 — **ADR-0002 and ADR-0004 missing**.
  - ADR-0004 §References lists ADR-0001, ADR-0005 — **ADR-0002 and ADR-0003 missing**.
  - ADR-0005 §References lists ADR-0001..0004 — complete.

  Plan §4.0.3 acceptance criterion ("Inter-ADR-Querverweise gemäß §4.0.1.0.2 … alle fünf ADRs verweisen aufeinander in §References, bidirektional") reads the rule as universal. The ADR-index `docs/decisions/README.md` Relationship-Graph (lines 75-103) is graph-edge-only and consistent with the implementation, so the gap is between ADR-bodies and the plan's closing sentence, not between ADR-bodies and the relationship-graph.

- **Why it matters:** A future maintainer who lands on, say, ADR-0002 looking for "everything I should read to change cross-module cascade" will not find pointers to ADR-0003 (FGS that hosts the orchestrator) or ADR-0004 (rendering that observes cascades). The universal-cross-reference rule was designed to make every ADR a self-sufficient entry point. The current implementation requires the reader to consult the ADR index + relationship graph.
- **Suggested fix scope:** small (one-file-each, mechanical — add 2–3 cross-references per ADR § References section, mirroring the graph-edge entries with one-line "see also" notes for the missing pairs).
- **Suggested fix:** Add a small "All Phase-1 ADRs" bullet block at the bottom of each ADR's `## References` § "Related ADRs" subsection that lists the other four ADRs by ID + title, even when the direct-edge text already covered the close neighbours. Alternative: clarify plan §4.0.1.0.2 to mean direct-edge-only (which contradicts §4.0.3 acceptance, so the universal interpretation is the more conservative read).

### AUDIT-PLAN-AND-API-B0-2

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/effects-and-failures.md:120-137`
- **Description:** Plan §4.0.1.3 (lines 446-458) specifies the EffectFailure-emit shape using `runCatching { … }.onFailure { … }` Kotlin-stdlib syntax. The architecture-doc effects-and-failures.md §4 ("How the orchestrator runs effects") shows the equivalent `try { … } catch (t: Throwable) { … }` form. Both are semantically equivalent for the failure-handling story.
- **Why it matters:** The plan's chosen syntax (`runCatching`) is the more idiomatic Kotlin form and is what the implementer is expected to copy. Showing `try/catch` in the teaching material is fine for the prose, but a reader copying from the doc and pasting into code will diverge from the plan §4.0.1.3 example by one stylistic step.
- **Suggested fix scope:** small (one-snippet rewrite to `runCatching`).
- **Suggested fix:** Replace the `try/catch` block in effects-and-failures.md §4 with the plan's `runCatching { typedModule.runEffect(effect, services) }.onFailure { … }` form. Add a brief note that semantic-equivalence is preserved.

### AUDIT-PLAN-AND-API-B0-3

- **Severity:** Nice-to-have
- **File:** `docs/decisions/0001-state-modular-orchestrator-pattern.md` and the four sibling ADRs (overall structure)
- **Description:** The audit prompt's checklist 4 expects ADRs to carry "Implementation Notes" and "Open Questions" as discrete sections (alongside Status/Date/Subsystem/Scope, Context, Decision, Decision History, Consequences-Positive, Consequences-Negative, Failure Modes, Alternatives Considered, References, Phase-2 Superseding Expectations). The plan §4.0.1.0.3 itself only specifies 12 mandatory sections (Kontext, Decision, hart-definierte-Regeln-3-bis-7, Consequences-Positiv, Consequences-Negativ+Failure-Modes, Subsystem-Header, References, Decision-History) and does **not** list "Implementation Notes" or "Open Questions". All five ADRs follow the plan's narrower 12-section spec; none carry an Implementation-Notes or Open-Questions section.
- **Why it matters:** Plan-treue is satisfied (the plan's explicit acceptance criterion does not require these sections). The gap is between the audit-prompt and the plan, not between the ADRs and the plan. Logging this as Nice-to-have so the orchestrator can decide whether to (a) add the two sections retroactively or (b) clarify in the audit-prompt that they are not plan-mandated.
- **Suggested fix scope:** small (no fix required if the plan-spec is taken as authoritative; if the orchestrator wants to align with knowledge-adr-format's broader skeleton, ~5 minutes per ADR to add stub Open-Questions / Implementation-Notes headings).
- **Suggested fix:** Either leave as-is (plan-treue holds) or, if a stricter alignment with the knowledge-adr-format skill is desired, add `## Open Questions` and `## Implementation Notes` headings per ADR with sub-pointers into the relevant Spec § (e.g. "Implementation Notes: see Spec 1 §4.3 for the canonical `dispatchInternal` body").

### AUDIT-PLAN-AND-API-B0-4

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/*` (all 12 files — frontmatter `status:` field)
- **Description:** All architecture-doc files use frontmatter `status: Skeleton`. The convention from `~/.claude/snippets/docs/lifecycle-research.md` § "Research → Spec promotion" lists `Skeleton` as an exploratory-research status; `Architecture` is a separate genre with its own `## Properties this Architecture Guarantees` section (which all 12 files have). The architecture-doc files materially have substance well beyond Skeleton — they are full tutorial-grade architecture material with worked code examples.
- **Why it matters:** A reader skimming the frontmatter might infer "this is still skeleton — wait for the real version", which is misleading given the substantive content. The knowledge-doc-format skill's Architecture genre would suggest `status: Initial draft` or simply omitting `status:` (Architecture docs have no canonical lifecycle marker analogous to Research/Spec). The "## N+1. Change History" + the "Initial draft" entries make the lifecycle clear regardless.
- **Suggested fix scope:** small (one-line edit per file).
- **Suggested fix:** Replace `status: Skeleton` with `status: Initial draft` (or remove the field entirely) across all 12 architecture-doc files. Alternatively, leave as-is — the change-history makes the lifecycle clear and the field is not load-bearing for downstream consumers.

### AUDIT-PLAN-AND-API-B0-5

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/README.md:167`
- **Description:** The closing reference line reads `[knowledge-doc-format skill](https://github.com/...) (UDOC convention)` — a placeholder GitHub URL rather than the actual skill-file path. The skill lives at `~/.claude/skills/knowledge-doc-format/` (local) and has no public GitHub URL.
- **Why it matters:** A reader clicking the link gets a `https://github.com/...` 404. The link is non-load-bearing but signals "this doc was finished hurriedly". The other architecture-doc files reference the skill correctly via plain-text paths.
- **Suggested fix scope:** small (one-line edit).
- **Suggested fix:** Replace with `\`~/.claude/skills/knowledge-doc-format/SKILL.md\`` (matching the pattern used in other ADRs' `## References § Skill` entries).

### AUDIT-PLAN-AND-API-B0-6

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/README.md:96-113` (Plan → topic-page map)
- **Description:** Plan §4.0.6.4 ("Allgemeines Walkthrough-Pattern") is the meta-decision-tree that helps an implementer pick which of the three walkthroughs to use. The README's "Who reads what" table (lines 27-37) and the "Plan → topic-page map" table (lines 96-113) **partially** materialise this, but the full §4.0.6.4 structure (the four numbered questions: "WO LEBT DAS NEUE FEATURE?" → walkthrough-pick; "WAS BERÜHRT ES?" → state/actions/effects/UI/cross-module; "WAS MUSS GETESTET WERDEN?" → 4 test-types; "WO WERDEN INLINE-ANKER GESETZT?" → 3 anchor-types) is **not** captured in any single doc. Pieces of it surface in individual walkthrough files (each walkthrough's "When this walkthrough applies" sections cover Question 1; each walkthrough's test section covers a slice of Question 3; adding-a-module.md has an inline-anchor checklist at the end covering Question 4).
- **Why it matters:** The plan's §4.0.6.4 was designed as a single decision-tree. A reader looking for "the canonical walkthrough-picker" today has to assemble it from three or four sources. This is not blocking — every individual decision is reachable — but it forces the reader to learn the structure rather than read it from a single page.
- **Suggested fix scope:** small (one new section in README, ~30 lines).
- **Suggested fix:** Add a "Walkthrough Decision Tree (Plan §4.0.6.4)" section to README.md that reproduces the four-question structure verbatim, with each question linking into the relevant walkthrough or topic page. Optionally also embed it as the opening section of each individual walkthrough so a reader who lands on adding-a-button.md from a search has the decision-tree adjacent.

### AUDIT-PLAN-AND-API-B0-7

- **Severity:** Nice-to-have
- **File:** `docs/decisions/README.md:69-73` (ADR-Index table — Status column)
- **Description:** ADR-Index lists all five ADRs as `Status: Proposed`. Plan §4.0.3 acceptance criterion explicitly reads "Fünf ADRs ... jeweils Status Accepted". Plan §4.0.1.0.3 last paragraph clarifies "Lifecycle pro ADR: Status `Proposed` während Block 0; nach Plan-Approval `Accepted`." — so `Proposed` is correct **during** Block 0 (the current phase) and acceptance criterion §4.0.3 is to be read as "Accepted by end of Block 0 lifecycle", not "Accepted at the moment of audit".
- **Why it matters:** Strictly comparing against §4.0.3 wording alone, the artefacts are not yet at the acceptance-target. With the §4.0.1.0.3 lifecycle clause, the current `Proposed` status is correct. Logging this as a sanity-check marker for the orchestrator/closeout — when Block 0 closes (Plan-Approval), the index + each ADR §Status + each Decision-History should be flipped to `Accepted` with a follow-up Decision-History entry.
- **Suggested fix scope:** small (six file edits: ADR-index status column + 5 ADR `Status:` headers + 5 ADR Decision-History append-only entries `2026-MM-DD — Accepted`).
- **Suggested fix:** At Block-0-closeout, batch-update: (a) `docs/decisions/README.md` Status column from `Proposed` to `Accepted`; (b) each ADR `**Status:** Proposed` → `**Status:** Accepted`; (c) each ADR adds a new Decision-History entry "## 2026-MM-DD — Accepted" with Trigger = Plan §4.0 Block-0-closeout. The decision-body itself stays unchanged (append-only contract per knowledge-adr-format skill).

## Coverage

### Plan §4.0 checklist mapping

| Plan reference | Audit-checklist item | Verdict |
|---|---|---|
| §4.0.1.0 Decision-Kernsatz #1 (Modular Orchestrator) | ADR-0001 §Decision lines 64-73 | ✅ present, verbatim per plan |
| §4.0.1.0 Decision-Kernsatz #2 (Cross-Module Cascade) | ADR-0002 §Decision lines 80-145 | ✅ present, all three rules (Mode 1+2 allowed, Mode 3 forbidden, self-cascade, MAX_CASCADE_DEPTH, EffectFailure origin-routing) |
| §4.0.1.0 Decision-Kernsatz #3 (FGS) | ADR-0003 §Decision lines 69-143 | ✅ present (FGS type=microphone, LocalBinder+StateFlow no IPC, no WorkManager, DB-replay+manual resume, persistent notification) |
| §4.0.1.0 Decision-Kernsatz #4 (LayoutCatalog + MotionLayout) | ADR-0004 §Decision lines 77-215 | ✅ present (declarative LayoutCatalog, MotionScene XML, RenderBackend multi-backend, click-once-wiring, visibility-mode-ignore) |
| §4.0.1.0 Decision-Kernsatz #5 (Triangle-FSM) | ADR-0005 §Decision lines 81-233 | ✅ present (computeViewMode pure fn, 3 modes, 7 transitions, userPrefersWidget transient, T7 mandatory) |
| §4.0.1.0.1 Plan-Body → ADR mapping | Mapping check across all ADRs | ✅ present; every plan-body sub-section listed in the table maps to the corresponding ADR section |
| §4.0.1.0.2 Inter-ADR cross-reference graph | ADR §References cross-check | ⚠ partial — see AUDIT-PLAN-AND-API-B0-1 |
| §4.0.1.0.3 12-section standard structure | All 5 ADRs section-by-section | ✅ all 12 plan-specified sections present per ADR; broader audit-prompt checklist with "Implementation Notes" + "Open Questions" is partially covered (see AUDIT-PLAN-AND-API-B0-3) |
| §4.0.1.1 Reducer-Regeln (Positiv/Negativ) | ADR-0001 §"Required mechanics" item 2 + Failure Modes (b)/(c) + state-and-actions.md §"Properties this Architecture Guarantees" + §"Reducer Context" | ✅ present, exhaustive |
| §4.0.1.2 Action-Regeln (5 sources) | state-and-actions.md §"4.1 Five sources of Actions" + ADR-0001 §"Required mechanics" item 4 + ADR-0002 §"EffectFailure routing" | ✅ all 5 trigger sources documented |
| §4.0.1.3 Effect-Regeln (runCatching/onFailure) | effects-and-failures.md §4 (try/catch syntax — see AUDIT-PLAN-AND-API-B0-2 for the stylistic note) + ADR-0001 §"Decision" item 4 | ✅ semantic match; AUDIT-PLAN-AND-API-B0-2 covers the syntax mismatch |
| §4.0.1.4 Cross-Module-Regeln (Mode 1+2, Mode 3 forbidden) | ADR-0002 §"The two allowed modes" + §"The forbidden third mode" + cross-module-cascade.md §3 + §4 + §5 | ✅ comprehensive; example flows match plan |
| §4.0.1.5 14 forbidden patterns (a–n) | forbidden-patterns.md §3 (entries (a)–(n)) + per-ADR Failure-Mode sections per plan §4.0.1.5 routing table | ✅ all 14 patterns present in forbidden-patterns.md; routing across ADR-0001/0002/0004 follows the plan-prescribed distribution; ADR-0005 not in the routing table (only ADR-0001/0002/0004 own forbidden patterns) and the ADR carries no forbidden-pattern entries — matches plan |
| §4.0.1.6 "Wer hält State" ASCII diagram | ADR-0001 §"State diagram — who holds state" + state-and-actions.md §"Dispatch loop" section | ✅ ASCII diagram present in both; the modules.md §5 also has a complementary "three modules side by side" diagram |
| §4.0.1.7 "Wer hält UI-Wiring" | wiring-ui.md (entire file) + ADR-0001 §"UI-Wiring boundary" + ADR-0004 §"Required mechanics" items 7+9 | ✅ comprehensive coverage; once-wiring, stateRef/modeRef, nullable resolver, special-touch handlers, click-flow diagram |
| §4.0.2 12 architecture-doc files | docs/architecture/state-architecture/ directory listing | ✅ README + 11 sub-files all present; total = 12 files matching plan §4.0.2 table |
| §4.0.5 Modul-Isolation (Lens, ASCII diagram, 3 channels, Registry) | modules.md §4 + §5 + §6 + §7 + §7.1 | ✅ all four sub-points covered |
| §4.0.6.1 INSERT_COMMA walkthrough | adding-a-button.md (7 steps) | ✅ matches plan §4.0.6.1 step-by-step |
| §4.0.6.2 Sub-keyboard walkthrough (Variant A + B) | adding-a-sub-keyboard.md §3 (Variant A) + §4 (Variant B) | ✅ both variants present with worked examples |
| §4.0.6.3 BatterySaverModule walkthrough | adding-a-module.md (8 steps) | ✅ matches plan §4.0.6.3 step-by-step |
| §4.0.6.4 General walkthrough pattern (decision tree) | README.md "Who reads what" + "Plan → topic-page map" tables | ⚠ partial coverage — see AUDIT-PLAN-AND-API-B0-6 (full §4.0.6.4 decision tree not in a single doc) |
| §4.0.3 Acceptance — 5 ADRs with all 12 sections | All 5 ADRs structurally checked | ✅ all 12 plan-specified sections present per ADR; §Status currently `Proposed` per §4.0.1.0.3 lifecycle (see AUDIT-PLAN-AND-API-B0-7 for the flip-to-Accepted closeout step) |
| §4.0.3 Acceptance — ADR-Index | docs/decisions/README.md (4913 bytes, with full index + relationship-graph + lifecycle pointers) | ✅ present, well-structured |
| §4.0.3 Acceptance — 12 architecture-doc files with "Properties this Architecture Guarantees" | All 12 architecture-doc files | ✅ every file has the §"Properties this Architecture Guarantees" section per UDOC-skeleton |
| §4.0.3 Acceptance — Bidirektionale Plan-↔-ADR | Plan §8.1 + every ADR §References | ✅ Plan §8.1 lists all 5 ADRs; every ADR §References lists the parent plan via the `Related Plan` bullet |
| §4.0.3 Acceptance — Bidirektionale Spec-↔-ADR | Spec 1 §12 (ADR-1/2/3) · Spec 2 §12 (ADR-1/4) · Spec 3 §12 (ADR-3/4/5) | ✅ all three back-reference edits present per plan §4.0.3 |
| §4.0.5 (Modul-Isolation continued) — `DictateModuleRegistry.all` wiring | modules.md §7 with `init { require(...) }` sanity-check + ProGuard-keep mention pointer | ✅ present; the init-time-error invariant is documented |

### Out-of-scope observations

- **Convention topic (handed off to AUDIT-CONVENTION):** Multiple ADR
  `Author:` fields read "Lukas + Claude Code"; standard ADR convention
  often uses just initials or full names. This is a CONVENTION-topic
  question, not plan-treue.
- **Logic topic (handed off to AUDIT-LOGIC):** The truth-table cell
  `false | * | true → HOVER` in triangle-fsm.md §4 vs the
  `computeViewMode` function uses two slightly different parameter
  names (`userPrefersWidget` in the function signature, `userToggledWidget`
  in some inner reducer code-snippets at triangle-fsm.md §5 T3 line 231
  and §5 T7 line 303). Semantically identical, but the naming drift is
  a logic-readability concern.

## Files audited

- docs/decisions/README.md
- docs/decisions/0001-state-modular-orchestrator-pattern.md
- docs/decisions/0002-state-cross-module-cascade.md
- docs/decisions/0003-service-foreground-pipeline-architecture.md
- docs/decisions/0004-ui-layout-catalog-motionlayout.md
- docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md
- docs/architecture/state-architecture/README.md
- docs/architecture/state-architecture/state-and-actions.md
- docs/architecture/state-architecture/modules.md
- docs/architecture/state-architecture/effects-and-failures.md
- docs/architecture/state-architecture/cross-module-cascade.md
- docs/architecture/state-architecture/rendering.md
- docs/architecture/state-architecture/wiring-ui.md
- docs/architecture/state-architecture/triangle-fsm.md
- docs/architecture/state-architecture/adding-a-module.md
- docs/architecture/state-architecture/adding-a-button.md
- docs/architecture/state-architecture/adding-a-sub-keyboard.md
- docs/architecture/state-architecture/forbidden-patterns.md

Cross-referenced (read for back-reference verification, not for content audit):

- Plan main file §4.0 + §8.1
- Spec 1 (1-pipeline-service.reviewed.md) §12
- Spec 2 (2-keyboard-layout.reviewed.md) §12
- Spec 3 (3-floating-overlay.reviewed.md) §12

Files skipped (with reason): none — all 18 docs in the audit-scope list were inspected.

Knowledge-skill checkpoints applied:

- knowledge-adr-format §"12-section standard structure" (compared against plan §4.0.1.0.3's 12-section list, which is the project-canonical narrower spec; ADRs satisfy the narrower spec).
- knowledge-doc-format §"Architecture genre" + §"Properties this Architecture Guarantees" heading rule (compared against all 12 architecture-doc files; all comply).
- Plan-treue cross-check methodology: every plan §4.0.1.x rule + every plan §4.0.2 doc-topic + every plan §4.0.5/§4.0.6 walkthrough verified by reading both the plan-body specification and the corresponding ADR/architecture-doc section. Findings logged where the implementation diverges from the explicit plan wording.
