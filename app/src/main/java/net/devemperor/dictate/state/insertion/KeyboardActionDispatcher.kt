package net.devemperor.dictate.state.insertion

/**
 * A thin, Java-friendly facade over a [KeyboardActionSink] whose method names mirror
 * [InsertionService] (`insert` / `control` / `editAction`) — so a call-site moves from
 * `insertionService().insert(req)` to `keyboardActions().insert(req)` with a one-word diff (§4.2).
 *
 * In **local mode** the wrapped router delegates to [LocalImeSink], which calls the very same
 * [InsertionService] method — so a rewired call-site is byte-for-byte identical to before. Only in
 * PC-mode does the action divert to the companion. The one behavioural difference is the return
 * type: a [SubmitResult] instead of an [InsertionResult] (the PC path is async and has no synchronous
 * result), so a call-site that inspects the outcome must handle [SubmitResult.Done].
 */
class KeyboardActionDispatcher(
    private val sink: KeyboardActionSink,
) {
    fun insert(request: InsertionRequest): SubmitResult = sink.submit(KeyboardAction.TypeText(request))

    fun control(op: ControlOp): SubmitResult = sink.submit(KeyboardAction.Control(op))

    fun editAction(action: EditAction): SubmitResult = sink.submit(KeyboardAction.Edit(action))
}
