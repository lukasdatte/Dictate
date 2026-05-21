package net.devemperor.dictate.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.audio.PipelineAudioResult
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Cache-dir-backed production [AudioFileRepository] (ADR-0007).
 *
 * **Naming scheme:** `sess_{sessionId}_seg{N}.m4a` (segments) +
 * `sess_{sessionId}_merged.m4a` (transient produced by [readForPipeline]).
 * The `sess_` prefix sets the new repository's files apart from the
 * legacy `CacheDirAudioFileFactory`'s `rec_{ms}_{uuid8}.m4a`-named
 * files during the migration window, so orphan cleanup over either
 * naming scheme stays self-contained.
 *
 * **Subdirectory choice — `cacheDir/audio/`:** identical to the legacy
 * factory's subdirectory so the existing "Cache leeren" preference
 * (`PreferencesFragment.clearCacheRecursively`) sweeps both old and
 * new files in one pass. The shared subdirectory is safe because the
 * repository identifies its own files by the `sess_` prefix.
 *
 * **MediaMuxer concatenation strategy.** When [readForPipeline] is
 * called with multiple segments, the implementation:
 *
 *  1. Opens [MediaExtractor] on the first segment, reads its
 *     `MediaFormat`, registers a single track with the [MediaMuxer]
 *     for the output.
 *  2. Iterates each segment with a fresh `MediaExtractor`, copying
 *     every sample into the muxer with the PTS shifted by an
 *     accumulating offset (segment-relative PTS + previous-max-PTS +
 *     1 ms gap). The 1 ms gap is a safety margin against PTS
 *     collisions when two consecutive segments share the exact same
 *     terminal frame timestamp.
 *  3. Stops/releases the muxer; the merged file is the return value.
 *
 * Assumption: every segment was produced by the same
 * `RecordingHardwareAdapter` configuration (M4A + AAC + 44.1 kHz +
 * 64 kbps). Heterogeneous codec parameters would make `addTrack`
 * reject the second segment — the planned mitigation is to capture
 * codec params on [allocateNext] and reconfigure the new MediaRecorder
 * identically (ADR-0007 Failure-Modes §1, scheduled for B.3).
 *
 * @param cacheDirProvider supplier for the application cache directory.
 *   Inject `{ applicationContext.cacheDir }` in production; tests pass
 *   a temp-folder lambda.
 * @param ioDispatcher dispatcher for [readForPipeline]'s IO + muxer
 *   work. Defaults to [Dispatchers.IO]; tests pass `Unconfined` or a
 *   `TestDispatcher` so the suspend point completes synchronously.
 *
 * @see net.devemperor.dictate.audio.AudioFileRepository
 * @see net.devemperor.dictate.core.CacheDirAudioFileFactory  (legacy factory, co-exists during rollout)
 * @see docs/decisions/0007-audio-multi-file-repository.md
 */
