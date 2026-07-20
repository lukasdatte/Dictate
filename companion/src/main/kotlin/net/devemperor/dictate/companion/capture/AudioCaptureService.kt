package net.devemperor.dictate.companion.capture

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Reference to a selectable input device — the mixer's stable name (desktop-host.md §4.2). `null`
 * everywhere means "the system default mixer".
 */
data class AudioDeviceRef(val mixerName: String)

/**
 * The finished audio of one dictation take (desktop-host.md §4.3).
 *
 * @property mergedWav the single upload file — the concatenation of [segmentPaths] (or the lone
 *   segment itself when there was only one). This is what the transcription step uploads.
 * @property segmentPaths the raw rolling segments, in order. Persisted as JSON on
 *   `sessions.audio_file_paths` (ADR-0007 parity) so a later cold-resume can rebuild the take.
 * @property durationSeconds whole-second length of the merged audio, from the PCM byte count.
 */
data class CaptureResult(
    val mergedWav: File,
    val segmentPaths: List<File>,
    val durationSeconds: Long,
)

/**
 * Captures microphone audio into rolling 16 kHz/mono/16-bit WAV segments and exposes a live amplitude
 * feed for the recording UI (desktop-host.md §4).
 *
 * A **port** (ADR-0018 style): the production [JavaSoundAudioCaptureService] drives a javax.sound
 * `TargetDataLine`, while the pipeline's headless tests substitute a fake that returns a WAV fixture —
 * so the whole dictation pipeline is drivable without a microphone (spec §11/§12).
 *
 * Lifecycle: [start] → (`pause`/`resume`)* → [stop] returns the take, OR [discard] throws it away.
 * One take at a time; a second [start] before [stop]/[discard] is a programming error.
 */
interface AudioCaptureService {

    /** Begins a new take, recording from [device] (or the system default when `null`). */
    fun start(device: AudioDeviceRef?)

    /** Pauses reading; the current segment is finalized and stays part of the take. */
    fun pause()

    /** Resumes reading after a [pause]. */
    fun resume()

    /** Finalizes the take, merges its segments and returns the result. */
    fun stop(): CaptureResult

    /** Aborts the take and deletes its segments — nothing is returned. */
    fun discard()

    /**
     * Normalized `0.0..1.0` loudness at ~10 Hz for the recording waveform (§4.4). Cold between takes;
     * a collector that missed the take simply sees nothing (a hot flow, not replayed).
     */
    val amplitudes: Flow<Float>
}
