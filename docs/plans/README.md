# Implementation Plans — Archive Index

This directory is the durable home for the project's implementation
plans. Every non-trivial feature, refactor, or migration that runs
through the `implement-long-plan` workflow lands here when its
implementation completes, together with the artefacts that prove and
explain the run (state file, block reports, research/specs).

A plan in this directory is a **historical record**: it is the
single source of truth for *what was built, why, and how it was
verified*. Future maintainers read it (and the ADRs it references) to
understand the shape of the code without access to the conversation
that produced it.

## Folder structure & naming schema

Each archived plan lives in its own dated folder:

```
docs/plans/
├── README.md                                  ← this file (process index, English)
├── YYYY-MM-DD - {name}/                        ← one folder per plan
│   ├── {name}.md                               ← the plan file (working language)
│   ├── {name}.en.md                            ← EN translation (post-archive, by Phase 5b/5c)
│   ├── README.md                               ← per-plan README (archive metadata + comparison)
│   ├── {name}.state.md                          ← orchestrator state file (chunk table, logs)
│   ├── {name}.chunks.json                       ← chunking artefact
│   ├── reports/                                ← block reports, audits, integration, e2e
│   └── research/                               ← plan-co-located research / specs
└── archive/                                    ← legacy flat-file plans (pre-dated-folder era)
```

- **Folder name:** `YYYY-MM-DD - {kebab-or-spaced name}/` — the date is
  the plan's creation date, not its archive date.
- **Plan file:** `{name}.md` (a `.reviewed.md` suffix indicates the
  plan went through a `plan-review` / `plan-quality-gate` pass before
  implementation; that reviewed file is then the SoT the run executed).
- The `research/` and `reports/` subdirectories **archive together
  with their plan** — there is no separate promotion step. Research
  files that were promoted to Spec status (frontmatter
  `status: Spec — programmer-ready`) remain the canonical
  single-source-of-truth for their topic; the plan references them by
  path and does not duplicate their content.

## Per-plan `README.md` convention

Every archived plan folder carries a `README.md` with:

- **Title** — the plan headline (one line).
- **Status** — `Archived YYYY-MM-DD` (+ the implementation outcome).
- **Summary** — 3–5 sentences: what was implemented and why.
- **Comparison context** — what changed vs. the previous state, what
  was deliberately *not* changed, scope boundaries, and (for forked /
  follow-up plans) the bidirectional link to the related plan.
- **Implementation reports** — pointer into `./reports/`.
- **EN translation** — pointer (filled by Phase 5b/5c).
- **Related ADRs** — bidirectional plan ↔ ADR links (see
  `docs/decisions/README.md`).

## Language convention

Per the project's documentation-language policy:

- **This index** and all process documentation are written in
  **English**.
- **Archived plan files** keep the **working language they were
  written in** (German is the working language for this project's
  plans). An English translation `{name}.en.md` is added *after*
  implementation completes (Phase 5b/5c of the
  `implement-long-plan-v2` workflow) — large plans are split into
  `{name}-0-overview.en.md` … `{name}-N-{block}.en.md`.
- **Research / spec files** under `research/` are translated in
  parallel to `research/{topic}.en.md` (no aggregate split).
- **Per-plan `README.md`** files are written in English (archive
  metadata is process documentation).

### Phase 5b/5c language disposition (D4)

A `.en.md` sidecar is produced **only for genuinely German-authored
docs** (the German-working-language → English hand-off the convention
exists for). Docs that are already English-native get a recorded
language attestation instead — a near-verbatim sidecar is the exact
redundant duplication the SSoT / no-redundant-work baseline forbids.
Each per-plan `README.md` carries a **"Language Disposition (Phase
5b/5c)"** section with the per-file audit. Summary across the two
dated-folder plans:

- `2026-05-15 - dictate-cutover-completion` — entirely
  **english-native** (authored by the implement-long-plan agents); **0
  sidecars**, correctly so.
