package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-reducer + cross-module-cascade tests for [OverlayModule].
 *
 * Coverage:
 * - UpdateOverlayPosition (portrait + landscape) → PersistOverlayPosition effect
 * - MarkOverlayOnboardingShown clears the pending flag
 * - DismissOverlayOnboarding clears the pending flag (permanently)
 * - SuppressAutoOverlayUntilNextSession sets the bit
 * - ResetSuppressBit clears the bit (idempotent)
 * - SetUserPrefersWidget mutates the bit (idempotent)
 * - OnOverlayPermissionChanged updates hasPermission
 * - RequestOverlayPermission emits OpenOverlayPermissionSettings effect
 * - Cross-module cascade: KEYBOARD→WIDGET → SetUserPrefersWidget(true)
 * - Cross-module cascade: WIDGET→KEYBOARD → SetUserPrefersWidget(false)
 * - Cross-module cascade: HOVER→KEYBOARD → SuppressBit + CancelRecording (Active)
 * - Cross-module cascade: HOVER→KEYBOARD → SuppressBit + CancelPipeline (no recording)
 * - Cross-module cascade: HOVER→KEYBOARD → SuppressBit only (idle)
 * - Cross-module cascade: permission-loss → SetViewMode(KEYBOARD)
 * - reduceFailure is NOT overridden (default null per Spec 3 §4.8 design decision)
 */
class OverlayModuleTest {

    private val module = OverlayModule
    private val testFile = File("/tmp/test.m4a")
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    // ─── Reducer arms ───────────────────────────────────────────────────

    @Test
    fun `UpdateOverlayPosition portrait writes portrait fields + PersistOverlayPosition effect`() {
        val state = OverlayState()
        val result = module.reduce(
            state,
            Action.OverlayAction.UpdateOverlayPosition(portrait = true, x = 0.5f, y = 0.7f),
            ctx(),
        )
        assertEquals(0.5f, result!!.nextState.positionPortraitX)
        assertEquals(0.7f, result.nextState.positionPortraitY)
        assertEquals(1.0f, result.nextState.positionLandscapeX)  // unchanged
        assertTrue(result.sideEffects.any { it is OverlayModule.Effect.PersistOverlayPosition })
    }

    @Test
    fun `UpdateOverlayPosition landscape writes landscape fields`() {
        val state = OverlayState()
        val result = module.reduce(
            state,
            Action.OverlayAction.UpdateOverlayPosition(portrait = false, x = 0.3f, y = 0.4f),
            ctx(),
        )
        assertEquals(0.3f, result!!.nextState.positionLandscapeX)
        assertEquals(0.4f, result.nextState.positionLandscapeY)
        assertEquals(1.0f, result.nextState.positionPortraitX)
    }

    @Test
    fun `MarkOverlayOnboardingShown clears onboardingPending + emits Mark effect`() {
        val state = OverlayState(onboardingPending = true)
        val result = module.reduce(state, Action.OverlayAction.MarkOverlayOnboardingShown, ctx())
        assertEquals(false, result!!.nextState.onboardingPending)
        assertTrue(result.sideEffects.contains(OverlayModule.Effect.MarkOnboardingShown))
    }

    @Test
    fun `DismissOverlayOnboarding clears onboardingPending + emits dismiss effect`() {
        val state = OverlayState(onboardingPending = true)
        val result = module.reduce(state, Action.OverlayAction.DismissOverlayOnboarding, ctx())
        assertEquals(false, result!!.nextState.onboardingPending)
        assertTrue(result.sideEffects.contains(OverlayModule.Effect.MarkOnboardingPermanentlyDismissed))
    }

    @Test
    fun `F-2 ShowOverlayOnboarding sets onboardingPending, no effect`() {
        val state = OverlayState(onboardingPending = false)
        val result = module.reduce(state, Action.OverlayAction.ShowOverlayOnboarding, ctx())
        assertEquals(true, result!!.nextState.onboardingPending)
        assertTrue("ShowOverlayOnboarding is a pure flag-set, no Settings launch.",
            result.sideEffects.isEmpty())
    }

