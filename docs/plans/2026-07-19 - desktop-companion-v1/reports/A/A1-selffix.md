# Chunk A1 — Self-Fix (fresh eyes, diff-based) report

**Chunk:** A1 (Block A) · **Role:** SELF-FIX (fresh eyes) · **Timestamp:** 2026-07-20T00:40:00+02:00
**Wave commit reviewed:** cc4f217 · **Scope (CHUNK_FILES):** the 8 plan-scoped ADR drafts under
`adrs/` + the 3 concept-research docs moved into `research/` (bestandsaufnahme, konzept-skizze,
fragenkatalog). The 5 specs + `reports/e2e-runbook.md` in the same commit belong to separate
spec agents (already existed, only checked in here) and are out of A1's authoring scope.

## What I did

Fresh-eyes review of the author-only A1 chunk against Plan §6 (ADR-Drafts), §3 D4.1 (concept-doc
move) and the `knowledge-adr-format` skill. No fixes were required — the chunk is clean. Verification
was structural (docs-only chunk: no production code, no unit tests, no meaningful build/test target;
the worktree additionally holds A2's uncommitted in-progress module extraction, so a `./gradlew`
run would exercise A2's incomplete work, not A1).

## Review — three lenses

**Plan correctness (Plan §6 + §3 D4.1 + D5):**
- All 8 required slugs present, one file each, filenames carry no number, header `# ADR-NNNN:`
  placeholder in all 8, Status `Proposed (plan-scoped — pending promotion)` in all 8.
- All 7 mandatory `knowledge-adr-format` H2 sections present and in order in all 8
  (Research → Context → Decision → Alternatives Considered → Consequences → References →
  Decision History). Consequences carry all three groups (Positive / Negative / Failure Modes).
- Each Decision-History initial entry is dated `2026-07-20` (matches header Date) and carries all
  four fields (Trigger / Before / After / Reasoning).
- The 4 Project-Wide ADRs (`shared-ai-module`, `secret-store`, `config-entity-model`, `peer-catalog`)
  each carry a `### Scope of this Convention` subsection, as the skill requires; the 4 subsystem-scoped
  ADRs use `**Subsystem:**` headers instead. Mutually exclusive, correct.
- Core decisions match §6 and are congruent with §3 incl. D5 (spot-checked shared-ai-module:
  D5.a mirror-enums / no `:shared-ai`→`:shared` edge, D5.d `PromptTypeClassifier` stays in `:app`,
  D5.e `AmplitudeProcessor` move — all reflected in Decision/Alternatives/Failure-Modes).
- D4.1 executed: the 3 concept docs live in `research/`, `tmp/desktop-concept/` is deleted, and the
  ADRs cite the post-move `research/…` paths. All research citations resolve on disk (8/8 OK).
- Plan ↔ ADR back-reference present in all 8 (each References section links
  `desktop-companion-v1.md`); the plan side reciprocates via §6's ADR table.

**Doc quality (ADR bar):**
- Alternatives Considered are substantive with per-option rejection reasoning (not skeletal).
- Consequences distinguish Negative (accepted trade-offs) from Failure Modes (footguns) correctly.
- Cooperates-with / revision blockquotes used appropriately: 5 ADRs carry a `Cooperates with`
  blockquote; `desktop-review` uses a tailored `Revises a sub-aspect of ADR-0013/ADR-0027`
  blockquote (not a wholesale `Status: Supersedes`) — the correct modelling, since neither parent
  ADR is replaced wholesale, only the "review is IME-only" clause; `secret-store` / `config-entity-model`
  legitimately omit the blockquote (they resolve-a-defer / touch rather than extend a sister ADR).

**Test quality:** N/A — author-only docs chunk, no code, no tests.

## Deviations by the implementer — reviewed, both defensible (D4)

1. ADR Research/References cite `research/…` instead of `tmp/desktop-concept/…` — required, because
   A1 itself moves those files and deletes `tmp/desktop-concept/`; citing `tmp/` would be dead links.
   Confirmed: 0 `tmp/desktop-concept` references remain inside `adrs/`.
2. `adr-desktop-review` models a *partial revision* with reciprocal cross-references instead of a
   formal `Status: Supersedes`. Correct per `knowledge-adr-format` (Supersede is for wholesale
   reversal; here only one clause of ADR-0013/ADR-0027-F8 changes).

Both are accurately documented in the impl report; nothing to correct.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

No architecture conflicts, no cross-chunk concerns, nothing delegated.

## Note (informational, not a finding, out of scope)

Residual `tmp/desktop-concept/` strings survive in the **plan file** (`desktop-companion-v1.md`,
e.g. L221/L836–838) and `reports/e2e-runbook.md` L56. These are narrative prose describing A1's
task and the pre-move source of the concept material, not live hyperlinks, and both files are
outside A1's authoring scope (the plan file is orchestrator-owned and per D4 stays in
`~/.claude/plans/` until archival; the e2e-runbook belongs to a separate agent). The impl report's
claim "no dead tmp links remain" holds for the ADRs, which is A1's deliverable. No action taken.

## Inline fixes applied

None — the chunk passed all three lenses.

## Files modified

None.

## Files outside my assigned scope (drift)

None.

## Test-run result

Not applicable — author-only docs chunk (no production code, no unit tests). Structural
verification only, as above; all checks green.
