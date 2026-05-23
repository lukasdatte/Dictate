package net.devemperor.dictate.audio

import java.io.File

/**
 * Result of [AudioFileRepository.readForPipeline].
 *
 * Distinguishes between a clean recovery (all segments readable, no
 * audio lost) and a partial recovery where one or more segments were
 * unreadable (e.g. due to process-death during a Rolling-Segment
 * roll — the last segment never received its `moov` atom). Callers
 * use the type to decide whether to surface a user-visible warning
 * after pipeline completion ("N seconds of audio skipped at send" —
 * see ADR-0006 / B4 Partial-Recovery InfoBar producer).
 *
 * **Single-segment fast path is always [Complete].** If a session
 * has exactly one segment, the repository returns it without trying
 * to open it — corrupted single segments surface at the Whisper
 * upload stage as a 4xx error, not as a recovery decision. The
 * zero-copy fast path stays trivial.
 *
 * @see net.devemperor.dictate.audio.AudioFileRepository.readForPipeline
 * @see docs/decisions/0007-audio-multi-file-repository.md
 */
sealed class PipelineAudioResult {

    /** The audio file ready for upload to the transcription service. */
    abstract val file: File

    /**
     * Every segment was readable. Either a single-segment session
     * (zero-copy) or all segments concatenated successfully.
     */
    data class Complete(override val file: File) : PipelineAudioResult()

    /**
     * One or more segments were unreadable and skipped during the
     * MediaMuxer concatenation pass. The merged [file] contains
     * the readable segments only.
     *
     * @property ignoredSegmentIndices zero-based indices into the
     *   [AudioFileRepository.segments] list of the segments that
     *   were skipped. Must be non-empty — use [Complete] instead
     *   when no segments were skipped.
     * @property estimatedLostSeconds heuristic estimate of how
     *   much audio is missing. With Rolling-Segments (default 30 s
     *   interval) each skipped segment represents up to 30 s of
     *   audio; the estimate is
     *   `ignoredSegmentIndices.size * DEFAULT_LOST_SECONDS_PER_SEGMENT`.
     */
    data class PartialRecovery(
        override val file: File,
        val ignoredSegmentIndices: List<Int>,
        val estimatedLostSeconds: Double,
    ) : PipelineAudioResult() {
        init {
            require(ignoredSegmentIndices.isNotEmpty()) {
                "PartialRecovery requires at least one ignored segment — " +
                    "use Complete instead"
            }
        }
    }
}
