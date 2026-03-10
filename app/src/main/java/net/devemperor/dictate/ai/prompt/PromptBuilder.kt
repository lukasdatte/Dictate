package net.devemperor.dictate.ai.prompt

class PromptBuilder {
    private val sections = mutableListOf<Pair<String, String>>()

    /** Generische XML-Sektion. Leere/blanke Inhalte werden uebersprungen. */
    fun section(tag: String, content: String): PromptBuilder {
        if (content.isNotBlank()) {
            sections.add(tag to content)
        }
        return this
    }

    // Convenience-Methoden fuer haeufige Tags
    fun instruction(content: String) = section("instruction", content)
    fun selectedText(content: String) = section("selected-text", content)
    fun userRequest(content: String) = section("user-request", content)
    fun languageHint(language: String?) = section("language-hint", language ?: "")
    fun transcript(content: String) = section("transcript", content)
    fun rules(content: String) = section("rules", content)
    fun examples(content: String) = section("examples", content)

    fun build(): String {
        return sections.joinToString("\n\n") { (tag, content) ->
            "<$tag>\n$content\n</$tag>"
        }
    }

    companion object {
        @JvmStatic
        fun create() = PromptBuilder()
    }
}
