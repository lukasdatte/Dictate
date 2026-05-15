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
    ): DictateUiState = DictateUiState.initial().copy(
        viewMode = viewMode,
        recording = recording,
        pipeline = pipeline,
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
    fun `render with suppress bit tears down (Issue 3 1 7)`() {
        val backend = newBackend()
        backend.attach { captured += it }

        // First attach the overlay normally.
        backend.render(stateWithPermission(), catalog.OVERLAY_5BUTTON)
        assertTrue(window.isAttached())

        // Now flip the suppress bit — should tear down.
        backend.render(
            stateWithPermission(suppress = true),
            catalog.OVERLAY_5BUTTON,
        )
        assertFalse("suppressAutoOverlay must tear down.", window.isAttached())
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
    fun `close click in WIDGET emits ToggleViewModeWidget`() {
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(stateWithPermission(viewMode = ViewMode.WIDGET), catalog.OVERLAY_5BUTTON)

        val closeBtn = findOverlayButton(LogicalButtonId.OVERLAY_CLOSE)
        closeBtn.performClick()

        assertEquals(
            listOf(Action.ViewModeAction.ToggleViewModeWidget),
            captured,
        )
    }

    @Test
    fun `close click in HOVER emits CloseOverlay`() {
        val backend = newBackend()
        backend.attach { captured += it }
        // HOVER needs an active recording for the state-axis to be
        // semantically valid (Spec 3 §7 — HOVER is auto-triggered while
        // pipeline / recording is live). The Close-resolver itself only
        // reads viewMode, but the visibility predicates would otherwise
        // hide all other buttons.
        backend.render(
            stateWithPermission(
                viewMode = ViewMode.HOVER,
                recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
            ),
            catalog.OVERLAY_5BUTTON,
        )

        val closeBtn = findOverlayButton(LogicalButtonId.OVERLAY_CLOSE)
        closeBtn.performClick()

        assertEquals(
            listOf(Action.ViewModeAction.CloseOverlay),
            captured,
        )
    }

    @Test
    fun `record click in HOVER is a silent no-op (R-3 null resolver)`() {
        val backend = newBackend()
        backend.attach { captured += it }
        // HOVER state with no active recording — visibility predicate
        // of OVERLAY_RECORD evaluates to true (Idle + Idle pipeline);
        // the action resolver returns null because viewMode != WIDGET.
        backend.render(
            stateWithPermission(viewMode = ViewMode.HOVER),
            catalog.OVERLAY_5BUTTON,
        )

        val recordBtn = findOverlayButton(LogicalButtonId.OVERLAY_RECORD)
        recordBtn.performClick()

        assertTrue(
            "HOVER record-click must be a silent no-op (R.3): $captured",
            captured.isEmpty(),
        )
    }

    @Test
    fun `record click in WIDGET emits StartRecording with allocated file`() {
        val tmpFile = File.createTempFile("overlay-test", ".m4a")
        tmpFile.deleteOnExit()
        val services = fakeModuleServices(
            emitAction = {},
            audioFileFactory = object : net.devemperor.dictate.state.AudioFileFactory {
                override fun allocate(): File = tmpFile
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

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun findOverlayButton(id: LogicalButtonId): View {
        // Walk the inflated tree to find the button by tag-id. We rely
        // on Robolectric's real inflate having run during the previous
        // render() call.
        // Use a workaround — search for known R.id.* per LogicalButtonId.
        val resId = when (id) {
            LogicalButtonId.OVERLAY_RECORD -> R.id.overlay_record_btn
            LogicalButtonId.OVERLAY_SEND -> R.id.overlay_send_btn
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
