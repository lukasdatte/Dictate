# Chunk A1 — ADR-Drafts (all 8) + Concept-Research + Specs check-in — IMPL+TEST report

**Chunk:** A1 (Block A) · **Agent:** IMPL+TEST · **Timestamp:** 2026-07-20T00:40:00+02:00
**Report author role:** author-only chunk (no production code, no unit tests).

## What I did

1. **Moved** the three concept documents from `tmp/desktop-concept/`
   (`bestandsaufnahme.md`, `konzept-skizze.md`, `fragenkatalog.md`) into the plan's
   `research/` directory (D4.1) and **removed** `tmp/desktop-concept/` — it is now
   obsolete. (`tmp/` itself is left empty; git does not track empty dirs.)
2. **Authored the 8 plan-scoped ADR drafts** under `adrs/`, each to the full
   `knowledge-adr-format` structure (Research → Context → Decision → Alternatives →
   Consequences[Positive/Negative/Failure Modes] → References → Decision History with a
   dated initial-proposal entry), filenames carrying **no number**, header
   `# ADR-NNNN:` placeholder, Status `Proposed (plan-scoped — pending promotion)`,
   and a plain-language summary up front (jargon explained on first use) for the
   colleague who reads the promoted ADR without plan context.
3. The five specs + `reports/e2e-runbook.md` already lived in the plan folder and are
   checked in together with this chunk (no action needed beyond leaving them in place).

Decision content is congruent with Plan §3 **including D5**: D1 (`:shared-ai`),
D5.a (mirror enums / no `:shared-ai`→`:shared` edge), D5.b (D3 owns entity tables,
E1→D3), D5.c (D1a-before-D1b split), D5.d (`PromptTypeClassifier` stays in `:app`),
D5.e (`AmplitudeProcessor` moves), D5.f (WorkManager — referenced in scope notes only,
belongs to E2), D5.g (`received_texts` retirement), and the F1–F34 fragenkatalog
decisions each ADR cites.

## ADR ↔ existing-ADR relationships captured (per Plan §6)

| Draft | Relationship encoded |
|---|---|
| `adr-shared-ai-module` | extends ADR-0015 (Cooperates-with); reuses ADR-0016 enum doctrine; ADR-0024 (pills stay app) |
| `adr-secret-store` | resolves ADR-0017 §F-3 plaintext defer; reuses ADR-0018 `available` pattern; ADR-0015 ceiling |
| `adr-config-entity-model` | new foundation; touches ADR-0024 (prompt type), ADR-0012 (resolution via Profile), reuses ADR-0016 |
| `adr-desktop-dictation-host` | extends ADR-0017 role model (Cooperates-with); reuses ADR-0001/0009/0007/0012/0013/0018 |
| `adr-desktop-panel-ui` | desktop analogue of ADR-0004/0027 (Cooperates-with); reuses ADR-0018/PlatformModule |
| `adr-desktop-review` | **revises** the "review is IME-only" sub-aspect of ADR-0013 / ADR-0027-F8 (partial, both cross-referenced) |
| `adr-peer-catalog` | extends ADR-0016/0025 (additive family) and ADR-0017/0020 (new config-only authority direction; dictations excluded F16) |
| `adr-companion-history-parity` | extends ADR-0014/0020 (parity + filter equivalence for desktop sessions) |

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| ADR Research sections cite `research/…` paths, not `tmp/desktop-concept/…` | Plan §6 ("Research zitiert `tmp/desktop-concept/`-Recherche") | Citations point to the post-move location `docs/plans/…/research/{bestandsaufnahme,konzept-skizze,fragenkatalog}.md` | A1 itself moves those files into `research/` and deletes `tmp/desktop-concept/` (D4.1); citing `tmp/` would be dead links immediately after this chunk | None — links resolve after the move | Yes (documented) |
| `adr-desktop-review` uses `Supersedes: —` + a "Revises a sub-aspect" blockquote rather than `Status: Supersedes ADR-NNNN` | Plan §6 ("**revidiert** … Supersede-Teilaspekt, beide referenzieren") | Modelled as a *partial revision* with reciprocal cross-references, not a wholesale supersede | Neither ADR-0013 nor ADR-0027 is replaced wholesale — only the "review render surface is IME-only" clause changes; a full supersede would misrepresent the still-valid ambiguity-mode / render-host decisions | At promotion, ADR-0013 + ADR-0027 each get a Decision-History note pointing here | Yes (documented) |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

No architecture conflicts, no blocking issues, nothing delegated.

## Inline fixes applied

None required (author-only chunk; no code).

## Files modified

- **Created** (8 ADR drafts):
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-shared-ai-module.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-secret-store.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-config-entity-model.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-desktop-dictation-host.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-desktop-panel-ui.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-desktop-review.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-peer-catalog.md`
  - `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-companion-history-parity.md`
- **Moved** (tmp → research, D4.1):
  - `research/bestandsaufnahme.md` (from `tmp/desktop-concept/`)
  - `research/konzept-skizze.md` (from `tmp/desktop-concept/`)
  - `research/fragenkatalog.md` (from `tmp/desktop-concept/`)
- **Removed:** `tmp/desktop-concept/` (directory, now obsolete)
- **Report:** `docs/plans/2026-07-19 - desktop-companion-v1/reports/A/A1-impl.md` (this file)

## Files outside my assigned scope (drift)

None. All edits are within the plan folder's `adrs/`, `research/`, and `reports/A/`.
The `git status` renames under `shared-ai/`, `app/build.gradle`, `settings.gradle` are
the **parallel A2 chunk's** work in the shared worktree — file-disjoint from mine, not
touched by this agent.

## Test-run result

Not applicable — author-only chunk (no production code, no unit tests). No
`build_command` / `test_command` run is meaningful for a docs-only change. Structural
verification instead:

- 8 ADR files, filenames carry no number, header `# ADR-NNNN:` placeholder present in all 8.
- Status line `Proposed (plan-scoped — pending promotion)` present in all 8.
- All 7 mandatory H2 sections present in all 8; `**Positive:**` / `**Negative:**` /
  `**Failure Modes:**` each exactly once; `## Alternatives Considered` present; dated
  `### 2026-07-20 — Initial proposal` history entry present.
- The four Project-Wide ADRs each carry a `### Scope of this Convention` subsection.
- No dead `tmp/desktop-concept` links remain (grep = 0 hits).
- `research/` holds all 8 docs (5 specs + 3 concept docs); `tmp/desktop-concept/` gone.

## Helper decisions

None (no code).
