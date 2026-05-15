package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-reducer + cross-module-cascade tests for [RecordingModule].
 *
 * K-1 (no mocking framework) + K-4 (pure JVM, no Android Context) compliant.
 * The reducer is a pure function; tests construct `(state, action, ctx)`
 * and assert against the `TransitionResult`.
 *
 * **Test coverage (Plan-AC mapping):**
 *
 * - Idle → Preparing: StartRecording emits Preparing-state + AllocateMediaRecorder
 * - Preparing → Active: MediaRecorderReady emits Active-state + StartTimer/Amp/Border
 * - Preparing → Idle (cancel): CancelRecording emits Idle + Release + DeleteAudioFile
 * - Active → Paused: PauseRecording emits Paused-state + 4 pause effects
 * - Active → Idle (stop): StopRecording emits Idle + 4 stop effects
 * - Active → Idle (cancel): CancelRecording emits Idle + 5 effects (incl. DeleteAudioFile)
 * - Paused → Active: ResumeRecording
 * - Paused → Idle (stop): StopRecording (Issue 2.0.8)
 * - Paused → Idle (cancel): CancelRecording with DeleteAudioFile
 * - Cross-module cascade: Idle → Preparing emits ResetSuppressBit (KG-RSB-2-Fix)
 * - No cascade on Preparing → Idle, Active → Idle, etc.
 * - reduceFailure: AllocateMediaRecorder failure during Preparing rolls back to Idle
 * - reduceFailure: StopMediaRecorder failure during Active/Paused rolls back to Idle
 * - reduceFailure: other failures return null (default Rejected)
 * - useBluetooth invariant: captured at Preparing time, propagated through Active/Paused
 * - F-10 (Epic §4 Block A2): sessionId minted at StartRecording is carried
 *   through Preparing/Active/Paused and read on StopRecordingAndSend
 *   (which carries no payload); survives a full Pause/Resume round-trip
 *
 * @see net.devemperor.dictate.state.RecordingModule
 */
class RecordingModuleTest {

    private val testFile = File("/tmp/test-rec.m4a")
    private val module = RecordingModule

    private fun ctx(state: DictateUiState = DictateUiState.initial()) =
        ReducerContext(global = state, now = 1_000_000L)

    // ─── Idle → Preparing ────────────────────────────────────────────────

