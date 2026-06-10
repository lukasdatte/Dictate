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
 * called with a non-null `sessionId`, the adapter caps each segment
 * with `setMaxFileSize` (a byte budget derived from
 * `rollingIntervalMs`) and keeps the **next** output file pre-armed via
 * `setNextOutputFile` (see [armNextSegment]). The MediaRecorder rolls
 * automatically into the pre-armed file when the cap is reached,
 * finalising the previous segment with a complete `moov` atom — so a
 * crash mid-segment loses at most one rolling interval of audio.
 *
 * **Why a byte cap, not `setMaxDuration`?** Android has **no**
 * `MEDIA_RECORDER_INFO_MAX_DURATION_APPROACHING` info — only
 * `MAX_FILESIZE_APPROACHING`. Seamless rolling needs the next file
 * armed *before* the cap is reached, which is only possible with the
 * filesize path. `setMaxDuration` would stop the recorder at the cap
 * with no chance to hand over.
 *
 * **Why pre-arm eagerly (durability fix 2026-06-10)?** The
 * "approaching" info is unreliable across a recording (fires once / not
 * at all on several ROMs when quiet passages under-fill the byte
 * budget). Arming `setNextOutputFile` only in that handler let
 * `MAX_FILESIZE_REACHED` stop the recorder silently when no next file
 * was armed — the keyboard kept showing "recording" (the timer is
 * decoupled) but no audio was captured past that point. The fix is the
 * always-one-ahead invariant in [armNextSegment]: a next file is armed
 * right after `start()` and after every handover, so the roll never
 * depends on the "approaching" signal.
 *
 * **Why callback-driven, not timer-driven?** The earlier B1.3 timer
 * approach (Kotlin coroutine that called `setNextOutputFile` every
 * 30 s) hit Android's MediaRecorder error `-38` (INVALID_OPERATION):
 * `setNextOutputFile` is only valid after `start()` and after each
 * `NEXT_OUTPUT_FILE_STARTED` handover. Outside those windows the native
 * call rejects. The current arming sites match that contract exactly.
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

    /**
     * Whether a `setNextOutputFile` is currently armed for the *next*
     * rolling segment (the "always-one-ahead" invariant — see
     * [armNextSegment]). Reset to `false` once the handover fires
     * ([MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED]) so
     * the listener re-arms for the segment after that. Guards against
     * double-arming when both the proactive arm and a redundant
     * `MAX_FILESIZE_APPROACHING` info try to set the next file.
     */
    private var nextSegmentArmed: Boolean = false

    override fun allocate(
        target: InsertionTarget,
        useBluetooth: Boolean,
        audioFile: File,
        codecParams: CodecParams?,
        sessionId: String?,
    ) {
        Log.i(
            "DictateTrace",
            "RecordingHardwareAdapter.allocate() target=$target sid=$sessionId " +
                "file=${audioFile.name} useBt=$useBluetooth existing=${recorder != null}"
        )
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
        Log.i("DictateTrace", "RecordingHardwareAdapter.start() recorder=${recorder != null} sid=$activeSessionId")
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
        // Rolling-Segments durability fix (2026-06-10): pre-arm the FIRST
        // rolling handover immediately after start(), independent of the
        // `MAX_FILESIZE_APPROACHING` info. See [armNextSegment] for the
        // silent-stop class this closes — without a pre-armed next file,
        // a `MAX_FILESIZE_REACHED` with no `setNextOutputFile` armed makes
        // the native MediaRecorder STOP, while the decoupled
        // RecordingTimerAdapter keeps ticking (the UI shows "recording"
        // but no audio is captured → only the leading segment reaches the
        // pipeline). No-op when rolling is disabled (no repo / no sid).
        armNextSegment(mr)
    }

    override fun pause() {
        Log.i("DictateTrace", "RecordingHardwareAdapter.pause() recorder=${recorder != null} sid=$activeSessionId")
        val mr = recorder ?: return
        try {
            mr.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.pause() failed (ignored — best-effort)", e)
        }
    }

    override fun resume() {
        Log.i("DictateTrace", "RecordingHardwareAdapter.resume() recorder=${recorder != null} sid=$activeSessionId")
        val mr = recorder ?: return
        try {
            mr.resume()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.resume() failed (ignored)", e)
        }
    }

    override fun stop() {
        Log.i("DictateTrace", "RecordingHardwareAdapter.stop() recorder=${recorder != null} sid=$activeSessionId")
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
        Log.i("DictateTrace", "RecordingHardwareAdapter.release() recorder=${recorder != null} sid=$activeSessionId")
        releaseRecorder()
    }

    private fun releaseRecorder() {
        recorder?.let {
            try { it.release() } catch (e: Exception) { Log.w(TAG, "release() failed", e) }
        }
        recorder = null
        activeSessionId = null
        lastCodecParams = null
        nextSegmentArmed = false
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
     * Arm the **next** rolling segment via `setNextOutputFile` so the
     * native MediaRecorder rolls into it the moment its `setMaxFileSize`
     * cap is reached.
     *
     * **The "always-one-ahead" invariant (durability fix 2026-06-10).**
     * This is called eagerly — right after [start] and again on every
     * [MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED]
     * handover — so a next output file is **always** pre-armed before
     * the cap is hit. This closes a silent-data-loss class:
     *
     * The previous design armed `setNextOutputFile` only inside the
     * `MAX_FILESIZE_APPROACHING` info handler. But Android emits that
     * "approaching" info unreliably across a recording (on several ROMs
     * it fires for the first segment only, or not at all when the
     * byte-budget cap under-fills on quiet passages). When
     * `MAX_FILESIZE_REACHED` then fires with **no** next file armed, the
     * native recorder simply **stops** — while the decoupled
     * [RecordingTimerAdapter] keeps ticking, so the keyboard still shows
     * "recording" yet no audio is captured. Only the leading segment(s)
     * reach the pipeline. This was the on-device "recording continued
     * but only the beginning was processed" report (small-mode toggle /
     * keyboard reopen was incidental — it merely correlated with
     * recordings long enough to cross the cap).
     *
     * Pre-arming makes the roll independent of the unreliable
     * "approaching" signal: `MAX_FILESIZE_REACHED` always has a target.
     *
     * **Idempotent** via [nextSegmentArmed] — a redundant
     * `MAX_FILESIZE_APPROACHING` belt-and-suspenders call after the
     * eager arm is a no-op. No-op when rolling is disabled (no repo /
     * no session-id, e.g. tests or the legacy path).
     *
     * **Within Android's contract.** `setNextOutputFile` is valid after
     * `start()` and after each `NEXT_OUTPUT_FILE_STARTED` — exactly the
     * two call sites here. (The original B1.3 `IllegalStateException`
     * `-38` came from a coroutine timer calling it at arbitrary moments
     * with no size-cap mechanism; that anti-pattern is not reintroduced.)
     */
    private fun armNextSegment(mr: MediaRecorder) {
        if (nextSegmentArmed) return
        val repo = audioFileRepository ?: return
        val sid = activeSessionId ?: return
        val params = lastCodecParams ?: return
        val next = try {
            repo.allocateNext(sid)
        } catch (e: IOException) {
            Log.w(TAG, "Rolling: allocateNext failed for $sid", e)
            return
        }
        try {
            mr.setNextOutputFile(next)
            nextSegmentArmed = true
            Log.d(TAG, "Rolling: setNextOutputFile($next) armed")
        } catch (e: Exception) {
            Log.w(TAG, "Rolling: setNextOutputFile failed", e)
            return
        }
        // Re-arm the per-segment byte cap for the segment currently being
        // written (the cap is per-output-file, not per-recording). Done
        // here, while the recorder is in the stable Recording state —
        // setting it inside NEXT_OUTPUT_FILE_STARTED raced with the native
        // rollover and threw IllegalStateException on Pixel 8 / SM-S948B
        // (logcat 00:30:48, B1.3-hotfix-2 trigger).
        try {
            mr.setMaxFileSize(rollingMaxFileSizeBytes(params, rollingIntervalMs))
            Log.d(TAG, "Rolling: setMaxFileSize re-armed for current segment")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Rolling: setMaxFileSize re-arm failed", e)
        }
    }

    /**
     * OnInfoListener wired into the MediaRecorder when both an
     * [AudioFileRepository] and a session-id are present. Drives the
     * rolling-segment continuity:
     *
     *  - `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`: the handover
     *    completed — the previous segment is finalised (`moov` written)
     *    and the recorder now writes into the pre-armed next file. We
     *    clear [nextSegmentArmed] and immediately [armNextSegment] the
     *    one after it (always-one-ahead), then dispatch `SegmentRolled`
     *    so the new path lands in `SessionEntity.audio_file_paths`.
     *  - `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING`: belt-and-
     *    suspenders [armNextSegment] in case the eager post-start /
     *    post-handover arm was somehow lost (idempotent — no-op when a
     *    next file is already armed).
     *  - `MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED`: observability log.
     *    With a next file always pre-armed the native recorder rolls
     *    seamlessly here rather than stopping.
     */
    private val rollingInfoListener = MediaRecorder.OnInfoListener { mr, what, _ ->
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                // Defensive re-arm only — the eager post-start /
                // post-handover arm is the primary mechanism now.
                armNextSegment(mr)

            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                Log.d(TAG, "Rolling: handover to next segment complete")
                // The pre-armed next file is now the active output; clear
                // the flag and immediately arm the segment after it so the
                // always-one-ahead invariant holds for the next roll.
                nextSegmentArmed = false
                armNextSegment(mr)
                // Block A1 (recording-stack-completion) — dispatch
                // SegmentRolled so RecordingModule emits SyncAudioSegments
                // and the new segment-path lands in
                // `SessionEntity.audio_file_paths`. A crash *after* this
                // point leaves the segment recoverable in the DB; a crash
                // *between* the handover and this callback loses only the
                // path entry (the finalised file's `moov` is already on
                // disk).
                activeSessionId?.let { sid ->
                    emitAction(Action.RecordingAction.SegmentRolled(sid))
                }
            }

            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                Log.d(TAG, "Rolling: max file size reached — rolling into pre-armed segment")
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
