package net.devemperor.dictate.database

import android.content.Context
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
import net.devemperor.dictate.database.migration.createPartialUniqueIndices

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
    version = 3,
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

        private fun buildDatabase(context: Context): DictateDatabase {
            val appContext = context.applicationContext
            return Room.databaseBuilder(
                appContext,
                DictateDatabase::class.java,
                DATABASE_NAME
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Create partial unique indices that Room annotations cannot express.
                        // Using IF NOT EXISTS so this is safe to run on every open.
                        createPartialUniqueIndices(db)
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
