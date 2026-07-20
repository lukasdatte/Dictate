# D1a — Companion Schema-Vollparität + `received_texts`-Ablösung + Sync-Umbau

**Chunk:** D1a · **Agent:** IMPL+TEST · **Timestamp:** 2026-07-20T00:40:00+02:00

## What I did

Brought the companion SQLite schema to full Room-v11 parity for the four core session tables
(`sessions`/`transcriptions`/`processing_steps`/`conversation_messages`) with identical Double-Enum
CHECK vocabularies, **ablated** `received_texts` into the parity `sessions` table + a 1:1
`dispatch_state` companion side-table (migration `2.sqm`, v2→v3, lossless backfill + DROP), and
rewired the phone-sync repository/queries onto the new model behaviour-identically. The five
existing sync/repo tests stay green **without assertion changes**; a new parity suite and a
lossless-migration test cover the rest.

## Files modified / created (absolute paths)

Schema + data (main):
- `/home/lukas/.../companion/src/main/sqldelight/net/devemperor/dictate/companion/db/Companion.sq` — the four parity tables + `dispatch_state`, phone-sync queries JOINed onto the new model.
- `/home/lukas/.../companion/src/main/sqldelight/net/devemperor/dictate/companion/db/migrations/2.sqm` — **NEW** v2→v3 migration (create tables, backfill received_texts→sessions+dispatch_state, DROP).
- `/home/lukas/.../companion/src/main/sqldelight/databases/3.db` — **NEW** generated schema snapshot for `verifyMigrations`.
- `/home/lukas/.../companion/src/main/kotlin/net/devemperor/dictate/companion/domain/session/SessionEnums.kt` — **NEW** companion-local session enums + `SessionOrigin↔SessionOriginWire` mappers.
- `/home/lukas/.../companion/src/main/kotlin/net/devemperor/dictate/companion/data/CompanionDatabase.kt` — new adapters (sessions/dispatch_state; the query-less tables get theirs in D1b).
- `/home/lukas/.../companion/src/main/kotlin/net/devemperor/dictate/companion/data/SqlDelightHistoryRepository.kt` — rewired onto sessions+dispatch_state, one shared mapper via SQLDelight mapper-overloads.
- `/home/lukas/.../companion/build.gradle` — `implementation project(':shared-ai')` (for `AIProviderException.ErrorType` on the SQLDelight classpath).

Tests:
- `/home/lukas/.../companion/src/test/kotlin/net/devemperor/dictate/companion/data/RoomParityReference.kt` — **NEW** hand-transcribed Room SSoT sets.
- `/home/lukas/.../companion/src/test/kotlin/net/devemperor/dictate/companion/data/CompanionSchemaParityTest.kt` — **NEW** §3.6 families (a) CHECK accept/reject + (b) Room parity (replaces OriginCheckConstraintParityTest).
- `/home/lukas/.../companion/src/test/kotlin/net/devemperor/dictate/companion/data/ReceivedTextsAblationMigrationTest.kt` — **NEW** lossless-backfill migration test.
- `/home/lukas/.../companion/src/test/kotlin/net/devemperor/dictate/companion/data/ChordMigrationSeedTest.kt` — faithful v1 fixture (2.sqm now reads received_texts) + new adapter wiring.
- `/home/lukas/.../companion/src/test/kotlin/net/devemperor/dictate/companion/data/SchemaMigratorTest.kt` — `theRealSchema` now asserts sessions/dispatch_state present, received_texts absent.
- `OriginCheckConstraintParityTest.kt` — **DELETED** (its subject `received_texts` was ablated; coverage moved to CompanionSchemaParityTest).

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Session enums placed in `domain/session/` (not spelled out) | §3.2 names package `...companion.domain.session` | Created the package + `SessionEnums.kt` | Spec names the package; domain layer owns vocabulary, data maps SQL→it | D1b imports these for the pipeline | ✓ |
| `usage` table NOT created in D1a | §3.3 note lists "+ usage"; 2.sqm comment lists it | Deferred `usage` to D1b | No DDL is specified and no D1a acceptance criterion (1–4) touches usage; the UsageSink port (§5.4) that defines its columns is D1b — creating a guessed schema now would churn across chunks | D1b owns `usage` + UsageSink | ✓ (documented) |
| `processing_steps`/`conversation_messages`/`transcriptions` adapters not registered in `CompanionDatabase` | §3.2 (all are Double-Enum) | Only sessions/dispatch_state adapters registered | SQLDelight omits an adapter from the DB constructor until a **query** references the table; these are schema-only in D1a. Adapters land with their queries in D1b | D1b adds queries → adds adapters | ✓ |
| `countHistory` + `selectCursor` JOIN `dispatch_state` (I first filtered only by host_origin) | §3.5 ("selectCursor/pageHistory/countHistory → FROM sessions JOIN dispatch_state") | Added the JOIN | Spec-mandated; also preserves the "revoke a device takes its texts with it" parity (dispatch_state cascade → row drops from history + cursor) | none | ✓ |

