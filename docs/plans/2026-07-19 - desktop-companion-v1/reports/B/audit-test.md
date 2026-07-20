# Block B — AUDIT-TEST (topic: test)

**Date:** 2026-07-20
**Scope:** Block B test diff `c46cfe8..HEAD` (SecretStore port + Android/Desktop impls + Android
plaintext-key migration), filtered to test files + helpers.
**Verdict:** Tests are green, well-named, and behavior-focused. No cross-chunk regressions. Three
Nice-to-have coverage gaps (all on documented-but-low-risk branches). **I fixed nothing.**

## Dynamic run (forced `--rerun-tasks`)

`./gradlew :shared-ai:test :app:testDebugUnitTest :companion:test --rerun-tasks` → **BUILD
SUCCESSFUL** (1m36s, all test tasks actually re-executed, not up-to-date). The full app + companion
+ shared-ai suites ran — not just the block's classes — so cross-block changes were exercised too.

Per-class counts (from `build/test-results/**/*.xml`):

| Test class | tests | pass | skipped | fail/err | Notes |
|---|---|---|---|---|---|
| `SecretRefTest` (shared-ai) | 5 | 5 | 0 | 0 | |
| `AndroidKeystoreSecretStoreTest` (app, Robolectric) | 11 | 11 | 0 | 0 | |
| `SecretsMigrationTest` (app, Robolectric) | 9 | 9 | 0 | 0 | +1 vs chunk report (B2-selffix regression test) |
| `NoLegacyKeyReadTest` (app) | 2 | 1 | 1 | 0 | 1 pending-by-design (`@Ignore`, re-pointed in C2/C3); scanner self-check runs green |
| `DpapiSecretStoreTest` (companion) | 3 | 1 | 2 | 0 | 2 Windows-gated via `assumeTrue` (`pending: block-B-windows-abnahme`) |
| `FileAesGcmSecretStoreTest` (companion) | 10 | 10 | 0 | 0 | real crypto on this Linux VM |
| **Block B total** | **40** | **37** | **3** | **0** | 3 skips are all deliberate + documented |

**Cross-chunk regressions:** none. Every previously-green test still green after the block's
changes, including the app/companion suites touched by neighboring blocks.

## Static quality — good

- **Names** describe behavior + condition (`putThenGet_returnsByteIdenticalValue`,
  `get_withForeignKek_throwsDecryptionFailed_notEmpty`, `putFailureMidway_abortsWithFlagUnset_backupHoldsAllPlaintext`). Behavior-focused, not method-focused.
- **Assertions concrete** — `assertArrayEquals` on raw bytes (incl. non-UTF-8 `byteArrayOf(0,-1,…)`
  and non-ASCII `sk-café✓-ß`), typed `assertThrows(SecretStoreException.DecryptionFailed::class.java)`.
  No snapshots. The §11 IV-uniqueness footgun is pinned (`twoPuts_ofSameValue_useDistinctIVs`) in
  both the Android and file stores.
- **Mock/helper convention** followed. `InMemoryKekProvider` (the §5.4 crypto seam) is a shared
  helper in the `secrets` test package, reused by both `AndroidKeystoreSecretStoreTest` and
  `SecretsMigrationTest` — no duplication. `FakeSharedPreferences` comes from the established
  `testutil/` location. `FakeSecretStore` is a private single-file fake in `SecretsMigrationTest`
  (drives the abort paths); single-use, correctly local — no premature helper extraction.
- **Pending/skip discipline** exemplary: `assumeTrue(Platform.isWindows())` for DPAPI and
  `@Ignore("pending: … C2/C3 …")` for `NoLegacyKeyReadTest` both carry a greppable `pending:` reason
  and a tracking pointer (spec §6.3 / §7.2, §2.6). `NoLegacyKeyReadTest` additionally ships a
  runnable scanner self-check so the pending assertion is meaningful the moment `@Ignore` is removed.
- **Doc-trail intact.** Block B production files are all new (greenfield, 789 committed insertions);
  the one code-bug fix during testing — `exportBackupOnce` rollback-write hardening (temp-file +
  atomic rename, moved inside the `try`, `catch(IOException)`) — is documented in `B2-selffix.md`
  and guarded by `backupWriteFailure_abortsCleanly_noPlaintextDeleted_noCrash`. No undocumented
  production change in the committed diff.

## Findings (all Nice-to-have)

### T1 — `AndroidKeystoreSecretStore.get`: "blob present but KEK unavailable" branch untested
`AndroidKeystoreSecretStore.kt:48-54` maps *KEK-unavailable-at-read-time* (the §5.3 "device-bound key
lost after a restore" scenario the comment explicitly calls out) to `DecryptionFailed`. No test hits
this branch: `get_withForeignKek…` supplies a *working* (different) key so it exercises the GCM-tag
path (lines 65-73), and `put_whenKekUnavailable…` never stores a blob to later read. The
`DecryptionFailed` *outcome* is asserted elsewhere, so risk is low — but this specific, security-
relevant branch is uncovered and is cheap to pin (pre-seed a blob with a working KEK, then `get`
through a store whose `KekProvider` throws).

### T2 — `detectSecretStore()` has no test
`SecretStoreModule.kt:20-21` (the platform backend selector, mirrors ADR-0018 `PlatformModule`) is
untested. The Linux branch (`detectSecretStore(dir) is FileAesGcmSecretStore`) is trivially
assertable on CI and would pin the platform-selection contract; the Windows branch stays under the
`block-B-windows-abnahme` acceptance. `secretFileName()` in the same file is covered indirectly via
`FileAesGcmSecretStoreTest.secretFilePath()`.

### T3 — uncommitted `FileAesGcmSecretStore` 0600-hardening lacks a guarding test
The working tree carries an **uncommitted** hardening of `writeOwnerOnly` (atomic `CREATE_NEW` +
`asFileAttribute(0600)` instead of write-then-`setPosixFilePermissions`) that closes a
world-readable master-key window. The suite is green *with* it applied, but `masterKeyFile_hasOwnerOnlyPermissions`
only asserts the *final* `0600` state — it passes equally for the old write-then-chmod code, so a
revert would not be caught. If this change is meant to land, it wants a regression test that fails on
the old approach (e.g. assert `CREATE_NEW` collision / no widened intermediate perms). Flagged also
because it is currently uncommitted — the responsible agent should commit it with its own coverage.

## Coverage notes (not findings — accepted risk)
- **DPAPI** (`DpapiSecretStore`) has effectively zero executed coverage on Linux CI beyond
  `get_onAbsentRef` — put/get/delete round-trips are all Windows-gated. This is by design and owned
  by `pending: block-B-windows-abnahme` (spec §6.3). Residual risk is the DPAPI acceptance run's job.
- Defensive `StorageIo` branches in `FileAesGcmSecretStore` / `DpapiSecretStore` (read/write/delete
  IO failures, encrypt failure) are unreachable without fault injection — acceptable defensive-unreachable.
- `coverage_command: none` — no branch-coverage tool configured; the gaps above were found by
  reading production branches against the test diff, not a coverage report.
