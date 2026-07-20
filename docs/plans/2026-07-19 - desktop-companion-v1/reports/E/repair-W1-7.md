# Repair Wave W1-7 — convention-E-3 (import ordering)

**Date:** 2026-07-20T13:30:00+02:00
**Agent role:** repair-fix
**Cluster:** convention-E-3 (green, Nice-to-have)

## Finding

`convention-E-3` — E3's added imports broke `CompanionContainer.kt`'s
single-block package-alphabetical import order:

- `SqlDelightPeerExplorerStore` (was line 18) sat before
  `SqlDelightDeviceRepository` (was line 19) — P before D.
- A `catalog.*` cluster and a stray `capture.AudioCaptureService` were
  appended mid-file (after the `data.*` block) rather than in package
  position, and a second out-of-order `domain.*` / `platform.fallback.*`
  run had been appended after `ui.panel.*`.

## What I did

Reordered the entire import block (lines 3-68) into one
case-insensitive alphabetical block with `java.*` last — the
convention the repo's IntelliJ-default optimize-imports produces (no
`.editorconfig`, no ktlint/spotless, no `.idea/codeStyle` in the repo;
`lint_command: none`).

Ordering rule validated against evidence rather than assumed:
- The file's own clean region already sorted `data.memory.*` between
  `data.Desktop…` and `data.SqlDelight…`, which only a
  **case-insensitive full-string** sort produces (`m` < `s`
  case-folded, but `Companion`/`Desktop` still precede `memory`).
- Sibling files (`SecretStoreModule.kt`, `ConfigViewModel.kt`,
  `InMemoryChordMapping.kt`) all place `java.*` at the **end** of the
  block, not first — so java last, everything else alphabetical.

Consequences of the rule, for the record (mechanical, not a mistake):
- `catalog.discovery.*` sorts between `catalog.CatalogSyncRunner` and
  `catalog.PeerIndexSource` (`d` < `p`).
- `domain.net.AddressCatalog` and `domain.SyncService` interleave
  around `domain.Pairing…`/`domain.port.*` (`n` < `p` < `s`).

Both flagged problems are fixed: `SqlDelightDeviceRepository` now
precedes `SqlDelightPeerExplorerStore`; `capture.AudioCaptureService`
sits with the other `capture.*` imports; the `catalog.*` cluster is in
package position; no trailing out-of-order runs remain.

## Verification

- Import multiset identical to HEAD (`diff` of sorted `^import` lines →
  IMPORT SET IDENTICAL). Pure reordering, 23 insertions / 23 deletions,
  66 imports before and after — no import added or removed.
- `./gradlew :companion:test` → BUILD SUCCESSFUL (`:companion:compileKotlin`
  recompiled the changed file; all companion tests green).

## Self-check

Re-read the diff: every import accounted for, ordering consistent with
the validated case-insensitive rule, `java.*` last, no code outside the
import block touched. No regressions.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/CompanionContainer.kt`

## Skipped findings

None.

## Drift

None — the edit is confined to the assigned file's import block.
