# Block E — Audit: `logic`

**Block:** E (peer-catalog) · **Date:** 2026-07-20T13:30:00+02:00 · **Topic:** logic
**Scope commit range:** `c46cfe8..HEAD` (file-scoped to BLOCK_FILES)
**Grounding:** `knowledge-typescript` (language-neutral: null-safety, exhaustiveness, race/edge reasoning)

## Verdict

Two confirmed logic/robustness defects. The acceptance core (`CatalogSyncEngine`) is
correct, faithful to `peer-katalog.md` §6, and thoroughly tested — no finding there.
The two findings both sit on the platform/UI seams: a subprocess-timeout gap in
`TailscalePeerDiscovery`, and blocking network seams invoked on the Compose UI scope
in `PeerExplorerViewModel` (an inconsistency the E3 self-fix half-closed for the
sibling discovery call).

## Findings

### logic-E-1 (Important) — `TailscalePeerDiscovery`: the 5 s timeout is defeated by reading stdout before `waitFor`, and the child process leaks on timeout

`TailscalePeerDiscovery.kt:60-68`

```kotlin
val process = ProcessBuilder("tailscale", "status", "--json").redirectErrorStream(false).start()
val output = process.inputStream.bufferedReader().use { it.readText() }   // blocks until stdout EOF
if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
```

- **Timeout not enforced.** `readText()` blocks until the child closes stdout (EOF).
  If `tailscale` hangs and never closes stdout, `readText()` blocks *forever* and the
  `waitFor(5 s, …)` guard on the next line is never reached. The class kdoc promises
  "a timeout … collapse[s] into an empty list" — that guarantee does not hold for a
  hung process. Because production discovery runs on `Dispatchers.IO`
  (`PeerExplorerViewModel.discoveryDispatcher`), a hang permanently ties up an IO
  worker thread; repeated invocations can starve the pool.
- **Process leak on the timeout branch.** When `waitFor` *does* return `false`
  (child still alive but stdout already closed), the code returns `null` without
  `process.destroy()` / `destroyForcibly()`, leaving an orphaned child.

**Failure scenario:** `tailscale status --json` is invoked while the daemon is wedged
(binary present but stuck) → stdout stays open with no data → `readText()` blocks
indefinitely → the discovery coroutine never completes and no empty-list fallback is
produced, contradicting AC11's "never blocks / always degrades to empty" contract.

**Suggested fix:** read stdout on a separate thread (or drain then `waitFor`), and on
timeout call `process.destroyForcibly()` before returning `null`. Simplest robust
form: `process.waitFor(TIMEOUT_SECONDS, SECONDS)` first with a bounded read; on
`false`, `destroyForcibly()` and return `null`. Reading stdout concurrently avoids the
stdout-buffer/stderr-buffer deadlock as well (`redirectErrorStream(false)` leaves
stderr un-drained).

### logic-E-2 (Important) — `PeerExplorerViewModel`: blocking network seams run on the injected UI scope with no dispatcher hop, inconsistent with the E3-SF1 discovery fix

`PeerExplorerViewModel.kt:106-109` (`syncNow`), `127-131` (`subscribe`), `141-157` (`load`, called from `init`→`refresh`, `selectPeer`, `unsubscribe`, `fork`, `syncNow`)

