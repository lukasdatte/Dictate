# Inline-Anchor Report — `companion-ui`

**Date:** 2026-07-20T17:25:00+02:00
**Group:** `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/`
**Worker:** doc-worker-inline (SLUG=companion-ui)
**Plan range:** `c46cfe8..HEAD`

## Summary

The companion UI group had **zero** resolvable `@see` plan/ADR anchors before this
pass (confirmed by grep) despite carrying rich, non-derivable module/class headers
that reference spec sections in prose (`desktop-host.md §6.2`, `peer-katalog.md §8`,
etc.). Block F promoted the governing ADRs (0030–0035) but the UI decision-point
classes were never anchored to them. This pass adds `@see` tags — matching the
established house format `@see docs/decisions/NNNN-…md` (87 existing uses) and
`@see docs/plans/2026-07-19 - desktop-companion-v1/research/<spec>.md §X` — to the
one decision-point unit per subsystem. No module header was rewritten; no gotcha or
header prose was changed. Anchors added at class/composable level only (one per
decision point), never per-method.

## Anchors added (per file)

| File | Unit | Anchors added | Decision point |
|---|---|---|---|
| `panel/PanelWindow.kt` | `PanelWindow` composable | `@see 0032-desktop-panel-ui.md`, `@see desktop-host.md §6` | Frameless, always-on-top, focus-free warm panel (ADR-0032) |
| `panel/PanelWindow.kt` | `ReviewRow` composable | `@see 0033-desktop-review.md`, `@see 0013-review-panel-and-ambiguity-modes.md` | Review incl. re-dictate on the companion, revising the IME-only rule (ADR-0033 over ADR-0013) |
| `panel/PanelWindowControl.kt` | `PanelWindowControl` interface | `@see 0032-desktop-panel-ui.md`, `@see desktop-host.md §6.2, §6.3` | Window warmth + visibility authority + focus-spike gate (ADR-0032) |
| `panel/PanelViewModel.kt` | `PanelViewModel` class | `@see desktop-host.md §7` | Timer/amplitude/glow presentation model lives in the VM, reducer stays clock-free (spec §7, §5.4) |
| `peers/PeerExplorerViewModel.kt` | `PeerExplorerViewModel` class | `@see 0034-peer-catalog.md`, `@see peer-katalog.md §8` | Read-only explorer, never-persisted derived §8.1 state matrix (ADR-0034) |
| `peers/OfferViewModel.kt` | `OfferViewModel` class | `@see 0034-peer-catalog.md`, `@see peer-katalog.md §8.2` | Offer view: visibility (envelope field, hash-excluded) + access-log last pickup (ADR-0034) |
| `config/ConfigViewModel.kt` | `ConfigViewModel` class | `@see 0030-config-entity-model.md`, `@see desktop-host.md §9.2` | CRUD over the four shareable entities with content-hash recompute on write (ADR-0030) |
| `history/DesktopHistoryViewModel.kt` | `DesktopHistoryViewModel` class | `@see 0035-companion-history-parity.md`, `@see desktop-host.md §9.3` | `DESKTOP_DICTATION` domain distinct from phone `ReceivedText`; direct re-insert, no `dispatch_state` (ADR-0035) |
| `history/HistoryScreen.kt` | `HistoryScreen` composable | `@see 0035-companion-history-parity.md`, `@see desktop-host.md §9.3` | Two histories (phone mirror + this PC) under one scope toggle (ADR-0035, §9.3) |

## Skipped (with reasons)

- **Layout-only composables** — `PeersScreen`, `PeerListScreen`, `PeerDetailScreen`,
  `OfferScreen`, `config/ManagementScreen`: these files are explicitly "layout only;
  the brain is a plain tested ViewModel". The decision point lives on the ViewModel,
  which now carries the anchor (one anchor per decision point). Anchoring the layout
  too would duplicate.
- **`App.kt`** — the nav-rail shell. Its one real non-derivable decision (the
  insertion-unavailable banner) already carries an inline `ADR-0018` reference in the
  header prose; ADR-0031 (dictation host) governs the pipeline/orchestrator, not the
  nav shell, so no host anchor was added.
- **`RecordingBar.kt`, `RecordingBarDesign.kt`, `CompanionIcon.kt`, `TimeFormat.kt`,
  `theme/Theme.kt`** — presentation primitives / trivial helpers; no plan/ADR decision
  the code alone doesn't justify. `RecordingBarDesign` already carries dense inline
  design-constant comments (the "why" behind bar counts/cadence), which are correct
  as-is.
- **Pre-existing, out-of-range files** — `devices/`, `pairing/`, `settings/`,
  `history/HistoryViewModel.kt` (phone mirror): not in the `c46cfe8..HEAD` UI footprint
  (this range only touched `HistoryScreen` + `DesktopHistoryViewModel` on the history
  side). Left untouched.

## Comment noise removed

None. The existing UI comments are all substantive "why" comments (design intent,
invariants, house-pattern rationale). No code-restating comments were found.

## Files modified (drift check)

All 8 edits are inside the assigned scope
(`companion/src/main/kotlin/net/devemperor/dictate/companion/ui/`). **No out-of-scope
(drift) edits.**

## Self-check

- Every `@see` resolves: ADR files `0013/0030/0032/0033/0034/0035` exist under
  `docs/decisions/`; spec sections `desktop-host.md §6/§6.2/§6.3/§7/§9.2/§9.3` and
  `peer-katalog.md §8/§8.2` all exist (verified by heading grep).
- Diff is anchor-only: 8 files, +26 lines, every added line is a `@see` tag or a blank
  ` *` KDoc continuation line. No logic, imports, or formatting touched.
- One anchor per decision point, at class/composable header level; no per-method anchors.

## Notes for final (non-blocking)

- **Prose `§X` shorthands remain elsewhere in the group.** Many secondary headers still
  reference specs by bare filename+section in prose (e.g. `RecordingBarDesign` → §7.2/§7.3).
  These are not decision points, so they were left as prose per the one-anchor-per-decision
  rule; if a future pass wants full navigability, they could be promoted to `@see` tags.
