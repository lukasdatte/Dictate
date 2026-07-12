package net.devemperor.dictate.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.ReviewPanelState
import net.devemperor.dictate.state.render.ReviewPanelRenderer
import net.devemperor.dictate.state.render.ReviewPanelViews
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
 * ADR-0013: proves the review-panel container inflates from the production
 * layout (all views present) and that [ReviewPanelRenderer] drives its
 * visibility / text / enable state off the `reviewPanel` axis. The device-only
 * concern (visual rendering, light/dark) stays on the manual checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewPanelInflationTest {

    private lateinit var ctx: Context
    private lateinit var root: View
    private lateinit var renderer: ReviewPanelRenderer
    private lateinit var container: View
    private lateinit var output: TextView
    private lateinit var message: TextView
    private lateinit var refining: View
    private lateinit var insert: View
    private lateinit var redictate: View
    private lateinit var discard: View

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Dictate)
        ctx = ContextThemeWrapper(activity, R.style.Theme_Dictate)
        root = View.inflate(ctx, R.layout.activity_dictate_keyboard_view, null)
        activity.setContentView(root)

        container = root.findViewById(R.id.review_panel_cl)
        output = root.findViewById(R.id.review_output_tv)
        message = root.findViewById(R.id.review_message_tv)
        refining = root.findViewById(R.id.review_refining_tv)
        insert = root.findViewById(R.id.review_insert_btn)
        redictate = root.findViewById(R.id.review_redictate_btn)
        discard = root.findViewById(R.id.review_discard_btn)

        renderer = ReviewPanelRenderer(
            ReviewPanelViews(container, message, output, refining, insert, redictate, discard),
        )
    }

    private fun render(panel: ReviewPanelState) =
        renderer.render(DictateUiState.initial().copy(reviewPanel = panel), catalogMode())

    // The mode value is unused by the renderer (backendType = null).
    private fun catalogMode() = net.devemperor.dictate.state.layout.LayoutCatalog(mirrorLayoutStrings())
        .KEYBOARD_REVIEW_PANEL

    @Test
    fun `all review views inflate`() {
        assertNotNull(container); assertNotNull(output); assertNotNull(message)
        assertNotNull(refining); assertNotNull(insert); assertNotNull(redictate); assertNotNull(discard)
    }

    @Test
    fun `closed panel is GONE`() {
        render(ReviewPanelState(open = false))
        assertEquals(View.GONE, container.visibility)
    }

    @Test
    fun `open panel shows output and message, buttons enabled`() {
        render(ReviewPanelState(open = true, sessionId = "s1", output = "the result", message = "why?"))
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals("the result", output.text.toString())
        assertEquals(View.VISIBLE, message.visibility)
        assertEquals("why?", message.text.toString())
        assertEquals(View.GONE, refining.visibility)
        assertTrue(insert.isEnabled && redictate.isEnabled && discard.isEnabled)
    }

    @Test
    fun `blank message hides the message row (output-only)`() {
        render(ReviewPanelState(open = true, sessionId = "s1", output = "x", message = null))
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(View.GONE, message.visibility)
    }

    @Test
    fun `refining shows the hint, disables insert-redictate, keeps discard as cancel`() {
        render(ReviewPanelState(open = true, sessionId = "s1", output = "x", message = "m", refining = true))
        assertEquals(View.VISIBLE, refining.visibility)
        assertTrue(!insert.isEnabled && !redictate.isEnabled)
        // Discard stays enabled — it is the cancel affordance during refining.
        assertTrue(discard.isEnabled)
    }

    @Test
    fun `refinement recording locks insert and discard, keeps re-dictate as the stop control (K1)`() {
        render(ReviewPanelState(open = true, sessionId = "s1", output = "x", message = "m", refinementRecording = true))
        // Insert + Discard must be disabled: a discard here would not be terminal
        // and an insert would double-commit the yet-to-be-refined output.
        assertTrue("insert must be locked while S2 records", !insert.isEnabled)
        assertTrue("discard must be locked while S2 records", !discard.isEnabled)
        // Re-dictate stays enabled — it is the stop control for the recording.
        assertTrue("re-dictate stays enabled as the stop control", redictate.isEnabled)
    }

    @Test
    fun `refinement recording shows a distinct recording hint (K12)`() {
        render(ReviewPanelState(open = true, sessionId = "s1", output = "x", message = "m", refinementRecording = true))
        assertEquals(View.VISIBLE, refining.visibility)
        assertEquals(
            ctx.getString(R.string.dictate_review_recording),
            (refining as TextView).text.toString(),
        )
    }
}
