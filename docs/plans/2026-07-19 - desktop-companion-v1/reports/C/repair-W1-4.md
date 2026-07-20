# Repair W1-4 — convention-C-6 (C3 settings label consistency)

**Timestamp:** 2026-07-20T00:40:00+02:00
**Finding:** `convention-C-6` (green, Nice-to-have) — same user-facing concept
rendered inconsistently across the C3 settings screens.

## What I did

### 1. Provider-type label in the API-settings hub subtitle

`APISettingsActivity.providerTypeName()` rendered the raw wire-enum token
(`type.name` → `OPENAI`/`CUSTOM`) in the provider-row subtitle, while the
provider editor (`ProviderEditActivity`) shows `AIProvider.displayName`
(`OpenAI`/`Custom`). Aligned the hub with the editor:

- Added import `net.devemperor.dictate.config.ConfigWireMapping.toAIProvider`.
- `providerTypeName(type)` now returns `type.toAIProvider().displayName`
  (same bridge the editor uses at `ProviderEditActivity.kt:112`), plus a
  one-line KDoc on why it is a display label, not the wire token.

File: `app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.kt`
(lines ~27 import, ~137 method).

### 2. Ambiguity spinner in the profile editor

`ProfileEditActivity.setupAmbiguitySpinner()` populated the spinner with
`ambiguityModes.map { it.name }` (raw `ALWAYS_INSERT`/`AUTO`/`ALWAYS_REVIEW`),
whereas the sibling prompt-mode spinner uses localized `getString(...)` labels.
Gave the ambiguity spinner the same treatment, reusing the existing global-setting
strings so the wording matches what the user already sees in Settings:

- `ALWAYS_INSERT` → `R.string.dictate_ambiguity_always_insert`
- `AUTO` → `R.string.dictate_ambiguity_auto`
- `ALWAYS_REVIEW` → `R.string.dictate_ambiguity_always_review`

Implemented as an exhaustive `when` mapper `ambiguityLabelRes(mode)` (extensible,
compiler-checked against `AmbiguityModeValue`) rather than an index-based array,
so adding a mode forces a label decision. `AmbiguityModeValue` and `R` were already
imported; no new imports needed.

File: `app/src/main/java/net/devemperor/dictate/settings/ProfileEditActivity.kt`
(`setupAmbiguitySpinner` + new `ambiguityLabelRes`).

No new string resources were created — the three strings already existed
(`strings.xml:165-167`).

## Tests

`./gradlew :app:testDebugUnitTest` — Kotlin compilation of the app module
(including both edited files) succeeded; 2493 tests loaded and ran.

One unrelated failure: `TranscriptionRerunJobTest > initializationError`
(`java.lang.ClassNotFoundException` at test-class init, in the `core` package).
It does not reference either settings file and is a pre-existing/parallel-run
artifact in this shared worktree (many other agents' uncommitted changes are
present); it is not caused by this fix, which only alters two label-rendering
methods.

## Skipped findings

none

## Files modified

- `app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.kt`
- `app/src/main/java/net/devemperor/dictate/settings/ProfileEditActivity.kt`

## Drift (out-of-scope edits)

none
