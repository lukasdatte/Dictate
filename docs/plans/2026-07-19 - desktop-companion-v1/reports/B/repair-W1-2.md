# Repair Wave W1-2 — Block B (SecretStore selector)

**Date:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix
**Cluster:** T2, convention-B-2 (both green / Nice-to-have, same file surface)

## Findings addressed

### convention-B-2 — navigational consistency with PlatformModule (fixed)

Both findings pointed at the same OS-detection surface, so I applied the
structural fix first. The finding offered two valid options; per D4
(long-term-better) I chose the one that matches the established ADR-0018
convention rather than the one that merely edits the KDoc.

- **File:** `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModule.kt`
- Wrapped the free top-level `detectSecretStore(configDir)` into
  `object SecretStoreModule { fun detect(configDir: Path): SecretStore }`,
  an exact parallel of `PlatformModule.detect()` — a reader navigating from
  `PlatformModule` now finds the same shape (`SecretStoreModule.detect(...)`).
  The "mirrors PlatformModule.detect()" KDoc framing is now accurate rather
  than aspirational.
- `secretFileName(handle)` stays a top-level `internal` helper in the same
  file: it is a cross-cutting helper shared by both `FileAesGcmSecretStore`
  and `DpapiSecretStore`, not part of the detect responsibility, so folding
  it into the object would have misrepresented its role.
- No production callers existed yet (`detect` is defined for the container
  wiring that lands later), so the rename touched no call sites.
- **File:** `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStore.kt`
  — updated the one stale prose reference (`detectSecretStore prevents` →
  `SecretStoreModule.detect prevents`) in the non-POSIX fallback comment so
  no dangling identifier remains.

### T2 — pin the platform-selection contract with a test (fixed)

- **File (new):** `companion/src/test/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModuleTest.kt`
- Added `SecretStoreModuleTest.detect_onNonWindowsHost_returnsFileAesGcmFallback`:
  asserts `SecretStoreModule.detect(tmp)` returns a `FileAesGcmSecretStore` on
  the non-Windows CI host, pinning the fallback branch. Mirrors the
  `TemporaryFolder` + `Assume` style already used by
  `FileAesGcmSecretStoreTest` / `DpapiSecretStoreTest`; the Windows/DPAPI
  branch is left to the block-B Windows acceptance (guarded via
  `assumeFalse(Platform.isWindows())`).

## Tests

`./gradlew :companion:test` → BUILD SUCCESSFUL. New test executed:
`tests="1" skipped="0" failures="0" errors="0"`. No regressions.

## Files modified

- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModule.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/FileAesGcmSecretStore.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/secrets/SecretStoreModuleTest.kt` (new)

## Skipped findings

None.

## Drift

- `FileAesGcmSecretStore.kt` comment update is outside the two findings' named
  files but is a direct consequence of the `convention-B-2` rename (the
  comment referenced the old free-function name). One-line prose fix, no
  behaviour change.
