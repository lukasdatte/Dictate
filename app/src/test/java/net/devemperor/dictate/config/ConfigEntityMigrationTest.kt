package net.devemperor.dictate.config

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.secrets.InMemoryKekProvider
import net.devemperor.dictate.secrets.SecretsMigration
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Invariants of the config-entity migration (spec §8 / AK6–AK7): a rollback backup is written
 * first, the run is idempotent, no plaintext key lands in a config table, and prompts are backfilled
 * with a stable uuid + content hash under an active Default profile.
 *
 * Runs the realistic startup order B2 [SecretsMigration] → C2 [ConfigEntityMigration] through a
 * real [AndroidKeystoreSecretStore] (with the [InMemoryKekProvider] seam) so the credential re-map
 * is exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigEntityMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var db: DictateDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        File(context.filesDir, PrefsBackup.BACKUP_DIR).deleteRecursively()
        File(context.filesDir, "backup").deleteRecursively()
    }

    private fun freshDb(seedPrompts: List<PromptEntity> = emptyList()): DictateDatabase {
        val database = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries().build()
        db = database
        if (seedPrompts.isNotEmpty()) database.promptDao().insertAll(seedPrompts)
        return database
    }

    private fun realStore() =
        AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(), hardwareBacked = false)

    private fun completionFixture(key: String = "sk-ant-secret"): FakeSharedPreferences =
        FakeSharedPreferences().apply {
            edit()
                .put(Pref.RewordingProvider, "ANTHROPIC")
                .put(Pref.RewordingAnthropicModel, "claude-sonnet-4-20250514")
                .put(Pref.RewordingApiKeyAnthropic, key)
                .apply()
        }

    @Test
    fun `creates active Default profile with model refs`() {
        val sp = completionFixture()
        val store = realStore()
        SecretsMigration.run(context, sp, store)
        val database = freshDb()
        ConfigEntityMigration.run(context, sp, database, store)

        val activeId = sp.get(Pref.ActiveProfileId)
        assertTrue("ActiveProfileId set", activeId.isNotEmpty())
        assertEquals(ConfigEntityMigration.CURRENT_MIGRATION_VERSION, sp.get(Pref.ConfigEntityMigrationDone))

        val profile = database.profileDao().byId(activeId)
        assertNotNull(profile)
        assertEquals("Default", profile!!.name)
        assertNotNull("completion model ref set", profile.completionModelRef)
        assertNotNull("transcription model ref set (default OpenAI chain)", profile.transcriptionModelRef)

        val model = database.modelRefDao().byId(profile.completionModelRef!!)!!
        assertEquals("claude-sonnet-4-20250514", model.modelId)
    }

    @Test
    fun `second run is a no-op (idempotent, deterministic ids)`() {
        val sp = completionFixture()
        val store = realStore()
        SecretsMigration.run(context, sp, store)
        val database = freshDb()

        ConfigEntityMigration.run(context, sp, database, store)
        val providers = database.providerConfigDao().count()
        val credentials = database.apiCredentialDao().count()
        val models = database.modelRefDao().count()
        val profiles = database.profileDao().count()

        ConfigEntityMigration.run(context, sp, database, store) // second run

        assertEquals(providers, database.providerConfigDao().count())
        assertEquals(credentials, database.apiCredentialDao().count())
        assertEquals(models, database.modelRefDao().count())
        assertEquals(profiles, database.profileDao().count())
    }

    @Test
    fun `writes rollback backup before migrating`() {
        val sp = completionFixture()
        val store = realStore()
        SecretsMigration.run(context, sp, store)
        ConfigEntityMigration.run(context, sp, freshDb(), store)

        val backupDir = File(context.filesDir, PrefsBackup.BACKUP_DIR)
        val backup = PrefsBackup.existingBackup(backupDir)
        assertNotNull("backup file present", backup)
        val json = backup!!.readText()
        // The C2 backup mirrors the config prefs it migrates (the keys are already in B2's own backup).
        assertTrue("backup captures the provider selection", json.contains(Pref.RewordingProvider.key))
    }

    @Test
    fun `no plaintext key in any config table and key retrievable via credential ref`() {
        val secret = "sk-ant-VERY-secret-123"
        val sp = completionFixture(secret)
        val store = realStore()
        SecretsMigration.run(context, sp, store)
        val database = freshDb()
        ConfigEntityMigration.run(context, sp, database, store)

        val credential = database.apiCredentialDao().getAll().single()
        assertFalse("fingerprint is not the key", credential.keyFingerprint.contains(secret))
        assertEquals(16, credential.keyFingerprint.length)

        // The key prefs are gone (B2, AK6) and no config-table string column holds the plaintext.
        assertFalse(sp.contains(Pref.RewordingApiKeyAnthropic.key))
        val allText = buildString {
            database.providerConfigDao().getAll().forEach { append(it) }
            database.apiCredentialDao().getAll().forEach { append(it) }
            database.modelRefDao().getAll().forEach { append(it) }
            database.profileDao().getAll().forEach { append(it) }
        }
        assertFalse("no config row contains the plaintext key", allText.contains(secret))

        // The key is retrievable via the credential SecretRef (proves the re-map worked).
        val bytes = store.get(SecretRef(ConfigSecrets.CREDENTIAL_NAMESPACE, credential.id))
        assertEquals(secret, bytes?.let { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `backfills prompts with stable uuid and content hash`() {
        val sp = completionFixture()
        val store = realStore()
        SecretsMigration.run(context, sp, store)
        val database = freshDb(
            seedPrompts = listOf(
                PromptEntity(id = 1, pos = 0, name = "Formalize", prompt = "Make it formal", autoApply = true),
                PromptEntity(id = 2, pos = 1, name = "Snippet", prompt = "hello", type = "TEXT"),
            ),
        )
        ConfigEntityMigration.run(context, sp, database, store)

        val prompts = database.promptDao().getAll()
        prompts.forEach {
            assertTrue("uuid backfilled for ${it.name}", it.uuid.isNotEmpty())
            assertTrue("content_hash backfilled for ${it.name}", it.contentHash.isNotEmpty())
        }
        val uuidById = prompts.associate { it.id to it.uuid }

        // A second run keeps the already-assigned uuids (§8.6). Reset the flag to force a re-run.
        sp.edit().put(Pref.ConfigEntityMigrationDone, 0).apply()
        ConfigEntityMigration.run(context, sp, database, store)
        database.promptDao().getAll().forEach { assertEquals(uuidById[it.id], it.uuid) }

        // The Default profile references the prompt uuids in order.
        val profile = database.profileDao().byId(sp.get(Pref.ActiveProfileId))!!
        val refs = database.profileDao().promptsOf(profile.id)
        assertEquals(uuidById[1], refs.first { it.pos == 0 }.promptRef)
        assertTrue("auto_apply preserved", refs.first { it.pos == 0 }.autoApply)
    }

    @Test
    fun `defers when secret store is unavailable`() {
        val sp = completionFixture()
        val database = freshDb()
        ConfigEntityMigration.run(context, sp, database, unavailableStore())

        assertEquals(0, sp.get(Pref.ConfigEntityMigrationDone))
        assertEquals(0, database.profileDao().count())
        assertEquals(0, database.providerConfigDao().count())
    }

    private fun unavailableStore(): SecretStore = object : SecretStore {
        override fun get(ref: SecretRef): ByteArray? = null
        override fun put(ref: SecretRef, value: ByteArray) = Unit
        override fun delete(ref: SecretRef) = Unit
        override val available: Boolean = false
        override val hardwareBacked: Boolean = false
    }
}
