package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Tests for [resolveOverlayRecordButtonText] — Variante 2a
 * (dictate-widget-integration §8.2 Chunk 2.1).
 *
 * The overlay record-button uses a single text resolver that composes
 * the two keyboard-surface resolvers (`resolveRecordButtonText` for the
 * non-pipeline path, `resolveRecordButtonTextPipeline` for the live
 * pipeline). This test pins the composition contract so a future change
 * to either keyboard resolver flows through unchanged.
 */
class TextResolversTest {

    private val strings: LayoutStrings = testLayoutStrings()
    private val baseState: DictateUiState = DictateUiState.initial()

    private fun audioFile(): File = stubAudioFile()

    @Test
    fun `overlay text Idle reflects keyboard Idle label`() {
        val s = baseState.copy(recording = RecordingState.Idle, pipeline = PipelineUiState.Idle)
        // testLayoutStrings.dictateButtonText("system") → "Dictate (system)"
        // (the state.language.effective default is "system" — confirmed
        // by the keyboard resolver's contract).
        assertEquals(
            resolveRecordButtonText(s, strings),
            resolveOverlayRecordButtonText(s, strings),
        )
    }

    @Test
    fun `overlay text Active reflects keyboard send literal`() {
        val s = baseState.copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = audioFile(), sessionId = "x",
            ),
            pipeline = PipelineUiState.Idle,
        )
        assertEquals(strings.send, resolveOverlayRecordButtonText(s, strings))
    }

    @Test
    fun `overlay text Paused reflects keyboard send literal`() {
        val s = baseState.copy(
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = audioFile(), sessionId = "x",
            ),
            pipeline = PipelineUiState.Idle,
        )
        assertEquals(strings.send, resolveOverlayRecordButtonText(s, strings))
    }

    @Test
    fun `overlay text Preparing-pipeline reflects formatPreparingLabel`() {
        // Pipeline Preparing — should defer to the keyboard SEND_MODE
        // resolver (resolveRecordButtonTextPipeline).
        val s = baseState.copy(pipeline = PipelineUiState.Preparing("sid-x"))
        assertEquals(
            resolveRecordButtonTextPipeline(s, strings),
            resolveOverlayRecordButtonText(s, strings),
        )
        // Cross-check with the formatter directly so a regression in the
        // composition still goes red.
        assertEquals(
            strings.formatPreparingLabel(false),
            resolveOverlayRecordButtonText(s, strings),
        )
    }

    @Test
    fun `overlay text Running reflects formatPipelineLabel`() {
        val s = baseState.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sid-x",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        assertEquals(
            resolveRecordButtonTextPipeline(s, strings),
            resolveOverlayRecordButtonText(s, strings),
        )
    }
}
