package net.devemperor.dictate.companion.pipeline

/**
 * The desktop dictation lifecycle (desktop-host.md §5.2). One linear path per take:
 *
 * ```
 * IDLE ─StartHotkey→ RECORDING ─StopRecording→ TRANSCRIBING ─ok→ POST_PROCESSING
 *                                                                    │
 *                                       Verdict.INSERT → INSERTED    │  Verdict.REVIEW → REVIEW
 *                                       (any step error) → FAILED    │  Discard(RECORDING) → CANCELLED
 * ```
 *
 * These map onto the persisted `sessions.status` (Room parity, §5.2): RECORDING→`RECORDING`,
 * TRANSCRIBING/POST_PROCESSING→`TRANSCRIBING`, INSERTED/REVIEW→`COMPLETED`, FAILED→`FAILED`,
 * CANCELLED→`CANCELLED` — no new persisted status values.
 */
enum class DictationPhase {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    POST_PROCESSING,
    REVIEW,
    INSERTED,
    FAILED,
    CANCELLED,
}

/** The recording axis of [DesktopUiState] — mic activity and how long it has run. */
sealed interface RecordingUi {
    data object Idle : RecordingUi
    data class Active(val paused: Boolean, val elapsedMillis: Long) : RecordingUi
}

/** The processing axis — what the pipeline is doing after the mic stops. */
sealed interface PipelineUi {
    data object Idle : PipelineUi
    data object Transcribing : PipelineUi
    data object PostProcessing : PipelineUi
    data class Failed(val errorKey: String) : PipelineUi
}

/**
 * The review axis (ADR-0013, filled by D3). Present only when the post-processing verdict was
 * [net.devemperor.dictate.ai.conversation.Verdict.REVIEW]; mirrors the shared ADR-0013 states so the
 * re-dictate flow (§8.3) plugs in without reshaping state.
 */
data class ReviewUi(
    val sessionId: String,
    val message: String?,
    val output: String,
    val refining: Boolean = false,
    val refinementRecording: Boolean = false,
    /** Pre-minted id of the in-flight `REVIEW_REFINEMENT` S2 session, set while re-dictating (§8.3). */
    val refinementSessionId: String? = null,
    /** A failed S2 transcription/continuation surfaces here; the panel shows it and stays in review. */
    val error: String? = null,
)

/**
 * A dictation the user triggered while a pipeline was still running (ADR-0009). Enqueued, never
 * discarded — recording order equals insert order. In v1 there is no parallel recording, so a queued
 * run only *starts* recording once the running take reaches a terminal phase (§5.6).
 */
data class QueuedRun(val sessionId: String)

/**
 * The whole desktop panel state, read directly by the Compose renderer (no wire protocol, F1/F5).
 * Pure data — every transition is a [DictationReducer] function of `(state, intent)`; all IO lives in
 * effects (§5.3/§5.4).
 */
data class DesktopUiState(
    val phase: DictationPhase = DictationPhase.IDLE,
    val panelVisible: Boolean = false,
    /** The session id of the take currently occupying the pipeline, or `null` when [phase] is IDLE. */
    val activeSessionId: String? = null,
    val recording: RecordingUi = RecordingUi.Idle,
    val pipeline: PipelineUi = PipelineUi.Idle,
    val review: ReviewUi? = null,
    val queued: List<QueuedRun> = emptyList(),
) {
    /** True while a take occupies the pipeline — a fresh hotkey then enqueues instead of starting. */
    val isBusy: Boolean get() = phase != DictationPhase.IDLE
}
