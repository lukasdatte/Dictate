package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.conversation.Verdict

/**
 * The pure heart of the desktop pipeline (desktop-host.md §5.4): `(state, intent) → (state, effects)`
 * with **no IO** — no clock, no UUID, no recording, no `converse`, no insert. Everything time- or
 * side-effect-bearing is named as an [Effect] and executed by [DictationEffects]. That is what makes
 * every phase transition (§5.2) and the ADR-0009 enqueue exhaustively unit-testable without a
 * microphone, an AI provider, or a window (spec §12, footgun "Reducer mit IO/Zeit").
 */
object DictationReducer {

    fun reduce(state: DesktopUiState, intent: DictationIntent): Pair<DesktopUiState, List<Effect>> =
        when (intent) {
            is DictationIntent.StartHotkey -> onStartHotkey(state, intent.sessionId)
            DictationIntent.PauseRecording -> onPause(state)
            DictationIntent.ResumeRecording -> onResume(state)
            DictationIntent.StopRecording -> onStop(state)
            DictationIntent.Discard -> onDiscard(state)
            is DictationIntent.TranscriptionCompleted -> onTranscriptionCompleted(state, intent.sessionId)
            is DictationIntent.PipelineVerdict -> onVerdict(state, intent)
            is DictationIntent.PipelineFailed -> onFailed(state, intent)
            is DictationIntent.InsertCompleted -> onInsertCompleted(state, intent.sessionId)
            DictationIntent.ConfirmInsert -> onConfirmInsert(state)
            is DictationIntent.StartRefinement -> onStartRefinement(state, intent.refinementSessionId)
            DictationIntent.StopRefinement -> onStopRefinement(state)
            is DictationIntent.RefinementTranscribed -> onRefinementTranscribed(state, intent.followUpText)
            is DictationIntent.ReviewTurnCompleted -> onReviewTurnCompleted(state, intent)
            is DictationIntent.RefinementFailed -> onRefinementFailed(state, intent.errorKey)
        }

    private fun onStartHotkey(state: DesktopUiState, sessionId: String): Pair<DesktopUiState, List<Effect>> {
        if (state.isBusy) {
            // ADR-0009: a hotkey during a running pipeline enqueues, never discards. Dedup by session
            // id so a double-dispatch of the same trigger cannot double-book the queue.
            val known = state.activeSessionId == sessionId || state.queued.any { it.sessionId == sessionId }
            if (known) return state to emptyList()
            return state.copy(queued = state.queued + QueuedRun(sessionId)) to emptyList()
        }
        return startRecording(state, sessionId)
    }

    private fun onPause(state: DesktopUiState): Pair<DesktopUiState, List<Effect>> {
        val rec = state.recording
        if (state.phase != DictationPhase.RECORDING || rec !is RecordingUi.Active || rec.paused) {
            return state to emptyList()
        }
        return state.copy(recording = rec.copy(paused = true)) to listOf(Effect.PauseCapture)
    }

    private fun onResume(state: DesktopUiState): Pair<DesktopUiState, List<Effect>> {
        val rec = state.recording
        if (state.phase != DictationPhase.RECORDING || rec !is RecordingUi.Active || !rec.paused) {
            return state to emptyList()
        }
        return state.copy(recording = rec.copy(paused = false)) to listOf(Effect.ResumeCapture)
    }

    private fun onStop(state: DesktopUiState): Pair<DesktopUiState, List<Effect>> {
        if (state.phase != DictationPhase.RECORDING) return state to emptyList()
        val sessionId = state.activeSessionId ?: return state to emptyList()
        return state.copy(
            phase = DictationPhase.TRANSCRIBING,
            recording = RecordingUi.Idle,
            pipeline = PipelineUi.Transcribing,
        ) to listOf(Effect.StopCaptureAndRun(sessionId))
    }

    private fun onDiscard(state: DesktopUiState): Pair<DesktopUiState, List<Effect>> {
        // Discard while the mic is live throws the audio away. Discard from the REVIEW/confirm wait
        // acknowledges without inserting (one acknowledge channel, ADR-0013 §4 / spec §8.5). In
        // between — pipeline running — the take is committed (the audio is being uploaded): no-op.
        if (state.phase == DictationPhase.REVIEW) {
            val review = state.review
            // Discard doubles as Cancel of an in-flight re-dictate (§8.4): drop the refinement, stay in
            // review, don't acknowledge the take. Only a Discard on a *settled* review closes it.
            if (review != null && (review.refining || review.refinementRecording)) {
                return state.copy(
                    review = review.copy(refining = false, refinementRecording = false, refinementSessionId = null, error = null),
                ) to listOf(Effect.CancelRefinement)
            }
            val sessionId = state.activeSessionId ?: return state to emptyList()
            return finish(state, listOf(Effect.AcknowledgeDiscard(sessionId)))
        }
        if (state.phase != DictationPhase.RECORDING) return state to emptyList()
        return finish(state, listOf(Effect.DiscardCapture))
    }

