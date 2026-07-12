// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package. The sub-directory keeps modules grouped together
// in the file tree without paying the per-file package cost.
package net.devemperor.dictate.state

import android.util.Log
import java.io.File
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

/**
 * Owns the [PipelineUiState] FSM (Idle / Preparing / Running /
 * ReprocessStaging).
 *
 * **Manual-paste flag relocated (F-1, 2026-05-15):** the IME-service-death
 * recovery `lastResultNeedsManualPaste` flag was previously declared as a
 * top-level [DictateUiState] field nominally attributed to PipelineModule.
 * It now lives on [ResendState] as a sibling of `lastAudioExists`; the
 * `NotifyManualPasteNeeded` / `ClearManualPasteFlag` actions are owned by
 * [Action.ResendAction]. See `research/manual-paste-field-architecture.md`.
 *
 * Side-effects delegate to the [ModuleServices.pipelineRunner] (AI-call
 * submission), [ModuleServices.sessionRepo] (DB persistence), and
 * [ModuleServices.notificationCoordinator] (foreground-service status
 * notification, Spec 1 §11).
 *
 * **Cross-module cascades (Coupling-Matrix §15.1.x row "Pipeline"):**
 *
 * - `Pipeline → Recording`: **Phase-2 (deferred no-op).** The matrix
 *   reserves this direction but the observer body emits no cascade —
 *   the actual "Send" flow is Recording→Pipeline via the
 *   [RecordingModule.Effect.EmitPipelineTrigger] async re-entry
 *   (F-2 fix, 2026-05-15). See the inline comment in
 *   [onCrossModuleStateChange] below; Phase 2 may reintroduce the
 *   reverse cascade if a future flow inverts the trigger order.
 * - `Pipeline → ViewMode`: when Pipeline transitions to a
 *   [PipelineUiState.Idle]-equivalent state (Done / Failed / Cancelled)
 *   from a non-Idle state, cascade
 *   [Action.ViewModeAction.OnPipelineDone] — this is the T7
 *   "Geist-Widget"-bug structural protection (Spec 3 §7.3 T7).
 * - `Pipeline → Resend`: cascade [Action.ResendAction.MarkLastAudio]
 *   so the Resend button knows there's a fresh audio file to retry.
 *   **F-21 (2026-05-15):** the cascade hard-codes `exists = true`
 *   regardless of whether PipelineDone was success vs cancel. The
 *   cancel-path audio-file deletion is not yet implemented (Phase-2
 *   cancel-cascade); when it lands, the observer should emit
 *   `MarkLastAudio(exists = false)` on the cancel-path branch
 *   instead — both halves must land together. Phase-1 acceptable,
 *   and (F-102 review, 2026-07-03) currently *correct*, not just
 *   self-consistent: with the audio still on disk a CANCELLED
 *   session IS resendable per `ResendStatusDispatcher` (Resume, or
 *   Insert when a partial transcription exists), so `exists = true`
 *   matches the click-time behaviour. Safety net since F-005: the
 *   boot-time `PipelineRecovery` Phase 6 re-derives the axis from
 *   `ResendableSessionPolicy`, so if the deletion half ever lands
 *   without this branch, a stale `true` self-corrects at the next
 *   service start (the runtime gap until then remains — hence
 *   "land together").
 * - `Pipeline → LivePrompt`: cascade [Action.LivePromptAction.ChainNext]
 *   when LivePrompt is enabled + has a pending chain (Spec 1
 *   §15.1 + §15.x LivePromptModule).
 *
 * Cascade emission uses **frozen-snapshot** semantics: `prev` and
 * `next` are captured once per observation pass; the orchestrator
 * dispatches each emitted action recursively at `depth + 1`.
 *
 * **Pipeline-Done cascade ordering note:** the Resend + LivePrompt +
 * ViewMode cascades all observe `prev != Done && next is Done`. The
 * orchestrator's `registry.all`-iteration order determines which
 * cascade dispatches first; later cascades see the prior cascades'
 * state mutations because the orchestrator re-snapshots between
 * recursive dispatches. The order is fixed in
 * [net.devemperor.dictate.state.DictateModuleRegistry.Default.all]
 * (verified by `DictateOrchestratorCascadeOrderTest`).
 *
 * @see net.devemperor.dictate.state.PipelineUiState
 * @see net.devemperor.dictate.state.Action.PipelineAction
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Cascade-Order"
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.3
 */
object PipelineModule : DictateModule<PipelineUiState, Action.PipelineAction, PipelineModule.Effect> {

    override val id: ModuleId = ModuleId.Pipeline
    override val actionClass: KClass<Action.PipelineAction> = Action.PipelineAction::class

    override fun read(global: DictateUiState): PipelineUiState = global.pipeline
    override fun write(global: DictateUiState, sub: PipelineUiState): DictateUiState =
        global.copy(pipeline = sub)

