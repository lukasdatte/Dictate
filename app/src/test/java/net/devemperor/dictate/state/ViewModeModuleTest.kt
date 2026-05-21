package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-reducer tests for [ViewModeModule] — the **Triangle-FSM**
 * (ADR-0005, Spec 3 §7).
 *
 * Coverage:
 * - All seven transitions T1–T7 (one named test each)
 * - The `computeViewMode` truth-table edge cases
 * - Permission-gate on T1 (user-toggle blocked without overlay-permission)
 * - SetViewMode direct mutation (permission-loss cascade target)
 * - No-op cases (toggle in HOVER, IME-Show when already KEYBOARD, etc.)
 *
 * These tests are critical for the FSM correctness — they pin the
 * transition semantics that the orchestrator + KeyboardLayoutManager
 * depend on.
 */
class ViewModeModuleTest {

    private val module = ViewModeModule

    /**
     * Builds a global state with the given overlay + pipeline + recording
     * facts to drive `computeViewMode` from the reducer.
     */
    private fun global(
        userPrefersWidget: Boolean = false,
        hasPermission: Boolean = true,
        pipelineActive: Boolean = false,
        recordingActive: Boolean = false,
    ): DictateUiState {
        val pipeline = if (pipelineActive) {
            PipelineUiState.Running("sid", InsertionTarget.INPUT_CONNECTION)
        } else {
            PipelineUiState.Idle
        }
        val recording: RecordingState = if (recordingActive) {
            RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a"), sessionId = "sid-test")
        } else {
            RecordingState.Idle
        }
        return DictateUiState.initial().copy(
            pipeline = pipeline,
            recording = recording,
            overlay = OverlayState(userPrefersWidget = userPrefersWidget, hasPermission = hasPermission),
        )
    }

    private fun ctx(state: DictateUiState = global()) = ReducerContext(global = state)

    // ─── Truth-table ────────────────────────────────────────────────────

    @Test
    fun `computeViewMode visible + userPrefersWidget yields WIDGET`() {
        assertEquals(
            ViewMode.WIDGET,
            module.computeViewMode(
                imeViewVisible = true,
                userPrefersWidget = true,
                pipelineActive = false,
            ),
        )
    }

    @Test
    fun `computeViewMode visible + not-userPrefersWidget yields KEYBOARD`() {
        assertEquals(
            ViewMode.KEYBOARD,
            module.computeViewMode(true, userPrefersWidget = false, pipelineActive = true),
        )
    }

    @Test
    fun `computeViewMode hidden + pipelineActive yields HOVER`() {
        assertEquals(
            ViewMode.HOVER,
            module.computeViewMode(imeViewVisible = false, userPrefersWidget = false, pipelineActive = true),
        )
    }

    @Test
    fun `computeViewMode hidden + no pipeline yields KEYBOARD (default)`() {
        assertEquals(
            ViewMode.KEYBOARD,
            module.computeViewMode(imeViewVisible = false, userPrefersWidget = true, pipelineActive = false),
        )
    }

    // ─── T1: KEYBOARD → WIDGET (user-toggle) ────────────────────────────

    @Test
    fun `T1 KEYBOARD to WIDGET via ToggleViewModeWidget (with permission)`() {
        val result = module.reduce(
            state = ViewMode.KEYBOARD,
            action = Action.ViewModeAction.ToggleViewModeWidget,
            ctx = ctx(global(hasPermission = true)),
        )
        assertEquals(ViewMode.WIDGET, result!!.nextState)
    }

    @Test
    fun `T1 Permission-gate blocks KEYBOARD to WIDGET when overlay permission missing`() {
        val result = module.reduce(
            state = ViewMode.KEYBOARD,
            action = Action.ViewModeAction.ToggleViewModeWidget,
            ctx = ctx(global(hasPermission = false)),
        )
        assertNull(result)
    }

    // ─── T2: WIDGET → KEYBOARD (user-toggle back) ───────────────────────

