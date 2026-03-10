package net.devemperor.dictate.core

import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.CompletionLogEntity
import net.devemperor.dictate.database.entity.InsertionMethod
import net.devemperor.dictate.database.entity.ProcessingStepEntity
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.database.entity.StepStatus
import net.devemperor.dictate.database.entity.StepType
import net.devemperor.dictate.database.entity.TextInsertionEntity
import net.devemperor.dictate.database.entity.TranscriptionEntity
import java.util.UUID

/**
 * Orchestrates session persistence with business logic (validation, versioning, transactions).
 * Placed in core/ alongside RecordingManager/PromptQueueManager — not a pure data-access layer.
 */
class SessionManager(private val db: DictateDatabase) {

    private val sessionDao = db.sessionDao()
    private val transcriptionDao = db.transcriptionDao()
    private val stepDao = db.processingStepDao()
    private val completionLogDao = db.completionLogDao()
    private val textInsertionDao = db.textInsertionDao()

    // region Session lifecycle

    /**
     * Creates a new session with type-specific validation.
     * @return the generated session ID
     */
    fun createSession(
        type: SessionType,
        targetAppPackage: String?,
        language: String?,
        audioFilePath: String?,
        parentSessionId: String? = null
    ): String {
        // Type-specific validation
        when (type) {
            SessionType.RECORDING -> {
                // RECORDING sessions should have audio info
            }
            SessionType.REWORDING -> {
                require(audioFilePath == null) { "REWORDING sessions must not have audio" }
                require(parentSessionId == null) { "REWORDING sessions must not have a parent" }
            }
            SessionType.POST_PROCESSING -> {
                require(audioFilePath == null) { "POST_PROCESSING sessions must not have audio" }
                require(parentSessionId != null) { "POST_PROCESSING sessions must have a parent_session_id" }
            }
        }

        val id = UUID.randomUUID().toString()
        sessionDao.insert(
            SessionEntity(
                id = id,
                type = type.name,
                createdAt = System.currentTimeMillis(),
                targetAppPackage = targetAppPackage,
                language = language,
                audioFilePath = audioFilePath,
                parentSessionId = parentSessionId
            )
        )
        return id
    }

    // endregion

    // region Transcription versioning

