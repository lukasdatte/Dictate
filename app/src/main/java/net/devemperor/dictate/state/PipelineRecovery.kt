package net.devemperor.dictate.state

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import java.io.File

/**
 * DB-replay step run at service startup (Spec 1 §4.6 + §6.3 + §11.6).
 *
 * **What it does (C10, full M4 algorithm):**
 *
 *  1. Read all non-terminal candidate rows from the DB (`RECORDING`,
 *     `TRANSCRIBING`, `RECORDED`, `COMPLETED`).
 *  2. **Promote `RECORDING → FAILED`** — the process died mid-recording;
 *     the partial audio file is not trustworthy. Record an UNKNOWN error
 *     with a `recording-interrupted-by-process-death` reason; opportunistic
 *     `File.delete()` of any associated audio file; clear the
 *     `audio_file_path` column. (Spec 1 §6.3, RECORDING-row).
 *  3. **Downgrade `TRANSCRIBING → RECORDED`** when the audio file exists
 *     (the recording was fully closed, only the pipeline died). Clear
 *     stale `last_error_*` so the UI doesn't show a leftover error from
 *     an earlier retry. No auto-resume (D4) — the user clicks Resend.
 *  4. **Promote `TRANSCRIBING → FAILED`** when the audio file is missing
 *     (storage cleanup race) — record an UNKNOWN error with reason
 *     "audio file vanished before transcription"; clear the path.
 *  5. **Ghost-cleanup `RECORDED → FAILED`** when the audio file is gone
 *     (cache wipe, user "clear cache") — same UNKNOWN-error pattern.
 *  6. Hydrate [DictateUiState.pendingSessions] with the **post-cleanup**
 *     pending set (RECORDED-with-file + COMPLETED-with-pending-insertion).
 *     Uses **MERGE**, not override — a parallel recording that started
 *     during recovery (Spec 1 §6.3 line 3433) keeps its in-memory entry.
 *  7. **SF-4 wiring** — for each COMPLETED row that satisfies
 *     "result available but never inserted via InputConnection" (i.e.
 *     `finalOutputText != NULL AND inserted_at IS NULL`), dispatch
 *     [Action.ResendAction.NotifyManualPasteNeeded] so the IME header
 *     shows the "tap-to-paste" affordance once the IME re-binds. The
 *     ResendModule reducer flips
 *     [ResendState.lastResultNeedsManualPaste] = true on receipt.
 *
 * **Threading:** the heavy DAO work runs in `Dispatchers.IO`. The single
 * `store.update` write resumes on whatever dispatcher called [recover]
 * (Main.immediate in production, Unconfined in tests). Subscribers see
 * the change on the main thread without an extra coroutine hop.
 *
 * **Failure semantics:** Spec 1 §11.6.1 admits this runs async on a
 * `SupervisorJob` without a `CoroutineExceptionHandler`. The IO-block is
 * wrapped in try/catch; on failure the store stays at the initial state
 * and the user sees "no pending sessions" (acceptable degradation).
 *
 * **Why a class with a single suspend function (not a top-level
 * `suspend fun`)?** The Spec 1 §6.3 algorithm has several private
 * sub-steps + dependencies — a class keeps them naturally scoped and
 * unit tests inject a fake [SessionDao] (and an optional emitAction
 * lambda for SF-4) via the constructor.
 *
 * @property sessionDao the Room DAO that owns the persistent recovery
 *   surface. Tests pass a [net.devemperor.dictate.testutil.FakeSessionDao].
 * @property sessionRepo the steady-state read surface used **after** the
 *   status-promotion pass. Production wiring passes a
 *   [PipelineSessionRepoAdapter] backed by the same `sessionDao`; tests
 *   can pass a separate fake to assert the merge contract.
 * @property emitAction action-sink invoked once per
 *   needs-manual-paste session (SF-4). Defaults to no-op so JVM tests
 *   that only care about state-hydration don't have to wire it.
 *
 * @see net.devemperor.dictate.state.DictateOrchestrator
 * @see net.devemperor.dictate.state.PipelinePrefMirror
 * @see net.devemperor.dictate.state.PipelineSessionRepoAdapter
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Required mechanics" item 8
 * @see docs/decisions/0003-service-foreground-pipeline-architecture.md §"OOM Death + Recovery"
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.3 §6.3.1 §11.6
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/manual-paste-field-architecture.md SF-4 closure
 */
