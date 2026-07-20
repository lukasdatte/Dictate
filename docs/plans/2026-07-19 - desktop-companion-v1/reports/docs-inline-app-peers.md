# Inline-Anchor Worker Report — `app-peers`

**Date:** 2026-07-20T17:25:00+02:00
**Slug:** app-peers
**Target files:** `app/src/main/java/net/devemperor/dictate/peers/`
**Agent:** docs-inline (finalize)

## Summary

All 10 `.kt` files in the peers subsystem are new this range (`c46cfe8..HEAD`,
709 insertions). They already carry strong prose **module/class headers**
(responsibility, invariants, fork-protection, no-secret-column notes) with
inline `(peer-katalog.md §X)` shorthand refs — but lacked the trailing
navigable **`@see` plan/ADR anchor** that the sibling `app/config` subsystem
(discovery: "good — mostly anchored") carries on every header. That is exactly
the gap the discovery report flagged (`app/peers` `@see` coverage **0/10 —
absent**).

Work performed: added one `@see` anchor to the spec section(s) each header
already cites, to every module header; plus one `@see docs/decisions/0034-peer-catalog.md`
at the single densest ADR decision point (the subscriber store). No prose was
rewritten, no code, imports, or formatting touched — the diff is 21 pure
additive JSDoc lines.

## Anchors added (per file)

| File | Anchor added | Section rationale |
|---|---|---|
| `AndroidCatalogSubscriberStore.kt` | `@see …/peer-katalog.md §6.2, §5.3` + `@see docs/decisions/0034-peer-catalog.md` | Envelope-credential→SecretStore, graft-not-raw-write, fork-protection query — the ADR-0034 core decisions all converge here (§6.2 pull-of-changed-entity, §5.3 subscriptions/fork). Only file that earns the ADR anchor. |
| `AndroidCatalogSyncGateway.kt` | `@see …/peer-katalog.md §6.5` | Production gateway = the §6.5 scheduler/best-effort driver. |
| `CatalogSyncGateway.kt` | `@see …/peer-katalog.md §6.5` | The worker↔engine seam interface (§6.5). Anchor on the interface header only, not the small `CatalogSync` hand-off object (one anchor per decision point). |
| `CatalogSyncWorker.kt` | `@see …/peer-katalog.md §6.5` | WorkManager poller (§6.5 scheduler). |
| `AndroidNotificationPort.kt` | `@see …/peer-katalog.md §7.2` | Android notification port (§7.2). |
| `PeerSecrets.kt` | `@see …/peer-katalog.md §5.1` | Peer pairing-secret SecretStore addressing (§5.1 peers). |
| `dao/PeerDaos.kt` | `@see …/peer-katalog.md §5.1, §5.3` | peers + subscriptions DAOs (§5.1, §5.3). |
| `entity/PeerRoomEntities.kt` | `@see …/peer-katalog.md §5.1, §5.3` | peers + subscriptions Room entities (§5.1, §5.3). Class header only (single decision point shared by both entities). |
| `ui/PeerCopiesOverview.kt` | `@see …/peer-katalog.md §8.3` | Android read-only Explorer model (§8.3). |
| `ui/PeerExplorerActivity.kt` | `@see …/peer-katalog.md §8.3` | Android read-only Explorer activity (§8.3). |

## Skips / decisions

- **No module-header additions** — every file already had a compliant 3–8 line
  header; extending them was unnecessary (the plan added no pattern the headers
  are silent on).
- **No comment removals** — none of the existing comments restate code; the
  gotcha/invariant comments (fork-protection query, `coalesce` no-op guard,
  `SecurityException` race, WorkManager-unavailable guard) are all
  non-derivable and correctly earn their place. Nothing to prune.
- **ADR-0034 anchored once, not per-file** — ADR-0034 is Project-Wide and
  governs the whole family; the spec `@see` on each header already routes a
  reader to `peer-katalog.md`, whose References link ADR-0034. Anchoring the ADR
  on all 10 files would be the comment-noise anti-pattern. One anchor at the
  densest decision point (the subscriber store) matches "one anchor per decision
  point".
- **Inline prose `(peer-katalog.md §X)` refs left as-is** — my scope is anchor
  content, not prose. The sibling `app/config` convention keeps contextual
  section shorthand in prose *and* a full-path `@see` (e.g. `ConfigRepository.kt`:
  prose `(spec §7.4, §5.3)` + `@see …entitaetenmodell-android.md §7.4, §5.3`);
  the peers headers now match that shape. Not SSoT-redundant per the established
  pattern — prose refs are contextual, the `@see` is the single navigable
  anchor.

## Self-check

- All 6 distinct cited spec sections (§5.1, §5.3, §6.2, §6.5, §7.2, §8.3) exist
  as `###` headings in `peer-katalog.md` — verified.
- `docs/decisions/0034-peer-catalog.md` exists — verified.
- `@see` path format (literal space in `2026-07-19 - desktop-companion-v1`, no
  URL-encoding) matches the repo's existing `@see docs/plans/…` convention.
- Diff is anchor-only: `git diff --stat` shows 21 insertions, 0 deletions, no
  code/import/format lines.

## Notes (not edited — for `notes_for_final`)

- `AndroidCatalogSubscriberStore.promptDtoByUuid()` does
  `db.promptDao().getAll().firstOrNull { it.uuid == uuid }` — an O(n) full-table
  scan per prompt entity update. A `promptDao().byUuid(uuid)` query would be the
  cleaner path. Minor efficiency smell, out of anchor scope.

## Files outside assigned scope (drift)

none
