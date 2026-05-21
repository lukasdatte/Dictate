package net.devemperor.dictate.state

import net.devemperor.dictate.audio.CodecParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reducer tests for the
 * [Action.RecordingAction.StartRecordingContinuation] arm (B2 / ADR-0008
 * §"Auto-Continuation"). Symmetric with [RecordingModuleTest]'s
 * StartRecording coverage, but verifies that:
 *
 *  - the reused sessionId is carried into [RecordingState.Preparing]
 *    (not minted fresh);
 *  - the next-segment file is the FSM's `audioFile`;
 *  - the codec parameters reach [RecordingModule.Effect.AllocateMediaRecorder]
 *    so the new MediaRecorder writes the same format as prior segments;
 *  - BT-mic path defers AllocateMediaRecorder (same shape as the
 *    StartRecording BT path) and carries the action's `target`;
 *  - a blank sessionId throws (load-bearing — a regression here would
 *    silently route an empty-string id through the FSM and into the
 *    pipeline).
 */
class RecordingModuleContinuationTest {

    private val nextSegment = File("/tmp/sess_existing_seg2.m4a")
    private val module = RecordingModule

    // 22050 Hz mono Opus — distinct from the DEFAULT_AAC_M4A constants
    // so the test catches accidental fallback-to-defaults regressions.
    private val priorCodec = CodecParams(
        sampleRate = 22050,
        channelCount = 1,
        bitRate = 32000,
        mimeType = "audio/opus",
    )

    private fun ctx(state: DictateUiState = DictateUiState.initial()) =
        ReducerContext(global = state, now = 1_000_000L)

    @Test
    fun `StartRecordingContinuation from Idle (non-BT) reuses sessionId + threads codec params`() {
        val global = DictateUiState.initial()
            .copy(audio = AudioState(useBluetoothMic = false))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecordingContinuation(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = nextSegment,
                sessionId = "existing-sid-99",
                codecParams = priorCodec,
            ),
            ctx = ctx(global),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(false, next.useBluetooth)
        assertEquals(nextSegment, next.audioFile)
        assertEquals("existing-sid-99", next.sessionId)
        assertEquals(false, next.awaitingSco)
        assertNull(next.target)

        assertEquals(2, result.sideEffects.size)
        val alloc = result.sideEffects[0] as RecordingModule.Effect.AllocateMediaRecorder
        assertEquals(InsertionTarget.INPUT_CONNECTION, alloc.target)
        assertEquals(false, alloc.useBluetooth)
        assertEquals(nextSegment, alloc.audioFile)
        assertEquals("existing-sid-99", alloc.sessionId)
        assertEquals(
            "Codec params must be threaded into the new MediaRecorder so " +
                "the eventual MediaMuxer concat does not reject heterogeneous formats",
            priorCodec, alloc.codecParams,
        )
        assertEquals(
            RecordingModule.Effect.PersistLastFileName(nextSegment.name),
            result.sideEffects[1],
        )
    }

    @Test
    fun `StartRecordingContinuation from Idle (BT-mic) defers AllocateMediaRecorder until SCO resolves`() {
        // BT path: same shape as StartRecording's BT branch — Preparing
        // with awaitingSco=true, action.target carried through, NO
        // AllocateMediaRecorder yet. Only PersistLastFileName fires.
        val global = DictateUiState.initial()
            .copy(audio = AudioState(useBluetoothMic = true))
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.StartRecordingContinuation(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = nextSegment,
                sessionId = "bt-sid-7",
                codecParams = priorCodec,
            ),
            ctx = ctx(global),
        )
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals(true, next.useBluetooth)
        assertEquals(nextSegment, next.audioFile)
        assertEquals("bt-sid-7", next.sessionId)
        assertEquals(true, next.awaitingSco)
        assertEquals(InsertionTarget.INPUT_CONNECTION, next.target)

        assertEquals(1, result.sideEffects.size)
        assertEquals(
            RecordingModule.Effect.PersistLastFileName(nextSegment.name),
            result.sideEffects[0],
        )
        assertTrue(
            "BT path must NOT fire AllocateMediaRecorder before SCO resolves",
            result.sideEffects.none { it is RecordingModule.Effect.AllocateMediaRecorder },
        )
    }

    @Test
    fun `StartRecordingContinuation with blank sessionId throws (regression guard)`() {
        // The non-empty sessionId is load-bearing: a blank id would
        // silently propagate through the FSM and into the pipeline,
        // matching the F-10 empty-string sentinel that the StartRecording
        // arm guards against. The continuation arm enforces the same
        // invariant.
        assertThrows(IllegalArgumentException::class.java) {
            module.reduce(
                state = RecordingState.Idle,
                action = Action.RecordingAction.StartRecordingContinuation(
                    target = InsertionTarget.INPUT_CONNECTION,
                    audioFile = nextSegment,
                    sessionId = "",
                    codecParams = priorCodec,
                ),
                ctx = ctx(),
            )
        }
    }

    @Test
    fun `StartRecordingContinuation in non-Idle state is rejected (reducer-null)`() {
        // Continuation is meaningful only when the FSM is Idle. Active /
        // Paused already own a session — receiving a continuation there
        // would be a logic bug; the reducer rejects it via the default
        // `else -> null` arm so the orchestrator logs Rejected("reducer-null")
        // instead of corrupting state.
        val activeState = RecordingState.Active(
            useBluetooth = false,
            audioFile = nextSegment,
            sessionId = "already-active",
        )
        val result = module.reduce(
            state = activeState,
            action = Action.RecordingAction.StartRecordingContinuation(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = nextSegment,
                sessionId = "new-attempt",
                codecParams = priorCodec,
            ),
            ctx = ctx(),
        )
        assertNull(result)
    }
}
