package net.devemperor.dictate.core

import android.media.MediaRecorder
import android.os.Handler
import java.io.File

/**
 * Minimal scheduler abstraction over [Handler.postDelayed] / [Handler.removeCallbacks].
 *
 * Exists for the same reason as [AudioFocusGate]: the controller's pause-timeout
 * logic uses an Android [Handler], but [Handler]'s constructor requires a Looper
 * (Robolectric or instrumentation). Policy K-1 (no Mockito) and K-4 (no Android
 * Context in unit tests) push us to wrap the two methods we actually need so a
 * unit test can supply a no-op or capturing fake.
 *
 * Production: [HandlerPauseTimeoutScheduler]. Tests: a pure-Kotlin fake.
 */
interface PauseTimeoutScheduler {
    fun postDelayed(action: Runnable, delayMs: Long)
    fun removeCallbacks(action: Runnable)
}

/**
 * Production [PauseTimeoutScheduler] backed by an Android [Handler] (typically
 * the main-thread Looper, since the controller's threading contract states all
 * public methods are called on the main thread).
 */
class HandlerPauseTimeoutScheduler(private val handler: Handler) : PauseTimeoutScheduler {
    override fun postDelayed(action: Runnable, delayMs: Long) {
        handler.postDelayed(action, delayMs)
    }
    override fun removeCallbacks(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

/**
 * State machine for the recording lifecycle.
 *
 * Owns all recording coordination that was previously scattered across
 * DictateInputMethodService: state transitions, Bluetooth SCO, audio focus,
 * amplitude processing, and pause timeout.
 *
 * Communicates state changes and events via a single [Callback] interface.
 * The Service sets a composite callback that delegates UI events to
 * RecordingUiController and handles lifecycle events itself.
 *
 * Threading: all public methods must be called on the main thread.
 * RecordingManager's timer already runs on the main thread, so amplitude
 * and timer callbacks arrive without thread-switching.
 *
 * # Post-cutover status (dictate-pipeline-render-and-state-unification §9.3 OQ-3)
 *
 * After the render-cutover-vol2 + indirection-cleanup waves, this
 * controller is **post-cutover dead code on the bound path** — the
 * orchestrator's [net.devemperor.dictate.state.modules.RecordingHardwareAdapter]
 * owns `MediaRecorder` directly, and `startRecording()` / `cancelRecording()`
 * / `togglePause()` are never invoked while the IME is bound. A
 * handful of read-sites in `DictateInputMethodService` still call
 * `recordingStateController.getState()` as a defensive pre-bind
 * fallback (the field is only `null` for the narrow `bindService`
 * window). The plan §9.3 decided to leave the controller in place and
 * mark it deprecated rather than delete it in scope — a follow-up
 * plan `dictate-recording-state-controller-removal` carries the
 * deletion.
 *
 * Do not add new readers / writers. The orchestrator state
 * (`pipelineBinder.getState().value.recording`) is the
 * single source of truth.
 */
@Deprecated(
    message = "Post-cutover dead code on the bound path. The orchestrator's " +
        "RecordingHardwareAdapter owns MediaRecorder + the FSM; read " +
        "recording state from `pipelineBinder.getState().value.recording`. " +
        "Pre-bind fallback callers in DictateInputMethodService.java are the " +
        "only legitimate remaining users. Slated for deletion in a " +
        "follow-up plan (dictate-recording-state-controller-removal).",
)
class RecordingStateController(
    private val gate: AudioFocusGate,
    private val amplitudeProcessor: AmplitudeProcessor,
    private val pauseTimeoutScheduler: PauseTimeoutScheduler
) : RecordingManager.RecordingCallback, BluetoothScoManager.BluetoothScoCallback {

    /**
     * Convenience overload kept so existing [Handler]-based call sites in the
     * service don't need to change construction. Production wraps the
     * [Handler] in a [HandlerPauseTimeoutScheduler] internally.
     */
    constructor(
        gate: AudioFocusGate,
        amplitudeProcessor: AmplitudeProcessor,
        mainHandler: Handler
    ) : this(gate, amplitudeProcessor, HandlerPauseTimeoutScheduler(mainHandler))

    // Setter-injection: managers are set after construction to break circular dependency
    // (Manager needs Controller as callback, Controller needs Manager to call methods).
    // Block 2 / Test Infrastructure: the BT manager is held via the
    // [BluetoothScoControl] interface so unit tests can substitute a pure-Kotlin
    // fake without instantiating the concrete BluetoothScoManager (which needs
    // a Context, AudioManager, and a main-thread Looper).
    private lateinit var recordingManager: RecordingManager
    private lateinit var bluetoothScoManager: BluetoothScoControl

    fun setManagers(recordingManager: RecordingManager, bluetoothScoManager: BluetoothScoControl) {
        this.recordingManager = recordingManager
        this.bluetoothScoManager = bluetoothScoManager
    }

    /**
     * Single callback interface for all recording events.
     *
     * UI-related methods (state/amplitude/timer) are implemented by RecordingUiController.
     * Lifecycle methods have default no-ops — only the Service overrides them.
     */
    interface Callback {
        fun onStateChanged(oldState: RecordingState, newState: RecordingState)
        fun onAmplitudeUpdate(level: Float)
        fun onTimerTick(elapsedMs: Long)
        fun onRecordingError(errorKey: String) {}
        fun onRecordingCompleted(audioFile: File) {}
        fun onKeepScreenAwakeChanged(keepAwake: Boolean) {}
        fun onAutoStopTimeout() {}
    }

    private var callback: Callback? = null

    /** Setter-injection: set after construction to break circular dependency. */
    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    var state: RecordingState = RecordingState.Idle
        private set

    private var audioFile: File? = null
    private var audioFocusEnabled: Boolean = true

    private val pauseTimeoutRunnable = Runnable {
        if (state is RecordingState.Paused) {
            cancelRecording()
            callback?.onAutoStopTimeout()
        }
    }

    // ── Public State Transitions ──

    /**
     * Starts a new recording.
     *
     * @param audioFile output file for the recording
     * @param useBluetooth whether to attempt Bluetooth SCO
     * @param audioFocusEnabled whether to manage audio focus
     */
    fun startRecording(audioFile: File, useBluetooth: Boolean, audioFocusEnabled: Boolean) {
        if (state.isRecordingOrPaused || state is RecordingState.Preparing) return

        this.audioFile = audioFile
        this.audioFocusEnabled = audioFocusEnabled

        if (useBluetooth && bluetoothScoManager.isBluetoothAvailable(useBluetooth)) {
            setState(RecordingState.Preparing(useBluetooth = true))
            bluetoothScoManager.startSco(2500)
        } else {
            proceedStartRecording(MediaRecorder.AudioSource.MIC, false)
        }
    }

    /**
     * Stops the recording and triggers transcription pipeline via callback.
     */
    fun stopRecording() {
        cancelScoWaitIfAny()
        val file = recordingManager.stop()
        callback?.onKeepScreenAwakeChanged(false)
        bluetoothScoManager.release()
        if (audioFocusEnabled) gate.abandon()
        amplitudeProcessor.reset()

        if (file != null) {
            setState(RecordingState.Idle)
            callback?.onRecordingCompleted(file)
        } else {
            setState(RecordingState.Idle)
        }
    }

    /**
     * Toggles between paused and active states.
     */
    fun togglePause() {
        when (val current = state) {
            is RecordingState.Active -> {
                recordingManager.pause()
                if (audioFocusEnabled) gate.abandon()
                setState(RecordingState.Paused)
            }
            is RecordingState.Paused -> {
                if (audioFocusEnabled) gate.request()
                recordingManager.resume()
                // Determine if BT is still connected
                val useBt = bluetoothScoManager.isScoStarted
                setState(RecordingState.Active(useBluetooth = useBt))
            }
            else -> { /* ignore */ }
        }
    }

    /**
     * Mid-recording AudioFocus override. Updates the [audioFocusEnabled] field and,
     * if currently [RecordingState.Active], immediately requests/abandons audio focus.
     *
     * State semantics:
     *  - Idle / Preparing / Paused: only the field is updated. The next state transition
     *    uses the new value via late-binding ([proceedStartRecording] re-reads the field
     *    when transitioning Preparing → Active; [togglePause] re-reads when leaving Paused).
     *  - Active: the field is updated AND AudioManager is mutated synchronously.
     *
     * The next [startRecording] resets the field from the [Pref.AudioFocus] value —
     * this method only affects the running session. Persistent effect comes from the
     * SP-write in [DictateInputMethodService.onAudioFocusToggled].
     *
     * Idempotent (Quality-Gate K10): a second call with the same value is a no-op
     * (the `when`-branch matches neither arm because `wasEnabled == enabled`).
     *
     * Consumed by Block 2's `onAudioFocusToggled()`.
     */
    fun setAudioFocusRuntime(enabled: Boolean) {
        val wasEnabled = audioFocusEnabled
        // Always update the field — Idle/Preparing/Paused defer the AudioManager-Call.
        audioFocusEnabled = enabled
        val current = state
        if (current is RecordingState.Active) {
            when {
                enabled && !wasEnabled -> gate.request()
                !enabled && wasEnabled -> gate.abandon()
            }
        }
    }

    /**
     * Cancels the recording and resets everything to Idle.
     */
    fun cancelRecording() {
        cancelScoWaitIfAny()
        recordingManager.release()
        bluetoothScoManager.release()
        if (audioFocusEnabled) gate.abandon()
        amplitudeProcessor.reset()
        cancelPauseTimeout()
        setState(RecordingState.Idle)
    }

    // ── Lifecycle (delegated from Service) ──

    /**
     * Called when the keyboard is hidden (app switch, back button).
     * Pauses active recording with a 60s auto-stop timeout.
     */
    fun onKeyboardHidden() {
        if (state is RecordingState.Active) {
            togglePause()
            startPauseTimeout()
        }
        if (state is RecordingState.Preparing) {
            cancelRecording()
        }
        // Release BT SCO when keyboard hidden (will rebuild on resume)
        if (state.isRecordingOrPaused) {
            bluetoothScoManager.release()
            if (audioFocusEnabled) gate.abandon()
        }
        callback?.onKeepScreenAwakeChanged(false)
    }

    /**
     * Called when the keyboard appears again.
     * Cancels pause timeout and restores keep-screen-awake.
     */
    fun onKeyboardShown() {
        cancelPauseTimeout()
        callback?.onKeepScreenAwakeChanged(state.isRecordingOrPaused)
    }

    /**
     * Called from Service.onDestroy(). Cleans up everything.
     */
    fun onDestroy() {
        cancelPauseTimeout()
        if (state.isRecordingOrPaused || state is RecordingState.Preparing) {
            cancelRecording()
        }
        amplitudeProcessor.reset()
    }

    // ── RecordingManager.RecordingCallback ──

    override fun onRecordingStarted() {
        val useBt = bluetoothScoManager.isScoStarted
        setState(RecordingState.Active(useBluetooth = useBt))
        callback?.onKeepScreenAwakeChanged(true)
    }

    override fun onRecordingStopped(audioFile: File?) {
        // No-op: stop is always followed by explicit action in stopRecording()
    }

    override fun onRecordingPaused() {
        // Already handled in togglePause()
    }

    override fun onRecordingResumed() {
        // Already handled in togglePause()
    }

    override fun onTimerTick(elapsedMs: Long) {
        callback?.onTimerTick(elapsedMs)
    }

    override fun onAmplitudeUpdate(maxAmplitude: Int) {
        val level = amplitudeProcessor.process(maxAmplitude)
        callback?.onAmplitudeUpdate(level)
    }

    // ── BluetoothScoManager.BluetoothScoCallback ──

    override fun onScoConnected() {
        if (state is RecordingState.Preparing) {
            proceedStartRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true)
        }
        // Update icon if already recording and BT connected
        if (state is RecordingState.Active) {
            val newState = RecordingState.Active(useBluetooth = true)
            setState(newState)
        }
    }

    override fun onScoDisconnected() {
        if (state is RecordingState.Active && (state as RecordingState.Active).useBluetooth) {
            setState(RecordingState.Active(useBluetooth = false))
        }
    }

    override fun onScoFailed() {
        if (state is RecordingState.Preparing) {
            proceedStartRecording(MediaRecorder.AudioSource.MIC, false)
        }
    }

    // ── Internal ──

    private fun proceedStartRecording(audioSource: Int, useBtForThisRecording: Boolean) {
        if (audioFocusEnabled) gate.request()

        val file = audioFile ?: return
        val started = recordingManager.start(file, audioSource)
        if (!started) {
            if (audioFocusEnabled) gate.abandon()
            setState(RecordingState.Idle)
            callback?.onRecordingError("recording_start_failed")
        }
        // On success, RecordingManager fires onRecordingStarted which updates state
    }

    private fun cancelScoWaitIfAny() {
        if (state is RecordingState.Preparing) {
            bluetoothScoManager.release()
        }
    }

    private fun startPauseTimeout() {
        cancelPauseTimeout()
        pauseTimeoutScheduler.postDelayed(pauseTimeoutRunnable, 60_000)
    }

    private fun cancelPauseTimeout() {
        pauseTimeoutScheduler.removeCallbacks(pauseTimeoutRunnable)
    }

    private fun setState(newState: RecordingState) {
        val old = state
        state = newState
        callback?.onStateChanged(old, newState)
    }
}
