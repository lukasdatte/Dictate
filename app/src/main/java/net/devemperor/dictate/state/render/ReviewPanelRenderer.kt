package net.devemperor.dictate.state.render

import android.view.View
import android.widget.TextView
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * The review-panel container views (ADR-0013). All nullable — the IME can run
 * in configurations where the panel is absent (treated as "not present", skip).
 */
data class ReviewPanelViews(
    val container: View?,
    val messageView: TextView?,
    val outputView: TextView?,
    val refiningView: View?,
    val insertButton: View?,
    val redictateButton: View?,
    val discardButton: View?,
)

/**
 * `RenderBackend` (`backendType = null` — consumes every mode) that drives the
 * `review_panel_cl` container from the `reviewPanel` axis (ADR-0013):
 *
 * - `open == false` → container GONE.
 * - `open == true` → container VISIBLE, `output`/`message` populated (the
 *   message row is GONE when blank so ALWAYS_REVIEW renders output-only), the
 *   Insert/Re-dictate/Discard buttons enabled only while NOT refining, and a
 *   "Refining…" hint shown during a dictated follow-up turn.
 *
 * Button click wiring lives in the IME service (imperative `InputConnection`
 * commit for Insert is a side-channel the reducer cannot reach); this renderer
 * owns only the container's visibility/text/enable state.
 *
 * @see net.devemperor.dictate.state.modules.ReviewPanelModule
 */
class ReviewPanelRenderer(
    private val views: ReviewPanelViews,
) : RenderBackend {

    override val backendType: BackendType? = null

    override fun attach(onAction: (Action) -> Unit) = Unit
    override fun detach() = Unit

    override fun render(state: DictateUiState, mode: LayoutMode) {
        val panel = state.reviewPanel
        val container = views.container ?: return
        if (!panel.open) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        views.outputView?.text = panel.output
        views.messageView?.let { mv ->
            if (panel.message.isNullOrBlank()) {
                mv.visibility = View.GONE
            } else {
                mv.visibility = View.VISIBLE
                mv.text = panel.message
            }
        }
        views.refiningView?.visibility = if (panel.refining) View.VISIBLE else View.GONE

        // Insert/Discard/Re-dictate are disabled while a follow-up turn runs;
        // a cancel affordance stays reachable via the trash button path.
        val actionable = !panel.refining
        views.insertButton?.isEnabled = actionable
        views.redictateButton?.isEnabled = actionable
        views.discardButton?.isEnabled = actionable
    }
}
