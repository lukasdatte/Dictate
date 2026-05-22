package net.devemperor.dictate.state

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.database.converter.Converters
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import java.io.File

/**
 * Production adapter that implements [PipelineSessionRepoSubsystem] on top
 * of [SessionDao] (Spec 1 §6.3, §6.4).
 *
 * **What this replaces (C7 → C10):** the no-op
 * `PipelineServiceStubSubsystems.sessionRepo` from C7 — that stub returned
 * `emptyList()` from [loadPending] and dropped every other call into
 * logcat. With C9's `SessionDao` surface (M4 migration) and C10's recovery
 * algorithm, the real adapter can drive boot-time hydration of
 * [DictateUiState.pendingSessions] from persistent storage.
 *
 * **What `loadPending()` returns (Spec 1 §6.3, recovery-tabelle):**
 *
 * The repo's purview is the **steady-state read** for the
 * `PendingSessionsModule`. The §6.3 status-promotion logic (RECORDING →
 * FAILED, TRANSCRIBING → RECORDED-or-FAILED, ghost-session cleanup) is
 * **not** the repo's job — [PipelineRecovery] runs that algorithm *first*
 * and then calls `loadPending()` to read the now-cleaned list. The split:
 *
 *  - `PipelineRecovery.recover(store)` — runs the status-promotion pass
 *    (writes DB), then calls `sessionRepo.loadPending()` to read the
 *    cleaned list, then writes it into [DictateUiState.pendingSessions].
 *  - `PipelineSessionRepoAdapter.loadPending()` — returns the
 *    user-visible "pending" set: `RECORDED` rows with an existing audio
 *    file + `COMPLETED` rows whose result hasn't been inserted yet
 *    (`final_output_text != NULL AND inserted_at IS NULL`).
 *
 * Returning the cleaned list (not the raw mid-flight rows) keeps the
 * `PendingSessionsModule` reducer pure — it doesn't see a `RECORDING`
 * row that needs to be filtered out. The recovery pass is the only place
 * that promotes statuses.
 *
 * **`markInserted` / `markFailed`:** straightforward DAO update wrappers.
 * Both run on `Dispatchers.IO` because Room queries on the main thread
 * would block dispatch + paint (see [DictateDatabase.buildDatabase] which
 * does allow main-thread queries as a legacy concession — the repo
 * adapter dispatches off explicitly so future callers don't have to
 * remember the convention).
 *
 * **`pendingFlow()`:** Phase-1 returns [emptyFlow] — the
 * `PendingSessionsModule` doesn't yet have a Flow-driven observer wired
 * up (the production update-path is `PipelineRecovery` at boot + the
 * legacy `SessionManager.finalizeXxx` writes that the IME path triggers).
 * A future B-phase can add `Room`'s `Flow<List<SessionEntity>>` if the
 * pending list needs live-updates outside of the boot pass. For now,
 * `emptyFlow` is the correct conservative wiring — it keeps the
 * `PendingSessionsModule` happy without claiming behaviour we don't
 * deliver yet.
 *
 * @property sessionDao the Room DAO supplied by `DictateDatabase.sessionDao()`.
 *
 * @see net.devemperor.dictate.state.PipelineRecovery
 * @see net.devemperor.dictate.state.PendingSessionsModule
 * @see net.devemperor.dictate.database.dao.SessionDao
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.3 §6.4
 */