    override fun initialState(): PipelineUiState = PipelineUiState.Idle

    /** Module-local side-effect surface. */
    sealed interface Effect : SideEffect {

        /** Submit a fresh pipeline job (transcription + optional rewording). */
        data class SubmitPipeline(val sessionId: String, val audioFile: File) : Effect

        /**
         * Submit a re-processing job with a custom prompt queue + language override.
         *
         * **F-19 (2026-05-15) — explicit nullable contract.** `audioFile`
         * is nullable to encode the "runner resolves the path by
         * `sessionId`-lookup in the DB session record" semantics
         * unambiguously at the type level. Earlier prose used the
         * `File("")` empty-path convention, but a future B3
         * implementer could easily miss the comment and treat the
         * empty path as a real file path. Pass `null` when the staging
         * record is the authoritative path source (the Phase-1
         * SendStaging case). Passing `File("")` is forbidden — use
         * `null` instead.
         *
         * **F-001 (2026-07-03) — content slots, explicit-vs-unset.**
         * `queue: List<Int>` was replaced by
         * `queuedPromptSlots: List<PromptQueueSlot>?` (the transport's
         * content-capable slot type). The nullability is load-bearing and
         * mirrors [JobRequest.TranscriptionPipeline.queuedPromptSlots]:
         *  - **empty** = EXPLICITLY NONE — the user emptied the staged
         *    queue; the pipeline runs zero prompts and must NOT read the
         *    live keyboard queue.
         *  - **null**  = UNSET — no explicit queue travelled; the pipeline
         *    falls back to the live auto-apply queue (legacy semantics).
         *
         * The `SendStaging` reducer arm always emits an **explicit** list
         * (the staged queue) — the `null` shape stays available for any
         * future caller that genuinely wants the run-time fallback.
         */
        data class SubmitReprocess(
            val sessionId: String,
            val audioFile: File?,
            val queuedPromptSlots: List<net.devemperor.dictate.core.PromptQueueSlot>?,
            val language: String?,
        ) : Effect

        /** Cancel a running pipeline job by id. */
        data class CancelPipelineJob(val sessionId: String) : Effect

        /**
         * Surface the just-cancelled run as a typed info-bar notice
         * (R5, spec §3.6) — Mode-1 cross-module emit into
         * [Action.InfoHintAction.PipelineCancelled]. Fired only by the
         * real `CancelPipeline` arm for a Preparing/Running state, so
         * every cancel origin (keyboard button, FGS notification
         * action) is covered by one seam while leaving the
         * ReprocessStaging editor exit silent.
         */
        data object NotifyCancellationHint : Effect

        /** Mark a session as inserted in the DB (post-Done). */
        data class MarkSessionInserted(val sessionId: String, val at: Long) : Effect

        /** Mark a session as failed in the DB (post-Failed / post-Cancelled). */
        data class MarkSessionFailed(val sessionId: String, val reason: String) : Effect

        /** Update the persistent FGS notification. */
        data class UpdateNotification(val status: NotificationStatus) : Effect

        /** Dismiss the persistent FGS notification (pipeline back to Idle). */
        data object DismissNotification : Effect

        /**
         * Append the just-finished session to `state.pendingSessions`
         * directly — no DB round-trip.
         *
         * Fired by [Action.PipelineAction.PipelineDone] when
         * `committed == false` (B3.5 widget-host-block path). The pipeline
         * finished producing [text] but the IME couldn't commit it into
         * an `InputConnection` (host-window mismatch).
         *
         * The earlier design re-read the pending list via
         * `sessionRepo.loadPending()`, but that lost a race: the pipeline
         * runner's `finalizeCompleted` (status → COMPLETED) and the
         * `PipelineDone` dispatch run on different threads, so
         * `findPendingInsertion` (which filters `status = COMPLETED`)
         * frequently returned an empty list (Logcat: "loaded 0 pending
         * sessions"). Carrying the session payload on the effect makes it
         * race-free — the reducer already has `sessionId` + `finalText`.
         */
        data class AddPendingInsertSession(
            val sessionId: String,
            val text: String,
            val createdAt: Long,
        ) : Effect
    }

