package net.devemperor.dictate.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.devemperor.dictate.database.DictateDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MIGRATION_10_11] (ADR — prompt pill types).
 *
 * **Local-only — no CI run today** (same status as `MigrationTo10Test`).
 * Execute via `./gradlew connectedDebugAndroidTest` on a device/emulator.
 *
 * Coverage:
 *  1. `[bracketed]` prompt → `type = 'TEXT'`, outer brackets stripped.
 *  2. `"  [x]  "` (whitespace) → TEXT, trimmed + stripped to `x`.
 *  3. plain instruction → `type = 'PROMPT'`, unchanged.
 *  4. `"[a] und [b]"` → TEXT with inner `a] und [b` (simple-rule edge case, F4).
 *  5. a fully-bracketed name is stripped too (F2), independent of type.
 *  6. `requires_selection` / `auto_apply` / `pos` survive the recreate.
 *  7. the `type` CHECK rejects an unknown value.
 *
 * These SQL-side cases intentionally mirror `PromptTypeClassifierTest` so the
 * migration and the shared classifier stay pinned to the same result.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo11Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun seedPrompt(
        db: SupportSQLiteDatabase,
        id: Int,
        name: String,
        prompt: String,
        requiresSelection: Int = 0,
        autoApply: Int = 0,
        pos: Int = id
    ) {
        db.execSQL(
            "INSERT INTO prompts (id, pos, name, prompt, requires_selection, auto_apply) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, pos, name, prompt, requiresSelection, autoApply)
        )
    }

    private fun SupportSQLiteDatabase.readString(id: Int, column: String): String? =
        query("SELECT $column FROM prompts WHERE id = $id").use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    private fun SupportSQLiteDatabase.readInt(id: Int, column: String): Int =
        query("SELECT $column FROM prompts WHERE id = $id").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    // 1, 2, 3, 4, 5, 6 in one migration run
    @Test
    fun migrate10To11_classifiesRows() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            seedPrompt(db, id = 1, name = "Greeting", prompt = "[Beste Grüße]")
            seedPrompt(db, id = 2, name = "Whitespace", prompt = "  [x]  ")
            seedPrompt(db, id = 3, name = "Formalize", prompt = "Make it formal")
            seedPrompt(db, id = 4, name = "Ambiguous", prompt = "[a] und [b]")
            seedPrompt(db, id = 5, name = "[Dictate is great]", prompt = "[Dictate is great]",
                requiresSelection = 1, autoApply = 1, pos = 42)
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        // 1
        assertEquals("TEXT", db.readString(1, "type"))
        assertEquals("Beste Grüße", db.readString(1, "prompt"))
        // 2
        assertEquals("TEXT", db.readString(2, "type"))
        assertEquals("x", db.readString(2, "prompt"))
        // 3
        assertEquals("PROMPT", db.readString(3, "type"))
        assertEquals("Make it formal", db.readString(3, "prompt"))
        // 4
        assertEquals("TEXT", db.readString(4, "type"))
        assertEquals("a] und [b", db.readString(4, "prompt"))
        // 5 — name + prompt stripped, flags/pos preserved
        assertEquals("TEXT", db.readString(5, "type"))
        assertEquals("Dictate is great", db.readString(5, "prompt"))
        assertEquals("Dictate is great", db.readString(5, "name"))
        assertEquals(1, db.readInt(5, "requires_selection"))
        assertEquals(1, db.readInt(5, "auto_apply"))
        assertEquals(42, db.readInt(5, "pos"))
    }

    // 7
    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate10To11_typeCheck_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 10).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)
        db.execSQL(
            "INSERT INTO prompts (pos, name, prompt, requires_selection, auto_apply, type) " +
                "VALUES (0, 'bad', 'x', 0, 0, 'BANANA')"
        )
    }

    companion object {
        private const val TEST_DB = "migration-test-11"
    }
}
