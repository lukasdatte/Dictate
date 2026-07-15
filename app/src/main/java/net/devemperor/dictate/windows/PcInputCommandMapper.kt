package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.InputCommandKindWire
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.EditAction
import net.devemperor.dictate.state.insertion.KeyboardAction

/**
 * The PC-mode routing matrix (§4.4) plus the send-window coalescing (§4.3.2) — pure and testable.
 *
 * Maps a [KeyboardAction] to its semantic wire command(s), and folds a buffered burst into the
 * smallest ordered batch: adjacent same-kind movement/deletion commands collapse into one `count`
 * (capped at [Endpoints.MAX_INPUT_REPEAT]), heterogeneous sequences stay an ordered list.
 */
object PcInputCommandMapper {

    /** The mapping of one action, or `null` when the action has no PC meaning (reported Unsupported). */
    fun toCommand(action: KeyboardAction): InputCommandWire? = when (action) {
        is KeyboardAction.TypeText ->
            // An empty insert is a no-op; the wire forbids an empty TYPE_TEXT text anyway.
            action.request.text.takeIf { it.isNotEmpty() }?.let {
                InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = it)
            }

        is KeyboardAction.Edit -> InputCommandWire(kind = action.action.toWireKind())

        is KeyboardAction.Control -> action.op.toWireKind()?.let { InputCommandWire(kind = it) }
    }

    private fun EditAction.toWireKind(): InputCommandKindWire = when (this) {
        EditAction.COPY -> InputCommandKindWire.COPY
        EditAction.PASTE -> InputCommandKindWire.PASTE
        EditAction.CUT -> InputCommandKindWire.CUT
        EditAction.UNDO -> InputCommandKindWire.UNDO
        EditAction.REDO -> InputCommandKindWire.REDO
        EditAction.SELECT_ALL -> InputCommandKindWire.SELECT_ALL
    }

    /** null → the op is IC-read-bound and gated away in PC-mode (§6.2), not routable to the PC. */
    private fun ControlOp.toWireKind(): InputCommandKindWire? = when (this) {
        is ControlOp.Backspace,
        is ControlOp.DeleteGrapheme,
        is ControlOp.DeleteSelection -> InputCommandKindWire.BACKSPACE

        is ControlOp.Enter,
        is ControlOp.PhysicalEnter -> InputCommandKindWire.ENTER

        is ControlOp.CursorMove ->
            if (direction < 0) InputCommandKindWire.CURSOR_LEFT else InputCommandKindWire.CURSOR_RIGHT

        is ControlOp.SelectWord ->
            if (direction < 0) InputCommandKindWire.CURSOR_WORD_SELECT_BACK else InputCommandKindWire.CURSOR_WORD_SELECT_FORWARD

        // IC-read-bound ops: only produced by paths gated in PC-mode (§6.2). Never routable.
        is ControlOp.CursorNudge,
        is ControlOp.SetSelection,
        is ControlOp.DeleteSurrounding -> null
    }

    /**
     * Folds a buffered command list into the smallest ordered batch (§4.3.2).
     *
     * Adjacent commands of the same **repeatable** kind (backspace, cursor, word-select) with no text
     * merge their `count`; a merge that would exceed [Endpoints.MAX_INPUT_REPEAT] spills into a second
     * command, so order and total are preserved. TYPE_TEXT/ENTER and the clipboard kinds never merge.
     */
    fun coalesce(commands: List<InputCommandWire>): List<InputCommandWire> {
        val out = ArrayList<InputCommandWire>(commands.size)
        for (command in commands) {
            val last = out.lastOrNull()
            if (last != null && command.isRepeatable() && last.kind == command.kind && last.count < Endpoints.MAX_INPUT_REPEAT) {
                val merged = (last.count + command.count).coerceAtMost(Endpoints.MAX_INPUT_REPEAT)
                val overflow = last.count + command.count - merged
                out[out.lastIndex] = last.copy(count = merged)
                if (overflow > 0) out += command.copy(count = overflow)
            } else {
                out += command
            }
        }
        return out
    }

    private fun InputCommandWire.isRepeatable(): Boolean = kind in REPEATABLE_KINDS

    private val REPEATABLE_KINDS = setOf(
        InputCommandKindWire.BACKSPACE,
        InputCommandKindWire.CURSOR_LEFT,
        InputCommandKindWire.CURSOR_RIGHT,
        InputCommandKindWire.CURSOR_WORD_SELECT_BACK,
        InputCommandKindWire.CURSOR_WORD_SELECT_FORWARD,
    )
}
