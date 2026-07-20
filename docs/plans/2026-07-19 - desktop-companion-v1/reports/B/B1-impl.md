# B1 — SecretStore-Port + Android/Desktop-Impls — Implementation Report

**Chunk:** B1 (Block B — SecretStore)
**Timestamp:** 2026-07-20T00:40:00+02:00
**Spec:** `research/secretstore.md` §4 (Port), §5 (Android Keystore incl. §5.3 backup exclusion, §5.4 Robolectric seam), §6 (Desktop DPAPI + file fallback)

## What was done

Built the projectwide `SecretStore` port in `:shared-ai` (byte-oriented `get/put/delete` + `available`/`hardwareBacked` flags + `SecretRef` + `SecretStoreException`), plus three platform implementations: `AndroidKeystoreSecretStore` (Keystore-KEK AES-256-GCM behind an injectable `KekProvider` cipher-seam), `DpapiSecretStore` (Windows DPAPI via the already-present `jna-platform` `Crypt32Util`), and `FileAesGcmSecretStore` (non-Windows `0600` master-key AES-GCM fallback), selected by `detectSecretStore()` after the `PlatformModule.detect()` pattern. Android Auto-Backup/device-transfer now excludes the secret-blob prefs file (§5.3). Migration (B2) is deliberately out of scope.

## Scope boundary (B1 vs B2)

Implemented (B1): the port + both impls + the seam + backup exclusion + all round-trip/error-semantics tests. **Not** implemented (belongs to B2 per plan §5): `SecretsMigration.kt`, the `SecretsMigratedV1` pref flag, the `PrefsMigration`/`RunnerFactory` edits, and the migration/`NoLegacyKeyRead`/characterization tests. `AndroidKeystoreSecretStore.create(context)` and `detectSecretStore(configDir)` are the entry points B2 (Android) and Block D (companion) will wire in.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| `:companion` now depends on `:shared-ai` | spec §9 lists only `:app` edits; settings.gradle comment defers companion→shared-ai to "Block D" | Added `implementation project(':shared-ai')` to `companion/build.gradle` | The port lives in `:shared-ai`; the companion impls implement it, so §2 criterion 1 ("`./gradlew build` green, `:companion` declares an impl") cannot hold without it. Spec §4.1 explicitly states both hosts depend on `:shared-ai` | Block D's planned wiring is brought forward one block; no rework — the dep is additive | ✓ |
| Android encrypt uses cipher-generated IV, not a hand-supplied `SecureRandom` IV | spec §5.2 wording ("IV je `put` neu aus `SecureRandom` … vorangestellt") | `cipher.init(ENCRYPT_MODE, key)` with no `GCMParameterSpec`; the fresh random 12-byte IV is read back via `cipher.iv` and prepended | AndroidKeyStore GCM keys **forbid** a caller-supplied IV on encrypt (`InvalidAlgorithmParameterException`). Cipher-generated IV keeps the invariant (fresh random per put, prepended) and works identically under the Robolectric in-memory key | None — blob format unchanged (`IV(12) ‖ ct+tag`); B2/E read via the same store | ✓ |
| Shared `secretFileName()` helper | spec §9 lists 3 companion files | Added `internal fun secretFileName()` inside the existing `SecretStoreModule.kt` (no extra file) | DRY: both file-backed stores map handle→file identically (SHA-256 URL-safe Base64) | None | ✓ |

## Issues

| ID | Severity | Description | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

## Tests (all green)

| Test | Module | Result |
|---|---|---|
| `SecretRefTest` | :shared-ai | 5/5 pass (handle derivation, blank/charset validation) |
| `AndroidKeystoreSecretStoreTest` (Robolectric + `InMemoryKekProvider`) | :app | 11/11 pass (round-trip incl. non-UTF-8 bytes, delete→null, replace, IV-uniqueness, foreign-KEK/corrupt/truncated → `DecryptionFailed`, unavailable → `Unavailable`) |
| `FileAesGcmSecretStoreTest` (real, Linux) | :companion | 10/10 pass (round-trip, delete→null, `0600` master key, `available==false` on read-only dir, swapped-key/truncated → `DecryptionFailed`) |
| `DpapiSecretStoreTest` | :companion | 3 tests, 2 `assumeTrue(Platform.isWindows())`-skipped on Linux (`pending: block-B-windows-abnahme`), 1 no-file→null runs green everywhere |
| `SharedAiPurityTest` | :shared-ai | green — `SecretStore.kt` has no Android/Ktor/coroutine/org.json imports |

Test command scope: `:shared-ai:test`, `:companion:test`, `:app:testDebugUnitTest --tests net.devemperor.dictate.secrets.*`, plus `:app:processDebugResources --rerun-tasks` (AAPT-validates the two backup XML files, referenced by the manifest). Full `./gradlew build` was not run to avoid the companion's known jpackage-cannot-cross-compile limitation on this Linux VM (documented in `companion/build.gradle`); all three modules compile (main+test) and their test suites pass.

## Files modified

New:
- `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/secrets/SecretStore.kt`
- `shared-ai/src/test/kotlin/net/devemperor/dictate/ai/secrets/SecretRefTest.kt`
- `app/src/main/java/net/devemperor/dictate/secrets/KekProvider.kt`
- `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt`
- `app/src/test/java/net/devemperor/dictate/secrets/InMemoryKekProvider.kt`
- `app/src/test/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStoreTest.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModule.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/DpapiSecretStore.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStore.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStoreTest.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/secrets/DpapiSecretStoreTest.kt`

Edited:
- `companion/build.gradle` (+ `:shared-ai` dependency)
- `app/src/main/res/xml/backup_rules.xml` (exclude `secretstore.xml`)
- `app/src/main/res/xml/data_extraction_rules.xml` (exclude `secretstore.xml`)

## Files outside assigned scope (drift)

none. (`shared/.../CanonicalJsonTest.kt` and `companion/.../domain/session/` show in `git status` but were produced by other concurrent agents — untouched by B1.)

## Helper decisions

- `InMemoryKekProvider` placed as a standalone test helper (`app/src/test/.../secrets/`) rather than inline, so the B2 migration test can reuse the same seam.
- `secretFileName()` handle→file mapping factored into `SecretStoreModule.kt` (shared by both file-backed stores).
- Reused the existing `FakeSharedPreferences` (`app/.../testutil`) and `TemporaryFolder` conventions rather than introducing Mockito.

## Primitives reused

`PlatformModule.detect()` (OS-branch pattern), `WindowsRegistry`/`Advapi32Util` (JNA `com.sun.jna.platform.win32` binding family → `Crypt32Util`), `TextInserter.available` (honest-availability flag semantics), `FakeSharedPreferences` test fake, `TemporaryFolder` + `Assume.assumeTrue` test conventions from `SingleInstanceGuardTest`/`AutostartManagerTest`.
