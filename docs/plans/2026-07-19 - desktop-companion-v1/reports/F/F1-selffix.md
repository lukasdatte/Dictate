# F1 — Self-Fix (fresh eyes, diff-based)

**Chunk:** F1 (Block F) · **Date:** 2026-07-20 · **Wave commit:** a6320ee · **Scope:** docs/promotion only.

## What I did

Reviewed the ADR-promotion diff against plan §5 F1, §2 criterion 9, and §6, with the
lifecycle-adr bidirectional-link rule in hand. The implementer's promotion mechanics
(8 `git mv` renames, index rows, Accepted status, Decision-History entries, placeholder
resolution) all check out. Fresh eyes surfaced **two link-integrity defects the
implementer's own self-check missed**, both fixed inline, both directly tied to acceptance
criterion 9 ("docs-Referenzen ohne tote Links") and the bidirectional-cross-link requirement.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| F1-SF1 | Important | **Broken `Related Plan` link in all 8 promoted ADRs.** Each used a root-relative + space-unencoded URL `](docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md)`. Markdown resolves relative to the file's dir (`docs/decisions/`), so it 404s (`docs/decisions/docs/plans/…`); the raw spaces compound it. Repo convention (every other ADR, e.g. `0002`/`0004`/`0008`) is file-relative + `%20`-encoded: `../plans/…%20-%20…`. `0028:187`, `0029:183`, `0030:204`, `0031:175`, `0032:166`, `0033:150`, `0034:230`, `0035:163`. | fixed-inline | — |
| F1-SF2 | Important | **Missing bidirectional back-link ADR-0020 → ADR-0034.** Plan §6 states peer-catalog "erweitert ADR-0017/**0020**"; `0034` forward-links `0020` in its References + "Cooperates with" + a dedicated "new authority direction" discussion. `0020` reciprocated only its `0035`-extender, silently omitting the arguably-more-prominent `0034` config-authority relationship — a dangling one-way link (lifecycle-adr). `0020` References + Decision-History. | fixed-inline | — |

## Fixes applied (inline)

1. **F1-SF1** — Rewrote the `Related Plan` link in all 8 promoted ADRs (`0028`–`0035`) to
   the repo-standard file-relative, `%20`-encoded form
   `[desktop-companion-v1](../plans/2026-07-19%20-%20desktop-companion-v1/desktop-companion-v1.md)`.
   Mechanical identical-string replacement across the 8 files.
2. **F1-SF2** — Added to `0020-lazy-cursor-sync.md`: (a) a "Related ADRs" References bullet
   for ADR-0034 (config-only authority direction; session sync stays this ADR's exclusive
   phone-authoritative domain, catalog-excluded per F16), and (b) a matching `2026-07-20`
   Decision-History entry, mirroring the style of the existing `0035` reciprocal and 0017's
   Block-E entry. Append-only (0020 is Accepted) — consistent with the 0035 reciprocal the
   implementer already appended.

## Verification (post-fix)

- Comprehensive relative-`.md`-link sweep (with `%20` decode) across all promoted + extended
  ADRs + `README.md` + `companion/README.md`: **all resolve, zero broken**.
- No root-relative `](docs/plans/…` or `](docs/decisions/…` links remain in `docs/decisions/`.
- Plan↔ADR bidirectional confirmed: promoted ADRs → plan (now-valid links); plan §12 (L852-858)
  → promoted `0028`–`0035` in the plan's own backtick-number style.
- 0020 now references ADR-0034 in both a References bullet and a Decision-History entry.
- Re-checked the implementer's other claims — all hold: 8 moves are renames; no residual
  `ADR-NNNN`/backticked slug in promoted ADRs; every referenced `ADR-####` (0001–0035) exists;
  README index rows present + Accepted; DATABASE-PATTERNS ToC anchor `#sqldelight-parity-companion`
  matches its heading; plan §12 has no clickable dead `tmp/desktop-concept/` or `adrs/` links
  (remaining mentions are historical prose, not links).

## Test result

Docs-only chunk — **no compilable code touched** (edits are Markdown only). No unit/build test
applies; running `./gradlew test` would exercise unrelated, partially-uncommitted concurrent
E-block work in the shared worktree and is out of scope for a documentation self-fix. Link
integrity (the real correctness surface here) verified by the sweep above.

## Files modified

- `docs/decisions/0020-lazy-cursor-sync.md` (F1-SF2)
- `docs/decisions/0028-shared-ai-module.md`, `0029-secret-store.md`, `0030-config-entity-model.md`,
  `0031-desktop-dictation-host.md`, `0032-desktop-panel-ui.md`, `0033-desktop-review.md`,
  `0034-peer-catalog.md`, `0035-companion-history-parity.md` (F1-SF1)

## Drift (files outside CHUNK_FILES)

None. All edits are within the assigned ADR set. The worktree also contains unrelated
modified/untracked files (`app/.../windows/*.kt`, `companion/**`, `gradle/libs.versions.toml`,
`peers/*`, catalog SQLDelight) from concurrent chunks — **not touched**, not mine; the
file-scoped commit-agent will exclude them.
