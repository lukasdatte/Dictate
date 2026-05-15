package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.testLayoutStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [PromptVisibilityController].
 *
 * Covers each row of the truth-table from the class KDoc plus the
 * `pipelineProgress`-vs-`promptsRv` substitution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptVisibilityControllerTest {

    private lateinit var promptsContainer: View
    private lateinit var promptsRv: View
    private lateinit var pipelineProgress: View
    private lateinit var qwertzRecControls: View
    private lateinit var controller: PromptVisibilityController
    private val catalog = LayoutCatalog(testLayoutStrings())

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        promptsContainer = FrameLayout(ctx)
        promptsRv = FrameLayout(ctx)
        pipelineProgress = FrameLayout(ctx)
        qwertzRecControls = FrameLayout(ctx)
        controller = PromptVisibilityController(
            PromptVisibilityViews(
                promptsContainer = promptsContainer,
                promptsRecyclerView = promptsRv,
                pipelineProgressView = pipelineProgress,
                qwertzRecordingControls = qwertzRecControls,
            )
        )
        controller.attach { /* */ }
    }

    @Test
    fun `backendType is null (consume every mode)`() {
        assertNull(controller.backendType)
    }

    @Test
    fun `smallMode hides the prompt container`() {
        val state = DictateUiState.initial().copy(
            layout = DictateUiState.initial().layout.copy(smallMode = true),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.GONE, promptsContainer.visibility)
    }

    @Test
    fun `emoji content-area hides prompts (small-mode irrelevant)`() {
        val state = DictateUiState.initial().copy(
            layout = DictateUiState.initial().layout.copy(contentArea = ContentArea.EMOJI_PICKER),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.GONE, promptsContainer.visibility)
    }

    @Test
    fun `active recording shows prompts`() {
        val state = DictateUiState.initial().copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x"), sessionId = "sid-test"),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, promptsContainer.visibility)
    }

    @Test
    fun `pipeline preparing shows prompts (upload phase keeps list)`() {
        val state = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Preparing(sessionId = "s"),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, promptsContainer.visibility)
    }

    @Test
    fun `rewordingEnabled with idle state shows prompts`() {
        val state = DictateUiState.initial().copy(
            features = DictateUiState.initial().features.copy(rewordingEnabled = true),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, promptsContainer.visibility)
    }

    @Test
    fun `rewordingDisabled with idle state hides prompts`() {
        val state = DictateUiState.initial().copy(
            features = DictateUiState.initial().features.copy(rewordingEnabled = false),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.GONE, promptsContainer.visibility)
    }

    @Test
    fun `running pipeline swaps prompts list for progress`() {
        val state = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, promptsContainer.visibility)
        assertEquals(View.GONE, promptsRv.visibility)
        assertEquals(View.VISIBLE, pipelineProgress.visibility)
    }

    @Test
    fun `reprocess staging keeps the recycler (editable queue), not progress`() {
        val state = DictateUiState.initial().copy(
            pipeline = PipelineUiState.ReprocessStaging(sessionId = "s", transcript = "hi"),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, promptsContainer.visibility)
        assertEquals(View.VISIBLE, promptsRv.visibility)
        assertEquals(View.GONE, pipelineProgress.visibility)
    }

    @Test
    fun `qwertz recording controls visible only when active in qwertz area`() {
        val state = DictateUiState.initial().copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x"), sessionId = "sid-test"),
            layout = DictateUiState.initial().layout.copy(contentArea = ContentArea.QWERTZ),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, qwertzRecControls.visibility)
    }

    @Test
    fun `qwertz recording controls hidden during running pipeline`() {
        val state = DictateUiState.initial().copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x"), sessionId = "sid-test"),
            layout = DictateUiState.initial().layout.copy(contentArea = ContentArea.QWERTZ),
            pipeline = PipelineUiState.Running(
                sessionId = "s",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        // Progress list replaces the controls.
        assertEquals(View.GONE, qwertzRecControls.visibility)
    }

    @Test
    fun `pipeline idle plus rewording off hides prompts but keeps recycler visible`() {
        // B4-VAL F-34d: covers the truth-table "no-pipeline" branch with
        // rewordingEnabled=false. Recycler stays VISIBLE (the controller
        // only flips it for the `Running` swap-to-progress case) — that's
        // intentional so a future flip into running has nothing to
        // re-attach.
        val state = DictateUiState.initial().copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            features = DictateUiState.initial().features.copy(rewordingEnabled = false),
        )
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.GONE, promptsContainer.visibility)
        assertEquals(View.VISIBLE, promptsRv.visibility)
        assertEquals(View.GONE, pipelineProgress.visibility)
    }

    @Test
    fun `nullable views are safely skipped`() {
        val controllerNoViews = PromptVisibilityController(
            PromptVisibilityViews(
                promptsContainer = null,
                promptsRecyclerView = null,
                pipelineProgressView = null,
                qwertzRecordingControls = null,
            )
        )
        controllerNoViews.attach { /* */ }
        // No crash expected.
        controllerNoViews.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
    }
}
