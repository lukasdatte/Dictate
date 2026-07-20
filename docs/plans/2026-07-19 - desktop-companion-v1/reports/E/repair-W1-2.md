# Repair Wave W1-2 — report

**Date:** 2026-07-20T13:30:00+02:00
**Agent:** repair-fix (Block E)
**Cluster:** logic-E-2

## Finding logic-E-2 — blocking network seams on the UI scope (FIXED)

**What was wrong:** `PeerExplorerViewModel` invoked three blocking, non-suspend
network/credential-touching seams directly on the injected `scope` (the Compose
Main scope in production, `PeersScreen.kt:41`) with no dispatcher hop:

- `indexSource.entries(peerId)` inside `load()` (called from init/refresh/
  selectPeer/syncNow/unsubscribe/fork/subscribe)
- `syncRunner.syncNow(peerId)` in `syncNow()`
- `subscriber.subscribe(peerId, entry)` in `subscribe()`

This contradicted the E3-SF1 self-fix, which had already hopped the equally
blocking `discovery.discover()` to a dispatcher. Latent today (the seams are
null-wired), but opening a peer detail pane or tapping "Sync now" would freeze
the companion window once E2-1 wires the production adapters.

**Fix applied (E3-SF1 pattern, uniformly):**

- Generalized the existing `discoveryDispatcher` constructor parameter into a
  single `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` — one seam for
  every blocking, credential/network-touching call, rather than one dispatcher
  per seam (most sustainable: new seams in this class reuse it). Doc comment
  rewritten to enumerate all four covered seams.
- `syncNow()`: wrapped `syncRunner?.syncNow(peerId)` in `withContext(ioDispatcher){}`.
- `subscribe()`: wrapped `subscriber?.subscribe(peerId, entry)` in `withContext(ioDispatcher){}`.
- `discoverCandidates()`: switched its `withContext(discoveryDispatcher)` to `ioDispatcher`.
- `load()`: made `suspend` (every caller already runs inside `scope.launch`, so
  no call-site change) and wrapped the `indexSource.entries()` fetch in
  `withContext(ioDispatcher){}`. The local SQLite reads (`store.peers()`,
  `store.copiesFrom()`) intentionally stay inline on the scope per the
  HistoryViewModel house pattern (documented in an inline comment).

File pointers:
- `companion/.../ui/peers/PeerExplorerViewModel.kt:94` (`ioDispatcher` param),
  `:107-110` (syncNow), `:129-133` (subscribe), `:136-139` (discoverCandidates),
  `:144-156` (`suspend load` + entries hop).

**Test update:** `PeerExplorerViewModelTest` constructs the VM with the renamed
named argument (`ioDispatcher = Dispatchers.Unconfined`). `Unconfined`
`withContext` runs inline, so state stays readable on the next line and all
existing matrix/action assertions hold unchanged.

## Tests

`./gradlew :companion:test --rerun-tasks` — BUILD SUCCESSFUL (all executed, green).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/ui/peers/PeerExplorerViewModel.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/ui/PeerExplorerViewModelTest.kt`

## Drift

- `PeerExplorerViewModelTest.kt` — required: the fix renames the constructor
  parameter, so the test's named argument (`discoveryDispatcher` →
  `ioDispatcher`) had to change for the module to compile. In-scope for the fix.
  Note: a parallel fixer independently restructured this same test file's fake
  store (peer-scoped `copiesFrom`) on disjoint lines; the two edits do not
  conflict and both are present in the final green build.

## Skipped

None.