class CacheDirAudioFileRepository(
    private val cacheDirProvider: () -> File?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioFileRepository {

    private val audioCacheDir: File by lazy {
        val root = requireNotNull(cacheDirProvider()) {
            "cacheDir is null — Application.onCreate has not run yet"
        }
        File(root, AUDIO_SUBDIR).apply { mkdirs() }
    }

    override fun allocateFirst(sessionId: String): File =
        allocateSegment(sessionId, segmentIndex = 1)

    override fun allocateNext(sessionId: String): File {
        val highestExisting = segments(sessionId)
            .mapNotNull { parseSegmentIndex(it.name) }
            .maxOrNull() ?: 0
        return allocateSegment(sessionId, segmentIndex = highestExisting + 1)
    }

    private fun allocateSegment(sessionId: String, segmentIndex: Int): File {
        ensureCacheDir()
        val name = "$PREFIX$sessionId$SEG_INFIX$segmentIndex$EXT"
        return File(audioCacheDir, name)
    }

    private fun ensureCacheDir() {
        if (audioCacheDir.exists() && !audioCacheDir.isDirectory) {
            throw IOException(
                "Audio cache dir path is occupied by a non-directory: $audioCacheDir"
            )
        }
        if (!audioCacheDir.exists() && !audioCacheDir.mkdirs()) {
            throw IOException("Audio cache dir not creatable: $audioCacheDir")
        }
    }

    override fun segments(sessionId: String): List<File> {
        val sessionPrefix = "$PREFIX$sessionId$SEG_INFIX"
        return audioCacheDir
            .listFiles { f ->
                f.isFile && f.name.startsWith(sessionPrefix) && f.name.endsWith(EXT)
            }
            ?.filter { parseSegmentIndex(it.name) != null }
            ?.sortedBy { parseSegmentIndex(it.name) ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    override suspend fun readForPipeline(sessionId: String): PipelineAudioResult? =
        withContext(ioDispatcher) {
            val segs = segments(sessionId)
            when {
                segs.isEmpty() -> null
                segs.size == 1 -> PipelineAudioResult.Complete(segs.first())
                else -> mergeSegments(sessionId, segs)
            }
        }

    private fun mergeSegments(sessionId: String, segs: List<File>): PipelineAudioResult? {
        val merged = mergedFile(sessionId)
        runCatching { merged.delete() }  // start fresh; ignored when missing
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var ptsOffsetUs = 0L
        val buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES)
        val ignoredIndices = mutableListOf<Int>()
        try {
            muxer = MediaMuxer(merged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for ((index, seg) in segs.withIndex()) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(seg.absolutePath)
                    if (extractor.trackCount == 0) {
                        Log.w(TAG, "Segment has no tracks: ${seg.name} (index $index); skipped")
                        ignoredIndices.add(index)
                        continue
                    }
                    extractor.selectTrack(0)
                    if (trackIndex < 0) {
                        // Register the audio track once, using the
                        // FIRST readable segment's format. ADR-0007
                        // assumes all segments share codec parameters
                        // (same RecordingHardwareAdapter configuration —
                        // B.3 mitigation in B1.2 captures and replays
                        // these on allocateNext).
                        trackIndex = muxer.addTrack(extractor.getTrackFormat(0))
                        muxer.start()
                    }
                    val info = MediaCodec.BufferInfo()
                    var maxPtsThisSeg = 0L
                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        val rawPts = extractor.sampleTime
                        info.offset = 0
                        info.size = sampleSize
                        info.flags = extractor.sampleFlags
                        info.presentationTimeUs = rawPts + ptsOffsetUs
                        if (info.presentationTimeUs > maxPtsThisSeg) {
                            maxPtsThisSeg = info.presentationTimeUs
                        }
                        muxer.writeSampleData(trackIndex, buffer, info)
                        extractor.advance()
                    }
                    // PTS offset for the next segment = end of this
                    // one + 1 ms safety gap (avoids exact-duplicate
                    // PTS when two segments end on the same tick).
                    ptsOffsetUs = maxPtsThisSeg + PTS_GAP_US
                } catch (e: Exception) {
                    // Per-segment failure: skip and record. Most
                    // common cause is a partial segment produced by a
                    // Rolling-Segment crash (no `moov` atom) — see
                    // ADR-0007 §"Activation + Rolling-Segments".
                    Log.w(
                        TAG,
                        "Segment ${seg.name} (index $index) unreadable; skipped",
                        e,
                    )
                    ignoredIndices.add(index)
                } finally {
                    runCatching { extractor.release() }
                }
            }
            // Edge case: no segment was readable. Bail — the merged
            // file has no track and is unusable.
            if (trackIndex < 0) {
                Log.w(TAG, "All segments unreadable for session $sessionId; no merged file")
                runCatching { merged.delete() }
                return null
            }
            muxer.stop()
            return if (ignoredIndices.isEmpty()) {
                PipelineAudioResult.Complete(merged)
            } else {
                PipelineAudioResult.PartialRecovery(
                    file = merged,
                    ignoredSegmentIndices = ignoredIndices.toList(),
                    estimatedLostSeconds =
                        ignoredIndices.size * DEFAULT_LOST_SECONDS_PER_SEGMENT,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to merge ${segs.size} segments for session $sessionId", t)
            runCatching { merged.delete() }
            return null
        } finally {
            runCatching { muxer?.release() }
        }
    }

    override fun deleteAll(sessionId: String) {
        segments(sessionId).forEach { seg ->
            runCatching { seg.delete() }
                .onFailure { Log.w(TAG, "deleteAll: segment ${seg.name} failed", it) }
        }
        val merged = mergedFile(sessionId)
        if (merged.exists()) {
            runCatching { merged.delete() }
                .onFailure { Log.w(TAG, "deleteAll: merged ${merged.name} failed", it) }
        }
    }

    override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> {
        val onDisk = audioCacheDir.listFiles()
            ?.asSequence()
            ?.mapNotNull { parseSessionId(it.name) }
            ?.toSet()
            ?: emptySet()
        return onDisk - knownSessionIds
    }

    private fun mergedFile(sessionId: String): File =
        File(audioCacheDir, "$PREFIX$sessionId$MERGED_SUFFIX$EXT")

    /**
     * Extract the segment-index `N` from a `sess_{sessionId}_seg{N}.m4a`
     * filename. Returns null when the name does not match the segment
     * pattern (incl. the merged-transient name, which has no index).
     */
    private fun parseSegmentIndex(filename: String): Int? {
        if (!filename.startsWith(PREFIX) || !filename.endsWith(EXT)) return null
        val infixIdx = filename.lastIndexOf(SEG_INFIX)
        val extIdx = filename.length - EXT.length
        if (infixIdx < 0 || infixIdx + SEG_INFIX.length >= extIdx) return null
        return filename.substring(infixIdx + SEG_INFIX.length, extIdx).toIntOrNull()
    }

    /**
     * Extract the session-id from any of the repository's filenames
     * (segment or merged-transient). Returns null when the name is
     * not a repository-owned file (e.g. legacy `rec_*.m4a` from the
     * old factory, or unrelated content placed in the audio cache).
     */
    private fun parseSessionId(filename: String): String? {
        if (!filename.startsWith(PREFIX) || !filename.endsWith(EXT)) return null
        val afterPrefix = filename.removePrefix(PREFIX).removeSuffix(EXT)
        val segIdx = afterPrefix.indexOf(SEG_INFIX)
        val mergedIdx = afterPrefix.indexOf(MERGED_SUFFIX)
        return when {
            segIdx > 0 -> afterPrefix.substring(0, segIdx)
            mergedIdx > 0 -> afterPrefix.substring(0, mergedIdx)
            else -> null
        }
    }

    internal companion object {
        internal const val TAG = "AudioFileRepository"
        internal const val AUDIO_SUBDIR = "audio"
        internal const val PREFIX = "sess_"
        internal const val SEG_INFIX = "_seg"
        internal const val MERGED_SUFFIX = "_merged"
        internal const val EXT = ".m4a"
        /** 1 MiB sample buffer — fits any single AAC frame with headroom. */
        internal const val BUFFER_SIZE_BYTES = 1024 * 1024
        /** 1 ms PTS gap between segments — avoids exact-duplicate timestamps. */
        internal const val PTS_GAP_US = 1_000L

        /**
         * Heuristic — estimated seconds of audio lost per unreadable
         * segment in [PipelineAudioResult.PartialRecovery]. Matches
         * the Rolling-Segment default interval (Pref.RollingSegmentIntervalSec,
         * B1.3). A skipped segment lost at most one rolling cycle of
         * audio; the actual value is somewhere between 0 and this
         * upper bound, but per-segment introspection of partial mdat
         * frames is not worth the complexity for a status message.
         */
        internal const val DEFAULT_LOST_SECONDS_PER_SEGMENT = 30.0
    }
}
