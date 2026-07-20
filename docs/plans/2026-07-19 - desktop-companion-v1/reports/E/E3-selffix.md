# E3 — Self-Fix (fresh eyes, diff-based)

**Chunk:** E3 · **Date:** 2026-07-20T13:30:00+02:00 · **Agent:** chunk-self-fix
**Wave commit reviewed:** 916fab31 · **Impl report:** `reports/E/E3-impl.md`

## What was done

Reviewed the full E3 diff (discovery, headless, Explorer VMs/screens, SQLDelight store + queries,
Android read-only page, tests) against Plan §5 Chunk E3 and spec `peer-katalog.md` §8/§9 with the
plan-correctness / code-quality / test-quality lenses. Three findings, all fixed inline; tests green
after fixes.

## Deviations

No new deviations beyond those in the impl report; the impl report's deviation table checked out —
each △ row genuinely maps to the delegated E2-1 seam work, and the E3 acceptance criteria
(AC11/AC12/AC13) are all covered without those seams.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| E3-SF1 | Important | `PeerExplorerViewModel.discoverCandidates()` ran the subprocess-backed `discovery.discover()` (blocks up to the 5 s CLI timeout) directly on the injected scope, which in production is the Compose UI scope (`PeersScreen`'s `rememberCoroutineScope()`) — violating `PeerDiscovery`'s own kdoc contract ("never the UI thread") and freezing the window while `tailscale status` runs. Fixed: injected `discoveryDispatcher` (default `Dispatchers.IO`), `withContext` hop around the discover call; test factory passes `Unconfined` to keep inline assertions valid. `PeerExplorerViewModel.kt:100-108,140-144` | fixed-inline | none |
| E3-SF2 | Nice-to-have | `PeerExplorerStore.kt` kdoc defects: class doc linked `PeerIndexSource` under the wrong package (`ui.peers.` instead of `catalog.`), and the `SubscribedCopy` doc claimed "a forked/one-shot copy has no subscription row" — contradicting the schema's `CHECK (mode IN ('SUBSCRIBE','ONE_SHOT'))`, under which ONE_SHOT rows DO exist; only forks lose their row. Both corrected. `PeerExplorerStore.kt:10,64-68` | fixed-inline | none |
| E3-SF3 | Nice-to-have | New `CompanionContainer` params and the `PeersScreen` fallback used fully-qualified type names against the files' established import style. Replaced with imports (`CatalogService`, `PeerExplorerStore`, `PeerDiscovery`/`NoopPeerDiscovery`/`TailscalePeerDiscovery`, `CatalogAuditLog`, `PeerIndexSource`, `CatalogSyncRunner`, `CatalogSubscriber`). `CompanionContainer.kt:23-30,44,54`, `PeersScreen.kt:22,42` | fixed-inline | none |

Not raised as issues (verified fine): §8.1 matrix precedence (FORKED > STALE > index-derived) matches
the spec's intent and is documented + tested; the documented CURRENT fallback for "fresh peer, no
index" is a defensible D4 deviation; blocking SQLite reads on the UI scope match the
`HistoryViewModel` house pattern (unlike the subprocess case above); `FLAG_ACTIVITY_NEW_TASK` and
the APISettings activity pattern match the surrounding `PreferencesFragment` launchers; the E1/E2
working tree carries uncommitted files, but that is the workflow's commit-agent domain, not an E3
code defect.

## Inline fixes applied

1. `PeerExplorerViewModel.kt` — `discoveryDispatcher: CoroutineDispatcher = Dispatchers.IO`
   constructor param (kdoc'd why) + `withContext(discoveryDispatcher)` around `discovery.discover()`.
2. `PeerExplorerViewModelTest.kt` — factory passes `discoveryDispatcher = Dispatchers.Unconfined`
   with a one-line why-comment.
3. `PeerExplorerStore.kt` — fixed the kdoc package link; corrected the one-shot/fork
   subscription-row claim to match the §5.3 CHECK.
4. `CompanionContainer.kt`, `PeersScreen.kt` — fully-qualified names replaced by imports.

## Files modified

- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/peers/PeerExplorerViewModel.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/PeerExplorerViewModelTest.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/port/PeerExplorerStore.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/CompanionContainer.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/peers/PeersScreen.kt`

## Files outside assigned scope (drift)

none

## Final test result

`./gradlew :companion:test :companion:verifySqlDelightMigration :shared:test` — BUILD SUCCESSFUL.
`./gradlew :app:testDebugUnitTest --tests "net.devemperor.dictate.peers.*"` — BUILD SUCCESSFUL
(up-to-date; no app-side file was touched by this pass).
