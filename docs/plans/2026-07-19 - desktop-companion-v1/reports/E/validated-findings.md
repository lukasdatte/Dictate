# Block E — Consolidated & Validated Findings

**Mode:** initial (consolidate 4 parallel audits) · **Block:** E (Peer-Katalog + Abo-Sync + Peer Explorer)
**Date:** 2026-07-20T13:30:00+02:00 · **Consolidator:** audit-consolidator
**Sources:** `reports/E/audit-plan-and-api.md`, `audit-convention.md`, `audit-logic.md`, `audit-test.md`
**Plan:** `desktop-companion-v1.md` §5 Block E · **Spec (SSoT):** `research/peer-katalog.md`
**Range audited:** `c46cfe8..HEAD` (`4952233`)

## Summary

14 raw findings in. After validation against the code: **8 survive as repair-wave findings**
(all confirmed real, all `green` — clear fixes), **3 deduped/merged** (E-T3 folded into logic-E-1),
**3 removed from the fix list** as *valid-but-already-tracked-delegated* work (the plan-and-api trio —
these are **not false positives**; they are honest, already-documented deferrals to the delegated
E2-1/E3-1 chunks and belong to the block-gate decision, not to a repair fixer).

No finding was a genuine false positive. No `yellow` findings — every surviving item has a clear,
small/medium-scope fix.

## Validation method

Every finding was checked against the actual code at HEAD (not just the audit prose):

- git tree state (`git status`, `git ls-files`, commit log) — confirmed E-T1 and the delegation framing.
- Read in full: `TailscalePeerDiscovery.kt`, `PeerExplorerViewModel.kt`, `PeersScreen.kt`,
  `PeerListScreen.kt`, `OfferScreen.kt`, `PeerDetailScreen.kt`, `CompanionContainer.kt`
  (`production()` + import block), `CatalogSubscriberStore.kt` (`markSourceRemoved` kdoc),
  `Companion.sq` (subscriptions CHECK), `HistoryScreen.kt` (`asTime()` precedent).
- Cross-checked the delegated-issue trail: `E2-impl.md` (issue E2-1), `E3-impl.md` (issues E3-1),
  `chunks.json` (Block E = chunks E1/E2/E3 only; E2-1/E3-1 are `delegated`+`blocks-following` issues).

---

## Surviving findings (green — go to the repair wave)

### F1 · logic-E-1 (+E-T3) — TailscalePeerDiscovery: 5 s timeout defeated + child-process leak + real-binary test coupling · Important · CONFIRMED

**Files:** `companion/.../catalog/discovery/TailscalePeerDiscovery.kt:60-68`,
`companion/src/test/.../catalog/TailscalePeerDiscoveryTest.kt` (the `realCliBinding_neverThrows` case)

Verified at lines 64-65: `readText()` runs **before** `waitFor(5s)`. `readText()` blocks until the
child closes stdout (EOF), so a hung `tailscale` process blocks the discovery coroutine forever and
the 5 s guard is never reached — the class kdoc's / AC11's "timeout → empty list" guarantee does not
hold. On the `waitFor == false` branch the child is returned-from without `destroyForcibly()`, leaking
an orphan. Production runs this on `Dispatchers.IO` (`PeerExplorerViewModel.discoveryDispatcher`,
line 91), so a hang ties up / can starve IO worker threads. `redirectErrorStream(false)` also leaves
stderr un-drained (pipe-buffer deadlock risk).

**Merged with E-T3** (Nice-to-have): `TailscalePeerDiscoveryTest.realCliBinding_neverThrows` execs the
real `tailscale` binary via the production default — same root cause (unbounded real-CLI exec). A
robust production timeout largely defends the test too; the fixer should also add a process timeout or
gate the real-binary case behind an opt-in so a wedged/interactive CLI cannot stall CI.

**Fix:** drain stdout on a separate thread (or bounded read), `waitFor(TIMEOUT)` first, and on timeout
`process.destroyForcibly()` before returning `null`; concurrent stdout draining also avoids the
stdout/stderr deadlock. Then confirm/guard the real-binary test.

### F2 · logic-E-2 — PeerExplorerViewModel: blocking network seams run on the injected UI scope · Important · CONFIRMED

**Files:** `companion/.../ui/peers/PeerExplorerViewModel.kt:106-109` (`syncNow`), `127-131`
(`subscribe`), `141-157` (`load`, incl. `indexSource.entries()` at 146)

