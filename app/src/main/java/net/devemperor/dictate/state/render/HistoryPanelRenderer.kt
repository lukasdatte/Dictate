package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * The history-panel views (ADR-0014 + Block B). All nullable — the IME can run in
 * configurations where the panel is absent (treated as "not present", skip).
 *
 * @property container the whole panel container (visibility from `open`).
 * @property listRv / [listHandle] the list surface (RecyclerView + resize handle),
 *   shown when no detail is open.
 * @property detailGroup the full-text detail surface, shown when
 *   `historyPanel.detailSessionId != null` (Block B).
 */
data class HistoryPanelViews(
    val container: View?,
    val listRv: View? = null,
    val listHandle: View? = null,
    val detailGroup: View? = null,
)

/**
 * `RenderBackend` (`backendType = null` — consumes every mode) that drives the
 * `history_panel_cl` container from the `historyPanel` axis (ADR-0014):
 *
 * - `open == false` → container GONE.
 * - `open == true` → container VISIBLE.
 *
 * On each *transition* of `open` it invokes [onOpenChanged] so the IME can start
 * the Paging collector when the panel opens and cancel it when it closes (the
 * paged list is IME-owned, not part of the state snapshot). Firing only on
 * change keeps `render` idempotent — it may be called on every state tick.
 *
 * The RecyclerView contents and per-row button clicks are wired in the IME (the
 * host commit for "insert" is a side-channel the reducer cannot reach); this
 * renderer owns only the container's visibility.
 *
 * @see net.devemperor.dictate.state.HistoryPanelModule
 */
class HistoryPanelRenderer(
    private val views: HistoryPanelViews,
    private val onOpenChanged: (Boolean) -> Unit,
    /**
     * Fired on each *transition* of `detailSessionId` (Block B) so the IME can load the
     * full text (non-null) or drop its reference (null). Firing only on change keeps
     * `render` idempotent. Defaults to a no-op so existing callers/tests are unaffected.
     */
    private val onDetailChanged: (String?) -> Unit = {},
) : RenderBackend {

    override val backendType: BackendType? = null

    override fun attach(onAction: (Action) -> Unit) = Unit
    override fun detach() = Unit

    /** Last rendered open flag; null until the first render (so the first tick fires). */
    private var lastOpen: Boolean? = null

    /** Last rendered detail session; a sentinel `false` until the first render. */
    private var lastDetail: Any? = false

    override fun render(state: DictateUiState, mode: LayoutMode) {
        val open = state.historyPanel.open
        val detail = state.historyPanel.detailSessionId
        val showDetail = open && detail != null

        views.container?.visibility = if (open) View.VISIBLE else View.GONE
        // List surface vs detail surface — swapped in place within the open panel.
        views.detailGroup?.visibility = if (showDetail) View.VISIBLE else View.GONE
        val listVisibility = if (open && detail == null) View.VISIBLE else View.GONE
        views.listRv?.visibility = listVisibility
        views.listHandle?.visibility = listVisibility

        if (lastOpen != open) {
            lastOpen = open
            onOpenChanged(open)
        }
        if (lastDetail != detail) {
            lastDetail = detail
            onDetailChanged(detail)
        }
    }
}
