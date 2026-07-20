# Desktop Usage-Sink Migration — Repair Research

**Date:** 2026-07-20T00:40:00+02:00
**Triggered by:** Finding `plan-and-api-D-1` [Important] — the companion `usage` table
+ `SqlDelightUsageSink` (spec desktop-host.md §3.3/§5.4, directory layout lists
`SqlDelightUsageSink.kt [NEW]`) were delegated by D1b (issue D1b-1) into D3's migration
and then silently dropped: `3.sqm` adds only config-entity tables, `Companion.sq` has no
`usage` table, no `SqlDelightUsageSink` exists, and `CompanionContainer.production()` still
wires `usageSink = NoopUsageSink`. `AIOrchestrator` calls `addUsage(...)` after every
transcription/completion, so all real desktop AI usage is discarded.
**Agent-ID:** repair-research (desktop-usage-sink-migration)

## Sources

1. **Code — the gap, first-hand.**
   - `companion/.../CompanionContainer.kt:141,148` — `usageSink = NoopUsageSink`, comment
     "no-op sink until D3's `usage` table (D5.b migration)".
   - `companion/.../ai/NoopUsageSink.kt` — the transitional sink; its doc says the persistent
     table "is deferred to D3".
   - `companion/.../db/Companion.sq` + `migrations/3.sqm` — no `usage` table anywhere.
   - `companion/.../db/migrations/2.sqm` — D1a's migration; also has no `usage` table (the
     spec's original placement, see below, was never implemented either).
   - `shared-ai/.../AIOrchestrator.kt:71,110,151` — the three `usageSink.addUsage(...)` call
     sites (transcribe / complete / converse); the data being dropped.
   - `app/.../database/entity/UsageEntity.kt` + `dao/UsageDao.kt` — the Room original to
     mirror (schema + increment-upsert semantics).
   - `app/.../ai/adapter/RoomUsageSink.kt` — the Android sink implementation, the exact shape
     the companion sink must copy.
