# Repair Wave W2 — cluster 2 report (finding plan-and-api-D-3)

**Date:** 2026-07-20T00:40:00+02:00
**Agent role:** repair-fix (implement-long-plan-v3)
**Cluster:** `plan-and-api-D-3` (Important, green — PARTIALLY RESOLVED by wave 1)

## Outcome

**Finding SKIPPED — escalated to main loop as a scope decision. No code changed; HEAD stays green.**

## Analysis

The finding names one defect: desktop-dictated sessions were unreachable by
the history read path (`pageHistory`/`countHistory` JOIN `dispatch_state` and
scope to `host_origin = 'PHONE_SYNC'`, so a `DESKTOP_DICTATION` session — which
has no `dispatch_state` row — could never surface).

**That named defect is already CLOSED by repair wave 1**, at the data layer:

- `Companion.sq` — `pageDesktopHistory` / `countDesktopHistory` /
  `desktopHistoryEntry` (scope `host_origin = 'DESKTOP_DICTATION' AND status =
  'COMPLETED' AND origin != 'REVIEW_REFINEMENT'`; expose `final_output_text` +
  the current transcript via a correlated `ORDER BY version DESC LIMIT 1`
  subquery; `instr(lower(...))` substring search matching `pageHistory`).
- `DesktopSessionRepository.kt` — `pageDesktopHistory` / `countDesktopHistory` /
  `desktopHistoryEntry` read methods + the `DesktopHistoryEntry` model.
- `DesktopHistoryTest.kt` — 5 tests over the read path.

## Why the remainder was not built in this wave

The unbuilt part of §9.3 is a **fresh Compose surface**, not a repair:

- `HistoryScreen.kt` / `HistoryViewModel` are built entirely around
  `ReceivedText` + `DispatchService.reinsert` (the phone-sync path). §9.3 asks
  for a unified history with a `host_origin` filter (Phone/Desktop), a detail
  view (transcript vs. final output), and desktop re-insert.
- Desktop re-insert **cannot** reuse `DispatchService.reinsert`: that resolves
  `history.findById(sessionId)` → a phone `ReceivedText`, then
  `history.recordDispatch(...)` against `dispatch_state`, which desktop rows do
  not have. A desktop re-insert must call `container.inserter.insert(
  finalOutputText)` (the `TextInserter` port) directly.
- `container.desktopSessions` is **nullable** (null in the `forTest` graph), so
  the view-model needs a deliberate decision on that dependency.
- `HistoryViewModel` carries a full test suite (`HistoryViewModelTest`); any
  new/extended VM must match that bar.

This is design-decision-heavy, multi-file feature work (VM state model,
dual-source or filtered list, a new detail surface, re-insert wiring, tests) —
the kind of surface the workflow routes through the chunk pipeline
(IMPL+TEST → block audit), not an unaudited repair-wave edit. The finding's own
`suggested_fix` says as much: *"confirm with the main loop whether to build now
within Block-D repair vs. a dedicated follow-up chunk."*

## Action taken

Sent `main` an escalation (state + analysis + recommendation) recommending a
**dedicated Block-D UI follow-up chunk**. Awaiting the scope decision; if the
main loop wants it built now, it can resume this cluster with an explicit go.

## Files modified

None.

## Skipped findings

| ID | Reason |
|---|---|
| `plan-and-api-D-3` | Remaining scope is a fresh Compose surface (desktop-history VM + UI section with Phone/Desktop filter, transcript-vs-output detail, re-insert via `TextInserter`) + test suite — feature work, not a repair. Named data-layer defect already closed by wave 1. Escalated to main loop per the finding's own recommendation; recommend a dedicated follow-up chunk. No code touched so HEAD stays green. |

## Drift

None — no files touched.
