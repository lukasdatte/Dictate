package net.devemperor.dictate.database.entity

enum class StepType {
    AUTO_FORMAT,
    REWORDING,
    QUEUED_PROMPT,

    /**
     * The consolidated post-processing turn (ADR-0012): auto-formatting + all
     * queued prompts + the ambiguity task merged into one model turn, persisted
     * as ONE step. From schema v8 on, `step_type` carries a SQL CHECK including
     * this value (Double-Enum retrofit).
     */
    CONVERSATION_TURN
}