Verified: `scope` is `rememberCoroutineScope()` (Main) in production (`PeersScreen.kt:41`), and three
blocking seams run inside `scope.launch { … }` with **no** `withContext` hop:
`indexSource.entries(peerId)` in `load()` (called from init/refresh/selectPeer/unsubscribe/fork/syncNow),
`syncRunner.syncNow()`, and `subscriber.subscribe()`. This is inconsistent with the E3-SF1 self-fix,
which wrapped the equally-blocking `discovery.discover()` in `withContext(discoveryDispatcher = IO)`
(line 135) precisely because that contract forbids the UI thread — the index/sync/subscribe seams carry
the identical "blocking / never UI thread" contract but were left on Main.

**Latent today** (confirmed: `PeersScreen.kt:45` null-wires `indexSource` to `PeerIndexSource { null }`;
`syncRunner`/`subscriber` are null — lines 47-48), so no freeze yet. It will freeze the companion window
the moment E2-1 supplies the real adapters (open a peer detail pane → `load()`→`entries()`; tap "Sync now").

**Fix:** apply the E3-SF1 pattern uniformly — hop `indexSource.entries()`, `syncRunner.syncNow()`,
`subscriber.subscribe()` to an injected IO dispatcher via `withContext(ioDispatcher){}`. Local SQLite
reads (`store.peers()`/`copiesFrom()`) may stay on the UI scope per the accepted `HistoryViewModel` house pattern.

### F3 · E-T1 — Committed E.2 is not self-contained: engine's port interfaces are untracked · Important · CONFIRMED

**Files:** `shared/.../sync/CatalogSubscriberStore.kt`, `shared/.../sync/NotificationPort.kt`,
`shared/.../client/WireResponse.kt`, `shared/src/test/.../sync/FakeCatalogSubscriberStore.kt`

Verified via git: `CatalogSyncEngine.kt` is **tracked** (committed in `d057bd0 [E.2]`) and references
`CatalogSubscriberStore`/`NotificationPort`/`WireResponse` (lines 27/34/38-39), but all three port
files **and** the test fake are **untracked** (`git ls-files` returns nothing for them; they show `??`).
A clean checkout of HEAD would fail to compile `:shared` and `:shared:test` — the green suite observed
in the audit depends entirely on the dirty working tree. The port interfaces are what the engine
*needs to compile*; they belong in the E.2 commit (distinct from the delegated E2-1 *adapters*, which
legitimately come later).

Note: these files sit inside a larger untracked catalog workstream (the in-flight E2-1/E3-1 work —
`CatalogSyncScheduler.kt`, `CatalogService.kt`, `CatalogRoutes.kt`, `SqlDelightCatalogRepository.kt`,
`AndroidNotificationPort.kt`, `CatalogSyncWorker.kt`, `CatalogE2ETest.kt`, `4.sqm`, …). The fix here is
narrow: commit the **port interfaces + test fake** the already-committed engine depends on, so each
`[E.x]` commit compiles standalone. Verify with a clean-checkout compile of `:shared:test`.

### F4 · E-T2 — Non-deterministic zip-filesystem flake in the full `:app` unit run · Important · CONFIRMED (order-dependent)

**Files:** `app/build.gradle` (add test isolation), `app/src/test/java/.../AndroidKeystoreSecretStoreTest.kt`
(the observed victim — Block B, not E)

`:app:testDebugUnitTest` first run: `AndroidKeystoreSecretStoreTest.put_whenKekUnavailable_throwsUnavailable`
failed with `FileSystemAlreadyExistsException` at `ZipFileSystemProvider.java:104` (JVM infra error, not
an assertion); passed in isolation and on `--rerun-tasks` → order-dependent test pollution (a
Robolectric/JVM `FileSystems.newFileSystem` on a jar URI left open in the same fork; no `forkEvery`).
**Not caused by Block E** and non-deterministic, but Block E adds a new Robolectric test
(`PeerExplorerActivityTest`) to the same module, raising collision odds — a real CI-reliability risk.

**Fix:** add app-module test isolation (`testOptions.unitTests` `forkEvery` / `maxParallelForks`) or
ensure Robolectric-adjacent tests close any `FileSystem` they open; re-run `:app:testDebugUnitTest`
repeatedly to confirm stability. (Lower priority than F1–F3 as a Block-E concern — it is a pre-existing
module-wide flake, not an E code defect — but cheap and worth closing while the module is being touched.)

### F5 · convention-E-1 — Timestamp formatting diverges from the companion pattern and is duplicated · Nice-to-have · CONFIRMED

