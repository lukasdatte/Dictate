package net.devemperor.dictate.peers

/**
 * The one call the [CatalogSyncWorker] makes into the app to sync every subscribed peer once
 * (peer-katalog.md §6.5).
 *
 * A seam, not the engine directly, for two reasons: the worker is created reflectively by
 * WorkManager (it cannot take constructor dependencies without a custom `WorkerFactory`), and the
 * Room-backed subscriber store + credential/transport wiring that a real run needs
 * (`AndroidCatalogSubscriberStore`, the peers→client builder) is a substantial, credential-sensitive
 * adapter of its own. This interface lets the worker, its scheduling and the notification path land
 * and be exercised now, while its concrete Room-backed implementation is wired at app start through
 * [CatalogSync.gateway].
 *
 * Implementations build the shared [net.devemperor.dictate.shared.sync.CatalogSyncEngine] over the
 * phone's Room tables, the Android SecretStore and an [AndroidNotificationPort], and iterate the
 * peers best-effort. Called on a background thread; must not throw for a mere unreachable peer.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §6.5
 */
fun interface CatalogSyncGateway {
    fun syncAllOnce()
}

/**
 * The process-wide hand-off point for the [CatalogSyncGateway] (set once at app start, read by the
 * reflectively-constructed [CatalogSyncWorker]). Null until the Room-backed implementation is wired,
 * in which case a worker run is a clean no-op rather than a crash.
 */
object CatalogSync {

    @Volatile
    var gateway: CatalogSyncGateway? = null
}
