package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.conversation.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every [DictationPhase] transition and the ADR-0009 enqueue, on the pure reducer — no microphone, no
 * AI, no window (desktop-host.md §5.2/§5.6, acceptance §2 criterion 6).
 */
class DictationReducerTest {

    private val sid = "s-1"

    private fun reduce(state: DesktopUiState, intent: DictationIntent) = DictationReducer.reduce(state, intent)

    /** Drives from IDLE to RECORDING for [sid]. */
    private fun recording(sessionId: String = sid): DesktopUiState =
        reduce(DesktopUiState(), DictationIntent.StartHotkey(sessionId)).first

    /** Drives RECORDING → TRANSCRIBING → POST_PROCESSING for [sid]. */
    private fun postProcessing(sessionId: String = sid): DesktopUiState {
        val transcribing = reduce(recording(sessionId), DictationIntent.StopRecording).first
        return reduce(transcribing, DictationIntent.TranscriptionCompleted(sessionId)).first
    }

    @Test
    fun startHotkey_fromIdle_startsRecordingAndOpensPanel() {
        val (state, effects) = reduce(DesktopUiState(), DictationIntent.StartHotkey(sid))
        assertEquals(DictationPhase.RECORDING, state.phase)
        assertEquals(sid, state.activeSessionId)
        assertTrue(state.panelVisible)
        assertEquals(RecordingUi.Active(paused = false, elapsedMillis = 0), state.recording)
        assertEquals(listOf(Effect.ShowPanel, Effect.StartCapture(device = null)), effects)
    }

    @Test
    fun pauseThenResume_togglesTheRecordingFlagAndAsksCapture() {
        val (paused, pauseEff) = reduce(recording(), DictationIntent.PauseRecording)
        assertEquals(RecordingUi.Active(paused = true, elapsedMillis = 0), paused.recording)
        assertEquals(listOf(Effect.PauseCapture), pauseEff)

        val (resumed, resumeEff) = reduce(paused, DictationIntent.ResumeRecording)
        assertEquals(RecordingUi.Active(paused = false, elapsedMillis = 0), resumed.recording)
        assertEquals(listOf(Effect.ResumeCapture), resumeEff)
    }

