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

    /**
     * Data section for UNTRUSTED, user-supplied content (a transcript, a
     * dictated reply, a selection). Escapes `& < >` so the payload cannot close
     * its own tag and forge sibling instructions — the containment the
     * `TRANSCRIPT_GUARDRAIL` assumes (N6). Structural tags built from trusted
     * template text (instructions, rules, guardrail) keep using [section].
     */
    fun dataSection(tag: String, content: String): PromptBuilder =
        section(tag, escapeXml(content))

    // Convenience-Methoden fuer haeufige Tags
    fun instruction(content: String) = section("instruction", content)
    fun selectedText(content: String) = dataSection("selected-text", content)
    fun userRequest(content: String) = dataSection("user-request", content)
    fun languageHint(language: String?) = section("language-hint", language ?: "")
    fun transcript(content: String) = dataSection("transcript", content)
    fun rules(content: String) = section("rules", content)
    fun examples(content: String) = section("examples", content)

    /**
     * Numbered `<instruction index="N">` children wrapped in one
     * `<instructions>` section. Used by the consolidated conversation turn
     * (ADR-0012), where auto-formatting rules, all queued prompts and the
     * ambiguity task are merged into a single, ordered instruction list.
     * Empty/blank items are dropped; renumbering keeps the visible indices
     * contiguous starting at 1.
     */
    fun instructions(items: List<String>): PromptBuilder {
        val inner = items
            .filter { it.isNotBlank() }
            .mapIndexed { i, content -> "<instruction index=\"${i + 1}\">\n$content\n</instruction>" }
            .joinToString("\n")
        return section("instructions", inner)
    }

    fun build(): String {
        return sections.joinToString("\n\n") { (tag, content) ->
            "<$tag>\n$content\n</$tag>"
        }
    }

    companion object {
        @JvmStatic
        fun create() = PromptBuilder()

        /** Escapes the three XML metacharacters (`&` first) so untrusted data
         *  cannot break out of its enclosing tag (N6). */
        internal fun escapeXml(content: String): String =
            content
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
