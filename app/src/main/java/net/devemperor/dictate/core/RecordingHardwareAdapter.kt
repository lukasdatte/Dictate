package net.devemperor.dictate.core

import android.media.MediaRecorder
import android.util.Log
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
 * driver of recording — the IME still owns the recording UX.
 *
 * **Rolling-Segments (B1.3 / ADR-0007 §"Activation + Rolling-Segments").**
 * When constructed with an [AudioFileRepository] and [allocate] is
 * called with a non-null `sessionId`, the adapter wires the
 * MediaRecorder's `MAX_DURATION_APPROACHING` callback to call
 * `setNextOutputFile(allocateNext(sessionId))`. The MediaRecorder
 * then rolls automatically when the per-segment duration limit
 * (`rollingIntervalMs`) is reached — finalising the previous segment
 * with a complete `moov` atom, so a crash mid-segment loses at most
 * one rolling interval of audio.
 *
 * **Why callback-driven, not timer-driven?** The earlier B1.3 timer
 * approach (Kotlin coroutine that called `setNextOutputFile` every
 * 30 s) hit Android's MediaRecorder error `-38` (INVALID_OPERATION):
 * `setNextOutputFile` is only valid in the narrow window between the
 * `MAX_DURATION_APPROACHING` and `MAX_DURATION_REACHED` infos.
 * Outside that window the native call rejects. The callback-driven
 * variant matches the API's documented contract.
 *
 * **Threading:** all public methods MUST be called on the main thread
 * (the dispatch loop is `Dispatchers.Main.immediate`-confined per
 * [DictateOrchestrator]). The OnInfoListener also fires on the main
 * looper.
 *
 * **Failure routing:** errors during [allocate] / [start] surface via
 * [emitAction] as [Action.EffectFailure] carrying [ModuleId.Recording]
 * so the reducer's `reduceFailure` arm rolls back the FSM.
 *
 * @param emitAction main-thread re-entry into the orchestrator.
 * @param audioFileRepository repository for Rolling-Segment files.
 *   `null` opts out of rolling — the adapter falls back to a single-
 *   segment recording, identical to the pre-B1.3 behaviour.
 * @param rollingIntervalMs per-segment duration cap (default 30 s,
 *   from `Pref.RollingSegmentIntervalSec`).
 *
 * @see net.devemperor.dictate.state.RecordingHardwareSubsystem
 * @see net.devemperor.dictate.core.RecordingManager
 */
