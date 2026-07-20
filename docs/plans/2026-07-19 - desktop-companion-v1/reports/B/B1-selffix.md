# B1 — SecretStore-Port + Android/Desktop-Impls — Self-Fix Report

**Chunk:** B1 (Block B — SecretStore)
**Reviewer:** fresh-eyes self-fix (diff-based on wave commit `ceddd52`)
**Timestamp:** 2026-07-20T00:40:00+02:00
**Spec:** `research/secretstore.md` §4 (Port), §5 (Android Keystore incl. §5.3 backup exclusion, §5.4 seam), §6 (Desktop DPAPI + file fallback)

## What I did

Reviewed the B1 diff against spec §4–§6 with the three lenses (plan correctness,
code quality, test quality). Plan correctness is solid — every §4–§6 requirement is
present and the two documented deviations (companion→shared-ai dep, cipher-generated
IV) are defensible. Fixed three items inline: one real defect in the committed wave
(duplicate Gradle dependency), one security hardening (atomic `0600` master-key
creation), one misleading test comment.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| B1-SF1 | Important | Duplicate `implementation project(':shared-ai')` in `companion/build.gradle:46,50` — the B1 wave committed two identical declarations (one carrying another chunk's `desktop-host.md §3.2` taxonomy rationale, one B1's SecretStore rationale). Parent `c6a828f` had neither; both landed in `ceddd52`. Gradle dedupes so the build stayed green, but it is a DRY/clarity defect. | fixed-inline | none |
| B1-SF2 | Nice-to-have | `FileAesGcmSecretStore.writeOwnerOnly` wrote `master.key` with the umask default and only *then* chmodded to `0600` — a readable window, and on a crash between the two calls a **permanently** world-readable master key. Defeats the §6.4 `0600` intent. | fixed-inline | none |
| B1-SF3 | Nice-to-have | `AndroidKeystoreSecretStoreTest.get_onCorruptBlob…` comment claimed "GCM tag mismatch" but the test stores invalid Base64, exercising the decode branch (the GCM path is covered by the foreign-KEK test). Comment corrected. | fixed-inline | none |
| B1-SF4 | Important | `companion/src/test/.../data/ChordMigrationSeedTest.kt` does not compile (`Unresolved reference 'Received_texts'`, missing `sessionsAdapter`/`dispatch_stateAdapter`), which blocks the **whole** `:companion` test source set — including the B1 secrets tests — from running. **Foreign, not a B1 regression:** caused by a *concurrent* agent's uncommitted edits to `Companion.sq`/`CompanionDatabase.kt`/`domain/session/` (all show ` M`/`??` in `git status`). Transient; resolves when that agent commits a consistent SQLDelight state. Out of B1 scope — do not fix here. | delegated | blocks-following |

## Inline fixes applied

1. **`companion/build.gradle`** — collapsed the two `implementation project(':shared-ai')`
   declarations into one, merging both rationales (AI taxonomy + SecretStore port) into
   a single comment block.
2. **`FileAesGcmSecretStore.kt`** — `writeOwnerOnly` now creates `master.key` atomically
   with owner-only perms via `Files.newByteChannel(CREATE_NEW, WRITE, asFileAttribute(0600))`,
   falling back to plain write on a non-POSIX FS (unreachable — `detectSecretStore` routes
   Windows to DPAPI). Added `ByteBuffer`, `StandardOpenOption`, `PosixFilePermissions` imports.
   The perm set is unchanged (`{OWNER_READ, OWNER_WRITE}`), so `masterKeyFile_hasOwnerOnlyPermissions`
   still holds.
3. **`AndroidKeystoreSecretStoreTest.kt`** — corrected the corrupt-blob test comment to
   describe the Base64-decode branch it actually exercises.

## Deviations reviewed (from impl, not re-opened)

- **`:companion` → `:shared-ai` dep** (spec §9 vs §4.1): defensible — §4.1 states both hosts
  depend on `:shared-ai`; the dep is additive. Accepted.
- **Cipher-generated IV on Android** (spec §5.2 wording): defensible and necessary —
  AndroidKeyStore GCM keys forbid a caller-supplied IV on encrypt; the invariant
  (fresh random 12-byte IV per put, prepended) holds and `twoPuts_…useDistinctIVs`
  proves it. Blob format unchanged. Accepted.
- **`get` on present-blob + KEK-loss → `DecryptionFailed`** (vs §2 crit 3 "unavailable → null"):
  correct reading of §4.3 — absent blob → `null`, present blob + lost device-bound KEK → the
  restore scenario → `DecryptionFailed`. Accepted.

## Tests (after fixes)

| Test | Module | Result |
|---|---|---|
| `SecretRefTest` | :shared-ai | green (`:shared-ai:test` up-to-date; file untouched) |
| `AndroidKeystoreSecretStoreTest` (Robolectric) | :app | green — `:app:testDebugUnitTest --tests net.devemperor.dictate.secrets.*` BUILD SUCCESSFUL |
| `InMemoryKekProvider` | :app | compiles + used by the above |
| `FileAesGcmSecretStoreTest`, `DpapiSecretStoreTest` | :companion | **not executed** — blocked by foreign B1-SF4 test-compile breakage. `:companion:compileKotlin` (main, incl. my Fix B) + `:companion:jar` succeed; the FileAesGcm test is structurally unchanged and my perm set matches its assertion. |

Command scope: `./gradlew :shared-ai:test :companion:test` (companion test-compile fails
on foreign `ChordMigrationSeedTest.kt`), `./gradlew :app:testDebugUnitTest --tests
net.devemperor.dictate.secrets.*` (green), `./gradlew :companion:compileKotlin` (green).

## Files modified

- `companion/build.gradle`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStore.kt`
- `app/src/test/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStoreTest.kt`

## Files outside assigned scope (drift)

none. All three edited files are B1 `CHUNK_FILES`. The foreign `:companion` test-compile
breakage (B1-SF4) is a concurrent agent's uncommitted work — observed, not touched.
