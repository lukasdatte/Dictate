package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.OverlayState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ViewMode
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.LayoutModeId
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.layout.testLayoutStrings
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [OverlayBackend].
 *
 * # Why Robolectric
 *
 * The backend calls [android.view.LayoutInflater.inflate] on a real
 * `R.layout.overlay_5button_layout` XML — the inflate step needs the
 * Android framework. The actual `WindowManager` is wrapped via
 * [FakeOverlayWindow] (K-1).
 *
 * # Coverage focus
 *
 * 1. **Permission gate** — `hasPermission=false` skips attach.
 * 2. **Suppress bit** — `suppressAutoOverlayUntilNextSession=true` skips
 *    attach.
 * 3. **First-render inflate** — the wrapper records exactly one
 *    `attach` call and the buttonViews map is populated.
 * 4. **Click → action** — clicking OVERLAY_CLOSE in HOVER emits
 *    `CloseOverlay`; clicking the same button in WIDGET emits
 *    `ToggleViewModeWidget`.
 * 5. **Null-resolver no-op (R.3)** — clicking OVERLAY_RECORD in HOVER
 *    fires no action.
 * 6. **Detach** — `overlayWindow.detach` is called, View references
 *    cleared, subsequent clicks are no-ops.
 * 7. **Backend mismatch** — render with a `BackendType.IME_VIEW` mode
 *    raises.
 * 8. **Permission revoke at attach** — `simulateBadTokenOnAttach`
 *    leaves the backend detached; no follow-up state captured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayBackendTest {

    private lateinit var ctx: Context
    private lateinit var window: FakeOverlayWindow
    private val catalog: LayoutCatalog = LayoutCatalog(testLayoutStrings())
    private val captured: MutableList<Action> = mutableListOf()

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        window = FakeOverlayWindow()
        captured.clear()
    }

    private fun newBackend(): OverlayBackend = OverlayBackend(
        ctx = ctx,
        services = fakeModuleServices(emitAction = {}),
        overlayWindow = window,
        permissions = NoOverlayPermissionGate,
        layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
    )

    private fun stateWithPermission(
        viewMode: ViewMode = ViewMode.WIDGET,
        recording: RecordingState = RecordingState.Idle,
        pipeline: PipelineUiState = PipelineUiState.Idle,
        hasPermission: Boolean = true,
        suppress: Boolean = false,
        // 2026-05-23 sticky-widget refactor: `state.widget` is the
        // overlay's source of truth. The default mirrors the legacy
        // viewMode so existing tests keep producing the same overlay
        // axes — WIDGET → Visible(USER), HOVER → Visible(PIPELINE),
        // KEYBOARD → Hidden. Tests that need a divergent shape pass
        // an explicit `widget` arg.
        widget: net.devemperor.dictate.state.WidgetState = when (viewMode) {
            ViewMode.WIDGET -> net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.USER,
            )
            ViewMode.HOVER -> net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.PIPELINE,
            )
            ViewMode.KEYBOARD -> net.devemperor.dictate.state.WidgetState.Hidden
        },
    ): DictateUiState = DictateUiState.initial().copy(
        viewMode = viewMode,
        recording = recording,
        pipeline = pipeline,
        widget = widget,
        overlay = OverlayState(
            hasPermission = hasPermission,
            suppressAutoOverlayUntilNextSession = suppress,
        ),
    )

    @Test
    fun `backendType is OVERLAY_WINDOW`() {
        assertEquals(BackendType.OVERLAY_WINDOW, newBackend().backendType)
    }

    @Test
    fun `render without permission does NOT attach the window`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(hasPermission = false), catalog.OVERLAY_5BUTTON)

        assertFalse("Wrapper must remain detached.", window.isAttached())
        assertTrue("No attach call must be recorded.", "attach" !in window.events)
    }

    @Test
    fun `render with permission attaches once on first render`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        assertTrue("Wrapper must be attached after first render.", window.isAttached())
        assertEquals(
            "Second render must NOT re-attach the window.",
            1, window.events.count { it == "attach" },
        )
    }

    @Test
    fun `render with suppress bit no longer tears down (sticky-widget refactor 2026-05-23)`() {
        // Pre-refactor the suppress-bit gate in OverlayBackend.render()
        // step 2 would teardownOverlay() whenever
        // suppressAutoOverlayUntilNextSession went true. That collided
        // with the sticky-widget attachment condition in
        // DictatePipelineService.syncOverlayBackendAttachment, which
        // keeps the backend attached whenever `state.widget is Visible`.
        // The result was a window-of-inconsistency: stateRef nulled by
        // teardown, click-listeners wired to the null snapshot, taps
        // silently swallowed. The gate is gone; close-of-overlay is now
        // driven exclusively by `state.widget == Hidden` going through
        // syncOverlayBackendAttachment → detach() → teardown.
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        assertTrue(window.isAttached())

        backend.render(
            stateWithPermission(suppress = true),
            catalog.OVERLAY_5BUTTON,
        )
        assertTrue(
            "sticky-widget refactor: suppress-bit is a dead axis, render() must NOT teardown",
            window.isAttached(),
        )
    }

    @Test
    fun `detach tears down the overlay`() {
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        assertTrue(window.isAttached())

        backend.detach()

        assertFalse("Wrapper must be detached after backend.detach().", window.isAttached())
        assertTrue("detach call must be recorded.", "detach" in window.events)
    }

    @Test
    fun `permission revoke at attach leaves backend in detached state`() {
        window.simulateBadTokenOnAttach = true
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        assertFalse(
            "BadToken catch must leave overlayWindow detached.",
            window.isAttached(),
        )
        // overlayView must remain null — verified indirectly by clicking
        // (no listener was wired because inflateAndAttach() bailed
        // after the BadToken).
        // We can't access private state, so we just confirm clicks don't
        // emit via a re-render path: render again with permission still
        // granted but window simulating BadToken → still no attach.
        window.simulateBadTokenOnAttach = false
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        assertTrue("Subsequent retry succeeds once BadToken is cleared.", window.isAttached())
    }

    @Test
    fun `render with mismatched backend throws`() {
        val backend = newBackend()
        backend.attach { captured += it }

        val imeMode = LayoutMode(
            id = LayoutModeId.KEYBOARD_TWO_ROW,
            backend = BackendType.IME_VIEW,
            rows = emptyList(),
        )

        try {
            backend.render(stateWithPermission(), imeMode)
            fail("Expected require() to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message must mention non-OVERLAY_WINDOW: ${e.message}",
                e.message!!.contains("non-OVERLAY_WINDOW"),
            )
        }
    }

    @Test
    fun `close click in WIDGET emits CloseWidget(WIDGET_BUTTON)`() {
        // 2026-05-22 — the overlay's X button dispatches CloseWidget
        // with WIDGET_BUTTON source directly (not ToggleViewModeWidget),
        // so the W2 reducer can pause the recording. WidgetModule's
        // cross-module observer cascades the viewMode-sync afterwards.
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(stateWithPermission(viewMode = ViewMode.WIDGET), catalog.OVERLAY_5BUTTON)

        val closeBtn = findOverlayButton(LogicalButtonId.OVERLAY_CLOSE)
        closeBtn.performClick()

        assertEquals(
            listOf(
                Action.WidgetAction.CloseWidget(
                    net.devemperor.dictate.state.WidgetCloseSource.WIDGET_BUTTON,
                ),
            ),
            captured,
        )
    }

    @Test
    fun `close click in HOVER emits CloseWidget(WIDGET_BUTTON) — sticky-widget refactor`() {
        // 2026-05-23 sticky-widget refactor: the X-button has a single
        // semantic — close the widget — regardless of legacy viewMode.
        // Pre-refactor HOVER routed through CloseOverlay (cancel
        // cascade), which broke when `state.viewMode` raced ahead of
        // the click and landed in KEYBOARD (resolver returned null,
        // tap silently no-op). The single CloseWidget path goes
        // through W2 whose WIDGET_BUTTON source still pauses an
        // active recording — the user-intent is preserved.
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(
            stateWithPermission(
                viewMode = ViewMode.HOVER,
                recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a"), sessionId = "sid-test"),
            ),
            catalog.OVERLAY_5BUTTON,
        )

        val closeBtn = findOverlayButton(LogicalButtonId.OVERLAY_CLOSE)
        closeBtn.performClick()

        assertEquals(
            listOf(
                Action.WidgetAction.CloseWidget(
                    net.devemperor.dictate.state.WidgetCloseSource.WIDGET_BUTTON,
                ),
            ),
            captured,
        )
    }

    @Test
    fun `record click with IME hidden + Active is a silent no-op (Senden verboten)`() {
        // 2026-05-22 — post-Widget-Pause-refactor spec: with the IME
        // hidden and a recording in flight, the record-btn is disabled
        // (no InputConnection target → Senden verboten). The user must
        // explicitly use the dedicated OVERLAY_PAUSE slot to pause.
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(
            stateWithPermission(viewMode = ViewMode.HOVER).copy(
                imeViewVisible = false,
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = File("/tmp/x.m4a"),
                    sessionId = "sid-x",
                ),
            ),
            catalog.OVERLAY_5BUTTON,
        )

        val recordBtn = findOverlayButton(LogicalButtonId.OVERLAY_RECORD)
        recordBtn.performClick()

        assertTrue(
            "IME hidden + Active record-click must be a silent no-op: $captured",
            captured.isEmpty(),
        )
    }

    @Test
    fun `record click in WIDGET emits StartRecording with allocated file`() {
        val tmpFile = File.createTempFile("overlay-test", ".m4a")
        tmpFile.deleteOnExit()
        val services = fakeModuleServices(
            emitAction = {},
            // Block A4 — Initial-File-Cutover. Resolver routes through
            // audioFileRepository.allocateFirst(sid) now, not the factory.
            audioFileRepository = object : net.devemperor.dictate.audio.AudioFileRepository {
                override fun allocateFirst(sessionId: String): File = tmpFile
                override fun allocateNext(sessionId: String): File =
                    error("not exercised by this test")
                override fun segments(sessionId: String): List<File> = emptyList()
                override suspend fun readForPipeline(
                    sessionId: String,
                ): net.devemperor.dictate.audio.PipelineAudioResult? = null
                override fun deleteAll(sessionId: String) = Unit
                override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
                override fun listAllOwnedFiles(): Map<String, List<File>> = emptyMap()
            },
        )
        val backend = OverlayBackend(
            ctx = ctx,
            services = services,
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
        )
        backend.attach { captured += it }

        backend.render(stateWithPermission(viewMode = ViewMode.WIDGET), catalog.OVERLAY_5BUTTON)

        val recordBtn = findOverlayButton(LogicalButtonId.OVERLAY_RECORD)
        recordBtn.performClick()

        assertEquals(1, captured.size)
        val action = captured[0] as? Action.RecordingAction.StartRecording
        assertNotNull("Expected StartRecording, got: ${captured[0]}", action)
        assertEquals(InsertionTarget.INPUT_CONNECTION, action!!.target)
        assertEquals(tmpFile, action.audioFile)
    }

    @Test
    fun `click after detach is a silent no-op`() {
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        val closeBtn = findOverlayButton(LogicalButtonId.OVERLAY_CLOSE)
        backend.detach()
        closeBtn.performClick()

        assertTrue(
            "Click on detached View must not emit (stateRef=null short-circuit).",
            captured.isEmpty(),
        )
    }

    @Test
    fun `re-render with same state is idempotent — only one attach`() {
        val backend = newBackend()
        backend.attach { captured += it }
        val state = stateWithPermission()
        repeat(5) { backend.render(state, catalog.OVERLAY_5BUTTON) }

        assertEquals(
            "Multiple identical renders must not re-attach the window.",
            1, window.events.count { it == "attach" },
        )
    }

    @Test
    fun `permission revoked mid-session tears down the window`() {
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        assertTrue(window.isAttached())

        // Permission revoked at runtime → state-axis observer updates
        // hasPermission to false → next render tears down.
        backend.render(stateWithPermission(hasPermission = false), catalog.OVERLAY_5BUTTON)

        assertFalse(
            "Permission revoke must tear down the window.",
            window.isAttached(),
        )
        assertTrue("detach call must be recorded.", "detach" in window.events)
    }

    // ─── C18 — position + drag wiring ─────────────────────────────────

    @Test
    fun `applyPosition updates window params via the mapper`() {
        val fakeMapper = FixedPositionMapper(px = 123, py = 456)
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            positionMapper = fakeMapper,
        )
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        assertEquals("params.x must be the mapped px", 123, window.lastParams!!.x)
        assertEquals("params.y must be the mapped py", 456, window.lastParams!!.y)
        assertTrue("window.update must have fired", "update" in window.events)
    }

    @Test
    fun `applyPosition is idempotent for an unchanged position`() {
        val fakeMapper = FixedPositionMapper(px = 10, py = 20)
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            positionMapper = fakeMapper,
        )
        backend.attach { captured += it }
        val state = stateWithPermission()
        backend.render(state, catalog.OVERLAY_5BUTTON)
        val updatesAfterFirst = window.events.count { it == "update" }
        backend.render(state, catalog.OVERLAY_5BUTTON)
        backend.render(state, catalog.OVERLAY_5BUTTON)

        assertEquals(
            "Re-render with the same position must not re-update the window",
            updatesAfterFirst, window.events.count { it == "update" },
        )
    }

    @Test
    fun `drag-controller persist dispatches UpdateOverlayPosition`() {
        // The fake drag-controller factory captures the persist sink so
        // the test can fire it directly (no MotionEvent simulation).
        // F-7: the sink now carries the orientation snapshot
        // (portrait, normX, normY).
        var persistSink: ((Boolean, Float, Float) -> Unit)? = null
        val factory = object : OverlayDragControllerFactory {
            override fun create(
                view: View,
                window: OverlayWindow,
                paramsHolder: () -> android.view.WindowManager.LayoutParams?,
                positionMapper: OverlayPositionMapper,
                orientationProvider: () -> Boolean,
                onPositionPersist: (Boolean, Float, Float) -> Unit,
            ): OverlayDragController {
                persistSink = onPositionPersist
                // Return a real controller; the test never feeds it
                // touch events, it invokes the captured sink directly.
                return OverlayDragController(
                    ctx, view, window, paramsHolder, positionMapper,
                    orientationProvider, onPositionPersist,
                )
            }
        }
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            dragControllerFactory = factory,
        )
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        // F-7: the controller now passes the orientation snapshot
        // through. Simulate a portrait drag-end.
        persistSink!!.invoke(true, 0.25f, 0.75f)

        val posAction = captured
            .filterIsInstance<Action.OverlayAction.UpdateOverlayPosition>()
            .single()
        assertEquals(0.25f, posAction.x)
        assertEquals(0.75f, posAction.y)
        assertTrue(
            "F-7: the orientation snapshot threaded through onPositionPersist must reach the action.",
            posAction.portrait,
        )
    }

    // ─── §8.3 Chunk 3.1 / 3.2 — imeSideAffordance hook ────────────────

    @Test
    fun `OVERLAY_RECORD click fires imeSideAffordance before catalog dispatch`() {
        // The hook MUST fire on every OVERLAY_RECORD click (the IME-side
        // implementation is self-gating). Without it, the orchestrator's
        // async `resolveFresh` finds no R-1 JobRequest snapshot and the
        // pipeline hangs in Preparing forever.
        val affordanceCalls: MutableList<Pair<LogicalButtonId, Boolean>> = mutableListOf()
        val tmpFile = File.createTempFile("overlay-affordance", ".m4a")
        tmpFile.deleteOnExit()
        val services = fakeModuleServices(
            emitAction = {},
            // Block A4 — Initial-File-Cutover. Resolver routes through
            // audioFileRepository.allocateFirst(sid) now, not the factory.
            audioFileRepository = object : net.devemperor.dictate.audio.AudioFileRepository {
                override fun allocateFirst(sessionId: String): File = tmpFile
                override fun allocateNext(sessionId: String): File =
                    error("not exercised by this test")
                override fun segments(sessionId: String): List<File> = emptyList()
                override suspend fun readForPipeline(
                    sessionId: String,
                ): net.devemperor.dictate.audio.PipelineAudioResult? = null
                override fun deleteAll(sessionId: String) = Unit
                override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
                override fun listAllOwnedFiles(): Map<String, List<File>> = emptyMap()
            },
        )
        val backend = OverlayBackend(
            ctx = ctx,
            services = services,
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            imeSideAffordance = { id, longPress -> affordanceCalls += id to longPress },
        )
        backend.attach { captured += it }
        backend.render(stateWithPermission(viewMode = ViewMode.WIDGET), catalog.OVERLAY_5BUTTON)

        findOverlayButton(LogicalButtonId.OVERLAY_RECORD).performClick()

        assertEquals(
            "Exactly one affordance call for the RECORD click, isLongPress=false.",
            listOf(LogicalButtonId.OVERLAY_RECORD to false),
            affordanceCalls,
        )
        // The catalog dispatch must also fire — affordance + dispatch
        // are symmetric, not exclusive.
        assertEquals(1, captured.size)
        assertTrue(
            "Expected StartRecording, got ${captured[0]}",
            captured[0] is Action.RecordingAction.StartRecording,
        )
    }

    @Test
    fun `non-RECORD clicks do not fire imeSideAffordance`() {
        // The affordance hook is RECORD-specific (the keyboard surface
        // also fires only for RECORD/RESEND — and the overlay has no
        // RESEND). PAUSE / TRASH / CLOSE clicks must not invoke it.
        val affordanceCalls: MutableList<LogicalButtonId> = mutableListOf()
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            imeSideAffordance = { id, _ -> affordanceCalls += id },
        )
        backend.attach { captured += it }
        backend.render(
            stateWithPermission(
                viewMode = ViewMode.WIDGET,
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = File("/tmp/x.m4a"),
                    sessionId = "sid-test",
                ),
            ),
            catalog.OVERLAY_5BUTTON,
        )

        // PAUSE click — must not fire affordance.
        findOverlayButton(LogicalButtonId.OVERLAY_PAUSE).performClick()
        // CLOSE click — must not fire affordance.
        findOverlayButton(LogicalButtonId.OVERLAY_CLOSE).performClick()
        // TRASH click — must not fire affordance.
        findOverlayButton(LogicalButtonId.OVERLAY_TRASH).performClick()

        assertTrue(
            "No affordance must fire for PAUSE/CLOSE/TRASH: $affordanceCalls",
            affordanceCalls.isEmpty(),
        )
    }

    // ─── §8.1 Chunks 1.3-1.4 — side-channel forwarders ────────────────

    @Test
    fun `onTimerTick is a no-op when no recording-animation factory is supplied`() {
        // JVM tests that don't care about animations leave the factory
        // null — the forwarder must not NPE / crash.
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        backend.onTimerTick(1234L)
        backend.onAmplitude(0.5f)
        backend.updateAccentColor(0xFF112233.toInt())
        // No exception = pass. (We can't observe the no-op directly
        // because there is no renderer to instrument.)
    }

    @Test
    fun `side-channel forwarders deliver to the factory-built renderers`() {
        // When a real factory is wired, onState / onTimerTick / onAmplitude
        // / updateAccentColor must reach the renderer instance.
        var lastTimerText: String? = null
        var lastAmplitude: Float? = null
        val fakeAnimation = object : net.devemperor.dictate.widget.RecordingAnimation {
            override fun prepare(target: android.view.View) = Unit
            override fun start() = Unit
            override fun pause() = Unit
            override fun resume() = Unit
            override fun cancel() = Unit
            override fun onAmplitude(level: Float) {
                lastAmplitude = level
            }
            override fun onTimerTick(timerText: String) {
                lastTimerText = timerText
            }
            override fun updateColor(color: Int) = Unit
        }
        val factory = RecordingAnimationControllerFactory { btn ->
            net.devemperor.dictate.state.render.RecordingAnimationController(
                fakeAnimation,
                btn,
                accentColorProvider = { 0 },
                animationsEnabled = { true },
            )
        }
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            recordingAnimationControllerFactory = factory,
        )
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        backend.onTimerTick(65_000L)  // 01:05
        backend.onAmplitude(0.7f)

        // Both side-channel ticks must reach the underlying animation.
        assertEquals("01:05", lastTimerText)
        assertEquals(0.7f, lastAmplitude)
    }

    @Test
    fun `teardown clears renderer bundle - subsequent forwarders are no-ops`() {
        // R-2 from the plan §10.1: pending animations must be detached
        // BEFORE the View refs become invalid. Once teardownOverlay
        // nulls the bundle, the forwarder methods must not touch any
        // (now-stale) renderer instances.
        var amplitudeAfterDetach: Float? = null
        val fakeAnimation = object : net.devemperor.dictate.widget.RecordingAnimation {
            override fun prepare(target: android.view.View) = Unit
            override fun start() = Unit
            override fun pause() = Unit
            override fun resume() = Unit
            override fun cancel() = Unit
            override fun onAmplitude(level: Float) {
                // If teardown didn't null the bundle, this would land
                // here after detach() → leak.
                amplitudeAfterDetach = level
            }
            override fun onTimerTick(timerText: String) = Unit
            override fun updateColor(color: Int) = Unit
        }
        val factory = RecordingAnimationControllerFactory { btn ->
            net.devemperor.dictate.state.render.RecordingAnimationController(
                fakeAnimation,
                btn,
                accentColorProvider = { 0 },
                animationsEnabled = { true },
            )
        }
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            recordingAnimationControllerFactory = factory,
        )
        backend.attach { captured += it }
        backend.render(stateWithPermission(viewMode = ViewMode.WIDGET), catalog.OVERLAY_5BUTTON)
        backend.detach()

        // Post-detach tick MUST be a no-op — the bundle is null.
        backend.onAmplitude(0.9f)

        assertEquals(
            "Forwarder must be a no-op after detach (bundle cleared).",
            null,
            amplitudeAfterDetach,
        )
        assertFalse("Window detached.", window.isAttached())
    }

    @Test
    fun `detach tears the window down after a position render`() {
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            positionMapper = FixedPositionMapper(px = 5, py = 6),
        )
        backend.attach { captured += it }
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        backend.detach()

        assertFalse("Window must be detached after backend.detach()", window.isAttached())
        assertTrue("detach call must be recorded", "detach" in window.events)
    }

    // ─── F-119 — Pref.Theme honoured by the overlay inflate ──────────

    @Test
    fun `theme=dark on a day system inflates against a night configuration`() {
        // Robolectric's default test configuration is notnight; with the
        // user pref forcing dark, the inflate context must carry the
        // night uiMode override so ?attr/colorSurface resolves from the
        // values-night Theme.Dictate variant.
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(
            stateWithPermission().copy(theming = net.devemperor.dictate.state.ThemingState(theme = "dark")),
            catalog.OVERLAY_5BUTTON,
        )

        assertEquals(
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
            attachedViewNightBits(),
        )
    }

    @Test
    fun `theme=system on a day system inflates against the day configuration`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)

        assertEquals(
            android.content.res.Configuration.UI_MODE_NIGHT_NO,
            attachedViewNightBits(),
        )
    }

    @Test
    fun `theme change while attached re-inflates the overlay with fresh colors`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        assertEquals(1, window.events.count { it == "attach" })
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO, attachedViewNightBits())

        backend.render(
            stateWithPermission().copy(theming = net.devemperor.dictate.state.ThemingState(theme = "dark")),
            catalog.OVERLAY_5BUTTON,
        )

        assertTrue("The stale day view must be torn down.", "detach" in window.events)
        assertEquals(
            "Theme flip while attached must re-inflate exactly once.",
            2, window.events.count { it == "attach" },
        )
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, attachedViewNightBits())
        assertTrue("Window must be attached after the re-inflate.", window.isAttached())
    }

    @Test
    fun `theme change that does not flip the effective night mode does NOT re-inflate`() {
        // system→light on a day system resolves to the same (day) mode —
        // tearing down would churn the window for nothing.
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        backend.render(
            stateWithPermission().copy(theming = net.devemperor.dictate.state.ThemingState(theme = "light")),
            catalog.OVERLAY_5BUTTON,
        )

        assertEquals(
            "Same effective night mode must not re-inflate.",
            1, window.events.count { it == "attach" },
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Night bits of the configuration the attached view was inflated with. */
    private fun attachedViewNightBits(): Int {
        val view = window.lastAttachedView
            ?: error("No View attached — render() must run with hasPermission=true first.")
        return view.context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
    }

    /**
     * [OverlayPositionMapper] that always maps to a fixed pixel pair
     * and round-trips the inverse — keeps `applyPosition` deterministic
     * without a measured view.
     */
    private class FixedPositionMapper(
        private val px: Int,
        private val py: Int,
    ) : OverlayPositionMapper {
        override fun normalizedToPixels(normX: Float, normY: Float, view: View): Pair<Int, Int> =
            px to py

        override fun pixelsToNormalized(px: Int, py: Int, view: View): Pair<Float, Float> =
            0f to 0f
    }

    private fun findOverlayButton(id: LogicalButtonId): View {
        // Walk the inflated tree to find the button by tag-id. We rely
        // on Robolectric's real inflate having run during the previous
        // render() call.
        // Variante 2a (dictate-widget-integration §6.5): OVERLAY_SEND was
        // merged into OVERLAY_RECORD; only four overlay views remain.
        val resId = when (id) {
            LogicalButtonId.OVERLAY_RECORD -> R.id.overlay_record_btn
            LogicalButtonId.OVERLAY_PAUSE -> R.id.overlay_pause_btn
            LogicalButtonId.OVERLAY_TRASH -> R.id.overlay_trash_btn
            LogicalButtonId.OVERLAY_CLOSE -> R.id.overlay_close_btn
            else -> error("Not an overlay-button id: $id")
        }
        val rootView = window.lastAttachedView
            ?: error("No View was attached — make sure render() ran with hasPermission=true.")
        return rootView.findViewById(resId)
            ?: error("View id $resId not found in attached overlay layout.")
    }
}