    override fun reduce(
        state: PipelineUiState,
        action: Action.PipelineAction,
        ctx: ReducerContext,
    ): TransitionResult<PipelineUiState, Effect>? = when (action) {
        // ─── Lifecycle entry ───────────────────────────────────────────
        is Action.PipelineAction.TriggerPipeline -> when (state) {
            is PipelineUiState.Idle -> TransitionResult(
                nextState = PipelineUiState.Preparing(sessionId = action.sessionId),
                sideEffects = listOf(
                    Effect.SubmitPipeline(action.sessionId, action.audioFile),
                    Effect.UpdateNotification(
                        NotificationStatus.Pipeline(action.sessionId, step = "preparing"),
                    ),
                ),
            )
            // ADR-0009: a run arrived while another is active — enqueue it
            // behind the active run instead of dropping it (the old silent
            // reject lost the user's part-B send). No submit effect: the
            // chain-start (terminal arms) is what submits. Dedup by
            // sessionId — a re-trigger of the active or an already-queued
            // session is a double-tap (return null; resend/reprocess route
            // through different actions, so none legitimately re-queues).
            is PipelineUiState.Preparing ->
                if (isDuplicateTrigger(state.sessionId, state.queued, action.sessionId)) {
                    null
                } else {
                    TransitionResult(
                        nextState = state.copy(
                            queued = state.queued.add(
                                QueuedRun(action.sessionId, action.audioFile, ctx.now),
                            ),
                        ),
                    )
                }
            is PipelineUiState.Running ->
                if (isDuplicateTrigger(state.sessionId, state.queued, action.sessionId)) {
                    null
                } else {
                    TransitionResult(
                        nextState = state.copy(
                            queued = state.queued.add(
                                QueuedRun(action.sessionId, action.audioFile, ctx.now),
                            ),
                        ),
                    )
                }
            // Staging is entered from Idle and the secondary record button is
            // not offered there — no run can arrive to queue (unchanged).
            is PipelineUiState.ReprocessStaging -> null
        }

        is Action.PipelineAction.StartPipeline -> when (state) {
            is PipelineUiState.Preparing -> if (state.sessionId == action.sessionId) {
                TransitionResult(
                    // F-13: stamp the progress baseline. `startedAtMs` is the
                    // elapsed-timer origin; `totalSteps` comes from the runner
                    // via the action payload (the only place the total is
                    // known). `completedSteps`/`elapsedMs` start at zero.
                    //
                    // #AE-DEEP2: merge `autoEnterActive` from BOTH the
                    // runner-supplied default (`action.autoEnterActive`,
                    // captured at the SEND-tap from Pref.AutoEnter) AND any
                    // in-Preparing user-toggle (`state.autoEnterActive`, set
                    // by `ToggleRunningAutoEnter` during the upload window).
                    // Either side being true wins, so the user's mid-upload
                    // toggle is never lost.
                    //
                    // Phase 5.A of dictate-render-cutover-completion-vol2 —
                    // stepHistory + hasFailure reset to fresh baselines so
                    // a previous run's leaks cannot bleed into this one.
                    nextState = PipelineUiState.Running(
                        sessionId = action.sessionId,
                        target = InsertionTarget.INPUT_CONNECTION,
                        autoEnterActive = action.autoEnterActive || state.autoEnterActive,
                        completedSteps = 0,
                        totalSteps = action.totalSteps,
                        startedAtMs = ctx.now,
                        elapsedMs = 0L,
                        hasFailure = false,
                        stepHistory = kotlinx.collections.immutable.persistentListOf(),
                        // ADR-0009: the run-queue survives the Preparing →
                        // Running hop — a fresh default here would silently
                        // drop every second-in-line run (mid-upload enqueues
                        // and the chain-start's handed-over rest alike).
                        queued = state.queued,
                    ),
                    sideEffects = listOf(
                        Effect.UpdateNotification(
                            NotificationStatus.Pipeline(action.sessionId, step = "running"),
                        ),
                    ),
                )
            } else null
            else -> null
        }

        is Action.PipelineAction.StepStarted -> when (state) {
            is PipelineUiState.Running -> if (state.sessionId == action.sessionId) {
                // Phase 5.A — append a RUNNING row to stepHistory; the
                // legacy renderer used to materialise this row via the
                // imperative `addRunningStep` callback, which Phase 5.B
                // collapses into a state-flow consumer.
                val nextHistory = state.stepHistory.add(
                    StepRowItem(
                        stepName = action.stepName,
                        status = StepStatus.RUNNING,
                        startedAtMs = ctx.now,
                    ),
                )
                TransitionResult(
                    // F-13: a step boundary is a progress tick — restamp the
                    // elapsed timer so the live label advances even between
                    // step completions. `totalSteps` is NOT touched here:
                    // `StepStarted` carries no total in its payload, and the
                    // authoritative total was already set by `StartPipeline`
                    // (see ### Deviations Dev-1).
                    nextState = state.copy(
                        elapsedMs = elapsedSince(state.startedAtMs, ctx.now),
                        stepHistory = nextHistory,
                    ),
                    sideEffects = listOf(
                        Effect.UpdateNotification(
                            NotificationStatus.Pipeline(action.sessionId, step = action.stepName),
                        ),
                    ),
                )
            } else null
            else -> null
        }

        is Action.PipelineAction.StepCompleted -> when (state) {
            // F-13: count the finished step and restamp the elapsed timer.
            // The live record-button label + FGS notification read
            // `completedSteps`/`totalSteps`/`elapsedMs` off `Running`.
            //
            // F-6: the runner is authoritative on step count;
            // `completedSteps` is NOT clamped here — if the runner
            // mis-reports / double-emits / `totalSteps == 0`, the label
            // may briefly show an overrun (`N/M` with `N > M`). The
            // display formatter, not the pure reducer, owns any cosmetic
            // clamp (keeps the reducer minimal + runner-authoritative;
            // a `completedSteps.coerceAtMost(totalSteps)` could live in
            // `formatPipelineLabel` in a later display-polish block —
            // forward-note only, not implemented in B1).
            //
            // Phase 5.A — finalise the last RUNNING row to COMPLETED
            // with durationMs.
            is PipelineUiState.Running -> if (state.sessionId == action.sessionId) {
                val lastIdx = state.stepHistory.indexOfLast { it.status == StepStatus.RUNNING }
                val nextHistory = if (lastIdx >= 0) {
                    val row = state.stepHistory[lastIdx]
                    state.stepHistory.set(
                        lastIdx,
                        row.copy(
                            status = StepStatus.COMPLETED,
                            durationMs = ctx.now - row.startedAtMs,
                        ),
                    )
                } else {
                    state.stepHistory
                }
                TransitionResult(
                    nextState = state.copy(
                        completedSteps = state.completedSteps + 1,
                        elapsedMs = elapsedSince(state.startedAtMs, ctx.now),
                        stepHistory = nextHistory,
                    ),
                )
            } else null
            else -> null
        }

        is Action.PipelineAction.StepFailed -> when (state) {
            // Q6 (cutover-vol2 §7): StepFailed is NOT pipeline-ending —
            // executeQueuedPrompts continues with the next queued prompt.
            // The Running arm keeps the pipeline in Running with
            // hasFailure=true, marks the last row as FAILED, and does NOT
            // dispatch DismissNotification (FGS stays alive for the next
            // step). Only PipelineFailed / PipelineDone / CancelPipeline
            // actually end the pipeline.
            is PipelineUiState.Running -> if (state.sessionId == action.sessionId) {
                val lastIdx = state.stepHistory.indexOfLast { it.status == StepStatus.RUNNING }
                val nextHistory = if (lastIdx >= 0) {
                    val row = state.stepHistory[lastIdx]
                    state.stepHistory.set(
                        lastIdx,
                        row.copy(
                            status = StepStatus.FAILED,
                            durationMs = ctx.now - row.startedAtMs,
                        ),
                    )
                } else {
                    state.stepHistory
                }
                TransitionResult(
                    nextState = state.copy(
                        hasFailure = true,
                        elapsedMs = elapsedSince(state.startedAtMs, ctx.now),
                        stepHistory = nextHistory,
                    ),
                    sideEffects = listOf(
                        Effect.MarkSessionFailed(action.sessionId, action.reason),
                        // NO DismissNotification — pipeline continues.
                    ),
                )
            } else null
            // The Preparing arm preserves the pre-Q6 behaviour: an
            // upload-time failure happens BEFORE any step row exists, so
            // it ends the pipeline. ADR-0009: the failed run's
            // MarkSessionFailed stays; the queue drains (chain-start next,
            // or Idle + DismissNotification when empty).
            is PipelineUiState.Preparing -> if (state.sessionId == action.sessionId) {
                val (nextState, terminalEffects) = nextAfterTerminal(queuedOf(state))
                TransitionResult(
                    nextState = nextState,
                    sideEffects = listOf(
                        Effect.MarkSessionFailed(action.sessionId, action.reason),
                    ) + terminalEffects,
                )
            } else null
            else -> null
        }

        is Action.PipelineAction.PipelineDone -> when (state) {
            is PipelineUiState.Running, is PipelineUiState.Preparing ->
                if (sessionIdOf(state) == action.sessionId) {
                    // 2026-05-22 — split effects on commit-success.
                    // committed=true: stamp inserted_at (legacy path).
                    // committed=false: leave inserted_at NULL so the row
                    // surfaces in the InfoBar's pending-insert producer;
                    // refresh pendingSessions live so the UI updates
                    // without waiting for the next Recovery pass.
                    val followUp = if (action.committed) {
                        Effect.MarkSessionInserted(action.sessionId, ctx.now)
                    } else {
                        Effect.AddPendingInsertSession(
                            sessionId = action.sessionId,
                            text = action.finalText,
                            createdAt = ctx.now,
                        )
                    }
                    // ADR-0009: keep the finished-session effect, then drain
                    // the queue (chain-start next) or return to Idle.
                    val (nextState, terminalEffects) = nextAfterTerminal(queuedOf(state))
                    TransitionResult(
                        nextState = nextState,
                        sideEffects = listOf(followUp) + terminalEffects,
                    )
                } else null
            else -> null
        }

        is Action.PipelineAction.PipelineFailed -> when (state) {
            is PipelineUiState.Running, is PipelineUiState.Preparing ->
                if (sessionIdOf(state) == action.sessionId) {
                    // ADR-0009: the failed run's MarkSessionFailed stays;
                    // the queue drains (a failure does not cancel queued
                    // runs — D5).
                    val (nextState, terminalEffects) = nextAfterTerminal(queuedOf(state))
                    TransitionResult(
                        nextState = nextState,
                        sideEffects = listOf(
                            Effect.MarkSessionFailed(action.sessionId, action.reason),
                        ) + terminalEffects,
                    )
                } else null
            else -> null
        }

        is Action.PipelineAction.ToggleRunningAutoEnter -> when (state) {
            // Per-run auto-enter toggle (second record-button tap during
            // Preparing OR Running). Distinct from
            // FeatureToggleAction.ToggleAutoEnter (which flips the global
            // Pref.AutoEnter).
            //
            // #AE-DEEP2: BOTH Preparing and Running accept the toggle —
            // the user's "double-tap to enable auto-enter" typically lands
            // during the 500ms–2s upload window (Preparing). Pre-fix
            // Preparing returned null, so the tap silently no-op'd. The
            // Preparing-side flag carries forward on the Preparing→Running
            // transition (see the StartPipeline arm above).
            is PipelineUiState.Preparing -> TransitionResult(
                nextState = state.copy(autoEnterActive = !state.autoEnterActive),
            )
            is PipelineUiState.Running -> TransitionResult(
                nextState = state.copy(autoEnterActive = !state.autoEnterActive),
            )
            else -> null
        }

        is Action.PipelineAction.CancelPipeline -> when (state) {
            is PipelineUiState.Idle -> null
            else -> {
                val sid = sessionIdOf(state)
                if (action.sessionId != null && action.sessionId != sid) {
                    // Stale cancel — target id doesn't match the current job.
                    null
                } else {
                    // ADR-0009 (D5): cancel targets the active run only —
                    // queued runs survive and chain-start.
                    val (nextState, terminalEffects) = nextAfterTerminal(queuedOf(state))
                    TransitionResult(
                        nextState = nextState,
                        sideEffects = buildList {
                            sid?.let { add(Effect.CancelPipelineJob(it)) }
                            // R5 (spec §3.6): surface the cancel as a typed
                            // info-bar notice — but only for a cancelled RUN
                            // (Preparing/Running). Leaving the
                            // ReprocessStaging editor also routes through
                            // this arm and is not a cancelled generation.
                            if (state is PipelineUiState.Preparing ||
                                state is PipelineUiState.Running
                            ) {
                                add(Effect.NotifyCancellationHint)
                            }
                            addAll(terminalEffects)
                        },
                    )
                }
            }
        }

        // ─── Reprocess-Staging sub-FSM ─────────────────────────────────
        is Action.PipelineAction.StartReprocessStaging -> when (state) {
            is PipelineUiState.Idle -> TransitionResult(
                nextState = PipelineUiState.ReprocessStaging(
                    sessionId = action.sessionId,
                    transcript = "",
                ),
                sideEffects = emptyList(),
            )
            else -> null
        }

        is Action.PipelineAction.UpdateReprocessQueue,
        is Action.PipelineAction.UpdateReprocessLanguage -> null
        // Queue / language edits live in the orchestrating UI; the
        // pipeline state itself doesn't carry the queue or override
        // (LanguageModule + ResendModule hold those). Returning `null`
        // signals "no state change needed in PipelineModule".

        is Action.PipelineAction.SendStaging -> when (state) {
            // F-12 single-submit guard (B1-VAL-W1 option b): there is no
            // `isStarting` flag. The first SendStaging transitions
            // `ReprocessStaging → Preparing`; a second tap on the large
            // record button arrives with `pipeline is Preparing` and falls
            // to the `else -> null` arm below — so the reprocess job is
            // submitted exactly once. Dispatch is main-thread-confined
            // (ADR-0001 §"Main-Thread Confined Dispatch") so the two taps
            // are serialized: the FSM `→ Preparing` edge IS the guard, not
            // a state flag. See
            // research/sendstaging-isstarting-guard-semantics.md.
            is PipelineUiState.ReprocessStaging -> if (state.sessionId != action.sessionId) {
                null
            } else {
                TransitionResult(
                    nextState = PipelineUiState.Preparing(sessionId = action.sessionId),
                    sideEffects = listOf(
                        Effect.SubmitReprocess(
                            sessionId = action.sessionId,
                            // F-19 — null means "runner resolves path by
                            // sessionId-lookup in the DB session record"
                            // (Phase-1 staging-FSM is pure-state-only,
                            // no file in the state).
                            audioFile = null,
                            // F-001 (2026-07-03) — the staged queue rides
                            // the action (an EXPLICIT list, empty = run
                            // zero prompts). Pre-fix this was
                            // `queue = emptyList()`, which the resolver
                            // read as UNSET → live-queue fallback, silently
                            // discarding the user's staged edits both ways.
                            queuedPromptSlots = action.queuedPromptSlots,
                            // F-001 — the staging language snapshotted at
                            // the send-tap now travels on the action too
                            // (was `ctx.global.language.override`, which the
                            // IME cleared before the async submit ran on the
                            // catalog path).
                            language = action.language,
                        ),
                        Effect.UpdateNotification(
                            NotificationStatus.Pipeline(action.sessionId, step = "reprocess"),
                        ),
                    ),
                )
            }
            else -> null
        }

        is Action.PipelineAction.CancelReprocessStaging -> when (state) {
            is PipelineUiState.ReprocessStaging -> if (state.sessionId == action.sessionId) {
                TransitionResult(nextState = PipelineUiState.Idle, sideEffects = emptyList())
            } else null
            else -> null
        }

        // ─── Result handling (post-Done) ────────────────────────────────
        is Action.PipelineAction.ConfirmInsertion,
        is Action.PipelineAction.DismissResult -> null
        // No state-change in PipelineModule — these are UI-only acks.

        // ─── Error paths ────────────────────────────────────────────────
        is Action.PipelineAction.PersistenceError -> when (state) {
            is PipelineUiState.Running, is PipelineUiState.Preparing ->
                if (sessionIdOf(state) == action.sessionId) {
                    // ADR-0009: Idle-producing terminal — drain the queue.
                    val (nextState, terminalEffects) = nextAfterTerminal(queuedOf(state))
                    TransitionResult(nextState = nextState, sideEffects = terminalEffects)
                } else null
            else -> null
        }

        is Action.PipelineAction.RejectedJobAlreadyActive ->
            // R.17 state-first race mitigation: a parallel job is active;
            // roll back if we're still in Preparing. ADR-0009: route through
            // the terminal helper so a queued run chain-starts instead of
            // stalling behind the rejected one.
            when (state) {
                is PipelineUiState.Preparing -> if (state.sessionId == action.sessionId) {
                    val (nextState, terminalEffects) = nextAfterTerminal(queuedOf(state))
                    TransitionResult(nextState = nextState, sideEffects = terminalEffects)
                } else null
                else -> null
            }

        // ─── Pipeline timer per-second tick (B-D-3) ────────────────────
        is Action.PipelineAction.TickPipelineTimer -> when (state) {
            // Per-second restamp of `elapsedMs` so the record-button
            // label visibly advances between step boundaries. Pure
            // state-only — no side effects. Idempotent: if `elapsedMs`
            // happens to already match (e.g. two ticks fire in the same
            // 1ms slot from a Handler race), `state.copy(...)`
            // produces an equal object and the StateFlow's
            // distinctUntilChanged in the observer chain will collapse
            // it.
            //
            // Triangle-FSM safety: the reducer is pure-state, so a
            // tick fired from the observer's tail-cancellation race
            // (ticker fired after the observer's collect-coroutine
            // saw the state leave `Running`) collapses to a no-op
            // on Idle/Done.
            is PipelineUiState.Running -> TransitionResult(
                nextState = state.copy(
                    elapsedMs = elapsedSince(state.startedAtMs, ctx.now),
                ),
            )
            else -> null
        }
    }

