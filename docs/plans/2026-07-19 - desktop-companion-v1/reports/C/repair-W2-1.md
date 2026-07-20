# Repair Wave W2 — Finding C-TEST-2 (WindowsDeviceSecret re-point + un-ignore NoLegacyKeyReadTest)

**Timestamp:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix (cluster W2-1)
**Finding:** C-TEST-2 (yellow, Critical) — the 11th secret pref (`Pref.WindowsDeviceSecret`) still
had three live main-source consumers, causing (1) an already-paired user to read as unpaired after
`SecretsMigration` deletes the plaintext pref, (2) `WindowsPairingActivity` re-persisting the
pairing secret in plaintext, and (3) `NoLegacyKeyReadTest` staying `@Ignore`d so the §2.6 invariant
ran in no test.

**Design followed:** research `androidaiconfig-secret-pref-retirement.md` Part 2 update (F6–F14),
which decided: split the **non-secret paired-predicate** (`WindowsTarget.isPaired`, over
url+deviceId) from the **secret-bearing send target** (`WindowsTarget.resolve(sp, SecretStore)`).

## What I did

### New SSoT + read/write seams
- **`secrets/PairingSecrets.kt` (new):** `object PairingSecrets { DEVICE_SECRET_REF =
  SecretRef("pairing", "windows_device_secret") }` — `@JvmField` so Java reads it. The one handle
  the migration write, the target read, and the pairing write/clear all share (F8).
- **`preferences/WindowsTarget.kt`:** removed `from(sp)`. Added `isPaired(sp): Boolean` (non-secret
  predicate over `WindowsTargetUrl` + `WindowsDeviceId`) and `resolve(sp, secretStore):
  WindowsTarget?` (reads the secret from `PairingSecrets.DEVICE_SECRET_REF`; returns null when not
  paired or the secret is absent/undecryptable — catches `SecretStoreException`, ADR-0017 "pair
  again"). Rewrote the class KDoc.
- **`secrets/SecretsMigration.kt`:** the `WindowsDeviceSecret` slot's **destination** ref now points
  at `PairingSecrets.DEVICE_SECRET_REF` (SSoT). Dropped the now-unused `PAIRING_NAMESPACE` const.
  The source pref is still named here — this file is allow-listed (§2.6).

### Re-pointed the 9 predicate sites → `isPaired`
`state/PipelinePrefMirror.kt` (init snapshot + change-sync; also dropped `Pref.WindowsDeviceSecret.key`
from the pairing `when` arm and updated the comment), `windows/WindowsAutoSend.kt`,
`settings/PreferencesFragment.java`, `core/StartPcDictationActivity.kt`,
`core/DictateInputMethodService.java` (×3: history-panel gate, PC long-press, history-send gate).

### Re-pointed the 4 secret sites → `resolve`
`core/DictatePipelineService.kt`: added a companion test-seam `secretStoreFactory: (Context) ->
SecretStore` (default = `AndroidKeystoreSecretStore.create`), built `val secretStore =
secretStoreFactory(this)` once in `onCreate`, and threaded it through the coordinator
`targetProvider`, the `PcInputCoordinator.send` resolve, and the app-start sync (F11).
`settings/WindowsPairingActivity.java`: `onTestClicked` now resolves via the store.

### Write seam (`WindowsPairingActivity.java`)
- `persistPairing` now returns `boolean`: stores the secret via `AndroidKeystoreSecretStore.create`
  **first** (secret-before-url, mirroring the migration's put-before-mark), then writes only the
  non-secret prefs (`WindowsTargetUrl`/`WindowsDeviceId`/`WindowsServerName`). On store
  unavailable / put failure it writes nothing and returns false; the pairing success handler then
  shows the generic pairing error instead of a false "paired" (keeps `url-present ⟺ secret-present`).
  Catches `Exception` (the Kotlin `SecretStoreException` has no `@Throws`, so Java cannot catch the
  specific checked type — documented inline).
- `onUnpairClicked` deletes the secret from the store and clears the url (which flips `isPaired`);
  keeps deviceId for stable re-pair identity.
- `refreshPairedState` uses `isPaired` + the non-secret `WindowsServerName` for the display name.

### Doc-only
`state/DictateUiState.kt` `windowsPaired` KDoc, `preferences/DictatePrefs.kt`
(`WindowsAutoSendEnabled` / `WindowsTargetUrl` / `WindowsDeviceSecret` KDocs — the secret is now
documented migration-only).

### Tests
- **`testutil/FakeSecretStore.kt` (new):** promoted the private fake from `SecretsMigrationTest` to a
  reusable pure-JVM `SecretStore` (available / hardwareBacked / failOn).
- **`NoLegacyKeyReadTest.kt`:** removed `@Ignore` and rewrote the KDoc — the guard now runs and is
  green, proving §2.6 end-to-end (all 11 secret prefs referenced only in the allow-list).
- **`WindowsTargetTest.kt`:** rewritten around `isPaired`/`resolve` (incl. the migrated-user case:
  paired with url+deviceId and no secret pref; resolve-null on missing/undecryptable secret).
- **`SecretsMigrationTest.kt`:** removed the private fake (imports the testutil one); added
  `pairingSecretRef_matchesTheMigrationSlot` and the migration-survival regression
  (`pairedUserSurvivesMigration_isPairedStaysTrue_resolveReadsMigratedSecret`).
- **`PipelinePrefMirrorTest.kt`**, **`WindowsAutoSendTest.kt`**,
  **`WindowsAutoSendBothProducersTest.kt`:** paired fixtures now seed url+deviceId (not the secret);
  the unpair-recompute case drives `WindowsTargetUrl.key`.
- **`DictatePipelineServiceCompositionTest.kt`:** the two paired app-start-sync tests inject a
  seeded `FakeSecretStore` via `secretStoreFactory` and drop the secret-pref seed; `@After` resets
  the factory.

## Regression coverage (red-before-green intent)
`WindowsTargetTest."isPaired is true … even when no secret pref exists"` and
`SecretsMigrationTest.pairedUserSurvivesMigration_…` both fail on the pre-fix code (old `from`
required the secret pref → null → treated unpaired) and pass now.

## Verification
- `./gradlew :app:compileDebugUnitTestSources` — BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL; the un-ignored
  `secretPrefs_areReferencedOnlyInDefinitionAndMigration` ran (0.1 s, not skipped) and passed, as
  did all new/updated tests.

## Skipped findings
None — the single finding was fully addressed.

## Files modified
See the structured return. All are under `app/`.

## Drift (edits outside the finding's named files)
The finding named 5 files; the decided design (research F6–F14) required the full re-point across
all 13 call sites + the SSoT + the send-path test seam. Beyond the 5 named files I therefore also
edited (each load-bearing for the invariant, not scope-creep):
`PipelinePrefMirror.kt`, `WindowsAutoSend.kt`, `PreferencesFragment.java`,
`StartPcDictationActivity.kt`, `DictateInputMethodService.java`, `DictatePipelineService.kt`,
`DictateUiState.kt`, `DictatePrefs.kt`, `SecretsMigration.kt` + new `PairingSecrets.kt`, plus the
test files above + new `testutil/FakeSecretStore.kt`.

**Observed (NOT mine):** the worktree already had uncommitted changes from earlier waves in
`ai/adapter/AndroidAiFactory.kt`, `ai/adapter/ProfileResolver.kt`,
`companion/.../JavaSoundAudioCaptureService.kt`, `shared/.../CanonicalJsonTest.kt`. I did not touch
these; they are excluded from my file-scoped commit list.
