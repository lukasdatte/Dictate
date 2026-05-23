package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [WidgetModule]'s W1-W8 transitions (B3.2 / ADR-0008
 * §"Surface-Axes", plan §3).
 *
 * Each test asserts one row of the transition table — both the
 * positive case (the trigger fires the expected next-state) and the
 * negative cases (other states return null / stay sticky). The cross-
 * module observer (`onCrossModuleStateChange`) gets its own block at
 * the bottom for W6 + W8.
 *
 * **Why a separate file from [ViewModeModuleTest]:** during the B3.2
 * — B3.5 migration both modules co-exist; keeping the new test surface
 * isolated makes the eventual `git rm ViewModeModuleTest.kt` in B3.5 a
 * single drop with no merge work.
 */
class WidgetModuleTest {

    private val module = WidgetModule
    private val testFile = File("/tmp/widget-test.m4a")

    private fun ctx(state: DictateUiState = DictateUiState.initial()) =
        ReducerContext(global = state, now = 1_000_000L)

    private fun activeRecording(): RecordingState.Active = RecordingState.Active(
        useBluetooth = false, audioFile = testFile, sessionId = "sid-a",
    )

    private fun pausedRecording(): RecordingState.Paused = RecordingState.Paused(
        useBluetooth = false, audioFile = testFile, sessionId = "sid-p",
    )

    private fun preparingRecording(): RecordingState.Preparing = RecordingState.Preparing(
        useBluetooth = false, audioFile = testFile, sessionId = "sid-pp",
    )

    // ─── W1: ToggleWidget from Hidden ────────────────────────────────

