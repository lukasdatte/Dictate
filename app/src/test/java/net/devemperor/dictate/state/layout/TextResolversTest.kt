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

    // ─── B-D-1: step-name in pipeline label ──────────────────────────

    @Test
    fun `Running with no stepHistory falls back to single-line label`() {
        // Right after StartPipeline (before the first StepStarted)
        // there is no RUNNING row in `stepHistory` → `currentStepName`
        // is null → the formatter falls back to the single-line legacy
        // shape so the button height does not flicker.
        val s = baseState.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sid-x",
                target = InsertionTarget.INPUT_CONNECTION,
                completedSteps = 0,
                totalSteps = 2,
                elapsedMs = 100L,
            ),
        )
        val text = resolveRecordButtonTextPipeline(s, strings).toString()
        // testLayoutStrings format with null step → "$done/$total$mark  ${elapsedMs}ms"
        assertEquals("0/2  100ms", text)
    }

    @Test
    fun `Running with currentStepName renders two-line label with phase on first line`() {
        // AC-D-1: live step name appears in the button label.
        val runningWithStep = PipelineUiState.Running(
            sessionId = "sid-x",
            target = InsertionTarget.INPUT_CONNECTION,
            completedSteps = 1,
            totalSteps = 2,
            elapsedMs = 8000L,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                net.devemperor.dictate.state.StepRowItem(
                    stepName = "Transcribe",
                    status = net.devemperor.dictate.state.StepStatus.RUNNING,
                    startedAtMs = 0L,
                ),
            ),
        )
        val s = baseState.copy(pipeline = runningWithStep)
        val text = resolveRecordButtonTextPipeline(s, strings).toString()
        // testLayoutStrings format with non-null step:
        //   "<phase>\n<done>/<total><mark>  <elapsedMs>ms"
        assertEquals("Transcribe\n1/2  8000ms", text)
        // Bonus: the overlay backend's composition pulls the same text.
        assertEquals(text, resolveOverlayRecordButtonText(s, strings).toString())
    }

    @Test
    fun `Running with multiple completed plus running step picks last RUNNING`() {
        // currentStepName picks `stepHistory.lastOrNull { status == RUNNING }`
        // — pin that contract so a regression in DictateUiState.kt's
        // extension property reflects here.
        val running = PipelineUiState.Running(
            sessionId = "sid-x",
            target = InsertionTarget.INPUT_CONNECTION,
            completedSteps = 1,
            totalSteps = 3,
            elapsedMs = 12000L,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                net.devemperor.dictate.state.StepRowItem(
                    stepName = "Transcribe",
                    status = net.devemperor.dictate.state.StepStatus.COMPLETED,
                    startedAtMs = 0L,
                    durationMs = 4000L,
                ),
                net.devemperor.dictate.state.StepRowItem(
                    stepName = "Reword: Casual",
                    status = net.devemperor.dictate.state.StepStatus.RUNNING,
                    startedAtMs = 4000L,
                ),
            ),
        )
        val s = baseState.copy(pipeline = running)
        val text = resolveRecordButtonTextPipeline(s, strings).toString()
        assertEquals("Reword: Casual\n1/3  12000ms", text)
    }

    @Test
    fun `Running with autoEnterActive renders the arrow marker`() {
        val running = PipelineUiState.Running(
            sessionId = "sid-x",
            target = InsertionTarget.INPUT_CONNECTION,
            autoEnterActive = true,
            completedSteps = 1,
            totalSteps = 2,
            elapsedMs = 500L,
            stepHistory = kotlinx.collections.immutable.persistentListOf(
                net.devemperor.dictate.state.StepRowItem(
                    stepName = "Format",
                    status = net.devemperor.dictate.state.StepStatus.RUNNING,
                    startedAtMs = 0L,
                ),
            ),
        )
        val s = baseState.copy(pipeline = running)
        val text = resolveRecordButtonTextPipeline(s, strings).toString()
        // testLayoutStrings includes ` ↵` when autoEnter is true.
        assertEquals("Format\n1/2 ↵  500ms", text)
    }

    // ─── B3.4 — Pause/Resume label override ──────────────────────────

    @Test
    fun `B3-4 overlay text Active + widget Visible(USER) returns pauseLabel`() {
        val s = baseState.copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = audioFile(), sessionId = "x",
            ),
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.USER
            ),
            pipeline = PipelineUiState.Idle,
        )
        assertEquals(strings.pauseLabel, resolveOverlayRecordButtonText(s, strings))
    }

    @Test
    fun `B3-4 overlay text Paused + widget Visible(PIPELINE) returns resumeLabel`() {
        val s = baseState.copy(
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = audioFile(), sessionId = "x",
            ),
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.PIPELINE
            ),
            pipeline = PipelineUiState.Idle,
        )
        assertEquals(strings.resumeLabel, resolveOverlayRecordButtonText(s, strings))
    }

    @Test
    fun `B3-4 overlay text Active + widget Hidden falls back to send label (no Pause override)`() {
        // widget == Hidden means there IS a guaranteed InputConnection;
        // the legacy StopRecordingAndSend path is still meaningful and
        // the Send-label stays. The Pause-override applies only when
        // widget is Visible.
        val s = baseState.copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = audioFile(), sessionId = "x",
            ),
            widget = net.devemperor.dictate.state.WidgetState.Hidden,
            pipeline = PipelineUiState.Idle,
        )
        assertEquals(strings.send, resolveOverlayRecordButtonText(s, strings))
    }

    @Test
    fun `B3-4 pipeline Running label wins over Pause-override (auto-enter owns the btn)`() {
        // Pipeline-Running takes precedence: the per-run auto-enter label
        // ("N/M ↵ M:SS") is what the user sees, even when widget is
        // visible and recording is Active.
        val running = PipelineUiState.Running(
            sessionId = "sid",
            target = InsertionTarget.INPUT_CONNECTION,
            completedSteps = 0,
            totalSteps = 0,
            elapsedMs = 0L,
            autoEnterActive = false,
        )
        val s = baseState.copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = audioFile(), sessionId = "x",
            ),
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.USER
            ),
            pipeline = running,
        )
        // Sanity check: the resolver did NOT return the pauseLabel —
        // it returned whatever the pipeline-resolver produces.
        val text = resolveOverlayRecordButtonText(s, strings)
        assertEquals(
            "Pipeline-Running must NOT be overridden by Pause-Toggle text",
            resolveRecordButtonTextPipeline(s, strings),
            text,
        )
    }
}
