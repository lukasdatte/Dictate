package net.devemperor.dictate.peers

import android.content.Context
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.peers.entity.PeerRoomEntity
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.sync.CatalogPeer
import net.devemperor.dictate.shared.sync.CatalogSyncEngine
import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport

/**
 * The production [CatalogSyncGateway] (peer-katalog.md §6.5): builds the shared [CatalogSyncEngine] over
 * the phone's Room-backed [AndroidCatalogSubscriberStore] + the Android [SecretStore] + an
 * [AndroidNotificationPort], and drives it over every subscribed peer best-effort.
 *
 * Constructed once at app start ([net.devemperor.dictate.DictateApplication]) and parked in
 * [CatalogSync.gateway], where the reflectively-created [CatalogSyncWorker] reads it. A run that cannot
 * reach a peer is not a failure — the engine records that as staleness and returns an outcome (§6.4);
 * one bad peer never starves the rest.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §6.5
 */
class AndroidCatalogSyncGateway(
    private val db: DictateDatabase,
    private val secretStore: SecretStore,
    notificationPort: NotificationPort,
    /** Injectable so a test can point the client at an in-process server; production is OkHttp. */
    private val transportFactory: (baseUrl: String) -> DispatchTransport = { OkHttpDispatchTransport(it) },
) : CatalogSyncGateway {

    constructor(db: DictateDatabase, secretStore: SecretStore, context: Context) :
        this(db, secretStore, AndroidNotificationPort(context))

    private val store = AndroidCatalogSubscriberStore(db, secretStore)
    private val engine = CatalogSyncEngine(store, notificationPort, clock = System::currentTimeMillis)

    override fun syncAllOnce() {
        db.peerDao().getAll().forEach { peer ->
            runCatching { engine.sync(catalogPeer(peer), clientFor(peer)) }
        }
    }

    private fun clientFor(peer: PeerRoomEntity): CatalogClient =
        CatalogClient(transportFactory(baseUrlOf(peer.address))) {
            secretStore.get(PeerSecrets.peerSecretRef(peer.secretRef))?.decodeToString()
                ?.let { Credentials(deviceId = peer.deviceId, deviceSecret = it) }
        }

    private fun catalogPeer(peer: PeerRoomEntity): CatalogPeer =
        CatalogPeer(peerId = peer.peerId, displayName = peer.displayName, lastRootHash = peer.lastRootHash)

    private companion object {
        /** Match WindowsPairingActivity: a scheme-less tailnet address defaults to http. */
        fun baseUrlOf(address: String): String =
            if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address"
    }
}
