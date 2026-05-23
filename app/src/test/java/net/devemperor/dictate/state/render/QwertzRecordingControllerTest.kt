package net.devemperor.dictate.state.render

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-DEL (C10-C3) — unit tests for [QwertzRecordingController], the
 * relocated `RecordingUiController` QWERTZ rec-button + prompts-visualizer
 * (G9 BLEIBT, A3 option-a, Spec 2 §9.4). Byte-equivalent to the still-live
 * half of the deleted `RecordingUiController`; the recording-axis
 * Main-button side-effects are NOT relocated (dead on the bound path —
 * collapsed onto `RecordingAnimationController` + the catalog resolvers).
 *
 * Quality-Gate K-4 exception (the controller mutates `MaterialButton`
 * icon/text/padding). Quality-Gate K-1: no Mockito — real controller +
 * a handwritten `MaterialButton` + a lambda provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QwertzRecordingControllerTest {

    private lateinit var themed: Context
    private lateinit var recBtn: MaterialButton
    private lateinit var promptRecBtn: MaterialButton
    private lateinit var promptPauseBtn: MaterialButton
    private var pauseToggled = 0
    private var sent = 0
    private lateinit var controller: QwertzRecordingController

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themed = ContextThemeWrapper(app, R.style.Theme_Dictate)
        recBtn = MaterialButton(themed)
        promptRecBtn = MaterialButton(themed)
        promptPauseBtn = MaterialButton(themed)
        pauseToggled = 0
        sent = 0
        controller = QwertzRecordingController(
            context = themed,
            qwertzRecButtonProvider = { recBtn },
            promptRecButton = promptRecBtn,
            promptPauseButton = promptPauseBtn,
            onPauseToggle = { pauseToggled++ },
            onSend = { sent++ },
        )
    }

    @Test
    fun updateQwertzRecButton_active_setsSendIcon_inactive_setsMicIcon() {
        controller.updateQwertzRecButton(true)
        assertNotNull("Active QWERTZ rec-button must have a (send) icon", recBtn.icon)

        controller.updateQwertzRecButton(false)
        // Inactive restores the mic icon + clears the text.
        assertNotNull("Inactive QWERTZ rec-button must have a (mic) icon", recBtn.icon)
        assertEquals("", recBtn.text.toString())
    }

    @Test
    fun enterPipelineDisplay_andUpdateTimer_paintCounterAndTimer() {
        // Phase 5.B (Vol 2): switched to the orchestrator's PipelineUiState.Running
        // (state.PipelineUiState). The Running data class no longer carries
        // currentStepName -- that field is derived from stepHistory and not
        // read by the controller's enterPipelineDisplay / updatePipelineTimer
        // path (which uses totalSteps + completedSteps + autoEnterActive only).
        val running = PipelineUiState.Running(
            sessionId = "test-sid",
            target = InsertionTarget.INPUT_CONNECTION,
            autoEnterActive = true,
            completedSteps = 1,
            totalSteps = 3,
        )
        controller.enterPipelineDisplay(running)
        // enterPipelineDisplay seeds an initial timer text (elapsed 0).
        assertTrue(
            "QWERTZ rec-button must show the n/m counter, got '${recBtn.text}'",
            recBtn.text.toString().contains("1/3"),
        )
        controller.updatePipelineTimer(running, 65_000L)
        val txt = recBtn.text.toString()
        assertTrue("counter present", txt.contains("1/3"))
        assertTrue(
            "autoEnterActive must render the ↵ indicator (U+21B5), got '$txt'",
            txt.contains("↵"),
        )
    }

    @Test
    fun onTimerTick_setsTwoLineQwertzButton_whenNoVisualizerHost() {
        // No prompt-rec button host needed for the QWERTZ-button half.
        controller.onTimerTick(125_000L) // 02:05
        assertEquals("02:05", recBtn.text.toString())
        assertNotNull(recBtn.icon)
    }

    @Test
    fun promptRecordingControls_activate_reset_areByteEquivalentToLegacy() {
        controller.activatePromptRecordingControls()
        assertNotNull(
            "Active prompts-rec button must host the visualizer drawable",
            promptRecBtn.foreground,
        )
        // Tapping the activated prompt-rec button fires onSend.
        promptRecBtn.performClick()
        assertEquals(1, sent)
        // Tapping the activated prompt-pause button fires onPauseToggle.
        promptPauseBtn.performClick()
        assertEquals(1, pauseToggled)

        controller.resetPromptRecordingControls()
        assertNull("reset must clear the prompt-rec foreground", promptRecBtn.foreground)
        assertEquals("", promptRecBtn.text.toString())
    }

    @Test
    fun nullRecButtonProvider_isASafeNoOp() {
        val safe = QwertzRecordingController(
            context = themed,
            qwertzRecButtonProvider = { null },
        )
        // Must not throw when the QWERTZ view is not inflated.
        safe.updateQwertzRecButton(true)
        safe.updatePipelineTimer(
            PipelineUiState.Running(
                sessionId = "test-sid",
                target = InsertionTarget.INPUT_CONNECTION,
                totalSteps = 1,
                completedSteps = 0,
            ),
            0L,
        )
        safe.onTimerTick(1000L)
        safe.onAmplitude(0.5f)
    }
}