2. **Spec `research/desktop-host.md`** — §3.3 NOTE (lines 411–418: `usage` is a
   "companion-necessary" table served by the `UsageSink` port, NOT Room-parity-required),
   §3.2 (lines 346–349: `usage.model_provider` is an **open vocabulary, no CHECK**), §5.4
   (lines 726–728: usage tracking via `UsageSink` → `SqlDelightUsageSink` writing "a small
   `usage` table"), directory layout line 1040 (`SqlDelightUsageSink.kt [NEW]`), and the
   original `2.sqm` sketch (lines 471–472) which listed `usage` alongside the core session
   tables.
3. **Plan `desktop-companion-v1.md`** — §3 D5.3 (lines 387–389, 861–862): the binding
   SQLDelight migration-number assignment **D1a=`2.sqm`, D3=`3.sqm`, E1=`4.sqm`**.
4. **D1b report** `reports/D/D1b-impl.md:69,77` — the delegation (issue D1b-1: "D3 owner: add
   `usage` DDL to its migration and swap `NoopUsageSink` → `SqlDelightUsageSink`").
5. **SchemaMigrator + build.gradle** — `SchemaMigrator.kt` (versioned `PRAGMA user_version`
   runner) and `companion/build.gradle:9-22` (`verifyMigrations = true`, snapshot dir
   `src/main/sqldelight/databases/`, current snapshots `1.db`–`4.db`).

## Findings

### 1. The gap is real and fully undocumented in the receiving chunk
D1b-1 was a valid, recorded hand-off. The D3 impl/self-fix reports never mention `usage`
(grep: zero hits) — an undocumented dropped delegation. Result: every desktop transcription,
completion and conversation turn calls `usageSink.addUsage(...)`, and `NoopUsageSink` throws
it away. Nothing is lost cosmetically today (the companion has no usage screen yet), but the
**accounting data** — the reason the `UsageSink` port exists — is silently discarded.

### 2. The migration-numbering constraint is the whole reason this is yellow
SQLDelight requires **contiguous** migration numbers and replays each checked-in
`databases/N.db` snapshot forward under `verifyMigrations = true`. Current state:

| Version | Migration (from→to) | Snapshot | Owner | Committed? |
|---|---|---|---|---|
| v1→v2 | `1.sqm` | `1.db` | chords (keyboard-action-engine) | yes |
| v2→v3 | `2.sqm` | `2.db` | D1a (session model + received_texts ablation) | yes |
| v3→v4 | `3.sqm` | `3.db` | D3 (config-entity tables) | yes (today) |
| current schema (v4) | — | `4.db` | — | yes |
| v4→v5 | `4.sqm` (planned) | — | **E1 (peers/subscriptions/catalog_access_log)** — not yet implemented | no |

Because the next free contiguous number is `4.sqm` and **plan D5.3 reserves `4.sqm` for E1**,
a *brand-new* migration for `usage` cannot slot in without either (a) taking `4.sqm` and
renumbering E1→`5.sqm` (a reversal of committed plan decision D5.3, touching parallel E-block
work), or (b) being folded into an already-committed migration. There is no "3.5.sqm" —
SQLDelight has no way to reserve `4.sqm` while inserting `usage` at `5.sqm` (you cannot have
`5.sqm` without `4.sqm`).

### 3. Editing the just-committed `3.sqm` is safe here
The safety of amending a committed migration turns on one question: **has any database in the
field already migrated to v4 under the old `3.sqm`?** It has not — the Dictate Companion
desktop app is **unreleased**; this plan (`desktop-companion-v1`) is its first release, and
`3.sqm` was committed *today* in this same implementation run (commit `cc365ad`). Adding a new
`CREATE TABLE usage` to `3.sqm` is safe for every path: fresh installs run `schema.create`
(the current `.sq`, which will include `usage`); any dev/CI DB at ≤ v3 runs the amended
`3.sqm` and gets the table. The only unsafe case — a user already at v4 who would never
receive the table — cannot exist pre-release. (E1 later builds `4.sqm` on top of the amended
`4.db`; unaffected.)

### 4. Folding into `3.sqm` is *less* churn than any new-migration path
`usage` is created *inside* `3.sqm` (v3→v4), so the v3 snapshot `3.db` (the state *before*
`3.sqm`) is unchanged; only the current-schema snapshot `4.db` regenerates. Adding it to D1a's
`2.sqm` instead would regenerate **both** `3.db` and `4.db` and would disturb the delicate
`received_texts` ablation migration and its `ReceivedTextsAblationMigrationTest` fixture — so
`3.sqm` is the better fold target than the spec's original `2.sqm` sketch. A new `4.sqm` +
E1→`5.sqm` renumber is the most churn (new file, new snapshot, plan-decision reversal,
coordination with whoever implements E1).

### 5. The `usage` table shape (mirror Room exactly, no new enums)
Room `usage` (UsageEntity): PK `model_name TEXT`, `audio_time INTEGER DEFAULT 0`,
`input_tokens INTEGER DEFAULT 0`, `output_tokens INTEGER DEFAULT 0`,
`model_provider TEXT DEFAULT 'OPENAI'`. `UsageDao.addUsage` is an **increment-upsert**:
`ON CONFLICT(model_name) DO UPDATE SET` the three counters `= col + :value` — `model_provider`
is *not* touched on conflict. `AIOrchestrator` calls the port as
`addUsage(modelName, audioDurationSeconds, promptTokens, completionTokens, provider.name)`
(transcribe passes duration + 0/0 tokens; complete/converse pass 0 duration + tokens), i.e.
identical to the Android `RoomUsageSink` → `UsageDao.addUsage` mapping. `usage.model_provider`
is an **open vocabulary → NO CHECK, no `EnumColumnAdapter`** (spec §3.2). The table therefore
adds **no** Double-Enum column and needs **no** adapter in `CompanionDatabase.build()`, and is
**not** part of the Room parity set (§3.3 NOTE) — `CompanionSchemaParityTest` enumerates only
enum columns and stays green.

### 6. `NoopUsageSink` stays — it is a live test double, not dead code
`DesktopDictationPipelineTest.kt:73` wires `usageSink = NoopUsageSink` for the headless E2E
(the pipeline test deliberately discards usage). Only `CompanionContainer.production()` swaps
to `SqlDelightUsageSink`. Keep `NoopUsageSink`, but rewrite its doc comment (currently "the
persistent table is deferred to D3") to describe its real, permanent role: the no-usage double
for headless tests.

## Implementation Hints

**Recommendation: fold the `usage` table into the already-committed `3.sqm`; keep E1 = `4.sqm`.**
This honours D1b-1's recorded delegation, requires no migration renumber, keeps the whole
E-block plan untouched, and is safe because no field DB is at v4. Concrete steps:

1. **`Companion.sq` — add the table + query.** Place a `usage` block (e.g. after the
   config-entity tables, or under a new "-- usage (UsageSink, §5.4)" heading). DDL, mirroring
   Room's `UsageEntity` — open-vocab `model_provider`, no CHECK:
   ```sql
   CREATE TABLE usage (
       model_name     TEXT NOT NULL PRIMARY KEY,
       audio_time     INTEGER NOT NULL DEFAULT 0,
       input_tokens   INTEGER NOT NULL DEFAULT 0,
       output_tokens  INTEGER NOT NULL DEFAULT 0,
       model_provider TEXT NOT NULL DEFAULT 'OPENAI'  -- open vocab (AIProvider.name), no CHECK (§3.2)
   );
   ```
   Named query mirroring `UsageDao.addUsage` (counters increment, provider untouched on
   conflict):
   ```sql
   addUsage:
   INSERT INTO usage (model_name, audio_time, input_tokens, output_tokens, model_provider)
   VALUES (:modelName, :audioTime, :inputTokens, :outputTokens, :provider)
   ON CONFLICT(model_name) DO UPDATE SET
       audio_time    = audio_time + :audioTime,
       input_tokens  = input_tokens + :inputTokens,
       output_tokens = output_tokens + :outputTokens;
   ```
   (Add read queries only if a test needs them, e.g. `usageByModel:` / `allUsage:`, mirroring
   `getByModelName` / `getAll` — optional, keep the surface minimal.)

2. **`3.sqm` — add the same `CREATE TABLE usage`** (byte-identical DDL text; the file headers
   state the CREATE must stay byte-identical to `Companion.sq`, and `verifyMigrations` replays
   it). Add a one-line comment: usage folded here per D1b-1 / D5.b — a companion-only
   accounting table (`UsageSink`), not Room parity (§3.3). `usage` has no FKs, so ordering
   within the migration is free.

3. **New `companion/.../ai/SqlDelightUsageSink.kt`** implementing `UsageSink`, delegating to
   the generated query with **no added threading** (parity with `RoomUsageSink`; the port doc
   mandates synchronous-on-the-caller's-background-thread). Constructor takes the
   `DictateCompanionDb` (or its generated queries object); `override fun addUsage(...) =
   db.<queriesObject>.addUsage(modelName, audioDurationSeconds, promptTokens, completionTokens,
   providerName)`. Add a module-header `@see` to `research/shared-ai-extraktion.md §4.2`
   (matching `RoomUsageSink`/the `UsageSink` port) and to desktop-host.md §5.4.

4. **`CompanionContainer.production()`** — swap `usageSink = NoopUsageSink` →
   `usageSink = SqlDelightUsageSink(database)` (line 148), and update the comment at lines
   139–141 (drop "no-op sink until D3's `usage` table"; state that usage now persists via
   `SqlDelightUsageSink`).

5. **`NoopUsageSink`** — keep (used by `DesktopDictationPipelineTest`); rewrite its KDoc to
   its real role (the intentional no-usage double for headless tests), removing the
   "deferred to D3" language.

6. **Regenerate the `4.db` snapshot** (the SQLDelight schema task, e.g.
   `./gradlew :companion:generateMainDictateCompanionDbSchema` — confirm the exact task name in
   this project) so it includes `usage`; commit the updated `4.db`. `3.db` must NOT change.
   Then `./gradlew :companion:verifySqlDelightMigration` and `:companion:test` green.
   `CompanionDatabase.build()` needs **no** change (no typed column on `usage`).

7. **Test** (regression + coverage): a `SqlDelightUsageSinkTest` on `CompanionDatabase.inMemory()`
   asserting (a) first `addUsage` inserts a row, (b) a second call for the same `model_name`
   **increments** audio/token counters (upsert), (c) `model_provider` survives the conflict
   update. Optionally a migration-level assertion that `usage` exists after `SchemaMigrator`.
   No `CompanionSchemaParityTest` change (usage is non-parity).

8. **Close the loop:** mark issue D1b-1 resolved in the D-block validated-findings / this
   chunk's report, and if convenient add a one-line note to the plan D5.3 area clarifying that
   `usage` rides in `3.sqm` (E1 stays `4.sqm`).

**Alternatives considered and rejected:**
- *New `4.sqm` for `usage`, E1 → `5.sqm`.* Reverses committed plan decision D5.3, forces a
  cross-block renumber touching parallel E-work, and regenerates/adds more snapshots — strictly
  more churn for no safety gain over amending an unreleased `3.sqm`.
- *Fold into D1a's `2.sqm`* (the spec's original sketch). Regenerates both `3.db` and `4.db`,
  disturbs the `received_texts` ablation migration + its fixture test, and contradicts D1b-1's
  more recent, considered delegation to `3.sqm`.
- *Leave `NoopUsageSink` in production* (conscious re-scope out of Block D). Rejected: spec
  §3.3/§5.4 + the directory layout put `usage`/`SqlDelightUsageSink` squarely in Block D, the
  data is being actively discarded, and the fix is small and low-risk.

## References

- Finding: `reports/D/validated-findings.md` (plan-and-api-D-1); origin `reports/D/D1b-impl.md`
  (issue D1b-1, line 77).
- Spec: `research/desktop-host.md` §3.2 (line 346), §3.3 NOTE (411–418), §5.4 (726–728),
  directory layout (line 1040), `2.sqm` sketch (471–472).
- Plan: `desktop-companion-v1.md` §3 D5.3 (387–389, 861–862).
- Code: `companion/.../CompanionContainer.kt`, `companion/.../ai/NoopUsageSink.kt`,
  `companion/.../db/Companion.sq`, `.../db/migrations/2.sqm` + `3.sqm`,
  `companion/.../data/{SchemaMigrator,CompanionDatabase}.kt`, `companion/build.gradle`;
  Android originals `app/.../database/entity/UsageEntity.kt`, `.../dao/UsageDao.kt`,
  `.../ai/adapter/RoomUsageSink.kt`; port `shared-ai/.../ai/port/UsageSink.kt`,
  call sites `shared-ai/.../ai/AIOrchestrator.kt:71,110,151`.
- Related research: `research/shared-ai-extraktion.md §4.2` (the `UsageSink` port contract).
- Database conventions: `docs/DATABASE-PATTERNS.md` (Double-Enum rule — why `model_provider`
  stays open-vocab / no CHECK).
