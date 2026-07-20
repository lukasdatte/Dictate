package net.devemperor.dictate.companion.data

import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.conversation.ReconstructedTurn
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
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md §5.5, §8.3, §9.3
 * @see docs/decisions/0012-pipeline-post-processing-conversation.md
 * @see docs/decisions/0013-review-panel-and-ambiguity-modes.md
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

    fun insertTranscription(record: TranscriptionRecord) {
        queries.insertTranscription(
            id = record.id,
            sessionId = record.sessionId,
            version = record.version,
            isCurrent = record.isCurrent,
            text = record.text,
            modelUsed = record.modelUsed,
            provider = record.provider,
            promptTokens = record.promptTokens,
            completionTokens = record.completionTokens,
            durationMs = record.durationMs,
            createdAt = record.createdAt,
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

    // ── re-dictate: refinement session + conversation continuation (§8.3, ADR-0013 §6) ──────────

    /**
     * Persists the transcription-only S2 take of a review re-dictate (§8.3 step 1): a COMPLETED
     * RECORDING with origin `REVIEW_REFINEMENT`, hung off the reviewed session via [parentSessionId].
     * The continuation TURN it produces is appended to the *parent* session, not here.
     */
    fun createRefinementSession(
        id: String,
        createdAt: Long,
        language: String?,
        audioFilePath: String?,
        audioFilePathsJson: String,
        durationSeconds: Long,
        parentSessionId: String,
    ) {
        queries.insertRefinementSession(
            id = id,
            createdAt = createdAt,
            language = language,
            audioFilePath = audioFilePath,
            audioFilePaths = audioFilePathsJson,
            durationSeconds = durationSeconds,
            parentSessionId = parentSessionId,
        )
    }

    /**
     * Loads a reviewed session's persisted conversation for a continuation (§8.3 step 3): the current,
     * SUCCESS `CONVERSATION_TURN` steps in chain order (each paired with its USER message) plus the
     * persisted SYSTEM prompt. ERROR turns are skipped — exactly as Android's `loadConversation` does,
     * so a failed follow-up never re-enters the replay (ADR-0013 §6).
     */
    fun loadConversation(reviewSessionId: String): ConversationSnapshot {
        val messages = messages(reviewSessionId)
        val systemContent = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        val userByTurn = messages.filter { it.role == MessageRole.USER }.associateBy { it.turn_index }
        val turns = steps(reviewSessionId)
            .filter { it.step_type == StepType.CONVERSATION_TURN && it.is_current && it.status == StepStatus.SUCCESS }
            .sortedBy { it.chain_index }
            .mapNotNull { step ->
                val user = userByTurn[step.chain_index] ?: return@mapNotNull null
                ReconstructedTurn(
                    userContent = user.content,
                    assistantOutput = step.output_text.orEmpty(),
                    assistantMessage = step.assistant_message,
                    chainIndex = step.chain_index.toInt(),
                )
            }
        return ConversationSnapshot(turns = turns, systemContent = systemContent)
    }

    /**
     * Appends a `ConversationContinuation` turn to [reviewSessionId] in one transaction (§8.3 step 3):
     * the new `CONVERSATION_TURN` step at `chain_index = max+1`, its USER follow-up message, and the
     * session's updated `final_output_text`. No new SYSTEM row — the system prompt persists once at
     * turn 0 (ADR-0012 §3).
     */
    fun appendContinuationTurn(record: ContinuationTurnRecord) {
        database.transaction {
            val existingSteps = steps(record.reviewSessionId)
            val nextChainIndex = (existingSteps.maxOfOrNull { it.chain_index } ?: -1L) + 1L
            val nextSeq = (messages(record.reviewSessionId).maxOfOrNull { it.seq } ?: -1L) + 1L
            queries.insertProcessingStep(
                id = record.stepId,
                sessionId = record.reviewSessionId,
                stepType = StepType.CONVERSATION_TURN,
                chainIndex = nextChainIndex,
                version = 1,
                isCurrent = true,
                inputText = record.followUpText,
                outputText = record.output,
                modelUsed = record.modelUsed,
                provider = record.provider,
                promptUsed = null,
                promptEntityId = null,
                previousStepId = existingSteps.maxByOrNull { it.chain_index }?.id,
                previousTranscriptionId = null,
                sourceSessionId = record.refinementSessionId,
                promptTokens = record.promptTokens,
                completionTokens = record.completionTokens,
                durationMs = record.durationMs,
                status = StepStatus.SUCCESS,
                errorMessage = null,
                createdAt = record.createdAt,
                assistantMessage = record.assistantMessage,
                responseFormat = record.responseFormat,
            )
            queries.insertConversationMessage(
                id = record.userMessageId,
                sessionId = record.reviewSessionId,
                turnIndex = nextChainIndex,
                seq = nextSeq,
                role = MessageRole.USER,
                content = record.userContent,
                stepId = record.stepId,
                createdAt = record.createdAt,
            )
            queries.completeDictationSession(finalOutputText = record.output, id = record.reviewSessionId)
        }
    }

    /**
     * Persists a failed continuation as an ERROR `CONVERSATION_TURN` step (§8.3 error path): auditable,
     * and skipped by [loadConversation] on the next replay so a bad follow-up never poisons the dialog.
     */
    fun appendErrorTurn(reviewSessionId: String, stepId: String, followUpText: String, errorMessage: String?, createdAt: Long) {
        val existingSteps = steps(reviewSessionId)
        val nextChainIndex = (existingSteps.maxOfOrNull { it.chain_index } ?: -1L) + 1L
        queries.insertProcessingStep(
            id = stepId,
            sessionId = reviewSessionId,
            stepType = StepType.CONVERSATION_TURN,
            chainIndex = nextChainIndex,
            version = 1,
            isCurrent = true,
            inputText = followUpText,
            outputText = null,
            modelUsed = "",
            provider = "",
            promptUsed = null,
            promptEntityId = null,
            previousStepId = existingSteps.maxByOrNull { it.chain_index }?.id,
            previousTranscriptionId = null,
            sourceSessionId = null,
            promptTokens = 0,
            completionTokens = 0,
            durationMs = 0,
            status = StepStatus.ERROR,
            errorMessage = errorMessage,
            createdAt = createdAt,
            assistantMessage = null,
            responseFormat = null,
        )
    }

    // ── history reads (desktop-dictated sessions, §9.3) ──────────────────────────────────────────
    //
    // The phone-mirror history (SqlDelightHistoryRepository / pageHistory) JOINs dispatch_state and
    // scopes to host_origin = 'PHONE_SYNC', so a locally-dictated session — which has no dispatch_state
    // row — can never surface there. These reads are the DESKTOP_DICTATION counterpart: they page over
    // the sessions this host produced, exposing both the final output (what was inserted) and the raw
    // transcript (before post-processing) so the History screen can show the difference (§9.3). Only
    // COMPLETED takes are listed — an in-flight or FAILED session has no final output to re-insert.

    /**
     * One page of completed desktop-dictated sessions, newest first (§9.3). [term] is a
     * case-insensitive substring of the final output (empty matches everything, as in [pageHistory]).
     */
    fun pageDesktopHistory(term: String, limit: Long, offset: Long): List<DesktopHistoryEntry> =
        queries.pageDesktopHistory(term, limit, offset).executeAsList().map { row ->
            DesktopHistoryEntry(
                sessionId = row.id,
                createdAt = row.created_at,
                finalOutputText = row.final_output_text.orEmpty(),
                transcriptText = row.transcript_text,
                insertedAt = row.inserted_at,
            )
        }

    /** How many completed desktop-dictated sessions match [term] — the pager's total (§9.3). */
    fun countDesktopHistory(term: String): Long =
        queries.countDesktopHistory(term).executeAsOne()

    /** A single desktop session as a history entry, or null if absent / not yet completed (§9.3). */
    fun desktopHistoryEntry(sessionId: String): DesktopHistoryEntry? =
        queries.desktopHistoryEntry(sessionId).executeAsOneOrNull()?.let { row ->
            DesktopHistoryEntry(
                sessionId = row.id,
                createdAt = row.created_at,
                finalOutputText = row.final_output_text.orEmpty(),
                transcriptText = row.transcript_text,
                insertedAt = row.inserted_at,
            )
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

/**
 * A completed desktop-dictated session as the History screen shows it (§9.3): the [finalOutputText]
 * that was (or can be) inserted, plus the raw [transcriptText] before post-processing so the user can
 * see what the model changed. [insertedAt] is null for a take that was reviewed-and-discarded (never
 * placed into a window), which the screen surfaces the same way the phone-mirror rows surface "synced".
 */
data class DesktopHistoryEntry(
    val sessionId: String,
    val createdAt: Long,
    val finalOutputText: String,
    /** The current transcription's text (version 1 / is_current), or null if none was persisted. */
    val transcriptText: String?,
    val insertedAt: Long?,
)

/** A transcription record to persist (§5.5 step 1). */
data class TranscriptionRecord(
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

/** A reviewed session's replayable conversation: prior turns in chain order + the persisted system prompt. */
data class ConversationSnapshot(
    val turns: List<ReconstructedTurn>,
    val systemContent: String?,
)

/** Everything a `ConversationContinuation` follow-up turn persists in a single transaction (§8.3 step 3). */
data class ContinuationTurnRecord(
    val reviewSessionId: String,
    /** The `REVIEW_REFINEMENT` session whose transcript this follow-up came from (audit link). */
    val refinementSessionId: String?,
    val stepId: String,
    val userMessageId: String,
    /** Raw spoken follow-up (step `input_text`). */
    val followUpText: String,
    /** The `<user-reply>`-wrapped follow-up actually sent to the model (the USER message content). */
    val userContent: String,
    val output: String,
    val assistantMessage: String?,
    val responseFormat: ResponseFormatKind?,
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
