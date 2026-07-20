# C3 — Self-Fix (fresh eyes, diff-based)

**Date:** 2026-07-20T00:40:00+02:00 · **Agent:** C3 SELF-FIX · **Wave commit:** ad41594

## What was done

Reviewed the C3 diff (settings rebuild, import/export, prompt-provenance seams, read-path flip)
against spec `entitaetenmodell-android.md` §10/§2 AK8-AK9 with the three lenses. The implementation
is complete and well-shaped; four fixes were applied, the biggest being a missing feature the diff
had already half-built: **provider deletion** existed as dead DAO methods
(`ProviderConfigDao.deleteById`, `ModelRefDao.deleteByProvider`, `ApiCredentialDao.deleteById`) and
an unused confirm string (`dictate_config_delete_provider_message`) but was wired to no UI.

## Fixes applied

| # | Fix | Files |
|---|---|---|
| 1 | **Provider deletion wired into the hub** (§10.1 CRUD completion; removes dead code): delete action on each provider row → confirm dialog → transaction deleting the provider, its model refs (`deleteByProvider`), and — when no other provider references it — its credential row + SecretStore secret. Regression test added (Robolectric, red on pre-fix code: no delete action existed). | `settings/APISettingsActivity.kt`, `settings/ApiSettingsNavigationTest.kt` |
| 2 | **Silent export no-op**: choosing a profile in the export dialog whose row vanished (`profileCatalog` → null) launched SAF anyway and then wrote nothing without feedback (and left a 0-byte document). Now toasts export-failed and skips the SAF launch. | `settings/APISettingsActivity.kt` |
| 3 | **Wrong toast strings** in `ProviderEditActivity`: SecretStore-unavailable showed "Export failed", the defensive save-first guard showed "Save". Added `dictate_config_key_store_failed` / `dictate_config_save_provider_first`. | `settings/ProviderEditActivity.kt`, `res/values/strings.xml` |
| 4 | **Doc-code mismatch**: `ProfileListMutations` kdoc claimed missing ids are "appended alphabetically"; the code (and its test `ordered_emptyOrderKeepsIncomingOrder`) keeps incoming order. Kdoc corrected. Also replaced the FQN inline `ConfigRepository(db)` in `duplicateProfile` with the activity-level `repo`. | `config/ProfileListMutations.kt`, `settings/APISettingsActivity.kt` |

## Review verdicts (no change needed)

- **Plan correctness**: §10.1-§10.6 all present; the implementer's deviations (ProfileOrder pref,
  button-based reorder, keyterms raw staying device-local, SystemPromptsActivity kept + rewired,
  onboarding entity setup, prompt-provenance stamping) are documented and defensible (D4). The
  §8.6 deterministic-id scheme in `ConfigEntitySetup` matches `ConfigEntityMigration` exactly
  (verified against the discriminator format).
- **Main-thread Room access** in the new activities relies on the app-wide
  `allowMainThreadQueries()` (verified in `DictateDatabase.kt:152`) — existing pattern, documented
  in `ActiveProfile`'s header.
- **Test quality**: CatalogImportExportTest covers D5 (no credentials in export), closure export,
  §5.3 tamper rejection, uuid-matched upsert, ADR-0024 legacy path with backfill; AK8 grep test has
  a scanner-reaches-sources guard. Concrete assertions throughout.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| C3-SF1 | Important | Provider deletion missing despite half-built support (dead DAO methods + unused string); §10.1 entity management incomplete and orphaned models/credentials impossible to remove. Fixed: hub row delete action + orphan-credential cleanup + regression test. | fixed-inline | none |
| C3-SF2 | Nice-to-have | Export dialog could launch SAF for a null `profileCatalog` and silently write nothing. Fixed with failed-toast + early return (`APISettingsActivity.kt` showExportDialog). | fixed-inline | none |
| C3-SF3 | Nice-to-have | Misleading toast strings in `ProviderEditActivity` (export-failed for key-store failure, "Save" as guard message). Fixed with two new strings. | fixed-inline | none |
| C3-SF4 | Nice-to-have | `SystemPromptsActivity` custom-text watchers now do a full profile read + `upsertProfile` (hash recompute) per keystroke on the main thread — behavior-parity with the old per-keystroke pref write, but heavier; a debounce would be a behavior change beyond this chunk's scope. | delegated | none |
| C3-SF5 | Nice-to-have | `ProviderEditActivity.fetchSuggestions` fetches once for the function selected at dialog-open; switching the function spinner does not refetch live suggestions (hardcoded union still applies, free text always works). | delegated | none |

Implementer issues C3-1 (AndroidAiConfig legacy key reads / `NoLegacyKeyReadTest` @Ignore — needs
orchestration decision) and C3-4/C3-5 remain delegated as filed; nothing in this review changes
their assessment.

## Files modified

- `app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.kt`
- `app/src/main/java/net/devemperor/dictate/settings/ProviderEditActivity.kt`
- `app/src/main/java/net/devemperor/dictate/config/ProfileListMutations.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/net/devemperor/dictate/settings/ApiSettingsNavigationTest.kt`

**Drift (files outside CHUNK_FILES):** none.

## Test run

`./gradlew :app:testDebugUnitTest --rerun-tasks` — **green**, 2492 tests, 0 failures (full app
unit suite incl. the extended navigation test with the new provider-deletion regression case).
