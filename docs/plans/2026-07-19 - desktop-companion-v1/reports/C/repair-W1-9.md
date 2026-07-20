# Repair Wave W1-9 — Block C

**Timestamp:** 2026-07-20T00:40:00+02:00
**Finding cluster:** C-TEST-4 (green, Nice-to-have)

## Finding C-TEST-4 — First-Use test-scaffold duplication

**What was duplicated:** the B2 (`SecretsMigration`) → C2 (`ConfigEntityMigration`)
startup scaffold — the `AndroidKeystoreSecretStore(FakeSharedPreferences(),
InMemoryKekProvider(), hardwareBacked = false)` store construction and the
`Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()` builder —
was rebuilt near-identically across `ConfigEntityMigrationTest`,
`ProfileResolverCharacterizationTest`, and `CatalogImportExportTest`.

**Fix applied:** extracted a single shared test helper
`app/src/test/java/net/devemperor/dictate/testutil/ConfigMigrationScenario.kt`
(new file) exposing three composable primitives:

- `realStore()` — the real-store construction (with the software-KEK seam).
- `inMemoryDb(context)` — the in-memory Room `DictateDatabase` builder.
- `runB2C2(context, sp, store, seedPrompts)` — runs `SecretsMigration` → builds
  the DB (seeding prompts before C2) → runs `ConfigEntityMigration`, returns the DB.

Composable primitives rather than one monolithic entry point were chosen
deliberately (D4, long-term-maintainable): the three call sites have **different
ordering constraints** — `ProfileResolverCharacterizationTest` must snapshot the
pre-migration pref path *before* B2 removes the key prefs, so the store is built
and the snapshot taken before `runB2C2` is invoked; `ConfigEntityMigrationTest`
tracks the DB in a field for `@After` teardown and runs the migration body twice
(idempotency) or with an unavailable store (defer path). A single `run()` that
did everything would not fit those sites without callback contortions.

### Call-site changes

- `ConfigEntityMigrationTest.kt` — `freshDb()` now delegates to
  `ConfigMigrationScenario.inMemoryDb(context)` (keeping local field-tracking +
  prompt seeding); `realStore()` delegates to `ConfigMigrationScenario.realStore()`.
  Removed now-unused imports (`Room`, `AndroidKeystoreSecretStore`,
  `InMemoryKekProvider`). The inline B2→C2 sequences stay in each test because
  their orderings vary (idempotent re-run, unavailable-store defer).
- `ProfileResolverCharacterizationTest.kt` — `migrate()` now uses
  `ConfigMigrationScenario.realStore()` + `ConfigMigrationScenario.runB2C2(...)`;
  the second inline store (empty-profile fallback test) uses `realStore()`.
  Removed now-unused imports (`Room`, `ConfigEntityMigration`,
  `AndroidKeystoreSecretStore`, `InMemoryKekProvider`, `SecretsMigration`).
- `CatalogImportExportTest.kt` — `setUp()` and the `v3RoundTrip_importIntoFreshDb`
  fresh DB now use `ConfigMigrationScenario.inMemoryDb(context)`. Removed unused
  `Room` import, added the `ConfigMigrationScenario` import.

## Tests

`./gradlew :app:testDebugUnitTest` for all three classes — **BUILD SUCCESSFUL**,
green (re-run after a concurrent edit to `CatalogImportExportTest` by another
agent; my changes compose cleanly with theirs).

## Skipped findings

None.

## Files modified

- `app/src/test/java/net/devemperor/dictate/testutil/ConfigMigrationScenario.kt` (new)
- `app/src/test/java/net/devemperor/dictate/config/ConfigEntityMigrationTest.kt`
- `app/src/test/java/net/devemperor/dictate/ai/adapter/ProfileResolverCharacterizationTest.kt`
- `app/src/test/java/net/devemperor/dictate/config/CatalogImportExportTest.kt`

## Drift

None — all edits are within the finding's listed files plus the one new shared
helper the fix requires. `ApiSettingsNavigationTest` (named in the finding's
prose but NOT in its file list) was intentionally left untouched: it uses the
`DictateDatabase.getInstance()` singleton and a `ConfigRepository`, not the
in-memory + store + migration scaffold, so it does not share this duplication.
