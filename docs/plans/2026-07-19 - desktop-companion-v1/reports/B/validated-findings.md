# Block B — Audit Consolidation (validated findings)

**Mode:** initial · **Block:** B (SecretStore port + Android/Desktop impls + Android plaintext-key migration)
**Timestamp:** 2026-07-20T00:40:00+02:00
**Consolidator grounding:** read all four audit reports in full + validated every finding against the
actual source (`AndroidKeystoreSecretStore.kt`, `SecretsMigration.kt`, `FileAesGcmSecretStore.kt`,
`DpapiSecretStore.kt`, `SecretStoreModule.kt`, `SecretStore.kt`) and `git status`/`git diff HEAD`.

## Summary

- **Raw findings in:** 10 (across 4 audits)
- **After dedup:** 8 validated findings — **1 Important, 7 Nice-to-have**
- **False positives eliminated:** 0
- **Deduped/merged:** 1 pair (`plan-api-B-1` ≡ `convention-B-1` — same underlying defect from two lenses)
- **Classification:** all 8 are **green** (fix clear from the finding, no research topic needed). No yellow.

The block is a faithful, high-quality implementation. The only non-cosmetic issue is the merged
Important one (Android `put` error-contract gap). The rest are robustness/coverage/consistency nits.

---

## Validated findings

### F1 (Important, green) — Android `put` leaks a raw `GeneralSecurityException`, defeating the migration's crash-safety
**Merged from `plan-api-B-1` + `convention-B-1` (same defect: error-contract lens + convention lens).**
**Files:** `app/.../secrets/AndroidKeystoreSecretStore.kt:83-89` (offender),
`app/.../secrets/SecretsMigration.kt:144-153` (consumer),
`companion/.../secrets/FileAesGcmSecretStore.kt:77-84` & `DpapiSecretStore.kt:49-61` (peer convention).

**Verified in source:** `AndroidKeystoreSecretStore.put` wraps only the KEK fetch
(`kekProvider.encryptionKey()` → `Unavailable`, lines 77-81); `cipher.init(ENCRYPT_MODE, key)` (l.87)
and `cipher.doFinal(value)` (l.88) run **unguarded**. Both throw `GeneralSecurityException` subclasses
(e.g. `KeyPermanentlyInvalidatedException`). The two companion `put` impls both wrap their encrypt step
into `SecretStoreException.StorageIo`, and this class's own `get` wraps decrypt into `DecryptionFailed`
(l.70) — Android `put` is the sole outlier. The sole prod caller `SecretsMigration.run` catches only
`SecretStoreException` (l.144) and `IOException` (l.148); it runs on every app start via
`DictateApplication.onCreate → PrefsMigration.migrateSecrets` until the flag is set. A raw
`GeneralSecurityException` therefore bypasses both catches → crashes `onCreate` → **boot loop** (flag
never set, plaintext stays, retries, crashes again). This is the exact app-start-crash mode the B2
self-fix hardened the backup-write path against, left open on the `put` path. **CONFIRMED** by two
independent auditors.

**Fix (clear/mechanical):** wrap `cipher.init`/`doFinal` in `try { … } catch (e: GeneralSecurityException)
{ throw SecretStoreException.StorageIo("encrypt failed for ${ref.handle}", e) }`, mirroring
`FileAesGcmSecretStore.put`. The migration's existing `catch (SecretStoreException)` then produces the
intended clean abort (flag unset, plaintext intact, retry next start).

