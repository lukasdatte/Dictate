package net.devemperor.dictate.core

import android.content.Intent
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.ViewMode
import net.devemperor.dictate.state.layout.KeyboardLayoutManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Robolectric tests for the C18 Triangle-FSM ↔ OverlayBackend
 * attach/detach wiring (Spec 3 §6 + §7.2 + §7.3, ADR-0005).
 *
 * The private `syncOverlayBackendAttachment` is exercised through its
 * only public surface: dispatching `ViewModeAction`s through the
 * binder and observing whether the [OverlayBackend] is registered
 * with the [KeyboardLayoutManager] (via
 * [KeyboardLayoutManager.attachedBackendCount]).
 *
 * # Permission precondition
 *
 * T1/T3 attach the backend only via the FSM, but the FSM's
 * KEYBOARD→WIDGET arm is permission-gated
 * (`ViewModeModule.reduce` returns `null` without
 * `overlay.hasPermission`). Robolectric's `Settings.canDrawOverlays`
 * defaults to **true** in the test sandbox, and the
 * `OverlayPermissionObserver.init()` call in `onCreate` mirrors that
 * into `state.overlay.hasPermission`, so the T1 user-toggle resolves.
 *
 * # Transition matrix asserted
 *
 * | # | From → To | Trigger | Expect overlay backend |
 * |---|-----------|---------|------------------------|
 * | T1 | KEYBOARD → WIDGET | `ToggleViewModeWidget` | attached |
 * | T2 | WIDGET → KEYBOARD | `ToggleViewModeWidget` | detached |
 * | T3 | KEYBOARD → HOVER  | `OnImeViewHidden` + pipeline | attached |
 * | T4 | WIDGET → HOVER    | `OnImeViewHidden` + pipeline | stays attached |
 * | T5 | HOVER → KEYBOARD  | `OnImeViewShown` | detached |
 * | T7 | HOVER → KEYBOARD  | pipeline-done cascade | detached |
 *
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceOverlayTransitionTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        JobExecutor.resetForTest()
    }

    /**
     * Boot the service and grant the overlay permission on the state
     * axis. The permission is mirrored from `Settings.canDrawOverlays`
     * (Robolectric default `false`), so the T1 user-toggle would be a
     * permission-gated no-op without this. Dispatching
     * `OnOverlayPermissionChanged(true)` directly is deterministic and
     * doesn't depend on the observer's lifecycle timing.
     */
    private fun binder(): DictatePipelineService.LocalBinder {
        controller.create()
        val b = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        b.dispatch(Action.OverlayAction.OnOverlayPermissionChanged(granted = true))
        ShadowLooper.idleMainLooper()
        return b
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    /**
     * Count of backends registered with the manager *minus* the
     * baseline (any backends the IME would attach). The Service alone
     * attaches no backends at boot, so the count is purely the overlay
     * backend's membership (0 or 1).
     */
    private fun KeyboardLayoutManager.overlayAttached(): Boolean =
        attachedBackendCount() > 0

    @Test
    fun `overlay backend is constructed and available`() {
        val b = binder()
        assertNotNull(
            "OverlayBackend must be constructed in onCreate (real WindowManager in Robolectric)",
            b.overlayBackend,
        )
    }

    @Test
    fun `boot state is KEYBOARD with overlay detached`() {
        val b = binder()
        idle()
        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(
            "No overlay backend attached in KEYBOARD",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T1 KEYBOARD to WIDGET attaches the overlay backend`() {
        val b = binder()
        idle()
        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget)
        idle()

        assertEquals(ViewMode.WIDGET, b.state.value.viewMode)
        assertTrue(
            "T1 must attach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T2 WIDGET to KEYBOARD detaches the overlay backend`() {
        val b = binder()
        idle()
        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // T1
        idle()
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // T2
        idle()

        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(
            "T2 must detach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T3 KEYBOARD to HOVER attaches the overlay backend`() {
        val b = binder()
        idle()
        // Pipeline must be active for HOVER auto-trigger.
        b.dispatch(
            Action.PipelineAction.TriggerPipeline(
                sessionId = "s1",
                audioFile = java.io.File("/tmp/a.m4a"),
            ),
        )
        idle()
        b.dispatch(Action.ViewModeAction.OnImeViewHidden) // T3
        idle()

        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(
            "T3 must attach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T4 WIDGET to HOVER keeps the overlay backend attached`() {
        val b = binder()
        idle()
        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // T1 → WIDGET
        idle()
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        b.dispatch(
            Action.PipelineAction.TriggerPipeline(
                sessionId = "s1",
                audioFile = java.io.File("/tmp/a.m4a"),
            ),
        )
        idle()
        b.dispatch(Action.ViewModeAction.OnImeViewHidden) // T4 → HOVER
        idle()

        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(
            "T4 must keep the OverlayBackend attached (no churn)",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T5 HOVER to KEYBOARD detaches the overlay backend`() {
        val b = binder()
        idle()
        b.dispatch(
            Action.PipelineAction.TriggerPipeline(
                sessionId = "s1",
                audioFile = java.io.File("/tmp/a.m4a"),
            ),
        )
        idle()
        b.dispatch(Action.ViewModeAction.OnImeViewHidden) // → HOVER
        idle()
        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        b.dispatch(Action.ViewModeAction.OnImeViewShown) // T5 → KEYBOARD
        idle()

        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(
            "T5 must detach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T7 pipeline-done in HOVER cascades to KEYBOARD and detaches (Geist-Widget protection)`() {
        val b = binder()
        idle()
        b.dispatch(
            Action.PipelineAction.TriggerPipeline(
                sessionId = "s1",
                audioFile = java.io.File("/tmp/a.m4a"),
            ),
        )
        idle()
        b.dispatch(Action.ViewModeAction.OnImeViewHidden) // → HOVER
        idle()
        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        // Pipeline settles — PipelineModule cascades OnPipelineDone,
        // ViewModeModule recomputes to KEYBOARD (T7 Geist-Widget
        // structural protection).
        b.dispatch(Action.PipelineAction.PipelineDone(sessionId = "s1", finalText = "hi"))
        idle()

        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(
            "T7 must detach the overlay (no Geist-Widget)",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }
}