- `2026-05-07 - dictate-keyboard-layout-refactor` — genuinely
  **German-native** (German working-language era). **All 12
  German-native plan-scope docs translated to parity-verified `.en.md`
  — EN-sidecar deliverable CLOSED, 0 outstanding** (wave 1: 6 research
  files; wave 2: the plan-file split per D16 + the 3 specs, ~14,200
  lines of dense technical German); 3 English-native research files
  attested with no sidecar. See that plan's README + state-file
  `plan_lifecycle.en_translation`.

## The `archive/` subdirectory

`docs/plans/archive/` holds **legacy flat-file plans** authored before
the dated-folder schema was adopted (e.g. `ai-abstraction-layer.md`,
`language-chip-curation.md`, `sequential-squishing-sutherland.md`).
These predate the per-plan-folder + README convention and are kept
as-is for historical reference. New plans always use the
`YYYY-MM-DD - {name}/` folder schema described above; the loose
`*.md` / `*.state.md` / `*.chunks.json` files directly under
`docs/plans/` are likewise pre-schema artefacts retained for history.

## Index of archived plans (dated-folder schema)

| Plan | Created | Archived | Status | Summary |
|------|---------|----------|--------|---------|
| [2026-05-07 - dictate-keyboard-layout-refactor](./2026-05-07%20-%20dictate-keyboard-layout-refactor/README.md) | 2026-05-07 | 2026-05-17 | Archived — Phase 4.5/4.6/4.7/5 completed-via the cutover Epic | Service-centred SSOT + 3-mode Triangle-FSM (KEYBOARD/WIDGET/HOVER). Built the entire new state-architecture (DictateOrchestrator + 14 modules + RenderBackends + Overlay) as a **parallel-dormant layer** (946 tests green); Phase-4 escalated **INT-1** (the new layer never drove production). Its cutover-completion + closure phases are satisfied via the follow-up Epic. |
| [2026-05-15 - dictate-cutover-completion](./2026-05-15%20-%20dictate-cutover-completion/README.md) | 2026-05-15 | 2026-05-17 | Archived — Implemented, INT-1 RESOLVED | The parent plan's **INT-1 routing-option-(a)** follow-up Epic. Made the new architecture *live* (real `PipelineRunnerSubsystemAdapter` + `PipelineNotificationCoordinator`), retired the legacy paths it rendered dormant (LanguageController, `audioFile` field, 4 dead controllers), and closed the Espresso test gap. 6 blocks / 19 chunks; 1180/0 tests both variants; INT-1 code-verified FALSE. |
| [2026-07-19 - desktop-companion-v1](./2026-07-19%20-%20desktop-companion-v1/README.md) | 2026-07-19 | 2026-07-20 | Archived — Implemented, green; 6 manual E2E pending | Grew the companion into a standalone desktop dictation host (Compose panel + global hotkey + slim orchestrator) and replaced loose config prefs with a `:shared` entity model whose canonical `contentHash` *is* the v3 file/wire format. Extracted the AI core into a fourth pure-JVM module `:shared-ai`, added a project-wide encrypted `SecretStore` port, Room v11→v13, and pull-only peer-catalog sharing. 6 blocks / 16 chunks; 59 commits / 484 files; auto E2E 10/10; 8 ADRs promoted (0028–0035). |

> The two plans above are **not independent**: the Epic
> `2026-05-15 - dictate-cutover-completion` exists solely to complete
> the cutover that `2026-05-07 - dictate-keyboard-layout-refactor`
> deferred (its escalated INT-1, routing option (a) — implement the
> parent ADRs' intended end-state). They run on the **same branch and
> the same commit lineage**; the parent plan's 3 specs in
> `2026-05-07 - …/research/` remain the SoT the Epic referenced
> without duplicating. The parent plan's deferred Phase 4.5/4.6/4.7/5
> were satisfied **via the Epic** rather than separately re-run on the
> identical unified codebase (no redundant re-run of identical-code
> phases — see each per-plan README's "Comparison context").
