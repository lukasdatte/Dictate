package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [KeyboardLayoutManager] — the render-orchestrator.
 *
 * Focus areas:
 *
 * 1. **Multi-backend fan-out** — every state-emit reaches every attached
 *    backend whose `backendType` matches (or is `null`).
 * 2. **Mode selection** — `computeLayoutMode(state)` picks correctly for
 *    every `ViewMode` × `pipeline` × `singleRowMode` combination
 *    (Spec 2 §4 + §8.6).
 * 3. **Attach / detach lifecycle** — backends get exactly one `attach` and
 *    one `detach`, the click-sink is wired through.
 * 4. **Initial render on attach** — a backend attached mid-session sees
 *    the current state immediately (no blank-frame flash).
 */
class KeyboardLayoutManagerTest {

    private val catalog = LayoutCatalog(testLayoutStrings())
    private val emittedActions = mutableListOf<Action>()
    private val manager = KeyboardLayoutManager(catalog) { action -> emittedActions.add(action) }

    // ─── Attach lifecycle ──────────────────────────────────────────────

    @Test
    fun `attachBackend wires onAction sink`() {
        val backend = TestRenderBackend()
        manager.attachBackend(backend)
        backend.simulateClick(Action.KeyboardInputAction.Backspace)
        assertEquals(listOf(Action.KeyboardInputAction.Backspace), emittedActions)
    }

    @Test
    fun `attachBackend invokes attach exactly once`() {
        val backend = TestRenderBackend()
        manager.attachBackend(backend)
        assertEquals(1, backend.attachCount)
    }

    @Test
    fun `attachBackend twice raises IllegalStateException`() {
        val backend = TestRenderBackend()
        manager.attachBackend(backend)
        assertThrows(IllegalStateException::class.java) {
            manager.attachBackend(backend)
        }
    }

    @Test
    fun `attachBackend re-renders current state to the new backend`() {
        val backend = TestRenderBackend()
        manager.onStateChanged(stateForKeyboard(singleRow = false))
        // Backend attached AFTER the state emit — must still see it on
        // attach so the user doesn't get a blank UI frame.
        manager.attachBackend(backend)
        assertEquals(1, backend.renderCount)
        assertSame(catalog.KEYBOARD_TWO_ROW, backend.lastMode)
    }

    // ─── Detach lifecycle ──────────────────────────────────────────────

    @Test
    fun `detachBackend invokes detach and stops future renders`() {
        val backend = TestRenderBackend()
        manager.attachBackend(backend)
        manager.detachBackend(backend)
        manager.onStateChanged(stateForKeyboard(singleRow = false))
        assertEquals(1, backend.detachCount)
        // The first render is the on-attach replay (currentState was
        // null, so attachBackend skipped it); after detach, the manager
        // emits to no one.
        assertEquals(0, backend.renderCount)
    }

    @Test
    fun `detachBackend on unknown backend is a no-op`() {
        val unattached = TestRenderBackend()
        // No exception thrown — defensive lifecycle.
        manager.detachBackend(unattached)
        assertEquals(0, unattached.detachCount)
    }

    @Test
    fun `detachAll tears down every attached backend`() {
        val a = TestRenderBackend()
        val b = TestRenderBackend()
        manager.attachBackend(a)
        manager.attachBackend(b)
        manager.detachAll()
        assertEquals(1, a.detachCount)
        assertEquals(1, b.detachCount)
        assertEquals(0, manager.attachedBackendCount())
    }

    // ─── Fan-out ───────────────────────────────────────────────────────

    @Test
    fun `onStateChanged renders to every attached backend regardless of viewMode`() {
        // Bidirectional render-sync (2026-05-21): every state-emit
        // reaches every attached backend. Each backend gets its
        // type-appropriate mode; viewMode no longer gates participation.
        val ime = TestRenderBackend(backendType = BackendType.IME_VIEW)
        val crossCutting = TestRenderBackend(backendType = null)
        val overlay = TestRenderBackend(backendType = BackendType.OVERLAY_WINDOW)
        manager.attachBackend(ime)
        manager.attachBackend(crossCutting)
        manager.attachBackend(overlay)

        manager.onStateChanged(stateForKeyboard(singleRow = false))

        // All three render — IME and crossCutting on KEYBOARD_TWO_ROW,
        // overlay on OVERLAY_5BUTTON (its type-appropriate mode).
        assertEquals(1, ime.renderCount)
        assertEquals(1, crossCutting.renderCount)
        assertEquals(1, overlay.renderCount)
        assertSame(catalog.KEYBOARD_TWO_ROW, ime.lastMode)
        assertSame(catalog.KEYBOARD_TWO_ROW, crossCutting.lastMode)
        assertSame(catalog.OVERLAY_5BUTTON, overlay.lastMode)
    }

