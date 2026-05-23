package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.HostEditorState
import net.devemperor.dictate.state.KeyboardInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Structural invariant test for the 5 ENTER slots in
 * [LayoutCatalog] (`docs/plans/2026-05-23 - dictate-enter-button-host-action`
 * AC-1: Single-Source-of-Truth).
 *
 * The slots in `KEYBOARD_TWO_ROW`, `KEYBOARD_SINGLE_ROW`,
 * `KEYBOARD_TWO_ROW_SEND_MODE`, `KEYBOARD_SINGLE_ROW_SEND_MODE` and
 * `KEYBOARD_REPROCESS_STAGING` must consume **identical** resolver
 * implementations so the Enter-button's icon and click behaviour is
 * uniform regardless of which keyboard mode is rendered. The Catalog
 * is data — five copies of the slot definition can drift in a refactor
 * if no test guards them. This test asserts the invariant by feeding
 * every slot the same state and asserting all five produce the same
 * `iconResolver` output and the same `actionResolver` output.
 */
class LayoutCatalogEnterSlotConsistencyTest {

    private val strings = testLayoutStrings()
    private val catalog = LayoutCatalog(strings)
    private val services = net.devemperor.dictate.testutil.fakeModuleServices()

    private fun enterSlots(): List<ButtonSlot> = catalog.allModes()
        .mapNotNull { mode ->
            mode.slots.firstOrNull { it.logicalId == LogicalButtonId.ENTER }
        }

    @Test
    fun `all keyboard layouts expose an ENTER slot (5 slots)`() {
        val slots = enterSlots()
        // KEYBOARD_TWO_ROW + KEYBOARD_SINGLE_ROW + the two _SEND_MODE
        // variants + KEYBOARD_REPROCESS_STAGING. OVERLAY_5BUTTON has
        // no ENTER slot by design.
        assertEquals(
            "ENTER slot count must stay at 5 (KEYBOARD_* modes only)",
            5,
            slots.size,
        )
    }

    @Test
    fun `every ENTER slot resolves the same icon for the same state`() {
        val state = stateWith(
            HostEditorState(imeActionId = IME_ACTION_SEND, hasEditorInfo = true),
        )
        val icons = enterSlots().map { it.iconResolver(state) }.distinct()
        assertEquals(
            "All 5 ENTER slots must produce the same icon for the same state",
            1,
            icons.size,
        )
        // Sanity: the icon is the Send-icon for an IME_ACTION_SEND editor.
        assertEquals(
            net.devemperor.dictate.R.drawable.ic_baseline_send_20,
            icons.single(),
        )
    }

    @Test
    fun `every ENTER slot resolves the same action when canCommitToHost`() {
        val state = stateWith(
            HostEditorState(imeActionId = IME_ACTION_SEND, hasEditorInfo = true),
        ).copy(imeViewVisible = true)
        val actions = enterSlots().map { it.actionResolver(state, services) }.distinct()
        assertEquals(
            "All 5 ENTER slots must dispatch the same Action",
            1,
            actions.size,
        )
        assertEquals(
            net.devemperor.dictate.state.Action.KeyboardInputAction.EnterKey,
            actions.single(),
        )
    }

    @Test
    fun `every ENTER slot returns null when IME view is hidden`() {
        val state = stateWith(HostEditorState(hasEditorInfo = true)).copy(imeViewVisible = false)
        enterSlots().forEachIndexed { idx, slot ->
            assertNotNull("slot $idx must have an iconResolver", slot.iconResolver(state))
            assertEquals(
                "slot $idx must not dispatch when imeViewVisible == false",
                null,
                slot.actionResolver(state, services),
            )
        }
    }

    @Test
    fun `every ENTER slot icon switches when imeAction changes`() {
        val send = stateWith(HostEditorState(imeActionId = IME_ACTION_SEND, hasEditorInfo = true))
        val done = stateWith(HostEditorState(imeActionId = IME_ACTION_DONE, hasEditorInfo = true))
        enterSlots().forEachIndexed { idx, slot ->
            val sendIcon = slot.iconResolver(send)
            val doneIcon = slot.iconResolver(done)
            // The icon must change with the host editor's imeOptions —
            // proves the slot reads from state.keyboardInput.hostEditor
            // and not from a hard-coded constant.
            assertNotNull("slot $idx send icon", sendIcon)
            assertNotNull("slot $idx done icon", doneIcon)
            assertEquals(
                "slot $idx SEND must produce Send-icon",
                net.devemperor.dictate.R.drawable.ic_baseline_send_20,
                sendIcon,
            )
            assertEquals(
                "slot $idx DONE must produce Check-icon",
                net.devemperor.dictate.R.drawable.ic_baseline_check_24,
                doneIcon,
            )
        }
    }

    private fun stateWith(host: HostEditorState): DictateUiState =
        DictateUiState.initial().copy(
            keyboardInput = KeyboardInputState(hostEditor = host),
        )
}
