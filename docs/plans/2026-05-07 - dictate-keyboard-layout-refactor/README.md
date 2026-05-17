# dictate-keyboard-layout-refactor — Archive README

**Title:** Keyboard-Layout-Refactor — Service-centred SSOT + 3-mode
Triangle-FSM (KEYBOARD / WIDGET / HOVER).

**Status:** Archived 2026-05-17 — **Implemented (6 blocks / 19 chunks);
Phase 4.5 / 4.6 / 4.7 / 5 completed-via the `dictate-cutover-completion`
Epic** (see "Deferred-phases closure" below).

**Created:** 2026-05-07 (skeleton finalised 2026-05-08) ·
**Branch/worktree:** `feature/dictate-keyboard-layout-refactor`.

## Summary

This plan replaced the Dictate IME's distributed-state main-button
architecture (the root cause of a recurring layout/visibility bug class
— five production bugs traced to parallel state-mutation paths) with a
**service-centred single-source-of-truth state architecture**:
`DictateOrchestrator` + 14 modules (pure reducers + cross-module
cascades) hosted in a process-resident `DictatePipelineService`
(Foreground Service), driving a `RenderBackend` and a 3-mode
Triangle-FSM (KEYBOARD / WIDGET / HOVER) with a floating overlay for
keyboard-switch survival. Block 0 authored the binding ADRs
(ADR-0001..0005) and the `docs/architecture/state-architecture/` doc
set as pre-code anchors. The 6 blocks shipped the architecture
unit-green (**946 tests**, build + suite reproducible).

## Comparison context

- **What changed:** the entire new state-architecture was built and
  shipped — `state/` package (orchestrator, registry, 14 modules,
  `DictateUiState`, `Action` hierarchy), `DictatePipelineService` (FGS),
  Room v3→v4 migration (SessionStatus 4→6 + `inserted_at` + data-loss
  fixes), `LayoutCatalog` + MotionScene render path, `OverlayBackend` +
  Triangle-FSM. ADR-0001..0005 + `state-architecture/` docs created.
- **What was deliberately NOT changed (the decisive scope boundary):**
  the new architecture was built as a **parallel-dormant layer**. The
  legacy `PipelineOrchestrator` (via `JobExecutor`), `LanguageController`,
  the IME `audioFile` field, and the 4 legacy controllers **remained
  the production drivers**. Two `ModuleServices` bindings
  (`pipelineRunner`, `notificationCoordinator`) were intentional no-op
  log stubs — so the new orchestrator could not drive a real recording
  or the foreground notification. This was *by design* for the 6-block
  scope; the cutover (make-it-live + retire-legacy) work was
  consistently forwarded to blocks ("B5-pre", "B6", "B7") that the
  executed 6-block plan **never contained**.
- **Phase-4 escalation — INT-1:** the parent Phase-4 Integration Check
  ([`reports/integration-check.md`](./reports/integration-check.md))
  found **no code-correctness regression** in the shipped 52-commit
  diff (legacy paths stay authoritative and functional, the keystone
  IME-activation chain works end-to-end), **but** raised **INT-1
  (`Critical, escalate-to-user`)**: a systemic plan-vs-implementation
  drift — the new architecture is a permanent parallel-dormant layer
  and the plan has no remaining block to make it live / retire legacy.
  The integration check explicitly instructed: *do not silently archive
  as "complete"* — the user must decide (a) a follow-up plan covering
  the cutover, or (b) accept-dormant + document as such.

## Deferred-phases closure — completed-via the cutover Epic

