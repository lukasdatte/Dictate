# Repair Wave W1 — cluster repair-W1-1 (Block B, SecretStore)

**Date:** 2026-07-20T00:40:00+02:00
**Role:** repair-fix agent
**Cluster findings:** plan-api-B-1 (Important), logic-B-1, logic-B-2, T1, T3 (all Nice-to-have)

## Per-finding outcome

### plan-api-B-1 (Important) — FIXED
`AndroidKeystoreSecretStore.put` wrapped `kekProvider.encryptionKey()` only; the
`cipher.init`/`cipher.doFinal` calls ran unguarded, so a raw `GeneralSecurityException`
subclass (e.g. `KeyPermanentlyInvalidatedException`) escaped `put()`, bypassed
`SecretsMigration.run`'s `catch (SecretStoreException)` / `catch (IOException)`, and would
crash app start into a boot loop.

Fix: the encrypt now runs inside `Cipher.getInstance(TRANSFORMATION).run { init(...); iv + doFinal(value) }`
wrapped in `try { … } catch (e: GeneralSecurityException) { throw SecretStoreException.StorageIo("encrypt failed for ${ref.handle}", e) }`,
mirroring `FileAesGcmSecretStore.put`. The migration's existing `catch (SecretStoreException)`
now yields the intended clean abort (flag unset, plaintext intact, retry next start).
- File: `app/.../secrets/AndroidKeystoreSecretStore.kt` (put)
- Regression test added: `AndroidKeystoreSecretStoreTest.put_whenCipherFails_throwsStorageIo_notRawCryptoException`
  — an invalid 5-byte AES key makes `cipher.init` throw `InvalidKeyException`; asserts `StorageIo`.
  Fails on the unfixed code (raw `InvalidKeyException` escapes), passes on the fix. **Verified green.**

### logic-B-1 (Nice-to-have) — FIXED
Both companion stores wrote the secret blob with in-place `Files.write` (default
`TRUNCATE_EXISTING`); a crash mid-replace left a truncated blob that the next `get()` surfaces
as a spurious `DecryptionFailed`.

Fix: added a shared package-level helper `writeSecretBlobAtomically(target, bytes)` in
`SecretStoreModule.kt` (DRY — same home as the shared `secretFileName`): stage into a sibling
`.tmp`, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` with a plain-`REPLACE_EXISTING`
fallback for `AtomicMoveNotSupportedException`, and temp-cleanup on any failure. Both
`FileAesGcmSecretStore.put` and `DpapiSecretStore.put` now call it.
- Files: `SecretStoreModule.kt` (new helper + imports), `FileAesGcmSecretStore.kt` (caller),
  `DpapiSecretStore.kt` (caller).

### logic-B-2 (Nice-to-have) — FIXED
`FileAesGcmSecretStore.available` was `by lazy`, memoizing a transient `false` for the whole
long-lived companion/Hub process lifetime. Converted to a per-access `get()` (createDirectories
is idempotent, isWritable is a cheap stat) so recovery is automatic once the directory becomes
writable — matching the Android and DPAPI stores. Doc comment updated.
- File: `FileAesGcmSecretStore.kt`.

### T1 (Nice-to-have) — FIXED
Added `AndroidKeystoreSecretStoreTest.get_whenKekUnavailableForStoredBlob_throwsDecryptionFailed`:
puts a blob through a store with a working `InMemoryKekProvider`, then calls `get()` through a
store whose `KekProvider` throws — asserts `DecryptionFailed` (the §5.3 device-bound-key-lost
branch, previously uncovered). **Verified green.**
- File: `AndroidKeystoreSecretStoreTest.kt`.

### T3 (Nice-to-have) — FIXED
The uncommitted `writeOwnerOnly` atomic-CREATE_NEW hardening (0600-from-first-byte) had no
fail-on-old regression guard; `masterKeyFile_hasOwnerOnlyPermissions` asserts only the final
0600 state and passes for the reverted write-then-chmod too. Added
`FileAesGcmSecretStoreTest.writeOwnerOnly_refusesToClobberExistingFile_viaAtomicCreateNew`,
which asserts a `FileAlreadyExistsException` when the target already exists — a CREATE_NEW-only
behaviour that the old `Files.write` path would not exhibit (it would clobber silently). To make
the collision observable, `writeOwnerOnly` visibility changed `private` → `internal` (same-module
test access), documented in its KDoc. The hardening itself is now part of this file's committed
state (finding's core ask).
- Files: `FileAesGcmSecretStore.kt` (visibility), `FileAesGcmSecretStoreTest.kt` (regression test).

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Shared helper instead of per-class duplicate | logic-B-1 | atomic write lives once in `SecretStoreModule.kt` | DRY, matches existing `secretFileName` shared helper | none | yes |
| `writeOwnerOnly` `private` → `internal` | T3 | widened visibility for a deterministic collision regression test | the only fail-on-old observable is CREATE_NEW collision; final-perms test cannot catch a revert | none | yes |
| Added a regression test for plan-api-B-1 beyond the T-findings | plan-api-B-1 | new `put_whenCipherFails_…` test | bug fixes land with a fail-on-old regression test (test-first-patterns) | none | yes |

## Tests

- `./gradlew :app:testDebugUnitTest` — **PASS**. `AndroidKeystoreSecretStoreTest`: 13 tests, 0
  failures, incl. both new tests (T1 + plan-api-B-1 regression).
- `./gradlew :companion:test` — **could not execute**: `companion:compileTestKotlin` fails on
  `companion/.../data/ChordMigrationSeedTest.kt:53` (`No value passed for parameter
  'conversation_messagesAdapter' / 'processing_stepsAdapter'`). That file is **outside my
  cluster** and was left uncompilable by a **concurrent** in-progress edit to `CompanionDatabase`
  (new SqlDelight table adapters) by another agent — not my change. My companion **main** sources
  compile (`:companion:jar` built successfully) and the Kotlin frontend reports errors only in
  `ChordMigrationSeedTest.kt` (none in my `FileAesGcmSecretStore.kt` / `SecretStoreModule.kt` /
  `DpapiSecretStore.kt` / `FileAesGcmSecretStoreTest.kt`), so my companion additions compile
  cleanly. The companion test run should be re-triggered once the concurrent `CompanionDatabase`
  work settles.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/test/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStoreTest.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModule.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStore.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/DpapiSecretStore.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStoreTest.kt`

## Drift (files outside cluster scope)

none — every edited file is within the finding cluster's file set.

## Note for the commit agent

`FileAesGcmSecretStore.kt` and `AndroidKeystoreSecretStoreTest.kt` already carried **pre-existing
uncommitted** working-tree content (the B1-SF2 `writeOwnerOnly` hardening and a test-comment
update). Staging these files therefore also commits that prior hardening — which is exactly
finding T3's core requirement, so it is intended, not accidental drift. Do **not** stage the
other unrelated modified files (`CompanionDatabase.kt`, `CompanionSettings.kt`, `AppPaths.kt`,
`shared-ai/.../SecretStore.kt`, `CanonicalJsonTest.kt`) — those belong to other agents' clusters.
