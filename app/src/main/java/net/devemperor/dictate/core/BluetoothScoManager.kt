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
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio connections.
 *
 * Handles:
 * - SCO connection setup and teardown
 * - BroadcastReceiver for SCO state updates
 * - Timeout handling with fallback to built-in microphone
 * - Reconnect after pause
 */
class BluetoothScoManager(
    private val context: Context,
    private val audioManager: AudioManager,
    private val callback: BluetoothScoCallback
) {
    interface BluetoothScoCallback {
        fun onScoConnected()
        fun onScoDisconnected()
        fun onScoFailed()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var broadcastReceiver: BroadcastReceiver? = null
    private var timeoutRunnable: Runnable? = null

    var isScoStarted: Boolean = false
        private set
    var isWaitingForSco: Boolean = false
        private set

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
                        isScoStarted = true
                        if (isWaitingForSco) {
                            isWaitingForSco = false
                            cancelTimeout()
                        }
                        callback.onScoConnected()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        isScoStarted = false
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
    fun startSco(timeoutMs: Long = 2500): Boolean {
        if (audioManager.isBluetoothScoOn) {
            isScoStarted = true
            callback.onScoConnected()
            return true
        }

        isWaitingForSco = true
        audioManager.startBluetoothSco()

        timeoutRunnable = Runnable {
            if (isWaitingForSco) {
                isWaitingForSco = false
                try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
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
    fun release() {
        cancelTimeout()
        isWaitingForSco = false
        if (isScoStarted) {
            try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
            isScoStarted = false
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
    fun isBluetoothAvailable(useBluetoothMic: Boolean): Boolean {
        return useBluetoothMic
                && audioManager.isBluetoothScoAvailableOffCall
                && hasBluetoothInputDevice()
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
