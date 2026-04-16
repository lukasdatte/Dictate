package net.devemperor.dictate.core

import android.util.Log
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.prompt.PromptService
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
    private val database: DictateDatabase? = null
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
     * Regenerates a single processing step. Loads the step's original input +
     * prompt, re-runs the completion, and writes a new version via
     * [SessionManager.regenerateProcessingStep].
     *
     * Does not abort the pipeline on individual step errors — errors propagate
     * up to [JobExecutor.start]'s catch block, which finalises the session as
     * FAILED.
     */
    fun regenerateStep(
        sessionId: String,
        stepChainIndex: Int,
        cancellationToken: CancellationToken = CancellationToken()
    ) {
        executor.execute {
            try {
                regenerateStepBlocking(sessionId, stepChainIndex, cancellationToken)
            } catch (t: Throwable) {
                Log.d(TAG, "Legacy wrapper caught regenerate exception", t)
            }
        }
    }

    /**
     * Synchronous regenerate. K1 fix: called directly from [JobExecutor]'s
     * thread so the registry lifecycle is correct.
     */
    @JvmOverloads
    fun regenerateStepBlocking(
        sessionId: String,
        stepChainIndex: Int,
        cancellationToken: CancellationToken = CancellationToken()
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

            val startTime = System.nanoTime()
            val pp = promptService.buildQueuedPrompt(
                target.promptUsed ?: "",
                if (stepType == StepType.QUEUED_PROMPT) target.inputText else null
            )
            val result = aiOrchestrator.complete(pp.userPrompt, pp.systemPrompt)
            cancellationToken.throwIfCancelled()
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name

            sessionManager.regenerateProcessingStep(
                sessionId, stepChainIndex, stepType,
                target.inputText, result.text,
                result.modelName, provider,
                target.promptUsed, target.promptEntityId,
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
     * Runs a post-processing completion on an existing session. Creates a new
     * QUEUED_PROMPT step on the session with the given [promptText] applied to
     * [inputText].
     *
     * This is the JobExecutor-routed variant; HistoryDetailActivity's direct
     * post-process path remains for the UX that needs an immediate tie-in
     * with the local executor (loading spinner, navigation on completion).
     */
    fun runPostProcessing(
        sessionId: String,
        inputText: String,
        promptText: String,
        promptId: Int?
    ) {
        executor.execute {
            try {
                runPostProcessingBlocking(sessionId, inputText, promptText, promptId)
            } catch (t: Throwable) {
                Log.d(TAG, "Legacy wrapper caught post-process exception", t)
            }
        }
    }

    /**
     * Synchronous post-processing. K1 fix: called directly from [JobExecutor]'s
     * thread.
     */
    fun runPostProcessingBlocking(
        sessionId: String,
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
                null, null, null,
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

                // Build PromptPair
                pp = if (model.id == -1) {
                    promptService.buildLivePrompt(prompt!!)
                } else {
                    promptService.buildRewording(
                        prompt,
                        config.selectedText ?: config.overrideSelection
                    )
                }

                // API call
                ctx = ProcessingContext(
                    StepType.REWORDING,
                    model.prompt,
                    if (model.id >= 0) model.id else null
                )

                trackAndNotifyStepStarted(displayName)
                val result = executeCompletion(pp, displayName, ctx, sid)
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                callback.onStepCompleted(displayName, durationMs)

                callback.onPipelineCompleted(result, InsertionSource.REWORDING)
            } catch (e: Exception) {
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                if (ctx != null && pp != null) {
                    handleCompletionError(e, ctx, pp, sid, displayName, durationMs)
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
        if (repo != null) {
            val recording = repo.persistFromCache(audioFile, sessionId)
            audioDurationSec = repo.extractDurationSeconds(recording.audioFile)
            audioPathForRow = recording.audioFile.absolutePath
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
        val session = sessionManager.getSessionById(sid)
        val audioPath = session?.audioFilePath
        val audioFile = audioPath?.let { File(it) } ?: config.audioFile

        // Step 1: Transcription (always)
        token.throwIfCancelled()
        var text = executeTranscription(
            audioFile ?: throw IllegalStateException("No audio for session $sid"),
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
                currentText = executeCompletion(pp, displayName, ctx, sessionId)
                token.throwIfCancelled()
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                callback.onStepCompleted(displayName, durationMs)
            } catch (cancelEx: java.util.concurrent.CancellationException) {
                throw cancelEx
            } catch (ie: InterruptedException) {
                throw ie
            } catch (e: Exception) {
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                handleCompletionError(e, ctx, pp, sessionId, displayName, durationMs)
                // Pipeline continues on error (same as the full-run path).
            }
        }
        callback.onPipelineCompleted(currentText, InsertionSource.QUEUED_PROMPT)
        return currentText
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
                currentText = executeCompletion(pp, displayName, ctx, sid)
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
                handleCompletionError(e, ctx, pp, sid, displayName, durationMs)
                // Pipeline does NOT abort - next prompt gets currentText
            }
        }
        return currentText
    }

    /**
     * Executes a single completion API call and persists the result.
     * Measures its own duration for the DB step entry.
     * Throws on error (caller handles via [handleCompletionError]).
     */
    private fun executeCompletion(
        pp: PromptService.PromptPair,
        name: String,
        ctx: ProcessingContext,
        sid: String?
    ): String {
        val completionStart = System.nanoTime()
        val result = aiOrchestrator.complete(pp.userPrompt, pp.systemPrompt)
        val completionDurationMs = (System.nanoTime() - completionStart) / 1_000_000
        val rewordedText = result.text

        if (sid != null) {
            val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name
            val stepId = sessionManager.appendProcessingStep(
                sid, ctx.stepType,
                pp.userPrompt, rewordedText,
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
        durationMs: Long
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
                persistErrorStep(sid, ctx, pp, durationMs, e.message)
                callback.onPipelineError(e.toInfoKey(), true, e.provider?.name)
                callback.onShowResend()
            }
            else -> {
                persistErrorStep(sid, ctx, pp, durationMs, e.message)
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
     */
    private fun persistErrorStep(
        sid: String?,
        ctx: ProcessingContext,
        pp: PromptService.PromptPair,
        durationMs: Long,
        errorMessage: String?
    ) {
        if (sid == null) return
        val provider = aiOrchestrator.getProvider(AIFunction.COMPLETION).name
        val model = aiOrchestrator.getModelName(AIFunction.COMPLETION)
        sessionManager.appendProcessingStep(
            sid, ctx.stepType,
            pp.userPrompt, null,
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
