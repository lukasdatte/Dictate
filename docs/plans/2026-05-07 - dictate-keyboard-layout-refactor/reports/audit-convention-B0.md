# Audit Report: convention (Block 0, scope: full-block)

**Agent-ID:** B0-AUDIT-CONVENTION
**Date:** 2026-05-14
**Knowledge skills used:** knowledge-adr-format (loaded), knowledge-doc-format (loaded)
**Files inspected:** 22 (5 ADRs + ADR-README + 12 architecture docs incl. README + 4 back-reference targets in plan §8.1 + Spec 1/2/3 §12)

Files (absolute paths):
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/decisions/README.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/decisions/0001-state-modular-orchestrator-pattern.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/decisions/0002-state-cross-module-cascade.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/decisions/0003-service-foreground-pipeline-architecture.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/decisions/0004-ui-layout-catalog-motionlayout.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/README.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/state-and-actions.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/modules.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/effects-and-failures.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/cross-module-cascade.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/rendering.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/wiring-ui.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/triangle-fsm.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/adding-a-module.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/adding-a-button.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/adding-a-sub-keyboard.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor/docs/architecture/state-architecture/forbidden-patterns.md`
- Plan §8.1 (back-references) and Spec 1/2/3 §12 (back-references) diff'd against `0df6557`.

## Summary

- Critical: 0
- Important: 5
- Nice-to-have: 8

The 18 docs are remarkably uniform in shape: identical ADR header skeleton, identical UDOC frontmatter shape, identical `## N. Information Gaps / ## N+1. Change History / ## N+2. References` tail. Every ADR carries the mandated `Research → Context → Decision → Alternatives Considered → Consequences → References → Decision History` order. Every architecture-doc carries `§1 Vision → §2 Properties (…) Guarantees → §3+ body → §N Information Gaps → §N+1 Change History → §N+2 References`. Cross-references between ADRs are dense and reciprocal. The bulk of the Important findings concern **SSoT duplication between an ADR and its companion architecture-doc** (diagrams + section content duplicated near-verbatim), **Phase-2 Superseding Expectations placed inside Decision History** (deviates from skill: Decision History is the append-only past-state log, not forward-looking notes), and **German-language leakage into English docs** (mostly via verbatim quotation of German Spec-section names — partially defensible, partially not). Nice-to-have items cover a placeholder URL, an inconsistent ADR-ID shorthand, literal `N`/`N+1`/`N+2` section numbering, and the broken-link pattern in one of the README references. No Critical findings — the mandatory shape is intact across every file.

## Findings

### AUDIT-CONVENTION-B0-1

- **Severity:** Important
- **File:** `docs/decisions/0001-state-modular-orchestrator-pattern.md:331-348`, `0002-state-cross-module-cascade.md:324-341`, `0003-service-foreground-pipeline-architecture.md:329-351`, `0004-ui-layout-catalog-motionlayout.md:376-393`, `0005-ui-triangle-fsm-keyboard-widget-hover.md:388-408`
- **Description:** All five ADRs place a `### Phase-2 Superseding Expectations` sub-section **inside** `## Decision History`, sibling to the `### 2026-05-14 — Initial proposal` entry. The `knowledge-adr-format` skill defines Decision History as the **append-only audit log of how the ADR got to its current state** with entries in `### YYYY-MM-DD — <title>` form using **Trigger / Before / After / Reasoning** (§"Decision History" + §"Lifecycle and editing rules"). A forward-looking "what could supersede this ADR" sub-section does not fit that shape — it predicts the future, it doesn't record the past. The skill explicitly says: "ADR is **always current**: the body above represents the present state; History records how it got there."
- **Why it matters:** A future maintainer reading the Decision-History expects an audit trail. Mixing in forward-looking speculation conflates "what happened" with "what might happen". When this ADR enters Accepted status (body becomes append-only per the skill), the speculative content arguably should not be append-only — supersedes happen, and rewriting predictions after the fact would violate the append-only rule. The information is valuable; the location is wrong.
- **Suggested fix scope:** small (5 ADRs, mechanical move). Move the sub-sections into a new top-level section, e.g. `## Future Considerations` or `## Supersede Triggers` placed between `## References` and `## Decision History`. Alternatively merge their content into the body's `## Decision` / `## Consequences` "what we'd consider in Phase 2" notes.
- **Suggested fix:** Move each `### Phase-2 Superseding Expectations` block out of `## Decision History` and into a new `## Supersede Triggers (Forward-Looking Notes)` section sited between `## References` and `## Decision History`. Keep the content verbatim; only the heading anchor changes.

