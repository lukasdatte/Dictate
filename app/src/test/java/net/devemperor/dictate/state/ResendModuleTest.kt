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
}
