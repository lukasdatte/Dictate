# Doc Worker Report — companion-readme

**Date:** 2026-07-20T17:25:00+02:00
**Action:** update
**Target doc:** `companion/README.md`
**Agent:** doc-worker (finalize)

## Summary

Verification pass of `companion/README.md` against the final `:companion` tree
(range `c46cfe8..HEAD`). The doc was authored in Block F and is comprehensive
and accurate on the whole — capture/pipeline/hotkey/ui/server/catalog/data/
secrets/ai/platform/domain descriptions, ADR references, conventions, and
cross-refs all match the shipped code. Three factual gaps were corrected; no
structural or voice changes.

## Changes applied per section

| Section | Change |
|---|---|
| Overview table (`Main.kt` row) | Added the `--minimized` flag (autostart → tray) and the delegation to `CompanionBootstrap`; the row previously named only `--headless`. Both flags confirmed in `Main.kt` (`FLAG_MINIMIZED` / `FLAG_HEADLESS`, lines 60/334/337). |
| Module Layout tree — `Main.kt` line | `entry point (--headless flag)` → `entry point (--minimized / --headless flags)`. |
| Module Layout tree — new line | Added `CompanionBootstrap.kt   start-up sequencing (db open, bind resolve, server start)` — a real top-level file (composition-root start-up sequencer that `Main.kt` delegates to) that was absent from the tree. |
| Module Layout tree — migrations line | `2=parity+dispatch_state, 3=config entities, 4=peers` → prepended `1=key-command chords, …`. Migration `1.sqm` (v1→v2, `key_command_chords` table) exists and was omitted. Descriptions verified against each `.sqm` header: 1=chord mapping, 2=Room-parity sessions + `received_texts` ablation + `dispatch_state`, 3=config-entity model (5 tables), 4=peer-catalog (peers/subscriptions/catalog_access_log). |

## Removed / added sections

None. Additions were single-line insertions within existing tables/tree.

## Verified-correct (no change needed)

- Migrations `{1..4}.sqm` all present; `Companion.sq` present.
- `ui/devices`, `ui/pairing`, `cli/PairCli.kt` all still present (discovery-flagged).
- `data/SchemaMigrator.kt` present (Module Layout claim holds).
- Build/Run/Test commands, ADR reference block, Key Conventions, Cross-Refs — all current.

## Notes for final

- **Stale scaffolding (source, not doc):** `companion/BuildProbe.kt`
  (`CompanionBuildProbe`) self-documents "**Delete this file once `wd-8` lands**".
  `wd-8` (the `ui/` module) has landed, so the compile-probe is now dead
  scaffolding (still referenced only by `CompanionBuildProbeTest`). Out of
  doc-worker scope (no source edits) — flag for a cleanup pass. Deliberately
  left out of the README's Module Layout tree as temporary code.
- **ui/ subdirs not exhaustively listed:** the actual `ui/` tree also contains
  `settings/`, `peers/`, `theme/`, `CompanionIcon.kt`, `TimeFormat.kt`. The
  README's `ui/` Overview row summarizes these as "peer/profile/prompt editors"
  and management screens — accurate at the summary altitude the row uses; not
  changed to avoid over-listing. No cross-doc contradiction.
- No links into sibling docs changed; Cross-Refs paths (`docs/DATABASE-PATTERNS.md`,
  plan/spec paths, `CLAUDE.md`) remain valid.
