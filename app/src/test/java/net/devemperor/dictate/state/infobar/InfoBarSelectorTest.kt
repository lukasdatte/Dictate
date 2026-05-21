package net.devemperor.dictate.state.infobar

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Block-C skeleton of [InfoBarSelector] and the
 * supporting data types ([InfoBarItem], [InfoBarMessage], [InfoBarStyle]).
 *
 * **What this suite verifies today:**
 *
 *  - Selector contract (purity, empty default, deterministic order).
 *  - Item/Message data-class invariants (equality, copy with default
 *    extras).
 *
 * **What this suite does NOT verify yet:**
 *
 *  - Production producer logic — Block D will add tests for the nine
 *    legacy cases and the overlay-permission onboarding hint.
 *  - Pending-Insert / Pending-Recording / Recovery / API-Key tests —
 *    Block E will add them once the corresponding state axes exist.
 *  - Renderer behaviour (visibility mutex, click→dispatch) — Block D
 *    introduces the renderer together with the first producer.
 *
 * The empty-case tests here are load-bearing: they fix the
 * "no-trigger → no-items" contract before any producer enters the
 * code, so a future producer breaking that contract surfaces as a
 * regression here rather than at the renderer.
 */
class InfoBarSelectorTest {

    private fun defaultState(): DictateUiState = DictateUiState.initial()

    @Test
    fun `selector returns empty list on default state`() {
        assertTrue(InfoBarSelector.select(defaultState()).isEmpty())
    }

    @Test
    fun `selector is deterministic — two calls return equal list`() {
        val state = defaultState()
        assertEquals(InfoBarSelector.select(state), InfoBarSelector.select(state))
    }

    @Test
    fun `InfoBarItem with same id and content is equal`() {
        val a = InfoBarItem(
            id = "x",
            createdAt = 1L,
            message = InfoBarMessage(textResId = 42),
            dismissAction = Action.OverlayAction.DismissOverlayOnboarding,
        )
        val b = InfoBarItem(
            id = "x",
            createdAt = 1L,
            message = InfoBarMessage(textResId = 42),
            dismissAction = Action.OverlayAction.DismissOverlayOnboarding,
        )
        assertEquals(a, b)
    }

    @Test
    fun `InfoBarItem confirmAction defaults to null`() {
        val item = InfoBarItem(
            id = "x",
            createdAt = 1L,
            message = InfoBarMessage(textResId = 42),
            dismissAction = Action.OverlayAction.DismissOverlayOnboarding,
        )
        assertNull("confirmAction defaults to null for dismiss-only items", item.confirmAction)
    }

    @Test
    fun `InfoBarMessage defaults are INFO style + empty args`() {
        val msg = InfoBarMessage(textResId = 42)
        assertEquals(InfoBarStyle.INFO, msg.style)
        assertEquals(emptyList<Any>(), msg.textArgs)
    }

    @Test
    fun `InfoBarMessage carries textArgs for parameterised strings`() {
        val msg = InfoBarMessage(
            textResId = 42,
            textArgs = listOf("OpenAI"),
            style = InfoBarStyle.ERROR,
        )
        assertEquals(listOf<Any>("OpenAI"), msg.textArgs)
        assertEquals(InfoBarStyle.ERROR, msg.style)
    }

    // ── Overlay-Permission-Onboarding producer (Block D) ───────────────

    @Test
    fun `overlay onboardingPending surfaces a single info item`() {
        val state = defaultState().copy(
            overlay = defaultState().overlay.copy(onboardingPending = true),
        )
        val items = InfoBarSelector.select(state)
        assertEquals("expected exactly one item", 1, items.size)
        val item = items.first()
        assertEquals("overlay-permission:onboarding", item.id)
        assertEquals(Action.OverlayAction.RequestOverlayPermission, item.confirmAction)
        assertEquals(Action.OverlayAction.DismissOverlayOnboarding, item.dismissAction)
        assertEquals(InfoBarStyle.INFO, item.message.style)
    }

    @Test
    fun `overlay onboardingPending false produces empty list`() {
        val state = defaultState().copy(
            overlay = defaultState().overlay.copy(onboardingPending = false),
        )
        assertTrue(InfoBarSelector.select(state).isEmpty())
    }

    @Test
    fun `overlay onboarding item pinned to top with createdAt 0`() {
        // Block D.2 + E will add later-timestamped items; the
        // overlay-onboarding item must sort first so its explainer
        // outranks transient errors. This test fixes that invariant
        // before later producers can accidentally invert it.
        val state = defaultState().copy(
            overlay = defaultState().overlay.copy(onboardingPending = true),
        )
        val item = InfoBarSelector.select(state).first()
        assertEquals(0L, item.createdAt)
    }
}
