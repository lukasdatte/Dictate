# Repair Wave W1-5 — Report

**Timestamp:** 2026-07-20T00:40:00+02:00
**Finding cluster:** T1 (green, Nice-to-have) — cross-chunk test-helper duplication in the `companion` data-package tests.

## Finding T1 — fixed

**What was duplicated:**
- `assertCheckFailure { }` — verbatim in `CompanionSchemaParityTest` and `ConfigEntityCheckParityTest`.
- `Iterable<Enum<*>>.names()` — same two files.
- `SqlDriver.exec(sql)` — all four data-package tests (`CompanionSchemaParityTest`, `ConfigEntityCheckParityTest`, `ReceivedTextsAblationMigrationTest`, `SchemaMigratorTest`).

**Fix:** Extracted all three into one support file at the project's test-helper home (`fakes/`, per `CONVENTIONS.test_helpers_location`):
`companion/src/test/kotlin/net/devemperor/dictate/companion/fakes/SqlCheckSupport.kt` — three top-level functions (`SqlDriver.exec`, `Iterable<Enum<*>>.names`, `assertCheckFailure`) with a module-header comment explaining why the single copy matters (no silent drift of the "CHECK constraint failed" matcher between parity suites).

**Consumers updated:**
- `CompanionSchemaParityTest.kt` — added the three `fakes.*` imports, removed the three local helpers, dropped the now-unused `org.junit.Assert.assertTrue` import.
- `ConfigEntityCheckParityTest.kt` — same (imports + removed helpers + dropped unused `assertTrue`).
- `ReceivedTextsAblationMigrationTest.kt` — added `fakes.exec` import, removed local `exec` (only `exec` was used here).
- `SchemaMigratorTest.kt` — added `fakes.exec` import, removed local `exec` (only `exec` was used here).

The extracted `assertCheckFailure`/`names`/`exec` are byte-for-byte the same behaviour as the copies they replace; no assertion semantics changed.

## Tests

`./gradlew :companion:test` — BUILD SUCCESSFUL. `:companion:compileTestKotlin` recompiled (the refactor is test-only) and the full companion test suite passed.

## Self-check

Re-read the diff once: the finding is fully addressed (single home, four consumers importing it, zero remaining local copies); imports are sound (`SqlDriver`/`JdbcSqliteDriver` still referenced where the driver field/params need them, only genuinely-orphaned `assertTrue` imports removed); no regressions (suite green).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/fakes/SqlCheckSupport.kt` (new)
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/data/CompanionSchemaParityTest.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/data/ConfigEntityCheckParityTest.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/data/ReceivedTextsAblationMigrationTest.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/data/SchemaMigratorTest.kt`

## Drift

none
