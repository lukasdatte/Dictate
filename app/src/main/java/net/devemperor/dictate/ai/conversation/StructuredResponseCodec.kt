package net.devemperor.dictate.ai.conversation

/**
 * The single authority for the `{ "message": ..., "output": ... }` wire format
 * used across the conversation feature (ADR-0012). Keeping encode + parse in
 * one place means the assistant-turn replay, the CUSTOM/OpenRouter text
 * fallback instruction, and the strict-response parse can never drift.
 *
 * Deliberately dependency-free (no `org.json`): the domain must stay
 * Android-free and unit-testable on a plain JVM. Parsing is lenient
 * brace-matching + code-fence stripping tuned for the controlled two-field
 * schema, not a general JSON parser.
 */
object StructuredResponseCodec {

    /** Field names of the schema — single source for building the provider schemas. */
    val fieldNames: Pair<String, String> = "message" to "output"

    /**
     * Canonical serialization of a structured response, used when replaying a
     * prior assistant turn back to the model (full `{message, output}`, ADR-0012
     * decision 3).
     */
    fun encode(message: String?, output: String): String {
        val messageJson = if (message == null) "null" else quote(message)
        return "{\"message\":$messageJson,\"output\":${quote(output)}}"
    }

    fun encode(response: StructuredResponse): String = encode(response.message, response.output)

    /**
     * Lenient parse. Handles a clean schema object, a code-fenced object, and
     * plain prose:
     *
     * - A `{...}` object with an `"output"` string field → `{message, output}`
     *   (`message` is `null` when absent or JSON `null`).
     * - No parseable `"output"` field → the whole (fence-stripped) text becomes
     *   `output`, `message = null`. This is exactly the pre-conversation
     *   behaviour, so a fallback provider that ignores the schema degrades to
     *   "the model's text is the output".
     */
    fun parseLenient(raw: String): StructuredResponse {
        val stripped = stripCodeFences(raw).trim()
        val obj = extractOuterObject(stripped)
        if (obj != null) {
            val output = extractStringField(obj, "output")
            if (output != null) {
                return StructuredResponse(
                    message = extractStringField(obj, "message"),
                    output = output
                )
            }
        }
        return StructuredResponse(message = null, output = stripped)
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun stripCodeFences(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        // Drop the opening fence line (```json / ```) and the trailing fence.
        val firstNewline = t.indexOf('\n')
        if (firstNewline < 0) return t
        var body = t.substring(firstNewline + 1)
        val closing = body.lastIndexOf("```")
        if (closing >= 0) body = body.substring(0, closing)
        return body.trim()
    }

    /** Returns the substring from the first `{` to its matching `}` (quote-aware), or null. */
    private fun extractOuterObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Extracts the JSON string value of `"<key>"` from an object body. Returns
     * `null` when the key is absent or its value is JSON `null`; treats a
     * non-string value defensively as absent.
     */
    private fun extractStringField(obj: String, key: String): String? {
        val marker = "\"$key\""
        var searchFrom = 0
        while (true) {
            val keyPos = findKeyOutsideString(obj, marker, searchFrom) ?: return null
            var i = keyPos + marker.length
            // Skip whitespace, require a colon.
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') { searchFrom = keyPos + marker.length; continue }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            return when {
                obj[i] == '"' -> readJsonString(obj, i)
                obj.startsWith("null", i) -> null
                else -> null // non-string value — defensively treat as absent
            }
        }
    }

    /** Finds `marker` at a position that is not inside a JSON string literal. */
    private fun findKeyOutsideString(text: String, marker: String, from: Int): Int? {
        var inString = false
        var escaped = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
            } else {
                if (text.startsWith(marker, i)) return i
                if (c == '"') inString = true
                i++
            }
        }
        return null
    }

    /** Reads a JSON string starting at [openQuote] (an index pointing at `"`). */
    private fun readJsonString(text: String, openQuote: Int): String {
        val sb = StringBuilder()
        var i = openQuote + 1
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i + 1 >= text.length) return sb.toString()
                    when (val esc = text[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 5 < text.length) {
                                val hex = text.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 4
                                } else {
                                    sb.append(esc)
                                }
                            } else {
                                sb.append(esc)
                            }
                        }
                        else -> sb.append(esc)
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
