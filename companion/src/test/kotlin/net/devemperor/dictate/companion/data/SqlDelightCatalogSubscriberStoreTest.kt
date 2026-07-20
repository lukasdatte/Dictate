package net.devemperor.dictate.companion.data

import kotlinx.serialization.json.Json
import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.fakes.FakeSecretStore
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.config.CanonicalJson
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion subscriber store proven against the REAL shared [CatalogSyncEngine] over an in-memory
 * SqlDelight DB — AC6/AC7/AC8/AC10 landing in actual rows, not a fake (peer-katalog.md §2, §6).
 *
 * The engine runs through the REAL [CatalogClient] over a [FakeTransport], so every run exercises the
 * true wire codec + the two real hash checks; only the socket is faked. The store, the `peers`/
 * `subscriptions` tables and the config-entity mirror are all real.
 */
class SqlDelightCatalogSubscriberStoreTest {

    private val database = CompanionDatabase.inMemory()
    private val clock = MutableClock()
    private val config = CompanionConfigRepository(database, now = clock::nowMillis)
    private val secretStore = FakeSecretStore()
    private val store = SqlDelightCatalogSubscriberStore(database, config, secretStore)
    private val queries = database.companionQueries
    private val notifier = RecordingNotifier()

    private val peerId = "peer-A"
    private val credentials = Credentials("device-1", "secret-long-enough")

    private fun engine() = CatalogSyncEngine(store, notifier, clock = { clock.nowMillis() })
    private fun client(transport: FakeCatalogTransport) = CatalogClient(transport) { credentials }
    private fun peer(rootHash: String?) = CatalogPeer(peerId, "Heim-PC", rootHash)

    // ── wire body builders (through the real codec, exactly as the server would emit) ─────────

    private fun promptPayload(name: String, text: String): String =
        CanonicalJson.canonicalString(PromptV3Entity(id = "ignored", name = name, text = text), PromptV3Entity.serializer())

    private fun hashOf(payload: String): String = contentHashOfElement(Json.parseToJsonElement(payload))

    private fun rootHashOf(vararg entries: CatalogEntry): String =
        net.devemperor.dictate.shared.auth.Secrets.sha256(entries.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.contentHash}" })

    private fun indexBody(root: String, entries: List<CatalogEntry>): String =
        ProtocolCodec.encode(CatalogIndexResponse(rootHash = root, entries = entries), CatalogIndexResponse.serializer(), Validations.catalogIndexResponse)

    private fun entityBody(id: String, hash: String, payload: String): String =
        ProtocolCodec.encode(
            CatalogEntityResponse(id = id, kind = CatalogEntityKindWire.PROMPT, contentHash = hash, payload = payload),
            CatalogEntityResponse.serializer(), Validations.catalogEntityResponse,
        )

    private fun entry(id: String, hash: String, kind: CatalogEntityKindWire = CatalogEntityKindWire.PROMPT) =
        CatalogEntry(id = id, kind = kind, contentHash = hash, updatedAt = 1L, label = "Label $id")

    /** Seed a subscribed prompt copy with local id [localId] mirroring source [sourceId] at [atHash]. */
    private fun seedSubscribedPrompt(localId: String, sourceId: String, atHash: String, name: String, text: String) {
        config.save(
            PromptV3Entity(
                id = localId, name = name, text = text, visibility = Visibility.SHARED,
                sourceRef = SourceRef(peerId, sourceId, atHash), subscriptionMode = SubscriptionMode.SUBSCRIBE,
            ),
        )
        queries.insertSubscription(
            localEntityId = localId, peerId = peerId, sourceEntityId = sourceId,
            kind = CatalogEntityKindWire.PROMPT, mode = SubscriptionMode.SUBSCRIBE, lastHash = atHash, lastCheckedAt = null,
        )
    }

    private fun seedPeer(rootHash: String?) =
        queries.insertPeer(peerId = peerId, displayName = "Heim-PC", address = "heim-pc:8756", deviceId = "device-1", secretRef = "peer/peer-A", addedAt = 0L)
            .also { if (rootHash != null) store.recordSuccess(peerId, 0L, rootHash) }

    // ── AC7: update detection lands a verified payload in the real row ────────────────────────