    @Test
    fun `StartRecording from Idle (BT-mic) defers AllocateMediaRecorder until SCO resolves`() {
        // C6-IMPL-1 / B2-C6-W1 — with useBluetoothMic=true the recorder
        // allocation is DEFERRED (no AllocateMediaRecorder yet): allocating
        // VOICE_COMMUNICATION before SCO is connected silently records the
        // phone mic (gate-RED silent-quality-loss). The SCO handshake is
        // kicked by AudioModule's RecordingStarted cascade; the deferred
        // allocate fires on ScoRouteResolved.
        val global = DictateUiState.initial()
            .copy(audio = AudioState(useBluetoothMic = true))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = testFile,
                sessionId = "sid-start-42",
            ),
            ctx = ctx(global),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(true, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        // F-10 — the caller-minted sessionId is carried into the FSM.
        assertEquals("sid-start-42", next.sessionId)
        // awaitingSco set; target carried for the deferred allocate.
        assertEquals(true, next.awaitingSco)
        assertEquals(InsertionTarget.INPUT_CONNECTION, next.target)
        // No AllocateMediaRecorder yet — it fires on ScoRouteResolved.
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `StartRecording from Idle (non-BT) allocates immediately, no SCO wait`() {
        // Non-BT path unchanged: immediate AllocateMediaRecorder(MIC),
        // awaitingSco=false, target consumed by the synchronous effect.
        val global = DictateUiState.initial()
            .copy(audio = AudioState(useBluetoothMic = false))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = testFile,
                sessionId = "sid-start-42",
            ),
            ctx = ctx(global),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(false, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        assertEquals("sid-start-42", next.sessionId)
        assertEquals(false, next.awaitingSco)
        assertNull(next.target)
        val effect = result.sideEffects.single() as RecordingModule.Effect.AllocateMediaRecorder
        assertEquals(InsertionTarget.INPUT_CONNECTION, effect.target)
        assertEquals(false, effect.useBluetooth)
        assertEquals(testFile, effect.audioFile)
    }

    @Test
    fun `StartRecording captures audio_useBluetoothMic = false correctly`() {
        val global = DictateUiState.initial().copy(audio = AudioState(useBluetoothMic = false))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, testFile, sessionId = "sid-test"),
            ctx = ctx(global),
        )
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(false, next.useBluetooth)
    }

    // ─── BT-SCO Preparing handshake (C6-IMPL-1 / B2-C6-W1) ──────────────

    @Test
    fun `ScoRouteResolved(true) from awaiting Preparing allocates VOICE_COMMUNICATION`() {
        val state = RecordingState.Preparing(
            useBluetooth = true,
            audioFile = testFile,
            sessionId = "sid-sco",
            awaitingSco = true,
            target = InsertionTarget.INPUT_CONNECTION,
        )
        val result = module.reduce(
            state = state,
            action = Action.RecordingAction.ScoRouteResolved(useBluetooth = true),
            ctx = ctx(),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(true, next.useBluetooth)
        // awaitingSco cleared so a duplicate resolve is a no-op.
        assertEquals(false, next.awaitingSco)
        val effect = result.sideEffects.single() as RecordingModule.Effect.AllocateMediaRecorder
        assertEquals(true, effect.useBluetooth)
        assertEquals(InsertionTarget.INPUT_CONNECTION, effect.target)
        assertEquals(testFile, effect.audioFile)
    }

    @Test
    fun `ScoRouteResolved(false) from awaiting Preparing falls back to MIC`() {
        // SCO fail / timeout → MIC fallback (legacy onScoFailed parity).
        val state = RecordingState.Preparing(
            useBluetooth = true,
            audioFile = testFile,
            sessionId = "sid-sco",
            awaitingSco = true,
            target = InsertionTarget.INPUT_CONNECTION,
        )
        val result = module.reduce(
            state = state,
            action = Action.RecordingAction.ScoRouteResolved(useBluetooth = false),
            ctx = ctx(),
        )
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(false, next.useBluetooth)
        assertEquals(false, next.awaitingSco)
        val effect = result.sideEffects.single() as RecordingModule.Effect.AllocateMediaRecorder
        assertEquals(false, effect.useBluetooth)
    }

    @Test
    fun `ScoRouteResolved when not awaiting is a no-op (duplicate broadcast guard)`() {
        // A late/duplicate SCO broadcast after we already allocated must
        // NOT fire a second AllocateMediaRecorder.
        val state = RecordingState.Preparing(
            useBluetooth = true,
            audioFile = testFile,
            sessionId = "sid-sco",
            awaitingSco = false,
            target = null,
        )
        val result = module.reduce(
            state = state,
            action = Action.RecordingAction.ScoRouteResolved(useBluetooth = true),
            ctx = ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `F-7 StartRecording with a blank sessionId throws`() {
        // Regression guard: B3 will route the IME's preAllocatedId into
        // StartRecording; a blank id there would silently re-introduce the
        // F-10 empty-string sentinel. The reducer fails fast at the FSM
        // entry point so the invariant cannot be re-entered via B3.
        assertThrows(IllegalArgumentException::class.java) {
            module.reduce(
                state = RecordingState.Idle,
                action = Action.RecordingAction.StartRecording(
                    target = InsertionTarget.INPUT_CONNECTION,
                    audioFile = testFile,
                    sessionId = "",
                ),
                ctx = ctx(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            module.reduce(
                state = RecordingState.Idle,
                action = Action.RecordingAction.StartRecording(
                    target = InsertionTarget.INPUT_CONNECTION,
                    audioFile = testFile,
                    sessionId = "   ",
                ),
                ctx = ctx(),
            )
        }
    }

    @Test
    fun `Idle rejects non-Start actions`() {
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StopRecording,
            ctx = ctx(),
        )
        assertNull(result)
    }

    // ─── Preparing → Active / Idle ─────────────────────────────────────

    @Test
    fun `MediaRecorderReady from Preparing emits Active + 4 start effects + Recording notification`() {
        // B3-VAL-W1 F-10: StartMediaRecorder added to the Preparing →
        // Active side-effect set so the subsystem-level start() runs
        // in the orchestrator-driven flow.
        // C5 / C4-IMPL-1: + Effect.UpdateNotification(Recording) so the
        // §7.6 Recording-Active FGS notification surfaces once the
        // recorder is confirmed alive (AC-2).
        val state = RecordingState.Preparing(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(
            state = state,
            action = Action.RecordingAction.MediaRecorderReady(audioFile = testFile),
            ctx = ctx(),
        )
        val next = result!!.nextState as RecordingState.Active
        assertEquals(false, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        assertEquals(5, result.sideEffects.size)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartTimer))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartAmplitudeStream))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartBorderGlow))
        assertTrue(
            result.sideEffects.contains(
                RecordingModule.Effect.UpdateNotification(
                    NotificationStatus.Recording("sid-test"),
                ),
            ),
        )
    }

    @Test
    fun `CancelRecording from Preparing emits Idle + Release + DeleteAudioFile`() {
        val state = RecordingState.Preparing(useBluetooth = true, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(
            state = state,
            action = Action.RecordingAction.CancelRecording,
            ctx = ctx(),
        )
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.ReleaseMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DeleteAudioFile(testFile)))
    }

    // ─── Active → Paused / Idle ────────────────────────────────────────

    @Test
    fun `PauseRecording from Active emits Paused + 4 pause effects + Paused notification`() {
        // C5 / C4-IMPL-1: + Effect.UpdateNotification(Paused) — the §7.6
        // Recording-Paused row swaps the action set to [Resume][Stopp][Senden].
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(state, Action.RecordingAction.PauseRecording, ctx())
        val next = result!!.nextState as RecordingState.Paused
        assertEquals(false, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        assertEquals(5, result.sideEffects.size)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.PauseMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.PauseTimer))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.PauseBorderGlow))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StopAmplitudeStream))
        assertTrue(
            result.sideEffects.contains(
                RecordingModule.Effect.UpdateNotification(
                    NotificationStatus.Paused("sid-test"),
                ),
            ),
        )
    }

    @Test
    fun `StopRecording from Active emits Idle + 4 stop effects + DismissNotification`() {
        // C5 — stop-without-send: recording discarded, no pipeline
        // hand-off, so the FGS notification is torn down (the
        // StopRecordingAndSend arm deliberately does NOT dismiss — the
        // pipeline trigger re-shows on the same NOTIF_ID).
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(state, Action.RecordingAction.StopRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertEquals(5, result.sideEffects.size)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StopMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DismissNotification))
    }

    @Test
    fun `F-10 StopRecordingAndSend from Active uses the FSM sessionId not an action payload`() {
        // F-10 (Epic §4 Block A2): StopRecordingAndSend carries NO payload.
        // The EmitPipelineTrigger sessionId is read off RecordingState.Active
        // — the same id minted at StartRecording — not from the action.
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-active")
        val result = module.reduce(
            state,
            Action.RecordingAction.StopRecordingAndSend,
            ctx(),
        )
        assertEquals(RecordingState.Idle, result!!.nextState)
        // F-2 — the "Send" semantic produces an EmitPipelineTrigger effect
        // on top of the StopRecording effects so the pipeline takes over
        // once recording is stopped. The sessionId is the FSM's, not "".
        assertTrue(
            result.sideEffects.contains(
                RecordingModule.Effect.EmitPipelineTrigger(
                    sessionId = "sid-active",
                    audioFile = testFile,
                ),
            ),
        )
        // Plain stop effects still fire.
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StopMediaRecorder))
    }

    @Test
    fun `F-10 StopRecordingAndSend from Paused uses the FSM sessionId`() {
        val state = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-paused")
        val result = module.reduce(
            state,
            Action.RecordingAction.StopRecordingAndSend,
            ctx(),
        )
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(
            result.sideEffects.contains(
                RecordingModule.Effect.EmitPipelineTrigger(
                    sessionId = "sid-paused",
                    audioFile = testFile,
                ),
            ),
        )
    }

    @Test
    fun `F-10 sessionId minted at StartRecording survives the full FSM round-trip`() {
        // The clean-source contract: StartRecording.sessionId → Preparing →
        // (MediaRecorderReady) → Active → (Pause) → Paused → (Resume) →
        // Active → (StopRecordingAndSend) → EmitPipelineTrigger carries the
        // SAME id end-to-end.
        val sid = "sid-roundtrip-42"
        val prep = module.reduce(
            RecordingState.Idle,
            Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, testFile, sessionId = sid),
            ctx(),
        )!!.nextState as RecordingState.Preparing
        assertEquals(sid, prep.sessionId)

        val active = module.reduce(
            prep,
            Action.RecordingAction.MediaRecorderReady(audioFile = testFile),
            ctx(),
        )!!.nextState as RecordingState.Active
        assertEquals(sid, active.sessionId)

        val paused = module.reduce(active, Action.RecordingAction.PauseRecording, ctx())!!
            .nextState as RecordingState.Paused
        assertEquals(sid, paused.sessionId)

        val resumed = module.reduce(paused, Action.RecordingAction.ResumeRecording, ctx())!!
            .nextState as RecordingState.Active
        assertEquals(sid, resumed.sessionId)

        val stopped = module.reduce(resumed, Action.RecordingAction.StopRecordingAndSend, ctx())!!
        assertEquals(RecordingState.Idle, stopped.nextState)
        assertTrue(
            stopped.sideEffects.contains(
                RecordingModule.Effect.EmitPipelineTrigger(sessionId = sid, audioFile = testFile),
            ),
        )
    }

    @Test
    fun `CancelRecording from Active emits Idle + Stop effects + DeleteAudioFile`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(state, Action.RecordingAction.CancelRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DeleteAudioFile(testFile)))
    }

    // ─── Paused → Active / Idle ────────────────────────────────────────

    @Test
    fun `ResumeRecording from Paused emits Active + 4 resume effects`() {
        val state = RecordingState.Paused(useBluetooth = true, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(state, Action.RecordingAction.ResumeRecording, ctx())
        val next = result!!.nextState as RecordingState.Active
        assertEquals(true, next.useBluetooth)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.ResumeMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.ResumeTimer))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.ResumeBorderGlow))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartAmplitudeStream))
    }

    @Test
    fun `StopRecording from Paused emits Idle + stop effects (no delete - Issue 2_0_8)`() {
        val state = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(state, Action.RecordingAction.StopRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        // No DeleteAudioFile on Stop (Paused holds a valid recording)
        assertTrue(result.sideEffects.none { it is RecordingModule.Effect.DeleteAudioFile })
    }

    @Test
    fun `CancelRecording from Paused emits Idle + stop effects + DeleteAudioFile`() {
        val state = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val result = module.reduce(state, Action.RecordingAction.CancelRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DeleteAudioFile(testFile)))
    }

    // ─── C5 / C4-IMPL-1 — recording-phase FGS notification emission ─────
    //
    // Spec 1 §7.6 Recording-Active / Recording-Paused. The recording FSM
    // is the on-path owner of the recording-phase notification once C5
    // flips the IME trigger to dispatch. The Recording→Pipeline hand-off
    // is seamless (StopRecordingAndSend does NOT dismiss — the
    // EmitPipelineTrigger → PipelineModule re-show()s a Pipeline status on
    // the same NOTIF_ID).

    @Test
    fun `C5 ResumeRecording re-shows the Recording notification`() {
        val state = RecordingState.Paused(useBluetooth = true, audioFile = testFile, sessionId = "sid-resume")
        val result = module.reduce(state, Action.RecordingAction.ResumeRecording, ctx())!!
        assertTrue(
            "Resume swaps the §7.6 notification back to Recording-Active ([Pause]…)",
            result.sideEffects.contains(
                RecordingModule.Effect.UpdateNotification(
                    NotificationStatus.Recording("sid-resume"),
                ),
            ),
        )
    }

    @Test
    fun `C5 StopRecordingAndSend does NOT dismiss (seamless Recording to Pipeline hand-off)`() {
        // The pipeline trigger re-shows a Pipeline status on the same
        // NOTIF_ID; a dismiss here would cause a visible flicker / a
        // window with no FGS notification (FGS-5s-Frist risk, R-2).
        val active = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-send")
        val r1 = module.reduce(active, Action.RecordingAction.StopRecordingAndSend, ctx())!!
        assertTrue(
            "StopRecordingAndSend from Active must NOT emit DismissNotification",
            r1.sideEffects.none { it is RecordingModule.Effect.DismissNotification },
        )
        assertTrue(
            r1.sideEffects.contains(
                RecordingModule.Effect.EmitPipelineTrigger("sid-send", testFile),
            ),
        )
        val paused = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-send2")
        val r2 = module.reduce(paused, Action.RecordingAction.StopRecordingAndSend, ctx())!!
        assertTrue(
            "StopRecordingAndSend from Paused must NOT emit DismissNotification",
            r2.sideEffects.none { it is RecordingModule.Effect.DismissNotification },
        )
    }

    @Test
    fun `C5 CancelRecording dismisses the notification from every recording state`() {
        // Discard (no pipeline hand-off) tears the FGS notification down.
        val prep = RecordingState.Preparing(useBluetooth = false, audioFile = testFile, sessionId = "s")
        assertTrue(
            module.reduce(prep, Action.RecordingAction.CancelRecording, ctx())!!
                .sideEffects.contains(RecordingModule.Effect.DismissNotification),
        )
        val active = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "s")
        assertTrue(
            module.reduce(active, Action.RecordingAction.CancelRecording, ctx())!!
                .sideEffects.contains(RecordingModule.Effect.DismissNotification),
        )
        val paused = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "s")
        assertTrue(
            module.reduce(paused, Action.RecordingAction.CancelRecording, ctx())!!
                .sideEffects.contains(RecordingModule.Effect.DismissNotification),
        )
    }

    @Test
    fun `C5 StopRecording from Paused dismisses the notification`() {
        val state = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "s")
        val result = module.reduce(state, Action.RecordingAction.StopRecording, ctx())!!
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DismissNotification))
    }

    @Test
    fun `C5 UpdateNotification and DismissNotification reach the coordinator subsystem`() {
        // runEffect wiring: the recording-phase effects drive the SAME
        // notificationCoordinator subsystem PipelineModule uses. K-1
        // handwritten capturing fake (no mocking framework).
        val showCalls = mutableListOf<NotificationStatus>()
        var dismissCount = 0
        val capturingCoordinator = object : net.devemperor.dictate.state.PipelineNotificationCoordinatorSubsystem {
            override fun show(status: NotificationStatus) { showCalls += status }
            override fun dismiss() { dismissCount++ }
        }
        val services = net.devemperor.dictate.testutil.fakeModuleServices(
            notificationCoordinator = capturingCoordinator,
        )
        module.runEffect(
            RecordingModule.Effect.UpdateNotification(NotificationStatus.Recording("sid-x")),
            services,
        )
        module.runEffect(RecordingModule.Effect.DismissNotification, services)
        assertEquals(listOf<NotificationStatus>(NotificationStatus.Recording("sid-x")), showCalls)
        assertEquals(1, dismissCount)
    }

    // ─── Cross-module cascade (KG-RSB-2-Fix verification) ───────────────

    @Test
    fun `cross-module Idle to Preparing cascades ResetSuppressBit`() {
        val prev = DictateUiState.initial()  // recording = Idle
        val next = prev.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = testFile, sessionId = "sid-test"),
        )
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(listOf<Action>(Action.OverlayAction.ResetSuppressBit), cascade)
    }

    @Test
    fun `cross-module Preparing to Active does NOT cascade`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Preparing(false, testFile, sessionId = "sid-test"))
        val next = prev.copy(
            recording = RecordingState.Active(false, testFile, sessionId = "sid-test"),
        )
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module Active to Idle does NOT cascade`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Active(false, testFile, sessionId = "sid-test"))
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module Preparing to Idle (cancel) does NOT cascade ResetSuppressBit`() {
        // Boundary check is strictly forward: Idle → Preparing is the
        // only cascade trigger. Cancel-during-Preparing must NOT fire.
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Preparing(false, testFile, sessionId = "sid-test"))
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── reduceFailure ──────────────────────────────────────────────────

    @Test
    fun `reduceFailure AllocateMediaRecorder during Preparing rolls back to Idle`() {
        val state = RecordingState.Preparing(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val failure = Action.EffectFailure(
            originModuleId = ModuleId.Recording,
            effect = "AllocateMediaRecorder(target=INPUT_CONNECTION, useBluetooth=false, audioFile=/tmp/test-rec.m4a)",
            reason = "MIC permission revoked",
        )
        val result = module.reduceFailure(state, failure, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.ReleaseMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DeleteAudioFile(testFile)))
    }

    @Test
    fun `reduceFailure StopMediaRecorder during Active rolls back to Idle (keeps file)`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val failure = Action.EffectFailure(
            originModuleId = ModuleId.Recording,
            effect = "StopMediaRecorder",
            reason = "too short",
        )
        val result = module.reduceFailure(state, failure, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        // No DeleteAudioFile — file may be partially valid.
        assertTrue(result.sideEffects.none { it is RecordingModule.Effect.DeleteAudioFile })
    }

    @Test
    fun `reduceFailure StopMediaRecorder during Paused also rolls back`() {
        val state = RecordingState.Paused(useBluetooth = true, audioFile = testFile, sessionId = "sid-test")
        val failure = Action.EffectFailure(ModuleId.Recording, "StopMediaRecorder", "x")
        val result = module.reduceFailure(state, failure, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
    }

    @Test
    fun `reduceFailure unknown effect returns null (default Rejected)`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test")
        val failure = Action.EffectFailure(ModuleId.Recording, "PauseMediaRecorder", "x")
        assertNull(module.reduceFailure(state, failure, ctx()))
    }

    @Test
    fun `reduceFailure StopMediaRecorder while Idle returns null (no rollback needed)`() {
        // Defensive: the orchestrator might route a stale stop-failure
        // after the state already left Active. No rollback semantics.
        val failure = Action.EffectFailure(ModuleId.Recording, "StopMediaRecorder", "x")
        assertNull(module.reduceFailure(RecordingState.Idle, failure, ctx()))
    }

    // ─── Lens / IDs ─────────────────────────────────────────────────────

    @Test
    fun `module id is Recording`() {
        assertEquals(ModuleId.Recording, module.id)
    }

    @Test
    fun `lens round-trip preserves recording axis`() {
        val state = DictateUiState.initial()
            .copy(recording = RecordingState.Active(true, testFile, sessionId = "sid-test"))
        val sub = module.read(state)
        val back = module.write(state, RecordingState.Idle)
        assertEquals(RecordingState.Active(true, testFile, sessionId = "sid-test"), sub)
        assertEquals(RecordingState.Idle, back.recording)
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(RecordingState.Idle, module.initialState())
    }
}
