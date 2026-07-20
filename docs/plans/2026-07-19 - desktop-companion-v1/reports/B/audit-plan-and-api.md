# Block B Audit — `plan-and-api`

**Block:** B (SecretStore port + Android/Desktop impls + Android plaintext-key migration)
**Topic:** plan-and-api — (a) plan fidelity, (b) stubs/placeholders, (c) API-consumer match
**Baseline:** `c46cfe8..HEAD`, file-scoped to BLOCK_FILES
**Spec:** `research/secretstore.md` §4–§7 · **ADR:** `adrs/adr-secret-store.md`
**Timestamp:** 2026-07-20T00:40:00+02:00
**Grounding loaded:** `knowledge-typescript` (sealed-class ≈ discriminated-union / exhaustiveness mapping — the project is Kotlin, not TS)

## Verdict

The block is a **faithful, high-quality** implementation of spec §4–§7. Every §2
acceptance criterion is addressed; the two impl deviations (companion→shared-ai dep,
Android cipher-generated IV) and the B2 scoping deviation (reader re-point deferred to
C2/C3, encoded as a pending `NoLegacyKeyReadTest`) are all documented and defensible
(D4). No stubs, no throw-not-implemented, no placeholder returns. All 11 slot pref keys
and their `SecretRef(namespace,id)` targets match §3.1/§7.2 exactly.

One **Important** finding: the port's implied error contract (all failures surface as
`SecretStoreException`) is honoured by both companion `put` impls but **broken** by the
Android `put`, and the migration consumer relies on that contract for its documented
crash-safety.

## Findings

### plan-api-B-1 (Important, CONFIRMED) — Android `put` leaks non-`SecretStoreException` from the cipher, defeating the migration's crash-safety

**Files:** `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt:87-88`
(consumer: `app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt:144-153`)

`AndroidKeystoreSecretStore.put` wraps only the KEK fetch (→ `Unavailable`); the cipher
operations are **unwrapped**:

```
cipher.init(Cipher.ENCRYPT_MODE, key)   // line 87 — can throw InvalidKeyException / KeyPermanentlyInvalidatedException
val ciphertext = cipher.doFinal(value)  // line 88 — can throw GeneralSecurityException
```

Both are `java.security.GeneralSecurityException` subclasses and propagate **raw**. This
breaks the port's error contract that the other two impls uphold — `FileAesGcmSecretStore.put`
wraps its encrypt in `StorageIo` (FileAesGcmSecretStore.kt:82-84) and `DpapiSecretStore.put`
wraps `cryptProtectData` in `StorageIo` (DpapiSecretStore.kt:50-54). The Android `get` path
is fully wrapped (every branch → `DecryptionFailed`); only `put`'s cipher is the outlier.

Why it matters (consumer coupling): `SecretsMigration.run` runs on **every app start**
(`DictateApplication.onCreate` → `PrefsMigration.migrateSecrets`) until the flag is set, and
its migration loop catches **only** `SecretStoreException` and `IOException`
(SecretsMigration.kt:144, 148). A raw `GeneralSecurityException` from `put` bypasses both
catches, propagates out of `run()` → `onCreate`, and **crashes app start** — a boot loop on
every start because the flag is never set (plaintext stays, migration retries, crashes again).
This is exactly the app-start-crash failure mode the B2 self-fix hardened the backup write
against (see B2-selffix.md §"App-start crash"); the `put` path leaves the same hole open.

Failure scenario: a device whose Keystore KEK became invalid (`KeyPermanentlyInvalidatedException`,
an `InvalidKeyException`, is observed on some OEMs even for non-auth keys after
lockscreen/biometric changes) passes `secretStore.available` (retrieving the key entry
succeeds; invalidation only surfaces on cipher use), then `put`'s `cipher.init` throws
uncaught → migration crashes `onCreate` on every boot.

