package net.devemperor.dictate.state

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.devemperor.dictate.database.dao.SessionDao
import java.io.File

/**
 * Service-Idle-Stop cleanup pass that combines the two
 * row-level retention policies into one entry point (Spec 1 §6.2 R.17 +
 * §6.3.1 KG-SST-2 RESOLVED).
 *
 * **Two cleanup paths, one trigger:**
 *
 *  1. **`deleteInsertedOlderThan(cutoff)`** — drop COMPLETED rows whose
 *     `inserted_at` is older than `now - gracePeriod`. The text has been
 *     surfaced to the user and the 7-day grace window has elapsed; the
 *     row + its child rows (transcriptions, processing_steps) are no
 *     longer useful for history (the user-facing detail-view has them in
 *     the rendered text already). CASCADE-deletes child rows.
 *  2. **`cleanupOrphanedTerminalAudio(cutoff)`** — find FAILED/CANCELLED
 *     rows whose `audio_file_path` is still set and `created_at` is older
 *     than the same cutoff. Delete each file from disk; bulk-clear the
 *     `audio_file_path` column. **The DB rows themselves stay** (Spec 1
 *     §6.3.1 — FAILED/CANCELLED rows are user-visible "what went wrong"
 *     entries; auto-deleting them would silently lose information).
 *
 * **Why one class with two methods (not two top-level functions)?**
 * Both methods share the cutoff-computation pattern (`now -
 * Pref.SessionCleanupGracePeriodMs`) and the same DAO; bundling makes
 * the call-site at the service-idle slot a single object-method chain
 * and the cutoff-source is canonical. Future cleanup phases (e.g. a
 * `deleteFailedOlderThan(60d)` per Spec 1 §6.3.1 follow-up) can join
 * the same class without rewiring callers.
 *
 * **Layer-Trennung:** the DAO is responsible for SELECT/UPDATE/DELETE;
 * this class is the only place File-IO touches the DB-cleanup pipeline
 * (`RecordingRepository.deleteBySessionId` is the parallel user-driven
 * path). File-IO runs inside the same `withContext(Dispatchers.IO)` as
 * the DAO calls so the whole cleanup is one async slice.
 *
 * **Concurrency contract (Spec 1 §6.3.1 Phase-B S-7):**
 *
 *  - Trigger is gated on `state.recording is Idle && state.pipeline is
 *    Idle` — the caller checks before invoking.
 *  - `cleanup()` is best-effort: a failed `File.delete()` is logged at
 *    WARN; the row stays unchanged and the next idle-stop tries again.
 *  - Concurrent user-delete (`RecordingRepository.deleteBySessionId`)
 *    races are tolerated — `File.delete()` is no-op on a missing file
 *    and `clearAudioFilePathBulk` is idempotent.
 *
 * @property sessionDao Room DAO supplied by `DictateDatabase.sessionDao()`.
 * @property nowProvider monotonic-time supplier — defaults to
 *   `System.currentTimeMillis`; tests inject a fixed time so the
 *   cutoff is deterministic.
 *
 * @see net.devemperor.dictate.database.dao.SessionDao.deleteInsertedOlderThan
 * @see net.devemperor.dictate.database.dao.SessionDao.findOrphanedTerminalAudio
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.2 §6.3.1
 */
