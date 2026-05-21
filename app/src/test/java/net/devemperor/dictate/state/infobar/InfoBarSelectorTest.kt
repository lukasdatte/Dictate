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
        // Block E adds later-timestamped pending items; the overlay-
        // onboarding item must sort first so its explainer outranks
        // transient errors. This test fixes the invariant.
        val state = defaultState().copy(
            overlay = defaultState().overlay.copy(onboardingPending = true),
        )
        val item = InfoBarSelector.select(state).first()
        assertEquals(0L, item.createdAt)
    }

    // ── Pending-Insert / Pending-Recording producers (Block E) ─────────

    @Test
    fun `pending COMPLETED session with text surfaces a pending-insert item`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "hello world",
            createdAt = 1_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val items = InfoBarSelector.select(state)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("pending-insert:abc", item.id)
        assertEquals(
            Action.PendingSessionsAction.AcceptAndInsert("abc"),
            item.confirmAction,
        )
        assertEquals(
            Action.PendingSessionsAction.Dismiss("abc"),
            item.dismissAction,
        )
        assertEquals(InfoBarStyle.ACTION, item.message.style)
    }

    @Test
    fun `pending COMPLETED session without text does not surface an item`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = null,
            createdAt = 1_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        assertTrue(InfoBarSelector.select(state).isEmpty())
    }

    @Test
    fun `pending RECORDED session surfaces a dismiss-only item`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "xyz",
            status = net.devemperor.dictate.database.entity.SessionStatus.RECORDED,
            transcribedText = null,
            createdAt = 2_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val items = InfoBarSelector.select(state)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("pending-recording:xyz", item.id)
        assertNull("MVP — Pending-Recording has no confirm action", item.confirmAction)
        assertEquals(
            Action.PendingSessionsAction.Dismiss("xyz"),
            item.dismissAction,
        )
    }

    @Test
    fun `pending items sort ascending by createdAt`() {
        val s1 = net.devemperor.dictate.state.PendingSession(
            "a", net.devemperor.dictate.database.entity.SessionStatus.COMPLETED, "x", 3_000L,
        )
        val s2 = net.devemperor.dictate.state.PendingSession(
            "b", net.devemperor.dictate.database.entity.SessionStatus.RECORDED, null, 1_000L,
        )
        val s3 = net.devemperor.dictate.state.PendingSession(
            "c", net.devemperor.dictate.database.entity.SessionStatus.COMPLETED, "y", 2_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(s1, s2, s3),
        )
        val items = InfoBarSelector.select(state)
        assertEquals(
            listOf("pending-recording:b", "pending-insert:c", "pending-insert:a"),
            items.map { it.id },
        )
    }

    // ── B4: Pending-Insert text preview ────────────────────────────────

    @Test
    fun `B4 pending-insert short text uses preview msg with full text as arg`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "hello world",
            createdAt = 1_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val item = InfoBarSelector.select(state).first { it.id == "pending-insert:abc" }
        assertEquals(
            net.devemperor.dictate.R.string.dictate_pending_insert_msg_preview,
            item.message.textResId,
        )
        assertEquals(listOf<Any>("hello world"), item.message.textArgs)
    }

    @Test
    fun `B4 pending-insert long text truncates to 60 chars with ellipsis`() {
        val longText = "A very long transcript that exceeds the sixty character preview limit by several extra words"
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = longText,
            createdAt = 1_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val item = InfoBarSelector.select(state).first { it.id == "pending-insert:abc" }
        val preview = item.message.textArgs.first() as String
        assertTrue(
            "Preview must end with ellipsis when truncated: was '$preview'",
            preview.endsWith("…"),
        )
        assertTrue(
            "Preview length (incl. ellipsis) must be ≤ 61: was ${preview.length}",
            preview.length <= 61,
        )
    }

    @Test
    fun `B4 pending-insert empty trimmed text falls back to generic msg`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "   \n   ",
            createdAt = 1_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val item = InfoBarSelector.select(state).first { it.id == "pending-insert:abc" }
        assertEquals(
            "All-whitespace transcribedText falls back to the legacy generic message",
            net.devemperor.dictate.R.string.dictate_pending_insert_msg,
            item.message.textResId,
        )
        assertTrue(item.message.textArgs.isEmpty())
    }

    @Test
    fun `B4 pending-insert preview replaces newlines with single spaces`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "first line\n\n  second line",
            createdAt = 1_000L,
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val item = InfoBarSelector.select(state).first { it.id == "pending-insert:abc" }
        assertEquals(
            listOf<Any>("first line second line"),
            item.message.textArgs,
        )
    }

    // ── B4: Partial-Recovery producer ──────────────────────────────────

    @Test
    fun `B4 partial-recovery surfaces ERROR item with seconds arg`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "hello world",
            createdAt = 2_000L,
            lastErrorMessage = "partial:7",
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val items = InfoBarSelector.select(state)
        val partial = items.first { it.id == "partial-recovery:abc" }
        assertEquals(
            net.devemperor.dictate.R.string.dictate_recovery_partial_msg,
            partial.message.textResId,
        )
        assertEquals(listOf<Any>(7), partial.message.textArgs)
        assertEquals(InfoBarStyle.ERROR, partial.message.style)
        assertNull(partial.confirmAction)
        assertEquals(
            Action.PendingSessionsAction.Dismiss("abc"),
            partial.dismissAction,
        )
    }

    @Test
    fun `B4 partial-recovery NOT surfaced when lastErrorMessage has no marker`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "hello world",
            createdAt = 2_000L,
            lastErrorMessage = "some other error - not a partial-marker",
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        assertTrue(
            "lastErrorMessage without `partial:<N>` marker must NOT surface partial-recovery",
            InfoBarSelector.select(state).none { it.id.startsWith("partial-recovery") },
        )
    }

    @Test
    fun `B4 partial-recovery tolerates embedded marker with surrounding context`() {
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "hello world",
            createdAt = 2_000L,
            lastErrorMessage = "concat warning - partial:12 segments=3",
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val partial = InfoBarSelector.select(state).first { it.id.startsWith("partial-recovery") }
        assertEquals(listOf<Any>(12), partial.message.textArgs)
    }

    @Test
    fun `B4 partial-recovery and pending-insert co-exist for the same session`() {
        // The two producers run independently; a session that has both
        // a transcribed text AND a partial-marker generates two stacked
        // info-bar items (one Pending-Insert, one Partial-Recovery).
        val session = net.devemperor.dictate.state.PendingSession(
            sessionId = "abc",
            status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
            transcribedText = "hello",
            createdAt = 2_000L,
            lastErrorMessage = "partial:5",
        )
        val state = defaultState().copy(
            pendingSessions = kotlinx.collections.immutable.persistentListOf(session),
        )
        val items = InfoBarSelector.select(state).map { it.id }
        assertTrue(items.contains("pending-insert:abc"))
        assertTrue(items.contains("partial-recovery:abc"))
    }
}
