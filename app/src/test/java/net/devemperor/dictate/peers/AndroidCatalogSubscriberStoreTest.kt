package net.devemperor.dictate.peers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import net.devemperor.dictate.config.CatalogImport
import net.devemperor.dictate.config.ConfigRepository
import net.devemperor.dictate.config.ConfigSecrets
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.peers.entity.PeerRoomEntity
import net.devemperor.dictate.peers.entity.SubscriptionRoomEntity
import net.devemperor.dictate.testutil.FakeSecretStore
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.auth.Secrets
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.config.CanonicalJson
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.shared.protocol.CatalogCredentialResponse
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntityResponse
import net.devemperor.dictate.shared.protocol.CatalogEntry
import net.devemperor.dictate.shared.protocol.CatalogIndexResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.sync.CatalogPeer
import net.devemperor.dictate.shared.sync.CatalogSyncEngine
import net.devemperor.dictate.shared.sync.CatalogSyncOutcome
import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.sync.SyncNotification
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android subscriber store proven against the REAL shared [CatalogSyncEngine] over a REAL in-memory
 * Room database — AC6/AC7/AC8 landing in actual `prompts`/`subscriptions`/`peers` rows (peer-katalog.md
 * §2, §6). The engine runs through the real [CatalogClient] over a local fake transport, so the wire
 * codec + the two hash checks are exercised; only the socket + the SecretStore are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidCatalogSubscriberStoreTest {

    private lateinit var db: DictateDatabase
    private lateinit var repo: ConfigRepository
    private lateinit var store: AndroidCatalogSubscriberStore
    private val secret = FakeSecretStore()
    private val notifier = RecordingNotifier()
    private var now = 1_000L

    private val peerId = "peer-A"
    private val credentials = Credentials("device-1", "secret-long-enough")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java).allowMainThreadQueries().build()
        repo = ConfigRepository(db) { now }
        store = AndroidCatalogSubscriberStore(db, secret, repo)
    }

    @After
    fun tearDown() = db.close()

    private fun engine() = CatalogSyncEngine(store, notifier, clock = { now })
    private fun client(t: FakeCatalogTransport) = CatalogClient(t) { credentials }
    private fun peer(root: String?) = CatalogPeer(peerId, "Heim-PC", root)

    private fun promptPayload(name: String, text: String) =
        CanonicalJson.canonicalString(PromptV3Entity(id = "ignored", name = name, text = text), PromptV3Entity.serializer())
    private fun hashOf(payload: String) = contentHashOfElement(Json.parseToJsonElement(payload))
    private fun rootHashOf(vararg e: CatalogEntry) = Secrets.sha256(e.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.contentHash}" })
    private fun entry(id: String, hash: String, kind: CatalogEntityKindWire = CatalogEntityKindWire.PROMPT) =
        CatalogEntry(id = id, kind = kind, contentHash = hash, updatedAt = 1L, label = "Label $id")
    private fun indexBody(root: String, entries: List<CatalogEntry>) =
        ProtocolCodec.encode(CatalogIndexResponse(rootHash = root, entries = entries), CatalogIndexResponse.serializer(), Validations.catalogIndexResponse)
    private fun entityBody(id: String, hash: String, payload: String) =
        ProtocolCodec.encode(CatalogEntityResponse(id = id, kind = CatalogEntityKindWire.PROMPT, contentHash = hash, payload = payload), CatalogEntityResponse.serializer(), Validations.catalogEntityResponse)

    private fun seedPeer(root: String?) {
        db.peerDao().upsert(PeerRoomEntity(peerId = peerId, displayName = "Heim-PC", address = "heim-pc:8756", deviceId = "device-1", secretRef = "peer-A-secret", addedAt = 0L))
        if (root != null) store.recordSuccess(peerId, 0L, root)
    }

    /** Seed a subscribed prompt copy (prompts row uuid=localId + a SUBSCRIBE subscription row). */
    private fun seedSubscribedPrompt(localId: String, sourceId: String, atHash: String, name: String, text: String) {
        CatalogImport.upsertPromptRow(db, { now }, PromptV3Entity(id = localId, name = name, text = text, visibility = Visibility.SHARED, sourceRef = SourceRef(peerId, sourceId, atHash), subscriptionMode = SubscriptionMode.SUBSCRIBE))
        db.subscriptionDao().upsert(SubscriptionRoomEntity(localEntityId = localId, peerId = peerId, sourceEntityId = sourceId, kind = CatalogEntityKindWire.PROMPT.name, mode = SubscriptionMode.SUBSCRIBE.name, lastHash = atHash, lastCheckedAt = null))
    }

    private fun promptText(uuid: String) = db.promptDao().getAll().first { it.uuid == uuid }.prompt

    @Test
    fun ac7_changedPrompt_isPulledVerifiedWritten_toTheRealRoomRow_andAnnounced() {
        val oldHash = hashOf(promptPayload("Formal", "old text"))
        seedPeer(rootHashOf(entry("p-src", oldHash)))
        seedSubscribedPrompt("p-local", "p-src", oldHash, "Formal", "old text")

        val newPayload = promptPayload("Formal", "a brand new instruction"); val newHash = hashOf(newPayload)
        val newRoot = rootHashOf(entry("p-src", newHash))
        val t = FakeCatalogTransport()
            .respond(Endpoints.CATALOG, indexBody(newRoot, listOf(entry("p-src", newHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/p-src", entityBody("p-src", newHash, newPayload))

        val outcome = engine().sync(peer(rootHashOf(entry("p-src", oldHash))), client(t))

        assertTrue(outcome is CatalogSyncOutcome.Updated)
        assertEquals("a brand new instruction", promptText("p-local"))
        assertEquals(newHash, db.subscriptionDao().byId("p-local")!!.lastHash)
        assertEquals(newRoot, db.peerDao().byId(peerId)!!.lastRootHash)
        assertEquals(1, notifier.fired.size)
    }

    @Test
    fun ac6_unchangedRootHash_isOneGet_noEntityFetch_noNotification() {
        val hash = hashOf(promptPayload("Formal", "text")); val root = rootHashOf(entry("p-src", hash))
        seedPeer(root)
        seedSubscribedPrompt("p-local", "p-src", hash, "Formal", "text")
        val t = FakeCatalogTransport().respond(Endpoints.CATALOG, indexBody(root, listOf(entry("p-src", hash))))

        assertTrue(engine().sync(peer(root), client(t)) is CatalogSyncOutcome.NoChange)
        assertEquals(listOf(Endpoints.CATALOG), t.gets)
        assertTrue(notifier.fired.isEmpty())
    }

    @Test
    fun ac8_forkedAndOneShotCopies_areNeverActive() {
        seedPeer(null)
        // A fork: LOCAL mode, provenance kept, NO subscription row (§5.3).
        CatalogImport.upsertPromptRow(db, { now }, PromptV3Entity(id = "p-fork", name = "Forked", text = "my edit", visibility = Visibility.SHARED, sourceRef = SourceRef(peerId, "p-src", "h"), subscriptionMode = SubscriptionMode.LOCAL))
        // A ONE_SHOT copy: a frozen subscription row that is not SUBSCRIBE.
        db.subscriptionDao().upsert(SubscriptionRoomEntity(localEntityId = "p-oneshot", peerId = peerId, sourceEntityId = "o-src", kind = CatalogEntityKindWire.PROMPT.name, mode = SubscriptionMode.ONE_SHOT.name, lastHash = "h", lastCheckedAt = null))

        assertTrue(store.activeSubscriptions(peerId).isEmpty())
        assertEquals("my edit", promptText("p-fork"))
    }

    @Test
    fun credentialUpdate_putsSecretInStore_andAdvancesWatermark() {
        seedPeer("root-old")
        db.subscriptionDao().upsert(SubscriptionRoomEntity(localEntityId = "cred-local", peerId = peerId, sourceEntityId = "cred-src", kind = CatalogEntityKindWire.CREDENTIAL.name, mode = SubscriptionMode.SUBSCRIBE.name, lastHash = "old", lastCheckedAt = null))
        val newHash = "b".repeat(64)
        val credBody = ProtocolCodec.encode(CatalogCredentialResponse(id = "cred-src", provider = "OPENAI", label = "My key", secret = "sk-proj-delivered-123"), CatalogCredentialResponse.serializer(), Validations.catalogCredentialResponse)
        val t = FakeCatalogTransport()
            .respond(Endpoints.CATALOG, indexBody(rootHashOf(entry("cred-src", newHash, CatalogEntityKindWire.CREDENTIAL)), listOf(entry("cred-src", newHash, CatalogEntityKindWire.CREDENTIAL))))
            .respond("${Endpoints.CATALOG_CREDENTIAL}/cred-src", credBody)

        engine().sync(peer("root-old"), client(t))

        assertEquals("sk-proj-delivered-123", secret.get(ConfigSecrets.credentialRef("cred-local"))?.decodeToString())
        assertEquals(newHash, db.subscriptionDao().byId("cred-local")!!.lastHash)
    }
}

/** Local programmable transport — app's twin of shared's FakeTransport (not on this test classpath). */
private class FakeCatalogTransport : DispatchTransport {
    private val answers = mutableMapOf<String, String>()
    val gets = mutableListOf<String>()
    fun respond(path: String, body: String) = apply { answers[path] = body }
    override fun post(path: String, body: String, headers: Map<String, String>) = throw AssertionError("catalog client only GETs; POST $path")
    override fun get(path: String, headers: Map<String, String>): HttpResponseLite {
        gets += path
        return HttpResponseLite(200, answers[path] ?: throw AssertionError("no answer for GET $path"))
    }
}

private class RecordingNotifier(override val available: Boolean = true) : NotificationPort {
    val fired = mutableListOf<SyncNotification>()
    override fun notify(notification: SyncNotification) { fired += notification }
}