    @Test
    fun `T2 WIDGET to KEYBOARD via ToggleViewModeWidget`() {
        val result = module.reduce(
            state = ViewMode.WIDGET,
            action = Action.ViewModeAction.ToggleViewModeWidget,
            ctx = ctx(),
        )
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    @Test
    fun `T2 WIDGET to KEYBOARD also via CloseOverlay`() {
        val result = module.reduce(
            state = ViewMode.WIDGET,
            action = Action.ViewModeAction.CloseOverlay,
            ctx = ctx(),
        )
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    // ─── T3: KEYBOARD → HOVER (IME hidden, pipeline active, was KEYBOARD) ─

    @Test
    fun `T3 KEYBOARD to HOVER on OnImeViewHidden + pipelineActive`() {
        val result = module.reduce(
            state = ViewMode.KEYBOARD,
            action = Action.ViewModeAction.OnImeViewHidden,
            ctx = ctx(global(pipelineActive = true)),
        )
        assertEquals(ViewMode.HOVER, result!!.nextState)
    }

    @Test
    fun `T3 with recording-only (no pipeline) still transitions to HOVER`() {
        val result = module.reduce(
            state = ViewMode.KEYBOARD,
            action = Action.ViewModeAction.OnImeViewHidden,
            ctx = ctx(global(recordingActive = true)),
        )
        assertEquals(ViewMode.HOVER, result!!.nextState)
    }

    // ─── T4: WIDGET → HOVER (IME hidden, pipeline active, was WIDGET) ────

    @Test
    fun `T4 WIDGET to HOVER on OnImeViewHidden + pipelineActive`() {
        // userPrefersWidget=true is irrelevant — IME hidden + pipelineActive
        // always lands in HOVER (Spec 3 §7.3 T4).
        val result = module.reduce(
            state = ViewMode.WIDGET,
            action = Action.ViewModeAction.OnImeViewHidden,
            ctx = ctx(global(userPrefersWidget = true, pipelineActive = true)),
        )
        assertEquals(ViewMode.HOVER, result!!.nextState)
    }

    // ─── T5: HOVER → KEYBOARD (IME shown, was-NOT-widget) ───────────────

    @Test
    fun `T5 HOVER to KEYBOARD on OnImeViewShown + userPrefersWidget=false`() {
        val result = module.reduce(
            state = ViewMode.HOVER,
            action = Action.ViewModeAction.OnImeViewShown,
            ctx = ctx(global(userPrefersWidget = false)),
        )
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    // ─── T6: HOVER → WIDGET (IME shown, userPrefersWidget=true) ──────────

    @Test
    fun `T6 HOVER to WIDGET on OnImeViewShown + userPrefersWidget=true`() {
        val result = module.reduce(
            state = ViewMode.HOVER,
            action = Action.ViewModeAction.OnImeViewShown,
            ctx = ctx(global(userPrefersWidget = true)),
        )
        assertEquals(ViewMode.WIDGET, result!!.nextState)
    }

    // ─── T7: HOVER → KEYBOARD via Pipeline-Done cascade ──────────────────

    @Test
    fun `T7 HOVER to KEYBOARD on OnPipelineDone (Geist-Widget structural protection)`() {
        // The plumbing: pipeline just finished. `pipelineActive=false` in
        // the new global state. From HOVER, computeViewMode falls to
        // KEYBOARD (because IME-visible is derived from current ViewMode =
        // HOVER ⇒ false, and pipelineActive=false ⇒ default branch KEYBOARD).
        val result = module.reduce(
            state = ViewMode.HOVER,
            action = Action.ViewModeAction.OnPipelineDone,
            // pipelineActive=false in the global (the cascade fires AFTER
            // pipeline = Idle has been written).
            ctx = ctx(global(pipelineActive = false)),
        )
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    @Test
    fun `T7 with userPrefersWidget=true still resolves to KEYBOARD (per truth-table)`() {
        // Even with the widget-bit, no pipeline + no IME-visible (HOVER)
        // collapses to default KEYBOARD. The widget-bit only takes effect
        // once the IME view comes back (T6).
        val result = module.reduce(
            state = ViewMode.HOVER,
            action = Action.ViewModeAction.OnPipelineDone,
            ctx = ctx(global(userPrefersWidget = true, pipelineActive = false)),
        )
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    // ─── No-op / direct-mutation paths ──────────────────────────────────

    @Test
    fun `ToggleViewModeWidget from HOVER is a no-op (user must reopen IME first)`() {
        val result = module.reduce(
            ViewMode.HOVER,
            Action.ViewModeAction.ToggleViewModeWidget,
            ctx(global(hasPermission = true)),
        )
        assertNull(result)
    }

    @Test
    fun `CloseOverlay from KEYBOARD is a no-op (no overlay to close)`() {
        val result = module.reduce(ViewMode.KEYBOARD, Action.ViewModeAction.CloseOverlay, ctx())
        assertNull(result)
    }

    @Test
    fun `SetViewMode KEYBOARD is applied (used by permission-loss cascade)`() {
        val result = module.reduce(
            ViewMode.WIDGET,
            Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD),
            ctx(),
        )
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    @Test
    fun `SetViewMode same-mode is a no-op`() {
        val result = module.reduce(
            ViewMode.KEYBOARD,
            Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `OnImeViewShown when already KEYBOARD with no pipeline is no-op`() {
        val result = module.reduce(
            ViewMode.KEYBOARD,
            Action.ViewModeAction.OnImeViewShown,
            ctx(global(userPrefersWidget = false, pipelineActive = false)),
        )
        // computeViewMode(true, false, false) = KEYBOARD == state ⇒ null
        assertNull(result)
    }

    @Test
    fun `CloseOverlay from HOVER transitions to KEYBOARD`() {
        val result = module.reduce(ViewMode.HOVER, Action.ViewModeAction.CloseOverlay, ctx())
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
    }

    // ─── CloseOverlay cascade effects (moved from OverlayModule, 2026-05-21) ───
    //
    // The destructive cascade — SuppressAutoOverlay + (conditional)
    // CancelRecording / CancelPipeline — used to fire from
    // `OverlayModule.onCrossModuleStateChange` on every HOVER → KEYBOARD
    // state-diff. That swept up the automatic T5 transition too
    // (`OnImeViewShown` with `userPrefersWidget=false`), silently
    // cancelling in-flight recordings when the user reopened the IME
    // after an app-switch. The cascade now lives in
    // `ViewModeModule.Effect.DispatchCloseOverlayCascade` and is emitted
    // ONLY from this explicit user-action arm.

    @Test
    fun `CloseOverlay from HOVER emits DispatchCloseOverlayCascade with active recording`() {
        val ctx = ctx(global(recordingActive = true))
        val result = module.reduce(ViewMode.HOVER, Action.ViewModeAction.CloseOverlay, ctx)
        val effects = result!!.sideEffects.filterIsInstance<ViewModeModule.Effect.DispatchCloseOverlayCascade>()
        assertEquals(1, effects.size)
        assertEquals(true, effects.single().shouldCancelRecording)
        assertEquals(false, effects.single().shouldCancelPipeline)
    }

    @Test
    fun `CloseOverlay from HOVER with active pipeline emits cascade with pipeline-cancel`() {
        val ctx = ctx(global(pipelineActive = true))
        val result = module.reduce(ViewMode.HOVER, Action.ViewModeAction.CloseOverlay, ctx)
        val cascade = result!!.sideEffects.filterIsInstance<ViewModeModule.Effect.DispatchCloseOverlayCascade>().single()
        assertEquals(false, cascade.shouldCancelRecording)
        assertEquals(true, cascade.shouldCancelPipeline)
    }

    @Test
    fun `CloseOverlay from HOVER with BOTH in-flight emits cascade with BOTH cancels`() {
        // The both-in-flight case: HOVER closed during the brief Send-
        // cascade window. Cascade carries both cancel flags;
        // runEffect dispatches them serially via services.emitAction.
        val ctx = ctx(global(recordingActive = true, pipelineActive = true))
        val result = module.reduce(ViewMode.HOVER, Action.ViewModeAction.CloseOverlay, ctx)
        val cascade = result!!.sideEffects.filterIsInstance<ViewModeModule.Effect.DispatchCloseOverlayCascade>().single()
        assertEquals(true, cascade.shouldCancelRecording)
        assertEquals(true, cascade.shouldCancelPipeline)
    }

    @Test
    fun `CloseOverlay from HOVER with NOTHING in-flight still emits cascade (suppress only)`() {
        // The suppress bit must always fire on explicit close — it's
        // the architectural marker that the user opted out of the
        // overlay this session. Recording/Pipeline cancels are
        // conditional; suppress is unconditional.
        val ctx = ctx(global())  // nothing in flight
        val result = module.reduce(ViewMode.HOVER, Action.ViewModeAction.CloseOverlay, ctx)
        val cascade = result!!.sideEffects.filterIsInstance<ViewModeModule.Effect.DispatchCloseOverlayCascade>().single()
        assertEquals(false, cascade.shouldCancelRecording)
        assertEquals(false, cascade.shouldCancelPipeline)
    }

    @Test
    fun `CloseOverlay from WIDGET emits cascade too (Spec 3 §6_2 — WIDGET close acts like HOVER close)`() {
        // The WIDGET-close path also triggers the cascade — it's a
        // user-driven overlay-dismiss as well. Symmetric with
        // ToggleViewModeWidget for the no-recording case, but
        // CloseOverlay carries the cascade.
        val ctx = ctx(global(recordingActive = true))
        val result = module.reduce(ViewMode.WIDGET, Action.ViewModeAction.CloseOverlay, ctx)
        val cascade = result!!.sideEffects.filterIsInstance<ViewModeModule.Effect.DispatchCloseOverlayCascade>().single()
        assertEquals(true, cascade.shouldCancelRecording)
    }

    @Test
    fun `OnImeViewShown auto-transition HOVER to KEYBOARD does NOT emit cascade`() {
        // The regression-lock for the bug fixed on 2026-05-21:
        // automatic T5 produces the same state-diff (HOVER → KEYBOARD)
        // as CloseOverlay, but MUST NOT carry the destructive cascade.
        // Recording stays alive across IME-reopen.
        val ctx = ctx(global(recordingActive = true, pipelineActive = false))
        val result = module.reduce(ViewMode.HOVER, Action.ViewModeAction.OnImeViewShown, ctx)
        // T5 produces KEYBOARD transition
        assertEquals(ViewMode.KEYBOARD, result!!.nextState)
        // ... but NO DispatchCloseOverlayCascade effect
        assertTrue(
            "OnImeViewShown must NOT emit CloseOverlay cascade — that's the bug from 2026-05-21",
            result.sideEffects.none { it is ViewModeModule.Effect.DispatchCloseOverlayCascade }
        )
    }

    // ─── No cross-module cascade emitted by this module ─────────────────

    @Test
    fun `ViewModeModule emits NO cross-module cascade (other modules observe ViewMode themselves)`() {
        val prev = DictateUiState.initial().copy(viewMode = ViewMode.KEYBOARD)
        val next = prev.copy(viewMode = ViewMode.WIDGET)
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── Lens / IDs ─────────────────────────────────────────────────────

    @Test
    fun `module id is ViewMode`() {
        assertEquals(ModuleId.ViewMode, module.id)
    }

    @Test
    fun `initial state is KEYBOARD`() {
        assertEquals(ViewMode.KEYBOARD, module.initialState())
    }

    @Test
    fun `lens round-trip preserves viewMode axis`() {
        val state = DictateUiState.initial().copy(viewMode = ViewMode.HOVER)
        assertEquals(ViewMode.HOVER, module.read(state))
        assertEquals(ViewMode.KEYBOARD, module.write(state, ViewMode.KEYBOARD).viewMode)
    }
}
