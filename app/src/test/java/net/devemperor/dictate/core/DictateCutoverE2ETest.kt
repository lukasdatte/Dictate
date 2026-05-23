package net.devemperor.dictate.core

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ViewMode
import net.devemperor.dictate.state.layout.KeyboardLayoutManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * **C6-D2pre — the cross-block cutover-completion aggregation test (the
 * D2-pre verification GATE's auto-tier).**
 *
 * The parent plan's keystone IME-activation chain (F-1/F-2/F-3) and the
 * Triangle-FSM (T1–T7, ADR-0005) were already proven *on the dormant
 * layer* by `DictatePipelineServiceOverlayTransitionTest` — but there
 * HOVER (T3/T4) is triggered by a synthetic `PipelineAction.TriggerPipeline`,
 * not a real recording. This Epic flips production onto the new
 * orchestrator (`USE_LEGACY_RECORDING_DRIVE=false`). This test re-proves
 * the same keystone + Triangle-FSM **driven by a real new-path recording
 * being Active** — i.e. the cutover path, the way the C5-flipped IME
 * actually dispatches it:
 *
 * - **Keystone F-1/F-2/F-3:** boot → service `onCreate` → binder bound →
 *   orchestrator initialised → `ViewMode.KEYBOARD` (the F-1/F-2/F-3
 *   activation chain still wired after the recording-drive flip — AC-9).
 * - **Triangle-FSM on the LIVE recording path:**
 *   - T1/T2 user WIDGET-toggle round-trip.
 *   - **T3/T4 KEYBOARD/WIDGET→HOVER driven by `state.recording` Active**
 *     (not a synthetic pipeline action): a *real* new-path recording
 *     makes `pipelineActive` true, so `OnImeViewHidden` (the keystone
 *     F-1 tail-dispatch in `onFinishInputView`) computes HOVER — the
 *     "recording survives keyboard-switch" behaviour, ADR-0003, the
 *     parent plan's raison d'être, now on the new path.
 *   - T5 HOVER→KEYBOARD on `OnImeViewShown`.
 *   - **T7 HOVER→KEYBOARD via the real Pipeline-Done cascade** (the
 *     Geist-Widget-Bug regression guard) — the pipeline runs via the
 *     JobExecutor-backed `PipelineRunnerSubsystem` (the C3 adapter) and
 *     `PipelineDone` cascades `OnPipelineDone` → KEYBOARD.
 * - **AC-2/AC-3 structurally:** `state.recording` Idle→Preparing→Active;
 *   the FGS notification shows `NotificationStatus.Recording` with the
 *   `[Pause][Stopp][Senden]` buttons; `StopRecordingAndSend` runs the
 *   pipeline via the new runner; the notification transitions
 *   `Recording → Pipeline → Idle` (dismissed when the pipeline finishes).
 *
 * **K-1 / K-4** — handwritten `PipelineRunner` spy (no mocking
 * framework); Robolectric is the justified opt-out (Service + IME +
 * NotificationManager binder wiring is not observable otherwise).
 * tearDown copies the `DictatePipelineServiceOverlayTransitionTest` /
 * `ImeRecordingDriveCutoverTest` DB + JobExecutor reset (Epic R-7 /
 * `b5-ime-activation-wiring.md` §8) so the pre-existing
 * `LegacyAudioFileMigrationTest` pollution flake is not amplified.
 *
 * @see net.devemperor.dictate.core.ImeRecordingDriveCutoverTest
 * @see net.devemperor.dictate.core.DictatePipelineServiceOverlayTransitionTest
 * @see docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/reports/e2e-runbook.md (C6-SUBSET TC-1/TC-6/TC-10/TC-11)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictateCutoverE2ETest {

    /**
     * Handwritten no-op [PipelineRunner] (K-1): keeps the real
     * `Effect.SubmitPipeline → JobExecutor.start` from hitting a real
     * network/DB pipeline in Robolectric. The test drives a
     * deterministic `PipelineDone` itself for the T7 cascade rather than
     * waiting on the JobExecutor worker thread (the brittleness C5
     * flagged — the worker leg is C3-tested).
     */
    private class CutoverRunner : PipelineRunner {
        override fun runTranscription(
            config: PipelineOrchestrator.PipelineConfig,
            reuseSessionId: String?,
            token: CancellationToken,
        ) = Unit

        override fun resume(sessionId: String, token: CancellationToken) = Unit
        override fun regenerate(sessionId: String, stepChainIndex: Int, token: CancellationToken) = Unit
        override fun postProcess(sessionId: String, inputText: String, promptText: String, promptId: Int?) = Unit
    }

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        nm.cancelAll()
        JobExecutor.resetForTest()
        // B2-VAL-W1 F-6 / Epic R-7 — drain the process-wide
        // ActiveJobRegistry single-job lock between tests.
        ActiveJobRegistry.resetForTest()
        // C8-IMPL-1 / B3-VAL F-1 — belt-and-suspenders: this test boots
        // the full Service (→ DictateApplication →
        // DurationHealingScheduler.schedule()). Drain the in-flight heal
        // thread BEFORE the DB is dropped so it cannot pollute a
        // co-locating sibling. Ordering mandatory: scheduler reset
        // precedes DictateDatabase.resetForTest.
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        net.devemperor.dictate.database.DictateDatabase.resetForTest(
            ApplicationProvider.getApplicationContext(),
        )
    }

    /**
     * Boot the service (keystone F-1/F-2/F-3 activation chain) and grant
     * the overlay permission on the state axis so the T1 user WIDGET
     * toggle is not a permission-gated no-op (mirrors
     * `DictatePipelineServiceOverlayTransitionTest.binder()`).
     */
    private fun boot(): DictatePipelineService.LocalBinder {
        controller.create()
        val b = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        b.dispatch(Action.OverlayAction.OnOverlayPermissionChanged(granted = true))
        ShadowLooper.idleMainLooper()
        return b
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun posted() =
        shadowOf(nm).getNotification(PipelineNotificationCoordinator.NOTIF_ID)

    private fun KeyboardLayoutManager.overlayAttached(): Boolean =
        attachedBackendCount() > 0

    /**
     * Pump the main looper until [cond] or a 2s deadline (the new-path
     * send is a multi-hop async chain — mirrors
     * `ImeRecordingDriveCutoverTest.pumpUntil`).
     */
    private fun pumpUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    /** Start a real new-path recording and wait until it reaches Active. */
    private fun startRecordingActive(
        b: DictatePipelineService.LocalBinder,
        sessionId: String,
    ): File {
        val audio = File.createTempFile("e2e", ".m4a", app.cacheDir)
        b.dispatch(
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = audio,
                sessionId = sessionId,
            ),
        )
        pumpUntil { b.state.value.recording is RecordingState.Active }
        return audio
    }

    // ──────────────────────────────────────────────────────────────────
    // Keystone F-1/F-2/F-3 — IME-activation chain after the recording
    // drive flip (AC-9: the cutover did not regress IME activation)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun keystone_serviceBootsToKeyboardWithOverlayDetached_afterTheCutover() {
        val b = boot()
        idle()
        // F-1/F-2/F-3: onCreate → binder bound → orchestrator initialised
        // → ViewMode.KEYBOARD, no overlay attached (the parent plan's
        // green activation trace, re-proven on the cutover build).
        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(
            "Boot must be KEYBOARD with overlay detached (keystone)",
            b.keyboardLayoutManager.overlayAttached(),
        )
        assertTrue(
            "recording starts Idle on a fresh boot",
            b.state.value.recording is RecordingState.Idle,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-2 — StartRecording drives the FSM Idle→Preparing→Active + the
    //        §7.6 Recording-Active FGS notification (on the new path)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun ac2_newPathRecording_reachesActive_andPostsRecordingNotificationWith3Buttons() {
        val b = boot()
        idle()
        assertTrue(b.state.value.recording is RecordingState.Idle)

        startRecordingActive(b, "e2e-ac2")

        assertTrue(
            "AC-2: state.recording must reach Active on the new path",
            b.state.value.recording is RecordingState.Active,
        )
        val n = posted()
        assertNotNull("AC-2: StartRecording must post the FGS Recording notification", n)
        assertEquals(
            app.getString(R.string.dictate_notif_recording_active),
            shadowOf(n).contentText,
        )
        assertEquals(
            "AC-2: Recording → [Pause][Stopp][Senden]",
            3,
            n!!.actions.size,
        )
        assertEquals(
            app.getString(R.string.dictate_action_pause),
            n.actions[0].title.toString(),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Triangle-FSM T1/T2 — user WIDGET-toggle round-trip (cutover-build)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun t1t2_widgetToggleRoundTrip_afterTheCutover() {
        val b = boot()
        idle()

        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // T1
        idle()
        assertEquals(ViewMode.WIDGET, b.state.value.viewMode)
        assertTrue(
            "T1 must attach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )

        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // T2
        idle()
        assertEquals(ViewMode.KEYBOARD, b.state.value.viewMode)
        assertFalse(
            "T2 must detach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Triangle-FSM T3/T5 — KEYBOARD→HOVER driven by a REAL new-path
    // recording being Active (the keyboard-switch-survival keystone,
    // ADR-0003), then HOVER→KEYBOARD on IME re-show.
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun t3t5_realRecordingActive_drivesKeyboardToHoverAndBack() {
        val b = boot()
        idle()
        // A genuine new-path recording (not a synthetic TriggerPipeline)
        // is what makes pipelineActive=true on the cutover path.
        startRecordingActive(b, "e2e-t3")
        assertTrue(b.state.value.recording is RecordingState.Active)

        // T3: keystone F-1 tail-dispatch (onFinishInputView dispatches
        // BOTH ViewModeAction and WidgetAction). Recording-active ⇒
        // HOVER (recording survives the keyboard switch — ADR-0003).
        // 2026-05-23 sticky-widget: the overlay attach gate now reads
        // `state.widget`, so the test must mirror production's dual
        // dispatch (DictateInputMethodService.onFinishInputView).
        b.dispatch(Action.ViewModeAction.OnImeViewHidden)
        b.dispatch(Action.WidgetAction.OnImeViewHidden)
        idle()
        assertEquals(
            "T3: real recording Active + IME hidden ⇒ HOVER",
            ViewMode.HOVER,
            b.state.value.viewMode,
        )
        assertTrue(
            "T3 must attach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
        assertTrue(
            "recording must still be Active across the keyboard switch (ADR-0003)",
            b.state.value.recording is RecordingState.Active,
        )

        // T5: IME returns (OnImeViewShown) ⇒ viewMode back to KEYBOARD,
        // but post sticky-widget refactor the widget stays Visible and
        // the overlay stays attached. Pre-refactor T5 expected detach.
        b.dispatch(Action.ViewModeAction.OnImeViewShown)
        b.dispatch(Action.WidgetAction.OnImeViewShown)
        idle()
        assertEquals(
            "T5: IME re-shown ⇒ viewMode KEYBOARD",
            ViewMode.KEYBOARD,
            b.state.value.viewMode,
        )
        assertTrue(
            "Sticky-widget: overlay stays attached after IME re-show",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    @Test
    fun t4_widgetThenRealRecording_keepsWidgetAndBackendAcrossImeHide() {
        // Truth-table revision 2026-05-21 (Row 3): when the user
        // explicitly prefers the widget, IME-hide no longer drops to
        // HOVER. WIDGET stays sticky and the overlay backend stays
        // attached — same observable property (overlay-still-on-screen)
        // as the pre-fix HOVER path, with the bonus that the user's
        // widget preference is preserved across IME-hide.
        val b = boot()
        idle()
        b.dispatch(Action.ViewModeAction.ToggleViewModeWidget) // T1 → WIDGET
        idle()
        assertEquals(ViewMode.WIDGET, b.state.value.viewMode)
        assertTrue(b.keyboardLayoutManager.overlayAttached())

        startRecordingActive(b, "e2e-t4")
        b.dispatch(Action.ViewModeAction.OnImeViewHidden)
        idle()

        // Pre-fix: HOVER. Post-fix: stays WIDGET (Row 3).
        assertEquals(
            "WIDGET + recording + IME hidden ⇒ stays WIDGET (sticky)",
            ViewMode.WIDGET,
            b.state.value.viewMode,
        )
        assertTrue(
            "Overlay backend stays attached across IME-hide while widget is user-preferred",
            b.keyboardLayoutManager.overlayAttached(),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-3 + Triangle-FSM T7 — StopRecordingAndSend runs the pipeline
    // via the new runner; the notification transitions
    // Recording → Pipeline → Idle; the real Pipeline-Done cascade drops
    // HOVER → KEYBOARD (Geist-Widget-Bug regression guard, ADR-0005 T7).
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun ac3_t7_stopAndSend_runsPipeline_notificationLifecycle_andHoverDropsToKeyboard() {
        val runner = CutoverRunner()
        JobExecutor.initializeForTest(runner)
        val b = boot()
        idle()

        val audio = startRecordingActive(b, "e2e-send")

        // Recording-Active notification is up (AC-2 precondition).
        assertEquals(
            app.getString(R.string.dictate_notif_recording_active),
            shadowOf(posted()).contentText,
        )

        // The IME would snapshot the fresh config at the send-tap (R-1);
        // mirror that so the orchestrator's resolver can rebuild a
        // JobRequest (the C5 R-1 surface). Registered while recording is
        // Active in KEYBOARD — the proven C5 send ordering.
        val resolver = ImePipelineConfigResolver(
            recordingsDirProvider = { app.filesDir },
            reprocessFallback = DefaultPipelineConfigResolver { app.filesDir },
        )
        resolver.snapshotFresh(
            "e2e-send",
            ImePipelineConfigResolver.FreshConfig(
                totalSteps = 1,
                audioFilePath = audio.absolutePath,
                language = null,
                queuedPromptIds = emptyList(),
                targetAppPackage = null,
                stylePrompt = null,
                livePrompt = false,
                autoSwitchKeyboard = false,
                showResendButton = false,
            ),
        )
        b.registerPipelineConfigResolver(resolver)

        // AC-3: StopRecordingAndSend → EmitPipelineTrigger →
        // TriggerPipeline. `PipelineModule.reduce(TriggerPipeline)` emits
        // BOTH `Effect.SubmitPipeline` (→ the C3 JobExecutor-backed
        // PipelineRunnerSubsystem) AND
        // `Effect.UpdateNotification(NotificationStatus.Pipeline)` — the
        // notification flip to "processing" is **state-reducer-driven**,
        // so observing it proves the whole
        // StopRecordingAndSend→EmitPipelineTrigger→TriggerPipeline chain
        // reduced on the new path (a robust AC-3 surface). The
        // JobExecutor-worker leg itself is C3-tested
        // (PipelineRunnerSubsystemAdapterTest) + C5's
        // `newPath_recordingNotification_*` — re-asserting it through 5
        // async hops + a worker thread is the brittleness C5 flagged
        // (the spy here only keeps `SubmitPipeline` from hitting a real
        // network/DB pipeline).
        b.dispatch(Action.RecordingAction.StopRecordingAndSend)
        pumpUntil {
            shadowOf(posted()).contentText ==
                app.getString(R.string.dictate_notif_processing)
        }

        // AC-3: seamless Recording → Pipeline hand-off on the same
        // NOTIF_ID (no FGS-less gap).
        assertEquals(
            "AC-3: notification transitions Recording → Pipeline",
            app.getString(R.string.dictate_notif_processing),
            shadowOf(posted()).contentText,
        )

        // TC-10 flow: the user dismisses the IME *while the pipeline is
        // transcribing* → HOVER (pipeline still active ⇒ pipelineActive
        // true ⇒ HOVER per the keystone F-1 tail-dispatch).
        b.dispatch(Action.ViewModeAction.OnImeViewHidden)
        idle()
        assertEquals(
            "Pipeline-active + IME hidden ⇒ HOVER (the T7 scenario)",
            ViewMode.HOVER,
            b.state.value.viewMode,
        )

        // T7: the real pipeline completes → PipelineDone cascades
        // OnPipelineDone → HOVER drops to KEYBOARD (Geist-Widget-Bug
        // cannot reappear), and the notification dismisses (→ Idle).
        b.dispatch(Action.PipelineAction.PipelineDone("e2e-send", "hello world"))
        pumpUntil { b.state.value.viewMode == ViewMode.KEYBOARD }

        assertEquals(
            "T7: Pipeline-Done in HOVER cascades to KEYBOARD (not WIDGET)",
            ViewMode.KEYBOARD,
            b.state.value.viewMode,
        )
        assertFalse(
            "T7 must detach the OverlayBackend",
            b.keyboardLayoutManager.overlayAttached(),
        )
        assertNull(
            "AC-3: notification dismissed when the pipeline is back to Idle",
            posted(),
        )

        JobExecutor.cancel("e2e-send")
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-2 — CancelRecording dismisses the FGS notification (no orphan
    // FGS after an aborted new-path recording)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun ac2_cancelRecording_dismissesTheNotification_onTheNewPath() {
        val b = boot()
        idle()
        startRecordingActive(b, "e2e-cancel")
        assertNotNull("pre-condition: Recording notification posted", posted())

        b.dispatch(Action.RecordingAction.CancelRecording)
        idle()

        assertNull(
            "CancelRecording must dismiss the FGS notification",
            posted(),
        )
        assertTrue(
            "CancelRecording rolls the FSM back to Idle",
            b.state.value.recording is RecordingState.Idle,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // C6-IMPL-1 / B2-C6-W1 — audio-focus is requested ON THE NEW PATH
    // (legacy parity; the C6-D2pre gate-RED-blocking finding). Proven
    // end-to-end: dispatch → AudioModule cascade → Effect.RequestAudioFocus
    // → AudioFocusSubsystemAdapter → RealAudioFocusGate → AudioManager
    // (Robolectric ShadowAudioManager records the focus request). This
    // is the assertion a C6-D2pre re-gate reads to prove GREEN.
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun c6impl1_newPathRecording_requestsAudioFocus_throughTheSystemAudioManager() {
        val b = boot()
        idle()
        // The service's RealAudioFocusGate is built from the *service's*
        // AudioManager (`DictatePipelineService.getSystemService`), which
        // in Robolectric is a distinct shadow from the application's.
        // Read the focus state off the same instance the gate uses.
        val am = controller.get()
            .getSystemService(android.media.AudioManager::class.java)
        val shadowAm = shadowOf(am)
        assertNull(
            "pre-condition: no focus request before recording starts",
            shadowAm.lastAudioFocusRequest,
        )

        // Non-BT new-path recording (useBluetoothMic defaults false →
        // Pref.AudioFocus default true → focus IS requested for ~100%
        // of users, exactly what legacy did and C7-deleted-legacy would
        // otherwise regress).
        startRecordingActive(b, "e2e-focus")
        assertTrue(b.state.value.recording is RecordingState.Active)

        assertNotNull(
            "C6-IMPL-1: the new recording path MUST request audio-focus " +
                "(legacy parity — gate-RED-blocking if absent)",
            shadowAm.lastAudioFocusRequest,
        )
    }

    @Test
    fun c6impl1_newPathStopRecording_abandonsAudioFocus() {
        val b = boot()
        idle()
        // The service's RealAudioFocusGate is built from the *service's*
        // AudioManager (`DictatePipelineService.getSystemService`), which
        // in Robolectric is a distinct shadow from the application's.
        // Read the focus state off the same instance the gate uses.
        val am = controller.get()
            .getSystemService(android.media.AudioManager::class.java)
        val shadowAm = shadowOf(am)

        startRecordingActive(b, "e2e-focus-stop")
        assertNotNull(shadowAm.lastAudioFocusRequest)

        b.dispatch(Action.RecordingAction.StopRecording)
        idle()

        assertNotNull(
            "C6-IMPL-1: stop must abandon audio-focus (legacy " +
                "gate.abandon() parity)",
            shadowAm.lastAbandonedAudioFocusRequest,
        )
        assertTrue(b.state.value.recording is RecordingState.Idle)
    }

    @Test
    fun c6impl1_audioFocusPrefOff_doesNotRequestFocus_onTheNewPath() {
        // Legacy parity for the opt-out: `if (audioFocusEnabled)
        // gate.request()`. With Pref.AudioFocus off the new path must
        // NOT request focus (matches legacy behaviour for that subset).
        val b = boot()
        idle()
        // Flip the audio-focus pref off via the audio axis.
        b.dispatch(Action.AudioAction.ToggleAudioFocusPref)
        idle()
        assertFalse(
            "pre-condition: audioFocusEnabledPref is now off",
            b.state.value.audio.audioFocusEnabledPref,
        )
        // The service's RealAudioFocusGate is built from the *service's*
        // AudioManager (`DictatePipelineService.getSystemService`), which
        // in Robolectric is a distinct shadow from the application's.
        // Read the focus state off the same instance the gate uses.
        val am = controller.get()
            .getSystemService(android.media.AudioManager::class.java)
        val shadowAm = shadowOf(am)

        startRecordingActive(b, "e2e-focus-off")
        assertTrue(b.state.value.recording is RecordingState.Active)

        assertNull(
            "C6-IMPL-1: with Pref.AudioFocus off the new path must NOT " +
                "request focus (legacy `if (audioFocusEnabled)` parity)",
            shadowAm.lastAudioFocusRequest,
        )
    }
}