    @Test
    fun ac7_changedEntity_isPulledVerifiedWrittenAndAnnounced() {
        val oldPayload = promptPayload("Formal", "old text"); val oldHash = hashOf(oldPayload)
        seedPeer(rootHashOf(entry("p-src", oldHash)))
        seedSubscribedPrompt(localId = "p-local", sourceId = "p-src", atHash = oldHash, name = "Formal", text = "old text")

        val newPayload = promptPayload("Formal", "a brand new instruction"); val newHash = hashOf(newPayload)
        val newRoot = rootHashOf(entry("p-src", newHash))
        val transport = FakeCatalogTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(newRoot, listOf(entry("p-src", newHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/p-src", 200, entityBody("p-src", newHash, newPayload))

        val outcome = engine().sync(peer(rootHashOf(entry("p-src", oldHash))), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.Updated)
        // The real local row adopted the source content but KEPT its local id + provenance.
        val updated = config.prompt("p-local")!!
        assertEquals("a brand new instruction", updated.text)
        assertEquals("p-local", updated.id)
        assertEquals(peerId, updated.sourceRef!!.peerId)
        assertEquals(newHash, updated.contentHash)
        // The subscription watermark + peer root hash advanced; one notification fired.
        assertEquals(newHash, queries.subscriptionsForPeer(peerId).executeAsList().single().last_hash)
        assertEquals(newRoot, queries.peerById(peerId).executeAsOne().last_root_hash)
        assertEquals(1, notifier.fired.size)
    }

    // ── AC6: idempotency — an unchanged root hash costs exactly one GET, writes nothing ───────

    @Test
    fun ac6_unchangedRootHash_isOneGet_noEntityFetch_noNotification() {
        val payload = promptPayload("Formal", "text"); val hash = hashOf(payload)
        val root = rootHashOf(entry("p-src", hash))
        seedPeer(root)
        seedSubscribedPrompt("p-local", "p-src", hash, "Formal", "text")
        val transport = FakeCatalogTransport().respond(Endpoints.CATALOG, 200, indexBody(root, listOf(entry("p-src", hash))))

        val outcome = engine().sync(peer(root), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.NoChange)
        assertEquals(1, transport.calls.size)
        assertEquals(Endpoints.CATALOG, transport.calls.single().path)
        assertTrue(notifier.fired.isEmpty())
    }

    // ── AC8: fork protection — a detached copy is invisible to the run ────────────────────────

    @Test
    fun ac8_forkedAndOneShotCopies_areNeverActive_soNeverTouched() {
        seedPeer(null)
        // A fork: LOCAL mode, provenance kept, NO subscription row (§5.3).
        config.save(PromptV3Entity(id = "p-fork", name = "Forked", text = "my edit", visibility = Visibility.SHARED, sourceRef = SourceRef(peerId, "p-src", "h"), subscriptionMode = SubscriptionMode.LOCAL))
        // A ONE_SHOT copy: a frozen subscription row that is not SUBSCRIBE.
        config.save(PromptV3Entity(id = "p-oneshot", name = "Frozen", text = "frozen", visibility = Visibility.SHARED, sourceRef = SourceRef(peerId, "o-src", "h"), subscriptionMode = SubscriptionMode.ONE_SHOT))
        queries.insertSubscription(localEntityId = "p-oneshot", peerId = peerId, sourceEntityId = "o-src", kind = CatalogEntityKindWire.PROMPT, mode = SubscriptionMode.ONE_SHOT, lastHash = "h", lastCheckedAt = null)

        val active = store.activeSubscriptions(peerId)

        assertTrue("no fork or one-shot is ever active", active.isEmpty())
        // And the fork copy stays byte-for-byte what the user edited.
        assertEquals("my edit", config.prompt("p-fork")!!.text)
    }

    // ── AC10: verify-before-write — a payload/hash mismatch leaves the copy untouched ─────────

    @Test
    fun ac10_indexPayloadHashMismatch_leavesCopyUnchanged_andDoesNotAdvanceRoot() {
        val oldPayload = promptPayload("Formal", "keep me"); val oldHash = hashOf(oldPayload)
        val oldRoot = rootHashOf(entry("p-src", oldHash))
        seedPeer(oldRoot)
        seedSubscribedPrompt("p-local", "p-src", oldHash, "Formal", "keep me")

        // The index advertises a new hash, but the served entity carries a DIFFERENT (tampered) payload.
        val advertised = hashOf(promptPayload("Formal", "advertised"))
        val tampered = promptPayload("Formal", "tampered body"); val tamperedHash = hashOf(tampered)
        val newRoot = rootHashOf(entry("p-src", advertised))
        val transport = FakeCatalogTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(newRoot, listOf(entry("p-src", advertised))))
            .respond("${Endpoints.CATALOG_ENTITY}/p-src", 200, entityBody("p-src", tamperedHash, tampered))

        val outcome = engine().sync(peer(oldRoot), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.PartialVerifyFailure)
        assertEquals("keep me", config.prompt("p-local")!!.text)
        // Root hash NOT advanced (still old) so the next run retries the unresolved change.
        assertEquals(oldRoot, queries.peerById(peerId).executeAsOne().last_root_hash)
        assertTrue(notifier.fired.isEmpty())
    }

    // ── Credential update lands ONLY in the SecretStore, never a column ───────────────────────

    @Test
    fun credentialUpdate_putsSecretInStore_andAdvancesWatermark_noColumn() {
        val credHash = "a".repeat(64)
        queries.insertPeer(peerId = peerId, displayName = "Heim-PC", address = "heim-pc:8756", deviceId = "device-1", secretRef = "peer/peer-A", addedAt = 0L)
        queries.insertSubscription(localEntityId = "cred-local", peerId = peerId, sourceEntityId = "cred-src", kind = CatalogEntityKindWire.CREDENTIAL, mode = SubscriptionMode.SUBSCRIBE, lastHash = "old", lastCheckedAt = null)
        store.recordSuccess(peerId, 0L, "root-old")

        val newHash = "b".repeat(64)
        val credBody = ProtocolCodec.encode(
            CatalogCredentialResponse(id = "cred-src", provider = "OPENAI", label = "My key", secret = "sk-proj-delivered-123"),
            CatalogCredentialResponse.serializer(), Validations.catalogCredentialResponse,
        )
        val transport = FakeCatalogTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootHashOf(entry("cred-src", newHash, CatalogEntityKindWire.CREDENTIAL)), listOf(entry("cred-src", newHash, CatalogEntityKindWire.CREDENTIAL))))
            .respond("${Endpoints.CATALOG_CREDENTIAL}/cred-src", 200, credBody)

        engine().sync(peer("root-old"), client(transport))

        // The plaintext is in the SecretStore under the LOCAL id — and the watermark advanced.
        assertEquals("sk-proj-delivered-123", secretStore.get(CredentialSecrets.credentialRef("cred-local"))?.decodeToString())
        assertEquals(newHash, queries.subscriptionsForPeer(peerId).executeAsList().single { it.local_entity_id == "cred-local" }.last_hash)
    }

