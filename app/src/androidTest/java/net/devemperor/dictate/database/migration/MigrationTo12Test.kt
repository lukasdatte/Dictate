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
 * Instrumented tests for [MIGRATION_11_12] (config-entity model, spec §7 / AK4).
 *
 * **Local-only — no CI run today** (same status as [MigrationTo11Test]).
 * Execute via `./gradlew connectedDebugAndroidTest` on a device/emulator.
 *
 * Coverage:
 *  1. the migration validates against the exported 12.json schema (`runMigrationsAndValidate`);
 *  2. an existing `prompts` row survives the recreate with its columns + the new envelope defaults;
 *  3. each new table's Double-Enum CHECK **accepts** a valid enum value;
 *  4. each new table's Double-Enum CHECK **rejects** an unknown enum value;
 *  5. `profile_prompts` CASCADEs when its parent profile is deleted.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo12Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun SupportSQLiteDatabase.readString(sql: String): String? =
        query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    private fun SupportSQLiteDatabase.readInt(sql: String): Int =
        query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun migrate11To12_preservesPromptRow_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO prompts (id, pos, name, prompt, requires_selection, auto_apply, type) " +
                    "VALUES (7, 3, 'Greeting', 'Make it formal', 1, 0, 'PROMPT')",
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        assertEquals("Make it formal", db.readString("SELECT prompt FROM prompts WHERE id = 7"))
        assertEquals("PROMPT", db.readString("SELECT type FROM prompts WHERE id = 7"))
        assertEquals(3, db.readInt("SELECT pos FROM prompts WHERE id = 7"))
        // New envelope columns default in the recreate.
        assertEquals("", db.readString("SELECT uuid FROM prompts WHERE id = 7"))
        assertEquals("PRIVATE", db.readString("SELECT visibility FROM prompts WHERE id = 7"))
        assertEquals("LOCAL", db.readString("SELECT subscription_mode FROM prompts WHERE id = 7"))
    }

    @Test
    fun migrate11To12_acceptsValidEnumValues() {
        helper.createDatabase(TEST_DB, 11).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        db.execSQL(
            "INSERT INTO provider_configs (id, provider_type, kind, label, visibility, subscription_mode, content_hash, updated_at) " +
                "VALUES ('p1', 'OPENAI', 'LOCAL', 'OpenAI', 'PRIVATE', 'LOCAL', 'h', 0)",
        )
        db.execSQL(
            "INSERT INTO api_credentials (id, provider_type, label, key_fingerprint, visibility, subscription_mode, content_hash, updated_at) " +
                "VALUES ('c1', 'ANTHROPIC', 'Anthropic Key', 'abcd', 'PRIVATE', 'LOCAL', 'h', 0)",
        )
        db.execSQL(
            "INSERT INTO model_refs (id, provider_ref, model_id, function, parameter_defaults, visibility, subscription_mode, content_hash, updated_at) " +
                "VALUES ('m1', 'p1', 'gpt-4o-mini', 'COMPLETION', '{}', 'PRIVATE', 'LOCAL', 'h', 0)",
        )
        db.execSQL(
            "INSERT INTO profiles (id, name, style_prompt_mode, system_prompt_mode, ambiguity_mode, parameter_overrides, visibility, subscription_mode, content_hash, updated_at) " +
                "VALUES ('pr1', 'Default', 'PREDEFINED', 'CUSTOM', 'AUTO', '{}', 'SHARED', 'SUBSCRIBE', 'h', 0)",
        )
        db.execSQL("INSERT INTO profile_prompts (profile_id, pos, prompt_ref, auto_apply) VALUES ('pr1', 0, 'u1', 1)")

        assertEquals(1, db.readInt("SELECT COUNT(*) FROM provider_configs"))
        assertEquals(1, db.readInt("SELECT COUNT(*) FROM profile_prompts"))
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate11To12_providerType_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 11).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)
        db.execSQL(
            "INSERT INTO provider_configs (id, provider_type, kind, label, content_hash, updated_at) " +
                "VALUES ('bad', 'BANANA', 'LOCAL', 'x', 'h', 0)",
        )
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate11To12_ambiguityMode_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 11).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)
        db.execSQL(
            "INSERT INTO profiles (id, name, ambiguity_mode, content_hash, updated_at) " +
                "VALUES ('bad', 'x', 'MAYBE', 'h', 0)",
        )
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate11To12_modelFunction_rejectsUnknown() {
        helper.createDatabase(TEST_DB, 11).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)
        db.execSQL(
            "INSERT INTO model_refs (id, provider_ref, model_id, function, content_hash, updated_at) " +
                "VALUES ('bad', 'p', 'm', 'SUMMARIZE', 'h', 0)",
        )
    }

    @Test
    fun migrate11To12_profilePrompts_cascadeOnProfileDelete() {
        helper.createDatabase(TEST_DB, 11).use { }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO profiles (id, name, content_hash, updated_at) VALUES ('pr1', 'Default', 'h', 0)",
        )
        db.execSQL("INSERT INTO profile_prompts (profile_id, pos, prompt_ref, auto_apply) VALUES ('pr1', 0, 'u1', 0)")
        db.execSQL("DELETE FROM profiles WHERE id = 'pr1'")
        assertEquals(0, db.readInt("SELECT COUNT(*) FROM profile_prompts"))
    }

    companion object {
        private const val TEST_DB = "migration-test-12"
    }
}
