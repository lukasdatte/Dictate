# Block C — Re-audit of repair wave 1

**Mode:** re-audit · **Block:** C · **Timestamp:** 2026-07-20T00:40:00+02:00
**Diff verified:** `9b2038ce7b09a8c2ce546e51f7766abbe8f177c3` — `[C] repair wave 1 (desktop-companion-v1)`
**Consolidator:** audit-consolidator (verify the wave — fixes nothing)
**Inputs:** 13 consolidated findings from the initial Block-C audit (11 green + 2 yellow).

## Verdict

**Converged on 12 of 13.** The wave cleanly resolved all 11 green findings and one of the
two yellows (`logic-C-1`). It compiles and the affected `:app` + `:shared` unit suites are
green (`./gradlew :shared:test :app:testDebugUnitTest --tests config.* secrets.* ai.adapter.*
shared.config.*` → BUILD SUCCESSFUL). **One yellow (`C-TEST-2`) is only partially resolved**
and — now that its root cause is traced end-to-end — is re-classified upward to a live
**Critical** runtime + security regression. One **minor new doc-drift** item was introduced
by the wave.

## Resolved by the wave (dropped)

| # | Finding | Evidence in the diff |
|---|---|---|
| G1 | `enum-parity-aifunction-modelfunction` | `AIFunction.toWire()`/`ModelFunction.toAIFunction()` added to `ConfigWireMapping`; both inline `if`s at `ConfigEntityMigration.kt:169` / `ConfigEntitySetup.kt:95` now call `function.toWire()`; `ConfigWireEnumParityTest` gains the name-set-equality + round-trip cases. |
| G2 | `convention-C-1` (canonical decimal ×2) | Single `ConfigEntityMapper.canonicalDecimal(Float)`; `ConfigEntityMigration` and `ParameterMapEditor` both route through it; the two private copies deleted. |
| G3 | `convention-C-3` (`ProfileRoomEntity`) | String-literal defaults replaced with `PromptSelectionMode.PREDEFINED.name` / `AmbiguityModeValue.ALWAYS_INSERT.name`; `stylePromptModeEnum`/`systemPromptModeEnum`/`ambiguityModeEnum` accessors added; mapper's private parse helpers removed. |
| G4 | `convention-C-4` (`SourceRef` guard ×2) | New `internal fun sourceRefOrNull(...)` in `SourceRefMapping.kt`; `ConfigEntityMapper` (all 4 `toDto`) and `CatalogExport.toPromptDto` both call it. |
| G5 | `convention-C-5` (charset) | `ConfigEntitySetup` now `Charsets.UTF_8`; dead `StandardCharsets` import removed from `ConfigEntityMigration`. |
| G6 | `convention-C-6` (raw enum labels) | Hub subtitle renders `type.toAIProvider().displayName`; ambiguity spinner uses localized `ambiguityLabelRes(...)`. |
| G7 | `logic-C-2` (silent keyterms no-op) | `setTranscriptionKeyterms` now returns `Boolean` (`false` = no active transcription ModelRef), documented. |
| G8 | `logic-C-3` (`pos = count()` collision) | New `PromptDao.nextPos()` = `COALESCE(MAX(pos),-1)+1`; both `upsertPromptRow` and `appendLegacyPrompts` use it. |
| G9 | `C-TEST-1` (no `PromptProvenance` regression test) | New `PromptProvenanceTest` (5 cases): `stamped` mint-when-empty / preserve / re-hash, `edited` re-hash + keep uuid+peer, `localCopy` clears + fresh uuid. |
| G10 | `C-TEST-3` (CHECK constraints un-run) | `e2e-runbook.md` §step-5 adds `MigrationTo12Test` as an explicit emulator release gate (all six methods). |
| G11 | `C-TEST-4` (dup startup scaffold) | New `testutil/ConfigMigrationScenario`; used by `ConfigEntityMigrationTest`, `ProfileResolverCharacterizationTest`, `CatalogImportExportTest`. |
| Y1 | `logic-C-1` (forward-compat hash-recompute) | Resolution (b) chosen: `importV3` §5.3 check recomputes over the **raw** re-parsed `JsonElement` via new `contentHashOfElement` / `CanonicalJson.canonicalString(JsonElement)`; unknown additive keys now survive into the hash. New `ContentHashTest` cases pin same-version equivalence + additive-key divergence. Sound and tested. |

## Still needs fixing

### C-TEST-2 — WindowsDeviceSecret consumers not re-pointed → live runtime + security regression (was Important yellow → **Critical yellow**)

