package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightCatalogAuditLog
import net.devemperor.dictate.companion.data.SqlDelightCatalogRepository
import net.devemperor.dictate.companion.fakes.FakeSecretStore
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.config.CanonicalJson
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CatalogService] against the REAL SqlDelight repositories on an in-memory database
 * (the invariants worth testing — SHARED filtering, the canonical payload, the audit write — live in
 * the SQL + the canonicaliser, not in a hand-written fake).
 *
 * Covers AC3 (root-hash determinism), AC5 at the service level (the secret never appears in an index
 * or entity payload; the credential call delivers it and writes exactly one audit row), and the
 * 404-on-unknown/private/credential-on-entity paths.
 */
class CatalogServiceTest {

    private val database = CompanionDatabase.inMemory()
    private val clock = MutableClock()
    private val config = CompanionConfigRepository(database, now = clock::nowMillis)
    private val secretStore = FakeSecretStore()
    private val auditLog = SqlDelightCatalogAuditLog(database)
    private val service = CatalogService(SqlDelightCatalogRepository(config), secretStore, auditLog, clock)

    private val sentinel = "SENTINEL-SECRET-sk-proj-abc123"

    private fun sharedPrompt(id: String, name: String = "Prompt $id", text: String = "Reword this") =
        config.save(PromptV3Entity(id = id, name = name, text = text, visibility = Visibility.SHARED))

    private fun privatePrompt(id: String) =
        config.save(PromptV3Entity(id = id, name = "private", text = "hidden", visibility = Visibility.PRIVATE))

    /** A SHARED provider config that references a credential, plus the secret it resolves to. */
    private fun sharedCredentialProvider(id: String, credentialRef: String) {
        secretStore.put(CredentialSecrets.credentialRef(credentialRef), sentinel.toByteArray())
        config.save(
            ProviderConfigEntity(
                id = id,
                providerType = ProviderType.OPENAI,
                label = "My OpenAI",
                credentialRef = credentialRef,
                visibility = Visibility.SHARED,
            ),
        )
    }

    // ── AC3: root-hash determinism ──────────────────────────────────────────────────────

    @Test
    fun rootHash_sameEntitySet_sameHash_regardlessOfInsertionOrder() {
        sharedPrompt("p-1"); sharedPrompt("p-2"); sharedPrompt("p-3")
        val first = service.index().rootHash

        // A fresh service over a fresh DB, same content inserted in a different order.
        val db2 = CompanionDatabase.inMemory()
        val config2 = CompanionConfigRepository(db2, now = clock::nowMillis)
        val service2 = CatalogService(SqlDelightCatalogRepository(config2), FakeSecretStore(), SqlDelightCatalogAuditLog(db2), clock)
        config2.save(PromptV3Entity(id = "p-3", name = "Prompt p-3", text = "Reword this", visibility = Visibility.SHARED))
        config2.save(PromptV3Entity(id = "p-1", name = "Prompt p-1", text = "Reword this", visibility = Visibility.SHARED))
        config2.save(PromptV3Entity(id = "p-2", name = "Prompt p-2", text = "Reword this", visibility = Visibility.SHARED))

        assertEquals(first, service2.index().rootHash)
    }

    @Test
    fun rootHash_changesWhenExactlyOneEntityChanges() {
        sharedPrompt("p-1"); sharedPrompt("p-2")
        val before = service.index().rootHash

        sharedPrompt("p-2", text = "A different instruction entirely")

        assertNotEquals(before, service.index().rootHash)
    }

    @Test
    fun index_excludesPrivateEntities() {
        sharedPrompt("p-1")
        privatePrompt("p-hidden")

        val ids = service.index().entries.map { it.id }

        assertTrue(ids.contains("p-1"))
        assertFalse(ids.contains("p-hidden"))
    }

    // ── AC5: credential isolation + audit ─────────────────────────────────────────────────

