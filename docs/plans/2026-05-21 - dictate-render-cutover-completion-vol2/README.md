# Plan: dictate-render-cutover-completion-vol2

**Status:** Implementer-ready (manual phase-by-phase implementation, no
`implement-long-plan-v2` orchestration). Created 2026-05-21.

## Summary

Completes the Render-Path-Cutover that the predecessor Epic
`2026-05-15 - dictate-cutover-completion` left as A3-option-a
("extract-and-preserve-behaviour"). The `PipelineStepRowRenderer` is
re-architected from a stateful, 100 ms-tick-driven legacy renderer into
a reactive consumer of the orchestrator's `state.PipelineUiState`. The
legacy `core.PipelineUiState` sealed class is deleted, the two
hand-coded bridges in `DictateInputMethodService.java` are removed, and
the Row 1↔Row 2 Constraint-Chain that collapses on a GONE `trash_btn`
anchor is decoupled.

## Comparison context

What this plan adds vs. its predecessor:

- The 2026-05-15 plan deleted the four legacy controllers
  (`MainButtonsController`, `RecordingUiController`, `KeyboardStateManager`,
  `KeyboardUiController`) and extracted their `BLEIBT`-halves into
  smaller owners (`PipelineStepRowRenderer`, `QwertzRecordingController`).
  That satisfied AC-RR-7 (grep-zero on the four class names) but
  preserved byte-equivalent legacy behaviour inside the new owners
  — including the 100 ms `ElapsedTimer`-driven direct-write to
  `record_btn.text` from a parallel `core.PipelineUiState` sealed class.
- This plan re-evaluates the SPLIT-disposition from
  `2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md`
  §3 G9/G13 + §7 A3 from option-a (preserve behaviour) to option-c
  (re-architect to reactive consumer). The single-Writer-per-axis
  invariant becomes load-bearing.

## Implementation reports

Phase-by-phase reports will be filed in `./reports/` once implementation
is underway:

- `reports/phase-1-catalog-surface.md` (TBD)
- `reports/phase-2-bridge-adapter.md` (TBD)
- `reports/phase-3-refresh-stop.md` (TBD)
- `reports/phase-4-constraint-chain.md` (TBD)
- `reports/phase-5-delete-core-state.md` (TBD)
- `reports/phase-6-cleanup.md` (TBD)

## EN translation

Pending — produced by Phase 5b/5c equivalent after plan-archive.

## Related ADRs

- **ADR-0005** (`docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`)
  — Phase 6 of this plan appends a Decision-History entry recording the
  shift from A3-option-a (extract-and-preserve) to A3-option-c
  (extract-and-re-architect) for the Pipeline-render axis.

## Related plans

- **Predecessor:** `docs/plans/2026-05-15 - dictate-cutover-completion/`
  — extracted the `BLEIBT`-halves under option-a; flagged the SPLIT
  as "Mitigation flagged, not chosen here" (RR-5).
- **Co-located feature branch:** `feature/language-chip-curation`
  (Single-Row-Mode) — touches the same Catalog slots; per §7 Q5 the
  recommended sequencing is Phase 1+2+4 of *this* plan **before** the
  Single-Row merge, Phase 3+5+6 **after**.
