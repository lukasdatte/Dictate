package net.devemperor.dictate.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ResendState
import net.devemperor.dictate.state.layout.KeyboardLayoutManager
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.render.applySlotToView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Measured-geometry regression test for the **single-row (compact)
 * keyboard layout** — the combined row the user reaches via long-press
 * on the edit-bar "smaller" button (`edit_numbers_btn` →
 * `onSingleRowModeToggled` → `KEYBOARD_SINGLE_ROW` / `single_row_state`).
 *
 * # Bug this guards against (2026-07 user report)
 *
 * In `single_row_state` every button of the two former rows is packed
 * into ONE horizontal chain. All buttons except `space_btn` kept their
 * intrinsic `wrap_content` width, so on phone-width screens the chain's
 * total intrinsic width exceeded the available row width. An overfull
 * ConstraintLayout chain does not shrink `wrap_content` members — it
 * distributes negative leftover space, so neighbouring buttons visually
 * stacked on top of each other ("Buttons liegen übereinander").
 *
 * # Why Robolectric
 *
 * The overlap only exists in real measured geometry — LayoutCatalog
 * (visibility) and the MotionScene schema (constraint inventory) are
 * both individually consistent. Inflating the production layout XML,
 * jumping the production MotionScene to `single_row_state`, and
 * measuring at a realistic phone width is the only JVM-runnable way to
 * pin the fix.
 *
 * @see net.devemperor.dictate.ui.KeyboardLayoutRenderMirrorTest — the
 *   visibility-axis sibling (same render-path SSoT, no geometry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class SingleRowLayoutGeometryTest {

    private lateinit var ctx: Context
    private lateinit var root: View
    private lateinit var motionLayout: MotionLayout
    private lateinit var catalog: LayoutCatalog
    private lateinit var manager: KeyboardLayoutManager
    private lateinit var buttons: Map<LogicalButtonId, View>

    /** 360dp phone width; mdpi qualifiers → 1px == 1dp. */
    private val screenWidthPx = 360

    @Before
    fun setUp() {
        // A real (Robolectric) Activity window: MotionLayout only
        // populates its internal constraint model once attached to a
        // window — a free-floating inflate measures every child to 0.
        val activity = org.robolectric.Robolectric
            .buildActivity(android.app.Activity::class.java)
            .setup()
            .get()
        activity.setTheme(R.style.Theme_Dictate)
        ctx = ContextThemeWrapper(activity, R.style.Theme_Dictate)
        root = View.inflate(ctx, R.layout.activity_dictate_keyboard_view, null)
        activity.setContentView(root)
        motionLayout = root.findViewById(R.id.main_buttons_cl)
        catalog = LayoutCatalog(mirrorLayoutStrings())
        manager = KeyboardLayoutManager(catalog) { /* click-sink unused */ }
        // The production LogicalButtonId → view-id map
        // (DictateInputMethodService builds the same associations).
        buttons = mapOf(
            LogicalButtonId.RECORD to root.findViewById(R.id.record_btn),
            LogicalButtonId.RESEND to root.findViewById(R.id.resend_btn),
            LogicalButtonId.RECORD_SECONDARY to root.findViewById(R.id.secondary_record_btn),
            LogicalButtonId.BACKSPACE to root.findViewById(R.id.backspace_btn),
            LogicalButtonId.AUDIO_FOCUS to root.findViewById(R.id.audio_focus_btn),
            LogicalButtonId.TRASH to root.findViewById(R.id.trash_btn),
            LogicalButtonId.SPACE to root.findViewById(R.id.space_btn),
            LogicalButtonId.PAUSE to root.findViewById(R.id.pause_btn),
            LogicalButtonId.ENTER to root.findViewById(R.id.enter_btn),
            LogicalButtonId.WIDGET_TOGGLE to root.findViewById(R.id.widget_toggle_btn),
        )
    }

    // ─── States under test ────────────────────────────────────────────

    private fun idle(singleRow: Boolean) = DictateUiState.initial()
        .copy(layout = LayoutState(singleRowMode = singleRow))

    private fun idleWithLastAudio(singleRow: Boolean) = idle(singleRow)
        .copy(resend = ResendState(lastAudioExists = true, resendEnabled = true))

    private fun active(singleRow: Boolean) = idle(singleRow).copy(
        recording = RecordingState.Active(
            useBluetooth = false,
            audioFile = File("/tmp/geom-test.m4a"),
            sessionId = "geom-sess",
        ),
    )

    // ─── Harness ──────────────────────────────────────────────────────

    /**
     * Renders [state] the way `ImeViewBackend.render` does — jump the
     * MotionScene to the mode's `sceneStateId`, then apply every slot's
     * resolvers to the mapped view — and runs a full measure+layout pass
     * at phone width.
     */
    private fun renderAndLayout(state: DictateUiState) {
        val mode = manager.computeLayoutMode(state)
        motionLayout.jumpToState(requireNotNull(mode.sceneStateId))
        mode.slots.forEach { slot ->
            applySlotToView(slot, buttons.getValue(slot.logicalId), state, ctx)
        }
        root.measure(
            MeasureSpec.makeMeasureSpec(screenWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    /** Direct MotionLayout children that are VISIBLE with a non-empty frame. */
    private fun visibleChildren(): List<View> =
        (0 until motionLayout.childCount)
            .map { motionLayout.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

    private fun name(view: View): String =
        runCatching { ctx.resources.getResourceEntryName(view.id) }.getOrDefault("id=${view.id}")

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)

    private fun assertNoOverlapAndInsideParent(label: String) {
        val children = visibleChildren()
        val layoutDump = children.joinToString("\n") { "  ${name(it)}: ${bounds(it)}" }

        // 1 — pairwise non-overlap of the visible buttons.
        for (i in children.indices) {
            for (j in i + 1 until children.size) {
                val a = children[i]
                val b = children[j]
                val intersects = Rect.intersects(bounds(a), bounds(b))
                assertTrue(
                    "$label: ${name(a)} ${bounds(a)} overlaps ${name(b)} ${bounds(b)}.\n" +
                        "Visible children:\n$layoutDump",
                    !intersects,
                )
            }
        }

        // 2 — every visible button fits inside the padded parent bounds
        //     (an overfull chain pushes members outside the row).
        val minLeft = motionLayout.paddingLeft
        val maxRight = motionLayout.width - motionLayout.paddingRight
        children.forEach { child ->
            assertTrue(
                "$label: ${name(child)} ${bounds(child)} exceeds the padded parent " +
                    "row [$minLeft..$maxRight].\nVisible children:\n$layoutDump",
                child.left >= minLeft && child.right <= maxRight,
            )
        }
    }

    // ─── Tests ────────────────────────────────────────────────────────

    @Test
    fun `single row idle with resend - no button overlap at phone width`() {
        renderAndLayout(idleWithLastAudio(singleRow = true))
        assertNoOverlapAndInsideParent("single_row idle+resend @360dp")
    }

    @Test
    fun `single row active recording - no button overlap at phone width`() {
        renderAndLayout(active(singleRow = true))
        assertNoOverlapAndInsideParent("single_row recording @360dp")
    }

    @Test
    fun `two row idle - no button overlap at phone width (sanity baseline)`() {
        renderAndLayout(idleWithLastAudio(singleRow = false))
        assertNoOverlapAndInsideParent("two_row idle+resend @360dp")
    }

    @Test
    fun `two row keeps the historic 72dp block indent`() {
        // The compact-row fix moved the indent from MotionLayout
        // paddingStart(72dp) to paddingStart(16dp) + a 56dp marginStart in
        // two_row_state (single_row_state overrides it away). This pins the
        // two-row visual parity: Row 1's chain root still starts at x=72,
        // and Row 2's trash_btn start-aligns to it while visible.
        renderAndLayout(active(singleRow = false))
        val pulse = root.findViewById<View>(R.id.record_pulse_layout)
        assertEquals(
            "two_row: record_pulse_layout must keep the 72dp block indent " +
                "(16dp padding + 56dp two_row_state margin)",
            72,
            pulse.left,
        )
        val trash = buttons.getValue(LogicalButtonId.TRASH)
        assertEquals(
            "two_row: trash_btn (visible while recording) must start-align " +
                "with record_pulse_layout — the GONE-safe indent carrier",
            pulse.left,
            trash.left,
        )
    }

    @Test
    fun `single row record button keeps a readable minimum width`() {
        // Even in the fullest compact row (recording: trash + record +
        // space + pause + backspace + enter + audio_focus) the record
        // button must not collapse below its 48dp floor — the flexible
        // members (record/space) absorb the squeeze, not the icon buttons.
        renderAndLayout(active(singleRow = true))
        val pulse = root.findViewById<View>(R.id.record_pulse_layout)
        assertTrue(
            "single_row recording: record_pulse_layout width ${pulse.width} " +
                "must be >= 48 (layout_constraintWidth_min floor)",
            pulse.width >= 48,
        )
    }
}