    @Test
    fun `F-4 RequestOverlayPermissionNotification emits NotifyOverlayPermissionRequired, no state change`() {
        val state = OverlayState(hasPermission = false)
        val result = module.reduce(
            state, Action.OverlayAction.RequestOverlayPermissionNotification, ctx())
        assertEquals(state, result!!.nextState)
        assertTrue(result.sideEffects.contains(
            OverlayModule.Effect.NotifyOverlayPermissionRequired))
    }

    @Test
    fun `SuppressAutoOverlayUntilNextSession sets the bit`() {
        val state = OverlayState(suppressAutoOverlayUntilNextSession = false)
        val result = module.reduce(state, Action.OverlayAction.SuppressAutoOverlayUntilNextSession, ctx())
        assertEquals(true, result!!.nextState.suppressAutoOverlayUntilNextSession)
    }

    @Test
    fun `ResetSuppressBit clears the bit (idempotent)`() {
        val state = OverlayState(suppressAutoOverlayUntilNextSession = true)
        val result = module.reduce(state, Action.OverlayAction.ResetSuppressBit, ctx())
        assertEquals(false, result!!.nextState.suppressAutoOverlayUntilNextSession)
    }

    @Test
    fun `ResetSuppressBit on already-false is still TransitionResult (idempotent emit)`() {
        // Spec 3 §4.8 — return TransitionResult even when bit already false
        // (StateFlow distinct-Vertrag suppresses subscriber re-emit anyway).
        val state = OverlayState(suppressAutoOverlayUntilNextSession = false)
        val result = module.reduce(state, Action.OverlayAction.ResetSuppressBit, ctx())
        assertNotNull(result)
    }

    @Test
    fun `SetUserPrefersWidget true updates the bit`() {
        val state = OverlayState(userPrefersWidget = false)
        val result = module.reduce(state, Action.OverlayAction.SetUserPrefersWidget(true), ctx())
        assertEquals(true, result!!.nextState.userPrefersWidget)
    }

    @Test
    fun `SetUserPrefersWidget with same value returns null`() {
        val state = OverlayState(userPrefersWidget = false)
        val result = module.reduce(state, Action.OverlayAction.SetUserPrefersWidget(false), ctx())
        assertNull(result)
    }

    @Test
    fun `OnOverlayPermissionChanged updates hasPermission`() {
        val state = OverlayState(hasPermission = false)
        val result = module.reduce(
            state,
            Action.OverlayAction.OnOverlayPermissionChanged(granted = true),
            ctx(),
        )
        assertEquals(true, result!!.nextState.hasPermission)
    }

    @Test
    fun `OnOverlayPermissionChanged idempotent returns null`() {
        val state = OverlayState(hasPermission = true)
        val result = module.reduce(
            state,
            Action.OverlayAction.OnOverlayPermissionChanged(granted = true),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `RequestOverlayPermission emits OpenOverlayPermissionSettings effect`() {
        val state = OverlayState()
        val result = module.reduce(state, Action.OverlayAction.RequestOverlayPermission, ctx())
        assertTrue(result!!.sideEffects.contains(OverlayModule.Effect.OpenOverlayPermissionSettings))
    }

    @Test
    fun `RequestOverlayPermission clears onboardingPending (post-cutover #HINT)`() {
        // Pre-fix the reducer left `onboardingPending = true` and relied
        // on the IME's inline setVisibility(GONE) at the click site to
        // hide the explainer bar. That hack was the only thing hiding
        // a permanent VISIBLE state — single-source-of-truth violation.
        // Post-fix the state clears here so OverlayOnboardingObserver
        // is the sole visibility writer.
        val state = OverlayState(onboardingPending = true)
        val result = module.reduce(state, Action.OverlayAction.RequestOverlayPermission, ctx())
        assertEquals(false, result!!.nextState.onboardingPending)
    }

    // ─── Cross-module cascade ───────────────────────────────────────────

    @Test
    fun `cascade T1 KEYBOARD to WIDGET emits SetUserPrefersWidget(true)`() {
        val prev = DictateUiState.initial().copy(viewMode = ViewMode.KEYBOARD)
        val next = prev.copy(viewMode = ViewMode.WIDGET)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.OverlayAction.SetUserPrefersWidget(true)))
    }

