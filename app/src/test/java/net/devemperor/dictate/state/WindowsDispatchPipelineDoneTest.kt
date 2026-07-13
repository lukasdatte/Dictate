package net.devemperor.dictate.state

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the `PipelineDone(awaitingDispatch=true)` reducer arm (ADR-0019): the queue still drains,
 * but the module emits NEITHER MarkSessionInserted NOR AddPendingInsertSession — the
 * WindowsDispatchModule owns the acknowledge. And `heldForReview && awaitingDispatch` is impossible.
 */
class WindowsDispatchPipelineDoneTest {

    private val module = PipelineModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial(), now = 5_000L)

    @Test
    fun `awaitingDispatch emits no follow-up effect and drains to Idle`() {
        val sid = "sess-1"
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(
            state,
            Action.PipelineAction.PipelineDone(sid, "final", committed = false, awaitingDispatch = true),
            ctx(),
        )!!
        assertFalse(result.sideEffects.any { it is PipelineModule.Effect.MarkSessionInserted })
        assertFalse(result.sideEffects.any { it is PipelineModule.Effect.AddPendingInsertSession })
        // The FSM went Idle — the ADR-0009 queue drained (nothing queued here).
        assertTrue(result.nextState is PipelineUiState.Idle)
    }

    @Test
    fun `awaitingDispatch takes precedence over committed=false (no pending part)`() {
        val sid = "sess-1"
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION)
        val result = module.reduce(
            state,
            // committed=false would normally add a pending part; awaitingDispatch must win.
            Action.PipelineAction.PipelineDone(sid, "final", committed = false, awaitingDispatch = true),
            ctx(),
        )!!
        assertFalse(result.sideEffects.any { it is PipelineModule.Effect.AddPendingInsertSession })
    }

    @Test
    fun `awaitingDispatch still chain-starts a queued run (ADR-0009 intact)`() {
        val sid = "sess-1"
        val queued = persistentListOf(QueuedRun("sess-2", File("/tmp/a.m4a"), enqueuedAt = 1L))
        val state = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION, queued = queued)
        val result = module.reduce(
            state,
            Action.PipelineAction.PipelineDone(sid, "final", committed = false, awaitingDispatch = true),
            ctx(),
        )!!
        // The next run chain-started rather than returning to Idle.
        assertTrue(result.nextState is PipelineUiState.Preparing)
        assertFalse(result.sideEffects.any { it is PipelineModule.Effect.MarkSessionInserted })
        assertFalse(result.sideEffects.any { it is PipelineModule.Effect.AddPendingInsertSession })
    }

    @Test
    fun `heldForReview and awaitingDispatch together is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            Action.PipelineAction.PipelineDone("s1", "final", heldForReview = true, awaitingDispatch = true)
        }
    }
}
