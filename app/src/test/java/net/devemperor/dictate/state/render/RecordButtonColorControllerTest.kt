package net.devemperor.dictate.state.render

import android.content.Context
import android.graphics.Color
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the [RecordButtonColorController] side-channel
 * introduced in Phase 5.A of
 * `2026-05-21 - dictate-render-cutover-completion-vol2`.
 *
 * Coverage:
 *  - Red on `Running.hasFailure == true`, white everywhere else.
 *  - Idempotency: repeated `onState` with the same `hasFailure` flag
 *    does not re-write `setTextColor`.
 *  - `reset()` re-arms the next `onState` to re-apply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordButtonColorControllerTest {

    private val failureColor = 0xFFF44336.toInt()
    private val defaultColor = Color.WHITE

    private lateinit var ctx: Context
    private lateinit var button: MaterialButton
    private lateinit var controller: RecordButtonColorController

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        button = MaterialButton(ctx)
        controller = RecordButtonColorController(button, failureColor, defaultColor)
    }

    private fun currentTextColor(): Int = button.currentTextColor

    @Test
    fun `default color on Idle`() {
        controller.onState(DictateUiState.initial().copy(pipeline = PipelineUiState.Idle))
        assertEquals(defaultColor, currentTextColor())
    }

    @Test
    fun `default color on Preparing`() {
        controller.onState(
            DictateUiState.initial().copy(pipeline = PipelineUiState.Preparing("s1")),
        )
        assertEquals(defaultColor, currentTextColor())
    }

    @Test
    fun `default color on Running without failure`() {
        controller.onState(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    hasFailure = false,
                ),
            ),
        )
        assertEquals(defaultColor, currentTextColor())
    }

    @Test
    fun `failure color on Running with hasFailure=true`() {
        controller.onState(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    hasFailure = true,
                ),
            ),
        )
        assertEquals(failureColor, currentTextColor())
    }

    @Test
    fun `flip from failure back to default when pipeline ends`() {
        // Start in failure
        controller.onState(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    hasFailure = true,
                ),
            ),
        )
        assertEquals(failureColor, currentTextColor())
        // Pipeline ends → Idle → color flips back to default
        controller.onState(DictateUiState.initial().copy(pipeline = PipelineUiState.Idle))
        assertEquals(defaultColor, currentTextColor())
    }

    @Test
    fun `reset arms the next onState to re-apply`() {
        controller.onState(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    hasFailure = true,
                ),
            ),
        )
        assertEquals(failureColor, currentTextColor())
        // Mutate the button text colour directly — simulating an
        // external write that we want the controller to overwrite on
        // the next state-emit after reset().
        button.setTextColor(Color.BLUE)
        // Without reset, the cache short-circuits the same-key apply.
        controller.onState(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    hasFailure = true,
                ),
            ),
        )
        assertEquals("Idempotent path must not overwrite", Color.BLUE, currentTextColor())
        // After reset the cache is cleared; the next onState re-applies.
        controller.reset()
        controller.onState(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    hasFailure = true,
                ),
            ),
        )
        assertEquals(failureColor, currentTextColor())
    }
}
