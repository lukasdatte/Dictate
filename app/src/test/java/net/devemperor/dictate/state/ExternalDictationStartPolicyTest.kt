package net.devemperor.dictate.state

import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [resolveExternalDictationStart] — the single policy behind
 * every external "start dictation without opening the keyboard" trigger
 * (launcher alias / S-Pen Air Command / Edge panel, static app shortcut,
 * Quick-Settings tile). All triggers funnel through
 * `StartDictationActivity` → `DictatePipelineService`
 * (`ACTION_START_DICTATION`) → this policy.
 *
 * Contract under test (see the policy KDoc):
 *
 *  1. Widget surfacing — when the overlay widget is hidden, the policy
 *     emits the canonical open-widget trigger:
 *     `ViewModeAction.ToggleViewModeWidget` from KEYBOARD (full T1
 *     cascade: userPrefersWidget + ToggleWidget + ResetSuppressBit), or
 *     the direct `ResetSuppressBit` + `WidgetAction.ToggleWidget` pair
 *     outside KEYBOARD (HOVER has no T1 arm).
 *  2. Recording start — only from `RecordingState.Idle`; byte-identical
 *     to the keyboard/overlay record-button start (shared
 *     `resolveStartRecordingFromIdle` body: allocation, UUID mint,
 *     continuation lookup, IOException→toast).
 *  3. No double-start — Active/Paused/Preparing/Interrupted recording
 *     yields no recording action; the trigger degrades to
 *     "bring the widget to the surface" (or a full no-op when the
 *     widget is already visible).
 *  4. ADR-0009 — a live pipeline does NOT block a fresh start; the new
 *     run queues behind the active one (same rule as
 *     `resolveSecondaryRecordAction`).
 */
class ExternalDictationStartPolicyTest {

    private val base = DictateUiState.initial()

    private fun visibleWidget(origin: WidgetOrigin = WidgetOrigin.USER): WidgetState =
        WidgetState.Visible(origin)

    private fun activeRecording(): RecordingState = RecordingState.Active(
        useBluetooth = false,
        audioFile = File("/tmp/ext-start-active.m4a"),
        sessionId = "sid-active",
    )

    // ─── 1. Idle paths — widget hidden ─────────────────────────────────

    @Test
    fun `idle + hidden widget + KEYBOARD emits canonical widget toggle then StartRecording`() {
        val actions = resolveExternalDictationStart(base, fakeModuleServices())

        assertEquals(2, actions.size)
        assertEquals(Action.ViewModeAction.ToggleViewModeWidget, actions[0])
        val start = actions[1] as Action.RecordingAction.StartRecording
        assertEquals(InsertionTarget.INPUT_CONNECTION, start.target)
        assertTrue(start.sessionId.isNotBlank())
    }

    @Test
    fun `idle + hidden widget + HOVER emits suppress-reset and direct widget toggle then StartRecording`() {
        val s = base.copy(viewMode = ViewMode.HOVER)

        val actions = resolveExternalDictationStart(s, fakeModuleServices())

        assertEquals(3, actions.size)
        assertEquals(Action.OverlayAction.ResetSuppressBit, actions[0])
        assertEquals(Action.WidgetAction.ToggleWidget, actions[1])
        assertTrue(actions[2] is Action.RecordingAction.StartRecording)
    }

    @Test
    fun `idle + hidden widget + WIDGET viewMode uses the direct widget path`() {
        // Inconsistent-but-reachable combination (viewMode WIDGET with the
        // widget axis already Hidden — e.g. mid-cascade after an X-close).
        // ToggleViewModeWidget from WIDGET would CLOSE the widget (T2), so
        // the policy must use the direct WidgetAction path instead.
        val s = base.copy(viewMode = ViewMode.WIDGET)

        val actions = resolveExternalDictationStart(s, fakeModuleServices())

        assertEquals(3, actions.size)
        assertEquals(Action.OverlayAction.ResetSuppressBit, actions[0])
        assertEquals(Action.WidgetAction.ToggleWidget, actions[1])
        assertTrue(actions[2] is Action.RecordingAction.StartRecording)
    }

    // ─── 2. Idle paths — widget already visible ────────────────────────

    @Test
    fun `idle + visible widget emits only StartRecording`() {
        val s = base.copy(widget = visibleWidget())

        val actions = resolveExternalDictationStart(s, fakeModuleServices())

        assertEquals(1, actions.size)
        assertTrue(actions[0] is Action.RecordingAction.StartRecording)
    }

    // ─── 3. No double-start ────────────────────────────────────────────

    @Test
    fun `active recording + visible widget is a full no-op`() {
        val s = base.copy(recording = activeRecording(), widget = visibleWidget())

        assertEquals(emptyList<Action>(), resolveExternalDictationStart(s, fakeModuleServices()))
    }

    @Test
    fun `active recording + hidden widget only surfaces the widget`() {
        val s = base.copy(recording = activeRecording())

        val actions = resolveExternalDictationStart(s, fakeModuleServices())

        assertEquals(listOf<Action>(Action.ViewModeAction.ToggleViewModeWidget), actions)
    }

    @Test
    fun `paused recording emits no StartRecording`() {
        val s = base.copy(
            recording = RecordingState.Paused(
                useBluetooth = false,
                audioFile = File("/tmp/ext-start-paused.m4a"),
                sessionId = "sid-paused",
            ),
            widget = visibleWidget(),
        )

        assertEquals(emptyList<Action>(), resolveExternalDictationStart(s, fakeModuleServices()))
    }

    @Test
    fun `preparing recording emits no StartRecording`() {
        val s = base.copy(
            recording = RecordingState.Preparing(
                useBluetooth = false,
                audioFile = File("/tmp/ext-start-preparing.m4a"),
                sessionId = "sid-preparing",
            ),
            widget = visibleWidget(),
        )

        assertEquals(emptyList<Action>(), resolveExternalDictationStart(s, fakeModuleServices()))
    }

    @Test
    fun `interrupted recording emits no StartRecording — widget surfaces the continue-discard choice`() {
        val s = base.copy(
            recording = RecordingState.Interrupted(sessionId = "sid-int", elapsedMs = 8_000L),
            widget = visibleWidget(),
        )

        assertEquals(emptyList<Action>(), resolveExternalDictationStart(s, fakeModuleServices()))
    }

    // ─── 4. ADR-0009 — pipeline live does not block a fresh start ──────

    @Test
    fun `running pipeline + idle recording still emits StartRecording (run queues, ADR-0009)`() {
        val s = base.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sid-pipe",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
            widget = visibleWidget(origin = WidgetOrigin.PIPELINE),
        )

        val actions = resolveExternalDictationStart(s, fakeModuleServices())

        assertEquals(1, actions.size)
        assertTrue(actions[0] is Action.RecordingAction.StartRecording)
    }
}
