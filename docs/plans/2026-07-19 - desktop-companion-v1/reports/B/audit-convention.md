# Block B — Convention Audit

**Topic:** `convention` (same operation done differently across chunks: logging, error handling, naming, file layout, imports)
**Block:** B (SecretStore port + Android/Desktop impls + Android plaintext-key migration)
**Chunks in scope:** B1 (port + 3 platform impls + backup exclusion), B2 (Android plaintext-key migration)
**Base:** `c46cfe8` → HEAD `06612d0`
**Timestamp:** 2026-07-20T00:40:00+02:00
**Grounding loaded:** `knowledge-reference` (TS-oriented, not directly applicable to this Kotlin/Java block), project `CLAUDE.md`, user `language-conventions.md`.

## Coverage

Audited (all new in this block except the 4 edited files):

- Port: `shared-ai/.../ai/secrets/SecretStore.kt`
- Android impl: `app/.../secrets/AndroidKeystoreSecretStore.kt`, `KekProvider.kt`
- Companion impls: `companion/.../secrets/SecretStoreModule.kt`, `DpapiSecretStore.kt`, `FileAesGcmSecretStore.kt`
- Migration: `app/.../secrets/SecretsMigration.kt`, `preferences/PrefsMigration.kt`, `DictatePrefs.kt`, `DictateApplication.java`
- Backup XML: `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`
- Gradle: `companion/build.gradle`
- Tests (naming/import/header convention only — behaviour is the `test` topic): all 8 new test files.

Cross-referenced against prevailing codebase conventions: `PlatformModule.kt` (companion OS-detection), the pervasive `"net.devemperor.dictate"` prefs-name hardcoding (12+ sites), the `catch (e: Exception)` unused-variable style (33× vs 11× `catch (_:`), the `@see docs/plans/...` inline-anchor convention.

Nothing skipped.

## Findings

### convention-B-1 (Important) — Android `put` leaves the encrypt step unwrapped; the other two stores (and its own `get`) wrap crypto failures into `SecretStoreException`

**Files:**
- `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt:83-89` (offender)
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStore.kt:77-84` (peer convention)
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/DpapiSecretStore.kt:49-61` (peer convention)
- `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt:65-73` (same-class `get` convention)
- `app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt:144-153` (consumer that only catches `SecretStoreException`/`IOException`)

**What's wrong:** All three `SecretStore` impls share one implied error-handling contract — the `SecretStoreException` sealed hierarchy is the store's error vocabulary, and every crypto/IO failure is wrapped into it (`DecryptionFailed`, `StorageIo`, `Unavailable`). Two of the three `put` impls follow this for the encrypt step:
- `FileAesGcmSecretStore.put` wraps `Cipher.init`/`doFinal` in `try { … } catch (GeneralSecurityException) → StorageIo`.
- `DpapiSecretStore.put` wraps `cryptProtectData` in `try { … } catch (Exception) → StorageIo`.

`AndroidKeystoreSecretStore.put` wraps **only** the KEK fetch (`kekProvider.encryptionKey()` → `Unavailable`, lines 77-81); the actual `cipher.init(ENCRYPT_MODE, key)` (line 87) and `cipher.doFinal(value)` (line 88) run **unguarded**. This is also inconsistent with the same class's `get`, which wraps the decrypt `GeneralSecurityException` into `DecryptionFailed` (line 70).

**Why it matters (not merely stylistic):** A raw `GeneralSecurityException` can escape `put()`. The realistic trigger is `cipher.init` on a Keystore key that was permanently invalidated (`KeyPermanentlyInvalidatedException extends InvalidKeyException extends GeneralSecurityException`) — e.g. after a biometric/lockscreen-credential change. Kotlin has no checked exceptions, so it propagates. The sole production caller is `SecretsMigration.run`, whose `try` catches only `SecretStoreException` and `IOException` (lines 144, 148). A raw `GeneralSecurityException` therefore is **not** caught → propagates through `PrefsMigration.migrateSecrets` → `DictateApplication.onCreate` → **crashes app start on every boot** until the condition clears. This is exactly the clean-abort invariant (§7.3: "abort → flag unset, plaintext intact, retry next start") the block otherwise guarantees, and the identical failure mode the B2 self-fix hardened for the backup-write path — left open here for the encrypt path via the convention gap.

