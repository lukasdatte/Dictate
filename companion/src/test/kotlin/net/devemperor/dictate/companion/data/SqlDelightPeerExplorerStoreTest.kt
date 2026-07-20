package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.domain.port.PeerRecord
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Explorer store on the real schema (peer-katalog.md §8, §5.1/§5.3): peers round-trip, the
 * subscribed+forked union of [SqlDelightPeerExplorerStore.copiesFrom], and the two mutations —
 * unsubscribe leaves the copy, fork flips the mode and drops the subscription row atomically. On the
 * real SQLite because the invariants live in the SQL (FK cascade, the §5.3 mode CHECK).
 */
class SqlDelightPeerExplorerStoreTest {

    private val database = CompanionDatabase.inMemory()
    private val config = CompanionConfigRepository(database, now = { 42L })
    private val store = SqlDelightPeerExplorerStore(database, config)

    @Test
    fun peers_roundTrip() {
        store.addPeer(peer("p1", "Heim-PC"))

        val stored = store.peers().single()

        assertEquals("p1", stored.peerId)
        assertEquals("Heim-PC", stored.displayName)
        assertEquals("heim-pc.tail1.ts.net:8756", stored.address)
        // A fresh peer has never been contacted — the E2 sync adapter owns those columns.
        assertEquals(null, stored.lastSuccessAt)
        assertEquals(null, stored.lastRootHash)
    }

    @Test
    fun copiesFrom_joinsSubscriptionWithEntityLabel() {
        store.addPeer(peer("p1", "Heim-PC"))
        val prompt = config.save(subscribedPrompt("local-a", "p1", "src-a"))
        subscribe("local-a", "p1", "src-a", prompt.contentHash)

        val copy = store.copiesFrom("p1").single()

        assertEquals("Formal tone", copy.label)
        assertEquals(SubscriptionMode.SUBSCRIBE, copy.mode)
        assertEquals(prompt.contentHash, copy.lastHash)
        assertEquals(CatalogEntityKindWire.PROMPT, copy.kind)
    }

    @Test
    fun copiesFrom_includesForkedCopies_withoutSubscriptionRow() {
        store.addPeer(peer("p1", "Heim-PC"))
        config.save(subscribedPrompt("local-a", "p1", "src-a").copy(subscriptionMode = SubscriptionMode.LOCAL))

        val copy = store.copiesFrom("p1").single()

        assertEquals(SubscriptionMode.LOCAL, copy.mode)
        assertEquals("src-a", copy.sourceEntityId)
    }

    @Test
    fun copiesFrom_ignoresOtherPeersAndLocalEntities() {
        store.addPeer(peer("p1", "Heim-PC"))
        store.addPeer(peer("p2", "Büro-PC"))
        config.save(PromptV3Entity(id = "mine", name = "Local", text = "t")) // no provenance
        val other = config.save(subscribedPrompt("theirs", "p2", "src-x"))
        subscribe("theirs", "p2", "src-x", other.contentHash)

        assertTrue(store.copiesFrom("p1").isEmpty())
        assertEquals(listOf("theirs"), store.copiesFrom("p2").map { it.localEntityId })
    }

    @Test
    fun unsubscribe_deletesTheRow_keepsTheCopyAndItsMode() {
        store.addPeer(peer("p1", "Heim-PC"))
        val prompt = config.save(subscribedPrompt("local-a", "p1", "src-a"))
        subscribe("local-a", "p1", "src-a", prompt.contentHash)

        store.unsubscribe("local-a")

        // Row gone → no longer listed (its mode is still SUBSCRIBE, so the fork-arm of the union
        // does not pick it up either). The entity itself survives untouched — the frozen copy
        // (§8.1 "Kopie bleibt eingefroren").
        assertTrue(store.copiesFrom("p1").isEmpty())
        assertEquals(SubscriptionMode.SUBSCRIBE, config.prompt("local-a")!!.subscriptionMode)
        assertEquals("Formal tone", config.prompt("local-a")!!.name)
    }

    @Test
    fun fork_flipsModeToLocal_andDeletesTheSubscription_inOneStep() {
        store.addPeer(peer("p1", "Heim-PC"))
        val prompt = config.save(subscribedPrompt("local-a", "p1", "src-a"))
        subscribe("local-a", "p1", "src-a", prompt.contentHash)

        store.fork("local-a", CatalogEntityKindWire.PROMPT)

        val forked = config.prompt("local-a")!!
        assertEquals(SubscriptionMode.LOCAL, forked.subscriptionMode)
        // Provenance survives the fork — that is what makes it show as FORKED, not as local (§8.1).
        assertEquals("p1", forked.sourceRef?.peerId)
        // The mode flip is an envelope change: the content hash must NOT move (CanonicalJson).
        assertEquals(prompt.contentHash, forked.contentHash)
        // And the copy now surfaces as the forked arm of the union.
        assertEquals(SubscriptionMode.LOCAL, store.copiesFrom("p1").single().mode)
    }

    @Test
    fun fork_ofACredentialBinding_justDropsTheSubscription() {
        store.addPeer(peer("p1", "Heim-PC"))
        subscribe("cred-a", "p1", "src-cred", "fingerprint", kind = CatalogEntityKindWire.CREDENTIAL)

        store.fork("cred-a", CatalogEntityKindWire.CREDENTIAL)

        assertTrue(store.copiesFrom("p1").isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun peer(id: String, name: String) = PeerRecord(
        peerId = id,
        displayName = name,
        address = "heim-pc.tail1.ts.net:8756",
        deviceId = "dev-$id",
        secretRef = "peer/$id",
        addedAt = 1L,
        lastContactAt = null,
        lastSuccessAt = null,
        lastRootHash = null,
    )

    private fun subscribedPrompt(id: String, peerId: String, sourceId: String) = PromptV3Entity(
        id = id,
        name = "Formal tone",
        text = "Rewrite formally.",
        sourceRef = SourceRef(peerId = peerId, originalId = sourceId, originalContentHash = "h0"),
        subscriptionMode = SubscriptionMode.SUBSCRIBE,
    )

    private fun subscribe(
        localId: String,
        peerId: String,
        sourceId: String,
        lastHash: String,
        kind: CatalogEntityKindWire = CatalogEntityKindWire.PROMPT,
    ) = database.companionQueries.insertSubscription(
        localEntityId = localId,
        peerId = peerId,
        sourceEntityId = sourceId,
        kind = kind,
        mode = SubscriptionMode.SUBSCRIBE,
        lastHash = lastHash,
        lastCheckedAt = null,
    )
}
