package net.devemperor.dictate.state

import net.devemperor.dictate.state.layout.EnterButtonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer tests for [KeyboardInputModule].
 *
 * Coverage:
 * - Each of the four trivial input actions translates 1:1 to the matching Effect
 * - `HostEditorAttached`/`HostEditorDetached` write through the lens
 * - `EnterKey` reduces to the right `PerformEnter` flavour for every
 *   row of the Plan §"Edge-Case-Tabelle"
 * - Lens read/write round-trips
 * - id + initial state
 */
class KeyboardInputModuleTest {

    private val module = KeyboardInputModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())
    private fun initial() = KeyboardInputState()
    private fun stateWith(host: HostEditorState) = KeyboardInputState(hostEditor = host)
    private fun boundHost(
        imeAction: Int = 0,
        customActionId: Int = 0,
        hasNoEnterAction: Boolean = false,
        isMultiLine: Boolean = false,
    ) = HostEditorState(
        imeActionId = imeAction,
        customActionId = customActionId,
        hasNoEnterAction = hasNoEnterAction,
        isMultiLine = isMultiLine,
        hasEditorInfo = true,
    )

    // ─── trivial effect-only actions ─────────────────────────────────

    @Test
    fun `Backspace emits SendBackspace effect`() {
        val result = module.reduce(initial(), Action.KeyboardInputAction.Backspace, ctx())
        assertEquals(initial(), result!!.nextState)
        assertTrue(result.sideEffects.contains(KeyboardInputModule.Effect.SendBackspace))
    }

    @Test
    fun `SpaceKey emits SendSpace effect`() {
        val result = module.reduce(initial(), Action.KeyboardInputAction.SpaceKey, ctx())
        assertTrue(result!!.sideEffects.contains(KeyboardInputModule.Effect.SendSpace))
    }

    @Test
    fun `CopyToClipboard emits the typed effect with the same text payload`() {
        val result = module.reduce(
            initial(),
            Action.KeyboardInputAction.CopyToClipboard("hello"),
            ctx(),
        )
        assertTrue(
            result!!.sideEffects.contains(KeyboardInputModule.Effect.CopyToClipboard("hello")),
        )
    }

    // ─── HostEditorAttached / Detached lens writes ────────────────────

    @Test
    fun `HostEditorAttached writes the snapshot into the sub-state`() {
        val snap = boundHost(imeAction = 4 /* SEND */)
        val result = module.reduce(
            initial(),
            Action.KeyboardInputAction.HostEditorAttached(snap),
            ctx(),
        )
        assertNotNull(result)
        assertEquals(snap, result!!.nextState.hostEditor)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `HostEditorDetached resets the snapshot to defaults`() {
        val seeded = stateWith(boundHost(imeAction = 4))
        val result = module.reduce(
            seeded,
            Action.KeyboardInputAction.HostEditorDetached,
            ctx(),
        )
        assertNotNull(result)
        assertEquals(HostEditorState(), result!!.nextState.hostEditor)
        assertFalse(result.nextState.hostEditor.hasEditorInfo)
    }

    // ─── EnterKey reducer — Edge-Case-Tabelle ─────────────────────────

    private fun enterEffect(host: HostEditorState): KeyboardInputModule.Effect {
        val result = module.reduce(stateWith(host), Action.KeyboardInputAction.EnterKey, ctx())
        assertNotNull(result)
        assertEquals(1, result!!.sideEffects.size)
        return result.sideEffects.first()
    }

    @Test
    fun `EnterKey with no EditorInfo emits SendPhysicalEnter`() {
        val eff = enterEffect(HostEditorState()) // hasEditorInfo = false
        assertEquals(KeyboardInputModule.Effect.SendPhysicalEnter, eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_GO emits PerformEnter(GO, 2)`() {
        val eff = enterEffect(boundHost(imeAction = 2))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.GO, 2), eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_SEARCH emits PerformEnter(SEARCH, 3)`() {
        val eff = enterEffect(boundHost(imeAction = 3))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.SEARCH, 3), eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_SEND emits PerformEnter(SEND, 4)`() {
        val eff = enterEffect(boundHost(imeAction = 4))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.SEND, 4), eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_NEXT emits PerformEnter(NEXT, 5)`() {
        val eff = enterEffect(boundHost(imeAction = 5))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.NEXT, 5), eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_DONE emits PerformEnter(DONE, 6)`() {
        val eff = enterEffect(boundHost(imeAction = 6))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.DONE, 6), eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_PREVIOUS emits PerformEnter(PREVIOUS, 7)`() {
        val eff = enterEffect(boundHost(imeAction = 7))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.PREVIOUS, 7), eff)
    }

    @Test
    fun `EnterKey with IME_ACTION_UNSPECIFIED emits PerformEnter(NEWLINE)`() {
        val eff = enterEffect(boundHost(imeAction = 0))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.NEWLINE, 0), eff)
    }

    @Test
    fun `EnterKey with hasNoEnterAction overrides SEND to NEWLINE`() {
        val eff = enterEffect(boundHost(imeAction = 4, hasNoEnterAction = true))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.NEWLINE, 0), eff)
    }

    @Test
    fun `EnterKey with isMultiLine overrides SEND to NEWLINE`() {
        val eff = enterEffect(boundHost(imeAction = 4, isMultiLine = true))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.NEWLINE, 0), eff)
    }

    @Test
    fun `EnterKey with customActionId emits PerformEnter(CUSTOM, customId)`() {
        val eff = enterEffect(boundHost(customActionId = 42, imeAction = 4))
        assertEquals(KeyboardInputModule.Effect.PerformEnter(EnterButtonRole.CUSTOM, 42), eff)
    }

    // ─── Module wiring sanity ─────────────────────────────────────────

    @Test
    fun `module id is KeyboardInput`() {
        assertEquals(ModuleId.KeyboardInput, module.id)
    }

    @Test
    fun `lens write round-trips`() {
        val global = DictateUiState.initial()
        val nextSub = stateWith(boundHost(imeAction = 4))
        val nextGlobal = module.write(global, nextSub)
        assertEquals(nextSub, module.read(nextGlobal))
    }

    @Test
    fun `initial state is empty KeyboardInputState`() {
        assertEquals(KeyboardInputState(), module.initialState())
    }
}
