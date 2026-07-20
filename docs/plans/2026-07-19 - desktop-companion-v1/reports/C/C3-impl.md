# C3 — Android-UI-Umbau auf das Entitätenmodell (IMPL+TEST)

**Date:** 2026-07-20 · **Agent:** C3 IMPL+TEST · **Spec:** `research/entitaetenmodell-android.md` §10 (+§2 AK8/AK9)

## What was done

Rebuilt the settings surface entity-based (Provider → Modelle → Profile): `APISettingsActivity`
(Java, 783 lines, pref-writing) was replaced by a Kotlin hub + two editors writing exclusively via
`ConfigRepository`; the v1/v2/v3 import dispatcher (§10.4), the credential-free v3 SAF export
(§10.5) and the PromptsOverview origin badge (§10.6) were added. The live AI read path was flipped
atomically with the write paths: `AndroidAiFactory` now wires `ProfileResolver` +
`ProfilePromptConfig`, the IME ambiguity read and `SystemPromptsActivity`/onboarding write the
active profile/entities.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Profile display order stored in device-local `Pref.ProfileOrder` (comma-joined ids) | Spec §10.3 "verschieben (Reorder)" | v12 `profiles` has no `pos` column (C2 schema frozen); order is a pref merged via `ProfileListMutations.ordered` | A `pos` payload column would pollute `contentHash` (same reasoning as D4 `is_active`); no schema change needed | None — order is device-local UI state, never exported | ✓ (issue C3-2, `plan-deviation-resolved`) |
| Reorder via up/down buttons, not drag (ItemTouchHelper) | Spec §10.3 "analog PromptsOverview" | Copy/order arithmetic shared + unit-tested (`ProfileListMutations`), gesture simplified | Hub hosts two lists in one ScrollView; drag inside nested lists is fragile; semantics identical | None (pure UX) | △ (issue C3-5) |
| `Pref.ElevenLabsKeytermsRaw` stays a device-local pref | Spec §3.1 (Raw → ModelRef) | Only the **parsed** JSON goes to `ModelRef.parameterDefaults["keyterms"]`; raw text (incl. comments) remains editor state | Raw text with comments in the shareable payload would hash-pollute the model ref | None; grep test documents the exception | ✓ |
| Keyterms editing stays in `SystemPromptsActivity` (active profile), not in the model dialog | Spec §10.2 | Rewired to write the active transcription ModelRef | Avoids duplicating the validating keyterms editor; same data destination | None | △ |
| Style/system prompt editable in BOTH `ProfileEditActivity` (any profile) and `SystemPromptsActivity` (active profile) | Spec §10.3 | SystemPromptsActivity kept (rewired) instead of deleted | Preserves established UX + help links; both write the same entity via `ConfigRepository` | None | ✓ |
| Onboarding writes entities via new `ConfigEntitySetup` | Spec §10 (implicit) | First-run key now creates credential/provider/model/Default-profile rows with the §8.6 deterministic ids | Onboarding runs after the migration already ran (empty), so pref writes would be dead | None — same ids as migration, idempotent | ✓ |
| Prompt write seams stamp uuid/content_hash (`PromptProvenance`) | Spec §7.3/§8.5 | `PromptEditActivity` insert/update, `PromptListMutations.copyOf`, PromptsOverview legacy import now backfill/keep uuid + hash | Post-migration prompt writes otherwise reset the v12 identity columns (update path rebuilt rows with the 7-arg constructor) | Positive — Block E relies on stable prompt uuids | ✓ (issue C3-3) |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| C3-1 | Important | `NoLegacyKeyReadTest` stays `@Ignore`d: `ai/adapter/AndroidAiConfig.kt:40-61` still references the 11 secret pref constants. It is no longer on the live path (factory flipped) but is required by `ConfigEntityMigration.parameterDefaults` and the A3/C2 parity + characterization tests. Needs an orchestration decision: retire AndroidAiConfig's key reads (move baseline into test sources) or extend the test's allow-list per spec secretstore.md §2.6. | delegated | none |
| C3-2 | Important | Profile ordering has no schema home in v12; implemented as device-local `Pref.ProfileOrder` + `ProfileListMutations` (see deviation) — audit should verify this call. | fixed-inline | plan-deviation-resolved |
| C3-3 | Important | C2 gap closed: prompt UI writes reset v12 uuid/provenance columns (e.g. `PromptEditActivity` update path). Fixed via `config/PromptProvenance.kt` at all three write seams. | fixed-inline | plan-deviation-resolved |
| C3-4 | Nice-to-have | Provider editor supports "new key" or "keep current" but no picker to reference another existing credential (spec §10.1 optional path). | delegated | none |
| C3-5 | Nice-to-have | Profile reorder is button-based, not drag-based (UX only; mutations shared + tested). | delegated | none |

