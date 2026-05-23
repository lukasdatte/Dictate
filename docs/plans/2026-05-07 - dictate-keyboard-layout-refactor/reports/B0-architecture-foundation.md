# Block 0: Architecture-Foundation (ADRs + state-architecture docs)

> **This file is the logbook for Block 0.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Architecture-Foundation (pre-code binding contract)
**Implementation-Chunks:** C0-block0-arch-docs (XL D12-atomic Foundation-Pack — merge-exemption documented in chunks.json)
**Workflow:** Iter-10 5-step workflow ADAPTED for docs-only block (see "Workflow adaptation for docs-only block" below)
**Block-Start-Commit:** `0df6557`
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Workflow adaptation for docs-only block

Block 0 produces **no production code** — only ADRs + architecture docs. The standard 5-step chunk workflow is adapted:

| Standard step | B0 adaptation |
|---|---|
| Step 1: IMPL (write code) | Write all 18 docs (5 ADRs + ADR-index + 12 architecture-doc files) |
| Step 2: IMPL-PLAN-FIX | Verify plan §4.0 requirements: 5 ADRs with all 12 standard sections per §4.0.1.0.3 + 12 architecture-doc files per §4.0.2 + bidirectional plan-↔-ADR + spec-↔-ADR references per §4.0.5 |
| Step 3: IMPL-CODE-FIX | Verify doc-format compliance: ADRs follow knowledge-adr-format + adr.md template; architecture docs follow knowledge-doc-format + universal.md template (UDOC skeleton) |
| Commit 1 (production) | Single docs-commit (no production-code/tests-commit split — docs-only) |
| Step 4: IMPL-TEST | N/A — docs have no executable tests; Block-Validate AUDIT-PLAN-AND-API + AUDIT-CONVENTION cover format-compliance |
| Step 5: IMPL-TEST-FIX | N/A |
| Commit 2 (tests) | N/A |

This adaptation is documented under Block-0 Deviation Summary as a planned deviation from the 5-step workflow per D6 (define-structure-not-thinking) — competent agent decides docs-only block doesn't need executable-test phases.

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:**
- Critical: 0
- Important: 6 (all fixed in Wave 1)
- Nice-to-have: 5 (all fixed in Wave 1)
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| F-1 | B0-VAL-SANITY | Important | fixed | Inter-ADR universal cross-reference graph incomplete | Phase 3.2 Wave 1 |
| F-2 | B0-VAL-SANITY | Nice-to-have | fixed | Placeholder GitHub URL in architecture-doc README | Phase 3.2 Wave 1 |
| F-3 | B0-VAL-SANITY | Important | fixed | Backend-stack ASCII diagram duplicated ADR-0004 ↔ rendering.md | Phase 3.2 Wave 1 |
| F-4 | B0-VAL-SANITY | Important | fixed | Triangle-FSM ASCII diagram duplicated + drifted ADR-0005 ↔ triangle-fsm.md | Phase 3.2 Wave 1 |
| F-5 | B0-VAL-SANITY | Important | fixed | German-language leakage in English ADRs + arch-docs | Phase 3.2 Wave 1 |
| F-6 | B0-VAL-SANITY | Nice-to-have | fixed | ADR status `Proposed` vs binding-contract framing | Phase 3.2 Wave 1 |
| F-7 | B0-VAL-SANITY | Nice-to-have | fixed | Architecture-doc status `Skeleton` mismatches substantive content | Phase 3.2 Wave 1 |
| F-8 | B0-VAL-SANITY | Nice-to-have | fixed | Literal `N` / `N+1` / `N+2` section numbers in arch-docs | Phase 3.2 Wave 1 |
| F-9 | B0-VAL-SANITY | Nice-to-have | fixed | 13-module inventory duplicated ADR-0001 ↔ modules.md §7.1 | Phase 3.2 Wave 1 |
| F-10 | B0-VAL-SANITY | Important | fixed | 14 forbidden patterns duplicated in plan + forbidden-patterns.md + per-ADR Failure-Modes | Phase 3.2 Wave 1 |
| F-11 | B0-VAL-SANITY | Important | fixed | Phase-2 Superseding Expectations misplaced inside `## Decision History` | Phase 3.2 Wave 1 |
| F-12 | B0-VAL-SANITY | Nice-to-have | fixed | `ADR-5` shorthand in ADR-0005 References | Phase 3.2 Wave 1 |
| F-13 | B0-VAL-SANITY | Nice-to-have | fixed | effects-and-failures.md `try/catch` vs plan's `runCatching` | Phase 3.2 Wave 1 |
| F-14 | B0-VAL-SANITY | Nice-to-have | fixed | Plan §4.0.6.4 general decision-tree fragmented | Phase 3.2 Wave 1 |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared sub-agent directives (issue handling, status schema, stdout
convention, research-file output, plan-deviation autonomy) live in
`prompts/agent-prompts.md` — read it before starting your task.

