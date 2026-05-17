# dictate-cutover-completion — Archive README

**Title:** Make the new DictateOrchestrator drive production recording +
notification, retire the legacy paths it renders dormant, and close the
Espresso UI-test gap — the cutover the parent plan deferred into blocks
that never existed.

**Status:** Archived 2026-05-17 — **Implemented; parent-plan INT-1
code-verified RESOLVED.**

**Created:** 2026-05-15 · **Branch/worktree:**
`feature/dictate-keyboard-layout-refactor` (same lineage as the parent
plan — this Epic builds directly on the parent's commits).

## Summary

This Epic is the **INT-1 routing-option-(a) follow-up** to the parent
plan `dictate-keyboard-layout-refactor`. That plan built a complete new
state-architecture (DictateOrchestrator + 14 modules + RenderBackends +
Overlay + Triangle-FSM) **as a parallel-dormant layer** and shipped it
unit-green (946 tests), but the parent's Phase-4 Integration Check
escalated **INT-1** (`Critical, escalate-to-user`): the new orchestrator
never drove a real recording or the foreground notification — legacy
`PipelineOrchestrator` + `LanguageController` + the `audioFile` field +
4 legacy controllers still owned production, and the cutover work was
repeatedly forwarded to blocks ("B5-pre", "B6", "B7") the executed plan
never contained. This Epic is the home for that work: it made the new
layer **live** (real `PipelineRunnerSubsystemAdapter` delegating to
`JobExecutor` per Spec 1 §9.6, real `PipelineNotificationCoordinator`),
retired the legacy paths it rendered dead (D-13 `LanguageController` →
stateless `LanguageResolver` + `LanguageModule`; D-14 `audioFile` field;
the 4 dead controllers), and closed the Espresso test gap — so a single
coherent architecture remains, not a half-migration. The
parent-plan INT-1 condition is now **code-verified FALSE**.

## Implementation outcome

- **6 blocks / 19 chunks** (12 base chunks + a 7-chunk **Theme C-R**
  render-path-cutover extension authored mid-Epic): B1 Theme-A
  (state-shape) · B2 Theme-B (recording-drive) + the in-plan D2-pre
  verification gate · B3 Theme-C (legacy-retire) · B5 Theme-C-R
  (render-path cutover) · B6 Theme-D (test-completion). There is no
  separate B4 — the render-cutover extension is B5.
- **Tests:** 1180/0/0 both variants (debug + release), uncached,
  reproducible — **+226 net** over the parent's 946 baseline (no
  behaviour-coverage deletion). All AC-1..AC-10 PASS. D15
  postponed-aggregate **UNDER THRESHOLD** (0 Critical, 0 open
  Important).
- **INT-1 RESOLVED** — `reports/integration-check.md` Central Verdict
  code-verifies all four INT-1 constituent facts now false: the two
  dormant stub seams are gone (real adapters wired in
  `DictatePipelineService.onCreate` Step 3/4); the new
  `DictateOrchestrator` drives production recording; the RenderBackend
  is the sole render driver (4 controllers deleted); legacy
  language/audioFile are single-sourced. A `CutoverArchitectureInvariantTest`
  (INT-3) now regression-locks the single-architecture invariant
  (empirically RED-proven against a re-injected double-dispatch).

### The 3× anti-pattern arc (the Epic's central narrative)

The exact INT-1 parallel-dormant anti-pattern this Epic existed to cure
**recurred three times during the Epic itself** — and each recurrence
was **resolved, not re-deferred**:

1. **C10-IMPL-2** — render-path cutover never happened (the parent
   B4-VAL F-1/F-2/F-33 had deferred it to a never-created "B5/B7"
   block; the mandatory per-class trace proved the C10 premise false).
   Resolved by authoring + implementing **Theme C-R** (7 new chunks)
   rather than forwarding it onward.
2. **CR4-IMPL-1** — `registerAllListeners()` bundled 3 no-owner
   sub-axes (edit-bar/emoji/overlay-chars) presupposing
   `EditBarController`/`EmojiController` that were never created.
   Resolved via the **CR-EXTRACT** mid-chunk-triage chunk.
