package net.devemperor.dictate.ai.conversation

/**
 * The two-field structured answer every assistant turn produces (ADR-0012):
 *
 * - [message] — a short explanation of what the model did or what was unclear.
 *   May be `null` (fallback provider that could not produce structured output,
 *   or a model that returned an empty message). Paket 2's ambiguity modes must
 *   treat `null` as "no ambiguity reported".
 * - [output] — the resulting text, ready to insert. Never `null`.
 *
 * @see StructuredResponseCodec
 */
data class StructuredResponse(
    val message: String?,
    val output: String
)
