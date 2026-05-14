# Validated Findings — Block 0

**Agent-ID:** B0-VAL-SANITY
**Date:** 2026-05-14
**Source audits:**
- `./reports/audit-plan-and-api-B0.md` (B0-AUDIT-PLAN-AND-API — 0 Crit / 1 Imp / 6 NTH)
- `./reports/audit-convention-B0.md` (B0-AUDIT-CONVENTION — 0 Crit / 5 Imp / 8 NTH)

`logic` + `test` topics N/A for B0 (docs-only block).

## Summary

| Verdict | Critical | Important | Nice-to-have | Total |
|---|---|---|---|---|
| 🟢 valid + auto-fixable | 0 | 6 | 5 | **11** |
| 🟡 valid + research-needed | 0 | 0 | 0 | **0** |
| ❌ eliminated | 0 | 0 | 0 | **0** |

After dedup of the two cross-audit overlaps (status-Skeleton + status-Proposed), the 7 + 13 = 20 raw findings collapse to **11 unique findings**. Per D3 (every severity gets fixed in this run), all 11 are routed to a single repair-wave.

**Repair-wave recommendation:** **1 wave** covers all 11 🟢 findings (mechanical doc-edits, no research needed). No 🟡 → no second wave. Estimated total: **1 repair-wave**.

**Repair status (Wave 1, applied 2026-05-14 by B0-VAL-REPAIR):** all 11 findings → `fixed`. Details under each finding below; consolidated table in block-report § "Block-Validate Repair Wave 1".

| Finding | Status | Files touched |
|---|---|---|
| F-1 (P-XREF) | fixed | ADR-0001, ADR-0002, ADR-0003, ADR-0004 — `### Related ADRs` blocks now bidirectionally complete |
| F-2 (P-PLACEHOLDER) | fixed | `state-architecture/README.md:167` — `https://github.com/...` → tilde-path |
| F-3 (P-SSoT) | fixed | ADR-0004 — backend-stack ASCII removed, pointer to `rendering.md §3` added |
| F-4 (P-SSoT) | fixed | ADR-0005 — Triangle-FSM ASCII removed, pointer to `triangle-fsm.md §3 + §5` added |
| F-5 (P-LANG) | fixed | 7 sites anglicised; Spec citations preserved as load-bearing pointers |
| F-6 (P-STATUS — ADRs) | fixed | All 5 ADRs `Proposed → Accepted` + Decision-History `Accepted` entry; ADR-index updated |
| F-7 (P-STATUS — arch-docs) | fixed | All 12 arch-docs `status: Skeleton → Accepted` |
| F-8 (P-PLACEHOLDER) | fixed | 11 arch-doc files (README excluded — no `N.` tail) — concrete section numbers substituted |
| F-9 (P-SSoT) | fixed | ADR-0001 — 13-module inventory compacted to id + axis table + pointer to `modules.md §7.1` |
| F-10 (P-SSoT) | fixed | ADR-0001, ADR-0002, ADR-0004 — Failure-Modes condensed + cross-references to `forbidden-patterns.md §3` |
| F-11 (P-STRUCTURE) | fixed | All 5 ADRs — `Phase-2 Superseding Expectations` moved out of `## Decision History` into new top-level `## Supersede Triggers (Forward-Looking Notes)` section |
| F-12 (singleton) | fixed | ADR-0005:344 — `ADR-5` → `ADR-0005` |
| F-13 (singleton) | fixed | `effects-and-failures.md §4` — `try/catch` → `runCatching { … }.onFailure { … }` |
| F-14 (singleton) | fixed | `state-architecture/README.md` — new `## Walkthrough Decision Tree (Plan §4.0.6.4)` section added between "Plan → topic-page map" and "Properties this Architecture Guarantees" |

## Cross-cut patterns

Three systemic patterns emerged across the two audits — they inform fix-bundling for the repair-wave:

1. **SSoT duplication ADR ↔ architecture-doc (P-SSoT)** — three findings (F-3 / F-4 / F-9) flag the same anti-pattern: an ASCII diagram or inventory block lives in both the binding ADR *and* the teaching architecture-doc. The fix template is identical across all three: keep the long form in the architecture-doc (lesson SoT), reduce the ADR to a 3-6-line summary + `> See <path>` pointer. Convention to establish: **ADR carries the binding contract in compact prose; architecture-doc carries the full diagrams + tables.** A fourth instance (F-10, 14 forbidden-patterns) follows the same template but with 3 SoTs (plan §4.0.1.5 + forbidden-patterns.md + per-ADR Failure-Modes) instead of 2 — same fix-shape, just one more place to abridge.

