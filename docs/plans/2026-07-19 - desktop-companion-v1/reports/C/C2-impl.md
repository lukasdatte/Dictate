# C2 — Android-Persistenz (Room v11→v12) + Prefs→Entitäten-Migration + Profil-Resolver

**Chunk:** C2 · **Timestamp:** 2026-07-20T00:40:00+02:00 · **Agent:** groundwork (Opus)

## What I did (summary)

Implemented the full config-entity persistence + migration layer (spec `entitaetenmodell-android.md`
§7–9): Room v11→v12 (five new tables + `prompts` recreate, all Double-Enum CHECKs), the Room
classes/DAOs/mapper/`ConfigRepository` write path, the one-time `ConfigEntityMigration` (backup →
deterministic entities → Default profile in one transaction → SecretStore legacy→credential re-map),
and the `ProfileResolver` (`AiConfig`) with a byte-equality characterization proof against the
pref-based path. Wired the migration into `DictateApplication` startup. All 2468 `:app` unit tests
green (0 failures), plus 27 new C2 tests.

## Files created (absolute)

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/config/entity/ConfigRoomEntities.kt` — 5 Room entities (Double-Enum accessors)
- `.../app/src/main/java/net/devemperor/dictate/config/dao/ConfigDaos.kt` — 4 DAOs
- `.../app/src/main/java/net/devemperor/dictate/config/ConfigEntityMapper.kt` — Room ⇄ `:shared` DTO
- `.../app/src/main/java/net/devemperor/dictate/config/ConfigWireMapping.kt` — domain↔wire enum bridge
- `.../app/src/main/java/net/devemperor/dictate/config/ConfigRepository.kt` — single write path (hash+updatedAt recompute)
- `.../app/src/main/java/net/devemperor/dictate/config/ConfigSecrets.kt` — `credential` SecretRef convention
- `.../app/src/main/java/net/devemperor/dictate/config/PrefsBackup.kt` — §8.4 rollback dump (write-once)
- `.../app/src/main/java/net/devemperor/dictate/config/PromptHashing.kt` — prompt content-hash projection
- `.../app/src/main/java/net/devemperor/dictate/config/ConfigEntityMigration.kt` — §8 migration
- `.../app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo12.kt` — Room v11→v12
- `.../app/src/main/java/net/devemperor/dictate/ai/adapter/ProfileResolver.kt` — §9 `AiConfig`
- `.../app/schemas/net.devemperor.dictate.database.DictateDatabase/12.json` — exported schema (KSP)
- `.../app/src/androidTest/java/net/devemperor/dictate/database/migration/MigrationTo12Test.kt` — instrumented CHECK accept/reject + CASCADE (AK4)
- `.../app/src/test/java/net/devemperor/dictate/database/migration/MigrationTo12MetadataTest.kt`
- `.../app/src/test/java/net/devemperor/dictate/config/ConfigEntityMigrationTest.kt` — backup/idempotency/key-security (AK6/AK7)
- `.../app/src/test/java/net/devemperor/dictate/config/ConfigWireEnumParityTest.kt`
- `.../app/src/test/java/net/devemperor/dictate/config/ConfigEntityMapperTest.kt`
- `.../app/src/test/java/net/devemperor/dictate/ai/adapter/ProfileResolverCharacterizationTest.kt` — §9.4 byte-equal proof (AK5)

## Files modified (absolute)

- `.../app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt` — v12; 5 entities + 4 DAOs registered; `MIGRATION_11_12`; onCreate default-prompt INSERT lists the new NOT-NULL columns (INTEGRATION_TARGET)
- `.../app/src/main/java/net/devemperor/dictate/database/entity/PromptEntity.kt` — uuid + envelope columns; `@JvmOverloads` keeps the historical 7-arg ctor Java-visible
- `.../app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` — `ActiveProfileId`, `ConfigEntityMigrationDone`
- `.../app/src/main/java/net/devemperor/dictate/DictateApplication.java` — `ConfigEntityMigration.run(this)` after DB build + B2
- `.../app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt` — added `internal legacyKeyRef(function, provider)` (§7.2 re-map helper; keeps secret-pref names inside the §2.6 allow-listed file)

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Live AI read path NOT flipped to `ProfileResolver` | §9 / NoLegacyKeyReadTest comment | `AndroidAiFactory` still builds `AndroidAiConfig`; C2 only populates entities + builds/tests the resolver | Flipping reads while settings still WRITE to prefs (until C3) would strand keys entered before C3 — the exact hazard `NoLegacyKeyReadTest` documents. INTEGRATION_TARGETS is `DictateDatabase.kt` only. | C3 flips reads+writes atomically (one-liner in `AndroidAiFactory`) and un-ignores `NoLegacyKeyReadTest` | Yes (documented; resolver ready) |
| Per-**function** provider/credential/model chains | §8.2 (reads "per provider") | A provider used for both transcription+completion yields two `provider_configs`/`model_refs` | The old model has SEPARATE key/host prefs per (function, provider); one `ProviderConfig` has one `credentialRef`, so per-function chains are the faithful, byte-equal mapping | C3 UI may merge/dedupe for display; resolution is correct | Yes |
| C2 `PrefsBackup` runs after B2 removed key prefs → C2 dump has no plaintext keys | §8.4 CAUTION | Keys are in B2's own backup (`prefs-secrets-pre-migration.json`); C2 backs up the config prefs it migrates | Realistic startup order is B2→C2; combined backups preserve the full pre-migration state | none | Yes |
| Key access routed via `SecretsMigration.legacyKeyRef` (no key-pref refs in `ConfigEntityMigration`) | §8.2 step 1 | Keys read from B2 `legacy` SecretRefs, not from `*ApiKey*` prefs | Keeps every secret-pref name inside the §2.6 allow-listed migration file; B2 already removed the prefs | C3 un-ignores `NoLegacyKeyReadTest` cleanly | Yes |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| C2-1 | Nice-to-have | Read-path flip to `ProfileResolver` deferred to C3 (see deviation 1) — verify the C3 flip + `NoLegacyKeyReadTest` un-ignore | delegated | plan-deviation-resolved |

## Inline fixes applied

- Fresh-install `DictateDatabase.onCreate` default-prompt INSERT would fail on the new NOT-NULL prompt
  columns (Room emits no SQLite default) → listed the columns explicitly with inert defaults (mirrors
  the existing `type` handling). Caught by the full unit-test run (38 DB-build failures → 0).
- `PromptEntity` gained `@JvmOverloads` so the ~10 Java call sites of `new PromptEntity(...,type)`
  keep compiling.

## Files outside my scope (drift)

- `.../app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt` — added one `internal`
  helper (`legacyKeyRef`) that B2's own doc anticipated ("C2 re-maps the legacy refs", §7.2). Rationale:
  it is the one file allowed to name the secret prefs (§2.6), so the (function,provider)→legacy-ref
  mapping belongs here, DRY and allow-list-clean.

_The many `companion/**` and `shared/**` working-tree changes are from other agents active in this
worktree (D2 etc.), not part of C2._

## Test run

- `./gradlew :app:testDebugUnitTest` → **2468 tests, 0 failures, 0 errors, 1 skipped** (the pre-existing
  `@Ignore` `NoLegacyKeyReadTest`, un-ignored in C3). New C2 tests: `ConfigEntityMapperTest`(4),
  `ConfigEntityMigrationTest`(6), `ConfigWireEnumParityTest`(6), `ProfileResolverCharacterizationTest`(9),
  `MigrationTo12MetadataTest`(2).
- `./gradlew :app:compileDebugAndroidTestKotlin` → OK. `MigrationTo12Test` (instrumented, AK4 CHECK
  accept/reject) compiles but was **not executed** — it needs an emulator (`connectedDebugAndroidTest`),
  same local-only status as the existing `MigrationTo11Test`.

## Acceptance mapping

- **AK4** Room v11→v12 (5 tables + prompts recreate, CHECKs): `MigrationTo12`, `12.json`, `MigrationTo12Test` (compiles; run needs emulator).
- **AK5** byte-equal runner config: `ProfileResolverCharacterizationTest` (9 cases incl. params, custom host, ElevenLabs keyterms, non-ASCII strip, empty-config fallback) — green.
- **AK6** key-freedom: `ConfigEntityMigrationTest.no plaintext key…` — green.
- **AK7** backup + idempotency: `ConfigEntityMigrationTest.writes rollback backup…` + `.second run is a no-op` — green.
