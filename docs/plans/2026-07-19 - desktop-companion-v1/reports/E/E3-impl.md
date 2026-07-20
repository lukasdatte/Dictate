# E3 — Peer Explorer + Discovery + headless (impl report)

**Chunk:** E3 · **Date:** 2026-07-20 · **Agent:** IMPL+TEST (chunk-impl-test)

## What was done

Implemented the E3 surface of spec `peer-katalog.md` (§8 Explorer, §9 Discovery + headless) per
Plan §5 Chunk E3:

- **Discovery (§9.2, AC11).** `PeerDiscovery` port + `PeerCandidate` + `NoopPeerDiscovery`
  (`companion/.../catalog/discovery/PeerDiscovery.kt`) and `TailscalePeerDiscovery`
  (CLI `tailscale status --json`, injectable runner, kotlinx-serialization DTOs with the CLI's
  PascalCase keys, online+DNS-name filter, FQDN-dot strip). Gap 5 resolved: **CLI over LocalAPI**
  (one uniform JSON on all three OSes vs. three socket/pipe/auth paths); every failure — missing
  binary, non-zero exit, timeout, garbage — collapses to the empty list (`JvmNetworkInterfaces`
  pattern).
- **Headless (§9.3, AC12).** `--headless` branch in `Main.kt` **before** `application {}`:
  `CompanionBootstrap.start()` (already Compose-free), shutdown hooks (server stop + guard release),
  parked main thread; boot failure → stderr + exit 1 (service-manager semantics). The
  `AlreadyRunning` path treats `--headless` like `--minimized` (no uninvited window surface).
- **Peer Explorer, consumer view (§8.1, AC13).** `PeerExplorerViewModel` (plain StateFlow VM,
  `HistoryViewModel` pattern) derives the full state matrix
  CURRENT/UPDATE_AVAILABLE/FORKED/STALE/SOURCE_REMOVED plus peer status OK/STALE/UNREACHABLE
  (derived, never stored — spec D4). Ports: `PeerExplorerStore` (stored half) and
  `PeerIndexSource`/`CatalogSyncRunner`/`CatalogSubscriber` (live half, see E3-1). Compose:
  `PeersScreen` (tabs) + `PeerListScreen` (status, add-peer w/ discovery candidates) +
  `PeerDetailScreen` (copies + sync-now/unsubscribe/fork + "Available from this peer" subscribe
  tab).
- **Offer view (§8.2, F34).** `OfferViewModel` + `OfferTab`: every own entity with a share switch
  (`visibility` through `CompanionConfigRepository.save` — envelope write, contentHash provably
  unchanged) + last pickup per entity from the `catalog_access_log` (newest row wins; credential
  pickups surface via their provider config's `credentialRef`).
- **Store (§5.1/§5.3).** `SqlDelightPeerExplorerStore` over new Companion.sq queries
  (`allPeers`/`insertPeer`/`subscriptionsForPeer`/`deleteSubscription`/`insertSubscription`) +
  `Subscriptions` enum adapters in `CompanionDatabase`. `copiesFrom` = subscriptions ∪ forks
  (provenance-LOCAL entities); `fork` = mode flip via config repo + subscription delete in one
  transaction (§5.3).
- **Editor integration (D3 screens).** `SourceBadge` on all four `ManagementScreen` entity lists
  ("from <peer>" / "copied from <peer>" / "forked"); `App.kt` gains the `PEERS` destination.
- **Android read-only Explorer (§8.3).** `PeerExplorerActivity` (settings page, APISettings
  pattern, manifest + `fragment_preferences.xml` entry + `PreferencesFragment` launcher) over the
  pure `PeerCopiesOverview` builder (Room provenance columns → per-peer groups, fork derivation).

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Explorer's live seams are ports, wired null | §8.1 (sync-now, "letzter Index"), §8.3 subscribe-tab | `PeerIndexSource`/`CatalogSyncRunner`/`CatalogSubscriber` defined + fully consumed by VM/UI, but their production impls need the per-peer `CatalogClient` (peers row + SecretStore secret) — exactly the credential-touching adapter delegated as **E2-1**. UI degrades honestly (stored-state matrix; subscribe disabled; sync-now no-op) | Finder-fixer separation on the credential path (E2's explicit rationale); duplicating that wiring here would collide with the delegated work | E2-1's owner implements 3 small fun-interfaces; all call sites exist | △ documented, see E3-1 |
| Android page has no sync-now/unsubscribe actions yet | §8.3 ("keine Aktionen außer sync-now/unsubscribe") | Read-only list only | Both actions need the Room-backed subscriber store + peers storage from E2-1; Android currently has no peers table | Same owner as E2-1 | △ documented, see E3-1 |
| Add-peer flow shows candidates + manual-path hint, no in-app pair-redemption dialog | §9.1 | Candidates listed; the §9.1 pairing (DispatchClient.pair + SecretStore put + `addPeer`) is not driven from a companion-side dialog yet. `PeerExplorerStore.addPeer` + `insertPeer` are ready | Pair-redemption stores a peer secret — same credential seam as E2-1; no acceptance criterion covers the dialog | E2-1 wires it; store+UI seams ready | △ documented, see E3-1 |
| Matrix fallback: fresh peer + no index ⇒ CURRENT | §8.1 table (silent on "no index") | Explicit rule + test | A scare label on every cold open would cry wolf; STALE already covers genuinely old data | none | ✓ |
| `PeerCandidate` fields | §9.2 sketch | Kept spec shape (`magicDnsName`, `address`); FQDN trailing dot stripped, offline/nameless peers filtered | A candidate without stable name/online state is unusable for the §9.1 follow-up | none | ✓ |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| E3-1 | Important | Live-catalog seams unwired (extends E2-1, same owner): production impls of `PeerIndexSource`/`CatalogSyncRunner`/`CatalogSubscriber` (`companion/.../catalog/PeerIndexSource.kt`) via the peers-row→`CatalogClient`+SecretStore builder (`CatalogSyncTargets`), companion add-peer pair-redemption dialog (§9.1), Android peers storage + sync-now/unsubscribe actions (§8.3), and `CatalogSyncScheduler.start()` call site. All consumer call sites exist and degrade honestly (`CompanionContainer.kt:126-135` doc, `PeersScreen.kt:44-47`). | delegated | blocks-following |

