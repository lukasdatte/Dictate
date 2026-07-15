package net.devemperor.dictate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import net.devemperor.dictate.R
import net.devemperor.dictate.widget.PeekingButtonBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the edit-bar inflates from the **production** layout as a
 * [PeekingButtonBar] and that the peek arithmetic actually reaches the
 * buttons through a real measure pass.
 *
 * The arithmetic itself is covered exhaustively (and Robolectric-free) by
 * `EditBarWidthCalculatorTest`; this suite covers the wiring the calculator
 * cannot see — that the XML really is a bar, that every edit-bar button is
 * one of its row children, and that measuring narrow writes narrow slots.
 *
 * Pixel-level appearance (icon centring, light/dark, RTL) stays on the
 * manual device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditBarPeekInflationTest {

    private lateinit var ctx: Context
    private lateinit var root: View
    private lateinit var bar: PeekingButtonBar
    private lateinit var row: ViewGroup

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Dictate)
        ctx = ContextThemeWrapper(activity, R.style.Theme_Dictate)
        root = View.inflate(ctx, R.layout.activity_dictate_keyboard_view, null)
        bar = root.findViewById(R.id.edit_buttons_keyboard_ll)
        row = bar.getChildAt(0) as ViewGroup
    }

    /** Measure the bar at [widthPx] the way a real layout pass would. */
    private fun measureAt(widthPx: Int) {
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    private fun visibleSlots(): List<View> =
        (0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility != View.GONE }

    @Test
    fun `the edit-bar inflates as a PeekingButtonBar wrapping one row`() {
        assertNotNull(bar)
        assertEquals("PeekingButtonBar must wrap exactly one row", 1, bar.childCount)
    }

    @Test
    fun `every edit-bar button is a child of the row`() {
        // Guards the XML swap: a button left behind in the old chain would
        // silently vanish from the bar (and from the peek arithmetic).
        val ids = listOf(
            R.id.edit_undo_btn, R.id.edit_redo_btn, R.id.edit_cut_btn,
            R.id.edit_copy_btn, R.id.edit_paste_btn, R.id.edit_emoji_btn,
            R.id.edit_keyboard_btn, R.id.edit_history_btn, R.id.edit_audio_focus_btn,
            R.id.edit_settings_btn, R.id.edit_numbers_btn, R.id.edit_widget_toggle_btn,
            R.id.edit_a11y_btn, R.id.edit_pc_btn,
        )
        for (id in ids) {
            val button = root.findViewById<View>(id)
            assertNotNull("button missing from the layout", button)
            assertEquals(
                "button is not inside the edit-bar row — it would never be sized",
                row, button.parent,
            )
        }
        assertEquals("row child count drifted from the id list", ids.size, row.childCount)
    }

    @Test
    fun `a wide bar fits every button and shows no peek`() {
        measureAt(2000)
        val r = bar.lastResult!!
        assertTrue("2000px must fit the whole bar", !r.overflowing)
        assertEquals(0, r.peekPx)
        assertEquals(visibleSlots().size, r.fullyVisibleCount)
    }

    @Test
    fun `a narrow bar overflows and writes the computed slot width onto the buttons`() {
        // 480px ≈ a 320dp phone at 1.5x — too narrow for the whole bar.
        measureAt(480)
        val r = bar.lastResult!!
        assertTrue("480px cannot fit the whole bar", r.overflowing)
        assertTrue("the overflow must advertise itself", r.peekPx > 0)
        // The point of the measure pass: the arithmetic has to land on the
        // actual children, not just in `lastResult`.
        for (slot in visibleSlots()) {
            assertEquals(
                "slot width was not applied to ${slot.id}",
                r.slotWidthPx, slot.layoutParams.width,
            )
        }
    }

    @Test
    fun `the row is wider than the viewport when overflowing (so it can scroll)`() {
        measureAt(480)
        val r = bar.lastResult!!
        val rowWidth = visibleSlots().size * r.slotWidthPx
        val viewport = 480 - bar.paddingLeft - bar.paddingRight
        assertTrue(
            "row ($rowWidth) must exceed the viewport ($viewport) or nothing scrolls",
            rowWidth > viewport,
        )
    }
}
