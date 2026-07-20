package net.devemperor.dictate.companion.catalog

import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.sync.CatalogPeer
import net.devemperor.dictate.shared.sync.CatalogSubscriberStore
import net.devemperor.dictate.shared.sync.CatalogSubscriptionRef
import net.devemperor.dictate.shared.sync.CatalogSyncEngine
import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.sync.SyncNotification
import net.devemperor.dictate.shared.sync.VerifiedCredentialUpdate
import net.devemperor.dictate.shared.sync.VerifiedEntityUpdate
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The scheduler's [CatalogSyncScheduler.syncAllNow] loop: it visits every peer, and one peer's
 * failure — whether the engine's or an unexpected store throw — never starves the rest (§6.5).
 */
class CatalogSyncSchedulerTest {

    private val noNotification = object : NotificationPort {
        override val available = false
        override fun notify(notification: SyncNotification) = Unit
    }

    /** A transport whose every call fails — enough to drive the engine down its `PeerUnreachable` arm. */
    private val downTransport = object : DispatchTransport {
        override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite =
            throw IOException("down")
        override fun get(path: String, headers: Map<String, String>): HttpResponseLite =
            throw IOException("down")
    }

    private fun unreachableTarget(peerId: String): CatalogSyncTarget {
        val client = CatalogClient(downTransport) { Credentials("d", "secret-long-enough") }
        return CatalogSyncTarget(CatalogPeer(peerId, peerId, lastRootHash = null), client)
    }

    @Test
    fun syncAllNow_visitsEveryPeer_evenWhenAllUnreachable() {
        val contacted = mutableListOf<String>()
        val store = recordingContactStore { contacted += it }
        val scheduler = CatalogSyncScheduler(
            targets = { listOf(unreachableTarget("peer-A"), unreachableTarget("peer-B")) },
            engine = CatalogSyncEngine(store, noNotification, { 1L }),
            intervalMillis = { 60_000L },
        )

        scheduler.syncAllNow()

        assertEquals(listOf("peer-A", "peer-B"), contacted)
    }

    @Test
    fun syncAllNow_continuesAfterAPeerThrows() {
        val contacted = mutableListOf<String>()
        // A store that throws for the first peer and records the second — proves the loop continues.
        val throwingStore = object : CatalogSubscriberStore by recordingContactStore({ contacted += it }) {
            override fun recordContact(peerId: String, at: Long) {
                if (peerId == "peer-A") throw IllegalStateException("boom")
                contacted += peerId
            }
        }
        val scheduler = CatalogSyncScheduler(
            targets = { listOf(unreachableTarget("peer-A"), unreachableTarget("peer-B")) },
            engine = CatalogSyncEngine(throwingStore, noNotification, { 1L }),
            intervalMillis = { 60_000L },
        )

        scheduler.syncAllNow() // must not throw

        assertEquals("the second peer is still visited after the first throws", listOf("peer-B"), contacted)
    }

    @Test
    fun syncAllNow_swallowsATargetEnumerationFailure() {
        val scheduler = CatalogSyncScheduler(
            targets = { throw IllegalStateException("db down") },
            engine = CatalogSyncEngine(recordingContactStore { }, noNotification, { 1L }),
            intervalMillis = { 60_000L },
        )

        scheduler.syncAllNow() // must not throw — enumeration failure is swallowed
    }

    private fun recordingContactStore(onContact: (String) -> Unit): CatalogSubscriberStore =
        object : CatalogSubscriberStore {
            override fun activeSubscriptions(peerId: String): List<CatalogSubscriptionRef> = emptyList()
            override fun recordContact(peerId: String, at: Long) = onContact(peerId)
            override fun recordSuccess(peerId: String, at: Long, rootHash: String?) = Unit
            override fun applyEntityUpdate(update: VerifiedEntityUpdate) = Unit
            override fun applyCredentialUpdate(update: VerifiedCredentialUpdate) = Unit
            override fun markSourceRemoved(localEntityId: String, at: Long) = Unit
        }
}
