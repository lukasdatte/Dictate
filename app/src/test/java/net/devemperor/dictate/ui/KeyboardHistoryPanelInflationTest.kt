package net.devemperor.dictate.ui

import android.content.Context
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.HistoryPanelState
import net.devemperor.dictate.state.render.HistoryPanelRenderer
import net.devemperor.dictate.state.render.HistoryPanelViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0014: proves the history-panel container inflates from the production
 * layout and that [HistoryPanelRenderer] drives its visibility off the
 * `historyPanel` axis, firing `onOpenChanged` only on a transition (so the IME
 * starts/stops the Paging collector once per open/close). Visual rendering
 * (height, light/dark, scroll) stays on the manual checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardHistoryPanelInflationTest {

    private lateinit var ctx: Context
    private lateinit var root: View
    private lateinit var container: View
    private lateinit var rv: View
    private lateinit var close: View
    private val openChanges = mutableListOf<Boolean>()
    private lateinit var renderer: HistoryPanelRenderer

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Dictate)
        ctx = ContextThemeWrapper(activity, R.style.Theme_Dictate)
        root = View.inflate(ctx, R.layout.activity_dictate_keyboard_view, null)
        activity.setContentView(root)

        container = root.findViewById(R.id.history_panel_cl)
        rv = root.findViewById(R.id.history_panel_rv)
        close = root.findViewById(R.id.history_panel_close_btn)
        renderer = HistoryPanelRenderer(HistoryPanelViews(container), onOpenChanged = { openChanges += it })
    }

    private fun mode() = net.devemperor.dictate.state.layout.LayoutCatalog(mirrorLayoutStrings())
        .KEYBOARD_HISTORY_PANEL

    private fun render(open: Boolean) =
        renderer.render(DictateUiState.initial().copy(historyPanel = HistoryPanelState(open = open)), mode())

    @Test
    fun `all history-panel views inflate`() {
        assertNotNull(container); assertNotNull(rv); assertNotNull(close)
    }

    @Test
    fun `closed panel is GONE, open panel is VISIBLE`() {
        render(false)
        assertEquals(View.GONE, container.visibility)
        render(true)
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test
    fun `onOpenChanged fires only on transitions`() {
        render(false)          // first render: null -> false, fires
        render(false)          // no change
        render(true)           // false -> true, fires
        render(true)           // no change
        render(false)          // true -> false, fires
        assertEquals(listOf(false, true, false), openChanges)
    }

    // ── Block B: item detail toggle ──

    @Test
    fun `detail session swaps the list surface for the detail group`() {
        val handle = root.findViewById<View>(R.id.history_panel_resize_handle)
        val detailGroup = root.findViewById<View>(R.id.history_panel_detail_group)
        val details = mutableListOf<String?>()
        val r = HistoryPanelRenderer(
            HistoryPanelViews(container, rv, handle, detailGroup),
            onOpenChanged = {},
            onDetailChanged = { details += it },
        )

        // Open, no detail → list (RV + handle) visible, detail GONE.
        r.render(state(open = true, detail = null), mode())
        assertEquals(View.VISIBLE, rv.visibility)
        assertEquals(View.VISIBLE, handle.visibility)
        assertEquals(View.GONE, detailGroup.visibility)

        // Open with a detail session → list GONE, detail VISIBLE.
        r.render(state(open = true, detail = "s1"), mode())
        assertEquals(View.GONE, rv.visibility)
        assertEquals(View.GONE, handle.visibility)
        assertEquals(View.VISIBLE, detailGroup.visibility)

        // onDetailChanged fires only on transitions (null then s1).
        assertEquals(listOf<String?>(null, "s1"), details)
    }

    private fun state(open: Boolean, detail: String?) =
        DictateUiState.initial().copy(historyPanel = HistoryPanelState(open = open, detailSessionId = detail))
}
