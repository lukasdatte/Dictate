package net.devemperor.dictate.database

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import net.devemperor.dictate.R
import net.devemperor.dictate.database.converter.Converters
import net.devemperor.dictate.database.dao.CompletionLogDao
import net.devemperor.dictate.database.dao.ProcessingStepDao
import net.devemperor.dictate.database.dao.PromptDao
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.dao.TextInsertionDao
import net.devemperor.dictate.database.dao.TranscriptionDao
import net.devemperor.dictate.database.dao.UsageDao
import net.devemperor.dictate.database.entity.CompletionLogEntity
import net.devemperor.dictate.database.entity.ProcessingStepEntity
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.TextInsertionEntity
import net.devemperor.dictate.database.entity.TranscriptionEntity
import net.devemperor.dictate.database.entity.UsageEntity
import net.devemperor.dictate.database.migration.MIGRATION_1_2
import net.devemperor.dictate.database.migration.MIGRATION_2_3
import net.devemperor.dictate.database.migration.MIGRATION_3_4
import net.devemperor.dictate.database.migration.MIGRATION_4_5
import net.devemperor.dictate.database.migration.MIGRATION_5_6
import net.devemperor.dictate.database.migration.MIGRATION_6_7

@Database(
    entities = [
        UsageEntity::class,
        PromptEntity::class,
        SessionEntity::class,
        TranscriptionEntity::class,
        ProcessingStepEntity::class,
        CompletionLogEntity::class,
        TextInsertionEntity::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class DictateDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao
    abstract fun promptDao(): PromptDao
    abstract fun sessionDao(): SessionDao
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun processingStepDao(): ProcessingStepDao
    abstract fun completionLogDao(): CompletionLogDao
    abstract fun textInsertionDao(): TextInsertionDao

    companion object {
        private const val DATABASE_NAME = "dictate.db"

        @Volatile
        private var instance: DictateDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): DictateDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        /**
         * **Test-only.** Closes and drops the process-wide singleton so
         * the next [getInstance] builds a fresh database.
         *
         * F-9 (B5): the singleton is shared across the Robolectric JVM
         * fork. Robolectric reuses application state across tests, and a
         * test that boots `DictatePipelineService` (which runs
         * `LegacyAudioFileMigration` + creates session rows on every
         * `onCreate`) can leave the DB / pref state in a shape a
         * sibling test's `deleteAll()`-only reset does not fully
         * neutralise — surfacing as the flaky
         * `LegacyAudioFileMigrationTest` failure. Affected tests call
         * this in `@Before`/`@After` so their pre-state is
         * deterministic regardless of fork co-location, instead of
         * depending on fork-scheduling. Idempotent — a no-op when no
         * singleton has been built.
         */
        @JvmStatic
        @VisibleForTesting
        fun resetForTest(context: Context) {
            synchronized(this) {
                instance?.let { db ->
                    if (db.isOpen) {
                        try {
                            db.close()
                        } catch (ignored: Throwable) {
                            // Closing an already-closing DB is harmless;
                            // the reset still drops the reference below.
                        }
                    }
                }
                instance = null
                // The production DB is FILE-backed (`dictate.db`), not
                // in-memory — closing the connection alone leaves the
                // file (and any rows a sibling test's
                // `DictatePipelineService` boot wrote) on disk across
                // the Robolectric JVM fork. Delete the file so the next
                // `getInstance` truly starts from an empty schema.
                runCatching {
                    context.applicationContext.deleteDatabase(DATABASE_NAME)
                }
            }
        }

        private fun buildDatabase(context: Context): DictateDatabase {
            val appContext = context.applicationContext
            return Room.databaseBuilder(
                appContext,
                DictateDatabase::class.java,
                DATABASE_NAME
            )
                .allowMainThreadQueries()
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Insert default prompts (mirrors PromptsDatabaseHelper.onCreate()).
                        // IMPORTANT: Do NOT call getInstance().promptDao() here!
                        // onCreate() runs DURING build(), before instance is set.
                        // Circular reference would cause deadlock or NPE.
                        // Instead: raw SQL INSERT via SupportSQLiteDatabase.
                        val defaultPrompts = listOf(
                            DefaultPrompt(0, R.string.dictate_example_prompt_one_name, R.string.dictate_example_prompt_one_prompt, requiresSelection = true),
                            DefaultPrompt(1, R.string.dictate_example_prompt_two_name, R.string.dictate_example_prompt_two_prompt, requiresSelection = true),
                            DefaultPrompt(2, R.string.dictate_example_prompt_three_name, R.string.dictate_example_prompt_three_prompt, requiresSelection = true),
                            DefaultPrompt(3, R.string.dictate_example_prompt_four_name, R.string.dictate_example_prompt_four_prompt, requiresSelection = false),
                            DefaultPrompt(4, R.string.dictate_example_prompt_five_name, R.string.dictate_example_prompt_five_prompt, requiresSelection = false),
                        )
                        for (prompt in defaultPrompts) {
                            db.execSQL(
                                "INSERT INTO prompts (pos, name, prompt, requires_selection, auto_apply) VALUES (?, ?, ?, ?, 0)",
                                arrayOf<Any>(
                                    prompt.pos,
                                    appContext.getString(prompt.nameRes),
                                    appContext.getString(prompt.promptRes),
                                    if (prompt.requiresSelection) 1 else 0
                                )
                            )
                        }
                    }

                })
                .build()
        }

        private data class DefaultPrompt(
            val pos: Int,
            val nameRes: Int,
            val promptRes: Int,
            val requiresSelection: Boolean
        )
    }
}
