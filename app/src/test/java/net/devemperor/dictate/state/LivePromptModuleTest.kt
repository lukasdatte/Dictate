package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [LivePromptModule].
 *
 * Coverage:
 * - EnableLivePrompt flips `enabled` to true (idempotent if already)
 * - DisableLivePrompt flips back AND drops any pending chain
 * - ChainNext consumes the pending bit; rejected if no chain pending
 * - Lens + id + initial state
 */
class LivePromptModuleTest {

    private val module = LivePromptModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `EnableLivePrompt sets enabled true`() {
        val result = module.reduce(LivePromptState(enabled = false), Action.LivePromptAction.EnableLivePrompt, ctx())
        assertEquals(true, result!!.nextState.enabled)
    }

    @Test
    fun `EnableLivePrompt when already enabled is no-op`() {
        assertNull(module.reduce(LivePromptState(enabled = true), Action.LivePromptAction.EnableLivePrompt, ctx()))
    }

    @Test
    fun `DisableLivePrompt clears enabled AND pendingChain`() {
        val state = LivePromptState(enabled = true, pendingChain = true)
        val result = module.reduce(state, Action.LivePromptAction.DisableLivePrompt, ctx())
        assertEquals(false, result!!.nextState.enabled)
        assertFalse(result.nextState.pendingChain)
    }

    @Test
    fun `DisableLivePrompt when already disabled is no-op`() {
        assertNull(module.reduce(LivePromptState(enabled = false), Action.LivePromptAction.DisableLivePrompt, ctx()))
    }

    @Test
    fun `ChainNext consumes the pending bit`() {
        val state = LivePromptState(enabled = true, pendingChain = true)
        val result = module.reduce(state, Action.LivePromptAction.ChainNext(text = "next"), ctx())
        assertEquals(false, result!!.nextState.pendingChain)
        assertEquals(true, result.nextState.enabled)   // enabled untouched
    }

    @Test
    fun `ChainNext when no chain is pending is no-op`() {
        val state = LivePromptState(enabled = true, pendingChain = false)
        assertNull(module.reduce(state, Action.LivePromptAction.ChainNext(text = "x"), ctx()))
    }

    @Test
    fun `module id is LivePrompt`() {
        assertEquals(ModuleId.LivePrompt, module.id)
    }

    @Test
    fun `lens round-trip preserves livePrompt axis`() {
        val state = DictateUiState.initial().copy(
            livePrompt = LivePromptState(enabled = true, pendingChain = true),
        )
        assertEquals(LivePromptState(enabled = true, pendingChain = true), module.read(state))
        val back = module.write(state, LivePromptState())
        assertEquals(LivePromptState(), back.livePrompt)
    }

    @Test
    fun `initial state is default LivePromptState`() {
        assertEquals(LivePromptState(), module.initialState())
    }
}