class PipelineSessionRepoAdapter(
    private val sessionDao: SessionDao,
    /**
     * Repository for the per-session segment files (recording-stack-
     * completion Block A1). The adapter's [syncAudioFilePaths] reads the
     * live segment list from this and writes the joined paths into the
     * DB row's `audio_file_paths` column. Nullable for the test surface
     * that only exercises pending-list / mark-status calls.
     */
    private val audioFileRepository: AudioFileRepository? = null,
    /**
     * Freshness floor supplier for [findPendingInsertion]. Production
     * wiring captures `now - Pref.PendingInsertionFreshnessMs` lazily;
     * tests inject a constant (e.g. `{ 0L }`) so the floor doesn't
     * exclude rows with synthetic `created_at` values. See
     * `research/b3-cleanup-cascade-and-backfill-policy.md` §5.3 — the
     * floor distinguishes "freshly COMPLETED but not yet inserted" from
     * "pre-M4 legacy whose NULL inserted_at is a backfill artefact".
     */
    private val pendingInsertionFreshnessFloor: () -> Long = { 0L },
    /**
     * Injectable IO dispatcher — production passes `Dispatchers.IO`,
     * tests can inject `Dispatchers.Unconfined` or a `TestDispatcher`
     * (B3-VAL-W1 F-25 alignment with `PipelineRecovery`).
     */
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : PipelineSessionRepoSubsystem {

    /**
     * Read the post-recovery "pending" set from the DB.
     *
     * Returns the union of two queries (Spec 1 §11.6.2):
     *
     *  1. **`RECORDED` rows with an existing audio file** — sessions that
     *     were recorded but never piped through transcription (User-cancel
     *     before clicking Send, or a crash mid-Stop). The audio file must
     *     still exist on disk; otherwise the row is a ghost and gets
     *     filtered.
     *  2. **`COMPLETED` rows with `final_output_text != NULL AND
     *     inserted_at IS NULL AND created_at >= freshnessFloor`** — the
     *     pipeline produced text but the user hasn't seen it yet. The
     *     freshness floor excludes pre-M4 legacy rows (Spec 1 §6.5,
     *     B3-VAL-W1 F-2).
     *
     * Both sets are surfaced in the UI as "Resume" entries. The user
     * either clicks Resend (RECORDED → retry pipeline) or taps the
     * pending-text affordance (COMPLETED → insert from clipboard /
     * paste-hint per F-1).
     */
    override suspend fun loadPending(): List<PendingSession> = withContext(ioContext) {
        // RECORDED rows with existing audio.
        // ADR-0007 Phase 1 — read through `effectiveAudioFilePaths` so
        // dual-column rows are handled. Multi-segment sessions must
        // have EVERY segment on disk to qualify; a partial loss bricks
        // the downstream MediaMuxer concat.
        val recordedWithAudio = sessionDao.getSessionsByStatuses(listOf(SessionStatus.RECORDED.name))
            .filter { entity ->
                val paths = entity.effectiveAudioFilePaths
                paths.isNotEmpty() && paths.all { File(it).exists() }
            }

        // COMPLETED rows with pending insertion — uses dedicated DAO query
        // with the freshness floor that gates legacy rows.
        val pendingInsertion = sessionDao.findPendingInsertion(pendingInsertionFreshnessFloor())

        (recordedWithAudio + pendingInsertion).map { it.toPendingSession() }
    }

    override suspend fun markInserted(sessionId: String, at: Long) {
        withContext(ioContext) {
            try {
                sessionDao.markInserted(sessionId, at)
            } catch (t: Throwable) {
                // Fail-soft: the adapter never throws back into the
                // module/effect-handler. Logged so debug builds surface
                // the failure (B3-VAL-W1 F-24).
                Log.w(TAG, "markInserted failed for $sessionId", t)
            }
        }
    }

    /**
     * Promote a session to terminal-FAILED with the given reason.
     *
     * Implementation note: per Spec 1 §6.3 the `markFailed(id, reason)`
     * call collapses into the existing `updateStatus + updateError` pair —
     * we don't add a dedicated DAO method (DRY). The reason string lands
     * in `last_error_message`; `last_error_type` is set to
     * `AIProviderException.ErrorType.UNKNOWN.name` because the
     * `PipelineSessionRepo` surface doesn't carry a structured ErrorType
     * (callers that need a typed error use the DAO directly).
     */
    override suspend fun markFailed(sessionId: String, reason: String) {
        withContext(ioContext) {
            try {
                sessionDao.updateStatus(sessionId, SessionStatus.FAILED.name)
                sessionDao.updateError(
                    sessionId,
                    net.devemperor.dictate.ai.AIProviderException.ErrorType.UNKNOWN.name,
                    reason,
                )
            } catch (t: Throwable) {
                // Fail-soft: see [markInserted] (B3-VAL-W1 F-24).
                Log.w(TAG, "markFailed failed for $sessionId", t)
            }
        }
    }

    /**
     * Phase-1 — no live flow. See class KDoc for rationale.
     *
     * The `PendingSessionsModule` is a passive sink: it copies whatever
     * the boot-time [PipelineRecovery] writes via
     * [Action.PendingSessionsAction.Refresh]. A live Flow would require
     * Room's `@Query` return-type change (`List<SessionEntity>` →
     * `Flow<List<SessionEntity>>`) plus a `services.scope.launch`
     * collector — both deferrable until a use-case demands it.
     */
    override fun pendingFlow(): Flow<List<PendingSession>> = emptyFlow()

    /**
     * Sync `audio_file_paths` from the live segment list on disk.
     * See [PipelineSessionRepoSubsystem.syncAudioFilePaths] for the
     * triggering boundaries.
     *
     * **Encoding (recording-stack-completion Block A1).** Room's
     * `@Query("UPDATE ... SET audio_file_paths = :paths")` does NOT run
     * the [Converters.fromStringList] type-converter on the bound
     * parameter — type-converters apply only on row-write through `@Insert`
     * / `@Update` entity methods. The adapter therefore joins the list
     * with [Converters.DELIMITER] (pipe) itself before binding. The
     * read-side [SessionEntity.audioFilePaths] still goes through the
     * `toStringList` converter on row-read, so the round-trip is
     * symmetric.
     *
     * **Fail-soft.** A DAO/IO failure during sync is logged + swallowed;
     * recording must never crash because the path-mirror failed. The
     * next sync boundary (segment-roll, stop-and-send) gets another shot,
     * and `PipelineRecovery` reads `effectiveAudioFilePaths` so a missed
     * sync doesn't strand the session — the legacy `audio_file_path`
     * column still points at the first segment.
     */
    override suspend fun syncAudioFilePaths(sessionId: String): Int =
        withContext(ioContext) {
            val repo = audioFileRepository ?: run {
                Log.w(TAG, "syncAudioFilePaths($sessionId) — no AudioFileRepository wired, skipping")
                return@withContext 0
            }
            try {
                val segments = repo.segments(sessionId)
                val paths = segments.map { it.absolutePath }
                val encoded = paths.joinToString(Converters.DELIMITER)
                sessionDao.updateAudioFilePaths(sessionId, encoded)
                paths.size
            } catch (t: Throwable) {
                Log.w(TAG, "syncAudioFilePaths failed for $sessionId", t)
                0
            }
        }

    private companion object {
        private const val TAG = "PipelineSessionRepoAdapter"
    }
}

/**
 * Boundary mapper: [SessionEntity] (DB row) → [PendingSession] (UI model).
 *
 * Lives at the adapter layer because [PipelineSessionRepoSubsystem]'s
 * contract is in terms of [PendingSession] (the state-side type), but
 * the DAO returns full [SessionEntity] rows. The mapper drops the DB-
 * only fields (origin, queued_prompt_ids, last_error_*, target_app_package,
 * inserted_at, …) — the UI doesn't need them for the pending-list
 * affordance.
 *
 * **`statusEnum` is the boundary** — see [SessionEntity.statusEnum]: a
 * row with a status string unknown to this build (downgrade scenario)
 * falls back to `SessionStatus.RECORDED` rather than crashing. The
 * mapper inherits that behaviour.
 */
internal fun SessionEntity.toPendingSession(): PendingSession = PendingSession(
    sessionId = id,
    status = statusEnum,
    transcribedText = finalOutputText,
    createdAt = createdAt,
    lastErrorMessage = lastErrorMessage,
)