    @Test
    fun `W1 ToggleWidget from Hidden becomes Visible USER`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        val r = module.reduce(s, Action.WidgetAction.ToggleWidget, ctx())
        assertNotNull(r)
        assertEquals(
            WidgetState.Visible(WidgetOrigin.USER),
            r!!.nextState.widget,
        )
        assertTrue(r.nextState.imeViewVisible)
        assertTrue(r.sideEffects.isEmpty())
    }

    @Test
    fun `W1 ToggleWidget from already-Visible USER returns null (use CloseWidget instead)`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        assertNull(module.reduce(s, Action.WidgetAction.ToggleWidget, ctx()))
    }

    @Test
    fun `W1 ToggleWidget from already-Visible PIPELINE returns null`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.PIPELINE),
            imeViewVisible = true,
        )
        assertNull(module.reduce(s, Action.WidgetAction.ToggleWidget, ctx()))
    }

    // ─── W2: CloseWidget from Visible ─────────────────────────────────

    @Test
    fun `W2 CloseWidget WIDGET_BUTTON + recording Active emits Hidden + cascade with Pause`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        val globalWithActive = DictateUiState.initial().copy(recording = activeRecording())
        val r = module.reduce(
            s,
            Action.WidgetAction.CloseWidget(WidgetCloseSource.WIDGET_BUTTON),
            ctx(globalWithActive),
        )
        assertNotNull(r)
        assertEquals(WidgetState.Hidden, r!!.nextState.widget)
        assertEquals(1, r.sideEffects.size)
        val cascade = r.sideEffects[0] as WidgetModule.Effect.DispatchCloseWidgetCascade
        assertTrue(
            "WIDGET_BUTTON close + Active recording must trigger PauseRecording",
            cascade.shouldPauseRecording,
        )
    }

    @Test
    fun `W2 CloseWidget KEYBOARD_TOGGLE + recording Active emits Hidden + cascade WITHOUT Pause`() {
        // 2026-05-22 — closing via the edit-bar toggle keeps the IME-View
        // on screen, so the recording must keep running (user-req).
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        val globalWithActive = DictateUiState.initial().copy(recording = activeRecording())
        val r = module.reduce(
            s,
            Action.WidgetAction.CloseWidget(WidgetCloseSource.KEYBOARD_TOGGLE),
            ctx(globalWithActive),
        )
        assertNotNull(r)
        assertEquals(WidgetState.Hidden, r!!.nextState.widget)
        val cascade = r.sideEffects[0] as WidgetModule.Effect.DispatchCloseWidgetCascade
        assertEquals(
            "KEYBOARD_TOGGLE close must NOT pause — recording keeps running",
            false, cascade.shouldPauseRecording,
        )
    }

    @Test
    fun `W2 CloseWidget WIDGET_BUTTON + recording Paused emits Hidden + cascade WITHOUT Pause`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.PIPELINE),
            imeViewVisible = true,
        )
        val globalWithPaused = DictateUiState.initial().copy(recording = pausedRecording())
        val r = module.reduce(
            s,
            Action.WidgetAction.CloseWidget(WidgetCloseSource.WIDGET_BUTTON),
            ctx(globalWithPaused),
        )
        assertNotNull(r)
        assertEquals(WidgetState.Hidden, r!!.nextState.widget)
        val cascade = r.sideEffects[0] as WidgetModule.Effect.DispatchCloseWidgetCascade
        assertEquals(
            "Already-paused recording is not pause-able again — cascade must not emit PauseRecording",
            false, cascade.shouldPauseRecording,
        )
    }

    @Test
    fun `W2 CloseWidget from Hidden is rejected`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        assertNull(
            module.reduce(
                s,
                Action.WidgetAction.CloseWidget(WidgetCloseSource.WIDGET_BUTTON),
                ctx(),
            ),
        )
    }

    // ─── W3: OnImeViewHidden auto-show ────────────────────────────────

    @Test
    fun `W3 OnImeViewHidden auto-shows PIPELINE widget when recording Active and not suppressed`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        val global = DictateUiState.initial().copy(recording = activeRecording())
        val r = module.reduce(s, Action.WidgetAction.OnImeViewHidden, ctx(global))
        assertNotNull(r)
        assertEquals(
            WidgetState.Visible(WidgetOrigin.PIPELINE),
            r!!.nextState.widget,
        )
        assertEquals(false, r.nextState.imeViewVisible)
    }

    @Test
    fun `W3 OnImeViewHidden auto-shows when pipeline non-Idle`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        val global = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Preparing(sessionId = "sid-pipe"),
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewHidden, ctx(global))
        assertNotNull(r)
        assertEquals(
            WidgetState.Visible(WidgetOrigin.PIPELINE),
            r!!.nextState.widget,
        )
    }

    @Test
    fun `W3 sticky-widget refactor — OnImeViewHidden ignores suppress-bit (auto-show wins)`() {
        // 2026-05-23: the suppress-bit no longer gates W3. The pre-
        // refactor expectation ("widget stays Hidden when suppress-bit
        // is set, because the user just closed it") contradicts the
        // new "auto-open whenever IME hides + recording active, only
        // manual close ever closes" contract. Suppress-bit is now an
        // effectively dead axis (still written by W2, no reader).
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        val global = DictateUiState.initial().copy(
            recording = activeRecording(),
            overlay = OverlayState(suppressAutoOverlayUntilNextSession = true),
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewHidden, ctx(global))
        assertNotNull(r)
        assertEquals(
            "Sticky-widget: suppress-bit no longer blocks W3 auto-show",
            WidgetState.Visible(WidgetOrigin.PIPELINE),
            r!!.nextState.widget,
        )
        assertEquals(false, r.nextState.imeViewVisible)
    }

    @Test
    fun `W3 OnImeViewHidden does NOT auto-show when recording Idle and pipeline Idle`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewHidden, ctx())
        assertNotNull(r)
        assertEquals(WidgetState.Hidden, r!!.nextState.widget)
        assertEquals(false, r.nextState.imeViewVisible)
    }

    @Test
    fun `W3 OnImeViewHidden keeps USER widget visible (already Visible — no auto-toggle)`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewHidden, ctx())
        assertNotNull(r)
        assertEquals(
            "Sticky USER widget must survive IME-hide",
            WidgetState.Visible(WidgetOrigin.USER),
            r!!.nextState.widget,
        )
    }

    // ─── W4 / W5: OnImeViewShown ──────────────────────────────────────
    //
    // 2026-05-23 sticky-widget refactor: W4 no longer auto-closes a
    // PIPELINE widget — once the surface is up the user owns the close
    // decision, regardless of origin. Both origins now follow the W5
    // sticky rule. See `WidgetModule` KDoc §"Sticky-widget lifecycle".

    @Test
    fun `W4 sticky — OnImeViewShown keeps PIPELINE widget visible (no auto-release)`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.PIPELINE),
            imeViewVisible = false,
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewShown, ctx())
        assertNotNull(r)
        assertEquals(
            "Sticky-widget refactor: PIPELINE widget must survive IME-show",
            WidgetState.Visible(WidgetOrigin.PIPELINE),
            r!!.nextState.widget,
        )
        assertEquals(true, r.nextState.imeViewVisible)
    }

    @Test
    fun `W5 OnImeViewShown keeps USER widget visible (sticky)`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = false,
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewShown, ctx())
        assertNotNull(r)
        assertEquals(
            WidgetState.Visible(WidgetOrigin.USER),
            r!!.nextState.widget,
        )
        assertEquals(true, r.nextState.imeViewVisible)
    }

    @Test
    fun `OnImeViewShown from Hidden flips imeViewVisible without touching widget`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = false,
        )
        val r = module.reduce(s, Action.WidgetAction.OnImeViewShown, ctx())
        assertNotNull(r)
        assertEquals(WidgetState.Hidden, r!!.nextState.widget)
        assertEquals(true, r.nextState.imeViewVisible)
    }

    // ─── W6: OnPipelineDone is a no-op (sticky-widget refactor) ───────
    //
    // 2026-05-23: pipeline-done used to auto-close `Visible(PIPELINE)`.
    // The reducer arm is retained as a no-op for compile-compat; the
    // cross-module observer no longer emits the action (covered below).

    @Test
    fun `W6 OnPipelineDone leaves PIPELINE widget sticky (no auto-close)`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.PIPELINE),
            imeViewVisible = false,
        )
        assertNull(
            "Sticky-widget refactor: OnPipelineDone must not auto-close — reducer is a no-op",
            module.reduce(s, Action.WidgetAction.OnPipelineDone, ctx()),
        )
    }

    @Test
    fun `W6 OnPipelineDone leaves USER widget sticky`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        assertNull(
            "USER widget must survive pipeline-done — arm is a no-op for every origin",
            module.reduce(s, Action.WidgetAction.OnPipelineDone, ctx()),
        )
    }

    @Test
    fun `W6 OnPipelineDone on Hidden is a no-op`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Hidden,
            imeViewVisible = true,
        )
        assertNull(module.reduce(s, Action.WidgetAction.OnPipelineDone, ctx()))
    }

    // ─── ResetSuppressBit is a reserved no-op (suppress-bit lives in OverlayState today) ───

    @Test
    fun `ResetSuppressBit is no-op in WidgetModule (OverlayAction is the real path)`() {
        val s = WidgetModule.WidgetSubState(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        assertNull(module.reduce(s, Action.WidgetAction.ResetSuppressBit, ctx()))
    }

    // ─── Cross-module observer ────────────────────────────────────────
    //
    // 2026-05-23 sticky-widget refactor: the observer no longer emits
    // OnPipelineDone on any pipeline/recording-quiesce boundary; the
    // widget stays visible across pipeline-end. The W8 suppress-bit
    // reset and the CloseWidget viewMode bridge are unchanged.

    @Test
    fun `sticky-widget — observer does NOT emit OnPipelineDone on Active → Idle boundary`() {
        val prev = DictateUiState.initial().copy(recording = activeRecording())
        val next = DictateUiState.initial().copy(recording = RecordingState.Idle)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "Pipeline-end must not snap the widget away — OnPipelineDone is gone",
            cascade.none { it is Action.WidgetAction.OnPipelineDone },
        )
    }

    @Test
    fun `sticky-widget — observer does NOT emit OnPipelineDone on Pipeline-Running → Idle boundary`() {
        val prev = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Preparing(sessionId = "sid-pipe"),
        )
        val next = DictateUiState.initial()
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            cascade.none { it is Action.WidgetAction.OnPipelineDone },
        )
    }

    @Test
    fun `W8 observer emits OverlayAction-ResetSuppressBit on Paused → Active`() {
        val prev = DictateUiState.initial().copy(recording = pausedRecording())
        val next = DictateUiState.initial().copy(recording = activeRecording())
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "W8 resume must clear suppress-bit so next IME-hide can auto-show",
            cascade.contains(Action.OverlayAction.ResetSuppressBit),
        )
    }

    @Test
    fun `W8 observer does NOT emit ResetSuppressBit on Active → Paused (only on resume)`() {
        val prev = DictateUiState.initial().copy(recording = activeRecording())
        val next = DictateUiState.initial().copy(recording = pausedRecording())
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "Pause direction must not reset the suppress-bit",
            cascade.none { it is Action.OverlayAction.ResetSuppressBit },
        )
    }

    @Test
    fun `sticky-widget — observer does NOT emit on Preparing → Idle quiesce`() {
        // Edge case retained for documentation: a recording that fails
        // to allocate goes Preparing → Idle without ever passing through
        // Active. Pre-refactor this triggered the W6 auto-close; post-
        // sticky-refactor the widget stays visible and the user closes
        // it manually if/when they want to.
        val prev = DictateUiState.initial().copy(recording = preparingRecording())
        val next = DictateUiState.initial()
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            cascade.none { it is Action.WidgetAction.OnPipelineDone },
        )
    }
}