    /**
     * Failure-reducer — recovers the FSM from a [Effect.SubmitPipeline]
     * throw (R-1 cold-process tripwire).
     *
     * **Why this exists (2026-07-12).** `PipelineRunnerSubsystemAdapter.submit`
     * calls `PipelineConfigResolver.resolveFresh` **synchronously**, so its
     * R-1 guard throw (`UnsupportedOperationException` when no fresh-config
     * snapshot exists — the cold-process case where the IME never bound and
     * therefore never ran the send-tap `snapshotFresh`) surfaces via
     * `runEffect` and is wrapped by the orchestrator into an
     * [Action.EffectFailure] routed here. Before this arm existed
     * `PipelineModule` had no `reduceFailure` override, so the default
     * returned `null` and the FSM — already moved `Idle → Preparing` when it
     * emitted `SubmitPipeline` — stayed **stuck in Preparing forever**
     * ("Sending …" with no step rows and no recovery). See
     * `OverlayBackend.wireStaticOverlayHandlers` KDoc §"R-1 snapshot" for the
     * full failure narrative.
     *
     * The recovery is deliberately minimal: roll `Preparing → Idle` and
     * [Effect.DismissNotification] to clear the stuck FGS notification. The
     * failure stays **loud** (the run is lost and the throw is logged) but is
     * now **recoverable** — the user can simply record again. Building a
     * service-side fresh-config resolver so the cold-process send actually
     * completes is out of scope here (tracked against ADR-0011
     * headless-completion). Effect string is a `data class` toString, so we
     * match with `startsWith("SubmitPipeline(")` (see [SideEffect] KDoc).
     *
     * We only recover from `Preparing` for `SubmitPipeline`: a `Running`
     * state means the submit already succeeded and a later throw is a
     * different concern; any other effect has no PipelineModule failure
     * semantics and falls through to `null`.
     */
    override fun reduceFailure(
        state: PipelineUiState,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<PipelineUiState, Effect>? = when {
        failure.effect.startsWith("SubmitPipeline(") && state is PipelineUiState.Preparing ->
            TransitionResult(
                nextState = PipelineUiState.Idle,
                sideEffects = listOf(Effect.DismissNotification),
            )
        else -> null
    }

    /**
     * Elapsed-ms since the [PipelineUiState.Running.startedAtMs] baseline,
     * floored at zero.
     *
     * F-13: the reducer is the only legal time source (R.2 pure-reducer
     * invariant), so every counter-affecting transition restamps
     * `elapsedMs` from this. Coerced non-negative so a test-constructed
     * `Running` with the defaulted `startedAtMs = 0L` (or an injected
     * `ctx.now` earlier than the baseline) can't surface a nonsensical
     * negative timer in the live label.
     */
    private fun elapsedSince(startedAtMs: Long, now: Long): Long =
        (now - startedAtMs).coerceAtLeast(0L)

    /**
     * Look up the current sessionId (or `null` for [PipelineUiState.Idle]).
     * Centralised to avoid the `when` over PipelineUiState repeated across
     * every action arm.
     */
    private fun sessionIdOf(state: PipelineUiState): String? = when (state) {
        is PipelineUiState.Idle -> null
        is PipelineUiState.Preparing -> state.sessionId
        is PipelineUiState.Running -> state.sessionId
        is PipelineUiState.ReprocessStaging -> state.sessionId
    }

    /**
     * The run-queue carried by [state], or empty for the states that hold
     * none ([PipelineUiState.Idle] / [PipelineUiState.ReprocessStaging] —
     * invariant #7: Idle-with-queue is unrepresentable). @see ADR-0009.
     */
    private fun queuedOf(
        state: PipelineUiState,
    ): kotlinx.collections.immutable.PersistentList<QueuedRun> = when (state) {
        is PipelineUiState.Preparing -> state.queued
        is PipelineUiState.Running -> state.queued
        is PipelineUiState.Idle -> kotlinx.collections.immutable.persistentListOf()
        is PipelineUiState.ReprocessStaging -> kotlinx.collections.immutable.persistentListOf()
    }

    /**
     * `true` when a `TriggerPipeline` for [sessionId] is a double-tap: it
     * matches either the active run or a run already queued. Such a
     * trigger is not-relevant (the enqueue arms return `null`), because
     * no legitimate re-trigger of the same session queues behind itself —
     * resend / reprocess route through different actions. @see ADR-0009.
     */
    private fun isDuplicateTrigger(
        activeSessionId: String,
        queued: kotlinx.collections.immutable.PersistentList<QueuedRun>,
        sessionId: String,
    ): Boolean =
        activeSessionId == sessionId || queued.any { it.sessionId == sessionId }

    /**
     * Terminal transition (ADR-0009 §3.2): drain the queue or return to
     * Idle. Empty queue → [PipelineUiState.Idle] + [Effect.DismissNotification]
     * (today's behaviour, byte-identical). Non-empty → chain-start the head
     * run: [PipelineUiState.Preparing] carrying the rest, plus a
     * [Effect.SubmitPipeline] and an [Effect.UpdateNotification]. The
     * [Effect.DismissNotification] lives here (not in the arms) so it fires
     * only when the chain actually ends — never mid-chain.
     *
     * The caller concatenates its own finished-session effects
     * ([Effect.MarkSessionInserted] / [Effect.MarkSessionFailed] /
     * [Effect.AddPendingInsertSession] / [Effect.CancelPipelineJob]) before
     * this result.
     */
    private fun nextAfterTerminal(
        queued: kotlinx.collections.immutable.PersistentList<QueuedRun>,
    ): Pair<PipelineUiState, List<Effect>> =
        if (queued.isEmpty()) {
            PipelineUiState.Idle to listOf(Effect.DismissNotification)
        } else {
            val next = queued.first()
            PipelineUiState.Preparing(
                sessionId = next.sessionId,
                queued = queued.removeAt(0),
            ) to listOf(
                Effect.SubmitPipeline(next.sessionId, next.audioFile),
                Effect.UpdateNotification(
                    NotificationStatus.Pipeline(next.sessionId, step = "preparing"),
                ),
            )
        }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        is Effect.SubmitPipeline -> services.pipelineRunner.submit(effect.sessionId, effect.audioFile)
        is Effect.SubmitReprocess -> services.pipelineRunner.submitReprocess(
            sessionId = effect.sessionId,
            audioFile = effect.audioFile,
            queuedPromptSlots = effect.queuedPromptSlots,
            language = effect.language,
        )
        is Effect.CancelPipelineJob -> services.pipelineRunner.cancel(effect.sessionId)
        Effect.NotifyCancellationHint ->
            services.emitAction(Action.InfoHintAction.PipelineCancelled)
        is Effect.MarkSessionInserted -> {
            services.scope.launch {
                services.sessionRepo.markInserted(effect.sessionId, effect.at)
            }
            Unit
        }
        is Effect.MarkSessionFailed -> {
            services.scope.launch {
                services.sessionRepo.markFailed(effect.sessionId, effect.reason)
            }
            Unit
        }
        is Effect.UpdateNotification -> services.notificationCoordinator.show(effect.status)
        Effect.DismissNotification -> services.notificationCoordinator.dismiss()
        is Effect.AddPendingInsertSession -> {
            // 2026-05-22 (B3.5 widget-host-block follow-up) — append the
            // freshly-produced session straight to state.pendingSessions
            // so the InfoBar's pending-insert producer surfaces a "Tap to
            // paste" item live. Race-free: the text payload comes off the
            // PipelineDone action, not a DB query (see the effect's KDoc).
            //
            // ADR-0009 / spec §3.5 — recording-order key. The pending part's
            // `createdAt` must be the session's DB `created_at` (recording
            // order == session-creation order), NOT `effect.createdAt`
            // (= ctx.now at PipelineDone, i.e. completion time). Resolve it
            // off `sessionRepo` (effects may do IO); fall back to the
            // effect's timestamp when the row is missing. Note the race the
            // effect's KDoc warns about concerned the COMPLETED *status*
            // write — `created_at` is written once at session creation and
            // is race-free, so this lookup is safe.
            Log.i(
                "DictateTrace",
                "AddPendingInsertSession sid=${effect.sessionId.take(8)} " +
                    "textLen=${effect.text.length}",
            )
            services.scope.launch {
                val createdAt =
                    services.sessionRepo.findCreatedAt(effect.sessionId) ?: effect.createdAt
                services.emitAction(
                    Action.PendingSessionsAction.AddOne(
                        net.devemperor.dictate.state.PendingSession(
                            sessionId = effect.sessionId,
                            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
                            transcribedText = effect.text,
                            createdAt = createdAt,
                        ),
                    ),
                )
            }
            Unit
        }
    }

