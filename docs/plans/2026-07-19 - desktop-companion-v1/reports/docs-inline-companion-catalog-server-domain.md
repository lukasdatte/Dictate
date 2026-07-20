# Inline-Anchor Worker Report — companion-catalog-server-domain

**Date:** 2026-07-20T17:25:00+02:00
**Slug:** companion-catalog-server-domain
**Scope:** `companion/src/main/kotlin/net/devemperor/dictate/companion/{catalog,server,domain}/`
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Range:** `c46cfe8..HEAD`

## Summary

The three subsystems (`catalog/`, `server/`, `domain/`) already carry
**strong, rich module/class headers** from the Block-F doc chunk — responsibility,
invariants, port-rationale, and gotchas are all present and high quality. The one
consistent gap was the **`@see` plan/ADR anchor**: every new decision-bearing type
named its governing spec section *in prose* ("peer-katalog.md §4.2", "ADR-0018")
but carried **no machine-resolvable `@see` tag**, so the prose refs did not resolve
to a path. The codebase-wide convention (213 files elsewhere in this range) uses
`@see docs/plans/2026-07-19 - desktop-companion-v1/research/{spec}.md §X`.

This pass added that trailing `@see` anchor to 13 new types, matching the prose each
header already carries (link, don't paraphrase — knowledge-doc-format §"Inline anchors").
No module headers were added (all new files already had them) and no comment noise was
found to remove.

## `@see` anchors added (13 files)

| File | `@see` added |
|---|---|
| `domain/CatalogService.kt` | `peer-katalog.md §4.2, §4.3` + `docs/decisions/0034-peer-catalog.md` |
| `server/routes/CatalogRoutes.kt` | `peer-katalog.md §4.1` |
| `domain/port/CatalogEntityRepository.kt` | `peer-katalog.md §4.2` |
| `domain/port/CatalogAuditLog.kt` | `peer-katalog.md §4.3, §5.4` |
| `domain/port/PeerExplorerStore.kt` | `peer-katalog.md §8` |
| `catalog/PeerIndexSource.kt` | `peer-katalog.md §8.1` |
| `catalog/discovery/PeerDiscovery.kt` | `peer-katalog.md §9.2` |
| `catalog/discovery/TailscalePeerDiscovery.kt` | `peer-katalog.md §9.2` |
| `catalog/CatalogSyncScheduler.kt` | `peer-katalog.md §6.5` |
| `domain/FocusRestorationPolicy.kt` | `desktop-host.md §6.3` |
| `domain/port/ForegroundWindows.kt` | `desktop-host.md §6.3` + `docs/decisions/0018-windows-text-insertion-port.md` (interface header, not the `WindowHandle` value type) |
| `domain/session/SessionEnums.kt` | `desktop-host.md §3.2, §3.6` |
| `catalog/CompanionCatalogWiring.kt` | `peer-katalog.md §6.5, §8.1` on the central `PeerCatalogClientFactory` seam |

ADR anchors were added only where the header names the ADR as the *governing* decision:
ADR-0034 on `CatalogService` (the offer-side heart of the peer-catalog subsystem, anchored
once per SSoT — the finer per-file `§`-anchors carry the rest) and ADR-0018 on
`ForegroundWindows` ("A **port** (ADR-0018)"). ADR-0020 in `CatalogSyncScheduler`'s prose is
a pattern reference, not the governing decision, so only the spec `§6.5` anchor was added.

## Skips (with reasons)

| File | Reason |
|---|---|
| `domain/CompanionSettings.kt` | Class header states a general "defaults live in the domain" principle; the governing spec refs (`desktop-host.md §4.2/§4.3/§6.1/§8.5`, `peer-katalog.md §6.5`) are already anchored **per-property in the body** at the right granularity. No single class-level decision point → a class-level `@see` would be a misleading single anchor. |
| `server/CompanionServer.kt` (+5) | Pre-existing class; the change is a route-wiring line with its own scoped inline "why" comment (`peer-katalog.md §4.4`). A class-level `@see` would misattribute the whole server module to the catalog decision. |
| `server/plugins/StatusPagesSetup.kt` (+3) | Pre-existing plugin; added one exception handler, self-explanatory. |
| `domain/HealthService.kt` (+8) | Pre-existing class; added `supportsCatalog` param already carries a scoped KDoc citing `peer-katalog.md §4.4`. |
| `domain/DomainErrors.kt` (+8) | Pre-existing sealed hierarchy; new `CatalogEntityNotFoundException` already carries a scoped KDoc explaining the uniform-404 decision. |

## Removals

None. No comment restated code; the existing headers are high-quality WHY/invariant docs.

## Module headers

None added or extended — every new service/port/route/policy/enum file in scope already
carried a strong module/class header (Block-F work). The `@see` tag was the only missing anchor.

## Verification (self-check)

- **All added `@see` paths resolve:** `peer-katalog.md` and `desktop-host.md` exist under the
  plan's `research/`; sections §4.1/§4.2/§4.3/§5.4/§6.5/§8/§8.1/§9.2 (peer-katalog) and
  §3.2/§3.6/§6.3 (desktop-host) all exist as headings; `docs/decisions/0034-peer-catalog.md` and
  `docs/decisions/0018-windows-text-insertion-port.md` both exist.
- **No logic touched:** the diff over all 13 files is `+25` lines, every one a ` *` KDoc line
  (verified: no non-doc added line).
- **No comment noise added:** anchors are `@see` pointers, not code restatements.

## Notes for final

- `domain/CatalogService.kt` shows in `git diff --stat` as a **binary** file (`Bin 7519 -> 7657`).
  This is pre-existing (the docs-discovery report also saw it as `Bin 0 -> 7519`) and is caused
  by UTF-8 `§`/em-dash characters in the file that git's heuristic flags; it is **not** an
  encoding problem introduced by this pass. Cosmetic only — `git diff --textconv`/`grep -a`
  read it fine. Consider a `.gitattributes` `*.kt text` entry if the binary-diff display is a
  nuisance (out of scope here).
- The plan-wide `git diff` over `companion/**` currently shows many additional `@see` lines from
  **sibling inline workers** (ui / data / ai-secrets groups) editing the same worktree
  concurrently — those are not part of this group's 13-file scope.
