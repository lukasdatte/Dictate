package net.devemperor.dictate.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.devemperor.dictate.rewording.PromptImportExport
import net.devemperor.dictate.shared.config.CatalogCodec
import net.devemperor.dictate.shared.config.CatalogEntry
import net.devemperor.dictate.shared.config.CatalogFileV3
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.testutil.ConfigMigrationScenario
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §10.4 import dispatcher + §10.5 v3 export against a real in-memory Room v12 schema:
 * credentials never leave in a file export (D5), a profile export carries its closure, a v3
 * round-trip survives, tampered hashes are rejected (§5.3), and legacy prompt files land through
 * the unchanged ADR-0024 parser WITH the uuid/hash backfill.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogImportExportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DictateDatabase
    private lateinit var repo: ConfigRepository

    @Before
    fun setUp() {
        db = ConfigMigrationScenario.inMemoryDb(context)
        repo = ConfigRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private fun seedProviderChain(): Triple<ProviderConfigEntity, ModelRefEntity, ProfileEntity> {
        val provider = repo.upsertProviderConfig(
            ProviderConfigEntity(id = "prov-1", providerType = ProviderType.OPENAI, label = "OpenAI"),
        )
        val model = repo.upsertModelRef(
            ModelRefEntity(
                id = "model-1",
                providerRef = provider.id,
                modelId = "gpt-4o-mini",
                function = ModelFunction.COMPLETION,
                parameterDefaults = mapOf("temperature" to "0.7"),
            ),
        )
        val profile = repo.upsertProfile(
            ProfileEntity(
                id = "profile-1",
                name = "Work",
                completionModelRef = model.id,
                orderedPrompts = listOf(ProfilePromptRef("prompt-uuid-1", true)),
            ),
        )
        db.promptDao().insert(
            PromptEntity(
                id = 0, pos = 0, name = "Fix", prompt = "Fix grammar",
                requiresSelection = false, autoApply = true,
                type = PromptType.PROMPT.name, uuid = "prompt-uuid-1",
                contentHash = "", updatedAt = 1L,
            ),
        )
        db.promptDao().insert(
            PromptEntity(
                id = 0, pos = 1, name = "Sig", prompt = "-- Lukas",
                requiresSelection = false, autoApply = false,
                type = PromptType.TEXT.name, uuid = "text-uuid-1",
            ),
        )
        return Triple(provider, model, profile)
    }

    // ── Export (§10.5) ────────────────────────────────────────────────────────────────────────

    @Test
    fun fullCatalog_neverContainsCredentials() {
        seedProviderChain()
        repo.upsertCredential(
            net.devemperor.dictate.shared.config.ApiCredentialEntity(
                id = "cred-1", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = "abcd",
            ),
        )
        val catalog = CatalogExport.fullCatalog(db)
        assertTrue(catalog.entities.isNotEmpty())
        assertTrue(
            "file export must not carry credentials (D5/F12)",
            catalog.entities.none { it is CatalogEntry.Credential },
        )
    }

    @Test
    fun fullCatalog_excludesTextPills() {
        seedProviderChain()
        val prompts = CatalogExport.fullCatalog(db).entities.filterIsInstance<CatalogEntry.Prompt>()
        assertEquals(listOf("prompt-uuid-1"), prompts.map { it.entity.id })
    }

    @Test
    fun profileCatalog_carriesReferencedClosure() {
        val (provider, model, profile) = seedProviderChain()
        // Noise that must NOT be exported with the profile:
        repo.upsertProviderConfig(
            ProviderConfigEntity(id = "prov-2", providerType = ProviderType.GROQ, label = "Groq"),
        )
        val catalog = CatalogExport.profileCatalog(db, profile.id)!!
        val kinds = catalog.entities.groupBy { it::class.simpleName }
        assertEquals(listOf(provider.id), kinds["Provider"]!!.map { (it as CatalogEntry.Provider).entity.id })
        assertEquals(listOf(model.id), kinds["Model"]!!.map { (it as CatalogEntry.Model).entity.id })
        assertEquals(listOf("prompt-uuid-1"), kinds["Prompt"]!!.map { (it as CatalogEntry.Prompt).entity.id })
        assertEquals(listOf(profile.id), kinds["Profile"]!!.map { (it as CatalogEntry.Profile).entity.id })
    }

    @Test
    fun profileCatalog_unknownIdIsNull() {
        assertNull(CatalogExport.profileCatalog(db, "nope"))
    }

    // ── Detect (§10.4) ────────────────────────────────────────────────────────────────────────

    @Test
    fun detect_v3VersusLegacy() {
        assertEquals(CatalogImport.Format.V3, CatalogImport.detect("""{"version":3,"entities":[]}"""))
        assertEquals(CatalogImport.Format.LEGACY_PROMPTS, CatalogImport.detect("""{"version":2,"prompts":[]}"""))
        assertEquals(CatalogImport.Format.LEGACY_PROMPTS, CatalogImport.detect("""[{"name":"a","prompt":"b"}]"""))
        assertEquals(CatalogImport.Format.LEGACY_PROMPTS, CatalogImport.detect("not json"))
    }

    // ── v3 import (§10.4, §5.3) ───────────────────────────────────────────────────────────────

    @Test
    fun v3RoundTrip_importIntoFreshDb() {
        seedProviderChain()
        val encoded = CatalogCodec.encode(CatalogExport.fullCatalog(db))

        val fresh = ConfigMigrationScenario.inMemoryDb(context)
        try {
            val result = CatalogImport.importV3(encoded, fresh)
            assertTrue("expected V3Imported, got $result", result is CatalogImport.Result.V3Imported)
            assertNotNull(fresh.providerConfigDao().byId("prov-1"))
            assertNotNull(fresh.modelRefDao().byId("model-1"))
            val profile = fresh.profileDao().byId("profile-1")!!
            assertEquals("Work", profile.name)
            assertEquals(1, fresh.profileDao().promptsOf("profile-1").size)
            val prompt = fresh.promptDao().getAll().single()
            assertEquals("prompt-uuid-1", prompt.uuid)
            assertEquals(PromptType.PROMPT, prompt.typeEnum)
            assertTrue(prompt.contentHash.isNotEmpty())
        } finally {
            fresh.close()
        }
    }

    @Test
    fun v3Import_updatesExistingPromptByUuid_keepingPillType() {
        seedProviderChain()
        val encoded = CatalogCodec.encode(CatalogExport.fullCatalog(db))
        val result = CatalogImport.importV3(encoded, db)
        assertTrue(result is CatalogImport.Result.V3Imported)
        // Still two rows (matched by uuid, no duplicate), TEXT pill untouched.
        val prompts = db.promptDao().getAll()
        assertEquals(2, prompts.size)
        assertEquals(PromptType.TEXT, prompts.first { it.uuid == "text-uuid-1" }.typeEnum)
    }

    @Test
    fun v3Import_rejectsTamperedContentHash() {
        seedProviderChain()
        val encoded = CatalogCodec.encode(CatalogExport.fullCatalog(db))
        // Tamper: change a payload value without recomputing the carried hash.
        val tampered = encoded.replace("\"Work\"", "\"Evil\"")
        val result = CatalogImport.importV3(tampered, db)
        assertTrue("expected Invalid (hash mismatch), got $result", result is CatalogImport.Result.Invalid)
    }

    @Test
    fun v3Import_malformedFile() {
        assertTrue(CatalogImport.importV3("{broken", db) is CatalogImport.Result.Malformed)
        assertTrue(
            CatalogImport.importV3("""{"version":3,"entities":[{"kind":"alien"}]}""", db)
                is CatalogImport.Result.Malformed,
        )
    }

    /**
     * Regression (finding logic-C-1): a file from a NEWER writer carrying an unknown additive payload
     * field, with a carried `contentHash` computed over that superset payload, must import — the §5.3
     * check recomputes from the raw file bytes (not the lossy `ignoreUnknownKeys` decode), so the
     * additive field survives into the hash. Red on the pre-fix typed recompute (mismatch → Invalid).
     */
    @Test
    fun v3Import_acceptsAdditiveFieldFromNewerWriter() {
        seedProviderChain()
        val encoded = CatalogCodec.encode(CatalogExport.fullCatalog(db))

        val tree = Json.parseToJsonElement(encoded).jsonObject
        val patchedEntities = tree["entities"]!!.jsonArray.map { entry ->
            val obj = entry.jsonObject
            if (obj["kind"]?.jsonPrimitive?.content != "prompt") return@map entry
            val payload = obj["entity"]!!.jsonObject.toMutableMap()
            // Newer writer adds an additive field and hashes the SUPERSET payload (envelope stripped).
            payload["futureField"] = JsonPrimitive("from-a-newer-app-version")
            payload["contentHash"] = JsonPrimitive(contentHashOfElement(JsonObject(payload)))
            JsonObject(obj.toMutableMap().apply { put("entity", JsonObject(payload)) })
        }
        val newerFile = JsonObject(
            tree.toMutableMap().apply { put("entities", JsonArray(patchedEntities)) },
        ).toString()

        val fresh = ConfigMigrationScenario.inMemoryDb(context)
        try {
            val result = CatalogImport.importV3(newerFile, fresh)
            assertTrue(
                "a newer-writer file with an additive payload field must import, got $result",
                result is CatalogImport.Result.V3Imported,
            )
        } finally {
            fresh.close()
        }
    }

    /**
     * Regression (finding logic-C-3): appending must use `MAX(pos)+1`, not `COUNT(*)`. With a gap in
     * `pos` (a middle row deleted), `COUNT(*)` hands out an already-occupied position and two rows
     * collide on the same `pos`. Red on the pre-fix `dao.count()` append.
     */
    @Test
    fun appendLegacyPrompts_assignsUniquePosDespiteGapInPositions() {
        val dao = db.promptDao()
        dao.insert(PromptEntity(id = 0, pos = 0, name = "A", prompt = "a", type = PromptType.PROMPT.name, uuid = "u0"))
        val midId = dao.insert(
            PromptEntity(id = 0, pos = 1, name = "B", prompt = "b", type = PromptType.PROMPT.name, uuid = "u1"),
        ).toInt()
        dao.insert(PromptEntity(id = 0, pos = 2, name = "C", prompt = "c", type = PromptType.PROMPT.name, uuid = "u2"))
        dao.deleteById(midId) // positions now {0, 2}, COUNT(*) = 2 → would collide at pos 2

        CatalogImport.appendLegacyPrompts(
            db,
            listOf(PromptEntity(id = 0, pos = 0, name = "New", prompt = "new", type = PromptType.PROMPT.name)),
        ) { 1L }

        val positions = dao.getAll().map { it.pos }
        assertEquals("no two prompts may share a pos", positions.size, positions.toSet().size)
        assertEquals(3, dao.getAll().first { it.name == "New" }.pos)
    }

    // ── Legacy path (§10.4 v1/v2) ─────────────────────────────────────────────────────────────

    @Test
    fun legacyImport_parsesViaAdr0024PathAndBackfills() {
        val v1 = """[{"name":"Übersetzer","prompt":"Translate to French"},{"name":"[Sig]","prompt":"[-- L]"}]"""
        val parsed = PromptImportExport.parse(v1)
        CatalogImport.appendLegacyPrompts(db, parsed) { 99L }

        val rows = db.promptDao().getAll()
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertTrue("uuid backfilled", row.uuid.isNotEmpty())
            assertEquals(PromptHashing.contentHashOf(row.uuid, row), row.contentHash)
            assertEquals(99L, row.updatedAt)
        }
        // ADR-0024 classification preserved: bracketed v1 prompt becomes a TEXT pill.
        assertEquals(PromptType.TEXT, rows.first { it.name == "Sig" }.typeEnum)
    }
}
