package net.devemperor.dictate.state.infobar

import android.content.res.Resources
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState

/**
 * Reactive renderer for the state-derived info-bar (ADR-0006). Since
 * the 2026-07-02 consolidation this is the **single** info-bar surface
 * — the legacy imperative info-bar controller and its second container
 * are deleted.
 *
 * # Wiring contract
 *
 * The IME service inflates the keyboard view in `onCreateInputView()`
 * and constructs a renderer with the `infobar_cl` container plus its
 * three child views (`infobar_message_tv` text label,
 * `infobar_confirm_btn` confirm, `infobar_dismiss_btn` dismiss) from
 * `activity_dictate_keyboard_view.xml`. The renderer's [start] launches
 * a `Dispatchers.Main` collector against the pipeline `StateFlow`;
 * [stop] cancels it (same lifecycle envelope as the legacy single-axis
 * onboarding observer this renderer replaced — ADR-0006 §"Big-Bang
 * migration").
 *
 * # Selection contract
 *
 * On every distinct state emit, [InfoBarSelector.select] returns the
 * sorted item list. The renderer:
 *
 *  - **Empty list** → hides [container]. Other surfaces (chips row,
 *    pipeline-step row) take over per the existing visibility
 *    machinery. The renderer does NOT touch them — it owns only the
 *    container's visibility; the mutex with `prompts_keyboard_cl` is
 *    owned by `PromptVisibilityController` (which derives the same
 *    `InfoBarSelector.select(state)` signal), and the single-row
 *    force-expand by `LayoutCatalog.forKeyboard`.
 *  - **Non-empty list** → renders the top item (smallest `createdAt`):
 *    text label gets the resolved string (with [InfoBarMessage.textArgs]
 *    spread into `getString(...)`), style sets the text color
 *    (INFO=blue / ERROR=red / ACTION=action-color), confirm button is
 *    `VISIBLE` iff [InfoBarItem.confirmAction] != null and dispatches
 *    that action on click, dismiss button is `VISIBLE` iff
 *    [InfoBarItem.dismissAction] != null and dispatches it on click.
 *    Pure-info items (both actions null) render text only — the
 *    item's lifecycle is then fully owned by the selector's source
 *    condition (see [InfoBarItem] KDoc).
 *
 * # Action-dispatch boundary
 *
 * Click handlers feed [onAction] — the same single sink the
 * `KeyboardLayoutManager` uses for backend events. The state mutation
 * triggered by the action removes the item's source (per ADR-0006
 * §"Dismiss = natural-source mutation"), the next state emit produces
 * the new selector output, and the renderer follows. No imperative
 * `dismiss()` call exists on this surface.
 *
 * # Thread safety
 *
 * All public methods MUST be called on the main thread. The collector
 * itself runs on `Dispatchers.Main`, so callback dispatch into
 * [onAction] is main-thread by construction.
 *
 * @param container the info-bar container view (`infobar_cl` from
 *   `activity_dictate_keyboard_view.xml`).
 * @param messageView text label inside [container] (`infobar_message_tv`).
 * @param confirmButton confirm button (`infobar_confirm_btn`).
 * @param dismissButton dismiss button (`infobar_dismiss_btn`).
 * @param state pipeline state-flow — the renderer's source of truth.
 * @param onAction action dispatcher, typically
 *   `pipelineBinder::dispatch` from the IME service.
 * @param resources used to resolve [InfoBarMessage.textResId] +
 *   [InfoBarStyle] colour mapping.
 * @param themeProvider supplies the current theme for colour resolution
 *   (`themeProvider()` is invoked at render time so theme switches at
 *   runtime carry through).
 *
 * @see InfoBarSelector
 * @see InfoBarItem
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
class InfoBarRenderer(
    private val container: ConstraintLayout,
    private val messageView: TextView,
    private val confirmButton: Button,
    private val dismissButton: Button,
    private val state: StateFlow<DictateUiState>,
    private val onAction: (Action) -> Unit,
    private val resources: Resources,
    private val themeProvider: () -> Resources.Theme,
) {
    private var scope: CoroutineScope? = null

    /**
     * Begin observing. Idempotent — a second [start] while already
     * running is a no-op (the existing collector keeps running).
     */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            state
                .map { InfoBarSelector.select(it) }
                .distinctUntilChanged()
                .collect { items -> render(items) }
        }
    }

    /**
     * Stop observing and release the collector scope. Idempotent.
     * Does NOT reset the container's visibility — the IME service's
     * `onDestroyInputView` handles view teardown.
     */
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Visible-for-testing render pass — exposed for InstrumentedTests. */
    internal fun render(items: List<InfoBarItem>) {
        try {
            if (items.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            val top = items.first()
            val msg = top.message
            val args = msg.textArgs.toTypedArray()
            val quantity = msg.quantity
            val text = when {
                // Plurals: textResId is a @PluralsRes id, pick the form for
                // `quantity` (the count is interpolated only if the string
                // references it — Android does not auto-substitute it).
                quantity != null -> resources.getQuantityString(msg.textResId, quantity, *args)
                args.isEmpty() -> resources.getString(msg.textResId)
                else -> resources.getString(msg.textResId, *args)
            }
            messageView.text = text
            messageView.setTextColor(resources.getColor(colorForStyle(top.message.style), themeProvider()))

            if (top.confirmAction != null) {
                confirmButton.visibility = View.VISIBLE
                confirmButton.setOnClickListener { onAction(top.confirmAction) }
            } else {
                confirmButton.visibility = View.GONE
                confirmButton.setOnClickListener(null)
            }
            if (top.dismissAction != null) {
                dismissButton.visibility = View.VISIBLE
                dismissButton.setOnClickListener { onAction(top.dismissAction) }
            } else {
                dismissButton.visibility = View.GONE
                dismissButton.setOnClickListener(null)
            }

            container.visibility = View.VISIBLE
        } catch (t: Throwable) {
            // Defensive: a malformed item (e.g. invalid string-resource
            // id) must NOT crash the IME process. Log + hide and let
            // the next state emit retry.
            Log.e(TAG, "render failed for ${items.firstOrNull()?.id}", t)
            container.visibility = View.GONE
        }
    }

    private fun colorForStyle(style: InfoBarStyle): Int = when (style) {
        InfoBarStyle.INFO -> R.color.dictate_blue
        InfoBarStyle.ERROR -> R.color.dictate_red
        // ACTION reuses the blue accent today; if the design system
        // adds a dedicated action-tone colour, switch here.
        InfoBarStyle.ACTION -> R.color.dictate_blue
    }

    private companion object {
        private const val TAG = "InfoBarRenderer"
    }
}
