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
        // Discard is only meaningful while the mic is live; once the pipeline is running the take is
        // committed (the audio is being uploaded). REVIEW discard is a D3 concern (§8.5).
        if (state.phase != DictationPhase.RECORDING) return state to emptyList()
        return finish(state, DictationPhase.CANCELLED, listOf(Effect.DiscardCapture))
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
            Verdict.INSERT -> state.copy(phase = DictationPhase.INSERTED, pipeline = PipelineUi.Idle) to
                listOf(Effect.InsertText(intent.sessionId, intent.output))
            Verdict.REVIEW -> state.copy(
                phase = DictationPhase.REVIEW,
                pipeline = PipelineUi.Idle,
                review = ReviewUi(sessionId = intent.sessionId, message = intent.message, output = intent.output),
            ) to emptyList()
        }
    }

    private fun onFailed(state: DesktopUiState, intent: DictationIntent.PipelineFailed): Pair<DesktopUiState, List<Effect>> {
        if (state.activeSessionId != intent.sessionId || state.phase !in NON_TERMINAL) return state to emptyList()
        return finish(state, DictationPhase.FAILED, emptyList(), PipelineUi.Failed(intent.errorKey))
    }

    private fun onInsertCompleted(state: DesktopUiState, sessionId: String): Pair<DesktopUiState, List<Effect>> {
        if (state.phase != DictationPhase.INSERTED || state.activeSessionId != sessionId) {
            return state to emptyList()
        }
        return finish(state, DictationPhase.INSERTED, emptyList())
    }

    /**
     * A take reached a terminal phase. Either the next queued run starts recording (ADR-0009: the
     * panel stays up, recording order = insert order), or — with an empty queue — the panel returns to
     * IDLE and hides. [terminalPhase]/[terminalPipeline] are irrelevant once we hand off, but are
     * applied when the queue is empty so a Failed banner survives to the resting state.
     */
    private fun finish(
        state: DesktopUiState,
        terminalPhase: DictationPhase,
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
