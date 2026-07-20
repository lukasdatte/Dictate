# Repair W1-10 — C-TEST-2 / C3-1 (AndroidAiConfig secret-pref retirement)

**Timestamp:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix (cluster W1-10)
**Finding:** C-TEST-2 (dedup of C3-1) — `NoLegacyKeyReadTest.secretPrefs_areReferencedOnlyInDefinitionAndMigration` stays `@Ignore`d because main still references the secret prefs, so spec secretstore.md §2.6 is enforced by no running test.

## What I did

Followed the research `androidaiconfig-secret-pref-retirement.md` verdict: **retire `AndroidAiConfig` from main** (Part 1) — the C3-1 "retire vs. widen the allow-list" decision, resolved in favour of retire (the sustainable end-state; widening would enshrine a runtime `AiConfig` that reads plaintext key prefs). Part 2 (the `WindowsDeviceSecret` re-point) is a separately-scoped latent Critical bug touching three files outside this cluster and needing a design decision — **escalated, not applied** (see New issues). The guard therefore **stays `@Ignore`d**, but its reason string + KDoc are rewritten to name the real, narrowed blocker.

### Part 1 — AndroidAiConfig retirement (applied)

1. **Extracted the non-secret completion-parameter reader into main**: new `app/src/main/java/net/devemperor/dictate/ai/adapter/PrefCompletionParameters.kt` (`object` with `of(sp, provider, model)`), holding the former `AndroidAiConfig.PARAMETER_PREFS` + `completionParameters` body verbatim. These prefs (`Temperature*`/`MaxTokens*`/`ReasoningEffort*`) are non-secret, so they live in main freely (SRP: "read completion params from legacy prefs").
2. **Re-pointed the last main-source consumer**: `ConfigEntityMigration.parameterDefaults` (`:220`) now calls `PrefCompletionParameters.of(sp, provider, model)`; dropped `import …AndroidAiConfig`; updated the method KDoc.
3. **Moved `AndroidAiConfig.kt` main → test** via `git mv` to `app/src/test/java/net/devemperor/dictate/ai/adapter/AndroidAiConfig.kt` (same package `net.devemperor.dictate.ai.adapter`, identical FQN → the ~7 test consumers need **no import change**). Its `completionParameters` now **delegates** to `PrefCompletionParameters.of`, so `ParameterResolutionParityTest` stays a real test of the extracted main helper and the code stays DRY. Removed the now-dead `PARAMETER_PREFS` companion + `ParameterRegistry` import from the fixture. Rewrote the class KDoc to mark it the test-only frozen baseline (with a "do not re-introduce into main" gotcha).

Effect: the **10 API-key** secret prefs are no longer referenced anywhere in `src/main/java` outside the allow-list.

### `@Ignore` handling (kept, narrowed — not removed)

`NoLegacyKeyReadTest.kt`: kept `@Ignore` (removing it now would make the guard **red** — see below) but rewrote the reason string and class KDoc so the pending marker points at the precise remaining gap: the **`WindowsDeviceSecret`** slot, still referenced by three main files. This is the honest post-Part-1 state (the API-key half is done; the misleading "C2/C3 re-point reads/writes" wording is gone).

## Why the guard cannot be un-ignored yet (verified)

`grep` of `src/main/java` for the 11 secret prefs outside the allow-list, after Part 1:

```
state/PipelinePrefMirror.kt:328        Pref.WindowsDeviceSecret.key      (change-watch for windowsPaired)
preferences/WindowsTarget.kt:39        sp.get(Pref.WindowsDeviceSecret)  (builds the send target)
settings/WindowsPairingActivity.java:218/286  put(Pref.WindowsDeviceSecret) (writes the secret back to plaintext prefs)
```

`SecretsMigration` (live via `DictateApplication`) already deletes `Pref.WindowsDeviceSecret` on every start and parks it in the store, but no reader/writer was re-pointed — so removing `@Ignore` would fail the assertion on these three lines. Keeping `@Ignore` is required by repair-fix rule 3 (green before finish).

## Tests

`./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL** (full app unit suite green, incl. `AiConfigParityTest`, `ParameterResolutionParityTest`, `ProfileResolverCharacterizationTest`, and `NoLegacyKeyReadTest.theScanner_readsSourcesAndCanMatch`). `:app:compileDebugKotlin` green (validates the main-side re-point independently).

Note: an intermediate run showed widespread transient `initializationError`s ("Detected multiple Kotlin daemon sessions") from concurrent gradle invocations by parallel fixers; no failure/error result XMLs were produced and a clean re-run was fully green. Not attributable to this change.

## New issues discovered (escalate — out of this cluster's scope)

| ID | Severity | Description | Status | Marker |
|---|---|---|---|---|
| C-TEST-2-WDS | Critical | `WindowsDeviceSecret` is deleted by the live `SecretsMigration` but never re-pointed: `WindowsTarget.from` reads the now-empty pref → a previously-paired user is treated as **unpaired** (PC-dictation send disabled — regression in this plan's own feature); `WindowsPairingActivity` still **writes the secret into plaintext prefs**, defeating Block-B "no plaintext secret at rest"; `PipelinePrefMirror` watches a pref that no longer holds the value. Fix needs a `SecretStore` read/write seam for `WindowsTarget`/`WindowsPairingActivity` plus a **non-secret** `paired?` predicate (over `WindowsTargetUrl`+`WindowsDeviceId`) for the reactive mirror — a design decision (possibly a short spec/ADR note), spec secretstore.md §7.2. Gates removal of the `NoLegacyKeyReadTest` `@Ignore`. | delegated | blocks-following |

Detail + implementation shape: research `androidaiconfig-secret-pref-retirement.md` Part 2.

## Files modified

- `app/src/main/java/net/devemperor/dictate/ai/adapter/PrefCompletionParameters.kt` (new)
- `app/src/main/java/net/devemperor/dictate/config/ConfigEntityMigration.kt` (re-point + import + method KDoc)
- `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiConfig.kt` (deleted — moved)
- `app/src/test/java/net/devemperor/dictate/ai/adapter/AndroidAiConfig.kt` (moved here; delegate + KDoc + dead-code removal)
- `app/src/test/java/net/devemperor/dictate/secrets/NoLegacyKeyReadTest.kt` (narrowed `@Ignore` reason + KDoc)

## Drift (outside the finding's stated file list)

- `ConfigEntityMigration.kt` — re-pointing the sole functional consumer was a required co-edit of retiring `AndroidAiConfig` from main (otherwise main would not compile). One line + one import + one KDoc line. **Concurrency note:** a parallel fixer also edited this file (imports/KDoc, unrelated to line 220); my three edits are disjoint and both sets coexist (verified green).
- `PrefCompletionParameters.kt` (new main file) — the SRP extraction that lets the non-secret param reader stay in main while the secret-reading fixture leaves. Necessary producer of the fix.
- Rename deferral: kept the fixture named `AndroidAiConfig` (research floated `LegacyPrefAiConfig`) — a rename ripples to ~7 test imports for churn only; the KDoc now states it is the test-only frozen baseline, which is the stated minimum. Left as optional future cleanup to keep the diff tight.
