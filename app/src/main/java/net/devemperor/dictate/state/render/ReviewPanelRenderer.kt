package net.devemperor.dictate.state.render

import android.view.View
import android.widget.TextView
import net.devemperor.dictate.R
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
        // The hint row is shown for both busy phases: the S2 re-dictate recording
        // (K12 — otherwise the panel gives no sign a recording is running) and the
        // follow-up turn. Its text distinguishes the two.
        val busyRecording = panel.refinementRecording
        val busyRefining = panel.refining
        views.refiningView?.let { hint ->
            if (busyRecording || busyRefining) {
                hint.visibility = View.VISIBLE
                (hint as? TextView)?.setText(
                    if (busyRecording) R.string.dictate_review_recording
                    else R.string.dictate_review_refining
                )
            } else {
                hint.visibility = View.GONE
            }
        }

        // Insert/Discard must be locked during BOTH busy phases (K1): during the
        // S2 recording a discard would not be terminal (the recording keeps
        // running and still inserts) and an insert would double-commit; during the
        // follow-up turn the same disable applies, except Discard doubles as the
        // cancel affordance (its handler branches on `refining`, ADR-0013 (d)) and
        // stays enabled. Re-dictate stays enabled while recording — it is the stop
        // control — but is disabled once the follow-up turn runs.
        views.insertButton?.isEnabled = !busyRefining && !busyRecording
        views.redictateButton?.isEnabled = !busyRefining
        views.discardButton?.isEnabled = !busyRecording
    }
}
