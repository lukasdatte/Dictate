package net.devemperor.dictate.state

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.devemperor.dictate.core.PipelineTerminalDispatchGuard
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus

/**
 * Per-IME-bind reconciliation that heals the **in-memory pipeline FSM**
 * when a terminal pipeline dispatch was lost (ADR-0011 Decision 2).
 *
 * **Purpose — the covering safety net.** ADR-0011's headless fallback
 * (Decision 1) closes the specific "no delegate bound when the terminal
 * callback fires" window. This reconciliation is the *durable* net for
 * every residual terminal-drop window the fallback cannot cover — e.g.
 * the delegate WAS delivered but the IME main-handler runnable was lost
 * without process death, or any future path where `state.pipeline` is
 * stuck non-Idle while the DB row already reached a terminal status. On
 * every IME bind it inspects the in-flight session and, if the DB says
 * that session already finished, emits the matching terminal action so
 * the FSM leaves `Preparing`/`Running`.
 *
 * **Relationship to [PipelineRecovery] (ordering + idempotence).** The
 * two are complementary and safe in any interleaving:
 *
 *  - **[PipelineRecovery]** runs **once per process**, async at
 *    orchestrator init ([DictateOrchestrator]'s `init` block). It heals
 *    the **DB** (status promotion, ghost cleanup) and merges the
 *    persisted pending set into `state.pendingSessions`. It does NOT
 *    touch the in-memory pipeline FSM.
 *  - **[PipelineBindReconciliation]** runs **per IME bind**. It heals the
 *    in-memory pipeline **FSM** by replaying a terminal DB status into a
 *    terminal action. It does NOT write the DB.
 *
 *  Both are idempotent and may run concurrently (reconcile can fire while
 *  recovery has not finished): the deferred-insertion path dedups by
 *  sessionId (`PendingSessionsAction.AddOne`), and the shared
 *  [PipelineTerminalDispatchGuard] admits exactly one terminal dispatch
 *  per session across all producers. A reconcile that races a still-live
 *  headless fallback simply loses (or wins) the guard — never both fire.
 *
 * **Never preempt a live run.** Reconciliation only acts on a **terminal**
 * DB status (`COMPLETED` / `FAILED` / `CANCELLED`). A non-terminal row
 * (`RECORDING` / `RECORDING_INTERRUPTED` / `TRANSCRIBING` / `RECORDED`)
 * means the run may still genuinely be in flight, so it is a strict no-op
 * — reconciliation must never terminate a running pipeline.
 *
 * **Snapshot arms:**
 *  - `Preparing(S)` / `Running(S)` → the run's terminal dispatch may have
 *    been dropped; reconcile against the DB.
 *  - `ReprocessStaging(S)` → **skipped.** A COMPLETED DB row here is the
 *    *expected resting state* of a finished session the user is re-editing
 *    before resend — it is not a dropped terminal, and dispatching
 *    `PipelineDone` would kick the user out of the staging editor. There is
 *    no in-memory FSM drop to heal in this state.
 *  - `Idle` → nothing in flight; no-op.
 *
 * **Terminal-status mapping (each guarded by [PipelineTerminalDispatchGuard]):**
 *  - `COMPLETED` → `PipelineDone(S, getFinalOutput(S) ?: "", committed=false)`.
 *    `committed=false` keeps text-commit IME-exclusive: the transcript
 *    surfaces as a "Tap to paste" pending part rather than being inserted
 *    (ADR-0011). Text resolves via [getFinalOutput] (step-chain →
 *    transcription → denormalized column), NEVER the raw `final_output_text`
 *    column (historically empty for dictations — resend bug 9637fc3).
 *  - `FAILED` → `PipelineFailed(S, reason)` where `reason` is the row's
 *    `last_error_message` (falling back to `last_error_type`, then a
 *    generic string). `PipelineFailed` triggers `Effect.MarkSessionFailed`
 *    → a `markFailed` DB write; that write is **harmless** on an
 *    already-FAILED row (it re-stamps the same terminal status).
 *  - `CANCELLED` → `Action.PipelineAction.CancelPipeline(S)`. Its reducer
 *    arm (`PipelineModule.kt`) returns the FSM to Idle and drains the
 *    ADR-0009 queue. It emits `Effect.CancelPipelineJob(S)` — a **safe
 *    no-op** here because the underlying `JobExecutor.cancel` only flips a
 *    cancellation token / interrupts the active thread (both null-safe;
 *    the already-finished job leaves nothing live to cancel). It also
 *    emits `Effect.NotifyCancellationHint` — a late "cancelled" info-bar
 *    hint on bind is acceptable UX (the run genuinely was cancelled while
 *    the IME was unbound).
 *
 * **Failure semantics.** The whole body is wrapped in try/catch (like
 * [PipelineRecovery]); a reconcile failure is logged and swallowed so it
 * can never break the IME bind path.
 *
 * **Why narrow lambdas over the full DAO/store?** Reconciliation needs
 * exactly one DB lookup and one snapshot read (ISP): injecting
 * `loadSession` + `snapshotProvider` lambdas keeps the collaborator
 * surface minimal and unit tests trivial (a map-backed lambda plays the
 * DB; a fixed snapshot drives the arm) — no Room/Android dependency.
 *
 * @property loadSession one-shot session lookup (production: the Room
 *   `SessionDao.getById`). Invoked on [ioContext].
 * @property getFinalOutput authoritative-text resolver
 *   (`SessionManager.getFinalOutput`). Invoked on [ioContext] only for the
 *   COMPLETED arm, and only after the guard is won.
 * @property guard the process-wide once-guard shared with the bridge's
 *   delegate-delivery + headless fallback (ADR-0011). The three producers
 *   are mutually exclusive per session through this instance.
 * @property emitAction async main-confined action sink
 *   (`orchestrator.emitAction`) — reconcile fires from a coroutine, so the
 *   terminal action hops onto the main looper where the orchestrator runs.
 * @property snapshotProvider reads the in-flight pipeline sub-state
 *   (production: `store.snapshot`).
 * @property ioContext dispatcher for the DB IO. Production passes
 *   `Dispatchers.IO`; tests inject `Dispatchers.Unconfined` so `withContext`
 *   stays inline with `runTest` scheduling (mirrors [PipelineRecovery]).
 *
 * @see net.devemperor.dictate.state.PipelineRecovery
 * @see net.devemperor.dictate.core.PipelineTerminalDispatchGuard
 * @see net.devemperor.dictate.core.PipelineCallbackBridge
 * @see docs/decisions/0011-pipeline-headless-completion-fallback.md
 */
