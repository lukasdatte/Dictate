package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [RecordingStateController.setAudioFocusRuntime] (Block 3c)
 * and the matching multi-recording / idempotency invariants (Block 2 / V-2).
 *
 * Quality-Gate references:
 *  - K-1: handwritten fakes only — no Mockito.
 *  - K-4: tests run in the JVM unit-test runner without Robolectric or
 *    Android instrumentation. The Android-only seams ([RecordingManager],
 *    [BluetoothScoManager], [AudioFocusGate]) are open/abstracted so the
 *    fakes below can substitute them without touching MediaRecorder, the
 *    AudioManager, or a Looper.
 *  - K10: idempotency of [RecordingStateController.setAudioFocusRuntime].
 *  - V-2: the sequence + multi-recording cases mirror the device-mode
 *    verification checklist; running them headless lets a regression be
 *    caught before the manual phase.
 */
class RecordingStateControllerTest {

    private lateinit var gate: FakeAudioFocusGate
    private lateinit var amplitude: AmplitudeProcessor
    private lateinit var scheduler: NoOpPauseTimeoutScheduler
    private lateinit var controller: RecordingStateController
    private lateinit var fakeRecordingManager: FakeRecordingManager
    private lateinit var fakeBluetoothScoManager: FakeBluetoothScoControl

    private val tempFile = File("/tmp/test-recording-state-controller.m4a")

    @Before
    fun setUp() {
        gate = FakeAudioFocusGate()
        amplitude = AmplitudeProcessor()
        scheduler = NoOpPauseTimeoutScheduler()
        controller = RecordingStateController(gate, amplitude, scheduler)
        fakeRecordingManager = FakeRecordingManager(controller)
        fakeBluetoothScoManager = FakeBluetoothScoControl()
        controller.setManagers(fakeRecordingManager, fakeBluetoothScoManager)
        // Wire a state-tracking callback so tests can observe transitions.
        controller.setCallback(NoOpControllerCallback)
    }

    // ───────────────────────────────────────────────────────────
    // Idle / Preparing / Paused — only field, no gate calls
    // ───────────────────────────────────────────────────────────

    @Test
    fun `setAudioFocusRuntime in Idle state only updates field, no gate calls`() {
        // Pre: state is Idle (default).
        assertSame(RecordingState.Idle, controller.state)

        controller.setAudioFocusRuntime(false)
        controller.setAudioFocusRuntime(true)

        // No gate touches because we never entered Active.
        assertEquals(0, gate.requestCount)
        assertEquals(0, gate.abandonCount)
        // Field-only update is verified indirectly: starting a recording
        // immediately after with audioFocusEnabled=true reads the new field —
        // but startRecording explicitly overwrites the field from its own
        // parameter. The field-only contract for Idle is therefore covered
        // by "no AudioManager mutation occurred", which is the gate counter.
    }

    @Test
    fun `setAudioFocusRuntime in Paused state only updates field, deferred to next togglePause`() {
        // Drive controller to Paused: start with focus enabled, transition to
        // Active via the recording-manager callback, then togglePause.
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = true)
        // proceedStartRecording fires gate.request() before recordingManager.start().
        assertEquals(1, gate.requestCount)
        // FakeRecordingManager.start() invokes onRecordingStarted → state is Active.
        assertTrue(controller.state is RecordingState.Active)

        controller.togglePause()
        assertSame(RecordingState.Paused, controller.state)
        // togglePause(Active→Paused) abandons focus.
        assertEquals(1, gate.abandonCount)

        val requestsBefore = gate.requestCount
        val abandonsBefore = gate.abandonCount

        // While paused, flip the runtime flag — pure field update.
        controller.setAudioFocusRuntime(false)

