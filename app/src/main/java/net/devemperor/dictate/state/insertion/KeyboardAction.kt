package net.devemperor.dictate.state.insertion

/**
 * The unifying hull over the three existing action models — the one type every dispatching
 * call-site submits to a [KeyboardActionSink] (§4.2).
 *
 * Deliberately a **thin wrapper**, not a parallel hierarchy: [ControlOp], [EditAction] and
 * [InsertionRequest] stay the semantic carriers (DRY), so the local sink can delegate to
 * [InsertionService] byte-for-byte and no second model can drift. The router picks exactly one sink
 * per action (local IME vs. PC), so the same submit reaches the field the user is actually looking at
 * (§4.1).
 */
sealed interface KeyboardAction {

    /** A text insert (dictation fragment, space, emoji, QWERTZ char, text pill, resend). */
    data class TypeText(val request: InsertionRequest) : KeyboardAction

    /** A control op (backspace, enter, cursor move, word-select, selection delete). */
    data class Control(val op: ControlOp) : KeyboardAction

    /** An edit-bar action (copy/cut/paste/undo/redo/select-all). */
    data class Edit(val action: EditAction) : KeyboardAction
}

/**
 * The single routing port in front of the [InsertionService] fassade (§4.1).
 *
 * A [KeyboardActionRouter] implements it by choosing a sink; the sinks ([LocalImeSink],
 * `PcInputSink`) implement it as the actual write. The local sink returns synchronously; the PC sink
 * returns [SubmitResult.Accepted] immediately and reports the network result asynchronously through
 * the state (an emitted `Action`).
 */
fun interface KeyboardActionSink {
    fun submit(action: KeyboardAction): SubmitResult
}

/** The outcome of a [KeyboardActionSink.submit]. */
sealed interface SubmitResult {

    /** Local sink, synchronous — carries the underlying [InsertionResult]. */
    data class Done(val result: InsertionResult) : SubmitResult

    /** PC sink — accepted onto the send window; the network result arrives later via state. */
    data object Accepted : SubmitResult

    /** The chosen sink cannot perform this action — reported, never silently dropped. */
    data class Unsupported(val reason: UnsupportedReason) : SubmitResult
}

/** Why a sink refused an action (kept small — it feeds a log line, not the user UI). */
enum class UnsupportedReason {
    /** A control op that only exists in the other sink's world (e.g. [ControlOp.SelectWord] on local, an IC-read op on PC). */
    OP_NOT_ROUTABLE,
}
