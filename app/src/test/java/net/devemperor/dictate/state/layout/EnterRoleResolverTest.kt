package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.HostEditorState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the Enter-button role/action/icon resolvers
 * (`docs/plans/2026-05-23 - dictate-enter-button-host-action` Edge-Case-Tabelle).
 *
 * The 12 plan rows are mirrored 1:1 below, each verifying
 * [resolveEnterRole], [actionIdForEnter], and [resolveEnterIcon]
 * together so a regression in any of the three breaks the same test.
 *
 * Priority guards (`hasNoEnterAction` > `isMultiLine` > `customActionId`
 * > `imeActionId`) are additionally verified by dedicated tests so a
 * future re-ordering breaks the most-specific test first.
 */
class EnterRoleResolverTest {

    private fun host(
        imeAction: Int = 0,
        customActionId: Int = 0,
        hasNoEnterAction: Boolean = false,
        isMultiLine: Boolean = false,
        hasEditorInfo: Boolean = true,
    ) = HostEditorState(
        imeActionId = imeAction,
        customActionId = customActionId,
        hasNoEnterAction = hasNoEnterAction,
        isMultiLine = isMultiLine,
        hasEditorInfo = hasEditorInfo,
    )

    // ─── Edge-Case-Tabelle ───────────────────────────────────────────

    @Test
    fun `row 1 — browser GO`() {
        val h = host(imeAction = IME_ACTION_GO)
        assertEquals(EnterButtonRole.GO, resolveEnterRole(h))
        assertEquals(IME_ACTION_GO, actionIdForEnter(EnterButtonRole.GO, h))
        assertEquals(R.drawable.ic_baseline_send_20, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 2 — multi-line textarea`() {
        val h = host(imeAction = IME_ACTION_UNSPECIFIED, isMultiLine = true)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
        assertEquals(0, actionIdForEnter(EnterButtonRole.NEWLINE, h))
        assertEquals(R.drawable.ic_baseline_subdirectory_arrow_left_24, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 3 — input without action`() {
        val h = host(imeAction = IME_ACTION_UNSPECIFIED)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
        assertEquals(R.drawable.ic_baseline_subdirectory_arrow_left_24, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 4 — chat SEND`() {
        val h = host(imeAction = IME_ACTION_SEND)
        assertEquals(EnterButtonRole.SEND, resolveEnterRole(h))
        assertEquals(IME_ACTION_SEND, actionIdForEnter(EnterButtonRole.SEND, h))
        assertEquals(R.drawable.ic_baseline_send_20, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 5 — search SEARCH`() {
        val h = host(imeAction = IME_ACTION_SEARCH)
        assertEquals(EnterButtonRole.SEARCH, resolveEnterRole(h))
        assertEquals(IME_ACTION_SEARCH, actionIdForEnter(EnterButtonRole.SEARCH, h))
        assertEquals(R.drawable.ic_baseline_send_20, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 6 — IME_FLAG_NO_ENTER_ACTION overrides SEND`() {
        val h = host(imeAction = IME_ACTION_SEND, hasNoEnterAction = true)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
        assertEquals(R.drawable.ic_baseline_subdirectory_arrow_left_24, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 7 — custom action`() {
        val h = host(customActionId = 42, imeAction = IME_ACTION_SEND)
        assertEquals(EnterButtonRole.CUSTOM, resolveEnterRole(h))
        assertEquals(42, actionIdForEnter(EnterButtonRole.CUSTOM, h))
        assertEquals(R.drawable.ic_baseline_send_20, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 8 — form NEXT`() {
        val h = host(imeAction = IME_ACTION_NEXT)
        assertEquals(EnterButtonRole.NEXT, resolveEnterRole(h))
        assertEquals(IME_ACTION_NEXT, actionIdForEnter(EnterButtonRole.NEXT, h))
        assertEquals(R.drawable.ic_baseline_send_20, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 9 — single-line DONE`() {
        val h = host(imeAction = IME_ACTION_DONE)
        assertEquals(EnterButtonRole.DONE, resolveEnterRole(h))
        assertEquals(IME_ACTION_DONE, actionIdForEnter(EnterButtonRole.DONE, h))
        assertEquals(R.drawable.ic_baseline_check_24, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 10 — no editor info pre-bind`() {
        val h = host(hasEditorInfo = false)
        // resolveEnterRole doesn't branch on hasEditorInfo (the Effect
        // layer does via Effect.SendPhysicalEnter), so the role-only
        // resolver still maps UNSPECIFIED → NEWLINE. The icon resolver
        // additionally falls back to the Return-arrow.
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
        assertEquals(R.drawable.ic_baseline_subdirectory_arrow_left_24, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 11 — multi-line plus SEND overrides to NEWLINE`() {
        val h = host(imeAction = IME_ACTION_SEND, isMultiLine = true)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
        assertEquals(R.drawable.ic_baseline_subdirectory_arrow_left_24, resolveEnterIcon(stateWith(h)))
    }

    @Test
    fun `row 12 — PREVIOUS`() {
        val h = host(imeAction = IME_ACTION_PREVIOUS)
        assertEquals(EnterButtonRole.PREVIOUS, resolveEnterRole(h))
        assertEquals(IME_ACTION_PREVIOUS, actionIdForEnter(EnterButtonRole.PREVIOUS, h))
        assertEquals(R.drawable.ic_baseline_send_20, resolveEnterIcon(stateWith(h)))
    }

    // ─── Priority guards ─────────────────────────────────────────────

    @Test
    fun `hasNoEnterAction dominates customActionId`() {
        val h = host(customActionId = 42, hasNoEnterAction = true)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
    }

    @Test
    fun `isMultiLine dominates customActionId`() {
        val h = host(customActionId = 42, isMultiLine = true)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
    }

    @Test
    fun `customActionId dominates imeActionId`() {
        val h = host(customActionId = 99, imeAction = IME_ACTION_GO)
        assertEquals(EnterButtonRole.CUSTOM, resolveEnterRole(h))
        assertEquals(99, actionIdForEnter(EnterButtonRole.CUSTOM, h))
    }

    @Test
    fun `unknown imeAction falls back to NEWLINE`() {
        val h = host(imeAction = 99)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
    }

    @Test
    fun `IME_ACTION_NONE explicit no-action maps to NEWLINE`() {
        val h = host(imeAction = IME_ACTION_NONE)
        assertEquals(EnterButtonRole.NEWLINE, resolveEnterRole(h))
    }

    // ─── resolveEnterAction — host-commit guard ──────────────────────

    @Test
    fun `resolveEnterAction returns EnterKey when canCommitToHost`() {
        val state = net.devemperor.dictate.state.DictateUiState.initial()
            .copy(imeViewVisible = true)
        val action = resolveEnterAction(state, fakeServices())
        assertEquals(net.devemperor.dictate.state.Action.KeyboardInputAction.EnterKey, action)
    }

    @Test
    fun `resolveEnterAction returns null when IME view hidden`() {
        val state = net.devemperor.dictate.state.DictateUiState.initial()
            .copy(imeViewVisible = false)
        assertEquals(null, resolveEnterAction(state, fakeServices()))
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private fun stateWith(host: HostEditorState) = net.devemperor.dictate.state.DictateUiState.initial()
        .copy(
            keyboardInput = net.devemperor.dictate.state.KeyboardInputState(hostEditor = host),
        )

    private fun fakeServices(): net.devemperor.dictate.state.ModuleServices =
        net.devemperor.dictate.testutil.fakeModuleServices()
}
