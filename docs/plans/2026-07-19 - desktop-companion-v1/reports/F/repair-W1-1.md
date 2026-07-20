# Repair W1-1 — criterion 9 vs. Windows-checklist scope correction

**Date:** 2026-07-20T13:30:00+02:00 · **Block:** F · **Scope:** docs-only (two Markdown artefacts kept in sync).

## Finding fixed

**plan-and-api-F-1** (green, Important) — the two artefacts wrongly claimed §2
**criterion 9 (ADR-Vollständigkeit)** is satisfied by ticking + signing the Windows
acceptance checklist. Per the plan (`desktop-companion-v1.md:215-216`) criterion 9 is
only about the 8 §6 ADR drafts being promoted to `docs/decisions/` + index — already
completed by F1 (ADR-0028–0035) and independent of any Windows device. The checklist's
own scope line (`:4`) correctly says it validates the Windows-only criteria **3 / 4 / 7**.

### What I changed

- `reports/windows-acceptance-checklist.md:98` — reworded the closing `[!IMPORTANT]`
  callout: criterion 9 is **already satisfied** by the promoted ADRs (0028–0035 + index),
  independently of the checklist; a fully ticked-and-signed checklist is what closes the
  **Windows-only** criteria 3 / 4 / 7 (any FAIL still routes to issue-triage). This now
  agrees with the document's own `Scope:` line (:4).
- `reports/F/F1-impl.md:66` — corrected the "Pending user action" section: criterion 9
  is fully met by the ADR promotion and is **not** gated on the Windows checklist; what
  remains pending is the manual Windows acceptance of criteria 3 / 4 / 7.

The two artefacts are back in sync, and neither now mislabels the pending manual
acceptance as "ADR completeness".

## Skipped findings

None.

## Test run

Not applicable — both edits are pure Markdown (documentation reports); no production
code, build config, or test surface was touched. `./gradlew build` would not exercise
these files.

## Files modified

- `docs/plans/2026-07-19 - desktop-companion-v1/reports/windows-acceptance-checklist.md`
- `docs/plans/2026-07-19 - desktop-companion-v1/reports/F/F1-impl.md`

## Drift (files outside assigned scope)

None. Both files were named in the finding's `files` list.
