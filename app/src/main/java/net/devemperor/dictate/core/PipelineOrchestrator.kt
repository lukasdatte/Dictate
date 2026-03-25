package net.devemperor.dictate.core

import android.util.Log
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.database.dao.PromptDao
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.database.entity.StepStatus
import net.devemperor.dictate.database.entity.StepType
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 */
class PipelineOrchestrator(
    private val aiOrchestrator: AIOrchestrator,
    private val autoFormattingService: AutoFormattingService,
    private val promptQueueManager: PromptQueueManager,
    private val promptService: PromptService,
    private val sessionManager: SessionManager,
    private val sessionTracker: SessionTracker,
    private val promptDao: PromptDao,
    private val callback: PipelineCallback
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

    data class PipelineConfig(
        val audioFile: File?,
        val language: String?,
        val stylePrompt: String?,
        val livePrompt: Boolean = false,
        val autoSwitchKeyboard: Boolean = false,
        val showResendButton: Boolean = false,
        val recordingsDir: File,
        val targetAppPackage: String?
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
     */
    fun runTranscriptionPipeline(config: PipelineConfig) {
        val audioFile = config.audioFile
        if (audioFile == null) {
            callback.onPipelineError("internet_error", false, null)
            callback.onPipelineFinished()
            return
        }

        cancelled = false
        running = true

        // Calculate total steps for state restoration
        totalSteps = 1 // transcription always
        if (autoFormattingService.isEnabled()) totalSteps++
        if (!config.livePrompt) totalSteps += promptQueueManager.getQueuedIds().size
        currentStepIndex = 0
        currentStepName = null

        executor.execute {
            try {
                // Start RECORDING session
                sessionTracker.startSession(
                    SessionType.RECORDING,
                    config.targetAppPackage,
                    config.language,
                    audioFile.absolutePath,
                    null
                )
                val sid = sessionTracker.currentSessionId ?: return@execute

                // Step 1: Transcription
                var text = executeTranscription(
                    audioFile, config.language, config.stylePrompt,
                    sid, config.recordingsDir
                )
                if (cancelled) return@execute

                // Step 2: Auto-formatting (optional)
                text = executeAutoFormat(text, config.language, sid)
                if (cancelled) return@execute

                // Step 3: Queued prompts (unless live-prompt mode)
                if (!config.livePrompt) {
                    val queuedIds = promptQueueManager.getQueuedIds()
                    if (queuedIds.isNotEmpty()) {
                        text = executeQueuedPrompts(text, queuedIds, sid)
                    }
                }

                // Step 4: Deliver result
                val source = if (promptQueueManager.getQueuedIds().isNotEmpty() && !config.livePrompt)
                    InsertionSource.QUEUED_PROMPT else InsertionSource.TRANSCRIPTION
                callback.onPipelineCompleted(text, source)

                // Step 5: Resend + AutoSwitch
                if (config.showResendButton) callback.onShowResend()
                if (config.autoSwitchKeyboard) callback.onAutoSwitch()

            } catch (e: AIProviderException) {
                handlePipelineError(e)
            } catch (e: RuntimeException) {
                handlePipelineError(e)
            } finally {
                callback.onPipelineFinished()
                running = false
            }
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
                // Start REWORDING session (only for non-live prompts)
                if (model.id != -1) {
                    sessionTracker.startSession(
                        SessionType.REWORDING,
                        config.targetAppPackage,
                        null, null, null
                    )
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

        // Persist audio file (only file copy - no MediaMetadataRetriever)
        if (audioFile.exists()) {
            val dest = persistAudioFile(audioFile, sid, recordingsDir)
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
     * currentText stays at the last successful value.
     */
    private fun executeQueuedPrompts(text: String, promptIds: List<Int>, sid: String): String {
        var currentText = text
        for (promptId in promptIds) {
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
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                callback.onStepCompleted(displayName, durationMs)
            } catch (e: Exception) {
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
     * 1. AIProviderException CANCELLED -> onStepFailed only (no audit trail, no resend)
     * 2. AIProviderException (other) -> persist error step + show error + resend
     * 3. RuntimeException -> persist error step + show error (unless InterruptedIOException from shutdownNow)
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
            e is AIProviderException && e.errorType == AIProviderException.ErrorType.CANCELLED -> {
                // User cancelled - no audit trail, no resend
                return
            }
            e is AIProviderException -> {
                persistErrorStep(sid, ctx, pp, durationMs, e.message)
                callback.onPipelineError(e.toInfoKey(), true, e.provider?.name)
                callback.onShowResend()
            }
            e is RuntimeException -> {
                persistErrorStep(sid, ctx, pp, durationMs, e.message)
                if (e.cause !is InterruptedIOException) {
                    // Not a cancel via shutdownNow - show error
                    callback.onPipelineError("internet_error", true, null)
                    callback.onShowResend()
                }
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
            e is AIProviderException && e.errorType == AIProviderException.ErrorType.CANCELLED -> {
                // User cancelled - silent
            }
            e is AIProviderException -> {
                Log.w(TAG, "Pipeline error", e)
                callback.onPipelineError(e.toInfoKey(), true, e.provider?.name)
                callback.onShowResend()
            }
            e is RuntimeException && e.cause is InterruptedIOException -> {
                // Cancel via shutdownNow - silent
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
     * Copies the audio cache file to permanent storage.
     * Only performs file copy - NO MediaMetadataRetriever (that stays in the Service callback).
     */
    private fun persistAudioFile(cacheFile: File, sessionId: String, recordingsDir: File): File {
        recordingsDir.mkdirs()
        val dest = File(recordingsDir, "$sessionId.m4a")
        Files.copy(cacheFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
    }
}
