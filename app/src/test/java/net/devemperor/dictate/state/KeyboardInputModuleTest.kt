package net.devemperor.dictate.state

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.insertion.AutoEnterScheduler
import net.devemperor.dictate.state.insertion.ClipboardGateway
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.KeyboardActionDispatcher
import net.devemperor.dictate.state.insertion.LocalImeSink
import net.devemperor.dictate.state.insertion.EditAction
import net.devemperor.dictate.state.insertion.HostSelection
import net.devemperor.dictate.state.insertion.HostTarget
import net.devemperor.dictate.state.insertion.InsertionAuditLog
import net.devemperor.dictate.state.insertion.InsertionService
import net.devemperor.dictate.state.insertion.RecoveryHandler
import net.devemperor.dictate.state.layout.EnterButtonRole
import net.devemperor.dictate.testutil.FakeHostTextReader
import net.devemperor.dictate.testutil.fakeModuleServices
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

    // ─── SendBackspace effect — F-018 DeleteGrapheme dispatch ─────────

    /**
     * Real [InsertionService] with a recording executor + configurable
     * [FakeHostTextReader]: the resolved primitive proves *which* ControlOp
     * the module dispatched (DeleteGrapheme resolves through the reader; the
     * pre-fix raw Backspace passes through unresolved).
     */
    private class RecordingInsertion(reader: FakeHostTextReader) {
        val controlOps = mutableListOf<ControlOp>()
        val service = InsertionService(
            ic = { HostTarget(FakeInputConnection(), null as EditorInfo?) },
            guard = { true },
            committer = { _, _ -> true },
            controlExecutor = { _, op -> controlOps += op; true },
            autoEnter = object : AutoEnterScheduler {
                override fun isActive() = false
                override fun schedule(text: String) {}
            },
            audit = object : InsertionAuditLog {
                override fun captureReplaced(ic: InputConnection): String? = null
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) {}
            },
            recovery = object : RecoveryHandler {
                override fun notifyFocusLost() {}
                override fun resume(sessionId: String) {}
            },
            clipboard = object : ClipboardGateway {
                override fun performHostAction(ic: InputConnection, action: EditAction) = true
                override fun fallback(ic: InputConnection, action: EditAction) {}
            },
            textReader = reader,
        )

        /**
         * The seam facade the module now writes through (§4.2). LocalImeSink delegates byte-for-byte
         * to [service], so the same `controlOps`/inserts are recorded — this is the rot-vor-grün proof
         * that the Space/Backspace/Enter behaviour survives the router rewire unchanged.
         */
        val dispatcher = KeyboardActionDispatcher(LocalImeSink(service))
    }

    @Test
    fun `SendBackspace with an active selection deletes the selection`() {
        // F-018 regression: the pre-fix handler dispatched the raw
        // ControlOp.Backspace (deleteSurroundingText(1,0)), which per Android
        // contract deletes AROUND an active selection instead of deleting it.
        val rec = RecordingInsertion(FakeHostTextReader(selection = HostSelection(2, 5)))
        val services = fakeModuleServices(keyboardActionsProvider = { rec.dispatcher })

        module.runEffect(KeyboardInputModule.Effect.SendBackspace, services)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSelection), rec.controlOps)
    }

    @Test
    fun `SendBackspace with an emoji before the cursor deletes the whole pair`() {
        // F-018 regression: the pre-fix raw Backspace deleted one UTF-16 unit,
        // splitting the surrogate pair.
        val rec = RecordingInsertion(
            FakeHostTextReader(selection = HostSelection(3, 3), beforeCursor = "x😀"),
        )
        val services = fakeModuleServices(keyboardActionsProvider = { rec.dispatcher })

        module.runEffect(KeyboardInputModule.Effect.SendBackspace, services)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSurrounding(2, 0)), rec.controlOps)
    }

    @Test
    fun `SendBackspace without insertion service is a no-op`() {
        val services = fakeModuleServices(keyboardActionsProvider = { null })
        // Must not throw — legacy null-IC behaviour.
        module.runEffect(KeyboardInputModule.Effect.SendBackspace, services)
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
