package net.devemperor.dictate.ai.prompt

/**
 * Type-safe representation of the 3-way prompt selection radio groups
 * in SystemPromptsActivity. Maps to SharedPreferences int values 0/1/2.
 *
 * Both StylePromptSelection and SystemPromptSelection use the same schema.
 */
enum class PromptMode(val value: Int) {
    NONE(0),
    PREDEFINED(1),
    CUSTOM(2);

    companion object {
        @JvmStatic
        fun fromValue(value: Int): PromptMode =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}