The user chose **INT-1 routing option (a)** — implement the cutover.
That follow-up is the Epic
[`2026-05-15 - dictate-cutover-completion`](../2026-05-15%20-%20dictate-cutover-completion/README.md),
authored + implemented on the **same branch + worktree + commit
lineage** as this plan (the Epic's baseline sits directly on this
plan's 52 commits; the two plans are **one codebase** at archive time).

Because the Epic operated on the **identical unified codebase**, this
plan's deferred **Phase 4.5 (E2E) / 4.6 (Documentation) / 4.7
(Implementation-Report) / 5 (Closure)** are recorded as
**`closed-via-epic dictate-cutover-completion`** — they were satisfied
by the Epic's corresponding phases rather than separately re-run on the
same code (no redundant re-run of identical-code phases — the
orchestrator's D4 / single-source-of-truth decision). Specifically:

- **INT-1 RESOLVED** — the Epic's Phase-4 Integration Check
  ([`../2026-05-15 - …/reports/integration-check.md`](../2026-05-15%20-%20dictate-cutover-completion/reports/integration-check.md))
  code-verifies all four INT-1 constituent facts now FALSE: real
  adapters wired, new orchestrator drives production recording,
  RenderBackend is sole render driver, legacy language/audioFile
  single-sourced. A `CutoverArchitectureInvariantTest` regression-locks
  the invariant.
- **Phase 4.5 / 4.7 satisfied** by the Epic's
  [`e2e-test.md`](../2026-05-15%20-%20dictate-cutover-completion/reports/e2e-test.md)
  + [`implementation-report.md`](../2026-05-15%20-%20dictate-cutover-completion/reports/implementation-report.md)
  (1180/0 tests both variants on the unified codebase; +226 over this
  plan's 946 baseline; all AC PASS; D15 under threshold).
- **Phase 4.6 satisfied** by the Epic's Phase-4.6 doc pass — the
  **ADR-0001 / ADR-0003 / ADR-0005 Decision-History appends** captured
  *this plan's now-live architecture decisions* (e.g. ADR-0001
  2026-05-17 *"Two-orchestrator coexistence collapsed; single-dispatch
  is now the production recording driver"*), and the
  `state-architecture/` dormant-seam docs were updated to the live
  cutover.

This plan's **body and specs are preserved unchanged** as the
historical record — the 3 specs in
[`./research/`](./research/) (Spec 1 Pipeline-Service §6984 lines,
Spec 2 Keyboard-Layout, Spec 3 Floating-Overlay) **remain the SoT**
that the Epic referenced (and §15.x of Spec 1 was amended in-worktree
by the Epic to keep the SoT coherent with the live cutover). Nothing
in this plan's plan-file or specs was deleted or rewritten on archival.

## Implementation reports

Full run artefacts in [`./reports/`](./reports/): per-block reports
`B{0..5}-*.md`, `audit-*.md`, `validated-findings-B*.md`, the
[`integration-check.md`](./reports/integration-check.md) (the INT-1
escalation source), and [`e2e-runbook.md`](./reports/e2e-runbook.md).
Plan-review + quality-gate artefacts in `./plan-review/` and
`./quality-gate/`. Plan-co-located research in
[`./research/`](./research/).

## EN translation

This plan is **genuinely German-native** (its plan file + 3 specs +
several research files). See **Language Disposition (Phase 5b/5c)**
below for the per-file audit, what received a real `.en.md`, and what
remains a tracked outstanding sidecar deliverable.

## Language Disposition (Phase 5b/5c)

**Decision (orchestrator D4):** produce a real `{name}.en.md` ONLY for
genuinely German-authored docs (the German-working-language → English
hand-off the EN-sidecar convention exists for). For English-native
docs, record the language attestation instead of duplicating — a
sidecar ~98% identical to its source is the exact redundant
duplication the SSoT / no-redundant-work engineering baseline forbids;
`language-conventions.md`'s German→EN trigger does not apply to
English-native docs.

Unlike the follow-up Epic (which was authored English-native by the
implement-long-plan agents), this parent plan is from the project's
**German working-language** era — its plan file, all 3 specs, and most
research files are substantively German prose and therefore **do
require** a real `.en.md`.

**Per-file language audit (all plan-scope docs):**

| File | Lines | Prose language | Action |
|------|------:|----------------|--------|
| `dictate-keyboard-layout-refactor.reviewed.md` | 1717 | **german-native** (German body throughout — "Symptom-Geschichte", "Architektur-Vision", …) | real `.en.md` REQUIRED (split per D16 — `…-0-overview.en.md` … `…-N-{block}.en.md`) — **OUTSTANDING** |
| `research/1-pipeline-service/1-pipeline-service.reviewed.md` (Spec 1) | 7023 | **german-native** ("Diese Spec beschreibt die Service-Schicht …") | real `.en.md` REQUIRED — **OUTSTANDING** |
| `research/2-keyboard-layout/2-keyboard-layout.reviewed.md` (Spec 2) | 2613 | **german-native** | real `.en.md` REQUIRED — **OUTSTANDING** |
| `research/3-floating-overlay/3-floating-overlay.reviewed.md` (Spec 3) | 2868 | **german-native** | real `.en.md` REQUIRED — **OUTSTANDING** |
| `research/motionlayout-architecture-options.md` | 312 | **german-native** | ✅ `research/motionlayout-architecture-options.en.md` |
| `research/main-button-area-inventory.md` | 536 | **german-native** | ✅ `research/main-button-area-inventory.en.md` |
| `research/_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md` | 464 | **german-native** | ✅ `…/_pending-ime-lifecycle-view-recreation.en.md` |
| `research/_pending-layout-container-architecture/_pending-layout-container-architecture.md` | 405 | **german-native** | ✅ `…/_pending-layout-container-architecture.en.md` |
| `research/_pending-persistence-background-architecture/_pending-persistence-background-architecture.md` | 772 | **german-native** | ✅ `…/_pending-persistence-background-architecture.en.md` |
| `research/_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md` | 483 | **german-native** | ✅ `…/_pending-state-machine-visibility-owners.en.md` |
| `research/b3-cleanup-cascade-and-backfill-policy.md` | 645 | english-native (spec, `status: Spec — programmer-ready`) | attested english-native — **no sidecar (SSoT; German→EN trigger N/A)** |
| `research/b5-ime-activation-wiring.md` | 801 | english-native (spec) | attested english-native — **no sidecar (SSoT)** |
| `research/manual-paste-field-architecture.md` | 400 | english-native | attested english-native — **no sidecar (SSoT)** |

**Real `.en.md` files produced this session: 6** — all six
German-native research files were translated 1:1 (code blocks,
identifiers, file paths, ASCII diagrams kept verbatim; only prose +
headings translated):
`research/motionlayout-architecture-options.en.md`,
`research/main-button-area-inventory.en.md`, and the four
`research/_pending-*/_pending-*.en.md` companions.

**Genuinely German, EN-sidecar OUTSTANDING (not skipped, not
attested-English): 4** — the plan file (1717 lines) and the 3 specs
(7023 / 2613 / 2868 lines, ~12,500 lines of dense technical German).
These are real German work that the EN-sidecar convention requires;
they are recorded here and in the state-file
`plan_lifecycle.en_translation.outstanding-genuine-german` as a
tracked, still-open deliverable rather than falsely closed. Honesty
note: this Phase 5b/5c session completed the six tractable German
research files; the plan + 3 specs were not produced in this session
and remain owed.

**English-native, no sidecar (SSoT): 3** — `b3-cleanup-cascade-…`,
`b5-ime-activation-wiring`, `manual-paste-field-architecture` are
English prose (the latter two carry German only in occasional terms /
spec metadata). A near-verbatim `.en.md` for these would be the exact
redundant duplication the SSoT rule forbids.

**D4 rationale (one line):** the EN-sidecar convention exists for the
German-working-language → English hand-off; German-native docs get a
real `.en.md`, English-native docs get an attestation (a near-identical
sidecar violates SSoT). The chunks.json artefacts are JSON (not
translatable prose) and are out of scope.

> **Cross-plan note:** the follow-up Epic
> `2026-05-15 - dictate-cutover-completion` is entirely English-native
> (authored by the implement-long-plan agents) — its README's "Language
> Disposition" records 0 sidecars, which is correct for it.

## Related plans

- **Follow-up / cutover-completion Epic:**
  [`../2026-05-15 - dictate-cutover-completion/`](../2026-05-15%20-%20dictate-cutover-completion/README.md)
  — the home for this plan's deferred cutover work; the resolution of
  this plan's escalated **INT-1** (routing option (a)). Its README
  carries the reciprocal link.

## Related ADRs

This plan's **Block 0 authored** ADR-0001..0005 (in `docs/decisions/`);
the plan's §8.1 and the specs' §12 carry the back-references. The
cutover Epic later appended Decision-History entries to ADR-0001 /
ADR-0003 / ADR-0005 recording the architecture going live (bidirectional
— each ADR's `## References` links **both** this plan §sections and the
Epic):

- **[ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)**
  — the single-dispatch + per-module-reducer foundation this plan
  introduced (ADR `## References` → this plan §4.0.1.1/§4.0.1.2/
  §4.0.1.6/§4.0.5 as *the plan that motivated this ADR*). Also carries
  the 2026-05-15 B2-VAL F-1 manual-paste relocation Decision-History
  entry made during this plan's own B2 block-validate.
- **[ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md)**
- **[ADR-0003 — service-foreground-pipeline-architecture](../../decisions/0003-service-foreground-pipeline-architecture.md)**
  (amended by this plan's B3 block-validate — Data-preservation rule —
  and by the Epic — real FGS coordinator).
- **[ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md)**
- **[ADR-0005 — ui-triangle-fsm-keyboard-widget-hover](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)**
