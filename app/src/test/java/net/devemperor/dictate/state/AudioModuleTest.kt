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

    // ─── B2-VAL-W1 F-1: SCO phase prime on the BT-mic path ──────────────

    @Test
    fun `RecordingStarted with BT-mic primes the SCO phase to Waiting`() {
        // F-1 core: a stale `Connected` phase (StopBluetoothSco→release()
        // does not emit Disconnected synchronously) is reset so the next
        // SCO broadcast is always a real edge.
        val state = AudioState(
            audioFocusEnabledPref = true,
            useBluetoothMic = true,
            bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected, null),
        )
        val result = module.reduce(state, Action.AudioAction.RecordingStarted, ctx())
        assertNotNull(result)
        assertEquals(ScoPhase.Waiting, result!!.nextState.bluetoothSco.phase)
        assertNull(result.nextState.bluetoothSco.failureReason)
    }

    @Test
    fun `RecordingStarted without BT-mic does NOT touch the SCO phase`() {
        // Non-BT path emits no SCO effect — must not corrupt the phase.
        val state = AudioState(
            useBluetoothMic = false,
            bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected, null),
        )
        val result = module.reduce(state, Action.AudioAction.RecordingStarted, ctx())
        assertNotNull(result)
        assertEquals(ScoPhase.Connected, result!!.nextState.bluetoothSco.phase)
    }

    @Test
    fun `F-1 already-connected hang is defeated - primed Waiting makes Connected a real edge`() {
        // End-to-end no-hang proof. Prior session left phase stale at
        // Connected. RecordingStarted (BT) primes it to Waiting; the
        // subsequent OnBluetoothScoStateChanged(Connected) from the
        // startSco() already-connected early-return is now a genuine
        // Waiting→Connected edge → observer cascades ScoRouteResolved.
        val staleState = AudioState(
            useBluetoothMic = true,
            bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected, null),
        )
        val primed = module.reduce(
            staleState, Action.AudioAction.RecordingStarted, ctx(),
        )!!.nextState
        assertEquals(ScoPhase.Waiting, primed.bluetoothSco.phase)

        // The Connected broadcast is now NOT a reducer no-op.
        val afterBroadcast = module.reduce(
            primed,
            Action.AudioAction.OnBluetoothScoStateChanged(ScoPhase.Connected, null),
            ctx(),
        )
        assertNotNull(afterBroadcast)
        assertEquals(ScoPhase.Connected, afterBroadcast!!.nextState.bluetoothSco.phase)

        // And the observer sees the real Waiting→Connected edge while
        // the recording is Preparing(awaitingSco) → ScoRouteResolved.
        val prep = RecordingState.Preparing(
            true, testFile, "sid-sco", awaitingSco = true,
            target = InsertionTarget.INPUT_CONNECTION,
        )
        val prev = DictateUiState.initial().copy(recording = prep, audio = primed)
        val next = prev.copy(audio = afterBroadcast.nextState)
        assertEquals(
            listOf<Action>(Action.RecordingAction.ScoRouteResolved(useBluetooth = true)),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `F-1 genuine-wait timeout path still resolves to MIC fallback`() {
        // After the prime to Waiting, a Failed broadcast (subsystem
        // 2500ms timeout on the not-connected branch) is a real
        // Waiting→Failed edge → ScoRouteResolved(false) → MIC.
        val primed = AudioState(
            useBluetoothMic = true,
            bluetoothSco = BluetoothScoPublicState(ScoPhase.Waiting, null),
        )
        val prep = RecordingState.Preparing(
            true, testFile, "sid-sco", awaitingSco = true,
            target = InsertionTarget.INPUT_CONNECTION,
        )
        val prev = DictateUiState.initial().copy(recording = prep, audio = primed)
        val next = prev.copy(
            audio = primed.copy(
                bluetoothSco = BluetoothScoPublicState(ScoPhase.Failed, "sco-timeout"),
            ),
        )
        assertEquals(
            listOf<Action>(Action.RecordingAction.ScoRouteResolved(useBluetooth = false)),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `F-1 stale-resolve-after-cancel still defeated despite phase prime`() {
        // Cancel mid-wait → recording Idle. A late Connected broadcast
        // now DOES write the phase (Waiting→Connected real edge), but
        // the observer's `nextRec is Preparing && awaitingSco` guard is
        // false (recording Idle) → NO ScoRouteResolved cascade.
        val primed = AudioState(
            useBluetoothMic = true,
            bluetoothSco = BluetoothScoPublicState(ScoPhase.Waiting, null),
        )
        // recording was cancelled → Idle
        val prev = DictateUiState.initial().copy(
            recording = RecordingState.Idle, audio = primed,
        )
        val next = prev.copy(
            audio = primed.copy(
                bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected, null),
            ),
        )
        assertEquals(emptyList<Action>(), module.onCrossModuleStateChange(prev, next))
    }

    // ─── B2-VAL-W1 F-2: focus re-acquire on SCO-wait-resolved edge ──────

    @Test
    fun `F-2 SCO-wait-resolved edge cascades ReacquireAudioFocus`() {
        // awaitingSco true→false (the deferred-allocate transition
        // ScoRouteResolved produces) — focus must be re-asserted so a
        // BT recording cannot reach Active having lost focus mid-wait.
        val prevPrep = RecordingState.Preparing(
            true, testFile, "sid-sco", awaitingSco = true,
            target = InsertionTarget.INPUT_CONNECTION,
        )
        val nextPrep = RecordingState.Preparing(
            true, testFile, "sid-sco", awaitingSco = false, target = null,
        )
        val prev = DictateUiState.initial().copy(recording = prevPrep)
        val next = prev.copy(recording = nextPrep)
        assertEquals(
            listOf<Action>(Action.AudioAction.ReacquireAudioFocus),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `F-2 ReacquireAudioFocus reducer re-requests focus (pref on), focus-only`() {
        // Focus-only: NO StartBluetoothSco (handshake already resolved),
        // NO phase prime (would corrupt the resolved Connected phase).
        val state = AudioState(
            audioFocusEnabledPref = true,
            useBluetoothMic = true,
            bluetoothSco = BluetoothScoPublicState(ScoPhase.Connected, null),
        )
        val result = module.reduce(state, Action.AudioAction.ReacquireAudioFocus, ctx())
        assertNotNull(result)
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.RequestAudioFocus),
            result!!.sideEffects,
        )
        // phase untouched
        assertEquals(ScoPhase.Connected, result.nextState.bluetoothSco.phase)
    }

    @Test
    fun `F-2 ReacquireAudioFocus with focus pref off emits nothing`() {
        val state = AudioState(audioFocusEnabledPref = false, useBluetoothMic = true)
        val result = module.reduce(state, Action.AudioAction.ReacquireAudioFocus, ctx())
        assertNotNull(result)
        assertTrue(result!!.sideEffects.isEmpty())
    }

    @Test
    fun `F-2 SCO-wait-resolved does NOT also cascade RecordingStarted (mutually exclusive)`() {
        // The engagement-edge clause must NOT fire on Preparing→Preparing
        // (prev already engaged) — only ReacquireAudioFocus.
        val prevPrep = RecordingState.Preparing(
            true, testFile, "s", awaitingSco = true,
            target = InsertionTarget.INPUT_CONNECTION,
        )
        val nextPrep = RecordingState.Preparing(true, testFile, "s", awaitingSco = false)
        val prev = DictateUiState.initial().copy(recording = prevPrep)
        val next = prev.copy(recording = nextPrep)
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "must not contain RecordingStarted",
            !cascade.contains(Action.AudioAction.RecordingStarted),
        )
        assertEquals(
            listOf<Action>(Action.AudioAction.ReacquireAudioFocus), cascade,
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
