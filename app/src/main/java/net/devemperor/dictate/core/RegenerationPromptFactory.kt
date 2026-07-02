package net.devemperor.dictate.core

import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.database.entity.StepType

/**
 * Builds the completion prompt for regenerating a persisted processing step
 * (F-109 — history-reprocess-hardening).
 *
 * Contract: given a step persisted under the F-109 raw-input contract
 * (`prompt_used` = raw instruction, `input_text` = raw text the instruction
 * was applied to — see `PipelineOrchestrator.executeCompletion`), the factory
 * reproduces the exact `PromptPair` the original pipeline call sent. This is
 * what makes regenerated versions comparable to v1 in the versioning UI.
 *
 * Gotcha: steps persisted BEFORE the F-109 fix carry the already-built XML
 * prompt in `input_text`. Regenerating those applies the instruction to the
 * built prompt (single wrap — no double instruction, but not byte-identical
 * to the original call). Accepted status quo per the spec's information-gap
 * fallback; no schema addition.
 */
object RegenerationPromptFactory {

    /**
     * @param stepType persisted type of the step being regenerated
     * @param promptInstruction effective raw instruction (the step's
     *   `promptUsed`, or an "Other prompt" override). Null only for
     *   AUTO_FORMAT steps, which carry no per-step instruction.
     * @param inputText the step's persisted `input_text` (raw contract above)
     * @param languageHint session language — consulted only by the
     *   AUTO_FORMAT branch (part of the formatter's user prompt)
     */
    @JvmStatic
    fun build(
        stepType: StepType,
        promptInstruction: String?,
        inputText: String,
        languageHint: String?,
        promptService: PromptService
    ): PromptService.PromptPair = when {
        // F-108: an AUTO_FORMAT step regenerates through the formatter's own
        // prompt set (system/rules/examples + languageHint/transcript user
        // prompt) — pre-fix this sent the bare transcript with a NULL prompt
        // and the model answered the transcript instead of reformatting it.
        // An explicit "Other prompt" override applies that instruction to the
        // transcript instead (queued-prompt shape, else-branch below).
        stepType == StepType.AUTO_FORMAT && promptInstruction == null ->
            AutoFormattingService.buildPrompts(inputText, languageHint)

        // Original call: PromptService.buildRewording(prompt, selectedText)
        // with the REWORDING system prompt (see runStandalonePrompt).
        stepType == StepType.REWORDING ->
            promptService.buildRewording(
                promptInstruction,
                inputText.takeIf { it.isNotEmpty() }
            )

        // Original call: PromptService.buildQueuedPrompt(prompt, textForPrompt)
        // with the QUEUED system prompt. buildQueuedPrompt skips an empty
        // text-to-process section, matching the pipeline's
        // `textForPrompt = null` for prompts without requiresSelection.
        else ->
            promptService.buildQueuedPrompt(promptInstruction ?: "", inputText)
    }
}
