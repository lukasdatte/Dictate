package net.devemperor.dictate.core

import android.util.Log
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.audio.PipelineAudioResult
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.dao.ProcessingStepDao
import net.devemperor.dictate.database.dao.PromptDao
import net.devemperor.dictate.database.dao.TranscriptionDao
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.ProcessingStepEntity
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.database.entity.StepStatus
import net.devemperor.dictate.database.entity.StepType
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Orchestrates the transcription and prompt processing pipeline on a single
 * background executor thread. Replaces the previous multi-thread-pool approach
 * (speechApiThread + rewordingApiThread per prompt) with one shared executor.
 *
 * # Cutover boundary — `PipelineRunnerSubsystem` adaptee (OQ-1, Spec 1 §9.6)
 *
 * This class is the **runner body**, and is **never deleted** (Spec 1 §9.6
 * "Lösch-/Adapter-/Erhalt-Tabelle": *`JobExecutor` nie gelöscht —
 * implementiert das `PipelineRunner`-Interface*; Spec 1 §1.x naming block:
 * `PipelineOrchestrator` *"bleibt unverändert … implementiert
 * `PipelineRunner`-Interface"*). The new state-architecture reaches it
 * through a **delegation** chain — it is **not** reimplemented:
 *
 * ```
 * DictateOrchestrator → PipelineModule.runEffect
 *   → ModuleServices.pipelineRunner (PipelineRunnerSubsystem)
 *     = PipelineRunnerSubsystemAdapter            (C3-B1 — thin wrapper)
 *       → JobExecutor.INSTANCE.start/cancel        (process-global single-job lock)
 *         → PipelineOrchestratorRunner             (JobExecutor inner runner)
 *           → PipelineOrchestrator  ◄── THIS CLASS (unchanged runner body)
 * ```
 *
 * [net.devemperor.dictate.core.PipelineRunnerSubsystemAdapter] delegates to
 * `JobExecutor.INSTANCE`; `JobExecutor` was bound to this body via
 * `JobExecutor.initialize(pipelineOrchestrator)` →
 * `PipelineOrchestratorRunner(orchestrator)`. The only **other** legitimate
 * direct callers are the standalone-prompt path
 * ([PipelineOrchestrator.StandaloneConfig]), the cancel path
 * ([PipelineOrchestrator.CancelInfo]), and the RESUME carve-out
 * (C6-IMPL-2) — none of which is a second state-router. No double-dispatch
 * (AC-10): every recording/pipeline user action routes through
 * `DictateOrchestrator` and reaches this body only via the adapter.
 *
 * @see net.devemperor.dictate.core.PipelineRunnerSubsystemAdapter
 * @see net.devemperor.dictate.core.JobExecutor
 * @see net.devemperor.dictate.state.modules.PipelineModule
 *
 * Threading contract:
 * - [runTranscriptionPipeline] and [runStandalonePrompt] are called from the main thread.
 *   They submit work to the internal executor.
 * - All [PipelineCallback] methods are called from the executor thread.
 *   The Service is responsible for routing UI updates via mainHandler.post {}.
 * - [cancel] is called from the main thread. It shuts down the executor and
 *   immediately creates a new one so the next pipeline can start.
 *
 * This class does NOT hold Android Views or Context references.
 *
 * After the reprocess-refactor (Chunk 2):
 *  - `runTranscriptionPipeline` persists the audio file FIRST (before any API
 *    call) and creates the session with `status = RECORDED` and the correct
 *    `audioDurationSeconds`. This avoids the data-loss window that existed
 *    when the async `onAudioPersisted` callback could be interrupted by cancel.
 *  - A new `reuseSessionId` parameter lets callers operate on an existing
 *    session (history reprocess, resume).
 *  - A new `CancellationToken` parameter is threaded through the pipeline and
 *    checked at defined checkpoints (before each step, after each API call).
 *  - A new `resumePipeline(sessionId)` method resumes a FAILED/RECORDED session
 *    from the first non-successful step.
 */
class PipelineOrchestrator @JvmOverloads constructor(
    private val aiOrchestrator: AIOrchestrator,
    private val autoFormattingService: AutoFormattingService,
    private val promptQueueManager: PromptQueueManager,
    private val promptService: PromptService,
    private val sessionManager: SessionManager,
    private val sessionTracker: SessionTracker,
    private val promptDao: PromptDao,
    private val callback: PipelineCallback,
    // Optional wiring used by the refactored persist-first flow and by
    // `resumePipeline`. Java callers that still use the old 8-arg constructor
    // can omit these — the legacy code paths do not touch them.
    private val recordingRepository: RecordingRepository? = null,
    private val transcriptionDao: TranscriptionDao? = null,
    private val stepDao: ProcessingStepDao? = null,
    private val database: DictateDatabase? = null,
    /**
     * Block A2 (recording-stack-completion) — audio repository the
     * transcription pipeline reads through. When wired,
     * `runTranscriptionPipelineBody` calls
     * [AudioFileRepository.readForPipeline] before upload so multi-segment
     * sessions land at the AI provider as a single muxed file. Returns
     * `PartialRecovery` when the MediaMuxer skipped unreadable segments —
     * the orchestrator persists `partial:N` into `last_error_message` so
     * the InfoBar producer surfaces the warning to the user.
     *
     * Nullable for the legacy Java constructor path that still uses the
     * pre-A2 8-arg ctor; when null the code falls back to reading
     * `session.audioFilePath` directly (single-segment behaviour, no
     * partial-recovery handling).
     */
    private val audioFileRepository: AudioFileRepository? = null,
) {

    // region Callback interface

    interface PipelineCallback {
        fun onStepStarted(stepName: String)
        fun onStepCompleted(stepName: String, durationMs: Long)
        fun onStepFailed(stepName: String)
        fun onPipelineCompleted(text: String, source: InsertionSource)
        fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?)
        fun onPipelineFinished()
        fun onShowResend()
        fun onAutoSwitch()
        fun onAudioPersisted(audioFile: File, sessionId: String)
    }

    // endregion

    // region Config

    /**
     * Configuration for a transcription-pipeline run. `@JvmOverloads` makes the
     * legacy 8-arg constructor keep working from Java while still allowing
     * Kotlin callers to pass the new fields (origin, modelOverride, queuedPromptIds).
     */
    data class PipelineConfig @JvmOverloads constructor(
        val audioFile: File?,
        val language: String?,
        val stylePrompt: String?,
        val livePrompt: Boolean = false,
        val autoSwitchKeyboard: Boolean = false,
        val showResendButton: Boolean = false,
        val recordingsDir: File,
        val targetAppPackage: String?,
        // ── new (Chunk 2, Phase 4.1 / Phase 5) ──
        val origin: SessionOrigin = SessionOrigin.KEYBOARD,
        /**
         * W2: Optional per-call override for the transcription / completion model.
         *
         * NOTE: Currently IGNORED by the orchestrator — [AIOrchestrator.transcribe]
         * and [AIOrchestrator.complete] do not yet accept a per-call model override.
         * The field is threaded through so that the call-sites (JobExecutor +
         * future HistoryDetailActivity wiring) already pass the correct value;
         * Chunk 3 extends the runner-layer signatures to consume it.
         *
         * This is explicit no-op by design, not silent — see
         * [JobRequest.TranscriptionPipeline.toPipelineConfig] for the call-site
         * contract.
         */
        val modelOverride: String? = null,
        val queuedPromptIds: List<Int> = emptyList(),
        /**
         * W3: Caller-provided session ID for brand-new sessions. When non-null
         * (and [reuseSessionId] is null), [persistNewSession] uses this ID
         * instead of minting a fresh UUID. JobExecutor needs this to register
         * the session in [ActiveJobRegistry] BEFORE the pipeline runs.
         *
         * Ignored when [reuseSessionId] is non-null (reprocess/resume paths).
         */
        val preAllocatedSessionId: String? = null
    )

    data class StandaloneConfig(
        val promptEntity: PromptEntity,
        val selectedText: String?,
        val overrideSelection: String?,
        val targetAppPackage: String?
    )

    // endregion

    // region State

    @Volatile private var cancelled = false
    @Volatile var running = false
        private set
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Step tracking (for UI state restoration after view-recreation)
    @Volatile private var totalSteps = 0
    @Volatile private var currentStepIndex = 0
    @Volatile private var currentStepName: String? = null

    // endregion

    // region Public API

    /**
     * Runs the full transcription pipeline: transcribe -> auto-format -> queued prompts.
     * Must be called from the main thread.
     *
     * Legacy async wrapper — submits [runTranscriptionPipelineBlocking] onto the
     * orchestrator's internal executor and returns immediately. [JobExecutor]
     * does NOT route through this wrapper anymore (K1 fix) — it calls the
     * `*Blocking` variant directly from its own executor thread so it can track
     * the real job lifecycle.
     *
     * This wrapper is retained for the remaining Java call-site in the IME's
     * standalone-prompt flow and as a migration cushion; once every call-site
     * is routed through [JobExecutor], the wrapper can be removed along with
     * the internal executor.
     */
    @JvmOverloads
    fun runTranscriptionPipeline(
        config: PipelineConfig,
        reuseSessionId: String? = null,
        cancellationToken: CancellationToken = CancellationToken()
    ) {
        // Preflight mirrors the blocking variant so callers still see the
        // error-callback path without paying for an executor hop.
        if (reuseSessionId == null && config.audioFile == null) {
            callback.onPipelineError("internet_error", false, null)
            callback.onPipelineFinished()
            return
        }
        executor.execute {
            try {
                runTranscriptionPipelineBlocking(config, reuseSessionId, cancellationToken)
            } catch (t: Throwable) {
                // Legacy wrapper: swallow — the blocking variant already ran
                // `handlePipelineError` + finalize, and this executor has no
                // outer layer to forward to. (JobExecutor, which does want the
                // exception, does NOT go through this wrapper — it calls the
                // blocking variant directly.)
                Log.d(TAG, "Legacy wrapper caught pipeline exception", t)
            }
        }
    }

    /**
     * Synchronous (blocking) entry-point for [JobExecutor]. Runs on the caller
     * thread — must NOT be called from the main thread.
     *
     * K1 fix: [JobExecutor] submits this onto its own executor and expects the
     * call to block until the pipeline is fully finished (success, failure, or
     * cancel). This makes `ActiveJobRegistry` reflect the real lifecycle and
     * lets `JobExecutor.cancel()` target the actually running thread.
     *
     * `reuseSessionId`: if non-null, operate on the existing session (history
     * reprocess / resume). When null, the orchestrator persists audio +
     * creates a brand-new RECORDING session with `status = RECORDED` BEFORE
     * any API call.
     *
     * `cancellationToken`: cooperative token checked at defined checkpoints.
     */
    @JvmOverloads
    fun runTranscriptionPipelineBlocking(
        config: PipelineConfig,
        reuseSessionId: String? = null,
        cancellationToken: CancellationToken = CancellationToken()
    ) {
        // Preflight: if no reuseSessionId, we must have an audioFile.
        if (reuseSessionId == null && config.audioFile == null) {
            callback.onPipelineError("internet_error", false, null)
            callback.onPipelineFinished()
            return
        }

        cancelled = false
        running = true

        // Calculate total steps for state restoration
        totalSteps = 1 // transcription always
        if (autoFormattingService.isEnabled()) totalSteps++
        val queuedIdsAtStart = if (config.queuedPromptIds.isNotEmpty()) {
            config.queuedPromptIds
        } else {
            promptQueueManager.getQueuedIds()
        }
        if (!config.livePrompt) totalSteps += queuedIdsAtStart.size
        currentStepIndex = 0
        currentStepName = null

        var sid: String? = null
        try {
            // ── Stage PERSIST (synchronous, atomic) ──
            sid = if (reuseSessionId != null) {
                reuseSessionId.also {
                    sessionManager.getSessionById(it)
                        ?: throw IllegalStateException("Session $it not found")
                }
            } else {
                persistNewSession(config, queuedIdsAtStart)
            }

            // K4 Fix: currentSessionId was previously only set for new sessions
            // (via persistNewSession). For reuse (reprocess/resume) it stayed
            // null, causing onPipelineCancelClicked to fall back to the legacy
            // cancel path. Set it here so the cancel path picks JobExecutor.
            sessionTracker.currentSessionId = sid

            // Notify the Service that audio is persisted (legacy callback).
            // The Service's onAudioPersisted is now a no-op for duration — the
            // refactored flow wrote the correct duration synchronously.
            if (reuseSessionId == null && config.audioFile != null) {
                val persistedFile = File(config.recordingsDir, "$sid.m4a")
                if (persistedFile.exists()) {
                    callback.onAudioPersisted(persistedFile, sid)
                }
            }

            // ── Stage PROCESS ──
            runTranscriptionPipelineBody(config, sid, cancellationToken)

            // Terminal success
            sessionManager.finalizeCompleted(sid)

            // W1 Fix: Update the keyboard session cache NOW (with the
            // fully-populated session row), not at persist-time when the
            // row was still empty (status=RECORDED, finalOutputText=null).
            if (config.origin == SessionOrigin.KEYBOARD) {
                sessionManager.getSessionById(sid)?.let {
                    sessionTracker.notifyKeyboardSessionCompleted(it)
                }
            }

        } catch (cancelEx: java.util.concurrent.CancellationException) {
            // Cooperative-token cancel. sid may be null if we didn't get
            // past PERSIST — nothing to finalize in that case.
            if (sid != null) sessionManager.finalizeCancelled(sid)
            throw cancelEx
        } catch (interrupted: InterruptedException) {
            // Thread.interrupt() fallback — treat as cancel.
            if (sid != null) sessionManager.finalizeCancelled(sid)
            throw interrupted
        } catch (e: AIProviderException) {
            // K2 Fix: CANCELLED must NEVER be persisted as FAILED
            // (violates SessionEntity contract: last_error_type never holds CANCELLED).
            handlePipelineError(e)
            if (sid != null) {
                if (isCancellation(e)) {
                    sessionManager.finalizeCancelled(sid)
                } else {
                    sessionManager.finalizeFailed(sid, e.errorType.name, e.message ?: "")
                }
            }
            throw e
        } catch (e: RuntimeException) {
            // InterruptedIOException means cancel-via-shutdownNow.
            if (isCancellation(e)) {
                if (sid != null) sessionManager.finalizeCancelled(sid)
            } else {
                handlePipelineError(e)
                if (sid != null) {
                    sessionManager.finalizeFailed(
                        sid,
                        AIProviderException.ErrorType.UNKNOWN.name,
                        e.message ?: e.javaClass.simpleName
                    )
                }
            }
            throw e
        } finally {
            callback.onPipelineFinished()
            running = false
        }
    }

    /**
     * Resumes a pipeline for a non-completed session. Used by the short-press
     * resend flow and by the "Direkt ausführen" button in history.
     *
     * Legacy async wrapper — the work runs on the orchestrator's internal
     * executor. [JobExecutor] calls [resumePipelineBlocking] directly (K1 fix).
     */
    @JvmOverloads
    fun resumePipeline(
        sessionId: String,
        cancellationToken: CancellationToken = CancellationToken()
    ) {
        executor.execute {
            try {
                resumePipelineBlocking(sessionId, cancellationToken)
            } catch (t: Throwable) {
                Log.d(TAG, "Legacy wrapper caught resume exception", t)
            }
        }
    }

    /**
     * Synchronous (blocking) resume. K1 fix: [JobExecutor] runs this on its own
     * executor thread so the job lifecycle matches the registry lifecycle.
     *
     * Inspects existing steps and starts from the first non-successful step.
     * If no transcription exists yet, delegates to
     * [runTranscriptionPipelineBlocking].
     *
     * Must NOT be called from the main thread.
     */
    @JvmOverloads
    fun resumePipelineBlocking(
        sessionId: String,
        cancellationToken: CancellationToken = CancellationToken()
    ) {
        val tDao = transcriptionDao
        val sDao = stepDao
        if (tDao == null || sDao == null) {
            // Resume requires the full DB wiring — fail loud on misconfiguration.
            throw IllegalStateException(
                "PipelineOrchestrator.resumePipelineBlocking requires transcriptionDao/stepDao " +
                    "— construct the orchestrator with the full (Chunk 2) constructor."
            )
        }
        val session = sessionManager.getSessionById(sessionId)
            ?: throw IllegalStateException("Session $sessionId not found")

        val transcription = tDao.getCurrent(sessionId)

        if (transcription == null) {
            // No transcription — fall back to the full pipeline with reuseSessionId.
            val audioPath = session.audioFilePath
                ?: throw IllegalStateException("Session $sessionId has no audio file — cannot resume.")
            val cfg = PipelineConfig(
                audioFile = File(audioPath),
                language = session.language,
                stylePrompt = null,
                livePrompt = false,
                autoSwitchKeyboard = false,
                showResendButton = false,
                recordingsDir = File(audioPath).parentFile ?: File("."),
                targetAppPackage = session.targetAppPackage,
                origin = SessionOrigin.HISTORY_REPROCESS
            )
            runTranscriptionPipelineBlocking(
                cfg,
                reuseSessionId = sessionId,
                cancellationToken = cancellationToken
            )
            return
        }

        cancelled = false
        running = true

        // K4 Fix: set currentSessionId for the resume path too so
        // onPipelineCancelClicked routes to JobExecutor.cancel.
        sessionTracker.currentSessionId = sessionId

        val existingSteps = sDao.getCurrentChain(sessionId)

        // K1 Fix: The chain-index space and the queuedIds-index space are
        // NOT the same. Auto-format (if enabled) occupies chainIndex 0; the
        // queued prompts then start at chainIndex 1. When auto-format is
        // disabled, queued prompts start at chainIndex 0.
        //
        // `lastSuccessIndex` below is in CHAIN-INDEX space (from
        // processing_steps.chain_index). We need to translate it into
        // QUEUED-IDS-INDEX space before handing it to `executeStepsFrom`,
        // which iterates `queuedIds` by its list index.
        val promptIndexOffset = computePromptIndexOffset(existingSteps)

        val lastSuccessChainIndex = existingSteps
            .filter { StepStatus.valueOf(it.status) == StepStatus.SUCCESS }
            .maxOfOrNull { it.chainIndex } ?: -1

        // Translate chain-index to queuedIds-index. If the only successful
        // step is auto-format (chainIndex 0, offset=1), queued prompts start
        // at queuedIds[0] → resumeFromPromptIndex = 0.
        val resumeFromPromptIndex =
            (lastSuccessChainIndex - promptIndexOffset + 1).coerceAtLeast(0)

        val inputText = if (lastSuccessChainIndex == -1) {
            transcription.text
        } else {
            existingSteps.first { it.chainIndex == lastSuccessChainIndex }.outputText
                ?: transcription.text
        }

        // W5 Fix: Invalidate any ERROR / non-current steps AT or AFTER the
        // first failing chain-index, so the next `appendProcessingStep` call
        // computes a correct `chain_index` (getMaxChainIndex filters by
        // is_current=1, but an uncleared ERROR row at chainIndex N would
        // stop the resume from re-trying that slot cleanly). We do this by
        // demoting everything downstream of the last successful step.
        sDao.invalidateDownstream(sessionId, lastSuccessChainIndex)

        // Calculate total steps for UI restore — just the queued prompts still
        // to run.
        val queuedIdsAtStart = sessionManager.getHistoricalQueuedPromptIds(sessionId)
        totalSteps = (queuedIdsAtStart.size - resumeFromPromptIndex).coerceAtLeast(0)
        currentStepIndex = 0
        currentStepName = null

        try {
            cancellationToken.throwIfCancelled()
            executeStepsFrom(sessionId, resumeFromPromptIndex, inputText, queuedIdsAtStart, cancellationToken)
            sessionManager.finalizeCompleted(sessionId)
        } catch (cancelEx: java.util.concurrent.CancellationException) {
            sessionManager.finalizeCancelled(sessionId)
            throw cancelEx
        } catch (interrupted: InterruptedException) {
            sessionManager.finalizeCancelled(sessionId)
            throw interrupted
        } catch (e: AIProviderException) {
            // K2 Fix: CANCELLED must NEVER be persisted as FAILED
            // (violates SessionEntity contract: last_error_type never holds CANCELLED).
            handlePipelineError(e)
            if (isCancellation(e)) {
                sessionManager.finalizeCancelled(sessionId)
            } else {
                sessionManager.finalizeFailed(sessionId, e.errorType.name, e.message ?: "")
            }
            throw e
        } catch (e: RuntimeException) {
            if (isCancellation(e)) {
                sessionManager.finalizeCancelled(sessionId)
            } else {
                handlePipelineError(e)
                sessionManager.finalizeFailed(
                    sessionId,
                    AIProviderException.ErrorType.UNKNOWN.name,
                    e.message ?: e.javaClass.simpleName
                )
            }
            throw e
        } finally {
            callback.onPipelineFinished()
            running = false
        }
    }

    /**
     * Returns the number of non-queued-prompt steps at the start of the chain
     * (i.e., the offset between chain-index and queuedIds-index).
     *
     * Today the only such step is [StepType.AUTO_FORMAT] at chainIndex 0. If
     * an AUTO_FORMAT step exists (regardless of SUCCESS/ERROR), queued
     * prompts begin at chainIndex 1, so the offset is 1. Otherwise 0.
     *
     * Robust against missing rows: we check presence, not success, so a
     * failed auto-format still shifts the queued-prompt range correctly.
     */
    private fun computePromptIndexOffset(existingSteps: List<ProcessingStepEntity>): Int {
        val hasAutoFormat = existingSteps.any {
            it.chainIndex == 0 && it.stepType == StepType.AUTO_FORMAT.name
        }
        return if (hasAutoFormat) 1 else 0
    }

    /**
     * Synchronous regenerate — regenerates a single processing step. Loads the
     * step's original input + prompt, re-runs the completion, and writes a new
     * version via [SessionManager.regenerateProcessingStep]. K1 fix: called
     * directly from [JobExecutor]'s thread so the registry lifecycle is correct.
     *
     * F-055: this is the ONLY regenerate path — history "Regenerate" and
     * "Other prompt" dispatch a [JobRequest.StepRegenerate] instead of running
     * an Activity-scoped completion.
     *
     * @param promptOverride "Other prompt" flow: replaces the step's persisted
     *   `promptUsed` for this new version (persisted as the new version's
     *   prompt). `null` = re-run with the step's own prompt.
     * @param promptOverrideEntityId entity id belonging to [promptOverride]
     *   (null for free-text prompts). Only consulted when [promptOverride] is
     *   non-null.
     *
     * Errors propagate up to [JobExecutor.start]'s catch block, which
     * finalises the session as FAILED.
     */
    @JvmOverloads
    fun regenerateStepBlocking(
        sessionId: String,
        stepChainIndex: Int,
        cancellationToken: CancellationToken = CancellationToken(),
        promptOverride: String? = null,
        promptOverrideEntityId: Int? = null
    ) {
        val sDao = stepDao ?: throw IllegalStateException(
            "PipelineOrchestrator.regenerateStepBlocking requires stepDao — construct " +
                "the orchestrator with the full (Chunk 2) constructor."
        )

        cancelled = false
        running = true
        totalSteps = 1
        currentStepIndex = 0
        currentStepName = null

        // K4 Fix: set currentSessionId so cancel() routes to JobExecutor.
        sessionTracker.currentSessionId = sessionId

        try {
            cancellationToken.throwIfCancelled()

            // Pick the current step at the given chain index.
            val chain = sDao.getCurrentChain(sessionId)
            val target = chain.firstOrNull { it.chainIndex == stepChainIndex }
                ?: throw IllegalStateException(
                    "No current step at chain_index=$stepChainIndex for session=$sessionId"
                )

            val stepType = runCatching { StepType.valueOf(target.stepType) }
                .getOrDefault(StepType.QUEUED_PROMPT)
            val displayName = stepType.name
            trackAndNotifyStepStarted(displayName)

            // "Other prompt" replaces the persisted prompt for the new version;
            // a plain regenerate re-uses the step's own prompt. The entity id
            // follows the effective prompt (a free-text override must NOT keep
            // the old step's entity id).
            val effectivePrompt = promptOverride ?: target.promptUsed
            val effectivePromptId =
                if (promptOverride != null) promptOverrideEntityId else target.promptEntityId

            val startTime = System.nanoTime()
            // F-109: rebuild the prompt exactly like the original pipeline
            // call instead of feeding persisted text back through a second,
            // divergent construction (the pre-fix code double-wrapped the
            // built prompt).
            val pp = RegenerationPromptFactory.build(
                stepType,
                effectivePrompt,
                target.inputText,
                // Language hint for the AUTO_FORMAT branch — the original
                // pipeline call passed config.language, which was persisted
                // on the session row.
                sessionManager.getSessionById(sessionId)?.language,
                promptService
            )
            val result = aiOrchestrator.complete(pp.userPrompt, pp.systemPrompt)
            cancellationToken.throwIfCancelled()
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name

            sessionManager.regenerateProcessingStep(
                sessionId, stepChainIndex, stepType,
                target.inputText, result.text,
                result.modelName, provider,
                effectivePrompt, effectivePromptId,
                target.previousStepId, target.previousTranscriptionId,
                target.sourceSessionId,
                result.promptTokens, result.completionTokens,
                durationMs, StepStatus.SUCCESS, null
            )

            // The final output text follows the last current step in the chain,
            // which may or may not be the one we just regenerated.
            sessionManager.updateFinalOutputText(
                sessionId, sessionManager.getFinalOutput(sessionId)
            )

            callback.onStepCompleted(displayName, durationMs)
            sessionManager.finalizeCompleted(sessionId)
        } finally {
            callback.onPipelineFinished()
            running = false
        }
    }

    /**
     * Synchronous post-processing — runs a completion with [promptText] applied
     * to [inputText] and persists it as a QUEUED_PROMPT step. K1 fix: called
     * directly from [JobExecutor]'s thread.
     *
     * F-055/F-111: this is the ONLY post-process path. The POST_PROCESSING
     * session row for [sessionId] (caller-pre-allocated id) is created HERE,
     * inside the job body — not in the Activity. A dismissed chooser or a
     * rejected [JobExecutor.start] therefore never leaves an orphan session.
     * If the completion fails after creation, [JobExecutor] finalises the row
     * as FAILED, so the attempt stays visible in history (same lifecycle as a
     * failed reprocess).
     *
     * @param parentSessionId session whose step output is being post-processed;
     *   supplies the parent link plus targetAppPackage/language for the new row.
     */
    fun runPostProcessingBlocking(
        sessionId: String,
        parentSessionId: String,
        inputText: String,
        promptText: String,
        promptId: Int?
    ) {
        cancelled = false
        running = true
        totalSteps = 1
        currentStepIndex = 0
        currentStepName = null

        // K4 Fix: set currentSessionId so cancel() routes to JobExecutor.
        sessionTracker.currentSessionId = sessionId

        try {
            if (sessionManager.getSessionById(sessionId) == null) {
                val parent = sessionManager.getSessionById(parentSessionId)
                sessionManager.createSession(
                    id = sessionId,
                    type = SessionType.POST_PROCESSING,
                    targetApp = parent?.targetAppPackage,
                    language = parent?.language,
                    audioFilePath = null,
                    audioDurationSeconds = 0L,
                    parentId = parentSessionId,
                    origin = SessionOrigin.POST_PROCESSING,
                    queuedPromptIds = null,
                    initialStatus = SessionStatus.RECORDED
                )
                sessionManager.updateInputText(sessionId, inputText)
            }

            val displayName = "Post-process"
            trackAndNotifyStepStarted(displayName)

            val startTime = System.nanoTime()
            val pp = promptService.buildQueuedPrompt(promptText, inputText)
            val result = aiOrchestrator.complete(pp.userPrompt, pp.systemPrompt)
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name

            sessionManager.appendProcessingStep(
                sessionId, StepType.QUEUED_PROMPT,
                inputText, result.text,
                result.modelName, provider,
                promptText, promptId,
                // sourceSessionId: the step's text came from the parent session
                // (mirrors the pre-F-055 HistoryDetailActivity behaviour).
                null, null, parentSessionId,
                result.promptTokens, result.completionTokens,
                durationMs, StepStatus.SUCCESS, null
            )
            sessionManager.updateFinalOutputText(sessionId, result.text)
            callback.onStepCompleted(displayName, durationMs)
            sessionManager.finalizeCompleted(sessionId)
        } finally {
            callback.onPipelineFinished()
            running = false
        }
    }

    /**
     * Runs a standalone prompt (rewording or live). Must be called from the main thread.
     * The Service sets the UI mode BEFORE calling this method.
     */
    fun runStandalonePrompt(config: StandaloneConfig) {
        val model = config.promptEntity
        val prompt = model.prompt

        // Static response [text] - no API call needed
        if (promptService.isStaticResponse(prompt)) {
            val text = promptService.extractStaticResponse(prompt!!)
            callback.onPipelineCompleted(text, InsertionSource.STATIC_PROMPT)
            callback.onPipelineFinished()
            return
        }

        val displayName = if (model.id == -1) "Live-Prompt" else (model.name ?: "")
        cancelled = false
        running = true
        totalSteps = 1
        currentStepIndex = 0
        currentStepName = null
        executor.execute {
            // Declare outside try so catch can access them
            var sid: String? = null
            var pp: PromptService.PromptPair? = null
            var ctx: ProcessingContext? = null
            var rawInput: String? = null
            val startTime = System.nanoTime()
            try {
                // Start REWORDING session (only for non-live prompts).
                // Guard: if a session is somehow already active (e.g. racing callbacks),
                // skip creating a new one — the existing one will be used.
                if (model.id != -1 && sessionTracker.currentSessionId == null) {
                    val newId = UUID.randomUUID().toString()
                    sessionManager.createSession(
                        id = newId,
                        type = SessionType.REWORDING,
                        targetApp = config.targetAppPackage,
                        language = null,
                        audioFilePath = null,
                        audioDurationSeconds = 0L,
                        parentId = null,
                        origin = SessionOrigin.KEYBOARD,
                        queuedPromptIds = null,
                        initialStatus = SessionStatus.RECORDED
                    )
                    sessionTracker.currentSessionId = newId
                }
                sid = sessionTracker.currentSessionId

                // Build PromptPair. rawInput is what the prompt is applied to —
                // persisted as the step's input_text (F-109 contract, see
                // executeCompletion).
                pp = if (model.id == -1) {
                    rawInput = prompt
                    promptService.buildLivePrompt(prompt!!)
                } else {
                    rawInput = config.selectedText ?: config.overrideSelection
                    promptService.buildRewording(prompt, rawInput)
                }

                // API call
                ctx = ProcessingContext(
                    StepType.REWORDING,
                    model.prompt,
                    if (model.id >= 0) model.id else null
                )

                trackAndNotifyStepStarted(displayName)
                val result = executeCompletion(pp, displayName, ctx, sid, rawInput)
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                callback.onStepCompleted(displayName, durationMs)

                callback.onPipelineCompleted(result, InsertionSource.REWORDING)
            } catch (e: Exception) {
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                if (ctx != null && pp != null) {
                    handleCompletionError(e, ctx, pp, sid, displayName, durationMs, rawInput)
                } else {
                    // Error before pp/ctx were built (e.g. session start failed)
                    handlePipelineError(e)
                }
            } finally {
                callback.onPipelineFinished()
                running = false
            }
        }
    }

    /**
     * Cancels the running pipeline. Shuts down the executor (interrupts ongoing API calls)
     * and immediately creates a fresh executor for the next pipeline.
     * Must be called from the main thread.
     *
     * W4: Transition-period cancellation. Chunk 3 migrates the IME call-site
     * from `pipelineOrchestrator.cancel()` to [JobExecutor.cancel] (sessionId).
     * Both paths MUST NOT be triggered simultaneously for the same job —
     * [JobExecutor] already routes through its own cooperative token plus a
     * last-resort `Thread.interrupt()`, while this method shuts the executor
     * down outright. Calling both racing a single in-flight pipeline would
     * double-interrupt the worker thread and can cause the terminal status
     * write to be lost.
     *
     * @return info about the last tracked step/transcription for cancel recovery
     */
    fun cancel(): CancelInfo {
        cancelled = true
        val info = CancelInfo(
            lastStepId = sessionTracker.currentStepId,
            lastTranscriptionId = sessionTracker.currentTranscriptionId
        )
        executor.shutdownNow()
        executor = Executors.newSingleThreadExecutor()
        running = false
        currentStepName = null
        return info
    }

    fun isRunning(): Boolean = running

    /** Returns the total number of pipeline steps (set at pipeline start). */
    fun getTotalSteps(): Int = totalSteps

    /** Returns the current step index (1-based, incremented on each onStepStarted). */
    fun getCurrentStep(): Int = currentStepIndex

    /** Returns the display name of the currently running step, or null if idle. */
    fun getCurrentStepName(): String? = currentStepName

    /** Number of steps that have finished (= started steps minus the currently running one). */
    fun getCompletedSteps(): Int = maxOf(0, currentStepIndex - 1)

    /**
     * Shuts down the executor without creating a new one.
     * Use this in onDestroy() when the Service is being permanently destroyed.
     * For cancellation during normal operation, use [cancel] instead.
     */
    fun shutdown() {
        executor.shutdownNow()
        running = false
    }

    data class CancelInfo(
        val lastStepId: String?,
        val lastTranscriptionId: String?
    )

    // endregion

    // region Internal pipeline steps (run on executor thread)

    /**
     * PERSIST stage: copy the cache file into persistent storage, extract its
     * duration synchronously, and insert the session row with the correct
     * `audioDurationSeconds` + `status = RECORDED`.
     *
     * Requires [recordingRepository] to be wired — legacy callers that still
     * construct the orchestrator without the repository will fall back to the
     * old flow (the `onAudioPersisted` callback writes duration later).
     *
     * @return the sessionId of the newly created session.
     */
    private fun persistNewSession(
        config: PipelineConfig,
        queuedIdsAtStart: List<Int>
    ): String {
        val audioFile = config.audioFile
            ?: throw IllegalStateException("Audio file required for new session")

        // W3: Honour the caller-provided ID so JobExecutor can register the
        // session in ActiveJobRegistry BEFORE the pipeline runs. Fall back to
        // a fresh UUID for legacy callers that don't pre-allocate.
        val sessionId = config.preAllocatedSessionId ?: UUID.randomUUID().toString()

        // Prefer the repository (new flow, persist-first). Fall back to the old
        // ad-hoc copy for legacy call sites that haven't wired the repository.
        val repo = recordingRepository
        val audioDurationSec: Long
        val audioPathForRow: String
        // Block A4 (recording-stack-completion) — Multi-Segment detection.
        // Post-A4 the IME allocates the initial file as `sess_{sid}_seg1.m4a`
        // via AudioFileRepository.allocateFirst; rolling-segments produce
        // `_seg2`, `_seg3`, … alongside it. Copying the FIRST segment to
        // `files/recordings/{sid}.m4a` and then deleting the cache original
        // (legacy persist-first flow below) tears the segment sequence
        // apart — the muxer at upload-time would only see `_seg2`+. The
        // multi-segment path therefore SKIPS persistFromCache: the
        // segments live in `cache/audio/`; readForPipeline() merges them
        // at upload-time and the merged file is what reaches the AI.
        // `audioFilePath` on the session row points at the first segment
        // so legacy code paths (history, recovery cleanup) can still find
        // *an* audio file for the session.
        val isMultiSegmentInitial = audioFile.parentFile?.name == "audio" &&
            audioFile.name.startsWith("sess_") && audioFile.name.contains("_seg")
        if (repo != null && !isMultiSegmentInitial) {
            val recording = repo.persistFromCache(audioFile, sessionId)
            audioDurationSec = repo.extractDurationSeconds(recording.audioFile)
            audioPathForRow = recording.audioFile.absolutePath

            // KG-AFF-1 (Spec 1 §4.11.6.1): explicit cleanup of the cache
            // file once persistFromCache (copyTo + DB-row-create) lands
            // its result in filesDir/recordings/. The session row now
            // points at the persisted path, so the cache entry is no
            // longer in the "referenced" set — leaving it on disk would
            // only delay reclamation until the next boot's
            // `cleanupOrphans`. Idempotent: if `delete` fails (FS race,
            // permission), the next boot's orphan sweep catches it.
            runCatching { audioFile.delete() }
                .onFailure {
                    android.util.Log.w(
                        "PipelineOrchestrator",
                        "cache delete after persist failed: ${audioFile.name}",
                        it,
                    )
                }
        } else if (repo != null) {
            // Multi-Segment path — segments stay in cache; readForPipeline()
            // does the muxer concat at upload-time. Duration extraction
            // still runs on the first segment for the denormalised cache
            // (DurationHealingJob re-syncs to sum-of-segments later if a
            // rolling-roll lands after this point).
            audioDurationSec = repo.extractDurationSeconds(audioFile)
            audioPathForRow = audioFile.absolutePath
        } else {
            // Legacy path: copy to recordingsDir, no synchronous duration.
            config.recordingsDir.mkdirs()
            val dest = File(config.recordingsDir, "$sessionId.m4a")
            java.nio.file.Files.copy(
                audioFile.toPath(),
                dest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            audioDurationSec = 0L
            audioPathForRow = dest.absolutePath
        }

        // Recovery-chain reconciliation (2026-05-22). When the recording
        // was started through RecordingModule, the session row already
        // exists with status=RECORDING (Effect.CreateRecordingSession —
        // the first link of the recovery chain). Re-inserting it here
        // would hit SessionDao.insert's default ABORT conflict strategy
        // and throw. Transition the existing row to RECORDED instead;
        // audio_file_path(s) are already owned by the recording-start
        // insert + the SyncAudioSegments effects, so the metadata-only
        // update must not clobber them. Legacy callers that never
        // dispatched StartRecording through RecordingModule (audio
        // import, reprocess paths) have no row yet → createSession.
        if (sessionManager.getSessionById(sessionId) != null) {
            sessionManager.finalizeRecordedFromRecordingRow(
                sessionId = sessionId,
                targetApp = config.targetAppPackage,
                language = config.language,
                audioDurationSeconds = audioDurationSec,
                queuedPromptIds = queuedIdsAtStart.joinToString(","),
            )
        } else {
            sessionManager.createSession(
                id = sessionId,
                type = SessionType.RECORDING,
                targetApp = config.targetAppPackage,
                language = config.language,
                audioFilePath = audioPathForRow,
                audioDurationSeconds = audioDurationSec,
                parentId = null,
                origin = config.origin,
                queuedPromptIds = queuedIdsAtStart.joinToString(","),
                initialStatus = SessionStatus.RECORDED
            )
        }

        // Finding SEC-5-3: notifySessionCreated was never defined on
        // SessionTracker. Set currentSessionId directly.
        //
        // W1 Fix: Do NOT seed the keyboard cache with the freshly persisted
        // row here — at this moment the session is still RECORDED and has
        // finalOutputText=null, so any consumer would read a stale cache.
        // Instead, invalidate the cache; the PROCESS stage calls
        // `notifyKeyboardSessionCompleted` in its success path once the final
        // output is actually available.
        sessionTracker.currentSessionId = sessionId
        if (config.origin == SessionOrigin.KEYBOARD) {
            sessionTracker.invalidateLastKeyboardCache()
        }
        return sessionId
    }

    /**
     * PROCESS stage: transcription → auto-format → queued prompts. Writes
     * terminal success via `sessionManager.finalizeCompleted` in the caller.
     *
     * Throws on cancel (CancellationException / InterruptedException) or on
     * provider errors — the caller maps exceptions to the correct finalize call.
     */
    private fun runTranscriptionPipelineBody(
        config: PipelineConfig,
        sid: String,
        token: CancellationToken
    ) {
        // Block A2 — read audio through the repository so multi-segment
        // sessions are muxed into a single upload file. The repository is
        // the post-A2 source of truth for `the audio of session sid`;
        // legacy callers that still pre-allocate `config.audioFile`
        // (Java IME standalone-prompt path) fall through to the
        // pre-A2 path when the repo is not wired.
        val audioFile = resolvePipelineAudio(sid)
            ?: config.audioFile
            ?: throw IllegalStateException("No audio for session $sid")

        // Step 1: Transcription (always)
        token.throwIfCancelled()
        var text = executeTranscription(
            audioFile,
            config.language,
            config.stylePrompt,
            sid,
            config.recordingsDir
        )
        token.throwIfCancelled()
        if (cancelled) throw java.util.concurrent.CancellationException("cancelled flag set")

        // Step 2: Auto-formatting (optional)
        token.throwIfCancelled()
        text = executeAutoFormat(text, config.language, sid)
        token.throwIfCancelled()
        if (cancelled) throw java.util.concurrent.CancellationException("cancelled flag set")

        // Step 3: Queued prompts (unless live-prompt mode)
        if (!config.livePrompt) {
            val queuedIds = if (config.queuedPromptIds.isNotEmpty()) {
                config.queuedPromptIds
            } else {
                promptQueueManager.getQueuedIds()
            }
            if (queuedIds.isNotEmpty()) {
                text = executeQueuedPrompts(text, queuedIds, sid, token)
            }
        }

        // Step 4: Deliver result
        val hadQueued = config.queuedPromptIds.isNotEmpty() ||
            promptQueueManager.getQueuedIds().isNotEmpty()
        val source = if (hadQueued && !config.livePrompt)
            InsertionSource.QUEUED_PROMPT else InsertionSource.TRANSCRIPTION
        callback.onPipelineCompleted(text, source)

        // Step 5: Resend + AutoSwitch
        if (config.showResendButton) callback.onShowResend()
        if (config.autoSwitchKeyboard) callback.onAutoSwitch()
    }

    /**
     * Executes queued prompts starting at `fromIndex`. Used by [resumePipeline].
     *
     * Errors do NOT abort the chain — inputText stays at the last successful
     * value. Cancellation is checked before and after each step.
     */
    private fun executeStepsFrom(
        sessionId: String,
        fromIndex: Int,
        initialText: String,
        queuedIds: List<Int>,
        token: CancellationToken
    ): String {
        var currentText = initialText
        if (fromIndex >= queuedIds.size) {
            callback.onPipelineCompleted(currentText, InsertionSource.QUEUED_PROMPT)
            return currentText
        }

        for (i in fromIndex until queuedIds.size) {
            token.throwIfCancelled()
            if (cancelled) throw java.util.concurrent.CancellationException("cancelled flag set")

            val prompt = promptDao.getById(queuedIds[i]) ?: continue
            if (prompt.requiresSelection && currentText.isEmpty()) continue

            val textForPrompt = if (prompt.requiresSelection) currentText else null
            val pp = promptService.buildQueuedPrompt(prompt.prompt ?: "", textForPrompt)
            val ctx = ProcessingContext(StepType.QUEUED_PROMPT, prompt.prompt, prompt.id)
            val displayName = prompt.name ?: ""

            trackAndNotifyStepStarted(displayName)
            val startTime = System.nanoTime()
            try {
                currentText = executeCompletion(pp, displayName, ctx, sessionId, textForPrompt)
                token.throwIfCancelled()
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                callback.onStepCompleted(displayName, durationMs)
            } catch (cancelEx: java.util.concurrent.CancellationException) {
                throw cancelEx
            } catch (ie: InterruptedException) {
                throw ie
            } catch (e: Exception) {
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                handleCompletionError(e, ctx, pp, sessionId, displayName, durationMs, textForPrompt)
                // Pipeline continues on error (same as the full-run path).
            }
        }
        callback.onPipelineCompleted(currentText, InsertionSource.QUEUED_PROMPT)
        return currentText
    }

    /**
     * Block A2 (recording-stack-completion) — resolve the audio file to
     * upload for the session, reading through the [AudioFileRepository]
     * when wired.
     *
     * **Three outcomes:**
     *
     *  - `null` — neither the repository nor the legacy `audioFilePath`
     *    column produced a file. The caller falls back to
     *    `config.audioFile` (legacy Java IME standalone-prompt path);
     *    if that is also null the body throws `IllegalStateException`.
     *  - [PipelineAudioResult.Complete] — the standard path: every
     *    segment was readable, muxed into one upload file (or the single
     *    segment returned zero-copy).
     *  - [PipelineAudioResult.PartialRecovery] — some segments were
     *    skipped (corruption, truncation, codec mismatch). The orchestrator
     *    persists `partial:N` (where N = estimated lost seconds) into
     *    `last_error_message` so the InfoBar producer surfaces the
     *    warning. The transcription proceeds with the partial muxed
     *    file (better N-1 segments than nothing).
     *
     * **Threading.** Called from the orchestrator's executor-thread which
     * is synchronous; [AudioFileRepository.readForPipeline] is `suspend`
     * (it hops to `Dispatchers.IO` internally for the muxer). Wrapping
     * with [runBlocking] is safe here because the executor thread is
     * exactly the background hop the suspend function expects to land
     * on; there is no UI thread to deadlock.
     *
     * **Legacy fallback.** When the repo is null (pre-A2 Java ctor) the
     * method reads `session.audioFilePath` and wraps it in a File so the
     * legacy single-segment path still works without re-plumbing. This
     * lets the Java side migrate incrementally; once all callers route
     * through the Kotlin orchestrator pipeline this branch can be
     * deleted (Block A4).
     */
    private fun resolvePipelineAudio(sid: String): File? {
        val repo = audioFileRepository
        if (repo == null) {
            val legacyPath = sessionManager.getSessionById(sid)?.audioFilePath
            return legacyPath?.let { File(it) }
        }
        val result = runBlocking { repo.readForPipeline(sid) }
        return when (result) {
            null -> {
                // No segments on disk — fall back to the legacy column in
                // case an older session row points at a still-present
                // file. This is the bridge for pre-Block-A1 rows whose
                // recording started before audio_file_paths was being
                // populated; A3 + MigrationTo7 close this gap properly.
                val legacyPath = sessionManager.getSessionById(sid)?.audioFilePath
                legacyPath?.let { File(it) }
            }
            is PipelineAudioResult.Complete ->
                persistMuxedForUpload(sid, result.file)
            is PipelineAudioResult.PartialRecovery -> {
                // Persist the marker so the InfoBar producer surfaces the
                // warning. `last_error_message = "partial:N"` is the
                // contract InfoBarSelector.extractPartialRecoverySeconds
                // already consumes (B4 from the prior plan).
                runCatching {
                    database?.sessionDao()?.updateError(
                        id = sid,
                        type = null,
                        message = "partial:${result.estimatedLostSeconds}",
                    )
                }.onFailure {
                    Log.w(TAG, "Failed to persist partial-recovery marker for $sid", it)
                }
                Log.i(
                    TAG,
                    "Partial-recovery for $sid: ${result.ignoredSegmentIndices.size} " +
                        "segments skipped, ~${result.estimatedLostSeconds}s lost",
                )
                persistMuxedForUpload(sid, result.file)
            }
        }
    }

    /**
     * Block A4c (recording-stack-completion) — copy the about-to-be-
     * uploaded audio (single-segment OR muxed multi-segment) into
     * `filesDir/recordings/{sid}.m4a` so the session's authoritative
     * audio survives a cache eviction. The session row's
     * `audio_file_path` is updated to point at the persistent location.
     *
     * **Why here, not after upload success:** if the upload itself
     * crashes, the merged file in the cache could be evicted before a
     * retry-on-resume runs. Persisting before transcribe is the cheapest
     * mitigation — copyTo is O(file-size), typically <100 ms for a
     * 1-minute recording.
     *
     * **Segments stay in place.** The rolling segments under
     * `cacheDir/audio/sess_{sid}_seg*.m4a` are NOT deleted; the
     * cache-resident state is the "raw take" that recovery /
     * partial-recovery / future re-mux paths still need. Periodic
     * cleanup (B-style job, not in this commit) sweeps segments older
     * than the cache TTL.
     *
     * **Idempotent.** Re-running on an already-persisted session
     * overwrites `{sid}.m4a` with the latest mux (e.g. a re-mux after a
     * Cold-Resume continuation adds new segments) — `copyTo(..., overwrite=true)`.
     *
     * @return the persistent file if the copy + DB-update succeeded;
     *   otherwise the source (cache-resident) file, so the upload still
     *   has audio to send. Failure is logged but never throws back to
     *   the caller (recording-must-never-be-lost discipline).
     */
    private fun persistMuxedForUpload(sid: String, source: File): File {
        val repo = recordingRepository ?: run {
            // Pre-A4c orchestrator (no recordingRepository wired) —
            // legacy Java callers; keep returning the source so the
            // upload still happens.
            return source
        }
        return try {
            // persistFromCache is a copyTo (NOT a move) — segments and the
            // cache-resident merged file stay intact. Idempotent across
            // re-uploads (overwrite=true inside persistFromCache).
            val recording = repo.persistFromCache(source, sid)
            database?.sessionDao()?.updateAudioFilePath(sid, recording.audioFile.absolutePath)
            recording.audioFile
        } catch (t: Throwable) {
            Log.w(TAG, "persistMuxedForUpload($sid) failed; falling back to source", t)
            source
        }
    }

    /**
     * Executes the transcription step: API call, session persistence, audio file copy.
     */
    private fun executeTranscription(
        audioFile: File,
        language: String?,
        stylePrompt: String?,
        sid: String,
        recordingsDir: File
    ): String {
        trackAndNotifyStepStarted("Transkription")
        val startTime = System.nanoTime()

        val result = aiOrchestrator.transcribe(audioFile, language, stylePrompt)
        val resultText = result.text.trim()
        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        val provider = aiOrchestrator.getProvider(AIFunction.TRANSCRIPTION).name

        // Persist transcription version
        val tId = sessionManager.addTranscriptionVersion(
            sid, resultText, result.modelName, provider,
            0, 0, durationMs
        )
        sessionTracker.setTranscription(tId)

        // Completion log for transcription
        sessionManager.logCompletion(
            "TRANSCRIPTION", sid,
            null, tId, null, null, true, null
        )

        // Legacy path: if the session row doesn't have an audio path yet (old
        // constructor, no repo), copy it here and notify the Service.
        if (recordingRepository == null && audioFile.exists()) {
            val dest = persistAudioFileLegacy(audioFile, sid, recordingsDir)
            callback.onAudioPersisted(dest, sid)
        }

        callback.onStepCompleted("Transkription", durationMs)
        return resultText
    }

    /**
     * Executes auto-formatting if enabled. Returns the (possibly formatted) text.
     * Shows pipeline steps only when auto-formatting is actually enabled.
     */
    private fun executeAutoFormat(text: String, languageHint: String?, sid: String): String {
        val showStep = autoFormattingService.isEnabled()
        if (showStep) {
            trackAndNotifyStepStarted("Formatierung")
        }

        val startTime = System.nanoTime()
        val fr = autoFormattingService.formatIfEnabled(text, languageHint)
        val durationMs = (System.nanoTime() - startTime) / 1_000_000

        if (fr.completionResult != null) {
            // SUCCESS: auto-format worked
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name
            val stepId = sessionManager.appendProcessingStep(
                sid, StepType.AUTO_FORMAT, text, fr.text,
                fr.completionResult.modelName, provider,
                null, null,
                null, sessionTracker.currentTranscriptionId,
                null, fr.completionResult.promptTokens,
                fr.completionResult.completionTokens,
                durationMs, StepStatus.SUCCESS, null
            )
            sessionTracker.setStep(stepId)

            sessionManager.logCompletion(
                "AUTO_FORMAT", sid,
                stepId, null, null, null, true, null
            )

            if (showStep) callback.onStepCompleted("Formatierung", durationMs)

        } else if (fr.error != null) {
            // ERROR: auto-format failed - persist error step for audit trail
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name
            val model = aiOrchestrator.getModelName(AIFunction.COMPLETION)
            sessionManager.appendProcessingStep(
                sid, StepType.AUTO_FORMAT, text, null,
                model, provider, null, null,
                null, sessionTracker.currentTranscriptionId,
                null, 0, 0, durationMs,
                StepStatus.ERROR, fr.error.message
            )
            // No setStep() - output stays at the transcription

            sessionManager.logCompletion(
                "AUTO_FORMAT", sid,
                null, null, null, null, false, fr.error.message
            )

            if (showStep) callback.onStepFailed("Formatierung")
        }
        // else: disabled - no step, no log

        return fr.text
    }

    /**
     * Executes queued prompts ITERATIVELY (no recursion).
     * Each prompt builds on the previous result. Errors do NOT abort the chain -
     * currentText stays at the last successful value. The cancellation token is
     * checked before each prompt and after each API call returns.
     */
    private fun executeQueuedPrompts(
        text: String,
        promptIds: List<Int>,
        sid: String,
        token: CancellationToken
    ): String {
        var currentText = text
        for (promptId in promptIds) {
            token.throwIfCancelled()
            if (cancelled) break

            val prompt = promptDao.getById(promptId) ?: continue

            // Skip prompts that require selection when text is empty
            if (prompt.requiresSelection && currentText.isEmpty()) continue

            val textForPrompt = if (prompt.requiresSelection) currentText else null
            val pp = promptService.buildQueuedPrompt(prompt.prompt ?: "", textForPrompt)
            val ctx = ProcessingContext(StepType.QUEUED_PROMPT, prompt.prompt, prompt.id)
            val displayName = prompt.name ?: ""

            trackAndNotifyStepStarted(displayName)
            val startTime = System.nanoTime()
            try {
                currentText = executeCompletion(pp, displayName, ctx, sid, textForPrompt)
                token.throwIfCancelled()
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                callback.onStepCompleted(displayName, durationMs)
            } catch (cancelEx: java.util.concurrent.CancellationException) {
                throw cancelEx
            } catch (ie: InterruptedException) {
                throw ie
            } catch (e: Exception) {
                // N4 Fix: If the provider layer reported CANCELLED, rethrow
                // as CancellationException so the outer catch-block in
                // runTranscriptionPipeline finalises as CANCELLED (not
                // FAILED) and the loop does not continue.
                if (isCancellation(e)) {
                    callback.onStepFailed(displayName)
                    throw java.util.concurrent.CancellationException(
                        "Provider reported cancellation"
                    ).apply { initCause(e) }
                }
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                handleCompletionError(e, ctx, pp, sid, displayName, durationMs, textForPrompt)
                // Pipeline does NOT abort - next prompt gets currentText
            }
        }
        return currentText
    }

    /**
     * Executes a single completion API call and persists the result.
     * Measures its own duration for the DB step entry.
     * Throws on error (caller handles via [handleCompletionError]).
     *
     * F-109 persistence contract: the step's `input_text` column holds the
     * RAW text the prompt was applied to ([rawInputText] — e.g. the previous
     * step's output for a queued prompt, the selection for a rewording), NOT
     * the built `pp.userPrompt`. Regeneration re-runs the PromptService
     * builder on `(promptUsed, inputText)`; persisting the built prompt here
     * would make a regenerate wrap the instruction twice ("double-wrap").
     * The full built prompt is still preserved for audit via
     * [SessionManager.logCompletion]. Steps persisted before this fix carry
     * the built prompt in `input_text` — accepted status quo (spec §4.1),
     * their regenerate applies the instruction to the built prompt.
     */
    private fun executeCompletion(
        pp: PromptService.PromptPair,
        name: String,
        ctx: ProcessingContext,
        sid: String?,
        rawInputText: String?
    ): String {
        val completionStart = System.nanoTime()
        val result = aiOrchestrator.complete(pp.userPrompt, pp.systemPrompt)
        val completionDurationMs = (System.nanoTime() - completionStart) / 1_000_000
        val rewordedText = result.text

        if (sid != null) {
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name
            val stepId = sessionManager.appendProcessingStep(
                sid, ctx.stepType,
                rawInputText.orEmpty(), rewordedText,
                result.modelName, provider,
                ctx.promptUsed, ctx.promptEntityId,
                sessionTracker.currentStepId,
                sessionTracker.currentTranscriptionId,
                null, result.promptTokens, result.completionTokens,
                completionDurationMs,
                StepStatus.SUCCESS, null
            )

            sessionManager.logCompletion(
                ctx.stepType.name, sid,
                stepId, null,
                pp.systemPrompt, pp.userPrompt,
                true, null
            )

            sessionTracker.setStep(stepId)
        }

        return rewordedText
    }

    // endregion

    // region Error handling

    /**
     * Handles errors from completion API calls (queued prompts, standalone).
     * Follows the error cascade:
     * 1. Cancellation (per [isCancellation]) -> onStepFailed only (no audit trail, no resend)
     * 2. AIProviderException (non-cancel) -> persist error step + show error + resend
     * 3. RuntimeException -> persist error step + show error (cancel via shutdownNow handled in (1))
     */
    private fun handleCompletionError(
        e: Exception,
        ctx: ProcessingContext,
        pp: PromptService.PromptPair,
        sid: String?,
        displayName: String,
        durationMs: Long,
        rawInputText: String?
    ) {
        callback.onStepFailed(displayName)

        when {
            // N4 Fix: unified cancellation classification — AIProviderException
            // (CANCELLED), RuntimeException with InterruptedIOException cause,
            // CancellationException, InterruptedException all route here.
            isCancellation(e) -> {
                // User cancelled - no audit trail, no resend
                return
            }
            e is AIProviderException -> {
                persistErrorStep(sid, ctx, pp, durationMs, e.message, rawInputText)
                callback.onPipelineError(e.toInfoKey(), true, e.provider?.name)
                callback.onShowResend()
            }
            else -> {
                persistErrorStep(sid, ctx, pp, durationMs, e.message, rawInputText)
                callback.onPipelineError("internet_error", true, null)
                callback.onShowResend()
            }
        }
    }

    /**
     * Handles top-level pipeline errors (e.g. transcription failure).
     * These are errors that occur outside the individual completion step handling.
     */
    private fun handlePipelineError(e: Exception) {
        when {
            // K2 + N4: unified cancellation classification.
            isCancellation(e) -> {
                // User cancelled - silent
            }
            e is AIProviderException -> {
                Log.w(TAG, "Pipeline error", e)
                callback.onPipelineError(e.toInfoKey(), true, e.provider?.name)
                callback.onShowResend()
            }
            else -> {
                Log.w(TAG, "Pipeline error", e)
                callback.onPipelineError("internet_error", true, null)
                callback.onShowResend()
            }
        }
    }

    // endregion

    // region Persistence helpers

    /**
     * Legacy audio-file copy used by call sites that construct the orchestrator
     * without a [RecordingRepository]. The new flow uses
     * [RecordingRepository.persistFromCache] directly in [persistNewSession].
     */
    private fun persistAudioFileLegacy(cacheFile: File, sessionId: String, recordingsDir: File): File {
        recordingsDir.mkdirs()
        val dest = File(recordingsDir, "$sessionId.m4a")
        java.nio.file.Files.copy(
            cacheFile.toPath(),
            dest.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        return dest
    }

    /**
     * Persists an error step + completion log for failed API calls.
     * Does NOT call setStep() - the last successful step remains current.
     *
     * F-109: `input_text` holds the RAW input (same contract as
     * [executeCompletion]) so a later regenerate of the error step rebuilds
     * the prompt instead of double-wrapping the built one.
     */
    private fun persistErrorStep(
        sid: String?,
        ctx: ProcessingContext,
        pp: PromptService.PromptPair,
        durationMs: Long,
        errorMessage: String?,
        rawInputText: String?
    ) {
        if (sid == null) return
        val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name
        val model = aiOrchestrator.getModelName(AIFunction.COMPLETION)
        sessionManager.appendProcessingStep(
            sid, ctx.stepType,
            rawInputText.orEmpty(), null,
            model, provider,
            ctx.promptUsed, ctx.promptEntityId,
            sessionTracker.currentStepId,
            sessionTracker.currentTranscriptionId,
            null, 0, 0, durationMs,
            StepStatus.ERROR, errorMessage
        )

        sessionManager.logCompletion(
            ctx.stepType.name, sid,
            null, null,
            pp.systemPrompt, pp.userPrompt,
            false, errorMessage
        )
    }

    // endregion

    // region Step tracking

    /** Tracks the step and notifies the callback. */
    private fun trackAndNotifyStepStarted(stepName: String) {
        currentStepIndex++
        currentStepName = stepName
        callback.onStepStarted(stepName)
    }

    // endregion

    companion object {
        private const val TAG = "PipelineOrchestrator"

        /**
         * K2 + N4: Unified cancellation predicate. Any throwable that represents
         * a cooperative or forced cancellation MUST be routed to
         * [SessionManager.finalizeCancelled] (status=CANCELLED), NOT to
         * [SessionManager.finalizeFailed] (which writes last_error_type and
         * would violate the [SessionEntity] contract that CANCELLED is never
         * in last_error_type).
         *
         * Recognised as cancellation:
         * - [java.util.concurrent.CancellationException] — cooperative token fired
         * - [InterruptedException] — Thread.interrupt() fallback
         * - [AIProviderException] with errorType == CANCELLED — provider-layer cancel
         * - RuntimeException whose cause is [InterruptedIOException] — OkHttp cancel via shutdownNow
         */
        @JvmStatic
        fun isCancellation(t: Throwable): Boolean = when {
            t is java.util.concurrent.CancellationException -> true
            t is InterruptedException -> true
            t is AIProviderException && t.errorType == AIProviderException.ErrorType.CANCELLED -> true
            t is RuntimeException && t.cause is InterruptedIOException -> true
            else -> false
        }
    }
}
