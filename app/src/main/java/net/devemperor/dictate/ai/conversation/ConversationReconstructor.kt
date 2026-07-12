package net.devemperor.dictate.ai.conversation

import net.devemperor.dictate.database.entity.MessageRole

/**
 * One completed turn of a persisted conversation, as loaded from the DB: the
 * user message that was sent (canonical, from `conversation_messages`) plus the
 * assistant's answer of the CURRENT step version (from `processing_steps`).
 *
 * The assistant side carries both fields so the replay sends the full
 * `{message, output}` back to the model (ADR-0012 decision 3) — the model needs
 * to see its own earlier question/explanation as the referent for a later user
 * refinement.
 */
data class ReconstructedTurn(
    val userContent: String,
    val assistantOutput: String,
    val assistantMessage: String?
)

/**
 * Rebuilds the API message list for a conversation from persisted turns. Pure
 * and Android-free.
 *
 * The same shape serves both operations (ADR-0012 decision 4):
 * - **Continuation** (new turn N+1): `priorTurns = [0..N]`,
 *   `trailingUserContent =` the freshly built follow-up message.
 * - **Regenerate** (turn K): `priorTurns = [0..K-1]`,
 *   `trailingUserContent =` the stored user message of turn K (replayed
 *   verbatim, not rebuilt — this is what makes a regenerate byte-faithful).
 * - **Turn 0**: `priorTurns = []`, `trailingUserContent =` the built first
 *   message.
 *
 * The system prompt is NOT part of the returned list; callers pass it to the
 * runner as the separate system parameter (sourced from the persisted `SYSTEM`
 * row so it survives prompt-template changes across app versions).
 */
object ConversationReconstructor {

    fun toApiMessages(
        priorTurns: List<ReconstructedTurn>,
        trailingUserContent: String
    ): List<ConversationMessage> {
        val messages = ArrayList<ConversationMessage>(priorTurns.size * 2 + 1)
        for (turn in priorTurns) {
            messages.add(ConversationMessage(MessageRole.USER, turn.userContent))
            messages.add(
                ConversationMessage(
                    MessageRole.ASSISTANT,
                    StructuredResponseCodec.encode(turn.assistantMessage, turn.assistantOutput)
                )
            )
        }
        messages.add(ConversationMessage(MessageRole.USER, trailingUserContent))
        return messages
    }
}
