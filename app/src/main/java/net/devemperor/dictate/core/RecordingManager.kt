package net.devemperor.dictate.core

import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException

/**
 * Manages MediaRecorder lifecycle and recording timer.
 *
 * Responsibilities:
 * - MediaRecorder setup, start, pause, resume, stop, release
 * - Timer management (start, pause, resume, reset)
 * - State flags (isRecording, isPaused)
 * - Audio format configuration (M4A/AAC)
 *
 * Does NOT handle UI - communicates state changes via RecordingCallback.
 */
class RecordingManager(private val callback: RecordingCallback) {

    interface RecordingCallback {
        fun onRecordingStarted()
        fun onRecordingStopped(audioFile: File?)
        fun onRecordingPaused()
        fun onRecordingResumed()
        fun onTimerTick(elapsedMs: Long)
        fun onAmplitudeUpdate(maxAmplitude: Int) {}
    }

    private var recorder: MediaRecorder? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    var isRecording: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    var elapsedTimeMs: Long = 0L
        private set

    private var currentAudioFile: File? = null

    /**
     * Starts a new recording.
     *
     * @param audioFile output file for the recording
     * @param audioSource MediaRecorder.AudioSource constant (MIC or VOICE_COMMUNICATION)
     * @return true if recording started successfully, false on error
     */
    fun start(audioFile: File, audioSource: Int): Boolean {
        if (isRecording) return false

        currentAudioFile = audioFile
        recorder = MediaRecorder().apply {
            setAudioSource(audioSource)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64000)
            setAudioSamplingRate(44100)
            setOutputFile(audioFile)
        }

        return try {
            recorder!!.prepare()
            recorder!!.start()
            isRecording = true
            isPaused = false
            elapsedTimeMs = 0
            startTimer()
            callback.onRecordingStarted()
            true
        } catch (e: IOException) {
            recorder?.release()
            recorder = null
            isRecording = false
            false
        }
    }

    /**
     * Pauses the current recording.
     * Timer is stopped but state is preserved.
     */
    fun pause() {
        if (!isRecording || isPaused || recorder == null) return

        recorder?.pause()
        stopTimer()
        isPaused = true
        callback.onRecordingPaused()
    }

    /**
     * Resumes a paused recording.
     * Timer continues from where it left off.
     */
    fun resume() {
        if (!isRecording || !isPaused || recorder == null) return

        recorder?.resume()
        isPaused = false
        startTimer()
        callback.onRecordingResumed()
    }

    /**
     * Stops the recording and releases the MediaRecorder.
     * @return the audio file that was recorded, or null if no recording was active
     */
    fun stop(): File? {
        if (!isRecording) return null

        stopTimer()
        val file = currentAudioFile

        if (recorder != null) {
            try {
                recorder!!.stop()
            } catch (_: RuntimeException) {
                // stop() can throw if recorder is in an invalid state
            }
            recorder!!.release()
            recorder = null
        }

        isRecording = false
        isPaused = false
        callback.onRecordingStopped(file)
        return file
    }

    /**
     * Releases the MediaRecorder without stopping (for error/cleanup scenarios).
     */
    fun release() {
        stopTimer()
        recorder?.let {
            try { it.stop() } catch (_: RuntimeException) {}
            it.release()
        }
        recorder = null
        isRecording = false
        isPaused = false
    }

    /**
     * Resets timer and state without affecting the MediaRecorder.
     */
    fun resetTimer() {
        stopTimer()
        elapsedTimeMs = 0
    }

    /**
     * Returns the raw MediaRecorder for direct access (e.g., amplitude queries).
     * Use with caution - prefer using manager methods.
     */
    fun getRecorder(): MediaRecorder? = recorder

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                elapsedTimeMs += 100
                callback.onTimerTick(elapsedTimeMs)
                try {
                    recorder?.maxAmplitude?.let { callback.onAmplitudeUpdate(it) }
                } catch (_: IllegalStateException) { }
                timerHandler.postDelayed(this, 100)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }
}
