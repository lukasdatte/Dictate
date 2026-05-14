package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-reducer + cross-module-cascade tests for [AudioModule].
 *
 * Coverage:
 * - OnAudioFocusGrantChanged updates the granted flag (idempotent)
 * - OnBluetoothScoStateChanged updates the SCO state (idempotent)
 * - ToggleAudioFocusPref flips the pref
 * - Cross-module cascade: AudioFocus-loss during Active recording → PauseRecording
 * - Cross-module cascade: AudioFocus-loss during Paused recording → PauseRecording
 *   (the spec lists `isActiveOrPaused` which covers Paused too — but pausing an
 *   already-paused recorder is no-op via the reducer's reject path)
 * - Cross-module: no cascade when no recording is active
 * - Cross-module: no cascade on focus regain (Spec 1 §15.3 leaves resume to user)
 */
class AudioModuleTest {

    private val module = AudioModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())
    private val testFile = File("/tmp/test.m4a")

    @Test
    fun `OnAudioFocusGrantChanged true updates audioFocusGranted`() {
        val result = module.reduce(
            state = AudioState(audioFocusGranted = false),
            action = Action.AudioAction.OnAudioFocusGrantChanged(granted = true),
            ctx = ctx(),
        )
        assertNotNull(result)
        assertEquals(true, result!!.nextState.audioFocusGranted)
    }

    @Test
    fun `OnAudioFocusGrantChanged with same value returns null (no-op)`() {
        val state = AudioState(audioFocusGranted = true)
        val result = module.reduce(
            state,
            Action.AudioAction.OnAudioFocusGrantChanged(granted = true),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `OnBluetoothScoStateChanged updates the SCO public state`() {
        val state = AudioState()
        val result = module.reduce(
            state,
            Action.AudioAction.OnBluetoothScoStateChanged(ScoPhase.Connected, reason = null),
            ctx(),
        )
        assertEquals(ScoPhase.Connected, result!!.nextState.bluetoothSco.phase)
    }

    @Test
    fun `OnBluetoothScoStateChanged with identical state returns null`() {
        val state = AudioState(bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected, null))
        val result = module.reduce(
            state,
            Action.AudioAction.OnBluetoothScoStateChanged(ScoPhase.Connected, reason = null),
            ctx(),
        )
        assertNull(result)
    }

    @Test
    fun `ToggleAudioFocusPref flips the pref`() {
        val state = AudioState(audioFocusEnabledPref = true)
        val result = module.reduce(state, Action.AudioAction.ToggleAudioFocusPref, ctx())
        assertEquals(false, result!!.nextState.audioFocusEnabledPref)
    }

    // ─── Cross-module cascade ───────────────────────────────────────────

    @Test
    fun `cross-module AudioFocus loss during Active recording cascades PauseRecording`() {
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusGranted = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = testFile),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(listOf<Action>(Action.RecordingAction.PauseRecording), cascade)
    }

    @Test
    fun `cross-module AudioFocus loss during Paused recording cascades PauseRecording`() {
        // Per `isActiveOrPaused` semantics — Paused is included.
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusGranted = true),
            recording = RecordingState.Paused(useBluetooth = false, audioFile = testFile),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(listOf<Action>(Action.RecordingAction.PauseRecording), cascade)
    }

    @Test
    fun `cross-module AudioFocus loss without active recording does NOT cascade`() {
        val prev = DictateUiState.initial().copy(audio = AudioState(audioFocusGranted = true))
        val next = prev.copy(audio = AudioState(audioFocusGranted = false))
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module AudioFocus regain does NOT cascade ResumeRecording`() {
        // Spec 1 §15.3 — resume is user-driven, not auto.
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusGranted = false),
            recording = RecordingState.Paused(false, testFile),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = true))
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `cross-module AudioFocus loss during Preparing does NOT cascade (Preparing not in isActiveOrPaused)`() {
        // Per `isActiveOrPaused` doc: Preparing is sub-ms transient; we
        // don't auto-pause it (it'd race with the prepare-callback).
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusGranted = true),
            recording = RecordingState.Preparing(false, testFile),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = false))
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── Lens / IDs ─────────────────────────────────────────────────────

    @Test
    fun `module id is Audio`() {
        assertEquals(ModuleId.Audio, module.id)
    }

    @Test
    fun `lens round-trip`() {
        val state = DictateUiState.initial()
        val sub = module.read(state)
        val back = module.write(state, sub.copy(useBluetoothMic = true))
        assertEquals(true, back.audio.useBluetoothMic)
    }
}
