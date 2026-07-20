# Block E — AUDIT-TEST (topic: test)

**Date:** 2026-07-20T13:30:00+02:00
**Block:** E — Peer-Katalog (E1 protocol/server, E2 sync-engine/notification/Android-consumer, E3 discovery/headless/Explorer UI)
**Diff base:** c46cfe8 (LAST_VERIFY_COMMIT) .. HEAD
**Verdict:** Block-E tests are deterministically green, high quality, and cover the ACs well. Two Important findings: (1) the committed sync tests are **not reproducible from HEAD** — they compile only against uncommitted working-tree files; (2) the full `:app` unit run showed a **non-deterministic zip-filesystem flake**. Two Nice-to-have polish items.

---

## Step 0 — conventions applied

- `CONVENTIONS`: `test_command` per module, `coverage_command: none`, `schema_verify_companion: verifySqlDelightMigration`, jvm test helpers under `companion/.../fakes/` and `app/.../testutil/`.
- Kotlin/Compose-free ViewModel tests follow the documented `HistoryViewModelTest` pattern (`Dispatchers.Unconfined` so `launch` runs inline). PeerExplorer/Offer VM tests conform.
- No coverage tooling configured → no branch-coverage table possible; coverage assessed qualitatively from the diff.

## Step 2 — dynamic run

| Suite | Result |
|---|---|
| `:shared:test` (incl. `CatalogSyncEngineTest` 13) | **PASS** (`--rerun-tasks`, 15s) |
| `:companion:test` | **PASS** (`--rerun-tasks`) |
| `:companion:verifySqlDelightMigration` | **PASS** |
| `:app:testDebugUnitTest` | **FLAKY** — 2515 tests, 1 failed on first run, **0 failed** on `--rerun-tasks` and in isolation |

Block-E test classes, all green deterministically:

| Class | Tests |
|---|---|
| `CatalogSyncEngineTest` (shared) | 13 |
| `PeerExplorerViewModelTest` | 13 |
| `TailscalePeerDiscoveryTest` | 7 |
| `SqlDelightPeerExplorerStoreTest` | 7 |
| `OfferViewModelTest` | 3 |
| `HeadlessBootTest` | 1 |
| `ChordMigrationSeedTest` (modified) | 1 |
| `PeerCopiesOverviewTest` (app) | 4 |
| `PeerExplorerActivityTest` (app, Robolectric) | 2 |

Coverage of the §8.1 state matrix (CURRENT/UPDATE_AVAILABLE/FORKED/STALE/SOURCE_REMOVED + transient/no-index), the AC6–AC10 sync acceptance set (idempotency, update, fork-protection, staleness-not-error, verify-before-write, credential fingerprint watermark, mixed partial run), AC11 discovery parsing/fallbacks, AC12 headless boot, and the fork/unsubscribe atomicity on real SQLite is thorough.

---

## Findings

### E-T1 (Important) — Committed block-E sync tests are not reproducible from HEAD
`CatalogSyncEngine.kt` was added in the committed **d057bd0 `[E.2]`**, and `CatalogSyncEngineTest.kt` is committed, but their compile-critical dependencies are **untracked** (working-tree only):
- `shared/src/main/.../sync/CatalogSubscriberStore.kt` (the `store` interface)
- `shared/src/main/.../sync/NotificationPort.kt` (the `notifier` interface)
- `shared/src/main/.../client/WireResponse.kt`
- `shared/src/test/.../sync/FakeCatalogSubscriberStore.kt` (+ `RecordingNotificationPort`)

`git grep CatalogSubscriberStore d057bd0 -- shared/src/main` finds the symbol referenced only by the committed engine, while the interface file itself was never committed. A clean checkout at HEAD would **fail to compile `:shared` and `:shared:test`** — the green suite observed above depends entirely on the dirty working tree. (The same pattern extends to a large untracked catalog workstream — `CatalogClientTest`, `SyncNotificationTest`, `CatalogService(Test)`, `CatalogE2ETest`, `CatalogSyncScheduler(Test)`, `AndroidNotificationPort(Test)`, `4.sqm` — likely an in-flight E4/E5, but the **E.2 commit itself is already non-self-contained**.)
**Fix:** commit the sync interfaces + test fakes together with (or before) the engine so each `[E.x]` commit compiles standalone.

### E-T2 (Important) — Non-deterministic zip-filesystem flake in the full `:app` unit run
First `:app:testDebugUnitTest` run: `AndroidKeystoreSecretStoreTest.put_whenKekUnavailable_throwsUnavailable` failed with `java.nio.file.FileSystemAlreadyExistsException` at `ZipFileSystemProvider.java:104` (an infrastructure error, not an assertion). It **passed in isolation and on a full `--rerun-tasks`** → order-dependent test pollution: a Robolectric/JVM `FileSystems.newFileSystem` on a jar URI left open by an earlier test in the same fork (no `forkEvery`). The failing class is Block B, not E, but block E adds a new Robolectric test (`PeerExplorerActivityTest`) to the same module, raising collision odds. Not a hard regression (block-E tests pass deterministically), but a real CI-reliability risk.
**Fix:** set `testOptions.unitTests` `forkEvery`/max-parallel-forks isolation for the app module, or ensure Robolectric-adjacent tests close any `FileSystem` they open.

### E-T3 (Nice-to-have) — `TailscalePeerDiscoveryTest.realCliBinding_neverThrows` execs the real `tailscale` binary
The test invokes the production default which shells out to the actual CLI. The intent (contract smoke: "candidates or empty, never throw") is legitimate, but it couples the suite to the build machine's environment; a hanging or interactive `tailscale` could stall CI. Consider a process timeout or gating this one case behind an opt-in.

### E-T4 (Nice-to-have) — `FakePeerExplorerStore.copiesFrom(peerId)` ignores its `peerId` argument
The VM-test fake returns *all* subscriptions regardless of the requested peer. A VM defect passing the wrong `peerId` to `copiesFrom` would not be caught here; real per-peer filtering is only exercised in `SqlDelightPeerExplorerStoreTest`. Low fidelity, acceptable given the SQL-side coverage, but honoring the arg would make the VM test self-guarding.

---

## Static-quality notes (no finding)
- Test names describe behavior + condition throughout; assertions are concrete (no brittle snapshots).
- No harmful helper duplication: the `peer(...)`/`entry(...)`/`copy(...)`/`subscription(...)` builders across the VM, store, and sync tests build **different** types (`PeerRecord` vs `CatalogEntry` vs `SubscribedCopy` vs `CatalogSubscriptionRef`) — not consolidation candidates.
- Doc-trail intact: the E2 self-fix (4d45e92, transient mid-pull branch) is documented both in `reports/E/E2-selffix.md` and in the test's inline comment; E3 self-fix (4952233) is a wiring fix with no undocumented production-code changes in the test diff.
- `CatalogSyncEngineTest` drives through the **real** `CatalogClient` + codec + both hash checks over a `FakeTransport` — only socket and store are faked. Strong acceptance-level design.
