package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer + cascade tests for [ReviewPanelModule] (ADR-0013).
 */
class ReviewPanelModuleTest {

    private val module = ReviewPanelModule(clock = { 1_000L })
    private fun ctx() = ReducerContext(global = DictateUiState.initial(), now = 42L)

    private fun openState() = ReviewPanelState(
        open = true, sessionId = "s1", output = "out", message = "why", refining = false,
    )

    @Test
    fun `Show opens the panel with output and message`() {
        val r = module.reduce(
            ReviewPanelState(),
            Action.ReviewPanelAction.Show("s1", "hello", "unclear"),
            ctx(),
        )!!
        assertTrue(r.nextState.open)
        assertEquals("s1", r.nextState.sessionId)
        assertEquals("hello", r.nextState.output)
        assertEquals("unclear", r.nextState.message)
        assertFalse(r.nextState.refining)
    }

    @Test
    fun `Show over a different held session preserves the outgoing one as a pending part (G2-4)`() {
        val r = module.reduce(
            openState(), // holds "s1"
            Action.ReviewPanelAction.Show("s2", "second", "unclear2"),
            ctx(),
        )!!
        // The panel now shows the new session…
        assertEquals("s2", r.nextState.sessionId)
        assertEquals("second", r.nextState.output)
        // …and the outgoing "s1" is surfaced as a pending part, not lost.
        val eff = r.sideEffects.single() as ReviewPanelModule.Effect.SurfacePendingPart
        assertEquals("s1", eff.sessionId)
        assertEquals("out", eff.output)
    }

    @Test
    fun `Show for the same held session does not surface a pending part (G2-4)`() {
        val r = module.reduce(
            openState(), // holds "s1"
            Action.ReviewPanelAction.Show("s1", "refreshed", "why"),
            ctx(),
        )!!
        assertEquals("s1", r.nextState.sessionId)
        assertTrue(r.sideEffects.isEmpty())
    }

    @Test
    fun `Update refreshes output and clears refining`() {
        val r = module.reduce(
            openState().copy(refining = true),
            Action.ReviewPanelAction.Update("out2", "why2"),
            ctx(),
        )!!
        assertEquals("out2", r.nextState.output)
        assertEquals("why2", r.nextState.message)
        assertFalse(r.nextState.refining)
    }

    @Test
    fun `Update on closed panel is a no-op`() {
        assertNull(module.reduce(ReviewPanelState(), Action.ReviewPanelAction.Update("x", null), ctx()))
    }

    @Test
    fun `MarkRefining sets refining, CancelRefinement clears it keeping output`() {
        val refining = module.reduce(openState(), Action.ReviewPanelAction.MarkRefining, ctx())!!.nextState
        assertTrue(refining.refining)
        val cancelled = module.reduce(refining, Action.ReviewPanelAction.CancelRefinement, ctx())!!.nextState
        assertFalse(cancelled.refining)
        assertEquals("out", cancelled.output) // prior output preserved
    }

    @Test
    fun `MarkRefinementRecording locks the panel while S2 records (K1)`() {
        val r = module.reduce(openState(), Action.ReviewPanelAction.MarkRefinementRecording, ctx())!!
        assertTrue(r.nextState.refinementRecording)
        assertFalse(r.nextState.refining)
    }

    @Test
    fun `MarkRefining supersedes the recording lock (K1)`() {
        val recording = openState().copy(refinementRecording = true)
        val r = module.reduce(recording, Action.ReviewPanelAction.MarkRefining, ctx())!!
        assertTrue(r.nextState.refining)
        assertFalse(r.nextState.refinementRecording)
    }

    @Test
    fun `CancelRefinement clears the recording lock so the panel is never stuck (K1)`() {
        val recording = openState().copy(refinementRecording = true)
        val r = module.reduce(recording, Action.ReviewPanelAction.CancelRefinement, ctx())!!
        assertFalse(r.nextState.refinementRecording)
        assertFalse(r.nextState.refining)
        assertEquals("out", r.nextState.output)
    }

    @Test
    fun `Update clears the recording lock as well as refining`() {
        val recording = openState().copy(refinementRecording = true)
        val r = module.reduce(recording, Action.ReviewPanelAction.Update("out2", "why2"), ctx())!!
        assertFalse(r.nextState.refinementRecording)
        assertFalse(r.nextState.refining)
    }

    @Test
    fun `MarkRefinementRecording on a closed panel is a no-op`() {
        assertNull(module.reduce(ReviewPanelState(), Action.ReviewPanelAction.MarkRefinementRecording, ctx()))
    }

    @Test
    fun `Insert clears the axis and emits MarkAcknowledged`() {
        val r = module.reduce(openState(), Action.ReviewPanelAction.Insert, ctx())!!
        assertFalse(r.nextState.open)
        val eff = r.sideEffects.single() as ReviewPanelModule.Effect.MarkAcknowledged
        assertEquals("s1", eff.sessionId)
        assertEquals(42L, eff.at)
    }

    @Test
    fun `Discard clears the axis and emits MarkAcknowledged (same channel as Insert)`() {
        val r = module.reduce(openState(), Action.ReviewPanelAction.Discard, ctx())!!
        assertFalse(r.nextState.open)
        assertTrue(r.sideEffects.single() is ReviewPanelModule.Effect.MarkAcknowledged)
    }

    @Test
    fun `ConvertToPendingAndClose clears the axis and emits SurfacePendingPart`() {
        val r = module.reduce(openState(), Action.ReviewPanelAction.ConvertToPendingAndClose, ctx())!!
        assertFalse(r.nextState.open)
        val eff = r.sideEffects.single() as ReviewPanelModule.Effect.SurfacePendingPart
        assertEquals("s1", eff.sessionId)
        assertEquals("out", eff.output)
    }

    @Test
    fun `actions on a closed panel are no-ops`() {
        val closed = ReviewPanelState()
        assertNull(module.reduce(closed, Action.ReviewPanelAction.Insert, ctx()))
        assertNull(module.reduce(closed, Action.ReviewPanelAction.Discard, ctx()))
        assertNull(module.reduce(closed, Action.ReviewPanelAction.MarkRefining, ctx()))
        assertNull(module.reduce(closed, Action.ReviewPanelAction.ConvertToPendingAndClose, ctx()))
    }

    // ── teardown cascade ──

    @Test
    fun `IME view hidden while open cascades ConvertToPendingAndClose`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true, reviewPanel = openState())
        val next = prev.copy(imeViewVisible = false)
        assertEquals(
            listOf(Action.ReviewPanelAction.ConvertToPendingAndClose),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `IME view hidden while closed does not cascade`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true)
        val next = prev.copy(imeViewVisible = false)
        assertTrue(module.onCrossModuleStateChange(prev, next).isEmpty())
    }

    @Test
    fun `staying visible does not cascade`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true, reviewPanel = openState())
        assertTrue(module.onCrossModuleStateChange(prev, prev).isEmpty())
    }

    @Test
    fun `module id and lens`() {
        assertEquals(ModuleId.ReviewPanel, module.id)
        val s = DictateUiState.initial().copy(reviewPanel = openState())
        assertEquals(openState(), module.read(s))
        assertEquals(ReviewPanelState(), module.write(s, ReviewPanelState()).reviewPanel)
    }
}