**Expected instead:** wrap `cipher.init`/`doFinal` in `AndroidKeystoreSecretStore.put` in `try { … } catch (e: GeneralSecurityException) → SecretStoreException.StorageIo("encrypt failed for ${ref.handle}", e)` (or `Unavailable` for the key-invalidated case, if a re-key retry is desired), matching `FileAesGcmSecretStore.put` and `DpapiSecretStore.put`. Then the migration's existing `catch (SecretStoreException)` clean-abort covers it.

**Suggested fix:** mechanical — mirror the `FileAesGcmSecretStore.put` try/catch shape around lines 87-88.

### convention-B-2 (Nice-to-have) — companion OS-detection done two ways: `object PlatformModule { fun detect() }` vs. a top-level `detectSecretStore()`

**Files:**
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModule.kt:20-21`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/platform/PlatformModule.kt:29-43` (established convention)

**What's wrong:** The companion already has an OS-detection convention (ADR-0018): `object PlatformModule` with a `detect(): Bindings` method that branches on `Platform.isWindows()`. The new secret-store detection lives in a file named `SecretStoreModule.kt` — deliberately paralleling the `…Module.kt` filename — but is a **top-level function** `detectSecretStore(configDir)`, with no `SecretStoreModule` object. The B1 impl report explicitly states it is "selected by `detectSecretStore()` after the `PlatformModule.detect()` pattern", yet the structure differs (object+`detect()` vs. free function). A reader navigating from `PlatformModule` expects a parallel `SecretStoreModule.detect(...)`.

**Why it matters:** low — both forms are valid Kotlin and the free function is arguably simpler (no state to hold). It is a same-operation-two-ways consistency gap within one module, not a defect.

**Suggested fix:** either wrap as `object SecretStoreModule { fun detect(configDir: Path): SecretStore = … }` to mirror `PlatformModule` exactly, or keep the free-function form and drop the "mirrors `PlatformModule.detect()`" framing. Either resolves the mismatch; the object form maximizes navigational parity.

### convention-B-3 (Nice-to-have) — lone German phrase in an otherwise all-English KDoc

**File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/secrets/SecretStore.kt:73`

**What's wrong:** The `SecretStoreException` KDoc opens with `Fehler-Semantik-Träger:` — the only German fragment in the block's main source (grep-verified). `language-conventions.md` mandates English for code comments and identifiers, and every other KDoc in this file and across the block is English.

**Why it matters:** low — cosmetic/consistency only. Flagged because the topic is naming/comment consistency and this is the single divergence.

**Suggested fix:** replace with an English equivalent, e.g. `Error-semantics carrier:` or `Carries the error semantics:`.

## Out-of-scope observations (for the consolidator)

- **Error-class breadth for the decrypt/read path differs across impls but is justified, not a finding:** `AndroidKeystoreSecretStore.get` and `FileAesGcmSecretStore.get` catch the specific `GeneralSecurityException`, while `DpapiSecretStore.get` catches broad `Exception`. `Crypt32Util.cryptUnprotectData` throws `Win32Exception` (a `RuntimeException`), **not** `GeneralSecurityException`, so a narrow catch there would miss the real failure — the broad catch is necessary. Documented here so the consolidator does not re-raise it.
- **`SecretsMigration.MAIN_PREFS_NAME = "net.devemperor.dictate"` duplicates a string hardcoded at 12+ sites** (`DictateApplication`, `APISettingsActivity`, `DictateUtils`, … ; `DictatePipelineService.PREFS_NAME` exists but is not used as a shared constant). The new code introducing a **named** constant is if anything cleaner than the prevailing pattern; not a regression. A project-wide "single prefs-name constant" cleanup is out of this block's scope. (Possible `plan-and-api`/tech-debt note, not a convention finding.)
- **`AndroidKeystoreSecretStoreTest.kt` KDoc uses a dangling `[spec]` link** (`([spec] §5.4)`) with no link definition — a doc nit, belongs to `test`/docs, not convention.

## Verdicts

- convention-B-1: **CONFIRMED** — unwrapped encrypt verified in source; migration catch clauses verified to exclude raw `GeneralSecurityException`; app-start propagation path verified through `PrefsMigration.migrateSecrets` → `DictateApplication.onCreate`.
- convention-B-2: **CONFIRMED** — structural divergence verified against `PlatformModule.kt`.
- convention-B-3: **CONFIRMED** — grep-verified single German fragment.
