package net.devemperor.dictate.ai.conversation

import net.devemperor.dictate.ai.prompt.PromptBuilder
import net.devemperor.dictate.ai.prompt.PromptTemplates

/**
 * Builds the consolidated first user message of a post-processing conversation
 * (ADR-0012): auto-formatting rules + all queued instructions + the ambiguity
 * task, merged into one numbered `<instructions>` list, with the transcript
 * isolated as a `<transcript>` data tag behind an explicit guardrail.
 *
 * Pure and Android-free — the caller (pipeline) resolves all platform state
 * (enabled prefs, prompt-queue slots, language) into [PostProcessingInputs]
 * first, mirroring the `state/layout` split. Directly liftable into a shared
 * JVM module (future Windows dispatch).
 */
object ConversationTurnBuilder {

    /**
     * Whether this run needs a conversation turn at all. `false` for a plain
     * transcription with no auto-formatting and no queued prompt — the pipeline
     * then inserts the bare transcript with no step. [PostProcessingInputs.forceTurn]
     * is the Paket 2 override (ambiguity modes force a turn on a bare transcript).
     */
    fun hasWork(inputs: PostProcessingInputs): Boolean =
        inputs.forceTurn || inputs.autoFormatEnabled || inputs.instructions.isNotEmpty()

    fun buildFirstUserMessage(inputs: PostProcessingInputs): String {
        val items = ArrayList<String>()
        if (inputs.autoFormatEnabled) {
            items.add(
                PromptTemplates.AUTO_FORMATTING_INSTRUCTION_LEAD + "\n" +
                    PromptTemplates.AUTO_FORMATTING_RULES
            )
        }
        for (instruction in inputs.instructions) {
            if (instruction.text.isNotBlank()) items.add(instruction.text)
        }
        if (inputs.includeAmbiguityTask) {
            items.add(PromptTemplates.AMBIGUITY_TASK)
        }

        return PromptBuilder.create()
            .section("guardrail", PromptTemplates.TRANSCRIPT_GUARDRAIL)
            .instructions(items)
            .languageHint(inputs.languageHint)
            .transcript(inputs.transcript)
            .build()
    }

    /**
     * Builds the follow-up user message for a dictated review refinement
     * (ADR-0013). Unlike [buildFirstUserMessage], the spoken reply IS an
     * instruction/answer to the model's prior message — NOT transcript data — so
     * it carries no `<transcript>` guardrail, just a light `<user-reply>` wrap.
     */
    fun buildFollowUpUserMessage(spokenReply: String): String =
        PromptBuilder.create()
            .section("user-reply", spokenReply)
            .build()
}