**Files:** `preferences/WindowsTarget.kt:39`, `settings/WindowsPairingActivity.java:218,286`,
`state/PipelinePrefMirror.kt:328`, `secrets/NoLegacyKeyReadTest.kt` (still `@Ignore`d),
seam side: `secrets/SecretsMigration.kt` (`pairing`/`windows_device_secret` ref),
`ai/secrets/SecretStore`.

**Wave progress:** the API-key half is genuinely done — `AndroidAiConfig` was retired from
`src/main` to `src/test` and its non-secret parameter mirror extracted to
`PrefCompletionParameters`, so the 10 API-key prefs no longer appear outside the
`{DictatePrefs.kt, SecretsMigration.kt}` allow-list. But `NoLegacyKeyReadTest` is **still
`@Ignore`d**, so the §2.6 invariant runs in no automated test — the original finding's core
claim is unchanged.

**Why re-classified to Critical (root cause now traced live):** `SecretsMigration.run` is
wired live (`PrefsMigration.migrateSecrets` → app start) and, for **every** slot including
`Pref.WindowsDeviceSecret`, does `put`-to-store then `sp.edit().remove(key)`
(`SecretsMigration.kt:175`). It moves the pairing secret to `SecretRef("pairing",
"windows_device_secret")` and **clears the plaintext pref**. But three main-source consumers
still read/write that pref directly, and `WindowsTarget.from(sp)` (`WindowsTarget.kt:36-40`)
returns `null` on an empty `deviceSecret`:

- **Runtime regression (reachable):** `WindowsTarget.from(sp) != null` is the single
  "is a PC paired?" gate across the whole live send path — `DictatePipelineService.kt:851/872/896`
  (actual dispatch), `DictateInputMethodService.java:2207/6975/7161` (UI gating),
  `WindowsAutoSend.kt:23`, `PipelinePrefMirror.kt:217/333`, `PreferencesFragment`,
  `StartPcDictationActivity`. After the migration clears the pref, an already-paired user's
  companion silently reads as **unpaired** and dispatch is gated off.
- **Security regression:** `WindowsPairingActivity.java:218` still **writes** a fresh pairing
  secret back into the plaintext pref (and `SecretsMigratedV1` is already set, so it is never
  migrated) — re-persisting the pairing secret in plaintext, defeating the §2.6 SecretStore
  invariant for this slot.
- `PipelinePrefMirror.kt:328` watches the now-permanently-empty `WindowsDeviceSecret` key.

`SecretsMigration.kt:49-53` explicitly assigns this re-pointing to **C2 (ProfileResolver) /
C3 (UI)** — i.e. it is in Block C's remit and was missed. The repair author independently
flagged it as a Critical finding in the updated `NoLegacyKeyReadTest` header.

**Why yellow:** not mechanical. Needs a `SecretStore` read seam for `WindowsTarget` (today a
pure prefs-read data class), a write seam for the Java `WindowsPairingActivity`, and a
**non-secret `paired?` predicate** for the `PipelinePrefMirror` watch (the secret pref no
longer exists to observe) — plus handling the already-migrated + already-flagged state (spec
secretstore.md §7.2; research `androidaiconfig-secret-pref-retirement.md` Part 2). Only after
all three consumers go through the store may the `@Ignore` be removed.

### doc-drift-androidaiconfig-retired — stale `[AndroidAiConfig]` KDoc after retirement (new, Nice-to-have)

**Files:** `ai/adapter/AndroidAiFactory.kt:19-20`, `ai/adapter/ProfileResolver.kt:37`.

The wave deleted `AndroidAiConfig` from `src/main` (moved to `src/test`) but left main-source
KDoc claiming it "remains only as the migration's parameter mirror" (`AndroidAiFactory`) and
"AndroidAiFactory still builds `AndroidAiConfig`" (`ProfileResolver`). Both are now false —
the parameter mirror is `PrefCompletionParameters`, and the factory builds `ProfileResolver`.
KDoc `[AndroidAiConfig]` links from main files now resolve into test sources. **Not a build
break** (KDoc is not compiled; suites are green) — documentation drift only. Fix: update the
two comments to point at `PrefCompletionParameters` / the test-source characterization
baseline.

## New problems introduced by the wave

Only the Nice-to-have doc drift above. No broken imports, no behavior change beyond the
intended fixes, no convention regressions; `AndroidAiConfig` has no remaining *code*
reference in `src/main` (only KDoc links); build + affected unit suites green.

## Convergence

12 / 13 resolved. Remaining: 1 Critical yellow (`C-TEST-2`, re-pointing the
`WindowsDeviceSecret` consumers + un-`@Ignore` the guard) + 1 Nice-to-have green doc-drift
cleanup.
