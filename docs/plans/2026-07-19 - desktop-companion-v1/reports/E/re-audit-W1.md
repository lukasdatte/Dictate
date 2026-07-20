# Block E — Re-audit of Repair Wave 1

**Mode:** re-audit (verify repair wave) · **Block:** E (Peer-Katalog + Abo-Sync + Peer Explorer)
**Date:** 2026-07-20T13:30:00+02:00 · **Consolidator:** audit-consolidator
**Repair commit audited:** `df191be` — `[E] repair wave 1 (desktop-companion-v1)`
**Findings verified:** the 8 green findings from `reports/E/validated-findings.md` (F1–F8)
**Plan:** `desktop-companion-v1.md` §5 Block E · **Spec (SSoT):** `research/peer-katalog.md`

## Verdict

**Converged — all 8 findings resolved, no new problems introduced.** `findings = []`.

Verification was not prose-only: both directly-affected test classes were run green
(`:companion:test --tests *PeerExplorerViewModelTest --tests *TailscalePeerDiscoveryTest` →
BUILD SUCCESSFUL) and the changed modules compile
(`:companion:compileTestKotlin :shared:compileTestKotlin` → BUILD SUCCESSFUL).

---

## Per-finding resolution

### F1 · logic-E-1 (+E-T3) — TailscalePeerDiscovery timeout / leak / test coupling — RESOLVED

`execTailscaleStatus()` rewritten (`TailscalePeerDiscovery.kt:70-96`): stdout is now drained on a
daemon thread into an `AtomicReference`, `process.waitFor(TIMEOUT_SECONDS)` owns the deadline, and on
expiry `process.destroyForcibly()` kills the child before returning `null` (orphan-leak closed). stderr
is redirected to `ProcessBuilder.Redirect.DISCARD` (was `redirectErrorStream(false)`), so its pipe can
never fill and deadlock the child while only stdout is drained. On clean exit a bounded `drainer.join`
guarantees `output.get()` sees the complete stdout. The AC11 "timeout → empty list" promise now holds.
The kdoc documents the invariant ("the timeout must own the deadline, not `readText()`"). E-T3 folded
in: `realCliBinding_neverThrows_evenWhereTailscaleIsMissing` now carries `@Test(timeout = 15_000)` with
a comment — a wedged real CLI surfaces as a test failure, never an unbounded CI stall.

### F2 · logic-E-2 — PeerExplorerViewModel blocking seams on the UI scope — RESOLVED

`discoveryDispatcher` renamed to `ioDispatcher` (no leftover references anywhere) and all three
blocking, credential/network-touching seams now hop to it via `withContext(ioDispatcher)`:
`syncRunner.syncNow()` (`:110`), `subscriber.subscribe()` (`:132`), and `indexSource.entries()` inside
`load()` (`:152`). `load()` became `suspend`; all six call sites (init→refresh, selectPeer, syncNow,
unsubscribe, fork, subscribe) are inside `scope.launch`/suspend contexts — verified and compiles. Local
SQLite reads (`store.peers`/`copiesFrom`) correctly stay inline per the house pattern. The kdoc now
states the uniform "blocking → never UI thread" contract. Consistent with the E3-SF1 self-fix.

### F3 · E-T1 — Committed E.2 not self-contained (shared port files untracked) — RESOLVED

The four named files are now tracked at HEAD (`git ls-files` confirms):
`shared/.../sync/CatalogSubscriberStore.kt`, `shared/.../sync/NotificationPort.kt`,
`shared/.../client/WireResponse.kt`, `shared/src/test/.../sync/FakeCatalogSubscriberStore.kt`. The wave
also committed the surrounding shared protocol/client deps the engine + client need
(`DispatchClient.kt` mod, `DispatchError.kt`, `protocol/Dtos.kt`, `Endpoints.kt`, `ErrorEnvelope.kt`,
`Validations.kt`, tests `CatalogClientTest.kt`, `ValidationsTest.kt`, `SyncNotificationTest.kt`).
`git status shared/` now reports **zero** untracked or modified-uncommitted files, so a clean checkout
of HEAD == the working tree for `:shared`, and `:shared:compileTestKotlin` builds green. The module is
self-contained.

### F4 · E-T2 — Non-deterministic zip-filesystem flake in the full `:app` unit run — RESOLVED

