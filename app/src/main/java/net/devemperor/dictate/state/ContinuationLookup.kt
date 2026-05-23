package net.devemperor.dictate.state

import net.devemperor.dictate.audio.CodecParams
import java.io.File

/**
 * Single seam that decides whether the **next** Record-click should
 * **continue** a freshly crash-interrupted session instead of starting a
 * fresh one (B2 / ADR-0008 §"Auto-Continuation").
 *
 * **Why an extra service (and not direct SessionTracker access from the
 * resolver)?** The continuation decision is the synthesis of three
 * cooperating pieces — the DB lookup
 * ([net.devemperor.dictate.core.SessionTracker.findContinuationCandidate]),
 * the next-segment allocation
 * ([net.devemperor.dictate.audio.AudioFileRepository.allocateNext]), and
 * the codec-param read from the last segment
 * ([net.devemperor.dictate.audio.AudioCodecReader.readCodecParams]).
 * Threading all three into [ActionResolvers] would tangle the resolver
 * with IO, MediaExtractor, and the freshness-policy read; a thin
 * `ContinuationLookup` keeps the resolver pure (`state → Action?`) and
 * lets tests substitute either a deterministic eligibility or the
 * production composite.
 *
 * **One method, two consumers** — both the keyboard-surface
 * `resolveRecordAction` resolver **and** the legacy Java
 * `startRecording()` path in `DictateInputMethodService` invoke this
 * before allocating a fresh `audioFile`. The check is invoked on
 * `RecordingState.Idle`; non-Idle states never trigger continuation
 * (the FSM is already inside a session).
 *
 * **No state mutation** — `lookup()` is read-only by design. The
 * decision to actually continue (and thus to consume `allocateNext`'s
 * returned file) is made by the caller dispatching
 * [Action.RecordingAction.StartRecordingContinuation]. If the user
 * tapped Trash instead, the caller is free to discard the returned
 * eligibility — but [allocateNext] has already mutated the repository's
 * segment-counter and added a path to the DB. That is the intended
 * trade-off: a no-op trailing segment file (zero bytes) at worst is
 * orphan-cleaned on the next service boot per ADR-0007 §"Cleanup".
 *
 * @see net.devemperor.dictate.core.RecordingContinuationLookup the
 *   production composite that wires SessionTracker +
 *   AudioFileRepository + AudioCodecReader.
 * @see Action.RecordingAction.StartRecordingContinuation the action a
 *   caller dispatches once a non-null [EligibleContinuation] surfaces.
 */
interface ContinuationLookup {
    /**
     * Look up whether the most recent `RECORDING_INTERRUPTED` session is
     * within the [net.devemperor.dictate.preferences.Pref.ContinuationFreshnessMs]
     * window. If yes, allocate the **next** segment file for that
     * session, read the codec parameters from the latest existing
     * segment, and return the bundle.
     *
     * Returns `null` when:
     *
     *  - no `RECORDING_INTERRUPTED` row exists,
     *  - the latest such row is older than the freshness window,
     *  - the latest segment file is missing / unreadable (MediaExtractor
     *    cannot infer codec params — a partial-recovery scenario; the
     *    next record-click falls back to a fresh session and the user
     *    sees a Partial-Recovery info-bar after the next pipeline run).
     *
     * **IO + synchronous** — backed by SessionTracker's DB call,
     * AudioFileRepository's `allocateNext` (cache-dir mkdirs +
     * DB-append), and AudioCodecReader's MediaExtractor read. Callers
     * invoke this on the main thread; the operations are bounded
     * (single-row DB lookup + one mkdir + MediaExtractor open). The
     * IME's `startRecording()` already does main-thread IO via
     * `AudioFileFactory.allocate()` (mkdirs); this method is in the
     * same cost class.
     */
    fun lookup(): EligibleContinuation?
}

/**
 * Bundle returned by [ContinuationLookup.lookup] describing a
 * crash-interrupted session that is fresh enough to continue.
 *
 * @property sessionId the existing session-id to reuse — replaces the
 *   fresh UUID a non-continuation [Action.RecordingAction.StartRecording]
 *   would mint.
 * @property nextSegmentFile the pre-allocated next segment file for the
 *   reused session (already appended to `audio_file_paths` via
 *   [net.devemperor.dictate.audio.AudioFileRepository.allocateNext]).
 *   Passed into the recording FSM as `RecordingState.Preparing.audioFile`.
 * @property codecParams codec parameters read from the **last existing**
 *   segment of the session. The new MediaRecorder must be configured
 *   identically so the eventual MediaMuxer concat in
 *   [net.devemperor.dictate.audio.AudioFileRepository.readForPipeline]
 *   does not reject heterogeneous formats (ADR-0007 §"Failure-Modes §1").
 */
data class EligibleContinuation(
    val sessionId: String,
    val nextSegmentFile: File,
    val codecParams: CodecParams,
)

/**
 * Default [ContinuationLookup] that returns `null` — used in tests that
 * do not exercise the continuation path and as the orchestrator's
 * pre-binder fallback.
 */
object NoopContinuationLookup : ContinuationLookup {
    override fun lookup(): EligibleContinuation? = null
}