2. **Status-field uniformity across the doc-set (P-STATUS)** — two findings (F-6 ADRs `Proposed`, F-7 arch-docs `Skeleton`) both surface the same underlying question: *Block 0 is framed as a "binding pre-code contract" — should that be reflected in the status fields?* Both findings recommend the same closeout action (bump ADRs to `Accepted`, bump arch-docs to `Implementer-ready` or `Accepted`). Bundle as one editorial decision applied across 17 files (5 ADRs + 1 ADR-index row block + 12 arch-docs).

3. **Language convention drift in supposedly-English docs (P-LANG)** — F-5 catches German body-words leaking into English ADRs/arch-docs (`Send-Mode-Varianten`, `Silent-Skip-Schutz`, `Pflicht`, `SOLID-Verifikation`, `Android-FGS-Pflicht`, `Code-Review Pflicht`). Mechanical find-and-replace across 7 known sites. Citations of German Spec-section names stay as-is (load-bearing pointers). F-11 (NTH) is an *opportunistic-gloss* extension of the same pattern — add an English gloss `(Module Inventory)` after German citation strings to help non-German readers.

Two minor sub-patterns worth noting but not bundle-worthy:
- **Placeholder substitutions** — the literal-`N` section numbers (F-8) + the placeholder GitHub URL (F-2) are both "template tokens never replaced". Mechanical sweep.
- **Inter-ADR cross-reference graph completion** (F-1) — single-finding pattern, but mechanically uniform across 4 ADR files (3 of them need 2 extra cross-refs, 1 needs 3).

## Findings

### F-1 — Inter-ADR universal cross-reference graph incomplete (was AUDIT-PLAN-AND-API-B0-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `docs/decisions/0001-state-modular-orchestrator-pattern.md:293-296`, `0002-state-cross-module-cascade.md:287-289`, `0003-service-foreground-pipeline-architecture.md:292-294`, `0004-ui-layout-catalog-motionlayout.md:337-339`
- **Description:** Plan §4.0.1.0.2 closing sentence demands "Jede ADR §References trägt die anderen vier ADRs als Cross-Reference (bidirektional)". Plan §4.0.3 acceptance criterion reads this as universal. The current implementation honours only the **direct-edge** subset of the relationship graph:
  - ADR-0001 lists ADR-0002, 0003, 0004 — **ADR-0005 missing**.
  - ADR-0002 lists ADR-0001, 0005 — **ADR-0003 + ADR-0004 missing**.
  - ADR-0003 lists ADR-0001, 0005 — **ADR-0002 + ADR-0004 missing**.
  - ADR-0004 lists ADR-0001, 0005 — **ADR-0002 + ADR-0003 missing**.
  - ADR-0005 alone is complete.
- **Suggested fix (mechanical):** In each of the 4 ADRs above, under `## References` → `### Related ADRs`, add the missing ADR cross-references as one-line entries:
  - **ADR-0001:** append `- [ADR-0005 — UI Triangle-FSM (Keyboard/Widget/Hover)](0005-ui-triangle-fsm-keyboard-widget-hover.md) — view-mode FSM that drives this orchestrator's view-mode-derived state.`
  - **ADR-0002:** append `- [ADR-0003 — Service & Foreground-Pipeline Architecture](0003-service-foreground-pipeline-architecture.md) — FGS that hosts the orchestrator running these cascades.` and `- [ADR-0004 — UI LayoutCatalog + MotionLayout](0004-ui-layout-catalog-motionlayout.md) — rendering layer that observes cascade-driven state transitions.`
  - **ADR-0003:** append `- [ADR-0002 — Cross-Module Cascade](0002-state-cross-module-cascade.md) — cascade-protocol the orchestrator running inside this FGS enforces.` and `- [ADR-0004 — UI LayoutCatalog + MotionLayout](0004-ui-layout-catalog-motionlayout.md) — UI layer the service-hosted orchestrator publishes state to.`
  - **ADR-0004:** append `- [ADR-0002 — Cross-Module Cascade](0002-state-cross-module-cascade.md) — cascade-protocol whose outcomes this rendering layer must observe consistently.` and `- [ADR-0003 — Service & Foreground-Pipeline Architecture](0003-service-foreground-pipeline-architecture.md) — FGS lifecycle that bounds renderer initialization + teardown.`

  Mirror existing entry style (id + short title + one-line "what link to this ADR means" phrase). No body-text changes; only `## References` section is touched.
- **Domain bundle candidate:** P-XREF (single-pattern bundle, 4 files)

