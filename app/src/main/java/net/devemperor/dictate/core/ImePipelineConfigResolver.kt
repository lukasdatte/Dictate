package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.SessionOrigin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * C5 — the IME-faithful [PipelineConfigResolver] (R-1 closure for
 * C3-IMPL-1 / C3-IMPL-2, B2 block-report).
 *
 * **The R-1 problem this solves.** When C5 flips
 * `DictateInputMethodService`'s recording trigger from
 * `JobExecutor.INSTANCE.start(request)` to
 * `pipelineBinder.dispatch(StartRecording/StopRecordingAndSend)`, the new
 * orchestrator path's `Effect.SubmitPipeline` carries only
 * `(sessionId, audioFile)` — but the legacy
 * `DictateInputMethodService.java:2214-2230` `JobRequest.TranscriptionPipeline`
 * threads **15 fields**, 8 of which come from IME-runtime sources not on
 * the orchestrator path (`LanguageController`, `PromptService`,
 * `PromptQueueManager`, `AutoFormattingService`, the `EditorInfo` target
 * package, the IME's `livePrompt` / `autoSwitchKeyboard` instance flags).
 * A dropped field → recordings transcribe with the wrong language / no
 * prompts, **silently** — the exact silent-data-loss R-1 forbids.
 *
 * **The snapshot mechanism.** The IME computes those fields **once, at
 * recording-trigger time** (the send-tap, == the legacy
 * `runTranscriptionViaOrchestrator` point — same place, same values) and
 * stashes them here keyed by the recording's `sessionId` (the
 * `preAllocatedId` UUID the IME minted at `StartRecording`). When the
 * orchestrator's `PipelineModule.runEffect(SubmitPipeline)` later calls
 * `PipelineRunnerSubsystemAdapter.submit` →
 * `configResolver.resolveFresh(sessionId, audioFile)`, this resolver
 * looks the snapshot up and builds a `JobRequest.TranscriptionPipeline`
 * **field-for-field identical** to what the legacy path would have built.
 *
 * **Why snapshot at trigger-time and not read live in `resolveFresh`.**
 * `resolveFresh` runs asynchronously on the orchestrator dispatch loop
 * (the `StopRecordingAndSend → EmitPipelineTrigger → emitAction →
 * TriggerPipeline → SubmitPipeline` cascade is `emitAction`-deferred).
 * By then the IME may have reset `livePrompt`/`autoSwitchKeyboard` (the
 * legacy path resets them right after building the request, line
 * 2232-2234) and the `EditorInfo` / prompt-queue may have changed.
 * Capturing at the trigger instant reproduces the legacy semantics
 * exactly — the legacy path read every field at that same instant.
 *
 * **Why a `ConcurrentHashMap` keyed by sessionId (not a single
 * "current" field).** The single-job lock means only one fresh
 * recording is in flight, but the snapshot must outlive the dispatch
 * (it is consumed on a later async tick) and a stale snapshot from a
 * cancelled / superseded session must never bleed into the next. Keying
 * by the unique `preAllocatedId` makes each submit consume exactly its
 * own snapshot ([takeFresh] removes it), and a leaked entry (submit
 * never reached, e.g. the run was cancelled) is bounded — at most one
 * per cancelled recording, cleared on the next process boot. The map
 * is touched from the IME main thread ([snapshotFresh]) and the
 * orchestrator dispatch thread ([takeFresh]); `ConcurrentHashMap` makes
 * that hand-off safe without a lock.
 *
 * **Reprocess (C3-IMPL-2).** [snapshotReprocess] additionally captures
 * the reprocess `modelOverride` / `targetAppPackage` and the
 * AutoFormatting `+1` step that the C3 `DefaultPipelineConfigResolver`
 * defaulted to `null` / omitted — closing C3-IMPL-2. When no reprocess
 * snapshot is present (the staging-FSM path the IME does not flip in
 * C5), [resolveReprocess] falls back to [reprocessFallback] (the C3
 * default, near-1:1).
 *
 * **Fallback (Epic §6.2).** [resolveFresh] for a sessionId with no
 * snapshot throws — surfacing beats a silently-wrong default (R-1). In
 * practice this cannot happen on the new path (the IME always snapshots
 * before dispatching the send) but the guard stays as defence in depth
 * and mirrors `DefaultPipelineConfigResolver`'s fail-loud contract.
 *
 * @param recordingsDirProvider supplies the `recordings` directory
 *   parent (`DictateInputMethodService.getFilesDir()` — IME `:2223`).
 *   Provider-lambda so the resolver stays Context-free + unit-testable.
 * @param reprocessFallback the C3 [DefaultPipelineConfigResolver] used
 *   for reprocess submits the IME did not snapshot (staging-FSM path).
 *
 * @see net.devemperor.dictate.core.PipelineConfigResolver
 * @see net.devemperor.dictate.core.DelegatingPipelineConfigResolver
 * @see net.devemperor.dictate.core.DictateInputMethodService
 */
