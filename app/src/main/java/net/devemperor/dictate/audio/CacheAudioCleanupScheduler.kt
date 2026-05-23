package net.devemperor.dictate.audio

import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Production-owned scheduler for [CacheAudioCleanupJob] — the
 * hybrid Application-onCreate + Service-onDestroy entry point
 * (recording-stack-completion §4.5.2).
 *
 * Mirrors the
 * [net.devemperor.dictate.database.DurationHealingScheduler]
 * convention exactly: an `object` holder that owns a single-thread
 * executor, runs the job once after submit, and exposes a
 * `resetForTest()` seam with graceful shutdown for Robolectric
 * hygiene. The mirror is deliberate — production semantics stay
 * boring and predictable across cleanup-job-style holders.
 *
 * **Why this exists separately from the job.** The job
 * [CacheAudioCleanupJob.run] is a pure function `(repo, dao, now,
 * ttl) → CleanupResult`. The scheduler wraps it with the
 * gating-and-threading concerns that make sense at call sites
 * (`DictateApplication.onCreate`, `DictatePipelineService.onDestroy`):
 *
 *  - Read `Pref.CacheCleanupLastRunMs` + `Pref.CacheCleanupIntervalMs`,
 *    short-circuit when not yet due.
 *  - Hop to a background thread via a single-shot
 *    `Executors.newSingleThreadExecutor()` so the job's File-IO
 *    never sits on the main thread.
 *  - After the job returns, persist `now` into
 *    `Pref.CacheCleanupLastRunMs` so the next tick sees the gate
 *    closed.
 *
 * **Concurrency.** Two trigger sites can fire near-simultaneously
 * (e.g. service-restart immediately after app-launch). The
 * `@Synchronized` on `scheduleIfDue` serialises the gating-read so
 * only the first caller sees `now - lastRun >= interval` and
 * actually enqueues; the second caller sees the gate closed via
 * the wall-clock `nowMs` injected by the first.
 *
 * **Test seam — `resetForTest()`** mirrors
 * [DurationHealingScheduler.resetForTest] verbatim: graceful
 * `shutdown()` + `awaitTermination()` to drain an in-flight job
 * before the shared DB singleton is rebuilt. The rationale (no
 * `shutdownNow()` because Robolectric's native SQLite can't survive
 * a thread interrupt mid-call) is copied from there.
 *
 * @see net.devemperor.dictate.audio.CacheAudioCleanupJob
 * @see net.devemperor.dictate.database.DurationHealingScheduler
 *   (the mirrored production convention)
 */
object CacheAudioCleanupScheduler {

    private const val TAG = "CacheAudioCleanupScheduler"

    @Volatile
    private var executor: ExecutorService? = null

    /**
     * Run [CacheAudioCleanupJob] off the main thread **if and only
     * if** the last successful run is at least
     * `Pref.CacheCleanupIntervalMs` ago. No-op when the gate is closed.
     *
     * @param prefs the application's SharedPreferences (the
     *   `"net.devemperor.dictate"` file from
     *   `DictateApplication.onCreate`).
     * @param repository the on-disk audio owner — production passes
     *   the same [AudioFileRepository] singleton wired into the
     *   service.
     * @param sessionDao Room DAO from `DictateDatabase.sessionDao()`.
     * @param nowMs the wall-clock reference for both the gating
     *   comparison AND the cutoff inside the job. Defaults to
     *   `System.currentTimeMillis()`; tests inject a fixed value so
     *   gating + cutoff are deterministic.
     */
    @Synchronized
    fun scheduleIfDue(
        prefs: SharedPreferences,
        repository: AudioFileRepository,
        sessionDao: SessionDao,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val lastRun = prefs.get(Pref.CacheCleanupLastRunMs)
        val interval = prefs.get(Pref.CacheCleanupIntervalMs)
        if (nowMs - lastRun < interval) {
            // Gate closed — another trigger already ran the job
            // within the interval.
            return
        }
        val ttl = prefs.get(Pref.CacheCleanupTtlMs)
        val exec = Executors.newSingleThreadExecutor()
        executor = exec
        exec.execute {
            try {
                val result = CacheAudioCleanupJob.run(
                    repository = repository,
                    sessionDao = sessionDao,
                    nowMs = nowMs,
                    ttlMs = ttl,
                )
                // Persist the last-run timestamp ONLY after the job
                // finishes — a crash mid-run leaves the gate open so
                // the next tick retries. (apply() is async; loss on
                // process-death of the next 30s is acceptable —
                // worst case the next launch re-runs the job, which
                // is idempotent.)
                prefs.edit().put(Pref.CacheCleanupLastRunMs, nowMs).apply()
                Log.i(
                    TAG,
                    "cleanup done: scanned=${result.scanned}, " +
                        "deleted=${result.deleted}, kept=${result.kept}",
                )
            } catch (t: Throwable) {
                // The job itself catches its internal exceptions;
                // this guard catches anything that slips through
                // (e.g. SharedPreferences edit failures). Never let
                // the executor thread die with an uncaught exception.
                Log.w(TAG, "cleanup job threw", t)
            }
        }
        exec.shutdown()
    }

    /**
     * Convenience entry-point for Java callers (`DictateApplication`)
     * that prefer not to construct a [CacheDirAudioFileRepository]
     * inline — Kotlin default arguments do not survive into the Java
     * call site, and the repository constructor takes a function-typed
     * `cacheDirProvider` whose lambda form is uglier from Java.
     *
     * Constructs a fresh repository each call (cheap — the
     * `audioCacheDir` is `by lazy` and the lambda is a single
     * `cacheDir` capture). The scheduler's gating means redundant
     * constructions don't translate into redundant job runs.
     */
    @JvmStatic
    fun scheduleFromApp(
        cacheDirProvider: () -> java.io.File?,
        prefs: SharedPreferences,
        sessionDao: SessionDao,
    ) {
        val repository =
            net.devemperor.dictate.core.CacheDirAudioFileRepository(cacheDirProvider)
        scheduleIfDue(prefs, repository, sessionDao)
    }

    /**
     * **Test-only.** Drains any in-flight cleanup-job thread so it
     * cannot pollute the shared [net.devemperor.dictate.database.DictateDatabase]
     * singleton across Robolectric tests in the same JVM fork.
     *
     * Mechanic mirrors
     * [net.devemperor.dictate.database.DurationHealingScheduler.resetForTest] —
     * graceful `shutdown()` + `awaitTermination()`, **not**
     * `shutdownNow()` (a thread interrupt inside a Room/SQLite native
     * call corrupts the process-wide native SQLite runtime).
     *
     * Idempotent — a no-op when no run was scheduled.
     */
    @JvmStatic
    @VisibleForTesting
    internal fun resetForTest() {
        val exec = synchronized(this) {
            val current = executor
            executor = null
            current
        } ?: return
        exec.shutdown()
        exec.awaitTermination(10, TimeUnit.SECONDS)
    }
}