### AUDIT-CONVENTION-B0-2

- **Severity:** Important
- **File:** `docs/decisions/0004-ui-layout-catalog-motionlayout.md:157-194` ⟷ `docs/architecture/state-architecture/rendering.md:67-103`
- **Description:** The "Backend stack" ASCII diagram is duplicated near-verbatim in ADR-0004 §"Backend stack" and rendering.md §3 "The stack". `diff` shows a one-line wording difference ("dispatches state to every backend" vs "collects state, dispatches to backends"); everything else is identical. The `knowledge-doc-format` skill §"SSoT — anti-redundancy rule" mandates: "One piece of information lives in exactly one place. Code is the **anchor**, doc is the **truth**." The skill's anti-pattern table specifically lists "Architecture Walkthrough that duplicates Type Model" as redundant; the same logic applies to ADR-vs-architecture-doc.
- **Why it matters:** Two diagrams in two files means two sources of truth. When the stack is refactored (e.g. a fourth backend is added, or `SlotRenderer` is renamed), both files must be edited. The Iteration Log of one will likely fall behind the other, and a future reader will not know which is canonical. The ADR is supposed to be the *terse* contract — the architecture-doc is the *detailed* lesson. Carrying the same diagram in both inverts the relationship.
- **Suggested fix scope:** small (one file edit). The ADR is the binding contract; ASCII diagrams of the implementation shape belong in the architecture-doc. Replace the diagram in ADR-0004 §"Backend stack" with a one-line pointer (`> See [state-architecture/rendering.md §3 "The stack"](../architecture/state-architecture/rendering.md#3-the-stack)`) plus a 4-line **textual** summary (top → middle → leaves → shared helper). The full ASCII art stays in rendering.md as the SoT.
- **Suggested fix:** Slim the ADR-0004 "Backend stack" block to a paragraph pointing at rendering.md §3. Keep rendering.md §3 as-is (it's already the right home).

### AUDIT-CONVENTION-B0-3

- **Severity:** Important
- **File:** `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md:188-233` ⟷ `docs/architecture/state-architecture/triangle-fsm.md:69-113`
- **Description:** The Triangle-FSM ASCII diagram (KEYBOARD / WIDGET / HOVER with T1–T7 arrows) is duplicated in both files. Worse, the two copies have **drifted in wording**: ADR uses "Send-mode variants" / "InputConnection alive" / "no widget-pref"; triangle-fsm.md uses "Send-Mode-Varianten" (German!) / "InputConnection LIVE" / "no widget-pref". The diff is mostly cosmetic but reveals that the two diagrams were edited independently — the SSoT rule has already failed.
- **Why it matters:** Identical pictures with different captions/wording is the worst kind of duplicate-truth: a reader spotting the difference can't tell which is authoritative without checking commit history. Compare AUDIT-CONVENTION-B0-2 for the same pattern on a different concept; the consistent recurrence suggests this is a doc-author-level convention question, not just an isolated slip.
- **Suggested fix scope:** small (one file edit). Same approach: keep the full diagram in triangle-fsm.md (the lesson); reduce the ADR's diagram to a 6-line textual summary of the 7 transitions or a 3-line pointer. Decide once for the doc-set and apply consistently across all ADR/architecture-doc pairs.
- **Suggested fix:** Pick one home (recommended: architecture-doc), make the other side a `> See ...` pointer.

### AUDIT-CONVENTION-B0-4

- **Severity:** Important
- **File:** `docs/architecture/state-architecture/triangle-fsm.md:74` (`- Send-Mode-Varianten`); `docs/architecture/state-architecture/rendering.md:433` ("Silent-Skip-Schutz"); `docs/architecture/state-architecture/wiring-ui.md:280` ("Silent-Skip-Schutz"); `docs/architecture/state-architecture/adding-a-button.md:107` ("Silent-Skip-Schutz"); `docs/architecture/state-architecture/cross-module-cascade.md:53` ("Code-Review Pflicht"); `docs/decisions/0003-service-foreground-pipeline-architecture.md:226` ("Android-FGS-Pflicht"); `docs/decisions/0001-state-modular-orchestrator-pattern.md:37` ("SOLID-Verifikation").
- **Description:** Several non-citation German terms appear in body prose of supposedly English ADRs + architecture-docs: `Send-Mode-Varianten`, `Silent-Skip-Schutz` (the English half "Silent-Skip-" + German "Schutz" = "Protection"), `Pflicht` (= "duty/requirement"), `SOLID-Verifikation`. The `~/.claude/snippets/docs/language-conventions.md` rule is clear: "Documentation files (e.g. `docs/architecture/`, subsystem READMEs) are written in **English** directly" + "Code comments and identifiers: English". ADRs are explicitly listed as English by the project convention. (The Spec-§ citation strings like `Cross-Module-Effect-Modi` are arguably defensible — the Spec is in German, so citing a German section title verbatim is the only way to point at it. But `Send-Mode-Varianten` is descriptive body prose inside an ASCII diagram, not a citation.)
- **Why it matters:** A consistent English doc-corpus is easier for new readers + future internationalisation. Mixed-language prose looks like an incomplete migration. The `Silent-Skip-Schutz` form (mongrel English/German compound) is the worst — readers can't even guess what "Schutz" means.
- **Suggested fix scope:** small (mechanical replacement). Replace each German body-word with its English equivalent: `Silent-Skip-Schutz` → `silent-skip protection`; `Pflicht` → `mandate` / `requirement`; `Verifikation` → `verification`; `Send-Mode-Varianten` → `Send-mode variants`. Leave German-section-name citations as-is (they're load-bearing pointers into the German Spec).
- **Suggested fix:** Find/replace each instance; the project's `Silent-Skip-Schutz` term should be translated everywhere it appears (consistency).

### AUDIT-CONVENTION-B0-5

- **Severity:** Important
- **File:** `docs/architecture/state-architecture/README.md:42-52` (Topic-pages table cross-doc claims) ⟷ `docs/architecture/state-architecture/forbidden-patterns.md` (lists 14 patterns a–n) ⟷ ADRs 0001/0002/0004 (each lists its **subset** of patterns under §"Failure Modes")
- **Description:** The 14 forbidden patterns (a)–(n) are described **in full** in three places: (1) plan §4.0.1.5 (the SoT per plan-text), (2) `forbidden-patterns.md` (one full description per pattern with example + rationale + alternative), and (3) the relevant ADR's §"Failure Modes" section (each ADR re-describes its subset, e.g. ADR-0001 §"Failure Modes" has multiple paragraph-length entries for patterns (a, b, c, e, h, i, m, n)). Comparing ADR-0001 §"Failure Modes" lines 261-287 with forbidden-patterns.md §3.(a)–(n) shows substantially overlapping content — same examples, same rationales, sometimes worded slightly differently. The `knowledge-doc-format` SSoT-rule applies.
- **Why it matters:** Three places to keep in sync when a 15th pattern is added or an existing pattern's rationale evolves. The plan §4.0.1.5 + forbidden-patterns.md split is already documented (forbidden-patterns.md §1.1 acknowledges plan §4.0.1.5 as the SoT). The ADRs' §"Failure Modes" should be the abridged "what could go wrong in *this* ADR's subsystem"; the canonical catalog should live in forbidden-patterns.md.
- **Suggested fix scope:** medium. Each ADR's §"Failure Modes" rewrites the bullet for each pattern as a 1–2 sentence summary + `→ see forbidden-patterns.md §(x)` pointer. The full example + rationale + alternative stays in forbidden-patterns.md. Mitigation cross-references (regression tests, banner comments) stay in the ADR.
- **Suggested fix:** Per ADR, condense each Failure-Mode bullet to ~2 sentences + a pointer. The catalogue keeps the long form.

### AUDIT-CONVENTION-B0-6

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/README.md:167`
- **Description:** Placeholder URL in the References section: `- [knowledge-doc-format skill](https://github.com/...) (UDOC convention)`. The `https://github.com/...` is literally three dots — a broken/incomplete link. Either the skill has a public URL (then put it here) or the link should be removed / pointed at a relative path like `~/.claude/skills/knowledge-doc-format/SKILL.md` (consistent with how the ADRs reference their skill: ADR-0001:303 `- **Skill:** `~/.claude/skills/knowledge-adr-format/SKILL.md``).
- **Why it matters:** A reader clicking the link gets a 404. Broken links degrade trust in the doc-set as a whole and are an easy lint-catch.
- **Suggested fix scope:** small (one-line edit). Either delete the line or replace with the canonical tilde-path used by ADRs: `- **Skill:** `~/.claude/skills/knowledge-doc-format/SKILL.md``.
- **Suggested fix:** Replace with `- **UDOC convention skill:** `~/.claude/skills/knowledge-doc-format/SKILL.md``.

### AUDIT-CONVENTION-B0-7

- **Severity:** Nice-to-have
- **File:** `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md:344`
- **Description:** The Related-Plan reference cites `§4.0.1.0 (ADR-5 decision-kernsatz)` — using the short form `ADR-5` instead of the canonical `ADR-0005`. Every other inter-ADR link in the doc-corpus uses the 4-digit form (`ADR-0001`, `ADR-0002`, ...). The plan body now also uses the 4-digit form post-Block-0 edit (§8.1). `ADR-5` is a holdover from the pre-Block-0 plan when ADRs were referenced as `ADR-1..5`.
- **Why it matters:** Trivial readability — readers can mentally resolve `ADR-5` = `ADR-0005`, but consistency is the convention the rest of the corpus follows. A grep for `ADR-0005` misses this reference.
- **Suggested fix scope:** small. Replace `ADR-5` with `ADR-0005` in that one location.
- **Suggested fix:** `§4.0.1.0 (ADR-0005 decision-kernsatz)`.

### AUDIT-CONVENTION-B0-8

- **Severity:** Nice-to-have
- **File:** All 12 architecture-doc files (`docs/architecture/state-architecture/*.md`)
- **Description:** Each architecture-doc ends with literally-numbered "N" placeholders: `## N. Information Gaps`, `## N+1. Change History`, `## N+2. References`. The literal "N" is taken directly from the `knowledge-doc-format` skill's templates (the skill writes `## N. Information Gaps` as a placeholder). But the convention is for the **template** to use `N`; in the **rendered doc**, `N` should be replaced with the actual sequential number (e.g. `## 8. Information Gaps` in modules.md where the previous section is §7 and there are 10 numbered sections total). Compare: every other section in these docs is numbered with a concrete integer (`## 1.` through `## 10.` / `## 11.` / `## 12.`); only the trailing three sections keep the literal "N".
- **Why it matters:** Section anchors render with the literal heading text (`#n-information-gaps`). Cross-document links would have to use that anchor, which becomes confusing. The skill template uses "N" because it doesn't know how many sections any given doc will have; the author is supposed to substitute the actual number. Across 12 docs, this is uniform — so consistency itself is fine — but the substitution was missed.
- **Suggested fix scope:** small (mechanical, per file). For each architecture-doc, count the numbered sections above and renumber: `## N. Information Gaps` → `## 8. Information Gaps` (or whichever index follows the last real section). Update the auto-anchor links if any internal references use `#n-information-gaps` (a quick grep found none — the docs cross-reference by topic-page link, not by anchor).
- **Suggested fix:** Per file: count sections, replace `N` / `N+1` / `N+2` with concrete integers.

### AUDIT-CONVENTION-B0-9

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/README.md` (12 architecture-doc index — no `§1 Vision`, no `§2 Properties`, no numbered sections at all)
- **Description:** The README is the only architecture-doc that does **not** use the `§1 Vision and Motivation / §2 Properties` skeleton. It uses topic-area headings only: `## Who reads what / ## Topic pages / ## High-level architecture in 60 seconds / ## Plan → topic-page map / ## Properties this Architecture Guarantees / ## References`. The implementer correctly identified (per B0 report §"Plan deviations") that "A README is a directory-index, not a UDOC topic; forcing UDOC-§2 on it would dilute the convention." This **is** the right call, but it makes the README itself the only inconsistent member of the directory. The `knowledge-doc-format` skill explicitly defines a "Code-Pattern README" sub-type (§"Code-Pattern README" lines 266+) with a different skeleton — the architecture-doc README could lean on that pattern explicitly.
- **Why it matters:** Either the README adopts the architecture-doc shape (with §1, §2, etc.), or it makes clear via a frontmatter `type:` discriminator that it's an index file. Currently the frontmatter says `type: Architecture` (same as the topic pages), which suggests the same shape applies, but the body diverges. Readers expecting consistency are mildly confused.
- **Suggested fix scope:** small. Either (a) refit the README into the §1/§2/§N skeleton (so §1 Vision = "What this directory contains", §2 Properties = the existing "Properties this Architecture Guarantees" section, etc.), or (b) change the frontmatter `type:` to `type: Architecture-Index` and add a 1-line README-§"This is an index file, not a topic page; sections differ from the rest of the directory" disclaimer.
- **Suggested fix:** Option (b) is the lower-touch fix.

### AUDIT-CONVENTION-B0-10

- **Severity:** Nice-to-have
- **File:** All 12 architecture-doc frontmatter blocks
- **Description:** The architecture-docs all set `status: Skeleton` in the frontmatter. The `knowledge-doc-format` skill lists status values: `Skeleton | Research | Spec — programmer-ready, no invented detail | Implementer-ready | Accepted | Superseded by ...`. `Skeleton` is the **earliest** state — used for "exploratory, body not yet fleshed out". The actual content of these 12 docs is far beyond skeletal: ~3500 LOC of detailed tutorial-grade material with full ASCII diagrams, type definitions, code examples, walkthroughs. A truer status would be `Implementer-ready` (i.e. the doc is stable enough for a programmer to consume) or simply `Accepted` once Block 0 lands.
- **Why it matters:** A reader scanning the frontmatter sees `status: Skeleton` and may discount the content as preliminary. The author's intent is "first published version" (= Skeleton in the temporal sense), but the skill's `Skeleton` value implies "still being designed" (= incomplete in the depth sense). Semantic mismatch.
- **Suggested fix scope:** small. Change `status: Skeleton` to `status: Implementer-ready` (or `Accepted` if Block 0 is considered complete after this validation pass).
- **Suggested fix:** Bulk rewrite `status: Skeleton` → `status: Implementer-ready` across the 12 files.

### AUDIT-CONVENTION-B0-11

- **Severity:** Nice-to-have
- **File:** `docs/architecture/state-architecture/state-and-actions.md:340` (References: `[Spec 1 §15 — Modul-Inventar]`), `modules.md:355` (same), and other German-citation references throughout.
- **Description:** References sections often cite German Spec-section titles verbatim (`Modul-Inventar`, `Cross-Module-Effect-Modi`, `Modul-Inventar`). This is defensible — the Spec is in German, citing the section name is the most reliable pointer. But the citation form mixes English and German in the same bullet: `[Spec 1 §15 — Modul-Inventar](url)`. A more reader-friendly form is to add an English gloss: `[Spec 1 §15 — Modul-Inventar / Module inventory](url)`. Compare to References that already use English glosses: `[Spec 1 §4.3 — DictateOrchestrator.dispatchInternal]` (no German section name to translate, naturally English).
- **Why it matters:** Reader who doesn't read German can't infer what "Modul-Inventar" means; the link target is German Spec content anyway, so they may still need help, but the gloss makes the bullet itself readable.
- **Suggested fix scope:** small. Augment German Spec-section citations with English glosses where helpful.
- **Suggested fix:** `[Spec 1 §15 — Modul-Inventar (Module Inventory)](url)` form across References sections.

### AUDIT-CONVENTION-B0-12

- **Severity:** Nice-to-have
- **File:** `docs/decisions/0001-state-modular-orchestrator-pattern.md:172-194` ⟷ `docs/architecture/state-architecture/modules.md:268-289` (§7.1 Module-Inventar)
- **Description:** The 13-module inventory list (RecordingModule, PipelineModule, ..., KeyboardInputModule + InterruptionModule Phase-2 stub) is duplicated in ADR-0001 §"Module inventory" and modules.md §7.1 "Module-Inventar". Both list the same 14 entries with the same descriptions. SSoT applies the same way as in AUDIT-CONVENTION-B0-2 and -3.
- **Why it matters:** Same as those findings — three places to update when a 15th module lands (or when InterruptionModule is promoted from Phase-2 stub).
- **Suggested fix scope:** small. ADR carries the binding contract ("13 active + 1 Phase-2 stub" plus the per-module axis description); architecture-doc carries the auditable matrix + observer flags. ADR could keep a compact 1-line-per-module count + axis name and defer to modules.md for the rest.
- **Suggested fix:** Compact the ADR list to a 1-line-per-module format; the longer descriptions stay in modules.md §7.1.

### AUDIT-CONVENTION-B0-13

- **Severity:** Nice-to-have
- **File:** `docs/decisions/README.md:69-73` (Status column) ⟷ each ADR's `**Status:** Proposed` header
- **Description:** The ADR-index README shows all five rows with `Status: Proposed`. Each ADR body also says `**Status:** Proposed`. This is the **intended** state per plan §4.0.1.0 ("ADRs initially Proposed; Block 0 implementation may accept them or leave as Proposed"). However, the docs already contain extensive Decision-History text that reads as if the design is settled (no iterations recorded after the 2026-05-14 initial entry; Phase-2 Superseding Expectations are written from a "this is the agreed pattern" stance). The `knowledge-adr-format` skill §"Lifecycle and editing rules" distinguishes Proposed (body editable freely) from Accepted (body append-only). If implementers in Block 1b+ will be **bound** by these contracts (per the project's `binding pre-code contract` framing), arguably they should be Accepted now, not Proposed. Otherwise a future maintainer could legitimately argue "Proposed = open for revision".
- **Why it matters:** "Proposed" gives downstream blocks a justification to deviate ("this ADR isn't Accepted yet"). The plan's Block-0 framing (binding contract) is inconsistent with the Status field. Either the framing is aspirational (in which case Proposed is correct and the binding language should soften) or the binding is real (in which case Status should be Accepted before Block 1b starts).
- **Suggested fix scope:** small (5-line edit) once the project decides which framing to keep. Either:
  - Bump all 5 ADRs to `Status: Accepted` at the end of Block 0 validation (and update the index table).
  - Leave at `Proposed`, but explicitly note the binding interpretation: e.g. in `docs/decisions/README.md` add "Proposed ADRs in this project are still binding for downstream code; supersede via new ADR, never silent deviation".
- **Suggested fix:** Take an orchestrator-level decision; either bump Status or document the interpretation. Currently inconsistent.

## Out-of-scope observations

These belong to the `plan-and-api` audit-topic (B0-AUDIT-PLAN-AND-API consolidator picks up):

- **Plan §4.0.1.0.3 mandates "12 sections per ADR"** — the actual ADRs follow the `knowledge-adr-format` skill's `Research / Context / Decision / Alternatives Considered / Consequences / References / Decision History` 7-section structure, with `## Decision` containing the bulk of the "required mechanics" subsections (Scope, Required mechanics, State diagram, ...). The Block-0 report §"Plan deviations" already flags this as an inline-fixed plan-deviation (the agent judged the skill takes precedence on body structure). From a convention-audit lens this is consistent + intentional; from a plan-conformity lens it's a section-count delta. Routing to AUDIT-PLAN-AND-API.

- **Phase-1 ADR coverage of the "12 sections" content items** — even though the headings don't literally enumerate to 12, the content items (decision-kernsatz, scope, mechanics, supersede-expectations, references, ...) appear to be present. Routing to AUDIT-PLAN-AND-API for fine-grained content-vs-list verification.

## Coverage

- Files audited: 18 (5 ADRs + ADR-README + 12 architecture-doc files), plus the 4 back-reference targets via `git diff 0df6557..HEAD`.
- Files skipped (with reason): none (the chunk-scope is exactly the 18 files + 4 back-reference inserts).
- Knowledge-skill checkpoints applied:
  - `knowledge-adr-format` §"Required sections" → all 5 ADRs pass (Research → Context → Decision → Alternatives → Consequences → References → Decision History, exact order).
  - `knowledge-adr-format` §"Subsystem vs Scope (mutually exclusive)" → all 5 ADRs carry **both** `**Subsystem: X**` AND `**Scope: Project-Wide**` (the skill says "mutually exclusive"). This is a literal-reading deviation but the implementer made a deliberate call to set Subsystem (for grep-routing) + Scope (for the "applies project-wide" semantics). The plan §4.0.1.0 explicitly mandates both fields with these exact values. Routing to AUDIT-PLAN-AND-API for the cross-check; from a convention lens, the practice is at least uniform across all 5 ADRs.
  - `knowledge-adr-format` §"Cooperates with" — present in all 5 ADRs as a single blockquote between header and Research. Form matches skill.
  - `knowledge-adr-format` §"Decision History entry format" — present, uses Trigger/Before/After/Reasoning consistently.
  - `knowledge-adr-format` §"Plan References — bidirectional rule" — every ADR's References section names a Related Plan + Related Specs + Related ADRs. Plan §8.1 has been edited to point back to all 5 ADRs (verified via diff). Bidirectional cross-link is intact.
  - `knowledge-adr-format` §"Negative-vs-Failure-Modes distinction" — every ADR's §"Consequences" has three labelled groups (`**Positive:**`, `**Negative:**`, `**Failure Modes:**`) at the same heading level per skill. Distinction preserved.
  - `knowledge-doc-format` §"Frontmatter schema" — all 12 architecture-docs carry the 6-field block (date, author, status, context, related-plan, related-adrs) + 1 extra (`type: Architecture`). Schema-compliant.
  - `knowledge-doc-format` §"§1 Vision and Motivation" — every topic-page has §1 with §1.1/§1.2 sub-sections. README diverges (see AUDIT-CONVENTION-B0-9).
  - `knowledge-doc-format` §"§2 — topic-equivalent" — every topic-page has §2 in form "Properties this Architecture Guarantees" / "Properties this Walkthrough Guarantees" / "Properties this Catalogue Guarantees". The variants are sensible scope adjustments.
  - `knowledge-doc-format` §"GitHub Markdown features" — Alerts (`> [!NOTE]`, `> [!IMPORTANT]`, `> [!CAUTION]`) used 11× total across docs; fenced code-blocks with language tags (`kotlin`, `xml`) used 60+×; tables used liberally for matrices. Coverage is healthy.
  - `knowledge-doc-format` §"SSoT — anti-redundancy rule" — multiple violations (AUDIT-CONVENTION-B0-2, -3, -5, -12) — the doc-set has consistent SSoT-drift between ADR and companion architecture-doc.
  - `~/.claude/snippets/docs/language-conventions.md` — German leakage into English ADRs/arch-docs (AUDIT-CONVENTION-B0-4). Plan + Spec back-reference inserts (plan §8.1 + Spec 1/2/3 §12) are in German, matching the host doc's language. Correct.
  - Path/link conventions — all inter-doc links use relative paths; no absolute filesystem paths found. ADRs link to specs via `../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/.../*.reviewed.md` (correct relative depth from `docs/decisions/`). The architecture-docs link to ADRs via `../../decisions/000N-...md` (correct from `docs/architecture/state-architecture/`).

Phase complete.