    // ── Re-dictate over the review panel (ADR-0013 §6, spec §8.3) ────────────────────────────────

    private fun onStartRefinement(state: DesktopUiState, refinementSessionId: String): Pair<DesktopUiState, List<Effect>> {
        val review = state.review
        // Only from a settled review (not while already refining/recording — ADR-0013 K1).
        if (state.phase != DictationPhase.REVIEW || review == null || review.refining || review.refinementRecording) {
            return state to emptyList()
        }
        return state.copy(
            review = review.copy(refinementRecording = true, refinementSessionId = refinementSessionId, error = null),
        ) to listOf(Effect.StartCapture(device = null))
    }

    private fun onStopRefinement(state: DesktopUiState): Pair<DesktopUiState, List<Effect>> {
        val review = state.review
        if (state.phase != DictationPhase.REVIEW || review == null || !review.refinementRecording) {
            return state to emptyList()
        }
        val refinementSessionId = review.refinementSessionId ?: return state to emptyList()
        // Mic stops; the S2 transcription + continuation is the "refining" window (Insert/Discard stay
        // disabled). Transcription-only run, then the continuation is chained by RefinementTranscribed.
        return state.copy(
            review = review.copy(refinementRecording = false, refining = true),
        ) to listOf(Effect.RunRefinementTranscription(refinementSessionId, review.sessionId))
    }

    private fun onRefinementTranscribed(state: DesktopUiState, followUpText: String): Pair<DesktopUiState, List<Effect>> {
        val review = state.review
        if (state.phase != DictationPhase.REVIEW || review == null || !review.refining) return state to emptyList()
        // Still refining — kick off the ConversationContinuation with the spoken follow-up.
        return state to listOf(Effect.RunContinuation(review.sessionId, followUpText, review.refinementSessionId))
    }

    private fun onReviewTurnCompleted(state: DesktopUiState, intent: DictationIntent.ReviewTurnCompleted): Pair<DesktopUiState, List<Effect>> {
        val review = state.review
        // Guard on `refining`: a continuation result that lands after the user cancelled (refining
        // cleared) is dropped — the queue has no cancel, so the reducer state is the cancel authority.
        if (state.phase != DictationPhase.REVIEW || review == null || review.sessionId != intent.sessionId || !review.refining) {
            return state to emptyList()
        }
        return when (intent.verdict) {
            Verdict.INSERT ->
                if (intent.requiresConfirm) {
                    // Insert verdict but the confirm gate is on: park the new output, wait for ConfirmInsert.
                    state.copy(
                        review = review.copy(message = intent.message, output = intent.output, refining = false, refinementSessionId = null, error = null),
                    ) to emptyList()
                } else {
                    state.copy(phase = DictationPhase.INSERTED, review = null) to
                        listOf(Effect.InsertText(intent.sessionId, intent.output))
                }
            // REVIEW verdict: non-terminal panel update — iterative re-dictate stays possible (§8.3).
            Verdict.REVIEW -> state.copy(
                review = review.copy(message = intent.message, output = intent.output, refining = false, refinementSessionId = null, error = null),
            ) to emptyList()
        }
    }

    private fun onRefinementFailed(state: DesktopUiState, errorKey: String): Pair<DesktopUiState, List<Effect>> {
        val review = state.review
        if (state.phase != DictationPhase.REVIEW || review == null) return state to emptyList()
        // The take is NOT lost: the panel drops back to the last good review output and shows the error.
        return state.copy(
            review = review.copy(refining = false, refinementRecording = false, refinementSessionId = null, error = errorKey),
        ) to emptyList()
    }

    private fun onTranscriptionCompleted(state: DesktopUiState, sessionId: String): Pair<DesktopUiState, List<Effect>> {
        if (state.phase != DictationPhase.TRANSCRIBING || state.activeSessionId != sessionId) {
            return state to emptyList()
        }
        return state.copy(phase = DictationPhase.POST_PROCESSING, pipeline = PipelineUi.PostProcessing) to emptyList()
    }