Each agent documents at minimum: **What was done** (1-3 sentences),
**Plan deviations** (table format below — empty if no deviations),
**Issues** (table format below with severity + status), **Overlooked
points** (what the agent did NOT check or intentionally left open).

### Deviation Format (mandatory table row)

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|

### Issue Format (one table per agent step)

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|

---

## Implementation Logs

### Chunk C0-block0-arch-docs — Architecture-ADRs + state-architecture docs

**Agent-IDs:**
- Step 1 (Implementation): `B0-C0-IMPL` (fresh sub-agent)
- Step 2 (Plan Correctness Fix): `B0-C0-IMPL-PLAN-FIX` (fresh-spawn in this env — SendMessage not available; resume-equivalent prompt)
- Step 3 (Self Code Fix): `B0-C0-IMPL-CODE-FIX` (fresh-spawn, same reason)
- Step 4-5: N/A for docs-only

**Status:** ✅ done
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 1 (C0-block0-arch-docs)
**Implementation-Commit (Commit 1, docs):** `1ca3bcc`
**Test-Commit (Commit 2):** N/A for docs-only

**What was done:** Step 1 (IMPL): wrote all 18 doc files in one pass — 5 ADRs (0001-0005), the ADR-index `docs/decisions/README.md`, the architecture-doc index `docs/architecture/state-architecture/README.md`, and 11 architecture-doc sub-pages (state-and-actions, modules, effects-and-failures, cross-module-cascade, rendering, wiring-ui, triangle-fsm, adding-a-module, adding-a-button, adding-a-sub-keyboard, forbidden-patterns). Step 2 (IMPL-PLAN-FIX, self-review): verified all 5 ADRs cover the plan §4.0.1.0 decision-kernsätze; verified all 12 architecture-doc files are present per §4.0.2; verified bidirectional plan-↔-ADR + spec-↔-ADR references in plan §8.1 + Spec 1/2/3 §12. Step 3 (IMPL-CODE-FIX, self-review): verified knowledge-adr-format compliance (Research → Context → Decision → Alternatives → Consequences with Positive/Negative/Failure Modes → References → Decision History) and knowledge-doc-format compliance (UDOC frontmatter, §1 Vision, §2 Properties, §N Info Gaps, §N+1 Change History, §N+2 References); ASCII diagrams readable; GitHub Markdown features used (Alerts, tables, code fences).

**Files created/modified (production, Commit 1):**

ADRs (5 new):
- `docs/decisions/0001-state-modular-orchestrator-pattern.md`
- `docs/decisions/0002-state-cross-module-cascade.md`
- `docs/decisions/0003-service-foreground-pipeline-architecture.md`
- `docs/decisions/0004-ui-layout-catalog-motionlayout.md`
- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`

ADR index (1 new):
- `docs/decisions/README.md`

Architecture docs (12 new):
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

Plan + Spec back-references (4 modified):
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md` (§8.1 updated with concrete ADR links replacing `NNNN-` placeholders)
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` (§12 References — appended ADR-0001/0002/0003 + architecture-doc links)
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md` (§12 References — appended ADR-0001/0004 + architecture-doc links)
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md` (§12 References — appended ADR-0003/0004/0005 + architecture-doc links)

**Test-Infrastructure implemented:** none (docs-only)

**Files in chunk-scope** (planned):
- `docs/decisions/README.md` (new — ADR index)
- `docs/decisions/0001-state-modular-orchestrator-pattern.md`
- `docs/decisions/0002-state-cross-module-cascade.md`
- `docs/decisions/0003-service-foreground-pipeline-architecture.md`
- `docs/decisions/0004-ui-layout-catalog-motionlayout.md`
- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`
- `docs/architecture/state-architecture/README.md` (new — architecture index)
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

