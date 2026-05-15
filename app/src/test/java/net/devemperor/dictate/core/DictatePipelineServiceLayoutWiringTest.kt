package net.devemperor.dictate.core

import android.content.Intent
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.KeyboardLayoutManager
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import net.devemperor.dictate.state.DictateUiState

/**
 * Robolectric tests for the C15 keyboard-layout render-orchestration
 * wiring (Spec 2 §11.8 5c).
 *
 * **Coverage focus:**
 *  - `DictatePipelineService.onCreate` constructs the [LayoutCatalog]
 *    and the [KeyboardLayoutManager], both exposed via the
 *    [DictatePipelineService.LocalBinder].
 *  - The service-side state-collect coroutine forwards every emit
 *    into `manager.onStateChanged(...)` — verified by attaching a
 *    fake backend and observing render calls.
 *  - The manager re-renders newly attached backends with the
 *    current state (avoids blank-frame flash).
 *  - The binder's `moduleServices` accessor returns the same DI
 *    container the orchestrator was constructed with — the IME-side
 *    `ImeViewBackend` consumes the same reference its resolvers
 *    need for the `audioFileFactory.allocate()` call (Spec 1 §4.11).
 *
 * These tests complement [DictatePipelineServiceTest] +
 * [DictatePipelineServiceCompositionTest]; this file's scope is the
 * C15 wiring only.
 *
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see net.devemperor.dictate.state.layout.KeyboardLayoutManager
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceLayoutWiringTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        JobExecutor.resetForTest()
    }

    // ──────────────────────────────────────────────────────────────────
    // Catalog + manager construction
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `onCreate exposes LayoutCatalog via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        assertNotNull(
            "LayoutCatalog must be constructed during onCreate (C15 §11.8 5c)",
            binder.layoutCatalog,
        )
    }

    @Test
    fun `onCreate exposes KeyboardLayoutManager via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        assertNotNull(
            "KeyboardLayoutManager must be constructed during onCreate (C15 §11.8 5c)",
            binder.keyboardLayoutManager,
        )
    }

    @Test
    fun `onCreate exposes the same ModuleServices the orchestrator uses`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        assertNotNull(
            "ModuleServices must be exposed for IME-side ImeViewBackend wiring",
            binder.moduleServices,
        )
        // Verify the same factory the binder exposes is also reachable
        // through the ModuleServices view — the IME's
        // ImeViewBackend.actionResolver uses the latter (R.2 / Spec 1 §4.11).
        assertSame(
            "audioFileFactory exposed on binder must equal the one in ModuleServices",
            binder.audioFileFactory,
            binder.moduleServices.audioFileFactory,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // State-collect forwarding to the manager
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `state emissions reach attached backends via the manager`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        val manager: KeyboardLayoutManager = binder.keyboardLayoutManager

        // Capturing fake — null backendType so it consumes every mode
        // (mirrors ContentAreaController-style backends).
        val captured = mutableListOf<DictateUiState>()
        val fakeBackend = object : RenderBackend {
            override val backendType: BackendType? = null
            override fun attach(onAction: (Action) -> Unit) { /* no-op */ }
            override fun detach() { /* no-op */ }
            override fun render(state: DictateUiState, mode: LayoutMode) {
                captured += state
            }
        }

        manager.attachBackend(fakeBackend)
        // Attach triggers an immediate render with the current state.
        assertTrue(
            "Backend must be re-rendered with the current state on attach (no blank-frame flash)",
            captured.isNotEmpty(),
        )

        // Dispatch an action that produces a state change — the
        // service-side collect should forward the resulting state
        // emit to our fake.
        val countBefore = captured.size
        binder.dispatch(Action.ResendAction.MarkLastAudio(true))
        // The state-collect runs on Main; Robolectric's Looper drains
        // pending tasks via `idleMainLooper` — buildService runs the
        // Looper inline already, but make sure pending callbacks finish.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(
            "Backend must observe at least one further render after a state mutation (got count=$countBefore → ${captured.size})",
            captured.size > countBefore,
        )
        manager.detachBackend(fakeBackend)
    }

    @Test
    fun `manager re-renders newly attached backends with the current state`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        val manager: KeyboardLayoutManager = binder.keyboardLayoutManager

        val firstRenders = mutableListOf<LayoutMode>()
        val backend = object : RenderBackend {
            override val backendType: BackendType? = null
            override fun attach(onAction: (Action) -> Unit) { /* no-op */ }
            override fun detach() { /* no-op */ }
            override fun render(state: DictateUiState, mode: LayoutMode) {
                firstRenders += mode
            }
        }
        manager.attachBackend(backend)
        assertEquals(
            "Newly attached backend must receive exactly one synchronous render",
            1, firstRenders.size,
        )
        manager.detachBackend(backend)
    }
}