    @Test
    fun `cascade T2 WIDGET to KEYBOARD emits SetUserPrefersWidget(false)`() {
        val prev = DictateUiState.initial().copy(viewMode = ViewMode.WIDGET)
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.OverlayAction.SetUserPrefersWidget(false)))
    }

    @Test
    fun `cascade HOVER to KEYBOARD emits SuppressBit + CancelRecording (when active)`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.HOVER,
            recording = RecordingState.Active(false, testFile, sessionId = "sid-test"),
        )
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.OverlayAction.SuppressAutoOverlayUntilNextSession))
        assertTrue(cascade.contains(Action.RecordingAction.CancelRecording))
        // Pipeline-cancel must NOT also fire here — pipeline is Idle.
        // (F-7: both-in-flight case is covered separately below.)
        assertTrue(cascade.none { it is Action.PipelineAction.CancelPipeline })
    }

    @Test
    fun `F-7 — cascade HOVER to KEYBOARD with BOTH recording and pipeline in-flight emits BOTH cancels`() {
        // The both-in-flight case: HOVER closed during the brief Send-
        // cascade window (recording stopping while pipeline already
        // preparing). The earlier `if/else if/else` chain dropped the
        // pipeline cancel silently, leaving the pipeline running and
        // producing a transcript the user opted out of. The F-7
        // additive list emits both cancels; the orchestrator dispatches
        // them serially at depth+1 with re-snapshotting.
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.HOVER,
            recording = RecordingState.Active(false, testFile, sessionId = "sid-test"),
            pipeline = PipelineUiState.Preparing("sid"),
        )
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.OverlayAction.SuppressAutoOverlayUntilNextSession))
        assertTrue(cascade.contains(Action.RecordingAction.CancelRecording))
        assertTrue(cascade.any { it is Action.PipelineAction.CancelPipeline })
        // C-3 priority preserved by list order: Recording before Pipeline.
        val recordingIdx = cascade.indexOf(Action.RecordingAction.CancelRecording)
        val pipelineIdx = cascade.indexOfFirst { it is Action.PipelineAction.CancelPipeline }
        assertTrue(
            "C-3 ordering: CancelRecording must precede CancelPipeline",
            recordingIdx in 0..<pipelineIdx,
        )
    }

    @Test
    fun `cascade HOVER to KEYBOARD with Paused recording emits SuppressBit + CancelRecording`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.HOVER,
            recording = RecordingState.Paused(false, testFile, sessionId = "sid-test"),
        )
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.RecordingAction.CancelRecording))
    }

    @Test
    fun `cascade HOVER to KEYBOARD with Preparing recording emits SuppressBit + CancelRecording`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.HOVER,
            recording = RecordingState.Preparing(false, testFile, sessionId = "sid-test"),
        )
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.RecordingAction.CancelRecording))
    }

    @Test
    fun `cascade HOVER to KEYBOARD with no recording but pipeline running emits CancelPipeline`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.HOVER,
            pipeline = PipelineUiState.Running("sid", InsertionTarget.INPUT_CONNECTION),
        )
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.OverlayAction.SuppressAutoOverlayUntilNextSession))
        assertTrue(cascade.any { it is Action.PipelineAction.CancelPipeline })
        // Recording-cancel must NOT also fire
        assertTrue(cascade.none { it == Action.RecordingAction.CancelRecording })
    }

    @Test
    fun `cascade HOVER to KEYBOARD with nothing active emits SuppressBit only`() {
        val prev = DictateUiState.initial().copy(viewMode = ViewMode.HOVER)
        val next = prev.copy(viewMode = ViewMode.KEYBOARD)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(
            listOf<Action>(Action.OverlayAction.SuppressAutoOverlayUntilNextSession),
            cascade,
        )
    }

    @Test
    fun `cascade permission-loss from non-KEYBOARD viewMode emits SetViewMode(KEYBOARD)`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.HOVER,
            overlay = OverlayState(hasPermission = true),
        )
        val next = prev.copy(overlay = prev.overlay.copy(hasPermission = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD)))
    }

    @Test
    fun `F-4 cascade permission-loss also emits RequestOverlayPermissionNotification`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.WIDGET,
            overlay = OverlayState(hasPermission = true),
        )
        val next = prev.copy(overlay = prev.overlay.copy(hasPermission = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        // Spec 3 §9 O7 — the FGS notification surfaces the revoke reason
        // when the user is in another app (no in-IME info-bar reachable).
        assertTrue(cascade.contains(
            Action.OverlayAction.RequestOverlayPermissionNotification))
    }

    @Test
    fun `F-2 cascade non-WIDGET to WIDGET while onboardingPending emits MarkOverlayOnboardingShown`() {
        // User granted the permission, returned, and toggled the widget
        // again — Spec 3 §5.4 auto-cleanup so the stale explainer bar
        // does not linger.
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.KEYBOARD,
            overlay = OverlayState(hasPermission = true, onboardingPending = true),
        )
        val next = prev.copy(viewMode = ViewMode.WIDGET)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.contains(Action.OverlayAction.MarkOverlayOnboardingShown))
    }

    @Test
    fun `F-2 cascade to WIDGET without onboardingPending does NOT emit MarkOverlayOnboardingShown`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.KEYBOARD,
            overlay = OverlayState(hasPermission = true, onboardingPending = false),
        )
        val next = prev.copy(viewMode = ViewMode.WIDGET)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.none { it == Action.OverlayAction.MarkOverlayOnboardingShown })
    }

    @Test
    fun `cascade permission-loss when already in KEYBOARD does NOT cascade`() {
        val prev = DictateUiState.initial().copy(
            viewMode = ViewMode.KEYBOARD,
            overlay = OverlayState(hasPermission = true),
        )
        val next = prev.copy(overlay = prev.overlay.copy(hasPermission = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(cascade.none { it is Action.ViewModeAction.SetViewMode })
    }

    @Test
    fun `cascade no-op when nothing relevant changed`() {
        val prev = DictateUiState.initial()
        val next = prev.copy()
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── reduceFailure design decision ──────────────────────────────────

    @Test
    fun `reduceFailure is NOT overridden (Spec 3 §4_8 design decision)`() {
        // OverlayModule deliberately does not override reduceFailure —
        // all overlay effects are idempotent pref-writes/UI-effects, no
        // rollback semantics. The base `DictateModule.reduceFailure`
        // default returns null → DispatchOutcome.Rejected.
        val state = OverlayState()
        val failure = Action.EffectFailure(
            originModuleId = ModuleId.Overlay,
            effect = "PersistOverlayPosition(portrait=true, x=0.5, y=0.7)",
            reason = "disk full",
        )
        assertNull(module.reduceFailure(state, failure, ctx()))
    }

    // ─── Lens / IDs ─────────────────────────────────────────────────────

    @Test
    fun `module id is Overlay`() {
        assertEquals(ModuleId.Overlay, module.id)
    }

    @Test
    fun `initial state is OverlayState defaults`() {
        assertEquals(OverlayState(), module.initialState())
    }

    @Test
    fun `lens round-trip preserves overlay axis`() {
        val state = DictateUiState.initial().copy(overlay = OverlayState(hasPermission = true))
        assertEquals(OverlayState(hasPermission = true), module.read(state))
        val back = module.write(state, OverlayState())
        assertEquals(OverlayState(), back.overlay)
    }
}
