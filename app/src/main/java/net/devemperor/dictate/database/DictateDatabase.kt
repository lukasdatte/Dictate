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
import net.devemperor.dictate.database.dao.ConversationMessageDao
import net.devemperor.dictate.database.dao.ProcessingStepDao
import net.devemperor.dictate.database.dao.PromptDao
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.dao.TextInsertionDao
import net.devemperor.dictate.database.dao.TranscriptionDao
import net.devemperor.dictate.database.dao.UsageDao
import net.devemperor.dictate.database.entity.CompletionLogEntity
import net.devemperor.dictate.database.entity.ConversationMessageEntity
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
import net.devemperor.dictate.database.migration.MIGRATION_7_8
import net.devemperor.dictate.database.migration.MIGRATION_8_9
import net.devemperor.dictate.database.migration.MIGRATION_9_10
import net.devemperor.dictate.database.migration.MIGRATION_10_11
import net.devemperor.dictate.database.migration.MIGRATION_11_12
import net.devemperor.dictate.database.migration.MIGRATION_12_13
import net.devemperor.dictate.database.entity.PromptType
import net.devemperor.dictate.config.dao.ApiCredentialDao
import net.devemperor.dictate.config.dao.ModelRefDao
import net.devemperor.dictate.config.dao.ProfileDao
import net.devemperor.dictate.config.dao.ProviderConfigDao
import net.devemperor.dictate.config.entity.ApiCredentialRoomEntity
import net.devemperor.dictate.config.entity.ModelRefRoomEntity
import net.devemperor.dictate.config.entity.ProfilePromptRoomEntity
import net.devemperor.dictate.config.entity.ProfileRoomEntity
import net.devemperor.dictate.config.entity.ProviderConfigRoomEntity
import net.devemperor.dictate.peers.dao.PeerDao
import net.devemperor.dictate.peers.dao.SubscriptionDao
import net.devemperor.dictate.peers.entity.PeerRoomEntity
import net.devemperor.dictate.peers.entity.SubscriptionRoomEntity

@Database(
    entities = [
        UsageEntity::class,
        PromptEntity::class,
        SessionEntity::class,
        TranscriptionEntity::class,
        ProcessingStepEntity::class,
        CompletionLogEntity::class,
        TextInsertionEntity::class,
        ConversationMessageEntity::class,
        // Config-entity model (C2, spec §7).
        ProviderConfigRoomEntity::class,
        ApiCredentialRoomEntity::class,
        ModelRefRoomEntity::class,
        ProfileRoomEntity::class,
        ProfilePromptRoomEntity::class,
        // Peer-catalog subscriber model (E2, peer-katalog.md §5).
        PeerRoomEntity::class,
        SubscriptionRoomEntity::class
    ],
    version = 13,
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
    abstract fun conversationMessageDao(): ConversationMessageDao

    // ── Config-entity model (C2, spec §7) ──
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun apiCredentialDao(): ApiCredentialDao
    abstract fun modelRefDao(): ModelRefDao
    abstract fun profileDao(): ProfileDao

    // ── Peer-catalog subscriber model (E2, peer-katalog.md §5) ──
    abstract fun peerDao(): PeerDao
    abstract fun subscriptionDao(): SubscriptionDao

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
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
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
                            // Prompt 5 is a literal text pill (no brackets in strings.xml since v11).
                            DefaultPrompt(4, R.string.dictate_example_prompt_five_name, R.string.dictate_example_prompt_five_prompt, requiresSelection = false, type = PromptType.TEXT),
                        )
                        for (prompt in defaultPrompts) {
                            db.execSQL(
                                // The v12 envelope columns (uuid/visibility/subscription_mode/
                                // content_hash/updated_at) are NOT NULL with no SQLite default in the
                                // Room-generated fresh schema, so they must be listed here (same
                                // convention as `type`). The config-entity migration backfills the
                                // uuid/content_hash for these default prompts on first start (§8.5).
                                "INSERT INTO prompts (pos, name, prompt, requires_selection, auto_apply, type, uuid, visibility, subscription_mode, content_hash, updated_at) VALUES (?, ?, ?, ?, 0, ?, '', 'PRIVATE', 'LOCAL', '', 0)",
                                arrayOf<Any>(
                                    prompt.pos,
                                    appContext.getString(prompt.nameRes),
                                    appContext.getString(prompt.promptRes),
                                    if (prompt.requiresSelection) 1 else 0,
                                    prompt.type.name
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
            val requiresSelection: Boolean,
            val type: PromptType = PromptType.PROMPT
        )
    }
}
