# Block B — Audit: `logic`

**Topic:** logic (logic errors + edge cases: boundaries, null/empty, off-by-one, races, error-path coverage)
**Block:** B (SecretStore port + Android/Desktop impls + Android plaintext-key migration)
**Diff range:** `c46cfe8..HEAD`, file-scoped to BLOCK_FILES
**Timestamp:** 2026-07-20T00:40:00+02:00
**Grounding:** `knowledge-typescript` (conceptual: null-safety, discriminated-union / error-taxonomy, exhaustiveness) — the code is Kotlin/Java, so the TS mechanics don't transfer, only the reasoning lenses.

## Verdict

The block is logically sound. Every high-risk path I traced — the GCM blob framing,
the decrypt-vs-null-vs-DecryptionFailed error taxonomy (§4.3), the migration order
invariant (§7.3), idempotence, abort-and-retry, and the async-`apply()` ordering — is
correct. No Critical or Important logic defect. Two Nice-to-have robustness edges below.

## Paths verified correct (no finding)

- **GCM blob boundary guard.** Both `AndroidKeystoreSecretStore.get` (`:app`, l.61) and
  `FileAesGcmSecretStore.get` (`:companion`, l.58) reject `blob.size <= IV_LENGTH` before
  slicing. A valid GCM output is always ≥ IV(12)+tag(16)=28 bytes even for an empty
  payload, so no legitimate blob is rejected and a truncated/short blob surfaces as
  `DecryptionFailed`, never a silent empty. Off-by-one is correct (`<=`, not `<`).
- **Error taxonomy (§4.3).** `null` = no blob; `DecryptionFailed` = blob present but KEK
  lost / GCM-tag mismatch / corrupt Base64; `Unavailable` = store not initialisable on
  `put`. Traced every catch site — none lets a decrypt failure masquerade as an empty
  key. The Android "present-blob + KEK-loss → DecryptionFailed" reading (vs the literal
  §4.3 "available==false → null") is correct: after a device-transfer restore
  `getOrCreateKey()` mints a *fresh* KEK, so `available` is **true** and the old blob
  fails the GCM tag → `DecryptionFailed`. Documented + defensible (B1 self-fix).
- **Migration order invariant (§7.3) is preserved even though writes are async.** The
  loop does `secretStore.put` (blobPrefs `apply()`) → `sp.remove` (main-prefs `apply()`)
  per slot, then the flag `apply()` last. `apply()` is async, but Android's `QueuedWork`
  drains all finishers on a single thread in submission (= call) order across *all*
  SharedPreferences files, so on-disk order = call order: a plaintext is never removed on
  disk before its ciphertext is written, and the flag never lands before the blobs. No
  durability inversion.
- **Backup-before-delete + write-once + atomic-rename.** `exportBackupOnce` runs inside
  the `try` before the slot loop, stages to `${name}.tmp` and `renameTo`s, so the
  write-once guard (`if (backupFile.exists()) return`) can only ever observe a *complete*
  snapshot; a partial/failed backup write aborts the whole migration before any delete
  (both `SecretStoreException` and `IOException` catch → flag stays unset, plaintext
  intact, retry next start). Idempotence flag is set only on full success.
- **Abort/retry with a persistent backing store.** Prod re-creates the store each start
  but it is backed by the same `secretstore.xml` / secrets dir, so slots stored before a
  mid-loop failure survive the retry; the retry re-`put`s a *newly re-entered* slot value
  (non-empty wins) and skips already-migrated (now-empty) slots. Traced — no lost or
  double-migrated slot.
- **Fresh-install sets the flag with nothing migrated** — correct: the migration targets
  *pre-existing* upgrade-time plaintext; a fresh install has none, and B2/C2/C3 ship
  together so there is no cross-version window where post-flag prefs writes are stranded.
- **Path-traversal safety.** `secretFileName` = URL-safe unpadded Base64 of SHA-256(handle),
  so a `SecretRef.id` (unvalidated charset) can never escape the secrets dir even though
  only `namespace` is charset-checked. `delete` is a plain file delete of that hashed name.
- **IV freshness / GCM nonce.** Android relies on the AndroidKeyStore-generated IV read
  back via `cipher.iv` (a caller IV is forbidden there); FileAesGcm uses a fresh
  `SecureRandom` 12-byte IV per `put`. `twoPuts_ofSameValue_useDistinctIVs` pins it. No
  nonce reuse.
- **Master-key atomic create (`writeOwnerOnly`, `CREATE_NEW` + POSIX 0600 attr)** closes
  the write-then-chmod readable-window/crash footgun. Correct.

