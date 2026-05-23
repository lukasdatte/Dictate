package net.devemperor.dictate.state

import net.devemperor.dictate.core.ContentArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [LayoutModule].
 *
 * Coverage:
 * - ToggleSingleRowMode flips the boolean
 * - ToggleSmallMode flips smallMode (false → true) AND clamps contentArea
 *   to MAIN_BUTTONS atomically (Spec 2 §4.1 / KSM-bug fix)
 * - ToggleSmallMode true → false leaves contentArea alone
 * - SetSmallMode(true) is idempotent and atomic-clamps contentArea
 * - SetSmallMode(false) just clears the flag
 * - SetContentArea while small-mode + non-MAIN_BUTTONS is rejected
 * - SetContentArea otherwise updates the field
 * - Lens + id + initial state
 *
 * **Atomic setSmallMode (Spec 2 §4.1):** when `smallMode` flips to `true`,
 * `contentArea` MUST be MAIN_BUTTONS in the same `state.copy(...)` call.
 * The legacy `KeyboardStateManager` did these as two mutations, allowing
 * a momentary "small + QWERTZ" inconsistent state. The atomic reducer
 * structurally rules that out.
 */
class LayoutModuleTest {

    private val module = LayoutModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    // ─── ToggleSingleRowMode ────────────────────────────────────────────

    @Test
    fun `ToggleSingleRowMode false to true`() {
        val state = LayoutState(singleRowMode = false)
        val result = module.reduce(state, Action.LayoutAction.ToggleSingleRowMode, ctx())
        assertEquals(true, result!!.nextState.singleRowMode)
    }

    @Test
    fun `ToggleSingleRowMode true to false`() {
        val state = LayoutState(singleRowMode = true)
        val result = module.reduce(state, Action.LayoutAction.ToggleSingleRowMode, ctx())
        assertEquals(false, result!!.nextState.singleRowMode)
    }

    // ─── ToggleSmallMode (the atomic-clamp contract) ───────────────────

    @Test
    fun `ToggleSmallMode false to true atomically clamps contentArea to MAIN_BUTTONS`() {
        // Spec 2 §4.1 / KSM-bug fix: small + QWERTZ must never co-exist.
        val state = LayoutState(smallMode = false, contentArea = ContentArea.QWERTZ)
        val result = module.reduce(state, Action.LayoutAction.ToggleSmallMode, ctx())
        assertEquals(true, result!!.nextState.smallMode)
        assertEquals(
            "smallMode-enable must atomically clamp contentArea to MAIN_BUTTONS",
            ContentArea.MAIN_BUTTONS, result.nextState.contentArea,
        )
    }

    @Test
    fun `ToggleSmallMode false to true with EMOJI_PICKER also clamps`() {
        val state = LayoutState(smallMode = false, contentArea = ContentArea.EMOJI_PICKER)
        val result = module.reduce(state, Action.LayoutAction.ToggleSmallMode, ctx())
        assertEquals(ContentArea.MAIN_BUTTONS, result!!.nextState.contentArea)
    }

    @Test
    fun `ToggleSmallMode true to false leaves contentArea alone`() {
        // Disabling small mode does NOT force contentArea — the user
        // might re-open the sub-keyboard.
        val state = LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
        val result = module.reduce(state, Action.LayoutAction.ToggleSmallMode, ctx())
        assertEquals(false, result!!.nextState.smallMode)
        assertEquals(ContentArea.MAIN_BUTTONS, result.nextState.contentArea)
    }

    // ─── SetSmallMode ──────────────────────────────────────────────────

    @Test
    fun `SetSmallMode true with non-MAIN_BUTTONS atomically clamps`() {
        val state = LayoutState(smallMode = false, contentArea = ContentArea.QWERTZ)
        val result = module.reduce(state, Action.LayoutAction.SetSmallMode(enabled = true), ctx())
        assertEquals(true, result!!.nextState.smallMode)
        assertEquals(ContentArea.MAIN_BUTTONS, result.nextState.contentArea)
    }

    @Test
    fun `SetSmallMode same value returns null (idempotent)`() {
        assertNull(
            module.reduce(
                LayoutState(smallMode = true),
                Action.LayoutAction.SetSmallMode(enabled = true),
                ctx(),
            ),
        )
        assertNull(
            module.reduce(
                LayoutState(smallMode = false),
                Action.LayoutAction.SetSmallMode(enabled = false),
                ctx(),
            ),
        )
    }

    @Test
    fun `SetSmallMode false clears the flag without touching contentArea`() {
        val state = LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
        val result = module.reduce(state, Action.LayoutAction.SetSmallMode(enabled = false), ctx())
        assertEquals(false, result!!.nextState.smallMode)
        assertEquals(ContentArea.MAIN_BUTTONS, result.nextState.contentArea)
    }

    // ─── SetContentArea ────────────────────────────────────────────────

    @Test
    fun `SetContentArea while small-mode and non-MAIN_BUTTONS auto-exits small-mode`() {
        // Updated 2026-05-22 — the earlier "structural reject" of small +
        // non-MAIN_BUTTONS surfaced as "tap does nothing" (the user
        // couldn't reach emoji/QWERTZ from small-mode without first
        // toggling small-mode off). The reducer now atomically drops
        // small-mode AND sets the target area, with a Persist effect so
        // SharedPreferences agrees. Same atomic pair as ToggleSmallMode.
        val state = LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
        val result = module.reduce(
            state,
            Action.LayoutAction.SetContentArea(area = ContentArea.QWERTZ),
            ctx(),
        )
        assertEquals(false, result!!.nextState.smallMode)
        assertEquals(ContentArea.QWERTZ, result.nextState.contentArea)
        assertEquals(
            listOf(LayoutModule.Effect.PersistSmallMode(false)),
            result.sideEffects,
        )
    }

