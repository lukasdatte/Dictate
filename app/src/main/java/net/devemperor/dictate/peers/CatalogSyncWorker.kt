package net.devemperor.dictate.peers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The Android catalog-sync poller (peer-katalog.md §6.5, Plan D5.f): a WorkManager
 * [CoroutineWorker] that runs the shared [net.devemperor.dictate.shared.sync.CatalogSyncEngine] over
 * every subscribed peer in the background, on a `PeriodicWorkRequest` plus a one-shot at app start
 * (the full-background-polling of Plan D4.5).
 *
 * The work itself is delegated to [CatalogSync.gateway] (see [CatalogSyncGateway] for why the seam):
 * this class owns the WorkManager lifecycle — scheduling, the coroutine dispatch, and the
 * failure→`retry` mapping — not the Room/SecretStore wiring.
 *
 * A run that cannot reach a peer is NOT a failure — the engine records that as staleness and returns
 * an outcome (§6.4), so a clean run is [Result.success]. [Result.retry] is reserved for an
 * unexpected throw (a DB error), letting WorkManager back off and try again.
 */
class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val gateway = CatalogSync.gateway ?: return@withContext Result.success() // not wired yet — clean no-op
        try {
            gateway.syncAllOnce()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {

        private const val UNIQUE_PERIODIC = "catalog_sync_periodic"
        private const val UNIQUE_ONESHOT = "catalog_sync_oneshot"

        /**
         * WorkManager's hard floor for a periodic request. The companion twin's default interval is
         * matched to it (`CompanionSettings.DEFAULT_CATALOG_SYNC_INTERVAL_MILLIS`) so both hosts poll
         * on the same cadence.
         */
        private const val PERIOD_MINUTES = 15L

        /**
         * Schedule the background poll (KEEP-existing periodic) plus one immediate run — the app-start
         * trigger (D4.5). Idempotent: safe to call on every app start.
         *
         * Best-effort: scheduling is a background nicety, so a WorkManager that is not initialised in
         * this environment (Robolectric does not run the androidx.startup default initializer; a
         * restricted process may lack it too) must NOT crash app start. Production initialises WorkManager
         * via the manifest's InitializationProvider, so the catch is inert there — it only guards the
         * host environments where the poll simply cannot be scheduled.
         */
        fun enqueue(context: Context) {
            val work = try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                android.util.Log.w("CatalogSyncWorker", "WorkManager unavailable — skipping background sync scheduling", e)
                return
            }

            work.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CatalogSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build(),
            )

            work.enqueueUniqueWork(
                UNIQUE_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CatalogSyncWorker>().build(),
            )
        }
    }
}
