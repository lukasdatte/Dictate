# Block B — Re-Audit of Repair Wave W1

**Mode:** re-audit · **Block:** B (SecretStore port + Android/Desktop impls + Android plaintext-key migration)
**Repair-wave commit:** `58a88b19373838ec435e254376b8bd89561ed84a` — `[B] repair wave 1`
**Timestamp:** 2026-07-20T00:40:00+02:00
**Grounding:** read `git show 58a88b1` in full, re-read the current committed source of every touched file
(`AndroidKeystoreSecretStore.kt`, `FileAesGcmSecretStore.kt`, `SecretStoreModule.kt`, `DpapiSecretStore.kt`,
`SecretStore.kt` + the three test files), and ran the Android secret-store suite.

## Verdict: CONVERGED

All 8 findings the wave was meant to fix are **resolved**. The wave introduced **no new problems**.
`findings = []`, `eliminated_count = 8`.

## Per-finding outcome

| Finding | Sev | Status | Evidence in committed HEAD |
|---|---|---|---|
| F1 `plan-api-B-1` (≡ `convention-B-1`) | Important | **RESOLVED** | `AndroidKeystoreSecretStore.put` L83-100 now wraps `cipher.init`/`doFinal` in `try { … } catch (e: GeneralSecurityException) { throw SecretStoreException.StorageIo(…) }`, mirroring `FileAesGcmSecretStore.put` and the class's own `get`. The migration's `catch (SecretStoreException)` now yields the clean abort instead of a boot loop. Regression test `put_whenCipherFails_throwsStorageIo_notRawCryptoException` (invalid 5-byte AES key → `InvalidKeyException` → asserts `StorageIo`). Android suite **BUILD SUCCESSFUL**. |
| F2 `logic-B-1` | Nice | **RESOLVED** | New shared helper `writeSecretBlobAtomically(target, bytes)` in `SecretStoreModule.kt` (tmp + `ATOMIC_MOVE`/`REPLACE_EXISTING`, `AtomicMoveNotSupportedException` fallback, temp-cleanup on failure). Both `FileAesGcmSecretStore.put` (L93) and `DpapiSecretStore.put` (L57) now call it in place of in-place `Files.write`. |
| F3 `logic-B-2` | Nice | **RESOLVED** | `FileAesGcmSecretStore.available` converted from `by lazy` to a per-access `get()` (L45-51); doc comment updated to explain the anti-latch rationale. Matches Android/DPAPI per-access semantics. |
| F4 `T1` | Nice | **RESOLVED** | `AndroidKeystoreSecretStoreTest.get_whenKekUnavailableForStoredBlob_throwsDecryptionFailed` — stores via working `InMemoryKekProvider`, reads via a throwing `KekProvider`, asserts `DecryptionFailed` (the §5.3 KEK-lost-after-restore `get` branch). In the Android suite that ran green. |
| F5 `T2` | Nice | **RESOLVED** | New `SecretStoreModuleTest.detect_onNonWindowsHost_returnsFileAesGcmFallback` pins the non-Windows selector branch. (Correct by inspection; execution currently blocked only by the concurrent compile issue below, not by this test.) |
| F6 `T3` | Nice | **RESOLVED** | The `writeOwnerOnly` atomic `CREATE_NEW` + `asFileAttribute(0600)` hardening is now **committed** (was uncommitted at audit time). Visibility `private → internal` for test access; regression test `writeOwnerOnly_refusesToClobberExistingFile_viaAtomicCreateNew` asserts `FileAlreadyExistsException` on collision — a `CREATE_NEW`-only behaviour the reverted write-then-chmod would not exhibit. |
| F7 `convention-B-2` | Nice | **RESOLVED** | Free function `detectSecretStore(configDir)` wrapped into `object SecretStoreModule { fun detect(configDir): SecretStore }`, an exact parallel of `PlatformModule.detect()` (ADR-0018); KDoc now accurate; stale `detectSecretStore prevents` comment in `FileAesGcmSecretStore` updated to `SecretStoreModule.detect prevents`. |
| F8 `convention-B-3` | Nice | **RESOLVED** | `SecretStore.kt:73` German `Fehler-Semantik-Träger:` → English `Error-semantics carrier:`. Grep confirms no German fragment remains in the block's main source. |

## Newly introduced problems from the wave

None. Reviewed the full diff for broken imports, behaviour changes beyond the fix, and convention violations:

- The new `object SecretStoreModule` has no production callers yet (container wiring lands later), so the
  rename to `detect(...)` breaks no call sites; the one stale prose reference was updated in the same wave.
- `writeSecretBlobAtomically` preserves the pre-existing permission posture of secret blobs (they were, and
  remain, umask-default — they are AES-GCM ciphertext; only `master.key` is `0600`, via the separate
  `writeOwnerOnly` path, unchanged). No permission regression.
- `writeOwnerOnly` `private → internal` is a deliberate, documented test-visibility widening — same module,
  no API surface leak.

## Environment caveat (not a wave finding)

`./gradlew :companion:test` currently fails at **compile time** on
`companion/src/test/kotlin/.../data/ChordMigrationSeedTest.kt:53` (`No value passed for parameter
'processing_stepsAdapter'`). Verified this is **independent of the repair wave**: the wave commit
(`58a88b1`) touches neither `CompanionDatabase.kt` nor `ChordMigrationSeedTest.kt`; the breakage comes from
a **concurrent, still-uncommitted** working-tree edit to `CompanionDatabase.kt` by another agent (adding the
`processing_steps` SqlDelight adapter for the D1b desktop pipeline). The wave's own secrets tests are correct
by inspection, and `repair-W1-2` recorded `:companion:test` BUILD SUCCESSFUL during its run before that
concurrent edit landed. This is a transient multi-agent state, not a defect in Block B secrets. The
`:app` secret-store suite (which the Important finding lives in) compiles and passes cleanly.

## Cross-cut notes

- The `writeSecretBlobAtomically` (F2) and `writeOwnerOnly`/master-key atomic-create (F6) hardenings now
  form a consistent atomic-write posture across both file-backed companion stores — closing the
  inconsistency the initial audit flagged between the master-key path and the secret-blob path.
