package net.devemperor.dictate.shared.sync

import kotlinx.serialization.json.Json
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.shared.fakes.FakeTransport
import net.devemperor.dictate.shared.protocol.CatalogCredentialResponse
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntityResponse
import net.devemperor.dictate.shared.protocol.CatalogEntry
import net.devemperor.dictate.shared.protocol.CatalogIndexResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.Validations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Acceptance tests for the [CatalogSyncEngine] (peer-katalog.md §2 AC6–AC10), driven through the REAL
 * [CatalogClient] over a [FakeTransport] — so every run exercises the actual wire codec, the actual
 * validation, and the two real hash checks; only the socket and the local store are fakes.
 *
 * AC6  idempotency: an unchanged root hash costs exactly one GET and writes nothing.
 * AC7  update detection: a changed entry is pulled, verified, written and announced.
 * AC8  fork protection: a copy the store does not list as active is never touched.
 * AC9  staleness-not-error: an unreachable peer yields a non-error outcome, last_success untouched.
 * AC10 verify-before-write: a hash mismatch leaves the copy unchanged and does not advance the root.
 */
class CatalogSyncEngineTest {

    private val peerId = "peer-A"
    private val credentials = Credentials(deviceId = "device-1", deviceSecret = "secret-long-enough")
    private val clock = { 1_000L }

    private fun engine(store: CatalogSubscriberStore, notifier: NotificationPort) =
        CatalogSyncEngine(store, notifier, clock)

    private fun client(transport: FakeTransport) = CatalogClient(transport) { credentials }

    private fun peer(lastRootHash: String?) = CatalogPeer(peerId, "Heim-PC", lastRootHash)

    // ── Body builders (through the real codec, exactly as the server would emit) ─────────────

    private fun hashOf(payload: String): String = contentHashOfElement(Json.parseToJsonElement(payload))

    private fun indexBody(rootHash: String, entries: List<CatalogEntry>): String =
        ProtocolCodec.encode(
            CatalogIndexResponse(rootHash = rootHash, entries = entries),
            CatalogIndexResponse.serializer(),
            Validations.catalogIndexResponse,
        )

    private fun entry(id: String, hash: String, kind: CatalogEntityKindWire = CatalogEntityKindWire.PROMPT) =
        CatalogEntry(id = id, kind = kind, contentHash = hash, updatedAt = 1L, label = "Label $id")

    private fun entityBody(id: String, hash: String, payload: String): String =
        ProtocolCodec.encode(
            CatalogEntityResponse(id = id, kind = CatalogEntityKindWire.PROMPT, contentHash = hash, payload = payload),
            CatalogEntityResponse.serializer(),
            Validations.catalogEntityResponse,
        )

    private fun credentialBody(id: String): String =
        ProtocolCodec.encode(
            CatalogCredentialResponse(id = id, provider = "OPENAI", label = "OpenAI Key", secret = "sk-live-123"),
            CatalogCredentialResponse.serializer(),
            Validations.catalogCredentialResponse,
        )

    private fun subscription(local: String, source: String, lastHash: String, kind: CatalogEntityKindWire = CatalogEntityKindWire.PROMPT) =
        CatalogSubscriptionRef(localEntityId = local, sourceEntityId = source, kind = kind, lastHash = lastHash)

    private val rootA = "a".repeat(Endpoints.HASH_LENGTH)
    private val rootB = "b".repeat(Endpoints.HASH_LENGTH)

    // ── AC6 — idempotency: unchanged root hash = one GET, no writes ──────────────────────────

    @Test
    fun unchangedRootHash_isOneGetAndNoWrites() {
        val store = FakeCatalogSubscriberStore()
        val transport = FakeTransport().respond(Endpoints.CATALOG, 200, indexBody(rootA, emptyList()))

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertEquals(CatalogSyncOutcome.NoChange, outcome)
        assertEquals("exactly one GET on the no-op path", 1, transport.calls.size)
        assertEquals(Endpoints.CATALOG, transport.calls.single().path)
        assertTrue(store.entityUpdates.isEmpty())
        // The success is recorded with the (unchanged) root hash so last_success_at moves forward.
        assertEquals(rootA, store.successes.single().rootHash)
    }

    // ── AC7 — update detection: changed entry pulled, verified, written, announced ───────────

    @Test
    fun changedEntry_isPulledVerifiedWrittenAndAnnounced() {
        val payload = """{"name":"Neu","text":"Text"}"""
        val newHash = hashOf(payload)
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-1", lastHash = "old".let { "0".repeat(Endpoints.HASH_LENGTH) }))
        val notifier = RecordingNotificationPort()
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", newHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/src-1", 200, entityBody("src-1", newHash, payload))

