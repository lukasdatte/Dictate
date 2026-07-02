package net.devemperor.dictate.state

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer + cross-module-cascade tests for [InterruptionModule]
 * (F-036 implementation, 2026-07-02).
 *
 * Coverage per the spec's test matrix:
 *
 *  - interruption while `Active` → event recorded (the recording→paused
 *    transition is the observer's cascade, tested separately below)
 *  - interruption while `Idle` / `Preparing` / `Paused` / `Interrupted`
 *    → null (no-op)
 *  - `ClearInterruption` clears; stale clear → null
 *  - observer: fresh event + Active → `PauseRecording` cascade
 *  - observer: recording leaves Paused with live event →
 *    `ClearInterruption` self-cascade; no event → no cascade
 *  - no auto-resume anywhere (no arm emits ResumeRecording)
 */
class InterruptionModuleTest {

    private val module = InterruptionModule
    private val testFile = File("/tmp/test.m4a")
    private val now = 1_720_000_000_000L

    private fun activeRecording() = RecordingState.Active(
        useBluetooth = false,
        audioFile = testFile,
        sessionId = "sid-test",
    )

    private fun pausedRecording() = RecordingState.Paused(
        useBluetooth = false,
        audioFile = testFile,
        sessionId = "sid-test",
    )

    private fun ctx(recording: RecordingState = RecordingState.Idle) = ReducerContext(
        global = DictateUiState.initial().copy(recording = recording),
        now = now,
    )

    // ─── reduce: record-if-active gating ────────────────────────────

    @Test
    fun `AudioFocusInterrupted while Active records the event with reducer time`() {
        val result = module.reduce(
            InterruptionState(),
            Action.InterruptionAction.AudioFocusInterrupted,
            ctx(recording = activeRecording()),
        )
        assertNotNull(result)
        assertEquals(
            InterruptionEvent(InterruptionReason.AUDIO_FOCUS_LOST, occurredAt = now),
            result!!.nextState.lastInterruption,
        )
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `HeadsetDisconnected while Active records the event`() {
        val result = module.reduce(
            InterruptionState(),
            Action.InterruptionAction.HeadsetDisconnected,
            ctx(recording = activeRecording()),
        )
        assertEquals(
            InterruptionEvent(InterruptionReason.HEADSET_DISCONNECTED, occurredAt = now),
            result!!.nextState.lastInterruption,
        )
    }

    @Test
    fun `interruption while Idle is a no-op (Rejected)`() {
        assertNull(
            module.reduce(
                InterruptionState(),
                Action.InterruptionAction.AudioFocusInterrupted,
                ctx(recording = RecordingState.Idle),
            ),
        )
        assertNull(
            module.reduce(
                InterruptionState(),
                Action.InterruptionAction.HeadsetDisconnected,
                ctx(recording = RecordingState.Idle),
            ),
        )
    }

    @Test
    fun `interruption while Paused is a no-op (already paused)`() {
        assertNull(
            module.reduce(
                InterruptionState(),
                Action.InterruptionAction.AudioFocusInterrupted,
                ctx(recording = pausedRecording()),
            ),
        )
    }

    @Test
    fun `interruption while Preparing is a no-op (nothing capturing yet)`() {
        val preparing = RecordingState.Preparing(
            useBluetooth = false,
            audioFile = testFile,
            sessionId = "sid-test",
            awaitingSco = false,
            target = null,
        )
        assertNull(
            module.reduce(
                InterruptionState(),
                Action.InterruptionAction.HeadsetDisconnected,
                ctx(recording = preparing),
            ),
        )
    }

    @Test
    fun `interruption while Interrupted (recovery surface) is a no-op`() {
        val interrupted = RecordingState.Interrupted(sessionId = "sid-test", elapsedMs = 5_000L)
        assertNull(
            module.reduce(
                InterruptionState(),
                Action.InterruptionAction.AudioFocusInterrupted,
                ctx(recording = interrupted),
            ),
        )
    }

    // ─── reduce: ClearInterruption ──────────────────────────────────

    @Test
    fun `ClearInterruption clears a recorded event`() {
        val state = InterruptionState(
            lastInterruption = InterruptionEvent(InterruptionReason.AUDIO_FOCUS_LOST, now),
        )
        val result = module.reduce(
            state,
            Action.InterruptionAction.ClearInterruption,
            ctx(),
        )
        assertEquals(InterruptionState(), result!!.nextState)
    }

    @Test
    fun `stale ClearInterruption (nothing recorded) is a no-op`() {
        assertNull(
            module.reduce(
                InterruptionState(),
                Action.InterruptionAction.ClearInterruption,
                ctx(),
            ),
        )
    }

    // ─── observer: Interruption × Recording cascade ─────────────────

    @Test
    fun `fresh interruption event while Active cascades PauseRecording`() {
        val prev = DictateUiState.initial().copy(recording = activeRecording())
        val next = prev.copy(
            interruption = InterruptionState(
                lastInterruption = InterruptionEvent(InterruptionReason.AUDIO_FOCUS_LOST, now),
            ),
        )
        assertEquals(
            listOf<Action>(Action.RecordingAction.PauseRecording),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `unchanged interruption event does NOT re-cascade PauseRecording`() {
        val event = InterruptionEvent(InterruptionReason.HEADSET_DISCONNECTED, now)
        val prev = DictateUiState.initial().copy(
            recording = activeRecording(),
            interruption = InterruptionState(lastInterruption = event),
        )
        // Unrelated transition (recording stays Active, event unchanged).
        val next = prev.copy(imeViewVisible = false)
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── observer: self-clear when the pause resolves ───────────────

    @Test
    fun `recording leaving Paused with live event cascades ClearInterruption on resume`() {
        val event = InterruptionEvent(InterruptionReason.AUDIO_FOCUS_LOST, now)
        val prev = DictateUiState.initial().copy(
            recording = pausedRecording(),
            interruption = InterruptionState(lastInterruption = event),
        )
        val next = prev.copy(recording = activeRecording())
        assertEquals(
            listOf<Action>(Action.InterruptionAction.ClearInterruption),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `recording leaving Paused to Idle (stop) with live event cascades ClearInterruption`() {
        val event = InterruptionEvent(InterruptionReason.HEADSET_DISCONNECTED, now)
        val prev = DictateUiState.initial().copy(
            recording = pausedRecording(),
            interruption = InterruptionState(lastInterruption = event),
        )
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(
            listOf<Action>(Action.InterruptionAction.ClearInterruption),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `recording leaving Paused without a live event does NOT cascade`() {
        // A user-initiated pause (no interruption) resuming normally.
        val prev = DictateUiState.initial().copy(recording = pausedRecording())
        val next = prev.copy(recording = activeRecording())
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `entering Paused (the interruption pause itself) does NOT cascade a clear`() {
        val event = InterruptionEvent(InterruptionReason.AUDIO_FOCUS_LOST, now)
        val prev = DictateUiState.initial().copy(
            recording = activeRecording(),
            interruption = InterruptionState(lastInterruption = event),
        )
        val next = prev.copy(recording = pausedRecording())
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── shape invariants ───────────────────────────────────────────

    @Test
    fun `module id is Interruption`() {
        assertEquals(ModuleId.Interruption, module.id)
    }

    @Test
    fun `initial state records no interruption`() {
        assertEquals(InterruptionState(), module.initialState())
    }

    @Test
    fun `lens round-trip preserves the axis`() {
        val state = DictateUiState.initial()
        assertEquals(InterruptionState(), module.read(state))
        val custom = InterruptionState(
            lastInterruption = InterruptionEvent(InterruptionReason.AUDIO_FOCUS_LOST, now),
        )
        val withInterruption = module.write(state, custom)
        assertEquals(custom, withInterruption.interruption)
    }
}