class PipelineBindReconciliation(
    private val loadSession: (sessionId: String) -> SessionEntity?,
    private val getFinalOutput: (sessionId: String) -> String?,
    private val guard: PipelineTerminalDispatchGuard,
    private val emitAction: (Action) -> Unit,
    private val snapshotProvider: () -> DictateUiState,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Inspect the in-flight session against the DB and heal the FSM if its
     * terminal dispatch was dropped. Idempotent (the guard dedups) and safe
     * to call on every IME bind.
     */
    suspend fun reconcile() {
        try {
            val sid = when (val pipeline = snapshotProvider().pipeline) {
                is PipelineUiState.Preparing -> pipeline.sessionId
                is PipelineUiState.Running -> pipeline.sessionId
                // Expected resting state, not a dropped terminal — see KDoc.
                is PipelineUiState.ReprocessStaging -> return
                is PipelineUiState.Idle -> return
            }

            val row = withContext(ioContext) { loadSession(sid) } ?: return

            when (row.statusEnum) {
                SessionStatus.COMPLETED -> {
                    // Guard first: resolve the (DB-IO) text only if we own
                    // the terminal dispatch.
                    if (guard.tryConsume(sid)) {
                        val text = withContext(ioContext) { getFinalOutput(sid) } ?: ""
                        emitAction(
                            Action.PipelineAction.PipelineDone(
                                sessionId = sid,
                                finalText = text,
                                committed = false,
                            ),
                        )
                    }
                }

                SessionStatus.FAILED -> {
                    if (guard.tryConsume(sid)) {
                        val reason = row.lastErrorMessage
                            ?: row.lastErrorType
                            ?: DEFAULT_FAILURE_REASON
                        emitAction(Action.PipelineAction.PipelineFailed(sid, reason))
                    }
                }

                SessionStatus.CANCELLED -> {
                    if (guard.tryConsume(sid)) {
                        emitAction(Action.PipelineAction.CancelPipeline(sid))
                    }
                }

                // Non-terminal — the run may still be genuinely in flight.
                // Reconciliation must never preempt a live run.
                SessionStatus.RECORDING,
                SessionStatus.RECORDING_INTERRUPTED,
                SessionStatus.TRANSCRIBING,
                SessionStatus.RECORDED -> Unit
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Bind reconciliation failed", t)
        }
    }

    private companion object {
        private const val TAG = "PipelineBindReconcile"

        /** Fallback when a FAILED row carries neither error field. */
        private const val DEFAULT_FAILURE_REASON = "pipeline-failed-recovered-on-bind"
    }
}
