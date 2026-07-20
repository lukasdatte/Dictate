# Repair Wave W1-8 — C-TEST-3

**Timestamp:** 2026-07-20T00:40:00+02:00
**Cluster:** 1 finding (green / Nice-to-have), documentation-only.

## C-TEST-3 — Double-Enum CHECK behaviour (AK4) exercised only in the un-run instrumented suite → fixed

**Finding:** Accept/reject of each new table's Double-Enum CHECK, `profile_prompts`
CASCADE, and `runMigrationsAndValidate` schema validation for v12 live only in the
instrumented `MigrationTo12Test`, which is local-only and not run in CI (established
`MigrationTo11Test` convention). The JVM `MigrationTo12MetadataTest` pins only the
11→12 version pair. So the CHECK constraints go unexecuted in every automated run.

**Suggested fix:** add the instrumented migration suite to the Phase-4.5 E2E runbook
so `MigrationTo12Test` is executed on an emulator at least once before release.

**What I did:** Edited the plan's E2E runbook
(`reports/e2e-runbook.md`), test case **TC-A1** (the C2 emulator migration case, which
already runs on the emulator via `connectedDebugAndroidTest`). The suite was implicitly
covered by the whole-suite run but never named as a required, gated step. Made it explicit:

- Added **step 5** to TC-A1 naming `MigrationTo12Test` and its exact run command
  (`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.devemperor.dictate.database.migration.MigrationTo12Test`),
  stating that this is the *only* place the CHECK accept/reject, `profile_prompts`
  CASCADE, and v12 schema validation execute, and marking it a **release gate** (green
  at least once before release). Cross-referenced the pure-JVM
  `MigrationTo12MetadataTest` limitation (version-pair pin only).
- Extended TC-A1's **Expected Result** to require `MigrationTo12Test` green across all
  six methods (prompt-row preservation + schema validation, valid-enum accept, three
  unknown-enum rejects, `profile_prompts` CASCADE).

Used the AGP `-Pandroid.testInstrumentationRunnerArguments.class=` runner-argument form
(not the JVM `--tests` filter, which `connectedDebugAndroidTest` does not support) so the
command is correct as written.

**File pointers:** `reports/e2e-runbook.md` TC-A1 (steps 5 + Expected Result).

## Skipped

None.

## New issues discovered while fixing

None.

## Tests

Not run. This wave is a documentation-only edit to a plan-scoped runbook markdown file
(`reports/e2e-runbook.md`); it touches zero source, test, or build files, so
`./gradlew test` carries no signal for this change and was intentionally not executed.
The change itself *adds* a test-execution gate (`MigrationTo12Test` on the emulator)
whose actual run is the Phase-4.5 E2E responsibility, not this repair wave.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/docs/plans/2026-07-19 - desktop-companion-v1/reports/e2e-runbook.md`

## Drift

None — the edit stays within the finding's scope (the two files named in the finding
are test files that are already correct; the fix per the finding's own suggested_fix is
the runbook, which is what was edited).
