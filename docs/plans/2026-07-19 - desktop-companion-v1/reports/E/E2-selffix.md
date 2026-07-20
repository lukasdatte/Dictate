# E2 — Sync-Engine (`CatalogSyncEngine.kt`) — self-fix (fresh eyes)

**Chunk:** E2 · **Date:** 2026-07-20 · **Agent:** SELF-FIX (chunk-self-fix) · **Wave commit:** d057bd0
**Scope:** `shared/.../sync/CatalogSyncEngine.kt` only.

## What I did

Reviewed the just-committed `CatalogSyncEngine.kt` against spec `peer-katalog.md` §6.1–§6.5 and
Plan §5 E2 / §3 D5.f, through three lenses. The engine is correct and faithful to the spec; its four
documented deviations (Stale→PeerUnreachable merge, `NotificationPort` in `:shared`, real E1 enum
names, partial-run root-hash hold) are all defensible D4 calls, each explained in `E2-impl.md`. No
undocumented drift, no logic bug, no code-quality defect that warranted a change in the engine file.

I found and closed **one real test-coverage gap** (below) and re-ran the suite green.

## Fix applied — test coverage (test quality lens)

The transient mid-pull fetch-failure branch of `onPullFailure` (a non-`EntityGone` `DispatchError` —
the peer vanishes on connectivity *between* the index GET and the entity GET) was unexercised. Only
its sibling `EntityGone`/404 branch had a test. This is the engine's self-healing path (§6.4): keep
the copy, report `PartialVerifyFailure`, and hold the root hash so the next run retries — safety-
critical and very reachable (any network blip mid-run), not defensive-unreachable.

Added `transientFetchFailureMidPull_keepsCopyReportsFailureAndDoesNotAdvanceRoot` to
`CatalogSyncEngineTest.kt`, driving it via `FakeTransport.fail(entityPath, IOException)` →
`DispatchError.Unreachable`. Asserts: outcome is `PartialVerifyFailure`, copy untouched, NOT recorded
as a source removal, root hash `null` (not advanced), nothing announced.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

The implementer's `E2-1` (DB-backed `CatalogSubscriberStore` adapters + two-peer E2E) is correctly
`delegated` and out of my `CHUNK_FILES` scope; I do not re-raise it.

## Considered but not changed

- The mutable `changes`/`failures` accumulator lists threaded through the four `pull*` helpers are a
  mild smell, but the form is idiomatic for this pure engine, small, and fully tested; a return-based
  refactor would add churn/risk without a clear long-term win (D4 net-negative). Left as-is.
- `error::class.java.simpleName` appears in two distinct contexts (index vs. pull failure) — 2-use,
  below the 3-use extraction threshold. Left as-is.

## Files outside assigned scope (drift)

- `shared/src/test/kotlin/net/devemperor/dictate/shared/sync/CatalogSyncEngineTest.kt` — added one
  coverage test for the chunk's own untested self-healing branch. Rationale: the test-quality lens
  applies to the chunk's code; its tests live in this sibling file, and one focused, low-risk test is
  the proportionate fix over delegating a trivial gap.

## Files modified

- `shared/src/test/kotlin/net/devemperor/dictate/shared/sync/CatalogSyncEngineTest.kt`

## Final test result

`./gradlew :shared:test --tests "net.devemperor.dictate.shared.sync.*"` — **BUILD SUCCESSFUL**.
`CatalogSyncEngineTest` now 13 tests (was 12), all green; `SyncNotificationTest` 3, green.
