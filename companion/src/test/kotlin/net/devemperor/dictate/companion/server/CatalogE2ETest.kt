package net.devemperor.dictate.companion.server

import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightCatalogAuditLog
import net.devemperor.dictate.companion.data.SqlDelightCatalogRepository
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.CatalogService
import net.devemperor.dictate.companion.fakes.FakeSecretStore
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The catalog family driven end-to-end: a **real** `embeddedServer(CIO, port = 0)` and the **real**
 * `:shared` [CatalogClient] over **real** HTTP on 127.0.0.1 — the same safety net as [CompanionE2ETest],
 * now for `/v1/catalog`.
 *
 * This is where the two sides are proven to agree on paths, headers, the 404 fork, the error envelope
 * and — the point of the block — that a delivered credential lands ONLY through the credential call
 * (AC1, AC4, AC5). The only fakes are the ones that have to be: the SecretStore (no OS keyring on this
 * Linux VM) and the clock.
 */
class CatalogE2ETest {

    private val inserter = FakeTextInserter()
    private val clock = MutableClock()
    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)
    private val config = CompanionConfigRepository(database, now = clock::nowMillis)
    private val secretStore = FakeSecretStore()
    private val auditLog = SqlDelightCatalogAuditLog(database)
    private val catalogService = CatalogService(SqlDelightCatalogRepository(config), secretStore, auditLog, clock)

    private lateinit var container: CompanionContainer
    private lateinit var server: CompanionServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        container = CompanionContainer.forTest(
            inserter = inserter,
            clock = clock,
            devices = devices,
            history = history,
            serverName = SERVER_NAME,
            catalogService = catalogService,
        )
        server = CompanionServer(container, hosts = listOf("127.0.0.1"), port = 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.boundPort()}"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── AC1: additivity ───────────────────────────────────────────────────────────────────

    @Test
    fun health_reportsCatalogSupport_whenTheServiceIsWired() {
        assertTrue(pairedDispatchClient().health().successValue().supportsCatalog)
    }

    @Test
    fun aCompanionWithoutTheCatalogService_answersEndpointMissing() {
        // A minimal graph (no catalogService) mounts no /v1/catalog route → bare 404 → EndpointMissing.
        val bareContainer = CompanionContainer.forTest(inserter = inserter, clock = clock, devices = devices, history = history, serverName = SERVER_NAME)
        val bareServer = CompanionServer(bareContainer, hosts = listOf("127.0.0.1"), port = 0)
        bareServer.start()
        try {
            val bareUrl = "http://127.0.0.1:${bareServer.boundPort()}"
            val client = CatalogClient(OkHttpDispatchTransport(bareUrl), credentials = { pairedCredentials() })

            assertEquals(DispatchError.EndpointMissing, client.index().failureError())
            assertFalse(pairedDispatchClient(bareUrl).health().successValue().supportsCatalog)
        } finally {
            bareServer.stop()
        }
    }

    // ── AC4: auth parity ──────────────────────────────────────────────────────────────────

    @Test
    fun catalogIndex_withoutPairing_isUnauthorized() {
        val client = CatalogClient(OkHttpDispatchTransport(baseUrl), credentials = { Credentials(DEVICE_ID, "never-issued-secret-000") })

        assertEquals(DispatchError.Unauthorized, client.index().failureError())
    }

    @Test
    fun catalogEntityAndCredential_withoutPairing_areUnauthorized() {
        seedSharedCredentialProvider()
        val client = CatalogClient(OkHttpDispatchTransport(baseUrl), credentials = { Credentials(DEVICE_ID, "never-issued-secret-000") })

        assertEquals(DispatchError.Unauthorized, client.entity("p-1").failureError())
        assertEquals(DispatchError.Unauthorized, client.credential("cred-1").failureError())
    }

    // ── Happy path over real HTTP ──────────────────────────────────────────────────────────

    @Test
    fun index_thenEntity_sharesAPromptOverTheWire() {
        val saved = config.save(PromptV3Entity(id = "p-1", name = "Rewrite formally", text = "Rewrite this formally.", visibility = Visibility.SHARED))
        val client = catalogClient()

        val index = client.index().successValue()
        val entry = index.entries.single()
        assertEquals("p-1", entry.id)
        assertEquals(CatalogEntityKindWire.PROMPT, entry.kind)
        assertEquals(saved.contentHash, entry.contentHash)

        val entity = client.entity("p-1").successValue()
        assertEquals(saved.contentHash, entity.contentHash)
        assertTrue("payload carries the prompt text", entity.payload.contains("Rewrite this formally."))
    }

    @Test
    fun entity_forAnUnknownId_isEntityGone() {
        val client = catalogClient()
        assertEquals(DispatchError.EntityGone, client.entity("does-not-exist").failureError())
    }

    // ── AC5: credential isolation + audit, end-to-end ──────────────────────────────────────

    @Test
    fun credential_reachesTheClientOnlyViaTheCredentialCall_andIsAudited() {
        seedSharedCredentialProvider()
        val client = catalogClient()

        // The secret is in neither the index nor any entity payload — only a fingerprint.
        val index = client.index().successValue()
        val credentialEntry = index.entries.single { it.kind == CatalogEntityKindWire.CREDENTIAL }
        assertNull("no entity carries the sentinel", index.entries.firstOrNull { it.contentHash == SENTINEL })
        assertFalse(credentialEntry.contentHash == SENTINEL)

        // The credential call delivers it, and writes exactly one audit row against the paired device.
        val response = client.credential("cred-1").successValue()
        assertEquals(SENTINEL, response.secret)
        assertEquals("OPENAI", response.provider)

        val audit = auditLog.accessFor("cred-1")
        assertEquals(1, audit.size)
        assertEquals(DEVICE_ID, audit.single().peerDeviceId)
        assertEquals(CatalogEntityKindWire.CREDENTIAL, audit.single().kind)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    private fun seedSharedCredentialProvider() {
        secretStore.put(CredentialSecrets.credentialRef("cred-1"), SENTINEL.toByteArray())
        config.save(ProviderConfigEntity(id = "prov-1", providerType = ProviderType.OPENAI, label = "My OpenAI", credentialRef = "cred-1", visibility = Visibility.SHARED))
    }

    private fun catalogClient() = CatalogClient(OkHttpDispatchTransport(baseUrl), credentials = { pairedCredentials() })

    private fun pairedDispatchClient(url: String = baseUrl) =
        net.devemperor.dictate.shared.client.DispatchClient(OkHttpDispatchTransport(url), credentials = { pairedCredentials() })

    /** Pairs a device through the real HTTP route once and reuses its credentials. */
    private val pairedCredentialsCache: Credentials by lazy {
        val token = container.pairingService.issue().token
        val response = net.devemperor.dictate.shared.client.DispatchClient(OkHttpDispatchTransport(baseUrl), credentials = { null })
            .pair(token, DEVICE_ID, DEVICE_NAME).successValue()
        Credentials(deviceId = response.deviceId, deviceSecret = response.deviceSecret)
    }

    private fun pairedCredentials(): Credentials = pairedCredentialsCache

    private fun <T> DispatchResult<T>.successValue(): T = when (this) {
        is DispatchResult.Success -> value
        is DispatchResult.Failure -> throw AssertionError("expected success, got $error")
    }

    private fun <T> DispatchResult<T>.failureError(): DispatchError = when (this) {
        is DispatchResult.Success -> throw AssertionError("expected a failure, got $value")
        is DispatchResult.Failure -> error
    }

    private companion object {
        const val DEVICE_ID = "test-device-0001"
        const val DEVICE_NAME = "Pixel 8"
        const val SERVER_NAME = "test-pc"
        const val SENTINEL = "SENTINEL-SECRET-sk-proj-abc123"
    }
}
