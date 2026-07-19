package net.devemperor.dictate.ai.conversation

/**
 * The two-field structured answer every assistant turn produces (ADR-0012):
 *
 * - [message] — a short explanation of what the model did or what was unclear.
 *   May be `null` (fallback provider that could not produce structured output,
 *   or a model that returned an empty message). Paket 2's ambiguity modes must
 *   treat `null` as "no ambiguity reported".
 * - [output] — the resulting text, ready to insert. Never `null`.
 * - [needsClarification] — the model's verdict (ADR-0013): `true` when it had
 *   to guess or the request was ambiguous. A transient routing signal for the
 *   review modes; NOT persisted (no DB column) and NOT replayed to the model
 *   (see [StructuredResponseCodec.encode], which stays two-field). Absent /
 *   fallback ⇒ `false`, matching the "message == null ⇒ no ambiguity" rule.
 *
 * @see StructuredResponseCodec
 */
data class StructuredResponse(
    val message: String?,
    val output: String,
    val needsClarification: Boolean = false
)