`app/build.gradle` `testOptions.unitTests.all` changed `forkEvery = 80` → `forkEvery = 1` (one test
class per JVM fork), with an extensive rationale comment covering both drivers (Robolectric's per-JVM
`ZipFileSystemProvider` leak that made a later class throw `FileSystemAlreadyExistsException`, and
shadow-state/heap accumulation) and the wall-clock trade-off (with a documented `maxParallelForks`
escape hatch, deferred to avoid OOM on small runners). Hard process isolation is the guaranteed cure for
the order-dependent flake.

### F5 · convention-E-1 — Timestamp formatting divergence + duplication — RESOLVED

New single-source helper `companion/.../ui/TimeFormat.kt` (`internal fun Long.asTime()`, backed by
`java.time.DateTimeFormatter.ofPattern("dd.MM. HH:mm")`, kdoc stating the legacy `java.text`/`java.util`
pair is deliberately not used). `HistoryScreen.kt` dropped its private `asTime`/`TIME_FORMAT` and imports
the shared one; `OfferScreen.lastPickup` and `PeerListScreen.lastReached` now call `at.asTime()` instead
of `DateFormat.getDateTimeInstance(...).format(Date(...))`. One time style across the companion UI; no
duplication.

### F6 · convention-E-2 — Adjacent status-chip casing inconsistency — RESOLVED

`StatusLabel` (`PeerListScreen.kt:77-83`) changed to lower-case `"ok"/"stale"/"unreachable"` to match
`CopyStateLabel`'s existing lower-case pills; a comment records the choice (multi-word copy states like
"update available" rule out all-caps as the unified style). "stale" (peer) and "stale" (copy) now render
in one casing on `PeerDetailScreen`.

### F7 · E-T4 — FakePeerExplorerStore.copiesFrom ignores its peerId — RESOLVED

The fake now records each copy against its originating peer (`addCopy(peerId, copy)`; internal
`copies: List<Pair<String, SubscribedCopy>>`) and `copiesFrom(peerId)` filters by it, mirroring the SQL
store's `source_peer_id` filter. `unsubscribe`/`fork` updated to the new representation. A new test
`copies_areScopedToTheSelectedPeer` seeds copies for p1 and p2 and asserts only p1's row surfaces, so the
VM test now self-guards the peer-scoping wiring. Verified green.

### F8 · convention-E-3 — Import-block ordering drift in CompanionContainer.kt — RESOLVED

Imports reordered: `SqlDelightPeerExplorerStore` now follows `SqlDelightHistoryRepository` (D before P),
the `catalog.*` cluster and `platform.fallback.*` entries moved to package position. No duplicate imports
(`sort | uniq -d` empty) and the module compiles — the reorder introduced nothing and dropped nothing.

---

## New problems introduced by the wave

**None.** Checked for broken imports, behavior change beyond the fix, and convention violations:

- `load()` signature change to `suspend` — all call sites already inside coroutines; compiles + tests green.
- `discoveryDispatcher` → `ioDispatcher` rename — no dangling references (grep clean).
- F5 helper package placement (`...companion.ui`) does not collide with the removed `HistoryScreen`
  private copy; both `.ui.history` and `.ui.peers` import it cleanly.
- `forkEvery = 1` is strictly stronger isolation than the prior periodic recycle — no correctness risk.

## Note — pre-existing `:companion` clean-checkout gap (NOT re-opened, by design)

`CompanionContainer.kt` (committed) references `CatalogService` / `SqlDelightCatalogRepository`, which are
still **untracked** (the in-flight E2-1/E3-1 catalog workstream). This means `:companion` does not compile
from a clean HEAD checkout yet. This is **not** a regression and **not** F3's scope:

- The references existed in the parent commit (`df191be~1` already had 6 such references); the repair wave
  only reordered the import *line*, it did not introduce the dependency.
- F3 was explicitly scoped to `:shared` (the engine's port interfaces), which is now fully self-contained.
- This is exactly the delegated E2-1/E3-1 block-gate work that `validated-findings.md` §R2 routed out of the
  repair wave — it is being built now in the untracked working tree and resolves when those chunks commit.

Re-flagging it here would re-litigate the already-decided delegation, so it is recorded as context only,
not as a surviving finding.
