package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
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

    @Test
    fun `TriggerPipeline from Running is rejected`() {
        val state = PipelineUiState.Running(sessionId = sid, target = InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(
            state,
            Action.PipelineAction.TriggerPipeline(sid, audioFile),
            ctx(),
        )
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
        // F-13 (2026-05-15): StepStarted is a progress tick — it now
        // restamps `elapsedMs` from ctx.now (previously a pure no-op state
        // pass-through). The notification side-effect is unchanged. Only
        // `elapsedMs` may differ from the input state; all other Running
        // fields are preserved.
        val state = PipelineUiState.Running(
            sid,
            InsertionTarget.INPUT_CONNECTION,
            startedAtMs = 1_500L,
        )
        val result = module.reduce(state, Action.PipelineAction.StepStarted(sid, "transcribing"), ctx())
        val next = result!!.nextState as PipelineUiState.Running
        assertEquals(state.copy(elapsedMs = 3_500L), next)   // 5_000 - 1_500
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
}