Suggested fix: wrap `cipher.init`/`doFinal` in `AndroidKeystoreSecretStore.put` in
`try { … } catch (e: GeneralSecurityException) { throw SecretStoreException.StorageIo("encrypt failed for ${ref.handle}", e) }`
— mirroring the two companion impls. The migration's existing `catch (SecretStoreException)`
then turns it into the intended clean abort (flag unset, plaintext intact, retry next start),
restoring the "never crash app start" guarantee.

## Reviewed and accepted (not findings)

- **§2.3 (`unavailable → get==null`) vs §4.3 (`present blob + lost KEK → DecryptionFailed`)** —
  `AndroidKeystoreSecretStore.get` returns `null` when no blob exists and throws
  `DecryptionFailed` when a blob exists but the KEK is gone. The two spec clauses are in mild
  tension, but distinguishing by blob presence is the correct reconciliation of §4.3's explicit
  KEK-loss rule; documented in B1-selffix.md. Accepted.
- **Companion declares two `put` impls** (Dpapi + FileAesGcm) vs §2.1 "je genau eine
  Implementierung" — the §1a.0 diagram mandates the two runtime-selected platform variants
  behind `detectSecretStore`; "one" means one logical platform store. Accepted.
- **Cipher-generated IV on Android** (vs §5.2 "IV aus SecureRandom … vorangestellt") — AndroidKeyStore
  GCM forbids a caller-supplied IV on encrypt; the invariant (fresh 12-byte IV per put, prepended)
  holds. Documented. Accepted.
- **`:companion` → `:shared-ai` dependency** (spec §9 lists only :app edits) — §4.1 mandates both
  hosts depend on `:shared-ai`; additive, no rework. Accepted.
- **B2 reader re-point deferred to C2/C3, §2.6 as pending `NoLegacyKeyReadTest`** — matches the
  plan's explicit B2-before-C2 sequencing (§7.2); the pending test carries the real end-state
  assertion + tracking ref, and its scanner self-check is green. Accepted.

## Out-of-scope observations (for the consolidator)

- **[test]** Acceptance criterion §2.1 requires `./gradlew build` green **across all modules**;
  the reports state the full build was not run (companion jpackage cannot cross-compile on the
  Linux VM + concurrent-agent in-flight edits) and rely on per-module compile+test. The
  all-module build-green invariant is therefore unverified in this environment — confirm in the
  Windows/full-build acceptance (F1).
- **[convention/docs, Block C file]** `shared/src/main/kotlin/net/devemperor/dictate/shared/config/Entities.kt:84`
  comments "the clear-text key lives only in the SecretStore under `SecretRef(id)`", but the
  ctor is `SecretRef(namespace, id)`. Doc imprecision in a Block-C file (out of Block B scope) —
  C2 implementers should read it as `SecretRef("credential", id)` per §7.2.

## Coverage

Audited (all BLOCK_FILES): `SecretStore.kt`, `SecretRefTest.kt`, `KekProvider.kt`,
`AndroidKeystoreSecretStore.kt`, `AndroidKeystoreSecretStoreTest.kt`, `InMemoryKekProvider.kt`,
`SecretStoreModule.kt`, `DpapiSecretStore.kt`, `FileAesGcmSecretStore.kt`,
`FileAesGcmSecretStoreTest.kt`, `DpapiSecretStoreTest.kt`, `companion/build.gradle`,
`backup_rules.xml`, `data_extraction_rules.xml`, `SecretsMigration.kt`, `DictatePrefs.kt`
(diff), `PrefsMigration.kt`, `DictateApplication.java` (diff), `SecretsMigrationTest.kt`,
`NoLegacyKeyReadTest.kt`. Cross-checked against spec §2–§7, the four chunk reports
(B1/B2 impl+selffix), and a repo-wide grep for consumers of the new APIs (only PrefsMigration/
DictateApplication consume them; `detectSecretStore` intentionally unconsumed until Block D).

Skipped: none in scope. `AndroidKeystoreSecretStoreTest.kt`/companion tests reviewed for API
usage (no signature drift) but their coverage adequacy belongs to the `test` topic.
