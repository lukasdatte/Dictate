package net.devemperor.dictate.companion.catalog

import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.sync.CatalogPeer
import net.devemperor.dictate.shared.sync.CatalogSyncEngine
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Drives the [CatalogSyncEngine] over every subscribed peer on a timer, plus on the triggers that
 * matter — app start and panel/window open (peer-katalog.md §6.5, ADR-0020's app-start pattern).
 *
 * ## Best-effort, never on a critical path
 *
 * Sync is background housekeeping: it runs on a single daemon thread, it swallows a per-peer failure
 * so one unreachable peer never stops the others, and it never throws into the UI or the caller
 * (the same "fire and forget" contract as [net.devemperor.dictate.shared.sync.SyncClient]). The
 * engine already turns every failure into an outcome; the try/catch here is the belt-and-suspenders
 * for an unexpected store exception.
 *
 * ## The DB and credentials sit behind [CatalogSyncTargets]
 *
 * Building a [CatalogClient] for a peer needs its address (→ transport), its `device_id` and the
 * secret behind its `secret_ref` (→ credentials) — all of which live in the `peers` table and the
 * SecretStore. That wiring is [CatalogSyncTargets]' job, injected, so this scheduler stays a pure
 * timing coordinator that a test can drive with fake targets and a real engine.
 */
class CatalogSyncScheduler(
    private val targets: CatalogSyncTargets,
    private val engine: CatalogSyncEngine,
    /** Read fresh each schedule so a settings change takes effect on the next tick, not the next boot. */
    private val intervalMillis: () -> Long,
    private val executor: ScheduledExecutorService = defaultExecutor(),
    private val log: (String) -> Unit = {},
) {

    @Volatile
    private var started = false

    /**
     * Start the periodic poll and fire one immediate run (the app-start trigger, ADR-0020). Idempotent:
     * a second call is a no-op, so wiring it from both the container and a UI event is safe.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        executor.execute(::syncAllNow) // app-start trigger — do not wait a whole interval for the first sync
        val interval = intervalMillis().coerceAtLeast(1_000L)
        executor.scheduleWithFixedDelay(::syncAllNow, interval, interval, TimeUnit.MILLISECONDS)
    }

    /** The panel/window-open trigger (§6.5): a cheap extra run so a just-opened UI shows fresh state. */
    fun onWindowOpened() {
        if (started) executor.execute(::syncAllNow)
    }

    /**
     * Sync every target once, best-effort. This is the unit the tests drive directly. A peer whose
     * run throws is logged and skipped; the loop continues so one bad peer cannot starve the rest.
     */
    fun syncAllNow() {
        for (target in currentTargets()) {
            try {
                engine.sync(target.peer, target.client)
            } catch (e: Exception) {
                log("catalog-sync: peer ${target.peer.peerId} threw ${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    private fun currentTargets(): List<CatalogSyncTarget> =
        try {
            targets.targets()
        } catch (e: Exception) {
            log("catalog-sync: could not enumerate peers: ${e::class.java.simpleName}")
            emptyList()
        }

    /** Stops the timer. The executor is not reusable after this — build a new scheduler to restart. */
    fun stop() {
        started = false
        executor.shutdownNow()
    }

    private companion object {
        fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "catalog-sync").apply { isDaemon = true }
            }
    }
}

/**
 * One peer ready to sync: the [CatalogPeer] the engine needs and a [CatalogClient] already pointed
 * at that peer's address with that peer's pairing credentials.
 */
data class CatalogSyncTarget(val peer: CatalogPeer, val client: CatalogClient)

/**
 * Enumerates the peers to sync and builds their clients — the seam that hides the `peers` table, the
 * transport and the SecretStore-backed credentials from the [CatalogSyncScheduler] (peer-katalog.md §6.5).
 */
fun interface CatalogSyncTargets {
    /** Every subscribed peer as a ready-to-run target. Empty when there are no peers. */
    fun targets(): List<CatalogSyncTarget>
}