### F2 (Nice-to-have, green) — non-atomic secret-blob write (`logic-B-1`)
**Files:** `companion/.../FileAesGcmSecretStore.kt:86`, `companion/.../DpapiSecretStore.kt:57`.
Both `put` do in-place `Files.write` (default `TRUNCATE_EXISTING`). Verified. On crash/power-loss mid-write
while *replacing* an existing secret, the blob is left truncated → next `get` throws `DecryptionFailed`
for a previously-valid secret. Loud + recoverable + replace is rare → low severity, but inconsistent with
the master-key write, which *was* hardened to atomic `CREATE_NEW` (B1-SF2). **Fix:** write `${name}.tmp`
then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` in both companion `put` paths.

### F3 (Nice-to-have, green) — `FileAesGcmSecretStore.available` memoizes a transient `false` (`logic-B-2`)
**File:** `companion/.../FileAesGcmSecretStore.kt:37-44`. Verified `by lazy` over `createDirectories` +
`isWritable`. In the long-lived companion/headless-Hub process, a transient not-writable configDir at
first access caches `false` for the whole session (only restart recovers); Android and DPAPI stores
evaluate availability per access. **Fix:** compute `available` per call (idempotent `createDirectories`,
cheap `isWritable` stat).

### F4 (Nice-to-have, green) — `AndroidKeystoreSecretStore.get` KEK-unavailable branch untested (`T1`)
**Files:** `app/.../AndroidKeystoreSecretStore.kt:48-54`, test
`app/src/test/.../AndroidKeystoreSecretStoreTest.kt`. Verified: the §5.3 "blob present but KEK lost after
restore → DecryptionFailed" branch (l.48-54) is uncovered — `get_withForeignKek` supplies a *working*
key (hits the GCM-tag path l.65-73) and `put_whenKekUnavailable` stores no blob to read back. Security-
relevant branch, cheap to pin. **Fix:** put a blob via a working `InMemoryKekProvider`, then `get` through
a store whose `KekProvider` throws; assert `DecryptionFailed`.

### F5 (Nice-to-have, green) — `detectSecretStore()` has no test (`T2`)
**File:** `companion/.../SecretStoreModule.kt:20-21`. Verified: platform selector (mirrors ADR-0018
`PlatformModule`), the non-Windows branch (`is FileAesGcmSecretStore`) is trivially assertable on Linux CI
and would pin the platform-selection contract; `secretFileName()` in the same file is covered only
indirectly. **Fix:** one companion test asserting `detectSecretStore(tmp) is FileAesGcmSecretStore` on the
non-Windows host; Windows branch stays under `block-B-windows-abnahme`.

### F6 (Nice-to-have, green) — uncommitted `writeOwnerOnly` hardening lacks a guarding regression test (`T3`)
**Files:** `companion/.../FileAesGcmSecretStore.kt:121-134`, test
`companion/src/test/.../FileAesGcmSecretStoreTest.kt`. **Verified via git:** the atomic `CREATE_NEW` +
`asFileAttribute(0600)` hardening is **uncommitted** (`git status` shows `M`; last commit `ceddd52 [B.1]`
still carries the old write-then-chmod; `git diff HEAD` shows the full change staged in the working tree).
`masterKeyFile_hasOwnerOnlyPermissions` asserts only the final `0600` state, so it passes for the old code
too — a revert would not be caught. **Fix:** commit the `FileAesGcmSecretStore.kt` change with a regression
test that fails on the write-then-chmod approach (e.g. `CREATE_NEW` collision / no widened intermediate
perms).

### F7 (Nice-to-have, green) — companion OS-detection done two ways (`convention-B-2`)
**Files:** `companion/.../SecretStoreModule.kt:20-21` vs `companion/.../platform/PlatformModule.kt:29-43`.
Verified: `SecretStoreModule.kt` exposes a top-level `detectSecretStore(configDir)` (no
`SecretStoreModule` object), while the established ADR-0018 convention is `object PlatformModule { fun
detect(): Bindings }`; the file's own KDoc claims it "mirrors `PlatformModule.detect()`". Navigational-
consistency gap, both forms valid. **Fix:** either wrap as `object SecretStoreModule { fun
detect(configDir: Path): SecretStore }` (max parity) or keep the free function and drop the "mirrors
`PlatformModule.detect()`" framing.

### F8 (Nice-to-have, green) — lone German phrase in an all-English KDoc (`convention-B-3`)
**File:** `shared-ai/.../ai/secrets/SecretStore.kt:73`. Verified: KDoc opens with
`Fehler-Semantik-Träger:` — the only German fragment in the block's main source; `language-conventions.md`
mandates English. **Fix:** replace with e.g. `Error-semantics carrier:`.

---

## Eliminated (false positives)

None. Every raw finding validated against source as real.

## Dedup / cross-cut notes

- **`plan-api-B-1` ≡ `convention-B-1`** merged into **F1** — identical defect (Android `put` leaves
  `cipher.init/doFinal` unwrapped, breaking the store's `SecretStoreException` error contract and
  crashing app start through the migration). The plan-and-api audit framed it as an error-contract
  breach; the convention audit as a same-operation-three-ways divergence. One fix resolves both.
- **File clustering:** F2 + F3 + F6 all touch `companion/.../FileAesGcmSecretStore.kt` (F2 also
  `DpapiSecretStore.kt`); F1 + F4 touch `app/.../AndroidKeystoreSecretStore.kt`; F5 + F7 touch
  `companion/.../SecretStoreModule.kt`. The workflow's file-clustering can bundle these.
- **Not raised as a finding (auditor-vetted, agreed):** `DpapiSecretStore.get` catching broad
  `Exception` (vs narrow `GeneralSecurityException` in the other two) is *correct* —
  `cryptUnprotectData` throws `Win32Exception` (a `RuntimeException`), which a narrow catch would miss.
  `MAIN_PREFS_NAME` duplicating a 12+-site hardcoded prefs name is cleaner-than-prevailing, out of block
  scope. The §2.3-vs-§4.3, two-companion-`put`-impls, Android cipher-IV, and `:companion`→`:shared-ai`
  items are all documented, defensible deviations (D4). Left out.
- **Environment caveat (not a code finding):** the full all-module `./gradlew build` was not run
  (companion jpackage cannot cross-compile on the Linux VM); per-module compile+test is green. The
  all-module build-green invariant is confirmed under the Windows/full-build acceptance (F1 runbook).
