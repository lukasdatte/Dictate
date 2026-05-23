package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [InterruptionModule] (Phase-2 stub).
 *
 * The Phase-1 stub rejects every inbound action — see module KDoc.
 * Coverage:
 * - All three [Action.InterruptionAction] variants return null
 * - Initial state is null (axis not modelled in Phase 1)
 * - id + lens round-trip (the lens still works — it carries `null`)
 */
class InterruptionModuleTest {

    private val module = InterruptionModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `PhoneCallStateChanged returns null in Phase 1`() {
        assertNull(
            module.reduce(
                null,
                Action.InterruptionAction.PhoneCallStateChanged(incoming = true),
                ctx(),
            ),
        )
    }

    @Test
    fun `HeadsetPlugChanged returns null in Phase 1`() {
        assertNull(
            module.reduce(
                null,
                Action.InterruptionAction.HeadsetPlugChanged(plugged = false),
                ctx(),
            ),
        )
    }

    @Test
    fun `ScreenStateChanged returns null in Phase 1`() {
        assertNull(
            module.reduce(
                null,
                Action.InterruptionAction.ScreenStateChanged(awake = false),
                ctx(),
            ),
        )
    }

    @Test
    fun `module id is Interruption`() {
        assertEquals(ModuleId.Interruption, module.id)
    }

    @Test
    fun `initial state is null (axis unmodelled in Phase 1)`() {
        assertNull(module.initialState())
    }

    @Test
    fun `lens round-trip preserves the null axis`() {
        val state = DictateUiState.initial()
        assertNull(module.read(state))
        val custom = InterruptionState(callIncoming = true)
        val withInterruption = module.write(state, custom)
        assertEquals(custom, withInterruption.interruption)
    }
}