    @Test
    fun index_carriesTheCredentialButNotItsSecret() {
        sharedCredentialProvider(id = "prov-1", credentialRef = "cred-1")

        val index = service.index()
        val credentialEntry = index.entries.single { it.kind == CatalogEntityKindWire.CREDENTIAL }

        assertEquals("cred-1", credentialEntry.id)
        // The whole serialized index must not contain the sentinel — only a fingerprint hash.
        val json = CanonicalJson.json.encodeToString(net.devemperor.dictate.shared.protocol.CatalogIndexResponse.serializer(), index)
        assertFalse("secret leaked into the index", json.contains(sentinel))
        assertNotEquals(sentinel, credentialEntry.contentHash)
    }

    @Test
    fun entity_neverServesACredential() {
        sharedCredentialProvider(id = "prov-1", credentialRef = "cred-1")

        // A credential id is not a non-credential entity → 404, its secret unreachable via /entity.
        assertThrows { service.entity("cred-1") }
    }

    @Test
    fun credential_deliversTheSecretAndWritesExactlyOneAuditRow() {
        sharedCredentialProvider(id = "prov-1", credentialRef = "cred-1")
        clock.now = 1_700_000_123_000L

        val response = service.credential("cred-1", peerDeviceId = "peer-device-9")

        assertEquals(sentinel, response.secret)
        assertEquals("OPENAI", response.provider)

        val audit = auditLog.accessFor("cred-1")
        assertEquals(1, audit.size)
        assertEquals("peer-device-9", audit.single().peerDeviceId)
        assertEquals(CatalogEntityKindWire.CREDENTIAL, audit.single().kind)
        assertEquals(1_700_000_123_000L, audit.single().at)
    }

    @Test
    fun credential_forAnUnknownId_is404_andWritesNoAudit() {
        assertThrows { service.credential("nope", peerDeviceId = "peer-device-9") }
        assertTrue(auditLog.all().isEmpty())
    }

    // ── §6.3: the entity payload re-hashes to its contentHash ──────────────────────────────

    @Test
    fun entity_payloadReHashesToItsContentHash() {
        val saved = sharedPrompt("p-1")

        val entity = service.entity("p-1")

        assertEquals(saved.contentHash, entity.contentHash)
        // The receiver's verify path (§6.3): re-hash the canonical payload, expect the carried hash.
        assertEquals(entity.contentHash, contentHashOfElement(CanonicalJson.json.parseToJsonElement(entity.payload)))
    }

    @Test
    fun entity_forAPrivateEntity_is404() {
        privatePrompt("p-hidden")
        assertThrows { service.entity("p-hidden") }
    }

    private fun assertThrows(block: () -> Unit) {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue(
            "expected CatalogEntityNotFoundException, got $thrown",
            thrown is CompanionException.CatalogEntityNotFoundException,
        )
    }

    @Test
    fun index_emptyOffer_isDeterministicAndValid() {
        // No shared entities → empty entries, a well-formed 64-hex root hash of the empty join.
        assertNull(service.index().entries.firstOrNull())
    }

    @Test
    fun index_toleratesALongEntityName_upToTheConfigLimit() {
        // A config name may be up to 200 chars (ConfigValidations.MAX_NAME); the index label cap must
        // accommodate it, or the wire encode (respondProtocol) would throw on a legitimately long-named
        // shared prompt. Encoding through the SAME validation the route applies is the real guard.
        val longName = "N".repeat(200)
        sharedPrompt("p-long", name = longName)

        val index = service.index()
        assertEquals(longName, index.entries.single { it.id == "p-long" }.label)
        // Must not throw ProtocolViolationException — this is exactly what a 500 on /v1/catalog would be.
        net.devemperor.dictate.shared.protocol.ProtocolCodec.encode(
            index,
            net.devemperor.dictate.shared.protocol.CatalogIndexResponse.serializer(),
            net.devemperor.dictate.shared.protocol.Validations.catalogIndexResponse,
        )
    }
}
