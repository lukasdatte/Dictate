package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer tests for [ResendModule].
 *
 * Coverage:
 * - ResendLastAudio / ResendLastAudioLong arms cooldown when off
 * - Second click while cooldown=true is silent no-op (returns null)
 * - ResendCooldownExpired clears cooldown
 * - MarkLastAudio toggles lastAudioExists idempotently
 * - Lens round-trip + id + initial state
 */
class ResendModuleTest {

    private val module = ResendModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `ResendLastAudio with cooldown off arms cooldown`() {
        val state = ResendState(resendCooldown = false)
        val result = module.reduce(state, Action.ResendAction.ResendLastAudio, ctx())
        assertEquals(true, result!!.nextState.resendCooldown)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `ResendLastAudio with cooldown on returns null (silent no-op)`() {
        val state = ResendState(resendCooldown = true)
        assertNull(module.reduce(state, Action.ResendAction.ResendLastAudio, ctx()))
    }

    @Test
    fun `ResendLastAudioLong with cooldown off arms cooldown`() {
        val state = ResendState(resendCooldown = false)
        val result = module.reduce(state, Action.ResendAction.ResendLastAudioLong, ctx())
        assertEquals(true, result!!.nextState.resendCooldown)
    }

    @Test
    fun `ResendLastAudioLong with cooldown on is silent no-op`() {
        val state = ResendState(resendCooldown = true)
        assertNull(module.reduce(state, Action.ResendAction.ResendLastAudioLong, ctx()))
    }

    @Test
    fun `ResendCooldownExpired clears cooldown`() {
        val state = ResendState(resendCooldown = true)
        val result = module.reduce(state, Action.ResendAction.ResendCooldownExpired, ctx())
        assertEquals(false, result!!.nextState.resendCooldown)
    }

    @Test
    fun `ResendCooldownExpired while not on cooldown is no-op`() {
        val state = ResendState(resendCooldown = false)
        assertNull(module.reduce(state, Action.ResendAction.ResendCooldownExpired, ctx()))
    }

    @Test
    fun `MarkLastAudio true updates lastAudioExists`() {
        val state = ResendState(lastAudioExists = false)
        val result = module.reduce(state, Action.ResendAction.MarkLastAudio(exists = true), ctx())
        assertEquals(true, result!!.nextState.lastAudioExists)
    }

    @Test
    fun `MarkLastAudio with same value returns null (idempotent)`() {
        val state = ResendState(lastAudioExists = true)
        assertNull(module.reduce(state, Action.ResendAction.MarkLastAudio(exists = true), ctx()))
    }

    @Test
    fun `module id is Resend`() {
        assertEquals(ModuleId.Resend, module.id)
    }

    @Test
    fun `lens round-trip preserves resend axis`() {
        val state = DictateUiState.initial().copy(
            resend = ResendState(lastAudioExists = true, resendEnabled = true, resendCooldown = false),
        )
        val sub = module.read(state)
        assertEquals(ResendState(lastAudioExists = true, resendEnabled = true, resendCooldown = false), sub)
        val back = module.write(state, ResendState())
        assertEquals(ResendState(), back.resend)
    }

    @Test
    fun `initial state is default ResendState`() {
        assertEquals(ResendState(), module.initialState())
    }

    // ────────────────────────────────────────────────────────────────
    // F-1 — NotifyManualPasteNeeded + ClearManualPasteFlag
    //
    // The flag lives on ResendState (relocated from a top-level
    // DictateUiState field per `research/manual-paste-field-architecture.md`).
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `NotifyManualPasteNeeded flips lastResultNeedsManualPaste from false to true`() {
        val state = ResendState()
        val result = module.reduce(
            state,
            Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-1"),
            ctx(),
        )
        // B3-VAL-W1 F-14: the data shape carries both — alias Boolean
        // mirrors set-non-empty.
        assertEquals(true, result!!.nextState.lastResultNeedsManualPaste)
        assertEquals(setOf("sid-1"), result.nextState.pendingPasteSessionIds)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `NotifyManualPasteNeeded is idempotent when sessionId already pending`() {
        val state = ResendState(
            lastResultNeedsManualPaste = true,
            pendingPasteSessionIds = setOf("sid-1"),
        )
        assertNull(
            module.reduce(
                state,
                Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-1"),
                ctx(),
            ),
        )
    }

    @Test
    fun `NotifyManualPasteNeeded adds distinct sessionIds to the set`() {
        // B3-VAL-W1 F-14: pre-fix N-1 of N sessions were silently
        // dropped because the Boolean swallowed re-dispatch. With the
        // per-session set the second dispatch is no longer idempotent
        // — it records the additional sessionId.
        val state = ResendState(
            lastResultNeedsManualPaste = true,
            pendingPasteSessionIds = setOf("sid-1"),
        )
        val result = module.reduce(
            state,
            Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-2"),
            ctx(),
        )
        assertEquals(setOf("sid-1", "sid-2"), result!!.nextState.pendingPasteSessionIds)
        assertEquals(true, result.nextState.lastResultNeedsManualPaste)
    }

    @Test
    fun `ClearManualPasteFlag flips lastResultNeedsManualPaste from true to false`() {
        val state = ResendState(
            lastResultNeedsManualPaste = true,
            pendingPasteSessionIds = setOf("sid-1"),
        )
        val result = module.reduce(
            state,
            Action.ResendAction.ClearManualPasteFlag,
            ctx(),
        )
        assertEquals(false, result!!.nextState.lastResultNeedsManualPaste)
        // Clear-all semantic preserved (per F-14 contract).
        assertTrue(result.nextState.pendingPasteSessionIds.isEmpty())
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `ClearManualPasteFlag is idempotent when already clear`() {
        val state = ResendState()
        assertNull(
            module.reduce(
                state,
                Action.ResendAction.ClearManualPasteFlag,
                ctx(),
            ),
        )
    }
}