## Findings

### logic-B-1 (Nice-to-have) — non-atomic secret-blob write corrupts a secret on crash-during-replace

`FileAesGcmSecretStore.put` (`companion/.../FileAesGcmSecretStore.kt:86`,
`Files.write(fileFor(ref), iv + ciphertext)`) and `DpapiSecretStore.put`
(`companion/.../DpapiSecretStore.kt:57`, `Files.write(fileFor(ref), blob)`) write the
secret file in place with the default `TRUNCATE_EXISTING`. When *replacing* an existing
secret, a crash / power loss between truncate and full write leaves a truncated blob;
the next `get` then throws `DecryptionFailed` for a secret that was previously valid.

- **Failure scenario:** secret X exists; `put(X, newValue)` truncates X's file, process is
  killed mid-write → X's file is now shorter than IV or a partial ciphertext → next
  `get(X)` → `DecryptionFailed` → the user must re-enter a key they never intended to lose.
- **Why only Nice-to-have:** the failure is *loud* (`DecryptionFailed`, never a silent
  empty), recoverable by re-entry, replace is rare, and the whole subsystem already
  accepts "re-enter on KEK loss." But it is inconsistent with the master-key write, which
  *was* specifically hardened to be atomic (B1-SF2); the secret blobs were not.
- **Suggested fix:** mirror the master-key / backup pattern — write to a `${name}.tmp` and
  `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` in both companion stores.

### logic-B-2 (Nice-to-have) — `FileAesGcmSecretStore.available` memoizes a transient `false` for the process lifetime

`available` is a `by lazy` over `Files.createDirectories(secretsDir)` +
`Files.isWritable` (`companion/.../FileAesGcmSecretStore.kt:37-44`). The companion is a
long-lived desktop / headless-Hub process. If the *first* access to `available` (or any
op that reads it) happens while `configDir` is transiently not creatable/writable (slow
mount, a startup FS hiccup), the lazy caches `false` permanently; the store then rejects
every `put` with `Unavailable` for the rest of the run even after the directory becomes
writable, and only a restart recovers.

- **Failure scenario:** configDir on a network/removable mount not ready at first secret
  access → `available` caches false → all subsequent `put`s throw `Unavailable` for the
  whole session despite the mount coming online seconds later.
- **Why only Nice-to-have:** the normal companion startup creates configDir eagerly, so
  this needs an unusual environment; low real-world probability.
- **Suggested fix:** compute `available` per call (it is cheap — `createDirectories` is
  idempotent, `isWritable` is a stat) instead of memoizing, so recovery is automatic. The
  Android and DPAPI stores already evaluate availability per access.

## Out-of-scope observations (for the consolidator)

- **[plan-and-api]** The `SecretStore` port doc (SecretStore.kt:52-56) motivates
  `namespace` as "so a plausible key set stays enumerable and deletable (e.g. all keys of
  a removed ProviderConfig)", but the port exposes no enumerate / delete-by-namespace
  operation. Block-B scope is get/put/delete only; if C2's ProviderConfig deletion needs
  bulk secret removal it will have to iterate known refs. Not a B defect — flagging the
  latent gap for the entity-model chunks.
- **[convention]** `DpapiSecretStore.available` is a hard `true` while `put` failures
  surface as `StorageIo` (never `Unavailable`), whereas `FileAesGcmSecretStore` reports
  `available=false` + `Unavailable` on a non-writable dir. Defensible (DPAPI is genuinely
  always present under a Windows profile) but the two companion stores classify a
  write-target failure differently. Consistency call for the convention auditor.

## Coverage note

**Audited (all 20 BLOCK_FILES):** `SecretStore.kt`, `SecretRef`/`SecretStoreException`,
`AndroidKeystoreSecretStore.kt` + `KekProvider.kt`, `SecretsMigration.kt`,
`PrefsMigration.kt`, `DictatePrefs.kt` (flag), `DictateApplication.java` (call site),
`DpapiSecretStore.kt`, `FileAesGcmSecretStore.kt`, `SecretStoreModule.kt`
(`detectSecretStore` + `secretFileName`), `backup_rules.xml` /
`data_extraction_rules.xml` (exclude-path matches `secretstore.xml`), and all six test
files (read for edge-coverage cross-check, not audited as production logic).
**Skipped:** `companion/build.gradle` (dependency wiring, no logic). The DPAPI round-trip
is Windows-gated (`assumeTrue`) so its crypto path is unverifiable on this Linux VM — the
non-crypto branches (absent-ref → null, framing) were reasoned through statically.