**Files:** `companion/.../ui/peers/PeerListScreen.kt:87-90` (`lastReached`),
`companion/.../ui/peers/OfferScreen.kt:66-70` (`lastPickup`)

Verified: both re-roll `DateFormat.getDateTimeInstance(SHORT, SHORT).format(Date(at))` (legacy
`java.text`/`java.util` API), identical copy across the two files, diverging from the established
companion helper `Long.asTime()` (`HistoryScreen.kt:403`, `DateTimeFormatter.ofPattern("dd.MM. HH:mm")`,
line 406). Two timestamp styles now coexist in the companion UI package and Peers vs History print
different formats.

**Fix:** reuse (or lift into a shared companion UI helper) the existing `asTime()` extension so all
companion screens format times one way.

### F6 · convention-E-2 — Adjacent status-chip labels use inconsistent casing · Nice-to-have · CONFIRMED

**Files:** `companion/.../ui/peers/PeerListScreen.kt:77-85` (`StatusLabel`),
`companion/.../ui/peers/PeerDetailScreen.kt:88-98` (`CopyStateLabel`)

Verified: same idiom (coloured `Text` over an exhaustive enum `when`), rendered side-by-side on
`PeerDetailScreen`, but `StatusLabel` uses upper-case `"OK"/"STALE"/"UNREACHABLE"` (lines 80-82) while
`CopyStateLabel` uses lower-case `"current"/"update available"/"forked"/"stale"/"source removed"`
(lines 91-95). `"STALE"` (peer) and `"stale"` (copy) appear on the same screen in different casing.

**Fix:** pick one casing/spacing rule for the peer/copy status pills and apply it to both.

### F7 · E-T4 — FakePeerExplorerStore.copiesFrom(peerId) ignores its peerId argument · Nice-to-have · CONFIRMED

**File:** `companion/src/test/.../ui/PeerExplorerViewModelTest.kt` (`FakePeerExplorerStore`)

The VM-test fake returns all subscriptions regardless of the requested peer, so a VM defect passing the
wrong `peerId` to `copiesFrom` would not be caught (real per-peer filtering is only exercised by
`SqlDelightPeerExplorerStoreTest`). Acceptable given SQL-side coverage, but low fidelity.

**Fix:** honor `peerId` in the fake (filter subscriptions by source peer) so the VM test self-guards the
peer-scoping wiring.

### F8 · convention-E-3 — Import-block ordering drift in CompanionContainer.kt · Nice-to-have (low confidence) · CONFIRMED

**File:** `companion/.../CompanionContainer.kt:15-28` (import block)

Verified: `SqlDelightPeerExplorerStore` (line 18) is imported before `SqlDelightDeviceRepository`
(line 19) — P before D — and a `catalog.*` cluster (23-28) sits mid-`data`/`domain` rather than in
package order. **Lowest priority of the set:** the repo has no linter/formatter (CLAUDE.md), the order
is unenforced, and an IDE optimize-imports pass fixes it mechanically. Recorded for completeness.

**Fix:** run an IDE optimize-imports pass on `CompanionContainer.kt`.

---

## Removed from the fix list — valid-but-already-tracked-delegated (NOT false positives)

These three plan-and-api findings are **real and confirmed**, but they re-state work that the Block E
implementers already raised as **delegated issues E2-1 / E3-1** (`delegated` + `blocks-following` in
`E2-impl.md`/`E3-impl.md`). They are the **block-gate acceptance status** — input to the orchestrator's
"does Block E close, or does it gate on E2-1/E3-1?" decision — not repair-wave code fixes. Routing them
to a repair fixer would either (a) mis-scope the entire credential-touching subscriber runtime (a
deliberately separated, in-flight chunk) into a fixer, or (b) waste a fixer that finds nothing to do.
They are counted in `eliminated_count` **only** because the output schema has no third bucket; they are
explicitly *not* false positives.

The delegated E2-1/E3-1 work is precisely the large **untracked** catalog workstream currently in the
working tree (`SqlDelightCatalogRepository.kt`, `CatalogSyncScheduler.kt`, `CatalogService.kt`,
`CatalogRoutes.kt`, `AndroidNotificationPort.kt`, `CatalogSyncWorker.kt`, `CatalogE2ETest.kt`, `4.sqm`, …)
— i.e. it is being built now.

### R1 · plan-and-api-E-1 — AC10 (two-peer E2E) unmet, mapped as "covered" · Important · CONFIRMED, delegated to E2-1

