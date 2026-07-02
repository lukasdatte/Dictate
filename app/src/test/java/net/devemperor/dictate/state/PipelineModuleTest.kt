package net.devemperor.dictate.state

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-reducer + cross-module-cascade tests for [PipelineModule].
 *
 * Coverage:
 * - TriggerPipeline transitions Idle → Preparing + Submit effect
 * - StartPipeline transitions Preparing → Running + Update notification
 * - Stale sessionId is rejected (doesn't match current state.sessionId)
 * - StepStarted updates notification
 * - PipelineDone transitions Running → Idle + MarkInserted + Dismiss
 * - PipelineFailed transitions to Idle + MarkFailed + Dismiss
 * - CancelPipeline cancels active job + dismisses notification
 * - CancelPipeline with explicit sessionId mismatch is no-op
 * - ReprocessStaging entry / send / cancel transitions
 * - Cross-module cascade: pipeline-done emits OnPipelineDone + MarkLastAudio
 * - Cross-module cascade: livePrompt-pending triggers ChainNext
 */
class PipelineModuleTest {

    private val module = PipelineModule
    private val sid = "sess-1"
    private val audioFile = File("/tmp/test.m4a")

    private fun ctx(global: DictateUiState = DictateUiState.initial()) =
        ReducerContext(global = global, now = 5_000L)

    // ─── Lifecycle entry ────────────────────────────────────────────────

    @Test
    fun `TriggerPipeline from Idle emits Preparing + SubmitPipeline + UpdateNotification`() {
        val result = module.reduce(
            state = PipelineUiState.Idle,
            action = Action.PipelineAction.TriggerPipeline(sid, audioFile),
            ctx = ctx(),
        )
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals(sid, next.sessionId)
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.SubmitPipeline(sid, audioFile)),
        )
        assertTrue(result.sideEffects.any { it is PipelineModule.Effect.UpdateNotification })
    }

    // ─── ADR-0009: serialized run-queue (enqueue + chain-start) ─────────

    @Test
    fun `StartPipeline carries the queue from Preparing into Running`() {
        // Regression (ADR-0009 review): StartPipeline constructs Running
        // fresh; without an explicit carry-over the defaulted `queued`
        // silently drops every second-in-line run at the Preparing→Running
        // hop — both mid-upload enqueues and the chain-start's handed-over
        // rest would vanish.
        val waiting = QueuedRun("sess-2", File("/tmp/b.m4a"), enqueuedAt = 1_000L)
        val result = module.reduce(
            state = PipelineUiState.Preparing(sessionId = sid, queued = persistentListOf(waiting)),
            action = Action.PipelineAction.StartPipeline(sid, totalSteps = 2, autoEnterActive = false),
            ctx = ctx(),
        )
        val running = result!!.nextState as PipelineUiState.Running
        assertEquals(persistentListOf(waiting), running.queued)
    }

    @Test
    fun `TriggerPipeline while Running appends a QueuedRun instead of being dropped`() {
        // Red-proof (ADR-0009 / spec §3.2, criterion 5): the pre-change
        // reducer silently rejected a second TriggerPipeline while busy
        // (returned null → the user's part-B send was lost). Now it must
        // enqueue the run behind the active one, with NO submit effect.
        val state = PipelineUiState.Running(sessionId = sid, target = InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline("sess-2", File("/tmp/b.m4a")),
            ctx(),
        )
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(1, next.queued.size)
        val q = next.queued.first()
        assertEquals("sess-2", q.sessionId)
        assertEquals(File("/tmp/b.m4a"), q.audioFile)
        assertEquals(5_000L, q.enqueuedAt)   // ctx() injects now = 5_000L
        // Enqueue never submits — only the chain-start (terminal) does.
        assertTrue(
            "enqueue must not emit any SubmitPipeline effect",
            result.sideEffects.none { it is PipelineModule.Effect.SubmitPipeline },
        )
    }

    @Test
    fun `TriggerPipeline while Preparing appends a QueuedRun`() {
        val state = PipelineUiState.Preparing(sid)
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline("sess-2", File("/tmp/b.m4a")),
            ctx(),
        )
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals(sid, next.sessionId)
        assertEquals(1, next.queued.size)
        assertEquals("sess-2", next.queued.first().sessionId)
        assertTrue(result.sideEffects.none { it is PipelineModule.Effect.SubmitPipeline })
    }

    @Test
    fun `TriggerPipeline dedups a sessionId already queued`() {
        // Double-tap guard: a second trigger for a sessionId already
        // waiting is not-relevant (return null), not a duplicate entry.
        val state = PipelineUiState.Running(
            sessionId = sid,
            target = InsertionTarget.INPUT_CONNECTION,
            queued = persistentListOf(QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L)),
        )
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline("sess-2", File("/tmp/b.m4a")),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `TriggerPipeline dedups a sessionId equal to the active run`() {
        // A re-trigger of the currently-running session is a double-tap,
        // not a queue-behind-itself request (none exists today —
        // resend/reprocess route through different actions).
        val state = PipelineUiState.Running(sessionId = sid, target = InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline(sid, audioFile),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `TriggerPipeline from ReprocessStaging is still rejected`() {
        val state = PipelineUiState.ReprocessStaging(sid, transcript = "")
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline("sess-2", File("/tmp/b.m4a")),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `PipelineDone(committed=true) with a non-empty queue chain-starts the next run`() {
        val queued = persistentListOf(QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L))
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, queued = queued)
        val result = module.reduce(state, Action.PipelineAction.PipelineDone(sid, "hello"), ctx())
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-2", next.sessionId)
        assertTrue("rest of the queue carries over", next.queued.isEmpty())
        // Finished-session effect stays exactly as-is.
        assertTrue(result.sideEffects.contains(PipelineModule.Effect.MarkSessionInserted(sid, 5_000L)))
        // Chain-start effects.
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.SubmitPipeline("sess-2", File("/tmp/b.m4a"))),
        )
        assertTrue(
            result.sideEffects.any {
                it is PipelineModule.Effect.UpdateNotification &&
                    it.status is NotificationStatus.Pipeline
            },
        )
        // No DismissNotification mid-chain — the notification stays up.
        assertFalse(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `PipelineDone(committed=false) with a non-empty queue chain-starts and keeps AddPendingInsertSession`() {
        val queued = persistentListOf(QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L))
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, queued = queued)
        val result = module.reduce(
            state,
            Action.PipelineAction.PipelineDone(sid, "hello", committed = false),
            ctx(),
        )
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-2", next.sessionId)
        assertTrue(
            result.sideEffects.any { it is PipelineModule.Effect.AddPendingInsertSession },
        )
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.SubmitPipeline("sess-2", File("/tmp/b.m4a"))),
        )
        assertFalse(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `PipelineFailed with a non-empty queue chain-starts the next run`() {
        val queued = persistentListOf(QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L))
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, queued = queued)
        val result = module.reduce(state, Action.PipelineAction.PipelineFailed(sid, "rate-limit"), ctx())
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-2", next.sessionId)
        assertTrue(result.sideEffects.contains(PipelineModule.Effect.MarkSessionFailed(sid, "rate-limit")))
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.SubmitPipeline("sess-2", File("/tmp/b.m4a"))),
        )
        assertFalse(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `CancelPipeline with a non-empty queue cancels the active run and chain-starts the next`() {
        // D5: cancel targets the active run only; queued runs survive and
        // chain-start.
        val queued = persistentListOf(QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L))
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, queued = queued)
        val result = module.reduce(state, Action.PipelineAction.CancelPipeline(sid), ctx())
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-2", next.sessionId)
        assertTrue(result.sideEffects.contains(PipelineModule.Effect.CancelPipelineJob(sid)))
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.SubmitPipeline("sess-2", File("/tmp/b.m4a"))),
        )
        assertFalse(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `RejectedJobAlreadyActive with a non-empty queue chain-starts the next run`() {
        val queued = persistentListOf(QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L))
        val state = PipelineUiState.Preparing(sid, queued = queued)
        val result = module.reduce(state, Action.PipelineAction.RejectedJobAlreadyActive(sid), ctx())
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-2", next.sessionId)
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.SubmitPipeline("sess-2", File("/tmp/b.m4a"))),
        )
        assertFalse(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `multi-entry queue drains FIFO across successive terminals`() {
        val queued = persistentListOf(
            QueuedRun("sess-2", File("/tmp/b.m4a"), 1_000L),
            QueuedRun("sess-3", File("/tmp/c.m4a"), 2_000L),
        )
        val running = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, queued = queued)

        // First terminal → sess-2 starts, sess-3 stays queued.
        val r1 = module.reduce(running, Action.PipelineAction.PipelineDone(sid, "a"), ctx())
        val p2 = r1!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-2", p2.sessionId)
        assertEquals(1, p2.queued.size)
        assertEquals("sess-3", p2.queued.first().sessionId)

        // Promote sess-2 to Running (carry its queue), then finish it →
        // sess-3 chain-starts, queue now empty.
        val running2 = PipelineUiState.Running(
            "sess-2", InsertionTarget.INPUT_CONNECTION, queued = p2.queued,
        )
        val r2 = module.reduce(running2, Action.PipelineAction.PipelineDone("sess-2", "b"), ctx())
        val p3 = r2!!.nextState as PipelineUiState.Preparing
        assertEquals("sess-3", p3.sessionId)
        assertTrue(p3.queued.isEmpty())

        // Final terminal with an empty queue → Idle + DismissNotification.
        val running3 = PipelineUiState.Running("sess-3", InsertionTarget.INPUT_CONNECTION)
        val r3 = module.reduce(running3, Action.PipelineAction.PipelineDone("sess-3", "c"), ctx())
        assertEquals(PipelineUiState.Idle, r3!!.nextState)
        assertTrue(r3.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `TriggerPipeline from Running is rejected`() {
        val state = PipelineUiState.Running(sessionId = sid, target = InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline(sid, audioFile),
            ctx(),
        )
        // Same sessionId as the active run → dedup → not-relevant (null).
        assertNull(result)
    }

    @Test
    fun `StartPipeline transitions Preparing to Running`() {
        val result = module.reduce(
            state = PipelineUiState.Preparing(sid),
            action = Action.PipelineAction.StartPipeline(sid, totalSteps = 3, autoEnterActive = true),
            ctx = ctx(),
        )
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(sid, next.sessionId)
        assertEquals(true, next.autoEnterActive)
    }

    @Test
    fun `StartPipeline with mismatched sessionId is rejected`() {
        val result = module.reduce(
            PipelineUiState.Preparing(sid),
            Action.PipelineAction.StartPipeline("other-sid", 1, false),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `ToggleRunningAutoEnter flips autoEnterActive in Running (post-cutover #AE)`() {
        // Per-run toggle — distinct from the global Pref.AutoEnter that
        // FeatureToggleAction.ToggleAutoEnter would mutate. The second
        // SEND-tap during Running must dispatch THIS action so a transient
        // user override doesn't silently leak into the global preference.
        val running = PipelineUiState.Running(
            sid,
            InsertionTarget.INPUT_CONNECTION,
            autoEnterActive = false,
        )
        val result = module.reduce(running, Action.PipelineAction.ToggleRunningAutoEnter, ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(true, next.autoEnterActive)

        val again = module.reduce(next, Action.PipelineAction.ToggleRunningAutoEnter, ctx())
        assertEquals(false, (again!!.nextState as PipelineUiState.Running).autoEnterActive)
    }

    @Test
    fun `ToggleRunningAutoEnter is a no-op in Idle (post-cutover #AE-DEEP2)`() {
        // Pre-#AE-DEEP2 this also rejected Preparing — but the
        // double-tap-during-upload race lands in Preparing, so the action
        // must be accepted there. Idle stays a no-op: there is no pipeline
        // to toggle against.
        assertNull(module.reduce(PipelineUiState.Idle, Action.PipelineAction.ToggleRunningAutoEnter, ctx()))
    }

    @Test
    fun `ToggleRunningAutoEnter flips autoEnterActive in Preparing (#AE-DEEP2)`() {
        // The 500ms–2s upload window between StopRecordingAndSend
        // (state.pipeline → Preparing) and the first runner StepStarted
        // callback (Preparing → Running) is exactly where the user's
        // double-tap typically lands. Pre-fix the reducer returned null
        // here and the tap was silently lost; post-fix it flips the
        // Preparing-side flag which then carries forward via the
        // StartPipeline merge.
        val prep = PipelineUiState.Preparing(sid, autoEnterActive = false)
        val result = module.reduce(prep, Action.PipelineAction.ToggleRunningAutoEnter, ctx())
        val next = result!!.nextState as PipelineUiState.Preparing
        assertEquals(true, next.autoEnterActive)

        val again = module.reduce(next, Action.PipelineAction.ToggleRunningAutoEnter, ctx())
        assertEquals(false, (again!!.nextState as PipelineUiState.Preparing).autoEnterActive)
    }

    @Test
    fun `StartPipeline carries Preparing autoEnterActive into Running (#AE-DEEP2)`() {
        // If the user toggled auto-enter during the upload window
        // (Preparing.autoEnterActive = true), the runner's StartPipeline
        // must NOT silently overwrite it with the action-supplied default
        // (which is read off Pref.AutoEnter, typically false). Merge with
        // OR so either side being true wins.
        val prep = PipelineUiState.Preparing(sid, autoEnterActive = true)
        val result = module.reduce(
            state = prep,
            action = Action.PipelineAction.StartPipeline(sid, totalSteps = 1, autoEnterActive = false),
            ctx = ctx(),
        )
        val running = result!!.nextState as PipelineUiState.Running
        assertEquals(true, running.autoEnterActive)
    }

    @Test
    fun `StepStarted in Running emits UpdateNotification and restamps elapsedMs (F-13)`() {
        // F-13 (2026-05-15): StepStarted is a progress tick — restamps
        // `elapsedMs` from ctx.now. Phase 5.A of
        // dictate-render-cutover-completion-vol2 additionally appends a
        // RUNNING StepRowItem to stepHistory.
        val state = PipelineUiState.Running(
            sid,
            InsertionTarget.INPUT_CONNECTION,
            startedAtMs = 1_500L,
        )
        val result = module.reduce(state, Action.PipelineAction.StepStarted(sid, "transcribing"), ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(3_500L, next.elapsedMs)   // 5_000 - 1_500
        assertEquals(1, next.stepHistory.size)
        val row = next.stepHistory.first()
        assertEquals("transcribing", row.stepName)
        assertEquals(StepStatus.RUNNING, row.status)
        assertEquals(5_000L, row.startedAtMs)
        assertEquals(0L, row.durationMs)
        assertEquals(1, result.sideEffects.size)
    }

    // ─── Terminal transitions ───────────────────────────────────────────

    @Test
    fun `PipelineDone from Running drops to Idle + MarkInserted + DismissNotification`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(state, Action.PipelineAction.PipelineDone(sid, "hello"), ctx())
        assertEquals(PipelineUiState.Idle, result!!.nextState)
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.MarkSessionInserted(sid, 5_000L)),
        )
        assertTrue(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `PipelineFailed drops to Idle + MarkFailed`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(state, Action.PipelineAction.PipelineFailed(sid, "rate-limit"), ctx())
        assertEquals(PipelineUiState.Idle, result!!.nextState)
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.MarkSessionFailed(sid, "rate-limit")),
        )
    }

    // ─── B-D-3: per-second TickPipelineTimer ─────────────────────────

    @Test
    fun `TickPipelineTimer in Running restamps elapsedMs from startedAtMs`() {
        // B-D-3 fix: the per-second observer dispatches TickPipelineTimer
        // and the reducer restamps elapsedMs = ctx.now - startedAtMs so
        // the record-button label advances visibly between step
        // boundaries.
        val state = PipelineUiState.Running(
            sid,
            InsertionTarget.INPUT_CONNECTION,
            startedAtMs = 1_500L,
            elapsedMs = 0L,
        )
        val result = module.reduce(state, Action.PipelineAction.TickPipelineTimer, ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(3_500L, next.elapsedMs)  // 5_000 - 1_500
        // Pure state-only: no side effects emitted.
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `TickPipelineTimer in Idle is a no-op`() {
        // Late tick after the pipeline ended (Running → Idle race): the
        // observer's collect-coroutine may schedule one more
        // Handler.postDelayed before noticing the phase change. The
        // reducer's else-arm absorbs it (idempotent action contract).
        val result = module.reduce(
            PipelineUiState.Idle,
            Action.PipelineAction.TickPipelineTimer,
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `TickPipelineTimer in Preparing is a no-op`() {
        // Preparing has no elapsedMs slot; the timer only starts after
        // the runner's StartPipeline transitions Preparing → Running.
        val result = module.reduce(
            PipelineUiState.Preparing(sid),
            Action.PipelineAction.TickPipelineTimer,
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `TickPipelineTimer in ReprocessStaging is a no-op`() {
        val result = module.reduce(
            PipelineUiState.ReprocessStaging(sid, transcript = ""),
            Action.PipelineAction.TickPipelineTimer,
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `TickPipelineTimer preserves stepHistory and other fields`() {
        // The tick must restamp ONLY elapsedMs. Step history, autoEnter,
        // hasFailure etc. are owned by other action arms.
        val originalHistory = kotlinx.collections.immutable.persistentListOf(
            StepRowItem(
                stepName = "Transcribe",
                status = StepStatus.RUNNING,
                startedAtMs = 1_000L,
            ),
        )
        val state = PipelineUiState.Running(
            sessionId = sid,
            target = InsertionTarget.INPUT_CONNECTION,
            autoEnterActive = true,
            completedSteps = 1,
            totalSteps = 2,
            startedAtMs = 1_000L,
            elapsedMs = 2_000L,
            hasFailure = false,
            stepHistory = originalHistory,
        )
        val result = module.reduce(state, Action.PipelineAction.TickPipelineTimer, ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(4_000L, next.elapsedMs)
        assertEquals(true, next.autoEnterActive)
        assertEquals(1, next.completedSteps)
        assertEquals(2, next.totalSteps)
        assertEquals(originalHistory, next.stepHistory)
    }

    // ─── Phase 5.A: stepHistory + hasFailure ─────────────────────────

    @Test
    fun `StartPipeline resets stepHistory and hasFailure on fresh run`() {
        val prep = PipelineUiState.Preparing(sid, autoEnterActive = false)
        val result = module.reduce(
            prep,
            Action.PipelineAction.StartPipeline(sid, totalSteps = 2, autoEnterActive = false),
            ctx(),
        )
        val running = result!!.nextState as PipelineUiState.Running
        assertTrue("fresh StartPipeline must produce an empty stepHistory", running.stepHistory.isEmpty())
        assertFalse("fresh StartPipeline must clear hasFailure", running.hasFailure)
    }

    @Test
    fun `StepCompleted finalises the last RUNNING row to COMPLETED with durationMs`() {
        val started = PipelineUiState.Running(
            sid, InsertionTarget.INPUT_CONNECTION,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                StepRowItem("transcribing", StepStatus.RUNNING, startedAtMs = 1_000L),
            ),
        )
        val result = module.reduce(started, Action.PipelineAction.StepCompleted(sid), ctx())
        val running = result!!.nextState as PipelineUiState.Running
        assertEquals(1, running.stepHistory.size)
        val row = running.stepHistory.first()
        assertEquals(StepStatus.COMPLETED, row.status)
        assertEquals(4_000L, row.durationMs)  // ctx.now (5_000) - startedAtMs (1_000)
        assertEquals(1, running.completedSteps)
    }

    @Test
    fun `StepFailed on Running keeps Running with hasFailure and FAILED row, no DismissNotification`() {
        // Q6: StepFailed is NOT pipeline-ending.
        val started = PipelineUiState.Running(
            sid, InsertionTarget.INPUT_CONNECTION,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                StepRowItem("formatting", StepStatus.RUNNING, startedAtMs = 2_000L),
            ),
        )
        val result = module.reduce(started, Action.PipelineAction.StepFailed(sid, "model-overloaded"), ctx())
        val running = result!!.nextState as PipelineUiState.Running
        assertTrue("hasFailure must flip to true on StepFailed in Running", running.hasFailure)
        assertEquals(StepStatus.FAILED, running.stepHistory.first().status)
        assertEquals(3_000L, running.stepHistory.first().durationMs)
        assertTrue(
            "MarkSessionFailed must still be dispatched",
            result.sideEffects.contains(PipelineModule.Effect.MarkSessionFailed(sid, "model-overloaded")),
        )
        assertFalse(
            "Q6: pipeline continues — DismissNotification must NOT be dispatched on Running.StepFailed",
            result.sideEffects.contains(PipelineModule.Effect.DismissNotification),
        )
    }

    @Test
    fun `StepFailed on Preparing transitions to Idle and dispatches DismissNotification (upload fail)`() {
        // Preparing-arm preserves the legacy semantics: an upload-time
        // failure happens BEFORE any step row, so it ends the pipeline.
        val prep = PipelineUiState.Preparing(sid)
        val result = module.reduce(prep, Action.PipelineAction.StepFailed(sid, "upload-error"), ctx())
        assertEquals(PipelineUiState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(PipelineModule.Effect.DismissNotification))
    }

    @Test
    fun `currentStepName extension reflects the last RUNNING row`() {
        val running = PipelineUiState.Running(
            sid, InsertionTarget.INPUT_CONNECTION,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                StepRowItem("step1", StepStatus.COMPLETED, startedAtMs = 0L, durationMs = 100L),
                StepRowItem("step2", StepStatus.RUNNING, startedAtMs = 200L),
            ),
        )
        assertEquals("step2", running.currentStepName)
    }

    @Test
    fun `currentStepName is null when no row is RUNNING`() {
        val running = PipelineUiState.Running(
            sid, InsertionTarget.INPUT_CONNECTION,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                StepRowItem("step1", StepStatus.COMPLETED, startedAtMs = 0L, durationMs = 100L),
            ),
        )
        assertNull(running.currentStepName)
    }

    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `CancelPipeline drops to Idle + cancels job`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(state, Action.PipelineAction.CancelPipeline(sid), ctx())
        assertEquals(PipelineUiState.Idle, result!!.nextState)
        assertTrue(
            result.sideEffects.contains(PipelineModule.Effect.CancelPipelineJob(sid)),
        )
    }

    @Test
    fun `CancelPipeline with null sessionId cancels current`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(state, Action.PipelineAction.CancelPipeline(null), ctx())
        assertEquals(PipelineUiState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(PipelineModule.Effect.CancelPipelineJob(sid)))
    }

    @Test
    fun `CancelPipeline with mismatched sessionId is rejected`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(state, Action.PipelineAction.CancelPipeline("other"), ctx())
        assertNull(result)
    }

    @Test
    fun `CancelPipeline from Idle is rejected`() {
        val result = module.reduce(PipelineUiState.Idle, Action.PipelineAction.CancelPipeline(null), ctx())
        assertNull(result)
    }

    // ─── ReprocessStaging sub-FSM ───────────────────────────────────────

    @Test
    fun `StartReprocessStaging from Idle enters ReprocessStaging`() {
        val result = module.reduce(
            PipelineUiState.Idle,
            Action.PipelineAction.StartReprocessStaging(sid),
            ctx(),
        )
        val next = result!!.nextState as PipelineUiState.ReprocessStaging
        assertEquals(sid, next.sessionId)
    }

    @Test
    fun `SendStaging transitions ReprocessStaging to Preparing`() {
        val state = PipelineUiState.ReprocessStaging(sid, transcript = "x")
        val result = module.reduce(state, Action.PipelineAction.SendStaging(sid), ctx())
        assertTrue(result!!.nextState is PipelineUiState.Preparing)
        assertTrue(result.sideEffects.any { it is PipelineModule.Effect.SubmitReprocess })
    }

    @Test
    fun `CancelReprocessStaging drops to Idle`() {
        val state = PipelineUiState.ReprocessStaging(sid, "x")
        val result = module.reduce(state, Action.PipelineAction.CancelReprocessStaging(sid), ctx())
        assertEquals(PipelineUiState.Idle, result!!.nextState)
    }

    // ─── F-12 SendStaging single-submit guard (B1-VAL-W1 option b) ──────
    // The guard is the FSM `ReprocessStaging → Preparing` edge, NOT a
    // state flag — `ReprocessStaging` carries no `isStarting` field
    // (Spec 1 §3). Dispatch is main-thread-confined (ADR-0001) so two
    // taps are serialized: the second arrives in `Preparing` and reduces
    // to `null`. See research/sendstaging-isstarting-guard-semantics.md.

    @Test
    fun `F-12 second SendStaging after the first is a no-op (FSM edge guards single-submit)`() {
        // First tap: ReprocessStaging → Preparing + SubmitReprocess.
        val staging = PipelineUiState.ReprocessStaging(sid, transcript = "x")
        val first = module.reduce(staging, Action.PipelineAction.SendStaging(sid), ctx())
        assertTrue(first!!.nextState is PipelineUiState.Preparing)
        // Second tap on the large record button arrives with the pipeline
        // already in Preparing — falls to the `else -> null` arm, so the
        // reprocess job is submitted exactly once.
        val second = module.reduce(
            first.nextState,
            Action.PipelineAction.SendStaging(sid),
            ctx(),
        )
        assertNull(second)
    }

    @Test
    fun `F-12 SendStaging submits exactly once`() {
        val state = PipelineUiState.ReprocessStaging(sid, transcript = "x")
        val result = module.reduce(state, Action.PipelineAction.SendStaging(sid), ctx())
        assertTrue(result!!.nextState is PipelineUiState.Preparing)
        assertEquals(
            1,
            result.sideEffects.count { it is PipelineModule.Effect.SubmitReprocess },
        )
    }

    @Test
    fun `F-12 SendStaging with mismatched sessionId is rejected`() {
        val state = PipelineUiState.ReprocessStaging(sid, transcript = "x")
        val result = module.reduce(state, Action.PipelineAction.SendStaging("other-sid"), ctx())
        assertNull(result)
    }

    // ─── F-13 Running progress counters ─────────────────────────────────

    @Test
    fun `F-13 Running defaults all counters to zero`() {
        val running = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        assertEquals(0, running.completedSteps)
        assertEquals(0, running.totalSteps)
        assertEquals(0L, running.startedAtMs)
        assertEquals(0L, running.elapsedMs)
    }

    @Test
    fun `F-13 StartPipeline stamps totalSteps + startedAtMs from ctx-now`() {
        val result = module.reduce(
            state = PipelineUiState.Preparing(sid),
            action = Action.PipelineAction.StartPipeline(sid, totalSteps = 4, autoEnterActive = false),
            ctx = ctx(),
        )
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(4, next.totalSteps)
        assertEquals(0, next.completedSteps)
        assertEquals(5_000L, next.startedAtMs)   // ctx() injects now = 5_000L
        assertEquals(0L, next.elapsedMs)
    }

    @Test
    fun `F-13 StepCompleted increments completedSteps and restamps elapsedMs`() {
        val state = PipelineUiState.Running(
            sessionId = sid,
            target = InsertionTarget.INPUT_CONNECTION,
            completedSteps = 1,
            totalSteps = 3,
            startedAtMs = 1_000L,
        )
        val result = module.reduce(state, Action.PipelineAction.StepCompleted(sid), ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(2, next.completedSteps)
        assertEquals(4_000L, next.elapsedMs)   // ctx now 5_000 - startedAt 1_000
        assertEquals(3, next.totalSteps)       // unchanged
    }

    @Test
    fun `F-13 StepCompleted with mismatched sessionId is rejected`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, startedAtMs = 1_000L)
        val result = module.reduce(state, Action.PipelineAction.StepCompleted("other"), ctx())
        assertNull(result)
    }

    @Test
    fun `F-13 StepCompleted outside Running is a no-op`() {
        assertNull(module.reduce(PipelineUiState.Idle, Action.PipelineAction.StepCompleted(sid), ctx()))
        assertNull(
            module.reduce(
                PipelineUiState.Preparing(sid),
                Action.PipelineAction.StepCompleted(sid),
                ctx(),
            ),
        )
    }

    @Test
    fun `F-13 StepStarted restamps elapsedMs without touching counters`() {
        val state = PipelineUiState.Running(
            sessionId = sid,
            target = InsertionTarget.INPUT_CONNECTION,
            completedSteps = 1,
            totalSteps = 3,
            startedAtMs = 2_000L,
        )
        val result = module.reduce(state, Action.PipelineAction.StepStarted(sid, "rewording"), ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(3_000L, next.elapsedMs)   // 5_000 - 2_000
        assertEquals(1, next.completedSteps)   // unchanged
        assertEquals(3, next.totalSteps)       // unchanged
        assertTrue(result.sideEffects.any { it is PipelineModule.Effect.UpdateNotification })
    }

    @Test
    fun `F-13 StepStarted with mismatched sessionId is rejected`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, startedAtMs = 1_000L)
        val result = module.reduce(state, Action.PipelineAction.StepStarted("other", "x"), ctx())
        assertNull(result)
    }

    @Test
    fun `F-13 StepStarted outside Running is a no-op`() {
        assertNull(
            module.reduce(
                PipelineUiState.Preparing(sid),
                Action.PipelineAction.StepStarted(sid, "x"),
                ctx(),
            ),
        )
    }

    @Test
    fun `F-13 elapsedMs is floored at zero when ctx-now precedes startedAtMs`() {
        // Defensive: a test-constructed Running with a high startedAtMs (or
        // a non-monotonic injected clock) must not surface a negative timer.
        val state = PipelineUiState.Running(
            sessionId = sid,
            target = InsertionTarget.INPUT_CONNECTION,
            startedAtMs = 9_000L,   // later than ctx() now = 5_000L
        )
        val result = module.reduce(state, Action.PipelineAction.StepCompleted(sid), ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(0L, next.elapsedMs)
    }

    // ─── Cross-module cascade ───────────────────────────────────────────

    @Test
    fun `cross-module Running to Idle cascades OnPipelineDone + MarkLastAudio`() {
        val prev = DictateUiState.initial()
            .copy(pipeline = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION))
        val next = prev.copy(pipeline = PipelineUiState.Idle)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.ViewModeAction.OnPipelineDone))
        assertTrue(cascade.any { it is Action.ResendAction.MarkLastAudio })
    }

    @Test
    fun `cross-module Preparing to Idle cascades OnPipelineDone`() {
        val prev = DictateUiState.initial().copy(pipeline = PipelineUiState.Preparing(sid))
        val next = prev.copy(pipeline = PipelineUiState.Idle)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.ViewModeAction.OnPipelineDone))
    }

    @Test
    fun `cross-module Idle to Idle does NOT cascade`() {
        val prev = DictateUiState.initial()
        val next = prev.copy()
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module Idle to Preparing does NOT cascade OnPipelineDone`() {
        // Pipeline entering Preparing is NOT a session-end boundary.
        val prev = DictateUiState.initial()
        val next = prev.copy(pipeline = PipelineUiState.Preparing(sid))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.none { it == Action.ViewModeAction.OnPipelineDone })
    }

    @Test
    fun `F-24 — cross-module Preparing to Running does NOT cascade OnPipelineDone`() {
        // Preparing → Running is an internal pipeline-FSM transition, NOT
        // a session-end boundary. The boundary is `prev != Idle && next is
        // Idle`. F-24 pinned because the existing matrix tests covered
        // Idle→Idle (no cascade) and Idle→Preparing (no cascade) but not
        // the Preparing→Running boundary which also must NOT emit.
        val prev = DictateUiState.initial().copy(pipeline = PipelineUiState.Preparing(sid))
        val next = prev.copy(
            pipeline = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION),
        )
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "OnPipelineDone must not cascade on Preparing→Running",
            cascade.none { it is Action.ViewModeAction.OnPipelineDone },
        )
        assertTrue(
            "MarkLastAudio must not cascade on Preparing→Running",
            cascade.none { it is Action.ResendAction.MarkLastAudio },
        )
    }

    @Test
    fun `cross-module Pipeline-Done with livePrompt-pending cascades ChainNext`() {
        val prev = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION),
            livePrompt = LivePromptState(enabled = true, pendingChain = true),
        )
        val next = prev.copy(pipeline = PipelineUiState.Idle)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.any { it is Action.LivePromptAction.ChainNext })
    }

    @Test
    fun `cross-module Pipeline-Done without livePrompt-pending does NOT cascade ChainNext`() {
        val prev = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION),
            livePrompt = LivePromptState(enabled = true, pendingChain = false),
        )
        val next = prev.copy(pipeline = PipelineUiState.Idle)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.none { it is Action.LivePromptAction.ChainNext })
    }

    // ─── Lens / IDs ─────────────────────────────────────────────────────

    @Test
    fun `module id is Pipeline`() {
        assertEquals(ModuleId.Pipeline, module.id)
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(PipelineUiState.Idle, module.initialState())
    }

    @Test
    fun `lens round-trip preserves pipeline axis`() {
        val state = DictateUiState.initial().copy(pipeline = PipelineUiState.Preparing(sid))
        val sub = module.read(state)
        assertEquals(PipelineUiState.Preparing(sid), sub)
        val back = module.write(state, PipelineUiState.Idle)
        assertEquals(PipelineUiState.Idle, back.pipeline)
    }

    // ─── R4 recording-order key (ADR-0009 / spec §3.5) ──────────────────

    /**
     * Red-proof: the `AddPendingInsertSession` effect handler must key the
     * emitted `AddOne` by the session's DB `created_at` (recording order),
     * NOT by `effect.createdAt` (= ctx.now at PipelineDone, i.e. completion
     * time). Against the pre-R4 handler (which forwarded `effect.createdAt`)
     * this fails: the emitted session carries 9_000L instead of 1_000L.
     */
    @Test
    fun `AddPendingInsertSession effect keys AddOne by DB created_at not completion time`() {
        val emitted = mutableListOf<Action>()
        val repo = object : PipelineSessionRepoSubsystem {
            override suspend fun loadPending(): List<PendingSession> = emptyList()
            override suspend fun markInserted(sessionId: String, at: Long) = Unit
            override suspend fun markFailed(sessionId: String, reason: String) = Unit
            override fun pendingFlow(): kotlinx.coroutines.flow.Flow<List<PendingSession>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun syncAudioFilePaths(sessionId: String): Int = 0
            // Recording-order key: DB created_at differs from the effect's
            // completion timestamp.
            override suspend fun findCreatedAt(sessionId: String): Long? = 1_000L
        }
        val services = net.devemperor.dictate.testutil.fakeModuleServices(
            sessionRepo = repo,
            emitAction = { emitted += it },
        )

        module.runEffect(
            PipelineModule.Effect.AddPendingInsertSession(
                sessionId = sid,
                text = "deferred part",
                createdAt = 9_000L, // completion time — must NOT be used
            ),
            services,
        )

        val addOne = emitted.filterIsInstance<Action.PendingSessionsAction.AddOne>().single()
        assertEquals("keyed by DB created_at (recording order)", 1_000L, addOne.session.createdAt)
        assertEquals(sid, addOne.session.sessionId)
        assertEquals("deferred part", addOne.session.transcribedText)
    }

    /**
     * When the DB row is missing (`findCreatedAt` returns null) the handler
     * falls back to the effect's own timestamp — the pending part is still
     * surfaced, just keyed by completion time.
     */
    @Test
    fun `AddPendingInsertSession falls back to effect createdAt when the row is missing`() {
        val emitted = mutableListOf<Action>()
        val repo = object : PipelineSessionRepoSubsystem {
            override suspend fun loadPending(): List<PendingSession> = emptyList()
            override suspend fun markInserted(sessionId: String, at: Long) = Unit
            override suspend fun markFailed(sessionId: String, reason: String) = Unit
            override fun pendingFlow(): kotlinx.coroutines.flow.Flow<List<PendingSession>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun syncAudioFilePaths(sessionId: String): Int = 0
            override suspend fun findCreatedAt(sessionId: String): Long? = null
        }
        val services = net.devemperor.dictate.testutil.fakeModuleServices(
            sessionRepo = repo,
            emitAction = { emitted += it },
        )

        module.runEffect(
            PipelineModule.Effect.AddPendingInsertSession(sid, "x", createdAt = 7_777L),
            services,
        )

        val addOne = emitted.filterIsInstance<Action.PendingSessionsAction.AddOne>().single()
        assertEquals(7_777L, addOne.session.createdAt)
    }
}
