# Block F — Re-Audit of Repair Wave 1 (MODE = re-audit)

**Date:** 2026-07-20T13:30:00+02:00 · **Block:** F (single chunk F1) · **Consolidator:** audit-consolidator agent
**Repair commit:** `d0d2e19e6d4b811a939aa0e6e59103d14bf6b794` — `[F] repair wave 1 (desktop-companion-v1)`
**Findings verified:** `plan-and-api-F-1` (Important), `plan-and-api-F-2` (Nice-to-have)

**Verdict: converged.** Both findings fully resolved by the wave; no new problems introduced. `findings` array is empty.

## Per-finding verification

### plan-and-api-F-1 — Windows checklist misattributes §2 criterion 9 → RESOLVED

The wave touched **both** artefacts the finding required to stay in sync:

- `windows-acceptance-checklist.md` (`:99` note) now reads: *"§2 criterion 9 (ADR completeness) is **already satisfied** — independently of this checklist — by the 8 ADR drafts having been promoted to `docs/decisions/` (ADR-0028 through ADR-0035) plus their index rows. A fully ticked-and-signed checklist is what closes the **Windows-only** criteria **3 / 4 / 7** …"*. This now agrees with the checklist's own scope line (`:4`, criteria 3/4/7) — the contradiction is gone.
- `F1-impl.md` "Pending user action" (`:66`) was corrected in lockstep: criterion 9 is stated as *fully met by this chunk* (promotion of the 8 drafts to 0028–0035 + index, not gated on the checklist), with only the **Windows-only** criteria 3/4/7 remaining pending.

Both artefacts now tell the same, correct story. Resolved → dropped.

### plan-and-api-F-2 — One-way ADR links not reciprocated → RESOLVED

The wave took the "add reciprocal reference bullet" alternative (one of the two fixes the finding offered). Grep-confirmed against the committed files:

- `0016-wire-protocol-typed-dtos-konform.md` — added two bullets: reciprocal to **ADR-0030** (config entity model reuses the wire-DTO + Konform + additive-versioning doctrine) and **ADR-0034** (peer-catalog DTOs are an additive payload family on the wire stack). Both explicitly marked *"Additive reuse, not a revision of this ADR."*
- `0024-prompt-pill-types.md` — added "Built on by **ADR-0030**" bullet (shared `Prompt` entity carries the typed pill-kind into the v3 format; additive reuse).
- `0025-input-command-protocol.md` — added "Built on by: **ADR-0034**" bullet (catalog DTOs as a further additive payload family on the protocol stack).

The forward references from 0030/0034 already existed (per the original finding), so all three links are now bidirectional and internally consistent. Resolved → dropped.

## Wave-introduced problems

None. Checks performed:

- **Stale file-path label:** the finding listed 0016 as `0016-shared-wire-protocol-module.md`, but the on-disk file is `0016-wire-protocol-typed-dtos-konform.md`. The repair agent edited the **correct actual file** — no misdirected edit, no orphaned file.
- **Link integrity:** the added back-references name existing, correctly-numbered ADRs (0030, 0034); no dangling or wrong-number links.
- **Framing accuracy:** every added bullet frames the relationship as additive *reuse*, matching the finding's characterization and the plan's own §12 reciprocity accounting — no over-claim of "revision" that would misrepresent the ADRs.
- **No collateral edits:** the diff is confined to the four doc targets plus the two repair-report files; no code, tests, or unrelated docs touched.

## Cross-cut patterns

None outstanding. F-1's two-file echo (checklist + F1 report) was fixed as a synchronized pair, exactly as the consolidation flagged.
