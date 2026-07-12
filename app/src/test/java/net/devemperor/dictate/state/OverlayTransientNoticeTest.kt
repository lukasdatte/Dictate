package net.devemperor.dictate.state

import net.devemperor.dictate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the reusable [OverlayModule] transient-notice primitive
 * ("OverlayTransientNotice") and its hover-send trigger.
 *
 * Decision 3 (`docs/research/2026-07-11 - widget-mode-parity-and-third-row.md`):
 * a hover-send (a send committed to the pipeline while the IME-View is
 * hidden) defers its transcript to a pending part; the overlay surfaces a
 * transient "text will be inserted next time" notice for ~3.5 s.
 *
 * The primitive is state-driven (no `android.widget.Toast`): a
 * monotonically-increasing token distinguishes a live notice from a stale
 * expiry, so an older expiry never clears a newer notice and a second Show
 * before expiry wins.
 */
class OverlayTransientNoticeTest {

    private val module = OverlayModule
    private fun ctx(global: DictateUiState = DictateUiState.initial()) =
        ReducerContext(global = global)

    // ─── Reducer: Show ──────────────────────────────────────────────────

    @Test
    fun `ShowTransientNotice sets the notice and schedules expiry with the same token`() {
        val result = module.reduce(
            OverlayState(),
            Action.OverlayAction.ShowTransientNotice(
                textRes = R.string.overlay_notice_pending_insert,
                durationMs = 3500L,
            ),
            ctx(),
        )
        val notice = result!!.nextState.transientNotice!!
        assertEquals(R.string.overlay_notice_pending_insert, notice.textRes)
        val effect = result.sideEffects
            .filterIsInstance<OverlayModule.Effect.ScheduleNoticeExpiry>()
            .single()
        assertEquals(
            "the scheduled expiry must carry the notice's token",
            notice.token,
            effect.token,
        )
        assertEquals(3500L, effect.durationMs)
    }

    // ─── Reducer: Expire — matching token clears ────────────────────────

    @Test
    fun `matching-token ExpireTransientNotice clears the notice`() {
        val shown = module.reduce(
            OverlayState(),
            Action.OverlayAction.ShowTransientNotice(R.string.overlay_notice_pending_insert, 3500L),
            ctx(),
        )!!.nextState
        val token = shown.transientNotice!!.token

        val cleared = module.reduce(
            shown,
            Action.OverlayAction.ExpireTransientNotice(token),
            ctx(),
        )
        assertNull("matching token must clear the notice", cleared!!.nextState.transientNotice)
    }

    // ─── Reducer: Expire — stale token ignored ──────────────────────────

    @Test
    fun `stale-token ExpireTransientNotice is ignored (older expiry never clears a newer notice)`() {
        // A newer notice is live (token 2); a stale expiry for token 1 fires.
        val live = OverlayState(
            transientNotice = TransientNotice(R.string.overlay_notice_pending_insert, token = 2L),
        )
        val result = module.reduce(
            live,
            Action.OverlayAction.ExpireTransientNotice(token = 1L),
            ctx(),
        )
        // null result (reducer-null / not-relevant) — the notice survives.
        assertNull("stale expiry must be a no-op (returns null)", result)
    }

    // ─── Reducer: second Show before expiry wins ────────────────────────

    @Test
    fun `a second Show before expiry replaces the notice with a strictly-newer token`() {
        val first = module.reduce(
            OverlayState(),
            Action.OverlayAction.ShowTransientNotice(R.string.overlay_notice_pending_insert, 3500L),
            ctx(),
        )!!.nextState
        val second = module.reduce(
            first,
            Action.OverlayAction.ShowTransientNotice(R.string.overlay_notice_pending_insert, 3500L),
            ctx(),
        )!!.nextState
        assertTrue(
            "the second Show must mint a strictly-greater token so the first's " +
                "expiry becomes stale",
            second.transientNotice!!.token > first.transientNotice!!.token,
        )
    }

    // ─── Trigger: hover-send emits the notice ───────────────────────────

    @Test
    fun `hover-send (pipeline Idle to Preparing while IME hidden) emits ShowTransientNotice`() {
        val prev = DictateUiState.initial().copy(
            imeViewVisible = false,
            pipeline = PipelineUiState.Idle,
        )
        val next = prev.copy(pipeline = PipelineUiState.Preparing("sid-hover"))

        val cascade = module.onCrossModuleStateChange(prev, next)
        val show = cascade.filterIsInstance<Action.OverlayAction.ShowTransientNotice>().single()
        assertEquals(R.string.overlay_notice_pending_insert, show.textRes)
    }

    // ─── Trigger: keyboard-visible send does NOT emit ───────────────────

    @Test
    fun `keyboard-visible send (pipeline Idle to Preparing while IME visible) does NOT emit the notice`() {
        val prev = DictateUiState.initial().copy(
            imeViewVisible = true,
            pipeline = PipelineUiState.Idle,
        )
        val next = prev.copy(pipeline = PipelineUiState.Preparing("sid-kbd"))

        val cascade = module.onCrossModuleStateChange(prev, next)
        assertTrue(
            "a send with the keyboard visible commits immediately — no notice",
            cascade.none { it is Action.OverlayAction.ShowTransientNotice },
        )
    }
}
