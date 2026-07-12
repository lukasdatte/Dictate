package net.devemperor.dictate.ai.conversation

import net.devemperor.dictate.database.entity.MessageRole

/**
 * One message in a post-processing conversation, in the shape the provider
 * runners send to the API.
 *
 * Pure domain type (Android-free): the platform runners map this to the
 * OpenAI / Anthropic SDK message builders. `SYSTEM` content is passed to the
 * runner as a separate system parameter rather than as a message here, so a
 * [ConversationMessage] carries only `USER` / `ASSISTANT` roles in practice.
 *
 * @see net.devemperor.dictate.ai.conversation.ConversationReconstructor
 */
data class ConversationMessage(
    val role: MessageRole,
    val content: String
)
