package net.devemperor.dictate.audio

import android.util.Log
import net.devemperor.dictate.database.dao.SessionDao
import java.io.File

/**
 * Periodic cache-audio cleanup pass (recording-stack-completion §4.5.2).
 *
 * Sweeps stale segment files (`sess_{sid}_seg{N}.m4a`) and transient
 * merged files (`sess_{sid}_merged.m4a`) out of `cache/audio/`. The
 * persistent canonical audio in `files/recordings/{sid}.m4a`
 * (recording-stack-completion §A.4c) is untouched — that path is
 * owned by [PipelineOrphanCleaner].
 *
 * **Four-phase algorithm.**
 *
 *  1. **List.** Read every owned file from the repository grouped
 *     by session-id.
 *  2. **Alive-set.** Query the DAO for session-ids whose status is
 *     non-terminal (`RECORDING`, `RECORDING_INTERRUPTED`, `RECORDED`,
 *     `TRANSCRIBING`). Files belonging to these sessions are not
 *     deletion-candidates regardless of age.
 *  3. **Decide + delete.** For every (sessionId, files) pair whose
 *     sessionId is NOT in the alive-set, delete each file whose
 *     `lastModified()` is older than the cutoff. Files younger than
 *     the cutoff stay — the user may still reprocess the session.
 *  4. **Metric.** Return a result tuple for the caller's log line.
 *
 * **Why phase 1 before phase 2 (not after)?**
 *
 * Race: a session can transition from `RECORDING_INTERRUPTED` to
 * `FAILED` (via [net.devemperor.dictate.state.PipelineRecovery]) at any
 * time. If phase 2 ran first and a session terminated *between* the
 * two queries, files freshly transitioned to "deletable" could be
 * scooped up by phase 3. Listing first means the file-set reflects
 * the disk state at time T₀ while the alive-set reflects the DB at
 * time T₁ ≥ T₀ — any session terminated in (T₀, T₁) shows up as
 * alive (because phase 2's set is the *future* set), so we err on
 * the side of *keeping* files. Stale-towards-keep is the conservative
 * choice.
 *
 * **Best-effort throughout.** Individual `File.delete()` failures
 * are logged at WARN and the loop continues; the next job run gets
 * another chance. The job never throws back to the scheduler.
 *
 * @see net.devemperor.dictate.audio.CacheAudioCleanupScheduler
 *   (production-side entry point with timestamp gating + executor)
 * @see net.devemperor.dictate.state.PipelineOrphanCleaner
 *   (parallel cleanup pass for `files/recordings/` + DB-side rows)
 * @see docs/plans/2026-05-22 - dictate-recording-stack-completion/
 *   dictate-recording-stack-completion.md §4.5.2
 */
object CacheAudioCleanupJob {

    private const val TAG = "CacheAudioCleanupJob"

    /**
     * Run one cleanup pass.
     *
     * @param repository the owner of the on-disk audio cache layout
     *   (production: [CacheDirAudioFileRepository]).
     * @param sessionDao the DAO supplying the alive-set.
     * @param nowMs the wall-clock reference for the cutoff
     *   computation. Injectable so tests can drive a deterministic
     *   timeline (`{ fixedClock }`).
     * @param ttlMs files older than `nowMs - ttlMs` are deletion
     *   candidates (when their session is terminal).
     *
     * @return a [CleanupResult] for the caller to log; tests assert
     *   against the fields.
     */
    fun run(
        repository: AudioFileRepository,
        sessionDao: SessionDao,
        nowMs: Long,
        ttlMs: Long,
    ): CleanupResult {
        // Phase 1: list every repo-owned file grouped by sessionId.
        val owned = try {
            repository.listAllOwnedFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "listAllOwnedFiles failed", t)
            return CleanupResult(scanned = 0, deleted = 0, kept = 0)
        }
        if (owned.isEmpty()) {
            return CleanupResult(scanned = 0, deleted = 0, kept = 0)
        }

        // Phase 2: read the alive-set from the DB.
        val alive: Set<String> = try {
            sessionDao.findActiveSessionIds().toSet()
        } catch (t: Throwable) {
            // Without the alive-set the job cannot make a safe
            // deletion decision. Bail; the next run retries.
            Log.w(TAG, "findActiveSessionIds failed — skipping cleanup pass", t)
            val scanned = owned.values.sumOf { it.size }
            return CleanupResult(scanned = scanned, deleted = 0, kept = scanned)
        }
        val cutoff = nowMs - ttlMs

        // Phase 3: per-file decision + delete.
        var scanned = 0
        var deleted = 0
        var kept = 0
        for ((sessionId, files) in owned) {
            if (sessionId in alive) {
                // Session is still in flight. Files stay regardless of age.
                scanned += files.size
                kept += files.size
                continue
            }
            for (file in files) {
                scanned++
                val mtime = runCatching { file.lastModified() }.getOrDefault(0L)
                if (mtime in 1L until cutoff) {
                    val ok = runCatching { file.delete() }
                        .onFailure { Log.w(TAG, "delete failed for ${file.name}", it) }
                        .getOrDefault(false)
                    if (ok) deleted++ else kept++
                } else {
                    // mtime == 0L (read failure) → keep; mtime >= cutoff → keep.
                    kept++
                }
            }
        }

        if (deleted > 0 || scanned > 0) {
            Log.i(
                TAG,
                "cleanup: scanned=$scanned, deleted=$deleted, kept=$kept, " +
                    "aliveSessions=${alive.size}, cutoff=$cutoff",
            )
        }
        return CleanupResult(scanned = scanned, deleted = deleted, kept = kept)
    }

    /**
     * Diagnostic counters for [run].
     *
     * @property scanned every repository-owned file the job looked
     *   at (sum of files across all sessions).
     * @property deleted files where `File.delete()` returned `true`.
     * @property kept files left on disk (either alive-session-owned,
     *   younger than the TTL, or whose `delete()` failed).
     */
    data class CleanupResult(
        val scanned: Int,
        val deleted: Int,
        val kept: Int,
    )
}