## Inline fixes applied

- `ChordMigrationSeedTest.kt` — added the now-required `subscriptionsAdapter` to its hand-built
  `DictateCompanionDb` (the new subscriptions queries materialize the adapter into the DB
  constructor; same mechanism as the D1b/processing_steps precedent).
- First draft of the Tailscale DTOs used camelCase field names; fixed to the CLI's real PascalCase
  (`Peer`/`DNSName`/`TailscaleIPs`/`Online`) via `@SerialName` before any test ran against fixtures.

## Tests (all green)

- `:companion` `TailscalePeerDiscoveryTest` — 7 tests (AC11: fixture parse incl. offline/nameless
  filter + FQDN strip, CLI absent → empty, garbage → empty, no-Peer-map JSON → empty, real-binary
  binding never throws, Noop).
- `:companion` `PeerExplorerViewModelTest` — 12 tests (AC13: all five matrix states + no-index
  fallback, OK/STALE/UNREACHABLE, sync-now, unsubscribe, fork, subscribe incl. unwired-seam
  degradation, discovery-into-candidates).
- `:companion` `HeadlessBootTest` — AC12: `CompanionBootstrap.start()` alone (ephemeral port)
  serves `/v1/health` (401 = serving + auth intact) in a `java.awt.headless` JVM, no Compose.
- `:companion` `SqlDelightPeerExplorerStoreTest` — 7 tests (peers round-trip, subscription+fork
  union, other-peer isolation, unsubscribe keeps copy, fork flips mode + keeps provenance + hash
  unchanged + drops row, credential fork).
- `:companion` `OfferViewModelTest` — 3 tests (visibility + last pickup, private w/o pickup,
  toggle keeps contentHash).
- `:app` `PeerCopiesOverviewTest` — 4 tests (grouping, fork derivation, empty, unknown-mode
  degradation); `PeerExplorerActivityTest` — 2 Robolectric smokes (empty state; rendered group).

Runs: `:companion:test` full ✓, `:shared:test` full ✓, `:companion:verifySqlDelightMigration` ✓
(no DDL change — queries only), `:app:testDebugUnitTest` scoped to `settings.*`+`config.*`+
`peers.*` ✓ (the E3 app diff is additive UI; the full-suite run stays with the block audit, matching
E2's finding that the suite carries one pre-existing keystore-order flake).

## Self-check

Checked: every §8/§9 requirement mapped (✓/△ above); integration targets both diffed
(`Main.kt` headless branch `Main.kt:83-118`, `App.kt` PEERS destination `App.kt:38/70/88`); call
sites named for every integration point (PeersScreen→container seams; PreferencesFragment→
PeerExplorerActivity; ManagementScreen→SourceBadge); naming/patterns against `HistoryViewModel`/
`ManagementScreen`/`APISettingsActivity` precedents; no stubs or TODOs in the diff (null seams are
documented delegation points with honest UI degradation, not stubs). Found+fixed: the two inline
fixes above; replaced an over-clever `collectAsState` helper in `PeersScreen` with the standard
extension; switched `PeerExplorerActivity` from a needless executor to the house main-thread-DAO
pattern.

## Files outside assigned scope (drift)

- `companion/src/test/kotlin/.../data/ChordMigrationSeedTest.kt` — one-line adapter addition,
  forced by the generated DB constructor (see inline fixes). No behavioural change.

## Files modified

Companion main: `catalog/discovery/PeerDiscovery.kt` [NEW], `catalog/discovery/TailscalePeerDiscovery.kt` [NEW],
`catalog/PeerIndexSource.kt` [NEW], `domain/port/PeerExplorerStore.kt` [NEW],
`data/SqlDelightPeerExplorerStore.kt` [NEW], `data/CompanionDatabase.kt`, `CompanionContainer.kt`,
`Main.kt`, `ui/App.kt`, `ui/config/ManagementScreen.kt`, `ui/peers/{PeersScreen,PeerListScreen,PeerDetailScreen,OfferScreen,PeerExplorerViewModel,OfferViewModel}.kt` [NEW],
`sqldelight/.../Companion.sq`.
Companion test: `catalog/TailscalePeerDiscoveryTest.kt` [NEW], `ui/PeerExplorerViewModelTest.kt` [NEW],
`ui/OfferViewModelTest.kt` [NEW], `data/SqlDelightPeerExplorerStoreTest.kt` [NEW],
`HeadlessBootTest.kt` [NEW], `data/ChordMigrationSeedTest.kt` (drift).
App main: `peers/ui/PeerCopiesOverview.kt` [NEW], `peers/ui/PeerExplorerActivity.kt` [NEW],
`res/layout/activity_peer_explorer.xml` [NEW], `res/values/strings.xml`,
`res/xml/fragment_preferences.xml`, `AndroidManifest.xml`, `settings/PreferencesFragment.java`.
App test: `peers/PeerCopiesOverviewTest.kt` [NEW], `peers/PeerExplorerActivityTest.kt` [NEW].
