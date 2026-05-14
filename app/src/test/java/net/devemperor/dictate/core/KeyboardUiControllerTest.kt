package net.devemperor.dictate.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-19: unit tests for [KeyboardUiController.applyRecordButtonForRecording].
 *
 * The method is the central resolver for the record-button recording axis
 * (~50 LOC). The §9.5 race the plan eliminates lives in this branch
 * structure:
 *  - (a) pipeline-state guard (defers to refreshRecordButtonFromState),
 *  - (b) 4-branch `when` over [RecordingState] (Idle / Preparing / Active /
 *    Paused),
 *  - (c) inner Active.useBluetooth split.
 *
 * Quality-Gate K-4 exception. The method mutates `MaterialButton` view
 * properties (`text`, `isEnabled`, compound-drawable resources). The
 * production code uses `setCompoundDrawablesRelativeWithIntrinsicBounds`
 * with resource ids that Robolectric resolves from the merged
 * `app/src/main/res/` manifest.
 *
 * Quality-Gate K-1: no Mockito — real [KeyboardStateManager] instance
 * driven by handwritten lambda flags, no spy/mock framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardUiControllerTest {

    private lateinit var themed: Context
    private lateinit var recordButton: MaterialButton
    private lateinit var controller: KeyboardUiController

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        themed = ContextThemeWrapper(app, R.style.Theme_Dictate)

        recordButton = MaterialButton(themed)

        val views = KeyboardUiController.PipelineViews(
            pipelineStepsContainer = LinearLayout(themed),
            pipelineScrollView = ScrollView(themed),
            recordButton = recordButton,
            infoCl = View(themed),
            layoutInflater = LayoutInflater.from(themed),
            mainHandler = Handler(Looper.getMainLooper()),
        )

        controller = KeyboardUiController(
            views = views,
            stateManager = buildMinimalStateManager(),
            dictateButtonTextProvider = { "Record" },
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // (a) Pipeline-state guard — non-Idle pipeline defers to refresh
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun pipeline_preparing_defers_to_refreshFromState() {
        // Enter Preparing via the public API so the controller's internal
        // state matches what the resolver checks. Preparing branch in
        // refreshRecordButtonFromState disables the button + sets the
        // "Sending…" text — distinct from any RecordingState branch.
        controller.preparePipeline()
        assertFalse(
            "Preparing branch should disable the record button (pipeline owns it)",
            recordButton.isEnabled,
        )

        // Act: invoke applyRecordButtonForRecording with a RecordingState
        // that would otherwise enable the button (Idle, Active). The guard
        // must defer to the pipeline-axis paint instead.
        controller.applyRecordButtonForRecording(RecordingState.Idle)

        assertFalse(
            "Pipeline-guard must NOT let the recording-axis Idle branch enable the button while pipeline is Preparing",
            recordButton.isEnabled,
        )

        controller.applyRecordButtonForRecording(RecordingState.Active(useBluetooth = false))

        assertFalse(
            "Pipeline-guard must NOT let the recording-axis Active branch enable the button while pipeline is Preparing",
            recordButton.isEnabled,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // (b) Idle pipeline → recording-state branches
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun recording_idle_when_pipeline_idle_paintsIdleLabel() {
        controller.applyRecordButtonForRecording(RecordingState.Idle)
        assertEquals(
            "Idle branch must paint the dictate-button label from the provider",
            "Record",
            recordButton.text.toString(),
        )
        assertTrue("Button must be enabled in Idle state", recordButton.isEnabled)
    }

    @Test
    fun recording_preparing_when_pipeline_idle_disables_button() {
        controller.applyRecordButtonForRecording(RecordingState.Preparing(useBluetooth = false))
        assertFalse(
            "Preparing must disable the record button so a double-tap during SCO bring-up is suppressed",
            recordButton.isEnabled,
        )
    }

    @Test
    fun recording_active_useBluetooth_true_when_pipeline_idle() {
        controller.applyRecordButtonForRecording(RecordingState.Active(useBluetooth = true))
        assertTrue("Active state must enable the button (Bluetooth=true)", recordButton.isEnabled)
        // The branch calls setText(R.string.dictate_send). Robolectric
        // resolves the string template; we assert the prefix to avoid
        // tying the test to the exact format-arg presentation.
        assertTrue(
            "Active branch must paint the send label, got '${recordButton.text}'",
            recordButton.text.toString().startsWith("Send"),
        )
    }

    @Test
    fun recording_active_useBluetooth_false_when_pipeline_idle() {
        controller.applyRecordButtonForRecording(RecordingState.Active(useBluetooth = false))
        assertTrue("Active state must enable the button (Bluetooth=false)", recordButton.isEnabled)
        assertTrue(
            "Active branch must paint the send label, got '${recordButton.text}'",
            recordButton.text.toString().startsWith("Send"),
        )
    }

    @Test
    fun recording_paused_when_pipeline_idle_keepsActiveButtonState() {
        // Enter Active first to establish the carry-over baseline.
        controller.applyRecordButtonForRecording(RecordingState.Active(useBluetooth = false))
        val textBeforePaused = recordButton.text.toString()
        val enabledBeforePaused = recordButton.isEnabled

        // Paused must NOT mutate text or isEnabled — Active values carry
        // over until resume → Active or stop → Idle (matches the previous
        // RecordingUiController.applyPausedState behaviour, which only
        // touched the pause-button foreground).
        controller.applyRecordButtonForRecording(RecordingState.Paused)

        assertEquals(
            "Paused branch must not change the record-button text",
            textBeforePaused,
            recordButton.text.toString(),
        )
        assertEquals(
            "Paused branch must not change the record-button enabled flag",
            enabledBeforePaused,
            recordButton.isEnabled,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Test infrastructure
    // ──────────────────────────────────────────────────────────────────

    /**
     * Builds a [KeyboardStateManager] with stub views — sufficient for
     * `refresh()` to no-op safely. The test method under exercise calls
     * `refreshRecordButtonFromState()` (no stateManager touch) and
     * `updatePipelineState()` (one stateManager.refresh) only when state
     * actually changes. The stub avoids needing the full keyboard layout
     * inflated.
     */
    private fun buildMinimalStateManager(): KeyboardStateManager {
        val mainButtons = LinearLayout(themed) // ViewGroup satisfies the typed slot.
        val views = KeyboardViews(
            mainButtonsClTyped = mainButtons,
            editButtonsLl = ConstraintLayout(themed),
            promptsCl = ConstraintLayout(themed),
            emojiPickerCl = ConstraintLayout(themed),
            qwertzContainer = FrameLayout(themed),
            overlayCharactersLl = LinearLayout(themed),
            pauseButton = View(themed),
            trashButton = View(themed),
            promptRecordingControlsLl = null,
            promptTrashBtn = null,
            promptsRv = RecyclerView(themed),
            pipelineProgressLl = null,
            actionRow = ConstraintLayout(themed),
            inputRow = ConstraintLayout(themed),
            recordPulseLayout = View(themed),
            spaceButton = MaterialButton(themed),
            backspaceButton = MaterialButton(themed),
            enterButton = MaterialButton(themed),
            resendButton = MaterialButton(themed),
            audioFocusButtonInRow = MaterialButton(themed),
        )
        return KeyboardStateManager(
            views = views,
            isRecording = { false },
            isPaused = { false },
            isPipelineRunning = { false },
            isRewordingEnabled = { false },
            onKeepScreenAwakeChanged = { /* no-op */ },
            isPipelineProgressVisible = { false },
        )
    }
}
