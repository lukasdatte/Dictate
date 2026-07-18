package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (render-latency-wave2 §7.1) — pins the Problem-A pre-bind bootstrap
 * render surface. Pure catalog test, no Android views: asserts that
 * [DictateUiState.bootstrap] resolves to a genuinely usable idle keyboard
 * through the real [LayoutCatalog], for both `singleRowMode` values.
 *
 * The whole point of the bootstrap render is that the first frame after a
 * cold start shows the RECORD button (visible + enabled) so the user can
 * tap it — that tap is then buffered by `pendingRecordOnBind`. If a future
 * catalog change hid or disabled RECORD in the idle state, the bootstrap
 * would paint a dead keyboard; this test fails loudly first.
 */
class BootstrapUiStateTest {

    private val catalog = LayoutCatalog(testLayoutStrings())

    private fun slot(mode: LayoutMode, id: LogicalButtonId): ButtonSlot =
        mode.rows.flatMap { it.slots }.first { it.logicalId == id }

    @Test
    fun `two-row bootstrap resolves to KEYBOARD_TWO_ROW`() {
        val boot = DictateUiState.bootstrap(singleRowMode = false)
        assertEquals(LayoutModeId.KEYBOARD_TWO_ROW, catalog.forKeyboard(boot).id)
        // idle default axes the bootstrap relies on
        assertTrue(boot.recording is RecordingState.Idle)
        assertFalse(boot.layout.singleRowMode)
    }

    @Test
    fun `single-row bootstrap resolves to KEYBOARD_SINGLE_ROW`() {
        val boot = DictateUiState.bootstrap(singleRowMode = true)
        assertEquals(LayoutModeId.KEYBOARD_SINGLE_ROW, catalog.forKeyboard(boot).id)
        assertTrue(boot.layout.singleRowMode)
    }

    @Test
    fun `RECORD slot is visible and enabled in both bootstrap modes`() {
        for (singleRow in listOf(false, true)) {
            val boot = DictateUiState.bootstrap(singleRowMode = singleRow)
            val mode = catalog.forKeyboard(boot)
            val record = slot(mode, LogicalButtonId.RECORD)
            assertTrue(
                "RECORD must be visible in the bootstrap idle surface (singleRow=$singleRow)",
                record.visibilityPredicate(boot),
            )
            assertTrue(
                "RECORD must be enabled in the bootstrap idle surface (singleRow=$singleRow)",
                record.enabledResolver(boot),
            )
        }
    }

    @Test
    fun `idle affordance buttons match the idle expectation in both modes`() {
        for (singleRow in listOf(false, true)) {
            val boot = DictateUiState.bootstrap(singleRowMode = singleRow)
            val mode = catalog.forKeyboard(boot)
            // Text-entry keys are always present on the idle surface.
            assertTrue(slot(mode, LogicalButtonId.SPACE).visibilityPredicate(boot))
            assertTrue(slot(mode, LogicalButtonId.ENTER).visibilityPredicate(boot))
            assertTrue(slot(mode, LogicalButtonId.BACKSPACE).visibilityPredicate(boot))
            // TRASH is a recording/staging-only affordance — hidden at idle.
            assertFalse(
                "TRASH must be hidden on the idle bootstrap surface (singleRow=$singleRow)",
                slot(mode, LogicalButtonId.TRASH).visibilityPredicate(boot),
            )
        }
    }
}
