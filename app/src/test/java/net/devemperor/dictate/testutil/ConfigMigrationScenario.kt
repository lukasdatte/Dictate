package net.devemperor.dictate.testutil

import android.content.Context
import androidx.room.Room
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.config.ConfigEntityMigration
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.secrets.InMemoryKekProvider
import net.devemperor.dictate.secrets.SecretsMigration

/**
 * Shared test scaffold for the B2 ([SecretsMigration]) → C2 ([ConfigEntityMigration]) startup order.
 *
 * The store + in-memory-Room + two-migration scaffolding was near-identically rebuilt in
 * `ConfigEntityMigrationTest`, `ProfileResolverCharacterizationTest` and `CatalogImportExportTest`
 * (audit C-TEST-4). It is defined once here. Callers compose the primitives they need rather than
 * one monolithic entry point, because the ordering constraints differ per test — most notably
 * `ProfileResolverCharacterizationTest` must snapshot the pre-migration pref path BEFORE B2 removes
 * the key prefs, so the store is built (and the snapshot taken) before [runB2C2] is invoked.
 */
object ConfigMigrationScenario {

    /** A real [AndroidKeystoreSecretStore] over an isolated pref map, with the software-KEK test seam. */
    fun realStore(): AndroidKeystoreSecretStore =
        AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(), hardwareBacked = false)

    /** A fresh in-memory Room [DictateDatabase] with main-thread queries allowed. */
    fun inMemoryDb(context: Context): DictateDatabase =
        Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries().build()

    /**
     * Runs the realistic startup order B2 → C2 against [sp]: runs [SecretsMigration], builds a fresh
     * in-memory DB (seeding [seedPrompts] before C2, mirroring a pre-existing prompt table), runs
     * [ConfigEntityMigration], and returns the DB. The caller owns [store] (build it — and snapshot
     * any pre-B2 pref state — before calling) and owns closing the returned DB.
     */
    fun runB2C2(
        context: Context,
        sp: FakeSharedPreferences,
        store: SecretStore,
        seedPrompts: List<PromptEntity> = emptyList(),
    ): DictateDatabase {
        SecretsMigration.run(context, sp, store)
        val db = inMemoryDb(context)
        if (seedPrompts.isNotEmpty()) db.promptDao().insertAll(seedPrompts)
        ConfigEntityMigration.run(context, sp, db, store)
        return db
    }
}
