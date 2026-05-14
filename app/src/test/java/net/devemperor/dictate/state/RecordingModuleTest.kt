package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `StartRecording from Idle emits Preparing + AllocateMediaRecorder`() {
        val global = DictateUiState.initial()
            .copy(audio = AudioState(useBluetoothMic = true))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = testFile,
            ),
            ctx = ctx(global),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(true, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        // Allocate effect carries the same 3 args
        val effect = result.sideEffects.single() as RecordingModule.Effect.AllocateMediaRecorder
        assertEquals(InsertionTarget.INPUT_CONNECTION, effect.target)
        assertEquals(true, effect.useBluetooth)
        assertEquals(testFile, effect.audioFile)
    }

    @Test
    fun `StartRecording captures audio_useBluetoothMic = false correctly`() {
        val global = DictateUiState.initial().copy(audio = AudioState(useBluetoothMic = false))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, testFile),
            ctx = ctx(global),
        )
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(false, next.useBluetooth)
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
    fun `MediaRecorderReady from Preparing emits Active + 3 start effects`() {
        val state = RecordingState.Preparing(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(
            state = state,
            action = Action.RecordingAction.MediaRecorderReady(audioFile = testFile),
            ctx = ctx(),
        )
        val next = result!!.nextState as RecordingState.Active
        assertEquals(false, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        assertEquals(3, result.sideEffects.size)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartTimer))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartAmplitudeStream))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StartBorderGlow))
    }

    @Test
    fun `CancelRecording from Preparing emits Idle + Release + DeleteAudioFile`() {
        val state = RecordingState.Preparing(useBluetooth = true, audioFile = testFile)
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
    fun `PauseRecording from Active emits Paused + 4 pause effects`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(state, Action.RecordingAction.PauseRecording, ctx())
        val next = result!!.nextState as RecordingState.Paused
        assertEquals(false, next.useBluetooth)
        assertEquals(testFile, next.audioFile)
        assertEquals(4, result.sideEffects.size)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.PauseMediaRecorder))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.PauseTimer))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.PauseBorderGlow))
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StopAmplitudeStream))
    }

    @Test
    fun `StopRecording from Active emits Idle + 4 stop effects`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(state, Action.RecordingAction.StopRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertEquals(4, result.sideEffects.size)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.StopMediaRecorder))
    }

    @Test
    fun `StopRecordingAndSend from Active also drops to Idle`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(state, Action.RecordingAction.StopRecordingAndSend, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
    }

    @Test
    fun `CancelRecording from Active emits Idle + Stop effects + DeleteAudioFile`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(state, Action.RecordingAction.CancelRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DeleteAudioFile(testFile)))
    }

    // ─── Paused → Active / Idle ────────────────────────────────────────

    @Test
    fun `ResumeRecording from Paused emits Active + 4 resume effects`() {
        val state = RecordingState.Paused(useBluetooth = true, audioFile = testFile)
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
        val state = RecordingState.Paused(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(state, Action.RecordingAction.StopRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        // No DeleteAudioFile on Stop (Paused holds a valid recording)
        assertTrue(result.sideEffects.none { it is RecordingModule.Effect.DeleteAudioFile })
    }

    @Test
    fun `CancelRecording from Paused emits Idle + stop effects + DeleteAudioFile`() {
        val state = RecordingState.Paused(useBluetooth = false, audioFile = testFile)
        val result = module.reduce(state, Action.RecordingAction.CancelRecording, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertTrue(result.sideEffects.contains(RecordingModule.Effect.DeleteAudioFile(testFile)))
    }

    // ─── Cross-module cascade (KG-RSB-2-Fix verification) ───────────────

    @Test
    fun `cross-module Idle to Preparing cascades ResetSuppressBit`() {
        val prev = DictateUiState.initial()  // recording = Idle
        val next = prev.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = testFile),
        )
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(listOf<Action>(Action.OverlayAction.ResetSuppressBit), cascade)
    }

    @Test
    fun `cross-module Preparing to Active does NOT cascade`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Preparing(false, testFile))
        val next = prev.copy(
            recording = RecordingState.Active(false, testFile),
        )
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module Active to Idle does NOT cascade`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Active(false, testFile))
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module Preparing to Idle (cancel) does NOT cascade ResetSuppressBit`() {
        // Boundary check is strictly forward: Idle → Preparing is the
        // only cascade trigger. Cancel-during-Preparing must NOT fire.
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Preparing(false, testFile))
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── reduceFailure ──────────────────────────────────────────────────

    @Test
    fun `reduceFailure AllocateMediaRecorder during Preparing rolls back to Idle`() {
        val state = RecordingState.Preparing(useBluetooth = false, audioFile = testFile)
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
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile)
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
        val state = RecordingState.Paused(useBluetooth = true, audioFile = testFile)
        val failure = Action.EffectFailure(ModuleId.Recording, "StopMediaRecorder", "x")
        val result = module.reduceFailure(state, failure, ctx())
        assertEquals(RecordingState.Idle, result!!.nextState)
    }

    @Test
    fun `reduceFailure unknown effect returns null (default Rejected)`() {
        val state = RecordingState.Active(useBluetooth = false, audioFile = testFile)
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
            .copy(recording = RecordingState.Active(true, testFile))
        val sub = module.read(state)
        val back = module.write(state, RecordingState.Idle)
        assertEquals(RecordingState.Active(true, testFile), sub)
        assertEquals(RecordingState.Idle, back.recording)
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(RecordingState.Idle, module.initialState())
    }
}
