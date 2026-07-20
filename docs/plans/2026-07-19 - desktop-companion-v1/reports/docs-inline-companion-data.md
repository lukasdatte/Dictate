# Inline-Anchor Worker Report — `companion-data`

**Date:** 2026-07-20T17:25:00+02:00
**Slug:** companion-data
**Agent:** doc-worker-inline (finalize)
**Scope:** `companion/src/main/kotlin/net/devemperor/dictate/companion/data/` + `companion/src/main/sqldelight/net/devemperor/dictate/companion/db/`

## Summary

The group was already strongly anchored on anchors #1 (module/class headers) and #3
(gotcha comments) — every non-trivial repository carries a 3-10 line header explaining
responsibility, invariants, and library/protocol quirks (`PRAGMA foreign_keys`,
`content_hash` recompute-on-write, one-transaction turn writes, fork-protection-is-the-query).
The one systematic gap, matching the discovery report's finding that `companion/**`
`@see` coverage is sparse (8/155), was **anchor #2: the sanctioned `@see` plan/ADR tag**.
The files referenced governing specs/ADRs only in prose with bare filenames
(`desktop-host.md §5.5`, `peer-katalog.md §8`, `ADR-0013 §3`) — not resolvable by a
colleague who does not know where those specs live.

Work applied: added one class-level `@see` block per substantive repository, using the
established companion convention (verified against `SqlDelightUsageSink.kt`,
`CompanionConfigWireMapping.kt`): full-path plan-spec section + governing promoted ADR,
appended at the end of the existing KDoc. Existing prose (the narrow claim-level pointers
and the "why") was left intact — the `@see` is the consolidated, resolvable class-level
decision anchor, the inline prose stays claim-scoped, matching the module's precedent.

## Anchors added / updated / removed — per file

| File | Anchor #1 header | Anchor #2 `@see` added | Anchor #3 gotcha |
|---|---|---|---|
| `CompanionDatabase.kt` | present (kept) | `desktop-host.md §3.2, §3.3` + `ADR-0030` | present (kept) |
| `SchemaMigrator.kt` | present (kept) | `desktop-host.md §3` | present (kept) |
| `CompanionConfigRepository.kt` | present (kept) | `ADR-0030` | present (kept) |
| `DesktopSessionRepository.kt` | present (kept) | `desktop-host.md §5.5, §8.3, §9.3` + `ADR-0012` + `ADR-0013` | present (kept) |
| `SqlDelightCatalogRepository.kt` | present (kept) | `peer-katalog.md §4.2, §10` + `ADR-0034` | present (kept) |
| `SqlDelightCatalogAuditLog.kt` | present (kept) | `peer-katalog.md §5.4` + `ADR-0034` | present (kept) |
| `SqlDelightCatalogSubscriberStore.kt` | present (kept) | `peer-katalog.md §6` + `ADR-0034` | present (kept) |
| `SqlDelightPeerExplorerStore.kt` | present (kept) | `peer-katalog.md §8` + `ADR-0034` | present (kept) |
| `SqlDelightHistoryRepository.kt` | present (kept) | `desktop-host.md §3.4, §3.5` + `ADR-0035` + `ADR-0020` | present (kept) |

No anchors removed. No comment noise (code-restating comments) found to strip.

## Skips (with reasons)

| File | Reason |
|---|---|
| `SqlDelightChordMappingRepository.kt` | Header is already strong and self-contained. Its internal prose ref `(D6, §5.4)` is ambiguous — `desktop-host.md §5.4` is "Reducer-Reinheit + Effekte", not chord persistence (which is touched under §3.1 / §6 Hotkey). Per worker rule "don't anchor when the plan decision itself is unclear", I did not add a possibly-dangling `@see`. See notes. |
| `SqlDelightDeviceRepository.kt` | Thin CRUD wrapper; the `DeviceRepository` port carries the contract and the `ADR-0017` secret-hash rule already lives in the `Companion.sq` column comment. Below the anchor-worthiness bar. |
| `SqlDelightSettingsRepository.kt` | Trivial key/value wrapper, no plan/ADR decision governs its shape. Correctly header-light. |
| `memory/InMemoryChordMapping.kt`, `memory/InMemorySettings.kt` | Test fakes / stand-ins; not decision points. Existing role-explaining headers are sufficient. |
| `Companion.sq` | Already carries a strong SQL header + gotcha comments with spec/ADR prose refs (`desktop-host.md §3.2/§3.6`, `ADR-0020`, `ADR-0007`, `ADR-0017`). SQL uses `--` comments, not JSDoc `@see`; well-anchored in the sanctioned SQL form. |
| `migrations/2.sqm`, `3.sqm`, `4.sqm` | Each opens with a one-line SQL header naming the version bump + governing spec (`desktop-host.md §3.4`, `peer-katalog.md §5.2`, `peer-katalog.md §5`). Adequately anchored. |

## Self-check

- Every `@see` target resolves: all 6 ADR files exist (`0012`, `0013`, `0020`, `0030`, `0034`, `0035`); all 13 cited spec-section headings exist in `desktop-host.md` (§3, §3.2, §3.3, §3.4, §3.5, §5.5, §8.3, §9.3) and `peer-katalog.md` (§4.2, §5.4, §6, §8, §10).
- Diff is comment-only: every added line is a ` * @see …` tag or a blank ` *` KDoc-continuation line. No code logic, imports, or formatting changed (verified via `git diff` filter).
- `@see` path form (with spaces in `2026-07-19 - desktop-companion-v1`) matches the existing companion-module convention exactly.

## Files outside assigned scope (drift)

None.
