package net.devemperor.dictate.audio

/**
 * Audio codec parameters extracted from a recorded segment.
 *
 * Used by the Cold-Resume / Rolling-Segment path (ADR-0007 §"Activation
 * + Rolling-Segments", B1.2 / B1.3) to configure a new
 * [android.media.MediaRecorder] identically to an existing segment, so
 * the subsequent [android.media.MediaMuxer] concatenation in
 * [AudioFileRepository.readForPipeline] does not fail with
 * `addTrack`-rejection on heterogeneous formats.
 *
 * **Origin of values.** Read from
 * [android.media.MediaExtractor.getTrackFormat] via
 * [AudioCodecReader.readCodecParams]; the keys are the standard
 * [android.media.MediaFormat] constants. Cases where the source
 * segment is unreadable surface as `null` from the reader, and the
 * adapter falls back to [DEFAULT_AAC_M4A].
 *
 * @property sampleRate Hz, e.g. 44100.
 * @property channelCount typically 1 (mono) for voice recording.
 * @property bitRate bits per second, e.g. 64000. Note this is the
 *   **target** bitrate the recorder was configured with; the actual
 *   bytes-per-second of the produced segment may vary.
 * @property mimeType e.g. `"audio/mp4a-latm"` for AAC-LC in M4A.
 *
 * @see net.devemperor.dictate.audio.AudioCodecReader
 * @see net.devemperor.dictate.core.RecordingHardwareAdapter
 */
data class CodecParams(
    val sampleRate: Int,
    val channelCount: Int,
    val bitRate: Int,
    val mimeType: String,
) {
    companion object {
        /**
         * Defaults the [net.devemperor.dictate.core.RecordingHardwareAdapter]
         * has used historically (pre-B1.2). Used as the fallback when no
         * previous segment exists (fresh session) or the previous
         * segment is unreadable. Keeping these exactly matched to the
         * historic constants avoids regressing the first-segment
         * recording quality.
         */
        val DEFAULT_AAC_M4A = CodecParams(
            sampleRate = 44_100,
            channelCount = 1,
            bitRate = 64_000,
            mimeType = "audio/mp4a-latm",
        )
    }
}
