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

        assertEquals(4, result.sideEffects.size)
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
        // Recovery-chain (2026-05-22) — re-arm the interrupted row back
        // to RECORDING so a second interruption mid-continuation is
        // caught by PipelineRecovery again.
        assertEquals(
            RecordingModule.Effect.MarkSessionRecording("existing-sid-99"),
            result.sideEffects[1],
        )
        assertEquals(
            RecordingModule.Effect.PersistLastFileName(nextSegment.name),
            result.sideEffects[2],
        )
        // Block A1 — SyncAudioSegments persists the freshly-allocated
        // Cold-Resume segment into `audio_file_paths` (the
        // ContinuationLookup minted it via allocateNext but never wrote
        // the DB; this is the first sync that picks it up).
        assertEquals(
            RecordingModule.Effect.SyncAudioSegments("existing-sid-99"),
            result.sideEffects[3],
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

        assertEquals(3, result.sideEffects.size)
        // Recovery-chain (2026-05-22) — re-arm the interrupted row to
        // RECORDING; emitted even during the SCO wait.
        assertEquals(
            RecordingModule.Effect.MarkSessionRecording("bt-sid-7"),
            result.sideEffects[0],
        )
        assertEquals(
            RecordingModule.Effect.PersistLastFileName(nextSegment.name),
            result.sideEffects[1],
        )
        // Block A1 — BT path still persists segments even during SCO wait
        // (the file is already on disk; persistence must not gate on the
        // hardware allocate).
        assertEquals(
            RecordingModule.Effect.SyncAudioSegments("bt-sid-7"),
            result.sideEffects[2],
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

    // ─── RecordingState.Interrupted — recovery auto-surfacing (2026-05-22) ───

    @Test
    fun `SurfaceInterruptedRecording from Idle transitions to Interrupted with the elapsed time`() {
        // The recovery pass detected a fresh RECORDING_INTERRUPTED
        // session → drive Idle → Interrupted so the keyboard shows it
        // "as if briefly paused" with the timer frozen at elapsedMs.
        val result = module.reduce(
            state = RecordingState.Idle,
            action = Action.RecordingAction.SurfaceInterruptedRecording(
                sessionId = "interrupted-sid",
                elapsedMs = 8_000L,
            ),
            ctx = ctx(),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Interrupted
        assertEquals("interrupted-sid", next.sessionId)
        assertEquals(8_000L, next.elapsedMs)
        // Surfacing is passive — no hardware effect.
        assertTrue("Surfacing must emit no side-effects", result.sideEffects.isEmpty())
    }

    @Test
    fun `StartRecordingContinuation from Interrupted continues the recording`() {
        // A Record-tap on a surfaced interrupted recording continues it
        // — the same continuationTransition the Idle path uses.
        val result = module.reduce(
            state = RecordingState.Interrupted(sessionId = "existing-sid-99", elapsedMs = 8_000L),
            action = Action.RecordingAction.StartRecordingContinuation(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = nextSegment,
                sessionId = "existing-sid-99",
                codecParams = priorCodec,
            ),
            ctx = ctx(DictateUiState.initial().copy(audio = AudioState(useBluetoothMic = false))),
        )
        assertNotNull(result)
        val next = result!!.nextState as RecordingState.Preparing
        assertEquals("existing-sid-99", next.sessionId)
        assertEquals(nextSegment, next.audioFile)
        assertTrue(
            "continuation from Interrupted must allocate the recorder",
            result.sideEffects.any { it is RecordingModule.Effect.AllocateMediaRecorder },
        )
        assertTrue(
            "continuation must re-arm the DB row to RECORDING",
            result.sideEffects.contains(
                RecordingModule.Effect.MarkSessionRecording("existing-sid-99"),
            ),
        )
    }

    @Test
    fun `DiscardInterruptedSession from Interrupted returns to Idle and discards the audio`() {
        val result = module.reduce(
            state = RecordingState.Interrupted(sessionId = "sid-x", elapsedMs = 5_000L),
            action = Action.RecordingAction.DiscardInterruptedSession("sid-x"),
            ctx = ctx(),
        )
        assertNotNull(result)
        assertEquals(RecordingState.Idle, result!!.nextState)
        assertEquals(
            listOf<RecordingModule.Effect>(
                RecordingModule.Effect.DiscardAudioForSession("sid-x"),
            ),
            result.sideEffects,
        )
    }

    @Test
    fun `Interrupted rejects unrelated actions (reducer-null)`() {
        // Stop / Pause make no sense on a recording with no live
        // recorder — the reducer rejects them.
        val result = module.reduce(
            state = RecordingState.Interrupted(sessionId = "sid-x", elapsedMs = 0L),
            action = Action.RecordingAction.StopRecording,
            ctx = ctx(),
        )
        assertNull(result)
    }
}
