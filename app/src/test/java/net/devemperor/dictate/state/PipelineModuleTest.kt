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
    fun `StepStarted in Running emits UpdateNotification but keeps state`() {
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(state, Action.PipelineAction.StepStarted(sid, "transcribing"), ctx())
        assertEquals(state, result!!.nextState)
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
