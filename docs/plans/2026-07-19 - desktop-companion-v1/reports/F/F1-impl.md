# F1 — ADR-Promotion + Doku + Abnahme (impl report)

**Chunk:** F1 (Block F) · **Date:** 2026-07-20 · **Scope:** docs/promotion only (no production code).

## What I did

Promoted the 8 plan-scoped ADR drafts to numbered, Accepted ADRs (`0028`–`0035`) via `git mv` (history preserved), resolved all sibling slug cross-references to numbers, added index rows + bidirectional Plan↔ADR links, and appended reciprocal Decision-History entries to every extended/revised ADR (`0012`/`0013`/`0014`/`0015`/`0017`/`0020`/`0027`). Updated `CLAUDE.md` (module topology, `:shared-ai`, SecretStore + entity conventions), `docs/DATABASE-PATTERNS.md` (new SQLDelight-Parity section + ToC), created `companion/README.md`, and wrote the manual Windows acceptance checklist (§2 criteria 3/4/7).

## Number assignment (per plan §6 table order)

| Draft slug | Promoted | Header | Extends/revises (reciprocal note added) |
|---|---|---|---|
| adr-shared-ai-module | **0028** | Scope: Project-Wide | ADR-0015 |
| adr-secret-store | **0029** | Scope: Project-Wide | ADR-0017 (§F-3 resolution) |
| adr-config-entity-model | **0030** | Scope: Project-Wide | ADR-0012 |
| adr-desktop-dictation-host | **0031** | companion, audio-pipeline, state | ADR-0017 |
| adr-desktop-panel-ui | **0032** | companion, ui | — |
| adr-desktop-review | **0033** | companion, ai, state, ui | ADR-0013, ADR-0027 |
| adr-peer-catalog | **0034** | Scope: Project-Wide | ADR-0017 |
| adr-companion-history-parity | **0035** | companion, data | ADR-0014, ADR-0020 |

All 8 set `Status: Accepted`, each with a "Promoted and accepted" Decision-History entry.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Reciprocal notes added to more ADRs than the two named | CONTEXT_NOTES named only 0015 + 0017 | Also added reference bullets + Decision-History entries to 0012/0013/0014/0020/0027 | The drafts' own References promise "a Decision-History note is added there at promotion" for these; omitting them leaves one-way links (acceptance: "no dead doc links", bidirectional cross-links) | none (F1 is terminal) | ✓ |
| Fixed pre-existing dead links in the plan's §12 References | not in brief | `tmp/desktop-concept/*` → `research/*` (A1 moved+deleted tmp); `adrs/` pointer → "git log --follow" note (folder emptied by promotion) | Those paths no longer exist; acceptance requires no dead doc links | none | ✓ |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

## Scope decision — spec/report slug references left as-is

The research specs (`research/*.md`) and historical chunk reports (`reports/**`) still mention sibling ADRs by slug in prose/inline-code (e.g. `` `adr-desktop-review` ``). These are **not** clickable markdown links (verified: no `](...adrs/adr-*.md)` link exists), so they do not 404. Per `lifecycle-adr` the rewriting of plan/spec links to promoted paths happens at **archival**, not promotion; leaving them avoids churn now. The plan's own `§12 References` and `§6` describe the drafts and were updated to point at the promoted numbers.

## Verification performed

- No residual `ADR-NNNN` placeholder or backticked `` `adr-slug` `` inside any promoted ADR (grep clean).
- Every `ADR-NNNN` referenced by the 8 promoted ADRs resolves to an existing file (0001–0035 checked).
- Every README index link target (`0028`–`0035`) exists on disk.
- Each extended ADR mentions its new ADR ≥2× (reference bullet + history entry) — bidirectional confirmed.
- No clickable markdown links to the moved `adrs/*.md` files remain.
- `git status` shows the 8 moves as renames (history preserved).

## Test run

Docs-only chunk — **no production code changed** (all edits are Markdown: 8 ADR moves, 7 ADR appends, README index, plan, `CLAUDE.md`, `DATABASE-PATTERNS.md`, `companion/README.md`, checklist). No unit/build tests are in scope for F1 (Block F audit lens reduced to plan-and-api per CONTEXT_NOTES); nothing compilable was touched.

## Files modified / created

Promoted (git mv → docs/decisions/): `0028-shared-ai-module.md`, `0029-secret-store.md`, `0030-config-entity-model.md`, `0031-desktop-dictation-host.md`, `0032-desktop-panel-ui.md`, `0033-desktop-review.md`, `0034-peer-catalog.md`, `0035-companion-history-parity.md`.
Extended (append): `0012-…`, `0013-…`, `0014-…`, `0015-…`, `0017-…`, `0020-…`, `0027-…`.
Index: `docs/decisions/README.md`. Plan: `desktop-companion-v1.md` (§12 References). Conventions: `CLAUDE.md`, `docs/DATABASE-PATTERNS.md`. New: `companion/README.md`, `reports/windows-acceptance-checklist.md`. Cross-link: `reports/e2e-runbook.md`.

## Drift (files outside assigned scope)

None. All edits are within docs / the promoted ADR set / the two named integration targets (`docs/decisions/README.md`, `CLAUDE.md`) / the plan's own References. `companion/README.md` is a new doc co-located with the module it documents (not a code edit).

## Pending user action

§2 **criterion 9 (ADR-Vollständigkeit)** is fully met by this chunk: it only requires the 8 §6 drafts to be promoted to `docs/decisions/` + index (ADR-0028–0035), which is done — it is **not** gated on the Windows checklist. What remains pending is the **Windows-only** criteria **3 / 4 / 7**: the acceptance checklist (`reports/windows-acceptance-checklist.md`) is delivered **unchecked** and requires the user to run it on a Windows device and sign off. This cannot be completed by the agent.