    @Test
    fun `SetContentArea EMOJI_PICKER while small-mode auto-exits small-mode`() {
        // Symmetric case — emoji target also drops small-mode.
        val state = LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
        val result = module.reduce(
            state,
            Action.LayoutAction.SetContentArea(area = ContentArea.EMOJI_PICKER),
            ctx(),
        )
        assertEquals(false, result!!.nextState.smallMode)
        assertEquals(ContentArea.EMOJI_PICKER, result.nextState.contentArea)
    }

    @Test
    fun `SetContentArea while small-mode allows MAIN_BUTTONS (no-op equal)`() {
        // small + MAIN_BUTTONS is fine, but it's also what we're at —
        // idempotent null.
        val state = LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
        assertNull(
            module.reduce(
                state,
                Action.LayoutAction.SetContentArea(area = ContentArea.MAIN_BUTTONS),
                ctx(),
            ),
        )
    }

    @Test
    fun `SetContentArea outside small-mode updates the field`() {
        val state = LayoutState(smallMode = false, contentArea = ContentArea.MAIN_BUTTONS)
        val result = module.reduce(
            state,
            Action.LayoutAction.SetContentArea(area = ContentArea.QWERTZ),
            ctx(),
        )
        assertEquals(ContentArea.QWERTZ, result!!.nextState.contentArea)
        assertEquals(false, result.nextState.smallMode)
    }

    @Test
    fun `SetContentArea with same value returns null`() {
        val state = LayoutState(smallMode = false, contentArea = ContentArea.QWERTZ)
        assertNull(
            module.reduce(
                state,
                Action.LayoutAction.SetContentArea(area = ContentArea.QWERTZ),
                ctx(),
            ),
        )
    }

    @Test
    fun `module id is Layout`() {
        assertEquals(ModuleId.Layout, module.id)
    }

    @Test
    fun `lens round-trip preserves layout axis`() {
        val state = DictateUiState.initial().copy(
            layout = LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS, singleRowMode = true),
        )
        assertEquals(
            LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS, singleRowMode = true),
            module.read(state),
        )
        val back = module.write(state, LayoutState())
        assertEquals(LayoutState(), back.layout)
    }

    @Test
    fun `initial state is default LayoutState`() {
        assertEquals(LayoutState(), module.initialState())
    }

    // ─── Pref-Persist Effects (indirection-cleanup A-1/A-2, 2026-05-21) ─

    @Test
    fun `ToggleSmallMode emits PersistSmallMode with new value`() {
        val state = LayoutState(smallMode = false)
        val result = module.reduce(state, Action.LayoutAction.ToggleSmallMode, ctx())
        assertEquals(
            listOf(LayoutModule.Effect.PersistSmallMode(true)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `ToggleSmallMode true to false emits PersistSmallMode(false)`() {
        val state = LayoutState(smallMode = true)
        val result = module.reduce(state, Action.LayoutAction.ToggleSmallMode, ctx())
        assertEquals(
            listOf(LayoutModule.Effect.PersistSmallMode(false)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `SetSmallMode(true) emits PersistSmallMode(true)`() {
        val state = LayoutState(smallMode = false)
        val result = module.reduce(state, Action.LayoutAction.SetSmallMode(enabled = true), ctx())
        assertEquals(
            listOf(LayoutModule.Effect.PersistSmallMode(true)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `SetSmallMode idempotent emits no effects (null result)`() {
        // No state-change → null → no effect. Persistence guard: a stale
        // SP write would re-trigger the mirror for no reason.
        assertNull(
            module.reduce(
                LayoutState(smallMode = true),
                Action.LayoutAction.SetSmallMode(enabled = true),
                ctx(),
            ),
        )
    }

    @Test
    fun `ToggleSingleRowMode emits PersistSingleRowMode with new value`() {
        val state = LayoutState(singleRowMode = false)
        val result = module.reduce(state, Action.LayoutAction.ToggleSingleRowMode, ctx())
        assertEquals(
            listOf(LayoutModule.Effect.PersistSingleRowMode(true)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `ToggleSingleRowMode true to false emits PersistSingleRowMode(false)`() {
        val state = LayoutState(singleRowMode = true)
        val result = module.reduce(state, Action.LayoutAction.ToggleSingleRowMode, ctx())
        assertEquals(
            listOf(LayoutModule.Effect.PersistSingleRowMode(false)),
            result!!.sideEffects,
        )
    }

    @Test
    fun `SetContentArea emits no side-effects (no persist axis)`() {
        // contentArea is not Pref-mirrored — purely transient. The arm
        // returns an empty effects list (not a PersistContentArea effect).
        val state = LayoutState(smallMode = false, contentArea = ContentArea.MAIN_BUTTONS)
        val result = module.reduce(
            state,
            Action.LayoutAction.SetContentArea(area = ContentArea.QWERTZ),
            ctx(),
        )
        assertEquals(emptyList<LayoutModule.Effect>(), result!!.sideEffects)
    }
}
