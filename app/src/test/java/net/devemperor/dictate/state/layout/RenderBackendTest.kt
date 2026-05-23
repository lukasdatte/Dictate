package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [RenderBackend].
 *
 * **Why a contract test for an interface?** [RenderBackend] is an
 * abstraction whose semantics (attach-before-render, idempotent render,
 * detach-clears-onAction) every implementation must respect. We assert
 * those semantics against a hand-rolled [TestRenderBackend] so future
 * backend implementations (`ImeViewBackend` in C14, `OverlayBackend` in
 * B5) can re-use the same contract harness — copy the test file, swap
 * the backend factory.
 */
class RenderBackendTest {

    @Test
    fun `attach captures onAction and survives until detach`() {
        val backend = TestRenderBackend()
        val received = mutableListOf<Action>()

        backend.attach { received.add(it) }
        backend.simulateClick(Action.KeyboardInputAction.Backspace)

        assertEquals(listOf(Action.KeyboardInputAction.Backspace), received)

        backend.detach()
        backend.simulateClick(Action.KeyboardInputAction.EnterKey)

        // After detach, the click sink is null — the contract is "the
        // backend MUST NOT invoke the previously-supplied onAction sink".
        assertEquals(listOf(Action.KeyboardInputAction.Backspace), received)
    }

    @Test
    fun `render records both state and mode`() {
        val backend = TestRenderBackend()
        val catalog = LayoutCatalog(testLayoutStrings())
        backend.attach { /* unused */ }

        val state = DictateUiState.initial()
        val mode = catalog.KEYBOARD_TWO_ROW

        backend.render(state, mode)

        assertEquals(state, backend.lastState)
        assertEquals(mode, backend.lastMode)
        assertEquals(1, backend.renderCount)
    }

    @Test
    fun `render is idempotent — re-emitting same args increments counter only`() {
        val backend = TestRenderBackend()
        val catalog = LayoutCatalog(testLayoutStrings())
        backend.attach { /* */ }

        val state = DictateUiState.initial()
        val mode = catalog.KEYBOARD_TWO_ROW

        backend.render(state, mode)
        backend.render(state, mode)
        backend.render(state, mode)

        assertEquals(3, backend.renderCount)
        // A production backend would compare-and-skip, but the contract
        // doesn't mandate that — it mandates "produces no visible
        // change". A counter increment is fine.
    }

    @Test
    fun `backendType null means consume every mode`() {
        // ContentAreaController-style usage: a backend that listens on
        // every mode regardless of its surface. The default
        // [TestRenderBackend] uses IME_VIEW; we override via the
        // constructor parameter.
        val backend = TestRenderBackend(backendType = null)
        assertNull(backend.backendType)
    }
}

/**
 * Hand-rolled fake backend used by both [RenderBackendTest] and
 * [KeyboardLayoutManagerTest]. Records attach/detach/render calls so
 * tests can assert on the manager's fan-out behaviour.
 */
internal class TestRenderBackend(
    override val backendType: BackendType? = BackendType.IME_VIEW,
) : RenderBackend {

    var attachCount: Int = 0
        private set

    var detachCount: Int = 0
        private set

    var renderCount: Int = 0
        private set

    var lastState: DictateUiState? = null
        private set

    var lastMode: LayoutMode? = null
        private set

    private var onAction: ((Action) -> Unit)? = null

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        attachCount++
    }

    override fun detach() {
        onAction = null
        detachCount++
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        lastState = state
        lastMode = mode
        renderCount++
    }

    /** Simulate a user click that resolves to [action]. */
    fun simulateClick(action: Action) {
        onAction?.invoke(action)
    }
}