class PipelineOrphanCleaner(
    private val sessionDao: SessionDao,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    /**
     * Injectable IO dispatcher — production passes `Dispatchers.IO`,
     * tests can inject `Dispatchers.Unconfined` or a `TestDispatcher`.
     * Aligns with [PipelineRecovery] and [PipelineSessionRepoAdapter]
     * (B3-VAL-W1 F-25 — same-operation-three-ways drift fix).
     */
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Run both cleanup passes in sequence.
     *
     * Ordering rationale: `deleteInsertedOlderThan` first because dropping
     * COMPLETED rows shrinks the search-space of the second pass (in
     * theory; in practice both queries use disjoint status filters, so
     * it's a wash). Either order is correct — keeping the deterministic
     * order makes test assertions stable.
     *
     * @param gracePeriodMs the retention window (default: 7d + 1h safety
     *   buffer). Callers pass `Pref.SessionCleanupGracePeriodMs` value;
     *   tests pass a small value to test the cutoff logic.
     *
     * @return [CleanupResult] with counts of rows/files affected.
     */
    suspend fun cleanup(gracePeriodMs: Long): CleanupResult = withContext(ioContext) {
        val cutoff = nowProvider() - gracePeriodMs

        // Step 1 — drop expired inserted-COMPLETED rows.
        val deletedRows = try {
            sessionDao.deleteInsertedOlderThan(cutoff)
        } catch (t: Throwable) {
            Log.w(TAG, "deleteInsertedOlderThan failed at cutoff=$cutoff", t)
            0
        }

        // Step 2 — delete orphan audio files for old FAILED/CANCELLED rows.
        val (filesDeleted, idsCleared) = cleanupOrphanedTerminalAudio(cutoff)

        CleanupResult(
            deletedCompletedRows = deletedRows,
            filesActuallyDeleted = filesDeleted,
            clearedAudioPathRows = idsCleared.size,
        )
    }

    /**
     * Orphan-audio sub-pass (KG-SST-2). Returns a pair of:
     *
     *  - **`filesDeleted`** — count of `File.delete()` calls that returned
     *    `true` (or that found the file already missing — both are
     *    "success" for cleanup purposes).
     *  - **`idsCleared`** — list of session-ids whose `audio_file_path`
     *    we zeroed in the DB. Returned (not just counted) so tests can
     *    assert which rows were touched.
     *
     * **Idempotence:** every step is safe to re-run. `File.delete()` on a
     * non-existent file is a no-op; `clearAudioFilePathBulk` is an
     * `UPDATE ... WHERE id IN (...)` — running it twice has no effect
     * after the first run.
     *
     * **Layer separation:** the DAO returns paths only; `File.delete()` is
     * here, in the service-side cleanup module. Mirrors the
     * `RecordingRepository.deleteBySessionId` convention (no File-IO in
     * the DAO).
     */
    private fun cleanupOrphanedTerminalAudio(cutoff: Long): Pair<Int, List<String>> {
        val orphans = try {
            sessionDao.findOrphanedTerminalAudio(cutoff)
        } catch (t: Throwable) {
            Log.w(TAG, "findOrphanedTerminalAudio failed at cutoff=$cutoff", t)
            return 0 to emptyList()
        }
        if (orphans.isEmpty()) return 0 to emptyList()

        val cleared = mutableListOf<String>()
        var deletedFiles = 0
        for (row in orphans) {
            val file = File(row.audioFilePath)
            // B3-VAL-W1 F-26 — runCatching for single-statement
            // best-effort file delete (style alignment with
            // CacheDirAudioFileFactory.cleanupOrphans).
            val ok = runCatching { !file.exists() || file.delete() }
                .onFailure { Log.w(TAG, "orphan-audio delete failed for ${row.audioFilePath}", it) }
                .getOrDefault(false)
            if (ok) {
                cleared += row.id
                deletedFiles++
            }
        }
        if (cleared.isNotEmpty()) {
            try {
                sessionDao.clearAudioFilePathBulk(cleared)
            } catch (t: Throwable) {
                Log.w(TAG, "clearAudioFilePathBulk failed for ${cleared.size} rows", t)
            }
        }
        return deletedFiles to cleared
    }

    /**
     * Aggregate diagnostic returned from [cleanup]. Logged at the
     * service-side caller for observability; tests assert against the
     * fields.
     *
     * **Counter split (B3-VAL-W1 F-20):** the old single
     * `deletedAudioFiles` field counted both "file actually deleted"
     * and "row whose `audio_file_path` was cleared because the file
     * was already gone". The split keeps the legacy aggregate
     * ([clearedAudioPathRows] — total rows touched, the existing
     * semantic) plus a precise [filesActuallyDeleted] counter for
     * telemetry (only `File.delete()` calls that returned true).
     */
    data class CleanupResult(
        val deletedCompletedRows: Int,
        /**
         * Count of audio files where `File.delete()` returned true.
         * Excludes rows whose file was already missing (those rows
         * still appear in [clearedAudioPathRows]).
         */
        val filesActuallyDeleted: Int,
        /**
         * Total rows whose `audio_file_path` we zeroed in the DB.
         * Includes both "just deleted" and "was already missing"
         * cases — matches the historical
         * `deletedAudioFiles` headline metric.
         */
        val clearedAudioPathRows: Int,
    ) {
        /**
         * Backwards-compat alias for code that still reads the
         * pre-split field name. Same value as [clearedAudioPathRows].
         */
        @Deprecated(
            "Use clearedAudioPathRows or filesActuallyDeleted — F-20",
            replaceWith = ReplaceWith("clearedAudioPathRows"),
            level = DeprecationLevel.WARNING,
        )
        val deletedAudioFiles: Int get() = clearedAudioPathRows
    }

    private companion object {
        private const val TAG = "PipelineOrphanCleaner"
    }
}
