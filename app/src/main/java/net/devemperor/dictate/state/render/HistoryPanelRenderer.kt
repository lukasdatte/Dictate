package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * The history-panel container view (ADR-0014). Nullable — the IME can run in
 * configurations where the panel is absent (treated as "not present", skip).
 */
data class HistoryPanelViews(
    val container: View?,
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
) : RenderBackend {

    override val backendType: BackendType? = null

    override fun attach(onAction: (Action) -> Unit) = Unit
    override fun detach() = Unit

    /** Last rendered open flag; null until the first render (so the first tick fires). */
    private var lastOpen: Boolean? = null

    override fun render(state: DictateUiState, mode: LayoutMode) {
        val open = state.historyPanel.open
        views.container?.visibility = if (open) View.VISIBLE else View.GONE
        if (lastOpen != open) {
            lastOpen = open
            onOpenChanged(open)
        }
    }
}