    @Test
    fun pause_whenNotRecording_isANoOp() {
        val (state, effects) = reduce(DesktopUiState(), DictationIntent.PauseRecording)
        assertEquals(DesktopUiState(), state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun stopRecording_movesToTranscribingAndRunsThePipeline() {
        val (state, effects) = reduce(recording(), DictationIntent.StopRecording)
        assertEquals(DictationPhase.TRANSCRIBING, state.phase)
        assertEquals(RecordingUi.Idle, state.recording)
        assertEquals(PipelineUi.Transcribing, state.pipeline)
        assertEquals(listOf(Effect.StopCaptureAndRun(sid)), effects)
    }

    @Test
    fun transcriptionCompleted_movesToPostProcessing() {
        val (state, effects) = reduce(
            reduce(recording(), DictationIntent.StopRecording).first,
            DictationIntent.TranscriptionCompleted(sid),
        )
        assertEquals(DictationPhase.POST_PROCESSING, state.phase)
        assertEquals(PipelineUi.PostProcessing, state.pipeline)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun verdictInsert_movesToInsertedAndEmitsInsert() {
        val (state, effects) = reduce(
            postProcessing(),
            DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "hello world", null),
        )
        assertEquals(DictationPhase.INSERTED, state.phase)
        assertEquals(listOf(Effect.InsertText(sid, "hello world")), effects)
    }

    @Test
    fun verdictReview_movesToReviewWithNoInsert() {
        val (state, effects) = reduce(
            postProcessing(),
            DictationIntent.PipelineVerdict(sid, Verdict.REVIEW, "draft", "which Anna?"),
        )
        assertEquals(DictationPhase.REVIEW, state.phase)
        assertNotNull(state.review)
        assertEquals("which Anna?", state.review?.message)
        assertEquals("draft", state.review?.output)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun verdictInsert_worksOnABareTranscriptStraightFromTranscribing() {
        // hasWork=false path (§5.5): the verdict arrives without a POST_PROCESSING hop.
        val transcribing = reduce(recording(), DictationIntent.StopRecording).first
        val (state, effects) = reduce(
            transcribing,
            DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "bare", null),
        )
        assertEquals(DictationPhase.INSERTED, state.phase)
        assertEquals(listOf(Effect.InsertText(sid, "bare")), effects)
    }

    @Test
    fun insertCompleted_withEmptyQueue_returnsToIdleAndHidesPanel() {
        val inserted = reduce(
            postProcessing(),
            DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "out", null),
        ).first
        val (state, effects) = reduce(inserted, DictationIntent.InsertCompleted(sid))
        assertEquals(DictationPhase.IDLE, state.phase)
        assertNull(state.activeSessionId)
        assertTrue(!state.panelVisible)
        assertEquals(listOf(Effect.HidePanel), effects)
    }

    @Test
    fun discard_fromRecording_cancelsAndDiscardsAudio() {
        val (state, effects) = reduce(recording(), DictationIntent.Discard)
        assertEquals(DictationPhase.IDLE, state.phase)
        assertEquals(listOf(Effect.DiscardCapture, Effect.HidePanel), effects)
    }

    @Test
    fun discard_onceThePipelineIsRunning_isANoOp() {
        val transcribing = reduce(recording(), DictationIntent.StopRecording).first
        val (state, effects) = reduce(transcribing, DictationIntent.Discard)
        assertEquals(DictationPhase.TRANSCRIBING, state.phase)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun pipelineFailed_movesToFailedAndSurfacesTheErrorBanner() {
        val transcribing = reduce(recording(), DictationIntent.StopRecording).first
        val (state, effects) = reduce(transcribing, DictationIntent.PipelineFailed(sid, "RATE_LIMITED"))
        assertEquals(DictationPhase.IDLE, state.phase) // terminal → back to rest
        assertEquals(PipelineUi.Failed("RATE_LIMITED"), state.pipeline)
        assertEquals(listOf(Effect.HidePanel), effects)
    }

    @Test
    fun secondHotkeyDuringPipeline_enqueues_neverDiscards() {
        val transcribing = reduce(recording(), DictationIntent.StopRecording).first
        val (state, effects) = reduce(transcribing, DictationIntent.StartHotkey("s-2"))
        assertEquals("first take keeps running", DictationPhase.TRANSCRIBING, state.phase)
        assertEquals("second take is queued, not lost", listOf(QueuedRun("s-2")), state.queued)
        assertTrue("enqueue has no immediate effect", effects.isEmpty())
    }

    @Test
    fun terminalWithAQueuedRun_startsTheNextRecording_panelAlreadyUp() {
        val transcribing = reduce(recording(), DictationIntent.StopRecording).first
        val queued = reduce(transcribing, DictationIntent.StartHotkey("s-2")).first
        val postProcessed = reduce(queued, DictationIntent.TranscriptionCompleted(sid)).first
        val inserted = reduce(postProcessed, DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "out", null)).first

        val (state, effects) = reduce(inserted, DictationIntent.InsertCompleted(sid))
        assertEquals("the queued take starts recording (order preserved)", DictationPhase.RECORDING, state.phase)
        assertEquals("s-2", state.activeSessionId)
        assertTrue(state.queued.isEmpty())
        assertEquals("panel already visible → only StartCapture", listOf(Effect.StartCapture(device = null)), effects)
    }

    @Test
    fun secondHotkeyWithTheSameActiveSession_isDedupedAway() {
        val transcribing = reduce(recording(), DictationIntent.StopRecording).first
        val (state, effects) = reduce(transcribing, DictationIntent.StartHotkey(sid))
        assertTrue("already the active session — no double-book", state.queued.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun staleCallbackForAnOldSession_isIgnored() {
        val postProcessed = postProcessing()
        val (state, effects) = reduce(postProcessed, DictationIntent.PipelineVerdict("other", Verdict.INSERT, "x", null))
        assertEquals(postProcessed, state)
        assertTrue(effects.isEmpty())
    }

    // ── confirm-before-insert gate + the one acknowledge channel (D2, F21 / §8.5) ─────────────

    /** Drives to the confirm wait: an INSERT verdict carrying `requiresConfirm`. */
    private fun waitingForConfirm(): DesktopUiState = reduce(
        postProcessing(),
        DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "hold on", null, requiresConfirm = true),
    ).first

    @Test
    fun verdictInsertWithConfirm_parksInReview_insteadOfAutoInserting() {
        val (state, effects) = reduce(
            postProcessing(),
            DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "hold on", null, requiresConfirm = true),
        )
        assertEquals(DictationPhase.REVIEW, state.phase)
        assertEquals("hold on", state.review?.output)
        assertTrue("no insert until the user confirms", effects.isEmpty())
    }

    @Test
    fun confirmInsert_insertsTheParkedOutput() {
        val (state, effects) = reduce(waitingForConfirm(), DictationIntent.ConfirmInsert)
        assertEquals(DictationPhase.INSERTED, state.phase)
        assertNull(state.review)
        assertEquals(listOf(Effect.InsertText(sid, "hold on")), effects)
    }

    @Test
    fun confirmInsert_outsideTheReviewWait_isANoOp() {
        val (state, effects) = reduce(recording(), DictationIntent.ConfirmInsert)
        assertEquals(DictationPhase.RECORDING, state.phase)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun discard_fromTheReviewWait_acknowledgesWithoutInserting() {
        val (state, effects) = reduce(waitingForConfirm(), DictationIntent.Discard)
        assertEquals("terminal → back to rest", DictationPhase.IDLE, state.phase)
        assertEquals(
            "inserted_at stamp as the acknowledge — one channel for insert AND discard (ADR-0013 §4)",
            listOf(Effect.AcknowledgeDiscard(sid), Effect.HidePanel),
            effects,
        )
    }

    @Test
    fun discard_fromTheReviewWait_withAQueuedRun_startsTheNextTake() {
        val queued = reduce(waitingForConfirm(), DictationIntent.StartHotkey("s-2")).first
        val (state, effects) = reduce(queued, DictationIntent.Discard)
        assertEquals(DictationPhase.RECORDING, state.phase)
        assertEquals("s-2", state.activeSessionId)
        assertEquals(listOf(Effect.AcknowledgeDiscard(sid), Effect.StartCapture(device = null)), effects)
    }

    @Test
    fun verdictInsertWithoutConfirmFlag_stillAutoInserts() {
        val (state, effects) = reduce(
            postProcessing(),
            DictationIntent.PipelineVerdict(sid, Verdict.INSERT, "auto", null, requiresConfirm = false),
        )
        assertEquals(DictationPhase.INSERTED, state.phase)
        assertEquals(listOf(Effect.InsertText(sid, "auto")), effects)
    }

    // ── Re-dictate over the review panel (ADR-0013 §6, spec §8.3) ─────────────────────────────

    /** Drives to a settled REVIEW verdict panel. */
    private fun inReview(): DesktopUiState = reduce(
        postProcessing(),
        DictationIntent.PipelineVerdict(sid, Verdict.REVIEW, "draft", "which Anna?"),
    ).first

    @Test
    fun startRefinement_fromReview_startsS2CaptureAndDisablesInsertDiscard() {
        val (state, effects) = reduce(inReview(), DictationIntent.StartRefinement("r-1"))
        assertEquals(DictationPhase.REVIEW, state.phase)
        assertTrue("refinement recording flagged (Insert/Discard disabled, ADR-0013 K1)", state.review!!.refinementRecording)
        assertEquals("r-1", state.review!!.refinementSessionId)
        assertEquals(listOf(Effect.StartCapture(device = null)), effects)
    }

    @Test
    fun startRefinement_whileAlreadyRefining_isANoOp() {
        val recording = reduce(inReview(), DictationIntent.StartRefinement("r-1")).first
        val (state, effects) = reduce(recording, DictationIntent.StartRefinement("r-2"))
        assertEquals("r-1", state.review!!.refinementSessionId) // unchanged
        assertTrue(effects.isEmpty())
    }

    @Test
    fun stopRefinement_transcribesTheS2Take() {
        val recording = reduce(inReview(), DictationIntent.StartRefinement("r-1")).first
        val (state, effects) = reduce(recording, DictationIntent.StopRefinement)
        assertTrue("mic stopped", !state.review!!.refinementRecording)
        assertTrue("now refining until the continuation returns", state.review!!.refining)
        assertEquals(listOf(Effect.RunRefinementTranscription("r-1", sid)), effects)
    }

    @Test
    fun refinementTranscribed_runsTheContinuation() {
        val refining = reduce(
            reduce(inReview(), DictationIntent.StartRefinement("r-1")).first,
            DictationIntent.StopRefinement,
        ).first
        val (state, effects) = reduce(refining, DictationIntent.RefinementTranscribed("make it formal"))
        assertTrue(state.review!!.refining)
        assertEquals(listOf(Effect.RunContinuation(sid, "make it formal", "r-1")), effects)
    }

    @Test
    fun reviewTurnCompleted_reviewVerdict_updatesThePanelInPlace_nonTerminal() {
        val refining = refiningState()
        val (state, effects) = reduce(
            refining,
            DictationIntent.ReviewTurnCompleted(sid, Verdict.REVIEW, "sharper draft", "still which Anna?"),
        )
        assertEquals("panel stays open — iterative re-dictate", DictationPhase.REVIEW, state.phase)
        assertEquals("sharper draft", state.review!!.output)
        assertEquals("still which Anna?", state.review!!.message)
        assertTrue("no longer refining", !state.review!!.refining)
        assertTrue("no insert on a REVIEW verdict", effects.isEmpty())
    }

    @Test
    fun reviewTurnCompleted_insertVerdict_insertsAndCloses() {
        val (state, effects) = reduce(
            refiningState(),
            DictationIntent.ReviewTurnCompleted(sid, Verdict.INSERT, "final", null),
        )
        assertEquals(DictationPhase.INSERTED, state.phase)
        assertNull(state.review)
        assertEquals(listOf(Effect.InsertText(sid, "final")), effects)
    }

    @Test
    fun reviewTurnCompleted_afterCancel_isDropped() {
        // Cancel clears `refining`; a continuation result that lands afterwards must not re-open the panel.
        val cancelled = reduce(refiningState(), DictationIntent.Discard).first
        val (state, effects) = reduce(cancelled, DictationIntent.ReviewTurnCompleted(sid, Verdict.INSERT, "late", null))
        assertEquals(cancelled, state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun discard_duringRefinement_cancels_staysInReview() {
        val (state, effects) = reduce(refiningState(), DictationIntent.Discard)
        assertEquals("stays in review — the take is not acknowledged", DictationPhase.REVIEW, state.phase)
        assertTrue(!state.review!!.refining)
        assertEquals(listOf(Effect.CancelRefinement), effects)
    }

    @Test
    fun refinementFailed_dropsBackToReviewWithAnError() {
        val (state, effects) = reduce(refiningState(), DictationIntent.RefinementFailed("RATE_LIMITED"))
        assertEquals(DictationPhase.REVIEW, state.phase)
        assertEquals("RATE_LIMITED", state.review!!.error)
        assertTrue(!state.review!!.refining)
        assertEquals("original review output survives a failed follow-up", "draft", state.review!!.output)
        assertTrue(effects.isEmpty())
    }

    /** Drives inReview → StartRefinement → StopRefinement → RefinementTranscribed (refining=true). */
    private fun refiningState(): DesktopUiState {
        val recording = reduce(inReview(), DictationIntent.StartRefinement("r-1")).first
        val transcribing = reduce(recording, DictationIntent.StopRefinement).first
        return reduce(transcribing, DictationIntent.RefinementTranscribed("follow up")).first
    }
}