class RecordingHardwareAdapter(
    private val emitAction: (Action) -> Unit,
    private val audioFileRepository: AudioFileRepository? = null,
    private val rollingIntervalMs: Long = DEFAULT_ROLLING_INTERVAL_MS,
) : RecordingHardwareSubsystem {

    private var recorder: MediaRecorder? = null

    /**
     * Active recording session-id, set by [allocate]. The OnInfoListener
     * uses it to call [AudioFileRepository.allocateNext]. Null when
     * no session is active or when the caller did not supply a
     * session-id (tests, legacy path).
     */
    private var activeSessionId: String? = null

    /**
     * Codec params of the active recording, captured at [allocate]
     * time. Used by the rolling info-listener to re-arm
     * `setMaxFileSize` after each segment roll — the byte budget is
     * `bitRate × interval` so it has to be recomputed from the params
     * the recorder was configured with.
     */
    private var lastCodecParams: CodecParams? = null

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
        lastCodecParams = params
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
            // B1.3: Rolling-Segments via OnInfoListener callback.
            // Android only emits an "approaching" warning for file
            // SIZE, not duration — so we approximate the per-segment
            // cap as bytes: bitrate / 8 × interval × headroom.
            // The MediaRecorder fires
            //   MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING (~95 %),
            // at which point the listener calls setNextOutputFile;
            //   MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED
            // signals that the previous segment was finalised. The
            // resulting cadence is close to (but not exactly)
            // `rollingIntervalMs` — silence and quiet passages compress
            // smaller than headroom, so segments may roll a bit later.
            if (audioFileRepository != null && sessionId != null) {
                setMaxFileSize(rollingMaxFileSizeBytes(params, rollingIntervalMs))
                setOnInfoListener(rollingInfoListener)
            }
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
        }
    }

    override fun pause() {
        val mr = recorder ?: return
        try {
            mr.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.pause() failed (ignored — best-effort)", e)
        }
    }

    override fun resume() {
        val mr = recorder ?: return
        try {
            mr.resume()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.resume() failed (ignored)", e)
        }
    }

    override fun stop() {
        val mr = recorder ?: return
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
        releaseRecorder()
    }

    private fun releaseRecorder() {
        recorder?.let {
            try { it.release() } catch (e: Exception) { Log.w(TAG, "release() failed", e) }
        }
        recorder = null
        activeSessionId = null
        lastCodecParams = null
    }

    /**
     * Approximate byte budget for one rolling segment:
     * `bitRate × intervalSec ÷ 8 × headroom`. The 1.15 headroom
     * accounts for AAC frame headers + MP4 container overhead so the
     * MAX_FILESIZE_APPROACHING info fires inside the intended time
     * window (silence and quiet passages compress smaller than peak
     * bitrate, so segments may roll slightly later than the headline
     * interval — that's acceptable for a durability boost).
     */
    private fun rollingMaxFileSizeBytes(
        params: CodecParams,
        intervalMs: Long,
    ): Long {
        val intervalSec = intervalMs / 1000.0
        val bytes = (params.bitRate.toDouble() / 8.0) * intervalSec * 1.15
        return bytes.toLong().coerceAtLeast(MIN_ROLLING_SEGMENT_BYTES)
    }

    // ──── Rolling-Segments (B1.3) ───────────────────────────────────

    /**
     * OnInfoListener wired into the MediaRecorder when both an
     * [AudioFileRepository] and a session-id are present. Reacts to
     * the two roll-related info codes:
     *
     *  - `MEDIA_RECORDER_INFO_MAX_DURATION_APPROACHING` (~800 ms
     *    before the duration cap): allocate the next segment file
     *    and arm it via `setNextOutputFile`. MediaRecorder rolls
     *    on its own once the cap is hit; the previous segment is
     *    finalised (its `moov` atom is written) so a subsequent
     *    crash leaves a complete, readable segment behind.
     *  - `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`: the
     *    handover has completed — log only, for observability.
     *  - `MEDIA_RECORDER_INFO_MAX_DURATION_REACHED`: re-arm the
     *    duration cap on the new segment so rolling continues.
     *    The cap is per-segment, not per-recording.
     */
    private val rollingInfoListener = MediaRecorder.OnInfoListener { mr, what, _ ->
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                val repo = audioFileRepository ?: return@OnInfoListener
                val sid = activeSessionId ?: return@OnInfoListener
                val params = lastCodecParams ?: return@OnInfoListener
                val next = try {
                    repo.allocateNext(sid)
                } catch (e: IOException) {
                    Log.w(TAG, "Rolling: allocateNext failed for $sid", e)
                    return@OnInfoListener
                }
                try {
                    mr.setNextOutputFile(next)
                    Log.d(TAG, "Rolling: setNextOutputFile($next) armed")
                } catch (e: Exception) {
                    Log.w(TAG, "Rolling: setNextOutputFile failed", e)
                    return@OnInfoListener
                }
                // Re-arm setMaxFileSize **in the APPROACHING window**,
                // before the actual handover happens — the recorder
                // is still in the stable "Recording" state here.
                // Setting the cap inside NEXT_OUTPUT_FILE_STARTED
                // raced with the native rollover and threw
                // IllegalStateException on Pixel 8 / SM-S948B
                // (logcat 00:30:48, B1.3-hotfix-2 trigger).
                try {
                    mr.setMaxFileSize(
                        rollingMaxFileSizeBytes(params, rollingIntervalMs)
                    )
                    Log.d(TAG, "Rolling: setMaxFileSize re-armed for next segment")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Rolling: setMaxFileSize re-arm failed", e)
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                Log.d(TAG, "Rolling: handover to next segment complete")
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                Log.d(TAG, "Rolling: max file size reached")
            }
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
         * pass a smaller interval without going through SharedPreferences.
         */
        const val DEFAULT_ROLLING_INTERVAL_MS: Long = 30_000L

        /**
         * Floor for the per-segment byte budget. Avoids pathologically
         * tiny caps if a future caller passes a sub-second interval —
         * the MediaRecorder native layer rejects file-size caps that
         * cannot fit even a single AAC frame header.
         */
        const val MIN_ROLLING_SEGMENT_BYTES: Long = 8_192L
    }
}