    /**
     * Adds a new transcription version for the session.
     * Marks previous current version as non-current within a transaction.
     * @return the generated transcription ID
     */
    fun addTranscriptionVersion(
        sessionId: String,
        text: String,
        modelUsed: String,
        provider: String,
        promptTokens: Long = 0,
        completionTokens: Long = 0,
        durationMs: Long
    ): String {
        val id = UUID.randomUUID().toString()
        db.runInTransaction {
            val newVersion = transcriptionDao.getMaxVersion(sessionId) + 1
            transcriptionDao.clearCurrent(sessionId)
            transcriptionDao.insert(
                TranscriptionEntity(
                    id = id,
                    sessionId = sessionId,
                    version = newVersion,
                    isCurrent = true,
                    text = text,
                    modelUsed = modelUsed,
                    provider = provider,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    durationMs = durationMs,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return id
    }

    // endregion

    // region Processing step chain management

    /**
     * Appends a new processing step at the end of the current chain.
     * @return the generated step ID
     */
    fun appendProcessingStep(
        sessionId: String,
        stepType: StepType,
        inputText: String,
        outputText: String?,
        modelUsed: String,
        provider: String,
        promptUsed: String? = null,
        promptEntityId: Int? = null,
        previousStepId: String? = null,
        previousTranscriptionId: String? = null,
        sourceSessionId: String? = null,
        promptTokens: Long = 0,
        completionTokens: Long = 0,
        durationMs: Long,
        status: StepStatus,
        errorMessage: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        db.runInTransaction {
            val chainIndex = stepDao.getMaxChainIndex(sessionId) + 1
            stepDao.insert(
                ProcessingStepEntity(
                    id = id,
                    sessionId = sessionId,
                    stepType = stepType.name,
                    chainIndex = chainIndex,
                    version = 1,
                    isCurrent = true,
                    inputText = inputText,
                    outputText = outputText,
                    modelUsed = modelUsed,
                    provider = provider,
                    promptUsed = promptUsed,
                    promptEntityId = promptEntityId,
                    previousStepId = previousStepId,
                    previousTranscriptionId = previousTranscriptionId,
                    sourceSessionId = sourceSessionId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    durationMs = durationMs,
                    status = status.name,
                    errorMessage = errorMessage,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return id
    }

    /**
     * Regenerates a processing step at the given chain index.
     * Invalidates all downstream steps and creates a new version at that index.
     * @return the generated step ID
     */
    fun regenerateProcessingStep(
        sessionId: String,
        chainIndex: Int,
        stepType: StepType,
        inputText: String,
        outputText: String?,
        modelUsed: String,
        provider: String,
        promptUsed: String? = null,
        promptEntityId: Int? = null,
        previousStepId: String? = null,
        previousTranscriptionId: String? = null,
        sourceSessionId: String? = null,
        promptTokens: Long = 0,
        completionTokens: Long = 0,
        durationMs: Long,
        status: StepStatus,
        errorMessage: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        db.runInTransaction {
            val newVersion = stepDao.getMaxVersion(sessionId, chainIndex) + 1
            stepDao.clearCurrentAtIndex(sessionId, chainIndex)
            stepDao.invalidateDownstream(sessionId, chainIndex)
            stepDao.insert(
                ProcessingStepEntity(
                    id = id,
                    sessionId = sessionId,
                    stepType = stepType.name,
                    chainIndex = chainIndex,
                    version = newVersion,
                    isCurrent = true,
                    inputText = inputText,
                    outputText = outputText,
                    modelUsed = modelUsed,
                    provider = provider,
                    promptUsed = promptUsed,
                    promptEntityId = promptEntityId,
                    previousStepId = previousStepId,
                    previousTranscriptionId = previousTranscriptionId,
                    sourceSessionId = sourceSessionId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    durationMs = durationMs,
                    status = status.name,
                    errorMessage = errorMessage,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return id
    }

    // endregion

    // region Completion logging

    /**
     * Logs an AI API call (transcription or processing step).
     */
    fun logCompletion(
        type: String,
        sessionId: String?,
        stepId: String? = null,
        transcriptionId: String? = null,
        systemPrompt: String? = null,
        userPrompt: String? = null,
        success: Boolean,
        errorMessage: String? = null
    ) {
        completionLogDao.insert(
            CompletionLogEntity(
                timestamp = System.currentTimeMillis(),
                type = type,
                sessionId = sessionId,
                stepId = stepId,
                transcriptionId = transcriptionId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                success = success,
                errorMessage = errorMessage
            )
        )
    }

    // endregion

    // region Text insertion logging

    /**
     * Logs a text insertion (commit or paste) into the target app.
     */
    fun logTextInsertion(
        sessionId: String?,
        text: String,
        replacedText: String? = null,
        targetAppPackage: String? = null,
        cursorPosition: Int? = null,
        sourceStepId: String? = null,
        sourceTranscriptionId: String? = null,
        method: InsertionMethod
    ) {
        textInsertionDao.insert(
            TextInsertionEntity(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                insertedText = text,
                replacedText = replacedText,
                targetAppPackage = targetAppPackage,
                cursorPosition = cursorPosition,
                sourceStepId = sourceStepId,
                sourceTranscriptionId = sourceTranscriptionId,
                insertionMethod = method.name
            )
        )
    }

    /**
     * Convenience method for logging a paste from history.
     */
    fun logPasteFromHistory(
        sessionId: String?,
        sourceStepId: String?,
        sourceTranscriptionId: String?,
        text: String
    ) {
        logTextInsertion(
            sessionId = sessionId,
            text = text,
            sourceStepId = sourceStepId,
            sourceTranscriptionId = sourceTranscriptionId,
            method = InsertionMethod.PASTE
        )
    }

    // endregion

    // region Query helpers

    /**
     * Returns the final output text for a session.
     * Fallback chain: last current step output > current transcription > denormalized field.
     * The denormalized field is only populated if callers explicitly call [updateFinalOutputText].
     */
    fun getFinalOutput(sessionId: String): String? {
        val chain = stepDao.getCurrentChain(sessionId)
        if (chain.isNotEmpty()) {
            val lastStep = chain.last()
            if (lastStep.status == StepStatus.SUCCESS.name && lastStep.outputText != null) {
                return lastStep.outputText
            }
        }
        val transcription = transcriptionDao.getCurrent(sessionId)
        if (transcription != null) {
            return transcription.text
        }
        return sessionDao.getById(sessionId)?.finalOutputText
    }

    /**
     * Returns the final output with source information for traceability.
     */
    fun getFinalOutputSource(sessionId: String): FinalOutputInfo? {
        val chain = stepDao.getCurrentChain(sessionId)
        if (chain.isNotEmpty()) {
            val lastStep = chain.last()
            if (lastStep.status == StepStatus.SUCCESS.name && lastStep.outputText != null) {
                return FinalOutputInfo(
                    text = lastStep.outputText,
                    stepId = lastStep.id,
                    transcriptionId = null
                )
            }
        }
        val transcription = transcriptionDao.getCurrent(sessionId)
        if (transcription != null) {
            return FinalOutputInfo(
                text = transcription.text,
                stepId = null,
                transcriptionId = transcription.id
            )
        }
        return null
    }

    /**
     * Returns the output text of a specific processing step by ID.
     */
    fun getStepOutput(stepId: String): String? {
        return stepDao.getById(stepId)?.outputText
    }

    /**
     * Returns the text of a specific transcription version by ID.
     */
    fun getTranscriptionText(transcriptionId: String): String? {
        return transcriptionDao.getById(transcriptionId)?.text
    }

    /**
     * Updates the denormalized final_output_text on the session entity.
     */
    fun updateFinalOutputText(sessionId: String, text: String?) {
        sessionDao.updateFinalOutputText(sessionId, text)
    }

    /**
     * Updates the denormalized input_text on the session entity.
     * For REWORDING: the selected text. For POST_PROCESSING: the parent session's output.
     */
    fun updateInputText(sessionId: String, text: String?) {
        sessionDao.updateInputText(sessionId, text)
    }

    /**
     * Updates the audio duration after audio file has been persisted.
     */
    fun updateAudioDuration(sessionId: String, durationSeconds: Long) {
        sessionDao.updateAudioDuration(sessionId, durationSeconds)
    }

    // endregion

    /**
     * Holds the final output text with its source reference for Java interop.
     */
    data class FinalOutputInfo(
        val text: String,
        val stepId: String?,
        val transcriptionId: String?
    )
}
