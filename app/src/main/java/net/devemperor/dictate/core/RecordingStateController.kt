package net.devemperor.dictate.core

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import java.io.File

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
 */
class RecordingStateController(
    private val audioManager: AudioManager,
    private val audioFocusRequest: AudioFocusRequest,
    private val amplitudeProcessor: AmplitudeProcessor,
    private val mainHandler: Handler
) : RecordingManager.RecordingCallback, BluetoothScoManager.BluetoothScoCallback {

    // Setter-injection: managers are set after construction to break circular dependency
    // (Manager needs Controller as callback, Controller needs Manager to call methods)
    private lateinit var recordingManager: RecordingManager
    private lateinit var bluetoothScoManager: BluetoothScoManager

    fun setManagers(recordingManager: RecordingManager, bluetoothScoManager: BluetoothScoManager) {
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
        if (audioFocusEnabled) audioManager.abandonAudioFocusRequest(audioFocusRequest)
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
                if (audioFocusEnabled) audioManager.abandonAudioFocusRequest(audioFocusRequest)
                setState(RecordingState.Paused)
            }
            is RecordingState.Paused -> {
                if (audioFocusEnabled) audioManager.requestAudioFocus(audioFocusRequest)
                recordingManager.resume()
                // Determine if BT is still connected
                val useBt = bluetoothScoManager.isScoStarted
                setState(RecordingState.Active(useBluetooth = useBt))
            }
            else -> { /* ignore */ }
        }
    }

    /**
     * Cancels the recording and resets everything to Idle.
     */
    fun cancelRecording() {
        cancelScoWaitIfAny()
        recordingManager.release()
        bluetoothScoManager.release()
        if (audioFocusEnabled) audioManager.abandonAudioFocusRequest(audioFocusRequest)
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
            if (audioFocusEnabled) audioManager.abandonAudioFocusRequest(audioFocusRequest)
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
        if (audioFocusEnabled) audioManager.requestAudioFocus(audioFocusRequest)

        val file = audioFile ?: return
        val started = recordingManager.start(file, audioSource)
        if (!started) {
            if (audioFocusEnabled) audioManager.abandonAudioFocusRequest(audioFocusRequest)
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
        mainHandler.postDelayed(pauseTimeoutRunnable, 60_000)
    }

    private fun cancelPauseTimeout() {
        mainHandler.removeCallbacks(pauseTimeoutRunnable)
    }

    private fun setState(newState: RecordingState) {
        val old = state
        state = newState
        callback?.onStateChanged(old, newState)
    }
}
