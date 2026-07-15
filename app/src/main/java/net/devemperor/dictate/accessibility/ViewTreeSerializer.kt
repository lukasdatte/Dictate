package net.devemperor.dictate.accessibility

/**
 * Turns a [UiNodeSnapshot] tree into the compact text block that goes into the
 * prompt as `<ui-context>`.
 *
 * # Shape
 *
 * One line per interesting node, indented by depth:
 *
 * ```
 * EditText #compose_body "Hi Anna" (editable)
 *   TextView "To: anna@example.com"
 *   Button "Send"
 * ```
 *
 * Uninteresting nodes (no text, no description, no id, not editable) are
 * dropped but their children are kept and re-parented, so layout scaffolding —
 * which is most of a real tree — does not eat the budget. A raw dump of a
 * moderately complex screen runs 5–10k tokens; pruned like this it lands
 * around 1–2k, which is what makes this affordable to send on every dictation.
 *
 * # Why not XML
 *
 * The transcript already sits inside XML-ish tags, and nesting an escaped XML
 * document inside one is both bigger and easier for a model to confuse with
 * the surrounding structure. An indented list is smaller and unambiguous.
 * The whole block is still escaped by `PromptBuilder.dataSection` when it goes
 * into the prompt — see [ConversationTurnBuilder][net.devemperor.dictate.ai.conversation.ConversationTurnBuilder].
 *
 * # Privacy
 *
 * Redaction is [AccessibilityContextReader]'s job (it is the only place that
 * can see the platform's `isPassword` / input-type signals). This class
 * renders the decision: a node marked [UiNodeSnapshot.redacted] prints
 * `[redacted]` rather than its text. It never *recovers* text — if the reader
 * dropped it, it is gone before this class runs, so a bug here cannot leak a
 * password.
 *
 * @see AccessibilityContextReader
 */
object ViewTreeSerializer {

    /** Placeholder for a field whose content was withheld. */
    const val REDACTED = "[redacted]"

    /**
     * Hard ceiling on the emitted block. A pathological screen (a long chat
     * log, a web view) can produce thousands of nodes, and an unbounded block
     * would blow up both the request and — because ADR-0012 persists the built
     * message verbatim — the database row behind it. Truncation is preferable
     * to either.
     */
    const val MAX_CHARS = 4000

    /** Marker appended when [MAX_CHARS] cut the output. */
    const val TRUNCATION_MARKER = "… [truncated]"

    /**
     * Render [root] as an indented outline, or `null` when nothing survives
     * pruning (an empty block is worse than no block: it costs tokens and
     * tells the model nothing).
     */
    fun serialize(root: UiNodeSnapshot?): String? {
        if (root == null) return null
        val out = StringBuilder()
        appendNode(root, depth = 0, out = out)
        if (out.isEmpty()) return null

        val text = out.trimEnd().toString()
        return if (text.length <= MAX_CHARS) {
            text
        } else {
            // Cut on a line boundary — half a line reads as a truncated *value*
            // and could mislead the model about what is on screen.
            val cut = text.lastIndexOf('\n', MAX_CHARS)
            val head = if (cut > 0) text.substring(0, cut) else text.substring(0, MAX_CHARS)
            head + "\n" + TRUNCATION_MARKER
        }
    }

    private fun appendNode(node: UiNodeSnapshot, depth: Int, out: StringBuilder) {
        val line = describe(node)
        // An uninteresting node does not consume a depth level either: keeping
        // the indent would imply a hierarchy the reader never sees.
        val childDepth = if (line != null) {
            out.append("  ".repeat(depth)).append(line).append('\n')
            depth + 1
        } else {
            depth
        }
        for (child in node.children) appendNode(child, childDepth, out)
    }

    /** One line for [node], or `null` if it carries no information. */
    private fun describe(node: UiNodeSnapshot): String? {
        val text = when {
            node.redacted -> REDACTED
            !node.text.isNullOrBlank() -> "\"${node.text.trim()}\""
            else -> null
        }
        val description = node.contentDescription
            ?.takeIf { it.isNotBlank() && !node.redacted }
            ?.let { "desc=\"${it.trim()}\"" }
        val id = node.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { "#$it" }

        if (text == null && description == null && id == null && !node.isEditable) return null

        return buildString {
            append(node.className?.substringAfterLast('.') ?: "View")
            id?.let { append(' ').append(it) }
            text?.let { append(' ').append(it) }
            description?.let { append(' ').append(it) }
            if (node.isEditable) append(" (editable)")
        }
    }
}