        val outcome = engine(store, notifier).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.Updated)
        val update = store.entityUpdates.single()
        assertEquals("local-1", update.localEntityId)
        assertEquals(newHash, update.contentHash)
        assertEquals(payload, update.payload)
        assertEquals(rootB, store.successes.single().rootHash) // clean run → root advanced
        assertEquals(1, notifier.fired.size)
        assertEquals("Heim-PC", notifier.fired.single().peerName)
    }

    @Test
    fun entryWhoseHashMatchesSubscription_isNotPulled() {
        val sameHash = "c".repeat(Endpoints.HASH_LENGTH)
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-1", lastHash = sameHash))
        val notifier = RecordingNotificationPort()
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", sameHash))))

        val outcome = engine(store, notifier).sync(peer(lastRootHash = rootA), client(transport))

        assertEquals(CatalogSyncOutcome.NoChange, outcome)
        assertEquals("no entity GET — the subscription hash matched", 1, transport.calls.size)
        assertTrue(notifier.fired.isEmpty())
        assertEquals(rootB, store.successes.single().rootHash)
    }

    // ── AC8 — fork protection: a copy the store does not list is never touched ───────────────

    @Test
    fun forkedCopy_isNeverPulled_becauseItIsNotActive() {
        // The store returns NO active subscriptions (the forked copy's row was deleted, §5.3) even
        // though the peer's root hash changed and the index still offers the entity.
        val store = FakeCatalogSubscriberStore().withActive(peerId /* no refs */)
        val notifier = RecordingNotificationPort()
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", "d".repeat(Endpoints.HASH_LENGTH)))))

        val outcome = engine(store, notifier).sync(peer(lastRootHash = rootA), client(transport))

        assertEquals(CatalogSyncOutcome.NoChange, outcome)
        assertTrue("forked copy must not be written", store.entityUpdates.isEmpty())
        assertEquals("only the index GET — no entity fetch for a non-subscribed copy", 1, transport.calls.size)
    }

    // ── §6.4 — upstream removed: keep the copy, mark it, announce it ─────────────────────────

    @Test
    fun sourceRemovedFromIndex_keepsCopyMarksAndAnnounces() {
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-gone", lastHash = "e".repeat(Endpoints.HASH_LENGTH)))
        val notifier = RecordingNotificationPort()
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, emptyList())) // src-gone absent

        val outcome = engine(store, notifier).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.Updated)
        assertEquals("local-1" to 1_000L, store.sourceRemoved.single())
        assertTrue("copy is kept, never written over", store.entityUpdates.isEmpty())
        assertTrue(notifier.fired.single().changes.single() is CatalogChange.SourceRemoved)
    }

    @Test
    fun entityGone404OnFetch_isTreatedAsSourceRemoved() {
        val newHash = "f".repeat(Endpoints.HASH_LENGTH)
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-1", lastHash = "0".repeat(Endpoints.HASH_LENGTH)))
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", newHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/src-1", 404, envelope(ErrorCode.CATALOG_ENTITY_NOT_FOUND))

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.Updated)
        assertEquals("local-1" to 1_000L, store.sourceRemoved.single())
    }

    @Test
    fun transientFetchFailureMidPull_keepsCopyReportsFailureAndDoesNotAdvanceRoot() {
        // The index says the entity changed, but the entity GET dies on connectivity (NOT a 404):
        // the copy is kept (this is not a source removal), the run reports PartialVerifyFailure, and
        // the root hash must NOT advance — so the next run re-detects and retries (§6.4 self-healing,
        // the distinct else-branch of onPullFailure vs. the EntityGone case above).
        val newHash = "f".repeat(Endpoints.HASH_LENGTH)
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-1", lastHash = "0".repeat(Endpoints.HASH_LENGTH)))
        val notifier = RecordingNotificationPort()
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", newHash))))
            .fail("${Endpoints.CATALOG_ENTITY}/src-1", IOException("connection reset mid-pull"))

        val outcome = engine(store, notifier).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.PartialVerifyFailure)
        assertTrue("copy left untouched on a transient fetch failure", store.entityUpdates.isEmpty())
        assertTrue("not a source removal — the entity still exists upstream", store.sourceRemoved.isEmpty())
        assertNull("root must not advance so the next run retries", store.successes.single().rootHash)
        assertTrue("nothing landed, so nothing is announced", notifier.fired.isEmpty())
    }

    // ── AC10 — verify-before-write: both hash checks, no unverified row ever written ─────────

    @Test
    fun indexPayloadHashMismatch_leavesCopyUnchangedAndDoesNotAdvanceRoot() {
        // The served payload's own contentHash disagrees with what the index advertised (check 1).
        val payload = """{"name":"X"}"""
        val indexHash = hashOf(payload)
        val liedHash = "9".repeat(Endpoints.HASH_LENGTH) // different from indexHash
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-1", lastHash = "0".repeat(Endpoints.HASH_LENGTH)))
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", indexHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/src-1", 200, entityBody("src-1", liedHash, payload))

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.PartialVerifyFailure)
        assertTrue("nothing unverified is written", store.entityUpdates.isEmpty())
        assertNull("partial run must NOT advance the root hash", store.successes.single().rootHash)
    }

    @Test
    fun payloadRecomputeMismatch_leavesCopyUnchanged() {
        // check 1 passes (served hash == index hash) but the payload does not canonicalize to it
        // (a canonicalization drift / tamper) → check 2 fails.
        val servedHash = "7".repeat(Endpoints.HASH_LENGTH)
        val tamperedPayload = """{"name":"tampered"}""" // hashOf(payload) != servedHash
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription(local = "local-1", source = "src-1", lastHash = "0".repeat(Endpoints.HASH_LENGTH)))
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-1", servedHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/src-1", 200, entityBody("src-1", servedHash, tamperedPayload))

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.PartialVerifyFailure)
        assertTrue(store.entityUpdates.isEmpty())
    }

    // ── AC9 — staleness, not error ───────────────────────────────────────────────────────────

    @Test
    fun unreachablePeer_isStaleNotError_lastSuccessUntouched() {
        val store = FakeCatalogSubscriberStore()
        val transport = FakeTransport().fail(Endpoints.CATALOG, IOException("connection refused"))

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertEquals(CatalogSyncOutcome.PeerUnreachable, outcome)
        assertEquals(peerId to 1_000L, store.contacts.single()) // last_contact_at moved
        assertTrue("last_success_at must be untouched", store.successes.isEmpty())
    }

    @Test
    fun oldPeerWithoutCatalogRoute_isEndpointMissing() {
        val store = FakeCatalogSubscriberStore()
        val transport = FakeTransport().respond(Endpoints.CATALOG, 404, "") // bare 404, no envelope

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertEquals(CatalogSyncOutcome.EndpointMissing, outcome)
        assertEquals(peerId to 1_000L, store.contacts.single())
        assertTrue(store.successes.isEmpty())
    }

    // ── Credential path — secret goes to the sink, fingerprint is the watermark (F12) ────────

    @Test
    fun changedCredential_deliversSecretToStoreWithFingerprintWatermark() {
        val fingerprint = "1".repeat(Endpoints.HASH_LENGTH)
        val store = FakeCatalogSubscriberStore()
            .withActive(peerId, subscription("local-cred", "src-cred", "0".repeat(Endpoints.HASH_LENGTH), CatalogEntityKindWire.CREDENTIAL))
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-cred", fingerprint, CatalogEntityKindWire.CREDENTIAL))))
            .respond("${Endpoints.CATALOG_CREDENTIAL}/src-cred", 200, credentialBody("src-cred"))

        val outcome = engine(store, RecordingNotificationPort()).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.Updated)
        val cred = store.credentialUpdates.single()
        assertEquals("sk-live-123", cred.secret)
        assertEquals("the index fingerprint is the watermark, never a recomputed content hash", fingerprint, cred.contentHash)
        assertTrue("no entity write for a credential", store.entityUpdates.isEmpty())
    }

    // ── Partial run — one updates, one fails: PartialVerifyFailure carries both ───────────────

    @Test
    fun mixedRun_reportsBothChangesAndFailures_andDoesNotAdvanceRoot() {
        val okPayload = """{"name":"Ok"}"""
        val okHash = hashOf(okPayload)
        val badHash = "8".repeat(Endpoints.HASH_LENGTH)
        val store = FakeCatalogSubscriberStore().withActive(
            peerId,
            subscription("local-ok", "src-ok", "0".repeat(Endpoints.HASH_LENGTH)),
            subscription("local-bad", "src-bad", "0".repeat(Endpoints.HASH_LENGTH)),
        )
        val notifier = RecordingNotificationPort()
        val transport = FakeTransport()
            .respond(Endpoints.CATALOG, 200, indexBody(rootB, listOf(entry("src-ok", okHash), entry("src-bad", badHash))))
            .respond("${Endpoints.CATALOG_ENTITY}/src-ok", 200, entityBody("src-ok", okHash, okPayload))
            .respond("${Endpoints.CATALOG_ENTITY}/src-bad", 200, entityBody("src-bad", "6".repeat(Endpoints.HASH_LENGTH), okPayload))

        val outcome = engine(store, notifier).sync(peer(lastRootHash = rootA), client(transport))

        assertTrue(outcome is CatalogSyncOutcome.PartialVerifyFailure)
        val partial = outcome as CatalogSyncOutcome.PartialVerifyFailure
        assertEquals(1, partial.changes.size)
        assertEquals(1, partial.failures.size)
        assertEquals("local-ok", store.entityUpdates.single().localEntityId)
        assertNull("a run with any failure must not advance the root", store.successes.single().rootHash)
        assertEquals("still announces the one that landed", 1, notifier.fired.size)
    }

    private fun envelope(code: ErrorCode) = ProtocolCodec.encode(
        ErrorEnvelope(code = code, message = code.name),
        ErrorEnvelope.serializer(),
        Validations.errorEnvelope,
    )
}
