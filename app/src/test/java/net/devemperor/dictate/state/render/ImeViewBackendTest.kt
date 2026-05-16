package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.keyboard.KeyPressAnimator
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.LayoutModeId
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.layout.testLayoutStrings
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [ImeViewBackend].
 *
 * # Why Robolectric
 *
 * The backend's hot path mutates real Android `View` properties
 * (`visibility`, `isEnabled`, `alpha`, `MaterialButton.text`,
 * `MaterialButton.icon`). Hand-rolled `View` fakes would need to
 * re-implement the entire Android view contract — Robolectric is the
 * narrower workaround (K-4 exception, same approach as
 * `KeyboardUiControllerTest`).
 *
 * # Coverage focus
 *
 * 1. **Click-listener single-wire (L8)** — only one click handler
 *    survives across many `render` calls; the lambda reads `stateRef`
 *    at click time.
 * 2. **MotionSurface contract** — `firstRender` triggers
 *    `jumpToState`, subsequent renders use `transitionToState` (unless
 *    `animationsEnabled = false`, R.14 backstop).
 * 3. **Resolver `null` is a silent no-op (R.3)** — clicks where the
 *    actionResolver returns `null` do NOT invoke `onAction`.
 * 4. **`detach` clears state** — clicks after detach short-circuit
 *    silently because `onAction` and `stateRef` are nulled.
 * 5. **Silent-skip-guard** — a slot referencing an absent
 *    `LogicalButtonId` raises `error(...)` instead of swallowing.
 * 6. **Animation forwarding** — render fans the recording-state into
 *    [RecordingAnimationController].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeViewBackendTest {

    private lateinit var ctx: Context
    private lateinit var motion: FakeMotionSurface
    private lateinit var buttons: MutableMap<LogicalButtonId, View>
    private lateinit var anim: FakeRecordingAnimation
    private lateinit var controller: RecordingAnimationController
    private val captured: MutableList<Action> = mutableListOf()
    private val catalog = LayoutCatalog(testLayoutStrings())

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        motion = FakeMotionSurface()
        buttons = LogicalButtonId.entries
            .filter { id ->
                id != LogicalButtonId.OVERLAY_RECORD &&
                    id != LogicalButtonId.OVERLAY_SEND &&
                    id != LogicalButtonId.OVERLAY_PAUSE &&
                    id != LogicalButtonId.OVERLAY_TRASH &&
                    id != LogicalButtonId.OVERLAY_CLOSE
            }
            .associateWith { MaterialButton(ctx) as View }
            .toMutableMap()
        anim = FakeRecordingAnimation()
        controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })
        captured.clear()
    }

    private fun newBackend(): ImeViewBackend = ImeViewBackend(
        motionSurface = motion,
        buttonViews = buttons.toMap(),
        ctx = ctx,
        services = fakeModuleServices(emitAction = {}),
        recordingAnimationController = controller,
    )

    @Test
    fun `backendType is IME_VIEW`() {
        assertEquals(BackendType.IME_VIEW, newBackend().backendType)
    }

    @Test
    fun `first render jumps to the scene state (R-14)`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        assertEquals(listOf("jump" to R.id.two_row_state), motion.events)
    }

    @Test
    fun `second render transitions instead of jumping`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_SINGLE_ROW)

        assertEquals(
            listOf(
                "jump" to R.id.two_row_state,
                "transition" to R.id.single_row_state,
            ),
            motion.events,
        )
    }

    @Test
    fun `animationsEnabled=false forces jumpToState every tick`() {
        val backend = newBackend()
        backend.attach { captured += it }

        val s = DictateUiState.initial().copy(
            layout = DictateUiState.initial().layout.copy(animationsEnabled = false),
        )
        backend.render(s, catalog.KEYBOARD_TWO_ROW)
        backend.render(s, catalog.KEYBOARD_SINGLE_ROW)

        assertEquals(
            listOf(
                "jump" to R.id.two_row_state,
                "jump" to R.id.single_row_state,
            ),
            motion.events,
        )
    }

    @Test
    fun `applies visibility from the slot's predicate`() {
        val backend = newBackend()
        backend.attach { captured += it }

        // KEYBOARD_TWO_ROW: AUDIO_FOCUS predicate is `{ false }` (gone
        // in two-row), RESEND requires lastAudioExists+resendEnabled
        // (false here) so it's GONE too. RECORD always visible.
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        assertEquals(View.VISIBLE, buttons[LogicalButtonId.RECORD]!!.visibility)
        assertEquals(View.GONE, buttons[LogicalButtonId.AUDIO_FOCUS]!!.visibility)
        assertEquals(View.GONE, buttons[LogicalButtonId.RESEND]!!.visibility)
        assertEquals(View.VISIBLE, buttons[LogicalButtonId.SPACE]!!.visibility)
    }

    @Test
    fun `click invokes onAction via the action resolver`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        buttons[LogicalButtonId.BACKSPACE]!!.performClick()

        assertEquals(listOf(Action.KeyboardInputAction.Backspace), captured)
    }

    @Test
    fun `click reads current state — multiple renders share one listener`() {
        val backend = newBackend()
        backend.attach { captured += it }

        // First render with Two-Row state, then switch to Single-Row.
        // The click listener wired during attach should pick up the
        // updated stateRef + modeRef (L8 — single-wire semantics).
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_SINGLE_ROW)

        buttons[LogicalButtonId.BACKSPACE]!!.performClick()

        // The single-row mode's backspace slot also emits
        // KeyboardInputAction.Backspace.
        assertEquals(listOf(Action.KeyboardInputAction.Backspace), captured)
    }

    @Test
    fun `click on null-resolver slot is a silent no-op (R-3)`() {
        val backend = newBackend()
        backend.attach { captured += it }

        // In SEND_MODE, several slots have `actionResolver = { _, _ -> null }`.
        val sendMode = DictateUiState.initial().copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sess",
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
            ),
        )
        backend.render(sendMode, catalog.KEYBOARD_TWO_ROW_SEND_MODE)

        // RESEND slot in SEND_MODE returns `null` from the resolver.
        buttons[LogicalButtonId.RESEND]!!.performClick()

        assertTrue(
            "Expected no actions emitted, got: $captured",
            captured.isEmpty(),
        )
    }

    @Test
    fun `detach clears onAction and stateRef — subsequent click is no-op`() {
        val backend = newBackend()
        backend.attach { captured += it }

        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        backend.detach()

        buttons[LogicalButtonId.BACKSPACE]!!.performClick()

        assertTrue(captured.isEmpty())
    }

    @Test
    fun `detach resets firstRender flag — re-attach jumps again`() {
        val backend = newBackend()
        backend.attach { captured += it }
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        motion.events.clear()

        backend.detach()
        backend.attach { captured += it }
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        assertEquals(listOf("jump" to R.id.two_row_state), motion.events)
    }

    @Test
    fun `render with mismatched backend throws`() {
        val backend = newBackend()
        backend.attach { captured += it }

        // Force a mismatched LayoutMode (OVERLAY_WINDOW backend).
        val overlayMode = LayoutMode(
            id = LayoutModeId.OVERLAY_5BUTTON,
            backend = BackendType.OVERLAY_WINDOW,
            rows = emptyList(),
        )

        try {
            backend.render(DictateUiState.initial(), overlayMode)
            fail("Expected require() to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ImeViewBackend received a non-IME_VIEW mode"))
        }
    }

    @Test
    fun `missing view mapping throws (silent-skip-guard)`() {
        // Drop the BACKSPACE entry to simulate a forgotten findViewById
        // wire-up. The render must error out instead of silently
        // skipping (Issue 3.0.12).
        buttons.remove(LogicalButtonId.BACKSPACE)
        val backend = newBackend()
        backend.attach { captured += it }

        try {
            backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
            fail("Expected error() to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("BACKSPACE"))
        }
    }

    @Test
    fun `render forwards into RecordingAnimationController`() {
        val backend = newBackend()
        backend.attach { captured += it }

        val active = DictateUiState.initial().copy(
            recording = RecordingState.Active(
                useBluetooth = false,
                audioFile = File("/tmp/x.m4a"), sessionId = "sid-test",
            ),
        )
        backend.render(active, catalog.KEYBOARD_TWO_ROW)

        assertEquals(listOf("start"), anim.events)
    }

    @Test
    fun `staticHandlerInstaller is invoked on attach with the buttonViews map`() {
        var calls = 0
        var seen: Map<LogicalButtonId, View>? = null
        val backend = ImeViewBackend(
            motionSurface = motion,
            buttonViews = buttons.toMap(),
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            recordingAnimationController = controller,
            staticHandlerInstaller = { views ->
                calls++
                seen = views
            },
        )

        backend.attach { /* */ }
        // Second attach is only legal after a detach (RenderBackend
        // KDoc) — the installer fires on every attach.
        backend.detach()
        backend.attach { /* */ }

        assertEquals(2, calls)
        assertNotNull(seen)
        assertSame(buttons[LogicalButtonId.RECORD], seen!![LogicalButtonId.RECORD])
    }

    @Test
    fun `staticHandlerInstaller is NOT invoked on render (single-wire L8)`() {
        // B4-VAL F-34e: render must not re-fire the installer. The L8
        // single-wire contract pins listener-attachment to attach() only;
        // a per-render installer call would re-register the special-touch
        // handlers every tick.
        var calls = 0
        val backend = ImeViewBackend(
            motionSurface = motion,
            buttonViews = buttons.toMap(),
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            recordingAnimationController = controller,
            staticHandlerInstaller = { _ -> calls++ },
        )

        backend.attach { /* */ }
        assertEquals("Installer must fire once on attach.", 1, calls)

        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_SINGLE_ROW)
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        assertEquals(
            "Installer must NOT fire on render (L8 single-wire contract).",
            1, calls,
        )
    }

    @Test
    fun `onVibrate fires on every click`() {
        var vibrations = 0
        val backend = ImeViewBackend(
            motionSurface = motion,
            buttonViews = buttons.toMap(),
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            recordingAnimationController = controller,
            onVibrate = { vibrations++ },
        )
        backend.attach { captured += it }

        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        buttons[LogicalButtonId.BACKSPACE]!!.performClick()
        buttons[LogicalButtonId.SPACE]!!.performClick()

        assertEquals(2, vibrations)
    }

    // ─── CR1 (Theme C-R) — long-press / key-press-anim / applyTheme ─────

    /** State where the RESEND button is visible (`isResendVisible`). */
    private fun resendVisibleState(): DictateUiState =
        DictateUiState.initial().copy(
            resend = net.devemperor.dictate.state.ResendState(
                lastAudioExists = true,
                resendEnabled = true,
            ),
        )

    /**
     * Build a backend whose `buttonViews` are [RecordingButton]s (a
     * handwritten K-1 fake that captures `setBackgroundColor` /
     * `setOnLongClickListener` / `setOnTouchListener` without a mocking
     * framework). Returns the backend + the id→RecordingButton map.
     */
    private fun recordingBackend(
        keyPressAnimator: KeyPressAnimator = KeyPressAnimator(),
    ): Pair<ImeViewBackend, Map<LogicalButtonId, RecordingButton>> {
        val recBtns = buttons.keys.associateWith { RecordingButton(ctx) }
        val backend = ImeViewBackend(
            motionSurface = motion,
            buttonViews = recBtns.mapValues { it.value as View },
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            recordingAnimationController = controller,
            keyPressAnimator = keyPressAnimator,
        )
        return backend to recBtns
    }

    @Test
    fun `RESEND long-press fires ResendLastAudioLong via the catalog longClickResolver`() {
        // CR1 moved RESEND long-press from a hardcoded backend wire to the
        // catalog `longClickResolver`. The backend's RESEND
        // OnLongClickListener must resolve it from the active slot.
        val (backend, recBtns) = recordingBackend()
        backend.attach { captured += it }
        backend.render(resendVisibleState(), catalog.KEYBOARD_TWO_ROW)

        val resend = recBtns[LogicalButtonId.RESEND]!!
        val consumed = resend.longClickListener!!.onLongClick(resend)

        assertEquals(listOf(Action.ResendAction.ResendLastAudioLong), captured)
        assertTrue("Long-press must be consumed (return true)", consumed)
    }

    @Test
    fun `RECORD long-press listener is NOT attached in CR1 (RR-1 no-double-wire)`() {
        // RR-1: attaching a RECORD long-press listener here would overwrite
        // the live legacy `onRecordLongClicked` and regress the keyboard.
        // CR1 attaches the long-press listener for RESEND only; RECORD's
        // listener is CR4's (in the same chunk it removes the legacy drive).
        val (backend, recBtns) = recordingBackend()
        backend.attach { captured += it }
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        assertNull(
            "RECORD must have NO backend long-press listener in CR1 (RR-1)",
            recBtns[LogicalButtonId.RECORD]!!.longClickListener,
        )
    }

    @Test
    fun `only RESEND gets a backend long-press listener in CR1 (RR-1)`() {
        // BACKSPACE/SPACE/ENTER/etc. have no catalog `longClickResolver`
        // and (per RR-1) no backend long-press listener in CR1 — their
        // legacy long-press handlers survive. Only RESEND is wired.
        val (backend, recBtns) = recordingBackend()
        backend.attach { captured += it }
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        recBtns.forEach { (id, btn) ->
            if (id == LogicalButtonId.RESEND) {
                assertNotNull(
                    "RESEND must have a long-press listener",
                    btn.longClickListener,
                )
            } else {
                assertNull(
                    "$id must NOT have a backend long-press listener in CR1",
                    btn.longClickListener,
                )
            }
        }
    }

    @Test
    fun `keyPressAnimator is applied to non-special buttons and skips SPACE BACKSPACE ENTER (RR-1)`() {
        // G7 + RR-1: press-animation is wired per owned button EXCEPT the
        // three special-touch buttons whose OnTouchListener is the
        // installer's (CR2). `applyPressAnimation` calls
        // `setOnTouchListener`; the RecordingButton fake captures whether
        // a touch listener was set.
        val (backend, recBtns) = recordingBackend()
        backend.attach { captured += it }
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        // Non-special owned buttons get the press-anim OnTouchListener.
        assertNotNull(recBtns[LogicalButtonId.RECORD]!!.touchListener)
        assertNotNull(recBtns[LogicalButtonId.RESEND]!!.touchListener)
        assertNotNull(recBtns[LogicalButtonId.PAUSE]!!.touchListener)
        assertNotNull(recBtns[LogicalButtonId.AUDIO_FOCUS]!!.touchListener)
        assertNotNull(recBtns[LogicalButtonId.TRASH]!!.touchListener)
        // Special-touch buttons skipped (installer owns their touch — CR2).
        assertNull(
            "SPACE press-anim must be skipped (RR-1)",
            recBtns[LogicalButtonId.SPACE]!!.touchListener,
        )
        assertNull(
            "BACKSPACE press-anim must be skipped (RR-1)",
            recBtns[LogicalButtonId.BACKSPACE]!!.touchListener,
        )
        assertNull(
            "ENTER press-anim must be skipped (RR-1)",
            recBtns[LogicalButtonId.ENTER]!!.touchListener,
        )
    }

    @Test
    fun `keyPressAnimator OnTouchListener returns false (does not consume - click_long-press unaffected)`() {
        val (backend, recBtns) = recordingBackend(KeyPressAnimator(animationsEnabled = true))
        backend.attach { captured += it }
        backend.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        val record = recBtns[LogicalButtonId.RECORD]!!
        val ev = android.view.MotionEvent.obtain(
            0L, 0L, android.view.MotionEvent.ACTION_DOWN, 0f, 0f, 0,
        )
        // R: applyPressAnimation's listener returns false so the click /
        // long-press path still works (KeyPressAnimator contract).
        assertEquals(false, record.touchListener!!.onTouch(record, ev))
        ev.recycle()
    }

    @Test
    fun `applyTheme colours the owned buttons in the legacy accent tiers`() {
        // G6: RECORD = accent; BACKSPACE / ENTER = accent darkened 0.35;
        // the remaining owned buttons = accent darkened 0.18; WIDGET_TOGGLE
        // untouched (legacy applyTheme never themed it).
        val (backend, recBtns) = recordingBackend()
        backend.attach { captured += it }

        val accent = android.graphics.Color.rgb(80, 160, 240)
        backend.applyTheme(accent)

        val medium = net.devemperor.dictate.DictateUtils.darkenColor(accent, 0.18f)
        val dark = net.devemperor.dictate.DictateUtils.darkenColor(accent, 0.35f)

        fun bg(id: LogicalButtonId): Int? = recBtns[id]!!.lastBackgroundColor

        assertEquals(accent, bg(LogicalButtonId.RECORD))
        assertEquals(dark, bg(LogicalButtonId.BACKSPACE))
        assertEquals(dark, bg(LogicalButtonId.ENTER))
        assertEquals(medium, bg(LogicalButtonId.RESEND))
        assertEquals(medium, bg(LogicalButtonId.PAUSE))
        assertEquals(medium, bg(LogicalButtonId.TRASH))
        assertEquals(medium, bg(LogicalButtonId.AUDIO_FOCUS))
        assertEquals(medium, bg(LogicalButtonId.SPACE))
    }

    @Test
    fun `applyTheme leaves WIDGET_TOGGLE untouched`() {
        val (backend, recBtns) = recordingBackend()
        backend.attach { captured += it }

        backend.applyTheme(android.graphics.Color.rgb(10, 20, 30))

        assertNull(
            "WIDGET_TOGGLE must not be re-coloured by applyTheme",
            recBtns[LogicalButtonId.WIDGET_TOGGLE]!!.lastBackgroundColor,
        )
    }
}

