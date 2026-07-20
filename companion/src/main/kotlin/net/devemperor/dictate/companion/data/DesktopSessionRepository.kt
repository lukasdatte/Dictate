package net.devemperor.dictate.companion.data

import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.companion.db.Conversation_messages
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Processing_steps
import net.devemperor.dictate.companion.db.Sessions
import net.devemperor.dictate.companion.db.Transcriptions
import net.devemperor.dictate.companion.domain.session.MessageRole
import net.devemperor.dictate.companion.domain.session.ResponseFormatKind
import net.devemperor.dictate.companion.domain.session.SessionStatus
import net.devemperor.dictate.companion.domain.session.StepStatus
import net.devemperor.dictate.companion.domain.session.StepType

/**
 * The write side of a locally-dictated session over the Room-parity model (desktop-host.md §5.5).
 *
 * Everything a desktop take produces — the session row, its transcription, the post-processing
 * conversation turn and that turn's SYSTEM/USER messages — lands here, all under
 * `host_origin = 'DESKTOP_DICTATION'` so the phone-sync cursor never sees it (and vice versa). The
 * conversation turn is written in **one transaction** (step + both messages + the denormalized
 * `final_output_text`) so a crash cannot leave a half-turn (ADR-0013 §3 crash-resilience).
 *
 * Complements [SqlDelightHistoryRepository] (the phone-mirror read/write side); they share the four
 * tables but never each other's rows.
 */
class DesktopSessionRepository(private val database: DictateCompanionDb) {

    private val queries = database.companionQueries

    /**
     * Persists a fresh take once the mic stops, already in the pipeline (status
     * [SessionStatus.TRANSCRIBING]). [audioFilePathsJson] is the JSON array of rolling-segment paths
     * (ADR-0007 parity); [audioFilePath] is the merged upload file.
     */
    fun createDictationSession(
        id: String,
        createdAt: Long,
        language: String?,
        audioFilePath: String?,
        audioFilePathsJson: String,
        durationSeconds: Long,
        status: SessionStatus = SessionStatus.TRANSCRIBING,
    ) {
        queries.insertDictationSession(
            id = id,
            createdAt = createdAt,
            language = language,
            audioFilePath = audioFilePath,
            audioFilePaths = audioFilePathsJson,
            durationSeconds = durationSeconds,
            status = status,
        )
    }

    fun updateStatus(id: String, status: SessionStatus) {
        queries.updateDictationStatus(status = status, id = id)
    }

    fun markFailed(id: String, errorType: AIProviderException.ErrorType, message: String?) {
        queries.failDictationSession(errorType = errorType, message = message, id = id)
    }

    /** Bare-transcript completion (no post-processing turn, §5.5 hasWork=false, ADR-0012 §1). */
    fun completeWithFinalOutput(id: String, finalOutputText: String) {
        queries.completeDictationSession(finalOutputText = finalOutputText, id = id)
    }

    /** Records that the output was placed into a window (the auto-insert of an INSERT verdict). */
    fun stampInserted(id: String, insertedAt: Long) {
        queries.stampInserted(insertedAt = insertedAt, id = id)
    }

    fun insertTranscription(row: TranscriptionRow) {
        queries.insertTranscription(
            id = row.id,
            sessionId = row.sessionId,
            version = row.version,
            isCurrent = row.isCurrent,
            text = row.text,
            modelUsed = row.modelUsed,
            provider = row.provider,
            promptTokens = row.promptTokens,
            completionTokens = row.completionTokens,
            durationMs = row.durationMs,
            createdAt = row.createdAt,
        )
    }

    /**
     * Writes a complete post-processing conversation turn atomically (§5.5 step 2): the
     * `CONVERSATION_TURN` step, its persisted SYSTEM + USER messages, and the session's
     * `final_output_text` + COMPLETED status — one transaction so the turn is all-or-nothing.
     */
    fun persistConversationTurn(turn: ConversationTurnRecord) {
        database.transaction {
            queries.insertProcessingStep(
                id = turn.stepId,
                sessionId = turn.sessionId,
                stepType = StepType.CONVERSATION_TURN,
                chainIndex = turn.chainIndex,
                version = 1,
                isCurrent = true,
                inputText = turn.inputText,
                outputText = turn.output,
                modelUsed = turn.modelUsed,
                provider = turn.provider,
                promptUsed = null,
                promptEntityId = null,
                previousStepId = null,
                previousTranscriptionId = turn.previousTranscriptionId,
                sourceSessionId = null,
                promptTokens = turn.promptTokens,
                completionTokens = turn.completionTokens,
                durationMs = turn.durationMs,
                status = StepStatus.SUCCESS,
                errorMessage = null,
                createdAt = turn.createdAt,
                assistantMessage = turn.assistantMessage,
                responseFormat = turn.responseFormat,
            )
            queries.insertConversationMessage(
                id = turn.systemMessageId,
                sessionId = turn.sessionId,
                turnIndex = turn.chainIndex.toLong(),
                seq = 0,
                role = MessageRole.SYSTEM,
                content = turn.systemContent,
                stepId = turn.stepId,
                createdAt = turn.createdAt,
            )
            queries.insertConversationMessage(
                id = turn.userMessageId,
                sessionId = turn.sessionId,
                turnIndex = turn.chainIndex.toLong(),
                seq = 1,
                role = MessageRole.USER,
                content = turn.userContent,
                stepId = turn.stepId,
                createdAt = turn.createdAt,
            )
            queries.completeDictationSession(finalOutputText = turn.output, id = turn.sessionId)
        }
    }

    // ── reads (history/tests) ────────────────────────────────────────────────────────────────

    fun session(id: String): Sessions? = queries.dictationSessionById(id).executeAsOneOrNull()

    fun transcriptions(sessionId: String): List<Transcriptions> =
        queries.transcriptionsForSession(sessionId).executeAsList()

    fun steps(sessionId: String): List<Processing_steps> =
        queries.stepsForSession(sessionId).executeAsList()

    fun messages(sessionId: String): List<Conversation_messages> =
        queries.messagesForSession(sessionId).executeAsList()
}

/** A transcription row to persist (§5.5 step 1). */
data class TranscriptionRow(
    val id: String,
    val sessionId: String,
    val version: Long,
    val isCurrent: Boolean,
    val text: String,
    val modelUsed: String,
    val provider: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val durationMs: Long,
    val createdAt: Long,
)

/** Everything one post-processing conversation turn persists in a single transaction (§5.5 step 2). */
data class ConversationTurnRecord(
    val sessionId: String,
    val stepId: String,
    val systemMessageId: String,
    val userMessageId: String,
    val chainIndex: Long,
    val inputText: String,
    val output: String,
    val assistantMessage: String?,
    val responseFormat: ResponseFormatKind?,
    val modelUsed: String,
    val provider: String,
    val previousTranscriptionId: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val durationMs: Long,
    val systemContent: String,
    val userContent: String,
    val createdAt: Long,
)
