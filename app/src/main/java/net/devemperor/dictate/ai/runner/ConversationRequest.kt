package net.devemperor.dictate.ai.runner

import net.devemperor.dictate.ai.conversation.ConversationMessage

/**
 * A structured, multi-turn completion request (ADR-0012). The last message is
 * the new user turn; earlier messages are the replayed conversation history.
 * The system prompt is passed separately (sourced from the persisted SYSTEM
 * row, not a message here).
 *
 * The `{message, output}` schema is fixed for the foundation, so it is a runner
 * constant rather than a request field.
 */
data class ConversationRequest(
    val messages: List<ConversationMessage>,
    val model: String,
    val systemPrompt: String?,
    val parameters: Map<String, Any> = emptyMap()
)