    @Test
    fun `onStateChanged in WIDGET mode still renders IME backend with keyboard mode`() {
        // Bidirectional render-sync regression-lock: the original
        // mutually-exclusive design suppressed IME-side rendering while
        // viewMode == WIDGET, which froze keyboard buttons (no live
        // pipeline label, stale click resolvers). The IME backend now
        // receives `forKeyboard(state)` even when the widget is open.
        val ime = TestRenderBackend(backendType = BackendType.IME_VIEW)
        val overlay = TestRenderBackend(backendType = BackendType.OVERLAY_WINDOW)
        manager.attachBackend(ime)
        manager.attachBackend(overlay)

        manager.onStateChanged(DictateUiState.initial().copy(viewMode = ViewMode.WIDGET))

        assertEquals(1, ime.renderCount)
        assertEquals(1, overlay.renderCount)
        assertSame(catalog.KEYBOARD_TWO_ROW, ime.lastMode)
        assertSame(catalog.OVERLAY_5BUTTON, overlay.lastMode)
    }

    @Test
    fun `onStateChanged in HOVER mode still renders IME backend with keyboard mode`() {
        // Symmetric to the WIDGET regression-lock: HOVER must not freeze
        // the keyboard renderer either.
        val ime = TestRenderBackend(backendType = BackendType.IME_VIEW)
        val overlay = TestRenderBackend(backendType = BackendType.OVERLAY_WINDOW)
        manager.attachBackend(ime)
        manager.attachBackend(overlay)

        manager.onStateChanged(DictateUiState.initial().copy(viewMode = ViewMode.HOVER))

        assertEquals(1, ime.renderCount)
        assertEquals(1, overlay.renderCount)
        assertSame(catalog.KEYBOARD_TWO_ROW, ime.lastMode)
        assertSame(catalog.OVERLAY_5BUTTON, overlay.lastMode)
    }

    @Test
    fun `onStateChanged in KEYBOARD mode still renders OVERLAY backend with overlay mode`() {
        // The mirror of the above: an overlay backend attached during
        // KEYBOARD mode (e.g. when WIDGET was just toggled off and the
        // overlay-detach is pending) must keep receiving renders too.
        val overlay = TestRenderBackend(backendType = BackendType.OVERLAY_WINDOW)
        manager.attachBackend(overlay)

        manager.onStateChanged(stateForKeyboard(singleRow = false))

        assertEquals(1, overlay.renderCount)
        assertSame(catalog.OVERLAY_5BUTTON, overlay.lastMode)
    }

    // ─── Mode selection (Spec 2 §8.6) ─────────────────────────────────

    @Test
    fun `computeLayoutMode returns KEYBOARD_TWO_ROW for keyboard idle two-row`() {
        val state = stateForKeyboard(singleRow = false)
        assertSame(catalog.KEYBOARD_TWO_ROW, manager.computeLayoutMode(state))
    }

    @Test
    fun `computeLayoutMode returns SINGLE_ROW for keyboard idle single-row`() {
        val state = stateForKeyboard(singleRow = true)
        assertSame(catalog.KEYBOARD_SINGLE_ROW, manager.computeLayoutMode(state))
    }

    @Test
    fun `computeLayoutMode picks SEND_MODE for active pipeline`() {
        val state = stateForKeyboard(singleRow = false).copy(
            pipeline = PipelineUiState.Preparing("s1"),
        )
        assertSame(catalog.KEYBOARD_TWO_ROW_SEND_MODE, manager.computeLayoutMode(state))
    }

    @Test
    fun `computeLayoutMode picks REPROCESS_STAGING for staging state`() {
        val state = stateForKeyboard(singleRow = false).copy(
            pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"),
        )
        assertSame(catalog.KEYBOARD_REPROCESS_STAGING, manager.computeLayoutMode(state))
    }