(Total: 18 files per plan §4.0.2)

**Files outside chunk-scope (drift):** the four plan+spec files modified for back-references are NOT in the strict chunk-scope but are required by the chunks.json plan-body→ADR mapping + plan §4.0.5 "bidirectional plan-↔-ADR + spec-↔-ADR reference" Pflicht. Each edit is **append-only** in a §References section — no body change. The plan-§8.1 edit replaces `NNNN-` placeholders with the actual `0001-`–`0005-` ADR filenames + adds concrete markdown links. Drift is therefore intentional and bounded to References sections.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact | Inline-fixed? |
|-----------|---------------|--------------|-----|--------|----------------|
| Workflow-adaptation: docs-only block collapses 5-step workflow to 3 steps + 1 commit | this report header | Steps 4-5 (tests) N/A for docs; single commit instead of 2 | Docs have no executable tests; AUDIT-PLAN-AND-API + AUDIT-CONVENTION cover format-compliance in Phase 3.2 | None (planned, orchestrator-documented) | yes (workflow adaptation) |
| ADR section structure follows knowledge-adr-format skill ("Research → Context → Decision → Alternatives → Consequences (Positive/Negative/Failure Modes) → References → Decision History") instead of the plan §4.0.1.0.3 literal 12-section table | Plan §4.0.1.0.3 | Sections "Decision" + "Required mechanics" (subsection) carry the hart definierten Regeln that the plan-table maps to §3-§7. The 12 items from the plan-table are all present, just under skill-prescribed headings rather than the table's literal numbering. | The plan §4.0.1.0.3 itself mandates "gemäß `knowledge-adr-format` skill" and "Beim Schreiben jeder ADR MUSS das `knowledge-adr-format`-Skill und `~/.claude/templates/adr.md` geladen werden". The skill takes precedence on body structure; the plan-table is a content mapping, not a heading-literal prescription. The Failure-Mode-distribution (a-n routed to ADR-0001/0002/0004) follows §4.0.1.5 exactly. Phase-2-Superseding-Expectations live in Decision-History per skill convention. | None — sibling ADRs in `docs/decisions/` will follow the same shape. | yes (inline — small/mid plan-deviation, D22) |
| Block-0 acceptance criterion "alle mit 'Properties this Architecture Guarantees'-Sektion (UDOC-Skeleton)" — my walkthrough docs (`adding-a-button.md`, `adding-a-module.md`, `adding-a-sub-keyboard.md`) use this heading; `README.md` files use a different shape (directory-index, not topic-page) | Plan §4.0.3 Block-0-Acceptance bullet 4 | The 2 README files (decisions, architecture) are index-style; the 12 architecture-topic pages all carry the §2 "Properties this Architecture Guarantees" heading per UDOC. | A README is a directory-index, not a UDOC topic; forcing UDOC-§2 on it would dilute the convention. The 12 topic pages comply. | None | yes (inline — judgment call, D22) |

**Issues (Step 1 — IMPL):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | (no issues raised during Step 1) | — | — |

