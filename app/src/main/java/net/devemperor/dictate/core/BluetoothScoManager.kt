package net.devemperor.dictate.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Test-seam abstraction over [BluetoothScoManager] — covers the methods
 * [RecordingStateController] calls on the concrete manager.
 *
 * Block 2 / Test Infrastructure: extracted so unit tests can supply a
 * pure-Kotlin fake (no Android Context/AudioManager/Looper) without having
 * to subclass the concrete manager. Production code keeps using the
 * concrete [BluetoothScoManager] directly; the controller only holds the
 * interface reference.
 *
 * The receiver-lifecycle methods ([BluetoothScoManager.registerReceiver]
 * and [BluetoothScoManager.unregisterReceiver]) are NOT part of this
 * interface — they belong to the service's lifecycle wiring, not the
 * controller's recording state machine.
 */
interface BluetoothScoControl {
    /** Whether SCO is currently started/connected. Read-only from the controller's POV. */
    val isScoStarted: Boolean

    /** Attempts to start SCO. Returns true if already connected. */
    fun startSco(timeoutMs: Long = 2500): Boolean

    /** Releases the SCO connection (no-op if not started). */
    fun release()

    /** Whether Bluetooth SCO is available off-call AND a BT input device exists. */
    fun isBluetoothAvailable(useBluetoothMic: Boolean): Boolean
}

/**
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio connections.
 *
 * Handles:
 * - SCO connection setup and teardown
 * - BroadcastReceiver for SCO state updates
 * - Timeout handling with fallback to built-in microphone
 * - Reconnect after pause
 */
// Test Infrastructure: the unit tests inject a `FakeBluetoothScoControl`
// (an [BluetoothScoControl] interface implementation, NOT a subclass), so the
// concrete class itself does not strictly need to be `open`. It stays `open`
// out of an abundance of caution — Kotlin's default `final` would forbid
// future ad-hoc test subclassing without a class-level diff.
open class BluetoothScoManager(
    private val context: Context,
    private val audioManager: AudioManager,
    private val callback: BluetoothScoCallback
) : BluetoothScoControl {
    interface BluetoothScoCallback {
        fun onScoConnected()
        fun onScoDisconnected()
        fun onScoFailed()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var broadcastReceiver: BroadcastReceiver? = null
    private var timeoutRunnable: Runnable? = null

    private var _isScoStarted: Boolean = false
    override val isScoStarted: Boolean
        get() = _isScoStarted
    var isWaitingForSco: Boolean = false
        private set

    /**
     * `true` between our own `stopBluetoothSco()` call and the next
     * terminal SCO broadcast (widget-cancel-restart fix, 2026-07-09).
     *
     * Android tears the SCO link down **asynchronously**:
     * `AudioManager.isBluetoothScoOn` keeps reporting `true` until the
     * `SCO_AUDIO_STATE_DISCONNECTED` broadcast lands. A recording
     * cancelled and immediately restarted (overlay-widget trash →
     * record) therefore hit [startSco]'s already-connected early-return
     * against the *dying* link: `onScoConnected` fired synchronously,
     * the `MediaRecorder` was allocated with `VOICE_COMMUNICATION`, and
     * the capture route died underneath it — a recording that visually
     * runs (the state-driven timer keeps ticking) but captures silence.
     *
     * While this flag is set, [startSco] skips the early-return and
     * runs the full handshake instead — the next CONNECTED broadcast
     * (or the timeout → MIC fallback) resolves it safely either way.
     * Any terminal broadcast (CONNECTED or DISCONNECTED) proves the
     * platform flag is fresh again and clears the window.
     */
    private var teardownInFlight: Boolean = false

    /**
     * Registers the BroadcastReceiver for SCO state updates.
     * Safe to call multiple times (no-op if already registered).
     */
    fun registerReceiver() {
        if (broadcastReceiver != null) return

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        _isScoStarted = true
                        // Terminal broadcast — the platform flag is
                        // authoritative again (teardown window closed).
                        teardownInFlight = false
                        if (isWaitingForSco) {
                            isWaitingForSco = false
                            cancelTimeout()
                        }
                        callback.onScoConnected()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        _isScoStarted = false
                        // Teardown completed — window closed.
                        teardownInFlight = false
                        callback.onScoDisconnected()
                    }
                }
            }
        }
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
    }

    /**
     * Attempts to start a Bluetooth SCO connection.
     * If already connected, calls onScoConnected immediately.
     * Otherwise, starts SCO and sets a timeout for fallback.
     *
     * @param timeoutMs timeout in milliseconds before falling back
     * @return true if SCO was already connected, false if waiting
     */
    override fun startSco(timeoutMs: Long): Boolean {
        // The early-return is only safe when the platform flag is
        // trustworthy — inside the async-teardown window it still
        // reports the dying link (see [teardownInFlight]).
        if (audioManager.isBluetoothScoOn && !teardownInFlight) {
            _isScoStarted = true
            callback.onScoConnected()
            return true
        }

        isWaitingForSco = true
        audioManager.startBluetoothSco()

        timeoutRunnable = Runnable {
            if (isWaitingForSco) {
                isWaitingForSco = false
                try {
                    audioManager.stopBluetoothSco()
                    // Our own stop → async teardown window opens.
                    teardownInFlight = true
                } catch (_: Exception) {
                }
                callback.onScoFailed()
            }
        }
        handler.postDelayed(timeoutRunnable!!, timeoutMs)

        return false
    }

    /**
     * Releases the SCO connection.
     * Call this when pausing recording or when the service is destroyed.
     */
    override fun release() {
        cancelTimeout()
        isWaitingForSco = false
        if (_isScoStarted) {
            try {
                audioManager.stopBluetoothSco()
                // Our own stop → async teardown window opens; the next
                // terminal broadcast (or a fresh full handshake) closes
                // it. See [teardownInFlight].
                teardownInFlight = true
            } catch (_: Exception) {
            }
            _isScoStarted = false
        }
    }

    /**
     * Rebuilds SCO connection after a pause.
     * Called when the user manually resumes recording.
     *
     * @param timeoutMs timeout in milliseconds before falling back
     */
    fun reconnect(timeoutMs: Long = 2500) {
        startSco(timeoutMs)
    }

    /**
     * Unregisters the BroadcastReceiver and releases all resources.
     */
    fun unregisterReceiver() {
        broadcastReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        broadcastReceiver = null
        release()
    }

    /**
     * Checks if a Bluetooth SCO input device is available.
     */
    fun hasBluetoothInputDevice(): Boolean {
        return try {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (_: Exception) {
            audioManager.isBluetoothScoOn
        }
    }

    /**
     * Checks if Bluetooth SCO is available off-call and a BT input device exists.
     */
    override fun isBluetoothAvailable(useBluetoothMic: Boolean): Boolean {
        return useBluetoothMic
                && audioManager.isBluetoothScoAvailableOffCall
                && hasBluetoothInputDevice()
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
