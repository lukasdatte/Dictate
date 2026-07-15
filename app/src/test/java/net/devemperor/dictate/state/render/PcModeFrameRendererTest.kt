package net.devemperor.dictate.state.render

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.FeatureToggles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PC-mode frame side-channel (§7, criterion 7): the toggle sets/clears the frame idempotently
 * and touches nothing else. Rendering order (foreground over children) is Android framework
 * behaviour, verified manually (§12.1) — this pins the state→apply logic.
 */
class PcModeFrameRendererTest {

    private val applied = mutableListOf<Boolean>()
    private val renderer = PcModeFrameRenderer { applied += it }

    private fun state(pcMode: Boolean): DictateUiState =
        DictateUiState.initial().let { it.copy(features = it.features.copy(windowsAutoSendActive = pcMode)) }

    @Test
    fun turningPcModeOn_setsTheFrameOnce() {
        renderer.onState(state(pcMode = true))
        renderer.onState(state(pcMode = true)) // idempotent — no second write

        assertEquals(listOf(true), applied)
    }

    @Test
    fun turningPcModeOff_clearsTheFrame() {
        renderer.onState(state(pcMode = true))
        renderer.onState(state(pcMode = false))

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun theInitialOffState_appliesAClearExactlyOnce() {
        renderer.onState(state(pcMode = false))
        renderer.onState(state(pcMode = false))

        assertEquals("first emit always applies, then caches", listOf(false), applied)
    }

    @Test
    fun aToggleRoundTrip_setsThenClears() {
        renderer.onState(state(pcMode = false))
        renderer.onState(state(pcMode = true))
        renderer.onState(state(pcMode = false))

        assertEquals(listOf(false, true, false), applied)
    }

    @Test
    fun reset_makesTheNextEmitApplyUnconditionally() {
        renderer.onState(state(pcMode = true))
        renderer.reset()
        renderer.onState(state(pcMode = true)) // after reset the cache is gone → re-applies

        assertEquals(listOf(true, true), applied)
    }
}
