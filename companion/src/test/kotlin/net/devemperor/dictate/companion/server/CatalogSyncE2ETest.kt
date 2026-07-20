package net.devemperor.dictate.companion.server

import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.catalog.PeerCatalogClientFactory
import net.devemperor.dictate.companion.catalog.PeerSecrets
import net.devemperor.dictate.companion.catalog.TakeoverCatalogSubscriber
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightCatalogAuditLog
import net.devemperor.dictate.companion.data.SqlDelightCatalogRepository
import net.devemperor.dictate.companion.data.SqlDelightCatalogSubscriberStore
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.data.SqlDelightPeerExplorerStore
import net.devemperor.dictate.companion.domain.CatalogService
import net.devemperor.dictate.companion.domain.port.PeerRecord
import net.devemperor.dictate.companion.fakes.FakeSecretStore
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.sync.CatalogSyncEngine
import net.devemperor.dictate.shared.sync.CatalogSyncOutcome
import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.sync.SyncNotification
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The full peer-catalog abo cycle end-to-end (AC10 §11): a **real** provider companion (embedded
 * `CIO, port = 0` server + the real `/v1/catalog` routes) and a **real** subscriber companion (its own
 * SqlDelight DB, subscriber store, sync engine + takeover), talking over **real HTTP** with the real
 * `CatalogClient`. Only the SecretStores and clocks are fakes.
 *
 * subscribe → sync (no-op) → update at the provider → sync (detect+pull+verify+write) → notify — every
 * step against actual rows and actual sockets, so nothing about the two sides' agreement is mocked.
 */
class CatalogSyncE2ETest {

    // ── Provider peer "A" (the offer side, a real server) ─────────────────────────────────────
    private val clockA = MutableClock()
    private val dbA = CompanionDatabase.inMemory()
    private val configA = CompanionConfigRepository(dbA, now = clockA::nowMillis)
    private val secretA = FakeSecretStore()
    private val auditA = SqlDelightCatalogAuditLog(dbA)
    private val catalogServiceA = CatalogService(SqlDelightCatalogRepository(configA), secretA, auditA, clockA)
    private lateinit var containerA: CompanionContainer
    private lateinit var serverA: CompanionServer
    private lateinit var addressA: String

    // ── Subscriber peer "B" (the consumer side, no server needed) ─────────────────────────────
    private val clockB = MutableClock()
    private val dbB = CompanionDatabase.inMemory()
    private val configB = CompanionConfigRepository(dbB, now = clockB::nowMillis)
    private val secretB = FakeSecretStore()
    private val subscriberStoreB = SqlDelightCatalogSubscriberStore(dbB, configB, secretB)
    private val peerExplorerB = SqlDelightPeerExplorerStore(dbB, configB)
    private val notifierB = RecordingNotifierE2E()
    private val engineB = CatalogSyncEngine(subscriberStoreB, notifierB, clock = clockB::nowMillis)
    private val getCounts = mutableListOf<String>()
    private val clientsB = PeerCatalogClientFactory(secretB) { url -> CountingTransport(OkHttpDispatchTransport(url), getCounts) }
    private val subscriberB = TakeoverCatalogSubscriber(peerExplorerB, clientsB, configB, secretB, dbB, clockB::nowMillis)

    private val peerId = "peer-A"

    @Before
    fun setUp() {
        containerA = CompanionContainer.forTest(
            inserter = FakeTextInserter(),
            clock = clockA,
            devices = SqlDelightDeviceRepository(dbA),
            history = SqlDelightHistoryRepository(dbA),
            serverName = "PC-A",
            catalogService = catalogServiceA,
        )
        serverA = CompanionServer(containerA, hosts = listOf("127.0.0.1"), port = 0)
        serverA.start()
        addressA = "127.0.0.1:${serverA.boundPort()}"

        // Pair B with A over the real pairing route, then register A as a peer of B with its secret in
        // B's SecretStore — exactly the state a §9.1 pair-redemption leaves behind.
        val token = containerA.pairingService.issue().token
        val paired = DispatchClient(OkHttpDispatchTransport("http://$addressA"), credentials = { null })
            .pair(token, DEVICE_ID, "PC-B").let { (it as net.devemperor.dictate.shared.client.DispatchResult.Success).value }
        secretB.put(PeerSecrets.peerSecretRef(SECRET_REF), paired.deviceSecret.toByteArray())
        peerExplorerB.addPeer(
            PeerRecord(peerId = peerId, displayName = "PC-A", address = addressA, deviceId = paired.deviceId,
                secretRef = SECRET_REF, addedAt = 0L, lastContactAt = null, lastSuccessAt = null, lastRootHash = null),
        )
    }

    @After
    fun tearDown() {
        serverA.stop()
    }

