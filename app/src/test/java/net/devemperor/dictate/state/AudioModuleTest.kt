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
            recording = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test"),
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
            recording = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-test"),
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
            recording = RecordingState.Paused(false, testFile, "sid-test"),
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
            recording = RecordingState.Preparing(false, testFile, "sid-test"),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = false))
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── C6-IMPL-1 / B2-C6-W1: audio-focus + SCO emission ───────────────

    @Test
    fun `RecordingStarted with audioFocus pref on emits RequestAudioFocus`() {
        val state = AudioState(audioFocusEnabledPref = true, useBluetoothMic = false)
        val result = module.reduce(state, Action.AudioAction.RecordingStarted, ctx())
        assertNotNull(result)
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.RequestAudioFocus),
            result!!.sideEffects,
        )
    }

    @Test
    fun `RecordingStarted with audioFocus pref OFF does NOT request focus`() {
        // Legacy parity: `if (audioFocusEnabled) gate.request()`.
        val state = AudioState(audioFocusEnabledPref = false, useBluetoothMic = false)
        val result = module.reduce(state, Action.AudioAction.RecordingStarted, ctx())
        assertNotNull(result)
        assertTrue(result!!.sideEffects.isEmpty())
    }

    @Test
    fun `RecordingStarted with BT-mic pref on also starts SCO`() {
        val state = AudioState(audioFocusEnabledPref = true, useBluetoothMic = true)
        val result = module.reduce(state, Action.AudioAction.RecordingStarted, ctx())
        assertEquals(
            listOf<AudioModule.Effect>(
                AudioModule.Effect.RequestAudioFocus,
                AudioModule.Effect.StartBluetoothSco,
            ),
            result!!.sideEffects,
        )
    }

    @Test
    fun `RecordingStarted BT-mic on but focus pref off starts SCO only`() {
        val state = AudioState(audioFocusEnabledPref = false, useBluetoothMic = true)
        val result = module.reduce(state, Action.AudioAction.RecordingStarted, ctx())
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.StartBluetoothSco),
            result!!.sideEffects,
        )
    }

    @Test
    fun `RecordingEnded releases focus and stops SCO (idempotent, unconditional)`() {
        val state = AudioState(audioFocusEnabledPref = false, useBluetoothMic = false)
        val result = module.reduce(state, Action.AudioAction.RecordingEnded, ctx())
        assertEquals(
            listOf<AudioModule.Effect>(
                AudioModule.Effect.ReleaseAudioFocus,
                AudioModule.Effect.StopBluetoothSco,
            ),
            result!!.sideEffects,
        )
    }

    // ─── Cross-module observer: recording-lifecycle → audio ─────────────

    @Test
    fun `Idle to Preparing cascades RecordingStarted`() {
        val prev = DictateUiState.initial()
        val next = prev.copy(
            recording = RecordingState.Preparing(false, testFile, "sid-test"),
        )
        assertEquals(
            listOf<Action>(Action.AudioAction.RecordingStarted),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `Paused to Active (resume) cascades RecordingStarted (re-acquire focus)`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Paused(false, testFile, "sid-test"))
        val next = prev.copy(
            recording = RecordingState.Active(false, testFile, "sid-test"),
        )
        assertEquals(
            listOf<Action>(Action.AudioAction.RecordingStarted),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `Active to Idle (stop) cascades RecordingEnded`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Active(false, testFile, "sid-test"))
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(
            listOf<Action>(Action.AudioAction.RecordingEnded),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `Preparing to Idle (cancel mid-prepare) cascades RecordingEnded`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Preparing(true, testFile, "sid-test", awaitingSco = true))
        val next = prev.copy(recording = RecordingState.Idle)
        assertEquals(
            listOf<Action>(Action.AudioAction.RecordingEnded),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `Active to Paused (pause) cascades RecordingEnded (abandon focus)`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Active(false, testFile, "sid-test"))
        val next = prev.copy(recording = RecordingState.Paused(false, testFile, "sid-test"))
        assertEquals(
            listOf<Action>(Action.AudioAction.RecordingEnded),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `no recording transition cascades no recording-lifecycle action`() {
        val prev = DictateUiState.initial()
            .copy(recording = RecordingState.Active(false, testFile, "sid-test"))
        // identical recording state — only an unrelated change
        val next = prev.copy(audio = prev.audio.copy(vibrationEnabled = false))
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── BT-SCO Preparing handshake resolution ──────────────────────────

    @Test
    fun `SCO connect during awaiting Preparing cascades ScoRouteResolved(true)`() {
        val prep = RecordingState.Preparing(true, testFile, "sid-sco", awaitingSco = true, target = InsertionTarget.INPUT_CONNECTION)
        val prev = DictateUiState.initial().copy(
            recording = prep,
            audio = AudioState(bluetoothSco = BluetoothScoPublicState(ScoPhase.Waiting)),
        )
        val next = prev.copy(
            audio = prev.audio.copy(bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected)),
        )
        assertEquals(
            listOf<Action>(Action.RecordingAction.ScoRouteResolved(useBluetooth = true)),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `SCO fail during awaiting Preparing cascades ScoRouteResolved(false)`() {
        val prep = RecordingState.Preparing(true, testFile, "sid-sco", awaitingSco = true, target = InsertionTarget.INPUT_CONNECTION)
        val prev = DictateUiState.initial().copy(
            recording = prep,
            audio = AudioState(bluetoothSco = BluetoothScoPublicState(ScoPhase.Waiting)),
        )
        val next = prev.copy(
            audio = prev.audio.copy(bluetoothSco = BluetoothScoPublicState(ScoPhase.Failed, "sco-timeout")),
        )
        assertEquals(
            listOf<Action>(Action.RecordingAction.ScoRouteResolved(useBluetooth = false)),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `SCO phase unchanged does NOT re-cascade ScoRouteResolved (duplicate broadcast)`() {
        val prep = RecordingState.Preparing(true, testFile, "sid-sco", awaitingSco = true, target = InsertionTarget.INPUT_CONNECTION)
        val prev = DictateUiState.initial().copy(
            recording = prep,
            audio = AudioState(bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected)),
        )
        val next = prev // identical — no phase transition
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    @Test
    fun `SCO change while Preparing not awaiting does NOT cascade ScoRouteResolved`() {
        val prep = RecordingState.Preparing(false, testFile, "sid-sco", awaitingSco = false)
        val prev = DictateUiState.initial().copy(
            recording = prep,
            audio = AudioState(bluetoothSco = BluetoothScoPublicState(ScoPhase.Waiting)),
        )
        val next = prev.copy(
            audio = prev.audio.copy(bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected)),
        )
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
