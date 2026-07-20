# Block C — Re-Audit of Repair Wave 2

**Mode:** re-audit · **Block:** C · **Timestamp:** 2026-07-20T00:40:00+02:00
**Re-auditor:** audit-consolidator (verify wave / re-classify / detect new problems — fixes nothing)
**Diff commit:** `d3c6e51e76c7a33e129f3e943663da21cca44224` — `[C] repair wave 2 (desktop-companion-v1)`
**Findings verified:** 2 (`C-TEST-2` yellow/Critical, `doc-drift-androidaiconfig-retired` green/Nice-to-have)

## Verdict

**Converged.** Both findings the wave targeted are fully resolved against the code at
`d3c6e51`. The wave introduced **no** new problems. `findings` array is empty.

---

## Finding 1 — `C-TEST-2` (yellow, Critical) — RESOLVED

The device-secret half of the §2.6 SecretStore invariant is now closed end-to-end, and the
guard test runs.

**`WindowsTarget.kt` — `from` split into a non-secret predicate + a store-backed resolve.**
`WindowsTarget.from(sp)` is gone. It became two members:
- `isPaired(sp): Boolean` — non-secret predicate over `Pref.WindowsTargetUrl` +
  `Pref.WindowsDeviceId`. This is the new "is a PC coupled?" gate; it never touches the
  SecretStore, so UI/state/routing stay decoupled from the crypto store. Crucially it
  reports an already-migrated user as **paired** (the old "secret present in a pref" gate
  wrongly flipped them to unpaired after the migration cleared the plaintext pref — the
  CONCRETE FAILURE 1 from the finding).
- `resolve(sp, secretStore): WindowsTarget?` — the only path that needs the secret value;
  reads it from `PairingSecrets.DEVICE_SECRET_REF`, treats `SecretStoreException` as "not
  resolvable" → `null` (never a crash).

**All main-source consumers re-pointed** (grep-confirmed: zero `WindowsTarget.from` /
`\.from(sp` matches remain in `app/src/main`):
- `DictateInputMethodService.java:2207/6975/7161` → `isPaired(sp)` (UI gating).
- `DictatePipelineService.kt:857/878/902` → `resolve(sharedPrefs, secretStore)` (dispatch,
  send, sync). A `secretStoreFactory` test seam (`@VisibleForTesting`, reset in `@After`)
  was added so the Robolectric composition tests inject an in-memory store (the real
  Keystore provider is absent under Robolectric, spec §5.4).
- `WindowsAutoSend.kt:23` → `isPaired(sp)`.
- `PipelinePrefMirror.kt:217/333` → `isPaired(sp)`; the watched-key set dropped
  `Pref.WindowsDeviceSecret.key` (a now-permanently-empty key) — it watches
  `WindowsAutoSendEnabled` + `WindowsTargetUrl` + `WindowsDeviceId` (CONCRETE FAILURE from
  `PipelinePrefMirror.kt:328` addressed).

**`WindowsPairingActivity.java` — write and clear routed through the store (CONCRETE FAILURE
2, the security regression, addressed).** `persistPairing` now `put`s the secret into
`PairingSecrets.DEVICE_SECRET_REF` **before** writing the non-secret prefs (put-before-mark
order preserves `url-present ⟺ secret-present`), returns `false` + surfaces an error dialog
if the store is unavailable, and no longer writes the secret back into the plaintext pref.
`onUnpairClicked` `delete`s from the store instead of clearing a pref. `refreshPairedState`
/ `onTestClicked` use `isPaired` / `resolve`. The plaintext re-persist path is gone.

**`SecretsMigration.kt`** now targets the shared SSoT `PairingSecrets.DEVICE_SECRET_REF`
(new `PairingSecrets.kt` object) as the migration destination; it still *names* the source
`Pref.WindowsDeviceSecret` — allow-listed by §2.6. `DictatePrefs.kt` re-documents
`WindowsDeviceSecret` as migration-source-only.

**The guard test runs and passes.** `NoLegacyKeyReadTest.secretPrefs_areReferencedOnlyIn
DefinitionAndMigration` had its `@Ignore` removed; allow-list is exactly
`{DictatePrefs.kt, SecretsMigration.kt}`. Verified:
- `grep` for all 11 secret prefs across `app/src/main` outside the allow-list → **NONE**.
- `./gradlew :app:testDebugUnitTest --tests NoLegacyKeyReadTest` → **BUILD SUCCESSFUL**.

The §2.6 invariant is now enforced by a running automated test, and a future plaintext
read/write of any secret pref fails it with the exact `file:line`.

## Finding 2 — `doc-drift-androidaiconfig-retired` (green, Nice-to-have) — RESOLVED

Both stale KDoc comments were corrected, and the broken cross-module `[AndroidAiConfig]`
doc-links (which resolved into test sources) were converted to backtick code spans:
- `AndroidAiFactory.kt` — now states the migration's non-secret parameter mirror lives in
  `PrefCompletionParameters` and the retired `AndroidAiConfig` survives only in test sources
  as the characterization baseline.
- `ProfileResolver.kt` — the "Not yet the live read path" section became "Live read path
  (C3)": `AndroidAiFactory` builds this resolver; `AndroidAiConfig` was retired to test
  sources.

`grep` confirms no code (non-comment) reference to `AndroidAiConfig` remains in `app/src/main`;
the class lives only at `app/src/test/.../ai/adapter/AndroidAiConfig.kt`.

---

## New problems introduced by the wave

None. Reviewed the wave's additions:
- New `PairingSecrets.kt` — clean SSoT object, well-documented, analogue of `ConfigSecrets`.
- `secretStoreFactory` seam — `@VisibleForTesting internal var`, production default is the
  Keystore-backed store, reset in the composition test's `@After`.
- `WindowsPairingActivity` error path references `R.string.dictate_pairing_failed_title`,
  `dictate_pairing_error_generic`, `dictate_okay` — all three exist in
  `res/values/strings.xml` (and the main sources compile, which R-references would break).

## Test evidence (this re-audit)

- `:app:testDebugUnitTest --tests NoLegacyKeyReadTest` → **SUCCESSFUL** (the newly un-ignored
  §2.6 guard).
- `:app:testDebugUnitTest` for `WindowsTargetTest`, `SecretsMigrationTest`,
  `PipelinePrefMirrorTest`, `DictatePipelineServiceCompositionTest`, `WindowsAutoSendTest`,
  `WindowsAutoSendBothProducersTest` → **SUCCESSFUL** (all suites the wave touched).

## Files inspected

`WindowsTarget.kt`, `WindowsPairingActivity.java`, `PipelinePrefMirror.kt`,
`SecretsMigration.kt`, `PairingSecrets.kt` (new), `DictatePrefs.kt`,
`DictatePipelineService.kt`, `DictateInputMethodService.java`, `WindowsAutoSend.kt`,
`NoLegacyKeyReadTest.kt`, `AndroidAiFactory.kt`, `ProfileResolver.kt`, `strings.xml`,
`AndroidAiConfig.kt` (test-source location only).