3. **CR4-IMPL-3 / B5-VAL F-2·F-6** — the RESEND-action staging-override
   was a parallel-dormant seam (wrong staging language + cross-session
   leak + a false "cleared" KDoc). Caught at block-validate, re-opened,
   properly closed.

Each was caught behind the §6.2 staged build-but-dormant→atomic-flip
safety-net, gated by an RR-3 per-class responsibility-trace before any
deletion, and locked against silent regression by INT-3. The Epic's own
process did **not** reproduce the failure it existed to cure.

## Comparison context

- **Relationship to the parent plan:** This Epic is *not* an
  independent plan. It is the parent plan's escalated **INT-1
  routing-option-(a)**: implement the parent ADRs' intended end-state
  (single coherent architecture) rather than (b) accept-dormant. It
  runs on the **same branch + worktree + commit lineage** as
  `2026-05-07 - dictate-keyboard-layout-refactor` (Epic baseline HEAD
  `65bb303` sits on top of the parent's 52 commits). Both plans are
  **one codebase on this branch/commit** at archive time.
- **The parent plan's 3 specs are the SoT** — they live in
  `../2026-05-07 - dictate-keyboard-layout-refactor/research/` (Spec 1
  Pipeline-Service, Spec 2 Keyboard-Layout, Spec 3 Floating-Overlay).
  This Epic *references* §-sections; it did not duplicate or relocate
  them. Two disciplined, documented Spec-1 §15.x amendments were made
  to keep the SoT coherent with the live cutover (the AudioModule
  observer / BT-SCO `awaitingSco` redesign, B2-VAL-W1).
- **What changed vs. the parent's shipped state:** the parent shipped
  the new architecture *dormant*; this Epic made it the *sole live*
  architecture and **deleted** the legacy paths (4 controllers,
  `LanguageController`, the `audioFile` field, the
  `USE_LEGACY_RECORDING_DRIVE` guarded fallback).
- **What was deliberately NOT changed:** Room `@Database(version=)`
  stays at **v4** — invariant E-7 (the cutover is code-only blast
  radius; no schema migration, which is why the parent's
  DB-migration-consent E2E items were dropped). One **RESUME**
  `JobExecutor.INSTANCE.start` carve-out survives in the IME by design
  (documented, regression-locked by `CutoverArchitectureInvariantTest`).
- **Scope boundary / known follow-up (non-blocking):** **INT-2** — a
  pre-existing `JobExecutor.INSTANCE.start` in
  `HistoryDetailActivity.java:492` (the History-detail "re-process"
  button) is **out-of-scope-recorded** by design (D3 carve-out): it
  is unchanged since the Epic baseline, single-dispatch, and does not
  violate AC-10. Recorded here as a Phase-5 known follow-up if the
  team later wants 100% single-driver. One NTH cosmetic deferral
  (**C5-IMPL-2** — in-keyboard amplitude/timer/border-glow animation
  side-channel undriven on the new path; the FGS notification is the
  authoritative recording-active surface and recording works
  end-to-end) is carried open with a tracking owner.

## Implementation reports

Full run artefacts in [`./reports/`](./reports/):

- [`implementation-report.md`](./reports/implementation-report.md) —
  Phase-4.7 aggregate (33 issues / 26 drifts / 24 fixes; 🔴 0 / 🟠 13 /
  🟢 20; the 3× anti-pattern arc).
- [`integration-check.md`](./reports/integration-check.md) — Phase-4
  Integration Check: **the INT-1 RESOLVED Central Verdict** (the
  substantive answer the parent INT-1 escalation demanded) +
  INTEGRATION-W1 (INT-3 regression-lock, INT-4 doc-hygiene).
- [`e2e-test.md`](./reports/e2e-test.md) /
  [`e2e-runbook.md`](./reports/e2e-runbook.md) — Phase-4.5 (auto-tier
  GREEN; device-tier env-blocked, not a failure).
- [`phase-4.6-report.md`](./reports/phase-4.6-report.md) — Phase-4.6
  documentation update (3 ADR Decision-History appends + 3 architecture
  docs + 5 render-file inline-anchor re-tense).
- Per-block reports `B{1,2,3,5,6}-*.md`, `audit-*.md`,
  `validated-findings-B*.md`.
- Plan-co-located research in [`./research/`](./research/)
  (`render-path-cutover.md`, `recording-audiofocus-btsco-handshake.md`,
  `imported-audiofile-orchestrator-route.md`,
  `sendstaging-isstarting-guard-semantics.md`).

## EN translation

See **Language Disposition (Phase 5b/5c)** below. All of this Epic's
plan-scope docs are English-native — **no `.en.md` sidecar is produced
(correct, not skipped)**. The `language-conventions.md` German→EN
trigger targets German-working-language plans; this Epic was authored
English-native, so a near-verbatim sidecar would be the exact
redundant duplication the SSoT rule forbids.

## Language Disposition (Phase 5b/5c)

**Decision (orchestrator D4):** produce a real `{name}.en.md` ONLY for
genuinely German-authored docs. For English-native docs, record the
language attestation instead of duplicating (a sidecar ~98% identical
to its source violates SSoT / the no-redundant-work engineering
baseline; `language-conventions.md`'s German→EN trigger does not
apply).

**Per-file language audit (this Epic's plan-scope docs):**

| File | Prose language | Action |
|------|----------------|--------|
| `dictate-cutover-completion.md` | english-native (English prose; German limited to §-headings, e.g. "§1 Kontext & Auslöser", "§6 Risiken & Rollback") — `mixed-heading` | attested english-native — **no sidecar (SSoT; German→EN trigger N/A)** |
| `research/imported-audiofile-orchestrator-route.md` | english-native | attested — no sidecar |
| `research/recording-audiofocus-btsco-handshake.md` | english-native | attested — no sidecar |
| `research/render-path-cutover.md` | english-native (spec, `status: Spec — programmer-ready`) | attested — no sidecar |
| `research/sendstaging-isstarting-guard-semantics.md` | english-native | attested — no sidecar |

**Real `.en.md` files produced for this Epic: 0** — and that is the
correct outcome per the rule: every plan-scope doc here is
English-native, so the German-working-language → EN-sidecar convention
does not trigger for any of them.

**D4 rationale (one line):** the EN-sidecar convention targets
German-working-language plans; this Epic's plan + 4 research files were
authored English-native by the planning/impl agents, so a
near-identical sidecar would be redundant duplication that violates the
SSoT rule.

> **Note on the parent plan:** the parent
> `2026-05-07 - dictate-keyboard-layout-refactor` is genuinely
> **German-native** (its plan file + 3 specs + several research files).
> This Phase 5b/5c session translated 6 of its German research files
> (real `.en.md` produced); its plan file + 3 specs remain a tracked
> outstanding EN-sidecar deliverable. See that plan's README "Language
> Disposition (Phase 5b/5c)" + its state-file
> `plan_lifecycle.en_translation`.

## Related plans

- **Parent / motivating plan:**
  [`../2026-05-07 - dictate-keyboard-layout-refactor/`](../2026-05-07%20-%20dictate-keyboard-layout-refactor/README.md)
  — this Epic is its escalated **INT-1 routing-option-(a)** follow-up.
  See that plan's README "Comparison context" for the reciprocal link
  (its deferred Phase 4.5/4.6/4.7/5 are completed-via this Epic).

## Related ADRs

The parent plan's Block-0 authored ADR-0001..0005 (in
`docs/decisions/`). This Epic appended **Decision-History** entries
that record the parent's now-live architecture decisions (bidirectional
— each ADR's `## References` links both this Epic and the parent plan):

- **[ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)**
  — Decision-History 2026-05-17 *"Two-orchestrator coexistence
  collapsed; single-dispatch is now the production recording driver
  (Epic dictate-cutover-completion)"*. Its `## References` links this
  Epic + `reports/integration-check.md` (INT-1 RESOLVED) +
  `research/render-path-cutover.md` +
  `research/sendstaging-isstarting-guard-semantics.md`.
- **[ADR-0003 — service-foreground-pipeline-architecture](../../decisions/0003-service-foreground-pipeline-architecture.md)**
  — Decision-History append: `PipelineNotificationCoordinator` becomes
  the real FGS coordinator (single-source `NOTIF_ID`).
- **[ADR-0005 — ui-triangle-fsm-keyboard-widget-hover](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)**
  — Decision-History append: IME recording-trigger flips to the
  dispatch path.

(ADR-0002 / ADR-0004 referenced for context; no Decision-History
append required.)