    // ── markSourceRemoved keeps the copy, records the check ───────────────────────────────────

    @Test
    fun sourceRemovedUpstream_keepsTheCopy_recordsTheCheck() {
        val payload = promptPayload("Formal", "still here"); val hash = hashOf(payload)
        seedPeer(rootHashOf(entry("p-src", hash)))
        seedSubscribedPrompt("p-local", "p-src", hash, "Formal", "still here")
        // The new index no longer lists p-src → the source was removed/unshared.
        val transport = FakeCatalogTransport().respond(Endpoints.CATALOG, 200, indexBody(rootHashOf(), emptyList()))

        val outcome = engine().sync(peer(rootHashOf(entry("p-src", hash))), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.Updated) // a SourceRemoved change, copy kept
        assertEquals("still here", config.prompt("p-local")!!.text)
        assertFalse("copy is NOT deleted", config.prompt("p-local") == null)
        assertNull("no secret involved", secretStore.get(CredentialSecrets.credentialRef("p-local")))
    }
}

/**
 * A programmable [DispatchTransport] for the store test — the companion-local twin of shared's
 * `FakeTransport` (which lives in the :shared test source set, not on this module's test classpath).
 * It fakes only the socket: the [CatalogClient] above it still runs the real codec + validation.
 */
private class FakeCatalogTransport : DispatchTransport {
    private val answers = mutableMapOf<String, Pair<Int, String>>()
    val calls = mutableListOf<RecordedGet>()

    data class RecordedGet(val path: String)

    fun respond(path: String, status: Int, body: String) = apply { answers[path] = status to body }

    override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite =
        throw AssertionError("the catalog client only GETs; POST to $path is a bug")

    override fun get(path: String, headers: Map<String, String>): HttpResponseLite {
        calls += RecordedGet(path)
        val (status, body) = answers[path] ?: throw AssertionError("no answer queued for GET $path")
        return HttpResponseLite(status, body)
    }
}

/** Records the runs it was asked to announce — the local twin of shared's `RecordingNotificationPort`. */
private class RecordingNotifier(override val available: Boolean = true) : NotificationPort {
    val fired = mutableListOf<SyncNotification>()
    override fun notify(notification: SyncNotification) { fired += notification }
}