    @Test
    fun subscribe_thenSync_detectsAndAppliesAProviderSideUpdate_andNotifies() {
        // A offers a shared prompt + a shared credential provider.
        val prompt = configA.save(PromptV3Entity(id = "p-1", name = "Formal", text = "Rewrite formally.", visibility = Visibility.SHARED))
        secretA.put(CredentialSecrets.credentialRef("cred-1"), "sk-proj-A-secret-123".toByteArray())
        configA.save(ProviderConfigEntity(id = "prov-1", providerType = ProviderType.OPENAI, label = "A's OpenAI", credentialRef = "cred-1", visibility = Visibility.SHARED))

        // ── Subscribe: B takes over both offered entries over real HTTP ───────────────────────
        val index = clientsB.clientFor(peerRow()).index().let { (it as net.devemperor.dictate.shared.client.DispatchResult.Success).value }
        index.entries.forEach { subscriberB.subscribe(peerId, it) }

        // B now has a local mirror of the prompt (same content hash) + the credential in its SecretStore.
        val copy = configB.prompt("p-1")!!
        assertEquals("Rewrite formally.", copy.text)
        assertEquals(prompt.contentHash, copy.contentHash)
        assertEquals(SubscriptionMode.SUBSCRIBE, copy.subscriptionMode)
        assertEquals(peerId, copy.sourceRef!!.peerId)
        assertEquals("sk-proj-A-secret-123", secretB.get(CredentialSecrets.credentialRef("cred-1"))?.decodeToString())

        // ── First sync run: everything already current → advances the root watermark, no change ──
        assertTrue(syncB() is CatalogSyncOutcome.NoChange)

        // ── Idempotency: an unchanged provider costs exactly one GET (the index), nothing else ──
        getCounts.clear()
        assertTrue(syncB() is CatalogSyncOutcome.NoChange)
        assertEquals(listOf("/v1/catalog"), getCounts)
        assertTrue(notifierB.fired.isEmpty())

        // ── Provider changes the prompt ────────────────────────────────────────────────────────
        configA.save(prompt.copy(text = "Rewrite this in a strictly formal register."))

        // ── Sync detects via the root hash, pulls only the changed entity, verifies + writes, notifies ──
        val outcome = syncB()
        assertTrue(outcome is CatalogSyncOutcome.Updated)
        assertEquals("Rewrite this in a strictly formal register.", configB.prompt("p-1")!!.text)
        assertEquals(1, notifierB.fired.size)
        assertEquals("PC-A", notifierB.fired.single().peerName)
        // B's copy still hashes identically to A's new content — the mirror stays byte-exact.
        assertEquals(configA.prompt("p-1")!!.contentHash, configB.prompt("p-1")!!.contentHash)
    }

    @Test
    fun forkedCopy_isNeverOverwritten_byASubsequentProviderChange() {
        val prompt = configA.save(PromptV3Entity(id = "p-1", name = "Formal", text = "original", visibility = Visibility.SHARED))
        val index = clientsB.clientFor(peerRow()).index().let { (it as net.devemperor.dictate.shared.client.DispatchResult.Success).value }
        index.entries.forEach { subscriberB.subscribe(peerId, it) }
        syncB()

        // The user edits (forks) B's copy: subscription row deleted + mode LOCAL (§5.3, AC8).
        peerExplorerB.fork("p-1", CatalogEntityKindWire.PROMPT)
        configB.save(configB.prompt("p-1")!!.copy(text = "my own local edit"))

        // A keeps changing upstream; B keeps syncing.
        configA.save(prompt.copy(text = "a new upstream version"))
        val outcome = syncB()

        // The fork is invisible to the run — the local edit survives untouched.
        assertTrue(outcome is CatalogSyncOutcome.NoChange)
        assertEquals("my own local edit", configB.prompt("p-1")!!.text)
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────
    private fun peerRow(): PeerRecord = peerExplorerB.peers().single { it.peerId == peerId }
    private fun syncB(): CatalogSyncOutcome {
        val peer = peerRow()
        return engineB.sync(clientsB.catalogPeer(peer), clientsB.clientFor(peer))
    }

    private companion object {
        const val DEVICE_ID = "sub-device-0001"
        const val SECRET_REF = "peer-A-secret"
    }
}

/** Wraps a real transport, recording every GET path so the idempotency assertion can count calls. */
private class CountingTransport(private val delegate: DispatchTransport, private val gets: MutableList<String>) : DispatchTransport {
    override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite = delegate.post(path, body, headers)
    override fun get(path: String, headers: Map<String, String>): HttpResponseLite {
        gets += path
        return delegate.get(path, headers)
    }
}

private class RecordingNotifierE2E(override val available: Boolean = true) : NotificationPort {
    val fired = mutableListOf<SyncNotification>()
    override fun notify(notification: SyncNotification) { fired += notification }
}