    @Test
    fun `computeLayoutMode picks OVERLAY for non-KEYBOARD viewModes`() {
        val widget = DictateUiState.initial().copy(viewMode = ViewMode.WIDGET)
        val hover = DictateUiState.initial().copy(viewMode = ViewMode.HOVER)
        assertSame(catalog.OVERLAY_5BUTTON, manager.computeLayoutMode(widget))
        assertSame(catalog.OVERLAY_5BUTTON, manager.computeLayoutMode(hover))
    }

    // ─── State propagation ────────────────────────────────────────────

    @Test
    fun `onStateChanged with no backends attached is a no-op`() {
        // Just don't throw. The state still gets cached so a future
        // attachBackend re-renders it.
        manager.onStateChanged(stateForKeyboard(singleRow = false))
        val backend = TestRenderBackend()
        manager.attachBackend(backend)
        assertEquals(1, backend.renderCount)
    }

    @Test
    fun `attachedBackendCount tracks active backends`() {
        assertEquals(0, manager.attachedBackendCount())
        val a = TestRenderBackend()
        val b = TestRenderBackend()
        manager.attachBackend(a)
        assertEquals(1, manager.attachedBackendCount())
        manager.attachBackend(b)
        assertEquals(2, manager.attachedBackendCount())
        manager.detachBackend(a)
        assertEquals(1, manager.attachedBackendCount())
    }

    @Test
    fun `two backends with the same backendType both receive renders`() {
        // B4-VAL F-34a: the multi-backend fan-out (R.10) must keep both
        // members live even when they share a backendType. Earlier code
        // could have hashed on backendType and lost one — assert both
        // see every render-tick.
        val a = TestRenderBackend(backendType = BackendType.IME_VIEW)
        val b = TestRenderBackend(backendType = BackendType.IME_VIEW)
        manager.attachBackend(a)
        manager.attachBackend(b)

        manager.onStateChanged(stateForKeyboard(singleRow = false))

        assertEquals(1, a.renderCount)
        assertEquals(1, b.renderCount)
    }

    // ─── CR3 — audit-logger render-generation boundary (RR-2) ──────────

    @Test
    fun `onStateChanged opens one audit render-generation per state-emit`() {
        org.junit.Assume.assumeTrue(
            "audit logger is DEBUG-guarded",
            net.devemperor.dictate.BuildConfig.DEBUG,
        )
        val logger = net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger()
        val auditedManager = KeyboardLayoutManager(
            catalog = catalog,
            visibilityAuditLogger = logger,
        ) { action -> emittedActions.add(action) }

        val viewId = 9001

        // Generation 1: simulate the legacy KSM live write, then a
        // dormant controller reporting the same axis (the CR3 steady
        // state). Exactly one live writer → no double-write.
        auditedManager.onStateChanged(stateForKeyboard(singleRow = false))
        logger.logWrite(viewId, "KeyboardStateManager", android.view.View.GONE, live = true)
        logger.logWrite(viewId, "ContentAreaController", android.view.View.VISIBLE, live = false)
        assertEquals(0, logger.doubleWriteCount)
        assertEquals("KeyboardStateManager", logger.soleLiveWriterOf(viewId))

        // Generation 2: a fresh state-emit must reset the per-generation
        // ledger (else the gen-1 owner would carry over and a gen-2
        // different live writer would be a false double-write).
        auditedManager.onStateChanged(stateForKeyboard(singleRow = true))
        logger.logWrite(viewId, "PromptVisibilityController", android.view.View.GONE, live = true)
        assertEquals(0, logger.doubleWriteCount)
        assertEquals("PromptVisibilityController", logger.soleLiveWriterOf(viewId))
    }

    // ─── Test fixtures ─────────────────────────────────────────────────

    private fun stateForKeyboard(singleRow: Boolean): DictateUiState =
        DictateUiState.initial().copy(
            viewMode = ViewMode.KEYBOARD,
            layout = LayoutState(singleRowMode = singleRow),
        )

    @Suppress("unused")
    private fun assertedNoActionsBeyond(expected: List<Action>) {
        assertEquals(expected, emittedActions)
    }
}

// Suppress unused-warning for the helper-anchor — referenced only by tests.
@Suppress("unused") private val _anchor: (Action) -> Unit = { _ ->
    assertNull(null)  // keeps the import live
}