class ImePipelineConfigResolver(
    private val recordingsDirProvider: () -> File,
    private val reprocessFallback: PipelineConfigResolver,
) : PipelineConfigResolver {

    /**
     * Field-for-field mirror of the legacy
     * `DictateInputMethodService.java:2214-2230`
     * `JobRequest.TranscriptionPipeline` for a fresh recording, captured
     * at recording-trigger time. Each property is named after its legacy
     * line so a reviewer can diff the two constructions 1:1.
     */
    data class FreshConfig(
        val totalSteps: Int,
        val audioFilePath: String,
        val language: String?,
        val queuedPromptIds: List<Int>,
        val targetAppPackage: String?,
        val stylePrompt: String?,
        val livePrompt: Boolean,
        val autoSwitchKeyboard: Boolean,
        val showResendButton: Boolean,
    )

    /**
     * Reprocess-staging config the staging-FSM path does not carry
     * (C3-IMPL-2): the `modelOverride`, the `targetAppPackage`, and the
     * AutoFormatting `+1` reflected in `totalSteps`.
     */
    data class ReprocessConfig(
        val totalSteps: Int,
        val modelOverride: String?,
        val targetAppPackage: String?,
    )

    private val freshSnapshots = ConcurrentHashMap<String, FreshConfig>()
    private val reprocessSnapshots = ConcurrentHashMap<String, ReprocessConfig>()

    /**
     * IME calls this on the main thread at the send-tap (the legacy
     * trigger instant) **before** `dispatch(StopRecordingAndSend())`.
     * [sessionId] is the `preAllocatedId` UUID minted at
     * `dispatch(StartRecording(...))`.
     */
    fun snapshotFresh(sessionId: String, config: FreshConfig) {
        freshSnapshots[sessionId] = config
    }

    /** IME calls this before dispatching a reprocess submit (C3-IMPL-2). */
    fun snapshotReprocess(sessionId: String, config: ReprocessConfig) {
        reprocessSnapshots[sessionId] = config
    }

    /**
     * Drop a snapshot without consuming it — used when the recording is
     * cancelled before the send so the entry does not linger until the
     * next process boot.
     */
    fun discard(sessionId: String) {
        freshSnapshots.remove(sessionId)
        reprocessSnapshots.remove(sessionId)
    }

    override fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline {
        val cfg = freshSnapshots.remove(sessionId)
            ?: throw UnsupportedOperationException(
                "ImePipelineConfigResolver: no fresh-recording snapshot for sessionId=" +
                    "$sessionId. The IME must call snapshotFresh(...) at the send-tap " +
                    "before dispatching StopRecordingAndSend (R-1: surfacing beats a " +
                    "silently-wrong JobRequest). The legacy path stays authoritative " +
                    "behind USE_LEGACY_RECORDING_DRIVE (Epic §6.2).",
            )
        // Field-for-field identical to DictateInputMethodService.java:2214-2230.
        return JobRequest.TranscriptionPipeline(
            /* sessionId */ sessionId,
            /* totalSteps */ cfg.totalSteps,
            /* kind */ JobRequest.TranscriptionKind.RECORDING,
            /* audioFilePath */ cfg.audioFilePath,
            /* language */ cfg.language,
            /* modelOverride */ null,
            /* queuedPromptIds */ cfg.queuedPromptIds,
            /* targetAppPackage */ cfg.targetAppPackage,
            /* recordingsDir */ File(recordingsDirProvider(), "recordings"),
            /* reuseSessionId */ null,
            /* stylePrompt */ cfg.stylePrompt,
            /* origin */ SessionOrigin.KEYBOARD,
            /* livePrompt */ cfg.livePrompt,
            /* autoSwitchKeyboard */ cfg.autoSwitchKeyboard,
            /* showResendButton */ cfg.showResendButton,
        )
    }

    override fun resolveReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ): JobRequest.TranscriptionPipeline {
        val cfg = reprocessSnapshots.remove(sessionId)
            ?: return reprocessFallback.resolveReprocess(sessionId, audioFile, queue, language)
        // C3-IMPL-2 closed: thread the modelOverride / targetAppPackage /
        // AutoFormatting +1 the staging-FSM path drops. Mirrors the
        // legacy DictateInputMethodService.java:3038-3051 reprocess
        // construction.
        return JobRequest.TranscriptionPipeline(
            /* sessionId */ sessionId,
            /* totalSteps */ cfg.totalSteps,
            /* kind */ JobRequest.TranscriptionKind.REPROCESS_STAGING,
            /* audioFilePath */ audioFile?.absolutePath,
            /* language */ language,
            /* modelOverride */ cfg.modelOverride,
            /* queuedPromptIds */ queue,
            /* targetAppPackage */ cfg.targetAppPackage,
            /* recordingsDir */ File(recordingsDirProvider(), "recordings"),
            /* reuseSessionId */ sessionId,
            /* stylePrompt */ null,
            /* origin */ SessionOrigin.KEYBOARD,
        )
    }
}
