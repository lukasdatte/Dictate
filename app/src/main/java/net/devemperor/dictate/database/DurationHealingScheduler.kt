package net.devemperor.dictate.database

import androidx.annotation.VisibleForTesting
import net.devemperor.dictate.core.RecordingRepository
import net.devemperor.dictate.database.dao.SessionDao
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Production-owned scheduler for the one-time [DurationHealingJob].
 *
 * [net.devemperor.dictate.DictateApplication.onCreate] used to inline an
 * ad-hoc `Executors.newSingleThreadExecutor()` + `executor.shutdown()`
 * (a *non-blocking* shutdown with **no cancel/await seam**) to run
 * [DurationHealingJob.heal] off the main thread. Production semantics are
 * unchanged here — still async, still single-shot, still shut down right
 * after enqueuing the single task (executor threads are non-daemon by
 * default, so `shutdown()` releases the worker thread once the task
 * finishes).
 *
 * The difference is purely a **test seam**: extracting the executor into
 * this holder gives it a [resetForTest] entry-point that cancels +
 * awaits the in-flight heal thread. Without it, every Robolectric test
 * in the JVM fork instantiates `DictateApplication` → spawns a heal
 * thread that runs against the shared [DictateDatabase] singleton; an
 * in-flight heal racing a sibling test's inserted rows overwrites their
 * `status`/`last_error_message` (it promotes any row whose audio file is
 * absent to `FAILED` with `"Audio file not found during healing"`) and
 * fails a method-varying assertion (the textbook non-deterministic
 * shared-state-pollution signature — root-caused as C8-IMPL-1 / F-1 in
 * the dictate-cutover-completion B3 block-report).
 *
 * Mirrors the established
 * [DictateDatabase.resetForTest] / [net.devemperor.dictate.core.JobExecutor.resetForTest]
 * / [net.devemperor.dictate.core.ActiveJobRegistry.resetForTest]
 * production-owned reset-seam convention (K-1 — no Mockito).
 *
 * @see net.devemperor.dictate.database.DurationHealingJob
 * @see net.devemperor.dictate.DictateApplication
 */
object DurationHealingScheduler {

    @Volatile
    private var executor: ExecutorService? = null

    /**
     * Enqueues the one-time [DurationHealingJob.heal] on a single-thread
     * executor and shuts the executor down immediately after submitting
     * the task (the submitted task still runs to completion; the worker
     * thread is released afterwards). Identical semantics to the previous
     * inline `DictateApplication.onCreate` code.
     */
    @Synchronized
    fun schedule(dao: SessionDao, recordingRepository: RecordingRepository) {
        val exec = Executors.newSingleThreadExecutor()
        executor = exec
        exec.execute {
            DurationHealingJob.heal(dao, recordingRepository)
        }
        exec.shutdown()
    }

    /**
     * **Test-only.** Drains any in-flight heal thread so it cannot
     * pollute the shared [DictateDatabase] singleton across Robolectric
     * tests in the same JVM fork.
     *
     * Must be called **before** [DictateDatabase.resetForTest] in a
     * test's `@Before`/`@After` — the heal thread has to be drained
     * before the DB is rebuilt, otherwise a still-running heal can write
     * `status=FAILED` into the *next* test's freshly rebuilt DB. Mirrors
     * the established `*.resetForTest()` production-seam convention.
     * Idempotent — a no-op when no heal was scheduled.
     *
     * Drain mechanic — **graceful** `shutdown()` + `awaitTermination()`,
     * NOT `shutdownNow()` (B3-VAL F-1 deviation D22 from AUDIT-TEST's
     * specified `shutdownNow()`): `shutdownNow()` sends a `Thread`
     * interrupt to the heal thread, which may be inside a Room/SQLite
     * native call. Interrupting a thread mid-native-SQLite-call under
     * Robolectric corrupts the process-wide native SQLite runtime — every
     * subsequent `nativeOpen` in the fork then throws
     * `UnsatisfiedLinkError` (empirically reproduced: a release-suite-wide
     * SQLite cascade). It *also* fails to actually stop the in-flight
     * heal (a JNI-blocked thread ignores the interrupt flag), so the
     * pollution still fired. A graceful `shutdown()` lets the in-flight
     * heal run to completion uninterrupted (against the *old* DB —
     * harmless: that DB is dropped next), and `awaitTermination` blocks
     * the caller until it is done, so it cannot reach the next test's
     * rebuilt DB. This drains deterministically without touching native
     * SQLite state.
     */
    @JvmStatic
    @VisibleForTesting
    internal fun resetForTest() {
        val exec = synchronized(this) {
            val current = executor
            executor = null
            current
        } ?: return
        // Graceful: the already-submitted heal runs to completion (no
        // interrupt → no native-SQLite corruption); awaitTermination
        // blocks until the heal finished, so it cannot race the next
        // test's DB.
        exec.shutdown()
        exec.awaitTermination(10, TimeUnit.SECONDS)
    }
}
