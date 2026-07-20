# C2 — Self-Fix (fresh eyes, diff-based)

**Chunk:** C2 · **Timestamp:** 2026-07-20T00:40:00+02:00 · **Reviewer:** self-fix (Opus, fresh eyes)
**Wave commit reviewed:** 07fee22c

## What I did (summary)

Reviewed the C2 config-entity persistence + Prefs→entity migration + `ProfileResolver` against spec
§7–§9 (AK4–AK7) with three lenses (plan correctness / code quality / test quality). Found **no
correctness bug** and **no undocumented drift**. Applied **one test-quality fix**: strengthened the
idempotency test so it actually re-executes the migration body (deterministic-id path, §8.6) instead
of only tripping the `Done`-flag early return. All C2 test classes green after the change.

## Review findings by lens

### Plan correctness — complete, deviations defensible
- §7.2/§7.3: all five tables + `prompts`-recreate present in `MIGRATION_11_12`, every finite-set
  column carries its Double-Enum `CHECK`; `profile_prompts` has the `(profile_id,pos)` PK, CASCADE FK
  and `prompt_ref` index. Matches the spec DDL verbatim.
- §8.1–8.6: order (after B2, after `PrefsMigration`), `Done`-flag idempotency, backup-first,
  per-function provider/credential/model chain, deterministic `nameUUIDFromBytes` ids, prompt-uuid
  preservation, one-transaction Default profile, flag-set-last — all present.
- §9.2/§9.3: resolver resolution + all three fallback branches (no profile / no modelRef / missing
  credential) implemented; never crashes.
- The four deviations in `C2-impl.md` (read-path flip deferred to C3; per-function chains;
  backup-after-B2; key access via `SecretsMigration.legacyKeyRef`) are each faithful to the spec's
  intent and correctly documented. Issue **C2-1** (read-path flip verification) is a legitimate
  `plan-deviation-resolved` for the C3 audit; I do not re-raise it.

### Code quality — solid, no changes needed
- Byte-equality is achieved *by construction*: the migration stores
  `AndroidAiConfig.completionParameters(...)` output (DRY — no re-implementation of §8.3), and the
  resolver re-derives through the same `ParameterRegistry` def iteration + sentinel filters. This is
  the right call and removes a whole class of drift.
- Double-Enum `xxxEnum` accessors are consistent with `docs/DATABASE-PATTERNS.md`; `ConfigSecrets` /
  `ConfigWireMapping` centralise the two drift-prone conventions (SecretRef namespace, enum bridge).
- The reverse wire mappers (`toPromptMode`/`toAmbiguityMode`) are exercised by
  `ConfigWireEnumParityTest` and needed by C3 — kept.
- Minor, not fixed (premature-optimization / clarity trade-off): `completionParameters` reads the
  active profile twice (once directly, once inside `resolve`). The resolver is called once per
  transcription, not in a loop; the redundant read is not worth obscuring the code.

### Test quality — one gap closed
- **Fixed:** `ConfigEntityMigrationTest.second run is a no-op` previously only asserted the
  `Done`-flag early return (the trivial no-op). It never proved the harder §8.6/AK7 property — that a
  *real* re-execution (as after a crash mid-run, `Done` still low) REPLACEs the same deterministic-id
  rows instead of duplicating them. Added a third phase: reset `ConfigEntityMigrationDone = 0`, re-run,
  assert `provider_configs`/`api_credentials`/`model_refs`/`profiles` counts are unchanged.
- Remaining coverage is strong: the 9-case characterization matrix (AK5), key-security + fingerprint
  (AK6), backup-first + prompt-uuid stability (AK7), mapper round-trips, enum parity, and the
  instrumented CHECK accept/reject + CASCADE (AK4, compiles; emulator-gated like `MigrationTo11Test`).

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | none raised | — | — |

## Inline fixes applied

- `app/src/test/java/net/devemperor/dictate/config/ConfigEntityMigrationTest.kt` — strengthened
  `second run is a no-op` with a forced-re-run count-stability assertion (deterministic-id idempotency,
  §8.6).

## Files modified (absolute)

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/test/java/net/devemperor/dictate/config/ConfigEntityMigrationTest.kt`

## Files outside my scope (drift)

- none.

## Test run

- `./gradlew :app:testDebugUnitTest --tests ConfigEntityMigrationTest --tests ConfigEntityMapperTest
  --tests ConfigWireEnumParityTest --tests ProfileResolverCharacterizationTest --tests
  MigrationTo12MetadataTest` → **BUILD SUCCESSFUL**, all selected classes green (incl. the
  strengthened idempotency test).
