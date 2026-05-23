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
        // B2-VAL-W1 F-6 / Epic R-7 — drain the process-wide
        // ActiveJobRegistry single-job lock. The new B2 boot-tests
        // faithfully copied this reference test's tearDown discipline,
        // but the reference itself never drained ActiveJobRegistry
        // (inherited-incomplete-discipline — the actual R-7 root cause).
        // Fixed here too so the discipline is complete at the source.
        ActiveJobRegistry.resetForTest()
        // F-9 (B5): this test is the amplifier — it boots the full
        // DictatePipelineService many times, each `onCreate` running
        // LegacyAudioFileMigration + creating session rows against the
        // shared DictateDatabase singleton. Drop the singleton AND
        // delete the file-backed DB on teardown so a sibling test
        // (notably LegacyAudioFileMigrationTest) co-locating after this
        // one in the same Robolectric fork starts from a clean DB
        // rather than this test's accumulated rows / migration flag.
        //
        // C8-IMPL-1 / B3-VAL F-1 — this test is the amplifier for the
        // heal-thread axis too: every onCreate runs
        // DurationHealingScheduler.schedule(). Drain the in-flight heal
        // thread BEFORE the DB is dropped so it cannot pollute the
        // sibling. Ordering mandatory: scheduler reset precedes
        // DictateDatabase.resetForTest.
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        net.devemperor.dictate.database.DictateDatabase.resetForTest(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
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
        // 2026-05-23 sticky-widget refactor: production IME service
        // dispatches BOTH ImeView-axis actions per Spec 3 §6 (see
        // DictateInputMethodService.onFinishInputView). The overlay
        // backend's attach gate now keys on `state.widget`, so the
        // test sequence must mirror production.
        b.dispatch(Action.ViewModeAction.OnImeViewHidden) // T3
        b.dispatch(Action.WidgetAction.OnImeViewHidden)
        idle()

        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(
            "T3 must attach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T4-sticky WIDGET stays WIDGET on IME-hide keeping the overlay backend attached`() {
        // 2026-05-21 truth-table Row 3: when the user explicitly
        // prefers the widget, IME-hide no longer collapses to HOVER —
        // WIDGET stays sticky. Overlay backend remains attached
        // either way (HOVER and WIDGET share `viewMode != KEYBOARD`).
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
        b.dispatch(Action.ViewModeAction.OnImeViewHidden)
        idle()

        // Pre-fix: HOVER. Post-fix: stays WIDGET (Row 3).
        assertEquals(ViewMode.WIDGET, b.state.value.viewMode)
        assertTrue(
            "Overlay backend remains attached across IME-hide while user prefers widget",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T5-sticky HOVER to KEYBOARD keeps overlay attached (sticky-widget refactor)`() {
        // 2026-05-23 sticky-widget refactor: pre-refactor T5
        // (OnImeViewShown in HOVER) detached the overlay because it
        // flipped viewMode → KEYBOARD and the attach gate keyed on
        // viewMode. Post-refactor the gate keys on `state.widget` and
        // the widget stays Visible(PIPELINE) until the user closes it
        // explicitly. The overlay must therefore SURVIVE the IME
        // re-show — that's the whole point of the sticky semantic.
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
        b.dispatch(Action.WidgetAction.OnImeViewHidden)   // → widget Visible(PIPELINE)
        idle()
        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        b.dispatch(Action.ViewModeAction.OnImeViewShown) // T5 viewMode-axis → KEYBOARD
        b.dispatch(Action.WidgetAction.OnImeViewShown)   // widget axis: stays Visible
        idle()

        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertTrue(
            "Sticky-widget refactor: overlay stays attached while widget is Visible",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun `T7-sticky pipeline-done in HOVER cascades viewMode to KEYBOARD but overlay stays`() {
        // 2026-05-23 sticky-widget refactor: pre-refactor pipeline-
        // done in HOVER closed the overlay (W6 auto-close + viewMode
        // cascade). Post-refactor the widget stays Visible until the
        // user closes it, so the overlay stays attached even though
        // viewMode recomputes to KEYBOARD on the Geist-Widget
        // structural protection.
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
        b.dispatch(Action.WidgetAction.OnImeViewHidden)   // widget axis: Visible(PIPELINE)
        idle()
        assertEquals(ViewMode.HOVER, b.state.value.viewMode)
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        // Pipeline settles — viewMode recomputes to KEYBOARD,
        // widget axis is left as-is (sticky). Overlay stays.
        b.dispatch(Action.PipelineAction.PipelineDone(sessionId = "s1", finalText = "hi"))
        idle()

        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertTrue(
            "Sticky-widget: pipeline-done leaves widget Visible, overlay stays attached",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // B5 F-1 — IME-activation production-trigger regression guards.
    // The IME drives T3/T4/T5/T6 via OnImeViewHidden/OnImeViewShown;
    // these exercise the action surface the new IME hooks dispatch.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `F-1 recording-active + OnImeViewHidden enters HOVER (primary use-case)`() {
        // The headline HOVER use-case: dictation continues after the
        // user switches the keyboard away (Spec 3 §1.1). Drives the
        // recording axis active, then the IME-hide boundary on BOTH
        // axes (production parity — see Spec 3 §6).
        val b = binder()
        idle()
        b.dispatch(
            Action.RecordingAction.StartRecording(
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
                audioFile = java.io.File("/tmp/a.m4a"), sessionId = "sid-test",
            ),
        )
        idle()
        b.dispatch(Action.RecordingAction.MediaRecorderReady(java.io.File("/tmp/a.m4a")))
        idle()

        b.dispatch(Action.ViewModeAction.OnImeViewHidden) // F-1 T3
        b.dispatch(Action.WidgetAction.OnImeViewHidden)   // sticky-widget axis
        idle()

        assertEquals(
            "Recording-active + IME hidden ⇒ HOVER (the primary F-1 trigger)",
            ViewMode.HOVER, b.state.value.viewMode,
        )
        assertTrue(b.keyboardLayoutManager.overlayAttached())
    }

    @Test
    fun `F-1 T6 HOVER to WIDGET on OnImeViewShown when userPrefersWidget`() {
        // Truth-table revision 2026-05-21 made Row 3 sticky: when the
        // user prefers widget, IME-hide no longer enters HOVER. To
        // exercise T6 the test now drives HOVER directly via
        // SetViewMode(HOVER) (the same path the recovery / permission-
        // cascade pipelines use) so the T6 transition can be observed.
        val b = binder()
        idle()
        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // → WIDGET, userPrefersWidget=true
        idle()
        b.dispatch(Action.ViewModeAction.SetViewMode(ViewMode.HOVER)) // force HOVER directly
        idle()
        assertEquals(ViewMode.HOVER, b.state.value.viewMode)

        b.dispatch(Action.ViewModeAction.OnImeViewShown) // T6 → WIDGET (persist bit)
        idle()

        assertEquals(
            "userPrefersWidget persists ⇒ HOVER→WIDGET on view-shown (T6)",
            ViewMode.WIDGET, b.state.value.viewMode,
        )
    }

    @Test
    fun `F-1 OnImeViewShown with no mode change is idempotent (no spurious cascade)`() {
        val b = binder()
        idle()
        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        // Already KEYBOARD, not pipeline-active — OnImeViewShown
        // recomputes to KEYBOARD == current; reducer must no-op.
        b.dispatch(Action.ViewModeAction.OnImeViewShown)
        idle()
        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(b.keyboardLayoutManager.overlayAttached())
    }

    @Test
    fun `F-8 both-in-flight HOVER-close-from-pipeline-done stays within MAX_CASCADE_DEPTH`() {
        // Worst-case cascade: recording AND pipeline both in flight,
        // HOVER active, pipeline settles → OnPipelineDone cascade →
        // (state != HOVER recompute) → HOVER→KEYBOARD cancel-cascade
        // (SuppressBit + CancelRecording + CancelPipeline, each
        // re-snapshotted at depth+1, CancelRecording further fanning
        // RecordingModule's cross-module observer). At MAX_CASCADE_DEPTH
        // (8) the orchestrator error()s in DEBUG. This test proves the
        // budget holds: the dispatch completes WITHOUT an
        // IllegalStateException and the FSM settles on KEYBOARD with the
        // overlay detached (the F-7-internal opt-out is honoured).
        val b = binder()
        idle()
        // Recording active.
        b.dispatch(
            Action.RecordingAction.StartRecording(
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
                audioFile = java.io.File("/tmp/a.m4a"), sessionId = "sid-test",
            ),
        )
        idle()
        b.dispatch(Action.RecordingAction.MediaRecorderReady(java.io.File("/tmp/a.m4a")))
        idle()
        // Pipeline also in flight (both-in-flight precondition).
        b.dispatch(
            Action.PipelineAction.TriggerPipeline(
                sessionId = "s1",
                audioFile = java.io.File("/tmp/a.m4a"),
            ),
        )
        idle()
        // IME hidden → HOVER.
        b.dispatch(Action.ViewModeAction.OnImeViewHidden)
        idle()
        assertEquals(ViewMode.HOVER, b.state.value.viewMode)

        // Pipeline settles while in HOVER with recording still active —
        // the deepest cascade path. Must not throw (cascade-cap) in
        // DEBUG and must settle to KEYBOARD.
        b.dispatch(Action.PipelineAction.PipelineDone(sessionId = "s1", finalText = "hi"))
        idle()

        assertEquals(
            "F-8: both-in-flight HOVER-close cascade must settle on KEYBOARD without tripping MAX_CASCADE_DEPTH",
            ViewMode.KEYBOARD, b.state.value.viewMode,
        )
        assertFalse(
            "F-8: overlay must be detached after the cancel-cascade",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }
}