    private fun onVerdict(state: DesktopUiState, intent: DictationIntent.PipelineVerdict): Pair<DesktopUiState, List<Effect>> {
        // POST_PROCESSING is the normal source; TRANSCRIBING too, because a bare transcript with no
        // post-processing work (hasWork=false, §5.5) yields a verdict without a POST_PROCESSING hop.
        val validPhase = state.phase == DictationPhase.POST_PROCESSING || state.phase == DictationPhase.TRANSCRIBING
        if (!validPhase || state.activeSessionId != intent.sessionId) return state to emptyList()
        return when (intent.verdict) {
            // The confirm gate (F21, §8.5): an INSERT verdict with `insertion.confirmBeforeInsert`
            // set parks the finished text in the panel's waiting state instead of auto-inserting;
            // ConfirmInsert (or Discard) resolves it. The Verdict itself is untouched — the gate is
            // presentation policy, not a second decision path (§8.2 footgun).
            Verdict.INSERT ->
                if (intent.requiresConfirm) {
                    state.copy(
                        phase = DictationPhase.REVIEW,
                        pipeline = PipelineUi.Idle,
                        review = ReviewUi(sessionId = intent.sessionId, message = intent.message, output = intent.output),
                    ) to emptyList()
                } else {
                    state.copy(phase = DictationPhase.INSERTED, pipeline = PipelineUi.Idle) to
                        listOf(Effect.InsertText(intent.sessionId, intent.output))
                }
            Verdict.REVIEW -> state.copy(
                phase = DictationPhase.REVIEW,
                pipeline = PipelineUi.Idle,
                review = ReviewUi(sessionId = intent.sessionId, message = intent.message, output = intent.output),
            ) to emptyList()
        }
    }

    private fun onFailed(state: DesktopUiState, intent: DictationIntent.PipelineFailed): Pair<DesktopUiState, List<Effect>> {
        if (state.activeSessionId != intent.sessionId || state.phase !in NON_TERMINAL) return state to emptyList()
        return finish(state, emptyList(), PipelineUi.Failed(intent.errorKey))
    }

    private fun onConfirmInsert(state: DesktopUiState): Pair<DesktopUiState, List<Effect>> {
        val review = state.review ?: return state to emptyList()
        if (state.phase != DictationPhase.REVIEW) return state to emptyList()
        return state.copy(phase = DictationPhase.INSERTED, review = null) to
            listOf(Effect.InsertText(review.sessionId, review.output))
    }

    private fun onInsertCompleted(state: DesktopUiState, sessionId: String): Pair<DesktopUiState, List<Effect>> {
        if (state.phase != DictationPhase.INSERTED || state.activeSessionId != sessionId) {
            return state to emptyList()
        }
        return finish(state, emptyList())
    }

    /**
     * A take reached a terminal phase (INSERTED / FAILED / CANCELLED). Either the next queued run
     * starts recording (ADR-0009: the panel stays up, recording order = insert order), or — with an
     * empty queue — the panel returns to the resting IDLE phase and hides. The resting phase is always
     * IDLE regardless of which terminal was reached (the persisted `sessions.status` already recorded
     * COMPLETED/FAILED/CANCELLED, §5.2); [terminalPipeline] is the one carry-over, so a Failed banner
     * survives on the hidden panel until the next take.
     */
    private fun finish(
        state: DesktopUiState,
        pre: List<Effect>,
        terminalPipeline: PipelineUi = PipelineUi.Idle,
    ): Pair<DesktopUiState, List<Effect>> {
        val next = state.queued.firstOrNull()
        if (next != null) {
            val (recordingState, effects) = startRecording(
                state.copy(queued = state.queued.drop(1)),
                next.sessionId,
                showPanel = false, // panel is already visible from the take that just finished
            )
            return recordingState to (pre + effects)
        }
        return DesktopUiState(
            phase = DictationPhase.IDLE,
            panelVisible = false,
            pipeline = terminalPipeline,
        ) to (pre + Effect.HidePanel)
    }

    private fun startRecording(
        state: DesktopUiState,
        sessionId: String,
        showPanel: Boolean = true,
    ): Pair<DesktopUiState, List<Effect>> {
        val next = state.copy(
            phase = DictationPhase.RECORDING,
            panelVisible = true,
            activeSessionId = sessionId,
            recording = RecordingUi.Active(paused = false, elapsedMillis = 0),
            pipeline = PipelineUi.Idle,
            review = null,
        )
        val effects = buildList {
            if (showPanel) add(Effect.ShowPanel)
            add(Effect.StartCapture(device = null))
        }
        return next to effects
    }

    private val NON_TERMINAL = setOf(
        DictationPhase.RECORDING,
        DictationPhase.TRANSCRIBING,
        DictationPhase.POST_PROCESSING,
    )
}