    /**
     * Cross-module observer. Emits cascades to Recording, Resend, LivePrompt,
     * and ViewMode per the Coupling-Matrix §15.1.x.
     */
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        val cascade = mutableListOf<Action>()

        // ─── Pipeline-Done detection ────────────────────────────────────
        // We treat "Pipeline left a non-Idle state to Idle" as the
        // session-end boundary (covers Done / Failed / Cancelled paths,
        // all of which set `pipeline = Idle` in this module's reducer).
        // The plan's `prev.pipeline !is Done && next.pipeline is Done`
        // check from Spec 3 §7.3 T7 is a simplified form — since the
        // reducer collapses Done directly into Idle (no explicit Done
        // state in PipelineUiState), the equivalent observable
        // transition is `prev != Idle → next is Idle`.
        val pipelineWasActive = prev.pipeline !is PipelineUiState.Idle
        val pipelineIsIdle = next.pipeline is PipelineUiState.Idle
        if (pipelineWasActive && pipelineIsIdle) {
            // T7 Geist-Widget-bug structural protection (Spec 3 §7.3 T7).
            cascade += Action.ViewModeAction.OnPipelineDone

            // Mark the just-finished audio file as available for "Resend"
            // (Spec 1 §15.x ResendModule). Existence is `true` unless the
            // pipeline-cancel path deleted the file; ResendModule's
            // failure-arm clears the marker if a later file-check fails.
            cascade += Action.ResendAction.MarkLastAudio(exists = true)

            // LivePrompt chain-trigger — if the user enabled live-prompt
            // and a chain is pending, kick the next prompt off.
            if (next.livePrompt.enabled && next.livePrompt.pendingChain) {
                cascade += Action.LivePromptAction.ChainNext(text = "")
            }
        }

        // ─── Send-trigger: stop the recording when Pipeline enters Preparing ─
        // The "Send" button dispatches `RecordingAction.StopRecordingAndSend`
        // (no payload — the sessionId lives in the RecordingState FSM, F-10)
        // which the RecordingModule reduces to `Idle` + stop effects, plus a
        // `RecordingModule.Effect.EmitPipelineTrigger(sessionId, audioFile)` that
        // re-enters the dispatch loop with `Action.PipelineAction.TriggerPipeline`
        // (F-2 fix, 2026-05-15). The Coupling-Matrix row
        // `R(state.pipeline) C(RecordingAction.StopRecording)` is documented as
        // **Phase-2 deferred no-op** — the actual Recording → Pipeline direction
        // is the async re-entry above, not a cross-module observer cascade.
        // No cascade emission needed here in PipelineModule.

        return cascade
    }
}

