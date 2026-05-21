package net.devemperor.dictate.core

import android.util.Log
import net.devemperor.dictate.audio.AudioCodecReader
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.state.ContinuationLookup
import net.devemperor.dictate.state.EligibleContinuation

/**
 * Production [ContinuationLookup] composite (B2 / ADR-0008
 * §"Auto-Continuation").
 *
 * Three cooperating pieces:
 *
 *  1. [SessionTracker.findContinuationCandidate] — the DB lookup for
 *     the latest `RECORDING_INTERRUPTED` row within the freshness
 *     window. Returns `null` outside the window or when no such row
 *     exists.
 *  2. [AudioFileRepository.segments] — list the existing segments for
 *     the candidate session. If the list is empty the row is unusable
 *     for continuation (likely a write-side race where the row was
 *     persisted before the first segment landed) — fall back to null.
 *  3. [AudioCodecReader.readCodecParams] on the **last existing**
 *     segment — the new MediaRecorder must be configured identically
 *     so MediaMuxer-concat in [AudioFileRepository.readForPipeline]
 *     does not reject heterogeneous formats. Returns `null` when the
 *     last segment is unreadable (corrupted or truncated). Treated
 *     here as an abort: the caller falls back to a fresh session and
 *     the next pipeline run shows a Partial-Recovery info-bar.
 *
 * After all three checks pass, the composite calls
 * [AudioFileRepository.allocateNext] to mint the next segment file
 * **before** the caller dispatches
 * [net.devemperor.dictate.state.Action.RecordingAction.StartRecordingContinuation].
 * The allocation appends to the session's `audio_file_paths` column so
 * the eventual concat sees all segments.
 *
 * @param freshnessMsSupplier read of
 *   [net.devemperor.dictate.preferences.Pref.ContinuationFreshnessMs] —
 *   passed as a supplier so the lookup picks up live pref changes
 *   without rebinding.
 * @param nowMs clock injection seam (defaults to
 *   `System.currentTimeMillis`); tests use a frozen clock.
 */
class RecordingContinuationLookup(
    private val sessionTracker: SessionTracker,
    private val audioFileRepository: AudioFileRepository,
    private val freshnessMsSupplier: () -> Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ContinuationLookup {

    override fun lookup(): EligibleContinuation? {
        val candidate = sessionTracker.findContinuationCandidate(
            freshnessMs = freshnessMsSupplier(),
            nowMs = nowMs(),
        ) ?: return null

        val existingSegments = audioFileRepository.segments(candidate.id)
        if (existingSegments.isEmpty()) {
            Log.w(
                TAG,
                "Continuation-candidate ${candidate.id} has no segments on disk — skipping",
            )
            return null
        }
        val lastSegment = existingSegments.last()
        val codecParams = AudioCodecReader.readCodecParams(lastSegment)
        if (codecParams == null) {
            Log.w(
                TAG,
                "Continuation-candidate ${candidate.id}: last segment " +
                    "${lastSegment.name} is unreadable — partial recovery " +
                    "scenario, abort continuation",
            )
            return null
        }

        val nextFile = try {
            audioFileRepository.allocateNext(candidate.id)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "allocateNext failed for ${candidate.id}", e)
            return null
        }

        return EligibleContinuation(
            sessionId = candidate.id,
            nextSegmentFile = nextFile,
            codecParams = codecParams,
        )
    }

    private companion object {
        private const val TAG = "ContinuationLookup"
    }
}