## Inline fixes applied

- `EdgeToEdge.enable` → `enableEdgeToEdge()` (Kotlin extension; the Java-visible static resolves only from Java).
- Add-model button disabled until a new provider row is saved (models FK-reference the provider id).
- `ConfigEntityMigration.fingerprint` made `internal` and reused by `ConfigEntitySetup` (no drift).

## Integration call sites (Phase B #5)

- `ai/adapter/AndroidAiFactory.kt:29-42` — orchestrator/prompt-service now built on `ProfileResolver` / `ProfilePromptConfig` (the C3 flip the C2 docs promised).
- `core/DictatePipelineService.kt:397-398` — passes `this` context into the new factory signatures.
- `core/DictateInputMethodService.java:5680-5683` — `currentAmbiguityMode()` reads `ActiveProfile.ambiguityMode(sp, dictateDb)`.
- `settings/APISettingsActivity.kt` — **INTEGRATION_TARGET**: the old `.java` is deleted, replaced by the Kotlin rebuild (diff shows delete + add on the same logical unit).
- `onboarding/OnboardingAdapter.java:178-183` — `ConfigEntitySetup.applyOnboardingKey`.

## Files

**New:** `config/{ProfileListMutations,CatalogExport,CatalogImport,ActiveProfile,ConfigEntitySetup,PromptProvenance}.kt`, `ai/adapter/ProfilePromptConfig.kt`, `settings/{APISettingsActivity,ProviderEditActivity,ProfileEditActivity,ParameterMapEditor}.kt`, layouts `activity_api_settings.xml` (rewrite), `activity_provider_edit.xml`, `activity_profile_edit.xml`, `item_config_row.xml`, `menu/menu_api_settings.xml`; tests `config/{ProfileListMutationsTest,CatalogImportExportTest}.kt`, `settings/{ApiSettingsNavigationTest,NoMigratedPrefUiReferenceTest}.kt`.
**Modified:** `config/dao/ConfigDaos.kt` (delete queries), `config/ConfigEntityMigration.kt` (fingerprint internal), `preferences/DictatePrefs.kt` (Pref.ProfileOrder), `ai/adapter/AndroidAiFactory.kt`, `core/DictatePipelineService.kt`, `core/DictateInputMethodService.java`, `settings/SystemPromptsActivity.java`, `onboarding/OnboardingAdapter.java`, `rewording/{PromptEditActivity,PromptsOverviewActivity,PromptsOverviewAdapter}.java`, `rewording/PromptListMutations.kt`, `res/values/strings.xml`, `res/layout/item_prompts_overview.xml`, `AndroidManifest.xml`.
**Deleted:** `settings/APISettingsActivity.java` (replaced by Kotlin rebuild).

**Drift (files outside assigned scope):** `core/DictatePipelineService.kt`, `core/DictateInputMethodService.java`, `onboarding/OnboardingAdapter.java`, `ai/adapter/AndroidAiFactory.kt` — each edit is the read/write-path flip C2 explicitly deferred to C3 ("flipped together with the settings write paths in C3", ProfileResolver.kt:37-38 / DictateApplication.java:82); `rewording/*` prompt-seam stamping closes the §7.3 uuid-stability requirement the rebuilt profile editor depends on.

## Test run

`./gradlew :app:testDebugUnitTest` — **green** (full app unit suite incl. the 4 new test classes,
ProfileResolver characterization, ADR-0024 prompt tests, ConfigEntityMigrationTest).
`./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac` — green.
Manual E2E (TC-A3, `reports/e2e-runbook.md`) is a Phase-4.5 item, not run here.

## Helper decisions

- `ParameterMapEditor` (settings) — one canonical-string map editor for both ModelRef defaults and
  Profile overrides; replaces the PARAM_PREFS pref map entirely.
- `PromptProvenance` (config) — recompute-on-write for the legacy `prompts` table, mirroring
  `ConfigRepository`'s §5.3 invariant.
- `ActiveProfile` (config) — Java-friendly active-profile reads/writes for IME +
  SystemPromptsActivity.
