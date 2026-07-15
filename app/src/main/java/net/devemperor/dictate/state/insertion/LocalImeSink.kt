package net.devemperor.dictate.state.insertion

/**
 * The local sink: routes a [KeyboardAction] byte-for-byte into [InsertionService] (§4.2).
 *
 * There is deliberately **no behaviour** here beyond the switch — the whole point is that local mode
 * stays verhaltensidentisch to before the router existed. Every historical robustness (auto-enter,
 * host-guard, audit, recovery, grapheme/selection semantics) still lives in [InsertionService]; this
 * class only unwraps the hull and calls the matching entry point.
 *
 * [ControlOp.SelectWord] is the one op with no local meaning (the on-device backspace-swipe works the
 * field directly via `setSelection`, not through a control op) — it is reported
 * [SubmitResult.Unsupported] rather than passed to a control executor that would no-op it silently.
 */
class LocalImeSink(
    private val insertion: InsertionService,
) : KeyboardActionSink {

    override fun submit(action: KeyboardAction): SubmitResult = when (action) {
        is KeyboardAction.TypeText -> SubmitResult.Done(insertion.insert(action.request))
        is KeyboardAction.Edit -> SubmitResult.Done(insertion.editAction(action.action))
        is KeyboardAction.Control -> when (action.op) {
            is ControlOp.SelectWord -> SubmitResult.Unsupported(UnsupportedReason.OP_NOT_ROUTABLE)
            else -> SubmitResult.Done(insertion.control(action.op))
        }
    }
}