### F-2 — Placeholder GitHub URL in architecture-doc README (was AUDIT-PLAN-AND-API-B0-5 + AUDIT-CONVENTION-B0-6, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/README.md:167`
- **Description:** Reference reads `- [knowledge-doc-format skill](https://github.com/...) (UDOC convention)` — literal `https://github.com/...` placeholder. Skill has no public URL; other ADRs reference the skill via tilde-path `~/.claude/skills/knowledge-doc-format/SKILL.md`.
- **Suggested fix (mechanical, 1-line edit):** Replace line 167 with `- **UDOC convention skill:** \`~/.claude/skills/knowledge-doc-format/SKILL.md\``.
- **Domain bundle candidate:** P-PLACEHOLDER (with F-8)

### F-3 — Backend-stack ASCII diagram duplicated in ADR-0004 + rendering.md (was AUDIT-CONVENTION-B0-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `docs/decisions/0004-ui-layout-catalog-motionlayout.md:157-194` ⟷ `docs/architecture/state-architecture/rendering.md:67-103`
- **Description:** Same ASCII "backend stack" diagram in both files, with a one-line wording drift ("dispatches state to every backend" vs "collects state, dispatches to backends"). SSoT violation per knowledge-doc-format §"SSoT — anti-redundancy rule".
- **Suggested fix:** Slim `0004-ui-layout-catalog-motionlayout.md:157-194` to a 4-line textual summary + a pointer:
  ```
  ### Backend stack

  Three rendering backends sit below `RenderEngine`:
  - **MotionLayoutBackend** — applies MotionScene transitions to the keyboard view.
  - **SlotRenderer** — paints individual button slots inside the active layout.
  - **OverlayBackend** — drives the floating overlay window (transparent surface above the IME).

  All three share `LayoutCatalog` as the layout-id resolver. Full diagram + rationale:
  see [state-architecture/rendering.md §3 "The stack"](../architecture/state-architecture/rendering.md#3-the-stack).
  ```
  Keep `rendering.md §3` as the SoT (untouched). Add a small note in the ADR's `## Decision History` row indicating diagram was relocated to rendering.md (so the move is auditable).
- **Domain bundle candidate:** P-SSoT (with F-4, F-9, F-10)

### F-4 — Triangle-FSM ASCII diagram duplicated + drifted in ADR-0005 + triangle-fsm.md (was AUDIT-CONVENTION-B0-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md:188-233` ⟷ `docs/architecture/state-architecture/triangle-fsm.md:69-113`
- **Description:** KEYBOARD / WIDGET / HOVER triangle + T1–T7 arrows duplicated in both files; copies have **already drifted** in wording (`Send-mode variants` ↔ `Send-Mode-Varianten`, `InputConnection alive` ↔ `InputConnection LIVE`, etc.). The drift itself proves the SSoT-rule was already lost.
- **Suggested fix:** Apply the same template as F-3:
  1. Keep `triangle-fsm.md:69-113` as SoT — but **first fix the German leakage** (covered in F-5: replace `Send-Mode-Varianten` → `Send-mode variants` in this file).
  2. Replace `0005-ui-triangle-fsm-keyboard-widget-hover.md:188-233` ASCII block with a compact textual summary:
     ```
     ### State diagram — three modes, seven transitions

     Three view modes (`KEYBOARD`, `WIDGET`, `HOVER`) and seven transitions (T1–T7).
     T1: KEYBOARD → WIDGET (user opens widget pref).
     T2: WIDGET → KEYBOARD (user closes widget pref).
     T3: KEYBOARD/WIDGET → HOVER (focus loss + InputConnection alive).
     T4: HOVER → KEYBOARD (focus regain, widget pref unset).
     T5: HOVER → WIDGET (focus regain, widget pref set).
     T6: HOVER → KEYBOARD (InputConnection dies in HOVER — failsafe).
     T7: any → KEYBOARD (mandatory reset on IME-restart).

     Full state diagram + truth-table + per-transition example:
     see [state-architecture/triangle-fsm.md §3-§5](../architecture/state-architecture/triangle-fsm.md#3-state-diagram).
     ```
  3. Add Decision-History entry recording the relocation.
- **Domain bundle candidate:** P-SSoT (with F-3, F-9, F-10)

### F-5 — German-language leakage in English ADRs + arch-docs (was AUDIT-CONVENTION-B0-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files (8 sites):**
  - `docs/architecture/state-architecture/triangle-fsm.md:74` — `Send-Mode-Varianten`
  - `docs/architecture/state-architecture/rendering.md:433` — `Silent-Skip-Schutz`
  - `docs/architecture/state-architecture/wiring-ui.md:280` — `Silent-Skip-Schutz`
  - `docs/architecture/state-architecture/adding-a-button.md:107` — `Silent-Skip-Schutz`
  - `docs/architecture/state-architecture/cross-module-cascade.md:53` — `Code-Review Pflicht`
  - `docs/decisions/0003-service-foreground-pipeline-architecture.md:226` — `Android-FGS-Pflicht`
  - `docs/decisions/0001-state-modular-orchestrator-pattern.md:37` — `SOLID-Verifikation`