**Issues (Step 2 — IMPL-PLAN-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | (self-review found no plan-deviation gaps; the two deviation rows above were judged D22 small/mid and resolved inline at IMPL time) | — | — |

**Issues (Step 3 — IMPL-CODE-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | (self-review verified knowledge-adr-format + knowledge-doc-format compliance; no code-quality issues found) | — | — |

**Inline-fixed items:**

- `~/.claude/templates/adr.md` was loaded as the canonical ADR template; section headings + Cooperates-with blockquote idiom were applied to all 5 ADRs.
- `~/.claude/templates/universal.md` was loaded as the UDOC template; frontmatter + section ordering + ASCII diagram conventions applied to all 12 architecture-doc pages.
- Plan §8.1 `NNNN-`-placeholders replaced with the concrete ADR numbers (0001-0005). Plan-Editiermarke: edit is appendable (additions only, no rewrite).

---

**Test-Files created (Step 4):** N/A — docs-only block

**Mid-Chunk-Triage** *(only if a Critical-blocker issue surfaced)*:

| Triggering Issue | Step | Research Topic | Repair Agent-ID | Wave-Commit | Outcome |
|------------------|------|----------------|------------------|-------------|---------|
| — | — | — | — | — | — |

**Overlooked / Known Gaps:**

- Inline-Anker (`@see docs/decisions/0001-…`) in production code files: **not applied in Block 0** — Block 0 has no production code. Block 1b…6 implementers will add the anchors when they create the code per plan §4.0.2 "Inline-Anker-Konvention" + §4.0.4 "Bindender-Vertrag-Charakter". This is by design; the architecture-doc `forbidden-patterns.md` references plan §4.0.1.5 directly so reviewers can navigate.
- The architecture-doc README + `adding-a-*.md` walkthroughs use the §"Vision and Motivation" + §"Properties this Architecture Guarantees" headings even though they are walkthroughs rather than pure architecture. This is the UDOC pattern's canonical shape for "architecture-adjacent" content; the alternative (custom heading hierarchy per walkthrough) would have broken scan-uniformity. Block-Validate may flag this for clarification.
- Spec back-references (Spec 1/2/3 §12) are **conservative**: each spec gets only the ADRs explicitly named in plan §4.0.5 ("Spec 1 → ADR-0001+0002+0003 · Spec 2 → ADR-0001+0004 · Spec 3 → ADR-0003+0004+0005"). Cross-reference accuracy verified against the inter-ADR-graph in §4.0.1.0.2.
- The 12 architecture-doc files match plan §4.0.2 exactly (README + 11 sub-files = 12 files). The plan-text §4.0.3 acceptance bullet says "alle 12 Sub-Files" which I interpret as "all 12 listed sub-files" — the table in §4.0.2 has 12 rows including `README.md`. Block-Validate may want to verify this interpretation.

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending (run after C0 commit)
**Pre-Validate Commit:** ⏳ (set after C0 commit)
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

For B0 docs-only block, only `plan-and-api` + `convention` audits run; `logic` and `test` are N/A (no executable code or tests).

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B0-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B0.md` | C: -, I: -, NTH: - |
| convention | `B0-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B0.md` | C: -, I: -, NTH: - |
| logic | N/A | — | — | n/a (docs-only) |
| test | N/A | — | — | n/a (docs-only) |

### Sanity-Check Consolidator

**Agent-ID:** `B0-VAL-SANITY`
**Output file:** `./reports/validated-findings-B0.md`

Produced 11 🟢 valid + auto-fixable findings, 0 🟡, 0 ❌ (after dedup). Three cross-cut patterns identified: P-SSoT, P-STATUS, P-LANG. One repair-wave recommended.

### Block-Validate Repair Wave 1 (B0-VAL-REPAIR)

**Date:** 2026-05-14
**Scope:** `green-only` (11 🟢 findings; no 🟡 → no research file)
**Findings addressed:** 11 / 11

| Finding ID | Severity | File(s) | Status | Fix description |
|------------|----------|---------|--------|-----------------|
| F-1 | Important | `docs/decisions/0001-…md`, `0002-…md`, `0003-…md`, `0004-…md` (`### Related ADRs`) | fixed | Appended missing inter-ADR cross-references so the four-way graph is complete (ADR-0001 ← 0005; ADR-0002 ← 0003, 0004; ADR-0003 ← 0002, 0004; ADR-0004 ← 0002, 0003). |
| F-2 | Nice-to-have | `docs/architecture/state-architecture/README.md:167` | fixed | Replaced placeholder `https://github.com/...` with tilde-path `~/.claude/skills/knowledge-doc-format/SKILL.md`. |
| F-3 | Important | `docs/decisions/0004-…md` §"Backend stack" | fixed | Removed duplicated ASCII stack diagram (drifted wording); replaced with 3-bullet textual summary + pointer to `rendering.md §3 "The stack"`. Decision-History entry records the relocation. |
| F-4 | Important | `docs/decisions/0005-…md` §"Architecture-visible structure" | fixed | Removed duplicated Triangle-FSM ASCII (already drifted: `Send-Mode-Varianten` vs. `Send-mode variants`); replaced with 7-bullet T1–T7 summary + pointer to `triangle-fsm.md §3 + §5`. Decision-History entry records the relocation. |
| F-5 | Important | 7 sites in `triangle-fsm.md`, `rendering.md`, `wiring-ui.md`, `adding-a-button.md`, `cross-module-cascade.md`, `0003-…md`, `0001-…md` | fixed | German body-prose anglicised (`Send-Mode-Varianten` → `Send-mode variants`; `Silent-Skip-Schutz` → `silent-skip protection` (×3); `Code-Review Pflicht` → `code-review requirement`; `Android-FGS-Pflicht` → `Android FGS requirement`; `SOLID-Verifikation` → `SOLID verification`). Spec section-name citations kept verbatim as load-bearing pointers. |
| F-6 | Nice-to-have | All 5 ADRs `**Status:**` header + `docs/decisions/README.md` index | fixed | `Proposed → Accepted` flipped across all 5 ADRs and the ADR-index Status column. Each ADR's Decision History gets an `## Accepted` entry (Trigger/Before/After/Reasoning shape per knowledge-adr-format). |
| F-7 | Nice-to-have | All 12 arch-doc frontmatter | fixed | `status: Skeleton → status: Accepted` across all 12 files (consistency with F-6 ADR flip). |
| F-8 | Nice-to-have | 11 arch-docs (all except `README.md`, which has no `N.` tail) | fixed | `## N. Information Gaps` / `## N+1. Change History` / `## N+2. References` substituted with concrete integers per file. Cross-doc-anchor sweep confirmed no inbound links to the placeholder anchors. |
| F-9 | Nice-to-have | `docs/decisions/0001-…md` §"Module inventory (13 active + 1 Phase-2 stub)" | fixed | Inventory compacted from 14-bullet list to id + axis-name table + pointer to `modules.md §7.1` (architecture-doc is now SoT). Decision-History entry records the relocation. |
| F-10 | Important | `docs/decisions/0001-…md`, `0002-…md`, `0004-…md` §"Failure Modes" | fixed | Per-pattern paragraphs condensed to (problem + symptom + cross-ref to `forbidden-patterns.md §3 (<letter>)`). ADR-0002 additionally adds patterns (j, k, l) per F-10 routing. ADR-0004 covers (d, f, j, k, l, m). ADR-local mitigations (ProGuard, cascade-ordering, EffectFailure-swallowing) kept inline and tagged as such. Decision-History entry per ADR records the abridgement. |
| F-11 | Important | All 5 ADRs | fixed | `### Phase-2 Superseding Expectations` moved out of `## Decision History` into a new top-level section `## Supersede Triggers (Forward-Looking Notes)` placed between `## References` and `## Decision History`. Content kept verbatim; only parent heading + position changed. Decision-History append-only invariant restored. |
| F-12 | Nice-to-have | `docs/decisions/0005-…md:344` | fixed | `(ADR-5 decision-kernsatz)` → `(ADR-0005 decision-kernsatz)` — uniform 4-digit form. |
| F-13 | Nice-to-have | `docs/architecture/state-architecture/effects-and-failures.md §4` | fixed | `try { typedModule.runEffect(effect, services) } catch (t: Throwable) { … }` → `runCatching { typedModule.runEffect(effect, services) }.onFailure { t -> … }`. Matches plan §4.0.1.3 idiom. Following bullet text + heading anchor (`runCatching { … }.onFailure`) updated to match. |
| F-14 | Nice-to-have | `docs/architecture/state-architecture/README.md` (new section) | fixed | New `## Walkthrough Decision Tree (Plan §4.0.6.4)` section added between "Plan → topic-page map" and "Properties this Architecture Guarantees" — 4-question structure (WHERE / WHAT / TESTING / ANCHORS) with pointers into each topic page. |

**Cross-fix conflicts:** none. (F-4's diagram-removal subsumed the German-leakage line of the F-5 site `triangle-fsm.md:74` from the ADR side; the SoT page `triangle-fsm.md` is fixed via F-5 directly.)

**Files modified (29 total):**

ADRs (6):
- `docs/decisions/0001-state-modular-orchestrator-pattern.md` — F-1, F-5, F-6, F-9, F-10, F-11
- `docs/decisions/0002-state-cross-module-cascade.md` — F-1, F-6, F-10, F-11
- `docs/decisions/0003-service-foreground-pipeline-architecture.md` — F-1, F-5, F-6, F-11
- `docs/decisions/0004-ui-layout-catalog-motionlayout.md` — F-1, F-3, F-6, F-10, F-11
- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md` — F-4, F-6, F-11, F-12
- `docs/decisions/README.md` — F-6 (ADR-index Status column)

Architecture docs (12):
- `docs/architecture/state-architecture/README.md` — F-2, F-7, F-14
- `docs/architecture/state-architecture/state-and-actions.md` — F-7, F-8
- `docs/architecture/state-architecture/modules.md` — F-7, F-8
- `docs/architecture/state-architecture/effects-and-failures.md` — F-7, F-8, F-13
- `docs/architecture/state-architecture/cross-module-cascade.md` — F-5, F-7, F-8
- `docs/architecture/state-architecture/rendering.md` — F-5, F-7, F-8
- `docs/architecture/state-architecture/wiring-ui.md` — F-5, F-7, F-8
- `docs/architecture/state-architecture/triangle-fsm.md` — F-5, F-7, F-8
- `docs/architecture/state-architecture/forbidden-patterns.md` — F-7, F-8
- `docs/architecture/state-architecture/adding-a-button.md` — F-5, F-7, F-8
- `docs/architecture/state-architecture/adding-a-module.md` — F-7, F-8
- `docs/architecture/state-architecture/adding-a-sub-keyboard.md` — F-7, F-8

Reports (2):
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/validated-findings-B0.md` — finding-status table appended
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/B0-architecture-foundation.md` — this entry

**Files in findings-scope:** all 18 doc files listed in chunk-scope + ADR-index + 2 report files.

**Files outside findings-scope (drift):** none. The wave-diff is bounded to `docs/decisions/`, `docs/architecture/state-architecture/`, and the two `reports/` files — exactly the surfaces named in the findings.

#### Validate-Fixes Self-Check (Wave 1)

Self-check performed after all 11 fixes applied. Per finding:

| Finding | Self-check outcome |
|---|---|
| F-1 | ✅ All four ADRs (0001-0004) now list the previously-missing siblings under `### Related ADRs`. ADR-0005 already complete per original audit. |
| F-2 | ✅ Line 167 of `state-architecture/README.md` now reads `- **UDOC convention skill:** \`~/.claude/skills/knowledge-doc-format/SKILL.md\``. No `https://github.com/...` remains in `docs/architecture/` or `docs/decisions/`. |
| F-3 | ✅ ADR-0004 §"Backend stack" no longer contains the ASCII block — replaced with bullet-summary + pointer. `rendering.md §3 "The stack"` retained as SoT (untouched diagram). |
| F-4 | ✅ ADR-0005 §"Architecture-visible structure" no longer contains the ASCII state-diagram — replaced with bullet-summary + pointer. `triangle-fsm.md §3 + §5` retained as SoT. Drifted-wording duplicate eliminated. |
| F-5 | ✅ All 7 sites anglicised; remaining German strings are intentional (Spec citations + Decision-History audit-trail entries that describe the prior German wording). |
| F-6 | ✅ All 5 ADRs carry `**Status:** Accepted`; ADR-index Status column shows `Accepted` for all 5 rows; each ADR's Decision-History has a fresh `### 2026-05-14 — Accepted` entry above the `Initial proposal` block. |
| F-7 | ✅ All 12 arch-docs carry `status: Accepted` in frontmatter. No `status: Skeleton` remains. |
| F-8 | ✅ No `## N.` / `## N+1.` / `## N+2.` heading remains in the arch-doc directory. Per-file numbering verified by inspection (e.g. `cross-module-cascade.md`: §1–§11 concrete → §12 Info Gaps, §13 Change, §14 References). |
| F-9 | ✅ ADR-0001 §"Module inventory" now shows the compact id+axis table + pointer to `modules.md §7.1`. `modules.md §7.1` (lines 268-289) untouched. Decision-History entry records the relocation. |
| F-10 | ✅ ADR-0001 §"Failure Modes" now condensed (patterns a, b, c, e, h, i, m, n + ADR-local ProGuard mitigation, each ≤4 lines, each with `→ see forbidden-patterns.md §3 (…)`). ADR-0002 §"Failure Modes" adds (j, k, l) + condenses (f, g) — pattern bundle for this ADR per F-10 routing. ADR-0004 §"Failure Modes" condenses (d, f, j, k, l, m). `forbidden-patterns.md §3` untouched as SoT. |
| F-11 | ✅ All 5 ADRs now have `## Supersede Triggers (Forward-Looking Notes)` as a sibling-of-Decision-History section between `## References` and `## Decision History`. `## Decision History` contains only audit-log entries (Initial proposal + new Accepted + new Block-0 doc-set cleanup). |
| F-12 | ✅ ADR-0005:344 (now renumbered after Status/History edits) carries `ADR-0005`. |
| F-13 | ✅ effects-and-failures.md §4 main snippet uses `runCatching { … }.onFailure { … }`. The bullet-list label below the snippet updated to match (`**runCatching { … }.onFailure**`). The two remaining `try/catch` occurrences in the file (§5 narrative example, §10 `shutdown()` helper) are out of F-13 scope. |
| F-14 | ✅ `state-architecture/README.md` has a new section `## Walkthrough Decision Tree (Plan §4.0.6.4)` between the existing "Plan → topic-page map" and "Properties this Architecture Guarantees" sections, with the 4-question structure (WHERE / WHAT / TESTING / ANCHORS). |

**Side-effects scan:**
- F-3 / F-4 / F-9 / F-10 pointer-anchors: verified that the target headings exist in the architecture-doc SoT pages (`rendering.md#3-the-stack`, `triangle-fsm.md#3-the-three-modes`, `triangle-fsm.md#5-the-seven-transitions-t1t7`, `modules.md#71-module-inventar-13-active--1-phase-2-stub`, `forbidden-patterns.md#3-the-14-forbidden-patterns`). GitHub auto-anchor rules respected.
- F-8 renumbering — verified no internal anchors to old `N.`-suffixed sections exist (grep `#n-information-gaps` returns 0 matches).
- F-11 section-move: no other doc references the old `### Phase-2 Superseding Expectations` anchor inside Decision History (grep returns 0 inbound).
- F-6 / F-7 status-flip: no test or CI step parses these status values (Block 0 is docs-only).

No new issues surfaced during self-check. Wave is ready for orchestrator-commit.

---

## Block Deviation Summary

⏳ to be consolidated after C0 + Block-Validate

---

## Block Closeout (Orchestrator)

> Only the orchestrator fills this section. Agents leave it empty.

- **All chunks complete (5-step workflow done — adapted to 3 steps for docs-only):** ⏳
- **Block-Validate converged (2-topic audit + sanity-pass + repair-waves done):** ⏳
- **AUDIT-PLAN-AND-API + AUDIT-CONVENTION green:** ⏳
- **Build/Lint green at block-end:** N/A (no code touched)
- **Issue index reconciled (all ids closed/postponed/forwarded):** ⏳
- **Conventions section filled:** ⏳
- **Deviation list propagated to plan/state:** ⏳
- **Cross-block-API consumer info forwarded to Block 1:** ⏳ (B1 reads `docs/decisions/0001..0005` + `docs/architecture/state-architecture/*` as binding contract per plan §4.0.4)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
**Cross-reference set in state file:** ⏳
**Postponed issues forwarded to phase 4 aggregate:** ⏳