        assertEquals(requestsBefore, gate.requestCount)
        assertEquals(abandonsBefore, gate.abandonCount)
    }

    @Test
    fun `setAudioFocusRuntime in Preparing state only updates field, transition to Active reads new value via late-binding`() {
        // Drive to Preparing via Bluetooth path: useBluetooth=true and the fake
        // SCO manager reports BT available, so startRecording sets state to
        // Preparing(true) and waits for onScoConnected.
        fakeBluetoothScoManager.bluetoothAvailable = true
        controller.startRecording(tempFile, useBluetooth = true, audioFocusEnabled = true)

        assertTrue(
            "expected Preparing, got ${controller.state}",
            controller.state is RecordingState.Preparing
        )
        // No gate.request yet — that fires inside proceedStartRecording.
        assertEquals(0, gate.requestCount)

        // Mid-Preparing override: flip focus off. Field-only.
        controller.setAudioFocusRuntime(false)
        assertEquals(0, gate.requestCount)
        assertEquals(0, gate.abandonCount)

        // Now simulate SCO success: controller transitions Preparing → Active
        // through proceedStartRecording, which reads audioFocusEnabled (now false)
        // and skips gate.request() — late binding works.
        controller.onScoConnected()
        assertTrue(controller.state is RecordingState.Active)
        assertEquals(0, gate.requestCount)
        assertEquals(0, gate.abandonCount)
    }

    // ───────────────────────────────────────────────────────────
    // Active — synchronous gate mutation
    // ───────────────────────────────────────────────────────────

    @Test
    fun `setAudioFocusRuntime to true in Active when previously false (1 req, 0 abandon)`() {
        // Active with focus off.
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = false)
        assertTrue(controller.state is RecordingState.Active)
        assertEquals(0, gate.requestCount)
        assertEquals(0, gate.abandonCount)

        controller.setAudioFocusRuntime(true)

        assertEquals(1, gate.requestCount)
        assertEquals(0, gate.abandonCount)
    }

    @Test
    fun `setAudioFocusRuntime to false in Active when previously true (0 req, 1 abandon)`() {
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = true)
        assertTrue(controller.state is RecordingState.Active)
        assertEquals(1, gate.requestCount) // from proceedStartRecording
        assertEquals(0, gate.abandonCount)

        controller.setAudioFocusRuntime(false)

        // Exactly one new abandon, no new request.
        assertEquals(1, gate.requestCount)
        assertEquals(1, gate.abandonCount)
    }

    @Test
    fun `setAudioFocusRuntime no-op when value unchanged in Active (0 req, 0 abandon)`() {
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = true)
        val requestsBefore = gate.requestCount
        val abandonsBefore = gate.abandonCount

        controller.setAudioFocusRuntime(true) // already true

        assertEquals(requestsBefore, gate.requestCount)
        assertEquals(abandonsBefore, gate.abandonCount)
    }

    // ───────────────────────────────────────────────────────────
    // Sequence-level invariants (V-2)
    // ───────────────────────────────────────────────────────────

    @Test
    fun `Active + setAudioFocusRuntime(false) + togglePause + togglePause sees 1 abandon and no extra request on resume`() {
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = true)
        assertEquals(1, gate.requestCount)
        assertEquals(0, gate.abandonCount)

        // Live-toggle off while Active: 1 abandon (synchronous gate mutation).
        controller.setAudioFocusRuntime(false)
        assertEquals(1, gate.requestCount)
        assertEquals(1, gate.abandonCount)

        // Pause: Active → Paused. Because audioFocusEnabled is now false,
        // togglePause must NOT call abandon again.
        controller.togglePause()
        assertSame(RecordingState.Paused, controller.state)
        assertEquals(1, gate.requestCount)
        assertEquals(1, gate.abandonCount)

        // Resume: Paused → Active. Because audioFocusEnabled is false,
        // togglePause must NOT call request — the audio focus stays released
        // for the rest of this session.
        controller.togglePause()
        assertTrue(controller.state is RecordingState.Active)
        assertEquals(1, gate.requestCount)
        assertEquals(1, gate.abandonCount)
    }

    @Test
    fun `Multi-recording sequence — second startRecording with prefTrue re-requests after stop`() {
        // First recording: focus on.
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = true)
        assertEquals(1, gate.requestCount)
        assertEquals(0, gate.abandonCount)

        // Live-override mid-recording: focus off. (1 abandon.)
        controller.setAudioFocusRuntime(false)
        assertEquals(1, gate.requestCount)
        assertEquals(1, gate.abandonCount)

        // Stop: stopRecording calls gate.abandon ONLY if audioFocusEnabled is
        // true at the time of stop — the field is now false so no extra abandon.
        controller.stopRecording()
        assertSame(RecordingState.Idle, controller.state)
        assertEquals(1, gate.requestCount)
        assertEquals(1, gate.abandonCount)

        // Second recording: prefTrue. This MUST re-request, proving the
        // field is overwritten from the parameter on every startRecording.
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = true)
        assertEquals(2, gate.requestCount)
        assertEquals(1, gate.abandonCount)
        assertTrue(controller.state is RecordingState.Active)
    }

    // ───────────────────────────────────────────────────────────
    // Idempotency (K10)
    // ───────────────────────────────────────────────────────────

    @Test
    fun `Active + setAudioFocusRuntime(true) twice — exactly 1 request total`() {
        // Start with focus off so the first explicit (true) actually flips.
        controller.startRecording(tempFile, useBluetooth = false, audioFocusEnabled = false)
        assertEquals(0, gate.requestCount)

        controller.setAudioFocusRuntime(true)
        controller.setAudioFocusRuntime(true)

        assertEquals(
            "second call must be a no-op (wasEnabled == enabled)",
            1, gate.requestCount
        )
        assertEquals(0, gate.abandonCount)
    }

    // ───────────────────────────────────────────────────────────
    // Test fakes
    // ───────────────────────────────────────────────────────────

    /**
     * No-op pause-timeout scheduler. The tests in this file do not exercise
     * the keyboard-hidden / pause-timeout path; ignoring postDelayed is safe.
     */
    private class NoOpPauseTimeoutScheduler : PauseTimeoutScheduler {
        override fun postDelayed(action: Runnable, delayMs: Long) { /* no-op */ }
        override fun removeCallbacks(action: Runnable) { /* no-op */ }
    }

    /**
     * Fake [RecordingManager] that simulates a successful start by firing the
     * controller's [RecordingManager.RecordingCallback.onRecordingStarted]
     * synchronously. No MediaRecorder, no Handler.
     *
     * The controller is the callback recipient (it implements
     * [RecordingManager.RecordingCallback]) — passing it directly avoids the
     * lateinit-circular-dependency dance from the production wiring.
     */
    private class FakeRecordingManager(
        private val recordingCallback: RecordingManager.RecordingCallback
    ) : RecordingManager(recordingCallback) {

        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        var pauseCount: Int = 0
            private set
        var resumeCount: Int = 0
            private set

        override fun start(audioFile: File, audioSource: Int): Boolean {
            startCount += 1
            recordingCallback.onRecordingStarted()
            return true
        }

        override fun stop(): File? {
            stopCount += 1
            return File("/tmp/fake-recording.m4a")
        }

        override fun pause() {
            pauseCount += 1
        }

        override fun resume() {
            resumeCount += 1
        }

        override fun release() { /* no-op */ }
    }

    /**
     * Pure-Kotlin fake [BluetoothScoControl] — no Android Context, no AudioManager,
     * no Looper. Each method is reduced to a deterministic stub the controller
     * tests can drive directly.
     *
     * Quality-Gate K-4 (no Android Context): satisfied because [BluetoothScoControl]
     * is the seam, not the concrete [BluetoothScoManager].
     */
    private class FakeBluetoothScoControl(
        var bluetoothAvailable: Boolean = false,
        override var isScoStarted: Boolean = false
    ) : BluetoothScoControl {
        override fun startSco(timeoutMs: Long): Boolean = false
        override fun release() { /* no-op */ }
        override fun isBluetoothAvailable(useBluetoothMic: Boolean): Boolean =
            useBluetoothMic && bluetoothAvailable
    }

    /**
     * Minimal callback impl — the tests inspect [FakeAudioFocusGate] counters
     * and the controller's [RecordingStateController.state], not callback
     * dispatch. Suppressing the events keeps the test signal narrow.
     */
    private object NoOpControllerCallback : RecordingStateController.Callback {
        override fun onStateChanged(oldState: RecordingState, newState: RecordingState) {}
        override fun onAmplitudeUpdate(level: Float) {}
        override fun onTimerTick(elapsedMs: Long) {}
    }
}