- **Description:** Body-prose German terms in English-mandated docs (per `~/.claude/snippets/docs/language-conventions.md`). German Spec-section *citations* (e.g. `Modul-Inventar` in References) are defensible as load-bearing pointers — those stay; **descriptive body prose is replaced.**
- **Suggested fix (mechanical, find-and-replace):**
  - `Send-Mode-Varianten` → `Send-mode variants`
  - `Silent-Skip-Schutz` → `silent-skip protection` (all 3 sites — consistent term)
  - `Code-Review Pflicht` → `code-review requirement`
  - `Android-FGS-Pflicht` → `Android FGS requirement`
  - `SOLID-Verifikation` → `SOLID verification`
- **Domain bundle candidate:** P-LANG

### F-6 — ADR status `Proposed` vs binding-contract framing (was AUDIT-PLAN-AND-API-B0-7 + AUDIT-CONVENTION-B0-13, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have (treated together with F-7 as the P-STATUS closeout decision)
- **Files:**
  - `docs/decisions/README.md:69-73` (Status column — 5 rows)
  - `docs/decisions/0001-state-modular-orchestrator-pattern.md` `**Status:**` header + Decision History
  - `docs/decisions/0002-state-cross-module-cascade.md` (same)
  - `docs/decisions/0003-service-foreground-pipeline-architecture.md` (same)
  - `docs/decisions/0004-ui-layout-catalog-motionlayout.md` (same)
  - `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md` (same)
- **Description:** All 5 ADRs carry `**Status:** Proposed`. Plan §4.0.3 acceptance demands "Status Accepted"; plan §4.0.1.0.3 lifecycle clause clarifies "Proposed during Block 0; Accepted after Plan-Approval". Block-0 audit-consolidation **is** the closeout marker — this is the point at which to flip the status. The "binding pre-code contract" framing in plan §4.0 + AUDIT-CONVENTION-B0-13 both point at this. Leaving them Proposed lets downstream blocks claim "ADR isn't accepted yet, I can deviate" — opposite of the intent.
- **Suggested fix (mechanical, 11 edits):**
  1. **ADR-Index** (`docs/decisions/README.md:69-73`): change Status column for all 5 rows from `Proposed` to `Accepted`.
  2. **Per-ADR header** (5 files, line typically 3-5 of each ADR): replace `**Status:** Proposed` with `**Status:** Accepted`.
  3. **Per-ADR Decision History** (5 files): append a new entry **above** the existing `### 2026-05-14 — Initial proposal` block (chronological order, newest first per `knowledge-adr-format` §"Decision History entry format"):
     ```
     ### 2026-05-14 — Accepted

     **Trigger:** Block-0 audit-consolidation pass (B0-VAL-SANITY) — plan §4.0 binding-pre-code-contract closeout.

     **Before:** Status: Proposed (per §4.0.1.0.3 lifecycle clause "Proposed during Block 0").

     **After:** Status: Accepted (body now append-only per knowledge-adr-format §"Lifecycle and editing rules").

     **Reasoning:** Block-0 acceptance criteria from plan §4.0.3 met; B0-AUDIT-PLAN-AND-API + B0-AUDIT-CONVENTION pass; ADR binds downstream Blocks 1b…6 per plan §4.0.4 "Bindender-Vertrag-Charakter".
     ```
- **Domain bundle candidate:** P-STATUS (with F-7) — one decision, ~17 edits

