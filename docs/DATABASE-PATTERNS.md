# Database Patterns — Dictate

Conventions and patterns for working with the Room database in the Dictate Android app.

This document is the authoritative reference for database-related architectural decisions. The `CLAUDE.md` at the repo root contains only a short pointer to this file.

---

## Table of Contents

1. [Double-Enum Pattern](#double-enum-pattern)
2. [Denormalized Cache Columns](#denormalized-cache-columns)
3. [Migration Conventions](#migration-conventions) *(placeholder — fill as conventions emerge)*
4. [Versioning & Schema Exports](#versioning--schema-exports) *(placeholder)*

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
| `sessions` | `last_error_type` | `AIProviderException.ErrorType` (reused) | `ai/AIProviderException.kt` |
| `processing_steps` | `status` | `StepStatus` | `database/entity/StepStatus.kt` |

Columns that should be retrofitted to this pattern when next touched:

| Table | Column | Current state |
|-------|--------|---------------|
| `sessions` | `type` | Plain String, values from `SessionType` enum but no CHECK |
| `processing_steps` | `step_type` | Plain String, values from `StepType` enum but no CHECK |
| `insertion_log` | `insertion_method` | Plain String, values from `InsertionMethod` enum but no CHECK |

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

## Migration Conventions

*(Placeholder — extend as migration conventions emerge. Things to document here in future:*
*- Naming conventions for migration files*
*- Rules for splitting migrations vs. combining*
*- When to recreate a table vs. ALTER*
*- How to test migrations)*

---

## Versioning & Schema Exports

*(Placeholder — extend. Things to document here:*
*- Why `exportSchema = true` matters*
*- Where schemas live and when to commit them*
*- Version bump policy)*