`E2-impl.md` maps "AC6–AC10" to `CatalogSyncEngineTest` (FakeTransport unit tests). AC6–AC9 are
legitimately engine-unit-testable and covered; **AC10** — two real `embeddedServer(CIO, port=0)`
instances over the real `CatalogClient` with credential→SecretStore isolation + a provider access-log
row (spec §2.10/§11) — is **not** an engine-unit test and does not exist as committed. It is explicitly
delegated inside **E2-1** (whose deliverables include "the two-peer `CatalogE2ETest` (§11)"). Honest
deferral — no stub fakes a passing AC. **Block-gate rule:** do not treat AC6–AC10 as satisfied until
E2-1's two-peer `CatalogE2ETest` lands.

### R2 · plan-and-api-E-2 — Production subscriber runtime is unwired · Important · CONFIRMED, delegated to E2-1/E3-1

`CompanionContainer.production()` (verified lines 242-267) leaves `peerIndexSource` / `catalogSyncRunner`
/ `catalogSubscriber` at their `null` defaults, never constructs/starts a `CatalogSyncScheduler`, and no
`SqlDelightCatalogSubscriberStore` / Android `CatalogSubscriberStore` / `CatalogSyncWorker` wiring /
add-peer pair-redemption exists. On a real companion the Peer Explorer cannot fetch an index, sync-now,
subscribe, or add a peer, and nothing inserts a `peers`/`subscriptions` row — the §6/§8.3/§9.1 consumer
runtime is inert. Honestly documented (E2-1, E3-1) with honest UI degradation (`PeersScreen.kt:45`
`PeerIndexSource { null }`; `canSubscribe = false`; PeerDetail "sync adapter not wired yet"). The block
ships offer-side (E1) + engine core (E2a) + UI shells (E3). **Block-gate rule:** the consumer feature is
non-functional in production until E2-1/E3-1 land.

### R3 · plan-and-api-E-3 — `markSourceRemoved` has no schema home; SOURCE_REMOVED not persisted · Nice-to-have · PLAUSIBLE, fold into E2-1

`CatalogSubscriberStore.markSourceRemoved` (kdoc lines 74-79) promises to "mark the subscription
SOURCE_REMOVED", but no such state exists: `SubscriptionMode = {LOCAL,SUBSCRIBE,ONE_SHOT}` and
`subscriptions.mode CHECK IN ('SUBSCRIBE','ONE_SHOT')` (Companion.sq:360) carry no SOURCE_REMOVED value,
and there is no `source_removed` column. The Explorer derives `CopyState.SOURCE_REMOVED` purely live
(entry absent from the fetched index, `PeerExplorerViewModel.kt:169`) and falls back to `CURRENT` when
no index is in hand (line 168) — so if the index is later unfetchable, the Explorer can silently
disagree with the removal notification the user saw. This is a **contract-clarity refinement for the
E2-1 adapter** (which owns the not-yet-written `markSourceRemoved` impl): the E2-1 owner should either
add a persisted marker (`source_removed_at` read by a `copiesFrom` derivation) **or** redefine the port
method to "record the attempt (`last_checked_at`), keep the copy — SOURCE_REMOVED is index-derived only".
Folded into E2-1 rather than fixed piecemeal now, to avoid a repair fixer pre-empting E2-1's design.

---

## Cross-cut patterns (for file-clustering)

- **`companion/.../ui/peers/` cluster** — F5 (PeerListScreen + OfferScreen), F6 (PeerListScreen +
  PeerDetailScreen), F2 (PeerExplorerViewModel). PeerListScreen.kt appears in both F5 and F6; a single
  fixer over the `ui/peers` package can address F2/F5/F6 coherently.
- **TailscalePeerDiscovery cluster** — F1 spans `TailscalePeerDiscovery.kt` (main) + its test; one fixer.
- **Commit/test-infra** — F3 (git commit scoping of `:shared` port files) and F4 (`:app` test isolation)
  are not source-logic edits; F3 is a commit-scoping action (route through the commit-agent path), F4 is
  a `build.gradle`/testOptions change.
- **Confirmed-good, no finding** (recorded so re-audit doesn't re-open): `CatalogSyncEngine` core
  (two-hash verify-before-write, partial-run root-hash hold, EntityGone/transient branches — all tested);
  `CatalogClient` 404-vs-EntityGone split; enum reuse across E1/E2/E3 (no parallel `*Wire` copies);
  fork = mode-flip-to-LOCAL + subscription-row-delete in one transaction; `OfferViewModel` newest-pickup
  ordering. No stubs / TODO / throw-not-implemented anywhere in the block diff.
