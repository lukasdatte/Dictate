# Repair Wave W1-6 — E-T4

**Date:** 2026-07-20T13:30:00+02:00
**Agent:** repair-fix
**Cluster:** E-T4 (green / Nice-to-have)

## Finding E-T4 — FakePeerExplorerStore.copiesFrom ignored its peerId

**File:** `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/PeerExplorerViewModelTest.kt`

The in-memory fake returned `subscriptions.toList()` regardless of the requested
`peerId`, so a ViewModel defect passing the wrong peer to `copiesFrom` would not
be caught by the VM tests — only `SqlDelightPeerExplorerStoreTest` exercised real
per-peer scoping.

### What I did

- Reworked `FakePeerExplorerStore` to record each copy against its originating
  peer (`copies: MutableList<Pair<String, SubscribedCopy>>`) — the fake's stand-in
  for the SQL store's `source_peer_id` column (see
  `SqlDelightPeerExplorerStore.copiesFrom`, which unions `subscriptionsForPeer` +
  `forksFrom`, both peer-scoped). `copiesFrom(peerId)` now filters by that peer;
  `unsubscribe`/`fork` operate on the paired list unchanged in behavior.
- Added `fun addCopy(peerId, copy)` as the fake's write seam and migrated all 8
  existing write sites (`store.subscriptions += …` → `store.addCopy("p1", …)`).
  No test read `store.subscriptions`, so the field's removal is safe.
- Added a focused test `copies_areScopedToTheSelectedPeer`: two peers, one copy
  each; selecting `p1` must surface only `local-a`. This fails if `copiesFrom`
  ignores its argument (both copies would leak), directly self-guarding the wiring
  the finding flagged.

### Verification

`./gradlew :companion:test --tests "…PeerExplorerViewModelTest"` → BUILD
SUCCESSFUL, 14 tests, 0 failures (was 13; +1 new scoping test). Result XML
confirms `copies_areScopedToTheSelectedPeer` ran and passed.

## Skipped findings

None.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/ui/PeerExplorerViewModelTest.kt`

## Drift (files outside assigned scope)

None. All edits are inside the single file named by the finding.

> Note: the file was concurrently modified by a parallel fixer (VM constructor
> param `discoveryDispatcher` → `ioDispatcher` in the `viewModel()` helper). That
> change is unrelated to E-T4, compiled cleanly alongside my edits, and I left it
> intact.
