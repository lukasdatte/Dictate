package net.devemperor.dictate.windows

import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.InputCommandKindWire
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.EditAction
import net.devemperor.dictate.state.insertion.InsertionPolicy
import net.devemperor.dictate.state.insertion.InsertionRequest
import net.devemperor.dictate.state.insertion.KeyboardAction
import net.devemperor.dictate.state.layout.EnterButtonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The PC routing matrix (§4.4) and the send-window coalescing (§4.3.2).
 */
class PcInputCommandMapperTest {

    private fun typeText(text: String) = KeyboardAction.TypeText(
        InsertionRequest(text = text, source = InsertionSource.STATIC_PROMPT, policy = InsertionPolicy.KEYSTROKE),
    )

    private fun kind(action: KeyboardAction) = PcInputCommandMapper.toCommand(action)?.kind

    // ── Mapping ───────────────────────────────────────────────────────────────────────────

    @Test
    fun textPill_mapsToTypeText() {
        val command = PcInputCommandMapper.toCommand(typeText("hello"))
        assertEquals(InputCommandKindWire.TYPE_TEXT, command?.kind)
        assertEquals("hello", command?.text)
    }

    @Test
    fun anEmptyTypeText_isNotRoutable() {
        assertNull(PcInputCommandMapper.toCommand(typeText("")))
    }

    @Test
    fun theDeletionOps_mapToBackspace() {
        listOf(ControlOp.Backspace, ControlOp.DeleteGrapheme, ControlOp.DeleteSelection).forEach { op ->
            assertEquals(InputCommandKindWire.BACKSPACE, kind(KeyboardAction.Control(op)))
        }
    }

    @Test
    fun enterOps_mapToEnter() {
        assertEquals(InputCommandKindWire.ENTER, kind(KeyboardAction.Control(ControlOp.PhysicalEnter)))
        assertEquals(InputCommandKindWire.ENTER, kind(KeyboardAction.Control(ControlOp.Enter(EnterButtonRole.NEWLINE, 0))))
    }

    @Test
    fun cursorMove_mapsByDirection() {
        assertEquals(InputCommandKindWire.CURSOR_LEFT, kind(KeyboardAction.Control(ControlOp.CursorMove(-1))))
        assertEquals(InputCommandKindWire.CURSOR_RIGHT, kind(KeyboardAction.Control(ControlOp.CursorMove(1))))
    }

    @Test
    fun selectWord_mapsToWordSelectByDirection() {
        assertEquals(InputCommandKindWire.CURSOR_WORD_SELECT_BACK, kind(KeyboardAction.Control(ControlOp.SelectWord(-1))))
        assertEquals(InputCommandKindWire.CURSOR_WORD_SELECT_FORWARD, kind(KeyboardAction.Control(ControlOp.SelectWord(1))))
    }

    @Test
    fun editActions_mapOneToOne() {
        assertEquals(InputCommandKindWire.SELECT_ALL, kind(KeyboardAction.Edit(EditAction.SELECT_ALL)))
        assertEquals(InputCommandKindWire.CUT, kind(KeyboardAction.Edit(EditAction.CUT)))
        assertEquals(InputCommandKindWire.COPY, kind(KeyboardAction.Edit(EditAction.COPY)))
        assertEquals(InputCommandKindWire.PASTE, kind(KeyboardAction.Edit(EditAction.PASTE)))
        assertEquals(InputCommandKindWire.UNDO, kind(KeyboardAction.Edit(EditAction.UNDO)))
        assertEquals(InputCommandKindWire.REDO, kind(KeyboardAction.Edit(EditAction.REDO)))
    }

    @Test
    fun theIcReadBoundOps_areNotRoutable() {
        listOf(ControlOp.CursorNudge(2), ControlOp.SetSelection(0, 3), ControlOp.DeleteSurrounding(1, 0)).forEach { op ->
            assertNull("$op must be gated, not routed", PcInputCommandMapper.toCommand(KeyboardAction.Control(op)))
        }
    }

    // ── Coalescing ────────────────────────────────────────────────────────────────────────

    private fun cmd(kind: InputCommandKindWire, count: Int = 1) = InputCommandWire(kind = kind, count = count)

    @Test
    fun sameDirectionRun_collapsesIntoOneCount() {
        val coalesced = PcInputCommandMapper.coalesce(List(4) { cmd(InputCommandKindWire.CURSOR_LEFT) })

        assertEquals(listOf(cmd(InputCommandKindWire.CURSOR_LEFT, count = 4)), coalesced)
    }

    @Test
    fun aDirectionChange_breaksTheRun_preservingOrder() {
        val coalesced = PcInputCommandMapper.coalesce(
            listOf(
                cmd(InputCommandKindWire.CURSOR_LEFT),
                cmd(InputCommandKindWire.CURSOR_LEFT),
                cmd(InputCommandKindWire.BACKSPACE),
                cmd(InputCommandKindWire.CURSOR_LEFT),
            ),
        )

        assertEquals(
            listOf(
                cmd(InputCommandKindWire.CURSOR_LEFT, count = 2),
                cmd(InputCommandKindWire.BACKSPACE, count = 1),
                cmd(InputCommandKindWire.CURSOR_LEFT, count = 1),
            ),
            coalesced,
        )
    }

    @Test
    fun aRunPastTheRepeatCap_spillsIntoASecondCommand() {
        val coalesced = PcInputCommandMapper.coalesce(List(Endpoints.MAX_INPUT_REPEAT + 5) { cmd(InputCommandKindWire.BACKSPACE) })

        assertEquals(
            listOf(
                cmd(InputCommandKindWire.BACKSPACE, count = Endpoints.MAX_INPUT_REPEAT),
                cmd(InputCommandKindWire.BACKSPACE, count = 5),
            ),
            coalesced,
        )
    }

    @Test
    fun typeTextAndEnter_neverCoalesce() {
        val input = listOf(
            cmd(InputCommandKindWire.ENTER),
            cmd(InputCommandKindWire.ENTER),
            InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = "a"),
            InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = "b"),
        )

        assertEquals(input, PcInputCommandMapper.coalesce(input))
    }
}
