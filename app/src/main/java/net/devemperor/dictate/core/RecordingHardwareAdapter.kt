package net.devemperor.dictate.core

import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.audio.CodecParams
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.ModuleId
import net.devemperor.dictate.state.RecordingHardwareSubsystem
import java.io.File
import java.io.IOException

/**
 * Production [RecordingHardwareSubsystem] backed by an Android
 * [MediaRecorder].
 *
 * **C8 — subsystem-adapter migration (Spec 1 §9.6 + §15.2):** the legacy
 * [RecordingManager] is kept in place for the IME-side recording flow
 * (driven by [RecordingStateController]); this adapter is a **parallel**
 * production-quality implementation that the [DictateOrchestrator]
 * consumes via [RecordingModule.runEffect]. Both paths can coexist
 * because today the orchestrator-side modules are not yet the primary
 * driver of recording — the IME still owns the recording UX. The
 * adapter ensures that when a future block (B4-B6 LayoutCatalog /
 * Overlay) routes record-button clicks through `dispatch(Action.RecordingAction.X)`,
 * the corresponding effects produce real audio without further wiring.
 *
 * **Threading:** all public methods MUST be called on the main thread
 * (the dispatch loop is `Dispatchers.Main.immediate`-confined per
 * [DictateOrchestrator]). The internal Timer/amplitude callbacks
 * post back to the main looper via [Handler].
 *
 * **Failure routing:** errors during [allocate] / [start] surface via
 * [emitAction] as [Action.EffectFailure] carrying [ModuleId.Recording]
 * so the reducer's `reduceFailure` arm rolls back the FSM.
 *
 * @param audioSource the [MediaRecorder.AudioSource] constant used when
 *   the recording is **not** Bluetooth — typically [MediaRecorder.AudioSource.MIC].
 *   Bluetooth recordings use [MediaRecorder.AudioSource.VOICE_COMMUNICATION].
 * @param emitAction main-thread re-entry into the orchestrator. Called
 *   when the adapter completes `allocate` to signal
 *   [Action.RecordingAction.MediaRecorderReady] (audio file is ready
 *   for the FSM transition Preparing → Active).
 *
 * @see net.devemperor.dictate.state.RecordingHardwareSubsystem
 * @see net.devemperor.dictate.core.RecordingManager
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §9.1 §15.2
 */