The injected `scope` is the Compose UI scope in production
(`PeersScreen.kt:41` `rememberCoroutineScope()` → `Dispatchers.Main`; confirmed by the
VM's own kdoc lines 88-91 "[scope] in production IS the UI scope"). Three seams are
**blocking network I/O** yet are invoked directly inside `scope.launch { … }` with no
`withContext` hop:

- `indexSource.entries(peerId)` in `load()` — its port kdoc: "fetching an index needs
  a `CatalogClient` built for the peer … blocking". Runs on **every** `load()`, i.e.
  on `init`/`refresh`/`selectPeer`/`unsubscribe`/`fork`/`syncNow`.
- `syncRunner.syncNow(peerId)` in `syncNow()` — "one engine run against one peer", a
  full network sync.
- `subscriber.subscribe(peerId, entry)` in `subscribe()` — network pull + verify + write.

Contrast with `discoverCandidates()` (`134-137`), which the E3 self-fix (E3-SF1)
specifically wrapped in `withContext(discoveryDispatcher = Dispatchers.IO)` *because*
`PeerDiscovery`'s contract "forbids the UI thread". The index/sync/subscribe seams are
the identical class of blocking work (`PeerDiscovery.kt:13-15` and `PeerIndexSource`
carry the same "never the UI thread"/"blocking" note) but were left on `Main`.

**Failure scenario (latent):** these seams are currently null-wired
(`PeersScreen.kt:45` `container.peerIndexSource ?: PeerIndexSource { null }`;
`syncRunner`/`subscriber` null today), so there is no freeze *yet*. The moment E2-1
supplies the production adapters the code is built to receive, opening a peer's detail
pane (`load()` → `indexSource.entries`) or tapping "Sync now" freezes the companion
window for the full network round-trip / CLI timeout on the Main dispatcher.

**Suggested fix:** apply the E3-SF1 pattern uniformly — hop the three network seams to
an injected IO dispatcher via `withContext(ioDispatcher) { … }` (the local SQLite
reads `store.peers()`/`copiesFrom()` may stay on the UI scope per the accepted
`HistoryViewModel` house pattern). Keeps the threading contract consistent across all
four seams of this VM.

## Coverage

**Audited (full read):**
- `shared/.../sync/CatalogSyncEngine.kt` — clean: two-hash verify-before-write, partial-run
  root-hash hold, source-removed/EntityGone/transient-fetch branches all correct and tested.
- `shared/.../client/CatalogClient.kt` — bare-404→`EndpointMissing` vs enveloped
  `EntityGone` split correct; `credentials()` lambda read at call time; `IOException`→`Unreachable`.
- `companion/.../catalog/discovery/TailscalePeerDiscovery.kt` — finding logic-E-1.
- `companion/.../catalog/discovery/PeerDiscovery.kt`, `catalog/PeerIndexSource.kt` — ports, fine.
- `companion/.../ui/peers/PeerExplorerViewModel.kt` — finding logic-E-2; matrix derivation
  (`state()`/`status()`) otherwise correct (first-match precedence FORKED>STALE>index; null-index→CURRENT documented D4).
- `companion/.../ui/peers/OfferViewModel.kt` — verified: `all()` is `ORDER BY at DESC`
  (`Companion.sq:766`), so `putIfAbsent` correctly keeps the newest pickup. No bug.
- `companion/.../data/SqlDelightPeerExplorerStore.kt` — `copiesFrom` union (subscriptions ∪
  forks with `alreadyListed` guard) correct; `fork()` mode-flip + `deleteSubscription` in one
  `database.transaction` (nested `config.save` transaction supported by SqlDelight).
- `companion/.../domain/port/PeerExplorerStore.kt`, `PeerIndexSource.kt` — port contracts.
- `companion/.../ui/peers/{PeersScreen,PeerListScreen,PeerDetailScreen}.kt` — layout only;
  used to confirm the production scope wiring for logic-E-2.
- `app/.../peers/ui/PeerCopiesOverview.kt` — pure builder; `mode()` degrades unknown text to
  LOCAL (Double-Enum tolerance); grouping/sort deterministic. Fine.

**Skipped (out of logic scope):** SQL DDL / migration correctness (topic: plan-and-api /
schema, verified green by `verifySqlDelightMigration`), Compose rendering details, test files,
Android manifest/strings/xml.

## Out-of-scope observations (for the consolidator)

- **convention:** `error::class.java.simpleName` used for failure reason strings in two places
  in `CatalogSyncEngine` (index vs. pull) — the E2 self-fix already noted this is below the
  3-use extraction threshold; not a logic issue.
- **plan-and-api:** the live seams `PeerIndexSource`/`CatalogSyncRunner`/`CatalogSubscriber`
  are consumed but production-null-wired (documented issues E2-1/E3-1, delegated to the
  credential-touching adapter owner). Not re-litigated here; noted so the plan-fidelity topic
  tracks that AC13's "live" half is intentionally deferred.
