package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-reducer + cascade tests for [HistoryPanelModule] (ADR-0014).
 */
class HistoryPanelModuleTest {

    private val module = HistoryPanelModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial(), now = 42L)

    private fun recording(sessionId: String = "s2") =
        RecordingState.Preparing(useBluetooth = false, audioFile = File("x"), sessionId = sessionId)

    // ── reducer ──

    @Test
    fun `Open opens the panel`() {
        val r = module.reduce(HistoryPanelState(), Action.HistoryPanelAction.Open, ctx())!!
        assertTrue(r.nextState.open)
        assertTrue(r.sideEffects.isEmpty())
    }

    @Test
    fun `Open on an already-open panel is a no-op`() {
        assertNull(module.reduce(HistoryPanelState(open = true), Action.HistoryPanelAction.Open, ctx()))
    }

    @Test
    fun `Close closes the panel`() {
        val r = module.reduce(HistoryPanelState(open = true), Action.HistoryPanelAction.Close, ctx())!!
        assertFalse(r.nextState.open)
    }

    @Test
    fun `Close on a closed panel is a no-op`() {
        assertNull(module.reduce(HistoryPanelState(), Action.HistoryPanelAction.Close, ctx()))
    }

    @Test
    fun `AcknowledgeInsert emits MarkAcknowledged without changing open state`() {
        val open = HistoryPanelState(open = true)
        val r = module.reduce(open, Action.HistoryPanelAction.AcknowledgeInsert("sX"), ctx())!!
        assertEquals(open, r.nextState) // open/close orthogonal to acknowledge
        val eff = r.sideEffects.single() as HistoryPanelModule.Effect.MarkAcknowledged
        assertEquals("sX", eff.sessionId)
        assertEquals(42L, eff.at)
    }

    // ── auto-close cascade ──

    @Test
    fun `IME view hidden while open cascades Close`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true, historyPanel = HistoryPanelState(open = true))
        val next = prev.copy(imeViewVisible = false)
        assertEquals(listOf(Action.HistoryPanelAction.Close), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `recording start while open cascades Close`() {
        val prev = DictateUiState.initial().copy(
            recording = RecordingState.Idle, historyPanel = HistoryPanelState(open = true),
        )
        val next = prev.copy(recording = recording())
        assertEquals(listOf(Action.HistoryPanelAction.Close), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `review panel opening while history is open cascades Close (G2-3)`() {
        val prev = DictateUiState.initial().copy(
            reviewPanel = ReviewPanelState(), historyPanel = HistoryPanelState(open = true),
        )
        val next = prev.copy(reviewPanel = ReviewPanelState(open = true, sessionId = "s1", output = "o"))
        assertEquals(listOf(Action.HistoryPanelAction.Close), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `no cascade when panel is closed`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true, recording = RecordingState.Idle)
        val next = prev.copy(imeViewVisible = false, recording = recording())
        assertTrue(module.onCrossModuleStateChange(prev, next).isEmpty())
    }

    @Test
    fun `no cascade when nothing relevant changed`() {
        val prev = DictateUiState.initial().copy(historyPanel = HistoryPanelState(open = true))
        assertTrue(module.onCrossModuleStateChange(prev, prev).isEmpty())
    }

    @Test
    fun `module id and lens`() {
        assertEquals(ModuleId.HistoryPanel, module.id)
        val s = DictateUiState.initial().copy(historyPanel = HistoryPanelState(open = true))
        assertEquals(HistoryPanelState(open = true), module.read(s))
        assertEquals(HistoryPanelState(), module.write(s, HistoryPanelState()).historyPanel)
    }
}