/**
 * Handwritten K-1 fake `MaterialButton` that records the listener +
 * background-colour mutations [ImeViewBackend] performs, without a
 * mocking framework. Robolectric's `MaterialButton` background handling
 * (tint vs ColorDrawable) makes a read-back assertion brittle; capturing
 * the call directly is the deterministic, intent-level assertion.
 */
internal class RecordingButton(ctx: Context) : MaterialButton(ctx) {
    var lastBackgroundColor: Int? = null
    var longClickListener: OnLongClickListener? = null
    var touchListener: OnTouchListener? = null

    override fun setBackgroundColor(color: Int) {
        lastBackgroundColor = color
        super.setBackgroundColor(color)
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longClickListener = l
        super.setOnLongClickListener(l)
    }

    @Suppress("ClickableViewAccessibility")
    override fun setOnTouchListener(l: OnTouchListener?) {
        touchListener = l
        super.setOnTouchListener(l)
    }
}

/**
 * Hand-rolled fake `MotionSurface` — records every state-transition
 * request so tests can assert on the jump-vs-transition selection.
 */
internal class FakeMotionSurface : MotionSurface {
    val events: MutableList<Pair<String, Int>> = mutableListOf()

    override fun jumpToState(stateId: Int) {
        events += "jump" to stateId
    }

    override fun transitionToState(stateId: Int) {
        events += "transition" to stateId
    }
}
