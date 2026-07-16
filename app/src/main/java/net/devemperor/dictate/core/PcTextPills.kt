package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import net.devemperor.dictate.state.insertion.InsertionPolicy
import net.devemperor.dictate.state.insertion.InsertionRequest

/**
 * Pure text-pill logic for the PC-dictation Activity (pc-dictation-activity F7), split out so the
 * filtering and the command mapping are JVM-testable without Room / Android instrumentation.
 *
 * The Activity shows a permanent text-pill row above the keyboard (team decision: a plain row like
 * the history, NOT the IME's mutually-exclusive `contentArea` panel). Only TEXT pills (ADR-0024)
 * appear; a tap types the pill's literal content to the PC.
 */
object PcTextPills {

    /** Keep only the literal-text pills (ADR-0024 `PromptType.TEXT`); PROMPT pills are hidden here. */
    fun filter(prompts: List<PromptEntity>): List<PromptEntity> =
        prompts.filter { it.typeEnum == PromptType.TEXT }

    /**
     * The insertion request a text-pill tap submits. Mirrors the IME's `insertTextPill`
     * (`STATIC_PROMPT` / `PIPELINE`); through the Activity's PC dispatcher this maps to a `TYPE_TEXT`
     * command on the PC (the policy is irrelevant on the PC path — `PcInputCommandMapper` only reads
     * the text). A null [PromptEntity.prompt] degrades to empty text.
     */
    fun toRequest(pill: PromptEntity): InsertionRequest =
        InsertionRequest(
            pill.prompt ?: "",
            InsertionSource.STATIC_PROMPT,
            InsertionPolicy.PIPELINE,
            null,
            null,
        )
}
