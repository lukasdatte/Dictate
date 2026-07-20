# Database Patterns — Dictate

Conventions and patterns for working with the Room database in the Dictate Android app.

This document is the authoritative reference for database-related architectural decisions. The `CLAUDE.md` at the repo root contains only a short pointer to this file.

---

## Table of Contents

1. [Double-Enum Pattern](#double-enum-pattern)
2. [Denormalized Cache Columns](#denormalized-cache-columns)
3. [SQLDelight Parity (Companion)](#sqldelight-parity-companion)
4. [Migration Conventions](#migration-conventions) *(placeholder — fill as conventions emerge)*
5. [Versioning & Schema Exports](#versioning--schema-exports) *(placeholder)*

---

## Double-Enum Pattern

**Status:** Mandatory for all finite-set columns.

### What it is

Whenever a Room entity column holds a value from a **finite, known set** (status, origin, type, role, error classifier, etc.), it MUST be modelled as a **Double Enum**: a Kotlin `enum class` in code AND a SQL `CHECK` constraint in the database. The two representations are kept in sync through migrations — there is no way to change one without the other.

### Why it exists

Room cannot store Kotlin enums natively. Enums are always persisted as `String` (typically via `Enum.name`). Without a DB-side constraint, the database silently accepts any string — including typos, stale values from previous app versions, or values that were renamed but not migrated.

The `CHECK` constraint enforces at the database level that only valid enum values can be written. If a developer adds a new enum value in Kotlin but forgets the migration, an INSERT with the new value fails loudly at runtime instead of silently creating a corrupted row. This is the classic "added enum value, forgot migration" bug that silently rots databases — the Double-Enum pattern makes that bug impossible.

### Core principle

> **You cannot change the Kotlin enum without also changing the SQL schema. The database will reject you.**

This is enforced mechanically, not through convention or review discipline. That makes it the right level of defence for a data layer that persists across app versions.

### Required structure

**Step 1 — Kotlin enum (source of truth for the code):**

```kotlin
package net.devemperor.dictate.database.entity

/**
 * Terminal persisted state of a [SessionEntity].
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md):
 * the SQL column has a CHECK constraint matching these values exactly.
 */
enum class SessionStatus {
    RECORDED,   // Audio persistent, no processing run (yet) or aborted before DB write
    COMPLETED,  // Pipeline finished successfully
    FAILED,     // Pipeline finished with an error
    CANCELLED   // User explicitly cancelled
}
```

**Step 2 — Entity column as String:**

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    // ... other fields ...

    // Column stores SessionStatus.name — see Step 4 for the SQL CHECK
    @ColumnInfo(name = "status") val status: String = SessionStatus.RECORDED.name
) {
    // Convenience accessor for boundary conversion
    val statusEnum: SessionStatus
        get() = runCatching { SessionStatus.valueOf(status) }
            .getOrDefault(SessionStatus.RECORDED)
}
```

The convenience accessor (`statusEnum`) is important: it handles the edge case where the DB contains a value not yet known to the code (e.g., during a downgrade or rollback). It falls back to a safe default instead of crashing.

**Step 3 — DAO takes String, not enum:**

```kotlin
@Dao
interface SessionDao {
    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    fun updateStatus(id: String, status: String)  // String, not SessionStatus
}
```

Callers pass `SessionStatus.COMPLETED.name`. This keeps the enum→string conversion at the application boundary, where it belongs.

**Step 4 — SQL CHECK constraint in the migration:**

```kotlin
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                -- ... other columns ...
                status TEXT NOT NULL DEFAULT 'RECORDED'
                    CHECK (status IN ('RECORDED', 'COMPLETED', 'FAILED', 'CANCELLED'))
            )
        """.trimIndent())

        // ... copy data, drop old table, rename new ...
    }
}
```

The literal values in the `CHECK` clause MUST exactly match `enum.name` in Kotlin (case-sensitive). A mismatch will not be caught at compile time — it will surface as a runtime INSERT failure, so **write a migration test** (see below).

### Adding a new enum value — the forced workflow

1. Add the value to the Kotlin `enum class`.
2. Write a Room migration that recreates the table with the updated `CHECK` list.

   SQLite does **not** support `ALTER TABLE ... DROP CHECK` or `ADD CHECK`. The only way to change a `CHECK` constraint is to recreate the table. The pattern is:

   ```sql
   CREATE TABLE sessions_new (... new CHECK ...);
   INSERT INTO sessions_new SELECT * FROM sessions;
   DROP TABLE sessions;
   ALTER TABLE sessions_new RENAME TO sessions;
   -- Recreate indices
   ```

3. Bump the Room database version in `DictateDatabase.kt`.
4. Update any DAO fallback defaults if the new value has special semantics.
5. Write a migration test that verifies the new value is accepted and old values are preserved.

### Removing a value — forbidden without multi-step migration

Simply deleting a value from the Kotlin enum is a bug trap:

- **If you also tighten the CHECK to exclude the removed value**, old rows with that value become unreadable.
- **If you don't tighten the CHECK**, the database can still contain rows with the removed value, but `SessionStatus.valueOf(...)` throws `IllegalArgumentException`. The convenience accessor's `getOrDefault` is your only safety net.

The only safe removal sequence is:

1. **Rewrite** — write a migration that updates all rows with the old value to a replacement value.
2. **Tighten** — in a subsequent migration (or the same one, carefully), tighten the `CHECK` to exclude the removed value.
3. **Delete from Kotlin** — only now is it safe to remove the enum value from code.

In practice, the cost of this process means enum values should be **deprecated** rather than removed. Keep the value in the enum, stop writing it, and leave a `// Deprecated — no new writes` comment. Eventually a batch cleanup migration removes it.

### Migration test pattern

Every Double-Enum migration must have a corresponding test. Template:

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java
    )

    @Test
    fun migrate13To14_acceptsValidStatus() {
        val db = helper.createDatabase(TEST_DB, 13).apply {
            execSQL("INSERT INTO sessions (id, type, created_at) VALUES ('test', 'RECORDING', 0)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14)
        migrated.query("SELECT status FROM sessions WHERE id = 'test'").use {
            it.moveToFirst()
            assertEquals("COMPLETED", it.getString(0))  // default after migration
        }
    }

    @Test
    fun migrate13To14_rejectsInvalidStatus() {
        val db = helper.createDatabase(TEST_DB, 13).apply { close() }
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14)

        val ex = assertFailsWith<SQLiteConstraintException> {
            migrated.execSQL("INSERT INTO sessions (id, type, created_at, status) VALUES ('bad', 'RECORDING', 0, 'NOT_A_REAL_STATUS')")
        }
        assertTrue(ex.message!!.contains("CHECK constraint failed"))
    }
}
```

### When to apply this pattern

**Apply to:**
- Status columns (session status, step status, job status)
- Type discriminators (session type, step type, insertion source)
- Role / origin fields (session origin, message role)
- Error classifiers (error type, error category)
- Any column whose valid values are a closed set controlled by the app

**Do NOT apply to:**
- Free-form text (error messages, user input, filenames)
- Open vocabularies that grow organically (prompt names, app package names, language codes — those are governed by external systems)
- Values from third-party APIs (external HTTP status codes, vendor-specific error codes) — those are not under your control, and the CHECK would reject new values before you could react

### Checklist for new Double-Enum columns

Before merging a PR that adds a Double-Enum column:

- [ ] Kotlin `enum class` defined in `database/entity/` with a KDoc pointing to this document
- [ ] Entity field is `val xxx: String`, not `val xxx: Xxx` (enum type)
- [ ] Convenience accessor `xxxEnum: Xxx` defined on the entity class with `getOrDefault` fallback
- [ ] DAO methods take `String`, not the enum type
- [ ] Migration SQL includes the `CHECK` clause with all enum values as string literals
- [ ] Migration test verifies valid values are accepted and invalid values are rejected
- [ ] Default value in the `@ColumnInfo` matches the `DEFAULT` clause in SQL
- [ ] Index on the column added if it will be filtered frequently in queries

### Applied columns (as of now)

The following columns follow this pattern:

| Table | Column | Enum class | Location |
|-------|--------|------------|----------|
| `sessions` | `status` | `SessionStatus` | `database/entity/SessionStatus.kt` |
| `sessions` | `origin` | `SessionOrigin` | `database/entity/SessionOrigin.kt` |
| `sessions` | `type` | `SessionType` | `database/entity/SessionType.kt` (CHECK added in schema v9, `MigrationTo9`) |
| `sessions` | `last_error_type` | `AIProviderException.ErrorType` (reused) | `ai/AIProviderException.kt` |
| `processing_steps` | `status` | `StepStatus` | `database/entity/StepStatus.kt` |
| `processing_steps` | `step_type` | `StepType` | `database/entity/StepType.kt` (CHECK added in schema v8, `MigrationTo8`) |
| `text_insertions` | `insertion_method` | `InsertionMethod` | `database/entity/InsertionMethod.kt` (CHECK added in schema v10, `MigrationTo10`, alongside the new value `WINDOWS_DISPATCH` — ADR-0019) |
| `prompts` | `type` | `PromptType` | `database/entity/PromptType.kt` (CHECK added in schema v11, `MigrationTo11`; replaces the former `[bracketed]` string convention for text pills — ADR — prompt pill types) |
| `provider_configs` | `provider_type` | `ProviderType` | `shared/config/ConfigEnums.kt` (CHECK added in schema v12, `MigrationTo12` — shareable config-entity model, ADR-0030) |
| `provider_configs` | `kind` | `ProviderKind` | `shared/config/ConfigEnums.kt` (schema v12) |
| `api_credentials` | `provider_type` | `ProviderType` | `shared/config/ConfigEnums.kt` (schema v12) |
| `model_refs` | `function` | `ModelFunction` | `shared/config/ConfigEnums.kt` (schema v12) |
| `profiles` | `style_prompt_mode` | `PromptSelectionMode` | `shared/config/ConfigEnums.kt` (schema v12) |
| `profiles` | `system_prompt_mode` | `PromptSelectionMode` | `shared/config/ConfigEnums.kt` (schema v12) |
| `profiles` | `ambiguity_mode` | `AmbiguityModeValue` | `shared/config/ConfigEnums.kt` (schema v12) |
| `provider_configs` / `api_credentials` / `model_refs` / `profiles` / `prompts` | `visibility`, `subscription_mode` | `Visibility`, `SubscriptionMode` | `shared/config/ConfigEnums.kt` — the envelope/provenance columns every shareable entity carries (schema v12, `MigrationTo12`) |
| `subscriptions` | `kind` | `CatalogEntityKindWire` | `shared/protocol/Dtos.kt` (CHECK added in schema v13, `MigrationTo13` — peer-catalog subscriber journal, ADR-0034) |
| `subscriptions` | `mode` | `SubscriptionMode` | `shared/config/ConfigEnums.kt` (schema v13; **subset** CHECK — only `SUBSCRIBE`/`ONE_SHOT`, never `LOCAL`, because a subscription row is always an active binding — a fork deletes its row) |

> **Enum home for the shareable columns.** The v12/v13 config-entity and
> peer-catalog enums live in **`:shared`** (`shared/config/ConfigEnums.kt`,
> `shared/protocol/Dtos.kt`), not in `app`'s `database/entity/`, because they are
> the cross-platform **canonical** enums: the identical `enum class` + CHECK
> literals also drive the companion's SQLDelight schema and its parity tests (see
> [SQLDelight Parity](#sqldelight-parity-companion)). The Double-Enum discipline is
> unchanged — the Room entity columns are still `String` with a `xxxEnum`
> `getOrDefault` accessor (`config/entity/ConfigRoomEntities.kt`,
> `peers/entity/PeerRoomEntities.kt`); only the enum's file home differs.

Columns that should be retrofitted to this pattern when next touched:

*None.* The three original retrofit debts were discharged in successive migrations: `processing_steps.step_type` (v8), `sessions.type` (v9), and `text_insertions.insertion_method` (v10, which also widened it with `WINDOWS_DISPATCH` and added `target_device_id` — a `CHECK` can only change via a table recreate, so a new enum value *is* the "next touched" event). Schema v12 (`MigrationTo12`, shareable config-entity model) and v13 (`MigrationTo13`, peer-catalog subscriber journal) then extended the pattern to every new finite-set column from the outset — no retrofit needed, each landed with its `CHECK` already in place. Every finite-set column now carries its Double-Enum `CHECK`. Room's `validateMigration` ignores `CHECK` constraints, so these stay invisible to schema validation exactly as the existing `status`/`origin` CHECKs do.

`target_device_id` (added in v10) is deliberately NOT a Double-Enum column: a device id is an open vocabulary (a UUID), like `target_app_package` — the pattern explicitly does not apply to open vocabularies.

---

## Denormalized Cache Columns

**Status:** Use sparingly, document explicitly.

### What it is

Some columns on the `sessions` table are deliberately denormalized — they duplicate data that technically lives in related tables (`transcriptions`, `processing_steps`) or is derivable from them. Examples: `final_output_text`, `input_text`, `last_error_message`.

### Why they exist

- **History list performance:** The HistoryAdapter renders dozens of rows and needs a one-liner preview for each. Joining across `sessions`, `transcriptions`, and the latest `processing_step` for every row would be prohibitively expensive.
- **Search:** The preview text is searchable. Putting it on the parent session row means a single `LIKE` query instead of a multi-table text search.
- **UI simplicity:** The HistoryAdapter can render from `SessionEntity` alone, no DAO joins.

### The rules

- **Update together:** Any code path that writes the canonical data (e.g., `appendProcessingStep`) MUST also update the denormalized cache (`finalOutputText`) in the same transaction. This is enforced by routing all writes through `SessionManager`, which knows about both sides.
- **Never read-then-compute:** Don't load a `SessionEntity`, recompute the final output from `ProcessingStep`s, and write it back. That's a source of inconsistency. Compute at write time only.
- **Document the invariant:** Every denormalized column has a KDoc comment on the entity field that states what it mirrors and when it's updated.

### Applied columns

| Column | Mirrors | Updated by |
|--------|---------|------------|
| `sessions.final_output_text` | Last successful step's output OR current transcription | `SessionManager.updateFinalOutputText()` at end of pipeline |
| `sessions.input_text` | For REWORDING sessions, the user's input text | `SessionManager.updateInputText()` on session creation |
| `sessions.last_error_message` | Last error context for FAILED sessions | `SessionManager.finalizeFailed()` |

---

## SQLDelight Parity (Companion)

**Status:** Mandatory for every finite-set column and every session/config table shared across platforms.

### What it is

The Android app persists with **Room**; the desktop companion (`:companion`) persists with **SQLDelight**. The two ORMs **cannot share table definitions** (ADR-0030 D3), so the companion schema (`companion/src/main/sqldelight/.../db/Companion.sq` + `.../migrations/*.sqm`) is a **hand-translation** of the Room schema. Any drift in an enum vocabulary or a column silently corrupts phone↔companion sync or the shared history view.

### Why it exists

- **One shared session archive (ADR-0035, F16).** Phone-synced sessions and desktop-recorded sessions live in the **same** `sessions` table, separated by the `origin` column. The cursor-based sync (ADR-0020) round-trips the full record only if both schemas match exactly.
- **Cross-platform config identity (ADR-0030).** A `contentHash` means "the same profile" on phone and desktop only if the canonical serialization and the entity columns are byte-for-byte equivalent.

### The rules

- **Full parity, not a subset.** The companion `sessions` / `transcriptions` / `processing_steps` / `conversation_messages` tables mirror Room table-for-table; the config-entity tables (`provider_configs` / `model_refs` / `prompts` / `profiles` / `profile_prompts`) mirror the `:shared` entity model.
- **Double-Enum on both sides.** Every finite-set column carries the same Kotlin enum + SQL `CHECK` on **both** platforms. The enum vocabularies (desktop-host spec §3.2) are the single source of truth for both the CHECK literals and the parity assertions.
- **Parity tests are the drift guard — a mismatch fails the build.** They are the SQLDelight equivalent of the wire-vs-domain parity discipline (ADR-0016). Do not weaken or disable them:
  - `companion/.../data/CompanionSchemaParityTest.kt` — schema shape vs Room (`RoomParityReference.kt` is the reference).
  - `companion/.../data/ConfigEntityCheckParityTest.kt` + `CatalogCheckConstraintParityTest.kt` — CHECK-constraint parity.
  - `companion/.../ai/CompanionConfigWireEnumParityTest.kt` — enum-vocabulary parity.
  - Verify migrations with `./gradlew :companion:verifySqlDelightMigration`.
- **`contentHash`/`updatedAt` are recompute-on-write** on the SQLDelight side too — same rule as [Denormalized Cache Columns](#denormalized-cache-columns): recompute from the current payload at every write path, never trust a value delivered from a file or peer (ADR-0030 recompute-on-import).
- **`received_texts` is retired (ADR-0035, D5.g).** Its sync/dispatch bookkeeping moved to the 1:1 `dispatch_state` table; the `2.sqm` migration backfills `received_texts` → `sessions` (+ `dispatch_state`) then DROPs it. The behaviour-neutrality proof is five existing tests (`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`) staying green **without assertion changes**.

### Migration-number assignment (desktop-companion-v1)

| `.sqm` | Chunk | Content |
|--------|-------|---------|
| `2.sqm` | D1a | Full session-schema parity + `dispatch_state` + `received_texts` backfill/DROP (ADR-0035) |
| `3.sqm` | D3 | Config-entity tables (`provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`) incl. provenance columns, NULL until E2 (ADR-0030/0034) |
| `4.sqm` | E1 | `peers` / `subscriptions` / `catalog_access_log` (ADR-0034) |

---

## Migration Conventions

*(Placeholder — extend as migration conventions emerge. Things to document here in future:*
*- Naming conventions for migration files*
*- Rules for splitting migrations vs. combining*
*- When to recreate a table vs. ALTER*
*- How to test migrations)*

### Data-preservation rule

> Source: B3 sanity-pass findings F-1 + F-2 (2026-05-15). Full
> derivation in
> `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md`.

When a migration introduces a new column that drives a cleanup
policy (row eligibility for auto-delete), apply two rules together:

**Rule 1 — NULL is "the marker does not apply"**

Backfill the new column with `NULL` for pre-existing rows when the
exact value cannot be reconstructed. Cleanup queries filter on
`<column> IS NOT NULL` so the legacy population is immune. This
preserves the pre-migration data contract (e.g. "history is
forever" for an IME that had no automatic cleanup before).

Avoid:
- Backfilling with a synthetic timestamp like `created_at` that
  makes the legacy rows immediately eligible.
- Sentinel values (`-1`, `0L`) — Kotlin nullable types are the
  idiomatic equivalent and harder for downstream code to
  misinterpret as a real value.
- One-shot pref flags that gate the cleanup — they couple
  cleanup correctness to non-DB state.

**Rule 2 — Row-level DELETE preserves children via SET NULL FK**

Self-referential foreign keys with `ON DELETE CASCADE` propagate
row-level DELETEs into the child sub-tree. When the cleanup
policy targets rows whose children may be fresh + user-visible
(`POST_PROCESSING` chains, future parent-child types), the FK
must be `ON DELETE SET NULL` — the child survives with
`parent_session_id = NULL` and surfaces as a root-level history
entry.

CASCADE is appropriate only for child rows that carry no
standalone meaning (e.g. `transcriptions`, `processing_steps` —
both are facts about the parent session; the parent's deletion
makes them stale).

**Test contract:**

Every migration that touches a cleanup-policy column must include
two test cases:

1. Pre-existing rows post-migration are immune to the cleanup
   query (e.g. `pipelineOrphanCleaner_doesNotDeleteLegacyRows`).
2. Row-level DELETE on a parent preserves children with their FK
   set to NULL (e.g. `migrate3To4_setsForeignKeyToSetNull`). Note:
   FK enforcement is disabled during migration; the test must
   enable it with `PRAGMA foreign_keys = ON` before exercising
   the cascade behaviour.

---

## Versioning & Schema Exports

*(Placeholder — extend. Things to document here:*
*- Why `exportSchema = true` matters*
*- Where schemas live and when to commit them*
*- Version bump policy)*
