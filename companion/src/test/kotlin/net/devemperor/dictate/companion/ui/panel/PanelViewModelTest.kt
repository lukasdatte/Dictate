package net.devemperor.dictate.companion.ui.panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.companion.pipeline.DesktopUiState
import net.devemperor.dictate.companion.pipeline.DictationPhase
import net.devemperor.dictate.companion.pipeline.RecordingUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The panel's presentation state machine (desktop-host.md §7.4): buffer shift,
 * idle/active/paused transitions, timer format — all synchronous math against a [MutableClock], no
 * coroutine machinery (the flow glue in `start()` is trivial and not under test here).
 */
class PanelViewModelTest {

    private val clock = MutableClock()
    private val dictation = MutableStateFlow(DesktopUiState())

    private val viewModel = PanelViewModel(
        dictation = dictation,
        amplitudes = emptyFlow(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = clock,
    )

    private fun recordingState(paused: Boolean = false) = DesktopUiState(
        phase = DictationPhase.RECORDING,
        panelVisible = true,
        activeSessionId = "s-1",
        recording = RecordingUi.Active(paused = paused, elapsedMillis = 0),
    )

    @Test
    fun initialState_isColdBufferAndZeroTimer() {
        assertEquals(RecordingBarDesign.emptyLevels(), viewModel.state.value.levels)
        assertEquals(PanelUi.ZERO_TIMER, viewModel.state.value.timerText)
        assertEquals(0f, viewModel.state.value.glowLevel, 0f)
    }

    @Test
    fun amplitude_pushesIntoTheRingBuffer_newestRight_andDrivesGlow() {
        viewModel.onAmplitude(0.5f)
        viewModel.onAmplitude(0.9f)

        val levels = viewModel.state.value.levels
        assertEquals(RecordingBarDesign.BAR_COUNT, levels.size)
        assertEquals(0.9f, levels[29], 1e-4f)
        assertEquals(0.5f, levels[28], 1e-4f)
        assertEquals(0.9f, viewModel.state.value.glowLevel, 1e-4f)
    }

    @Test
    fun startingATake_clearsBufferAndTimer() {
        viewModel.onAmplitude(0.8f)
        clock.advance(5_000)

        viewModel.onDictationState(recordingState())

        assertEquals("cold buffer on a fresh take", RecordingBarDesign.emptyLevels(), viewModel.state.value.levels)
        assertEquals(PanelUi.ZERO_TIMER, viewModel.state.value.timerText)
    }

    @Test
    fun tick_whileRecording_advancesTheTimer() {
        viewModel.onDictationState(recordingState())
        clock.advance(65_000)
        viewModel.onTick()
        assertEquals("01:05", viewModel.state.value.timerText)
    }

    @Test
    fun pause_freezesTheTimer_resumeContinuesWhereItStopped() {
        viewModel.onDictationState(recordingState())
        clock.advance(10_000)
        viewModel.onDictationState(recordingState(paused = true))

        clock.advance(60_000) // a long think — paused time must not count
        viewModel.onTick()
        assertEquals(PanelUi.ZERO_TIMER, viewModel.state.value.timerText) // no tick while paused updates nothing…
        viewModel.onDictationState(recordingState(paused = false))
        clock.advance(5_000)
        viewModel.onTick()
        assertEquals("recorded 10 s + 5 s, not the 60 s pause", "00:15", viewModel.state.value.timerText)
    }

    @Test
    fun stoppingTheMic_freezesTimerAndDropsGlow_keepsTheWaveform() {
        viewModel.onDictationState(recordingState())
        viewModel.onAmplitude(0.7f)
        clock.advance(3_000)

        viewModel.onDictationState(
            DesktopUiState(phase = DictationPhase.TRANSCRIBING, panelVisible = true, activeSessionId = "s-1")
        )
        clock.advance(30_000)
        viewModel.onTick()

        assertEquals("timer frozen once the mic stopped", PanelUi.ZERO_TIMER, viewModel.state.value.timerText)
        assertEquals(0f, viewModel.state.value.glowLevel, 0f)
        assertEquals("last waveform stays visible behind the pipeline phases", 0.7f, viewModel.state.value.levels[29], 1e-4f)
    }

    @Test
    fun secondTake_startsFromZeroAgain() {
        viewModel.onDictationState(recordingState())
        clock.advance(20_000)
        viewModel.onTick()
        viewModel.onDictationState(DesktopUiState()) // take finished, back to idle

        viewModel.onDictationState(recordingState())
        clock.advance(1_000)
        viewModel.onTick()

        assertEquals("00:01", viewModel.state.value.timerText)
    }
}