class PipelineRecovery(
    private val sessionDao: SessionDao,
    private val sessionRepo: PipelineSessionRepoSubsystem? = null,
    private val emitAction: (Action) -> Unit = {},
    /**
     * Dispatcher used for the DB-IO blocks. Production code passes
     * `Dispatchers.IO`; unit tests inject `Dispatchers.Unconfined`
     * (or a `TestDispatcher`) so the `withContext` switch does not
     * desynchronise from `runTest` scheduling.
     *
     * **Why a parameter, not a hard-coded constant?** `Dispatchers.IO`
     * dispatches off the test-scheduler's coroutine context entirely
     * (it goes to a real thread pool). Tests using
     * `runTest { testScheduler.advanceUntilIdle() }` would otherwise
     * have to busy-wait for the IO block to complete — flaky and slow.
     * Injecting the dispatcher keeps the IO-discipline in production
     * while letting tests run synchronously.
     */
    private val ioContext: CoroutineContext = Dispatchers.IO,
    /**
     * Freshness floor supplier for `findPendingInsertion` (B3-VAL-W1
     * F-2 + Spec 1 §6.5). Defaults to `0L` for the test path; production
     * wiring captures `now - Pref.PendingInsertionFreshnessMs`. Without
     * this gate the first post-upgrade boot would surface every legacy
     * pre-M4 COMPLETED row as a pending-paste candidate.
     */
    private val pendingInsertionFreshnessFloor: () -> Long = { 0L },
) {

    /**
     * Run the full §6.3 recovery algorithm and atomically hydrate
     * [DictateUiState.pendingSessions].
     *
     * Idempotent — re-running [recover] is safe. The status-promotion
     * pass is a no-op the second time around (no RECORDING/TRANSCRIBING
     * rows remain after the first pass); the store-merge filters
     * duplicate session-ids so the in-memory list stays consistent.
     */
    suspend fun recover(store: DictateUiStateStore) {
        try {
            // ── Phase 1: status-promotion (DB writes) ────────────────
            // F-19 dedup: read `findPendingInsertion` once at the start
            // of the IO block, so Phase 2's `sessionRepo.loadPending()`
            // and Phase 4's SF-4 dispatch share the same list (avoids
            // two SELECTs against the same query).
            val freshnessFloor = pendingInsertionFreshnessFloor()
            val pendingInsertionRows = withContext(ioContext) {
                runStatusPromotion()
                runCatching { sessionDao.findPendingInsertion(freshnessFloor) }
                    .onFailure { Log.w(TAG, "findPendingInsertion failed during recovery", it) }
                    .getOrDefault(emptyList())
            }

            // ── Phase 2: read post-cleanup pending list ──────────────
            // `loadPending` runs RECORDED-with-audio + a second
            // `findPendingInsertion(freshnessFloor)` — the second query
            // is the canonical adapter contract; the dedup is internal
            // here so Phase 4 doesn't issue a third SELECT.
            val repo = requireNotNull(sessionRepo) {
                "recover() requires a sessionRepo — use recoverDbOnly() for boot-time " +
                    "DB-cleanup without a Store."
            }
            val pending = repo.loadPending()

            // ── Phase 3: merge into store (idempotent on sessionId) ─
            store.update { current ->
                val seen = current.pendingSessions.map { it.sessionId }.toSet()
                val merged = current.pendingSessions + pending.filter { it.sessionId !in seen }
                current.copy(pendingSessions = merged.toPersistentList())
            }

            // ── Phase 4: SF-4 — dispatch manual-paste hint per row ───
            // For every COMPLETED + final_output_text != NULL +
            // inserted_at IS NULL + fresh-enough row we surfaced, the
            // IME process previously died before the commitText could
            // land (or no InputConnection was available). Tell
            // ResendModule to flip the user-facing flag — when the IME
            // re-binds, the header shows "tap-to-paste".
            pendingInsertionRows.forEach { entity ->
                emitAction(Action.ResendAction.NotifyManualPasteNeeded(entity.id))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Recovery failed", t)
        }
    }

    /**
     * Boot-time recovery — runs only Phase 1 (DB status-promotion).
     *
     * Does not require a [DictateUiStateStore] or a real
     * [PipelineSessionRepoSubsystem], so it can run from a
     * [android.content.BroadcastReceiver] context where the
     * orchestrator is not constructed. Idempotent with [recover] —
     * when the IME later binds and calls the full `recover(store)`,
     * the second status-promotion pass is a no-op (no
     * RECORDING/TRANSCRIBING rows remain).
     *
     * **Threading:** dispatches onto [ioContext] internally; callers
     * pass any dispatcher (typically `Dispatchers.IO`).
     */
    suspend fun recoverDbOnly() {
        try {
            withContext(ioContext) {
                runStatusPromotion()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Boot DB recovery failed", t)
        }
    }

    /**
     * Apply the §6.3 status-promotion algorithm against the DB. Runs in
     * `Dispatchers.IO`. Caller's responsibility to dispatch.
     */
    private fun runStatusPromotion() {
        val candidates: List<SessionEntity> = try {
            sessionDao.getSessionsByStatuses(
                listOf(
                    SessionStatus.RECORDING.name,
                    SessionStatus.TRANSCRIBING.name,
                    SessionStatus.RECORDED.name,
                    SessionStatus.COMPLETED.name,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "getSessionsByStatuses failed during recovery", t)
            return
        }

        // 1. RECORDING → FAILED. Recording-phase doesn't survive OOM-death.
        //    Ordering: DB-promote FIRST, opportunistic File.delete SECOND.
        //    See §6.3 Z. 3333-3341 for the rationale.
        //
        //    ADR-0007 Phase 1 — read through `effectiveAudioFilePaths`
        //    so the dual-column window is transparent. Multi-segment
        //    sessions delete every segment in the list.
        candidates.filter { it.statusEnum == SessionStatus.RECORDING }.forEach { row ->
            safeUpdateStatus(row.id, SessionStatus.FAILED)
            safeUpdateError(row.id, AIProviderException.ErrorType.UNKNOWN.name,
                "recording-interrupted-by-process-death")
            row.effectiveAudioFilePaths.forEach { deleteAudioOpportunistic(it) }
            safeClearAudioPath(row.id)
        }

        // 2. TRANSCRIBING — downgrade to RECORDED if audio exists, else FAILED.
        //    Clear stale errors on downgrade (Spec 1 §6.3 Z. 3389-3394).
        //    Multi-segment: a session counts as "audio exists" only when
        //    EVERY segment is still on disk — a partial loss bricks the
        //    concatenation step downstream.
        candidates.filter { it.statusEnum == SessionStatus.TRANSCRIBING }.forEach { row ->
            val paths = row.effectiveAudioFilePaths
            val audioOk = paths.isNotEmpty() && paths.all { File(it).exists() }
            if (audioOk) {
                safeUpdateStatus(row.id, SessionStatus.RECORDED)
                safeUpdateError(row.id, null, null)
            } else {
                safeUpdateStatus(row.id, SessionStatus.FAILED)
                safeUpdateError(row.id, AIProviderException.ErrorType.UNKNOWN.name,
                    "audio file vanished before transcription")
                safeClearAudioPath(row.id)
            }
        }

        // 3. Ghost RECORDED — audio gone. Promote to FAILED + clear path.
        candidates.filter { it.statusEnum == SessionStatus.RECORDED }.forEach { row ->
            val paths = row.effectiveAudioFilePaths
            val audioOk = paths.isNotEmpty() && paths.all { File(it).exists() }
            if (paths.isNotEmpty() && !audioOk) {
                safeUpdateStatus(row.id, SessionStatus.FAILED)
                safeUpdateError(row.id, AIProviderException.ErrorType.UNKNOWN.name,
                    "audio file vanished")
                safeClearAudioPath(row.id)
            }
        }
    }

    private fun safeUpdateStatus(id: String, status: SessionStatus) {
        try {
            sessionDao.updateStatus(id, status.name)
        } catch (t: Throwable) {
            Log.w(TAG, "updateStatus($id, $status) failed", t)
        }
    }

    private fun safeUpdateError(id: String, type: String?, message: String?) {
        try {
            sessionDao.updateError(id, type, message)
        } catch (t: Throwable) {
            Log.w(TAG, "updateError($id, $type) failed", t)
        }
    }

    private fun safeClearAudioPath(id: String) {
        try {
            sessionDao.clearAudioFilePath(id)
        } catch (t: Throwable) {
            Log.w(TAG, "clearAudioFilePath($id) failed", t)
        }
    }

    private fun deleteAudioOpportunistic(path: String?) {
        if (path == null) return
        val file = File(path)
        if (!file.exists()) return
        // B3-VAL-W1 F-26 — runCatching idiom matches
        // CacheDirAudioFileFactory.cleanupOrphans + LegacyAudioFileMigration
        // for best-effort single-statement file ops. Multi-line
        // `safeUpdateStatus / safeUpdateError / safeClearAudioPath`
        // stay as try/catch because they have multi-statement bodies.
        runCatching { file.delete() }
            .onFailure { Log.w(TAG, "opportunistic audio-delete failed for $path", it) }
    }

    private companion object {
        private const val TAG = "PipelineRecovery"
    }
}
