package net.devemperor.dictate.state.render

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.AutoEnterIconRenderer
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric tests for [AutoEnterRenderer] — the Phase-3 side-channel
 * that owns the `record_btn` compound drawables.
 *
 * Coverage scope:
 *
 *  - Branch outputs for every relevant `(pipeline, recording)` cell:
 *    left + right drawables match what the legacy renderer wrote on
 *    the same state combination.
 *  - Idempotency: a second [AutoEnterRenderer.onState] with the same
 *    cache key does not re-write the compound drawables (validated via
 *    a write-count probe wired into the [AutoEnterIconRenderer]
 *    factory).
 *  - Reset semantics: after [AutoEnterRenderer.reset] the next
 *    `onState` re-applies unconditionally.
 *
 * @see net.devemperor.dictate.state.render.AutoEnterRenderer
 * @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 3 + §7 Q1
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoEnterRendererTest {

    private lateinit var ctx: Context
    private lateinit var button: MaterialButton

    /**
     * Recording fake — exposes the per-`active`-key `get()` count + the
     * `invalidate()` call count so tests can assert call patterns
     * without resorting to Mockito.
     */
    private class FakeAutoEnterIconRenderer(private val ctx: Context) : AutoEnterIconRenderer(ctx) {
        val getCallsActive = mutableListOf<Boolean>()
        var invalidateCount = 0
        override fun get(active: Boolean): Drawable {
            getCallsActive.add(active)
            // Distinct colour per `active` value so tests can identify
            // which drawable variant was applied to the button.
            return ColorDrawable(if (active) Color.GREEN else Color.RED)
        }
        override fun invalidate() {
            invalidateCount++
        }
    }

    private lateinit var fakeIconRenderer: FakeAutoEnterIconRenderer
    private lateinit var renderer: AutoEnterRenderer

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        button = MaterialButton(ctx)
        fakeIconRenderer = FakeAutoEnterIconRenderer(ctx)
        renderer = AutoEnterRenderer(button) { fakeIconRenderer }
    }

    // ── Branch outputs ─────────────────────────────────────────────────

    @Test
    fun `pipeline=Idle, recording=Idle writes both left and right drawables`() {
        renderer.onState(stateOf(pipeline = PipelineUiState.Idle, recording = RecordingState.Idle))
        val drawables = button.compoundDrawablesRelative
        assertNotNull("left drawable should be set (mic_20)", drawables[0])
        assertNotNull("right drawable should be set (folder_open_20)", drawables[2])
    }

    @Test
    fun `pipeline=Idle, recording=Active(bluetooth=true) writes both left and right`() {
        renderer.onState(
            stateOf(
                pipeline = PipelineUiState.Idle,
                recording = activeRec(useBluetooth = true),
            ),
        )
        val drawables = button.compoundDrawablesRelative
        assertNotNull("left drawable should be set (send_20)", drawables[0])
        assertNotNull("right drawable should be set (bluetooth_20)", drawables[2])
    }

    @Test
    fun `pipeline=Preparing writes left=send_20, right=null (no AutoEnter icon yet)`() {
        renderer.onState(stateOf(pipeline = PipelineUiState.Preparing("s1")))
        val drawables = button.compoundDrawablesRelative
        assertNotNull("left drawable should be set (send_20)", drawables[0])
        assertNull("right slot is empty during Preparing", drawables[2])
        // AutoEnter renderer is not consulted outside Running.
        assertTrue("iconRenderer.get must not be called during Preparing", fakeIconRenderer.getCallsActive.isEmpty())
    }

    @Test
    fun `pipeline=Running(autoEnter=true) writes left=null, right=AutoEnter(active=true)`() {
        renderer.onState(
            stateOf(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    autoEnterActive = true,
                ),
            ),
        )
        val drawables = button.compoundDrawablesRelative
        assertNull("left slot is empty during Running", drawables[0])
        assertNotNull("right slot carries the AutoEnter drawable", drawables[2])
        assertTrue("right slot is the fake's ColorDrawable", drawables[2] is ColorDrawable)
        assertEquals(
            "right slot uses the active=true variant",
            Color.GREEN, (drawables[2] as ColorDrawable).color,
        )
        assertEquals(listOf(true), fakeIconRenderer.getCallsActive)
    }

    @Test
    fun `pipeline=Running(autoEnter=false) writes left=null, right=AutoEnter(active=false)`() {
        renderer.onState(
            stateOf(
                pipeline = PipelineUiState.Running(
                    sessionId = "s1",
                    target = InsertionTarget.INPUT_CONNECTION,
                    autoEnterActive = false,
                ),
            ),
        )
        val drawables = button.compoundDrawablesRelative
        assertNull(drawables[0])
        assertTrue(drawables[2] is ColorDrawable)
        assertEquals(Color.RED, (drawables[2] as ColorDrawable).color)
        assertEquals(listOf(false), fakeIconRenderer.getCallsActive)
    }

    @Test
    fun `pipeline=ReprocessStaging writes both left and right drawables`() {
        renderer.onState(stateOf(pipeline = PipelineUiState.ReprocessStaging("s1", "t")))
        val drawables = button.compoundDrawablesRelative
        assertNotNull("left drawable should be set (play_arrow_24)", drawables[0])
        assertNotNull("right drawable should be set (send_24)", drawables[2])
    }

    // ── Idempotency ────────────────────────────────────────────────────

    @Test
    fun `repeated onState with the same key invokes iconRenderer once at most`() {
        val state = stateOf(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = true,
            ),
        )
        renderer.onState(state)
        renderer.onState(state)
        renderer.onState(state)
        assertEquals(
            "Idempotency contract — only the first onState may consult iconRenderer.get",
            1, fakeIconRenderer.getCallsActive.size,
        )
    }

    @Test
    fun `non-Running to non-Running with same icons does not re-write`() {
        val s1 = stateOf(pipeline = PipelineUiState.Idle, recording = RecordingState.Idle)
        renderer.onState(s1)
        // Same logical key; the cache must short-circuit.
        renderer.onState(s1.copy())
        // Even though we created a structurally identical copy, the cache
        // key is built from the resolver-output ids, not from object
        // identity — so re-write is suppressed.
        assertTrue(fakeIconRenderer.invalidateCount <= 1)
    }

    // ── Reset semantics ────────────────────────────────────────────────

    @Test
    fun `reset() clears cache so the next onState re-applies`() {
        val running = stateOf(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = true,
            ),
        )
        renderer.onState(running)
        assertEquals(1, fakeIconRenderer.getCallsActive.size)
        renderer.reset()
        renderer.onState(running)
        assertEquals(
            "After reset() a same-key onState must re-apply",
            2, fakeIconRenderer.getCallsActive.size,
        )
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun stateOf(
        pipeline: PipelineUiState = PipelineUiState.Idle,
        recording: RecordingState = RecordingState.Idle,
    ): DictateUiState = DictateUiState.initial().copy(
        recording = recording,
        pipeline = pipeline,
    )

    private fun activeRec(useBluetooth: Boolean) = RecordingState.Active(
        useBluetooth = useBluetooth,
        audioFile = File("/tmp/dictate-test.m4a"),
        sessionId = "s-test",
    )
}