### F-7 — Architecture-doc status `Skeleton` mismatches substantive content (was AUDIT-PLAN-AND-API-B0-4 + AUDIT-CONVENTION-B0-10, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have (treated together with F-6 as the P-STATUS closeout decision)
- **Files:** All 12 architecture-doc files (`docs/architecture/state-architecture/*.md`) — frontmatter `status:` field
- **Description:** All 12 arch-docs carry `status: Skeleton`. Per `~/.claude/snippets/docs/lifecycle-research.md` + `knowledge-doc-format` skill, `Skeleton` is the earliest exploratory state. Actual content is ~3500 LoC of tutorial-grade material with full diagrams, code examples, and walkthroughs — well past Skeleton. With F-6 flipping the ADRs to Accepted at Block-0-closeout, the parallel move for arch-docs is `Accepted` (matches the binding-contract framing) or `Implementer-ready` (matches knowledge-doc-format's "stable enough for handoff" lifecycle marker).
- **Suggested fix (mechanical, 12 edits — pick one option):** Bulk find-and-replace `status: Skeleton` → `status: Accepted` across all 12 files. Recommendation: `Accepted` (matches F-6 ADRs; signals that Block-0 closeout is also the doc-set acceptance event). Alternative if a softer term is preferred: `status: Implementer-ready`. **Consistency between the two options matters more than which one is picked** — pick once for all 12.
- **Domain bundle candidate:** P-STATUS (with F-6)

### F-8 — Literal `N` / `N+1` / `N+2` section numbers in arch-docs (was AUDIT-CONVENTION-B0-8)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** All 12 architecture-doc files (`docs/architecture/state-architecture/*.md`)
- **Description:** Each arch-doc ends with three literal-`N` headings (`## N. Information Gaps`, `## N+1. Change History`, `## N+2. References`) — taken verbatim from the knowledge-doc-format skill template. The substitution-to-concrete-integer step was missed. Other sections use concrete integers (`## 1.` through `## 10/11/12.`).
- **Suggested fix (mechanical, per file):** For each of the 12 files: count the highest concrete section number above the tail block, then renumber:
  - `## N. Information Gaps` → `## {highest+1}. Information Gaps`
  - `## N+1. Change History` → `## {highest+2}. Change History`
  - `## N+2. References` → `## {highest+3}. References`

  Example: in a doc whose last concrete section is `## 7. ...`, the tail becomes `## 8. Information Gaps`, `## 9. Change History`, `## 10. References`. Verify with `grep -c '^## ' <file>` to count headings; verify by inspection that the integer right above the tail is `7` (or whatever the local number is).

  Cross-doc anchors: a grep confirmed no doc references `#n-information-gaps` (auto-anchors of the literal headings), so no inbound-link updates needed.
- **Domain bundle candidate:** P-PLACEHOLDER (with F-2)

### F-9 — 13-module inventory duplicated ADR-0001 + modules.md §7.1 (was AUDIT-CONVENTION-B0-12)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `docs/decisions/0001-state-modular-orchestrator-pattern.md:172-194` ⟷ `docs/architecture/state-architecture/modules.md:268-289`
- **Description:** 13-active-modules + 1-Phase-2-stub inventory listed with identical descriptions in both files. SSoT applies (same pattern as F-3, F-4). The ADR binds the contract ("13 active + 1 Phase-2 stub" count, plus per-module axis); the architecture-doc carries the auditable matrix + observer flags.
- **Suggested fix:** In `0001-state-modular-orchestrator-pattern.md:172-194`, compact the inventory to a 1-line-per-module format (id + axis-name) and add a pointer:
  ```
  | # | ModuleId | Axis it owns |
  |---|----------|---------------|
  | 1 | `Recording` | recording lifecycle |
  | 2 | `Pipeline` | pipeline state machine |
  ... (13 + 1 rows) ...

  Full descriptions, observer flags, and Action+Effect inventories:
  see [state-architecture/modules.md §7.1 — Module inventory](../architecture/state-architecture/modules.md#71-module-inventory).
  ```
  Keep `modules.md §7.1` (lines 268-289) as the SoT — untouched.
  Add Decision-History entry recording the relocation.
- **Domain bundle candidate:** P-SSoT (with F-3, F-4, F-10)

### F-10 — 14 forbidden patterns duplicated in plan + forbidden-patterns.md + per-ADR Failure-Modes (was AUDIT-CONVENTION-B0-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:**
  - `docs/architecture/state-architecture/forbidden-patterns.md` (SoT — keep as-is)
  - `docs/decisions/0001-state-modular-orchestrator-pattern.md:261-287` (§Failure Modes — patterns a, b, c, e, h, i, m, n)
  - `docs/decisions/0002-state-cross-module-cascade.md` (§Failure Modes — patterns g, j, k, l)
  - `docs/decisions/0004-ui-layout-catalog-motionlayout.md` (§Failure Modes — patterns d, f)
- **Description:** 14 forbidden-pattern descriptions live in 3 places: plan §4.0.1.5 (acknowledged plan-text SoT), `forbidden-patterns.md` (canonical catalog), and per-ADR `§Failure Modes` (each ADR re-describes its routed subset paragraph-length). The plan + `forbidden-patterns.md` overlap is already documented (forbidden-patterns.md §1.1 acknowledges plan §4.0.1.5 as SoT). The ADR re-descriptions are the redundancy to remove.
- **Suggested fix (medium, 3 files):** Per ADR (`0001`, `0002`, `0004`):
  - For each pattern entry currently in `### Failure Modes`, condense the existing paragraph to a 2-sentence summary (problem + symptom) + `→ see [forbidden-patterns.md §3.({letter})](../architecture/state-architecture/forbidden-patterns.md#3{letter}-...)`.
  - **Mitigation cross-references** (regression-test pointers, banner comments) stay in the ADR — they are this-ADR-specific.
  - Long examples + rationales + alternatives **stay only in** `forbidden-patterns.md`.

  Template for the condensed ADR entry:
  ```
  **(a) Reducer with side-effects** — reducer mutates state-flow-listener, log-sink, or DB outside the (state, action) → state contract. Symptom: non-deterministic state, race-prone tests. → see [forbidden-patterns.md §3.a](../architecture/state-architecture/forbidden-patterns.md#3a-reducer-with-side-effects). Mitigation: `ReducerPurityTest` (Spec 1 §11.4.2) + banner comment in `DictateOrchestrator.dispatchInternal`.
  ```
  Add Decision-History entry to each touched ADR recording the abridgement.
- **Domain bundle candidate:** P-SSoT (with F-3, F-4, F-9)

### F-11 — Phase-2 Superseding Expectations misplaced inside `## Decision History` (was AUDIT-CONVENTION-B0-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files (5 ADRs):**
  - `docs/decisions/0001-state-modular-orchestrator-pattern.md:331-348`
  - `docs/decisions/0002-state-cross-module-cascade.md:324-341`
  - `docs/decisions/0003-service-foreground-pipeline-architecture.md:329-351`
  - `docs/decisions/0004-ui-layout-catalog-motionlayout.md:376-393`
  - `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md:388-408`
- **Description:** All 5 ADRs place `### Phase-2 Superseding Expectations` as a sub-section *inside* `## Decision History`, sibling to `### 2026-05-14 — Initial proposal`. Per `knowledge-adr-format` skill §"Decision History" + §"Lifecycle and editing rules": Decision History is the **append-only audit log of how the ADR got to its current state**, with entries in Trigger/Before/After/Reasoning shape — forward-looking speculation does not fit. The skill specifically says: "ADR is **always current**: the body above represents the present state; History records how it got there." Mixing forward-looking content into append-only history blocks future revisions if the supersede happens (you'd be rewriting predictions, violating append-only).
- **Suggested fix (mechanical, 5 ADRs):** Move each `### Phase-2 Superseding Expectations` block **out of** `## Decision History` and into a new top-level section `## Supersede Triggers (Forward-Looking Notes)` placed **between** `## References` and `## Decision History`. Keep the content verbatim; only the parent heading + position change. Update the ADR's frontmatter (no), the table of contents (none present) — no other ripple.
  - After move, with F-6's Accepted-status flip, this content is correctly outside the append-only body — future-supersede mechanics can revise it via Decision-History entries (the standard append-only flow), without violating the rule.
- **Domain bundle candidate:** P-STRUCTURE (single-pattern bundle, 5 files)

### F-12 — `ADR-5` shorthand in ADR-0005 References (was AUDIT-CONVENTION-B0-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md:344`
- **Description:** Related-Plan reference cites `§4.0.1.0 (ADR-5 decision-kernsatz)`. Every other inter-ADR reference uses 4-digit form `ADR-0005`. Holdover from pre-Block-0 plan body.
- **Suggested fix (mechanical, 1-line):** Replace `ADR-5` with `ADR-0005` on line 344.
- **Domain bundle candidate:** none — standalone

### F-13 — Effects-and-failures.md syntax drift `try/catch` vs plan's `runCatching` (was AUDIT-PLAN-AND-API-B0-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/effects-and-failures.md:120-137`
- **Description:** Plan §4.0.1.3 specifies the `EffectFailure`-emit shape using `runCatching { … }.onFailure { … }` Kotlin-stdlib idiom. The architecture-doc shows the equivalent `try { … } catch (t: Throwable) { … }` form. Semantically equivalent; stylistically divergent. A reader copying from the teaching material will diverge from plan §4.0.1.3 by one stylistic step.
- **Suggested fix (mechanical, one snippet):** Replace the `try/catch` block in §4 with the plan's form:
  ```kotlin
  runCatching {
      typedModule.runEffect(effect, services)
  }.onFailure { failure ->
      orchestrator.dispatch(
          Action.EffectFailure(
              originModuleId = moduleId,
              cause = failure,
              effect = effect,
          )
      )
  }
  ```
  Add a one-line note after the snippet: "Semantically equivalent to `try { … } catch (t: Throwable) { … }`; the `runCatching` idiom matches plan §4.0.1.3 and avoids the boilerplate of catching `Throwable` directly."
- **Domain bundle candidate:** none — standalone

### F-14 — Plan §4.0.6.4 general decision-tree fragmented across README + walkthroughs (was AUDIT-PLAN-AND-API-B0-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/README.md` (add a new section)
- **Description:** Plan §4.0.6.4 is the canonical 4-question walkthrough-decision-tree ("WO LEBT DAS NEUE FEATURE?" → walkthrough pick; "WAS BERÜHRT ES?" → state/actions/effects/UI/cross-module; "WAS MUSS GETESTET WERDEN?" → 4 test-types; "WO WERDEN INLINE-ANKER GESETZT?" → 3 anchor-types). Partially materialised across README "Who reads what" + "Plan → topic-page map" tables + individual walkthrough "When this walkthrough applies" sections. No single page reproduces the 4-question structure verbatim.
- **Suggested fix (~30 lines added to README):** Add a new section to `docs/architecture/state-architecture/README.md` (between the existing "Plan → topic-page map" and "Properties this Architecture Guarantees" sections) titled `## Walkthrough Decision Tree (Plan §4.0.6.4)`:
  ```
  ## Walkthrough Decision Tree (Plan §4.0.6.4)

  Four questions, in order. Each leads to one or more topic pages in this directory.

  ### 1. Where does the new feature live?
  - **A new button on an existing layout?** → see [adding-a-button.md](adding-a-button.md).
  - **A new sub-keyboard variant?** → see [adding-a-sub-keyboard.md](adding-a-sub-keyboard.md) (Variant A: declarative-only; Variant B: with module).
  - **A new module owning its own state axis?** → see [adding-a-module.md](adding-a-module.md).

  ### 2. What does the feature touch?
  - **State?** Action shape + reducer entry → see [state-and-actions.md](state-and-actions.md) + [modules.md](modules.md).
  - **Effects?** Effect declaration + failure routing → see [effects-and-failures.md](effects-and-failures.md).
  - **UI rendering?** LayoutCatalog entry + MotionLayout transition → see [rendering.md](rendering.md) + [wiring-ui.md](wiring-ui.md).
  - **Cross-module behaviour?** Mode 1 or Mode 2 cascade — Mode 3 is forbidden → see [cross-module-cascade.md](cross-module-cascade.md).

  ### 3. What needs testing?
  Four test types: reducer-purity, effect-runner, cascade-flow, UI-integration. Each walkthrough's `## Testing` section lists the relevant types.

  ### 4. Where do inline anchors go?
  Three anchor types per `~/.claude/CLAUDE.md` "Inline-Anker-Konvention": module-header `@see <ADR>`, gotcha comments, plan-section pointers. Checklist in [adding-a-module.md §8](adding-a-module.md#8-inline-anchors).
  ```
- **Domain bundle candidate:** none — standalone

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|
| AUDIT-PLAN-AND-API-B0-3 | block-audit (plan-and-api topic) | **False positive — plan-treue holds.** The finding flags that ADRs lack `## Implementation Notes` and `## Open Questions` sections. The audit's checklist comes from a broader ADR-skeleton convention; the **plan §4.0.1.0.3 explicitly enumerates 14 mandatory sections**, and neither `Implementation Notes` nor `Open Questions` appears in that list. The ADRs satisfy the plan's narrower spec. Per `knowledge-adr-format` §"Required sections", the skill names `Research / Context / Decision / Alternatives / Consequences / References / Decision History` — also no `Implementation Notes` or `Open Questions`. Both authorities agree these sections are optional. No fix required; the gap is between audit-prompt and plan-spec, not between docs and plan. |
| (implicit) Plan §4.0.1.0.3 "12 sections" vs skill's 7-section structure | routing-hint flagged by AUDIT-CONVENTION § Out-of-scope | **False positive — already documented inline-fix.** Plan §4.0.1.0.3 *itself* mandates "gemäß `knowledge-adr-format` skill" in its preamble. The 12-table content items are all present, just organised under skill-prescribed headings (Decision + Required Mechanics subsection carry the bulk of items §3-§7). The implementer documented this as an inline-fixed plan-deviation in the B0-report `### Plan deviations` table per D22 (small/mid). No new fix needed. |
| (implicit) `Subsystem:` + `Scope: Project-Wide` co-presence in all 5 ADRs | routing-hint flagged by AUDIT-CONVENTION coverage notes | **False positive — plan-mandate overrides skill convention.** Plan §4.0.1.0 lines 267-269 *explicitly* mandates both `Subsystem: <state/service/ui-rendering/ui-mode>` AND `Scope: Project-Wide` co-presence: "Alle fünf gleichzeitig mit `Scope: Project-Wide`-Hinweis im Header, weil sie auch andere Subsysteme binden." The `knowledge-adr-format` skill calls these "mutually exclusive", but the plan-mandate is the binding contract for *this* project + *this* batch of ADRs (5 ADRs that bind globally while being authored within a single subsystem). The skill explicitly says (line 82) "Subsystem values come from the project's CLAUDE.md … added there *first*" — i.e. project-local convention can extend the skill. This is exactly that case. No fix needed; the implementer made the right call. |
| AUDIT-CONVENTION-B0-9 | block-audit (convention topic) | **False positive — intentional design + already documented as plan-deviation.** The finding flags that `docs/architecture/state-architecture/README.md` doesn't use `§1 Vision / §2 Properties` skeleton. The B0-report `### Plan deviations` table line 163 documents this as "intentional inline-fixed judgment call (D22)": *"A README is a directory-index, not a UDOC topic; forcing UDOC-§2 on it would dilute the convention."* The `knowledge-doc-format` skill defines a separate "Code-Pattern README" sub-type with a different skeleton — which is what this file effectively is. The finding's option (b) — add a `type: Architecture-Index` discriminator — is reasonable polish, but at NTH severity and against an already-documented deliberate deviation, it would re-litigate a judgment call already made. Keep as-is. |
| AUDIT-CONVENTION-B0-11 | block-audit (convention topic) | **False positive — defensible as-is.** The finding suggests adding English glosses after German Spec-section citations (`[Spec 1 §15 — Modul-Inventar / Module Inventory]`). The German citations are load-bearing pointers (Spec is in German per language-conventions.md — `Archived plans keep the language they were written in`). Adding parenthetical English glosses is *opportunistic polish*, not a defect. The pattern is consistent across all References sections of the doc-set (always German section title verbatim, no gloss). Changing only some bullets would create *new* inconsistency. The audit itself calls it "small … augment ... where helpful" — that's not a closeout-blocking issue. If the doc-set is later translated to English-only, the citations migrate alongside the translated Spec. No fix this run. |

**Eliminated count:** 5 (3 from AUDIT-PLAN-AND-API + AUDIT-CONVENTION cross-referenced topics; 2 standalone from AUDIT-CONVENTION).

## Deduplication record

| Merged finding | Source IDs | Rationale |
|---|---|---|
| F-2 | AUDIT-PLAN-AND-API-B0-5 + AUDIT-CONVENTION-B0-6 | Both flag the identical placeholder URL on README.md:167. |
| F-6 | AUDIT-PLAN-AND-API-B0-7 + AUDIT-CONVENTION-B0-13 | Both flag the same `Status: Proposed` issue across all 5 ADRs + index. Combined fix is the same: bump to Accepted at closeout. |
| F-7 | AUDIT-PLAN-AND-API-B0-4 + AUDIT-CONVENTION-B0-10 | Both flag `status: Skeleton` across all 12 arch-docs. AUDIT-CONVENTION's "bulk Skeleton → Implementer-ready" recommendation aligns with AUDIT-PLAN-AND-API's "Skeleton → Initial draft" — pick `Accepted` to match F-6 (binding-contract framing). |

## Bundle plan for repair-wave

To minimize file churn during the single repair-wave, group the 11 findings into bundles by file-set + change-type. Recommended execution order (descending priority within the wave):

1. **Bundle P-LANG** (F-5) — 7 sites across 7 files, mechanical find-and-replace. Apply first so subsequent SSoT-relocations (F-4) carry the English form forward.
2. **Bundle P-SSoT** (F-3, F-4, F-9, F-10) — touches 3 ADRs + 3 arch-docs. Approach: keep the arch-doc as SoT (untouched) per F-3/F-4/F-9 spec; abridge the ADR with pointer + Decision-History note per F-10 spec.
3. **Bundle P-STRUCTURE** (F-11) — 5 ADRs, move `Phase-2 Superseding Expectations` blocks out of `## Decision History` into new `## Supersede Triggers` sections.
4. **Bundle P-XREF** (F-1) — 4 ADRs, append missing cross-references to `## References` → `### Related ADRs`.
5. **Bundle P-PLACEHOLDER** (F-2, F-8) — 1 README line + 12 arch-doc tail-renumberings.
6. **Bundle P-STATUS** (F-6, F-7) — Final step: 17 status-flips + 5 Decision-History entries. Done last so prior bundles' Decision-History-entries are appended *before* the Accepted-flip (preserves the chronological log).
7. **Singletons** (F-12, F-13, F-14) — three small standalone edits.

Total estimated file edits: ~30 files touched (5 ADRs + ADR-index + 12 arch-docs + 1 arch-doc README's new section). Single commit per the docs-only block's commit convention.