class RecordingHardwareAdapter(
    private val emitAction: (Action) -> Unit,
    private val audioFileRepository: AudioFileRepository? = null,
    private val scope: CoroutineScope = MainScope(),
    private val rollingIntervalMs: Long = DEFAULT_ROLLING_INTERVAL_MS,
) : RecordingHardwareSubsystem {

    private var recorder: MediaRecorder? = null

    /**
     * Active recording session-id, set by [allocate]. Used by the
     * Rolling-Segment loop to call
     * [AudioFileRepository.allocateNext]. Null when no session is
     * active or when the caller did not supply a session-id (tests,
     * legacy path).
     */
    private var activeSessionId: String? = null

    /**
     * Rolling-Segment timer job. Null when no rolling is active —
     * either because no recording is in flight, the session-id was
     * not supplied, or the repository was not injected.
     */
    private var rollingJob: Job? = null

    override fun allocate(
        target: InsertionTarget,
        useBluetooth: Boolean,
        audioFile: File,
        codecParams: CodecParams?,
        sessionId: String?,
    ) {
        if (recorder != null) {
            Log.w(TAG, "allocate() called with existing recorder — releasing previous instance")
            releaseRecorder()
        }
        activeSessionId = sessionId
        val source = if (useBluetooth) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        // codecParams==null = fresh session (no previous segment to
        // inherit from) → use the historic defaults. Cold-Resume passes
        // params read from the previous segment so the eventual
        // MediaMuxer concat does not reject heterogeneous formats.
        val params = codecParams ?: CodecParams.DEFAULT_AAC_M4A
        val mr = MediaRecorder().apply {
            setAudioSource(source)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(params.bitRate)
            setAudioSamplingRate(params.sampleRate)
            if (params.channelCount > 1) {
                setAudioChannels(params.channelCount)
            }
            setOutputFile(audioFile)
        }
        recorder = mr
        try {
            mr.prepare()
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder.prepare() failed", e)
            releaseRecorder()
            emitAction(
                Action.EffectFailure(
                    originModuleId = ModuleId.Recording,
                    effect = "AllocateMediaRecorder($target, $useBluetooth, $audioFile)",
                    reason = e.message ?: "prepare-failed",
                )
            )
            return
        }
        // Signal Preparing → Active. The audioFile is also already in
        // RecordingState.Preparing (R.2); we echo it in the action so
        // the reducer arm can switch into RecordingState.Active without
        // a separate state-read.
        emitAction(Action.RecordingAction.MediaRecorderReady(audioFile))
    }

    override fun start() {
        val mr = recorder
        if (mr == null) {
            Log.w(TAG, "start() called without an active recorder — no-op")
            return
        }
        try {
            mr.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder.start() failed", e)
            emitAction(
                Action.EffectFailure(
                    originModuleId = ModuleId.Recording,
                    effect = "StartMediaRecorder",
                    reason = e.message ?: "start-failed",
                )
            )
            return
        }
        startRollingTimerIfPossible()
    }

    override fun pause() {
        val mr = recorder ?: return
        try {
            mr.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.pause() failed (ignored — best-effort)", e)
        }
        // Stop the rolling timer while paused — the MediaRecorder is
        // not writing samples, so periodic setNextOutputFile() calls
        // would only churn empty segments. `resume()` re-arms it.
        cancelRollingTimer()
    }

    override fun resume() {
        val mr = recorder ?: return
        try {
            mr.resume()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.resume() failed (ignored)", e)
            return
        }
        startRollingTimerIfPossible()
    }

    override fun stop() {
        val mr = recorder ?: return
        cancelRollingTimer()
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // stop() throws on too-short streams or invalid states;
            // best-effort, the reducer's reduceFailure arm handles
            // FSM rollback.
            Log.w(TAG, "MediaRecorder.stop() failed", e)
            emitAction(
                Action.EffectFailure(
                    originModuleId = ModuleId.Recording,
                    effect = "StopMediaRecorder",
                    reason = e.message ?: "stop-failed",
                )
            )
        }
        releaseRecorder()
    }

    override fun release() {
        cancelRollingTimer()
        releaseRecorder()
    }

    private fun releaseRecorder() {
        recorder?.let {
            try { it.release() } catch (e: Exception) { Log.w(TAG, "release() failed", e) }
        }
        recorder = null
        activeSessionId = null
    }

    // ──── Rolling-Segments (L2) ─────────────────────────────────────

    /**
     * Start the periodic `setNextOutputFile` loop. No-op when the
     * adapter was constructed without an [AudioFileRepository], or
     * when [allocate] was called without a session-id (legacy /
     * test path). Idempotent — calling twice cancels and restarts.
     */
    private fun startRollingTimerIfPossible() {
        val repo = audioFileRepository
        val sid = activeSessionId
        if (repo == null || sid == null) {
            // Rolling is opt-in: a missing dependency just means we
            // keep the historic single-segment behaviour.
            return
        }
        cancelRollingTimer()
        rollingJob = scope.launch {
            while (isActive) {
                delay(rollingIntervalMs)
                rollNextSegment(repo, sid)
            }
        }
    }

    private fun cancelRollingTimer() {
        rollingJob?.cancel()
        rollingJob = null
    }

    private fun rollNextSegment(repo: AudioFileRepository, sessionId: String) {
        val mr = recorder ?: return
        val nextFile = try {
            repo.allocateNext(sessionId)
        } catch (e: IOException) {
            Log.w(TAG, "Rolling: allocateNext failed for $sessionId — keeping current segment", e)
            return
        }
        try {
            mr.setNextOutputFile(nextFile)
        } catch (e: Exception) {
            // setNextOutputFile throws IllegalStateException when the
            // recorder is not currently recording (e.g. mid-pause race),
            // and IOException on filesystem errors. Either way the
            // current segment keeps writing — the rolling cycle is a
            // best-effort durability boost, not a correctness invariant.
            Log.w(TAG, "Rolling: setNextOutputFile($nextFile) failed", e)
        }
    }

    /**
     * Test-only — expose the active [MediaRecorder]. Lets the unit-test
     * suite assert the allocator went through the expected states without
     * widening the public [RecordingHardwareSubsystem] interface.
     */
    internal fun activeRecorder(): MediaRecorder? = recorder

    /**
     * Post-cutover hotfix #3+#4 — read the current peak amplitude from
     * the active MediaRecorder for the IME-side recording-animation /
     * waveform side-channel ([ImeViewBackend.onAmplitude]).
     *
     * Returns `null` when no recording is in flight or the MediaRecorder
     * is not in a state that accepts `getMaxAmplitude()` (between
     * `start()` and `stop()`). Wraps the underlying call in try/catch
     * because `MediaRecorder.getMaxAmplitude` can throw
     * `IllegalStateException` on race conditions (e.g. polled
     * concurrently with stop) — the polling side-channel must never
     * crash the IME process.
     *
     * This is the bridge the legacy `RecordingManager`'s 100ms
     * amplitude-polling loop is built around; on the new path the
     * orchestrator-side [RecordingActivityTickerObserver] polls this
     * function at the same cadence and forwards into the side-channel.
     */
    fun maxAmplitudeOrNull(): Int? = try {
        recorder?.maxAmplitude
    } catch (e: IllegalStateException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val TAG: String = "RecordingHwAdapter"

        /**
         * Default Rolling-Segment interval, matches
         * `Pref.RollingSegmentIntervalSec.default` (30 s). The Pref
         * lookup is the caller's responsibility — the adapter receives
         * the resolved value via its constructor so unit tests can
         * pass a smaller interval (e.g. 50 ms) without going through
         * SharedPreferences.
         */
        const val DEFAULT_ROLLING_INTERVAL_MS: Long = 30_000L
    }
}
