package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.devemperor.dictate.testutil.fakeModuleServices
import java.io.File

/**
 * Pure-reducer + cross-module-cascade tests for [AudioModule].
 *
 * Coverage:
 * - OnAudioFocusGrantChanged updates the granted flag (idempotent)
 * - OnBluetoothScoStateChanged updates the SCO state (idempotent)
 * - ToggleAudioFocusPref flips the pref
 * - Cross-module: focus-loss no longer cascades PauseRecording here —
 *   the interruption authority moved to InterruptionModule
 *   (F-007 consolidation, 2026-07-02; see InterruptionModuleTest)
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
    fun `cross-module AudioFocus loss during Active recording does NOT pause here (F-007 consolidation)`() {
        // 2026-07-02: the granted-edge → PauseRecording cascade moved to
        // InterruptionModule (single interruption authority; the service
        // classifier dispatches InterruptionAction.AudioFocusInterrupted
        // for interrupting losses). AudioModule's granted flag is pure
        // bookkeeping now — see InterruptionModuleTest for the pause.
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusGranted = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-test"),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(emptyList<Action>(), cascade)
    }

    @Test
    fun `cross-module AudioFocus loss during Paused recording does NOT cascade (F-007 consolidation)`() {
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusGranted = true),
            recording = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-test"),
        )
        val next = prev.copy(audio = prev.audio.copy(audioFocusGranted = false))
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertEquals(emptyList<Action>(), cascade)
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

    // ─── Record-latency fix (2026-07-17): StartBluetoothSco availability gate ─
    //
    // Regression guard for the ~2.5 s SCO-timeout stall that hit EVERY
    // record-start when `use_bluetooth_mic` was on but no headset was
    // paired (measurements 2026-07-17 §V2: tap→start 2516 ms vs 9 ms).
    // The reducer cannot read hardware (ADR-0001 forbidden-pattern (b)),
    // so the availability probe lives in the `StartBluetoothSco` effect
    // handler. These tests exercise `runEffect` directly with counting
    // fakes; the fix is in `AudioModule.runEffect`.

    /** Counting [BluetoothScoSubsystem] fake with a configurable availability. */
    private class FakeBluetoothSco(private val available: Boolean) : BluetoothScoSubsystem {
        var startCount = 0
            private set
        var stopCount = 0
            private set
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
        override fun isAvailable(): Boolean = available
    }

    @Test
    fun `StartBluetoothSco with NO available BT route resolves to MIC immediately (no SCO wait)`() {
        // The bug: with the BT-mic pref on but no headset, the handler
        // armed startSco() and waited out the full ~2.5 s timeout before
        // falling back to the mic. The fix: emit the terminal Failed phase
        // now so the ScoRouteResolved(false) → MIC cascade fires without
        // the stall — and NEVER arm the SCO handshake.
        val emitted = mutableListOf<Action>()
        val sco = FakeBluetoothSco(available = false)
        val services = fakeModuleServices(
            bluetoothSco = sco,
            emitAction = { emitted += it },
        )

        module.runEffect(AudioModule.Effect.StartBluetoothSco, services)

        assertEquals("SCO handshake must NOT be armed when no route exists", 0, sco.startCount)
        assertEquals(
            "must resolve the route immediately via a Failed phase",
            listOf<Action>(
                Action.AudioAction.OnBluetoothScoStateChanged(
                    phase = ScoPhase.Failed,
                    reason = "no-bt-device",
                ),
            ),
            emitted,
        )
    }

    @Test
    fun `StartBluetoothSco with an available BT route arms the handshake (unchanged)`() {
        // A real headset must still wait for the SCO handshake — the fix
        // must not regress the BT capture path.
        val emitted = mutableListOf<Action>()
        val sco = FakeBluetoothSco(available = true)
        val services = fakeModuleServices(
            bluetoothSco = sco,
            emitAction = { emitted += it },
        )

        module.runEffect(AudioModule.Effect.StartBluetoothSco, services)

        assertEquals("SCO handshake must be armed for a real headset", 1, sco.startCount)
        assertTrue("no synthetic route-resolve when a real handshake runs", emitted.isEmpty())
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

    // ─── Pref-Persist + Runtime-Apply Effects (indirection-cleanup Chunk 3.1 + 3.2) ─

    @Test
    fun `ToggleAudioFocusPref emits PersistAudioFocusPref with new value when idle`() {
        // Idle path: no live recording → no ApplyAudioFocusRuntime, just
        // the persist Effect. Mirrors the A-3 click path before the user
        // ever starts a recording.
        val state = AudioState(audioFocusEnabledPref = true, audioFocusGranted = false)
        val result = module.reduce(state, Action.AudioAction.ToggleAudioFocusPref, ctx())
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.PersistAudioFocusPref(false)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `ToggleAudioFocusPref false to true emits PersistAudioFocusPref(true)`() {
        val state = AudioState(audioFocusEnabledPref = false)
        val result = module.reduce(state, Action.AudioAction.ToggleAudioFocusPref, ctx())
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.PersistAudioFocusPref(true)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `ToggleAudioFocusPref during Active recording emits Persist + ApplyAudioFocusRuntime`() {
        // Active path: live focus needs to follow the pref flip too.
        // Pref was true (focus held), now turning off → release focus.
        val state = AudioState(audioFocusEnabledPref = true, audioFocusGranted = true)
        val ctxActive = ReducerContext(
            global = DictateUiState.initial().copy(
                audio = state,
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = testFile,
                    sessionId = "sid-toggle",
                ),
            ),
        )
        val result = module.reduce(state, Action.AudioAction.ToggleAudioFocusPref, ctxActive)
        assertEquals(
            listOf<AudioModule.Effect>(
                AudioModule.Effect.PersistAudioFocusPref(false),
                AudioModule.Effect.ApplyAudioFocusRuntime(false),
            ),
            result!!.sideEffects,
        )
    }

    @Test
    fun `ToggleAudioFocusPref Active but focus already matches new value emits only Persist`() {
        // Defensive: if the pref turns on but focus is already granted
        // (pref-off but focus-granted because some external code held
        // it), don't re-request — the AudioManager.request() is
        // idempotent so the gate is more about avoiding spurious system
        // calls than correctness. Same idea on the off direction:
        // already-released stays released.
        val state = AudioState(audioFocusEnabledPref = false, audioFocusGranted = true)
        val ctxActive = ReducerContext(
            global = DictateUiState.initial().copy(
                audio = state,
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = testFile,
                    sessionId = "sid-match",
                ),
            ),
        )
        val result = module.reduce(state, Action.AudioAction.ToggleAudioFocusPref, ctxActive)
        // nextPref = true, audioFocusGranted = true → no runtime apply
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.PersistAudioFocusPref(true)),
            result!!.sideEffects,
        )
    }

    // ─── ApplyAudioFocusRuntimeFromPref (Chunk 3.5 — C-3 SP-listener removal) ─

    @Test
    fun `ApplyAudioFocusRuntimeFromPref emits ApplyAudioFocusRuntime when value differs from granted`() {
        // External Settings-Activity SP-write path: PipelinePrefMirror
        // updates `state.audio.audioFocusEnabledPref`; the
        // `onCrossModuleStateChange` cascade dispatches this action.
        // Reducer must apply the runtime change iff the live focus
        // state still mismatches the wanted state.
        val state = AudioState(audioFocusEnabledPref = false, audioFocusGranted = true)
        val result = module.reduce(
            state,
            Action.AudioAction.ApplyAudioFocusRuntimeFromPref(enabled = false),
            ctx(),
        )
        assertEquals(state, result!!.nextState)  // state-write is the mirror's job
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.ApplyAudioFocusRuntime(false)),
            result.sideEffects,
        )
    }

    @Test
    fun `ApplyAudioFocusRuntimeFromPref emits no effect when value matches granted`() {
        // Idempotency gate — same value, no AudioManager call needed.
        val state = AudioState(audioFocusGranted = true)
        val result = module.reduce(
            state,
            Action.AudioAction.ApplyAudioFocusRuntimeFromPref(enabled = true),
            ctx(),
        )
        assertEquals(emptyList<AudioModule.Effect>(), result!!.sideEffects)
    }

    @Test
    fun `cross-module pref change during Active recording cascades ApplyAudioFocusRuntimeFromPref`() {
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusEnabledPref = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-ext"),
        )
        val next = prev.copy(
            audio = prev.audio.copy(audioFocusEnabledPref = false),
        )
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "expected ApplyAudioFocusRuntimeFromPref in cascade, got $cascade",
            cascade.contains(
                Action.AudioAction.ApplyAudioFocusRuntimeFromPref(enabled = false),
            ),
        )
    }

    @Test
    fun `cross-module pref change during Idle does NOT cascade ApplyAudioFocusRuntimeFromPref`() {
        // No recording → mirror updates state but no live AudioManager
        // call needs to follow (the next startRecording will read the
        // pref fresh anyway).
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusEnabledPref = true),
        )
        val next = prev.copy(
            audio = prev.audio.copy(audioFocusEnabledPref = false),
        )
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertFalse(
            "Idle pref change should not cascade runtime apply: $cascade",
            cascade.any { it is Action.AudioAction.ApplyAudioFocusRuntimeFromPref },
        )
    }

    @Test
    fun `cross-module pref change during Paused does NOT cascade ApplyAudioFocusRuntimeFromPref`() {
        // Paused: legacy `setAudioFocusRuntime` only acted when state is
        // Active. Mirror that exactly — Paused is a deferred state, the
        // live AudioManager state is already released.
        val prev = DictateUiState.initial().copy(
            audio = AudioState(audioFocusEnabledPref = true),
            recording = RecordingState.Paused(useBluetooth = false, audioFile = testFile, sessionId = "sid-pause"),
        )
        val next = prev.copy(
            audio = prev.audio.copy(audioFocusEnabledPref = false),
        )
        val cascade = module.onCrossModuleStateChange(prev, next)
        assertFalse(
            "Paused pref change should not cascade runtime apply: $cascade",
            cascade.any { it is Action.AudioAction.ApplyAudioFocusRuntimeFromPref },
        )
    }

    @Test
    fun `cross-module pref unchanged does NOT cascade ApplyAudioFocusRuntimeFromPref`() {
        // Defensive: every state-emit goes through onCrossModuleStateChange,
        // most don't touch the audio pref. No `prev != next` → no cascade.
        val state = DictateUiState.initial().copy(
            audio = AudioState(audioFocusEnabledPref = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = testFile, sessionId = "sid-stay"),
        )
        // Same pref, but mutate an unrelated audio field.
        val next = state.copy(audio = state.audio.copy(audioFocusGranted = true))
        val cascade = module.onCrossModuleStateChange(state, next)
        assertFalse(
            "Unchanged pref should not cascade: $cascade",
            cascade.any { it is Action.AudioAction.ApplyAudioFocusRuntimeFromPref },
        )
    }

    @Test
    fun `ToggleAudioFocusPref during Paused recording skips runtime apply`() {
        // Paused is not Active — legacy `setAudioFocusRuntime` only
        // mutated the AudioManager when `state is Active`. Mirror that
        // exactly: in Paused, the flag flips but the live state is left
        // alone until resume.
        val state = AudioState(audioFocusEnabledPref = true, audioFocusGranted = false)
        val ctxPaused = ReducerContext(
            global = DictateUiState.initial().copy(
                audio = state,
                recording = RecordingState.Paused(
                    useBluetooth = false,
                    audioFile = testFile,
                    sessionId = "sid-paused",
                ),
            ),
        )
        val result = module.reduce(state, Action.AudioAction.ToggleAudioFocusPref, ctxPaused)
        assertEquals(
            listOf<AudioModule.Effect>(AudioModule.Effect.PersistAudioFocusPref(false)),
            result!!.sideEffects,
        )
    }
}