Drift (files outside my chunk scope): **none** — the `secrets/`, `AndroidKeystoreSecretStoreTest`, `shared/CanonicalJsonTest` and the build.gradle SecretStore comment are other parallel agents' edits; I did not touch them.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

## Inline fixes applied

- `SessionEnums.kt`: Kotlin nests block comments, so `entity/*` inside a KDoc opened a nested comment → reworded to `entity/`.
- Stale SQLDelight build-cache output served an incomplete DB constructor → `--rerun-tasks` + cleared generated dir; confirmed against the fresh generation.

## Test run

`./gradlew :companion:test` → **207 completed, 0 failed, 2 skipped** (the 2 skips are pre-existing
`pending:` tests unrelated to D1a). `verifyMainDictateCompanionDbMigration` → **BUILD SUCCESSFUL**
(2.sqm replays consistently against the 3.db snapshot). The five protected tests
(`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`,
`SqlDelightHistoryRepositoryTest`) are byte-unchanged and green.

## Helper decisions

- Reused the existing `OriginCheckConstraintParityTest` structure (accept-all + reject-fantasy) as
  the template for `CompanionSchemaParityTest` per §3.6's explicit "Vorbild".
- Reused SQLDelight's mapper-overload feature so all read queries share one `toReceivedText` mapping
  (no per-query generated result type to map).

---

## Verification pass (2026-07-20, re-run of D1a)

The D1a implementation above was found already present **uncommitted** in the working tree at
re-invocation (a prior interrupted attempt — no commit was ever made). Rather than rewrite a
coherent implementation from scratch (regression risk on the highest-risk chunk of Block D), this
pass verified it end-to-end against spec §3.2–§3.6 and closed the one gap found.

**Verified:**
- `./gradlew :companion:verifySqlDelightMigration` — green (`2.sqm` replays against `2.db`, new `3.db` snapshot consistent).
- `./gradlew :companion:test` — 32 test classes, **0 failures / 0 errors** (2 pre-existing platform-gated skips in `DpapiSecretStoreTest`, unrelated). New: `CompanionSchemaParityTest` (16), `ReceivedTextsAblationMigrationTest` (5).
- `./gradlew :companion:build` — BUILD SUCCESSFUL.
- **Behaviour-neutrality proof** — the five protected tests (`SyncE2ETest` 8, `CompanionE2ETest` 22, `MultiConnectorE2ETest` 4, `TruncatedResponseE2ETest` 1, `SqlDelightHistoryRepositoryTest` 5) have **empty `git diff`** (byte-unchanged) and pass green. `SyncService.kt` / `DispatchService.kt` also have empty diff — the `HistoryRepository` port signature held, so no call-site changes were needed.
- Integration gate — both `INTEGRATION_TARGETS` (`Companion.sq`, `SqlDelightHistoryRepository.kt`) carry real diffs.

**Additional inline fix (this pass):**
- `SqlDelightChordMappingRepositoryTest.kt:16` — dangling KDoc link `[OriginCheckConstraintParityTest]` (the class D1a deletes) repointed to `[CompanionSchemaParityTest]`. Comment-only; suite re-run green afterwards. This is out-of-chunk-scope drift, justified: my chunk's deletion of the class broke this doc link; leaving it dangling would mislead future readers.

**Build.gradle attribution note:** `companion/build.gradle` carries a dedup that merges D1a's `:shared-ai` dependency line (needed for `AIProviderException.ErrorType`) with B1's identical line (needed for the SecretStore port) into a single declaration with a combined comment. Partly D1a's concern, partly B1's — already in the working tree, build green; left as-is (reverting would re-duplicate the declaration).

**Final verdict:** D1a complete. All acceptance criteria 1–4 (spec §2) satisfied. No open issues, no delegated escalations.
