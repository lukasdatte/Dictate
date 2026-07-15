package net.devemperor.dictate.ai.prompt

import net.devemperor.dictate.database.entity.PromptType

/**
 * Single source of truth for deciding whether a raw prompt string is a literal
 * [PromptType.TEXT] snippet or an AI [PromptType.PROMPT] instruction.
 *
 * A prompt whose *fully trimmed* content is wrapped in a single pair of outer
 * brackets (`[...]`) is a TEXT pill: the outer brackets are stripped and the
 * inner text is what gets inserted 1:1. Everything else is a PROMPT, stored
 * verbatim.
 *
 * This is the successor to the former runtime `PromptService.isStaticResponse`
 * check. It exists so exactly ONE place owns the trim+strip rule, shared by:
 *  - the JSON import path (v1 files without a `type` field), and
 *  - the SQL migration [net.devemperor.dictate.database.migration.MIGRATION_10_11],
 *    which mirrors this logic in SQL; `PromptTypeClassifierTest` and
 *    `MigrationTo11Test` pin the two to the same result.
 *
 * Edge case (documented, plan F4): `"[a] und [b]"` trims to a bracketed string,
 * so it classifies as TEXT with inner content `a] und [b` — identical to the old
 * runtime behaviour. No bracket-balancing is attempted by design.
 */
object PromptTypeClassifier {

    /**
     * Classifies a raw prompt string.
     *
     * @param raw the stored prompt string (nullable — null/non-bracketed stays a
     *   [PromptType.PROMPT] with its original value preserved).
     * @return the classified type paired with the payload to store (brackets
     *   stripped for TEXT, unchanged otherwise).
     */
    @JvmStatic
    fun classify(raw: String?): Pair<PromptType, String?> {
        val inner = bracketedInner(raw)
        return if (inner != null) PromptType.TEXT to inner else PromptType.PROMPT to raw
    }

    /**
     * Strips the outer brackets from a fully-bracketed pill *name* (plan F2), so
     * a migrated/imported `[Dictate is great]` label no longer shows brackets
     * once the convention is gone. Non-bracketed names are returned unchanged.
     */
    @JvmStatic
    fun stripName(raw: String?): String? = bracketedInner(raw) ?: raw

    /** The inner text of a fully-bracketed (trimmed) string, or null if not bracketed. */
    private fun bracketedInner(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            null
        }
    }
}
